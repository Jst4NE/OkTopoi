package jst.oktopoi

import kotlin.test.Test
import kotlin.test.assertEquals

class DebugTest {
    
    @Test
    fun testSimpleSubMap() {
        val collection = es<Int, String>(keySelector = { it.toInt() })
        
        for (i in 1..20) {
            collection.put(i, i.toString())
        }
        
        println("Collection size: ${collection.size}")
        println("Keys: ${collection.keys.toList()}")
        
        // Test case from failing test: subMap(5, true, 15, false)
        val subMap = collection.subMap(5, true, 15, false)
        println("SubMap size: ${subMap.size}")
        println("SubMap keys: ${subMap.keys.toList()}")
        println("SubMap expected: 5,6,7,8,9,10,11,12,13,14 (10 elements)")
        
        // Test the boundary conditions
        println("Contains 5: ${subMap.containsKey(5)}")  // Should be true
        println("Contains 14: ${subMap.containsKey(14)}") // Should be true
        println("Contains 15: ${subMap.containsKey(15)}") // Should be false
        
        // Test exclusive bounds: subMap(5, false, 15, false) 
        val exclusiveSubMap = collection.subMap(5, false, 15, false)
        println("Exclusive SubMap size: ${exclusiveSubMap.size}")
        println("Exclusive SubMap keys: ${exclusiveSubMap.keys.toList()}")
        println("Exclusive expected: 6,7,8,9,10,11,12,13,14 (9 elements)")
        
        // Test boundary checks for exclusive
        println("Exclusive contains 5: ${exclusiveSubMap.containsKey(5)}")  // Should be false
        println("Exclusive contains 6: ${exclusiveSubMap.containsKey(6)}")  // Should be true
        println("Exclusive contains 14: ${exclusiveSubMap.containsKey(14)}") // Should be true
        println("Exclusive contains 15: ${exclusiveSubMap.containsKey(15)}") // Should be false
        
        // Test mixed bounds: subMap(5, true, 15, true)
        val mixedSubMap = collection.subMap(5, true, 15, true)
        println("Mixed SubMap size: ${mixedSubMap.size}")
        println("Mixed SubMap keys: ${mixedSubMap.keys.toList()}")
        println("Mixed expected: 5,6,7,8,9,10,11,12,13,14,15 (11 elements)")
        
        // Also test the raw TreeMap to see if the issue is in Es or TreeMap
        val rawMap = TreeMap<Int, String>()
        for (i in 1..20) {
            rawMap.put(i, i.toString())
        }
        val rawExclusiveSubMap = rawMap.subMap(5, false, 15, false)
        println("Raw TreeMap exclusive SubMap size: ${rawExclusiveSubMap.size}")
        println("Raw TreeMap exclusive SubMap keys: ${rawExclusiveSubMap.keys.toList()}")
    }
    
    @Test 
    fun testReplayCache() {
        val state = e<String>()
        state.value = "test"
        
        println("State value: ${state.value}")
        println("Replay cache: ${state.replayCache}")
        
        try {
            state.resetReplayCache()
            println("Reset succeeded")
        } catch (e: Exception) {
            println("Reset failed: ${e.message}")
        }
    }
    
    @Test
    fun testSecondaryKeys() {
        data class TestItem(val id: Int, val name: String, val category: String, val value: Double, val tags: List<String>)
        
        val map = TreeMap<Int, TestItem>(secondaryKeys = {
            key("category") { item -> item.category }
            key("valueRange") { item -> (item.value / 100).toInt() * 100 }
            key("nameLength") { item -> item.name.length }
            key("hasTag") { item -> item.tags.firstOrNull() }
            key("expensiveCategory") { item -> 
                if (item.value > 100.0) item.category else null 
            }
            key("primaryTag") { item ->
                item.tags.find { it.length > 5 }
            }
        })
        
        val items = listOf(
            TestItem(1, "Apple", "Fruit", 150.0, listOf("red", "sweet")),
            TestItem(2, "Banana", "Fruit", 80.0, listOf("yellow", "sweet")),
            TestItem(3, "Carrot", "Vegetable", 50.0, listOf("orange", "healthy")),
            TestItem(4, "Spinach", "Vegetable", 25.0, listOf("green", "healthy")),
            TestItem(5, "Chocolate", "Dessert", 300.0, listOf("brown", "sweet")),
            TestItem(6, "Ice Cream", "Dessert", 250.0, listOf("cold", "sweet"))
        )
        
        items.forEach { map.put(it.id, it) }
        
        println("Map size: ${map.size}")
        
        // Test category grouping
        val fruits = map.getBySecondaryKey("category", "Fruit")
        println("Fruits count: ${fruits.size}, expected: 2")
        println("Fruits: ${fruits.map { it.name }}")
        
        // Test value ranges
        val lowValue = map.getBySecondaryKey("valueRange", 0) // 0-99
        println("Low value count: ${lowValue.size}, expected: 2") // Banana(80), Carrot(50), Spinach(25)
        
        val midValue = map.getBySecondaryKey("valueRange", 100) // 100-199  
        println("Mid value count: ${midValue.size}, expected: 1") // Apple(150)
        
        val highValue = map.getBySecondaryKey("valueRange", 200) // 200-299
        println("High value count: ${highValue.size}, expected: 2") // Chocolate(300), Ice Cream(250)
        
        // Test null secondary keys
        val expensiveFruits = map.getBySecondaryKey("expensiveCategory", "Fruit")
        println("Expensive fruits count: ${expensiveFruits.size}, expected: 1") // Apple only
        
        val expensiveVegetables = map.getBySecondaryKey("expensiveCategory", "Vegetable")
        println("Expensive vegetables count: ${expensiveVegetables.size}, expected: 0") // None
        
        val nullExpensive = map.getBySecondaryKey("expensiveCategory", null)
        println("Null expensive count: ${nullExpensive.size}, expected: 3") // Banana, Carrot, Spinach
        
        // Test primary tag (long tags)
        val orangeTag = map.getBySecondaryKey("primaryTag", "orange")
        println("Orange tag count: ${orangeTag.size}, expected: 1") // Carrot
        
        val healthyTag = map.getBySecondaryKey("primaryTag", "healthy")
        println("Healthy tag count: ${healthyTag.size}, expected: 2") // Carrot, Spinach
        
        val nullPrimaryTag = map.getBySecondaryKey("primaryTag", null)
        println("Null primary tag count: ${nullPrimaryTag.size}, expected: 2") // Apple, Banana (no long tags)
    }
}