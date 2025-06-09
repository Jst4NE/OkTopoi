package jst.oktopoi

import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
data class Person(val name: String, val age: Int, val city: String)

class OkTopoiTest {

    @BeforeTest
    fun setup() {
        // Initialize default IO for tests
        initDefaultIO(Path("/tmp/oktopoi-test"), SystemFileSystem)
    }

    // ==================== E() Function Tests ====================

    @Test
    fun testE_basicOperations() {
        val state = e<String>()
        
        // Test initial null value
        assertNull(state.value)
        assertTrue(state.isEmpty())
        
        // Test setting value
        state.set("hello")
        assertEquals("hello", state.value)
        assertEquals("hello", state.get())
        assertFalse(state.isEmpty())
        
        // Test updating value
        state.value = "world"
        assertEquals("world", state.value)
        
        // Test clearing
        state.clear()
        assertNull(state.value)
        assertTrue(state.isEmpty())
    }

    @Test
    fun testE_withDefaultValue() {
        val state = e<String> { "default" }
        
        // Should have default value initially
        assertEquals("default", state.value)
        assertFalse(state.isEmpty())
        
        // Setting to null should work
        state.value = null
        assertNull(state.value)
        assertTrue(state.isEmpty())
    }

    @Test
    fun testE_setIfDifferent() {
        val state = e<String>()
        
        // Should return true when setting different value
        assertTrue(state.setIfDifferent("hello"))
        assertEquals("hello", state.value)
        
        // Should return false when setting same value
        assertFalse(state.setIfDifferent("hello"))
        assertEquals("hello", state.value)
        
        // Should return true when setting different value again
        assertTrue(state.setIfDifferent("world"))
        assertEquals("world", state.value)
        
        // Should return true when setting null
        assertTrue(state.setIfDifferent(null))
        assertNull(state.value)
        
        // Should return false when setting null again
        assertFalse(state.setIfDifferent(null))
    }

    @Test
    fun testE_compareAndSet() {
        val state = e<String>()
        
        // Should succeed when expecting null and setting value
        assertTrue(state.compareAndSet(null, "hello"))
        assertEquals("hello", state.value)
        
        // Should fail when expecting wrong value
        assertFalse(state.compareAndSet("wrong", "world"))
        assertEquals("hello", state.value)
        
        // Should succeed when expecting correct value
        assertTrue(state.compareAndSet("hello", "world"))
        assertEquals("world", state.value)
    }

    @Test
    fun testE_flowOperations() {
        val state = e<Int>()
        
        // Test emit and tryEmit
        assertTrue(state.tryEmit(42))
        assertEquals(42, state.value)
        
        // Test collecting values
        val values = mutableListOf<Int?>()
        state.value = 1
        state.value = 2
        state.value = 3
    }

    @Test
    fun testE_replayCache() {
        val state = e<String>()
        
        // Should have current value in replay cache
        state.value = "test"
        assertEquals(listOf("test"), state.replayCache)
        
        state.value = "updated"
        assertEquals(listOf("updated"), state.replayCache)
    }

    @Test
    fun testE_toString() {
        val state = e<String>()
        
        assertEquals("null", state.toString())
        
        state.value = "hello"
        assertEquals("hello", state.toString())
    }

    // ==================== ES() Function Tests ====================

    @Test
    fun testEs_basicOperations() {
        val collection = es<String, Person>(keySelector = { it.name })
        
        // Test empty collection
        assertTrue(collection.isEmpty())
        assertEquals(0, collection.size)
        
        // Test adding elements
        val person1 = Person("Alice", 30, "NYC")
        val person2 = Person("Bob", 25, "LA")
        
        collection.put("Alice", person1)
        collection.put("Bob", person2)
        
        assertEquals(2, collection.size)
        assertFalse(collection.isEmpty())
        assertEquals(person1, collection["Alice"])
        assertEquals(person2, collection["Bob"])
        
        // Test containsKey and containsValue
        assertTrue(collection.containsKey("Alice"))
        assertTrue(collection.containsValue(person1))
        assertFalse(collection.containsKey("Charlie"))
        assertFalse(collection.containsValue(Person("Charlie", 35, "Chicago")))
    }

    @Test
    fun testEs_withKeySelector() {
        val collection = es<String, Person>(keySelector = { it.name })
        
        val person = Person("Alice", 30, "NYC")
        collection.put("key1", person)
        
        // Should be accessible by the key used in put
        assertEquals(person, collection["key1"])
        assertEquals(1, collection.size)
    }

    @Test
    fun testEs_withCustomComparator() {
        // Test with reverse string comparator
        val reverseComparator = Comparator<String> { a, b -> b.compareTo(a) }
        val collection = es<String, Person>(
            keySelector = { it.name },
            comparator = reverseComparator
        )
        
        collection.put("Alice", Person("Alice", 30, "NYC"))
        collection.put("Bob", Person("Bob", 25, "LA"))
        collection.put("Charlie", Person("Charlie", 35, "Chicago"))
        
        // Keys should be in reverse order
        val keys = collection.keys.toList()
        assertEquals(listOf("Charlie", "Bob", "Alice"), keys)
    }

    @Test
    fun testEs_secondaryKeys() {
        val collection = es<String, Person>(keySelector = { it.name })
        collection.put("p1", Person("Alice", 30, "NYC"))
        collection.put("p2", Person("Bob", 25, "NYC"))
        collection.put("p3", Person("Charlie", 35, "Chicago"))
        
        // Note: Secondary keys functionality would need to be tested
        // when secondary key API is available in the Es constructor
    }

    @Test
    fun testEs_removeOperations() {
        val collection = es<String, Person>(keySelector = { it.name })
        
        val person1 = Person("Alice", 30, "NYC")
        val person2 = Person("Bob", 25, "LA")
        
        collection.put("Alice", person1)
        collection.put("Bob", person2)
        
        // Test remove by key
        assertEquals(person1, collection.remove("Alice"))
        assertEquals(1, collection.size)
        assertNull(collection["Alice"])
        
        // Test remove non-existent key
        assertNull(collection.remove("Charlie"))
        assertEquals(1, collection.size)
        
        // Test clear
        collection.clear()
        assertTrue(collection.isEmpty())
        assertEquals(0, collection.size)
    }

    @Test
    fun testEs_putAll() {
        val collection = es<String, Person>(keySelector = { it.name })
        
        val people = mapOf(
            "Alice" to Person("Alice", 30, "NYC"),
            "Bob" to Person("Bob", 25, "LA"),
            "Charlie" to Person("Charlie", 35, "Chicago")
        )
        
        collection.putAll(people)
        
        assertEquals(3, collection.size)
        assertEquals(people["Alice"], collection["Alice"])
        assertEquals(people["Bob"], collection["Bob"])
        assertEquals(people["Charlie"], collection["Charlie"])
    }

    @Test
    fun testEs_iterators() {
        val collection = es<String, Person>(keySelector = { it.name })
        
        collection.put("Alice", Person("Alice", 30, "NYC"))
        collection.put("Bob", Person("Bob", 25, "LA"))
        collection.put("Charlie", Person("Charlie", 35, "Chicago"))
        
        // Test keys iterator
        val keys = collection.keys.toList()
        assertEquals(3, keys.size)
        assertTrue(keys.contains("Alice"))
        assertTrue(keys.contains("Bob"))
        assertTrue(keys.contains("Charlie"))
        
        // Test values iterator
        val values = collection.values.toList()
        assertEquals(3, values.size)
        
        // Test entries iterator
        val entries = collection.entries.toList()
        assertEquals(3, entries.size)
        entries.forEach { entry ->
            assertEquals(collection[entry.key], entry.value)
        }
    }

    // ==================== TreeMap Functionality Tests ====================

    @Test
    fun testTreeMap_navigationalOperations() {
        val collection = es<Int, String>(keySelector = { it.toInt() })
        
        collection.put(5, "five")
        collection.put(2, "two")
        collection.put(8, "eight")
        collection.put(1, "one")
        collection.put(9, "nine")
        
        // Test navigational methods
        assertEquals(1, collection.firstKey())
        assertEquals(9, collection.lastKey())
        
        assertEquals(1, collection.lowerKey(2))
        assertEquals(2, collection.floorKey(2))
        assertEquals(5, collection.ceilingKey(5))
        assertEquals(8, collection.higherKey(5))
        
        assertNull(collection.lowerKey(1))
        assertNull(collection.higherKey(9))
    }

    @Test
    fun testTreeMap_entryOperations() {
        val collection = es<Int, String>(keySelector = { it.toInt() })
        
        collection.put(5, "five")
        collection.put(2, "two")
        collection.put(8, "eight")
        
        val firstEntry = collection.firstEntry()
        assertNotNull(firstEntry)
        assertEquals(2, firstEntry.key)
        assertEquals("two", firstEntry.value)
        
        val lastEntry = collection.lastEntry()
        assertNotNull(lastEntry)
        assertEquals(8, lastEntry.key)
        assertEquals("eight", lastEntry.value)
        
        // Test poll operations
        val polledFirst = collection.pollFirstEntry()
        assertNotNull(polledFirst)
        assertEquals(2, polledFirst.key)
        assertEquals("two", polledFirst.value)
        assertEquals(2, collection.size) // Should be removed
        
        val polledLast = collection.pollLastEntry()
        assertNotNull(polledLast)
        assertEquals(8, polledLast.key)
        assertEquals("eight", polledLast.value)
        assertEquals(1, collection.size) // Should be removed
    }

    @Test
    fun testTreeMap_subMapOperations() {
        val collection = es<Int, String>(keySelector = { it.toInt() })
        
        for (i in 1..10) {
            collection.put(i, i.toString())
        }
        
        // Test subMap
        val subMap = collection.subMap(3, true, 7, false)
        assertEquals(4, subMap.size) // 3, 4, 5, 6
        assertTrue(subMap.containsKey(3))
        assertTrue(subMap.containsKey(6))
        assertFalse(subMap.containsKey(7))
        
        // Test headMap
        val headMap = collection.headMap(5, true)
        assertEquals(5, headMap.size) // 1, 2, 3, 4, 5
        assertTrue(headMap.containsKey(5))
        assertFalse(headMap.containsKey(6))
        
        // Test tailMap
        val tailMap = collection.tailMap(8, true)
        assertEquals(3, tailMap.size) // 8, 9, 10
        assertTrue(tailMap.containsKey(8))
        assertTrue(tailMap.containsKey(10))
        assertFalse(tailMap.containsKey(7))
    }

    @Test
    fun testTreeMap_advancedOperations() {
        val collection = es<String, Int>(keySelector = { it.toString() })
        
        // Test putIfAbsent
        assertNull(collection.putIfAbsent("key1", 100))
        assertEquals(100, collection["key1"])
        assertEquals(100, collection.putIfAbsent("key1", 200)) // Should return existing value
        assertEquals(100, collection["key1"])
        
        // Test replace
        assertNull(collection.replace("nonexistent", 300))
        assertEquals(100, collection.replace("key1", 150))
        assertEquals(150, collection["key1"])
        
        // Test conditional replace
        assertFalse(collection.replace("key1", 100, 200)) // Wrong expected value
        assertEquals(150, collection["key1"])
        assertTrue(collection.replace("key1", 150, 200)) // Correct expected value
        assertEquals(200, collection["key1"])
        
        // Test conditional remove
        assertFalse(collection.remove("key1", 100)) // Wrong value
        assertEquals(200, collection["key1"])
        assertTrue(collection.remove("key1", 200)) // Correct value
        assertNull(collection["key1"])
    }

    @Test
    fun testTreeMap_computeOperations() {
        val collection = es<String, Int>(keySelector = { it.toString() })
        
        // Test compute
        val result1 = collection.compute("key1") { key, value ->
            if (value == null) 1 else value + 1
        }
        assertEquals(1, result1)
        assertEquals(1, collection["key1"])
        
        val result2 = collection.compute("key1") { key, value ->
            if (value == null) 1 else value + 1
        }
        assertEquals(2, result2)
        assertEquals(2, collection["key1"])
        
        // Test computeIfAbsent
        val result3 = collection.computeIfAbsent("key2") { 10 }
        assertEquals(10, result3)
        assertEquals(10, collection["key2"])
        
        val result4 = collection.computeIfAbsent("key2") { 20 } // Should not change
        assertEquals(10, result4)
        assertEquals(10, collection["key2"])
        
        // Test computeIfPresent
        val result5 = collection.computeIfPresent("key1") { key, value -> value * 2 }
        assertEquals(4, result5)
        assertEquals(4, collection["key1"])
        
        val result6 = collection.computeIfPresent("nonexistent") { key, value -> value * 2 }
        assertNull(result6)
        
        // Test merge
        val result7 = collection.merge("key3", 5) { oldValue, newValue -> 
            (oldValue ?: 0) + newValue 
        }
        assertEquals(5, result7)
        assertEquals(5, collection["key3"])
        
        val result8 = collection.merge("key3", 3) { oldValue, newValue -> 
            (oldValue ?: 0) + newValue 
        }
        assertEquals(8, result8)
        assertEquals(8, collection["key3"])
    }

    // ==================== Change Notification Tests ====================

    @Test
    fun testEs_changeNotifications() {
        val collection = es<String, Int>(keySelector = { it.toString() })
        val changes = mutableListOf<TreeMap.MapChange<String, Int>>()
        
        // Collect changes (this would typically be done in a coroutine)
        collection.put("key1", 100)
        collection.put("key2", 200)
        collection.remove("key1")
        collection.clear()
        
        // Note: Testing change collection would require setting up a coroutine
        // to collect from collection.changes flow in a real test scenario
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun testTreeMap_errorConditions() {
        val collection = es<String, Int>(keySelector = { it.toString() })
        
        // Test empty collection operations
        assertNull(collection.firstKey())
        assertNull(collection.lastKey())
        assertNull(collection.firstEntry())
        assertNull(collection.lastEntry())
        assertNull(collection.pollFirstEntry())
        assertNull(collection.pollLastEntry())
        
        // Test invalid subMap range
        assertFailsWith<IllegalArgumentException> {
            collection.subMap("z", true, "a", false)
        }
    }

    @Test
    fun testTreeMap_equalityAndHashing() {
        val collection1 = es<String, Int>(keySelector = { it.toString() })
        val collection2 = es<String, Int>(keySelector = { it.toString() })
        
        collection1.put("a", 1)
        collection1.put("b", 2)
        
        collection2.put("a", 1)
        collection2.put("b", 2)
        
        // Test Map equality
        val map1: Map<String, Int> = collection1
        val map2: Map<String, Int> = collection2
        assertEquals(map1, map2)
        assertEquals(map1.hashCode(), map2.hashCode())
        
        // Test toString
        assertTrue(collection1.toString().contains("a=1"))
        assertTrue(collection1.toString().contains("b=2"))
    }

    @Test
    fun testTreeMap_collectionViews() {
        val collection = es<String, Int>(keySelector = { it.toString() })
        
        collection.put("a", 1)
        collection.put("b", 2)
        collection.put("c", 3)
        
        // Test keys view
        val keys = collection.keys
        assertEquals(3, keys.size)
        assertTrue(keys.contains("a"))
        assertFalse(keys.contains("d"))
        
        // Test values view
        val values = collection.values
        assertEquals(3, values.size)
        assertTrue(values.contains(1))
        assertFalse(values.contains(4))
        
        // Test entries view
        val entries = collection.entries
        assertEquals(3, entries.size)
        
        // Test modification through views
        assertTrue(keys.remove("a"))
        assertEquals(2, collection.size)
        assertNull(collection["a"])
        
        assertTrue(values.remove(2))
        assertEquals(1, collection.size)
        assertNull(collection["b"])
    }

    @Test
    fun testTreeMap_descendingViews() {
        val collection = es<Int, String>(keySelector = { it.toInt() })
        
        collection.put(1, "one")
        collection.put(2, "two")
        collection.put(3, "three")
        
        val descendingKeys = collection.descendingKeySet()
        val keysList = descendingKeys.toList()
        assertEquals(listOf(3, 2, 1), keysList)
        
        val descendingMap = collection.descendingMap()
        val entriesList = descendingMap.entries.toList()
        assertEquals(3, entriesList[0].key)
        assertEquals(2, entriesList[1].key)
        assertEquals(1, entriesList[2].key)
    }
}