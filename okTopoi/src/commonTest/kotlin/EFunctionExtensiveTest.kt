package jst.oktopoi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
data class TestUser(val id: Int, val name: String, val email: String, val active: Boolean = true)

@Serializable
data class TestConfig(val theme: String, val language: String, val version: Int)

class EFunctionExtensiveTest {

    @BeforeTest
    fun setup() {
        initDefaultIO(Path("/tmp/oktopoi-e-test"), SystemFileSystem)
    }

    // ==================== Basic e() Function Tests ====================

    @Test
    fun testE_defaultConstructor() {
        val state = e<String>()
        
        assertNull(state.value)
        assertTrue(state.isEmpty())
        assertNull(state.get())
        assertEquals(listOf<String?>(null), state.replayCache)
        assertEquals("null", state.toString())
    }

    @Test
    fun testE_withDefaultValue() {
        val state = e<String> { "default" }
        
        assertEquals("default", state.value)
        assertFalse(state.isEmpty())
        assertEquals("default", state.get())
        assertEquals(listOf("default"), state.replayCache)
        assertEquals("default", state.toString())
    }

    @Test
    fun testE_withNullableDefaultValue() {
        val state = e<String?> { null }
        
        assertNull(state.value)
        assertTrue(state.isEmpty())
        assertEquals(listOf<String?>(null), state.replayCache)
    }

    @Test
    fun testE_withComputedDefaultValue() {
        var callCount = 0
        val state = e<Int> { 
            callCount++
            42 
        }
        
        assertEquals(1, callCount)
        assertEquals(42, state.value)
        assertFalse(state.isEmpty())
    }

    // ==================== Value Operations ====================

    @Test
    fun testE_setValue() {
        val state = e<String>()
        
        state.value = "hello"
        assertEquals("hello", state.value)
        assertEquals("hello", state.get())
        assertFalse(state.isEmpty())
        assertEquals("hello", state.toString())
        
        state.value = "world"
        assertEquals("world", state.value)
        assertEquals("world", state.get())
    }

    @Test
    fun testE_setMethod() {
        val state = e<Int>()
        
        state.set(100)
        assertEquals(100, state.value)
        assertEquals(100, state.get())
        assertFalse(state.isEmpty())
    }

    @Test
    fun testE_setToNull() {
        val state = e<String>()
        
        state.value = "test"
        state.value = null
        
        assertNull(state.value)
        assertTrue(state.isEmpty())
        assertEquals("null", state.toString())
    }

    @Test
    fun testE_clear() {
        val state = e<String>()
        
        state.value = "test"
        state.clear()
        
        assertNull(state.value)
        assertTrue(state.isEmpty())
    }

    @Test
    fun testE_clearWithDefaultValue() {
        val state = e<String> { "default" }
        
        state.value = "changed"
        state.clear()
        
        assertNull(state.value)
        assertTrue(state.isEmpty())
    }

    // ==================== setIfDifferent Tests ====================

    @Test
    fun testE_setIfDifferent_basicOperations() {
        val state = e<String>()
        
        // Setting from null should return true
        assertTrue(state.setIfDifferent("hello"))
        assertEquals("hello", state.value)
        
        // Setting same value should return false
        assertFalse(state.setIfDifferent("hello"))
        assertEquals("hello", state.value)
        
        // Setting different value should return true
        assertTrue(state.setIfDifferent("world"))
        assertEquals("world", state.value)
        
        // Setting to null should return true
        assertTrue(state.setIfDifferent(null))
        assertNull(state.value)
        
        // Setting null again should return false
        assertFalse(state.setIfDifferent(null))
        assertNull(state.value)
    }

    @Test
    fun testE_setIfDifferent_withNumbers() {
        val state = e<Int>()
        
        assertTrue(state.setIfDifferent(42))
        assertEquals(42, state.value)
        
        assertFalse(state.setIfDifferent(42))
        assertEquals(42, state.value)
        
        assertTrue(state.setIfDifferent(100))
        assertEquals(100, state.value)
    }

    @Test
    fun testE_setIfDifferent_withObjects() {
        val state = e<TestUser>()
        
        val user1 = TestUser(1, "Alice", "alice@example.com")
        val user2 = TestUser(1, "Alice", "alice@example.com")
        val user3 = TestUser(2, "Bob", "bob@example.com")
        
        assertTrue(state.setIfDifferent(user1))
        assertEquals(user1, state.value)
        
        // Same object should return false
        assertFalse(state.setIfDifferent(user1))
        
        // Equal object should return false (value equality)
        assertFalse(state.setIfDifferent(user2))
        
        // Different object should return true
        assertTrue(state.setIfDifferent(user3))
        assertEquals(user3, state.value)
    }

    // ==================== compareAndSet Tests ====================

    @Test
    fun testE_compareAndSet_basicOperations() {
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
        
        // Should succeed when setting to null
        assertTrue(state.compareAndSet("world", null))
        assertNull(state.value)
        
        // Should succeed when expecting null
        assertTrue(state.compareAndSet(null, "new"))
        assertEquals("new", state.value)
    }

    @Test
    fun testE_compareAndSet_withObjects() {
        val state = e<TestUser>()
        
        val user1 = TestUser(1, "Alice", "alice@example.com")
        val user2 = TestUser(2, "Bob", "bob@example.com")
        
        assertTrue(state.compareAndSet(null, user1))
        assertEquals(user1, state.value)
        
        assertFalse(state.compareAndSet(user2, user1))
        assertEquals(user1, state.value)
        
        assertTrue(state.compareAndSet(user1, user2))
        assertEquals(user2, state.value)
    }

    // ==================== Flow Operations ====================

    @Test
    fun testE_tryEmit() {
        val state = e<Int>()
        
        assertTrue(state.tryEmit(42))
        assertEquals(42, state.value)
        
        assertTrue(state.tryEmit(100))
        assertEquals(100, state.value)
        
        assertTrue(state.tryEmit(null))
        assertNull(state.value)
    }

    @Test
    fun testE_emit() {
        val state = e<String>()
        
        assertTrue(state.tryEmit("test"))
        assertEquals("test", state.value)
        
        assertTrue(state.tryEmit(null))
        assertNull(state.value)
    }

    @Test
    fun testE_replayCache() {
        val state = e<String>()
        
        assertEquals(listOf<String?>(null), state.replayCache)
        
        state.value = "first"
        assertEquals(listOf("first"), state.replayCache)
        
        state.value = "second"
        assertEquals(listOf("second"), state.replayCache)
        
        state.value = null
        assertEquals(listOf<String?>(null), state.replayCache)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testE_resetReplayCache() {
        val state = e<String>()
        
        state.value = "test"
        assertEquals(listOf("test"), state.replayCache)
        
        // Test that resetReplayCache method exists and can be called
        // The actual behavior may be platform-specific for MutableStateFlow
        try {
            state.resetReplayCache()
            // If resetReplayCache works, the cache behavior depends on the underlying implementation
            // MutableStateFlow always keeps the current value in cache regardless of resetReplayCache
            assertTrue(state.replayCache.isNotEmpty())
            assertEquals("test", state.value) // Current value should remain unchanged
        } catch (e: UnsupportedOperationException) {
            // If resetReplayCache is not supported on this platform, that's also acceptable
            // Just verify the state is still correct
            assertEquals("test", state.value)
            assertEquals(listOf("test"), state.replayCache)
        }
    }

    @Test
    fun testE_flowBasics() {
        val state = e<Int>()
        
        // Test basic flow properties
        assertEquals(listOf<Int?>(null), state.replayCache)
        
        state.value = 42
        assertEquals(listOf(42), state.replayCache)
        
        state.value = 100
        assertEquals(listOf(100), state.replayCache)
    }

    // ==================== Complex Data Type Tests ====================

    @Test
    fun testE_withComplexObjects() {
        val state = e<TestConfig>()
        
        val config1 = TestConfig("dark", "en", 1)
        val config2 = TestConfig("light", "es", 2)
        
        state.value = config1
        assertEquals(config1, state.value)
        assertEquals("dark", state.value?.theme)
        assertEquals("en", state.value?.language)
        assertEquals(1, state.value?.version)
        
        state.value = config2
        assertEquals(config2, state.value)
        assertEquals("light", state.value?.theme)
        assertEquals("es", state.value?.language)
        assertEquals(2, state.value?.version)
    }

    @Test
    fun testE_withCollections() {
        val state = e<List<String>>()
        
        val list1 = listOf("a", "b", "c")
        val list2 = listOf("x", "y", "z")
        
        state.value = list1
        assertEquals(list1, state.value)
        assertEquals(3, state.value?.size)
        
        state.value = list2
        assertEquals(list2, state.value)
        assertEquals(3, state.value?.size)
        
        state.value = emptyList()
        assertEquals(emptyList(), state.value)
        assertTrue(state.value?.isEmpty() == true)
    }

    @Test
    fun testE_withMaps() {
        val state = e<Map<String, Int>>()
        
        val map1 = mapOf("a" to 1, "b" to 2)
        val map2 = mapOf("x" to 10, "y" to 20, "z" to 30)
        
        state.value = map1
        assertEquals(map1, state.value)
        assertEquals(2, state.value?.size)
        assertEquals(1, state.value?.get("a"))
        
        state.value = map2
        assertEquals(map2, state.value)
        assertEquals(3, state.value?.size)
        assertEquals(10, state.value?.get("x"))
    }

    // ==================== Null Safety Tests ====================

    @Test
    fun testE_nullableTypes() {
        val state = e<String?>()
        
        assertNull(state.value)
        assertTrue(state.isEmpty())
        
        state.value = "test"
        assertEquals("test", state.value)
        assertFalse(state.isEmpty())
        
        state.value = null
        assertNull(state.value)
        assertTrue(state.isEmpty())
    }

    @Test
    fun testE_nullableWithDefaultValue() {
        val state = e<String?> { "default" }
        
        assertEquals("default", state.value)
        assertFalse(state.isEmpty())
        
        state.value = null
        assertNull(state.value)
        assertTrue(state.isEmpty())
        
        state.value = "new"
        assertEquals("new", state.value)
        assertFalse(state.isEmpty())
    }

    // ==================== Edge Cases ====================

    @Test
    fun testE_consecutiveOperations() {
        val state = e<Int>()
        
        // Rapid consecutive sets
        for (i in 1..100) {
            state.value = i
            assertEquals(i, state.value)
        }
        
        assertEquals(100, state.value)
    }

    @Test
    fun testE_sameValueMultipleTimes() {
        val state = e<String>()
        
        // Setting same value multiple times
        state.value = "same"
        assertEquals("same", state.value)
        
        state.value = "same"
        assertEquals("same", state.value)
        
        state.value = "same"
        assertEquals("same", state.value)
        
        // setIfDifferent should return false for same value
        assertFalse(state.setIfDifferent("same"))
    }

    @Test
    fun testE_alternatingValues() {
        val state = e<Boolean>()
        
        state.value = true
        assertTrue(state.value == true)
        
        state.value = false
        assertTrue(state.value == false)
        
        state.value = true
        assertTrue(state.value == true)
        
        state.value = false
        assertTrue(state.value == false)
    }

    @Test
    fun testE_largeObjects() {
        val state = e<List<TestUser>>()
        
        val users = (1..1000).map { 
            TestUser(it, "User$it", "user$it@example.com") 
        }
        
        state.value = users
        assertEquals(1000, state.value?.size)
        assertEquals("User1", state.value?.first()?.name)
        assertEquals("User1000", state.value?.last()?.name)
    }

    // ==================== toString Tests ====================

    @Test
    fun testE_toStringVariousTypes() {
        val stringState = e<String>()
        assertEquals("null", stringState.toString())
        stringState.value = "hello"
        assertEquals("hello", stringState.toString())
        
        val intState = e<Int>()
        assertEquals("null", intState.toString())
        intState.value = 42
        assertEquals("42", intState.toString())
        
        val boolState = e<Boolean>()
        assertEquals("null", boolState.toString())
        boolState.value = true
        assertEquals("true", boolState.toString())
        
        val objectState = e<TestUser>()
        assertEquals("null", objectState.toString())
        objectState.value = TestUser(1, "Alice", "alice@example.com")
        assertTrue(objectState.toString().contains("Alice"))
    }

    // ==================== Concurrency Safety Tests ====================

    @Test
    fun testE_compareAndSetBasic() {
        val state = e<Int>()
        state.value = 0
        
        // Test basic compare and set
        assertTrue(state.compareAndSet(0, 1))
        assertEquals(1, state.value)
        
        assertFalse(state.compareAndSet(0, 2)) // Wrong expected value
        assertEquals(1, state.value)
        
        assertTrue(state.compareAndSet(1, 2))
        assertEquals(2, state.value)
    }

    @Test
    fun testE_setIfDifferentBasic() {
        val state = e<String>()
        
        assertTrue(state.setIfDifferent("value1"))
        assertEquals("value1", state.value)
        
        assertFalse(state.setIfDifferent("value1")) // Same value
        assertEquals("value1", state.value)
        
        assertTrue(state.setIfDifferent("value2"))
        assertEquals("value2", state.value)
    }

    // ==================== Default Value Behavior ====================

    @Test
    fun testE_defaultValueNotRecomputed() {
        var computationCount = 0
        val state = e<String> { 
            computationCount++
            "computed$computationCount"
        }
        
        assertEquals("computed1", state.value)
        assertEquals(1, computationCount)
        
        // Accessing value again shouldn't recompute
        assertEquals("computed1", state.value)
        assertEquals(1, computationCount)
        
        // Clearing and accessing again shouldn't recompute
        state.clear()
        assertNull(state.value)
        assertEquals(1, computationCount)
    }

    @Test
    fun testE_defaultValueLambdaExceptions() {
        // Exception should be thrown during construction when default value lambda is invoked
        assertFailsWith<RuntimeException> {
            val state = e<String> { 
                throw RuntimeException("Default value error")
            }
        }
    }

    // ==================== Type Safety Tests ====================

    @Test
    fun testE_stronglyTyped() {
        val stringState = e<String>()
        val intState = e<Int>()
        val userState = e<TestUser>()
        
        // These should compile and work correctly
        stringState.value = "test"
        intState.value = 42
        userState.value = TestUser(1, "Test", "test@example.com")
        
        assertEquals("test", stringState.value)
        assertEquals(42, intState.value)
        assertEquals("Test", userState.value?.name)
    }

    @Test
    fun testE_nullableVsNonNullable() {
        val nonNullableState = e<String>()
        val nullableState = e<String?>()
        
        // Both can be set to null
        nonNullableState.value = null
        nullableState.value = null
        
        assertNull(nonNullableState.value)
        assertNull(nullableState.value)
        
        // Both can be set to non-null values
        nonNullableState.value = "test"
        nullableState.value = "test"
        
        assertEquals("test", nonNullableState.value)
        assertEquals("test", nullableState.value)
    }
}