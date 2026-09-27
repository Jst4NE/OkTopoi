@file:OptIn(ExperimentalTime::class)
@file:Suppress("UNCHECKED_CAST")

package jst.oktopoi

import co.touchlab.kermit.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@Serializable
private data class PersistedValueWithSyncEsp<T>(
    val value: T?, // Nullable to support deletions/null values
    val syncTimestamp: Long  // 0 = synced, >0 = unsynced timestamp, <0 = deleted timestamp
)

/**
 * E-Sync-Persistent implementation that extends Ep with synchronization capabilities.
 * 
 * This class follows the same architecture pattern as Esps extending Eps, providing
 * bi-directional synchronization on top of file-based persistence.
 * 
 * Architecture: E -> Ep -> Esp (state -> persist -> sync)
 */
@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
open class Esp<ValueType : Any?> : Ep<ValueType> {

    private val log = Logger.withTag(this::class.simpleName.toString())

    // 0 = synced, >0 = unsynced local set (device time), <0 = unsynced local clear (negated device time).
    // Guarded by [lock], which also covers every local change and its persistence, so a change and
    // the outbound check-and-settle can never interleave.
    private var syncTimestamp: Long = 0L
    private val syncTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1) // Triggers sync flow
    private val lock = SynchronizedObject() // Reentrant: setters nest through Ep/E into encodeValue

    // Sync parameters - initialized in constructor
    private val incomingSync: Flow<Triple<String, ValueType?, Long>>
    private val outgoingSync: suspend (String, ValueType?, Long) -> Unit
    private val syncActive: Flow<Boolean>
    private val syncInterval: Duration
    private var syncScope: CoroutineScope

    // Track sync jobs for cancellation and restart
    private var inboundJob: Job? = null
    private var outboundJob: Job? = null

    constructor(
        persisted: PersistedEInfo<ValueType>,
        observing: StateFlow<ValueType>? = null,
        defaultValue: (() -> ValueType?)? = null,
        incomingSync: Flow<Triple<String, ValueType?, Long>>,
        outgoingSync: suspend (String, ValueType?, Long) -> Unit,
        syncActive: Flow<Boolean> = MutableStateFlow(true),
        syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
        syncInterval: Duration = OkTopoiConstants.DEFAULT_SYNC_INTERVAL
    ) : super(persisted, observing, defaultValue) {
        this.incomingSync = incomingSync
        this.outgoingSync = outgoingSync
        this.syncActive = syncActive
        this.syncScope = syncScope
        this.syncInterval = syncInterval
    }

    override fun setup() {
        // Initialize sync-aware persistence and start sync operations
        // Call parent setup which handles persistence and file I/O
        // The overridden decodeValue method will extract sync metadata automatically
        super.setup()
        // Start sync operations after persistence is initialized
        startSyncOperations()
    }

    private fun startSyncOperations() {
        // Set up inbound sync (only when active)
        inboundJob = syncScope.launch {
            syncActive
                .flatMapLatest { active -> if (active) incomingSync else emptyFlow() }
                .collect { (propertyId, value, timestamp) -> fromSync(value, timestamp) }
        }

        // Outbound: same cadence semantics as Esps — INFINITE = disabled, ZERO = on change only,
        // >0 = on change plus a periodic retry.
        if (syncInterval == Duration.INFINITE) return
        outboundJob = syncScope.launch {
            syncActive
                .flatMapLatest { active ->
                    if (active) {
                        val triggerFlow = if (syncInterval > Duration.ZERO) {
                            kotlinx.coroutines.flow
                                .merge(syncTrigger, flow { while (true) { delay(syncInterval); emit(Unit) } })
                        } else {
                            syncTrigger
                        }
                        triggerFlow.onStart { emit(Unit) }
                    } else {
                        emptyFlow()
                    }
                }
                .collect {
                    getEntryToSync()?.let { (propertyId, sent, timestamp) ->
                        try {
                            outgoingSync(propertyId, sent, timestamp)
                            // Settle only if nothing changed while the write was in flight: same value
                            // and same kind of change (set vs clear). Otherwise the newer change stays
                            // pending and goes out next.
                            val stillPending = synchronized(lock) {
                                val unchanged = syncTimestamp != 0L &&
                                    (syncTimestamp > 0) == (timestamp > 0) &&
                                    super.value == sent
                                if (unchanged) {
                                    syncTimestamp = 0L
                                    // Persist the synced state to avoid re-sync on app restart
                                    persistValue(super.value)
                                }
                                syncTimestamp != 0L
                            }
                            if (stillPending) syncTrigger.tryEmit(Unit)
                        } catch (e: Exception) {
                            Logger.e(e, tag = "OkTopoi-Esp") { "Error in outbound sync for $propertyId" }
                        }
                    }
                }
        }
    }

    /**
     * Handles incoming sync data, applying it if timestamp is newer than a pending local change.
     */
    @OptIn(ExperimentalTime::class)
    fun fromSync(value: ValueType?, timestamp: Long, force: Boolean = false): Boolean {
        return synchronized(lock) {
            // Compare against when the pending change happened; a pending clear is stored negated.
            val currentTimestamp = syncTimestamp.absoluteValue

            // Check if we should update based on timestamp
            if (!force && timestamp <= currentTimestamp) {
                return@synchronized false
            }

            // Sync state first, so the value's single persistence write already records it.
            val previous = syncTimestamp
            syncTimestamp = if (timestamp > 0) 0L else timestamp // Mark as synced if positive timestamp
            try {
                // Update the value without triggering additional sync
                if (value != null) {
                    super.value = value
                } else {
                    super.value = defaultValue?.invoke() ?: null as ValueType
                }
            } catch (e: Throwable) {
                syncTimestamp = previous
                throw e
            }

            return@synchronized true
        }
    }

    /**
     * Gets the current entry that needs to be synced, if any.
     */
    private fun getEntryToSync(): Triple<String, ValueType?, Long>? {
        return synchronized(lock) {
            if (syncTimestamp != 0L) {
                val propertyId = "${callingClassName}:${propertyName}"
                Triple(propertyId, super.value, syncTimestamp)
            } else {
                null
            }
        }
    }

    /**
     * Applies a local change as pending sync. The timestamp is stamped BEFORE [change] runs, so the
     * persistence write the change performs (Ep writes on every set) already records the pending
     * state — stamping after it would leave a file that reloads as synced if the process dies before
     * the outbound write. Restored if the change fails or reports no change ([applied] false).
     */
    private fun <R> changeLocally(
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        applied: (R) -> Boolean = { true },
        change: () -> R
    ): R {
        val result = synchronized(lock) {
            val previous = syncTimestamp
            syncTimestamp = timestamp
            val r = try {
                change()
            } catch (e: Throwable) {
                syncTimestamp = previous
                throw e
            }
            if (!applied(r)) syncTimestamp = previous
            r
        }
        if (applied(result)) syncTrigger.tryEmit(Unit)
        return result
    }

    // Override all state-changing methods to add sync tracking. The convenience variants route
    // through the value setter (E's set/clear already do, virtually), so each change is stamped and
    // persisted exactly once.

    override var value: ValueType
        get() = super.value
        set(value) {
            changeLocally { super.value = value }
        }

    override suspend fun emit(value: ValueType) {
        this.value = value
    }

    override fun tryEmit(value: ValueType): Boolean {
        this.value = value
        return true
    }

    override fun compareAndSet(expect: ValueType, update: ValueType): Boolean =
        changeLocally(applied = { it }) { super.compareAndSet(expect, update) }

    override fun set(newValue: ValueType) {
        value = newValue
    }

    // setIfDifferent is inherited: E implements it via compareAndSet, which is overridden above.

    override fun clear() {
        value = defaultValue?.invoke() ?: null as ValueType
    }

    // Override persistence methods to include sync metadata

    override fun encodeValue(value: ValueType?): String {
        val wrappedValue = PersistedValueWithSyncEsp<ValueType?>(
            value = value,
            syncTimestamp = synchronized(lock) { syncTimestamp }
        )
        return oktopoiJson.encodeToString(
            PersistedValueWithSyncEsp.serializer(valueSerializer),
            wrappedValue
        )
    }

    override fun decodeValue(content: String): ValueType? {
        val wrappedValue = oktopoiJson.decodeFromString(
            PersistedValueWithSyncEsp.serializer(valueSerializer),
            content
        )
        synchronized(lock) {
            syncTimestamp = wrappedValue.syncTimestamp
        }
        return wrappedValue.value
    }

    /**
     * Sync-enabled methods for manual sync operations
     */

    fun putSync(newValue: ValueType): ValueType? =
        changeLocally {
            val oldValue = super.value
            super.value = newValue
            oldValue
        }

    fun clearSync(): ValueType? =
        changeLocally(timestamp = -Clock.System.now().toEpochMilliseconds()) { // Negative for deletion
            val oldValue = super.value
            super.value = defaultValue!!.invoke()!!
            oldValue
        }

    /**
     * Check if sync operations are currently active.
     */
    val isSyncActive: Boolean
        get() = syncScope.isActive && (inboundJob?.isActive == true || outboundJob?.isActive == true)
}
