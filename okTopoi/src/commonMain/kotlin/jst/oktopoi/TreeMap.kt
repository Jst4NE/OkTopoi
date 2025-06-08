package jst.oktopoi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.concurrent.Volatile

/**
 * A Red-Black Tree based TreeMap implementation for Kotlin Multiplatform
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
 * This implementation is **NOT thread-safe**. For concurrent access, use external synchronization.
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

    private val secondaryIndexes: MutableMap<String, MutableMap<Any?, MutableSet<K>>>

    /**
     * Builder for creating secondary key configurations in a fluent way
     */
    class SecondaryKeyBuilder<V> {
        private val keys = mutableListOf<TreeMap.Key<V, *>>()

        /**
         * Adds a secondary key with the given name and extractor function
         *
         * @param name unique name for this secondary key
         * @param extractor function that extracts the secondary key value from the main value
         */
        fun <SK> key(name: String, extractor: (V) -> SK) {
            require(keys.none { it.name == name }) { "Duplicate secondary key name: $name" }
            keys.add(TreeMap.Key(name, extractor))
        }

        /**
         * Builds the final list of secondary keys
         */
        internal fun build(): List<TreeMap.Key<V, *>> = keys.toList()
    }

    /**
     * Color enum for Red-Black Tree nodes
     */
    private enum class Color { RED, BLACK }

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
    fun <SK> getBySecondaryKey(keyName: String, keyValue: SK): List<V> {
        return ((secondaryIndexes[keyName] ?: mutableMapOf())[keyValue]
            ?: setOf()).map { this[it]!! }
    }

    // Modern Map API additions

    /**
     * If the specified key is not already associated with a value, associates it with the given value.
     *
     * @return the previous value associated with the key, or null if there was no mapping
     */
    fun putIfAbsent(key: K, value: V): V? {
        val node = findNode(key)
        if (node == null) {
            return put(key, value)
        }
        return node.value
    }

    /**
     * Replaces the entry for the specified key only if it is currently mapped to some value.
     *
     * @return the previous value associated with the key, or null if there was no mapping
     */
    fun replace(key: K, value: V): V? {
        val node = findNode(key)
        return if (node != null) {
            nodePut(node, key, value)
        } else {
            null
        }
    }

    /**
     * Replaces the entry for the specified key only if currently mapped to the specified value.
     *
     * @return true if the value was replaced
     */
    fun replace(key: K, expectedValue: V, newValue: V): Boolean {
        val node = findNode(key)
        return if (node != null && node.value == expectedValue) {
            nodePut(node, key, newValue)
            true
        } else {
            return false
        }
    }

    /**
     * Removes the entry for the specified key only if it is currently mapped to the specified value.
     *
     * @return true if the value was removed
     */
    fun remove(key: K, value: V): Boolean {
        val node = findNode(key) ?: return false
        return if (node.value == value) {
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
    fun compute(key: K, remappingFunction: (K, V?) -> V?): V? {
        val node = findNode(key)
        val newValue: V?
        if (node == null) {
            newValue = remappingFunction(key, null)
            if (newValue != null) {
                put(key, newValue)
            }
        } else if (node.value == null) {
            newValue = remappingFunction(key, null)
            if (newValue != null) {
                nodePut(node, key, newValue)
            } else {
                nodeRemove(node)
            }
        } else {
            newValue = remappingFunction(key, node.value)
            if (newValue != null) {
                nodePut(node, key, newValue)
            } else {
                nodeRemove(node)
            }
        }
        return newValue
    }

    /**
     * If the specified key is not already associated with a value, computes its value using the mapping function.
     *
     * @param mappingFunction the function to compute a value
     * @return the current (existing or computed) value associated with the key
     */
    fun computeIfAbsent(key: K, mappingFunction: (K) -> V): V {
        val node = findNode(key)
        if (node == null) {
            val newValue = mappingFunction(key)
            put(key, newValue)
            return newValue
        } else {
            return node.value
        }
    }

    /**
     * If the value for the specified key is present, computes a new mapping given the key and its current value.
     *
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the key, or null if none
     */
    fun computeIfPresent(key: K, remappingFunction: (K, V) -> V): V? {
        val node = findNode(key)
        if (node != null) {
            val newValue = remappingFunction(key, node.value)
            nodePut(node, key, newValue)
            return newValue
        } else {
            return null
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
    fun merge(key: K, value: V, remappingFunction: (oldValue: V?, newValue: V) -> V?): V? {
        val node = findNode(key)
        val newValue: V?
        if (node == null) {
            newValue = remappingFunction(null, value)
            if (newValue != null) {
                put(key, newValue)
            }
        } else if (node.value == null) {
            newValue = remappingFunction(null, value)
            if (newValue != null) {
                nodePut(node, key, newValue)
            } else {
                nodeRemove(node)
            }
        } else {
            newValue = remappingFunction(node.value, value)
            if (newValue != null) {
                nodePut(node, key, newValue)
            } else {
                nodeRemove(node)
            }
        }
        return newValue
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
    private class Node<K, V>(
        var key: K,
        var value: V,
        var color: Color = Color.RED,
        var left: Node<K, V>? = null,
        var right: Node<K, V>? = null,
        var parent: Node<K, V>? = null
    ) {
        override fun toString(): String = "Node(key=$key, value=$value, color=$color)"
    }

    private var root: Node<K, V>? = null
    private var _size = 0

    override val size: Int get() = _size
    override val keys: MutableSet<K> get() = KeySet()
    override val values: MutableCollection<V> get() = ValueCollection()
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = EntrySet()

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
    override fun put(key: K, value: V): V? {
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

        return oldValueOldNode.first
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
    override fun get(key: K): V? {
        return findNode(key)?.value
    }

    /**
     * Removes the mapping for the specified key from this map if present.
     *
     * @param key the key whose mapping is to be removed
     * @return the previous value associated with key, or null if no mapping existed
     */
    override fun remove(key: K): V? {
        val node = findNode(key) ?: return null
        return nodeRemove(node)
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
    override fun containsKey(key: K): Boolean = findNode(key) != null

    /**
     * Returns true if this map maps one or more keys to the specified value.
     */
    override fun containsValue(value: V): Boolean {
        var current = minimum(root)
        while (current != null) {
            if (current.value == value) return true
            current = successor(current)
        }
        return false
    }

    /**
     * Returns true if this map contains no key-value mappings.
     */
    override fun isEmpty(): Boolean = _size == 0

    /**
     * Removes all mappings from this map.
     *
     * Clears both the primary tree structure and all secondary indexes.
     * Emits a Cleared change event.
     *
     * **Performance**: O(1) for tree, O(k*s) for secondary indexes where k is number of
     * secondary keys and s is number of unique secondary key values.
     */
    override fun clear() {
        val previousSize = _size

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
    override fun putAll(from: Map<out K, V>) {
        from.forEach { (key, value) ->
            updateSecondaryKeys(
                key,
                findOrCreateNodeAndPut(key, value).first,
                value
            )
        }
        emitChange(MapChange.Rebuild())
    }

    // NavigableMap-like functionality

    /**
     * Returns the first (lowest) key currently in this map, or null if the map is empty.
     *
     * **Performance**: O(log n)
     */
    fun firstKey(): K? = minimum(root)?.key

    /**
     * Returns the last (highest) key currently in this map, or null if the map is empty.
     *
     * **Performance**: O(log n)
     */
    fun lastKey(): K? = maximum(root)?.key

    /**
     * Returns a key-value mapping associated with the least key in this map, or null if the map is empty.
     *
     * **Performance**: O(log n)
     */
    fun firstEntry(): MutableMap.MutableEntry<K, V>? = minimum(root)?.let { TreeEntry(it) }

    /**
     * Returns a key-value mapping associated with the greatest key in this map, or null if the map is empty.
     *
     * **Performance**: O(log n)
     */
    fun lastEntry(): MutableMap.MutableEntry<K, V>? = maximum(root)?.let { TreeEntry(it) }

    /**
     * Returns the greatest key strictly less than the given key, or null if no such key exists.
     */
    fun lowerKey(key: K): K? = lowerNode(key)?.key

    /**
     * Returns the greatest key less than or equal to the given key, or null if no such key exists.
     */
    fun floorKey(key: K): K? = floorNode(key)?.key

    /**
     * Returns the least key greater than or equal to the given key, or null if no such key exists.
     */
    fun ceilingKey(key: K): K? = ceilingNode(key)?.key

    /**
     * Returns the least key strictly greater than the given key, or null if no such key exists.
     */
    fun higherKey(key: K): K? = higherNode(key)?.key

    fun lowerEntry(key: K): MutableMap.MutableEntry<K, V>? = lowerNode(key)?.let { TreeEntry(it) }
    fun floorEntry(key: K): MutableMap.MutableEntry<K, V>? = floorNode(key)?.let { TreeEntry(it) }
    fun ceilingEntry(key: K): MutableMap.MutableEntry<K, V>? =
        ceilingNode(key)?.let { TreeEntry(it) }

    fun higherEntry(key: K): MutableMap.MutableEntry<K, V>? = higherNode(key)?.let { TreeEntry(it) }

    /**
     * Removes and returns a key-value mapping associated with the least key in this map,
     * or null if the map is empty.
     *
     * The returned entry is detached from the map. Calling setValue() on the returned
     * entry will NOT update the map.
     *
     * @return a detached entry that was removed from the map, or null if the map was empty
     */
    fun pollFirstEntry(): MutableMap.MutableEntry<K, V>? {
        val first = minimum(root) ?: return null
        val key = first.key
        val value = first.value
        remove(key)
        return DetachedEntry(key, value)
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
    fun pollLastEntry(): MutableMap.MutableEntry<K, V>? {
        val last = maximum(root) ?: return null
        val key = last.key
        val value = last.value
        remove(key)
        return DetachedEntry(key, value)
    }

    fun descendingKeySet(): Set<K> = DescendingKeySet()
    fun descendingMap(): Map<K, V> = DescendingMap()

    fun subMap(
        fromKey: K,
        fromInclusive: Boolean = true,
        toKey: K,
        toInclusive: Boolean = false
    ): Map<K, V> {
        return SubMap(fromKey, fromInclusive, toKey, toInclusive)
    }

    fun headMap(toKey: K, inclusive: Boolean = false): Map<K, V> {
        return HeadMap(toKey, inclusive)
    }

    fun tailMap(fromKey: K, inclusive: Boolean = true): Map<K, V> {
        return TailMap(fromKey, inclusive)
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
    private fun findNode(key: K): Node<K, V>? {
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

    private fun minimum(node: Node<K, V>?): Node<K, V>? {
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

    private fun successor(node: Node<K, V>): Node<K, V>? {
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
        override fun setValue(newValue: V): V {
            val oldValue = node.value

            // Apply changes
            node.value = newValue

            updateSecondaryKeys(key, oldValue, newValue)

            // Emit change notification
            emitChange(MapChange.Put(key, newValue, true, oldValue))

            return oldValue
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
        override fun contains(element: K): Boolean = containsKey(element)
        override fun containsAll(elements: Collection<K>): Boolean = elements.all { contains(it) }

        override fun add(element: K): Boolean =
            throw UnsupportedOperationException("Cannot add key without value")

        override fun addAll(elements: Collection<K>): Boolean =
            throw UnsupportedOperationException("Cannot add keys without values")

        override fun remove(element: K): Boolean = this@TreeMap.remove(element) != null
        override fun removeAll(elements: Collection<K>): Boolean {
            var modified = false
            elements.forEach { if (remove(it)) modified = true }
            return modified
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
        override fun contains(element: V): Boolean = containsValue(element)
        override fun containsAll(elements: Collection<V>): Boolean = elements.all { contains(it) }

        override fun add(element: V): Boolean =
            throw UnsupportedOperationException("Cannot add value without key")

        override fun addAll(elements: Collection<V>): Boolean =
            throw UnsupportedOperationException("Cannot add values without keys")

        override fun remove(element: V): Boolean {
            var current = minimum(root)
            while (current != null) {
                if (current.value == element) {
                    this@TreeMap.remove(current.key)
                    return true
                }
                current = successor(current)
            }
            return false
        }

        override fun removeAll(elements: Collection<V>): Boolean {
            var modified = false
            elements.forEach { if (remove(it)) modified = true }
            return modified
        }

        override fun retainAll(elements: Collection<V>): Boolean {
            val toKeep = elements.toSet()
            val toRemove = mutableListOf<K>()
            var current = minimum(root)
            while (current != null) {
                if (current.value !in toKeep) {
                    toRemove.add(current.key)
                }
                current = successor(current)
            }
            var modified = false
            toRemove.forEach {
                this@TreeMap.remove(it)
                modified = true
            }
            return modified
        }

        override fun clear() = this@TreeMap.clear()
        override fun iterator(): MutableIterator<V> = ValueIterator()
    }

    private inner class EntrySet : MutableSet<MutableMap.MutableEntry<K, V>> {
        override val size: Int get() = this@TreeMap.size
        override fun isEmpty(): Boolean = this@TreeMap.isEmpty()
        override fun contains(element: MutableMap.MutableEntry<K, V>): Boolean {
            val value = get(element.key)
            return value != null && value == element.value
        }

        override fun containsAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean =
            elements.all { contains(it) }

        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean {
            val oldValue = put(element.key, element.value)
            return oldValue != element.value
        }

        override fun addAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean {
            var modified = false
            elements.forEach { if (add(it)) modified = true }
            return modified
        }

        override fun remove(element: MutableMap.MutableEntry<K, V>): Boolean {
            val current = get(element.key)
            return if (current == element.value) {
                this@TreeMap.remove(element.key) != null
            } else false
        }

        override fun removeAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean {
            var modified = false
            elements.forEach { if (remove(it)) modified = true }
            return modified
        }

        override fun retainAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean {
            val toKeep = elements.associate { it.key to it.value }
            val toRemove = mutableListOf<K>()
            var current = minimum(root)
            while (current != null) {
                if (toKeep[current.key] != current.value) {
                    toRemove.add(current.key)
                }
                current = successor(current)
            }
            var modified = false
            toRemove.forEach {
                this@TreeMap.remove(it)
                modified = true
            }
            return modified
        }

        override fun clear() = this@TreeMap.clear()
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> = EntryIterator()
    }

    // Iterators with concurrent modification detection
    private abstract inner class TreeIterator<T> : MutableIterator<T> {
        private var expectedModCount = modCount
        private var nextNode: Node<K, V>? = minimum(root)
        private var lastReturned: Node<K, V>? = null

        override fun hasNext(): Boolean = nextNode != null

        override fun next(): T {
            checkForModification()
            val current = nextNode ?: throw NoSuchElementException("No more elements in iterator")
            lastReturned = current
            nextNode = successor(current)
            return getValue(current)
        }

        override fun remove() {
            checkForModification()
            val toRemove = lastReturned ?: throw IllegalStateException(
                "remove() called before next() or remove() already called"
            )

            val keyToRemove = toRemove.key

            // If nextNode is the node we're removing, advance it first
            if (nextNode == toRemove) {
                nextNode = successor(toRemove)
            }

            // Remove using the public API
            this@TreeMap.remove(keyToRemove)

            // Update expected mod count
            expectedModCount = modCount
            lastReturned = null
        }

        private fun checkForModification() {
            if (modCount != expectedModCount) {
                throw ConcurrentModificationException("TreeMap modified during iteration")
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
        override fun get(key: K): V? = this@TreeMap[key]
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

    // Descending iterators
    private abstract inner class DescendingTreeIterator<T> : MutableIterator<T> {
        private var expectedModCount = modCount
        private var nextNode: Node<K, V>? = maximum(root)
        private var lastReturned: Node<K, V>? = null

        override fun hasNext(): Boolean {
            checkForModification()
            return nextNode != null
        }

        override fun next(): T {
            checkForModification()
            val current =
                nextNode ?: throw NoSuchElementException("No more elements in descending iterator")
            lastReturned = current
            nextNode = predecessor(current)
            return getValue(current)
        }

        override fun remove() {
            checkForModification()
            val toRemove = lastReturned ?: throw IllegalStateException(
                "remove() called before next() or remove() already called"
            )

            val keyToRemove = toRemove.key

            // If nextNode is the node we're removing, it's already correctly set to predecessor
            // No need to adjust nextNode since we already moved past this node

            // Remove using the public API
            this@TreeMap.remove(keyToRemove)

            // Update expected mod count
            expectedModCount = modCount
            lastReturned = null
        }

        private fun checkForModification() {
            if (modCount != expectedModCount) {
                throw ConcurrentModificationException("TreeMap modified during iteration")
            }
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

    // Sub-map implementations - (kept same as before for brevity)
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
            get() {
                var count = 0
                var node = firstNode()
                while (node != null && inRange(node.key)) {
                    count++
                    node = successor(node)
                }
                return count
            }

        override val keys: Set<K>
            get() = object : Set<K> {
                override val size: Int get() = this@SubMap.size
                override fun isEmpty(): Boolean = this@SubMap.isEmpty()
                override fun contains(element: K): Boolean =
                    inRange(element) && containsKey(element)

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
                    return inRange(element.key) && this@TreeMap[element.key] == element.value
                }

                override fun containsAll(elements: Collection<Map.Entry<K, V>>): Boolean =
                    elements.all { contains(it) }

                override fun iterator(): Iterator<Map.Entry<K, V>> = SubMapEntryIterator()
            }

        private fun firstNode(): Node<K, V>? {
            return ceilingNode(fromKey)?.takeIf { inRange(it.key) }
        }

        override fun isEmpty(): Boolean = firstNode() == null
        override fun get(key: K): V? = if (inRange(key)) this@TreeMap[key] else null
        override fun containsKey(key: K): Boolean = inRange(key) && this@TreeMap.containsKey(key)
        override fun containsValue(value: V): Boolean {
            var node = firstNode()
            while (node != null && inRange(node.key)) {
                if (node.value == value) return true
                node = successor(node)
            }
            return false
        }

        private inner class SubMapKeyIterator : Iterator<K> {
            private var next = firstNode()
            private val expectedModCount = modCount

            override fun hasNext(): Boolean = next != null && inRange(next!!.key)

            override fun next(): K {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val current = next ?: throw NoSuchElementException()
                if (!inRange(current.key)) throw NoSuchElementException()
                next = successor(current)
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
                next = successor(current)
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
                next = successor(current)
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

        private fun firstNode(): Node<K, V>? = minimum(root)

        override val size: Int
            get() {
                var count = 0
                var node = firstNode()
                while (node != null && inRange(node.key)) {
                    count++
                    node = successor(node)
                }
                return count
            }

        override val keys: Set<K>
            get() = object : Set<K> {
                override val size: Int get() = this@HeadMap.size
                override fun isEmpty(): Boolean = this@HeadMap.isEmpty()
                override fun contains(element: K): Boolean =
                    inRange(element) && containsKey(element)

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
                    return inRange(element.key) && this@TreeMap[element.key] == element.value
                }

                override fun containsAll(elements: Collection<Map.Entry<K, V>>): Boolean =
                    elements.all { contains(it) }

                override fun iterator(): Iterator<Map.Entry<K, V>> = HeadMapEntryIterator()
            }

        override fun isEmpty(): Boolean = firstNode()?.let { inRange(it.key) } != true
        override fun get(key: K): V? = if (inRange(key)) this@TreeMap[key] else null
        override fun containsKey(key: K): Boolean = inRange(key) && this@TreeMap.containsKey(key)
        override fun containsValue(value: V): Boolean {
            var node = firstNode()
            while (node != null && inRange(node.key)) {
                if (node.value == value) return true
                node = successor(node)
            }
            return false
        }

        private inner class HeadMapKeyIterator : Iterator<K> {
            private var next = firstNode()
            private val expectedModCount = modCount

            override fun hasNext(): Boolean = next != null && inRange(next!!.key)

            override fun next(): K {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                if (!inRange(node.key)) throw NoSuchElementException()
                next = successor(node)
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
                next = successor(node)
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
                next = successor(node)
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

        private fun firstNode(): Node<K, V>? {
            return ceilingNode(fromKey)?.takeIf { inRange(it.key) }
        }

        override val size: Int
            get() {
                var count = 0
                var node = firstNode()
                while (node != null) {
                    count++
                    node = successor(node)
                }
                return count
            }

        override val keys: Set<K>
            get() = object : Set<K> {
                override val size: Int get() = this@TailMap.size
                override fun isEmpty(): Boolean = this@TailMap.isEmpty()
                override fun contains(element: K): Boolean =
                    inRange(element) && containsKey(element)

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
                    return inRange(element.key) && this@TreeMap[element.key] == element.value
                }

                override fun containsAll(elements: Collection<Map.Entry<K, V>>): Boolean =
                    elements.all { contains(it) }

                override fun iterator(): Iterator<Map.Entry<K, V>> = TailMapEntryIterator()
            }

        override fun isEmpty(): Boolean = firstNode() == null
        override fun get(key: K): V? = if (inRange(key)) this@TreeMap[key] else null
        override fun containsKey(key: K): Boolean = inRange(key) && this@TreeMap.containsKey(key)
        override fun containsValue(value: V): Boolean {
            var node = firstNode()
            while (node != null) {
                if (node.value == value) return true
                node = successor(node)
            }
            return false
        }

        private inner class TailMapKeyIterator : Iterator<K> {
            private var next = firstNode()
            private val expectedModCount = modCount

            override fun hasNext(): Boolean = next != null

            override fun next(): K {
                if (modCount != expectedModCount) throw ConcurrentModificationException()
                val node = next ?: throw NoSuchElementException()
                next = successor(node)
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
                next = successor(node)
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
                next = successor(node)
                return TreeEntry(node)
            }
        }
    }

    // NavigableSet support
    fun navigableKeySet(): Set<K> = keys

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

    /**
     * Custom exception for concurrent modification detection during iteration.
     */
    class ConcurrentModificationException : RuntimeException {
        constructor() : super()
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
}