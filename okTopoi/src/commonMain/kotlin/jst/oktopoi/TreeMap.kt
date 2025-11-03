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
 * - Transparent suspend context optimization (with compiler plugin)
 * - Complete NavigableMap API (firstKey, lastKey, lower/floor/ceiling/higher)
 * - Collection views with efficient iterators
 * 
 * The architecture uses a view-based pattern:
 * - UnsafeTreeMapCore: Thread-unsafe core with all algorithms
 * - SuspendTreeMapView: Internal suspend-optimized wrapper (accessed by compiler plugin)
 * - BlockingTreeMapView: Blocking access wrapper for non-suspend contexts
 * - Public API: Delegates to blocking view, transparently optimized in suspend contexts
 * 
 * ## IR Optimization and Final API
 * 
 * - The OkTopoi compiler plugin rewrites direct TreeMap API calls at IR to the appropriate view:
 *   - In suspend contexts: `treeMap.suspend.method(...)`
 *   - In non-suspend contexts: `treeMap.blocking.method(...)`
 * - Public API methods on TreeMap are final to ensure consistent behavior and guarantee that
 *   hook methods (onBefore* / onAfter*) are always invoked through the validated execution path.
 * - Subclasses must customize behavior via the protected unsafe hooks; overriding the public
 *   API is intentionally disallowed.
 * - Unsafe overrides in TreeMap (putUnsafe/removeUnsafe/etc.) are final to preserve invariants
 *   and ensure hooks are always executed. Do not override unsafe methods in subclasses; use hooks.
 * - When the compiler plugin is not applied, the public API still functions correctly by
 *   delegating to the blocking view. For validation, each public API surface logs at debug
 *   level with tag "OkTopoi-TreeMap-Fallback" when the fallback path is executed.
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
) : UnsafeTreeMapCore<K, V>(keyComparator), MutableMap<K, V> {


    /**
     * Internal: log when a direct TreeMap API method is executed at runtime.
     * This indicates the compiler plugin did not redirect the call to a view.
     * Enabled unconditionally at debug level to help validate IR transformations.
     */
    private val log = Logger.withTag("OkTopoi-TreeMap-Fallback")
    
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
    
    
    // View instances (public for direct access and compiler plugin optimization)
    val blocking: BlockingTreeMapView = BlockingTreeMapView()
    val suspend: SuspendTreeMapView = SuspendTreeMapView()
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
    // Public API - Delegates to BlockingTreeMapView
    // ========================================================================
    
    final override val size: Int
        get() {
            log.d { "getSize()" }
            return blocking.size
        }
    
    final override fun isEmpty(): Boolean {
        log.d { "isEmpty()" }
        return blocking.isEmpty()
    }
    
    final override fun containsKey(key: K): Boolean {
        log.d { "containsKey()" }
        return blocking.containsKey(key)
    }
    
    final override fun containsValue(value: V): Boolean {
        log.d { "containsValue()" }
        return blocking.containsValue(value)
    }
    
    final override fun get(key: K): V? {
        log.d { "get()" }
        return blocking.get(key)
    }
    
    final override fun put(key: K, value: V): V? {
        log.d { "put()" }
        return blocking.put(key, value)
    }
    
    final override fun remove(key: K): V? {
        log.d { "remove()" }
        return blocking.remove(key)
    }
    
    final override fun putAll(from: Map<out K, V>) {
        log.d { "putAll()" }
        return blocking.putAll(from)
    }
    
    final override fun clear() {
        log.d { "clear()" }
        return blocking.clear()
    }
    
    final override val keys: MutableSet<K>
        get() {
            log.d { "keys" }
            return blocking.keys
        }
    
    final override val values: MutableCollection<V>
        get() {
            log.d { "values" }
            return blocking.values
        }
    
    final override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() {
            log.d { "entries" }
            return blocking.entries
        }
    
    // NavigableMap operations
    fun firstKey(): K {
        log.d { "firstKey()" }
        return blocking.firstKey()
    }
    fun lastKey(): K {
        log.d { "lastKey()" }
        return blocking.lastKey()
    }
    fun firstEntry(): MapEntry<K, V>? {
        log.d { "firstEntry()" }
        return blocking.firstEntry()
    }
    fun lastEntry(): MapEntry<K, V>? {
        log.d { "lastEntry()" }
        return blocking.lastEntry()
    }
    
    fun lowerKey(key: K): K? {
        log.d { "lowerKey()" }
        return blocking.lowerKey(key)
    }
    fun floorKey(key: K): K? {
        log.d { "floorKey()" }
        return blocking.floorKey(key)
    }
    fun ceilingKey(key: K): K? {
        log.d { "ceilingKey()" }
        return blocking.ceilingKey(key)
    }
    fun higherKey(key: K): K? {
        log.d { "higherKey()" }
        return blocking.higherKey(key)
    }
    
    fun lowerEntry(key: K): MapEntry<K, V>? {
        log.d { "lowerEntry()" }
        return blocking.lowerEntry(key)
    }
    fun floorEntry(key: K): MapEntry<K, V>? {
        log.d { "floorEntry()" }
        return blocking.floorEntry(key)
    }
    fun ceilingEntry(key: K): MapEntry<K, V>? {
        log.d { "ceilingEntry()" }
        return blocking.ceilingEntry(key)
    }
    fun higherEntry(key: K): MapEntry<K, V>? {
        log.d { "higherEntry()" }
        return blocking.higherEntry(key)
    }
    
    fun pollFirstEntry(): MapEntry<K, V>? {
        log.d { "pollFirstEntry()" }
        return blocking.pollFirstEntry()
    }
    fun pollLastEntry(): MapEntry<K, V>? {
        log.d { "pollLastEntry()" }
        return blocking.pollLastEntry()
    }

    fun getBy(vararg criteria: Pair<String, Any?>): Collection<V> {
        log.d { "getBy()" }
        return blocking.getBy(*criteria)
    }

    // Secondary index operations
    fun containsSecondaryKey(indexName: String, secondaryKey: Any?): Boolean {
        log.d { "containsSecondaryKey()" }
        return blocking.containsSecondaryKey(indexName, secondaryKey)
    }
    
    fun getSecondaryKeys(indexName: String): Set<Any?> {
        log.d { "getSecondaryKeys()" }
        return blocking.getSecondaryKeys(indexName)
    }
    
    fun getSecondaryIndexNames(): Set<String> {
        log.d { "getSecondaryIndexNames()" }
        return blocking.getSecondaryIndexNames()
    }

    fun forEachBy(vararg criteria: Pair<String, Any?>, action: (key: K, value: V) -> Unit) {
        log.d { "forEachBy()" }
        return blocking.forEachBy(*criteria, action = action)
    }
    
    // Modern Map API
    fun putIfAbsent(key: K, value: V): V? {
        log.d { "putIfAbsent()" }
        return blocking.putIfAbsent(key, value)
    }
    fun replace(key: K, value: V): V? {
        log.d { "replace(key, value)" }
        return blocking.replace(key, value)
    }
    fun replace(key: K, oldValue: V, newValue: V): Boolean {
        log.d { "replace(key, oldValue, newValue)" }
        return blocking.replace(key, oldValue, newValue)
    }
    
    fun compute(key: K, remappingFunction: (K, V?) -> V?): V? {
        log.d { "compute()" }
        return blocking.compute(key, remappingFunction)
    }
    fun computeIfAbsent(key: K, mappingFunction: (K) -> V?): V? {
        log.d { "computeIfAbsent()" }
        return blocking.computeIfAbsent(key, mappingFunction)
    }
    fun computeIfPresent(key: K, remappingFunction: (K, V) -> V?): V? {
        log.d { "computeIfPresent()" }
        return blocking.computeIfPresent(key, remappingFunction)
    }
    fun merge(key: K, value: V, remappingFunction: (V, V) -> V?): V? {
        log.d { "merge()" }
        return blocking.merge(key, value, remappingFunction)
    }
    
    // Range operations
    fun subMapEntries(fromKey: K?, fromInclusive: Boolean, toKey: K?, toInclusive: Boolean): List<MapEntry<K, V>> {
        log.d { "subMapEntries()" }
        return blocking.subMapEntries(fromKey, fromInclusive, toKey, toInclusive)
    }
    
    fun headMapEntries(toKey: K): List<MapEntry<K, V>> {
        log.d { "headMapEntries()" }
        return blocking.headMapEntries(toKey)
    }
    fun tailMapEntries(fromKey: K): List<MapEntry<K, V>> {
        log.d { "tailMapEntries()" }
        return blocking.tailMapEntries(fromKey)
    }
    
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
    inner class SuspendTreeMapView {
        
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
        
        suspend fun size(): Int = rwLock.withReadLock { sizeUnsafe }
        suspend fun isEmpty(): Boolean = rwLock.withReadLock { isEmptyUnsafe }
        
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
            rwLock.withReadLock { containsSecondaryKeyUnsafe(indexName, secondaryKey) }

        suspend fun getSecondaryKeys(indexName: String): Set<Any?> =
            rwLock.withReadLock { getSecondaryKeysUnsafe(indexName) }
        
        suspend fun getSecondaryIndexNames(): Set<String> = 
            rwLock.withReadLock { getSecondaryIndexNamesUnsafe() }

        suspend fun forEachBy(vararg criteria: Pair<String, Any?>, action: (key: K, value: V) -> Unit) =
            rwLock.withReadLock { forEachKeyByUnsafe(*criteria, action = { action.invoke(it, getUnsafe(it)!!) }) }

        suspend fun getBy(vararg criteria: Pair<String, Any?>): Collection<V> =
            rwLock.withReadLock { getByUnsafe(*criteria) }
        
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

        suspend fun entries(): MutableSet<MapEntry<K, V>> {
            return rwLock.withReadLock { entriesUnsafe() }
        }

        suspend fun keys(): MutableSet<K> {
            return rwLock.withReadLock { keysUnsafe() }
        }

        suspend fun values(): MutableCollection<V> {
            return rwLock.withReadLock { valuesUnsafe() }
        }
    }
    
    // ========================================================================
    // BlockingTreeMapView - Blocking access wrapper with runBlockingMultiplatform
    // ========================================================================
    
    /**
     * Blocking view for non-suspend contexts.
     * Uses runBlockingMultiplatform to wrap suspend operations for thread-safe access.
     */
    inner class BlockingTreeMapView : MutableMap<K, V> {
        
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

        // Collection views - use resilient wrappers for hook consistency and safe iteration
        override val keys: MutableSet<K> 
            get() = ResilientKeySet()
        
        override val values: MutableCollection<V> 
            get() = ResilientValueCollection()
        
        override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
            get() = ResilientEntrySet()
        
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
            runBlockingMultiplatform { rwLock.withReadLock { containsSecondaryKeyUnsafe(indexName, secondaryKey) } }

        fun getSecondaryKeys(indexName: String): Set<Any?> =
            runBlockingMultiplatform { rwLock.withReadLock { getSecondaryKeysUnsafe(indexName) } }

        fun getSecondaryIndexNames(): Set<String> = 
            runBlockingMultiplatform { rwLock.withReadLock { getSecondaryIndexNamesUnsafe() } }

        fun forEachBy(vararg criteria: Pair<String, Any?>, action: (key: K, value: V) -> Unit) =
            runBlockingMultiplatform { rwLock.withReadLock { forEachKeyByUnsafe(*criteria, action = { action.invoke(it, getUnsafe(it)!!) }) } }

        fun getBy(vararg criteria: Pair<String, Any?>): Collection<V> =
            runBlockingMultiplatform { rwLock.withReadLock { getByUnsafe(*criteria) } }
        
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
     * Resilient iterator for keys.
     */
    private inner class ResilientKeyIterator : ResilientIterator<K>() {
        override fun extractValue(node: Node<K, V>): K = node.key
    }
    
    /**
     * Resilient iterator for values.
     */
    private inner class ResilientValueIterator : ResilientIterator<V>() {
        override fun extractValue(node: Node<K, V>): V = node.value
    }
    
    /**
     * Resilient iterator for entries.
     */
    private inner class ResilientEntryIterator : ResilientIterator<MapEntry<K, V>>() {
        override fun extractValue(node: Node<K, V>): MapEntry<K, V> = MapEntry(node.key, node.value)
    }
    
    // ========================================================================
    // Resilient Collection Wrappers - Hook-aware collections with safe iteration
    // ========================================================================
    
    /**
     * Wrapper for keys that uses resilient iterators and routes mutations through hooks.
     */
    private inner class ResilientKeySet : MutableSet<K> {
        override fun iterator(): MutableIterator<K> = ResilientKeyIterator()
        
        // Size/query operations - delegate to unsafe under read lock
        override val size: Int get() = runBlockingMultiplatform {
            rwLock.withReadLock { sizeUnsafe }
        }
        override fun isEmpty(): Boolean = runBlockingMultiplatform {
            rwLock.withReadLock { isEmptyUnsafe }
        }
        override fun contains(element: K): Boolean = runBlockingMultiplatform {
            rwLock.withReadLock { containsKeyUnsafe(element) }
        }
        override fun containsAll(elements: Collection<K>): Boolean = runBlockingMultiplatform {
            rwLock.withReadLock { elements.all { containsKeyUnsafe(it) } }
        }
        
        // Mutation operations - route through TreeMap hooks
        override fun remove(element: K): Boolean = runBlockingMultiplatform {
            rwLock.withWriteLock { removeUnsafe(element) != null }
        }
        override fun removeAll(elements: Collection<K>): Boolean = runBlockingMultiplatform {
            rwLock.withWriteLock {
                var changed = false
                elements.forEach { if (removeUnsafe(it) != null) changed = true }
                changed
            }
        }
        override fun retainAll(elements: Collection<K>): Boolean = runBlockingMultiplatform {
            rwLock.withWriteLock {
                var changed = false
                forEachUnsafe { key, _ ->
                    if (key !in elements) {
                        if (removeUnsafe(key) != null) changed = true
                    }
                }
                changed
            }
        }
        override fun clear() = runBlockingMultiplatform {
            rwLock.withWriteLock { clearUnsafe() }
        }
        
        // Unsupported operations for key sets
        override fun add(element: K): Boolean = 
            throw UnsupportedOperationException("Cannot add to key set")
        override fun addAll(elements: Collection<K>): Boolean = 
            throw UnsupportedOperationException("Cannot add to key set")
    }
    
    /**
     * Wrapper for values that uses resilient iterators and routes mutations through hooks.
     */
    private inner class ResilientValueCollection : MutableCollection<V> {
        override fun iterator(): MutableIterator<V> = ResilientValueIterator()
        
        // Size/query operations - delegate to unsafe under read lock
        override val size: Int get() = runBlockingMultiplatform {
            rwLock.withReadLock { sizeUnsafe }
        }
        override fun isEmpty(): Boolean = runBlockingMultiplatform {
            rwLock.withReadLock { isEmptyUnsafe }
        }
        override fun contains(element: V): Boolean = runBlockingMultiplatform {
            rwLock.withReadLock { containsValueUnsafe(element) }
        }
        override fun containsAll(elements: Collection<V>): Boolean = runBlockingMultiplatform {
            rwLock.withReadLock { elements.all { containsValueUnsafe(it) } }
        }
        
        // Mutation operations - route through TreeMap hooks
        override fun remove(element: V): Boolean = runBlockingMultiplatform {
            rwLock.withWriteLock {
                // Find FIRST entry with this value and remove it (Java Collections behavior)
                val entry = findEntryByValueUnsafe { it == element }
                if (entry != null) {
                    removeUnsafe(entry.key) != null
                } else {
                    false
                }
            }
        }
        override fun removeAll(elements: Collection<V>): Boolean = runBlockingMultiplatform {
            rwLock.withWriteLock {
                var changed = false
                elements.forEach { if (remove(it)) changed = true }
                changed
            }
        }
        override fun retainAll(elements: Collection<V>): Boolean = runBlockingMultiplatform {
            rwLock.withWriteLock {
                var changed = false
                forEachUnsafe { key, value ->
                    if (value !in elements) {
                        if (removeUnsafe(key) != null) changed = true
                    }
                }
                changed
            }
        }
        override fun clear() = runBlockingMultiplatform {
            rwLock.withWriteLock { clearUnsafe() }
        }
        
        // Unsupported operations for value collections
        override fun add(element: V): Boolean = 
            throw UnsupportedOperationException("Cannot add to value collection")
        override fun addAll(elements: Collection<V>): Boolean = 
            throw UnsupportedOperationException("Cannot add to value collection")
    }
    
    /**
     * Wrapper for entries that uses resilient iterators and routes mutations through hooks.
     */
    private inner class ResilientEntrySet : MutableSet<MutableMap.MutableEntry<K, V>> {
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> = 
            ResilientEntryIterator() as MutableIterator<MutableMap.MutableEntry<K, V>>
        
        // Size/query operations - delegate to unsafe under read lock
        override val size: Int get() = runBlockingMultiplatform {
            rwLock.withReadLock { sizeUnsafe }
        }
        override fun isEmpty(): Boolean = runBlockingMultiplatform {
            rwLock.withReadLock { isEmptyUnsafe }
        }
        override fun contains(element: MutableMap.MutableEntry<K, V>): Boolean = runBlockingMultiplatform {
            rwLock.withReadLock {
                val value = getUnsafe(element.key)
                value != null && value == element.value
            }
        }
        override fun containsAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean = runBlockingMultiplatform {
            rwLock.withReadLock { elements.all { contains(it) } }
        }
        
        // Mutation operations - route through TreeMap hooks
        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = runBlockingMultiplatform {
            rwLock.withWriteLock {
                val oldValue = putUnsafe(element.key, element.value)
                oldValue == null
            }
        }
        override fun addAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean = runBlockingMultiplatform {
            rwLock.withWriteLock {
                var changed = false
                elements.forEach { if (add(it)) changed = true }
                changed
            }
        }
        override fun remove(element: MutableMap.MutableEntry<K, V>): Boolean = runBlockingMultiplatform {
            rwLock.withWriteLock {
                val currentValue = getUnsafe(element.key)
                if (currentValue == element.value) {
                    removeUnsafe(element.key) != null
                } else {
                    false
                }
            }
        }
        override fun removeAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean = runBlockingMultiplatform {
            rwLock.withWriteLock {
                var changed = false
                elements.forEach { if (remove(it)) changed = true }
                changed
            }
        }
        override fun retainAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean = runBlockingMultiplatform {
            rwLock.withWriteLock {
                var changed = false
                forEachUnsafe { key, value ->
                    if (MapEntry<K, V>(key, value) !in elements) {
                        if (removeUnsafe(key) != null) changed = true
                    }
                }
                changed
            }
        }
        override fun clear() = runBlockingMultiplatform {
            rwLock.withWriteLock { clearUnsafe() }
        }
    }
}