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

    // ========== Iteration & Collection Operations ==========

    /**
     * Performs the given [action] on each entry in the map.
     */
    suspend fun forEach(action: suspend (Map.Entry<K, V>) -> Unit)

    /**
     * Performs the given [action] on each key-value pair in the map.
     * This is a convenience method that unpacks entries.
     */
    suspend fun forEachPair(action: suspend (key: K, value: V) -> Unit) {
        forEach { (key, value) -> action(key, value) }
    }

    /**
     * Returns a list containing only entries matching the given [predicate].
     */
    suspend fun filter(predicate: suspend (Map.Entry<K, V>) -> Boolean): List<Map.Entry<K, V>>

    /**
     * Returns a map containing only entries matching the given [predicate].
     */
    suspend fun filterToMap(predicate: suspend (Map.Entry<K, V>) -> Boolean): Map<K, V> {
        val result = mutableMapOf<K, V>()
        forEach { entry ->
            if (predicate(entry)) {
                result[entry.key] = entry.value
            }
        }
        return result
    }

    /**
     * Returns a list containing only entries whose keys match the given [predicate].
     */
    suspend fun filterKeys(predicate: suspend (K) -> Boolean): List<Map.Entry<K, V>> {
        return filter { predicate(it.key) }
    }

    /**
     * Returns a list containing only entries whose values match the given [predicate].
     */
    suspend fun filterValues(predicate: suspend (V) -> Boolean): List<Map.Entry<K, V>> {
        return filter { predicate(it.value) }
    }

    /**
     * Returns a list containing the results of applying the given [transform] function
     * to each entry in the map.
     */
    suspend fun <R> map(transform: suspend (Map.Entry<K, V>) -> R): List<R>

    /**
     * Returns a list containing the results of applying the given [transform] function
     * to each key-value pair in the map.
     */
    suspend fun <R> mapPairs(transform: suspend (key: K, value: V) -> R): List<R> {
        return map { (key, value) -> transform(key, value) }
    }

    /**
     * Returns a list containing only the non-null results of applying the given [transform]
     * function to each entry in the map.
     */
    suspend fun <R : Any> mapNotNull(transform: suspend (Map.Entry<K, V>) -> R?): List<R>

    /**
     * Returns a list containing all key-value pairs.
     */
    suspend fun toList(): List<Map.Entry<K, V>>

    /**
     * Returns a standard Kotlin Map containing all key-value pairs.
     * This creates a snapshot copy at the time of the call.
     */
    suspend fun toMap(): Map<K, V> {
        val result = mutableMapOf<K, V>()
        forEach { (key, value) ->
            result[key] = value
        }
        return result
    }

    /**
     * Returns a snapshot of all keys in the map, in key order.
     */
    suspend fun keys(): Set<K>

    /**
     * Returns a snapshot of all values in the map, in key order.
     */
    suspend fun values(): List<V>

    // ========== Aggregation Operations ==========

    /**
     * Returns `true` if all entries match the given [predicate].
     */
    suspend fun all(predicate: suspend (Map.Entry<K, V>) -> Boolean): Boolean

    /**
     * Returns `true` if at least one entry matches the given [predicate].
     */
    suspend fun any(predicate: suspend (Map.Entry<K, V>) -> Boolean): Boolean

    /**
     * Returns `true` if no entries match the given [predicate].
     */
    suspend fun none(predicate: suspend (Map.Entry<K, V>) -> Boolean): Boolean

    /**
     * Returns the number of entries matching the given [predicate].
     */
    suspend fun count(predicate: suspend (Map.Entry<K, V>) -> Boolean): Int {
        var count = 0
        forEach { entry ->
            if (predicate(entry)) count++
        }
        return count
    }

    /**
     * Returns the first entry matching the given [predicate], or `null` if no such entry was found.
     */
    suspend fun find(predicate: suspend (Map.Entry<K, V>) -> Boolean): Map.Entry<K, V>?

    /**
     * Returns the first entry matching the given [predicate], or throws [NoSuchElementException]
     * if no such entry was found.
     */
    suspend fun first(predicate: suspend (Map.Entry<K, V>) -> Boolean): Map.Entry<K, V> {
        return find(predicate) ?: throw NoSuchElementException("No entry matching predicate found")
    }

    /**
     * Returns the first entry matching the given [predicate], or `null` if no such entry was found.
     */
    suspend fun firstOrNull(predicate: suspend (Map.Entry<K, V>) -> Boolean): Map.Entry<K, V>? {
        return find(predicate)
    }
}