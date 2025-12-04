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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Clock
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

    private var syncTimestamp: Long = 0L // Current sync timestamp (0 = synced, >0 = needs sync)
    private val syncTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1) // Triggers sync flow
    private val lock = SynchronizedObject() // For sync metadata access
    
    // Sync parameters - initialized in constructor
    private val incomingSync: Flow<Triple<String, ValueType?, Long>>
    private val outgoingSync: suspend (String, ValueType?, Long) -> Unit
    private val syncActive: Flow<Boolean>
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
        syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    ) : super(persisted, observing, defaultValue) {
        this.incomingSync = incomingSync
        this.outgoingSync = outgoingSync
        this.syncActive = syncActive
        this.syncScope = syncScope
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

        // Set up outbound sync: periodic sync of unsynced data
        outboundJob = syncScope.launch {
            syncActive
                .flatMapLatest { active ->
                    if (active) {
                        kotlinx.coroutines.flow
                            .merge(syncTrigger, flow { while (true) { delay(OkTopoiConstants.DEFAULT_SYNC_INTERVAL); emit(Unit) } })
                            .mapLatest { getEntryToSync() }
                            .onStart { emit(getEntryToSync()) }
                    } else {
                        emptyFlow()
                    }
                }
                .collect { entry ->
                    entry?.let { (propertyId, value, timestamp) ->
                        try {
                            outgoingSync(propertyId, value, timestamp)
                            synchronized(lock) {
                                syncTimestamp = 0L // Mark as synced
                            }
                            // Persist the updated sync state to avoid re-sync on app restart
                            persistValue(super.value)
                        } catch (e: Exception) {
                            Logger.e("OkTopoi-Esp", e) { "Error in outbound sync for $propertyId" }
                        }
                    }
                }
        }
    }

    /**
     * Handles incoming sync data, applying it if timestamp is newer.
     */
    @OptIn(ExperimentalTime::class)
    fun fromSync(value: ValueType?, timestamp: Long, force: Boolean = false): Boolean {
        return synchronized(lock) {
            val currentTimestamp = syncTimestamp
            
            // Check if we should update based on timestamp
            if (!force && timestamp <= currentTimestamp) {
                return@synchronized false
            }
            
            // Update the value without triggering additional sync
            if (value != null) {
                super.value = value
            } else {
                super.value = defaultValue?.invoke() ?: null as ValueType
            }
            
            // Update sync timestamp
            syncTimestamp = if (timestamp > 0) 0L else timestamp // Mark as synced if positive timestamp
            
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
     * Marks the current value for sync with current timestamp.
     */
    private fun markForSync() {
        synchronized(lock) {
            syncTimestamp = Clock.System.now().toEpochMilliseconds()
        }
        syncTrigger.tryEmit(Unit)
    }

    // Override all state-changing methods to add sync tracking
    
    override var value: ValueType
        get() = super.value
        set(value) {
            super.value = value
            markForSync()
        }

    override suspend fun emit(value: ValueType) {
        super.emit(value)
        markForSync()
    }

    override fun tryEmit(value: ValueType): Boolean {
        val result = super.tryEmit(value)
        if (result) {
            markForSync()
        }
        return result
    }

    override fun compareAndSet(expect: ValueType, update: ValueType): Boolean {
        val result = super.compareAndSet(expect, update)
        if (result) {
            markForSync()
        }
        return result
    }

    override fun set(newValue: ValueType) {
        super.set(newValue)
        markForSync()
    }

    override fun setIfDifferent(newValue: ValueType): Boolean {
        val result = super.setIfDifferent(newValue)
        if (result) {
            markForSync()
        }
        return result
    }

    override fun clear() {
        super.clear()
        markForSync()
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
    
    fun putSync(newValue: ValueType): ValueType? {
        synchronized(lock) {
            val oldValue = super.value
            super.value = newValue
            syncTimestamp = Clock.System.now().toEpochMilliseconds()
            syncTrigger.tryEmit(Unit)
            return oldValue
        }
    }

    fun clearSync(): ValueType? {
        synchronized(lock) {
            val oldValue = super.value
            value = defaultValue!!.invoke()!!
            syncTimestamp = -Clock.System.now().toEpochMilliseconds() // Negative for deletion
            syncTrigger.tryEmit(Unit)
            return oldValue
        }
    }

    /**
     * Check if sync operations are currently active.
     */
    val isSyncActive: Boolean
        get() = syncScope.isActive && (inboundJob?.isActive == true || outboundJob?.isActive == true)
}