@file:OptIn(ExperimentalTime::class)

package jst.oktopoi

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.minutes

@Serializable
private data class PersistedValueWithSync<T>(
    val value: T?,
    val syncTimestamp: Long  // 0 = synced, >0 = unsynced timestamp, <0 = deleted timestamp
)

@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class Esps<KeyType : Any, ValueType : Any> : Eps<KeyType, ValueType> {

    private val unsyncedKeysMap: MutableMap<KeyType, Long> = mutableMapOf() // Keys that need syncing (timestamp)
    private val syncTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1) // Triggers sync flow
    private val lock = SynchronizedObject() // atomicfu for sync metadata map access
    
    // Sync parameters - will be initialized in constructor
    private val incomingSync: Flow<Triple<KeyType, ValueType?, Long>>
    private val outgoingSync: suspend (KeyType, ValueType?, Long) -> Unit
    private val syncActive: Flow<Boolean>
    private var syncScope: CoroutineScope
    
    // Track sync jobs for cancellation and restart
    private var inboundJob: Job? = null
    private var outboundJob: Job? = null

    constructor(
        persisted: PersistedEsInfo<KeyType, ValueType>,
        keySelector: ((ValueType) -> KeyType)?,
        sortingBy: Comparator<KeyType>,
        secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {},
        incomingSync: Flow<Triple<KeyType, ValueType?, Long>>,
        outgoingSync: suspend (KeyType, ValueType?, Long) -> Unit,
        syncActive: Flow<Boolean> = MutableStateFlow(true),
        syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ) : super(persisted, keySelector, sortingBy, secondaryKeys) {
        this.incomingSync = incomingSync
        this.outgoingSync = outgoingSync
        this.syncActive = syncActive
        this.syncScope = syncScope
    }

    override fun setup() {
        // Call parent setup for persistence
        super.setup()
        startSyncOperations()
    }
    
    private fun startSyncOperations() {
        // Set up inbound sync (only when active)
        inboundJob = syncScope.launch { 
            syncActive
                .flatMapLatest { active -> if (active) incomingSync else emptyFlow() }
                .collect { (key, value, timestamp) -> fromSync(key, value, timestamp) }
        }

        // Set up outbound sync: start with existing unsynced, then observe changes
        outboundJob = syncScope.launch {
            syncActive
                .flatMapLatest { active ->
                    if (active) {
                        kotlinx.coroutines.flow
                            .merge(syncTrigger, flow { while (true) { delay(OkTopoiConstants.DEFAULT_SYNC_INTERVAL); emit(Unit) } })
                            .mapLatest { entriesToSync() }
                            .onStart { emit(entriesToSync()) }
                    } else {
                        emptyFlow()
                    }
                }
                .collect { list ->
                    list.forEach { (key, value, timestamp) ->
                        try {
                            outgoingSync(key, value, timestamp)
                            synchronized(lock) {
                                unsyncedKeysMap.remove(key)
                            }
                        } catch (e: Exception) {
                            Logger.e("ESPS sync error for key $key: ${e.message}", e)
                            // Leave for retry
                        }
                    }
                }
        }
    }


    override fun fromPersistString(string: String, fileName: String) {
        val combined = Json.decodeFromString(PersistedValueWithSync.serializer(persisted.valueTypeSerializer), string)
        // Extract key from filename (always contains serialized key)
        val key = Json.decodeFromString(persisted.keyTypeSerializer, fileName)
        
        synchronized(lock) {
            // Add to unsynced map if needs syncing (non-zero timestamp)
            if (combined.syncTimestamp != 0L) {
                unsyncedKeysMap[key] = combined.syncTimestamp
            }
            
            // Only restore to TreeMap if not deleted (value exists and timestamp >= 0)
            if (combined.value != null && combined.syncTimestamp >= 0L) {
                put(key, combined.value)
            }
        }
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
    fun fromSync(key: KeyType, value: ValueType?, timestamp: Long, force: Boolean = false): Boolean {
        return synchronized(lock) {
            val currentTimestamp = unsyncedKeysMap[key] ?: 0L

            // Check if we should update based on timestamp
            if (!force && timestamp <= currentTimestamp) {
                return@synchronized false
            }

            // Update the value in TreeMap (null means remove)
            if (value != null) {
                put(key, value)
                // Mark as synced by removing from unsynced map
                unsyncedKeysMap.remove(key)
            } else {
                // Direct TreeMap removal - no sync tracking 
                remove(key)
                // Mark as synced by removing from unsynced map
                unsyncedKeysMap.remove(key)
            }

            return@synchronized true
        }
    }


    fun entriesToSync(): List<Triple<KeyType, ValueType?, Long>> {
        return synchronized(lock) {
            val keysToRemove = mutableListOf<KeyType>()
            val result = unsyncedKeysMap.mapNotNull { (key, timestamp) ->
                if (timestamp > 0) {
                    // Active entry - check if still exists in TreeMap
                    val value = this[key]
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

    // Primary sync methods
    fun putSync(key: KeyType, value: ValueType): ValueType? {
        synchronized(lock) {
            val result = put(key, value)
            unsyncedKeysMap[key] = Clock.System.now().toEpochMilliseconds()
            syncTrigger.tryEmit(Unit)
            return result
        }
    }

    fun putAllSync(from: Map<out KeyType, ValueType>) {
        synchronized(lock) {
            putAll(from)
            val timestamp = Clock.System.now().toEpochMilliseconds()
            from.keys.forEach { key ->
                unsyncedKeysMap[key] = timestamp
            }
            syncTrigger.tryEmit(Unit)
        }
    }

    fun removeSync(key: KeyType): ValueType? {
        synchronized(lock) {
            val result = remove(key)
            if (result != null) {
                unsyncedKeysMap[key] = -1L
                syncTrigger.tryEmit(Unit)
            }
            return result
        }
    }

    fun clearSync() {
        synchronized(lock) {
            // Mark all existing entries as deleted before clearing
            this.keys.forEach { key ->
                unsyncedKeysMap[key] = -1L
            }
            clear()
            syncTrigger.tryEmit(Unit)
        }
    }

    // Conditional sync methods
    fun putIfAbsentSync(key: KeyType, value: ValueType): ValueType? {
        synchronized(lock) {
            val result = putIfAbsent(key, value)
            if (result == null) {
                // Value was inserted
                unsyncedKeysMap[key] = Clock.System.now().toEpochMilliseconds()
                syncTrigger.tryEmit(Unit)
            }
            return result
        }
    }

    fun replaceSync(key: KeyType, value: ValueType): ValueType? {
        synchronized(lock) {
            val result = replace(key, value)
            if (result != null) {
                // Value was replaced
                unsyncedKeysMap[key] = Clock.System.now().toEpochMilliseconds()
                syncTrigger.tryEmit(Unit)
            }
            return result
        }
    }

    fun replaceSync(key: KeyType, oldValue: ValueType, newValue: ValueType): Boolean {
        synchronized(lock) {
            val result = replace(key, oldValue, newValue)
            if (result) {
                // Value was replaced
                unsyncedKeysMap[key] = Clock.System.now().toEpochMilliseconds()
                syncTrigger.tryEmit(Unit)
            }
            return result
        }
    }

    // Compute sync methods
    fun computeSync(key: KeyType, remappingFunction: (KeyType, ValueType?) -> ValueType?): ValueType? {
        synchronized(lock) {
            val hadKey = containsKey(key)
            val result = compute(key, remappingFunction)
            
            when {
                hadKey && result == null -> {
                    // Entry was deleted
                    unsyncedKeysMap[key] = -1L
                    syncTrigger.tryEmit(Unit)
                }
                result != null -> {
                    // Entry was added or updated
                    unsyncedKeysMap[key] = Clock.System.now().toEpochMilliseconds()
                    syncTrigger.tryEmit(Unit)
                }
                // !hadKey && result == null -> no change, no sync needed
            }
            return result
        }
    }

    fun computeIfAbsentSync(key: KeyType, mappingFunction: (KeyType) -> ValueType?): ValueType? {
        synchronized(lock) {
            val hadKey = containsKey(key)
            val result = computeIfAbsent(key, mappingFunction)
            if (result != null && !hadKey) {
                // Value was created
                unsyncedKeysMap[key] = Clock.System.now().toEpochMilliseconds()
                syncTrigger.tryEmit(Unit)
            }
            return result
        }
    }

    fun computeIfPresentSync(key: KeyType, remappingFunction: (KeyType, ValueType) -> ValueType?): ValueType? {
        synchronized(lock) {
            val hadKey = containsKey(key)
            val result = computeIfPresent(key, remappingFunction)
            if (result != null && hadKey) {
                // Value was modified
                unsyncedKeysMap[key] = Clock.System.now().toEpochMilliseconds()
                syncTrigger.tryEmit(Unit)
            } else if (result == null && hadKey) {
                // Entry was removed
                unsyncedKeysMap[key] = -1L
                syncTrigger.tryEmit(Unit)
            }
            return result
        }
    }

    fun mergeSync(key: KeyType, value: ValueType, remappingFunction: (ValueType, ValueType) -> ValueType?): ValueType? {
        synchronized(lock) {
            val hadKey = containsKey(key)
            val result = merge(key, value, remappingFunction)
            if (result != null) {
                // Value was merged/inserted
                unsyncedKeysMap[key] = Clock.System.now().toEpochMilliseconds()
                syncTrigger.tryEmit(Unit)
            } else if (hadKey) {
                // Entry was removed by merge function returning null
                unsyncedKeysMap[key] = -1L
                syncTrigger.tryEmit(Unit)
            }
            return result
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
                outgoingSync(key, value, timestamp)
                synchronized(lock) {
                    unsyncedKeysMap.remove(key)
                }
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
        val syncTimestamp = unsyncedKeysMap[key] ?: 0L
        
        if (value != null) {
            // Write with sync metadata
            val content = Json.encodeToString(
                PersistedValueWithSync.serializer(persisted.valueTypeSerializer),
                PersistedValueWithSync(value, syncTimestamp)
            )
            writeToFile(key, content)
        } else {
            // Delete case
            if (syncTimestamp < 0) {
                // Deletion tombstone - write null + negative timestamp instead of deleting
                val content = Json.encodeToString(
                    PersistedValueWithSync.serializer(persisted.valueTypeSerializer),
                    PersistedValueWithSync(null, syncTimestamp)
                )
                writeToFile(key, content)
            } else {
                // Regular delete
                deleteFromFile(key)
            }
        }
    }
}