package jst.oktopoi


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
 * ## Secondary Index Usage Warning
 * 
 * **IMPORTANT**: When using secondary indexes, values should be treated as immutable 
 * after being stored in the map. Mutating values after storage can cause inconsistent 
 * secondary index state and incorrect query results.
 * 
 * **Safe approach:**
 * ```kotlin
 * map.put("key", user)  // Store immutable value
 * map.replace("key", user.copy(department = "Sales"))  // Update via replacement
 * ```
 * 
 * **Unsafe approach:**
 * ```kotlin
 * map.put("key", user)  // Store value
 * user.department = "Sales"  // Direct mutation - breaks secondary indexes!
 * ```
 * 
 * @param K the type of keys maintained by this map
 * @param V the type of mapped values
 * @param keyComparator the comparator used to order the keys, or null for natural ordering
 */
open class TreeMap<K, V> internal constructor(
    keyComparator: Comparator<K> = Comparator { k1, k2 ->
        (k1 as Comparable<K>).compareTo(k2)
    }
) : UnsafeTreeMapCore<K, V>(keyComparator), MutableMap<K, V> {
    
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
    private val rwLock = ReadWriteLock()
    
    // Secondary index management (moved from UnsafeTreeMapCore)
    private val secondaryKeyExtractors = mutableMapOf<String, (V) -> Any?>()
    private val secondaryIndexes = mutableMapOf<String, MutableMap<Any?, MutableSet<K>>>()
    
    // ========================================================================
    // Override core operations to add secondary index management
    // ========================================================================
    
    override fun putUnsafe(key: K, value: V): V? {
        val oldValue = super.putUnsafe(key, value)
        updateSecondaryIndexes(key, oldValue, value)
        return oldValue
    }
    
    override fun removeUnsafe(key: K): V? {
        val oldValue = super.removeUnsafe(key)
        if (oldValue != null) {
            updateSecondaryIndexes(key, oldValue, null)
        }
        return oldValue
    }
    
    override fun clearUnsafe() {
        super.clearUnsafe()
        // Clear all secondary indexes
        secondaryIndexes.values.forEach { it.clear() }
    }
    
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
         * Adds a secondary index for efficient lookups by key extraction.
         * 
         * @param indexName unique name for this index
         * @param keyExtractor function to extract secondary key from values
         * @return this builder for chaining
         */
        fun key(indexName: String, keyExtractor: (V) -> Any?): SecondaryIndexBuilder<K, V> {
            treeMap.addSecondaryIndex(indexName, keyExtractor)
            return this
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
    internal constructor(
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
        entriesUnsafe().forEach { entry ->
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
        return blocking.put(key, value)
    }
    
    override fun remove(key: K): V? {
        return blocking.remove(key)
    }
    
    override fun putAll(from: Map<out K, V>) {
        return blocking.putAll(from)
    }
    
    override fun clear() {
        return blocking.clear()
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
        return getPrimaryKeysBySecondaryKey(*criteria).mapNotNull { this[it] }
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
    open fun putIfAbsent(key: K, value: V): V? = blocking.putIfAbsent(key, value)
    open fun replace(key: K, value: V): V? = blocking.replace(key, value)
    open fun replace(key: K, oldValue: V, newValue: V): Boolean = blocking.replace(key, oldValue, newValue)
    
    open fun compute(key: K, remappingFunction: (K, V?) -> V?): V? = blocking.compute(key, remappingFunction)
    open fun computeIfAbsent(key: K, mappingFunction: (K) -> V?): V? = blocking.computeIfAbsent(key, mappingFunction)
    open fun computeIfPresent(key: K, remappingFunction: (K, V) -> V?): V? = blocking.computeIfPresent(key, remappingFunction)
    open fun merge(key: K, value: V, remappingFunction: (V, V) -> V?): V? = blocking.merge(key, value, remappingFunction)
    
    // Range operations
    fun subMapEntries(fromKey: K?, fromInclusive: Boolean, toKey: K?, toInclusive: Boolean): List<MapEntry<K, V>> =
        blocking.subMapEntries(fromKey, fromInclusive, toKey, toInclusive)
    
    fun headMapEntries(toKey: K): List<MapEntry<K, V>> = blocking.headMapEntries(toKey)
    fun tailMapEntries(fromKey: K): List<MapEntry<K, V>> = blocking.tailMapEntries(fromKey)
    
//    // Debug utilities
//    fun debugString(): String =
//        runBlockingMultiplatform { rwLock.withReadLock { debugStringUnsafe() } }
    
    
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
            rwLock.withReadLock { getUnsafe(key) }
        
        suspend fun put(key: K, value: V): V? = 
            rwLock.withWriteLock { putUnsafe(key, value) }
        
        suspend fun remove(key: K): V? = 
            rwLock.withWriteLock { removeUnsafe(key) }
        
        suspend fun clear() = 
            rwLock.withWriteLock { clearUnsafe() }
        
        suspend fun containsKey(key: K): Boolean = 
            rwLock.withReadLock { containsKeyUnsafe(key) }
        
        suspend fun containsValue(value: V): Boolean = 
            rwLock.withReadLock { containsValueUnsafe(value) }
        
        suspend fun putAll(from: Map<out K, V>) = 
            rwLock.withWriteLock { 
                from.forEach { (key, value) -> putUnsafe(key, value) }
            }
        
        val size: Int get() = size
        val isEmpty: Boolean get() = isEmpty
        
        // NavigableMap operations
        suspend fun firstKey(): K = 
            rwLock.withReadLock { firstKeyUnsafe() }
        
        suspend fun lastKey(): K = 
            rwLock.withReadLock { lastKeyUnsafe() }
        
        suspend fun firstEntry(): MapEntry<K, V>? = 
            rwLock.withReadLock { firstEntryUnsafe() }
        
        suspend fun lastEntry(): MapEntry<K, V>? = 
            rwLock.withReadLock { lastEntryUnsafe() }
        
        suspend fun lowerKey(key: K): K? = 
            rwLock.withReadLock { lowerKeyUnsafe(key) }
        
        suspend fun floorKey(key: K): K? = 
            rwLock.withReadLock { floorKeyUnsafe(key) }
        
        suspend fun ceilingKey(key: K): K? = 
            rwLock.withReadLock { ceilingKeyUnsafe(key) }
        
        suspend fun higherKey(key: K): K? = 
            rwLock.withReadLock { higherKeyUnsafe(key) }
        
        suspend fun lowerEntry(key: K): MapEntry<K, V>? = 
            rwLock.withReadLock { lowerEntryUnsafe(key) }
        
        suspend fun floorEntry(key: K): MapEntry<K, V>? = 
            rwLock.withReadLock { floorEntryUnsafe(key) }
        
        suspend fun ceilingEntry(key: K): MapEntry<K, V>? = 
            rwLock.withReadLock { ceilingEntryUnsafe(key) }
        
        suspend fun higherEntry(key: K): MapEntry<K, V>? = 
            rwLock.withReadLock { higherEntryUnsafe(key) }
        
        suspend fun pollFirstEntry(): MapEntry<K, V>? = 
            rwLock.withWriteLock { 
                pollFirstEntryUnsafe() 
            }
        
        suspend fun pollLastEntry(): MapEntry<K, V>? = 
            rwLock.withWriteLock { 
                pollLastEntryUnsafe() 
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
                putIfAbsentUnsafe(key, value) 
            }
        
        suspend fun replace(key: K, value: V): V? = 
            rwLock.withWriteLock { 
                replaceUnsafe(key, value) 
            }
        
        suspend fun replace(key: K, oldValue: V, newValue: V): Boolean = 
            rwLock.withWriteLock { 
                replaceUnsafe(key, oldValue, newValue) 
            }
        
        suspend fun compute(key: K, remappingFunction: (K, V?) -> V?): V? = 
            rwLock.withWriteLock { 
                computeUnsafe(key, remappingFunction) 
            }
        
        suspend fun computeIfAbsent(key: K, mappingFunction: (K) -> V?): V? = 
            rwLock.withWriteLock { 
                computeIfAbsentUnsafe(key, mappingFunction) 
            }
        
        suspend fun computeIfPresent(key: K, remappingFunction: (K, V) -> V?): V? = 
            rwLock.withWriteLock { 
                computeIfPresentUnsafe(key, remappingFunction) 
            }
        
        suspend fun merge(key: K, value: V, remappingFunction: (V, V) -> V?): V? = 
            rwLock.withWriteLock { 
                mergeUnsafe(key, value, remappingFunction) 
            }
        
        // Range operations
        suspend fun subMapEntries(fromKey: K?, fromInclusive: Boolean, toKey: K?, toInclusive: Boolean): List<MapEntry<K, V>> = 
            rwLock.withReadLock { 
                subMapEntriesUnsafe(fromKey, fromInclusive, toKey, toInclusive) 
            }
        
        suspend fun headMapEntries(toKey: K): List<MapEntry<K, V>> = 
            rwLock.withReadLock { headMapEntriesUnsafe(toKey) }
        
        suspend fun tailMapEntries(fromKey: K): List<MapEntry<K, V>> = 
            rwLock.withReadLock { tailMapEntriesUnsafe(fromKey) }
        
        // Collection views (Note: These return non-suspend collections for compatibility)
        val keys: MutableSet<K> 
            get() = runBlockingMultiplatform { rwLock.withReadLock { keysUnsafe() } }
        
        val values: MutableCollection<V> 
            get() = runBlockingMultiplatform { rwLock.withReadLock { valuesUnsafe() } }
        
        val entries: MutableSet<MapEntry<K, V>> 
            get() = runBlockingMultiplatform { rwLock.withReadLock { entriesUnsafe() } }
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
            runBlockingMultiplatform { rwLock.withReadLock { getUnsafe(key) } }
        
        override fun put(key: K, value: V): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { putUnsafe(key, value) }
            }
        
        override fun remove(key: K): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { removeUnsafe(key) }
            }
        
        override fun clear() = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { clearUnsafe() }
            }
        
        override fun containsKey(key: K): Boolean = 
            runBlockingMultiplatform { rwLock.withReadLock { containsKeyUnsafe(key) } }
        
        override fun containsValue(value: V): Boolean = 
            runBlockingMultiplatform { rwLock.withReadLock { containsValueUnsafe(value) } }
        
        override fun putAll(from: Map<out K, V>) = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    from.forEach { (key, value) -> putUnsafe(key, value) }
                }
            }
        
        override val size: Int get() = runBlockingMultiplatform {
            rwLock.withReadLock {
                sizeUnsafe
            }
        }
        override fun isEmpty(): Boolean = runBlockingMultiplatform {
            rwLock.withReadLock {
                isEmptyUnsafe
            }
        }

        // Collection views
        override val keys: MutableSet<K> 
            get() = runBlockingMultiplatform { rwLock.withReadLock { keysUnsafe() } }
        
        override val values: MutableCollection<V> 
            get() = runBlockingMultiplatform { rwLock.withReadLock { valuesUnsafe() } }
        
        override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
            get() = runBlockingMultiplatform { 
                rwLock.withReadLock { 
                    // MapEntry now properly implements MutableMap.MutableEntry - safe cast
                    @Suppress("UNCHECKED_CAST")
                    entriesUnsafe() as MutableSet<MutableMap.MutableEntry<K, V>>
                }
            }
        
        // NavigableMap operations
        fun firstKey(): K = 
            runBlockingMultiplatform { rwLock.withReadLock { firstKeyUnsafe() } }
        
        fun lastKey(): K = 
            runBlockingMultiplatform { rwLock.withReadLock { lastKeyUnsafe() } }
        
        fun firstEntry(): MapEntry<K, V>? = 
            runBlockingMultiplatform { rwLock.withReadLock { firstEntryUnsafe() } }
        
        fun lastEntry(): MapEntry<K, V>? = 
            runBlockingMultiplatform { rwLock.withReadLock { lastEntryUnsafe() } }
        
        fun lowerKey(key: K): K? = 
            runBlockingMultiplatform { rwLock.withReadLock { lowerKeyUnsafe(key) } }
        
        fun floorKey(key: K): K? = 
            runBlockingMultiplatform { rwLock.withReadLock { floorKeyUnsafe(key) } }
        
        fun ceilingKey(key: K): K? = 
            runBlockingMultiplatform { rwLock.withReadLock { ceilingKeyUnsafe(key) } }
        
        fun higherKey(key: K): K? = 
            runBlockingMultiplatform { rwLock.withReadLock { higherKeyUnsafe(key) } }
        
        fun lowerEntry(key: K): MapEntry<K, V>? = 
            runBlockingMultiplatform { rwLock.withReadLock { lowerEntryUnsafe(key) } }
        
        fun floorEntry(key: K): MapEntry<K, V>? = 
            runBlockingMultiplatform { rwLock.withReadLock { floorEntryUnsafe(key) } }
        
        fun ceilingEntry(key: K): MapEntry<K, V>? = 
            runBlockingMultiplatform { rwLock.withReadLock { ceilingEntryUnsafe(key) } }
        
        fun higherEntry(key: K): MapEntry<K, V>? = 
            runBlockingMultiplatform { rwLock.withReadLock { higherEntryUnsafe(key) } }
        
        fun pollFirstEntry(): MapEntry<K, V>? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    pollFirstEntryUnsafe() 
                }
            }
        
        fun pollLastEntry(): MapEntry<K, V>? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    pollLastEntryUnsafe() 
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
                    putIfAbsentUnsafe(key, value) 
                }
            }
        
        fun replace(key: K, value: V): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    replaceUnsafe(key, value) 
                }
            }
        
        fun replace(key: K, oldValue: V, newValue: V): Boolean = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    replaceUnsafe(key, oldValue, newValue) 
                }
            }
        
        fun compute(key: K, remappingFunction: (K, V?) -> V?): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    computeUnsafe(key, remappingFunction) 
                }
            }
        
        fun computeIfAbsent(key: K, mappingFunction: (K) -> V?): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    computeIfAbsentUnsafe(key, mappingFunction) 
                }
            }
        
        fun computeIfPresent(key: K, remappingFunction: (K, V) -> V?): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    computeIfPresentUnsafe(key, remappingFunction) 
                }
            }
        
        fun merge(key: K, value: V, remappingFunction: (V, V) -> V?): V? = 
            runBlockingMultiplatform { 
                rwLock.withWriteLock { 
                    mergeUnsafe(key, value, remappingFunction) 
                }
            }
        
        // Range operations
        fun subMapEntries(fromKey: K?, fromInclusive: Boolean, toKey: K?, toInclusive: Boolean): List<MapEntry<K, V>> = 
            runBlockingMultiplatform { 
                rwLock.withReadLock { 
                    subMapEntriesUnsafe(fromKey, fromInclusive, toKey, toInclusive) 
                }
            }
        
        fun headMapEntries(toKey: K): List<MapEntry<K, V>> = 
            runBlockingMultiplatform { rwLock.withReadLock { headMapEntriesUnsafe(toKey) } }
        
        fun tailMapEntries(fromKey: K): List<MapEntry<K, V>> = 
            runBlockingMultiplatform { rwLock.withReadLock { tailMapEntriesUnsafe(fromKey) } }
    }
}