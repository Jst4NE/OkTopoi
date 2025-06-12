package jst.oktopoi

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadSafetyBasicTest {

    @Test
    fun testBasicMapOperations() = runTest {
        val map = TreeMap<String, Int>()
        
        // Test suspend API
        assertNull(map.getSuspend("key1"))
        assertNull(map.putSuspend("key1", 100))
        assertEquals(100, map.getSuspend("key1"))
        assertEquals(100, map.putSuspend("key1", 200))
        assertEquals(200, map.getSuspend("key1"))
        
        assertTrue(map.containsKeySuspend("key1"))
        assertFalse(map.containsKeySuspend("key2"))
        
        assertEquals(200, map.removeSuspend("key1"))
        assertNull(map.getSuspend("key1"))
    }

    @Test
    fun testOperatorSyntax() = runTest {
        val map = TreeMap<String, Int>()
        
        // Test operator extensions
        assertNull(map["key1"])
        map["key1"] = 100
        assertEquals(100, map["key1"])
        
        assertTrue("key1" in map)
        assertFalse("key2" in map)
        
        map -= "key1"
        assertNull(map["key1"])
    }

    @Test
    fun testBatchOperations() = runTest {
        val map = TreeMap<String, Int>()
        
        // Test batch putAll
        map.putAll("a" to 1, "b" to 2, "c" to 3)
        assertEquals(3, map.size)
        assertEquals(1, map["a"])
        assertEquals(2, map["b"])
        assertEquals(3, map["c"])
        
        // Test batch removeAll
        map.removeAll("a", "c")
        assertEquals(1, map.size)
        assertEquals(2, map["b"])
        assertNull(map["a"])
        assertNull(map["c"])
    }

    @Test
    fun testEsSecondaryKeys() = runTest {
        val es = Es<Int, Person>(
            persisted = null,
            keySelector = { it.id },
            sortingBy = null
        ) {
            key("name") { it.name }
            key("age") { it.age }
        }

        val person1 = Person(1, "Alice", 25)
        val person2 = Person(2, "Bob", 30)
        val person3 = Person(3, "Alice", 35)
        
        es[1] = person1
        es[2] = person2  
        es[3] = person3

        // Test secondary key queries
        val alices = es.bySecondaryKey("name", "Alice")
        assertEquals(2, alices.size)
        assertTrue(alices.any { it.id == 1 })
        assertTrue(alices.any { it.id == 3 })

        val age30 = es.bySecondaryKey("age", 30)
        assertEquals(1, age30.size)
        assertEquals(person2, age30.first())
    }

    @Test
    fun testConcurrentAccess() = runTest {
        val map = TreeMap<Int, String>()
        
        // Test concurrent reads and writes
        val jobs = (1..10).map { threadId ->
            async {
                repeat(100) { i ->
                    val key = threadId * 100 + i
                    map[key] = "value_$key"
                    assertEquals("value_$key", map[key])
                }
            }
        }
        
        jobs.awaitAll()
        
        // Verify all data is present
        assertEquals(1000, map.size)
        for (i in 1..10) {
            for (j in 0 until 100) {
                val key = i * 100 + j
                assertEquals("value_$key", map[key])
            }
        }
    }

    @Test
    fun testBackwardCompatibility() {
        val map = TreeMap<String, Int>()
        
        // Test that blocking API still works
        assertNull(map.get("key1"))
        assertNull(map.put("key1", 100))
        assertEquals(100, map.get("key1"))
        
        assertTrue(map.containsKey("key1"))
        assertFalse(map.containsKey("key2"))
        
        map.putAll(mapOf("key2" to 200, "key3" to 300))
        assertEquals(3, map.size)
        
        map.clear()
        assertEquals(0, map.size)
        assertTrue(map.isEmpty())
    }

    data class Person(
        val id: Int,
        val name: String,
        val age: Int
    )
}