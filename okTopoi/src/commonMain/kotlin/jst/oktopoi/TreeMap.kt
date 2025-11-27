package jst.oktopoi

import co.touchlab.kermit.Logger
import kotlin.collections.emptySet


/**
 * A Red-Black tree based implementation of MutableMap with NavigableMap operations.
 *
 * This implementation provides:
 * - O(log n) performance for all basic operations (get, put, remove)
 * - O(1) secondary index access per criterion, O(k) for k criteria due to intersection
 * - Thread-safe access through ReadWriteLock
 * - Suspend-first API design for seamless coroutine integration
 * - Complete NavigableMap API (firstKey, lastKey, lower/floor/ceiling/higher)
 * - Collection views with efficient iterators
 *
 * ## Architecture
 *
 * TreeMap uses a layered approach for thread safety:
 * - UnsafeTreeMapCore: Thread-unsafe core with all red-black tree algorithms
 * - TreeMap: Wraps core with ReadWriteLock and provides suspend API
 * - All public operations are suspend functions requiring coroutine context
 *
 * ## Extensibility via Hooks
 *
 * - Public API methods on TreeMap are final to ensure consistent behavior and guarantee that
 *   hook methods (onBefore* / onAfter*) are always invoked through the validated execution path.
 * - Subclasses must customize behavior via the protected unsafe hooks; overriding the public
 *   API is intentionally disallowed.
 * - Unsafe overrides in TreeMap (putUnsafe/removeUnsafe/etc.) are final to preserve invariants
 *   and ensure hooks are always executed. Do not override unsafe methods in subclasses; use hooks.
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
@Suppress("UNCHECKED_CAST")
open class TreeMap<K, V> internal constructor(
    keyComparator: Comparator<K> = Comparator { k1, k2 ->
        (k1 as Comparable<K>).compareTo(k2)
    }
) : UnsafeTreeMapCore<K, V>(keyComparator), SuspendMutableMap<K, V> {


    /**
     * Logger for TreeMap operations, primarily for error reporting
     * (e.g., missing secondary index definitions).
     */
    private val log = Logger.withTag("OkTopoi-TreeMap")
    
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
    
    // Expose lock helpers to subclasses to support single-lock designs in extensions
    // Use these only for cross-cutting concerns (e.g., persistence/sync metadata) that must share
    // the same critical sections as TreeMap mutations.
    protected suspend fun <T> withReadLock(block: suspend () -> T): T =
        rwLock.withReadLock { block() }
    
    protected suspend fun <T> withWriteLock(block: suspend () -> T): T =
        rwLock.withWriteLock { block() }
    
    protected fun <T> withReadLockBlocking(block: () -> T): T =
        runBlockingMultiplatform { rwLock.withReadLock { block() } }
    
    protected fun <T> withWriteLockBlocking(block: () -> T): T =
        runBlockingMultiplatform { rwLock.withWriteLock { block() } }
    
    // Deletion tracking for resilient iterators
    private var deleteModCountUnsafe = 0
    
    // Secondary index management (moved from UnsafeTreeMapCore)
    private val secondaryKeyExtractors = mutableMapOf<String, (V) -> Any?>()
    private val secondaryIndexes = mutableMapOf<String, MutableMap<Any?, MutableSet<K>>>()
    
    // ========================================================================
    // Hook methods for subclass overrides
    // ========================================================================
    
    /**
     * Hook called before a put operation while under write lock.
     * Subclasses should override this for side effects that must happen before memory update.
     * 
     * @param key the key to be added/updated
     * @param newValue the new value to be stored
     */
    protected open fun onBeforePutUnsafe(key: K, newValue: V) {
        // No-op in base TreeMap
    }
    
    /**
     * Hook called after a successful put operation while under write lock.
     * Subclasses should override this for side effects that should happen after memory update.
     * 
     * @param key the key that was added/updated
     * @param newValue the new value that was stored
     * @param oldValue the previous value (null if this was an insert)
     */
    protected open fun onAfterPutUnsafe(key: K, newValue: V, oldValue: V?) {
        // TreeMap's responsibility: update secondary indexes
        updateSecondaryIndexes(key, oldValue, newValue)
    }
    
    /**
     * Hook called before a remove operation while under write lock.
     * Subclasses should override this for side effects that must happen before memory update.
     * 
     * @param key the key to be removed
     */
    protected open fun onBeforeRemoveUnsafe(key: K) {
        // No-op in base TreeMap
    }
    
    /**
     * Hook called after a successful remove operation while under write lock.
     * Subclasses should override this for side effects that should happen after memory update.
     * 
     * @param key the key that was removed
     * @param oldValue the value that was removed
     */
    protected open fun onAfterRemoveUnsafe(key: K, oldValue: V) {
        // TreeMap's responsibility: update secondary indexes and deletion tracking
        deleteModCountUnsafe++
        updateSecondaryIndexes(key, oldValue, null)
    }
    
    /**
     * Hook called before a clear operation while under write lock.
     * Subclasses should override this for side effects that must happen before memory update.
     */
    protected open fun onBeforeClearUnsafe() {
        // No-op in base TreeMap
    }
    
    /**
     * Hook called after a successful clear operation while under write lock.
     * Subclasses should override this for side effects that should happen after memory update.
     */
    protected open fun onAfterClearUnsafe() {
        // TreeMap's responsibility: clear secondary indexes and deletion tracking
        deleteModCountUnsafe++
        secondaryIndexes.values.forEach { it.clear() }
    }
    
    // ========================================================================
    // Override core operations to add hook calls
    // ========================================================================
    
    final override fun putUnsafe(key: K, value: V): V? {
        onBeforePutUnsafe(key, value)
        val oldValue = super.putUnsafe(key, value)
        onAfterPutUnsafe(key, value, oldValue)
        return oldValue
    }
    
    final override fun removeUnsafe(key: K): V? {
        onBeforeRemoveUnsafe(key)
        val oldValue = super.removeUnsafe(key)
        if (oldValue != null) {
            onAfterRemoveUnsafe(key, oldValue)
        }
        return oldValue
    }
    
    final override fun clearUnsafe() {
        onBeforeClearUnsafe()
        super.clearUnsafe()
        onAfterClearUnsafe()
    }
    
    final override fun replaceUnsafe(key: K, value: V): V? {
        onBeforePutUnsafe(key, value)
        val old = super.replaceUnsafe(key, value)
        if (old != null) {
            onAfterPutUnsafe(key, value, old)
        }
        return old
    }
    
    final override fun replaceUnsafe(key: K, oldValue: V, newValue: V): Boolean {
        onBeforePutUnsafe(key, newValue)
        val replaced = super.replaceUnsafe(key, oldValue, newValue)
        if (replaced) {
            onAfterPutUnsafe(key, newValue, oldValue)
        }
        return replaced
    }
    
    final override fun pollFirstEntryUnsafe(): MapEntry<K, V>? {
        // Peek the entry before removal for the hook
        val entry = firstEntryUnsafe()
        if (entry != null) {
            onBeforeRemoveUnsafe(entry.key)
        }
        val result = super.pollFirstEntryUnsafe()
        if (result != null) {
            onAfterRemoveUnsafe(result.key, result.value)
        }
        return result
    }
    
    final override fun pollLastEntryUnsafe(): MapEntry<K, V>? {
        // Peek the entry before removal for the hook
        val entry = lastEntryUnsafe()
        if (entry != null) {
            onBeforeRemoveUnsafe(entry.key)
        }
        val result = super.pollLastEntryUnsafe()
        if (result != null) {
            onAfterRemoveUnsafe(result.key, result.value)
        }
        return result
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
    internal fun addSecondaryIndex(indexName: String, keyExtractor: (V) -> Any?) {
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
        forEachUnsafe { key, value ->
            val secondaryKey = keyExtractor(value)
            index.getOrPut(secondaryKey) { mutableSetOf() }.add(key)  // Allow null as valid secondary key
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
    
    
    
    // Unsafe helpers for secondary index reads (require caller to hold at least a read lock)
    private fun containsSecondaryKeyUnsafe(indexName: String, secondaryKey: Any?): Boolean {
        val index = secondaryIndexes[indexName] ?: return false
        return index.containsKey(secondaryKey) && index[secondaryKey]?.isNotEmpty() == true
    }
    
    private fun getSecondaryKeysUnsafe(indexName: String): Set<Any?> {
        val index = secondaryIndexes[indexName] ?: return emptySet()
        return index.keys.toSet()
    }
    
    private fun getSecondaryIndexNamesUnsafe(): Set<String> {
        return secondaryKeyExtractors.keys.toSet()
    }
    
    /**
     * Efficiently iterates over entries matching multiple secondary key criteria.
     * Caller must hold appropriate lock.
     */
    private fun forEachKeyByUnsafe(vararg criteria: Pair<String, Any?>, action: (key: K) -> Unit) {
        if (criteria.isEmpty()) {
            forEachUnsafe { key, value -> action.invoke(key) }
        } else if (criteria.size == 1) {
            val (indexName, secondaryKey) = criteria[0]
            secondaryIndexes[indexName].also {
                if (it == null) {
                    log.e { "secondary index $indexName is not defined; defined keys: $secondaryIndexes" }
                } else {
                    it[secondaryKey]?.forEach { primaryKey -> action(primaryKey) }
                }
            }
        } else {
            // Multi-criteria: collect matching keys, then iterate safely
            criteria
                .map { (indexName, secondaryKey) ->
                    secondaryIndexes[indexName].let {
                        if (it == null) {
                            log.e { "secondary index $indexName is not defined; defined keys: $secondaryIndexes" }
                            emptySet()
                        } else {
                            it[secondaryKey]?.toSet() ?: emptySet()
                        }
                    }
                }
                .reduce { acc, keySet -> acc.intersect(keySet) }
                .forEach { key -> action(key) }
        }
    }
    
    private fun getByUnsafe(vararg criteria: Pair<String, Any?>): Collection<V> {
        val list = mutableListOf<V>()
        forEachKeyByUnsafe(*criteria) { list.add(getUnsafe(it)!!) }
        return list
    }
    
    // ========================================================================
    // NavigableMap Operations (suspend-based)
    // ========================================================================

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
        rwLock.withWriteLock { pollFirstEntryUnsafe() }

    suspend fun pollLastEntry(): MapEntry<K, V>? =
        rwLock.withWriteLock { pollLastEntryUnsafe() }

    // ========================================================================
    // Secondary Index Operations (suspend-based)
    // ========================================================================

    suspend fun containsSecondaryKey(indexName: String, secondaryKey: Any?): Boolean =
        rwLock.withReadLock { containsSecondaryKeyUnsafe(indexName, secondaryKey) }

    suspend fun getSecondaryKeys(indexName: String): Set<Any?> =
        rwLock.withReadLock { getSecondaryKeysUnsafe(indexName) }

    suspend fun getSecondaryIndexNames(): Set<String> =
        rwLock.withReadLock { getSecondaryIndexNamesUnsafe() }

    suspend fun forEachBy(vararg criteria: Pair<String, Any?>, action: (key: K, value: V) -> Unit) =
        rwLock.withReadLock { forEachKeyByUnsafe(*criteria, action = { action.invoke(it, getUnsafe(it)!!) }) }

    suspend fun getBy(vararg criteria: Pair<String, Any?>): Collection<V> =
        rwLock.withReadLock { getByUnsafe(*criteria) }

    // ========================================================================
    // Extended Map Operations (suspend-based)
    // ========================================================================

    suspend fun putIfAbsent(key: K, value: V): V? =
        rwLock.withWriteLock { putIfAbsentUnsafe(key, value) }

    override suspend fun replace(key: K, value: V): V? =
        rwLock.withWriteLock { replaceUnsafe(key, value) }

    override suspend fun replace(key: K, oldValue: V, newValue: V): Boolean =
        rwLock.withWriteLock { replaceUnsafe(key, oldValue, newValue) }

    /**
     * Computes a value for the specified key and its current mapped value (or null if there is no current mapping).
     *
     * ⚠️ **DEADLOCK WARNING**: Your remappingFunction runs while holding a write lock on this TreeMap.
     * **DO NOT** call any suspend TreeMap operations (get, put, remove, etc.) inside the lambda,
     * as they will attempt to acquire locks and cause a deadlock.
     *
     * Safe usage:
     * ```kotlin
     * map.compute(key) { k, oldValue ->
     *     oldValue?.copy(counter = oldValue.counter + 1) // ✅ Safe - no lock calls
     * }
     * ```
     *
     * **WILL DEADLOCK:**
     * ```kotlin
     * map.compute(key) { k, oldValue ->
     *     val other = map.get(otherId)  // ❌ DEADLOCK - tries to acquire read lock
     *     oldValue?.copy(related = other)
     * }
     * ```
     *
     * @param key The key whose value is to be computed
     * @param remappingFunction The function to compute a value. Must NOT call suspend TreeMap operations.
     * @return The new value associated with the key, or null if none
     */
    suspend fun compute(key: K, remappingFunction: (K, V?) -> V?): V? =
        rwLock.withWriteLock { computeUnsafe(key, remappingFunction) }

    /**
     * Computes a value for the specified key if it is not already present.
     *
     * ⚠️ **DEADLOCK WARNING**: Your mappingFunction runs while holding a write lock on this TreeMap.
     * **DO NOT** call any suspend TreeMap operations (get, put, remove, etc.) inside the lambda,
     * as they will attempt to acquire locks and cause a deadlock.
     *
     * Safe usage:
     * ```kotlin
     * map.computeIfAbsent(key) { k ->
     *     MyValue(id = k, name = "Default") // ✅ Safe - no lock calls
     * }
     * ```
     *
     * **WILL DEADLOCK:**
     * ```kotlin
     * map.computeIfAbsent(key) { k ->
     *     val template = map.get(templateId)  // ❌ DEADLOCK - tries to acquire read lock
     *     template?.copy(id = k)
     * }
     * ```
     *
     * @param key The key whose value is to be computed if absent
     * @param mappingFunction The function to compute a value. Must NOT call suspend TreeMap operations.
     * @return The current (existing or computed) value associated with the key, or null if the computed value is null
     */
    suspend fun computeIfAbsent(key: K, mappingFunction: (K) -> V?): V? =
        rwLock.withWriteLock { computeIfAbsentUnsafe(key, mappingFunction) }

    /**
     * Computes a new value for the specified key if it is already present.
     *
     * ⚠️ **DEADLOCK WARNING**: Your remappingFunction runs while holding a write lock on this TreeMap.
     * **DO NOT** call any suspend TreeMap operations (get, put, remove, etc.) inside the lambda,
     * as they will attempt to acquire locks and cause a deadlock.
     *
     * Safe usage:
     * ```kotlin
     * map.computeIfPresent(key) { k, oldValue ->
     *     oldValue.copy(updatedAt = Clock.System.now()) // ✅ Safe - no lock calls
     * }
     * ```
     *
     * **WILL DEADLOCK:**
     * ```kotlin
     * map.computeIfPresent(key) { k, oldValue ->
     *     val parent = map.get(oldValue.parentId)  // ❌ DEADLOCK - tries to acquire read lock
     *     oldValue.copy(parentName = parent?.name)
     * }
     * ```
     *
     * @param key The key whose value is to be computed
     * @param remappingFunction The function to compute a value. Must NOT call suspend TreeMap operations.
     * @return The new value associated with the key, or null if none
     */
    suspend fun computeIfPresent(key: K, remappingFunction: (K, V) -> V?): V? =
        rwLock.withWriteLock { computeIfPresentUnsafe(key, remappingFunction) }

    /**
     * Merges the specified value with the existing value for the specified key using the given remapping function.
     *
     * ⚠️ **DEADLOCK WARNING**: Your remappingFunction runs while holding a write lock on this TreeMap.
     * **DO NOT** call any suspend TreeMap operations (get, put, remove, etc.) inside the lambda,
     * as they will attempt to acquire locks and cause a deadlock.
     *
     * Safe usage:
     * ```kotlin
     * map.merge(key, newValue) { old, new ->
     *     old.copy(count = old.count + new.count) // ✅ Safe - no lock calls
     * }
     * ```
     *
     * **WILL DEADLOCK:**
     * ```kotlin
     * map.merge(key, newValue) { old, new ->
     *     val reference = map.get(refId)  // ❌ DEADLOCK - tries to acquire read lock
     *     old.copy(refData = reference)
     * }
     * ```
     *
     * @param key The key whose value is to be merged
     * @param value The non-null value to merge with the existing value
     * @param remappingFunction The function to merge values. Must NOT call suspend TreeMap operations.
     * @return The new value associated with the key, or null if none
     */
    suspend fun merge(key: K, value: V, remappingFunction: (V, V) -> V?): V? =
        rwLock.withWriteLock { mergeUnsafe(key, value, remappingFunction) }

    // ========================================================================
    // Range Operations (suspend-based)
    // ========================================================================

    suspend fun subMapEntries(fromKey: K?, fromInclusive: Boolean, toKey: K?, toInclusive: Boolean): List<MapEntry<K, V>> =
        rwLock.withReadLock { subMapEntriesUnsafe(fromKey, fromInclusive, toKey, toInclusive) }

    suspend fun headMapEntries(toKey: K): List<MapEntry<K, V>> =
        rwLock.withReadLock { headMapEntriesUnsafe(toKey) }

    suspend fun tailMapEntries(fromKey: K): List<MapEntry<K, V>> =
        rwLock.withReadLock { tailMapEntriesUnsafe(fromKey) }

    // ========================================================================
    // Collection View Operations (suspend-based)
    // ========================================================================

    suspend fun entries(): MutableSet<MapEntry<K, V>> =
        rwLock.withReadLock { entriesUnsafe() }

    override suspend fun keys(): MutableSet<K> =
        rwLock.withReadLock { keysUnsafe() }

    override suspend fun values(): MutableCollection<V> =
        rwLock.withReadLock { valuesUnsafe() }


    // ========================================================================
    // Resilient Iterator - Weakly consistent iteration with deletion tracking
    // ========================================================================
    
    /**
     * Base resilient iterator that provides weakly consistent iteration.
     * Uses deleteModCount to detect deletions and recover position efficiently.
     * 
     * Features:
     * - O(1) normal iteration when no deletions occur
     * - O(log n) recovery only when deletions are detected
     * - Iterator removes route through TreeMap hooks for consistency
     * - Weakly consistent: sees snapshot at creation + recovers from deletions
     */
    private abstract inner class ResilientIterator<T> : MutableIterator<T> {
        protected var expectedDeleteModCount: Int = 0
        protected var nextNode: Node<K, V>? = null
        protected var lastReturnedKey: K? = null
        
        init {
            // Initialize under read lock to capture consistent state
            runBlockingMultiplatform {
                rwLock.withReadLock {
                    expectedDeleteModCount = deleteModCountUnsafe
                    nextNode = firstNodeUnsafe()
                }
            }
        }
        
        override fun hasNext(): Boolean = runBlockingMultiplatform {
            rwLock.withReadLock {
                if (deleteModCountUnsafe != expectedDeleteModCount) {
                    recoverFromDeletions()
                }
                nextNode != null
            }
        }
        
        override fun next(): T = runBlockingMultiplatform {
            rwLock.withReadLock {
                if (deleteModCountUnsafe != expectedDeleteModCount) {
                    recoverFromDeletions()
                }
                
                val node = nextNode ?: throw NoSuchElementException()
                lastReturnedKey = node.key
                nextNode = node.successor()
                
                extractValue(node)
            }
        }
        
        override fun remove() = runBlockingMultiplatform {
            rwLock.withWriteLock {
                val key = lastReturnedKey ?: throw IllegalStateException("next() must be called before remove()")
                removeUnsafe(key)  // Goes through hooks, increments deleteModCount
                expectedDeleteModCount = deleteModCountUnsafe  // Sync after our own change
                lastReturnedKey = null
            }
        }
        
        /**
         * Recovers iterator position after deletions.
         * Uses ceilingNodeUnsafe for efficient O(log n) position recovery.
         */
        private fun recoverFromDeletions() {
            nextNode = nextNode?.key?.let { key ->
                ceilingNodeUnsafe(key)  // Single O(log n) operation
            }
            expectedDeleteModCount = deleteModCountUnsafe
        }
        
        /**
         * Extract the value from a node for this iterator type.
         */
        protected abstract fun extractValue(node: Node<K, V>): T
    }
    
    /**
     * Resilient iterator for entries.
     */
    private inner class ResilientEntryIterator : ResilientIterator<MapEntry<K, V>>() {
        override fun extractValue(node: Node<K, V>): MapEntry<K, V> = MapEntry(node.key, node.value)
    }

    override suspend fun size(): Int =
        rwLock.withReadLock { sizeUnsafe }

    override suspend fun isEmpty(): Boolean =
        rwLock.withReadLock { isEmptyUnsafe }

    suspend fun isNotEmpty(): Boolean =
        rwLock.withReadLock { !isEmptyUnsafe }

    override suspend fun containsValue(value: V): Boolean =
        rwLock.withReadLock { containsValueUnsafe(value) }

    override suspend fun containsKey(key: K): Boolean =
        rwLock.withReadLock { containsKeyUnsafe(key) }

    override suspend fun get(key: K): V? =
        rwLock.withReadLock { getUnsafe(key) }

    override suspend fun put(key: K, value: V): V? =
        rwLock.withWriteLock { putUnsafe(key, value) }

    override suspend fun remove(key: K): V? =
        rwLock.withWriteLock { removeUnsafe(key) }

    override suspend fun putAll(from: Map<out K, V>) =
        rwLock.withWriteLock {
            from.forEach { (key, value) -> putUnsafe(key, value) }
        }

    override suspend fun clear() =
        rwLock.withWriteLock { clearUnsafe() }

    // Extended iteration operations

    override suspend fun forEach(action: suspend (Map.Entry<K, V>) -> Unit) {
        // Collect entries under lock, then iterate outside lock to allow suspend actions
        val entries = rwLock.withReadLock {
            val list = mutableListOf<MapEntry<K, V>>()
            forEachUnsafe { key, value ->
                list.add(MapEntry(key, value))
            }
            list
        }
        entries.forEach { action(it) }
    }

    override suspend fun filter(predicate: suspend (Map.Entry<K, V>) -> Boolean): List<Map.Entry<K, V>> {
        val result = mutableListOf<Map.Entry<K, V>>()
        forEach { entry ->
            if (predicate(entry)) {
                result.add(entry)
            }
        }
        return result
    }

    override suspend fun <R> map(transform: suspend (Map.Entry<K, V>) -> R): List<R> {
        val result = mutableListOf<R>()
        forEach { entry ->
            result.add(transform(entry))
        }
        return result
    }

    override suspend fun <R : Any> mapNotNull(transform: suspend (Map.Entry<K, V>) -> R?): List<R> {
        val result = mutableListOf<R>()
        forEach { entry ->
            transform(entry)?.let { result.add(it) }
        }
        return result
    }

    override suspend fun toList(): List<Map.Entry<K, V>> {
        return rwLock.withReadLock {
            val result = mutableListOf<Map.Entry<K, V>>()
            forEachUnsafe { key, value ->
                result.add(MapEntry(key, value))
            }
            result
        }
    }

    override suspend fun all(predicate: suspend (Map.Entry<K, V>) -> Boolean): Boolean {
        val entries = toList()
        for (entry in entries) {
            if (!predicate(entry)) return false
        }
        return true
    }

    override suspend fun any(predicate: suspend (Map.Entry<K, V>) -> Boolean): Boolean {
        val entries = toList()
        for (entry in entries) {
            if (predicate(entry)) return true
        }
        return false
    }

    override suspend fun none(predicate: suspend (Map.Entry<K, V>) -> Boolean): Boolean {
        return !any(predicate)
    }

    override suspend fun find(predicate: suspend (Map.Entry<K, V>) -> Boolean): Map.Entry<K, V>? {
        val entries = toList()
        for (entry in entries) {
            if (predicate(entry)) return entry
        }
        return null
    }
}