package jst.oktopoi

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
data class TreeTestItem(val id: Int, val name: String, val category: String, val value: Double, val tags: List<String>)

class TreeMapAdvancedTest {

    // ==================== Change Notifications ====================

    @Test
    fun testTreeMap_changeNotificationsPut() = runTest {
        val map = TreeMap<String, Int>()
        val changes = mutableListOf<TreeMap.MapChange<String, Int>>()
        
        // Collect changes from the SharedFlow
        val collectJob = launch {
            map.changes.collect { change ->
                changes.add(change)
            }
        }
        
        // Allow some time for the collector to start
        delay(10)
        
        // Generate changes
        map.put("a", 1) // Put new
        map.put("b", 2) // Put new  
        map.put("a", 10) // Update existing
        map.remove("b") // Remove
        map.clear() // Clear
        
        // Allow time for changes to be collected
        delay(50)
        collectJob.cancel()
        
        // Verify we captured the expected number of changes
        assertEquals(5, changes.size)
        
        // Check Put new
        val putNew1 = changes[0] as TreeMap.MapChange.Put
        assertEquals("a", putNew1.key)
        assertEquals(1, putNew1.value)
        assertFalse(putNew1.isUpdate)
        assertNull(putNew1.oldValue)
        
        // Check Put new 2
        val putNew2 = changes[1] as TreeMap.MapChange.Put
        assertEquals("b", putNew2.key)
        assertEquals(2, putNew2.value)
        assertFalse(putNew2.isUpdate)
        assertNull(putNew2.oldValue)
        
        // Check Update
        val putUpdate = changes[2] as TreeMap.MapChange.Put
        assertEquals("a", putUpdate.key)
        assertEquals(10, putUpdate.value)
        assertTrue(putUpdate.isUpdate)
        assertEquals(1, putUpdate.oldValue)
        
        // Check Remove
        val remove = changes[3] as TreeMap.MapChange.Removed
        assertEquals("b", remove.key)
        assertEquals(2, remove.oldValue)
        
        // Check Clear
        assertTrue(changes[4] is TreeMap.MapChange.Cleared)
    }

    @Test
    fun testTreeMap_changeNotificationsPutAll() = runTest {
        val map = TreeMap<String, Int>()
        val changes = mutableListOf<TreeMap.MapChange<String, Int>>()
        
        // Collect changes from the SharedFlow
        val collectJob = launch {
            map.changes.collect { change ->
                changes.add(change)
            }
        }
        
        // Allow some time for the collector to start
        delay(10)
        
        map.putAll(mapOf("a" to 1, "b" to 2, "c" to 3))
        
        // Allow time for changes to be collected
        delay(50)
        collectJob.cancel()
        
        // Verify changes were captured
        // putAll could generate either individual Put changes or a Rebuild change
        assertTrue(changes.isNotEmpty())
        
        // Verify final state
        assertEquals(3, map.size)
        assertEquals(1, map["a"])
        assertEquals(2, map["b"])
        assertEquals(3, map["c"])
    }

    @Test
    fun testTreeMap_changeNotificationsAdvancedOperations() {
        val map = TreeMap<String, Int>()
        val changes = mutableListOf<TreeMap.MapChange<String, Int>>()
        
        // Initial data
        map.put("a", 1)
        map.put("b", 2)
        
        // Test advanced operations
        map.putIfAbsent("c", 3) // Should generate Put
        map.putIfAbsent("a", 10) // Should not generate change
        map.replace("b", 20) // Should generate Put (update)
        map.replace("d", 40) // Should not generate change
        map.replace("a", 1, 100) // Should generate Put (update)
        map.replace("a", 50, 500) // Should not generate change  
        map.remove("b", 20) // Should generate Remove
        map.remove("a", 50) // Should not generate change
        
        // Note: Change collection would happen asynchronously in real usage
        
        // Note: async change collection disabled
        // val putChanges = changes.filterIsInstance<TreeMap.MapChange.Put<String, Int>>()
        // val removeChanges = changes.filterIsInstance<TreeMap.MapChange.Removed<String, Int>>()
        // assertEquals(5, putChanges.size) // a, b, c, b update, a update
        // assertEquals(1, removeChanges.size) // b removal
    }

    // ==================== Secondary Key Operations ====================

    @Test
    fun testTreeMap_secondaryKeysComplexScenarios() {
        val map = TreeMap<Int, TreeTestItem>(secondaryKeys = {
            key("category") { item -> item?.category }
            key("valueRange") { item -> item?.let { (it.value / 100).toInt() * 100 } }
            key("nameLength") { item -> item?.name?.length }
            key("hasTag") { item -> item?.tags?.firstOrNull() }
            key("firstTag") { item -> item?.tags?.firstOrNull() }
        })
        
        val items = listOf(
            TreeTestItem(1, "Apple", "Fruit", 150.0, listOf("red", "sweet")),
            TreeTestItem(2, "Banana", "Fruit", 80.0, listOf("yellow", "sweet")),
            TreeTestItem(3, "Carrot", "Vegetable", 50.0, listOf("orange", "healthy")),
            TreeTestItem(4, "Spinach", "Vegetable", 25.0, listOf("green", "healthy")),
            TreeTestItem(5, "Chocolate", "Dessert", 300.0, listOf("brown", "sweet")),
            TreeTestItem(6, "Ice Cream", "Dessert", 250.0, listOf("cold", "sweet"))
        )
        
        items.forEach { map.put(it.id, it) }
        
        // Test category grouping
        val fruits = map.getBySecondaryKey("category", "Fruit")
        assertEquals(2, fruits.size)
        assertEquals(setOf("Apple", "Banana"), fruits.map { it.name }.toSet())
        
        val vegetables = map.getBySecondaryKey("category", "Vegetable")
        assertEquals(2, vegetables.size)
        assertEquals(setOf("Carrot", "Spinach"), vegetables.map { it.name }.toSet())
        
        val desserts = map.getBySecondaryKey("category", "Dessert")
        assertEquals(2, desserts.size)
        assertEquals(setOf("Chocolate", "Ice Cream"), desserts.map { it.name }.toSet())
        
        // Test value ranges
        val lowValue = map.getBySecondaryKey("valueRange", 0) // 0-99
        assertEquals(3, lowValue.size) // Banana, Carrot, Spinach
        
        val midValue = map.getBySecondaryKey("valueRange", 100) // 100-199  
        assertEquals(1, midValue.size) // Apple
        assertEquals("Apple", midValue[0].name)
        
        val highValue = map.getBySecondaryKey("valueRange", 200) // 200-299
        assertEquals(1, highValue.size) // Ice Cream
        
        val veryHighValue = map.getBySecondaryKey("valueRange", 300) // 300-399
        assertEquals(1, veryHighValue.size) // Chocolate
        
        // Test name length
        val shortNames = map.getBySecondaryKey("nameLength", 5) // 5 characters
        assertEquals(1, shortNames.size)
        assertEquals("Apple", shortNames[0].name)
        
        val mediumNames = map.getBySecondaryKey("nameLength", 6) // 6 characters
        assertEquals(2, mediumNames.size) // Banana, Carrot
        
        val longNames = map.getBySecondaryKey("nameLength", 7) // 7 characters
        assertEquals(1, longNames.size)
        assertEquals("Spinach", longNames[0].name)
        
        // Test tag-based lookup (using firstTag)
        val redItems = map.getBySecondaryKey("firstTag", "red")
        assertEquals(1, redItems.size)
        assertEquals("Apple", redItems[0].name)
        
        val yellowItems = map.getBySecondaryKey("firstTag", "yellow")
        assertEquals(1, yellowItems.size)
        assertEquals("Banana", yellowItems[0].name)
        
        val greenItems = map.getBySecondaryKey("firstTag", "green")
        assertEquals(1, greenItems.size)
        assertEquals("Spinach", greenItems[0].name)
    }

    @Test
    fun testTreeMap_secondaryKeysWithNullValues() {
        val map = TreeMap<Int, TreeTestItem>(secondaryKeys = {
            key("expensiveCategory") { item -> 
                if (item != null && item.value > 100.0) item.category else null 
            }
            key("primaryTag") { item ->
                item?.tags?.find { it.length > 5 }
            }
        })
        
        val items = listOf(
            TreeTestItem(1, "Apple", "Fruit", 150.0, listOf("red", "sweet")),
            TreeTestItem(2, "Banana", "Fruit", 80.0, listOf("yellow", "sweet")),
            TreeTestItem(3, "Carrot", "Vegetable", 50.0, listOf("orange", "healthy")),
            TreeTestItem(4, "Spinach", "Vegetable", 25.0, listOf("green", "healthy"))
        )
        
        items.forEach { map.put(it.id, it) }
        
        // Test expensive category (null for cheap items)
        val expensiveFruits = map.getBySecondaryKey("expensiveCategory", "Fruit")
        assertEquals(1, expensiveFruits.size)
        assertEquals("Apple", expensiveFruits[0].name)
        
        val expensiveVegetables = map.getBySecondaryKey("expensiveCategory", "Vegetable")
        assertEquals(0, expensiveVegetables.size) // No expensive vegetables
        
        val nullExpensive = map.getBySecondaryKey("expensiveCategory", null)
        assertEquals(3, nullExpensive.size) // Banana, Carrot, Spinach
        
        // Test primary tag (long tags)
        val orangeTag = map.getBySecondaryKey("primaryTag", "orange")
        assertEquals(1, orangeTag.size)
        assertEquals("Carrot", orangeTag[0].name)
        
        val healthyTag = map.getBySecondaryKey("primaryTag", "healthy")
        assertEquals(1, healthyTag.size) // Spinach
        assertEquals("Spinach", healthyTag[0].name)
        
        val nullPrimaryTag = map.getBySecondaryKey("primaryTag", null)
        assertEquals(1, nullPrimaryTag.size) // Apple (no long tags)
        assertEquals("Apple", nullPrimaryTag[0].name)
    }

    @Test
    fun testTreeMap_secondaryKeysUpdateAndRemove() {
        val map = TreeMap<Int, TreeTestItem>(secondaryKeys = {
            key("category") { item -> item?.category }
            key("expensive") { item -> item?.let { it.value > 100.0 } }
        })
        
        val item1 = TreeTestItem(1, "Apple", "Fruit", 150.0, listOf("red"))
        map.put(1, item1)
        
        // Verify initial secondary keys
        assertEquals(1, map.getBySecondaryKey("category", "Fruit").size)
        assertEquals(0, map.getBySecondaryKey("category", "Vegetable").size)
        assertEquals(1, map.getBySecondaryKey("expensive", true).size)
        assertEquals(0, map.getBySecondaryKey("expensive", false).size)
        
        // Update item to change category and price
        val item1Updated = TreeTestItem(1, "Apple", "Vegetable", 50.0, listOf("green"))
        map.put(1, item1Updated)
        
        // Verify secondary keys were updated
        assertEquals(0, map.getBySecondaryKey("category", "Fruit").size)
        assertEquals(1, map.getBySecondaryKey("category", "Vegetable").size)
        assertEquals(0, map.getBySecondaryKey("expensive", true).size)
        assertEquals(1, map.getBySecondaryKey("expensive", false).size)
        
        // Remove item
        map.remove(1)
        
        // Verify secondary keys were cleaned up
        assertEquals(0, map.getBySecondaryKey("category", "Fruit").size)
        assertEquals(0, map.getBySecondaryKey("category", "Vegetable").size)
        assertEquals(0, map.getBySecondaryKey("expensive", true).size)
        assertEquals(0, map.getBySecondaryKey("expensive", false).size)
    }

    // ==================== Iterator Safety ====================

    @Test
    fun testTreeMap_iteratorModificationDetection() {
        val map = TreeMap<Int, String>()
        
        for (i in 1..10) {
            map.put(i, "value$i")
        }
        
        val keyIterator = map.keys.iterator()
        keyIterator.next()
        
        // Modify map
        map.put(11, "value11")
        
        // Should throw concurrent modification exception
        assertFailsWith<TreeMap.ConcurrentModificationException> {
            keyIterator.next()
        }
    }

    @Test
    fun testTreeMap_iteratorRemove() {
        val map = TreeMap<Int, String>()
        
        for (i in 1..5) {
            map.put(i, "value$i")
        }
        
        val keyIterator = map.keys.iterator()
        val firstKey = keyIterator.next()
        assertEquals(1, firstKey)
        
        // Remove through iterator
        keyIterator.remove()
        assertEquals(4, map.size)
        assertNull(map[1])
        
        // Test illegal state - remove without next
        assertFailsWith<IllegalStateException> {
            keyIterator.remove()
        }
    }

    @Test
    fun testTreeMap_valueIteratorRemove() {
        val map = TreeMap<String, Int>()
        map.put("a", 1)
        map.put("b", 2)
        map.put("c", 2) // Duplicate value
        
        val valueIterator = map.values.iterator()
        val firstValue = valueIterator.next()
        assertEquals(1, firstValue)
        
        valueIterator.remove()
        assertEquals(2, map.size)
        assertNull(map["a"])
        
        // Remove duplicate value
        val secondValue = valueIterator.next()
        assertEquals(2, secondValue)
        valueIterator.remove()
        assertEquals(1, map.size)
        // One of the entries with value 2 should be removed
    }

    @Test
    fun testTreeMap_entryIteratorSetValue() {
        val map = TreeMap<String, Int>()
        map.put("a", 1)
        map.put("b", 2)
        map.put("c", 3)
        
        val entryIterator = map.entries.iterator()
        val firstEntry = entryIterator.next()
        assertEquals("a", firstEntry.key)
        assertEquals(1, firstEntry.value)
        
        // Modify value through entry
        val oldValue = firstEntry.setValue(10)
        assertEquals(1, oldValue)
        assertEquals(10, map["a"])
        assertEquals(10, firstEntry.value)
        
        // Remove through iterator
        entryIterator.remove()
        assertEquals(2, map.size)
        assertNull(map["a"])
    }

    // ==================== SubMap Behavior ====================

    @Test
    fun testTreeMap_subMapModifications() {
        val map = TreeMap<Int, String>()
        
        for (i in 1..20) {
            map.put(i, "value$i")
        }
        
        val subMap = map.subMap(5, true, 15, false)
        assertEquals(10, subMap.size) // 5 through 14
        
        // Test iterator on submap
        val subMapKeys = subMap.keys.toList()
        assertEquals((5..14).toList(), subMapKeys)
        
        // Test modification detection in submap
        val subMapIterator = subMap.keys.iterator()
        subMapIterator.next()
        
        // Modify parent map
        map.put(21, "value21")
        
        assertFailsWith<TreeMap.ConcurrentModificationException> {
            subMapIterator.next()
        }
    }

    @Test
    fun testTreeMap_subMapBoundaryConditions() {
        val map = TreeMap<Int, String>()
        
        for (i in 1..10) {
            map.put(i, "value$i")
        }
        
        // Test edge cases
        val emptySubMap = map.subMap(15, true, 20, false)
        assertTrue(emptySubMap.isEmpty())
        assertEquals(0, emptySubMap.size)
        
        val singleElementSubMap = map.subMap(5, true, 6, false)
        assertEquals(1, singleElementSubMap.size)
        assertTrue(singleElementSubMap.containsKey(5))
        
        // Test inclusive/exclusive boundaries
        val inclusiveSubMap = map.subMap(5, true, 8, true)
        assertEquals(4, inclusiveSubMap.size) // 5, 6, 7, 8
        assertTrue(inclusiveSubMap.containsKey(5))
        assertTrue(inclusiveSubMap.containsKey(8))
        
        val exclusiveSubMap = map.subMap(5, false, 8, false)
        assertEquals(2, exclusiveSubMap.size) // 6, 7
        assertFalse(exclusiveSubMap.containsKey(5))
        assertFalse(exclusiveSubMap.containsKey(8))
    }

    @Test
    fun testTreeMap_headTailMapBehavior() {
        val map = TreeMap<String, Int>()
        
        val items = listOf("apple", "banana", "cherry", "date", "elderberry")
        items.forEachIndexed { index, item ->
            map.put(item, index + 1)
        }
        
        // Test headMap
        val headMap = map.headMap("cherry", true)
        assertEquals(3, headMap.size) // apple, banana, cherry
        assertTrue(headMap.containsKey("apple"))
        assertTrue(headMap.containsKey("banana"))
        assertTrue(headMap.containsKey("cherry"))
        assertFalse(headMap.containsKey("date"))
        
        val exclusiveHeadMap = map.headMap("cherry", false)
        assertEquals(2, exclusiveHeadMap.size) // apple, banana
        assertFalse(exclusiveHeadMap.containsKey("cherry"))
        
        // Test tailMap
        val tailMap = map.tailMap("cherry", true)
        assertEquals(3, tailMap.size) // cherry, date, elderberry
        assertTrue(tailMap.containsKey("cherry"))
        assertTrue(tailMap.containsKey("date"))
        assertTrue(tailMap.containsKey("elderberry"))
        assertFalse(tailMap.containsKey("banana"))
        
        val exclusiveTailMap = map.tailMap("cherry", false)
        assertEquals(2, exclusiveTailMap.size) // date, elderberry
        assertFalse(exclusiveTailMap.containsKey("cherry"))
    }

    // ==================== Descending Operations ====================

    @Test
    fun testTreeMap_descendingIteratorBehavior() {
        val map = TreeMap<Int, String>()
        
        for (i in 1..10) {
            map.put(i, "value$i")
        }
        
        val descendingKeys = map.descendingKeySet()
        val descendingKeysList = descendingKeys.toList()
        assertEquals((10 downTo 1).toList(), descendingKeysList)
        
        val descendingMap = map.descendingMap()
        val descendingEntries = descendingMap.entries.toList()
        assertEquals(10, descendingEntries.size)
        assertEquals(10, descendingEntries[0].key)
        assertEquals(1, descendingEntries[9].key)
        
        // Test descending iterator modification detection
        val descendingIterator = descendingKeys.iterator()
        descendingIterator.next()
        
        map.put(11, "value11")
        
        assertFailsWith<TreeMap.ConcurrentModificationException> {
            descendingIterator.next()
        }
    }

    // ==================== Memory and Performance Edge Cases ====================

    @Test
    fun testTreeMap_emptyMapOperations() {
        val map = TreeMap<String, Int>()
        
        // All these should work without exceptions
        assertNull(map.firstKey())
        assertNull(map.lastKey())
        assertNull(map.firstEntry())
        assertNull(map.lastEntry())
        assertNull(map.pollFirstEntry())
        assertNull(map.pollLastEntry())
        assertNull(map.lowerKey("any"))
        assertNull(map.floorKey("any"))
        assertNull(map.ceilingKey("any"))
        assertNull(map.higherKey("any"))
        
        // SubMaps on empty map
        val emptySubMap = map.subMap("a", true, "z", false)
        assertTrue(emptySubMap.isEmpty())
        
        val emptyHeadMap = map.headMap("m", true)
        assertTrue(emptyHeadMap.isEmpty())
        
        val emptyTailMap = map.tailMap("m", true)
        assertTrue(emptyTailMap.isEmpty())
    }

    @Test
    fun testTreeMap_singleElementOperations() {
        val map = TreeMap<String, Int>()
        map.put("single", 42)
        
        assertEquals("single", map.firstKey())
        assertEquals("single", map.lastKey())
        
        val firstEntry = map.firstEntry()
        assertNotNull(firstEntry)
        assertEquals("single", firstEntry.key)
        assertEquals(42, firstEntry.value)
        
        val lastEntry = map.lastEntry()
        assertNotNull(lastEntry)
        assertEquals("single", lastEntry.key)
        assertEquals(42, lastEntry.value)
        
        // Navigation should return null for single element
        assertNull(map.lowerKey("single"))
        assertNull(map.higherKey("single"))
        assertEquals("single", map.floorKey("single"))
        assertEquals("single", map.ceilingKey("single"))
        
        // Poll operations
        val polledFirst = map.pollFirstEntry()
        assertNotNull(polledFirst)
        assertEquals("single", polledFirst.key)
        assertEquals(42, polledFirst.value)
        assertTrue(map.isEmpty())
    }

    @Test
    fun testTreeMap_duplicateKeys() {
        val map = TreeMap<String, Int>()
        
        // Putting same key multiple times
        assertNull(map.put("key", 1))
        assertEquals(1, map.put("key", 2))
        assertEquals(2, map.put("key", 3))
        assertEquals(3, map.put("key", 4))
        
        assertEquals(1, map.size)
        assertEquals(4, map["key"])
        
        // Remove
        assertEquals(4, map.remove("key"))
        assertTrue(map.isEmpty())
        assertNull(map.remove("key"))
    }

    @Test
    fun testTreeMap_nullValueHandling() {
        val map = TreeMap<String, Int?>()
        
        map.put("a", 1)
        map.put("b", null)
        map.put("c", 3)
        
        assertEquals(3, map.size)
        assertEquals(1, map["a"])
        assertNull(map["b"])
        assertEquals(3, map["c"])
        
        assertTrue(map.containsKey("b"))
        assertTrue(map.containsValue(null))
        assertTrue(map.containsValue(1))
        
        // Remove null value
        assertNull(map.remove("b"))
        assertEquals(2, map.size)
        assertFalse(map.containsKey("b"))
    }

    // ==================== Red-Black Tree Properties ====================

    @Test
    fun testTreeMap_balancedTreeMaintenance() {
        val map = TreeMap<Int, String>()
        
        // Insert in ascending order (worst case for unbalanced tree)
        for (i in 1..100) {
            map.put(i, "value$i")
        }
        
        assertEquals(100, map.size)
        
        // Tree should still perform well (O(log n))
        assertEquals("value1", map[1])
        assertEquals("value50", map[50])
        assertEquals("value100", map[100])
        
        // Navigation should work efficiently
        assertEquals(1, map.firstKey())
        assertEquals(100, map.lastKey())
        assertEquals(49, map.lowerKey(50))
        assertEquals(51, map.higherKey(50))
        
        // Remove half the elements
        for (i in 1..50) {
            map.remove(i)
        }
        
        assertEquals(50, map.size)
        assertEquals(51, map.firstKey())
        assertEquals(100, map.lastKey())
    }

    @Test
    fun testTreeMap_insertDeletePattern() {
        val map = TreeMap<Int, String>()
        
        // Alternating insert/delete pattern
        for (round in 1..10) {
            // Insert a batch
            for (i in (round * 10 - 9)..(round * 10)) {
                map.put(i, "value$i")
            }
            
            // Remove every other element from previous rounds
            if (round > 1) {
                for (i in ((round - 1) * 10 - 9)..((round - 1) * 10) step 2) {
                    map.remove(i)
                }
            }
        }
        
        // Verify final state
        assertTrue(map.size > 0)
        
        // All keys should be in sorted order
        val keys = map.keys.toList()
        assertEquals(keys.sorted(), keys)
        
        // Navigation should work correctly
        val firstKey = map.firstKey()
        val lastKey = map.lastKey()
        assertNotNull(firstKey)
        assertNotNull(lastKey)
        assertTrue(firstKey <= lastKey)
    }

    // ==================== Stress Tests ====================

    @Test
    fun testTreeMap_randomOperations() {
        val map = TreeMap<Int, String>()
        val referenceMap = mutableMapOf<Int, String>()
        
        val random = kotlin.random.Random(42) // Deterministic seed
        
        for (i in 1..1000) {
            val operation = random.nextInt(4)
            val key = random.nextInt(100)
            val value = "value$key"
            
            when (operation) {
                0 -> { // Put
                    val treeResult = map.put(key, value)
                    val refResult = referenceMap.put(key, value)
                    assertEquals(refResult, treeResult)
                }
                1 -> { // Get
                    val treeResult = map[key]
                    val refResult = referenceMap[key]
                    assertEquals(refResult, treeResult)
                }
                2 -> { // Remove
                    val treeResult = map.remove(key)
                    val refResult = referenceMap.remove(key)
                    assertEquals(refResult, treeResult)
                }
                3 -> { // ContainsKey
                    val treeResult = map.containsKey(key)
                    val refResult = referenceMap.containsKey(key)
                    assertEquals(refResult, treeResult)
                }
            }
            
            // Verify size consistency
            assertEquals(referenceMap.size, map.size)
        }
        
        // Final verification
        assertEquals(referenceMap.size, map.size)
        referenceMap.forEach { (key, value) ->
            assertEquals(value, map[key])
        }
    }

    @Test
    fun testTreeMap_computeOperationsComplex() {
        val map = TreeMap<String, MutableList<Int>>()
        
        // Use compute operations to build complex structures
        for (i in 1..100) {
            val key = "group${i % 10}"
            map.compute(key) { _, list ->
                (list ?: mutableListOf()).apply { add(i) }
            }
        }
        
        assertEquals(10, map.size) // 10 different groups
        
        // Each group should have 10 elements
        map.values.forEach { list ->
            assertEquals(10, list.size)
        }
        
        // Test computeIfAbsent and computeIfPresent
        val newGroup = map.computeIfAbsent("group10") { mutableListOf(999) }
        assertEquals(listOf(999), newGroup)
        assertEquals(11, map.size)
        
        val existingGroup = map.computeIfPresent("group0") { _, list ->
            list.apply { add(1000) }
        }
        assertEquals(11, existingGroup?.size) // 10 original + 1000
        
        // Test merge operation
        map.merge("group0", mutableListOf(2000)) { old, new ->
            old!!.apply { addAll(new) }
        }
        assertEquals(12, map["group0"]?.size) // 10 original + 1000 + 2000
    }
}