package jst.oktopoi

import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
data class NullTestItem(
    val id: Int,
    val name: String?,
    val category: String?,
    val optionalTag: String?,
    val value: Double
)

/**
 * Comprehensive test suite for secondary key null value handling
 */
class SecondaryKeysNullHandlingTest {

    @Test
    fun testSecondaryKeysWithNullValues_comprehensive() {
        val map = TreeMap<Int, NullTestItem>(secondaryKeys = {
            // Key that can be null for some items
            key("category") { item -> item.category }
            
            // Key that evaluates to null based on conditions
            key("expensiveCategory") { item -> 
                if (item.value > 100.0) item.category else null 
            }
            
            // Key that finds optional data
            key("nameFirstChar") { item -> item.name?.firstOrNull() }
            
            // Key that can be explicitly null
            key("optionalTag") { item -> item.optionalTag }
            
            // Complex conditional key
            key("categoryOrDefault") { item -> 
                item.category ?: "uncategorized"
            }
        })
        
        val items = listOf(
            NullTestItem(1, "Apple", "Fruit", "sweet", 150.0),      // Normal item
            NullTestItem(2, "Banana", null, null, 80.0),           // Null category
            NullTestItem(3, null, "Vegetable", "healthy", 50.0),   // Null name
            NullTestItem(4, "Fish", null, null, 200.0),            // Null category, expensive
            NullTestItem(5, "", "Empty", null, 25.0)               // Empty string name
        )
        
        items.forEach { map.put(it.id, it) }
        
        assertEquals(5, map.size)
        
        // Test 1: Category key with null values
        val fruitItems = map.getBySecondaryKey("category", "Fruit")
        assertEquals(1, fruitItems.size)
        assertEquals("Apple", fruitItems[0].name)
        
        val vegetableItems = map.getBySecondaryKey("category", "Vegetable")
        assertEquals(1, vegetableItems.size)
        assertEquals(3, vegetableItems[0].id)
        
        val nullCategoryItems = map.getBySecondaryKey("category", null)
        assertEquals(2, nullCategoryItems.size) // Banana and Fish
        val nullCategoryIds = nullCategoryItems.map { it.id }.toSet()
        assertEquals(setOf(2, 4), nullCategoryIds)
        
        // Test 2: Expensive category (conditional null)
        val expensiveFruit = map.getBySecondaryKey("expensiveCategory", "Fruit")
        assertEquals(1, expensiveFruit.size)
        assertEquals("Apple", expensiveFruit[0].name)
        
        val expensiveNull = map.getBySecondaryKey("expensiveCategory", null)
        assertEquals(4, expensiveNull.size) // Banana(80), item3(50), Fish(expensive but null category), item5(25)
        
        // Test 3: Name first character (null handling)
        val aItems = map.getBySecondaryKey("nameFirstChar", 'A')
        assertEquals(1, aItems.size)
        assertEquals("Apple", aItems[0].name)
        
        val nullNameItems = map.getBySecondaryKey("nameFirstChar", null)
        assertEquals(2, nullNameItems.size) // item3 (null name), item5 (empty string -> first char is null)
        
        // Test 4: Optional tag
        val sweetItems = map.getBySecondaryKey("optionalTag", "sweet")
        assertEquals(1, sweetItems.size)
        assertEquals("Apple", sweetItems[0].name)
        
        val nullTagItems = map.getBySecondaryKey("optionalTag", null)
        assertEquals(3, nullTagItems.size) // Banana, Fish, item5
        
        // Test 5: Category with default
        val uncategorizedItems = map.getBySecondaryKey("categoryOrDefault", "uncategorized")
        assertEquals(2, uncategorizedItems.size) // Banana and Fish (null categories)
        
        val fruitItemsDefault = map.getBySecondaryKey("categoryOrDefault", "Fruit")
        assertEquals(1, fruitItemsDefault.size)
        assertEquals("Apple", fruitItemsDefault[0].name)
    }
    
    @Test
    fun testSecondaryKeysNullTransitions() {
        val map = TreeMap<Int, NullTestItem>(secondaryKeys = {
            key("category") { item -> item.category }
            key("optionalTag") { item -> item.optionalTag }
        })
        
        // Start with item that has values
        val item1 = NullTestItem(1, "Apple", "Fruit", "sweet", 150.0)
        map.put(1, item1)
        
        // Verify initial state
        assertEquals(1, map.getBySecondaryKey("category", "Fruit").size)
        assertEquals(1, map.getBySecondaryKey("optionalTag", "sweet").size)
        assertEquals(0, map.getBySecondaryKey("category", null).size)
        assertEquals(0, map.getBySecondaryKey("optionalTag", null).size)
        
        // Update to null values
        val item1Updated = NullTestItem(1, "Apple", null, null, 150.0)
        map.put(1, item1Updated)
        
        // Verify transition to null
        assertEquals(0, map.getBySecondaryKey("category", "Fruit").size)
        assertEquals(0, map.getBySecondaryKey("optionalTag", "sweet").size)
        assertEquals(1, map.getBySecondaryKey("category", null).size)
        assertEquals(1, map.getBySecondaryKey("optionalTag", null).size)
        
        // Update back to non-null values
        val item1Final = NullTestItem(1, "Apple", "Vegetable", "healthy", 150.0)
        map.put(1, item1Final)
        
        // Verify transition from null
        assertEquals(0, map.getBySecondaryKey("category", "Fruit").size)
        assertEquals(1, map.getBySecondaryKey("category", "Vegetable").size)
        assertEquals(0, map.getBySecondaryKey("category", null).size)
        assertEquals(0, map.getBySecondaryKey("optionalTag", "sweet").size)
        assertEquals(1, map.getBySecondaryKey("optionalTag", "healthy").size)
        assertEquals(0, map.getBySecondaryKey("optionalTag", null).size)
    }
    
    @Test
    fun testSecondaryKeysRemovalWithNulls() {
        val map = TreeMap<Int, NullTestItem>(secondaryKeys = {
            key("category") { item -> item.category }
            key("optionalTag") { item -> item.optionalTag }
        })
        
        val items = listOf(
            NullTestItem(1, "Apple", "Fruit", "sweet", 150.0),
            NullTestItem(2, "Banana", null, null, 80.0),
            NullTestItem(3, "Carrot", null, "healthy", 50.0)
        )
        
        items.forEach { map.put(it.id, it) }
        
        // Verify initial state
        assertEquals(1, map.getBySecondaryKey("category", "Fruit").size)
        assertEquals(2, map.getBySecondaryKey("category", null).size)
        assertEquals(1, map.getBySecondaryKey("optionalTag", "sweet").size)
        assertEquals(1, map.getBySecondaryKey("optionalTag", "healthy").size)
        assertEquals(1, map.getBySecondaryKey("optionalTag", null).size)
        
        // Remove item with null category
        map.remove(2)
        
        assertEquals(1, map.getBySecondaryKey("category", "Fruit").size)
        assertEquals(1, map.getBySecondaryKey("category", null).size) // Only Carrot left
        assertEquals(1, map.getBySecondaryKey("optionalTag", "sweet").size)
        assertEquals(1, map.getBySecondaryKey("optionalTag", "healthy").size)
        assertEquals(0, map.getBySecondaryKey("optionalTag", null).size) // Banana removed
        
        // Remove item with non-null category
        map.remove(1)
        
        assertEquals(0, map.getBySecondaryKey("category", "Fruit").size)
        assertEquals(1, map.getBySecondaryKey("category", null).size)
        assertEquals(0, map.getBySecondaryKey("optionalTag", "sweet").size)
        assertEquals(1, map.getBySecondaryKey("optionalTag", "healthy").size)
        assertEquals(0, map.getBySecondaryKey("optionalTag", null).size)
    }
    
    @Test
    fun testSecondaryKeysEdgeCases() {
        val map = TreeMap<Int, NullTestItem>(secondaryKeys = {
            // Test various edge cases
            key("emptyStringCategory") { item -> 
                if (item.category?.isEmpty() == true) "" else item.category 
            }
            key("nullOrEmptyName") { item ->
                when {
                    item.name == null -> "null"
                    item.name.isEmpty() -> "empty"
                    else -> item.name
                }
            }
        })
        
        val items = listOf(
            NullTestItem(1, null, null, null, 100.0),        // All nulls
            NullTestItem(2, "", "", null, 100.0),            // Empty strings
            NullTestItem(3, "Name", "Category", null, 100.0) // Normal values
        )
        
        items.forEach { map.put(it.id, it) }
        
        // Test empty string handling
        assertEquals(1, map.getBySecondaryKey("emptyStringCategory", "").size)
        assertEquals(1, map.getBySecondaryKey("emptyStringCategory", null).size) // item1 only
        assertEquals(1, map.getBySecondaryKey("emptyStringCategory", "Category").size) // item3 only
        
        // Test null vs empty distinction
        assertEquals(1, map.getBySecondaryKey("nullOrEmptyName", "null").size)
        assertEquals(1, map.getBySecondaryKey("nullOrEmptyName", "empty").size)
        assertEquals(1, map.getBySecondaryKey("nullOrEmptyName", "Name").size)
    }
    
    @Test
    fun testSecondaryKeysPerformanceWithNulls() {
        val map = TreeMap<Int, NullTestItem>(secondaryKeys = {
            key("category") { item -> item.category }
            key("nullableComputed") { item -> 
                if (item.id % 3 == 0) null else "computed_${item.id % 5}"
            }
        })
        
        // Add many items with varying null patterns
        for (i in 1..1000) {
            val item = NullTestItem(
                id = i,
                name = "Item$i",
                category = if (i % 4 == 0) null else "Category${i % 3}",
                optionalTag = if (i % 7 == 0) null else "tag${i % 2}",
                value = i.toDouble()
            )
            map.put(i, item)
        }
        
        assertEquals(1000, map.size)
        
        // Test null category lookup (should be every 4th item)
        val nullCategoryItems = map.getBySecondaryKey("category", null)
        assertEquals(250, nullCategoryItems.size) // 1000 / 4
        
        // Test specific category
        val category0Items = map.getBySecondaryKey("category", "Category0")
        assertEquals(250, category0Items.size) // Every 4th of remaining 750 items
        
        // Test computed null values (every 3rd item)
        val nullComputedItems = map.getBySecondaryKey("nullableComputed", null)
        assertEquals(333, nullComputedItems.size) // 1000 / 3 = 333 exactly
    }
}