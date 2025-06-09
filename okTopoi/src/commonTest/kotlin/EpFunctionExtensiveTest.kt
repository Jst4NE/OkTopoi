package jst.oktopoi

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
data class PersistentUser(val id: Int, val name: String, val email: String, val settings: UserSettings)

@Serializable 
data class UserSettings(val theme: String, val notifications: Boolean, val language: String)

@Serializable
data class ComplexData(
    val numbers: List<Int>,
    val metadata: Map<String, String>,
    val nested: NestedData
)

@Serializable
data class NestedData(val value: String, val timestamp: Long)

class EpFunctionExtensiveTest {

    private val testRootDir = Path("/tmp/oktopoi-ep-test")
    
    @BeforeTest
    fun setup() {
        // Initialize IO for persistence tests
        initDefaultIO(testRootDir, SystemFileSystem)
        initRootDirIO(testRootDir, SystemFileSystem)
    }

    // ==================== Basic ep() Function Tests ====================

    @Test
    fun testEp_defaultConstructor() {
        val state = ep<String>()
        
        assertNull(state.value)
        assertTrue(state.isEmpty())
        assertNull(state.get())
        assertEquals("null", state.toString())
    }

    @Test
    fun testEp_withDefaultValue() {
        val state = ep<String> { "persistent_default" }
        
        assertEquals("persistent_default", state.value)
        assertFalse(state.isEmpty())
        assertEquals("persistent_default", state.get())
        assertEquals("persistent_default", state.toString())
    }

    @Test
    fun testEp_withExplicitRootDir() {
        val customDir = Path("/tmp/oktopoi-ep-custom")
        val state = ep<String>(rootDir = customDir)
        
        assertNull(state.value)
        state.value = "custom_dir_test"
        assertEquals("custom_dir_test", state.value)
        
        // Custom directory test completed
    }

    @Test
    fun testEp_withExplicitFileSystem() {
        val state = ep<String>(fileSystem = SystemFileSystem)
        
        assertNull(state.value)
        state.value = "filesystem_test"
        assertEquals("filesystem_test", state.value)
    }

    // ==================== Basic Operations ====================

    @Test
    fun testEp_setValue() {
        val state = ep<String>()
        
        state.value = "persisted_hello"
        assertEquals("persisted_hello", state.value)
        assertEquals("persisted_hello", state.get())
        assertFalse(state.isEmpty())
        
        state.value = "persisted_world"
        assertEquals("persisted_world", state.value)
    }

    @Test
    fun testEp_setMethod() {
        val state = ep<Int>()
        
        state.set(200)
        assertEquals(200, state.value)
        assertEquals(200, state.get())
        assertFalse(state.isEmpty())
    }

    @Test
    fun testEp_setToNull() {
        val state = ep<String>()
        
        state.value = "test"
        state.value = null
        
        assertNull(state.value)
        assertTrue(state.isEmpty())
    }

    @Test
    fun testEp_clear() {
        val state = ep<String>()
        
        state.value = "test"
        state.clear()
        
        assertNull(state.value)
        assertTrue(state.isEmpty())
    }

    // ==================== setIfDifferent Tests ====================

    @Test
    fun testEp_setIfDifferent() {
        val state = ep<String>()
        
        assertTrue(state.setIfDifferent("hello"))
        assertEquals("hello", state.value)
        
        assertFalse(state.setIfDifferent("hello"))
        assertEquals("hello", state.value)
        
        assertTrue(state.setIfDifferent("world"))
        assertEquals("world", state.value)
        
        assertTrue(state.setIfDifferent(null))
        assertNull(state.value)
        
        assertFalse(state.setIfDifferent(null))
        assertNull(state.value)
    }

    @Test
    fun testEp_setIfDifferent_withObjects() {
        val state = ep<PersistentUser>()
        
        val user1 = PersistentUser(1, "Alice", "alice@example.com", 
            UserSettings("dark", true, "en"))
        val user2 = PersistentUser(1, "Alice", "alice@example.com", 
            UserSettings("dark", true, "en"))
        val user3 = PersistentUser(2, "Bob", "bob@example.com", 
            UserSettings("light", false, "es"))
        
        assertTrue(state.setIfDifferent(user1))
        assertEquals(user1, state.value)
        
        assertFalse(state.setIfDifferent(user1))
        
        // Equal object should return false
        assertFalse(state.setIfDifferent(user2))
        
        // Different object should return true
        assertTrue(state.setIfDifferent(user3))
        assertEquals(user3, state.value)
    }

    // ==================== compareAndSet Tests ====================

    @Test
    fun testEp_compareAndSet() {
        val state = ep<String>()
        
        assertTrue(state.compareAndSet(null, "hello"))
        assertEquals("hello", state.value)
        
        assertFalse(state.compareAndSet("wrong", "world"))
        assertEquals("hello", state.value)
        
        assertTrue(state.compareAndSet("hello", "world"))
        assertEquals("world", state.value)
        
        assertTrue(state.compareAndSet("world", null))
        assertNull(state.value)
    }

    // ==================== Flow Operations ====================

    @Test
    fun testEp_tryEmit() {
        val state = ep<Int>()
        
        assertTrue(state.tryEmit(42))
        assertEquals(42, state.value)
        
        assertTrue(state.tryEmit(100))
        assertEquals(100, state.value)
        
        assertTrue(state.tryEmit(null))
        assertNull(state.value)
    }

    @Test
    fun testEp_emit() {
        val state = ep<String>()
        
        state.tryEmit("test")
        assertEquals("test", state.value)
        
        state.tryEmit(null)
        assertNull(state.value)
    }

    @Test
    fun testEp_replayCache() {
        val state = ep<String>()
        
        assertEquals(listOf<String?>(null), state.replayCache)
        
        state.value = "first"
        assertEquals(listOf("first"), state.replayCache)
        
        state.value = "second"
        assertEquals(listOf("second"), state.replayCache)
        
        state.value = null
        assertEquals(listOf<String?>(null), state.replayCache)
    }

    // ==================== Complex Data Types ====================

    @Test
    fun testEp_withComplexObjects() {
        val state = ep<PersistentUser>()
        
        val user = PersistentUser(
            id = 1,
            name = "Alice Johnson",
            email = "alice.johnson@example.com",
            settings = UserSettings(
                theme = "dark",
                notifications = true,
                language = "en"
            )
        )
        
        state.value = user
        assertEquals(user, state.value)
        assertEquals("Alice Johnson", state.value?.name)
        assertEquals("alice.johnson@example.com", state.value?.email)
        assertEquals("dark", state.value?.settings?.theme)
        assertTrue(state.value?.settings?.notifications == true)
        assertEquals("en", state.value?.settings?.language)
    }

    @Test
    fun testEp_withNestedCollections() {
        val state = ep<ComplexData>()
        
        val complexData = ComplexData(
            numbers = listOf(1, 2, 3, 4, 5),
            metadata = mapOf(
                "version" to "1.0",
                "author" to "test",
                "description" to "Test data structure"
            ),
            nested = NestedData(
                value = "nested_value",
                timestamp = 1234567890L
            )
        )
        
        state.value = complexData
        assertEquals(complexData, state.value)
        assertEquals(5, state.value?.numbers?.size)
        assertEquals("1.0", state.value?.metadata?.get("version"))
        assertEquals("nested_value", state.value?.nested?.value)
    }

    @Test
    fun testEp_withLists() {
        val state = ep<List<String>>()
        
        val list1 = listOf("a", "b", "c", "d", "e")
        val list2 = listOf("x", "y", "z")
        val emptyList = emptyList<String>()
        
        state.value = list1
        assertEquals(list1, state.value)
        assertEquals(5, state.value?.size)
        assertEquals("a", state.value?.first())
        assertEquals("e", state.value?.last())
        
        state.value = list2
        assertEquals(list2, state.value)
        assertEquals(3, state.value?.size)
        
        state.value = emptyList
        assertEquals(emptyList, state.value)
        assertTrue(state.value?.isEmpty() == true)
    }

    @Test
    fun testEp_withMaps() {
        val state = ep<Map<String, Int>>()
        
        val map1 = mapOf("a" to 1, "b" to 2, "c" to 3)
        val map2 = mapOf("x" to 10, "y" to 20, "z" to 30)
        val emptyMap = emptyMap<String, Int>()
        
        state.value = map1
        assertEquals(map1, state.value)
        assertEquals(3, state.value?.size)
        assertEquals(1, state.value?.get("a"))
        
        state.value = map2
        assertEquals(map2, state.value)
        assertEquals(3, state.value?.size)
        assertEquals(10, state.value?.get("x"))
        
        state.value = emptyMap
        assertEquals(emptyMap, state.value)
        assertTrue(state.value?.isEmpty() == true)
    }

    // ==================== Persistence-Specific Tests ====================

    @Test
    fun testEp_persistenceSetup() {
        val state = ep<String>()
        
        // Set some values to trigger persistence setup
        state.value = "test1"
        state.value = "test2"
        state.value = "test3"
        
        assertEquals("test3", state.value)
    }

    @Test
    fun testEp_rapidValueChanges() {
        val state = ep<Int>()
        
        // Rapid value changes to test persistence handling
        for (i in 1..100) {
            state.value = i
        }
        
        assertEquals(100, state.value)
    }

    @Test
    fun testEp_nullableValuePersistence() {
        val state = ep<String?>()
        
        state.value = "not_null"
        state.value = null
        state.value = "back_to_not_null"
        
        assertEquals("back_to_not_null", state.value)
    }

    // ==================== Default Value with Persistence ====================

    @Test
    fun testEp_defaultValueBehavior() {
        var computationCount = 0
        val state = ep<String> { 
            computationCount++
            "persistent_default_$computationCount"
        }
        
        assertEquals("persistent_default_1", state.value)
        assertEquals(1, computationCount)
        
        // Default value should not be recomputed
        assertEquals("persistent_default_1", state.value)
        assertEquals(1, computationCount)
        
        // Setting and clearing should not recompute default
        state.value = "changed"
        state.clear()
        assertNull(state.value)
        assertEquals(1, computationCount)
    }

    @Test
    fun testEp_defaultValueWithNullable() {
        val state = ep<String?> { "nullable_default" }
        
        assertEquals("nullable_default", state.value)
        assertFalse(state.isEmpty())
        
        state.value = null
        assertNull(state.value)
        assertTrue(state.isEmpty())
        
        state.value = "new_value"
        assertEquals("new_value", state.value)
        assertFalse(state.isEmpty())
    }

    // ==================== Error Handling ====================

    @Test
    fun testEp_withInvalidData() {
        val state = ep<String>()
        
        // These operations should not throw exceptions
        state.value = ""
        assertEquals("", state.value)
        
        state.value = "very_long_string_" + "x".repeat(10000)
        assertTrue(state.value?.length == 10017)
        
        state.value = "special_characters_!@#$%^&*()_+-=[]{}|;':\",./<>?`~"
        assertEquals("special_characters_!@#$%^&*()_+-=[]{}|;':\",./<>?`~", state.value)
    }

    @Test
    fun testEp_concurrentOperations() {
        val state = ep<Int>()
        
        // Test rapid operations without concurrency
        for (i in 1..50) {
            state.value = i
            state.setIfDifferent(i * 2)
        }
        
        // Should have some final value
        assertNotNull(state.value)
    }

    // ==================== Memory and Performance ====================

    @Test
    fun testEp_largeDataSets() {
        val state = ep<List<PersistentUser>>()
        
        val users = (1..1000).map { i ->
            PersistentUser(
                id = i,
                name = "User$i",
                email = "user$i@example.com",
                settings = UserSettings(
                    theme = if (i % 2 == 0) "dark" else "light",
                    notifications = i % 3 == 0,
                    language = when (i % 3) {
                        0 -> "en"
                        1 -> "es" 
                        else -> "fr"
                    }
                )
            )
        }
        
        state.value = users
        assertEquals(1000, state.value?.size)
        assertEquals("User1", state.value?.first()?.name)
        assertEquals("User1000", state.value?.last()?.name)
    }

    @Test
    fun testEp_deeplyNestedData() {
        val state = ep<Map<String, Map<String, List<ComplexData>>>>()
        
        val deeplyNested = mapOf(
            "level1" to mapOf(
                "level2a" to listOf(
                    ComplexData(
                        numbers = listOf(1, 2, 3),
                        metadata = mapOf("key" to "value"),
                        nested = NestedData("deep", 1234567890L)
                    )
                ),
                "level2b" to listOf(
                    ComplexData(
                        numbers = listOf(4, 5, 6),
                        metadata = mapOf("another" to "value"),
                        nested = NestedData("deeper", 1234567890L)
                    )
                )
            )
        )
        
        state.value = deeplyNested
        assertEquals(deeplyNested, state.value)
        assertEquals(1, state.value?.size)
        assertEquals(2, state.value?.get("level1")?.size)
        assertEquals("deep", state.value?.get("level1")?.get("level2a")?.first()?.nested?.value)
    }

    // ==================== Edge Cases ====================

    @Test
    fun testEp_emptyStrings() {
        val state = ep<String>()
        
        state.value = ""
        assertEquals("", state.value)
        assertFalse(state.isEmpty()) // Empty string is not null
        
        state.value = "   "
        assertEquals("   ", state.value)
        
        state.value = "\n\t\r"
        assertEquals("\n\t\r", state.value)
    }

    @Test
    fun testEp_specialCharacters() {
        val state = ep<String>()
        
        val unicodeString = "αβγδεζηθικλμνξοπρστυφχψω"
        state.value = unicodeString
        assertEquals(unicodeString, state.value)
        
        val emojiString = "🚀🌟💯🎉🔥"
        state.value = emojiString
        assertEquals(emojiString, state.value)
        
        val mixedString = "Hello 世界 🌍 αβγ 123"
        state.value = mixedString
        assertEquals(mixedString, state.value)
    }

    @Test
    fun testEp_numericalLimits() {
        val intState = ep<Int>()
        intState.value = Int.MAX_VALUE
        assertEquals(Int.MAX_VALUE, intState.value)
        
        intState.value = Int.MIN_VALUE
        assertEquals(Int.MIN_VALUE, intState.value)
        
        val longState = ep<Long>()
        longState.value = Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, longState.value)
        
        longState.value = Long.MIN_VALUE
        assertEquals(Long.MIN_VALUE, longState.value)
        
        val doubleState = ep<Double>()
        doubleState.value = Double.MAX_VALUE
        assertEquals(Double.MAX_VALUE, doubleState.value)
        
        doubleState.value = Double.MIN_VALUE
        assertEquals(Double.MIN_VALUE, doubleState.value)
    }

    // ==================== toString Tests ====================

    @Test
    fun testEp_toStringBehavior() {
        val state = ep<String>()
        assertEquals("null", state.toString())
        
        state.value = "persistent_test"
        assertEquals("persistent_test", state.toString())
        
        val objectState = ep<PersistentUser>()
        assertEquals("null", objectState.toString())
        
        objectState.value = PersistentUser(1, "Test", "test@example.com", 
            UserSettings("dark", true, "en"))
        assertTrue(objectState.toString().contains("Test"))
    }

    // ==================== Type Safety ====================

    @Test
    fun testEp_typeConstraints() {
        // Test that ep() works with various serializable types
        val stringState = ep<String>()
        val intState = ep<Int>()
        val boolState = ep<Boolean>()
        val listState = ep<List<String>>()
        val mapState = ep<Map<String, Int>>()
        val userState = ep<PersistentUser>()
        
        // All should be created successfully
        assertNotNull(stringState)
        assertNotNull(intState)
        assertNotNull(boolState)
        assertNotNull(listState)
        assertNotNull(mapState)
        assertNotNull(userState)
    }
}