package jst.oktopoi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.concurrent.Volatile

/**
 * A Red-Black Tree based TreeMap implementation fo¸r Kotlin Multiplatform
 *
 * This implementation provides a sorted map with O(log n) performance for basic operations,
 * NavigableMap-like functionality, secondary key indexing, and reactive change notifications.
 *
 * ## Features
 * - **Performance**: O(log n) time complexity for get, put, remove operations
 * - **Sorted Access**: Keys are maintained in sorted order with efficient range operations
 * - **Navigation**: NavigableMap-like functionality (floor, ceiling, subMap, etc.)
 * - **Secondary Keys**: Optional O(1) lookups by alternate keys
 * - **Reactive**: Change notifications via Kotlin coroutines SharedFlow
 * - **Multiplatform**: Full Kotlin Multiplatform compatibility
 *
 * ## Thread Safety
 * This implementation is **thread-safe** using ReadWriteLock for concurrent access.
 * - Reads can proceed concurrently
 * - Writes are exclusive
 * - Suspend API provides optimal performance
 * - Blocking API provides compatibility
 *
 * ## Null Handling
 * - **Null Keys**: Not permitted.
 * - **Null Values**: Not permitted
 * - **Secondary Keys**: Null secondary key values are permitted and indexed
 *
 * @param K the type of primary keys (must be non-null, Comparable)
 * @param V the type of mapped values (nullable allowed)
 */
open class TreeMap<K, V> : MutableMap<K, V> {

    internal val rwLock = ReadWriteLock()
    val comparator: Comparator<K>?
    val secondaryKeysSpec: List<Key<V, *>>

    constructor(
        comparator: Comparator<K>? = null,
        secondaryKeys: SecondaryKeyBuilder<V>.() -> Unit = {}
    ) {
        this.comparator = comparator
        val builder = SecondaryKeyBuilder<V>()
        builder.secondaryKeys()
        this.secondaryKeysSpec = builder.build()

        this.secondaryIndexes = mutableMapOf<String, MutableMap<Any?, MutableSet<K>>>()
        secondaryKeysSpec.forEach { spec ->
            secondaryIndexes[spec.name] = mutableMapOf()
        }
        this._changes = MutableSharedFlow<MapChange<K, V>>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
        )
        this.changes = _changes.asSharedFlow()
    }

    internal val secondaryIndexes: MutableMap<String, MutableMap<Any?, MutableSet<K>>>

    /**
     * Builder for creating secondary key configurations in a fluent way
     */
    class SecondaryKeyBuilder<V> {
        private val keys = mutableListOf<Key<V, *>>()

        /**
         * Adds a secondary key with the given name and extractor function
         *
         * @param name unique name for this secondary key
         * @param extractor function that extracts the secondary key value from the main value
         */
        fun <SK> key(name: String, extractor: (V) -> SK) {
            require(keys.none { it.name == name }) { "Duplicate secondary key name: $name" }
            keys.add(Key(name, extractor))
        }

        /**
         * Builds the final list of secondary keys
         */
        internal fun build(): List<Key<V, *>> = keys.toList()
    }


    /**
     * Suspend-aware iterator interface for optimal performance in coroutine contexts.
     */
    interface SuspendIterator<out T> {
        suspend fun hasNext(): Boolean
        suspend fun next(): T
    }

    /**
     * Suspend-aware mutable iterator interface for optimal performance in coroutine contexts.
     */
    interface SuspendMutableIterator<T> : SuspendIterator<T> {
        suspend fun remove()
    }

    /**
     * Color enum for Red-Black Tree nodes
     */
    internal enum class Color { RED, BLACK }

    /**
     * Represents a secondary key configuration
     */
    data class Key<V, SK>(
        val name: String,
        val keyExtractor: (V) -> SK
    )

    // Modification count for concurrent modification detection
    @Volatile
    private var modCount = 0L

    /**
     * Sealed class representing different types of changes that can occur to the TreeMap
     */
    sealed class MapChange<K, V> {
        /**
         * Represents a put operation (insert or update)
         */
        data class Put<K, V>(
            val key: K,
            val value: V,
            val isUpdate: Boolean,
            val oldValue: V?
        ) : MapChange<K, V>()

        /**
         * Represents a remove operation
         */
        data class Removed<K, V>(
            val key: K,
            val oldValue: V
        ) : MapChange<K, V>()

        /**
         * Represents a clear operation that removed all entries
         */
        class Cleared<K, V>() : MapChange<K, V>()

        /**
         * Represents a bulk operation completion
         */
        class Rebuild<K, V>() : MapChange<K, V>()
    }

    // Use MutableSharedFlow with overflow handling
    private val _changes: MutableSharedFlow<MapChange<K, V>>

    /**
     * SharedFlow that emits changes to the TreeMap.
     *
     * The flow uses DROP_OLDEST overflow strategy to prevent memory issues.
     * If you need guaranteed delivery, use a larger changeBufferSize in configuration.
     */
    val changes: SharedFlow<MapChange<K, V>>

    // Helper to emit changes
    private fun emitChange(change: MapChange<K, V>) {
        _changes.tryEmit(change)
    }

    /**
     * Gets all values by a secondary key.
     *
     * Performs O(1) lookup in the secondary index, then O(k) retrieval where k is the number
     * of matching primary keys. Automatically cleans up stale index entries if found.
     *
     * @param keyName the name of the secondary key
     * @param keyValue the secondary key value to search for
     * @return list of matching values (empty if none found)
     */
    fun <SK> getBySecondaryKey(keyName: String, keyValue: SK): List<V> = 
        runBlockingMultiplatform { getBySecondaryKeySuspend(keyName, keyValue) }

    /**
     * Suspend version of getBySecondaryKey() for optimal performance in coroutine contexts.
     * Uses read lock for safe concurrent access to secondary indexes.
     * All primary key lookups occur under a single lock for efficiency.
     */
    suspend fun <SK> getBySecondaryKeySuspend(keyName: String, keyValue: SK): List<V> = 
        rwLock.withReadLock {
            val primaryKeys = secondaryIndexes[keyName]?.get(keyValue) ?: emptySet()
            primaryKeys.mapNotNull { findNode(it)?.value }
        }

    // Modern Map API additions

    /**
     * If the specified key is not already associated with a value, associates it with the given value.
     *
     * @return the previous value associated with the key, or null if there was no mapping
     */
    fun putIfAbsent(key: K, value: V): V? = runBlockingMultiplatform { putIfAbsentSuspend(key, value) }

    /**
     * Suspend version of putIfAbsent() for optimal performance in coroutine contexts.
     */
    suspend fun putIfAbsentSuspend(key: K, value: V): V? = rwLock.withWriteLock {
        val node = findNode(key)
        if (node == null) {
            updateSecondaryKeys(key, findOrCreateNodeAndPut(key, value).first, value)
            emitChange(MapChange.Put(key, value, false, null))
            null
        } else {
            node.value
        }
    }

    /**
     * Replaces the entry for the specified key only if it is currently mapped to some value.
     *
     * @return the previous value associated with the key, or null if there was no mapping
     */
    fun replace(key: K, value: V): V? = runBlockingMultiplatform { replaceSuspend(key, value) }

    /**
     * Suspend version of replace() for optimal performance in coroutine contexts.
     */
    suspend fun replaceSuspend(key: K, value: V): V? = rwLock.withWriteLock {
        val node = findNode(key)
        if (node != null) {
            val oldValue = node.value
            node.value = value
            updateSecondaryKeys(key, oldValue, value)
            emitChange(MapChange.Put(key, value, true, oldValue))
            oldValue
        } else {
            null
        }
    }

    /**
     * Replaces the entry for the specified key only if currently mapped to the specified value.
     *
     * @return true if the value was replaced
     */
    fun replace(key: K, expectedValue: V, newValue: V): Boolean = runBlockingMultiplatform { replaceSuspend(key, expectedValue, newValue) }

    /**
     * Suspend version of replace() for optimal performance in coroutine contexts.
     */
    suspend fun replaceSuspend(key: K, expectedValue: V, newValue: V): Boolean = rwLock.withWriteLock {
        val node = findNode(key)
        if (node != null && node.value == expectedValue) {
            val oldValue = node.value
            node.value = newValue
            updateSecondaryKeys(key, oldValue, newValue)
            emitChange(MapChange.Put(key, newValue, true, oldValue))
            true
        } else {
            false
        }
    }

    /**
     * Removes the entry for the specified key only if it is currently mapped to the specified value.
     *
     * @return true if the value was removed
     */
    fun remove(key: K, value: V): Boolean = runBlockingMultiplatform { removeSuspend(key, value) }

    /**
     * Suspend version of remove() for optimal performance in coroutine contexts.
     */
    suspend fun removeSuspend(key: K, value: V): Boolean = rwLock.withWriteLock {
        val node = findNode(key) ?: return@withWriteLock false
        if (node.value == value) {
            nodeRemove(node)
            true
        } else {
            false
        }
    }

    /**
     * Computes a mapping for the specified key and its current mapped value (or null if no mapping).
     *
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the key, or null if none
     */
    fun compute(key: K, remappingFunction: (K, V?) -> V?): V? = runBlockingMultiplatform { computeSuspend(key, remappingFunction) }

    /**
     * Suspend version of compute() for optimal performance in coroutine contexts.
     */
    suspend fun computeSuspend(key: K, remappingFunction: (K, V?) -> V?): V? = rwLock.withWriteLock {
        val node = findNode(key)
        val currentValue = node?.value
        val newValue = remappingFunction(key, currentValue)
        
        when {
            node == null && newValue != null -> {
                // Insert new mapping
                updateSecondaryKeys(key, findOrCreateNodeAndPut(key, newValue).first, newValue)
                emitChange(MapChange.Put(key, newValue, false, null))
            }
            node != null && newValue != null -> {
                // Update existing mapping
                val oldValue = node.value
                node.value = newValue
                updateSecondaryKeys(key, oldValue, newValue)
                emitChange(MapChange.Put(key, newValue, true, oldValue))
            }
            node != null && newValue == null -> {
                // Remove existing mapping
                nodeRemove(node)
            }
            // node == null && newValue == null: no operation needed
        }
        
        newValue
    }

    /**
     * If the specified key is not already associated with a value, computes its value using the mapping function.
     *
     * @param mappingFunction the function to compute a value
     * @return the current (existing or computed) value associated with the key
     */
    fun computeIfAbsent(key: K, mappingFunction: (K) -> V): V = runBlockingMultiplatform { computeIfAbsentSuspend(key, mappingFunction) }

    /**
     * Suspend version of computeIfAbsent() for optimal performance in coroutine contexts.
     */
    suspend fun computeIfAbsentSuspend(key: K, mappingFunction: (K) -> V): V = rwLock.withWriteLock {
        val node = findNode(key)
        if (node == null) {
            val newValue = mappingFunction(key)
            updateSecondaryKeys(key, findOrCreateNodeAndPut(key, newValue).first, newValue)
            emitChange(MapChange.Put(key, newValue, false, null))
            newValue
        } else {
            node.value
        }
    }

    /**
     * If the value for the specified key is present, computes a new mapping given the key and its current value.
     *
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the key, or null if none
     */
    fun computeIfPresent(key: K, remappingFunction: (K, V) -> V): V? = runBlockingMultiplatform { computeIfPresentSuspend(key, remappingFunction) }

    /**
     * Suspend version of computeIfPresent() for optimal performance in coroutine contexts.
     */
    suspend fun computeIfPresentSuspend(key: K, remappingFunction: (K, V) -> V): V? = rwLock.withWriteLock {
        val node = findNode(key)
        if (node != null) {
            val oldValue = node.value
            val newValue = remappingFunction(key, oldValue)
            node.value = newValue
            updateSecondaryKeys(key, oldValue, newValue)
            emitChange(MapChange.Put(key, newValue, true, oldValue))
            newValue
        } else {
            null
        }
    }

    /**
     * If the specified key is not already associated with a value or is associated with null,
     * associates it with the given non-null value.
     *
     * @param value the non-null value to be associated with the specified key
     * @param remappingFunction the function to recompute a value if present
     * @return the new value associated with the key
     */
    fun merge(key: K, value: V, remappingFunction: (oldValue: V?, newValue: V) -> V?): V? = runBlockingMultiplatform { mergeSuspend(key, value, remappingFunction) }

    /**
     * Suspend version of merge() for optimal performance in coroutine contexts.
     */
    suspend fun mergeSuspend(key: K, value: V, remappingFunction: (oldValue: V?, newValue: V) -> V?): V? = rwLock.withWriteLock {
        val node = findNode(key)
        val currentValue = node?.value
        val newValue = remappingFunction(currentValue, value)
        
        when {
            node == null && newValue != null -> {
                // Insert new mapping
                updateSecondaryKeys(key, findOrCreateNodeAndPut(key, newValue).first, newValue)
                emitChange(MapChange.Put(key, newValue, false, null))
            }
            node != null && newValue != null -> {
                // Update existing mapping
                val oldValue = node.value
                node.value = newValue
                updateSecondaryKeys(key, oldValue, newValue)
                emitChange(MapChange.Put(key, newValue, true, oldValue))
            }
            node != null && newValue == null -> {
                // Remove existing mapping
                nodeRemove(node)
            }
            // node == null && newValue == null: no operation needed
        }
        
        newValue
    }

    private fun updateSecondaryKeys(key: K, oldValue: V?, newValue: V?) {
        if (secondaryKeysSpec.isNotEmpty()) {
            if (oldValue != null && newValue != null) {
                secondaryKeysSpec.forEach { spec ->
                    val newSecondaryKey = spec.keyExtractor(newValue)
                    val oldSecondaryKey = spec.keyExtractor(oldValue)
                    if (oldSecondaryKey != newSecondaryKey) {
                        val index = secondaryIndexes[spec.name]!!

                        val oldSet = index[oldSecondaryKey]
                        if (oldSet != null) {
                            oldSet.remove(key)
                            if (oldSet.isEmpty()) {
                                index.remove(oldSecondaryKey)
                            }
                        }

                        index.getOrPut(newSecondaryKey) { mutableSetOf() }.add(key)
                    }
                }
            } else if (oldValue != null) {
                secondaryKeysSpec.forEach { spec ->
                    val oldSecondaryKey = spec.keyExtractor(oldValue)
                    val index = secondaryIndexes[spec.name]!!

                    val oldSet = index[oldSecondaryKey]
                    if (oldSet != null) {
                        oldSet.remove(key)
                        if (oldSet.isEmpty()) {
                            index.remove(oldSecondaryKey)
                        }
                    }
                }
            } else if (newValue != null) {
                secondaryKeysSpec.forEach { spec ->
                    val newSecondaryKey = spec.keyExtractor(newValue)

                    val index = secondaryIndexes[spec.name]!!

                    index.getOrPut(newSecondaryKey) { mutableSetOf() }.add(key)
                }
            }

        }
    }

    // Tree node implementation
    internal class Node<K, V>(
        var key: K,
        var value: V,
        var color: Color = Color.RED,
        var left: Node<K, V>? = null,
        var right: Node<K, V>? = null,
        var parent: Node<K, V>? = null
    ) {
        override fun toString(): String = "Node(key=$key, value=$value, color=$color)"
    }

    internal var root: Node<K, V>? = null
    private var _size = 0

    override val size: Int get() = runBlockingMultiplatform { sizeSuspend() }
    override val keys: MutableSet<K> get() = runBlockingMultiplatform { keysSuspend() }
    override val values: MutableCollection<V> get() = runBlockingMultiplatform { valuesSuspend() }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = runBlockingMultiplatform { entriesSuspend() }
    
    // Suspend versions for coroutine contexts
    suspend fun sizeSuspend(): Int = rwLock.withReadLock { _size }
    suspend fun keysSuspend(): MutableSet<K> = KeySet()
    suspend fun valuesSuspend(): MutableCollection<V> = ValueCollection()
    suspend fun entriesSuspend(): MutableSet<MutableMap.MutableEntry<K, V>> = EntrySet()

    /**
     * Returns a suspend-aware key iterator for optimal performance in coroutine contexts.
     */
    fun keysSuspendIterator(): SuspendMutableIterator<K> = SuspendKeyIterator()

    /**
     * Returns a suspend-aware value iterator for optimal performance in coroutine contexts.
     */
    fun valuesSuspendIterator(): SuspendMutableIterator<V> = SuspendValueIterator()

    /**
     * Returns a suspend-aware entry iterator for optimal performance in coroutine contexts.
     */
    fun entriesSuspendIterator(): SuspendMutableIterator<MutableMap.MutableEntry<K, V>> = SuspendEntryIterator()

    /**
     * Gets the color of a node (null nodes are black)
     */
    private fun getNodeColor(node: Node<K, V>?): Color {
        return node?.color ?: Color.BLACK
    }

    // Compares keys using configured comparator or natural ordering
    private fun compare(k1: K, k2: K): Int {
        @Suppress("UNCHECKED_CAST") // because K is enforced to be comparable by ES creator functions if custom comparator is null
        return comparator?.compare(k1, k2) ?: (k1 as Comparable<K>).compareTo(k2)
    }

    /**
     * Associates the specified value with the specified key in this map.
     *
     * **Performance**: O(log n) for tree operations, plus O(k) for secondary key updates
     * where k is the number of configured secondary keys.
     *
     * @param key primary key (must be non-null)
     * @param value value to associate with key (null allowed)
     * @return previous value associated with key, or null if no previous mapping
     */
    override fun put(key: K, value: V): V? = runBlockingMultiplatform { putSuspend(key, value) }

    /**
     * Suspend version of put() for optimal performance in coroutine contexts.
     * Uses write lock for exclusive access during tree modifications.
     */
    suspend fun putSuspend(key: K, value: V): V? = rwLock.withWriteLock {
        val oldValueOldNode = findOrCreateNodeAndPut(key, value)

        updateSecondaryKeys(key, oldValueOldNode.first, value)

        emitChange(
            MapChange.Put(
                key = key,
                value = value,
                isUpdate = oldValueOldNode.second != null && oldValueOldNode.first != value,
                oldValue = oldValueOldNode.first
            )
        )

        oldValueOldNode.first
    }


    private fun nodePut(node: Node<K, V>, key: K, newValue: V): V {
        val oldValue = node.value
        node.value = newValue

        updateSecondaryKeys(key, oldValue, newValue)

        emitChange(
            MapChange.Put(
                key = key,
                value = newValue,
                isUpdate = true,
                oldValue = oldValue
            )
        )

        return oldValue
    }

    private fun findOrCreateNodeAndPut(key: K, value: V): Pair<V?, Node<K, V>?> {
        if (root == null) {
            modCount++
            root = Node(key, value, Color.BLACK)
            _size++
            return null to null
        }

        var current = root!!
        while (true) {
            val cmp = compare(key, current.key)
            when {
                cmp < 0 -> {
                    if (current.left == null) {
                        modCount++
                        val newNode = Node<K, V>(key, value, Color.RED, parent = current)
                        current.left = newNode
                        _size++
                        fixInsert(newNode)
                        return null to null
                    }
                    current = current.left!!
                }

                cmp > 0 -> {
                    if (current.right == null) {
                        modCount++
                        val newNode = Node<K, V>(key, value, Color.RED, parent = current)
                        current.right = newNode
                        _size++
                        fixInsert(newNode)
                        return null to null
                    }
                    current = current.right!!
                }

                else -> {
                    // Update existing node
                    val oldValue = current.value
                    current.value = value
                    return oldValue to current
                }
            }
        }
    }

    /**
     * Returns the value to which the specified key is mapped, or null if this map contains no mapping for the key.
     */
    override fun get(key: K): V? = runBlockingMultiplatform { getSuspend(key) }

    /**
     * Suspend version of get() for optimal performance in coroutine contexts.
     * Uses read lock allowing concurrent access.
     */
    suspend fun getSuspend(key: K): V? = rwLock.withReadLock {
        findNode(key)?.value
    }

    /**
     * Removes the mapping for the specified key from this map if present.
     *
     * @param key the key whose mapping is to be removed
     * @return the previous value associated with key, or null if no mapping existed
     */
    override fun remove(key: K): V? = runBlockingMultiplatform { removeSuspend(key) }

    /**
     * Suspend version of remove() for optimal performance in coroutine contexts.
     * Uses write lock for exclusive access during tree modifications.
     */
    suspend fun removeSuspend(key: K): V? = rwLock.withWriteLock {
        val node = findNode(key) ?: return@withWriteLock null
        nodeRemove(node)
    }

    private fun nodeRemove(node: Node<K, V>): V {
        val oldValue = node.value

        modCount++
        deleteNode(node)
        _size--

        updateSecondaryKeys(node.key, oldValue, null)

        emitChange(MapChange.Removed(node.key, oldValue))

        return oldValue
    }

    /**
     * Returns true if this map contains a mapping for the specified key.
     */
    override fun containsKey(key: K): Boolean = runBlockingMultiplatform { containsKeySuspend(key) }

    /**
     * Suspend version of containsKey() for optimal performance in coroutine contexts.
     */
    suspend fun containsKeySuspend(key: K): Boolean = rwLock.withReadLock {
        findNode(key) != null
    }

    /**
     * Returns true if this map maps one or more keys to the specified value.
     */
    override fun containsValue(value: V): Boolean = runBlockingMultiplatform { containsValueSuspend(value) }

    /**
     * Suspend version of containsValue() for optimal performance in coroutine contexts.
     */
    suspend fun containsValueSuspend(value: V): Boolean = rwLock.withReadLock {
        var current = minimum(root)
        while (current != null) {
            if (current.value == value) return@withReadLock true
            current = successor(current)
        }
        false
    }

    /**
     * Returns true if this map contains no key-value mappings.
     */
    override fun isEmpty(): Boolean = runBlockingMultiplatform { isEmptySuspend() }
    
    suspend fun isEmptySuspend(): Boolean = rwLock.withReadLock { _size == 0 }

    /**
     * Removes all mappings from this map.
     *
     * Clears both the primary tree structure and all secondary indexes.
     * Emits a Cleared change event.
     *
     * **Performance**: O(1) for tree, O(k*s) for secondary indexes where k is number of
     * secondary keys and s is number of unique secondary key values.
     */
    override fun clear() = runBlockingMultiplatform { clearSuspend() }

    /**
     * Suspend version of clear() for optimal performance in coroutine contexts.
     * Uses write lock for exclusive access during clear operation.
     */
    suspend fun clearSuspend() = rwLock.withWriteLock {
        modCount++
        root = null
        _size = 0

        // Clear secondary indexes
        if (secondaryKeysSpec.isNotEmpty()) {
            secondaryIndexes.values.forEach { it.clear() }
        }

        emitChange(MapChange.Cleared())
    }

    /**
     * Copies all of the mappings from the specified map to this map.
     * Only emits a single Rebuild event, not individual Put events.
     *
     * @param from mappings to be stored in this map
     */
    override fun putAll(from: Map<out K, V>) = runBlockingMultiplatform { putAllSuspend(from) }

    /**
     * Suspend version of putAll() for optimal performance in coroutine contexts.
     * Uses write lock for exclusive access during batch operation.
     * All operations occur under a single lock for efficiency.
     */
    suspend fun putAllSuspend(from: Map<out K, V>) = rwLock.withWriteLock {
        from.forEach { (key, value) ->
            updateSecondaryKeys(
                key,
                findOrCreateNodeAndPut(key, value).first,
                value
            )
        }
        emitChange(MapChange.Rebuild())
    }

    // Suspend batch operation methods for efficient operations under single lock
    
    /**
     * Suspend version of putAll with varargs for convenient batch operations.
     * All operations occur under a single write lock for efficiency.
     */
    open suspend fun putAllSuspend(vararg entries: Pair<K, V>) = rwLock.withWriteLock {
        entries.forEach { (key, value) ->
            updateSecondaryKeys(
                key,
                findOrCreateNodeAndPut(key, value).first,
                value
            )
        }
        emitChange(MapChange.Rebuild())
    }
    
    /**
     * Efficient batch removal using single write lock.
     * All removals occur under a single write lock for optimal performance.
     */
    open suspend fun removeAllSuspend(vararg keys: K) = rwLock.withWriteLock {
        keys.forEach { key ->
            findNode(key)?.let { node -> nodeRemove(node) }
        }
        emitChange(MapChange.Rebuild())
    }

    /**
     * Range query with suspend support for thread-safe access.
     */
    open suspend fun rangeSuspend(
        from: K, fromInclusive: Boolean = true,
        to: K, toInclusive: Boolean = false
    ): Map<K, V> = rwLock.withReadLock {
        subMap(from, fromInclusive, to, toInclusive)
    }

    // NavigableMap-like functionality

    /**
     * Returns the first (lowest) key currently in this map, or null if the map is empty.
     *
     * **Performance**: O(log n)
     */
    fun firstKey(): K? = runBlockingMultiplatform { firstKeySuspend() }

    /**
     * Suspend version of firstKey() for optimal performance in coroutine contexts.
     */
    suspend fun firstKeySuspend(): K? = rwLock.withReadLock { minimum(root)?.key }

    /**
     * Returns the last (highest) key currently in this map, or null if the map is empty.
     *
     * **Performance**: O(log n)
     */
    fun lastKey(): K? = runBlockingMultiplatform { lastKeySuspend() }

    /**
     * Suspend version of lastKey() for optimal performance in coroutine contexts.
     */
    suspend fun lastKeySuspend(): K? = rwLock.withReadLock { maximum(root)?.key }

    /**
     * Returns a key-value mapping associated with the least key in this map, or null if the map is empty.
     *
     * **Performance**: O(log n)
     */
    fun firstEntry(): MutableMap.MutableEntry<K, V>? = runBlockingMultiplatform { firstEntrySuspend() }

    /**
     * Suspend version of firstEntry() for optimal performance in coroutine contexts.
     */
    suspend fun firstEntrySuspend(): MutableMap.MutableEntry<K, V>? = rwLock.withReadLock { 
        minimum(root)?.let { TreeEntry(it) }
    }

    /**
     * Returns a key-value mapping associated with the greatest key in this map, or null if the map is empty.
     *
     * **Performance**: O(log n)
     */
    fun lastEntry(): MutableMap.MutableEntry<K, V>? = runBlockingMultiplatform { lastEntrySuspend() }

    /**
     * Suspend version of lastEntry() for optimal performance in coroutine contexts.
     */
    suspend fun lastEntrySuspend(): MutableMap.MutableEntry<K, V>? = rwLock.withReadLock { 
        maximum(root)?.let { TreeEntry(it) }
    }

    /**
     * Returns the greatest key strictly less than the given key, or null if no such key exists.
     */
    fun lowerKey(key: K): K? = runBlockingMultiplatform { lowerKeySuspend(key) }

    /**
     * Suspend version of lowerKey() for optimal performance in coroutine contexts.
     */
    suspend fun lowerKeySuspend(key: K): K? = rwLock.withReadLock { lowerNode(key)?.key }

    /**
     * Returns the greatest key less than or equal to the given key, or null if no such key exists.
     */
    fun floorKey(key: K): K? = runBlockingMultiplatform { floorKeySuspend(key) }

    /**
     * Suspend version of floorKey() for optimal performance in coroutine contexts.
     */
    suspend fun floorKeySuspend(key: K): K? = rwLock.withReadLock { floorNode(key)?.key }

    /**
     * Returns the least key greater than or equal to the given key, or null if no such key exists.
     */
    fun ceilingKey(key: K): K? = runBlockingMultiplatform { ceilingKeySuspend(key) }

    /**
     * Suspend version of ceilingKey() for optimal performance in coroutine contexts.
     */
    suspend fun ceilingKeySuspend(key: K): K? = rwLock.withReadLock { ceilingNode(key)?.key }

    /**
     * Returns the least key strictly greater than the given key, or null if no such key exists.
     */
    fun higherKey(key: K): K? = runBlockingMultiplatform { higherKeySuspend(key) }

    /**
     * Suspend version of higherKey() for optimal performance in coroutine contexts.
     */
    suspend fun higherKeySuspend(key: K): K? = rwLock.withReadLock { higherNode(key)?.key }

    fun lowerEntry(key: K): MutableMap.MutableEntry<K, V>? = runBlockingMultiplatform { lowerEntrySuspend(key) }
    suspend fun lowerEntrySuspend(key: K): MutableMap.MutableEntry<K, V>? = rwLock.withReadLock { lowerNode(key)?.let { TreeEntry(it) } }
    
    fun floorEntry(key: K): MutableMap.MutableEntry<K, V>? = runBlockingMultiplatform { floorEntrySuspend(key) }
    suspend fun floorEntrySuspend(key: K): MutableMap.MutableEntry<K, V>? = rwLock.withReadLock { floorNode(key)?.let { TreeEntry(it) } }
    
    fun ceilingEntry(key: K): MutableMap.MutableEntry<K, V>? = runBlockingMultiplatform { ceilingEntrySuspend(key) }
    suspend fun ceilingEntrySuspend(key: K): MutableMap.MutableEntry<K, V>? = rwLock.withReadLock { ceilingNode(key)?.let { TreeEntry(it) } }

    fun higherEntry(key: K): MutableMap.MutableEntry<K, V>? = runBlockingMultiplatform { higherEntrySuspend(key) }
    suspend fun higherEntrySuspend(key: K): MutableMap.MutableEntry<K, V>? = rwLock.withReadLock { higherNode(key)?.let { TreeEntry(it) } }

    /**
     * Removes and returns a key-value mapping associated with the least key in this map,
     * or null if the map is empty.
     *
     * The returned entry is detached from the map. Calling setValue() on the returned
     * entry will NOT update the map.
     *
     * @return a detached entry that was removed from the map, or null if the map was empty
     */
    fun pollFirstEntry(): MutableMap.MutableEntry<K, V>? = runBlockingMultiplatform { pollFirstEntrySuspend() }

    /**
     * Suspend version of pollFirstEntry() for optimal performance in coroutine contexts.
     */
    suspend fun pollFirstEntrySuspend(): MutableMap.MutableEntry<K, V>? = rwLock.withWriteLock {
        val first = minimum(root) ?: return@withWriteLock null
        val key = first.key
        val value = first.value
        nodeRemove(first)
        DetachedEntry(key, value)
    }

    /**
     * Removes and returns a key-value mapping associated with the greatest key in this map,
     * or null if the map is empty.
     *
     * The returned entry is detached from the map. Calling setValue() on the returned
     * entry will NOT update the map.
     *
     * @return a detached entry that was removed from the map, or null if the map was empty
     */
    fun pollLastEntry(): MutableMap.MutableEntry<K, V>? = runBlockingMultiplatform { pollLastEntrySuspend() }

    /**
     * Suspend version of pollLastEntry() for optimal performance in coroutine contexts.
     */
    suspend fun pollLastEntrySuspend(): MutableMap.MutableEntry<K, V>? = rwLock.withWriteLock {
        val last = maximum(root) ?: return@withWriteLock null
        val key = last.key
        val value = last.value
        nodeRemove(last)
        DetachedEntry(key, value)
    }

    fun descendingKeySet(): Set<K> = DescendingKeySet()
    fun descendingMap(): Map<K, V> = DescendingMap()
    
    // Suspend versions for coroutine contexts
    suspend fun descendingKeySetSuspend(): Set<K> = DescendingKeySet()
    suspend fun descendingMapSuspend(): Map<K, V> = DescendingMap()

    fun subMap(
        fromKey: K,
        fromInclusive: Boolean = true,
        toKey: K,
        toInclusive: Boolean = false
    ): Map<K, V> {
        return SubMap(fromKey, fromInclusive, toKey, toInclusive)
    }

    /**
     * Suspend version of subMap() for optimal performance in coroutine contexts.
     * Returns a thread-safe map view that uses suspend operations.
     */
    suspend fun subMapSuspend(
        fromKey: K,
        fromInclusive: Boolean = true,
        toKey: K,
        toInclusive: Boolean = false
    ): Map<K, V> = rwLock.withReadLock {
        // Create a snapshot of the submap entries using thread-safe operations
        val entries = mutableMapOf<K, V>()
        var node = ceilingNode(fromKey)
        while (node != null) {
            val key = node.key
            val fromCmp = compare(key, fromKey)
            val toCmp = compare(key, toKey)
            val inRange = (if (fromInclusive) fromCmp >= 0 else fromCmp > 0) &&
                         (if (toInclusive) toCmp <= 0 else toCmp < 0)
            
            if (!inRange) {
                if (compare(key, toKey) >= 0) break
                node = successor(node)
                continue
            }
            
            entries[key] = node.value
            node = successor(node)
        }
        entries
    }

    fun headMap(toKey: K, inclusive: Boolean = false): Map<K, V> {
        return HeadMap(toKey, inclusive)
    }

    /**
     * Suspend version of headMap() for optimal performance in coroutine contexts.
     */
    suspend fun headMapSuspend(toKey: K, inclusive: Boolean = false): Map<K, V> = rwLock.withReadLock {
        val entries = mutableMapOf<K, V>()
        var node = minimum(root)
        while (node != null) {
            val cmp = compare(node.key, toKey)
            val inRange = if (inclusive) cmp <= 0 else cmp < 0
            if (!inRange) break
            
            entries[node.key] = node.value
            node = successor(node)
        }
        entries
    }

    fun tailMap(fromKey: K, inclusive: Boolean = true): Map<K, V> {
        return TailMap(fromKey, inclusive)
    }

    /**
     * Suspend version of tailMap() for optimal performance in coroutine contexts.
     */
    suspend fun tailMapSuspend(fromKey: K, inclusive: Boolean = true): Map<K, V> = rwLock.withReadLock {
        val entries = mutableMapOf<K, V>()
        var node = if (inclusive) ceilingNode(fromKey) else higherNode(fromKey)
        while (node != null) {
            entries[node.key] = node.value
            node = successor(node)
        }
        entries
    }

    // Helper methods for navigable operations
    private fun lowerNode(key: K): Node<K, V>? {
        var current = root
        var result: Node<K, V>? = null

        while (current != null) {
            val cmp = compare(key, current.key)
            when {
                cmp <= 0 -> current = current.left
                else -> {
                    result = current
                    current = current.right
                }
            }
        }
        return result
    }

    private fun floorNode(key: K): Node<K, V>? {
        var current = root
        var result: Node<K, V>? = null

        while (current != null) {
            val cmp = compare(key, current.key)
            when {
                cmp < 0 -> current = current.left
                cmp == 0 -> return current
                else -> {
                    result = current
                    current = current.right
                }
            }
        }
        return result
    }

    private fun ceilingNode(key: K): Node<K, V>? {
        var current = root
        var result: Node<K, V>? = null

        while (current != null) {
            val cmp = compare(key, current.key)
            when {
                cmp > 0 -> current = current.right
                cmp == 0 -> return current
                else -> {
                    result = current
                    current = current.left
                }
            }
        }
        return result
    }

    private fun higherNode(key: K): Node<K, V>? {
        var current = root
        var result: Node<K, V>? = null

        while (current != null) {
            val cmp = compare(key, current.key)
            when {
                cmp >= 0 -> current = current.right
                else -> {
                    result = current
                    current = current.left
                }
            }
        }
        return result
    }

    // Red-Black Tree operations
    internal fun findNode(key: K): Node<K, V>? {
        var current = root
        while (current != null) {
            val cmp = compare(key, current.key)
            current = when {
                cmp < 0 -> current.left
                cmp > 0 -> current.right
                else -> return current
            }
        }
        return null
    }

    internal fun minimum(node: Node<K, V>?): Node<K, V>? {
        var current = node
        while (current?.left != null) {
            current = current.left
        }
        return current
    }

    private fun maximum(node: Node<K, V>?): Node<K, V>? {
        var current = node
        while (current?.right != null) {
            current = current.right
        }
        return current
    }

    internal fun successor(node: Node<K, V>): Node<K, V>? {
        if (node.right != null) {
            return minimum(node.right)
        }

        var current = node
        var parent = current.parent
        while (parent != null && current == parent.right) {
            current = parent
            parent = parent.parent
        }
        return parent
    }

    // Unsafe tree traversal methods for single-lock optimizations
    // WARNING: These methods are NOT thread-safe and must only be called under appropriate locks

    /**
     * Performs action on each node in the tree in sorted order.
     * WARNING: Not thread-safe - must be called under read or write lock.
     */
    internal fun forEachNodeUnsafe(action: (Node<K, V>) -> Unit) {
        var current = minimum(root)
        while (current != null) {
            action(current)
            current = successor(current)
        }
    }

    /**
     * Performs action on each key-value pair in the tree in sorted order.
     * WARNING: Not thread-safe - must be called under read or write lock.
     */
    internal fun forEachEntryUnsafe(action: (K, V) -> Unit) {
        var current = minimum(root)
        while (current != null) {
            action(current.key, current.value)
            current = successor(current)
        }
    }

    /**
     * Filters nodes based on predicate and returns matching nodes.
     * WARNING: Not thread-safe - must be called under read or write lock.
     */
    internal fun filterNodesUnsafe(predicate: (Node<K, V>) -> Boolean): List<Node<K, V>> {
        val result = mutableListOf<Node<K, V>>()
        var current = minimum(root)
        while (current != null) {
            if (predicate(current)) {
                result.add(current)
            }
            current = successor(current)
        }
        return result
    }

    /**
     * Finds first node matching the value predicate.
     * WARNING: Not thread-safe - must be called under read or write lock.
     */
    internal fun findValueUnsafe(predicate: (V) -> Boolean): Node<K, V>? {
        var current = minimum(root)
        while (current != null) {
            if (predicate(current.value)) {
                return current
            }
            current = successor(current)
        }
        return null
    }

    /**
     * Performs action on each node within the specified key range.
     * WARNING: Not thread-safe - must be called under read or write lock.
     */
    internal fun forEachInRangeUnsafe(
        fromKey: K, 
        fromInclusive: Boolean,
        toKey: K, 
        toInclusive: Boolean,
        action: (Node<K, V>) -> Unit
    ) {
        var current = if (fromInclusive) ceilingNode(fromKey) else higherNode(fromKey)
        while (current != null) {
            val key = current.key
            val toCmp = compare(key, toKey)
            val inRange = if (toInclusive) toCmp <= 0 else toCmp < 0
            
            if (!inRange) break
            
            action(current)
            current = successor(current)
        }
    }

    /**
     * Counts nodes within the specified key range.
     * WARNING: Not thread-safe - must be called under read or write lock.
     */
    internal fun countInRangeUnsafe(
        fromKey: K,
        fromInclusive: Boolean, 
        toKey: K,
        toInclusive: Boolean
    ): Int {
        var count = 0
        forEachInRangeUnsafe(fromKey, fromInclusive, toKey, toInclusive) { _ -> count++ }
        return count
    }

    /**
     * Collects all entries into a list efficiently.
     * WARNING: Not thread-safe - must be called under read or write lock.
     */
    internal fun collectAllEntriesUnsafe(): List<Map.Entry<K, V>> {
        val result = mutableListOf<Map.Entry<K, V>>()
        forEachEntryUnsafe { key, value -> result.add(object : Map.Entry<K, V> {
            override val key: K
                get() = key
            override val value: V
                get() = value

        }) }
        return result
    }

    /**
     * Removes multiple keys efficiently in a single traversal where possible.
     * WARNING: Not thread-safe - must be called under write lock.
     * Returns the number of keys actually removed.
     */
    internal fun removeAllKeysUnsafe(keys: Collection<K>): Int {
        if (keys.isEmpty()) return 0
        
        val keySet = keys.toSet() // For O(1) lookup
        var removedCount = 0
        
        // For small numbers of keys, individual removal is more efficient
        if (keySet.size <= 10) {
            keySet.forEach { key ->
                findNode(key)?.let { node ->
                    nodeRemove(node)
                    removedCount++
                }
            }
        } else {
            // For large numbers of keys, traverse tree and collect nodes to remove
            val nodesToRemove = mutableListOf<Node<K, V>>()
            forEachNodeUnsafe { node ->
                if (node.key in keySet) {
                    nodesToRemove.add(node)
                }
            }
            
            // Remove collected nodes
            nodesToRemove.forEach { node ->
                nodeRemove(node)
                removedCount++
            }
        }
        
        return removedCount
    }
    
    /**
     * Alias for findNode() to clearly indicate unsafe usage.
     * Must be called within appropriate locks.
     */
    internal fun findNodeUnsafe(key: K): Node<K, V>? = findNode(key)

    private fun predecessor(node: Node<K, V>): Node<K, V>? {
        if (node.left != null) {
            return maximum(node.left)
        }

        var parent = node.parent
        var current = node
        while (parent != null && current == parent.left) {
            current = parent
            parent = parent.parent
        }
        return parent
    }

    private fun rotateLeft(x: Node<K, V>) {
        val y = x.right ?: return
        x.right = y.left
        y.left?.parent = x
        y.parent = x.parent

        when {
            x.parent == null -> root = y
            x == x.parent?.left -> x.parent?.left = y
            else -> x.parent?.right = y
        }

        y.left = x
        x.parent = y
    }

    private fun rotateRight(y: Node<K, V>) {
        val x = y.left ?: return
        y.left = x.right
        x.right?.parent = y
        x.parent = y.parent

        when {
            y.parent == null -> root = x
            y == y.parent?.left -> y.parent?.left = x
            else -> y.parent?.right = x
        }

        x.right = y
        y.parent = x
    }

    private fun fixInsert(z: Node<K, V>) {
        var node = z
        node.color = Color.RED

        while (node.parent != null && getNodeColor(node.parent) == Color.RED) {
            val parent = node.parent!!
            val grandparent = parent.parent ?: break

            if (parent == grandparent.left) {
                val uncle = grandparent.right
                if (getNodeColor(uncle) == Color.RED) {
                    // Case 1: Uncle is red
                    parent.color = Color.BLACK
                    uncle?.color = Color.BLACK
                    grandparent.color = Color.RED
                    node = grandparent
                } else {
                    // Uncle is black
                    if (node == parent.right) {
                        // Case 2: Node is right child
                        node = parent
                        rotateLeft(node)
                    }
                    // Case 3: Node is left child
                    val currentParent = node.parent
                    val currentGrandparent = currentParent?.parent
                    if (currentParent != null && currentGrandparent != null) {
                        currentParent.color = Color.BLACK
                        currentGrandparent.color = Color.RED
                        rotateRight(currentGrandparent)
                    }
                    break
                }
            } else {
                // Symmetric case
                val uncle = grandparent.left
                if (getNodeColor(uncle) == Color.RED) {
                    // Case 1: Uncle is red
                    parent.color = Color.BLACK
                    uncle?.color = Color.BLACK
                    grandparent.color = Color.RED
                    node = grandparent
                } else {
                    // Uncle is black
                    if (node == parent.left) {
                        // Case 2: Node is left child
                        node = parent
                        rotateRight(node)
                    }
                    // Case 3: Node is right child
                    val currentParent = node.parent
                    val currentGrandparent = currentParent?.parent
                    if (currentParent != null && currentGrandparent != null) {
                        currentParent.color = Color.BLACK
                        currentGrandparent.color = Color.RED
                        rotateLeft(currentGrandparent)
                    }
                    break
                }
            }
        }

        root?.color = Color.BLACK
    }

    private fun deleteNode(z: Node<K, V>) {
        var y = z
        var yOriginalColor = y.color
        var x: Node<K, V>?
        var xParent: Node<K, V>?

        when {
            z.left == null -> {
                x = z.right
                xParent = z.parent
                transplant(z, z.right)
            }

            z.right == null -> {
                x = z.left
                xParent = z.parent
                transplant(z, z.left)
            }

            else -> {
                y = minimum(z.right)!!
                yOriginalColor = y.color
                x = y.right

                if (y.parent == z) {
                    xParent = y
                } else {
                    xParent = y.parent
                    transplant(y, y.right)
                    y.right = z.right
                    y.right?.parent = y
                }

                transplant(z, y)
                y.left = z.left
                y.left?.parent = y
                y.color = z.color
            }
        }

        if (yOriginalColor == Color.BLACK) {
            fixDelete(x, xParent)
        }
    }

    private fun transplant(u: Node<K, V>, v: Node<K, V>?) {
        when {
            u.parent == null -> root = v
            u == u.parent?.left -> u.parent?.left = v
            else -> u.parent?.right = v
        }
        v?.parent = u.parent
    }

    private fun fixDelete(x: Node<K, V>?, xParent: Node<K, V>?) {
        var node = x
        var parent = xParent

        while (node != root && getNodeColor(node) == Color.BLACK) {
            parent ?: break

            if (node == parent.left) {
                var w = parent.right ?: break

                if (getNodeColor(w) == Color.RED) {
                    w.color = Color.BLACK
                    parent.color = Color.RED
                    rotateLeft(parent)
                    w = parent.right ?: break
                }

                if (getNodeColor(w.left) == Color.BLACK && getNodeColor(w.right) == Color.BLACK) {
                    w.color = Color.RED
                    node = parent
                    parent = node.parent
                } else {
                    if (getNodeColor(w.right) == Color.BLACK) {
                        w.left?.color = Color.BLACK
                        w.color = Color.RED
                        rotateRight(w)
                        w = parent.right ?: break
                    }
                    w.color = parent.color
                    parent.color = Color.BLACK
                    w.right?.color = Color.BLACK
                    rotateLeft(parent)
                    node = root
                    break
                }
            } else {
                // Symmetric case
                var w = parent.left ?: break

                if (getNodeColor(w) == Color.RED) {
                    w.color = Color.BLACK
                    parent.color = Color.RED
                    rotateRight(parent)
                    w = parent.left ?: break
                }

                if (getNodeColor(w.right) == Color.BLACK && getNodeColor(w.left) == Color.BLACK) {
                    w.color = Color.RED
                    node = parent
                    parent = node.parent
                } else {
                    if (getNodeColor(w.left) == Color.BLACK) {
                        w.right?.color = Color.BLACK
                        w.color = Color.RED
                        rotateLeft(w)
                        w = parent.left ?: break
                    }
                    w.color = parent.color
                    parent.color = Color.BLACK
                    w.left?.color = Color.BLACK
                    rotateRight(parent)
                    node = root
                    break
                }
            }
        }

        node?.color = Color.BLACK
        root?.color = Color.BLACK
    }

    private inner class TreeEntry(private val node: Node<K, V>) : MutableMap.MutableEntry<K, V> {
        override val key: K get() = node.key
        override val value: V get() = node.value

        /**
         * Updates the value for this entry.
         *
         * @param newValue the new value to set
         * @return the previous value
         */
        override fun setValue(newValue: V): V = runBlockingMultiplatform {
            rwLock.withWriteLock {
                val oldValue = node.value

                // Apply changes
                node.value = newValue

                updateSecondaryKeys(key, oldValue, newValue)

                // Emit change notification
                emitChange(MapChange.Put(key, newValue, true, oldValue))

                oldValue
            }
        }

        override fun equals(other: Any?): Boolean {
            return other is Map.Entry<*, *> && key == other.key && value == other.value
        }

        override fun hashCode(): Int = key.hashCode() xor (value?.hashCode() ?: 0)
        override fun toString(): String = "$key=$value"
    }

    /**
     * Entry that is no longer associated with the TreeMap.
     * Used by poll operations to return entries that have been removed from the map.
     * Calling setValue() updates this entry's value but does NOT affect the map.
     */
    private class DetachedEntry<K, V>(
        override val key: K,
        override var value: V
    ) : MutableMap.MutableEntry<K, V> {
        override fun setValue(newValue: V): V {
            val old = value
            value = newValue
            return old
        }

        override fun equals(other: Any?): Boolean {
            return other is Map.Entry<*, *> && key == other.key && value == other.value
        }

        override fun hashCode(): Int = key.hashCode() xor (value?.hashCode() ?: 0)
        override fun toString(): String = "$key=$value"
    }

    // Collection views
    private inner class KeySet : MutableSet<K> {
        override val size: Int get() = this@TreeMap.size
        override fun isEmpty(): Boolean = this@TreeMap.isEmpty()
        override fun contains(element: K): Boolean = this@TreeMap.containsKey(element)
        override fun containsAll(elements: Collection<K>): Boolean = elements.all { contains(it) }

        override fun add(element: K): Boolean =
            throw UnsupportedOperationException("Cannot add key without value")

        override fun addAll(elements: Collection<K>): Boolean =
            throw UnsupportedOperationException("Cannot add keys without values")

        override fun remove(element: K): Boolean = this@TreeMap.remove(element) != null
        override fun removeAll(elements: Collection<K>): Boolean = runBlockingMultiplatform {
            removeAllSuspend(elements)
        }
        
        suspend fun removeAllSuspend(elements: Collection<K>): Boolean {
            if (elements.isEmpty()) return false
            val initialSize = this@TreeMap.size
            rwLock.withWriteLock {
                // Optimized: Use unsafe batch removal
                val removedCount = removeAllKeysUnsafe(elements)
                if (removedCount > 0) {
                    emitChange(MapChange.Rebuild())
                }
            }
            return this@TreeMap.size != initialSize
        }

        override fun retainAll(elements: Collection<K>): Boolean {
            val toRemove = this.filter { it !in elements }
            return removeAll(toRemove)
        }

        override fun clear() = this@TreeMap.clear()

        override fun iterator(): MutableIterator<K> = KeyIterator()
    }

    private inner class ValueCollection : MutableCollection<V> {
        override val size: Int get() = this@TreeMap.size
        override fun isEmpty(): Boolean = this@TreeMap.isEmpty()
        override fun contains(element: V): Boolean = this@TreeMap.containsValue(element)
        override fun containsAll(elements: Collection<V>): Boolean = elements.all { contains(it) }

        override fun add(element: V): Boolean =
            throw UnsupportedOperationException("Cannot add value without key")

        override fun addAll(elements: Collection<V>): Boolean =
            throw UnsupportedOperationException("Cannot add values without keys")

        override fun remove(element: V): Boolean = runBlockingMultiplatform {
            removeSuspend(element)
        }
        
        suspend fun removeSuspend(element: V): Boolean {
            return rwLock.withWriteLock {
                // Optimized: Use unsafe value search with early termination
                val nodeToRemove = findValueUnsafe { it == element }
                if (nodeToRemove != null) {
                    nodeRemove(nodeToRemove)
                    true
                } else {
                    false
                }
            }
        }

        override fun removeAll(elements: Collection<V>): Boolean = runBlockingMultiplatform {
            removeAllSuspend(elements)
        }
        
        suspend fun removeAllSuspend(elements: Collection<V>): Boolean {
            if (elements.isEmpty()) return false
            val valuesToRemove = elements.toSet()
            val initialSize = this@TreeMap.size
            rwLock.withWriteLock {
                // Optimized: Use unsafe node filtering for batch removal
                val nodesToRemove = filterNodesUnsafe { node -> node.value in valuesToRemove }
                if (nodesToRemove.isNotEmpty()) {
                    nodesToRemove.forEach { node -> nodeRemove(node) }
                    emitChange(MapChange.Rebuild())
                }
            }
            return this@TreeMap.size != initialSize
        }

        override fun retainAll(elements: Collection<V>): Boolean = runBlockingMultiplatform {
            retainAllSuspend(elements)
        }
        
        suspend fun retainAllSuspend(elements: Collection<V>): Boolean {
            val toKeep = elements.toSet()
            return rwLock.withWriteLock {
                // Optimized: Use unsafe node filtering for batch retain
                val nodesToRemove = filterNodesUnsafe { node -> node.value !in toKeep }
                if (nodesToRemove.isNotEmpty()) {
                    nodesToRemove.forEach { node -> nodeRemove(node) }
                    emitChange(MapChange.Rebuild())
                    true
                } else {
                    false
                }
            }
        }

        override fun clear() = this@TreeMap.clear()
        override fun iterator(): MutableIterator<V> = ValueIterator()
    }

    private inner class EntrySet : MutableSet<MutableMap.MutableEntry<K, V>> {
        override val size: Int get() = this@TreeMap.size
        override fun isEmpty(): Boolean = this@TreeMap.isEmpty()
        override fun contains(element: MutableMap.MutableEntry<K, V>): Boolean {
            val value = this@TreeMap.get(element.key)
            return value != null && value == element.value
        }

        override fun containsAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean =
            elements.all { contains(it) }

        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean {
            val oldValue = this@TreeMap.put(element.key, element.value)
            return oldValue != element.value
        }

        override fun addAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean = runBlockingMultiplatform {
            addAllSuspend(elements)
        }
        
        suspend fun addAllSuspend(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean {
            if (elements.isEmpty()) return false
            val initialSize = this@TreeMap.size
            rwLock.withWriteLock {
                elements.forEach { entry ->
                    updateSecondaryKeys(
                        entry.key,
                        findOrCreateNodeAndPut(entry.key, entry.value).first,
                        entry.value
                    )
                }
                emitChange(MapChange.Rebuild())
            }
            return this@TreeMap.size != initialSize
        }

        override fun remove(element: MutableMap.MutableEntry<K, V>): Boolean {
            val current = get(element.key)
            return if (current == element.value) {
                this@TreeMap.remove(element.key) != null
            } else false
        }

        override fun removeAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean = runBlockingMultiplatform {
            removeAllSuspend(elements)
        }
        
        suspend fun removeAllSuspend(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean {
            if (elements.isEmpty()) return false
            val initialSize = this@TreeMap.size
            rwLock.withWriteLock {
                var removed = false
                elements.forEach { entry ->
                    val current = findNode(entry.key)
                    if (current != null && current.value == entry.value) {
                        nodeRemove(current)
                        removed = true
                    }
                }
                if (removed) {
                    emitChange(MapChange.Rebuild())
                }
            }
            return this@TreeMap.size != initialSize
        }

        override fun retainAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean = runBlockingMultiplatform {
            retainAllSuspend(elements)
        }
        
        suspend fun retainAllSuspend(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean {
            val toKeep = elements.associate { it.key to it.value }
            return rwLock.withWriteLock {
                var removed = false
                var current = minimum(root)
                while (current != null) {
                    val next = successor(current)  // Get next before potential removal
                    if (toKeep[current.key] != current.value) {
                        nodeRemove(current)
                        removed = true
                    }
                    current = next
                }
                if (removed) {
                    emitChange(MapChange.Rebuild())
                }
                removed
            }
        }

        override fun clear() = this@TreeMap.clear()
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> = EntryIterator()
    }

    // Thread-safe iterators with proper concurrent modification detection
    // These use live traversal with change tracking instead of snapshots
    private abstract inner class TreeIterator<T> : MutableIterator<T> {
        private var next: Node<K, V>? = runBlockingMultiplatform {
            rwLock.withReadLock { minimum(root) }
        }
        private var lastReturned: Node<K, V>? = null
        private val expectedModCount = modCount

        override fun hasNext(): Boolean {
            checkForConcurrentModification()
            return next != null
        }

        override fun next(): T {
            checkForConcurrentModification()
            val current = next ?: throw NoSuchElementException("No more elements in iterator")
            lastReturned = current
            next = runBlockingMultiplatform {
                rwLock.withReadLock { successor(current) }
            }
            return getValue(current)
        }

        override fun remove() {
            checkForConcurrentModification()
            val nodeToRemove = lastReturned 
                ?: throw IllegalStateException("remove() called before next() or remove() already called")
            
            runBlockingMultiplatform {
                rwLock.withWriteLock {
                    // Find and remove the node (verify it still exists with same value)
                    findNodeUnsafe(nodeToRemove.key)?.let { currentNode ->
                        if (currentNode.value == nodeToRemove.value) {
                            nodeRemove(currentNode)
                        }
                    }
                }
            }
            lastReturned = null
        }

        private fun checkForConcurrentModification() {
            if (modCount != expectedModCount) {
                throw ConcurrentModificationException("TreeMap was modified during iteration")
            }
        }

        abstract fun getValue(node: Node<K, V>): T
    }

    private inner class KeyIterator : TreeIterator<K>() {
        override fun getValue(node: Node<K, V>): K = node.key
    }

    private inner class ValueIterator : TreeIterator<V>() {
        override fun getValue(node: Node<K, V>): V = node.value
    }

    private inner class EntryIterator : TreeIterator<MutableMap.MutableEntry<K, V>>() {
        override fun getValue(node: Node<K, V>): MutableMap.MutableEntry<K, V> = TreeEntry(node)
    }

    // Suspend iterator implementations with proper thread safety
    private abstract inner class SuspendTreeIterator<T> : SuspendMutableIterator<T> {
        private var next: Node<K, V>? = runBlockingMultiplatform {
            rwLock.withReadLock { minimum(root) }
        }
        private var lastReturned: Node<K, V>? = null
        private val expectedModCount = modCount

        override suspend fun hasNext(): Boolean {
            checkForConcurrentModification()
            return next != null
        }

        override suspend fun next(): T {
            checkForConcurrentModification()
            val current = next ?: throw NoSuchElementException("No more elements in iterator")
            lastReturned = current
            next = rwLock.withReadLock { successor(current) }
            return getValue(current)
        }

        override suspend fun remove() {
            checkForConcurrentModification()
            val nodeToRemove = lastReturned 
                ?: throw IllegalStateException("remove() called before next() or remove() already called")
            
            rwLock.withWriteLock {
                // Find and remove the node (verify it still exists with same value)
                findNodeUnsafe(nodeToRemove.key)?.let { currentNode ->
                    if (currentNode.value == nodeToRemove.value) {
                        nodeRemove(currentNode)
                    }
                }
            }
            lastReturned = null
        }

        private fun checkForConcurrentModification() {
            if (modCount != expectedModCount) {
                throw ConcurrentModificationException("TreeMap was modified during iteration")
            }
        }

        abstract fun getValue(node: Node<K, V>): T
    }

    private inner class SuspendKeyIterator : SuspendTreeIterator<K>() {
        override fun getValue(node: Node<K, V>): K = node.key
    }

    private inner class SuspendValueIterator : SuspendTreeIterator<V>() {
        override fun getValue(node: Node<K, V>): V = node.value
    }

    private inner class SuspendEntryIterator : SuspendTreeIterator<MutableMap.MutableEntry<K, V>>() {
        override fun getValue(node: Node<K, V>): MutableMap.MutableEntry<K, V> = TreeEntry(node)
    }

    // Descending views
    private inner class DescendingKeySet : Set<K> {
        override val size: Int get() = this@TreeMap.size
        override fun isEmpty(): Boolean = this@TreeMap.isEmpty()
        override fun contains(element: K): Boolean = containsKey(element)
        override fun containsAll(elements: Collection<K>): Boolean = elements.all { contains(it) }
        override fun iterator(): Iterator<K> = DescendingKeyIterator()
    }

    private inner class DescendingMap : Map<K, V> {
        override val size: Int get() = this@TreeMap.size
        override val keys: Set<K> get() = DescendingKeySet()
        override val values: Collection<V> get() = DescendingValueCollection()
        override val entries: Set<Map.Entry<K, V>> get() = DescendingEntrySet()

        override fun isEmpty(): Boolean = this@TreeMap.isEmpty()
        override fun get(key: K): V? = this@TreeMap.get(key)
        override fun containsKey(key: K): Boolean = this@TreeMap.containsKey(key)
        override fun containsValue(value: V): Boolean = this@TreeMap.containsValue(value)
    }

    private inner class DescendingValueCollection : Collection<V> {
        override val size: Int get() = this@TreeMap.size
        override fun isEmpty(): Boolean = this@TreeMap.isEmpty()
        override fun contains(element: V): Boolean = containsValue(element)
        override fun containsAll(elements: Collection<V>): Boolean = elements.all { contains(it) }
        override fun iterator(): Iterator<V> = DescendingValueIterator()
    }

    private inner class DescendingEntrySet : Set<Map.Entry<K, V>> {
        override val size: Int get() = this@TreeMap.size
        override fun isEmpty(): Boolean = this@TreeMap.isEmpty()
        override fun contains(element: Map.Entry<K, V>): Boolean {
            val value = get(element.key)
            return value != null && value == element.value
        }

        override fun containsAll(elements: Collection<Map.Entry<K, V>>): Boolean =
            elements.all { contains(it) }

        override fun iterator(): Iterator<Map.Entry<K, V>> = DescendingEntryIterator()
    }

    // Descending iterators with single-lock strategy
    private abstract inner class DescendingTreeIterator<T> : MutableIterator<T> {
        // Collect all nodes in descending order under single read lock
        private val nodes: List<Node<K, V>> = runBlockingMultiplatform {
            rwLock.withReadLock {
                val result = mutableListOf<Node<K, V>>()
                var current = maximum(root)
                while (current != null) {
                    result.add(current)
                    current = predecessor(current)
                }
                result
            }
        }
        private var currentIndex = 0
        private var lastReturnedIndex = -1

        override fun hasNext(): Boolean = currentIndex < nodes.size

        override fun next(): T {
            if (currentIndex >= nodes.size) {
                throw NoSuchElementException("No more elements in descending iterator")
            }
            val node = nodes[currentIndex]
            lastReturnedIndex = currentIndex
            currentIndex++
            return getValue(node)
        }

        override fun remove() {
            if (lastReturnedIndex == -1) {
                throw IllegalStateException("remove() called before next() or remove() already called")
            }
            
            val nodeToRemove = nodes[lastReturnedIndex]
            runBlockingMultiplatform {
                rwLock.withWriteLock {
                    // Find and remove the node (it might have been removed by another thread)
                    findNode(nodeToRemove.key)?.let { currentNode ->
                        if (currentNode.value == nodeToRemove.value) {
                            nodeRemove(currentNode)
                        }
                    }
                }
            }
            lastReturnedIndex = -1
        }

        abstract fun getValue(node: Node<K, V>): T
    }

    private inner class DescendingKeyIterator : DescendingTreeIterator<K>() {
        override fun getValue(node: Node<K, V>): K = node.key
    }

    private inner class DescendingValueIterator : DescendingTreeIterator<V>() {
        override fun getValue(node: Node<K, V>): V = node.value
    }

    private inner class DescendingEntryIterator : DescendingTreeIterator<Map.Entry<K, V>>() {
        override fun getValue(node: Node<K, V>): Map.Entry<K, V> = TreeEntry(node)
    }

    // Sub-map implementations
    private inner class SubMap(
        private val fromKey: K,
        private val fromInclusive: Boolean,
        private val toKey: K,
        private val toInclusive: Boolean
    ) : Map<K, V> {

        init {
            if (compare(fromKey, toKey) > 0) {
                throw IllegalArgumentException("fromKey ($fromKey) > toKey ($toKey)")
            }
        }

        private fun inRange(key: K): Boolean {
            val fromCmp = compare(key, fromKey)
            val toCmp = compare(key, toKey)
            return (if (fromInclusive) fromCmp >= 0 else fromCmp > 0) &&
                    (if (toInclusive) toCmp <= 0 else toCmp < 0)
        }

        override val size: Int
            get() = runBlockingMultiplatform {
                rwLock.withReadLock {
                    // Optimized: Use unsafe range counting method
                    countInRangeUnsafe(fromKey, fromInclusive, toKey, toInclusive)
                }
            }

        override val keys: Set<K>
            get() = object : Set<K> {
                override val size: Int get() = this@SubMap.size
                override fun isEmpty(): Boolean = this@SubMap.isEmpty()
                override fun contains(element: K): Boolean =
                    inRange(element) && this@SubMap.containsKey(element)

                override fun containsAll(elements: Collection<K>): Boolean =
                    elements.all { contains(it) }

                override fun iterator(): Iterator<K> = SubMapKeyIterator()
            }

        override val values: Collection<V>
            get() = object : Collection<V> {
                override val size: Int get() = this@SubMap.size
                override fun isEmpty(): Boolean = this@SubMap.isEmpty()
                override fun contains(element: V): Boolean = containsValue(element)
                override fun containsAll(elements: Collection<V>): Boolean =
                    elements.all { contains(it) }

                override fun iterator(): Iterator<V> = SubMapValueIterator()
            }

        override val entries: Set<Map.Entry<K, V>>
            get() = object : Set<Map.Entry<K, V>> {
                override val size: Int get() = this@SubMap.size
                override fun isEmpty(): Boolean = this@SubMap.isEmpty()
                override fun contains(element: Map.Entry<K, V>): Boolean {
                    return inRange(element.key) && this@TreeMap.get(element.key) == element.value
                }

                override fun containsAll(elements: Collection<Map.Entry<K, V>>): Boolean =
                    elements.all { contains(it) }

                override fun iterator(): Iterator<Map.Entry<K, V>> = SubMapEntryIterator()
            }

        private fun firstNode(): Node<K, V>? = runBlockingMultiplatform {
            rwLock.withReadLock {
                ceilingNode(fromKey)?.takeIf { inRange(it.key) }
            }
        }

        override fun isEmpty(): Boolean = runBlockingMultiplatform { isEmptySuspend() }
        
        override fun get(key: K): V? = runBlockingMultiplatform { getSuspend(key) }
        
        override fun containsKey(key: K): Boolean = runBlockingMultiplatform { containsKeySuspend(key) }
        
        suspend fun isEmptySuspend(): Boolean = rwLock.withReadLock { 
            countInRangeUnsafe(fromKey, fromInclusive, toKey, toInclusive) == 0 
        }
        
        suspend fun getSuspend(key: K): V? = 
            if (inRange(key)) this@TreeMap.getSuspend(key) else null
            
        suspend fun containsKeySuspend(key: K): Boolean = 
            inRange(key) && this@TreeMap.containsKeySuspend(key)
        override fun containsValue(value: V): Boolean {
            return runBlockingMultiplatform {
                rwLock.withReadLock {
                    // Optimized: Use single lock with unsafe range traversal with early termination
                    var current = if (fromInclusive) ceilingNode(fromKey) else higherNode(fromKey)
                    while (current != null) {
                        val key = current.key
                        val toCmp = compare(key, toKey)
                        val inRange = if (toInclusive) toCmp <= 0 else toCmp < 0
                        
                        if (!inRange) break
                        
                        if (current.value == value) {
                            return@withReadLock true // Early termination
                        }
                        current = successor(current)
                    }
                    false
                }
            }
        }

        private inner class SubMapKeyIterator : Iterator<K> {
            private var next = firstNode()
            private val expectedModCount = modCount

            override fun hasNext(): Boolean = next != null && inRange(next!!.key)

            override fun next(): K {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val current = next ?: throw NoSuchElementException()
                if (!inRange(current.key)) throw NoSuchElementException()
                next = runBlockingMultiplatform {
                    rwLock.withReadLock { successor(current) }
                }
                return current.key
            }

            suspend fun hasNextSuspend(): Boolean = next != null && inRange(next!!.key)

            suspend fun nextSuspend(): K {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val current = next ?: throw NoSuchElementException()
                if (!inRange(current.key)) throw NoSuchElementException()
                next = rwLock.withReadLock { successor(current) }
                return current.key
            }
        }

        private inner class SubMapValueIterator : Iterator<V> {
            private var next = firstNode()
            private val expectedModCount = modCount

            override fun hasNext(): Boolean = next != null && inRange(next!!.key)

            override fun next(): V {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val current = next ?: throw NoSuchElementException()
                if (!inRange(current.key)) throw NoSuchElementException()
                next = runBlockingMultiplatform {
                    rwLock.withReadLock { successor(current) }
                }
                return current.value
            }

            suspend fun hasNextSuspend(): Boolean = next != null && inRange(next!!.key)

            suspend fun nextSuspend(): V {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val current = next ?: throw NoSuchElementException()
                if (!inRange(current.key)) throw NoSuchElementException()
                next = rwLock.withReadLock { successor(current) }
                return current.value
            }
        }

        private inner class SubMapEntryIterator : Iterator<Map.Entry<K, V>> {
            private var next = firstNode()
            private val expectedModCount = modCount

            override fun hasNext(): Boolean = next != null && inRange(next!!.key)

            override fun next(): Map.Entry<K, V> {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val current = next ?: throw NoSuchElementException()
                if (!inRange(current.key)) throw NoSuchElementException()
                next = runBlockingMultiplatform {
                    rwLock.withReadLock { successor(current) }
                }
                return TreeEntry(current)
            }

            suspend fun hasNextSuspend(): Boolean = next != null && inRange(next!!.key)

            suspend fun nextSuspend(): Map.Entry<K, V> {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val current = next ?: throw NoSuchElementException()
                if (!inRange(current.key)) throw NoSuchElementException()
                next = rwLock.withReadLock { successor(current) }
                return TreeEntry(current)
            }
        }
    }

    // HeadMap and TailMap implementations (keeping same as before for brevity)
    private inner class HeadMap(private val toKey: K, private val inclusive: Boolean) : Map<K, V> {
        private fun inRange(key: K): Boolean {
            val cmp = compare(key, toKey)
            return if (inclusive) cmp <= 0 else cmp < 0
        }

        private fun firstNode(): Node<K, V>? = runBlockingMultiplatform {
            rwLock.withReadLock {
                minimum(root)
            }
        }

        override val size: Int
            get() = runBlockingMultiplatform {
                rwLock.withReadLock {
                    var count = 0
                    var current = minimum(root)
                    while (current != null && inRange(current.key)) {
                        count++
                        current = successor(current)
                    }
                    count
                }
            }

        override val keys: Set<K>
            get() = object : Set<K> {
                override val size: Int get() = this@HeadMap.size
                override fun isEmpty(): Boolean = this@HeadMap.isEmpty()
                override fun contains(element: K): Boolean =
                    inRange(element) && this@HeadMap.containsKey(element)

                override fun containsAll(elements: Collection<K>): Boolean =
                    elements.all { contains(it) }

                override fun iterator(): Iterator<K> = HeadMapKeyIterator()
            }

        override val values: Collection<V>
            get() = object : Collection<V> {
                override val size: Int get() = this@HeadMap.size
                override fun isEmpty(): Boolean = this@HeadMap.isEmpty()
                override fun contains(element: V): Boolean = containsValue(element)
                override fun containsAll(elements: Collection<V>): Boolean =
                    elements.all { contains(it) }

                override fun iterator(): Iterator<V> = HeadMapValueIterator()
            }

        override val entries: Set<Map.Entry<K, V>>
            get() = object : Set<Map.Entry<K, V>> {
                override val size: Int get() = this@HeadMap.size
                override fun isEmpty(): Boolean = this@HeadMap.isEmpty()
                override fun contains(element: Map.Entry<K, V>): Boolean {
                    return inRange(element.key) && this@TreeMap.get(element.key) == element.value
                }

                override fun containsAll(elements: Collection<Map.Entry<K, V>>): Boolean =
                    elements.all { contains(it) }

                override fun iterator(): Iterator<Map.Entry<K, V>> = HeadMapEntryIterator()
            }

        override fun isEmpty(): Boolean = runBlockingMultiplatform { isEmptySuspend() }
        
        override fun get(key: K): V? = runBlockingMultiplatform { getSuspend(key) }
        
        override fun containsKey(key: K): Boolean = runBlockingMultiplatform { containsKeySuspend(key) }
        
        suspend fun isEmptySuspend(): Boolean = rwLock.withReadLock { 
            minimum(root)?.let { inRange(it.key) } != true 
        }
        
        suspend fun getSuspend(key: K): V? = 
            if (inRange(key)) this@TreeMap.getSuspend(key) else null
            
        suspend fun containsKeySuspend(key: K): Boolean = 
            inRange(key) && this@TreeMap.containsKeySuspend(key)
        override fun containsValue(value: V): Boolean {
            return runBlockingMultiplatform {
                rwLock.withReadLock {
                    // Optimized: Use single lock with unsafe traversal and early termination
                    var current = minimum(root)
                    while (current != null) {
                        val cmp = compare(current.key, toKey)
                        val inRange = if (inclusive) cmp <= 0 else cmp < 0
                        if (!inRange) break
                        
                        if (current.value == value) {
                            return@withReadLock true
                        }
                        current = successor(current)
                    }
                    false
                }
            }
        }

        private inner class HeadMapKeyIterator : Iterator<K> {
            private var next = firstNode()
            private val expectedModCount = modCount

            override fun hasNext(): Boolean = next != null && inRange(next!!.key)

            override fun next(): K {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                if (!inRange(node.key)) throw NoSuchElementException()
                next = runBlockingMultiplatform {
                    rwLock.withReadLock { successor(node) }
                }
                return node.key
            }

            suspend fun hasNextSuspend(): Boolean = next != null && inRange(next!!.key)

            suspend fun nextSuspend(): K {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                if (!inRange(node.key)) throw NoSuchElementException()
                next = rwLock.withReadLock { successor(node) }
                return node.key
            }
        }

        private inner class HeadMapValueIterator : Iterator<V> {
            private var next = firstNode()
            private val expectedModCount = modCount

            override fun hasNext(): Boolean = next != null && inRange(next!!.key)

            override fun next(): V {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                if (!inRange(node.key)) throw NoSuchElementException()
                next = runBlockingMultiplatform {
                    rwLock.withReadLock { successor(node) }
                }
                return node.value
            }

            suspend fun hasNextSuspend(): Boolean = next != null && inRange(next!!.key)

            suspend fun nextSuspend(): V {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                if (!inRange(node.key)) throw NoSuchElementException()
                next = rwLock.withReadLock { successor(node) }
                return node.value
            }
        }

        private inner class HeadMapEntryIterator : Iterator<Map.Entry<K, V>> {
            private var next = firstNode()
            private val expectedModCount = modCount

            override fun hasNext(): Boolean = next != null && inRange(next!!.key)

            override fun next(): Map.Entry<K, V> {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                if (!inRange(node.key)) throw NoSuchElementException()
                next = runBlockingMultiplatform {
                    rwLock.withReadLock { successor(node) }
                }
                return TreeEntry(node)
            }

            suspend fun hasNextSuspend(): Boolean = next != null && inRange(next!!.key)

            suspend fun nextSuspend(): Map.Entry<K, V> {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                if (!inRange(node.key)) throw NoSuchElementException()
                next = rwLock.withReadLock { successor(node) }
                return TreeEntry(node)
            }
        }
    }

    private inner class TailMap(private val fromKey: K, private val inclusive: Boolean) :
        Map<K, V> {
        private fun inRange(key: K): Boolean {
            val cmp = compare(key, fromKey)
            return if (inclusive) cmp >= 0 else cmp > 0
        }

        private fun firstNode(): Node<K, V>? = runBlockingMultiplatform {
            rwLock.withReadLock {
                ceilingNode(fromKey)?.takeIf { inRange(it.key) }
            }
        }

        override val size: Int
            get() = runBlockingMultiplatform {
                rwLock.withReadLock {
                    var count = 0
                    var current = if (inclusive) ceilingNode(fromKey) else higherNode(fromKey)
                    while (current != null) {
                        count++
                        current = successor(current)
                    }
                    count
                }
            }

        override val keys: Set<K>
            get() = object : Set<K> {
                override val size: Int get() = this@TailMap.size
                override fun isEmpty(): Boolean = this@TailMap.isEmpty()
                override fun contains(element: K): Boolean =
                    inRange(element) && this@TailMap.containsKey(element)

                override fun containsAll(elements: Collection<K>): Boolean =
                    elements.all { contains(it) }

                override fun iterator(): Iterator<K> = TailMapKeyIterator()
            }

        override val values: Collection<V>
            get() = object : Collection<V> {
                override val size: Int get() = this@TailMap.size
                override fun isEmpty(): Boolean = this@TailMap.isEmpty()
                override fun contains(element: V): Boolean = containsValue(element)
                override fun containsAll(elements: Collection<V>): Boolean =
                    elements.all { contains(it) }

                override fun iterator(): Iterator<V> = TailMapValueIterator()
            }

        override val entries: Set<Map.Entry<K, V>>
            get() = object : Set<Map.Entry<K, V>> {
                override val size: Int get() = this@TailMap.size
                override fun isEmpty(): Boolean = this@TailMap.isEmpty()
                override fun contains(element: Map.Entry<K, V>): Boolean {
                    return inRange(element.key) && this@TreeMap.get(element.key) == element.value
                }

                override fun containsAll(elements: Collection<Map.Entry<K, V>>): Boolean =
                    elements.all { contains(it) }

                override fun iterator(): Iterator<Map.Entry<K, V>> = TailMapEntryIterator()
            }

        override fun isEmpty(): Boolean = runBlockingMultiplatform { isEmptySuspend() }
        
        override fun get(key: K): V? = runBlockingMultiplatform { getSuspend(key) }
        
        override fun containsKey(key: K): Boolean = runBlockingMultiplatform { containsKeySuspend(key) }
        
        suspend fun isEmptySuspend(): Boolean = rwLock.withReadLock { 
            ceilingNode(fromKey)?.takeIf { inRange(it.key) } == null 
        }
        
        suspend fun getSuspend(key: K): V? = 
            if (inRange(key)) this@TreeMap.getSuspend(key) else null
            
        suspend fun containsKeySuspend(key: K): Boolean = 
            inRange(key) && this@TreeMap.containsKeySuspend(key)
        override fun containsValue(value: V): Boolean {
            return runBlockingMultiplatform {
                rwLock.withReadLock {
                    // Optimized: Use single lock with unsafe traversal and early termination
                    var current = if (inclusive) ceilingNode(fromKey) else higherNode(fromKey)
                    while (current != null) {
                        if (current.value == value) {
                            return@withReadLock true
                        }
                        current = successor(current)
                    }
                    false
                }
            }
        }

        private inner class TailMapKeyIterator : Iterator<K> {
            private var next = firstNode()
            private val expectedModCount = modCount

            override fun hasNext(): Boolean = next != null

            override fun next(): K {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                next = runBlockingMultiplatform {
                    rwLock.withReadLock { successor(node) }
                }
                return node.key
            }

            suspend fun hasNextSuspend(): Boolean = next != null

            suspend fun nextSuspend(): K {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                next = rwLock.withReadLock { successor(node) }
                return node.key
            }
        }

        private inner class TailMapValueIterator : Iterator<V> {
            private var next = firstNode()
            private val expectedModCount = modCount

            override fun hasNext(): Boolean = next != null

            override fun next(): V {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                next = runBlockingMultiplatform {
                    rwLock.withReadLock { successor(node) }
                }
                return node.value
            }

            suspend fun hasNextSuspend(): Boolean = next != null

            suspend fun nextSuspend(): V {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                next = rwLock.withReadLock { successor(node) }
                return node.value
            }
        }

        private inner class TailMapEntryIterator : Iterator<Map.Entry<K, V>> {
            private var next = firstNode()
            private val expectedModCount = modCount

            override fun hasNext(): Boolean = next != null

            override fun next(): Map.Entry<K, V> {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                next = runBlockingMultiplatform {
                    rwLock.withReadLock { successor(node) }
                }
                return TreeEntry(node)
            }

            suspend fun hasNextSuspend(): Boolean = next != null

            suspend fun nextSuspend(): Map.Entry<K, V> {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                next = rwLock.withReadLock { successor(node) }
                return TreeEntry(node)
            }
        }
    }

    // NavigableSet support
    fun navigableKeySet(): Set<K> = keys
    
    // Suspend version for coroutine contexts
    suspend fun navigableKeySetSuspend(): Set<K> = keys

    /**
     * Creates a shallow copy of this TreeMap.
     *
     * The copy includes:
     * - All key-value mappings from the original
     * - Same comparator and configuration
     * - Same secondary key configuration
     * - Fresh secondary indexes (rebuilt from the copied data)
     *
     * The copy does not include:
     * - Change event subscribers (starts with no subscribers)
     * - Modification count (starts at 0)
     *
     * **Performance**: O(n log n) where n is the number of entries
     */
    fun copy(): TreeMap<K, V> {
        // Use the new constructor with builder pattern only
        val copied = TreeMap<K, V>(comparator) {
            // Recreate each secondary key from the original
            secondaryKeysSpec.forEach { keySpec ->
                key(keySpec.name, keySpec.keyExtractor)
            }
        }
        copied.putAll(this)
        return copied
    }
    
    // Suspend version for coroutine contexts
    suspend fun copySuspend(): TreeMap<K, V> {
        // Use the new constructor with builder pattern only
        val copied = TreeMap<K, V>(comparator) {
            // Recreate each secondary key from the original
            secondaryKeysSpec.forEach { keySpec ->
                key(keySpec.name, keySpec.keyExtractor)
            }
        }
        copied.putAllSuspend(this)
        return copied
    }

    // Standard object methods
    override fun toString(): String {
        return entries.joinToString(", ", "{", "}") { "${it.key}=${it.value}" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Map<*, *>) return false
        if (size != other.size) return false

        return entries.all { entry ->
            other.containsKey(entry.key) && other[entry.key] == entry.value
        }
    }

    override fun hashCode(): Int {
        return entries.sumOf { it.hashCode() }
    }


    // ================================
    // Bulk Operations Builder API
    // ================================

    /**
     * Creates a traversal builder for chaining bulk operations under a single lock.
     * Supports operations like .filter().map().collect() with maximum performance.
     */
    fun traverse(): TraversalBuilder<K, V> = TraversalBuilder(this)

    /**
     * Suspend version of traverse() for coroutine contexts.
     */
    suspend fun traverseSuspend(): SuspendTraversalBuilder<K, V> = SuspendTraversalBuilder(this)

    /**
     * Custom exception for concurrent modification detection during iteration.
     */
    class ConcurrentModificationException : RuntimeException {
        constructor() : super()
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
}
/**
 * Builder for chaining traversal operations under a single lock.
 * All operations are executed atomically within a single read lock.
 */
class TraversalBuilder<K, V>(private val treeMap: TreeMap<K, V>) {
    private val operations = mutableListOf<(TreeMap.Node<K, V>) -> Boolean>()

    /**
     * Filter entries by predicate.
     */
    fun filter(predicate: (Map.Entry<K, V>) -> Boolean): TraversalBuilder<K, V> {
        operations.add { node ->
            val entry = object : Map.Entry<K, V> {
                override val key: K = node.key
                override val value: V = node.value
            }
            predicate(entry)
        }
        return this
    }

    /**
     * Filter by key only.
     */
    fun filterKeys(predicate: (K) -> Boolean): TraversalBuilder<K, V> {
        operations.add { node ->
            predicate(node.key)
        }
        return this
    }

    /**
     * Filter by value only.
     */
    fun filterValues(predicate: (V) -> Boolean): TraversalBuilder<K, V> {
        operations.add { node ->
            predicate(node.value)
        }
        return this
    }

    /**
     * Transform entries (this will change the result type).
     */
    fun <R> map(transform: (Map.Entry<K, V>) -> R): MappedTraversalBuilder<K, V, R> {
        return MappedTraversalBuilder(treeMap, operations, transform)
    }

    /**
     * Transform keys only.
     */
    fun <R> mapKeys(transform: (K) -> R): MappedTraversalBuilder<K, V, R> {
        return MappedTraversalBuilder(treeMap, operations) { entry -> transform(entry.key) }
    }

    /**
     * Transform values only.
     */
    fun <R> mapValues(transform: (V) -> R): MappedTraversalBuilder<K, V, R> {
        return MappedTraversalBuilder(treeMap, operations) { entry -> transform(entry.value) }
    }

    /**
     * Collect filtered entries into a list.
     */
    fun toList(): List<Map.Entry<K, V>> = runBlockingMultiplatform {
        treeMap.rwLock.withReadLock {
            val result = mutableListOf<Map.Entry<K, V>>()
            treeMap.forEachNodeUnsafe { node ->
                if (operations.all { it(node) }) {
                    result.add(object : Map.Entry<K, V> {
                        override val key: K = node.key
                        override val value: V = node.value
                    })
                }
            }
            result
        }
    }

    /**
     * Collect filtered keys into a list.
     */
    fun keys(): List<K> = runBlockingMultiplatform {
        treeMap.rwLock.withReadLock {
            val result = mutableListOf<K>()
            treeMap.forEachNodeUnsafe { node ->
                if (operations.all { it(node) }) {
                    result.add(node.key)
                }
            }
            result
        }
    }

    /**
     * Collect filtered values into a list.
     */
    fun values(): List<V> = runBlockingMultiplatform {
        treeMap.rwLock.withReadLock {
            val result = mutableListOf<V>()
            treeMap.forEachNodeUnsafe { node ->
                if (operations.all { it(node) }) {
                    result.add(node.value)
                }
            }
            result
        }
    }

    /**
     * Count matching entries.
     */
    fun count(): Int = runBlockingMultiplatform {
        treeMap.rwLock.withReadLock {
            var count = 0
            treeMap.forEachNodeUnsafe { node ->
                if (operations.all { it(node) }) {
                    count++
                }
            }
            count
        }
    }

    /**
     * Check if any entry matches.
     */
    fun any(): Boolean = runBlockingMultiplatform {
        treeMap.rwLock.withReadLock {
            // Direct tree traversal for optimal early termination
            var current = treeMap.minimum(treeMap.root)
            while (current != null) {
                if (operations.all { it(current) }) {
                    return@withReadLock true // Early termination
                }
                current = treeMap.successor(current)
            }
            false
        }
    }

    /**
     * Check if all entries match.
     */
    fun all(): Boolean = runBlockingMultiplatform {
        treeMap.rwLock.withReadLock {
            // Direct tree traversal for optimal early termination
            var current = treeMap.minimum(treeMap.root)
            while (current != null) {
                if (!operations.all { it(current) }) {
                    return@withReadLock false // Early termination
                }
                current = treeMap.successor(current)
            }
            true
        }
    }

    /**
     * Get the first matching entry.
     */
    fun first(): Map.Entry<K, V> = runBlockingMultiplatform {
        treeMap.rwLock.withReadLock {
            var current = treeMap.minimum(treeMap.root)
            while (current != null) {
                if (operations.all { it(current) }) {
                    return@withReadLock object : Map.Entry<K, V> {
                        override val key: K = current.key
                        override val value: V = current.value
                    }
                }
                current = treeMap.successor(current)
            }
            throw NoSuchElementException("No matching element found")
        }
    }

    /**
     * Get the first matching entry or null if none found.
     */
    fun firstOrNull(): Map.Entry<K, V>? = runBlockingMultiplatform {
        treeMap.rwLock.withReadLock {
            var current = treeMap.minimum(treeMap.root)
            while (current != null) {
                if (operations.all { it(current) }) {
                    return@withReadLock object : Map.Entry<K, V> {
                        override val key: K = current.key
                        override val value: V = current.value
                    }
                }
                current = treeMap.successor(current)
            }
            null
        }
    }

    /**
     * Take the first n matching entries.
     */
    fun take(n: Int): List<Map.Entry<K, V>> = runBlockingMultiplatform {
        treeMap.rwLock.withReadLock {
            val result = mutableListOf<Map.Entry<K, V>>()
            var current = treeMap.minimum(treeMap.root)
            while (current != null && result.size < n) {
                if (operations.all { it(current) }) {
                    result.add(object : Map.Entry<K, V> {
                        override val key: K = current.key
                        override val value: V = current.value
                    })
                }
                current = treeMap.successor(current)
            }
            result
        }
    }

    /**
     * Skip the first n matching entries and return the rest.
     */
    fun drop(n: Int): List<Map.Entry<K, V>> = runBlockingMultiplatform {
        treeMap.rwLock.withReadLock {
            val result = mutableListOf<Map.Entry<K, V>>()
            var current = treeMap.minimum(treeMap.root)
            var skipped = 0
            while (current != null) {
                if (operations.all { it(current) }) {
                    if (skipped >= n) {
                        result.add(object : Map.Entry<K, V> {
                            override val key: K = current.key
                            override val value: V = current.value
                        })
                    } else {
                        skipped++
                    }
                }
                current = treeMap.successor(current)
            }
            result
        }
    }

    /**
     * Perform an action on each matching entry.
     */
    fun forEach(action: (Map.Entry<K, V>) -> Unit) = runBlockingMultiplatform {
        treeMap.rwLock.withReadLock {
            var current = treeMap.minimum(treeMap.root)
            while (current != null) {
                if (operations.all { it(current) }) {
                    val entry = object : Map.Entry<K, V> {
                        override val key: K = current.key
                        override val value: V = current.value
                    }
                    action(entry)
                }
                current = treeMap.successor(current)
            }
        }
    }
}

/**
 * Builder for mapped traversal operations.
 */
class MappedTraversalBuilder<K, V, R> internal constructor(
    private val treeMap: TreeMap<K, V>,
    internal val operations: List<(TreeMap.Node<K, V>) -> Boolean>, // Hide Node type as Any
    private val transform: (Map.Entry<K, V>) -> R
) {
    /**
     * Collect transformed results into a list.
     */
    fun toList(): List<R> = runBlockingMultiplatform {
        treeMap.rwLock.withReadLock {
            val result = mutableListOf<R>()
            treeMap.forEachNodeUnsafe { node ->
                if (operations.all { it(node) }) {
                    val entry = object : Map.Entry<K, V> {
                        override val key: K = node.key
                        override val value: V = node.value
                    }
                    result.add(transform(entry))
                }
            }
            result
        }
    }
}

/**
 * Suspend version of TraversalBuilder for optimal performance in coroutine contexts.
 */
class SuspendTraversalBuilder<K, V>(private val treeMap: TreeMap<K, V>) {
    private val operations = mutableListOf<(TreeMap.Node<K, V>) -> Boolean>()
    
    /**
     * Filter entries by predicate.
     */
    fun filter(predicate: (Map.Entry<K, V>) -> Boolean): SuspendTraversalBuilder<K, V> {
        operations.add { node ->
            val entry = object : Map.Entry<K, V> {
                override val key: K = node.key
                override val value: V = node.value
            }
            predicate(entry)
        }
        return this
    }

    /**
     * Filter by key only.
     */
    fun filterKeys(predicate: (K) -> Boolean): SuspendTraversalBuilder<K, V> {
        operations.add { node ->
            predicate(node.key)
        }
        return this
    }

    /**
     * Filter by value only.
     */
    fun filterValues(predicate: (V) -> Boolean): SuspendTraversalBuilder<K, V> {
        operations.add { node ->
            predicate(node.value)
        }
        return this
    }

    /**
     * Transform entries (this will change the result type).
     */
    fun <R> map(transform: (Map.Entry<K, V>) -> R): SuspendMappedTraversalBuilder<K, V, R> {
        return SuspendMappedTraversalBuilder(treeMap, operations, transform)
    }

    /**
     * Transform keys only.
     */
    fun <R> mapKeys(transform: (K) -> R): SuspendMappedTraversalBuilder<K, V, R> {
        return SuspendMappedTraversalBuilder(treeMap, operations) { entry -> transform(entry.key) }
    }

    /**
     * Transform values only.
     */
    fun <R> mapValues(transform: (V) -> R): SuspendMappedTraversalBuilder<K, V, R> {
        return SuspendMappedTraversalBuilder(treeMap, operations) { entry -> transform(entry.value) }
    }

    /**
     * Collect filtered entries into a list.
     */
    suspend fun toList(): List<Map.Entry<K, V>> = 
        treeMap.rwLock.withReadLock {
            val result = mutableListOf<Map.Entry<K, V>>()
            treeMap.forEachNodeUnsafe { node ->
                if (operations.all { it(node) }) {
                    result.add(object : Map.Entry<K, V> {
                        override val key: K = node.key
                        override val value: V = node.value
                    })
                }
            }
            result
        }

    /**
     * Collect filtered keys into a list.
     */
    suspend fun keys(): List<K> = 
        treeMap.rwLock.withReadLock {
            val result = mutableListOf<K>()
            treeMap.forEachNodeUnsafe { node ->
                if (operations.all { it(node) }) {
                    result.add(node.key)
                }
            }
            result
        }

    /**
     * Collect filtered values into a list.
     */
    suspend fun values(): List<V> = 
        treeMap.rwLock.withReadLock {
            val result = mutableListOf<V>()
            treeMap.forEachNodeUnsafe { node ->
                if (operations.all { it(node) }) {
                    result.add(node.value)
                }
            }
            result
        }

    /**
     * Count matching entries.
     */
    suspend fun count(): Int = 
        treeMap.rwLock.withReadLock {
            var count = 0
            treeMap.forEachNodeUnsafe { node ->
                if (operations.all { it(node) }) {
                    count++
                }
            }
            count
        }

    /**
     * Check if any entry matches.
     */
    suspend fun any(): Boolean = 
        treeMap.rwLock.withReadLock {
            // Direct tree traversal for optimal early termination
            var current = treeMap.minimum(treeMap.root)
            while (current != null) {
                if (operations.all { it(current) }) {
                    return@withReadLock true // Early termination
                }
                current = treeMap.successor(current)
            }
            false
        }

    /**
     * Check if all entries match.
     */
    suspend fun all(): Boolean = 
        treeMap.rwLock.withReadLock {
            // Direct tree traversal for optimal early termination
            var current = treeMap.minimum(treeMap.root)
            while (current != null) {
                if (!operations.all { it(current) }) {
                    return@withReadLock false // Early termination
                }
                current = treeMap.successor(current)
            }
            true
        }

    /**
     * Get the first matching entry.
     */
    suspend fun first(): Map.Entry<K, V> =
        treeMap.rwLock.withReadLock {
            var current = treeMap.minimum(treeMap.root)
            while (current != null) {
                if (operations.all { it(current) }) {
                    return@withReadLock object : Map.Entry<K, V> {
                        override val key: K = current.key
                        override val value: V = current.value
                    }
                }
                current = treeMap.successor(current)
            }
            throw NoSuchElementException("No matching element found")
        }

    /**
     * Get the first matching entry or null if none found.
     */
    suspend fun firstOrNull(): Map.Entry<K, V>? =
        treeMap.rwLock.withReadLock {
            var current = treeMap.minimum(treeMap.root)
            while (current != null) {
                if (operations.all { it(current) }) {
                    return@withReadLock object : Map.Entry<K, V> {
                        override val key: K = current.key
                        override val value: V = current.value
                    }
                }
                current = treeMap.successor(current)
            }
            null
        }

    /**
     * Take the first n matching entries.
     */
    suspend fun take(n: Int): List<Map.Entry<K, V>> =
        treeMap.rwLock.withReadLock {
            val result = mutableListOf<Map.Entry<K, V>>()
            var current = treeMap.minimum(treeMap.root)
            while (current != null && result.size < n) {
                if (operations.all { it(current) }) {
                    result.add(object : Map.Entry<K, V> {
                        override val key: K = current.key
                        override val value: V = current.value
                    })
                }
                current = treeMap.successor(current)
            }
            result
        }

    /**
     * Skip the first n matching entries and return the rest.
     */
    suspend fun drop(n: Int): List<Map.Entry<K, V>> =
        treeMap.rwLock.withReadLock {
            val result = mutableListOf<Map.Entry<K, V>>()
            var current = treeMap.minimum(treeMap.root)
            var skipped = 0
            while (current != null) {
                if (operations.all { it(current) }) {
                    if (skipped >= n) {
                        result.add(object : Map.Entry<K, V> {
                            override val key: K = current.key
                            override val value: V = current.value
                        })
                    } else {
                        skipped++
                    }
                }
                current = treeMap.successor(current)
            }
            result
        }

    /**
     * Perform an action on each matching entry.
     */
    suspend fun forEach(action: (Map.Entry<K, V>) -> Unit) =
        treeMap.rwLock.withReadLock {
            var current = treeMap.minimum(treeMap.root)
            while (current != null) {
                if (operations.all { it(current) }) {
                    val entry = object : Map.Entry<K, V> {
                        override val key: K = current.key
                        override val value: V = current.value
                    }
                    action(entry)
                }
                current = treeMap.successor(current)
            }
        }
}

/**
 * Suspend version of MappedTraversalBuilder.
 */
class SuspendMappedTraversalBuilder<K, V, R> internal constructor(
    private val treeMap: TreeMap<K, V>,
    internal val operations: List<(TreeMap.Node<K, V>) -> Boolean>, // Hide Node type as Any
    private val transform: (Map.Entry<K, V>) -> R
) {
    /**
     * Collect transformed results into a list.
     */
    suspend fun toList(): List<R> = 
        treeMap.rwLock.withReadLock {
            val result = mutableListOf<R>()
            treeMap.forEachNodeUnsafe { node ->
                if (operations.all { it(node) }) {
                    val entry = object : Map.Entry<K, V> {
                        override val key: K = node.key
                        override val value: V = node.value
                    }
                    result.add(transform(entry))
                }
            }
            result
        }
}