package jst.oktopoi

/**
 * Shared utilities for Map operations to eliminate code duplication between TreeMap and Es classes.
 */

/**
 * Unified Map.Entry implementation to replace duplicate Entry data classes.
 * Used across TreeMap and Es for consistent entry representation.
 * Implements MutableMap.MutableEntry for type-safe mutable operations.
 */
data class MapEntry<K, V>(
    override val key: K,
    private var _value: V
) : MutableMap.MutableEntry<K, V> {
    override val value: V get() = _value

    override fun setValue(newValue: V): V {
        val oldValue = _value
        _value = newValue
        return oldValue
    }
}