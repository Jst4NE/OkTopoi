package jst.oktopoi

/**
 * A suspend-based map interface that mirrors Kotlin's MutableMap API but with suspend functions.
 *
 * This interface provides all the useful Map operations without the deadlock risks of blocking APIs.
 * It's designed to work seamlessly with coroutines and structured concurrency.
 *
 * ## Key Design Principles
 *
 * 1. **All mutating operations are suspend functions** - ensures proper coroutine integration
 * 2. **Read operations are also suspend** - allows for async data fetching and locking
 * 3. **No operator overloading for get/set** - Kotlin doesn't support suspend operators
 * 4. **Collection views are suspend-based** - entries, keys, values require suspension
 *
 * ## Usage Example
 *
 * ```kotlin
 * suspend fun example(map: SuspendMutableMap<Long, User>) {
 *     // Basic operations
 *     val user = map.get(123L)
 *     map.put(456L, User("Alice"))
 *     map.remove(789L)
 *
 *     // Bulk operations
 *     map.putAll(mapOf(1L to user1, 2L to user2))
 *
 *     // Queries
 *     val isEmpty = map.isEmpty()
 *     val size = map.size()
 *     val hasKey = map.containsKey(123L)
 *
 *     // Iteration
 *     map.forEach { (key, value) ->
 *         println("$key -> $value")
 *     }
 *
 *     // Transformation
 *     val userNames = map.map { (_, value) -> value.name }
 *     val activeUsers = map.filter { (_, value) -> value.isActive }
 * }
 * ```
 *
 * @param K the type of map keys
 * @param V the type of map values
 */
interface SuspendMutableMap<K, V> {

    // ========== Core Map Operations ==========

    /**
     * Returns the number of key-value pairs in the map.
     */
    suspend fun size(): Int

    /**
     * Returns `true` if the map is empty (contains no elements), `false` otherwise.
     */
    suspend fun isEmpty(): Boolean

    /**
     * Returns `true` if the map maps one or more keys to the specified [value].
     */
    suspend fun containsValue(value: V): Boolean

    /**
     * Returns `true` if the map contains the specified [key].
     */
    suspend fun containsKey(key: K): Boolean

    /**
     * Returns the value corresponding to the given [key], or `null` if such a key is not present in the map.
     */
    suspend fun get(key: K): V?

    /**
     * Associates the specified [value] with the specified [key] in the map.
     *
     * @return the previous value associated with the key, or `null` if the key was not present in the map.
     */
    suspend fun put(key: K, value: V): V?

    /**
     * Removes the specified [key] and its corresponding value from this map.
     *
     * @return the previous value associated with the key, or `null` if the key was not present in the map.
     */
    suspend fun remove(key: K): V?

    /**
     * Updates all key/value pairs from the specified map [from] into this map.
     * Existing keys will be overwritten with new values.
     */
    suspend fun putAll(from: Map<out K, V>)

    /**
     * Removes all elements from this map.
     */
    suspend fun clear()

    // ========== Extended Operations ==========

    /**
     * Returns the value for the given [key], or the result of the [defaultValue] function if the key was not found.
     *
     * This operation does not modify the map.
     */
    suspend fun getOrDefault(key: K, defaultValue: suspend () -> V): V {
        return get(key) ?: defaultValue()
    }

    /**
     * Returns the value for the given [key]. If the key is not found in the map,
     * calls the [defaultValue] function, puts its result into the map under the given key and returns it.
     */
    suspend fun getOrPut(key: K, defaultValue: suspend () -> V): V {
        val value = get(key)
        return if (value == null) {
            val newValue = defaultValue()
            put(key, newValue)
            newValue
        } else {
            value
        }
    }

    /**
     * Removes the entry for the specified [key] only if it is currently mapped to the specified [value].
     *
     * @return `true` if the entry was removed, `false` otherwise.
     */
    suspend fun remove(key: K, value: V): Boolean {
        val currentValue = get(key)
        return if (currentValue == value) {
            remove(key)
            true
        } else {
            false
        }
    }

    /**
     * Replaces the entry for the specified [key] only if it is currently mapped to some value.
     *
     * @return the previous value associated with the key, or `null` if the key was not present.
     */
    suspend fun replace(key: K, value: V): V? {
        return if (containsKey(key)) {
            put(key, value)
        } else {
            null
        }
    }

    /**
     * Replaces the entry for the specified [key] only if currently mapped to the specified [oldValue].
     *
     * @return `true` if the value was replaced, `false` otherwise.
     */
    suspend fun replace(key: K, oldValue: V, newValue: V): Boolean {
        val currentValue = get(key)
        return if (currentValue == oldValue) {
            put(key, newValue)
            true
        } else {
            false
        }
    }

    // ========== Snapshots ==========
    //
    // The functional operations (forEach, filter, map, any, …) live on TreeMap as final
    // inline members: an interface member cannot be inline, and inlining is what lets their
    // lambdas suspend without allocating a suspend lambda and a continuation per call.

    /**
     * Returns a snapshot of all entries, in key order.
     */
    suspend fun toList(): List<Map.Entry<K, V>>

    /**
     * Returns a standard Kotlin Map containing all key-value pairs.
     * This creates a snapshot copy at the time of the call.
     */
    suspend fun toMap(): Map<K, V> = toList().associate { it.key to it.value }

    /**
     * Returns a snapshot of all keys in the map, in key order.
     */
    suspend fun keys(): Set<K>

    /**
     * Returns a snapshot of all values in the map, in key order.
     */
    suspend fun values(): List<V>
}