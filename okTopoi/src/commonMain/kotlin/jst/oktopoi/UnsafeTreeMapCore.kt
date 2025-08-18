package jst.oktopoi

/**
 * Thread-unsafe red-black tree implementation that serves as the core for all TreeMap operations.
 * 
 * This class provides a complete implementation of both MutableMap and NavigableMap operations
 * using a red-black tree data structure. It is designed to be wrapped by thread-safe views
 * (BlockingTreeMapView and SuspendTreeMapView) that handle concurrency concerns.
 * 
 * Performance characteristics:
 * - All basic operations (get, put, remove): O(log n)
 * - Range operations: O(log n + k) where k is the number of elements in range
 * - Space complexity: O(n)
 * 
 * Red-black tree invariants maintained:
 * 1. Every node is either red or black
 * 2. Root is always black
 * 3. Red nodes have only black children
 * 4. All paths from root to leaves contain the same number of black nodes
 * 
 * @param K the type of keys maintained by this tree
 * @param V the type of mapped values
 */
open class UnsafeTreeMapCore<K, V> internal constructor(
    protected val keyComparator: Comparator<K>
) {
    
    /**
     * Red-black tree node implementation.
     * 
     * Each node maintains:
     * - Key-value pair
     * - Color (red or black) for balancing
     * - Parent, left, and right child references
     * - Size of subtree rooted at this node (for efficient rank operations)
     */
    internal class Node<K, V>(
        var key: K,
        var value: V,
        var color: Color = Color.RED,
        var parent: Node<K, V>? = null,
        var left: Node<K, V>? = null,
        var right: Node<K, V>? = null,
        var size: Int = 1  // Size of subtree rooted at this node
    ) {
        
        /**
         * Updates the size of this node based on its children.
         * Must be called after any structural changes to maintain correct size information.
         */
        fun updateSize() {
            size = 1 + (left?.size ?: 0) + (right?.size ?: 0)
        }
        
        /**
         * Returns the minimum node in the subtree rooted at this node.
         */
        fun minimum(): Node<K, V> {
            var current = this
            while (current.left != null) {
                current = current.left!!
            }
            return current
        }
        
        /**
         * Returns the maximum node in the subtree rooted at this node.
         */
        fun maximum(): Node<K, V> {
            var current = this
            while (current.right != null) {
                current = current.right!!
            }
            return current
        }
        
        /**
         * Returns the successor of this node in the tree (next node in inorder traversal).
         * Returns null if this is the maximum node.
         */
        fun successor(): Node<K, V>? {
            // If right subtree exists, successor is minimum of right subtree
            right?.let { return it.minimum() }
            
            // Otherwise, successor is the first ancestor for which this node is in left subtree
            var current = this
            var parent = this.parent
            while (parent != null && current == parent.right) {
                current = parent
                parent = parent.parent
            }
            return parent
        }
        
        /**
         * Returns the predecessor of this node in the tree (previous node in inorder traversal).
         * Returns null if this is the minimum node.
         */
        fun predecessor(): Node<K, V>? {
            // If left subtree exists, predecessor is maximum of left subtree
            left?.let { return it.maximum() }
            
            // Otherwise, predecessor is the first ancestor for which this node is in right subtree
            var current = this
            var parent = this.parent
            while (parent != null && current == parent.left) {
                current = parent
                parent = parent.parent
            }
            return parent
        }
    }
    
    /**
     * Colors for red-black tree nodes.
     */
    internal enum class Color {
        RED, BLACK
    }
    
    // Core tree state
    private var root: Node<K, V>? = null
    private var _size: Int = 0
    
    /**
     * The number of key-value mappings in this tree.
     */
    protected val sizeUnsafe: Int get() = _size
    
    /**
     * Returns true if this tree contains no key-value mappings.
     */
    protected val isEmptyUnsafe: Boolean get() = _size == 0

    protected open fun isEmpty(): Boolean = isEmptyUnsafe
    
    
    
    // ========================================================================
    // Core Red-Black Tree Operations
    // ========================================================================
    
    /**
     * Performs left rotation around the given node.
     * 
     * Before:     x              After:      y
     *           /   \                      /   \
     *          α     y         =>         x     γ
     *              /   \                /   \
     *             β     γ              α     β
     */
    private fun rotateLeft(x: Node<K, V>) {
        val y = x.right!!
        
        // Turn y's left subtree into x's right subtree
        x.right = y.left
        y.left?.parent = x
        
        // Link x's parent to y
        y.parent = x.parent
        when {
            x.parent == null -> root = y  // x was root
            x == x.parent!!.left -> x.parent!!.left = y
            else -> x.parent!!.right = y
        }
        
        // Put x on y's left
        y.left = x
        x.parent = y
        
        // Update sizes
        x.updateSize()
        y.updateSize()
    }
    
    /**
     * Performs right rotation around the given node.
     * 
     * Before:       y            After:    x
     *             /   \                  /   \
     *            x     γ       =>       α     y
     *          /   \                        /   \
     *         α     β                      β     γ
     */
    private fun rotateRight(y: Node<K, V>) {
        val x = y.left!!
        
        // Turn x's right subtree into y's left subtree
        y.left = x.right
        x.right?.parent = y
        
        // Link y's parent to x
        x.parent = y.parent
        when {
            y.parent == null -> root = x  // y was root
            y == y.parent!!.left -> y.parent!!.left = x
            else -> y.parent!!.right = x
        }
        
        // Put y on x's right
        x.right = y
        y.parent = x
        
        // Update sizes
        y.updateSize()
        x.updateSize()
    }
    
    /**
     * Fixes red-black tree violations after insertion.
     * Maintains all red-black tree invariants.
     */
    private fun insertFixup(node: Node<K, V>) {
        var z = node
        
        while (z.parent?.color == Color.RED) {
            val parent = z.parent!!
            val grandparent = parent.parent!!
            
            if (parent == grandparent.left) {
                // Parent is left child of grandparent
                val uncle = grandparent.right
                
                if (uncle?.color == Color.RED) {
                    // Case 1: Uncle is red - recolor and move up
                    parent.color = Color.BLACK
                    uncle.color = Color.BLACK
                    grandparent.color = Color.RED
                    z = grandparent
                } else {
                    // Uncle is black
                    if (z == parent.right) {
                        // Case 2: z is right child - left rotate
                        z = parent
                        rotateLeft(z)
                    }
                    // Case 3: z is left child - recolor and right rotate
                    z.parent!!.color = Color.BLACK
                    z.parent!!.parent!!.color = Color.RED
                    rotateRight(z.parent!!.parent!!)
                }
            } else {
                // Parent is right child of grandparent (symmetric cases)
                val uncle = grandparent.left
                
                if (uncle?.color == Color.RED) {
                    // Case 1: Uncle is red - recolor and move up
                    parent.color = Color.BLACK
                    uncle.color = Color.BLACK
                    grandparent.color = Color.RED
                    z = grandparent
                } else {
                    // Uncle is black
                    if (z == parent.left) {
                        // Case 2: z is left child - right rotate
                        z = parent
                        rotateRight(z)
                    }
                    // Case 3: z is right child - recolor and left rotate
                    z.parent!!.color = Color.BLACK
                    z.parent!!.parent!!.color = Color.RED
                    rotateLeft(z.parent!!.parent!!)
                }
            }
        }
        
        // Root must always be black
        root?.color = Color.BLACK
    }
    
    /**
     * Replaces node u with node v in the tree structure.
     * Updates parent pointers appropriately.
     */
    private fun transplant(u: Node<K, V>, v: Node<K, V>?) {
        when {
            u.parent == null -> root = v
            u == u.parent!!.left -> u.parent!!.left = v
            else -> u.parent!!.right = v
        }
        v?.parent = u.parent
    }
    
    /**
     * Fixes red-black tree violations after deletion.
     * Maintains all red-black tree invariants.
     */
    private fun deleteFixup(node: Node<K, V>?) {
        var x = node
        
        while (x != root && x?.color == Color.BLACK) {
            val parent = x?.parent ?: return
            
            if (x == parent.left) {
                // x is left child
                var w = parent.right!!  // Sibling must exist due to RB properties
                
                if (w.color == Color.RED) {
                    // Case 1: Sibling is red
                    w.color = Color.BLACK
                    parent.color = Color.RED
                    rotateLeft(parent)
                    w = parent.right!!
                }
                
                if ((w.left?.color ?: Color.BLACK) == Color.BLACK && 
                    (w.right?.color ?: Color.BLACK) == Color.BLACK) {
                    // Case 2: Both of sibling's children are black
                    w.color = Color.RED
                    x = parent
                } else {
                    if ((w.right?.color ?: Color.BLACK) == Color.BLACK) {
                        // Case 3: Sibling's right child is black, left is red
                        w.left?.color = Color.BLACK
                        w.color = Color.RED
                        rotateRight(w)
                        w = parent.right!!
                    }
                    // Case 4: Sibling's right child is red
                    w.color = parent.color
                    parent.color = Color.BLACK
                    w.right?.color = Color.BLACK
                    rotateLeft(parent)
                    x = root
                }
            } else {
                // x is right child (symmetric cases)
                var w = parent.left!!  // Sibling must exist due to RB properties
                
                if (w.color == Color.RED) {
                    // Case 1: Sibling is red
                    w.color = Color.BLACK
                    parent.color = Color.RED
                    rotateRight(parent)
                    w = parent.left!!
                }
                
                if ((w.right?.color ?: Color.BLACK) == Color.BLACK && 
                    (w.left?.color ?: Color.BLACK) == Color.BLACK) {
                    // Case 2: Both of sibling's children are black
                    w.color = Color.RED
                    x = parent
                } else {
                    if ((w.left?.color ?: Color.BLACK) == Color.BLACK) {
                        // Case 3: Sibling's left child is black, right is red
                        w.right?.color = Color.BLACK
                        w.color = Color.RED
                        rotateLeft(w)
                        w = parent.left!!
                    }
                    // Case 4: Sibling's left child is red
                    w.color = parent.color
                    parent.color = Color.BLACK
                    w.left?.color = Color.BLACK
                    rotateRight(parent)
                    x = root
                }
            }
        }
        
        x?.color = Color.BLACK
    }
    
    // ========================================================================
    // MutableMap Operations
    // ========================================================================
    
    /**
     * Finds the node with the specified key.
     * Returns null if the key is not found.
     */
    private fun findNode(key: K): Node<K, V>? {
        var current = root
        while (current != null) {
            val cmp = keyComparator.compare(key, current.key)
            current = when {
                cmp < 0 -> current.left
                cmp > 0 -> current.right
                else -> return current
            }
        }
        return null
    }
    
    /**
     * Returns the value to which the specified key is mapped,
     * or null if this map contains no mapping for the key.
     */
    protected fun getUnsafe(key: K): V? = findNode(key)?.value
    
    /**
     * Returns true if this map contains a mapping for the specified key.
     */
    protected fun containsKeyUnsafe(key: K): Boolean = findNode(key) != null
    
    /**
     * Returns true if this map maps one or more keys to the specified value.
     * This operation requires O(n) time.
     */
    protected fun containsValueUnsafe(value: V): Boolean {
        return root?.let { containsValueInSubtree(it, value) } ?: false
    }
    
    /**
     * Recursively searches for a value in the subtree rooted at the given node.
     */
    private fun containsValueInSubtree(node: Node<K, V>, value: V): Boolean {
        if (node.value == value) return true
        
        node.left?.let { if (containsValueInSubtree(it, value)) return true }
        node.right?.let { if (containsValueInSubtree(it, value)) return true }
        
        return false
    }
    
    /**
     * Associates the specified value with the specified key in this map.
     * If the map previously contained a mapping for the key, the old value is replaced.
     * 
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @param changes list to record changes for observers (optional)
     * @return the previous value associated with key, or null if there was no mapping for key
     */
    protected open fun putUnsafe(key: K, value: V): V? {
        // Handle empty tree case
        if (root == null) {
            root = Node(key, value, Color.BLACK)
            _size = 1
            return null
        }
        
        // Find insertion point or existing node
        var current = root!!
        var parent: Node<K, V>? = null
        var cmp = 0
        
        while (true) {
            parent = current
            cmp = keyComparator.compare(key, current.key)
            
            when {
                cmp < 0 -> {
                    if (current.left == null) break
                    current = current.left!!
                }
                cmp > 0 -> {
                    if (current.right == null) break
                    current = current.right!!
                }
                else -> {
                    // Key already exists - update value
                    val oldValue = current.value
                    current.value = value
                    return oldValue
                }
            }
        }
        
        // Create new node
        val newNode = Node(key, value, Color.RED, parent)
        
        // Insert as child of parent
        if (cmp < 0) {
            parent.left = newNode
        } else {
            parent.right = newNode
        }
        
        // Update sizes up the tree
        var ancestor = parent
        while (ancestor != null) {
            ancestor.updateSize()
            ancestor = ancestor.parent
        }
        
        // Fix red-black tree violations
        insertFixup(newNode)
        
        _size++
        
        return null
    }
    
    /**
     * Removes the mapping for the specified key from this map if present.
     * 
     * @param key key whose mapping is to be removed from the map
     * @param changes list to record changes for observers (optional)
     * @return the previous value associated with key, or null if there was no mapping for key
     */
    protected open fun removeUnsafe(key: K): V? {
        val nodeToDelete = findNode(key) ?: return null
        val oldValue = nodeToDelete.value
        
        deleteNode(nodeToDelete)
        _size--
        
        
        return oldValue
    }
    
    /**
     * Deletes the specified node from the tree while maintaining red-black properties.
     */
    private fun deleteNode(z: Node<K, V>) {
        var y = z
        var yOriginalColor = y.color
        var x: Node<K, V>?
        
        when {
            z.left == null -> {
                x = z.right
                transplant(z, z.right)
            }
            z.right == null -> {
                x = z.left
                transplant(z, z.left)
            }
            else -> {
                // Node has two children - find successor
                y = z.right!!.minimum()
                yOriginalColor = y.color
                x = y.right
                
                if (y.parent == z) {
                    x?.parent = y
                } else {
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
        
        // Update sizes up the tree
        var current = x?.parent ?: root
        while (current != null) {
            current.updateSize()
            current = current.parent
        }
        
        // Fix red-black violations if we deleted a black node
        if (yOriginalColor == Color.BLACK) {
            deleteFixup(x)
        }
    }
    
    /**
     * Removes all mappings from this map.
     */
    protected open fun clearUnsafe() {
        root = null
        _size = 0
        
    }
    
    
    // ========================================================================
    // NavigableMap Operations
    // ========================================================================
    
    /**
     * Returns the first (lowest) key currently in this map.
     * Throws NoSuchElementException if this map is empty.
     */
    protected fun firstKeyUnsafe(): K {
        val node = root?.minimum() ?: throw NoSuchElementException("TreeMap is empty")
        return node.key
    }
    
    /**
     * Returns the last (highest) key currently in this map.
     * Throws NoSuchElementException if this map is empty.
     */
    protected fun lastKeyUnsafe(): K {
        val node = root?.maximum() ?: throw NoSuchElementException("TreeMap is empty")
        return node.key
    }
    
    /**
     * Returns a key-value mapping associated with the least key in this map,
     * or null if the map is empty.
     */
    protected fun firstEntryUnsafe(): MapEntry<K, V>? {
        val node = root?.minimum() ?: return null
        return MapEntry(node.key, node.value)
    }
    
    /**
     * Returns a key-value mapping associated with the greatest key in this map,
     * or null if the map is empty.
     */
    protected fun lastEntryUnsafe(): MapEntry<K, V>? {
        val node = root?.maximum() ?: return null
        return MapEntry(node.key, node.value)
    }
    
    /**
     * Returns the greatest key strictly less than the given key,
     * or null if no such key exists.
     */
    protected fun lowerKeyUnsafe(key: K): K? {
        return lowerEntryUnsafe(key)?.key
    }
    
    /**
     * Returns the greatest key less than or equal to the given key,
     * or null if no such key exists.
     */
    protected fun floorKeyUnsafe(key: K): K? {
        return floorEntryUnsafe(key)?.key
    }
    
    /**
     * Returns the least key greater than or equal to the given key,
     * or null if no such key exists.
     */
    protected fun ceilingKeyUnsafe(key: K): K? {
        return ceilingEntryUnsafe(key)?.key
    }
    
    /**
     * Returns the least key strictly greater than the given key,
     * or null if no such key exists.
     */
    protected fun higherKeyUnsafe(key: K): K? {
        return higherEntryUnsafe(key)?.key
    }
    
    /**
     * Returns a key-value mapping associated with the greatest key strictly less than the given key,
     * or null if no such key exists.
     */
    protected fun lowerEntryUnsafe(key: K): MapEntry<K, V>? {
        var result: Node<K, V>? = null
        var current = root
        
        while (current != null) {
            val cmp = keyComparator.compare(key, current.key)
            when {
                cmp <= 0 -> current = current.left  // key <= current.key, go left
                else -> {
                    result = current  // current.key < key, potential result
                    current = current.right
                }
            }
        }
        
        return result?.let { MapEntry(it.key, it.value) }
    }
    
    /**
     * Returns a key-value mapping associated with the greatest key less than or equal to the given key,
     * or null if no such key exists.
     */
    protected fun floorEntryUnsafe(key: K): MapEntry<K, V>? {
        var result: Node<K, V>? = null
        var current = root
        
        while (current != null) {
            val cmp = keyComparator.compare(key, current.key)
            when {
                cmp < 0 -> current = current.left  // key < current.key, go left
                cmp == 0 -> return MapEntry(current.key, current.value)  // exact match
                else -> {
                    result = current  // current.key < key, potential result
                    current = current.right
                }
            }
        }
        
        return result?.let { MapEntry(it.key, it.value) }
    }
    
    /**
     * Returns a key-value mapping associated with the least key greater than or equal to the given key,
     * or null if no such key exists.
     */
    protected fun ceilingEntryUnsafe(key: K): MapEntry<K, V>? {
        var result: Node<K, V>? = null
        var current = root
        
        while (current != null) {
            val cmp = keyComparator.compare(key, current.key)
            when {
                cmp == 0 -> return MapEntry(current.key, current.value)  // exact match
                cmp > 0 -> current = current.right  // key > current.key, go right
                else -> {
                    result = current  // current.key > key, potential result
                    current = current.left
                }
            }
        }
        
        return result?.let { MapEntry(it.key, it.value) }
    }
    
    /**
     * Returns a key-value mapping associated with the least key strictly greater than the given key,
     * or null if no such key exists.
     */
    protected fun higherEntryUnsafe(key: K): MapEntry<K, V>? {
        var result: Node<K, V>? = null
        var current = root
        
        while (current != null) {
            val cmp = keyComparator.compare(key, current.key)
            when {
                cmp >= 0 -> current = current.right  // key >= current.key, go right
                else -> {
                    result = current  // current.key > key, potential result
                    current = current.left
                }
            }
        }
        
        return result?.let { MapEntry(it.key, it.value) }
    }
    
    /**
     * Removes and returns a key-value mapping associated with the least key in this map,
     * or null if the map is empty.
     */
    protected fun pollFirstEntryUnsafe(): MapEntry<K, V>? {
        val node = root?.minimum() ?: return null
        val entry = MapEntry(node.key, node.value)
        
        deleteNode(node)
        _size--
        
        return entry
    }
    
    /**
     * Removes and returns a key-value mapping associated with the greatest key in this map,
     * or null if the map is empty.
     */
    protected fun pollLastEntryUnsafe(): MapEntry<K, V>? {
        val node = root?.maximum() ?: return null
        val entry = MapEntry(node.key, node.value)
        
        deleteNode(node)
        _size--
        
        return entry
    }
    
    // ========================================================================
    // Collection Views and Iterators
    // ========================================================================
    
    /**
     * Iterator for tree traversal that supports efficient in-order traversal.
     */
    private abstract inner class TreeIterator<T> : MutableIterator<T> {
        protected var current: Node<K, V>? = null
        protected var next: Node<K, V>? = null
        protected var lastReturned: Node<K, V>? = null
        
        constructor(first: Node<K, V>?) {
            this.next = first
        }
        
        override fun hasNext(): Boolean = next != null
        
        override fun next(): T {
            val node = next ?: throw NoSuchElementException()
            lastReturned = node
            next = node.successor()
            return extractValue(node)
        }
        
        override fun remove() {
            val toRemove = lastReturned ?: throw IllegalStateException("next() must be called before remove()")
            
            // If next would be removed, advance it
            if (next == toRemove) {
                next = toRemove.successor()
            }
            
            deleteNode(toRemove)
            _size--
            lastReturned = null
        }
        
        protected abstract fun extractValue(node: Node<K, V>): T
    }
    
    /**
     * Iterator for keys.
     */
    private inner class KeyIterator(first: Node<K, V>?) : TreeIterator<K>(first) {
        override fun extractValue(node: Node<K, V>): K = node.key
    }
    
    /**
     * Iterator for values.
     */
    private inner class ValueIterator(first: Node<K, V>?) : TreeIterator<V>(first) {
        override fun extractValue(node: Node<K, V>): V = node.value
    }
    
    /**
     * Iterator for entries.
     */
    private inner class EntryIterator(first: Node<K, V>?) : TreeIterator<MapEntry<K, V>>(first) {
        override fun extractValue(node: Node<K, V>): MapEntry<K, V> = MapEntry(node.key, node.value)
    }
    
    /**
     * Collection view for keys.
     */
    inner class KeySet : MutableSet<K> {
        override val size: Int get() = this@UnsafeTreeMapCore.sizeUnsafe
        override fun isEmpty(): Boolean = this@UnsafeTreeMapCore.isEmptyUnsafe
        
        override fun contains(element: K): Boolean = containsKeyUnsafe(element)
        override fun containsAll(elements: Collection<K>): Boolean = elements.all { contains(it) }
        
        override fun iterator(): MutableIterator<K> = KeyIterator(root?.minimum())
        
        override fun add(element: K): Boolean = 
            throw UnsupportedOperationException("Cannot add to key set")
            
        override fun addAll(elements: Collection<K>): Boolean = 
            throw UnsupportedOperationException("Cannot add to key set")
        
        override fun remove(element: K): Boolean {
            val sizeBefore = size
            removeUnsafe(element)
            return size < sizeBefore
        }
        
        override fun removeAll(elements: Collection<K>): Boolean {
            val sizeBefore = size
            elements.forEach { remove(it) }
            return size < sizeBefore
        }
        
        override fun retainAll(elements: Collection<K>): Boolean {
            val sizeBefore = size
            val toRemove = mutableListOf<K>()
            
            // Collect keys to remove
            val iter = iterator()
            while (iter.hasNext()) {
                val key = iter.next()
                if (key !in elements) {
                    toRemove.add(key)
                }
            }
            
            // Remove collected keys
            toRemove.forEach { removeUnsafe(it) }
            
            return size < sizeBefore
        }
        
        override fun clear() {
            clearUnsafe()
        }
    }
    
    /**
     * Collection view for values.
     */
    inner class ValueCollection : MutableCollection<V> {
        override val size: Int get() = this@UnsafeTreeMapCore.sizeUnsafe
        override fun isEmpty(): Boolean = this@UnsafeTreeMapCore.isEmptyUnsafe
        
        override fun contains(element: V): Boolean = containsValueUnsafe(element)
        override fun containsAll(elements: Collection<V>): Boolean = elements.all { contains(it) }
        
        override fun iterator(): MutableIterator<V> = ValueIterator(root?.minimum())
        
        override fun add(element: V): Boolean = 
            throw UnsupportedOperationException("Cannot add to value collection")
            
        override fun addAll(elements: Collection<V>): Boolean = 
            throw UnsupportedOperationException("Cannot add to value collection")
        
        override fun remove(element: V): Boolean {
            // Find and remove first entry with this value
            val iter = entryIterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.value == element) {
                    iter.remove()
                    return true
                }
            }
            return false
        }
        
        override fun removeAll(elements: Collection<V>): Boolean {
            val sizeBefore = size
            elements.forEach { remove(it) }
            return size < sizeBefore
        }
        
        override fun retainAll(elements: Collection<V>): Boolean {
            val sizeBefore = size
            val iter = iterator()
            while (iter.hasNext()) {
                if (iter.next() !in elements) {
                    iter.remove()
                }
            }
            return size < sizeBefore
        }
        
        override fun clear() {
            clearUnsafe()
        }
    }
    
    /**
     * Collection view for entries.
     */
    inner class EntrySet : MutableSet<MapEntry<K, V>> {
        override val size: Int get() = this@UnsafeTreeMapCore.sizeUnsafe
        override fun isEmpty(): Boolean = this@UnsafeTreeMapCore.isEmptyUnsafe
        
        override fun contains(element: MapEntry<K, V>): Boolean {
            val value = getUnsafe(element.key)
            return value != null && value == element.value
        }
        
        override fun containsAll(elements: Collection<MapEntry<K, V>>): Boolean = 
            elements.all { contains(it) }
        
        override fun iterator(): MutableIterator<MapEntry<K, V>> = EntryIterator(root?.minimum())
        
        override fun add(element: MapEntry<K, V>): Boolean {
            val oldValue = putUnsafe(element.key, element.value)
            return oldValue == null
        }
        
        override fun addAll(elements: Collection<MapEntry<K, V>>): Boolean {
            val sizeBefore = size
            elements.forEach { add(it) }
            return size > sizeBefore
        }
        
        override fun remove(element: MapEntry<K, V>): Boolean {
            val currentValue = getUnsafe(element.key)
            if (currentValue == element.value) {
                removeUnsafe(element.key)
                return true
            }
            return false
        }
        
        override fun removeAll(elements: Collection<MapEntry<K, V>>): Boolean {
            val sizeBefore = size
            elements.forEach { remove(it) }
            return size < sizeBefore
        }
        
        override fun retainAll(elements: Collection<MapEntry<K, V>>): Boolean {
            val sizeBefore = size
            val iter = iterator()
            while (iter.hasNext()) {
                if (iter.next() !in elements) {
                    iter.remove()
                }
            }
            return size < sizeBefore
        }
        
        override fun clear() {
            clearUnsafe()
        }
    }
    
    // Create collection view instances
    private val _keys = KeySet()
    private val _values = ValueCollection()
    private val _entries = EntrySet()
    
    /**
     * Returns a Set view of the keys contained in this map.
     */
    protected fun keysUnsafe(): MutableSet<K> = _keys
    
    /**
     * Returns a Collection view of the values contained in this map.
     */
    protected fun valuesUnsafe(): MutableCollection<V> = _values
    
    /**
     * Returns a Set view of the mappings contained in this map.
     */
    protected fun entriesUnsafe(): MutableSet<MapEntry<K, V>> = _entries
    
    /**
     * Returns an iterator over the entries in this map.
     */
    fun entryIterator(): MutableIterator<MapEntry<K, V>> = EntryIterator(root?.minimum())
    
    // ========================================================================
    // Modern Map API (Java 8+ style operations)
    // ========================================================================
    
    /**
     * If the specified key is not already associated with a value, associates it with the given value.
     * 
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with the specified key, or null if there was no mapping for the key
     */
    protected fun putIfAbsentUnsafe(key: K, value: V): V? {
        val existingNode = findNode(key)
        if (existingNode != null) {
            return existingNode.value
        }
        
        putUnsafe(key, value)
        return null
    }
    
    /**
     * Attempts to compute a mapping for the specified key and its current mapped value.
     * 
     * @param key key with which the specified value is to be associated
     * @param remappingFunction function to compute a value
     * @return the new value associated with the specified key, or null if none
     */
    protected fun computeUnsafe(key: K, remappingFunction: (K, V?) -> V?): V? {
        val oldValue = getUnsafe(key)
        val newValue = remappingFunction(key, oldValue)
        
        when {
            newValue == null -> {
                if (oldValue != null) {
                    removeUnsafe(key)
                }
                return null
            }
            else -> {
                putUnsafe(key, newValue)
                return newValue
            }
        }
    }
    
    /**
     * If the specified key is not already associated with a value, attempts to compute its value using the given mapping function.
     * 
     * @param key key with which the specified value is to be associated
     * @param mappingFunction function to compute a value
     * @return the current (existing or computed) value associated with the specified key, or null if the computed value is null
     */
    protected fun computeIfAbsentUnsafe(key: K, mappingFunction: (K) -> V?): V? {
        val existingValue = getUnsafe(key)
        if (existingValue != null) {
            return existingValue
        }
        
        val newValue = mappingFunction(key)
        if (newValue != null) {
            putUnsafe(key, newValue)
        }
        
        return newValue
    }
    
    /**
     * If the value for the specified key is present, attempts to compute a new mapping given the key and its current mapped value.
     * 
     * @param key key with which the specified value is to be associated
     * @param remappingFunction function to compute a value
     * @return the new value associated with the specified key, or null if none
     */
    protected fun computeIfPresentUnsafe(key: K, remappingFunction: (K, V) -> V?): V? {
        val oldValue = getUnsafe(key) ?: return null
        
        val newValue = remappingFunction(key, oldValue)
        
        when {
            newValue == null -> {
                removeUnsafe(key)
                return null
            }
            else -> {
                putUnsafe(key, newValue)
                return newValue
            }
        }
    }
    
    /**
     * If the specified key is not already associated with a value or is associated with null, associates it with the given non-null value.
     * Otherwise, replaces the associated value with the results of the given remapping function.
     * 
     * @param key key with which the specified value is to be associated
     * @param value the non-null value to be merged with the existing value associated with the key
     * @param remappingFunction function to recompute a value if present
     * @return the new value associated with the specified key, or null if no value is associated with the key
     */
    protected fun mergeUnsafe(key: K, value: V, remappingFunction: (V, V) -> V?): V? {
        val oldValue = getUnsafe(key)
        
        val newValue = if (oldValue == null) {
            value
        } else {
            remappingFunction(oldValue, value)
        }
        
        when {
            newValue == null -> {
                if (oldValue != null) {
                    removeUnsafe(key)
                }
                return null
            }
            else -> {
                putUnsafe(key, newValue)
                return newValue
            }
        }
    }
    
    /**
     * Replaces the entry for the specified key only if it is currently mapped to some value.
     * 
     * @param key key with which the specified value is associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with the specified key, or null if there was no mapping for the key
     */
    protected fun replaceUnsafe(key: K, value: V): V? {
        val existingNode = findNode(key) ?: return null
        val oldValue = existingNode.value
        
        existingNode.value = value
        
        return oldValue
    }
    
    /**
     * Replaces the entry for the specified key only if currently mapped to the specified value.
     * 
     * @param key key with which the specified value is associated
     * @param oldValue value expected to be associated with the specified key
     * @param newValue value to be associated with the specified key
     * @return true if the value was replaced
     */
    protected fun replaceUnsafe(key: K, oldValue: V, newValue: V): Boolean {
        val existingNode = findNode(key) ?: return false
        
        if (existingNode.value != oldValue) {
            return false
        }
        
        existingNode.value = newValue
        
        return true
    }
    
    /**
     * Copies all of the mappings from the specified map to this map.
     * 
     * @param from mappings to be stored in this map
     */
    protected fun putAllUnsafe(from: Map<out K, V>) {
        from.forEach { (key, value) ->
            putUnsafe(key, value)
        }
    }
    
    // ========================================================================
    // Range Operations (Basic Implementation)
    // ========================================================================
    
    /**
     * Returns all entries in the specified key range.
     * 
     * @param fromKey low endpoint of the keys in the returned map
     * @param fromInclusive true if the low endpoint is to be included in the returned view
     * @param toKey high endpoint of the keys in the returned map
     * @param toInclusive true if the high endpoint is to be included in the returned view
     * @return list of entries in the specified range
     */
    protected fun subMapEntriesUnsafe(
        fromKey: K?,
        fromInclusive: Boolean,
        toKey: K?,
        toInclusive: Boolean
    ): List<MapEntry<K, V>> {
        val result = mutableListOf<MapEntry<K, V>>()
        
        // Find the starting point
        val startNode = when {
            fromKey == null -> root?.minimum()
            fromInclusive -> ceilingEntryUnsafe(fromKey)?.let { findNode(it.key) }
            else -> higherEntryUnsafe(fromKey)?.let { findNode(it.key) }
        }
        
        // Traverse and collect entries in range
        var current = startNode
        while (current != null) {
            val inRange = when {
                toKey == null -> true
                toInclusive -> keyComparator.compare(current.key, toKey) <= 0
                else -> keyComparator.compare(current.key, toKey) < 0
            }
            
            if (!inRange) break
            
            result.add(MapEntry(current.key, current.value))
            current = current.successor()
        }
        
        return result
    }
    
    /**
     * Returns all entries with keys less than the specified key.
     * 
     * @param toKey high endpoint (exclusive) of the keys in the returned entries
     * @return list of entries with keys less than toKey
     */
    protected fun headMapEntriesUnsafe(toKey: K): List<MapEntry<K, V>> {
        return subMapEntriesUnsafe(null, true, toKey, false)
    }
    
    /**
     * Returns all entries with keys greater than or equal to the specified key.
     * 
     * @param fromKey low endpoint (inclusive) of the keys in the returned entries
     * @return list of entries with keys greater than or equal to fromKey
     */
    protected fun tailMapEntriesUnsafe(fromKey: K): List<MapEntry<K, V>> {
        return subMapEntriesUnsafe(fromKey, true, null, true)
    }

}