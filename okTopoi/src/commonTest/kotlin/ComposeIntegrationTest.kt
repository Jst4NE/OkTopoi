package jst.oktopoi

import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class ComposeTestItem(val id: Int, val name: String, val category: String, val score: Double)

class ComposeIntegrationTest {

    private val testRootDir = Path("/tmp/oktopoi-compose-test")
    
    @BeforeTest
    fun setup() {
        // Clean up any existing test data
        if (SystemFileSystem.exists(testRootDir)) {
            // SystemFileSystem.deleteRecursively(testRootDir) // disabled
        }
        
        // Initialize IO for compose tests
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

    // ==================== Basic SnapshotStateList Tests ====================

    @Test
    fun testEs_asSnapshotStateList_basicOperations() = runTest {
        val collection = es<Int, ComposeTestItem>(keySelector = { it.id })
        
        val items = listOf(
            ComposeTestItem(1, "Apple", "Fruit", 4.5),
            ComposeTestItem(2, "Banana", "Fruit", 4.2),
            ComposeTestItem(3, "Carrot", "Vegetable", 3.8),
            ComposeTestItem(4, "Broccoli", "Vegetable", 4.0),
            ComposeTestItem(5, "Orange", "Fruit", 4.3)
        )
        
        // Add items to collection
        items.forEach { item ->
            collection.put(item.id, item)
        }
        
        // Create SnapshotStateList
        val snapshotList = collection.asSnapshotStateList(this)
        
        // Give time for initialization
        delay(50.milliseconds)
        
        // Verify initial state
        assertEquals(5, snapshotList.size)
        
        // Items should be sorted by key (id)
        assertEquals(1, snapshotList[0].key)
        assertEquals(2, snapshotList[1].key)
        assertEquals(3, snapshotList[2].key)
        assertEquals(4, snapshotList[3].key)
        assertEquals(5, snapshotList[4].key)
        
        // Verify values
        assertEquals("Apple", snapshotList[0].value.name)
        assertEquals("Banana", snapshotList[1].value.name)
    }

    @Test
    fun testEs_asSnapshotStateList_reactiveUpdates() = runTest {
        val collection = es<Int, ComposeTestItem>(keySelector = { it.id })
        
        // Create SnapshotStateList first
        val snapshotList = collection.asSnapshotStateList(this)
        delay(10.milliseconds)
        
        // Initially empty
        assertEquals(0, snapshotList.size)
        
        // Add item
        val item1 = ComposeTestItem(1, "Apple", "Fruit", 4.5)
        collection.put(1, item1)
        delay(10.milliseconds)
        
        assertEquals(1, snapshotList.size)
        assertEquals(item1, snapshotList[0].value)
        
        // Add another item
        val item2 = ComposeTestItem(2, "Banana", "Fruit", 4.2)
        collection.put(2, item2)
        delay(10.milliseconds)
        
        assertEquals(2, snapshotList.size)
        assertEquals(item1, snapshotList[0].value) // Should maintain order
        assertEquals(item2, snapshotList[1].value)
        
        // Update existing item
        val updatedItem1 = ComposeTestItem(1, "Green Apple", "Fruit", 4.7)
        collection.put(1, updatedItem1)
        delay(10.milliseconds)
        
        assertEquals(2, snapshotList.size)
        assertEquals(updatedItem1, snapshotList[0].value)
        assertEquals("Green Apple", snapshotList[0].value.name)
        
        // Remove item
        collection.remove(2)
        delay(10.milliseconds)
        
        assertEquals(1, snapshotList.size)
        assertEquals(updatedItem1, snapshotList[0].value)
        
        // Clear all
        collection.clear()
        delay(10.milliseconds)
        
        assertEquals(0, snapshotList.size)
    }

    @Test
    fun testEs_asSnapshotStateList_withCustomComparator() = runTest {
        // Create collection with reverse order comparator
        val reverseComparator = Comparator<Int> { a, b -> b.compareTo(a) }
        val collection = es<Int, ComposeTestItem>(
            keySelector = { it.id },
            comparator = reverseComparator
        )
        
        val items = listOf(
            ComposeTestItem(1, "Item1", "Category", 1.0),
            ComposeTestItem(3, "Item3", "Category", 3.0),
            ComposeTestItem(2, "Item2", "Category", 2.0),
            ComposeTestItem(5, "Item5", "Category", 5.0),
            ComposeTestItem(4, "Item4", "Category", 4.0)
        )
        
        items.forEach { item ->
            collection.put(item.id, item)
        }
        
        // Create SnapshotStateList
        val snapshotList = collection.asSnapshotStateList(this)
        delay(50.milliseconds)
        
        assertEquals(5, snapshotList.size)
        
        // Should be in reverse order (5, 4, 3, 2, 1)
        assertEquals(5, snapshotList[0].key)
        assertEquals(4, snapshotList[1].key)
        assertEquals(3, snapshotList[2].key)
        assertEquals(2, snapshotList[3].key)
        assertEquals(1, snapshotList[4].key)
    }

    @Test
    fun testEs_asSnapshotStateList_withFilter() = runTest {
        val collection = es<Int, ComposeTestItem>(keySelector = { it.id })
        
        val items = listOf(
            ComposeTestItem(1, "Apple", "Fruit", 4.5),
            ComposeTestItem(2, "Banana", "Fruit", 4.2),
            ComposeTestItem(3, "Carrot", "Vegetable", 3.8),
            ComposeTestItem(4, "Broccoli", "Vegetable", 4.0),
            ComposeTestItem(5, "Orange", "Fruit", 4.3)
        )
        
        items.forEach { item ->
            collection.put(item.id, item)
        }
        
        // Create filtered SnapshotStateList (only fruits)
        val snapshotList = collection.asSnapshotStateList(
            scope = this,
            filter = { entry -> entry.value.category == "Fruit" }
        )
        
        delay(50.milliseconds)
        
        // Should only contain 3 fruit items
        assertEquals(3, snapshotList.size)
        snapshotList.forEach { entry ->
            assertEquals("Fruit", entry.value.category)
        }
        
        // Add a new vegetable - should not appear in filtered list
        collection.put(6, ComposeTestItem(6, "Lettuce", "Vegetable", 3.5))
        delay(10.milliseconds)
        
        assertEquals(3, snapshotList.size) // Still only fruits
        
        // Add a new fruit - should appear in filtered list
        collection.put(7, ComposeTestItem(7, "Grape", "Fruit", 4.1))
        delay(10.milliseconds)
        
        assertEquals(4, snapshotList.size) // Now includes the new fruit
        assertTrue(snapshotList.any { it.value.name == "Grape" })
    }

    @Test
    fun testEs_asSnapshotStateList_withCustomEntryComparator() = runTest {
        val collection = es<Int, ComposeTestItem>(keySelector = { it.id })
        
        val items = listOf(
            ComposeTestItem(1, "Apple", "Fruit", 4.5),
            ComposeTestItem(2, "Banana", "Fruit", 4.2),
            ComposeTestItem(3, "Carrot", "Vegetable", 3.8),
            ComposeTestItem(4, "Broccoli", "Vegetable", 4.0)
        )
        
        items.forEach { item ->
            collection.put(item.id, item)
        }
        
        // Create SnapshotStateList sorted by score (descending)
        val scoreComparator = Comparator<Map.Entry<Int, ComposeTestItem>> { a, b ->
            b.value.score.compareTo(a.value.score)
        }
        
        val snapshotList = collection.asSnapshotStateList(
            scope = this,
            entryComparator = scoreComparator
        )
        
        delay(50.milliseconds)
        
        assertEquals(4, snapshotList.size)
        
        // Should be sorted by score descending
        assertEquals(4.5, snapshotList[0].value.score) // Apple
        assertEquals(4.2, snapshotList[1].value.score) // Banana
        assertEquals(4.0, snapshotList[2].value.score) // Broccoli
        assertEquals(3.8, snapshotList[3].value.score) // Carrot
    }

    // ==================== Secondary Key SnapshotStateList Tests ====================

    @Test
    fun testEs_asSnapshotStateListBySecondaryKey_comparable() = runTest {
        val collection = es<Int, ComposeTestItem>(
            keySelector = { it.id },
            secondaryKeys = {
                key("category") { it.category }
                key("scoreRange") { (it.score * 10).toInt() / 10 } // Round to 1 decimal
            }
        )
        
        val items = listOf(
            ComposeTestItem(1, "Apple", "Fruit", 4.5),
            ComposeTestItem(2, "Banana", "Fruit", 4.2),
            ComposeTestItem(3, "Orange", "Fruit", 4.3),
            ComposeTestItem(4, "Carrot", "Vegetable", 3.8),
            ComposeTestItem(5, "Broccoli", "Vegetable", 4.0)
        )
        
        items.forEach { item ->
            collection.put(item.id, item)
        }
        
        // Create SnapshotStateList filtered by category
        val fruitList = collection.asSnapshotStateListBySecondaryKey<String>(
            scope = this,
            secondaryKeyName = "category",
            secondaryKeyValue = "Fruit"
        )
        
        delay(50.milliseconds)
        
        // Should contain only fruits
        assertEquals(3, fruitList.size)
        fruitList.forEach { entry ->
            assertEquals("Fruit", entry.value.category)
        }
        
        // Test vegetable list
        val vegetableList = collection.asSnapshotStateListBySecondaryKey<String>(
            scope = this,
            secondaryKeyName = "category",
            secondaryKeyValue = "Vegetable"
        )
        
        delay(50.milliseconds)
        
        assertEquals(2, vegetableList.size)
        vegetableList.forEach { entry ->
            assertEquals("Vegetable", entry.value.category)
        }
        
        // Add new item and verify lists update
        collection.put(6, ComposeTestItem(6, "Grape", "Fruit", 4.4))
        delay(10.milliseconds)
        
        assertEquals(4, fruitList.size) // Fruit list grows
        assertEquals(2, vegetableList.size) // Vegetable list unchanged
    }

    @Test
    fun testEs_asSnapshotStateListBySecondaryKey_withComparator() = runTest {
        val collection = es<Int, ComposeTestItem>(
            keySelector = { it.id },
            secondaryKeys = {
                key("category") { it.category }
            }
        )
        
        val items = listOf(
            ComposeTestItem(1, "Apple", "Fruit", 4.5),
            ComposeTestItem(2, "Banana", "Fruit", 4.2),
            ComposeTestItem(3, "Orange", "Fruit", 4.3),
            ComposeTestItem(4, "Carrot", "Vegetable", 3.8)
        )
        
        items.forEach { item ->
            collection.put(item.id, item)
        }
        
        // Custom comparator for non-Comparable secondary keys
        val categoryComparator = Comparator<String> { a, b -> a.compareTo(b) }
        
        val snapshotList = collection.asSnapshotStateListBySecondaryKey(
            scope = this,
            secondaryKeyName = "category",
            secondaryKeyComparator = categoryComparator,
            secondaryKeyValue = "Fruit"
        )
        
        delay(50.milliseconds)
        
        assertEquals(3, snapshotList.size)
        snapshotList.forEach { entry ->
            assertEquals("Fruit", entry.value.category)
        }
    }

    @Test
    fun testEs_asSnapshotStateListBySecondaryKey_allValues() = runTest {
        val collection = es<Int, ComposeTestItem>(
            keySelector = { it.id },
            secondaryKeys = {
                key("category") { it.category }
            }
        )
        
        val items = listOf(
            ComposeTestItem(1, "Apple", "Fruit", 4.5),
            ComposeTestItem(2, "Banana", "Fruit", 4.2),
            ComposeTestItem(3, "Carrot", "Vegetable", 3.8),
            ComposeTestItem(4, "Broccoli", "Vegetable", 4.0)
        )
        
        items.forEach { item ->
            collection.put(item.id, item)
        }
        
        // Create list with all values (no specific secondary key value filter)
        val allItemsList = collection.asSnapshotStateListBySecondaryKey<String>(
            scope = this,
            secondaryKeyName = "category",
            secondaryKeyValue = null // All values
        )
        
        delay(50.milliseconds)
        
        // Should contain all items
        assertEquals(4, allItemsList.size)
        
        // Should be sorted by secondary key first (category), then by primary key
        // Categories: "Fruit" comes before "Vegetable"
        val categories = allItemsList.map { it.value.category }
        val fruitsFirst = categories.takeWhile { it == "Fruit" }
        val vegetablesAfter = categories.dropWhile { it == "Fruit" }
        
        assertTrue(fruitsFirst.isNotEmpty())
        assertTrue(vegetablesAfter.all { it == "Vegetable" })
    }

    // ==================== SnapshotStateList Update Behavior Tests ====================

    @Test
    fun testEs_asSnapshotStateList_updateBehavior() = runTest {
        val collection = es<Int, ComposeTestItem>(keySelector = { it.id })
        
        val snapshotList = collection.asSnapshotStateList(this)
        delay(10.milliseconds)
        
        // Add items with specific order
        collection.put(3, ComposeTestItem(3, "Item3", "Category", 3.0))
        collection.put(1, ComposeTestItem(1, "Item1", "Category", 1.0))
        collection.put(2, ComposeTestItem(2, "Item2", "Category", 2.0))
        delay(10.milliseconds)
        
        assertEquals(3, snapshotList.size)
        assertEquals(1, snapshotList[0].key) // Should maintain sorted order
        assertEquals(2, snapshotList[1].key)
        assertEquals(3, snapshotList[2].key)
        
        // Update middle item
        collection.put(2, ComposeTestItem(2, "Updated Item2", "Category", 2.5))
        delay(10.milliseconds)
        
        assertEquals(3, snapshotList.size)
        assertEquals("Updated Item2", snapshotList[1].value.name)
        assertEquals(2.5, snapshotList[1].value.score)
        
        // Insert item that changes order
        collection.put(0, ComposeTestItem(0, "Item0", "Category", 0.0))
        delay(10.milliseconds)
        
        assertEquals(4, snapshotList.size)
        assertEquals(0, snapshotList[0].key) // New first item
        assertEquals(1, snapshotList[1].key)
        assertEquals(2, snapshotList[2].key)
        assertEquals(3, snapshotList[3].key)
    }

    @Test
    fun testEs_asSnapshotStateList_bulkOperations() = runTest {
        val collection = es<Int, ComposeTestItem>(keySelector = { it.id })
        
        val snapshotList = collection.asSnapshotStateList(this)
        delay(10.milliseconds)
        
        // Perform bulk putAll operation
        val bulkItems = (1..100).associate { id ->
            id to ComposeTestItem(id, "Item$id", if (id % 2 == 0) "Even" else "Odd", id.toDouble())
        }
        
        collection.putAll(bulkItems)
        delay(100.milliseconds)
        
        assertEquals(100, snapshotList.size)
        
        // Verify ordering
        for (i in 0 until 100) {
            assertEquals(i + 1, snapshotList[i].key)
        }
        
        // Perform bulk clear
        collection.clear()
        delay(50.milliseconds)
        
        assertEquals(0, snapshotList.size)
    }

    @Test
    fun testEs_asSnapshotStateList_concurrentModifications() = runTest {
        val collection = es<Int, ComposeTestItem>(keySelector = { it.id })
        
        val snapshotList = collection.asSnapshotStateList(this)
        delay(10.milliseconds)
        
        // Perform concurrent modifications
        val jobs = (1..50).map { index ->
            launch {
                repeat(10) { i ->
                    val id = index * 10 + i
                    collection.put(id, ComposeTestItem(id, "Item$id", "Category", id.toDouble()))
                    delay(1.milliseconds)
                }
            }
        }
        
        jobs.joinAll()
        delay(200.milliseconds)
        
        assertEquals(500, snapshotList.size)
        
        // Verify all items are present and ordered
        for (i in 0 until snapshotList.size - 1) {
            assertTrue(snapshotList[i].key <= snapshotList[i + 1].key)
        }
    }

    // ==================== Performance and Edge Cases ====================

    @Test
    fun testEs_asSnapshotStateList_largeDataset() = runTest {
        val collection = es<Int, ComposeTestItem>(keySelector = { it.id })
        
        // Add large dataset
        val largeDataset = (1..1000).map { id ->
            ComposeTestItem(id, "Item$id", "Category${id % 10}", (id % 50).toDouble())
        }
        
        largeDataset.forEach { item ->
            collection.put(item.id, item)
        }
        
        val snapshotList = collection.asSnapshotStateList(this)
        delay(200.milliseconds)
        
        assertEquals(1000, snapshotList.size)
        
        // Test navigation through large list
        assertEquals(1, snapshotList.first().key)
        assertEquals(1000, snapshotList.last().key)
        assertEquals(500, snapshotList[499].key)
        
        // Test filtering on large dataset
        val filteredList = collection.asSnapshotStateList(
            scope = this,
            filter = { entry -> entry.value.score > 25.0 }
        )
        
        delay(100.milliseconds)
        
        assertTrue(filteredList.size < snapshotList.size)
        filteredList.forEach { entry ->
            assertTrue(entry.value.score > 25.0)
        }
    }

    @Test
    fun testEs_asSnapshotStateList_memoryEfficiency() = runTest {
        val collection = es<Int, ComposeTestItem>(keySelector = { it.id })
        
        // Create multiple snapshot lists from same collection
        val list1 = collection.asSnapshotStateList(this)
        val list2 = collection.asSnapshotStateList(this, filter = { it.value.score > 3.0 })
        val list3 = collection.asSnapshotStateList(
            scope = this,
            entryComparator = Comparator { a, b -> a.value.name.compareTo(b.value.name) }
        )
        
        delay(10.milliseconds)
        
        // Add data
        repeat(100) { id ->
            collection.put(id, ComposeTestItem(id, "Item$id", "Category", (id % 10).toDouble()))
        }
        
        delay(100.milliseconds)
        
        // All lists should reflect the changes
        assertEquals(100, list1.size)
        assertTrue(list2.size < 100) // Filtered list
        assertEquals(100, list3.size)
        
        // Lists should be independent views
        assertNotSame(list1, list2)
        assertNotSame(list1, list3)
        assertNotSame(list2, list3)
    }
}