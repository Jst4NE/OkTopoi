package jst.oktopoi

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
data class PersistenceTestData(
    val id: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val numbers: List<Int> = emptyList(),
    val timestamp: Long = 0L
)

class PersistenceMechanismsTest {

    private val testRootDir = Path("/tmp/oktopoi-persistence-mechanisms-test")
    
    @BeforeTest
    fun setup() {
        // Initialize IO for persistence tests
        initDefaultIO(testRootDir, SystemFileSystem)
        initRootDirIO(testRootDir, SystemFileSystem)
    }

    // ==================== Initialization Functions ====================

    @Test
    fun testInitDefaultIO_basic() {
        val customDir = Path("/tmp/oktopoi-custom-init")
        
        // Test initDefaultIO
        initDefaultIO(customDir, SystemFileSystem)
        
        // Should be able to create persistent state without explicit rootDir
        val state = ep<String>()
        state.value = "test_value"
        
        assertEquals("test_value", state.value)
        
        // Custom directory test completed
    }

    @Test
    fun testInitRootDirIO_basic() {
        val customRootDir = Path("/tmp/oktopoi-root-init")
        
        // Test initRootDirIO
        initRootDirIO(customRootDir, SystemFileSystem)
        
        // Should be able to create persistent state with explicit rootDir
        val state = ep<String>(rootDir = customRootDir)
        state.value = "root_test_value"
        
        assertEquals("root_test_value", state.value)
        
        // Root directory test completed
    }

    @Test
    fun testInitialization_multipleDirectories() {
        val defaultDir = Path("/tmp/oktopoi-default")
        val explicitDir = Path("/tmp/oktopoi-explicit")
        
        // Initialize both
        initDefaultIO(defaultDir, SystemFileSystem)
        initRootDirIO(explicitDir, SystemFileSystem)
        
        // Create states using different directories
        val defaultState = ep<String>()
        val explicitState = ep<String>(rootDir = explicitDir)
        
        defaultState.value = "default_dir_value"
        explicitState.value = "explicit_dir_value"
        
        assertEquals("default_dir_value", defaultState.value)
        assertEquals("explicit_dir_value", explicitState.value)
        
        // Multiple directory test completed
    }

    // ==================== File System Operations ====================

    @Test
    fun testPersistence_fileSystemIntegration() {
        val state = ep<PersistenceTestData>()
        
        val testData = PersistenceTestData(
            id = "test-001",
            content = "This is test content",
            metadata = mapOf("author" to "test", "version" to "1.0"),
            numbers = listOf(1, 2, 3, 4, 5)
        )
        
        state.value = testData
        assertEquals(testData, state.value)
        
        // Wait for persistence operations
        // delay(200)
        
        // Verify that the persistence mechanism doesn't interfere with normal operations
        assertEquals(testData, state.value)
        assertEquals("test-001", state.value?.id)
        assertEquals("This is test content", state.value?.content)
        assertEquals(mapOf("author" to "test", "version" to "1.0"), state.value?.metadata)
        assertEquals(listOf(1, 2, 3, 4, 5), state.value?.numbers)
    }

    @Test
    fun testPersistence_collectionFileSystemIntegration() {
        val collection = eps<String, PersistenceTestData>(keySelector = { data -> data.id })
        
        val testData1 = PersistenceTestData("001", "Content 1", mapOf("type" to "test"))
        val testData2 = PersistenceTestData("002", "Content 2", mapOf("type" to "demo"))
        val testData3 = PersistenceTestData("003", "Content 3", mapOf("type" to "test"))
        
        collection.put("001", testData1)
        collection.put("002", testData2)
        collection.put("003", testData3)
        
        assertEquals(3, collection.size)
        assertEquals(testData1, collection["001"])
        assertEquals(testData2, collection["002"])
        assertEquals(testData3, collection["003"])
        
        // Wait for persistence operations
        // delay(200)
        
        // Verify data integrity
        assertEquals(3, collection.size)
        assertEquals("Content 1", collection["001"]?.content)
        assertEquals("Content 2", collection["002"]?.content)
        assertEquals("Content 3", collection["003"]?.content)
    }

    @Test
    fun testPersistence_rapidOperations() {
        val state = ep<String>()
        
        // Rapid consecutive operations to stress persistence
        for (i in 1..100) {
            state.value = "value_$i"
        }
        
        assertEquals("value_100", state.value)
        
        // Wait for all persistence operations to complete
        // delay(300)
        
        assertEquals("value_100", state.value)
    }

    @Test
    fun testPersistence_collectionRapidOperations() {
        val collection = eps<Int, PersistenceTestData>(keySelector = { data -> data.id.toInt() })
        
        // Rapid operations on collection
        for (i in 1..50) {
            val data = PersistenceTestData(
                id = i.toString(),
                content = "Content $i",
                numbers = listOf(i, i * 2, i * 3)
            )
            collection.put(i, data)
        }
        
        assertEquals(50, collection.size)
        
        // Remove some items rapidly
        for (i in 1..25) {
            collection.remove(i)
        }
        
        assertEquals(25, collection.size)
        
        // Wait for persistence operations
        // delay(300)
        
        assertEquals(25, collection.size)
        assertEquals("Content 26", collection[26]?.content)
        assertEquals("Content 50", collection[50]?.content)
    }

    // ==================== Serialization Edge Cases ====================

    @Test
    fun testPersistence_emptyAndNullValues() {
        val state = ep<String?>()
        
        // Test null values
        state.value = null
        assertNull(state.value)
        // delay(50)
        
        state.value = "not_null"
        assertEquals("not_null", state.value)
        // delay(50)
        
        state.value = null
        assertNull(state.value)
        // delay(50)
        
        // Test empty strings
        state.value = ""
        assertEquals("", state.value)
        // delay(50)
    }

    @Test
    fun testPersistence_specialCharacters() {
        val state = ep<String>()
        
        val specialStrings = listOf(
            "Unicode: αβγδεζηθικλμνξοπρστυφχψω",
            "Emojis: 🚀🌟💯🎉🔥🌍❤️",
            "JSON-like: {\"key\": \"value\", \"number\": 42}",
            "XML-like: <tag attribute=\"value\">content</tag>",
            "Newlines:\nLine 1\nLine 2\nLine 3",
            "Tabs:\tTabbed\tcontent\there",
            "Mixed: Hello 世界 🌍 αβγ 123 !@#$%^&*()",
            "Quotes: \"double\" and 'single' quotes",
            "Backslashes: C:\\Users\\Name\\Documents\\file.txt",
            "Very long string: " + "x".repeat(1000)
        )
        
        for (str in specialStrings) {
            state.value = str
            assertEquals(str, state.value)
            // delay(20)
        }
    }

    @Test
    fun testPersistence_complexDataStructures() {
        val state = ep<Map<String, List<PersistenceTestData>>>()
        
        val complexData = mapOf(
            "group1" to listOf(
                PersistenceTestData("1", "Content 1", mapOf("a" to "1")),
                PersistenceTestData("2", "Content 2", mapOf("b" to "2"))
            ),
            "group2" to listOf(
                PersistenceTestData("3", "Content 3", mapOf("c" to "3")),
                PersistenceTestData("4", "Content 4", mapOf("d" to "4")),
                PersistenceTestData("5", "Content 5", mapOf("e" to "5"))
            ),
            "empty_group" to emptyList()
        )
        
        state.value = complexData
        assertEquals(complexData, state.value)
        // delay(100)
        
        // Verify nested structure integrity
        assertEquals(3, state.value?.size)
        assertEquals(2, state.value?.get("group1")?.size)
        assertEquals(3, state.value?.get("group2")?.size)
        assertEquals(0, state.value?.get("empty_group")?.size)
        assertEquals("Content 1", state.value?.get("group1")?.get(0)?.content)
    }

    @Test
    fun testPersistence_numericLimits() {
        val intState = ep<Int>()
        val longState = ep<Long>()
        val doubleState = ep<Double>()
        
        // Test extreme values
        intState.value = Int.MAX_VALUE
        // delay(50)
        assertEquals(Int.MAX_VALUE, intState.value)
        
        intState.value = Int.MIN_VALUE
        // delay(50)
        assertEquals(Int.MIN_VALUE, intState.value)
        
        longState.value = Long.MAX_VALUE
        // delay(50)
        assertEquals(Long.MAX_VALUE, longState.value)
        
        longState.value = Long.MIN_VALUE
        // delay(50)
        assertEquals(Long.MIN_VALUE, longState.value)
        
        doubleState.value = Double.MAX_VALUE
        // delay(50)
        assertEquals(Double.MAX_VALUE, doubleState.value)
        
        doubleState.value = Double.MIN_VALUE
        // delay(50)
        assertEquals(Double.MIN_VALUE, doubleState.value)
        
        doubleState.value = Double.POSITIVE_INFINITY
        // delay(50)
        assertEquals(Double.POSITIVE_INFINITY, doubleState.value)
        
        doubleState.value = Double.NEGATIVE_INFINITY
        // delay(50)
        assertEquals(Double.NEGATIVE_INFINITY, doubleState.value)
    }

    // ==================== Concurrency and Async Behavior ====================

    @Test
    fun testPersistence_concurrentAccess() {
        val state = ep<String>()
        
        // Test concurrent operations without actual concurrency
        for (i in 1..20) {
            state.value = "concurrent_value_$i"
            assertTrue(state.value?.startsWith("concurrent_value_") == true)
        }
        
        // Should have some final value
        assertNotNull(state.value)
        assertTrue(state.value!!.startsWith("concurrent_value_"))
        
        // delay(100) // Wait for all persistence operations
    }

    @Test
    fun testPersistence_collectionConcurrentAccess() {
        val collection = eps<Int, String>(keySelector = { value -> value.toInt() })
        
        // Test concurrent operations without actual concurrency
        for (i in 1..30) {
            collection.put(i, "concurrent_item_$i")
            if (i % 2 == 0) {
                collection.remove(i)
            }
        }
        
        // Should have odd-numbered items remaining
        assertTrue(collection.size >= 10 && collection.size <= 20)
        
        // delay(150) // Wait for persistence operations
    }

    @Test
    fun testPersistence_backgroundOperations() {
        val state = ep<PersistenceTestData>()
        
        val testData = PersistenceTestData(
            id = "bg_test",
            content = "Background operation test",
            metadata = mapOf("async" to "true"),
            numbers = (1..100).toList()
        )
        
        // Set value and immediately verify it's available
        state.value = testData
        assertEquals(testData, state.value)
        
        // Change value multiple times rapidly
        for (i in 1..10) {
            state.value = testData.copy(
                content = "Background test iteration $i",
                timestamp = 0L
            )
            // Value should be immediately available even while persistence is ongoing
            assertEquals("Background test iteration $i", state.value?.content)
        }
        
        // delay(200) // Wait for all background persistence to complete
        
        assertEquals("Background test iteration 10", state.value?.content)
    }

    // ==================== Error Resilience ====================

    @Test
    fun testPersistence_invalidOperations() {
        val state = ep<String>()
        val collection = eps<String, String>(keySelector = { value -> value })
        
        // These operations should not cause crashes even if persistence fails
        repeat(100) {
            state.value = "stress_test_$it"
            collection.put("key_$it", "value_$it")
        }
        
        // delay(100)
        
        assertEquals("stress_test_99", state.value)
        assertEquals(100, collection.size)
        
        // Clear operations
        state.clear()
        collection.clear()
        
        // delay(100)
        
        assertNull(state.value)
        assertTrue(collection.isEmpty())
    }

    @Test
    fun testPersistence_largeDataSets() {
        val collection = eps<Int, PersistenceTestData>(keySelector = { data -> data.id.toInt() })
        
        // Create large dataset
        val largeDataSet = (1..500).map { i ->
            PersistenceTestData(
                id = i.toString(),
                content = "Large dataset item $i with more content to make it bigger",
                metadata = mapOf(
                    "index" to i.toString(),
                    "type" to "large_test",
                    "category" to "batch_${i / 50}",
                    "description" to "This is a longer description for item $i to test larger data"
                ),
                numbers = (1..i % 20).toList()
            )
        }
        
        // Insert all data
        largeDataSet.forEach { data ->
            collection.put(data.id.toInt(), data)
        }
        
        assertEquals(500, collection.size)
        
        // Wait for persistence
        // delay(500)
        
        // Verify random samples
        val randomSamples = listOf(1, 50, 100, 250, 400, 500)
        randomSamples.forEach { i ->
            val item = collection[i]
            assertNotNull(item)
            assertEquals(i.toString(), item.id)
            assertEquals("Large dataset item $i with more content to make it bigger", item.content)
        }
        
        // Remove half the items
        for (i in 1..250) {
            collection.remove(i)
        }
        
        assertEquals(250, collection.size)
        // delay(300)
        
        // Verify remaining items
        for (i in 251..500) {
            assertNotNull(collection[i])
        }
    }

    // ==================== File System Specific Tests ====================

    @Test
    fun testPersistence_directoryStructure() {
        val state1 = ep<String>()
        val state2 = ep<Int>()
        val collection1 = eps<String, String>(keySelector = { value -> value })
        val collection2 = eps<Int, PersistenceTestData>(keySelector = { data -> data.id.toInt() })
        
        // Set values to trigger persistence setup
        state1.value = "test1"
        state2.value = 42
        collection1.put("key1", "value1")
        collection2.put(1, PersistenceTestData("1", "Test"))
        
        // delay(200)
        
        // Verify that the directory structure is created
        assertTrue(SystemFileSystem.exists(testRootDir))
        
        // The exact directory structure depends on internal implementation
        // but operations should work correctly
        assertEquals("test1", state1.value)
        assertEquals(42, state2.value)
        assertEquals("value1", collection1["key1"])
        assertEquals("Test", collection2[1]?.content)
    }

    @Test
    fun testPersistence_fileSystemErrors() {
        // Test behavior when file system operations might fail
        val state = ep<String>()
        val collection = eps<String, String>(keySelector = { value -> value })
        
        // Normal operations should still work even if persistence fails
        state.value = "error_test_1"
        assertEquals("error_test_1", state.value)
        
        collection.put("error_key", "error_value")
        assertEquals("error_value", collection["error_key"])
        
        // Rapid operations
        for (i in 1..50) {
            state.value = "error_test_$i"
            collection.put("key_$i", "value_$i")
        }
        
        // delay(200)
        
        assertEquals("error_test_50", state.value)
        assertEquals(51, collection.size) // 50 + original error_key
    }

    // ==================== Integration Tests ====================

    @Test
    fun testPersistence_mixedOperations() {
        val simpleState = ep<String>()
        val complexState = ep<PersistenceTestData>()
        val simpleCollection = eps<String, Int>(keySelector = { value -> value.toString() })
        val complexCollection = eps<String, PersistenceTestData>(
            keySelector = { data -> data.id },
            secondaryKeys = {
                key("content_length") { data -> data.content.length }
                key("has_metadata") { data -> data.metadata.isNotEmpty() }
            }
        )
        
        // Perform mixed operations
        simpleState.value = "simple"
        complexState.value = PersistenceTestData("complex", "Complex data")
        
        simpleCollection.put("1", 1)
        simpleCollection.put("2", 2)
        
        complexCollection.put("data1", PersistenceTestData("data1", "First", mapOf("type" to "first")))
        complexCollection.put("data2", PersistenceTestData("data2", "Second"))
        
        // delay(100)
        
        // Verify all operations work correctly
        assertEquals("simple", simpleState.value)
        assertEquals("Complex data", complexState.value?.content)
        assertEquals(2, simpleCollection.size)
        assertEquals(2, complexCollection.size)
        
        // Test secondary keys
        val hasMetadata = complexCollection.getBySecondaryKey("has_metadata", true)
        assertEquals(1, hasMetadata.size)
        assertEquals("data1", hasMetadata[0].id)
        
        val noMetadata = complexCollection.getBySecondaryKey("has_metadata", false)
        assertEquals(1, noMetadata.size)
        assertEquals("data2", noMetadata[0].id)
        
        // Update and remove operations
        simpleState.value = "updated_simple"
        complexState.value = complexState.value?.copy(content = "Updated complex")
        simpleCollection.remove("1")
        complexCollection.remove("data1")
        
        // delay(100)
        
        assertEquals("updated_simple", simpleState.value)
        assertEquals("Updated complex", complexState.value?.content)
        assertEquals(1, simpleCollection.size)
        assertEquals(1, complexCollection.size)
        
        // Clear operations
        simpleState.clear()
        simpleCollection.clear()
        complexCollection.clear()
        
        // delay(100)
        
        assertNull(simpleState.value)
        assertTrue(simpleCollection.isEmpty())
        assertTrue(complexCollection.isEmpty())
    }
}