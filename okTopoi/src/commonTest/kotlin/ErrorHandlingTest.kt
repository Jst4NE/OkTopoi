package jst.oktopoi

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.io.buffered
import kotlinx.io.files.*
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class ErrorTestUser(val id: Int, val name: String, val email: String)

// Non-serializable class for testing serialization errors
data class NonSerializableData(val data: String, val callback: () -> Unit)

class ErrorHandlingTest {

    private val testRootDir = Path("/tmp/oktopoi-error-test")
    private val corruptTestDir = Path("/tmp/oktopoi-corrupt-test")
    
    // Helper function to replace assertDoesNotThrow
    private fun assertDoesNotThrow(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail("Expected no exception but got: ${e.message}")
        }
    }
    
    // Helper function for suspend blocks
    private suspend fun assertDoesNotThrowSuspend(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail("Expected no exception but got: ${e.message}")
        }
    }
    
    @BeforeTest
    fun setup() {
        // Clean up any existing test data
        if (SystemFileSystem.exists(testRootDir)) {
            // SystemFileSystem.deleteRecursively(testRootDir) // disabled
        }
        if (SystemFileSystem.exists(corruptTestDir)) {
            // SystemFileSystem.deleteRecursively(corruptTestDir) // disabled
        }
        
        // Initialize IO for error tests
        initDefaultIO(testRootDir, SystemFileSystem)
        initRootDirIO(testRootDir, SystemFileSystem)
    }

    @AfterTest
    fun cleanup() {
        // Clean up test data
        if (SystemFileSystem.exists(testRootDir)) {
            // SystemFileSystem.deleteRecursively(testRootDir) // disabled
        }
        if (SystemFileSystem.exists(corruptTestDir)) {
            // SystemFileSystem.deleteRecursively(corruptTestDir) // disabled
        }
    }

    // ==================== File System Permission Error Tests ====================

    @Test
    fun testEp_handlesNonExistentRootDirectory() = runTest {
        val nonExistentDir = Path("/non/existent/directory/path")
        val persistedState = ep<String>(rootDir = nonExistentDir)
        
        persistedState.callingClassName = "ErrorTest"
        persistedState.propertyName = "nonExistentDirTest"
        
        // Should not throw exception during setup
        assertDoesNotThrow {
            persistedState.setup()
        }
        
        // Should still allow normal operations
        persistedState.value = "test_value"
        assertEquals("test_value", persistedState.value)
        
        // Background persistence may fail, but foreground operations should work
        delay(100.milliseconds)
    }

    @Test
    fun testEps_handlesNonExistentRootDirectory() = runTest {
        val nonExistentDir = Path("/non/existent/directory/path")
        val persistedCollection = eps<String, ErrorTestUser>(
            rootDir = nonExistentDir,
            keySelector = { it.name }
        )
        
        persistedCollection.callingClassName = "ErrorTest"
        persistedCollection.propertyName = "nonExistentDirCollection"
        
        // Should not throw exception during setup
        assertDoesNotThrow {
            persistedCollection.setup()
        }
        
        val user = ErrorTestUser(1, "Alice", "alice@test.com")
        
        // Should still allow normal operations
        persistedCollection.put("alice", user)
        assertEquals(user, persistedCollection["alice"])
        assertEquals(1, persistedCollection.size)
        
        delay(100.milliseconds)
    }

    @Test
    fun testEp_handlesInvalidPath() = runTest {
        // Test with various invalid path characters
        val invalidPaths = listOf(
            Path("/tmp/oktopoi-test\u0000invalid"), // null character
            Path(""), // empty path
        )
        
        for (invalidPath in invalidPaths) {
            val persistedState = ep<String>(rootDir = invalidPath)
            
            persistedState.callingClassName = "ErrorTest"
            persistedState.propertyName = "invalidPathTest"
            
            // Should handle gracefully
            assertDoesNotThrow {
                persistedState.setup()
                persistedState.value = "test"
            }
        }
    }

    // ==================== Serialization Error Tests ====================

    @Test
    fun testEp_handlesComplexSerializationScenarios() = runTest {
        // Test with very large data that might cause memory issues
        val persistedState = ep<List<String>>()
        
        persistedState.callingClassName = "ErrorTest"
        persistedState.propertyName = "largeDataTest"
        persistedState.setup()
        
        // Create very large list
        val largeList = (1..10000).map { "item_$it with some additional text to increase size" }
        
        // Should handle large data
        assertDoesNotThrow {
            persistedState.value = largeList
        }
        
        assertEquals(largeList.size, persistedState.value?.size)
        
        delay(200.milliseconds)
    }

    @Test
    fun testEp_handlesNullSerialization() = runTest {
        val persistedState = ep<String?>()
        
        persistedState.callingClassName = "ErrorTest"
        persistedState.propertyName = "nullTest"
        persistedState.setup()
        
        // Should handle null values correctly
        assertDoesNotThrow {
            persistedState.value = null
            persistedState.value = "not null"
            persistedState.value = null
        }
        
        assertNull(persistedState.value)
        delay(100.milliseconds)
    }

    @Test
    fun testEps_handlesEmptyCollectionSerialization() = runTest {
        val persistedCollection = eps<String, ErrorTestUser>(keySelector = { it.name })
        
        persistedCollection.callingClassName = "ErrorTest"
        persistedCollection.propertyName = "emptyCollectionTest"
        persistedCollection.setup()
        
        // Should handle empty collection
        assertTrue(persistedCollection.isEmpty())
        
        // Add and remove all items
        val user = ErrorTestUser(1, "Alice", "alice@test.com")
        persistedCollection.put("alice", user)
        persistedCollection.clear()
        
        assertTrue(persistedCollection.isEmpty())
        delay(100.milliseconds)
    }

    @Test
    fun testEp_handlesRecursiveDataStructures() = runTest {
        // Test with deeply nested structures that might cause stack overflow
        @Serializable
        data class NestedData(val level: Int, val child: NestedData? = null)
        
        val persistedState = ep<NestedData>()
        
        persistedState.callingClassName = "ErrorTest"
        persistedState.propertyName = "nestedDataTest"
        persistedState.setup()
        
        // Create deeply nested structure
        var nested: NestedData? = null
        for (i in 100 downTo 1) {
            nested = NestedData(i, nested)
        }
        
        // Should handle deep nesting
        assertDoesNotThrow {
            persistedState.value = nested
        }
        
        assertEquals(1, persistedState.value?.level)
        delay(100.milliseconds)
    }

    // ==================== Corrupted File Handling Tests ====================

    @Test
    fun testEp_handlesCorruptedPersistenceFile() = runTest {
        val testDir = Path("/tmp/oktopoi-corrupt-test")
        SystemFileSystem.createDirectories(testDir)
        
        val persistedState = ep<String>(rootDir = testDir)
        
        persistedState.callingClassName = "CorruptTest"
        persistedState.propertyName = "corruptProperty"
        
        // Create a corrupted file
        val classDir = Path(testDir, "CorruptTest")
        SystemFileSystem.createDirectories(classDir)
        val corruptFile = Path(classDir, "corruptProperty")
        
        // Write invalid JSON to the file
        SystemFileSystem.sink(corruptFile).buffered().use { sink ->
            sink.writeString("{ invalid json content }")
        }
        
        // Setup should handle corrupted file gracefully
        assertDoesNotThrow {
            persistedState.setup()
        }
        
        // Should use default value (null) when file is corrupted
        assertNull(persistedState.value)
        
        // Should still allow setting new values
        persistedState.value = "new_value"
        assertEquals("new_value", persistedState.value)
        
        delay(100.milliseconds)
    }

    @Test
    fun testEps_handlesCorruptedPersistenceFiles() = runTest {
        val testDir = Path("/tmp/oktopoi-corrupt-collection-test")
        SystemFileSystem.createDirectories(testDir)
        
        val persistedCollection = eps<String, ErrorTestUser>(
            rootDir = testDir,
            keySelector = { it.name }
        )
        
        persistedCollection.callingClassName = "CorruptTest"
        persistedCollection.propertyName = "corruptCollection"
        
        // Create directory with some corrupted files
        val collectionDir = Path(testDir, "CorruptTest", "corruptCollection")
        SystemFileSystem.createDirectories(collectionDir)
        
        // Create some valid and invalid files
        val validFile = Path(collectionDir, "\"alice\"")
        SystemFileSystem.sink(validFile).buffered().use { sink ->
            sink.writeString(Json.encodeToString(ErrorTestUser.serializer(), ErrorTestUser(1, "Alice", "alice@test.com")))
        }
        
        val corruptFile = Path(collectionDir, "\"bob\"")
        SystemFileSystem.sink(corruptFile).buffered().use { sink ->
            sink.writeString("{ corrupted json }")
        }
        
        val emptyFile = Path(collectionDir, "\"charlie\"")
        SystemFileSystem.sink(emptyFile).buffered().use { sink ->
            sink.writeString("")
        }
        
        // Setup should handle mixed valid/corrupted files gracefully
        assertDoesNotThrow {
            persistedCollection.setup()
        }
        
        delay(100.milliseconds)
        
        // Should load only valid entries
        assertTrue(persistedCollection.containsKey("alice"))
        assertFalse(persistedCollection.containsKey("bob"))
        assertFalse(persistedCollection.containsKey("charlie"))
        
        // Should still allow normal operations
        val newUser = ErrorTestUser(2, "Diana", "diana@test.com")
        persistedCollection.put("diana", newUser)
        assertEquals(newUser, persistedCollection["diana"])
    }

    @Test
    fun testEp_handlesZeroSizeFile() = runTest {
        val testDir = Path("/tmp/oktopoi-zero-size-test")
        SystemFileSystem.createDirectories(testDir)
        
        val persistedState = ep<String>(rootDir = testDir)
        
        persistedState.callingClassName = "ZeroSizeTest"
        persistedState.propertyName = "zeroSizeProperty"
        
        // Create a zero-size file
        val classDir = Path(testDir, "ZeroSizeTest")
        SystemFileSystem.createDirectories(classDir)
        val zeroSizeFile = Path(classDir, "zeroSizeProperty")
        
        // Create empty file
        SystemFileSystem.sink(zeroSizeFile).buffered().use { /* empty */ }
        
        // Setup should handle zero-size file gracefully
        assertDoesNotThrow {
            persistedState.setup()
        }
        
        // Should use default value when file is empty
        assertNull(persistedState.value)
        
        delay(100.milliseconds)
    }

    // ==================== Memory and Resource Error Tests ====================

    @Test
    fun testE_handlesLargeNumberOfOperations() = runTest {
        val state = e<Int>()
        
        // Perform many operations rapidly
        assertDoesNotThrow {
            repeat(10000) { i ->
                state.value = i
                state.get()
                state.setIfDifferent(i + 1)
                state.compareAndSet(i + 1, i + 2)
            }
        }
        
        // State should still be functional
        assertNotNull(state.value)
    }

    @Test
    fun testEs_handlesLargeNumberOfEntries() = runTest {
        val collection = es<Int, String>(keySelector = { it.toInt() })
        
        // Add large number of entries
        assertDoesNotThrow {
            repeat(10000) { i ->
                collection.put(i, "value_$i")
            }
        }
        
        assertEquals(10000, collection.size)
        
        // Test navigation operations with large dataset
        assertEquals(0, collection.firstKey())
        assertEquals(9999, collection.lastKey())
        assertEquals(5000, collection.ceilingKey(5000))
        
        // Test removal
        assertDoesNotThrow {
            repeat(5000) { i ->
                collection.remove(i)
            }
        }
        
        assertEquals(5000, collection.size)
    }

    @Test
    fun testEs_handlesRapidModifications() = runTest {
        val collection = es<String, ErrorTestUser>(keySelector = { it.name })
        
        // Perform rapid modifications
        assertDoesNotThrow {
            repeat(1000) { i ->
                val user = ErrorTestUser(i, "User_$i", "user$i@test.com")
                collection.put("user_$i", user)
                
                if (i % 2 == 0) {
                    collection.remove("user_${i - 1}")
                }
                
                if (i % 10 == 0) {
                    // Update existing entry
                    collection.put("user_0", ErrorTestUser(0, "User_0", "updated@test.com"))
                }
            }
        }
        
        assertTrue(collection.size > 0)
    }

    @Test
    fun testEp_handlesConcurrentAccessWithErrors() = runTest {
        val persistedState = ep<String>()
        
        persistedState.callingClassName = "ConcurrentErrorTest"
        persistedState.propertyName = "concurrentProperty"
        persistedState.setup()
        
        // Launch multiple coroutines that might cause errors
        val jobs = (1..50).map { index ->
            launch {
                try {
                    repeat(100) { i ->
                        persistedState.value = "value_${index}_$i"
                        delay(1.milliseconds)
                        
                        // Occasionally try operations that might fail
                        if (i % 10 == 0) {
                            persistedState.clear()
                            persistedState.value = "cleared_${index}_$i"
                        }
                    }
                } catch (e: Exception) {
                    // Errors should be handled gracefully
                    println("Expected error in concurrent test: ${e.message}")
                }
            }
        }
        
        // Wait for all jobs
        jobs.joinAll()
        
        delay(200.milliseconds)
        
        // State should still be accessible
        assertDoesNotThrow {
            persistedState.value
            persistedState.get()
            persistedState.isEmpty()
        }
    }

    // ==================== Edge Case Error Tests ====================

    @Test
    fun testE_handlesExtremeSizeOperations() = runTest {
        val state = e<String>()
        
        // Test with very long string
        val extremelyLongString = "x".repeat(1000000) // 1MB string
        
        assertDoesNotThrow {
            state.value = extremelyLongString
        }
        
        assertEquals(1000000, state.value?.length)
        
        // Test clearing large value
        assertDoesNotThrow {
            state.clear()
        }
        
        assertNull(state.value)
    }

    @Test
    fun testEs_handlesSpecialCharacterKeys() = runTest {
        val collection = es<String, String>(keySelector = { it })
        
        // Test with various special characters in keys
        val specialKeys = listOf(
            "key with spaces",
            "key\nwith\nnewlines",
            "key\twith\ttabs",
            "key\"with\"quotes",
            "key'with'apostrophes",
            "key\\with\\backslashes",
            "key/with/slashes",
            "key:with:colons",
            "key|with|pipes",
            "key<with>brackets",
            "key{with}braces",
            "key[with]squares",
            "αβγδεζηθι", // Unicode characters
            "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02", // Emojis
        )
        
        assertDoesNotThrow {
            specialKeys.forEachIndexed { index, key ->
                collection.put(key, "value_$index")
            }
        }
        
        assertEquals(specialKeys.size, collection.size)
        
        // All keys should be retrievable
        specialKeys.forEach { key ->
            assertNotNull(collection[key])
        }
    }

    @Test
    fun testTreeMap_handlesComparatorExceptions() = runTest {
        // Create a comparator that throws exceptions for certain values
        val faultyComparator = Comparator<String> { a, b ->
            if (a.contains("error") || b.contains("error")) {
                throw RuntimeException("Comparator error")
            }
            a.compareTo(b)
        }
        
        val map = TreeMap<String, Int>(faultyComparator)
        
        // Normal operations should work
        assertDoesNotThrow {
            map.put("normal", 1)
            map.put("another", 2)
        }
        
        assertEquals(2, map.size)
        
        // Operations with error-causing keys should be handled
        // Note: This might throw or be handled depending on implementation
        try {
            map.put("error_key", 3)
        } catch (e: RuntimeException) {
            // Expected behavior - comparator exceptions are propagated
            assertTrue(e.message?.contains("Comparator error") == true)
        }
    }

    @Test
    fun testEp_handlesInitializationRaceConditions() = runTest {
        // Test multiple simultaneous initializations
        val states = (1..10).map { index ->
            ep<String>().apply {
                callingClassName = "RaceTest"
                propertyName = "property_$index"
            }
        }
        
        // Initialize all simultaneously
        val initJobs = states.map { state ->
            launch {
                state.setup()
                state.value = "initial_value"
            }
        }
        
        initJobs.joinAll()
        
        delay(200.milliseconds)
        
        // All states should be properly initialized
        states.forEach { state ->
            assertEquals("initial_value", state.value)
        }
    }
}