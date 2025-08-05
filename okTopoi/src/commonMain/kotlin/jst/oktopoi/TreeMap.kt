package jst.oktopoi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.BufferOverflow

/**
 * A Red-Black tree based implementation of MutableMap with NavigableMap operations.
 * 
 * This implementation provides:
 * - O(log n) performance for all basic operations (get, put, remove)
 * - O(1) secondary index lookups
 * - Thread-safe access through ReadWriteLock
 * - Transparent suspend context optimization (with compiler plugin)
 * - Complete NavigableMap API (firstKey, lastKey, lower/floor/ceiling/higher)
 * - Collection views with efficient iterators
 * 
 * The architecture uses a view-based pattern:
 * - UnsafeTreeMapCore: Thread-unsafe core with all algorithms
 * - SuspendTreeMapView: Suspend-optimized access wrapper
 * - BlockingTreeMapView: Blocking access wrapper for non-suspend contexts
 * - Public API: Delegates to blocking view by default
 * 
 * @param K the type of keys maintained by this map
 * @param V the type of mapped values
 * @param keyComparator the comparator used to order the keys, or null for natural ordering
 */
open class TreeMap<K, V>(
    protected val keyComparator: Comparator<K> = Comparator { k1, k2 ->
        (k1 as Comparable<K>).compareTo(k2)
    }
) : MutableMap<K, V> {
    
    /**
     * Sealed class representing different types of changes to the TreeMap.
     * Used for reactive programming and persistence systems.
     */
    sealed class MapChange<K, V> {
        /**
         * Represents a put operation (insert or update).
         */
        data class Put<K, V>(
            val key: K,
            val value: V,
            val isUpdate: Boolean,
            val oldValue: V?
        ) : MapChange<K, V>()

        /**
         * Represents a remove operation.
         */
        data class Removed<K, V>(
            val key: K,
            val oldValue: V
        ) : MapChange<K, V>()

        /**
         * Represents a clear operation.
         */
        class Cleared<K, V> : MapChange<K, V>()

        /**
         * Represents a rebuild operation (used for bulk updates).
         */
        class Rebuild<K, V> : MapChange<K, V>()
    }
    
    // Core components
    private val unsafeCore = UnsafeTreeMapCore<K, V>(keyComparator)
    private val rwLock = ReadWriteLock()
    
    // Secondary index management (moved from UnsafeTreeMapCore)
    private val secondaryKeyExtractors = mutableMapOf<String, (V) -> Any?>()
    private val secondaryIndexes = mutableMapOf<String, MutableMap<Any?, MutableSet<K>>>()
    
    /**
     * Extracts the secondary key from a value using the registered extractor.
     * @param indexName the name of the secondary index
     * @param value the value to extract the secondary key from
     * @return the extracted secondary key, or null if index not found
     */
    fun getSecondaryKey(indexName: String, value: V): Any? {
        val extractor = secondaryKeyExtractors[indexName] ?: return null
        return extractor(value)
    }
    
    
    
    // Flow-based change notifications for reactive programming
    private val _changeFlow = MutableSharedFlow<MapChange<K, V>>(replay = 0, extraBufferCapacity = 512)
    
    /**
     * Flow of changes made to this TreeMap.
     * Useful for reactive programming and implementing persistence.
     */
    val changes: SharedFlow<MapChange<K, V>> = _changeFlow.asSharedFlow()
    
    // View instances (internal for compiler plugin access)
    internal val blocking: BlockingTreeMapView = BlockingTreeMapView()
    internal val suspend: SuspendTreeMapView = SuspendTreeMapView()
    /**
     * Builder for configuring secondary indexes.
     */
    class SecondaryIndexBuilder<K, V> internal constructor(
        private val treeMap: TreeMap<K, V>
    ) {
        /**
         * Adds a secondary index for efficient lookups by a derived key.
         * 
         * @param indexName unique name for this index
         * @param keyExtractor function to extract secondary key from values
         * @return this builder for chaining
         */
        fun index(indexName: String, keyExtractor: (V) -> Any?): SecondaryIndexBuilder<K, V> {
            treeMap.addSecondaryIndex(indexName, keyExtractor)
            return this
        }
        
        /**
         * Adds a secondary index using a property name.
         * 
         * @param indexName unique name for this index
         * @param keyExtractor function to extract secondary key from values
         * @return this builder for chaining
         */
        fun key(indexName: String, keyExtractor: (V) -> Any?): SecondaryIndexBuilder<K, V> {
            return index(indexName, keyExtractor)
        }
    }
    
    /**
     * Configures secondary indexes using a builder pattern.
     * 
     * Example:
     * ```kotlin
     * val treeMap = TreeMap<String, Person> {
     *     key("email") { person -> person.email }
     *     key("id") { person -> person.id.toString() }
     * }
     * ```
     */
    constructor(
        keyComparator: Comparator<K> = Comparator { k1, k2 ->
            (k1 as Comparable<K>).compareTo(k2)
        },
        configure: SecondaryIndexBuilder<K, V>.() -> Unit
    ) : this(keyComparator) {
        SecondaryIndexBuilder(this).configure()
    }
    
    /**
     * Adds a secondary index for efficient lookups by a derived key.
     * 
     * @param indexName unique name for this index
     * @param keyExtractor function to extract secondary key from values
     */
    fun addSecondaryIndex(indexName: String, keyExtractor: (V) -> Any?) {
        require(indexName !in secondaryKeyExtractors) { 
            "Secondary index '$indexName' already exists" 
        }
        
        secondaryKeyExtractors[indexName] = keyExtractor
        val index = mutableMapOf<Any?, MutableSet<K>>()
        secondaryIndexes[indexName] = index
        
        // Populate index with existing entries
        populateSecondaryIndex(keyExtractor, index)
    }
    
    /**
     * Populates a secondary index with all existing tree entries.
     */
    private fun populateSecondaryIndex(
        keyExtractor: (V) -> Any?,
        index: MutableMap<Any?, MutableSet<K>>
    ) {
        unsafeCore.entriesUnsafe().forEach { entry ->
            val secondaryKey = keyExtractor(entry.value)
            index.getOrPut(secondaryKey) { mutableSetOf() }.add(entry.key)  // Allow null as valid secondary key
        }
    }
    
    /**
     * Updates secondary indexes when a key-value pair is added, updated, or removed.
     */
    private fun updateSecondaryIndexes(
        key: K,
        oldValue: V?,
        newValue: V?
    ) {
        secondaryKeyExtractors.forEach { (indexName, extractor) ->
            val index = secondaryIndexes[indexName] ?: return@forEach
            
            // Remove old secondary key mapping
            oldValue?.let { old ->
                val oldSecondaryKey = extractor(old)
                index[oldSecondaryKey]?.remove(key)
                // Clean up empty sets
                if (index[oldSecondaryKey]?.isEmpty() == true) {
                    index.remove(oldSecondaryKey)
                }
            }
            
            // Add new secondary key mapping
            newValue?.let { new ->
                val newSecondaryKey = extractor(new)
                index.getOrPut(newSecondaryKey) { mutableSetOf() }.add(key)  // Allow null as valid secondary key
            }
        }
    }
    
    
    // ========================================================================
    // Public API - Delegates to BlockingTreeMapView
    // ========================================================================
    
    override val size: Int
        get() = blocking.size
    
    override fun isEmpty(): Boolean = blocking.isEmpty()
    
    override fun containsKey(key: K): Boolean = blocking.containsKey(key)
    
    override fun containsValue(value: V): Boolean = blocking.containsValue(value)
    
    override fun get(key: K): V? = blocking.get(key)
    
    override fun put(key: K, value: V): V? {
        val oldValue = blocking.put(key, value)
        updateSecondaryIndexes(key, oldValue, value)
        emitChange(MapChange.Put(key, value, oldValue != null, oldValue))
        return oldValue
    }
    
    override fun remove(key: K): V? {
        val oldValue = blocking.remove(key)
        if (oldValue != null) {
            updateSecondaryIndexes(key, oldValue, null)
            emitChange(MapChange.Removed(key, oldValue))
        }
        return oldValue
    }
    
    override fun putAll(from: Map<out K, V>) {
        // Process each entry individually to maintain secondary indexes properly
        from.forEach { (key, value) ->
            put(key, value)  // This will handle secondary index updates and change emission
        }
    }
    
    override fun clear() {
        blocking.clear()
        // Clear all secondary indexes
        secondaryIndexes.values.forEach { it.clear() }
        emitChange(MapChange.Cleared())
    }
    
    override val keys: MutableSet<K>
        get() = blocking.keys
    
    override val values: MutableCollection<V>
        get() = blocking.values
    
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = blocking.entries
    
    // NavigableMap operations
    fun firstKey(): K = blocking.firstKey()
    fun lastKey(): K = blocking.lastKey()
    fun firstEntry(): MapEntry<K, V>? = blocking.firstEntry()
    fun lastEntry(): MapEntry<K, V>? = blocking.lastEntry()
    
    fun lowerKey(key: K): K? = blocking.lowerKey(key)
    fun floorKey(key: K): K? = blocking.floorKey(key)
    fun ceilingKey(key: K): K? = blocking.ceilingKey(key)
    fun higherKey(key: K): K? = blocking.higherKey(key)
    
    fun lowerEntry(key: K): MapEntry<K, V>? = blocking.lowerEntry(key)
    fun floorEntry(key: K): MapEntry<K, V>? = blocking.floorEntry(key)
    fun ceilingEntry(key: K): MapEntry<K, V>? = blocking.ceilingEntry(key)
    fun higherEntry(key: K): MapEntry<K, V>? = blocking.higherEntry(key)
    
    fun pollFirstEntry(): MapEntry<K, V>? = blocking.pollFirstEntry()
    fun pollLastEntry(): MapEntry<K, V>? = blocking.pollLastEntry()

    fun getBy(vararg criteria: Pair<String, Any?>): Collection<V> {
        return getPrimaryKeysBySecondaryKey(*criteria).map { this[it]!! }
    }

    // Secondary index operations
    fun containsSecondaryKey(indexName: String, secondaryKey: Any?): Boolean {
        val index = secondaryIndexes[indexName] ?: return false
        return index.containsKey(secondaryKey) && index[secondaryKey]?.isNotEmpty() == true
    }
    
    fun getSecondaryKeys(indexName: String): Set<Any?> {
        val index = secondaryIndexes[indexName] ?: return emptySet()
        return index.keys
    }
    
    fun getSecondaryIndexNames(): Set<String> {
        return secondaryKeyExtractors.keys
    }

    fun getPrimaryKeysBySecondaryKey(vararg criteria: Pair<String, Any?>): Set<K> {
        if (criteria.isEmpty()) {
            return keys
        } else if (criteria.size == 1) {
            val (indexName, secondaryKey) = criteria[0]
            return secondaryIndexes[indexName]?.get(secondaryKey) ?: emptySet()
        } else {
            return criteria
                .map { (indexName, secondaryKey) -> secondaryIndexes[indexName]?.get(secondaryKey) ?: emptySet() }
                .reduce { acc, keySet -> acc.intersect(keySet) }
        }
    }
    
    // Modern Map API
    fun putIfAbsent(key: K, value: V): V? = blocking.putIfAbsent(key, value)
    fun replace(key: K, value: V): V? = blocking.replace(key, value)
    fun replace(key: K, oldValue: V, newValue: V): Boolean = blocking.replace(key, oldValue, newValue)
    
    fun compute(key: K, remappingFunction: (K, V?) -> V?): V? = blocking.compute(key, remappingFunction)
    fun computeIfAbsent(key: K, mappingFunction: (K) -> V?): V? = blocking.computeIfAbsent(key, mappingFunction)
    fun computeIfPresent(key: K, remappingFunction: (K, V) -> V?): V? = blocking.computeIfPresent(key, remappingFunction)
    fun merge(key: K, value: V, remappingFunction: (V, V) -> V?): V? = blocking.merge(key, value, remappingFunction)
    
    // Range operations
    fun subMapEntries(fromKey: K?, fromInclusive: Boolean, toKey: K?, toInclusive: Boolean): List<MapEntry<K, V>> =
        blocking.subMapEntries(fromKey, fromInclusive, toKey, toInclusive)
    
    fun headMapEntries(toKey: K): List<MapEntry<K, V>> = blocking.headMapEntries(toKey)
    fun tailMapEntries(fromKey: K): List<MapEntry<K, V>> = blocking.tailMapEntries(fromKey)
    
//    // Debug utilities
//    fun validateRedBlackProperties(): Boolean =
//        runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.validateRedBlackPropertiesUnsafe() } }
//
//    fun debugString(): String =
//        runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.debugStringUnsafe() } }
    
    /**
     * Emit a change notification to the flow.
     */
    private fun emitChange(change: MapChange<K, V>) {

        val success = _changeFlow.tryEmit(change)
        if (!success) {
            Logger.w("OkTopoi-TreeMap") { "Failed to emit change: $change" }
        }
    }
    
    // ========================================================================
    // SuspendTreeMapView - Suspend-optimized access wrapper
    // ========================================================================
    
    /**
     * Suspend-optimized view for coroutine contexts.
     * Provides async access to the unsafe core with ReadWriteLock protection.
     */
    internal inner class SuspendTreeMapView {
        
        // Basic Map operations
        suspend fun get(key: K): V? = 
            rwLock.withReadLock { unsafeCore.getUnsafe(key) }
        
        suspend fun put(key: K, value: V): V? = 
            rwLock.withWriteLock { 
                unsafeCore.putUnsafe(key, value) 
            }
        
        suspend fun remove(key: K): V? = 
            rwLock.withWriteLock { 
                unsafeCore.removeUnsafe(key) 
            }
        
        suspend fun clear() = 
            rwLock.withWriteLock { 
                unsafeCore.clearUnsafe() 
            }
        
        suspend fun containsKey(key: K): Boolean = 
            rwLock.withReadLock { unsafeCore.containsKeyUnsafe(key) }
        
        suspend fun containsValue(value: V): Boolean = 
            rwLock.withReadLock { unsafeCore.containsValueUnsafe(value) }
        
        suspend fun putAll(from: Map<out K, V>) = 
            rwLock.withWriteLock { 
                unsafeCore.putAllUnsafe(from) 
            }
        
        val size: Int get() = unsafeCore.size
        val isEmpty: Boolean get() = unsafeCore.isEmpty
        
        // NavigableMap operations
        suspend fun firstKey(): K = 
            rwLock.withReadLock { unsafeCore.firstKeyUnsafe() }
        
        suspend fun lastKey(): K = 
            rwLock.withReadLock { unsafeCore.lastKeyUnsafe() }
        
        suspend fun firstEntry(): MapEntry<K, V>? = 
            rwLock.withReadLock { unsafeCore.firstEntryUnsafe() }
        
        suspend fun lastEntry(): MapEntry<K, V>? = 
            rwLock.withReadLock { unsafeCore.lastEntryUnsafe() }
        
        suspend fun lowerKey(key: K): K? = 
            rwLock.withReadLock { unsafeCore.lowerKeyUnsafe(key) }
        
        suspend fun floorKey(key: K): K? = 
            rwLock.withReadLock { unsafeCore.floorKeyUnsafe(key) }
        
        suspend fun ceilingKey(key: K): K? = 
            rwLock.withReadLock { unsafeCore.ceilingKeyUnsafe(key) }
        
        suspend fun higherKey(key: K): K? = 
            rwLock.withReadLock { unsafeCore.higherKeyUnsafe(key) }
        
        suspend fun lowerEntry(key: K): MapEntry<K, V>? = 
            rwLock.withReadLock { unsafeCore.lowerEntryUnsafe(key) }
        
        suspend fun floorEntry(key: K): MapEntry<K, V>? = 
            rwLock.withReadLock { unsafeCore.floorEntryUnsafe(key) }
        
        suspend fun ceilingEntry(key: K): MapEntry<K, V>? = 
            rwLock.withReadLock { unsafeCore.ceilingEntryUnsafe(key) }
        
        suspend fun higherEntry(key: K): MapEntry<K, V>? = 
            rwLock.withReadLock { unsafeCore.higherEntryUnsafe(key) }
        
        suspend fun pollFirstEntry(): MapEntry<K, V>? = 
            rwLock.withWriteLock { 
                unsafeCore.pollFirstEntryUnsafe() 
            }
        
        suspend fun pollLastEntry(): MapEntry<K, V>? = 
            rwLock.withWriteLock { 
                unsafeCore.pollLastEntryUnsafe() 
            }
        
        // Secondary index operations
        suspend fun containsSecondaryKey(indexName: String, secondaryKey: Any?): Boolean = 
            rwLock.withReadLock { this@TreeMap.containsSecondaryKey(indexName, secondaryKey) }

        suspend fun getSecondaryKeys(indexName: String): Set<Any?> =
            rwLock.withReadLock { this@TreeMap.getSecondaryKeys(indexName) }
        
        suspend fun getSecondaryIndexNames(): Set<String> = 
            rwLock.withReadLock { this@TreeMap.getSecondaryIndexNames() }

        suspend fun getPrimaryKeysBySecondaryKey(vararg criteria: Pair<String, Any?>): Set<K> =
            rwLock.withReadLock { this@TreeMap.getPrimaryKeysBySecondaryKey(*criteria) }

        suspend fun getBy(vararg criteria: Pair<String, Any?>): Collection<V> =
            rwLock.withReadLock { this@TreeMap.getBy(*criteria) }
        
        // Modern Map API
        suspend fun putIfAbsent(key: K, value: V): V? = 
            rwLock.withWriteLock { 
                unsafeCore.putIfAbsentUnsafe(key, value) 
            }
        
        suspend fun replace(key: K, value: V): V? = 
            rwLock.withWriteLock { 
                unsafeCore.replaceUnsafe(key, value) 
            }
        
        suspend fun replace(key: K, oldValue: V, newValue: V): Boolean = 
            rwLock.withWriteLock { 
                unsafeCore.replaceUnsafe(key, oldValue, newValue) 
            }
        
        suspend fun compute(key: K, remappingFunction: (K, V?) -> V?): V? = 
            rwLock.withWriteLock { 
                unsafeCore.computeUnsafe(key, remappingFunction) 
            }
        
        suspend fun computeIfAbsent(key: K, mappingFunction: (K) -> V?): V? = 
            rwLock.withWriteLock { 
                unsafeCore.computeIfAbsentUnsafe(key, mappingFunction) 
            }
        
        suspend fun computeIfPresent(key: K, remappingFunction: (K, V) -> V?): V? = 
            rwLock.withWriteLock { 
                unsafeCore.computeIfPresentUnsafe(key, remappingFunction) 
            }
        
        suspend fun merge(key: K, value: V, remappingFunction: (V, V) -> V?): V? = 
            rwLock.withWriteLock { 
                unsafeCore.mergeUnsafe(key, value, remappingFunction) 
            }
        
        // Range operations
        suspend fun subMapEntries(fromKey: K?, fromInclusive: Boolean, toKey: K?, toInclusive: Boolean): List<MapEntry<K, V>> = 
            rwLock.withReadLock { 
                unsafeCore.subMapEntriesUnsafe(fromKey, fromInclusive, toKey, toInclusive) 
            }
        
        suspend fun headMapEntries(toKey: K): List<MapEntry<K, V>> = 
            rwLock.withReadLock { unsafeCore.headMapEntriesUnsafe(toKey) }
        
        suspend fun tailMapEntries(fromKey: K): List<MapEntry<K, V>> = 
            rwLock.withReadLock { unsafeCore.tailMapEntriesUnsafe(fromKey) }
        
        // Collection views (Note: These return non-suspend collections for compatibility)
        val keys: MutableSet<K> 
            get() = runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.keysUnsafe() } }
        
        val values: MutableCollection<V> 
            get() = runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.valuesUnsafe() } }
        
        val entries: MutableSet<MapEntry<K, V>> 
            get() = runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.entriesUnsafe() } }
    }
    
    // ========================================================================
    // BlockingTreeMapView - Blocking access wrapper with runBlockingMultiplatform
    // ========================================================================
    
    /**
     * Blocking view for non-suspend contexts.
     * Uses runBlockingMultiplatform to wrap suspend operations for thread-safe access.
     */
    internal inner class BlockingTreeMapView : MutableMap<K, V> {
        
        // Basic Map operations
        override fun get(key: K): V? = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.getUnsafe(key) } }
        
        override fun put(key: K, value: V): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.putUnsafe(key, value) 
                }
            }
        
        override fun remove(key: K): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.removeUnsafe(key) 
                }
            }
        
        override fun clear() = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.clearUnsafe() 
                }
            }
        
        override fun containsKey(key: K): Boolean = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.containsKeyUnsafe(key) } }
        
        override fun containsValue(value: V): Boolean = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.containsValueUnsafe(value) } }
        
        override fun putAll(from: Map<out K, V>) = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.putAllUnsafe(from) 
                }
            }
        
        override val size: Int get() = unsafeCore.size
        override fun isEmpty(): Boolean = unsafeCore.isEmpty
        
        // Collection views
        override val keys: MutableSet<K> 
            get() = runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.keysUnsafe() } }
        
        override val values: MutableCollection<V> 
            get() = runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.valuesUnsafe() } }
        
        override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
            get() = runBlockingMultiplatform { 
                rwLock.withReadLock { 
                    // MapEntry now properly implements MutableMap.MutableEntry - safe cast
                    @Suppress("UNCHECKED_CAST")
                    unsafeCore.entriesUnsafe() as MutableSet<MutableMap.MutableEntry<K, V>>
                }
            }
        
        // NavigableMap operations
        fun firstKey(): K = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.firstKeyUnsafe() } }
        
        fun lastKey(): K = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.lastKeyUnsafe() } }
        
        fun firstEntry(): MapEntry<K, V>? = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.firstEntryUnsafe() } }
        
        fun lastEntry(): MapEntry<K, V>? = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.lastEntryUnsafe() } }
        
        fun lowerKey(key: K): K? = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.lowerKeyUnsafe(key) } }
        
        fun floorKey(key: K): K? = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.floorKeyUnsafe(key) } }
        
        fun ceilingKey(key: K): K? = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.ceilingKeyUnsafe(key) } }
        
        fun higherKey(key: K): K? = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.higherKeyUnsafe(key) } }
        
        fun lowerEntry(key: K): MapEntry<K, V>? = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.lowerEntryUnsafe(key) } }
        
        fun floorEntry(key: K): MapEntry<K, V>? = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.floorEntryUnsafe(key) } }
        
        fun ceilingEntry(key: K): MapEntry<K, V>? = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.ceilingEntryUnsafe(key) } }
        
        fun higherEntry(key: K): MapEntry<K, V>? = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.higherEntryUnsafe(key) } }
        
        fun pollFirstEntry(): MapEntry<K, V>? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.pollFirstEntryUnsafe() 
                }
            }
        
        fun pollLastEntry(): MapEntry<K, V>? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.pollLastEntryUnsafe() 
                }
            }
        
        // Secondary index operations
        fun containsSecondaryKey(indexName: String, secondaryKey: Any?): Boolean = 
            runBlockingMultiplatform { rwLock.withReadLock { this@TreeMap.containsSecondaryKey(indexName, secondaryKey) } }

        fun getSecondaryKeys(indexName: String): Set<Any?> =
            runBlockingMultiplatform { rwLock.withReadLock { this@TreeMap.getSecondaryKeys(indexName) } }

        fun getSecondaryIndexNames(): Set<String> = 
            runBlockingMultiplatform { rwLock.withReadLock { this@TreeMap.getSecondaryIndexNames() } }

        fun getPrimaryKeysBySecondaryKey(vararg criteria: Pair<String, Any?>): Set<K> =
            runBlockingMultiplatform { rwLock.withReadLock { this@TreeMap.getPrimaryKeysBySecondaryKey(*criteria) } }

        fun getBy(vararg criteria: Pair<String, Any?>): Collection<V> =
            runBlockingMultiplatform { rwLock.withReadLock { this@TreeMap.getBy(*criteria) } }
        
        // Modern Map API
        fun putIfAbsent(key: K, value: V): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.putIfAbsentUnsafe(key, value) 
                }
            }
        
        fun replace(key: K, value: V): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.replaceUnsafe(key, value) 
                }
            }
        
        fun replace(key: K, oldValue: V, newValue: V): Boolean = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.replaceUnsafe(key, oldValue, newValue) 
                }
            }
        
        fun compute(key: K, remappingFunction: (K, V?) -> V?): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.computeUnsafe(key, remappingFunction) 
                }
            }
        
        fun computeIfAbsent(key: K, mappingFunction: (K) -> V?): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.computeIfAbsentUnsafe(key, mappingFunction) 
                }
            }
        
        fun computeIfPresent(key: K, remappingFunction: (K, V) -> V?): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.computeIfPresentUnsafe(key, remappingFunction) 
                }
            }
        
        fun merge(key: K, value: V, remappingFunction: (V, V) -> V?): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    unsafeCore.mergeUnsafe(key, value, remappingFunction) 
                }
            }
        
        // Range operations
        fun subMapEntries(fromKey: K?, fromInclusive: Boolean, toKey: K?, toInclusive: Boolean): List<MapEntry<K, V>> = 
            runBlockingMultiplatform { 
                rwLock.withReadLock { 
                    unsafeCore.subMapEntriesUnsafe(fromKey, fromInclusive, toKey, toInclusive) 
                }
            }
        
        fun headMapEntries(toKey: K): List<MapEntry<K, V>> = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.headMapEntriesUnsafe(toKey) } }
        
        fun tailMapEntries(fromKey: K): List<MapEntry<K, V>> = 
            runBlockingMultiplatform { rwLock.withReadLock { unsafeCore.tailMapEntriesUnsafe(fromKey) } }
    }
}