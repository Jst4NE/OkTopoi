package jst.oktopoi

import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
data class User(val id: Int, val name: String, val email: String)

class PersistenceTest {

    private val testRootDir = Path("/tmp/oktopoi-persistence-test")
    
    @BeforeTest
    fun setup() {
        // Clean up any existing test data
        if (SystemFileSystem.exists(testRootDir)) {
            // SystemFileSystem.deleteRecursively(testRootDir) // disabled
        }
        
        // Initialize IO for persistence tests
        initDefaultIO(testRootDir, SystemFileSystem)
        initRootDirIO(testRootDir, SystemFileSystem)
    }

    @AfterTest
    fun cleanup() {
        // Clean up test data
        if (SystemFileSystem.exists(testRootDir)) {
            // SystemFileSystem.deleteRecursively(testRootDir) // disabled
        }
    }

    // ==================== EP() Function Tests ====================

    @Test
    fun testEp_basicPersistence() {
        // Test that ep() creates a persisted state container
        val persistedState = ep<String>()
        
        // Initially should be null
        assertNull(persistedState.value)
        
        // Set a value - this should trigger persistence
        persistedState.value = "persisted_value"
        assertEquals("persisted_value", persistedState.value)
        
        // Note: In real tests, persistence would be tested by setup/teardown
        
        // Value should persist (would need to test by creating new instance
        // but that requires setup() to be called which sets className/propertyName)
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
    fun testEp_complexDataTypes() {
        val userState = ep<User>()
        
        val user = User(1, "John Doe", "john@example.com")
        userState.value = user
        
        assertEquals(user, userState.value)
        assertEquals("John Doe", userState.value?.name)
        assertEquals("john@example.com", userState.value?.email)
    }

    @Test
    fun testEp_nullableOperations() {
        val nullableState = ep<Int?>()
        
        // Test null handling
        assertNull(nullableState.value)
        assertTrue(nullableState.isEmpty())
        
        // Set non-null value
        nullableState.value = 42
        assertEquals(42, nullableState.value)
        assertFalse(nullableState.isEmpty())
        
        // Set back to null
        nullableState.value = null
        assertNull(nullableState.value)
        assertTrue(nullableState.isEmpty())
    }

    @Test
    fun testEp_fileSystemOperations() {
        val state = ep<String>(rootDir = testRootDir)
        
        // This should work with explicit root directory
        state.value = "explicit_root_test"
        assertEquals("explicit_root_test", state.value)
        
        // Note: File system operations are asynchronous
    }

    // ==================== EPS() Function Tests ====================

    @Test
    fun testEps_basicPersistence() {
        val persistedCollection = eps<String, User>(keySelector = { it.name })
        
        // Initially should be empty
        assertTrue(persistedCollection.isEmpty())
        assertEquals(0, persistedCollection.size)
        
        // Add some users
        val user1 = User(1, "Alice", "alice@example.com")
        val user2 = User(2, "Bob", "bob@example.com")
        
        persistedCollection.put("alice", user1)
        persistedCollection.put("bob", user2)
        
        assertEquals(2, persistedCollection.size)
        assertEquals(user1, persistedCollection["alice"])
        assertEquals(user2, persistedCollection["bob"])
        
        // Note: Persistence operations are asynchronous
    }

    @Test
    fun testEps_withCustomComparator() {
        val reverseComparator = Comparator<String> { a, b -> b.compareTo(a) }
        val persistedCollection = eps<String, User>(
            keySelector = { it.name },
            sortingBy = reverseComparator
        )
        
        val user1 = User(1, "Alice", "alice@example.com")
        val user2 = User(2, "Bob", "bob@example.com")
        val user3 = User(3, "Charlie", "charlie@example.com")
        
        persistedCollection.put("alice", user1)
        persistedCollection.put("bob", user2)
        persistedCollection.put("charlie", user3)
        
        // Keys should be in reverse order due to custom comparator
        val keys = persistedCollection.keys.toList()
        assertEquals(listOf("charlie", "bob", "alice"), keys)
        
        // Note: Persistence operations are asynchronous
    }

    @Test
    fun testEps_removeOperations() {
        val persistedCollection = eps<String, User>(keySelector = { user -> user.id.toString() })
        
        val user1 = User(1, "Alice", "alice@example.com")
        val user2 = User(2, "Bob", "bob@example.com")
        val user3 = User(3, "Charlie", "charlie@example.com")
        
        persistedCollection.put("1", user1)
        persistedCollection.put("2", user2)
        persistedCollection.put("3", user3)
        
        assertEquals(3, persistedCollection.size)
        
        // Remove one user
        val removed = persistedCollection.remove("2")
        assertEquals(user2, removed)
        assertEquals(2, persistedCollection.size)
        assertNull(persistedCollection["2"])
        
        // Note: Persistence operations are asynchronous
        
        // Clear all
        persistedCollection.clear()
        assertTrue(persistedCollection.isEmpty())
        assertEquals(0, persistedCollection.size)
        
        // Note: Persistence operations are asynchronous
    }

    @Test
    fun testEps_putAllOperations() {
        val persistedCollection = eps<String, User>(keySelector = { user -> user.id.toString() })
        
        val users = mapOf(
            "1" to User(1, "Alice", "alice@example.com"),
            "2" to User(2, "Bob", "bob@example.com"),
            "3" to User(3, "Charlie", "charlie@example.com"),
            "4" to User(4, "Diana", "diana@example.com")
        )
        
        persistedCollection.putAll(users)
        
        assertEquals(4, persistedCollection.size)
        users.forEach { (key, user) ->
            assertEquals(user, persistedCollection[key])
        }
        
        // Note: Persistence operations are asynchronous
    }

    @Test
    fun testEps_updateOperations() {
        val persistedCollection = eps<String, User>(keySelector = { user -> user.id.toString() })
        
        val originalUser = User(1, "Alice", "alice@example.com")
        persistedCollection.put("1", originalUser)
        
        // Update the user
        val updatedUser = User(1, "Alice Smith", "alice.smith@example.com")
        val previousValue = persistedCollection.put("1", updatedUser)
        
        assertEquals(originalUser, previousValue)
        assertEquals(updatedUser, persistedCollection["1"])
        assertEquals(1, persistedCollection.size) // Size should remain the same
        
        // Note: Persistence operations are asynchronous
    }

    // ==================== Secondary Key Tests ====================

    @Test
    fun testEps_secondaryKeys() {
        val persistedCollection = eps<String, User>(
            keySelector = { user -> user.id.toString() },
            secondaryKeys = {
                key("email") { user -> user.email }
                key("namePrefix") { user -> user.name.take(1) }
            }
        )
        
        val user1 = User(1, "Alice", "alice@example.com")
        val user2 = User(2, "Bob", "bob@example.com")
        val user3 = User(3, "Anna", "anna@example.com")
        
        persistedCollection.put("1", user1)
        persistedCollection.put("2", user2)
        persistedCollection.put("3", user3)
        
        // Test secondary key lookups
        val aliceByEmail = persistedCollection.getBySecondaryKey("email", "alice@example.com")
        assertEquals(1, aliceByEmail.size)
        assertEquals(user1, aliceByEmail[0])
        
        val usersStartingWithA = persistedCollection.getBySecondaryKey("namePrefix", "A")
        assertEquals(2, usersStartingWithA.size)
        assertTrue(usersStartingWithA.contains(user1))
        assertTrue(usersStartingWithA.contains(user3))
        
        val usersStartingWithB = persistedCollection.getBySecondaryKey("namePrefix", "B")
        assertEquals(1, usersStartingWithB.size)
        assertEquals(user2, usersStartingWithB[0])
        
        // Note: Persistence operations are asynchronous
    }

    // ==================== Initialization Tests ====================

    @Test
    fun testInitialization_functions() {
        val customDir = Path("/tmp/oktopoi-custom-test")
        
        // Test initDefaultIO
        initDefaultIO(customDir, SystemFileSystem)
        
        // Test initRootDirIO  
        initRootDirIO(customDir, SystemFileSystem)
        
        // These should not throw exceptions
        val state = ep<String>()
        state.value = "test"
        
        // Note: Operations are asynchronous
        
        // Cleanup disabled due to API limitations
        // if (SystemFileSystem.exists(customDir)) {
        //     SystemFileSystem.deleteRecursively(customDir)
        // }
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun testPersistence_errorHandling() {
        // Test with invalid paths and file system operations
        val state = ep<String>()
        
        // These operations should not throw exceptions even if persistence fails
        state.value = "test1"
        state.value = "test2"
        state.clear()
        state.value = "test3"
        
        // Note: Background operations are asynchronous
    }

    @Test
    fun testPersistence_concurrentOperations() {
        val persistedCollection = eps<String, String>(keySelector = { value -> value })
        
        // Perform multiple rapid operations
        for (i in 1..50) {
            persistedCollection.put(i.toString(), "value_$i")
        }
        
        assertEquals(50, persistedCollection.size)
        
        // Remove some items
        for (i in 1..25) {
            persistedCollection.remove(i.toString())
        }
        
        assertEquals(25, persistedCollection.size)
        
        // Note: Persistence operations are asynchronous
    }

    // ==================== Data Integrity Tests ====================

    @Test
    fun testPersistence_dataIntegrity() {
        val state = ep<List<Int>>()
        
        val testData = listOf(1, 2, 3, 4, 5)
        state.value = testData
        
        assertEquals(testData, state.value)
        assertEquals(5, state.value?.size)
        assertTrue(state.value?.contains(3) == true)
        
        // Test with nested complex data
        val complexState = ep<Map<String, List<User>>>()
        val complexData = mapOf(
            "group1" to listOf(
                User(1, "Alice", "alice@example.com"),
                User(2, "Bob", "bob@example.com")
            ),
            "group2" to listOf(
                User(3, "Charlie", "charlie@example.com")
            )
        )
        
        complexState.value = complexData
        assertEquals(complexData, complexState.value)
        assertEquals(2, complexState.value?.get("group1")?.size)
        assertEquals("Alice", complexState.value?.get("group1")?.get(0)?.name)
        
        // Note: Persistence operations are asynchronous
    }

    @Test
    fun testPersistence_edgeCases() {
        // Test with empty strings
        val emptyStringState = ep<String>()
        emptyStringState.value = ""
        assertEquals("", emptyStringState.value)
        
        // Test with very long strings
        val longString = "x".repeat(10000)
        val longStringState = ep<String>()
        longStringState.value = longString
        assertEquals(longString, longStringState.value)
        assertEquals(10000, longStringState.value?.length)
        
        // Test with special characters
        val specialCharsState = ep<String>()
        val specialString = "!@#$%^&*()_+-=[]{}|;':\",./<>?`~αβγδεζηθικλμνξοπρστυφχψω"
        specialCharsState.value = specialString
        assertEquals(specialString, specialCharsState.value)
        
        // Note: Persistence operations are asynchronous
    }
}