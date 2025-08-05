package jst.oktopoi

import androidx.compose.runtime.snapshots.SnapshotStateList

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

/**
 * Utility object for creating and managing comparators in a consistent way.
 */
object ComparatorUtils {
    
    /**
     * Creates a unified entry comparator using the provided comparators.
     * Handles the common pattern of falling back to natural key comparison.
     */
    @Suppress("UNCHECKED_CAST")
    fun <K, V> createEntryComparator(
        keyComparator: Comparator<K>?,
        entryComparator: Comparator<Map.Entry<K, V>>?
    ): Comparator<Map.Entry<K, V>> {
        return entryComparator ?: run {
            val keyCmp = keyComparator ?: Comparator { k1, k2 ->
                (k1 as Comparable<K>).compareTo(k2)
            }
            Comparator { e1, e2 -> keyCmp.compare(e1.key, e2.key) }
        }
    }
    
    /**
     * Creates a null-safe comparator for secondary keys with proper null handling.
     * Used in secondary key comparisons where nulls should be ordered last.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> createNullableComparator(
        isComparable: Boolean,
        providedComparator: Comparator<T>? = null
    ): Comparator<T?> {
        return if (isComparable) {
            // Use natural comparison for Comparable secondary keys
            Comparator { sk1, sk2 ->
                when {
                    sk1 == null && sk2 == null -> 0
                    sk1 == null -> 1  // nulls last
                    sk2 == null -> -1
                    else -> (sk1 as Comparable<T>).compareTo(sk2)
                }
            }
        } else {
            // Use provided comparator for non-Comparable secondary keys
            Comparator { sk1, sk2 ->
                when {
                    sk1 == null && sk2 == null -> 0
                    sk1 == null -> 1  // nulls last
                    sk2 == null -> -1
                    else -> providedComparator!!.compare(sk1, sk2)
                }
            }
        }
    }
}

/**
 * Utility object for binary search operations on Map entries.
 */
object BinarySearchUtils {
    
    /**
     * Performs binary search to find the index of a target entry in a sorted list.
     * Uses sophisticated search algorithm that handles both exact matches and insertion points.
     * 
     * @param list the sorted list to search in
     * @param targetKey the key to search for
     * @param targetValue the value to search for  
     * @param comparator the comparator used for ordering the list
     * @param returnInsertionPoint if true, returns insertion point when exact match not found
     * @return the index of the matching entry, or insertion point if returnInsertionPoint is true
     */
    fun <K, V> findIndexForMapEntries(
        list: SnapshotStateList<out Map.Entry<K, V>>,
        targetKey: K,
        targetValue: V,
        comparator: Comparator<Map.Entry<K, V>>,
        returnInsertionPoint: Boolean = true
    ): Int {
        val targetEntry = MapEntry(targetKey, targetValue)
        var low = 0
        var high = list.size

        // Binary search for the general position
        while (low < high) {
            val mid = (low + high) / 2
            val midEntry = list[mid]

            if (comparator.compare(targetEntry, midEntry) > 0) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        // Sophisticated search around the found position for exact key match
        var left = low - 1
        var right = low

        while (left >= 0 || right < list.size) {
            if (right < list.size) {
                val entry = list[right]
                if (entry.key == targetKey) return right
                if (comparator.compare(targetEntry, entry) != 0) right = list.size else right++
            }

            if (left >= 0) {
                val entry = list[left]
                if (entry.key == targetKey) return left
                if (comparator.compare(targetEntry, entry) != 0) left = -1 else left--
            }
        }

        return if (returnInsertionPoint) low else -1
    }
}