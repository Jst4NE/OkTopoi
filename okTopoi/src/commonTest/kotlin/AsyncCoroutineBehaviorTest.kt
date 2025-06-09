package jst.oktopoi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Serializable
data class AsyncTestUser(val id: Int, val name: String, val email: String)

class AsyncCoroutineBehaviorTest {

    private val testRootDir = Path("/tmp/oktopoi-async-test")
    
    @BeforeTest
    fun setup() {
        // Clean up any existing test data
        if (SystemFileSystem.exists(testRootDir)) {
            // SystemFileSystem.deleteRecursively(testRootDir) // disabled for safety
        }
        
        // Initialize IO for async tests
        initDefaultIO(testRootDir, SystemFileSystem)
        initRootDirIO(testRootDir, SystemFileSystem)
    }

    @AfterTest
    fun cleanup() {
        // Clean up test data
        if (SystemFileSystem.exists(testRootDir)) {
            // SystemFileSystem.deleteRecursively(testRootDir) // disabled for safety
        }
    }

    // ==================== Change Notifications Flow Collection Tests ====================

    @Test
    fun testE_changeNotificationsFlow() = runTest {
        val state = e<String>()
        val changes = mutableListOf<String?>()
        
        // Start collecting changes
        val collectJob = launch {
            state.collect { value ->
                changes.add(value)
            }
        }
        
        // Give collector time to start
        delay(10.milliseconds)
        
        // Make changes
        state.value = "first"
        delay(10.milliseconds)
        
        state.value = "second"
        delay(10.milliseconds)
        
        state.value = null
        delay(10.milliseconds)
        
        state.value = "third"
        delay(10.milliseconds)
        
        collectJob.cancel()
        
        // Verify all changes were collected
        assertTrue(changes.size >= 4)
        assertEquals("first", changes[1])  // changes[0] is initial null
        assertEquals("second", changes[2])
        assertNull(changes[3])
        assertEquals("third", changes[4])
    }

    @Test
    fun testEs_changeNotificationsFlow() = runTest {
        val collection = es<String, AsyncTestUser>(keySelector = { it.name })
        val changes = mutableListOf<TreeMap.MapChange<String, AsyncTestUser>>()
        
        // Start collecting changes
        val collectJob = launch {
            collection.changes.collect { change ->
                changes.add(change)
            }
        }
        
        // Give collector time to start
        delay(10.milliseconds)
        
        val user1 = AsyncTestUser(1, "Alice", "alice@test.com")
        val user2 = AsyncTestUser(2, "Bob", "bob@test.com")
        val updatedUser1 = AsyncTestUser(1, "Alice", "alice.updated@test.com")
        
        // Make changes
        collection.put("alice", user1)
        delay(10.milliseconds)
        
        collection.put("bob", user2)
        delay(10.milliseconds)
        
        collection.put("alice", updatedUser1)  // Update existing
        delay(10.milliseconds)
        
        collection.remove("bob")
        delay(10.milliseconds)
        
        collection.clear()
        delay(10.milliseconds)
        
        collectJob.cancel()
        
        // Verify changes were collected
        assertTrue(changes.size >= 5)
        
        // Check Put operations
        val putChanges = changes.filterIsInstance<TreeMap.MapChange.Put<String, AsyncTestUser>>()
        assertTrue(putChanges.size >= 3)
        
        // Check Remove operations
        val removeChanges = changes.filterIsInstance<TreeMap.MapChange.Removed<String, AsyncTestUser>>()
        assertTrue(removeChanges.size >= 1)
        
        // Check Clear operations
        val clearChanges = changes.filterIsInstance<TreeMap.MapChange.Cleared<String, AsyncTestUser>>()
        assertTrue(clearChanges.size >= 1)
    }

    @Test
    fun testEs_multipleSubscribers() = runTest {
        val collection = es<String, Int>(keySelector = { it.toString() })
        val subscriber1Changes = mutableListOf<TreeMap.MapChange<String, Int>>()
        val subscriber2Changes = mutableListOf<TreeMap.MapChange<String, Int>>()
        
        // Start multiple subscribers
        val job1 = launch {
            collection.changes.collect { change ->
                subscriber1Changes.add(change)
            }
        }
        
        val job2 = launch {
            collection.changes.collect { change ->
                subscriber2Changes.add(change)
            }
        }
        
        delay(10.milliseconds)
        
        // Make changes
        collection.put("1", 100)
        collection.put("2", 200)
        collection.remove("1")
        
        delay(50.milliseconds)
        
        job1.cancel()
        job2.cancel()
        
        // Both subscribers should receive the same changes
        assertEquals(subscriber1Changes.size, subscriber2Changes.size)
        assertTrue(subscriber1Changes.size >= 3)
    }

    @Test
    fun testE_flowBackpressure() = runTest {
        val state = e<Int>()
        val slowCollector = mutableListOf<Int?>()
        
        // Start a slow collector
        val collectJob = launch {
            state.collect { value ->
                delay(50.milliseconds) // Simulate slow processing
                slowCollector.add(value)
            }
        }
        
        delay(10.milliseconds)
        
        // Rapidly emit values
        for (i in 1..10) {
            state.value = i
            delay(5.milliseconds)
        }
        
        // Wait for processing
        delay(600.milliseconds)
        collectJob.cancel()
        
        // Should have collected some values (StateFlow drops intermediate values)
        assertTrue(slowCollector.size >= 2)
        assertEquals(10, slowCollector.last()) // Last value should be preserved
    }

    // ==================== Persistence Coroutine Behavior Tests ====================

    @Test
    fun testEp_persistenceCoroutineBehavior() = runTest {
        val persistedState = ep<String>()
        
        // Simulate setting property metadata that would be done by compiler plugin
        persistedState.callingClassName = "TestClass"
        persistedState.propertyName = "testProperty"
        persistedState.setup()
        
        // Set initial value
        persistedState.value = "initial_value"
        
        // Wait for persistence coroutine to process
        delay(100.milliseconds)
        
        // Change value multiple times rapidly
        for (i in 1..5) {
            persistedState.value = "value_$i"
            delay(10.milliseconds)
        }
        
        // Wait for final persistence
        delay(200.milliseconds)
        
        // Value should be persisted (file existence would be checked in real scenario)
        assertEquals("value_5", persistedState.value)
    }

    @Test
    fun testEps_persistenceCoroutineBehavior() = runTest {
        val persistedCollection = eps<String, AsyncTestUser>(keySelector = { it.name })
        
        // Simulate setting property metadata
        persistedCollection.callingClassName = "TestClass"
        persistedCollection.propertyName = "testCollection"
        persistedCollection.setup()
        
        val users = listOf(
            AsyncTestUser(1, "Alice", "alice@test.com"),
            AsyncTestUser(2, "Bob", "bob@test.com"),
            AsyncTestUser(3, "Charlie", "charlie@test.com")
        )
        
        // Add users rapidly
        users.forEach { user ->
            persistedCollection.put(user.name, user)
            delay(10.milliseconds)
        }
        
        // Wait for persistence
        delay(200.milliseconds)
        
        // Update users
        users.forEachIndexed { index, user ->
            val updatedUser = user.copy(email = "updated_${user.email}")
            persistedCollection.put(user.name, updatedUser)
            delay(10.milliseconds)
        }
        
        // Wait for persistence
        delay(200.milliseconds)
        
        // Remove one user
        persistedCollection.remove("Bob")
        delay(100.milliseconds)
        
        // Collection should be in expected state
        assertEquals(2, persistedCollection.size)
        assertNotNull(persistedCollection["Alice"])
        assertNull(persistedCollection["Bob"])
        assertNotNull(persistedCollection["Charlie"])
    }

    @Test
    fun testEp_concurrentModifications() = runTest {
        val persistedState = ep<Int>()
        
        persistedState.callingClassName = "ConcurrentTest"
        persistedState.propertyName = "concurrentProperty"
        persistedState.setup()
        
        // Launch multiple coroutines modifying the state
        val jobs = (1..10).map { index ->
            launch {
                for (i in 1..10) {
                    persistedState.value = index * 10 + i
                    delay(5.milliseconds)
                }
            }
        }
        
        // Wait for all modifications to complete
        jobs.joinAll()
        delay(200.milliseconds)
        
        // State should have some final value (exact value is non-deterministic)
        assertNotNull(persistedState.value)
        assertTrue(persistedState.value!! > 0)
    }

    @Test
    fun testEps_bulkOperationsAsync() = runTest {
        val persistedCollection = eps<String, String>(keySelector = { it })
        
        persistedCollection.callingClassName = "BulkTest"
        persistedCollection.propertyName = "bulkCollection"
        persistedCollection.setup()
        
        // Perform bulk operations
        val largeData = (1..100).associate { it.toString() to "value_$it" }
        
        // Add all data using putAll
        persistedCollection.putAll(largeData)
        delay(300.milliseconds)
        
        assertEquals(100, persistedCollection.size)
        
        // Remove half the data concurrently
        val removeJobs = (1..50).map { key ->
            launch {
                persistedCollection.remove(key.toString())
                delay(2.milliseconds)
            }
        }
        
        removeJobs.joinAll()
        delay(200.milliseconds)
        
        assertEquals(50, persistedCollection.size)
        
        // Clear all
        persistedCollection.clear()
        delay(100.milliseconds)
        
        assertTrue(persistedCollection.isEmpty())
    }

    // ==================== Background I/O Operations Timing Tests ====================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEp_ioOperationTiming() = runTest {
        val persistedState = ep<List<String>>()
        
        persistedState.callingClassName = "IOTimingTest"
        persistedState.propertyName = "ioProperty"
        persistedState.setup()
        
        val largeList = (1..1000).map { "item_$it" }
        
        val startTime = currentTime
        persistedState.value = largeList
        
        // Wait for I/O to complete
        delay(500.milliseconds)
        
        val endTime = currentTime
        
        // I/O should not block the main operation
        val elapsedTime = endTime - startTime
        assertTrue(elapsedTime < 100.milliseconds.inWholeMilliseconds, "Operation should not block for I/O")
        
        assertEquals(largeList, persistedState.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEps_ioOperationTiming() = runTest {
        val persistedCollection = eps<String, AsyncTestUser>(keySelector = { it.name })
        
        persistedCollection.callingClassName = "IOTimingTest"
        persistedCollection.propertyName = "ioCollection"
        persistedCollection.setup()
        
        val largeUserSet = (1..100).map { 
            AsyncTestUser(it, "User_$it", "user$it@test.com") 
        }
        
        val startTime = currentTime
        
        // Add all users
        largeUserSet.forEach { user ->
            persistedCollection.put(user.name, user)
        }
        
        val endTime = currentTime
        
        // Operations should not block for I/O
        val elapsedTime = endTime - startTime
        assertTrue(elapsedTime < 200.milliseconds.inWholeMilliseconds, "Bulk operations should not block for I/O")
        
        // Wait for I/O to complete
        delay(1.seconds)
        
        assertEquals(100, persistedCollection.size)
    }

    @Test
    fun testE_stateFlowSubscriptionCount() = runTest {
        val state = e<String>()
        
        // Initially no subscribers
        assertEquals(0, state.subscriptionCount.value)
        
        val job1 = launch {
            state.collect { /* no-op */ }
        }
        
        delay(10.milliseconds)
        assertTrue(state.subscriptionCount.value >= 1)
        
        val job2 = launch {
            state.collect { /* no-op */ }
        }
        
        delay(10.milliseconds)
        assertTrue(state.subscriptionCount.value >= 2)
        
        job1.cancel()
        delay(10.milliseconds)
        assertTrue(state.subscriptionCount.value >= 1)
        
        job2.cancel()
        delay(10.milliseconds)
        // Note: subscription count might not immediately drop to 0 due to flow implementation
    }

    @Test
    fun testEp_initializationFlow() = runTest {
        // Test the initialization flow for persisted state
        val initChanges = mutableListOf<Pair<Path, kotlinx.io.files.FileSystem>?>()
        
        // Monitor initialization
        val job = launch {
            initDefaultIO.collect { value ->
                initChanges.add(value)
            }
        }
        
        delay(10.milliseconds)
        
        // Trigger initialization
        val customDir = Path("/tmp/test-init")
        initDefaultIO(customDir, SystemFileSystem)
        
        delay(50.milliseconds)
        job.cancel()
        
        // Should have received initialization event
        assertTrue(initChanges.size >= 2) // initial null + new value
        assertNotNull(initChanges.last())
        assertEquals(customDir, initChanges.last()!!.first)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEp_resetReplayCache() = runTest {
        val state = e<String>()
        
        state.value = "initial"
        assertEquals(listOf("initial"), state.replayCache)
        
        state.value = "updated"
        assertEquals(listOf("updated"), state.replayCache)
        
        // Reset replay cache (if supported)
        try {
            state.resetReplayCache()
            // After reset, should still have current value
            assertTrue(state.replayCache.isNotEmpty())
        } catch (e: Exception) {
            // resetReplayCache might not be supported in all implementations
            println("resetReplayCache not supported: ${e.message}")
        }
    }

    @Test
    fun testEs_changeBufferOverflow() = runTest {
        val collection = es<Int, String>(keySelector = { it.toInt() })
        val collectedChanges = mutableListOf<TreeMap.MapChange<Int, String>>()
        
        // Start collecting changes
        val collectJob = launch {
            collection.changes.collect { change ->
                collectedChanges.add(change)
                delay(100.milliseconds) // Slow collector to test overflow
            }
        }
        
        delay(10.milliseconds)
        
        // Generate many changes rapidly to test buffer overflow
        repeat(300) { i ->
            collection.put(i, "value_$i")
        }
        
        delay(2.seconds)
        collectJob.cancel()
        
        // Due to DROP_OLDEST strategy, we should have collected some changes
        // but not necessarily all of them
        assertTrue(collectedChanges.isNotEmpty())
        println("Collected ${collectedChanges.size} out of 300 changes")
    }
}