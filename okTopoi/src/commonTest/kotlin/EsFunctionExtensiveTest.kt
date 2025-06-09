package jst.oktopoi

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
data class CollectionUser(val id: Int, val name: String, val email: String, val department: String, val age: Int)

@Serializable  
data class Product(val id: String, val name: String, val category: String, val price: Double, val inStock: Boolean)

@Serializable
data class Order(val orderId: Int, val customerId: Int, val productId: String, val quantity: Int, val total: Double)

class EsFunctionExtensiveTest {

    @BeforeTest
    fun setup() {
        initDefaultIO(Path("/tmp/oktopoi-es-test"), SystemFileSystem)
    }

    // ==================== Basic es() Constructor Tests ====================

    @Test
    fun testEs_defaultConstructor() {
        val collection = es<String, CollectionUser>()
        
        assertTrue(collection.isEmpty())
        assertEquals(0, collection.size)
        assertTrue(collection.keys.isEmpty())
        assertTrue(collection.values.isEmpty())
        assertTrue(collection.entries.isEmpty())
    }

    @Test
    fun testEs_withKeySelector() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        assertTrue(collection.isEmpty())
        assertEquals(0, collection.size)
    }

    @Test
    fun testEs_withComparator() {
        val reverseComparator = Comparator<String> { a, b -> b.compareTo(a) }
        val collection = es<String, CollectionUser>(
            keySelector = { item -> item.name },
            comparator = reverseComparator
        )
        
        assertTrue(collection.isEmpty())
        assertEquals(0, collection.size)
    }

    @Test
    fun testEs_withSecondaryKeys() {
        val collection = es<String, CollectionUser>(
            keySelector = { item -> item.name },
            secondaryKeys = {
                key("department") { item -> item.department }
                key("ageGroup") { item -> item.age / 10 }
                key("emailDomain") { item -> item.email.substringAfter("@") }
            }
        )
        
        assertTrue(collection.isEmpty())
        assertEquals(0, collection.size)
    }

    // ==================== Basic Operations ====================

    @Test
    fun testEs_putAndGet() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32)
        
        assertNull(collection.put("alice", user1))
        assertEquals(1, collection.size)
        assertEquals(user1, collection["alice"])
        assertEquals(user1, collection.get("alice"))
        
        assertNull(collection.put("bob", user2))
        assertEquals(2, collection.size)
        assertEquals(user2, collection["bob"])
    }

    @Test
    fun testEs_putUpdate() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user1Updated = CollectionUser(1, "Alice", "alice.updated@example.com", "Engineering", 29)
        
        collection.put("alice", user1)
        assertEquals(user1, collection.put("alice", user1Updated))
        assertEquals(1, collection.size)
        assertEquals(user1Updated, collection["alice"])
        assertEquals("alice.updated@example.com", collection["alice"]?.email)
        assertEquals(29, collection["alice"]?.age)
    }

    @Test
    fun testEs_remove() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32)
        
        collection.put("alice", user1)
        collection.put("bob", user2)
        assertEquals(2, collection.size)
        
        assertEquals(user1, collection.remove("alice"))
        assertEquals(1, collection.size)
        assertNull(collection["alice"])
        assertEquals(user2, collection["bob"])
        
        assertNull(collection.remove("nonexistent"))
        assertEquals(1, collection.size)
    }

    @Test
    fun testEs_clear() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        collection.put("alice", CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28))
        collection.put("bob", CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32))
        collection.put("charlie", CollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25))
        
        assertEquals(3, collection.size)
        
        collection.clear()
        assertEquals(0, collection.size)
        assertTrue(collection.isEmpty())
        assertTrue(collection.keys.isEmpty())
        assertTrue(collection.values.isEmpty())
        assertTrue(collection.entries.isEmpty())
    }

    @Test
    fun testEs_containsKey() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        collection.put("alice", user)
        
        assertTrue(collection.containsKey("alice"))
        assertFalse(collection.containsKey("bob"))
        assertFalse(collection.containsKey(""))
        assertFalse(collection.containsKey("nonexistent"))
    }

    @Test
    fun testEs_containsValue() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32)
        val user3 = CollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25)
        
        collection.put("alice", user1)
        collection.put("bob", user2)
        
        assertTrue(collection.containsValue(user1))
        assertTrue(collection.containsValue(user2))
        assertFalse(collection.containsValue(user3))
    }

    // ==================== putAll Operations ====================

    @Test
    fun testEs_putAll() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val users = mapOf(
            "alice" to CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28),
            "bob" to CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32),
            "charlie" to CollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25)
        )
        
        collection.putAll(users)
        
        assertEquals(3, collection.size)
        assertEquals(users["alice"], collection["alice"])
        assertEquals(users["bob"], collection["bob"])
        assertEquals(users["charlie"], collection["charlie"])
    }

    @Test
    fun testEs_putAllWithUpdates() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        // Initial data
        collection.put("alice", CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28))
        collection.put("bob", CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32))
        
        // Update with putAll
        val updates = mapOf(
            "alice" to CollectionUser(1, "Alice", "alice.new@example.com", "Engineering", 29),
            "charlie" to CollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25)
        )
        
        collection.putAll(updates)
        
        assertEquals(3, collection.size)
        assertEquals("alice.new@example.com", collection["alice"]?.email)
        assertEquals(29, collection["alice"]?.age)
        assertEquals(updates["charlie"], collection["charlie"])
    }

    // ==================== Iteration Tests ====================

    @Test
    fun testEs_keyIteration() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        collection.put("alice", CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28))
        collection.put("bob", CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32))
        collection.put("charlie", CollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25))
        
        val keys = collection.keys.toSet()
        assertEquals(setOf("alice", "bob", "charlie"), keys)
        
        val keysList = collection.keys.toList()
        assertEquals(3, keysList.size)
        assertTrue(keysList.contains("alice"))
        assertTrue(keysList.contains("bob"))
        assertTrue(keysList.contains("charlie"))
    }

    @Test
    fun testEs_valueIteration() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32)
        val user3 = CollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25)
        
        collection.put("alice", user1)
        collection.put("bob", user2)
        collection.put("charlie", user3)
        
        val values = collection.values.toList()
        assertEquals(3, values.size)
        assertTrue(values.contains(user1))
        assertTrue(values.contains(user2))
        assertTrue(values.contains(user3))
    }

    @Test
    fun testEs_entryIteration() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32)
        
        collection.put("alice", user1)
        collection.put("bob", user2)
        
        val entries = collection.entries.toList()
        assertEquals(2, entries.size)
        
        entries.forEach { entry ->
            assertEquals(collection[entry.key], entry.value)
        }
        
        val entryMap = entries.associate { entry -> entry.key to entry.value }
        assertEquals(user1, entryMap["alice"])
        assertEquals(user2, entryMap["bob"])
    }

    // ==================== Sorting and Ordering ====================

    @Test
    fun testEs_naturalOrdering() {
        val collection = es<Int, Product>(keySelector = { item -> item.id.toInt() })
        
        collection.put(5, Product("5", "Product5", "CategoryA", 50.0, true))
        collection.put(2, Product("2", "Product2", "CategoryB", 20.0, false))
        collection.put(8, Product("8", "Product8", "CategoryA", 80.0, true))
        collection.put(1, Product("1", "Product1", "CategoryC", 10.0, true))
        collection.put(3, Product("3", "Product3", "CategoryB", 30.0, false))
        
        val keys = collection.keys.toList()
        assertEquals(listOf(1, 2, 3, 5, 8), keys)
    }

    @Test
    fun testEs_customComparator() {
        val reverseComparator = Comparator<String> { a, b -> b.compareTo(a) }
        val collection = es<String, CollectionUser>(
            keySelector = { item -> item.name },
            comparator = reverseComparator
        )
        
        collection.put("alice", CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28))
        collection.put("bob", CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32))
        collection.put("charlie", CollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25))
        
        val keys = collection.keys.toList()
        assertEquals(listOf("charlie", "bob", "alice"), keys)
    }

    @Test
    fun testEs_stringNaturalOrdering() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        collection.put("zebra", CollectionUser(1, "Zebra", "zebra@example.com", "A", 25))
        collection.put("apple", CollectionUser(2, "Apple", "apple@example.com", "B", 30))
        collection.put("banana", CollectionUser(3, "Banana", "banana@example.com", "C", 35))
        collection.put("cherry", CollectionUser(4, "Cherry", "cherry@example.com", "D", 40))
        
        val keys = collection.keys.toList()
        assertEquals(listOf("apple", "banana", "cherry", "zebra"), keys)
    }

    // ==================== Secondary Keys ====================

    @Test
    fun testEs_secondaryKeysBasic() {
        val collection = es<String, CollectionUser>(
            keySelector = { item -> item.name },
            secondaryKeys = {
                key("department") { item -> item.department }
                key("ageGroup") { item -> item.age / 10 }
            }
        )
        
        collection.put("alice", CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28))
        collection.put("bob", CollectionUser(2, "Bob", "bob@example.com", "Engineering", 32))
        collection.put("charlie", CollectionUser(3, "Charlie", "charlie@example.com", "Marketing", 25))
        collection.put("diana", CollectionUser(4, "Diana", "diana@example.com", "Sales", 35))
        
        // Test department secondary key
        val engineeringUsers = collection.getBySecondaryKey("department", "Engineering")
        assertEquals(2, engineeringUsers.size)
        val engineeringNames = engineeringUsers.map { item -> item.name }.toSet()
        assertEquals(setOf("Alice", "Bob"), engineeringNames)
        
        val marketingUsers = collection.getBySecondaryKey("department", "Marketing")
        assertEquals(1, marketingUsers.size)
        assertEquals("Charlie", marketingUsers[0].name)
        
        // Test age group secondary key
        val twentySomethings = collection.getBySecondaryKey("ageGroup", 2) // 20-29
        assertEquals(2, twentySomethings.size)
        val twentyNames = twentySomethings.map { item -> item.name }.toSet()
        assertEquals(setOf("Alice", "Charlie"), twentyNames)
        
        val thirtySomethings = collection.getBySecondaryKey("ageGroup", 3) // 30-39
        assertEquals(2, thirtySomethings.size)
        val thirtyNames = thirtySomethings.map { item -> item.name }.toSet()
        assertEquals(setOf("Bob", "Diana"), thirtyNames)
    }

    @Test
    fun testEs_secondaryKeysComplex() {
        val collection = es<String, Product>(
            keySelector = { item -> item.id },
            secondaryKeys = {
                key("category") { item -> item.category }
                key("inStock") { item -> item.inStock }
                key("priceRange") { item -> (item.price / 50).toInt() * 50 } // Price ranges: 0-49, 50-99, etc.
                key("nameLength") { item -> item.name.length }
            }
        )
        
        collection.put("1", Product("1", "Widget", "Electronics", 25.99, true))
        collection.put("2", Product("2", "Gadget", "Electronics", 75.50, false))
        collection.put("3", Product("3", "Book", "Media", 15.99, true))
        collection.put("4", Product("4", "Magazine", "Media", 5.99, true))
        collection.put("5", Product("5", "Laptop", "Electronics", 999.99, false))
        
        // Test by category
        val electronics = collection.getBySecondaryKey("category", "Electronics")
        assertEquals(3, electronics.size)
        val electronicNames = electronics.map { item -> item.name }.toSet()
        assertEquals(setOf("Widget", "Gadget", "Laptop"), electronicNames)
        
        val media = collection.getBySecondaryKey("category", "Media")
        assertEquals(2, media.size)
        val mediaNames = media.map { item -> item.name }.toSet()
        assertEquals(setOf("Book", "Magazine"), mediaNames)
        
        // Test by stock status
        val inStock = collection.getBySecondaryKey("inStock", true)
        assertEquals(3, inStock.size)
        
        val outOfStock = collection.getBySecondaryKey("inStock", false)
        assertEquals(2, outOfStock.size)
        
        // Test by price range
        val lowPrice = collection.getBySecondaryKey("priceRange", 0) // 0-49
        assertEquals(3, lowPrice.size)
        
        val midPrice = collection.getBySecondaryKey("priceRange", 50) // 50-99
        assertEquals(1, midPrice.size)
        assertEquals("Gadget", midPrice[0].name)
        
        val highPrice = collection.getBySecondaryKey("priceRange", 950) // 950-999
        assertEquals(1, highPrice.size)
        assertEquals("Laptop", highPrice[0].name)
        
        // Test by name length
        val shortNames = collection.getBySecondaryKey("nameLength", 4) // 4 characters
        assertEquals(1, shortNames.size)
        assertEquals("Book", shortNames[0].name)
        
        val mediumNames = collection.getBySecondaryKey("nameLength", 6) // 6 characters
        assertEquals(3, mediumNames.size) // Widget, Gadget, Laptop
    }

    @Test
    fun testEs_secondaryKeysWithNulls() {
        val collection = es<String, CollectionUser>(
            keySelector = { item -> item.name },
            secondaryKeys = {
                key("departmentOrNull") { user -> 
                    if (user.department == "Unknown") null else user.department
                }
            }
        )
        
        collection.put("alice", CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28))
        collection.put("bob", CollectionUser(2, "Bob", "bob@example.com", "Unknown", 32))
        collection.put("charlie", CollectionUser(3, "Charlie", "charlie@example.com", "Unknown", 25))
        
        val engineeringUsers = collection.getBySecondaryKey("departmentOrNull", "Engineering")
        assertEquals(1, engineeringUsers.size)
        assertEquals("Alice", engineeringUsers[0].name)
        
        val nullDepartmentUsers = collection.getBySecondaryKey("departmentOrNull", null)
        assertEquals(2, nullDepartmentUsers.size)
        val nullNames = nullDepartmentUsers.map { item -> item.name }.toSet()
        assertEquals(setOf("Bob", "Charlie"), nullNames)
    }

    @Test
    fun testEs_secondaryKeysUpdate() {
        val collection = es<String, CollectionUser>(
            keySelector = { item -> item.name },
            secondaryKeys = {
                key("department") { item -> item.department }
            }
        )
        
        collection.put("alice", CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28))
        
        val engineeringBefore = collection.getBySecondaryKey("department", "Engineering")
        assertEquals(1, engineeringBefore.size)
        assertEquals("Alice", engineeringBefore[0].name)
        
        val salesBefore = collection.getBySecondaryKey("department", "Sales")
        assertEquals(0, salesBefore.size)
        
        // Update user's department
        collection.put("alice", CollectionUser(1, "Alice", "alice@example.com", "Sales", 28))
        
        val engineeringAfter = collection.getBySecondaryKey("department", "Engineering")
        assertEquals(0, engineeringAfter.size)
        
        val salesAfter = collection.getBySecondaryKey("department", "Sales")
        assertEquals(1, salesAfter.size)
        assertEquals("Alice", salesAfter[0].name)
    }

    // ==================== TreeMap Navigation Operations ====================

    @Test
    fun testEs_navigationOperations() {
        val collection = es<Int, Product>(keySelector = { item -> item.id.toInt() })
        
        collection.put(1, Product("1", "Product1", "A", 10.0, true))
        collection.put(3, Product("3", "Product3", "B", 30.0, true))
        collection.put(5, Product("5", "Product5", "C", 50.0, true))
        collection.put(7, Product("7", "Product7", "D", 70.0, true))
        collection.put(9, Product("9", "Product9", "E", 90.0, true))
        
        // Test first/last
        assertEquals(1, collection.firstKey())
        assertEquals(9, collection.lastKey())
        
        // Test navigation
        assertEquals(3, collection.lowerKey(4)) // Greatest < 4
        assertEquals(3, collection.floorKey(3)) // Greatest <= 3
        assertEquals(5, collection.ceilingKey(4)) // Least >= 4
        assertEquals(5, collection.higherKey(3)) // Least > 3
        
        // Test boundary conditions
        assertNull(collection.lowerKey(1))
        assertNull(collection.higherKey(9))
        
        // Test with exact matches
        assertEquals(5, collection.floorKey(5))
        assertEquals(5, collection.ceilingKey(5))
    }

    @Test
    fun testEs_entryNavigation() {
        val collection = es<Int, Product>(keySelector = { item -> item.id.toInt() })
        
        collection.put(2, Product("2", "Product2", "A", 20.0, true))
        collection.put(5, Product("5", "Product5", "B", 50.0, true))
        collection.put(8, Product("8", "Product8", "C", 80.0, true))
        
        val firstEntry = collection.firstEntry()
        assertNotNull(firstEntry)
        assertEquals(2, firstEntry.key)
        assertEquals("Product2", firstEntry.value.name)
        
        val lastEntry = collection.lastEntry()
        assertNotNull(lastEntry)
        assertEquals(8, lastEntry.key)
        assertEquals("Product8", lastEntry.value.name)
        
        val lowerEntry = collection.lowerEntry(6)
        assertNotNull(lowerEntry)
        assertEquals(5, lowerEntry.key)
        
        val higherEntry = collection.higherEntry(6)
        assertNotNull(higherEntry)
        assertEquals(8, higherEntry.key)
    }

    @Test
    fun testEs_pollOperations() {
        val collection = es<Int, Product>(keySelector = { item -> item.id.toInt() })
        
        collection.put(3, Product("3", "Product3", "A", 30.0, true))
        collection.put(1, Product("1", "Product1", "B", 10.0, true))
        collection.put(5, Product("5", "Product5", "C", 50.0, true))
        
        assertEquals(3, collection.size)
        
        val firstPolled = collection.pollFirstEntry()
        assertNotNull(firstPolled)
        assertEquals(1, firstPolled.key)
        assertEquals("Product1", firstPolled.value.name)
        assertEquals(2, collection.size)
        assertNull(collection[1])
        
        val lastPolled = collection.pollLastEntry()
        assertNotNull(lastPolled)
        assertEquals(5, lastPolled.key)
        assertEquals("Product5", lastPolled.value.name)
        assertEquals(1, collection.size)
        assertNull(collection[5])
        
        // Verify the polled entries are detached
        firstPolled.setValue(Product("1", "Modified", "X", 999.0, false))
        assertEquals("Modified", firstPolled.value.name)
        // Should not affect the original collection
    }

    // ==================== SubMap Operations ====================

    @Test
    fun testEs_subMap() {
        val collection = es<Int, Product>(keySelector = { item -> item.id.toInt() })
        
        for (i in 1..20) {
            collection.put(i, Product(i.toString(), "Product$i", "Category", i * 10.0, true))
        }
        
        val subMap = collection.subMap(5, true, 15, false)
        assertEquals(10, subMap.size) // 5 through 14
        assertTrue(subMap.containsKey(5))
        assertTrue(subMap.containsKey(14))
        assertFalse(subMap.containsKey(4))
        assertFalse(subMap.containsKey(15))
        
        val exclusiveSubMap = collection.subMap(5, false, 15, false)
        assertEquals(9, exclusiveSubMap.size) // 6 through 14
        assertFalse(exclusiveSubMap.containsKey(5))
        assertFalse(exclusiveSubMap.containsKey(15))
        
        val mixedSubMap = collection.subMap(5, true, 15, true)
        assertEquals(11, mixedSubMap.size) // 5 through 15
        assertTrue(mixedSubMap.containsKey(5))
        assertTrue(mixedSubMap.containsKey(15))
    }

    @Test
    fun testEs_headTailMaps() {
        val collection = es<Int, Product>(keySelector = { item -> item.id.toInt() })
        
        for (i in 1..10) {
            collection.put(i, Product(i.toString(), "Product$i", "Category", i * 10.0, true))
        }
        
        val headMap = collection.headMap(6, true)
        assertEquals(6, headMap.size) // 1 through 6
        assertTrue(headMap.containsKey(6))
        assertFalse(headMap.containsKey(7))
        
        val exclusiveHeadMap = collection.headMap(6, false)
        assertEquals(5, exclusiveHeadMap.size) // 1 through 5
        assertFalse(exclusiveHeadMap.containsKey(6))
        
        val tailMap = collection.tailMap(5, true)
        assertEquals(6, tailMap.size) // 5 through 10
        assertTrue(tailMap.containsKey(5))
        assertFalse(tailMap.containsKey(4))
        
        val exclusiveTailMap = collection.tailMap(5, false)
        assertEquals(5, exclusiveTailMap.size) // 6 through 10
        assertFalse(exclusiveTailMap.containsKey(5))
    }

    // ==================== Advanced Map Operations ====================

    @Test
    fun testEs_putIfAbsent() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Alice", "alice.different@example.com", "Sales", 30)
        
        assertNull(collection.putIfAbsent("alice", user1))
        assertEquals(user1, collection["alice"])
        assertEquals(1, collection.size)
        
        assertEquals(user1, collection.putIfAbsent("alice", user2))
        assertEquals(user1, collection["alice"]) // Should not change
        assertEquals(1, collection.size)
    }

    @Test
    fun testEs_replace() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Alice", "alice.new@example.com", "Marketing", 30)
        
        collection.put("alice", user1)
        
        assertEquals(user1, collection.replace("alice", user2))
        assertEquals(user2, collection["alice"])
        
        assertNull(collection.replace("bob", user1))
        assertNull(collection["bob"])
    }

    @Test
    fun testEs_conditionalReplace() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Alice", "alice.new@example.com", "Marketing", 30)
        val user3 = CollectionUser(3, "Bob", "bob@example.com", "Sales", 25)
        
        collection.put("alice", user1)
        
        assertFalse(collection.replace("alice", user3, user2)) // Wrong expected value
        assertEquals(user1, collection["alice"])
        
        assertTrue(collection.replace("alice", user1, user2)) // Correct expected value
        assertEquals(user2, collection["alice"])
    }

    @Test
    fun testEs_conditionalRemove() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32)
        
        collection.put("alice", user1)
        
        assertFalse(collection.remove("alice", user2)) // Wrong value
        assertEquals(user1, collection["alice"])
        assertEquals(1, collection.size)
        
        assertTrue(collection.remove("alice", user1)) // Correct value
        assertNull(collection["alice"])
        assertEquals(0, collection.size)
    }

    // ==================== Compute Operations ====================

    @Test
    fun testEs_compute() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        
        // Compute for non-existent key
        val result1 = collection.compute("alice") { key, value ->
            if (value == null) user1 else value
        }
        assertEquals(user1, result1)
        assertEquals(user1, collection["alice"])
        
        // Compute for existing key
        val result2 = collection.compute("alice") { key, value ->
            value?.copy(age = (value.age + 1))
        }
        assertEquals(29, result2?.age)
        assertEquals(29, collection["alice"]?.age)
        
        // Compute to remove
        val result3 = collection.compute("alice") { key, value -> null }
        assertNull(result3)
        assertNull(collection["alice"])
    }

    @Test
    fun testEs_computeIfAbsent() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32)
        
        val result1 = collection.computeIfAbsent("alice") { user1 }
        assertEquals(user1, result1)
        assertEquals(user1, collection["alice"])
        
        val result2 = collection.computeIfAbsent("alice") { user2 }
        assertEquals(user1, result2) // Should return existing value
        assertEquals(user1, collection["alice"])
    }

    @Test
    fun testEs_computeIfPresent() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        collection.put("alice", user1)
        
        val result1 = collection.computeIfPresent("alice") { key, value ->
            value.copy(age = value.age + 5)
        }
        assertEquals(33, result1?.age)
        assertEquals(33, collection["alice"]?.age)
        
        val result2 = collection.computeIfPresent("bob") { key, value ->
            value.copy(age = value.age + 5)
        }
        assertNull(result2)
        assertNull(collection["bob"])
    }

    @Test
    fun testEs_merge() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Alice", "alice.new@example.com", "Marketing", 30)
        
        val result1 = collection.merge("alice", user1) { oldValue, newValue ->
            newValue
        }
        assertEquals(user1, result1)
        assertEquals(user1, collection["alice"])
        
        val result2 = collection.merge("alice", user2) { oldValue, newValue ->
            oldValue?.copy(
                email = newValue.email,
                department = newValue.department,
                age = newValue.age
            )
        }
        assertEquals("alice.new@example.com", result2?.email)
        assertEquals("Marketing", result2?.department)
        assertEquals(30, result2?.age)
    }

    // ==================== Collection Views ====================

    @Test
    fun testEs_keySetOperations() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        collection.put("alice", CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28))
        collection.put("bob", CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32))
        collection.put("charlie", CollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25))
        
        val keySet = collection.keys
        assertEquals(3, keySet.size)
        assertTrue(keySet.contains("alice"))
        assertFalse(keySet.contains("diana"))
        
        assertTrue(keySet.remove("bob"))
        assertEquals(2, collection.size)
        assertNull(collection["bob"])
        
        assertFalse(keySet.remove("nonexistent"))
        assertEquals(2, collection.size)
    }

    @Test
    fun testEs_valuesOperations() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32)
        val user3 = CollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25)
        
        collection.put("alice", user1)
        collection.put("bob", user2)
        collection.put("charlie", user3)
        
        val values = collection.values
        assertEquals(3, values.size)
        assertTrue(values.contains(user1))
        assertFalse(values.contains(CollectionUser(4, "Diana", "diana@example.com", "HR", 35)))
        
        assertTrue(values.remove(user2))
        assertEquals(2, collection.size)
        assertNull(collection["bob"])
    }

    @Test
    fun testEs_entriesOperations() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32)
        
        collection.put("alice", user1)
        collection.put("bob", user2)
        
        val entries = collection.entries
        assertEquals(2, entries.size)
        
        val entryToRemove = entries.find { item -> item.key == "bob" }
        assertNotNull(entryToRemove)
        assertTrue(entries.remove(entryToRemove))
        assertEquals(1, collection.size)
        assertNull(collection["bob"])
    }

    // ==================== Descending Views ====================

    @Test
    fun testEs_descendingViews() {
        val collection = es<Int, Product>(keySelector = { item -> item.id.toInt() })
        
        for (i in 1..5) {
            collection.put(i, Product(i.toString(), "Product$i", "Category", i * 10.0, true))
        }
        
        val descendingKeys = collection.descendingKeySet()
        val keyList = descendingKeys.toList()
        assertEquals(listOf(5, 4, 3, 2, 1), keyList)
        
        val descendingMap = collection.descendingMap()
        assertEquals(5, descendingMap.size)
        val descendingEntries = descendingMap.entries.toList()
        assertEquals(5, descendingEntries[0].key)
        assertEquals(1, descendingEntries[4].key)
    }

    // ==================== Error Conditions ====================

    @Test
    fun testEs_errorConditions() {
        val collection = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        // Empty collection operations
        assertNull(collection.firstKey())
        assertNull(collection.lastKey())
        assertNull(collection.firstEntry())
        assertNull(collection.lastEntry())
        assertNull(collection.pollFirstEntry())
        assertNull(collection.pollLastEntry())
        
        // Invalid subMap range
        assertFailsWith<IllegalArgumentException> {
            collection.subMap("z", true, "a", false)
        }
    }

    // ==================== Copy Operations ====================

    @Test
    fun testEs_copy() {
        val original = es<String, CollectionUser>(
            keySelector = { item -> item.name },
            secondaryKeys = {
                key("department") { item -> item.department }
            }
        )
        
        original.put("alice", CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28))
        original.put("bob", CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32))
        
        val copy = original.copy()
        
        assertEquals(original.size, copy.size)
        assertEquals(original["alice"], copy["alice"])
        assertEquals(original["bob"], copy["bob"])
        
        copy.put("charlie", CollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25))
        assertEquals(2, original.size)
        assertEquals(3, copy.size)
        
        // Secondary keys should work in copy
        val engineeringInCopy = copy.getBySecondaryKey("department", "Engineering")
        assertEquals(1, engineeringInCopy.size)
        assertEquals("Alice", engineeringInCopy[0].name)
    }

    // ==================== Performance and Edge Cases ====================

    @Test
    fun testEs_largeCollections() {
        val collection = es<Int, CollectionUser>(keySelector = { item -> item.id })
        
        val users = (1..1000).map { i ->
            CollectionUser(i, "User$i", "user$i@example.com", "Dept${i % 10}", 20 + (i % 50))
        }
        
        users.forEach { user ->
            collection.put(user.id, user)
        }
        
        assertEquals(1000, collection.size)
        assertEquals("User1", collection[1]?.name)
        assertEquals("User1000", collection[1000]?.name)
        
        assertEquals(1, collection.firstKey())
        assertEquals(1000, collection.lastKey())
    }

    @Test
    fun testEs_concurrentModificationDetection() {
        val collection = es<Int, CollectionUser>(keySelector = { item -> item.id })
        
        for (i in 1..10) {
            collection.put(i, CollectionUser(i, "User$i", "user$i@example.com", "Dept", 25))
        }
        
        val iterator = collection.keys.iterator()
        iterator.next()
        
        collection.put(11, CollectionUser(11, "User11", "user11@example.com", "Dept", 25))
        
        assertFailsWith<TreeMap.ConcurrentModificationException> {
            iterator.next()
        }
    }

    @Test
    fun testEs_equalityAndHashing() {
        val collection1 = es<String, CollectionUser>(keySelector = { item -> item.name })
        val collection2 = es<String, CollectionUser>(keySelector = { item -> item.name })
        
        val user1 = CollectionUser(1, "Alice", "alice@example.com", "Engineering", 28)
        val user2 = CollectionUser(2, "Bob", "bob@example.com", "Marketing", 32)
        
        collection1.put("alice", user1)
        collection1.put("bob", user2)
        
        collection2.put("alice", user1)
        collection2.put("bob", user2)
        
        val map1: Map<String, CollectionUser> = collection1
        val map2: Map<String, CollectionUser> = collection2
        assertEquals(map1, map2)
        assertEquals(map1.hashCode(), map2.hashCode())
    }
}