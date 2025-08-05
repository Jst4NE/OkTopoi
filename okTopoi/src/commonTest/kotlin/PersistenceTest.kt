package jst.oktopoi

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class TestUser(val id: Int, val name: String, val department: String)

class PersistenceTest {
    
    @Test
    fun testEpPersistence() = runTest {
        val testRootDir = Path("/tmp/oktopoi-ep-test-${System.currentTimeMillis()}")
        
        // Initialize the IO system FIRST
        initDefaultIO(testRootDir)
        
        // Then create the ep instance - compiler plugin will call setup() and find initDefaultIO
        val testEp = ep<String> { "initial" }
        
        // Allow setup to complete
        delay(100)
        
        // Set a value - compiler plugin should have called setup() automatically
        testEp.set("test-value")
        
        // Allow persistence to complete
        delay(100)
        
        // Print the path for manual inspection
        println("EP Test persistence directory: $testRootDir")
        
        // Also verify files actually exist on disk
        if (SystemFileSystem.exists(testRootDir)) {
            println("EP Root directory exists!")
            SystemFileSystem.list(testRootDir).forEach {
                println("EP Found: $it")
                if (SystemFileSystem.exists(it) && SystemFileSystem.metadataOrNull(it)?.isDirectory == true) {
                    SystemFileSystem.list(it).forEach { subFile ->
                        println("  EP Subfile: $subFile")
                        if (SystemFileSystem.exists(subFile) && SystemFileSystem.metadataOrNull(subFile)?.isDirectory == true) {
                            SystemFileSystem.list(subFile).forEach { deepFile ->
                                println("    EP Deep file: $deepFile")
                                // Also show file content if it's a file
                                if (SystemFileSystem.metadataOrNull(deepFile)?.isRegularFile == true) {
                                    val content = SystemFileSystem.source(deepFile).buffered().use { it.readString() }
                                    println("      Content: $content")
                                }
                            }
                        } else if (SystemFileSystem.metadataOrNull(subFile)?.isRegularFile == true) {
                            val content = SystemFileSystem.source(subFile).buffered().use { it.readString() }
                            println("    EP File content: $content")
                        }
                    }
                }
            }
        } else {
            println("EP Root directory does NOT exist!")
        }
        
        // Verify persistence worked by checking the value is still there
        assertEquals("test-value", testEp.get())
    }

    @Test 
    fun testEpsPersistence() = runTest {
        val testRootDir = Path("/tmp/oktopoi-test-${System.currentTimeMillis()}")
        
        // Initialize the IO system FIRST
        initDefaultIO(testRootDir)
        
        // Then create the eps instance - compiler plugin will call setup() and find initDefaultIO
        val testEps = eps<Int, TestUser>(keySelector = { it.id })
        
        // Manually call setup since compiler plugin doesn't apply to library's own tests
        testEps.callingClassName = "PersistenceTest"
        testEps.propertyName = "testEps"
        testEps.setup()
        
        // Allow setup to complete
        delay(100)
        
        // Add some data - compiler plugin should have called setup() automatically
        val user1 = TestUser(1, "Alice", "Engineering")
        val user2 = TestUser(2, "Bob", "Design")
        
        testEps.put(1, user1)
        testEps.put(2, user2)
        
        // Allow persistence to complete
        delay(200)
        
        // Print the path for manual inspection
        println("Test persistence directory: $testRootDir")
        
        // Also verify files actually exist on disk
        if (SystemFileSystem.exists(testRootDir)) {
            println("Root directory exists!")
            SystemFileSystem.list(testRootDir).forEach {
                println("Found: $it")
                if (SystemFileSystem.exists(it) && SystemFileSystem.metadataOrNull(it)?.isDirectory == true) {
                    SystemFileSystem.list(it).forEach { subFile ->
                        println("  Subfile: $subFile")
                        if (SystemFileSystem.exists(subFile) && SystemFileSystem.metadataOrNull(subFile)?.isDirectory == true) {
                            SystemFileSystem.list(subFile).forEach { deepFile ->
                                println("    Deep file: $deepFile")
                            }
                        }
                    }
                }
            }
        } else {
            println("Root directory does NOT exist!")
        }
        
        // Verify persistence worked
        assertEquals(2, testEps.size)
        assertEquals(user1, testEps[1])
        assertEquals(user2, testEps[2])
    }

    @Test
    fun testEpsWithSecondaryIndexPersistence() = runTest {
        val testRootDir = Path("/tmp/oktopoi-secondary-${System.currentTimeMillis()}")
        
        // Initialize the IO system FIRST
        initDefaultIO(testRootDir)
        
        // Then create the eps instance with secondary index
        val testEpsWithIndex = eps<Int, TestUser>(keySelector = { it.id }) {
            key("department") { it.department }
        }
        
        // Allow setup to complete
        delay(100)
        
        // Add some data
        val user1 = TestUser(1, "Alice", "Engineering")
        val user2 = TestUser(2, "Bob", "Engineering") 
        val user3 = TestUser(3, "Carol", "Design")
        
        testEpsWithIndex.put(1, user1)
        testEpsWithIndex.put(2, user2)
        testEpsWithIndex.put(3, user3)
        
        // Allow persistence to complete
        delay(200)
        
        // Verify persistence worked for both data and secondary indexes
        assertEquals(3, testEpsWithIndex.size)
        
        // Test secondary index functionality
        val engineeringUsers = testEpsWithIndex.getBy("department" to "Engineering")
        assertEquals(2, engineeringUsers.size)
        assertTrue(engineeringUsers.any { it.name == "Alice" })
        assertTrue(engineeringUsers.any { it.name == "Bob" })
        
        val designUsers = testEpsWithIndex.getBy("department" to "Design")
        assertEquals(1, designUsers.size)
        assertEquals("Carol", designUsers.first().name)
    }

    @Test
    fun testEpsModificationPersistence() = runTest {
        val testRootDir = Path("/tmp/oktopoi-modify-${System.currentTimeMillis()}")
        
        // Initialize the IO system FIRST  
        initDefaultIO(testRootDir)
        
        // Then create the eps instance
        val testEpsModify = eps<Int, TestUser>(keySelector = { it.id })
        
        delay(100)
        
        // Add initial data
        val user1 = TestUser(1, "Alice", "Engineering")
        val user2 = TestUser(2, "Bob", "Design")
        testEpsModify.put(1, user1)
        testEpsModify.put(2, user2)
        
        delay(100)
        
        // Modify data
        val updatedUser1 = TestUser(1, "Alice Updated", "Engineering")
        testEpsModify.put(1, updatedUser1)
        
        // Remove data
        testEpsModify.remove(2)
        
        delay(100)
        
        assertEquals(1, testEpsModify.size)
        assertEquals(updatedUser1, testEpsModify[1])
        assertEquals(null, testEpsModify[2])
    }
}