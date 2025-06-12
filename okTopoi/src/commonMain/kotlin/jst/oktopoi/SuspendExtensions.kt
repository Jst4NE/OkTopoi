package jst.oktopoi

/**
 * Suspend operator extensions for TreeMap and Es classes.
 * These provide natural operator syntax in suspend contexts.
 * 
 * Batch operations and range operations are implemented as methods
 * directly in TreeMap and Es classes for efficient access to internal state.
 */

// TreeMap suspend operator extensions
suspend operator fun <K, V> TreeMap<K, V>.get(key: K): V? = getSuspend(key)
suspend operator fun <K, V> TreeMap<K, V>.set(key: K, value: V) { putSuspend(key, value) }
suspend operator fun <K, V> TreeMap<K, V>.contains(key: K): Boolean = containsKeySuspend(key)
suspend operator fun <K, V> TreeMap<K, V>.plusAssign(entries: Map<out K, V>) = putAllSuspend(entries)
suspend operator fun <K, V> TreeMap<K, V>.minusAssign(key: K) { removeSuspend(key) }

// Es suspend operator extensions (inherits TreeMap extensions automatically)
suspend operator fun <K : Any, V : Any> Es<K, V>.get(key: K): V? = getSuspend(key)
suspend operator fun <K : Any, V : Any> Es<K, V>.set(key: K, value: V) { putSuspend(key, value) }
suspend operator fun <K : Any, V : Any> Es<K, V>.contains(key: K): Boolean = containsKeySuspend(key)
suspend operator fun <K : Any, V : Any> Es<K, V>.plusAssign(entries: Map<out K, V>) = putAllSuspend(entries)
suspend operator fun <K : Any, V : Any> Es<K, V>.minusAssign(key: K) { removeSuspend(key) }

// Convenience extensions that delegate to class methods

// Batch operations - delegate to efficient class methods
suspend fun <K, V> TreeMap<K, V>.putAll(vararg entries: Pair<K, V>) = putAllSuspend(*entries)
suspend fun <K, V> TreeMap<K, V>.removeAll(vararg keys: K) = removeAllSuspend(*keys)

suspend fun <K : Any, V : Any> Es<K, V>.putAll(vararg entries: Pair<K, V>) = putAllSuspend(*entries)
suspend fun <K : Any, V : Any> Es<K, V>.removeAll(vararg keys: K) = removeAllSuspend(*keys)

// Secondary key convenience - delegate to class method
suspend fun <K : Any, V : Any> Es<K, V>.bySecondaryKey(keyName: String, keyValue: Any): List<V> = 
    bySecondaryKeySuspend(keyName, keyValue)