package jst.oktopoi

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
data class TestPerson(val name: String, val age: Int)

class SimpleOkTopoiTest {

    @BeforeTest
    fun setup() {
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
    fun testE_serializable() {
        val state = e<TestPerson>()
        
        val person = TestPerson("Alice", 30)
        state.value = person
        
        assertEquals(person, state.value)
        assertEquals("Alice", state.value?.name)
        assertEquals(30, state.value?.age)
    }

    // ==================== ES() Function Tests ====================

    @Test
    fun testEs_basicOperations() {
        val collection = es<String, TestPerson>()
        
        // Test empty collection
        assertTrue(collection.isEmpty())
        assertEquals(0, collection.size)
        
        // Test adding elements
        val person1 = TestPerson("Alice", 30)
        val person2 = TestPerson("Bob", 25)
        
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
    }

    @Test
    fun testEs_removeOperations() {
        val collection = es<String, TestPerson>()
        
        val person1 = TestPerson("Alice", 30)
        val person2 = TestPerson("Bob", 25)
        
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
        val collection = es<String, TestPerson>()
        
        val people = mapOf(
            "Alice" to TestPerson("Alice", 30),
            "Bob" to TestPerson("Bob", 25),
            "Charlie" to TestPerson("Charlie", 35)
        )
        
        collection.putAll(people)
        
        assertEquals(3, collection.size)
        assertEquals(people["Alice"], collection["Alice"])
        assertEquals(people["Bob"], collection["Bob"])
        assertEquals(people["Charlie"], collection["Charlie"])
    }

    @Test
    fun testEs_iterators() {
        val collection = es<String, TestPerson>()
        
        collection.put("Alice", TestPerson("Alice", 30))
        collection.put("Bob", TestPerson("Bob", 25))
        collection.put("Charlie", TestPerson("Charlie", 35))
        
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

    // ==================== TreeMap Navigation Tests ====================

    @Test
    fun testTreeMap_navigationalOperations() {
        val collection = es<Int, String>()
        
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
        val collection = es<Int, String>()
        
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
        val collection = es<Int, String>()
        
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

    // ==================== TreeMap Advanced Operations ====================

    @Test
    fun testTreeMap_advancedOperations() {
        val collection = es<String, Int>()
        
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
        val collection = es<String, Int>()
        
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

    // ==================== Error Handling Tests ====================

    @Test
    fun testTreeMap_errorConditions() {
        val collection = es<String, Int>()
        
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
        val collection1 = es<String, Int>()
        val collection2 = es<String, Int>()
        
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

    // ==================== Persistence Basic Tests ====================

    @Test
    fun testEp_basicOperations() {
        val persistedState = ep<String>()
        
        // Initially should be null
        assertNull(persistedState.value)
        
        // Set a value - this should trigger persistence
        persistedState.value = "persisted_value"
        assertEquals("persisted_value", persistedState.value)
        
        // Basic operations should work like regular E
        persistedState.clear()
        assertNull(persistedState.value)
        assertTrue(persistedState.isEmpty())
    }

    @Test
    fun testEp_withDefaultValue() {
        val persistedState = ep<String> { "default_persistent" }
        
        // Should have default value initially
        assertEquals("default_persistent", persistedState.value)
        
        // Change value
        persistedState.value = "new_value"
        assertEquals("new_value", persistedState.value)
        
        // Clear to null
        persistedState.clear()
        assertNull(persistedState.value)
    }

    @Test
    fun testEp_serializable() {
        val userState = ep<TestPerson>()
        
        val user = TestPerson("John Doe", 25)
        userState.value = user
        
        assertEquals(user, userState.value)
        assertEquals("John Doe", userState.value?.name)
        assertEquals(25, userState.value?.age)
    }

    @Test
    fun testEps_basicOperations() {
        val persistedCollection = eps<String, TestPerson>()
        
        // Initially should be empty
        assertTrue(persistedCollection.isEmpty())
        assertEquals(0, persistedCollection.size)
        
        // Add some users
        val user1 = TestPerson("Alice", 30)
        val user2 = TestPerson("Bob", 25)
        
        persistedCollection.put("alice", user1)
        persistedCollection.put("bob", user2)
        
        assertEquals(2, persistedCollection.size)
        assertEquals(user1, persistedCollection["alice"])
        assertEquals(user2, persistedCollection["bob"])
    }

    @Test
    fun testEps_removeOperations() {
        val persistedCollection = eps<Int, TestPerson>()
        
        val user1 = TestPerson("Alice", 30)
        val user2 = TestPerson("Bob", 25)
        
        persistedCollection.put(1, user1)
        persistedCollection.put(2, user2)
        
        assertEquals(2, persistedCollection.size)
        
        // Remove one user
        val removed = persistedCollection.remove(2)
        assertEquals(user2, removed)
        assertEquals(1, persistedCollection.size)
        assertNull(persistedCollection[2])
        
        // Clear all
        persistedCollection.clear()
        assertTrue(persistedCollection.isEmpty())
        assertEquals(0, persistedCollection.size)
    }

    // ==================== Secondary Keys Tests ====================

    @Test
    fun testTreeMap_secondaryKeys() {
        val map = TreeMap<Int, TestPerson>(secondaryKeys = {
            key("name") { it.name }
            key("ageGroup") { it.age / 10 }
        })
        
        val person1 = TestPerson("Alice", 25)
        val person2 = TestPerson("Bob", 35)
        val person3 = TestPerson("Anna", 27)
        
        map.put(1, person1)
        map.put(2, person2)
        map.put(3, person3)
        
        // Test secondary key lookups
        val aliceByName = map.getBySecondaryKey("name", "Alice")
        assertEquals(1, aliceByName.size)
        assertEquals(person1, aliceByName[0])
        
        val twentySomethings = map.getBySecondaryKey("ageGroup", 2)
        assertEquals(2, twentySomethings.size)
        assertTrue(twentySomethings.contains(person1))
        assertTrue(twentySomethings.contains(person3))
        
        val thirtySomethings = map.getBySecondaryKey("ageGroup", 3)
        assertEquals(1, thirtySomethings.size)
        assertEquals(person2, thirtySomethings[0])
    }
}