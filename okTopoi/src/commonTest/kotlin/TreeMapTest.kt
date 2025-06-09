package jst.oktopoi

import kotlin.test.*

data class TestItem(val id: Int, val name: String, val category: String, val value: Double)

class TreeMapTest {

    // ==================== Basic TreeMap Operations ====================

    @Test
    fun testTreeMap_construction() {
        // Test default constructor
        val map1 = TreeMap<String, Int>()
        assertTrue(map1.isEmpty())
        assertEquals(0, map1.size)
        
        // Test with custom comparator
        val reverseComparator = Comparator<String> { a, b -> b.compareTo(a) }
        val map2 = TreeMap<String, Int>(reverseComparator)
        assertTrue(map2.isEmpty())
        assertSame(reverseComparator, map2.comparator)
    }

    @Test
    fun testTreeMap_basicOperations() {
        val map = TreeMap<String, Int>()
        
        // Test put and get
        assertNull(map.put("a", 1))
        assertEquals(1, map["a"])
        assertEquals(1, map.size)
        
        // Test update
        assertEquals(1, map.put("a", 2))
        assertEquals(2, map["a"])
        assertEquals(1, map.size)
        
        // Test multiple entries
        map.put("b", 3)
        map.put("c", 4)
        assertEquals(3, map.size)
        
        // Test remove
        assertEquals(2, map.remove("a"))
        assertNull(map["a"])
        assertEquals(2, map.size)
        
        // Test contains
        assertTrue(map.containsKey("b"))
        assertTrue(map.containsValue(3))
        assertFalse(map.containsKey("a"))
        assertFalse(map.containsValue(2))
    }

    @Test
    fun testTreeMap_ordering() {
        val map = TreeMap<Int, String>()
        
        // Insert in random order
        map.put(5, "five")
        map.put(2, "two")
        map.put(8, "eight")
        map.put(1, "one")
        map.put(9, "nine")
        map.put(3, "three")
        
        // Keys should be in sorted order
        val keys = map.keys.toList()
        assertEquals(listOf(1, 2, 3, 5, 8, 9), keys)
        
        // Test with string keys (natural ordering)
        val stringMap = TreeMap<String, Int>()
        stringMap.put("zebra", 1)
        stringMap.put("apple", 2)
        stringMap.put("banana", 3)
        
        val stringKeys = stringMap.keys.toList()
        assertEquals(listOf("apple", "banana", "zebra"), stringKeys)
    }

    @Test
    fun testTreeMap_customComparator() {
        // Reverse order comparator
        val reverseComparator = Comparator<String> { a, b -> b.compareTo(a) }
        val map = TreeMap<String, Int>(reverseComparator)
        
        map.put("apple", 1)
        map.put("banana", 2)
        map.put("cherry", 3)
        
        val keys = map.keys.toList()
        assertEquals(listOf("cherry", "banana", "apple"), keys)
    }

    // ==================== Secondary Keys Tests ====================

    @Test
    fun testTreeMap_secondaryKeys() {
        val map = TreeMap<Int, TestItem>(secondaryKeys = {
            key("category") { it.category }
            key("nameLength") { it.name.length }
            key("valueRange") { (it.value / 10).toInt() }
        })
        
        val item1 = TestItem(1, "Apple", "Fruit", 15.5)
        val item2 = TestItem(2, "Banana", "Fruit", 12.0)
        val item3 = TestItem(3, "Carrot", "Vegetable", 8.5)
        val item4 = TestItem(4, "Broccoli", "Vegetable", 25.0)
        
        map.put(1, item1)
        map.put(2, item2)
        map.put(3, item3)
        map.put(4, item4)
        
        // Test secondary key lookups
        val fruits = map.getBySecondaryKey("category", "Fruit")
        assertEquals(2, fruits.size)
        assertTrue(fruits.contains(item1))
        assertTrue(fruits.contains(item2))
        
        val vegetables = map.getBySecondaryKey("category", "Vegetable")
        assertEquals(2, vegetables.size)
        assertTrue(vegetables.contains(item3))
        assertTrue(vegetables.contains(item4))
        
        // Test by name length
        val sixLetterItems = map.getBySecondaryKey("nameLength", 6)
        assertEquals(2, sixLetterItems.size) // "Banana", "Carrot"
        
        // Test by value range
        val lowValueItems = map.getBySecondaryKey("valueRange", 0) // 0-9.99
        assertEquals(1, lowValueItems.size)
        assertEquals(item3, lowValueItems[0])
        
        val midValueItems = map.getBySecondaryKey("valueRange", 1) // 10-19.99
        assertEquals(2, midValueItems.size)
        
        val highValueItems = map.getBySecondaryKey("valueRange", 2) // 20-29.99
        assertEquals(1, highValueItems.size)
        assertEquals(item4, highValueItems[0])
    }

    @Test
    fun testTreeMap_secondaryKeyUpdates() {
        val map = TreeMap<Int, TestItem>(secondaryKeys = {
            key("category") { it.category }
        })
        
        val item = TestItem(1, "Apple", "Fruit", 15.5)
        map.put(1, item)
        
        // Verify initial secondary key
        val fruits = map.getBySecondaryKey("category", "Fruit")
        assertEquals(1, fruits.size)
        assertEquals(item, fruits[0])
        
        val vegetables = map.getBySecondaryKey("category", "Vegetable")
        assertEquals(0, vegetables.size)
        
        // Update item to change category
        val updatedItem = TestItem(1, "Apple", "Vegetable", 15.5)
        map.put(1, updatedItem)
        
        // Verify secondary key was updated
        val fruitsAfter = map.getBySecondaryKey("category", "Fruit")
        assertEquals(0, fruitsAfter.size)
        
        val vegetablesAfter = map.getBySecondaryKey("category", "Vegetable")
        assertEquals(1, vegetablesAfter.size)
        assertEquals(updatedItem, vegetablesAfter[0])
    }

    @Test
    fun testTreeMap_secondaryKeyWithNulls() {
        val map = TreeMap<Int, TestItem?>(secondaryKeys = {
            key("category") { it?.category }
            key("name") { it?.name }
        })
        
        val item1 = TestItem(1, "Apple", "Fruit", 15.5)
        map.put(1, item1)
        map.put(2, null)
        
        // Test lookup with null secondary key
        val nullItems = map.getBySecondaryKey("category", null)
        assertEquals(1, nullItems.size)
        assertNull(nullItems[0])
        
        // Test lookup with actual values
        val fruits = map.getBySecondaryKey("category", "Fruit")
        assertEquals(1, fruits.size)
        assertEquals(item1, fruits[0])
    }

    // ==================== NavigableMap Operations ====================

    @Test
    fun testTreeMap_navigation() {
        val map = TreeMap<Int, String>()
        
        for (i in listOf(1, 3, 5, 7, 9, 11, 13, 15)) {
            map.put(i, i.toString())
        }
        
        // Test first/last
        assertEquals(1, map.firstKey())
        assertEquals(15, map.lastKey())
        
        // Test lower/floor
        assertEquals(7, map.lowerKey(8))  // greatest < 8
        assertEquals(7, map.floorKey(7))  // greatest <= 7
        assertEquals(7, map.floorKey(8))  // greatest <= 8
        assertNull(map.lowerKey(1))      // no key < 1
        
        // Test ceiling/higher
        assertEquals(9, map.ceilingKey(8))  // least >= 8
        assertEquals(9, map.ceilingKey(9))  // least >= 9
        assertEquals(11, map.higherKey(9))  // least > 9
        assertNull(map.higherKey(15))      // no key > 15
        
        // Test with non-existent keys
        assertEquals(5, map.lowerKey(6))
        assertEquals(5, map.floorKey(6))
        assertEquals(7, map.ceilingKey(6))
        assertEquals(7, map.higherKey(6))
    }

    @Test
    fun testTreeMap_entryNavigation() {
        val map = TreeMap<Int, String>()
        
        map.put(5, "five")
        map.put(2, "two")
        map.put(8, "eight")
        
        // Test first/last entries
        val firstEntry = map.firstEntry()
        assertNotNull(firstEntry)
        assertEquals(2, firstEntry.key)
        assertEquals("two", firstEntry.value)
        
        val lastEntry = map.lastEntry()
        assertNotNull(lastEntry)
        assertEquals(8, lastEntry.key)
        assertEquals("eight", lastEntry.value)
        
        // Test navigation entries
        val lowerEntry = map.lowerEntry(5)
        assertNotNull(lowerEntry)
        assertEquals(2, lowerEntry.key)
        
        val higherEntry = map.higherEntry(5)
        assertNotNull(higherEntry)
        assertEquals(8, higherEntry.key)
    }

    @Test
    fun testTreeMap_pollOperations() {
        val map = TreeMap<Int, String>()
        
        map.put(3, "three")
        map.put(1, "one")
        map.put(5, "five")
        
        // Test pollFirstEntry
        val firstPolled = map.pollFirstEntry()
        assertNotNull(firstPolled)
        assertEquals(1, firstPolled.key)
        assertEquals("one", firstPolled.value)
        assertEquals(2, map.size)
        assertNull(map[1])
        
        // Test pollLastEntry
        val lastPolled = map.pollLastEntry()
        assertNotNull(lastPolled)
        assertEquals(5, lastPolled.key)
        assertEquals("five", lastPolled.value)
        assertEquals(1, map.size)
        assertNull(map[5])
        
        // Polled entries should be detached
        firstPolled.setValue("modified")
        assertEquals("modified", firstPolled.value)
        // This should not affect the original map
    }

    // ==================== SubMap Operations ====================

    @Test
    fun testTreeMap_subMap() {
        val map = TreeMap<Int, String>()
        
        for (i in 1..20) {
            map.put(i, i.toString())
        }
        
        // Test inclusive subMap
        val subMap = map.subMap(5, true, 15, true)
        assertEquals(11, subMap.size) // 5 through 15 inclusive
        assertTrue(subMap.containsKey(5))
        assertTrue(subMap.containsKey(15))
        assertFalse(subMap.containsKey(4))
        assertFalse(subMap.containsKey(16))
        
        // Test exclusive subMap
        val exclusiveSubMap = map.subMap(5, false, 15, false)
        assertEquals(9, exclusiveSubMap.size) // 6 through 14
        assertFalse(exclusiveSubMap.containsKey(5))
        assertFalse(exclusiveSubMap.containsKey(15))
        assertTrue(exclusiveSubMap.containsKey(6))
        assertTrue(exclusiveSubMap.containsKey(14))
        
        // Test mixed inclusivity
        val mixedSubMap = map.subMap(5, true, 15, false)
        assertEquals(10, mixedSubMap.size) // 5 through 14
        assertTrue(mixedSubMap.containsKey(5))
        assertFalse(mixedSubMap.containsKey(15))
    }

    @Test
    fun testTreeMap_headTailMaps() {
        val map = TreeMap<Int, String>()
        
        for (i in 1..10) {
            map.put(i, i.toString())
        }
        
        // Test headMap
        val headMap = map.headMap(5, true)
        assertEquals(5, headMap.size) // 1, 2, 3, 4, 5
        assertTrue(headMap.containsKey(5))
        assertFalse(headMap.containsKey(6))
        
        val exclusiveHeadMap = map.headMap(5, false)
        assertEquals(4, exclusiveHeadMap.size) // 1, 2, 3, 4
        assertFalse(exclusiveHeadMap.containsKey(5))
        
        // Test tailMap
        val tailMap = map.tailMap(7, true)
        assertEquals(4, tailMap.size) // 7, 8, 9, 10
        assertTrue(tailMap.containsKey(7))
        assertFalse(tailMap.containsKey(6))
        
        val exclusiveTailMap = map.tailMap(7, false)
        assertEquals(3, exclusiveTailMap.size) // 8, 9, 10
        assertFalse(exclusiveTailMap.containsKey(7))
    }

    // ==================== Advanced Map Operations ====================

    @Test
    fun testTreeMap_advancedOperations() {
        val map = TreeMap<String, Int>()
        
        // Test putIfAbsent
        assertNull(map.putIfAbsent("a", 1))
        assertEquals(1, map["a"])
        assertEquals(1, map.putIfAbsent("a", 2)) // Should return existing value
        assertEquals(1, map["a"])
        
        // Test replace
        assertNull(map.replace("nonexistent", 100))
        assertEquals(1, map.replace("a", 10))
        assertEquals(10, map["a"])
        
        // Test conditional replace
        assertFalse(map.replace("a", 1, 20)) // Wrong expected value
        assertTrue(map.replace("a", 10, 20)) // Correct expected value
        assertEquals(20, map["a"])
        
        // Test conditional remove
        assertFalse(map.remove("a", 10)) // Wrong value
        assertTrue(map.remove("a", 20)) // Correct value
        assertNull(map["a"])
    }

    @Test
    fun testTreeMap_computeOperations() {
        val map = TreeMap<String, Int>()
        
        // Test compute
        assertEquals(1, map.compute("a") { key, value -> (value ?: 0) + 1 })
        assertEquals(1, map["a"])
        assertEquals(2, map.compute("a") { key, value -> (value ?: 0) + 1 })
        assertEquals(2, map["a"])
        
        // Test computeIfAbsent
        assertEquals(10, map.computeIfAbsent("b") { 10 })
        assertEquals(10, map["b"])
        assertEquals(10, map.computeIfAbsent("b") { 20 }) // Should not change
        
        // Test computeIfPresent
        assertEquals(4, map.computeIfPresent("a") { key, value -> value * 2 })
        assertEquals(4, map["a"])
        assertNull(map.computeIfPresent("nonexistent") { key, value -> value * 2 })
        
        // Test merge
        assertEquals(5, map.merge("c", 5) { old, new -> (old ?: 0) + new })
        assertEquals(5, map["c"])
        assertEquals(15, map.merge("c", 10) { old, new -> (old ?: 0) + new })
        assertEquals(15, map["c"])
    }

    // ==================== Collection Views ====================

    @Test
    fun testTreeMap_keySet() {
        val map = TreeMap<String, Int>()
        map.put("a", 1)
        map.put("b", 2)
        map.put("c", 3)
        
        val keySet = map.keys
        assertEquals(3, keySet.size)
        assertTrue(keySet.contains("a"))
        assertTrue(keySet.contains("b"))
        assertTrue(keySet.contains("c"))
        assertFalse(keySet.contains("d"))
        
        // Test remove through keySet
        assertTrue(keySet.remove("b"))
        assertEquals(2, map.size)
        assertNull(map["b"])
        
        // Test iterator
        val keyList = keySet.toList()
        assertEquals(listOf("a", "c"), keyList)
    }

    @Test
    fun testTreeMap_values() {
        val map = TreeMap<String, Int>()
        map.put("a", 1)
        map.put("b", 2)
        map.put("c", 2) // Duplicate value
        
        val values = map.values
        assertEquals(3, values.size)
        assertTrue(values.contains(1))
        assertTrue(values.contains(2))
        
        // Test remove through values (should remove first occurrence)
        assertTrue(values.remove(2))
        assertEquals(2, map.size)
        // One of the entries with value 2 should be removed
        
        val remainingValues = values.toList()
        assertEquals(2, remainingValues.size)
    }

    @Test
    fun testTreeMap_entrySet() {
        val map = TreeMap<String, Int>()
        map.put("a", 1)
        map.put("b", 2)
        map.put("c", 3)
        
        val entries = map.entries
        assertEquals(3, entries.size)
        
        // Test entry modification
        val entryA = entries.find { it.key == "a" }
        assertNotNull(entryA)
        assertEquals(1, entryA.value)
        assertEquals(1, entryA.setValue(10))
        assertEquals(10, map["a"])
        
        // Test entry removal
        assertTrue(entries.remove(entryA))
        assertEquals(2, map.size)
        assertNull(map["a"])
    }

    // ==================== Descending Views ====================

    @Test
    fun testTreeMap_descendingViews() {
        val map = TreeMap<Int, String>()
        
        for (i in 1..5) {
            map.put(i, i.toString())
        }
        
        // Test descending key set
        val descendingKeys = map.descendingKeySet()
        val keyList = descendingKeys.toList()
        assertEquals(listOf(5, 4, 3, 2, 1), keyList)
        
        // Test descending map
        val descendingMap = map.descendingMap()
        assertEquals(5, descendingMap.size)
        val descendingEntries = descendingMap.entries.toList()
        assertEquals(5, descendingEntries[0].key)
        assertEquals(1, descendingEntries[4].key)
    }

    // ==================== Copy Operations ====================

    @Test
    fun testTreeMap_copy() {
        val original = TreeMap<String, Int>(secondaryKeys = {
            key("length") { value -> value.toString().length }
        })
        
        original.put("a", 1)
        original.put("bb", 2)
        original.put("ccc", 3)
        
        val copy = original.copy()
        
        // Copy should have same content
        assertEquals(original.size, copy.size)
        assertEquals(original["a"], copy["a"])
        assertEquals(original["bb"], copy["bb"])
        assertEquals(original["ccc"], copy["ccc"])
        
        // Copy should be independent
        copy.put("dddd", 4)
        assertEquals(3, original.size)
        assertEquals(4, copy.size)
        
        // Secondary keys should work in copy
        val lengthThreeItems = copy.getBySecondaryKey("length", 3)
        assertEquals(1, lengthThreeItems.size)
        assertEquals(3, lengthThreeItems[0])
    }

    // ==================== Error Conditions ====================

    @Test
    fun testTreeMap_errorConditions() {
        val map = TreeMap<String, Int>()
        
        // Test empty map operations
        assertNull(map.firstKey())
        assertNull(map.lastKey())
        assertNull(map.firstEntry())
        assertNull(map.lastEntry())
        assertNull(map.pollFirstEntry())
        assertNull(map.pollLastEntry())
        
        // Test invalid range in subMap
        assertFailsWith<IllegalArgumentException> {
            map.subMap("z", true, "a", false)
        }
    }

    // ==================== Concurrent Modification ====================

    @Test
    fun testTreeMap_concurrentModification() {
        val map = TreeMap<Int, String>()
        
        for (i in 1..10) {
            map.put(i, i.toString())
        }
        
        val iterator = map.keys.iterator()
        iterator.next() // Get first element
        
        // Modify map while iterating
        map.put(11, "eleven")
        
        // Next operation should detect concurrent modification
        assertFailsWith<TreeMap.ConcurrentModificationException> {
            iterator.next()
        }
    }

    @Test
    fun testTreeMap_iteratorRemove() {
        val map = TreeMap<Int, String>()
        
        for (i in 1..5) {
            map.put(i, i.toString())
        }
        
        val iterator = map.keys.iterator()
        val firstKey = iterator.next()
        assertEquals(1, firstKey)
        
        // Remove through iterator
        iterator.remove()
        assertEquals(4, map.size)
        assertNull(map[1])
        
        // Test illegal state - remove without next
        assertFailsWith<IllegalStateException> {
            iterator.remove()
        }
    }

    // ==================== Performance Characteristics ====================

    @Test
    fun testTreeMap_performanceCharacteristics() {
        val map = TreeMap<Int, String>()
        
        // Test with larger dataset to verify O(log n) characteristics
        val size = 1000
        
        // Insert many elements
        for (i in 1..size) {
            map.put(i, "value_$i")
        }
        
        assertEquals(size, map.size)
        
        // Test random access
        for (i in 1..100) {
            val randomKey = (1..size).random()
            assertEquals("value_$randomKey", map[randomKey])
        }
        
        // Test range operations
        val subMap = map.subMap(100, true, 200, false)
        assertEquals(100, subMap.size)
        
        // Test navigation operations
        assertEquals(1, map.firstKey())
        assertEquals(size, map.lastKey())
        assertEquals(500, map.ceilingKey(500))
        assertEquals(499, map.floorKey(500))
    }
}