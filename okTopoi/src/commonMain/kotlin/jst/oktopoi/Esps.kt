@file:OptIn(ExperimentalTime::class)

package jst.oktopoi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.writeString
import kotlin.time.Duration

/**
 * Internal wrapper for persisted values that includes synchronization timestamp.
 * Used by Esps to track sync state alongside the actual data.
 *
 * @param value The actual stored value (nullable)
 * @param syncTimestamp Sync state indicator: 0 = synced, >0 = unsynced timestamp, <0 = deleted timestamp
 * @param lastSyncedValue Snapshot of the last server-confirmed value, kept only while the entry is unsynced.
 *   Non-null means a local edit overwrote a previously-synced value; used to roll back on non-retryable
 *   sync failure. Null means either the entry is synced (no rollback needed) or the entry was locally
 *   created/re-created with no prior synced state (archive-and-remove on failure).
 */
@Serializable
private data class PersistedValueWithSync<T>(
    val value: T?,
    val syncTimestamp: Long,  // 0 = synced, >0 = unsynced timestamp, <0 = deleted timestamp
    val lastSyncedValue: T? = null  // Omitted from JSON when null (encodeDefaults=false)
)

/**
 * In-memory entry tracking a pending outbound sync.
 *
 * @param timestamp Sync timestamp: >0 = pending upsert, <0 = pending delete
 * @param lastSyncedValue Snapshot of the prior server-confirmed value (null if no prior state).
 *   Captured on the first local edit while synced; preserved across subsequent edits while pending
 *   (first-edit-wins). Used by archiveSyncError to roll back on non-retryable failure.
 */
private data class UnsyncedEntry<V>(val timestamp: Long, val lastSyncedValue: V?)

/**
 * Type-erased interface for sync dependency checking.
 * Allows Esps to check and sync dependencies before syncing its own entries.
 */
interface SyncDependency<ThisValueType> {
    /**
     * Check if the dependency for this value needs syncing, and sync it if needed.
     *
     * @param value The value that has a foreign key dependency
     * @param syncingStack Stack of (Esps, Key) pairs currently being synced (for circular dependency detection)
     */
    suspend fun syncDependencyIfNeeded(
        value: ThisValueType,
        syncingStack: Set<Pair<Any, Any>>
    )
}

/**
 * Type-safe implementation of SyncDependency.
 * Extracts a foreign key from a child value and ensures the parent is synced first.
 *
 * @param dependsOn The parent Esps instance that this entry depends on
 * @param keyExtractor Function to extract the foreign key from the child value (nullable for optional FKs)
 */
class TypedSyncDependency<ThisValueType, DepKeyType : Any, DepValueType : Any>(
    private val dependsOn: Esps<DepKeyType, DepValueType>,
    private val keyExtractor: (ThisValueType) -> DepKeyType?
) : SyncDependency<ThisValueType> {

    private val log = Logger.withTag("SyncDependency")

    override suspend fun syncDependencyIfNeeded(value: ThisValueType, syncingStack: Set<Pair<Any, Any>>) {
        val depKey = keyExtractor(value) ?: return  // No FK or null FK - no dependency

        // Check if dependency is already synced (not in unsyncedKeysMap or timestamp is 0)
        val depTimestamp = dependsOn.getUnsyncedTimestamp(depKey)
        if (depTimestamp == null || depTimestamp == 0L) {
            return  // Already synced, nothing to do
        }

        // Circular dependency detection
        if (syncingStack.contains(dependsOn to depKey)) {
            log.e { "Circular dependency detected: trying to sync $depKey in ${dependsOn.propertyName} again" }
            throw IllegalStateException("Circular dependency: $depKey already in sync stack")
        }

        // Get dependency value using suspend API (prevents runBlocking in suspend context)
        val depValue = dependsOn.get(depKey)
        if (depValue == null && depTimestamp > 0) {
            // Data integrity issue - FK points to non-existent entry
            log.w { "Dependency $depKey not found in ${dependsOn.propertyName} (referenced but missing)" }
            return
        }

        // Recursively sync dependency first
        // NOTE: Pass syncingStack as-is, let syncEntryWithDependencies add itself when syncing ITS dependencies
        log.d { "Syncing dependency: ${dependsOn.propertyName}[$depKey] before current entry" }
        dependsOn.syncEntryWithDependencies(
            depKey,
            depValue,
            depTimestamp,
            syncingStack  // Don't add (dependsOn to depKey) here!
        )
    }
}

/**
 * Builder for declaring foreign key dependencies.
 * Used in lambda-with-receiver style to declare which Esps instances this entity depends on.
 */
class ForeignKeyBuilder<KeyType, ValueType> {
    private val dependencies = mutableListOf<SyncDependency<ValueType>>()

    /**
     * Declare a dependency on another Esps instance.
     *
     * @param esps The parent Esps that this entity depends on
     * @param keyExtractor Function to extract the foreign key from this entity's value
     */
    fun <FK : Any> dependsOn(
        esps: Esps<FK, *>,
        keyExtractor: (ValueType) -> FK?
    ) {
        dependencies.add(TypedSyncDependency(esps, keyExtractor))
    }

    internal fun build(): List<SyncDependency<ValueType>> = dependencies.toList()
}

@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
open class Esps<KeyType : Any, ValueType : Any> : Eps<KeyType, ValueType> {

    private val log = Logger.withTag(this::class.simpleName.toString())

    private val unsyncedKeysMap: MutableMap<KeyType, UnsyncedEntry<ValueType>> = mutableMapOf() // Keys that need syncing (timestamp + last synced value for rollback)
    private val syncTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1) // Triggers sync flow

    // Emits the key whose unsynced state changed in a way Es.changes does not cover
    // (currently: outgoing-sync success removes the key from unsyncedKeysMap without
    // touching the underlying TreeMap). Drives the reactive sync-state flows below.
    private val unsyncedTick = MutableSharedFlow<KeyType>(extraBufferCapacity = 256)

    // Sync interval: ZERO = immediate, INFINITE = disabled, >0 = periodic fallback
    private var syncInterval: Duration = OkTopoiConstants.DEFAULT_SYNC_INTERVAL

    // Sync parameters - will be initialized in constructor
    private val incomingSync: Flow<Triple<KeyType, ValueType?, Long>>
    private val outgoingSync: suspend (KeyType, ValueType?, Long) -> Unit
    private val syncActive: Flow<Boolean>
    private var syncScope: CoroutineScope

    // Foreign key dependencies for sync ordering
    private val foreignKeyDependencies: List<SyncDependency<ValueType>>

    // Callback to classify sync errors as retryable or not
    private val isSyncErrorRetryable: (Exception) -> Boolean
    
    // Track sync jobs for cancellation and restart
    private var inboundJob: Job? = null
    private var outboundJob: Job? = null

    // Track entries currently being synced (prevents duplicate batch sends)
    private val syncingKeys = mutableSetOf<KeyType>()

    // Completion signals for entries being synced (allows waiting for ongoing syncs)
    private val syncCompletions = mutableMapOf<KeyType, CompletableDeferred<Unit>>()

    // Inbound suppression counter (only accessed under TreeMap write lock)
    private var inboundSuppressionDepth: Int = 0

    // Hooks for subclasses to observe persistence loading without extra I/O
    protected open fun onPersistedEntryLoaded(key: KeyType, value: ValueType?, syncTimestamp: Long) {}
    protected open fun onPersistenceLoadFinished() {}

    constructor(
        persisted: PersistedEsInfo<KeyType, ValueType>,
        sortingBy: Comparator<KeyType>,
        secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {},
        incomingSync: Flow<Triple<KeyType, ValueType?, Long>>,
        outgoingSync: suspend (KeyType, ValueType?, Long) -> Unit,
        syncInterval: Duration = OkTopoiConstants.DEFAULT_SYNC_INTERVAL,
        syncActive: Flow<Boolean> = MutableStateFlow(true),
        syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
        foreignKeys: ForeignKeyBuilder<KeyType, ValueType>.() -> Unit = {},
        isSyncErrorRetryable: (Exception) -> Boolean = { true }
    ) : super(persisted, sortingBy, secondaryKeys) {
        this.incomingSync = incomingSync
        this.outgoingSync = outgoingSync
        this.syncInterval = syncInterval
        this.syncActive = syncActive
        this.syncScope = syncScope
        this.foreignKeyDependencies = ForeignKeyBuilder<KeyType, ValueType>().apply(foreignKeys).build()
        this.isSyncErrorRetryable = isSyncErrorRetryable
    }

override fun setup() {
        // Call parent setup for persistence
        super.setup()

        // Allow subclasses to finalize any load-time computations before sync starts
        onPersistenceLoadFinished()

        startSyncOperations()

        log.d { "ESPS[${this@Esps.callingClassName}.${this@Esps.propertyName}] setup finished" }
    }
    
    private fun startSyncOperations() {

        // Set up inbound sync (only when active)
        inboundJob = syncScope.launch {
            syncActive
                .flatMapLatest { active -> if (active) incomingSync else emptyFlow() }
                .collect { (key, value, timestamp) -> fromSync(key, value, timestamp) }
        }

        // Set up outbound sync: start with existing unsynced, then observe changes
        // syncInterval: INFINITE = disabled, ZERO = immediate (trigger-only), >0 = trigger + periodic fallback
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
                    // Evaluate entriesToSync() here (not in mapLatest) so it runs AFTER
                    // the previous batch completes and its entries are removed from unsyncedKeysMap.
                    val list = entriesToSync()
                    if (list.isNotEmpty()) {
                        // Process items concurrently to enable batching
                        // Dependencies are still resolved correctly via syncEntryWithDependencies
                        coroutineScope {
                            list.map { (key, value, timestamp) ->
                                launch {
                                    try {
                                        syncEntryWithDependencies(key, value, timestamp)
                                    } catch (e: Exception) {
                                        if (isSyncErrorRetryable(e)) {
                                            Logger.e(e, tag = "OkTopoi-Esps") { "ESPS sync error for key $key: ${e.message}" }
                                            // Leave in unsyncedKeysMap for retry
                                        } else {
                                            Logger.w(tag = "ESPS") {
                                                "Non-retryable sync error for ${propertyName}[$key] - archiving entry: ${e.message}"
                                            }
                                            archiveSyncError(key, value, e)
                                        }
                                    }
                                }
                            }.joinAll()
                        }
                    }
                }
        }
    }


override fun fromPersistString(string: String, fileName: String) {
        val combined = oktopoiJson.decodeFromString(PersistedValueWithSync.serializer(persisted.valueTypeSerializer), string)
        // Extract key from filename (always contains serialized key)
        val key = fileNameToKey(fileName)

        // Notify subclasses about the loaded entry (no extra I/O)
        onPersistedEntryLoaded(key, combined.value, combined.syncTimestamp)
        
        // setup() runs this synchronously before sync starts, so no explicit lock is required here.
        // Persistence and sync marking are suppressed via persistenceLoadDepth.
//        log.v { "[fromPersistString]\n key: $key;\n value: ${combined.value};\n syncTime: ${combined.syncTimestamp}\n" }

        if (combined.syncTimestamp != 0L) {
            unsyncedKeysMap[key] = UnsyncedEntry(combined.syncTimestamp, combined.lastSyncedValue)
        }
        
        // Only restore to TreeMap if not deleted (value exists and timestamp >= 0)
        if (combined.value != null && combined.syncTimestamp >= 0L) {
            // Call unsafe; we are in single-threaded setup and persistence/marking is suppressed
            putUnsafe(key, combined.value)
        }
    }


    /**
     * Hook called before fromSync processes an incoming sync item.
     * Can be used to track sync progress or perform side effects.
     *
     * @param key The key being synced
     * @param value The value being synced (null for deletions)
     * @param timestamp The sync timestamp
     * @param force Whether this is a forced sync
     */
    protected open fun onBeforeFromSync(key: KeyType, value: ValueType?, timestamp: Long, force: Boolean) {}

    /**
     * Hook called after fromSync completes processing.
     * Can be used to track sync completion or perform side effects.
     *
     * @param key The key that was synced
     * @param value The value that was synced (null for deletions)
     * @param timestamp The sync timestamp
     * @param synced Whether the sync was actually performed (false if rejected due to timestamp)
     */
    protected open suspend fun onAfterFromSync(key: KeyType, value: ValueType?, timestamp: Long, synced: Boolean) {}

    /**
     * Extended variant of [onAfterFromSync] that also carries the value the store held
     * BEFORE this fromSync applied (captured under the same write lock; null if absent).
     * Default implementation delegates to the 4-arg hook for backward compatibility.
     *
     * @param key The key that was synced
     * @param oldValue The value held for [key] before this sync applied (null if absent)
     * @param value The value that was synced (null for deletions)
     * @param timestamp The sync timestamp
     * @param synced Whether the sync was actually performed (false if rejected due to timestamp)
     */
    protected open suspend fun onAfterFromSync(key: KeyType, oldValue: ValueType?, value: ValueType?, timestamp: Long, synced: Boolean) {
        onAfterFromSync(key, value, timestamp, synced)
    }

    /**
     * Inserts a value received from sync and marks it as synced.
     * Only updates if the provided timestamp is greater than current sync timestamp (unless force=true).
     *
     * @param key The key to insert/update
     * @param value The synced value to store
     * @param timestamp The sync timestamp of this value
     * @param force If true, ignores timestamp comparison and always updates
     * @return true if the sync was performed, false if rejected due to timestamp
     */
    @OptIn(ExperimentalTime::class)
    suspend fun fromSync(key: KeyType, value: ValueType?, timestamp: Long, force: Boolean = false): Boolean {
        onBeforeFromSync(key, value, timestamp, force)

        var oldValue: ValueType? = null
        val synced = withWriteLock {
            // Capture before any mutation (and before the timestamp-rejection early return,
            // where the current value IS the old value) — getUnsafe is only safe under the lock.
            oldValue = getUnsafe(key)

            val currentTimestamp = unsyncedKeysMap[key]?.timestamp ?: 0L

            log.d { "[${this@Esps.callingClassName}.${this@Esps.propertyName}] currentTimestamp: $currentTimestamp; incomingTimestamp: $timestamp" }

            if (!force && timestamp <= currentTimestamp) {
                return@withWriteLock false
            }

            // Ensure persistence sees synced state (0) for this key
            unsyncedKeysMap.remove(key)

            // Suppress outbound marking during inbound apply
            inboundSuppressionDepth++
            try {
                if (value != null) {
                    putUnsafe(key, value)
                } else {
                    removeUnsafe(key)
                }
            } finally {
                inboundSuppressionDepth--
            }
            true
        }

        onAfterFromSync(key, oldValue, value, timestamp, synced)
        return synced
    }

    /**
     * Inserts a batch of values received from sync, suppressing per-item change events.
     * A single Rebuild is emitted after all items are inserted so consumers re-read full state.
     * Use this for large initial fetches to avoid flooding the change flow buffer.
     */
    suspend fun fromSyncBatch(items: List<Triple<KeyType, ValueType?, Long>>) {
        beginBulkChanges()
        try {
            items.forEach { (key, value, timestamp) -> fromSync(key, value, timestamp) }
        } finally {
            endBulkChanges()
        }
    }


    suspend fun entriesToSync(): List<Triple<KeyType, ValueType?, Long>> {
        return withWriteLock {
            val keysToRemove = mutableListOf<KeyType>()
            val result = unsyncedKeysMap.mapNotNull { (key, entry) ->
                val timestamp = entry.timestamp
                if (timestamp > 0) {
                    // Active entry - check if still exists in TreeMap
                    val value = getUnsafe(key)
                    if (value != null) {
                        Triple(key, value, timestamp)
                    } else {
                        // TreeMap entry was removed externally, clean up unsyncedKeysMap
                        keysToRemove.add(key)
                        null
                    }
                } else {
                    // Deleted entry (negative timestamp) - always include
                    Triple(key, null, timestamp)
                }
            }
            // Clean up orphaned keys
            keysToRemove.forEach { unsyncedKeysMap.remove(it) }
            result
        }
    }

    /**
     * Get the unsynced timestamp for a key, or null if key is synced.
     * Used by TypedSyncDependency to check if a parent needs syncing.
     *
     * @return Timestamp if unsynced, null if synced or not present
     */
    internal suspend fun getUnsyncedTimestamp(key: KeyType): Long? {
        return withReadLock {
            unsyncedKeysMap[key]?.timestamp
        }
    }

    /**
     * Returns true if [key] has no pending outbound sync (local change confirmed by remote
     * or never modified locally).
     */
    suspend fun isSynced(key: KeyType): Boolean = withReadLock {
        !unsyncedKeysMap.containsKey(key)
    }

    /**
     * Snapshot of keys with a pending outbound sync (both upserts and deletes).
     * Built on demand; not maintained between calls.
     */
    suspend fun getUnsyncedKeys(): Set<KeyType> = withReadLock {
        unsyncedKeysMap.keys.toSet()
    }

    /**
     * Cold flow of pending-sync keys. Pulls a fresh snapshot via [getUnsyncedKeys] only when
     * subscribed and only when the underlying state may have changed. Does no work when no
     * collector is active.
     *
     * For a shared/replaying view, wrap with `.shareIn(scope, WhileSubscribed(), replay = 1)`.
     */
    val unsyncedKeys: Flow<Set<KeyType>> = merge(changes, unsyncedTick)
        .map { getUnsyncedKeys() }
        .onStart { emit(getUnsyncedKeys()) }
        .distinctUntilChanged()

    /**
     * Cold flow of [isSynced] for a single key. Re-evaluates only on changes that could
     * affect this key: its own Put/Removed, structural Cleared/Rebuild, or a sync-success
     * tick for this key.
     */
    fun isSyncedFlow(key: KeyType): Flow<Boolean> = merge(
        changes.filter { change ->
            when (change) {
                is TreeMap.MapChange.Put<*, *>     -> change.key == key
                is TreeMap.MapChange.Removed<*, *> -> change.key == key
                else                               -> true // Cleared / Rebuild
            }
        },
        unsyncedTick.filter { it == key }
    ).map { isSynced(key) }
     .onStart { emit(isSynced(key)) }
     .distinctUntilChanged()

    /**
     * Sync an entry with dependency resolution.
     * Recursively syncs all foreign key dependencies before syncing this entry.
     *
     * @param key The key to sync
     * @param value The value to sync (null for deletions)
     * @param timestamp The sync timestamp
     * @param syncingStack Stack of (Esps, Key) pairs currently being synced (for circular dependency detection)
     */
    internal suspend fun syncEntryWithDependencies(
        key: KeyType,
        value: ValueType?,
        timestamp: Long,
        syncingStack: Set<Pair<Any, Any>> = emptySet()
    ) {
        // Circular dependency guard
        if (syncingStack.contains(this to key)) {
            val stackStr = syncingStack.joinToString(" -> ") { (esps, k) ->
                val espsName = if (esps is Esps<*, *>) "${esps.propertyName}" else esps.toString()
                "$espsName[$k]"
            }
            log.e { "Circular dependency detected while syncing ${propertyName}[$key]! Chain: $stackStr -> ${propertyName}[$key]" }
            throw IllegalStateException("Circular dependency: $key already being synced")
        }

        // Check if already syncing (prevents duplicate batch sends)
        val completion = withWriteLock {
            if (syncingKeys.contains(key)) {
                // Already being synced - get the completion to wait for it
                syncCompletions[key]
            } else {
                // Start syncing - create completion and add to tracking
                val newCompletion = CompletableDeferred<Unit>()
                syncingKeys.add(key)
                syncCompletions[key] = newCompletion
                null  // Indicates we should sync
            }
        }

        // If another coroutine is syncing, wait for it to complete
        if (completion != null) {
            log.d { "Waiting for ${propertyName}[$key] being synced by another coroutine" }
            completion.await()
            return
        }

        try {
            // If upserting (not deleting), sync dependencies first
            if (value != null && foreignKeyDependencies.isNotEmpty()) {
                for (dependency in foreignKeyDependencies) {
                    dependency.syncDependencyIfNeeded(value, syncingStack + (this to key))
                }
            }

            // All dependencies synced, now sync this entry
            outgoingSync(key, value, timestamp)

            // Mark as synced on success and persist the updated sync state
            withWriteLock {
                unsyncedKeysMap.remove(key)
                // Persist the entry with syncTimestamp=0 to avoid re-sync on app restart
                // For deletions: this[key] is null, so persistEntry will delete the tombstone file
                // For upserts: this[key] still exists, so persistEntry will write syncTimestamp=0
                // Use getUnsafe to avoid deadlock (we're already holding write lock)
                persistEntry(key, value)
            }
            // Notify reactive sync-state flows: Es.changes does not fire here because the
            // underlying TreeMap value is unchanged — only unsyncedKeysMap shrank.
            if (!unsyncedTick.tryEmit(key)) {
                log.w { "unsyncedTick overflow for $key on ${propertyName} — slow or stuck subscriber" }
            }
        } finally {
            // Always remove from syncingKeys and complete deferred (even on failure, so it can be retried later)
            withWriteLock {
                syncingKeys.remove(key)
                syncCompletions.remove(key)?.complete(Unit)
            }
        }
    }

    /**
     * Hook called after a non-retryable sync error has been archived and the local entry
     * rolled back (or removed). Lets subclasses surface the failure to the user instead of
     * it living only in `.oktopoi-sync-errors/` and logs.
     *
     * Runs on the sync scope; exceptions thrown here are caught and logged so they cannot
     * poison the outbound sync loop.
     *
     * @param key The key whose write failed
     * @param value The value that failed to sync (null when the failed write was a deletion)
     * @param error The non-retryable exception the sync backend threw
     * @param rolledBack true if the entry was rolled back to its last synced value;
     *   false if it had no prior synced state and was removed entirely
     */
    protected open fun onSyncErrorArchived(key: KeyType, value: ValueType?, error: Exception, rolledBack: Boolean) {}

    /**
     * Archives a non-retryable sync error.
     *
     * If a `lastSyncedValue` is available, rolls the in-memory and persisted state back to it
     * (entry returns to synced state, matching the server's view). Otherwise removes the entry
     * entirely (the original behavior — used when there's no prior synced state to recover to).
     *
     * In both cases, the failed value and error details are archived under .oktopoi-sync-errors/
     * for inspection, and [onSyncErrorArchived] is invoked so subclasses can surface the failure.
     */
    private fun archiveSyncError(key: KeyType, value: ValueType?, error: Exception) {
        val (syncTimestamp, rolledBackTo) = withWriteLockBlocking {
            // 1. Capture sync state before mutation
            val entry = unsyncedKeysMap[key]
            val ts = entry?.timestamp ?: 0L
            val lastSyncedValue = entry?.lastSyncedValue
            // 2. Remove from retry queue
            unsyncedKeysMap.remove(key)
            // 3. Either roll back to last synced value or remove from in-memory TreeMap
            //    (suppressed so the put/remove doesn't re-mark as unsynced)
            inboundSuppressionDepth++
            try {
                if (lastSyncedValue != null) {
                    putUnsafe(key, lastSyncedValue)
                } else {
                    removeUnsafe(key)
                }
            } finally { inboundSuppressionDepth-- }
            ts to lastSyncedValue
        }

        if (rolledBackTo != null) {
            // Persist the rolled-back value as synced (syncTimestamp=0, no lastSyncedValue)
            try { persistEntry(key, rolledBackTo) } catch (_: Exception) {}
            Logger.w(tag = "ESPS") {
                "Non-retryable sync error for ${propertyName}[$key] - rolled back to last synced value: ${error.message}"
            }
        }

        // 4. Archive the persisted file + write error metadata
        try {
            val rootDir = dirPath.parent!!.parent!!  // dirPath = rootDir/className/propertyName
            val errorDir = Path(rootDir, ".oktopoi-sync-errors")
            fileSystem.createDirectories(errorDir)

            val timestamp = Clock.System.now().toEpochMilliseconds()
            val serializedKey = keyToFileName(key)
            val baseName = "${callingClassName}.${propertyName}_${timestamp}_${serializedKey}"

            // Archive the data (value + sync metadata)
            val dataContent = if (value != null) {
                oktopoiJson.encodeToString(
                    PersistedValueWithSync.serializer(persisted.valueTypeSerializer),
                    PersistedValueWithSync(value, syncTimestamp)
                )
            } else {
                "null (deletion tombstone)"
            }

            fileSystem.sink(Path(errorDir, baseName)).buffered().use {
                it.writeString(dataContent)
            }

            // Write error context
            val errorContent = buildString {
                appendLine("Error Class: ${error::class.simpleName}")
                appendLine("Message: ${error.message}")
                appendLine("---")
                appendLine("Stack Trace:")
                appendLine(error.stackTraceToString())
            }
            fileSystem.sink(Path(errorDir, "$baseName.error")).buffered().use {
                it.writeString(errorContent)
            }
        } catch (archiveError: Exception) {
            Logger.e(archiveError, tag = "OkTopoi-SyncErrorArchive") {
                "Failed to archive sync error for ${propertyName}[$key]"
            }
        }

        // 5. Delete the original persisted file (only if we didn't roll back —
        //    rollback already overwrote it with the synced value via persistEntry above)
        if (rolledBackTo == null) {
            try { deleteFromFile(key) } catch (_: Exception) {}
        }

        // 6. Notify subclasses so the failure can be surfaced to the user
        try {
            onSyncErrorArchived(key, value, error, rolledBackTo != null)
        } catch (hookError: Exception) {
            Logger.e(hookError, tag = "OkTopoi-Esps") {
                "onSyncErrorArchived hook failed for ${propertyName}[$key]"
            }
        }
    }

    // Primary sync methods removed - use regular operations which auto-mark as unsynced

    fun syncEntry(key: KeyType) {
        withWriteLockBlocking {
            val ts = Clock.System.now().toEpochMilliseconds()
            // Preserve any existing lastSyncedValue (manual re-queue isn't a value change)
            val existing = unsyncedKeysMap[key]
            unsyncedKeysMap[key] = UnsyncedEntry(ts, existing?.lastSyncedValue)
            syncTrigger.tryEmit(Unit)
        }
    }

    /**
     * Manually trigger sync for all pending entries.
     * Useful for button presses, periodic sync, or explicit sync requests.
     */
    fun sync() {
        syncTrigger.tryEmit(Unit)
    }

    /**
     * Immediately sync all pending entries synchronously.
     * @return number of entries synced
     */
    suspend fun syncNow(): Int {
        val entries = entriesToSync()
        var synced = 0
        entries.forEach { (key, value, timestamp) ->
            try {
                syncEntryWithDependencies(key, value, timestamp)
                synced++
            } catch (e: Exception) {
                Logger.e("ESPS immediate sync error for key $key: ${e.message}", e)
            }
        }
        return synced
    }
    
    /**
     * Restart sync operations with a new CoroutineScope.
     * This cancels existing sync operations and starts new ones with the provided scope.
     * Useful when the original scope gets cancelled but you want to continue syncing.
     * 
     * @param newScope The new CoroutineScope to use for sync operations
     */
    fun restartSync(newScope: CoroutineScope) {
        // Cancel existing sync jobs
        inboundJob?.cancel()
        outboundJob?.cancel()
        
        // Update to new scope
        syncScope = newScope
        
        // Restart sync operations
        startSyncOperations()
    }
    
    /**
     * Check if sync operations are currently active.
     * Returns true if the sync scope is active and at least one sync job is running.
     */
    val isSyncActive: Boolean
        get() = syncScope.isActive && (inboundJob?.isActive == true || outboundJob?.isActive == true)
    
    /**
     * Override persistence to handle sync-aware format and deletion tombstones.
     */
    override fun persistEntry(key: KeyType, value: ValueType?) {
        val unsynced = unsyncedKeysMap[key]
        val syncTimestamp = unsynced?.timestamp ?: 0L
        val lastSyncedValue = unsynced?.lastSyncedValue

        if (value != null) {
            // Write with sync metadata
            val content = oktopoiJson.encodeToString(
                PersistedValueWithSync.serializer(persisted.valueTypeSerializer),
                PersistedValueWithSync(value, syncTimestamp, lastSyncedValue)
            )
            writeToFile(key, content)
        } else {
            // Delete case
            if (syncTimestamp < 0) {
                // Deletion tombstone - write null + negative timestamp instead of deleting
                val content = oktopoiJson.encodeToString(
                    PersistedValueWithSync.serializer(persisted.valueTypeSerializer),
                    PersistedValueWithSync(null, syncTimestamp, lastSyncedValue)
                )
                writeToFile(key, content)
            } else {
                // Regular delete
                deleteFromFile(key)
            }
        }
    }
    
    // ========================================================================
    // Hook overrides for sync metadata handling
    // ========================================================================
    
    override fun onBeforePutUnsafe(key: KeyType, newValue: ValueType) {
        if (syncInterval != Duration.INFINITE && inboundSuppressionDepth == 0 && persistenceLoadDepth == 0) {
            val ts = Clock.System.now().toEpochMilliseconds()
            val existing = unsyncedKeysMap[key]
            unsyncedKeysMap[key] = if (existing == null) {
                // First edit while synced — snapshot current value as rollback target.
                // getUnsafe returns null when the key didn't exist before (locally created entry):
                // no prior synced state → null lastSyncedValue → archive-and-remove on failure.
                // Must run BEFORE super, which uses persistence-first and reads unsyncedKeysMap
                // inside Esps.persistEntry.
                UnsyncedEntry(ts, getUnsafe(key))
            } else {
                // Already pending — preserve the original lastSyncedValue (first-edit-wins),
                // just refresh the timestamp.
                existing.copy(timestamp = ts)
            }
            if (syncInterval == Duration.ZERO) syncTrigger.tryEmit(Unit)
        }
        super.onBeforePutUnsafe(key, newValue)
    }

    override fun onBeforeRemoveUnsafe(key: KeyType) {
        if (syncInterval != Duration.INFINITE && inboundSuppressionDepth == 0 && persistenceLoadDepth == 0) {
            val ts = -Clock.System.now().toEpochMilliseconds()
            val existing = unsyncedKeysMap[key]
            // First-edit-wins: if there's already a pending edit, keep its lastSyncedValue;
            // otherwise capture the current value (which is the last synced state).
            val lastSyncedValue = existing?.lastSyncedValue ?: getUnsafe(key)
            unsyncedKeysMap[key] = UnsyncedEntry(ts, lastSyncedValue)
            if (syncInterval == Duration.ZERO) syncTrigger.tryEmit(Unit)
        }
        super.onBeforeRemoveUnsafe(key)
    }

    // ========================================================================
    // Snapshot integration — preserve sync metadata + include pending-delete entries
    // ========================================================================

    override fun serializeEntryForSnapshot(key: KeyType, value: ValueType): String {
        val unsynced = unsyncedKeysMap[key]
        return oktopoiJson.encodeToString(
            PersistedValueWithSync.serializer(persisted.valueTypeSerializer),
            PersistedValueWithSync(value, unsynced?.timestamp ?: 0L, unsynced?.lastSyncedValue)
        )
    }

    override fun collectSnapshotEntriesUnsafe(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(sizeUnsafe + unsyncedKeysMap.size)
        val seen = HashSet<KeyType>(sizeUnsafe)
        // Live entries (may also be unsynced)
        keysUnsafe().forEach { key ->
            val value = getUnsafe(key) ?: return@forEach
            seen.add(key)
            out.add(
                keyToFileName(key) to serializeEntryForSnapshot(key, value)
            )
        }
        // Pending-delete entries live only in unsyncedKeysMap with timestamp < 0 — their persisted
        // file is a "deletion tombstone" (value=null, negative timestamp). Must be in the snapshot
        // so a remote-resync replay survives restart.
        unsyncedKeysMap.forEach { (key, entry) ->
            if (key in seen) return@forEach
            if (entry.timestamp >= 0) return@forEach  // upsert with no live value: skip
            val content = oktopoiJson.encodeToString(
                PersistedValueWithSync.serializer(persisted.valueTypeSerializer),
                PersistedValueWithSync(null, entry.timestamp, entry.lastSyncedValue)
            )
            out.add(
                keyToFileName(key) to content
            )
        }
        return out
    }

    override fun onBeforeClearUnsafe() {
        if (syncInterval != Duration.INFINITE) {
            // Mark all existing entries as deleted before clearing.
            // Capture each value as lastSyncedValue so a non-retryable delete sync can roll back
            // to the value the server still has.
            val ts = -Clock.System.now().toEpochMilliseconds()
            keysUnsafe().forEach { key ->
                val oldValue = getUnsafe(key)
                val existing = unsyncedKeysMap[key]
                val lastSyncedValue = existing?.lastSyncedValue ?: oldValue
                unsyncedKeysMap[key] = UnsyncedEntry(ts, lastSyncedValue)
            }
            if (syncInterval == Duration.ZERO) syncTrigger.tryEmit(Unit)
        }
        super.onBeforeClearUnsafe()
    }
}