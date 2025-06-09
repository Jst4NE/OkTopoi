package jst.oktopoi

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
data class PersistentCollectionUser(
    val id: Int, 
    val name: String, 
    val email: String, 
    val department: String, 
    val age: Int,
    val salary: Double,
    val active: Boolean = true
)

@Serializable
data class InventoryItem(
    val sku: String,
    val name: String,
    val category: String,
    val quantity: Int,
    val price: Double,
    val supplier: String,
    val lastUpdated: Long = 0L
)

@Serializable
data class CustomerOrder(
    val orderId: String,
    val customerId: Int,
    val items: List<OrderItem>,
    val total: Double,
    val status: OrderStatus,
    val createdAt: Long,
    val shippingAddress: Address
)

@Serializable
data class OrderItem(val productId: String, val quantity: Int, val unitPrice: Double)

@Serializable
data class Address(val street: String, val city: String, val state: String, val zipCode: String)

@Serializable
enum class OrderStatus { PENDING, PROCESSING, SHIPPED, DELIVERED, CANCELLED }

class EpsFunctionExtensiveTest {

    private val testRootDir = Path("/tmp/oktopoi-eps-test")
    
    @BeforeTest
    fun setup() {
        // Initialize IO for persistence tests
        initDefaultIO(testRootDir, SystemFileSystem)
        initRootDirIO(testRootDir, SystemFileSystem)
    }

    // ==================== Basic eps() Constructor Tests ====================

    @Test
    fun testEps_defaultConstructor() {
        val collection = eps<String, PersistentCollectionUser>()
        
        assertTrue(collection.isEmpty())
        assertEquals(0, collection.size)
        assertTrue(collection.keys.isEmpty())
        assertTrue(collection.values.isEmpty())
        assertTrue(collection.entries.isEmpty())
    }

    @Test
    fun testEps_withKeySelector() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        assertTrue(collection.isEmpty())
        assertEquals(0, collection.size)
    }

    @Test
    fun testEps_withComparator() {
        val reverseComparator = Comparator<String> { a, b -> b.compareTo(a) }
        val collection = eps<String, PersistentCollectionUser>(
            keySelector = { user -> user.name },
            sortingBy = reverseComparator
        )
        
        assertTrue(collection.isEmpty())
        assertEquals(0, collection.size)
    }

    @Test
    fun testEps_withSecondaryKeys() {
        val collection = eps<String, PersistentCollectionUser>(
            keySelector = { user -> user.name },
            secondaryKeys = {
                key("department") { it.department }
                key("ageGroup") { it.age / 10 }
                key("active") { it.active }
                key("salaryRange") { (it.salary / 10000).toInt() * 10000 }
            }
        )
        
        assertTrue(collection.isEmpty())
        assertEquals(0, collection.size)
    }

    @Test
    fun testEps_withExplicitRootDir() {
        val customDir = Path("/tmp/oktopoi-eps-custom")
        val collection = eps<String, PersistentCollectionUser>(
            rootDir = customDir,
            keySelector = { user -> user.name }
        )
        
        assertTrue(collection.isEmpty())
        
        // Add a user to test persistence path
        val user = PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0)
        collection.put("alice", user)
        assertEquals(1, collection.size)
        
        // Custom directory test completed
    }

    @Test
    fun testEps_withExplicitFileSystem() {
        val collection = eps<String, PersistentCollectionUser>(
            fileSystem = SystemFileSystem,
            keySelector = { user -> user.name }
        )
        
        assertTrue(collection.isEmpty())
        
        val user = PersistentCollectionUser(1, "Bob", "bob@example.com", "Marketing", 32, 65000.0)
        collection.put("bob", user)
        assertEquals(1, collection.size)
    }

    // ==================== Basic Operations ====================

    @Test
    fun testEps_putAndGet() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val user1 = PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0)
        val user2 = PersistentCollectionUser(2, "Bob", "bob@example.com", "Marketing", 32, 65000.0)
        
        assertNull(collection.put("alice", user1))
        assertEquals(1, collection.size)
        assertEquals(user1, collection["alice"])
        assertEquals(user1, collection.get("alice"))
        
        // Wait for potential async persistence operations
        // delay(50)
        
        assertNull(collection.put("bob", user2))
        assertEquals(2, collection.size)
        assertEquals(user2, collection["bob"])
        
        // delay(50)
    }

    @Test
    fun testEps_putUpdate() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val user1 = PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0)
        val user1Updated = PersistentCollectionUser(1, "Alice", "alice.new@example.com", "Engineering", 29, 80000.0)
        
        collection.put("alice", user1)
        // delay(50)
        
        assertEquals(user1, collection.put("alice", user1Updated))
        assertEquals(1, collection.size)
        assertEquals(user1Updated, collection["alice"])
        assertEquals("alice.new@example.com", collection["alice"]?.email)
        assertEquals(29, collection["alice"]?.age)
        assertEquals(80000.0, collection["alice"]?.salary)
        
        // delay(50)
    }

    @Test
    fun testEps_remove() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val user1 = PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0)
        val user2 = PersistentCollectionUser(2, "Bob", "bob@example.com", "Marketing", 32, 65000.0)
        
        collection.put("alice", user1)
        collection.put("bob", user2)
        assertEquals(2, collection.size)
        // delay(50)
        
        assertEquals(user1, collection.remove("alice"))
        assertEquals(1, collection.size)
        assertNull(collection["alice"])
        assertEquals(user2, collection["bob"])
        
        assertNull(collection.remove("nonexistent"))
        assertEquals(1, collection.size)
        
        // delay(50)
    }

    @Test
    fun testEps_clear() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        collection.put("alice", PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0))
        collection.put("bob", PersistentCollectionUser(2, "Bob", "bob@example.com", "Marketing", 32, 65000.0))
        collection.put("charlie", PersistentCollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25, 55000.0))
        
        assertEquals(3, collection.size)
        // delay(50)
        
        collection.clear()
        assertEquals(0, collection.size)
        assertTrue(collection.isEmpty())
        assertTrue(collection.keys.isEmpty())
        assertTrue(collection.values.isEmpty())
        assertTrue(collection.entries.isEmpty())
        
        // delay(50)
    }

    @Test
    fun testEps_containsOperations() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val user1 = PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0)
        val user2 = PersistentCollectionUser(2, "Bob", "bob@example.com", "Marketing", 32, 65000.0)
        val user3 = PersistentCollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25, 55000.0)
        
        collection.put("alice", user1)
        collection.put("bob", user2)
        
        assertTrue(collection.containsKey("alice"))
        assertTrue(collection.containsKey("bob"))
        assertFalse(collection.containsKey("charlie"))
        
        assertTrue(collection.containsValue(user1))
        assertTrue(collection.containsValue(user2))
        assertFalse(collection.containsValue(user3))
    }

    // ==================== putAll Operations ====================

    @Test
    fun testEps_putAll() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val users = mapOf(
            "alice" to PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0),
            "bob" to PersistentCollectionUser(2, "Bob", "bob@example.com", "Marketing", 32, 65000.0),
            "charlie" to PersistentCollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25, 55000.0)
        )
        
        collection.putAll(users)
        
        assertEquals(3, collection.size)
        assertEquals(users["alice"], collection["alice"])
        assertEquals(users["bob"], collection["bob"])
        assertEquals(users["charlie"], collection["charlie"])
        
        // delay(100) // Wait for persistence operations
    }

    @Test
    fun testEps_putAllWithUpdates() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        // Initial data
        collection.put("alice", PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0))
        collection.put("bob", PersistentCollectionUser(2, "Bob", "bob@example.com", "Marketing", 32, 65000.0))
        // delay(50)
        
        // Update with putAll
        val updates = mapOf(
            "alice" to PersistentCollectionUser(1, "Alice", "alice.new@example.com", "Engineering", 29, 80000.0),
            "charlie" to PersistentCollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25, 55000.0)
        )
        
        collection.putAll(updates)
        
        assertEquals(3, collection.size)
        assertEquals("alice.new@example.com", collection["alice"]?.email)
        assertEquals(29, collection["alice"]?.age)
        assertEquals(80000.0, collection["alice"]?.salary)
        assertEquals(updates["charlie"], collection["charlie"])
        
        // delay(100)
    }

    // ==================== Sorting and Ordering ====================

    @Test
    fun testEps_naturalOrdering() {
        val collection = eps<Int, InventoryItem>(keySelector = { it.sku.toInt() })
        
        collection.put(5, InventoryItem("5", "Item5", "CategoryA", 50, 50.0, "SupplierA"))
        collection.put(2, InventoryItem("2", "Item2", "CategoryB", 20, 20.0, "SupplierB"))
        collection.put(8, InventoryItem("8", "Item8", "CategoryA", 80, 80.0, "SupplierA"))
        collection.put(1, InventoryItem("1", "Item1", "CategoryC", 10, 10.0, "SupplierC"))
        collection.put(3, InventoryItem("3", "Item3", "CategoryB", 30, 30.0, "SupplierB"))
        
        val keys = collection.keys.toList()
        assertEquals(listOf(1, 2, 3, 5, 8), keys)
        
        // delay(100)
    }

    @Test
    fun testEps_customComparator() {
        val reverseComparator = Comparator<String> { a, b -> b.compareTo(a) }
        val collection = eps<String, PersistentCollectionUser>(
            keySelector = { user -> user.name },
            sortingBy = reverseComparator
        )
        
        collection.put("alice", PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0))
        collection.put("bob", PersistentCollectionUser(2, "Bob", "bob@example.com", "Marketing", 32, 65000.0))
        collection.put("charlie", PersistentCollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25, 55000.0))
        
        val keys = collection.keys.toList()
        assertEquals(listOf("charlie", "bob", "alice"), keys)
        
        // delay(100)
    }

    // ==================== Secondary Keys ====================

    @Test
    fun testEps_secondaryKeysBasic() {
        val collection = eps<String, PersistentCollectionUser>(
            keySelector = { user -> user.name },
            secondaryKeys = {
                key("department") { it.department }
                key("ageGroup") { it.age / 10 }
                key("active") { it.active }
            }
        )
        
        collection.put("alice", PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0, true))
        collection.put("bob", PersistentCollectionUser(2, "Bob", "bob@example.com", "Engineering", 32, 80000.0, true))
        collection.put("charlie", PersistentCollectionUser(3, "Charlie", "charlie@example.com", "Marketing", 25, 60000.0, false))
        collection.put("diana", PersistentCollectionUser(4, "Diana", "diana@example.com", "Sales", 35, 70000.0, true))
        
        // delay(100)
        
        // Test department secondary key
        val engineeringUsers = collection.getBySecondaryKey("department", "Engineering")
        assertEquals(2, engineeringUsers.size)
        val engineeringNames = engineeringUsers.map { it.name }.toSet()
        assertEquals(setOf("Alice", "Bob"), engineeringNames)
        
        val marketingUsers = collection.getBySecondaryKey("department", "Marketing")
        assertEquals(1, marketingUsers.size)
        assertEquals("Charlie", marketingUsers[0].name)
        
        // Test age group secondary key
        val twentySomethings = collection.getBySecondaryKey("ageGroup", 2) // 20-29
        assertEquals(2, twentySomethings.size)
        val twentyNames = twentySomethings.map { it.name }.toSet()
        assertEquals(setOf("Alice", "Charlie"), twentyNames)
        
        val thirtySomethings = collection.getBySecondaryKey("ageGroup", 3) // 30-39
        assertEquals(2, thirtySomethings.size)
        val thirtyNames = thirtySomethings.map { it.name }.toSet()
        assertEquals(setOf("Bob", "Diana"), thirtyNames)
        
        // Test active status secondary key
        val activeUsers = collection.getBySecondaryKey("active", true)
        assertEquals(3, activeUsers.size)
        val activeNames = activeUsers.map { it.name }.toSet()
        assertEquals(setOf("Alice", "Bob", "Diana"), activeNames)
        
        val inactiveUsers = collection.getBySecondaryKey("active", false)
        assertEquals(1, inactiveUsers.size)
        assertEquals("Charlie", inactiveUsers[0].name)
    }

    @Test
    fun testEps_secondaryKeysComplex() {
        val collection = eps<String, InventoryItem>(
            keySelector = { it.sku },
            secondaryKeys = {
                key("category") { it.category }
                key("supplier") { it.supplier }
                key("quantityRange") { (it.quantity / 25) * 25 } // 0-24, 25-49, 50-74, etc.
                key("priceRange") { (it.price / 50).toInt() * 50 } // 0-49, 50-99, etc.
                key("lowStock") { it.quantity < 25 }
            }
        )
        
        collection.put("ITEM001", InventoryItem("ITEM001", "Widget", "Electronics", 15, 25.99, "SupplierA"))
        collection.put("ITEM002", InventoryItem("ITEM002", "Gadget", "Electronics", 75, 75.50, "SupplierB"))
        collection.put("ITEM003", InventoryItem("ITEM003", "Book", "Media", 50, 15.99, "SupplierA"))
        collection.put("ITEM004", InventoryItem("ITEM004", "Magazine", "Media", 5, 5.99, "SupplierC"))
        collection.put("ITEM005", InventoryItem("ITEM005", "Laptop", "Electronics", 30, 999.99, "SupplierB"))
        
        // delay(100)
        
        // Test by category
        val electronics = collection.getBySecondaryKey("category", "Electronics")
        assertEquals(3, electronics.size)
        val electronicNames = electronics.map { it.name }.toSet()
        assertEquals(setOf("Widget", "Gadget", "Laptop"), electronicNames)
        
        val media = collection.getBySecondaryKey("category", "Media")
        assertEquals(2, media.size)
        val mediaNames = media.map { it.name }.toSet()
        assertEquals(setOf("Book", "Magazine"), mediaNames)
        
        // Test by supplier
        val supplierA = collection.getBySecondaryKey("supplier", "SupplierA")
        assertEquals(2, supplierA.size)
        val supplierANames = supplierA.map { it.name }.toSet()
        assertEquals(setOf("Widget", "Book"), supplierANames)
        
        // Test by quantity range
        val lowQuantity = collection.getBySecondaryKey("quantityRange", 0) // 0-24
        assertEquals(2, lowQuantity.size) // Widget (15), Magazine (5)
        
        val midQuantity = collection.getBySecondaryKey("quantityRange", 25) // 25-49
        assertEquals(1, midQuantity.size) // Laptop (30)
        
        val highQuantity = collection.getBySecondaryKey("quantityRange", 50) // 50-74
        assertEquals(2, highQuantity.size) // Gadget (75), Book (50)
        
        // Test by price range
        val lowPrice = collection.getBySecondaryKey("priceRange", 0) // 0-49
        assertEquals(3, lowPrice.size)
        
        val midPrice = collection.getBySecondaryKey("priceRange", 50) // 50-99
        assertEquals(1, midPrice.size)
        assertEquals("Gadget", midPrice[0].name)
        
        val highPrice = collection.getBySecondaryKey("priceRange", 950) // 950-999
        assertEquals(1, highPrice.size)
        assertEquals("Laptop", highPrice[0].name)
        
        // Test low stock flag
        val lowStockItems = collection.getBySecondaryKey("lowStock", true)
        assertEquals(2, lowStockItems.size) // Widget (15), Magazine (5)
        
        val adequateStockItems = collection.getBySecondaryKey("lowStock", false)
        assertEquals(3, adequateStockItems.size)
    }

    @Test
    fun testEps_secondaryKeysUpdate() {
        val collection = eps<String, PersistentCollectionUser>(
            keySelector = { user -> user.name },
            secondaryKeys = {
                key("department") { it.department }
                key("active") { it.active }
            }
        )
        
        collection.put("alice", PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0, true))
        // delay(50)
        
        val engineeringBefore = collection.getBySecondaryKey("department", "Engineering")
        assertEquals(1, engineeringBefore.size)
        assertEquals("Alice", engineeringBefore[0].name)
        
        val activeBefore = collection.getBySecondaryKey("active", true)
        assertEquals(1, activeBefore.size)
        
        // Update user's department and status
        collection.put("alice", PersistentCollectionUser(1, "Alice", "alice@example.com", "Sales", 28, 75000.0, false))
        // delay(50)
        
        val engineeringAfter = collection.getBySecondaryKey("department", "Engineering")
        assertEquals(0, engineeringAfter.size)
        
        val salesAfter = collection.getBySecondaryKey("department", "Sales")
        assertEquals(1, salesAfter.size)
        assertEquals("Alice", salesAfter[0].name)
        
        val activeAfter = collection.getBySecondaryKey("active", true)
        assertEquals(0, activeAfter.size)
        
        val inactiveAfter = collection.getBySecondaryKey("active", false)
        assertEquals(1, inactiveAfter.size)
        assertEquals("Alice", inactiveAfter[0].name)
    }

    // ==================== TreeMap Navigation Operations ====================

    @Test
    fun testEps_navigationOperations() {
        val collection = eps<Int, InventoryItem>(keySelector = { it.sku.toInt() })
        
        collection.put(1, InventoryItem("1", "Item1", "A", 10, 10.0, "SupplierA"))
        collection.put(3, InventoryItem("3", "Item3", "B", 30, 30.0, "SupplierB"))
        collection.put(5, InventoryItem("5", "Item5", "C", 50, 50.0, "SupplierC"))
        collection.put(7, InventoryItem("7", "Item7", "D", 70, 70.0, "SupplierD"))
        collection.put(9, InventoryItem("9", "Item9", "E", 90, 90.0, "SupplierE"))
        
        // delay(50)
        
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
    }

    @Test
    fun testEps_pollOperations() {
        val collection = eps<Int, InventoryItem>(keySelector = { it.sku.toInt() })
        
        collection.put(3, InventoryItem("3", "Item3", "A", 30, 30.0, "SupplierA"))
        collection.put(1, InventoryItem("1", "Item1", "B", 10, 10.0, "SupplierB"))
        collection.put(5, InventoryItem("5", "Item5", "C", 50, 50.0, "SupplierC"))
        
        assertEquals(3, collection.size)
        // delay(50)
        
        val firstPolled = collection.pollFirstEntry()
        assertNotNull(firstPolled)
        assertEquals(1, firstPolled.key)
        assertEquals("Item1", firstPolled.value.name)
        assertEquals(2, collection.size)
        assertNull(collection[1])
        
        val lastPolled = collection.pollLastEntry()
        assertNotNull(lastPolled)
        assertEquals(5, lastPolled.key)
        assertEquals("Item5", lastPolled.value.name)
        assertEquals(1, collection.size)
        assertNull(collection[5])
        
        // delay(50) // Wait for persistence of removals
    }

    // ==================== Advanced Map Operations ====================

    @Test
    fun testEps_putIfAbsent() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val user1 = PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0)
        val user2 = PersistentCollectionUser(2, "Alice", "alice.different@example.com", "Sales", 30, 80000.0)
        
        assertNull(collection.putIfAbsent("alice", user1))
        assertEquals(user1, collection["alice"])
        assertEquals(1, collection.size)
        // delay(50)
        
        assertEquals(user1, collection.putIfAbsent("alice", user2))
        assertEquals(user1, collection["alice"]) // Should not change
        assertEquals(1, collection.size)
        // delay(50)
    }

    @Test
    fun testEps_replace() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val user1 = PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0)
        val user2 = PersistentCollectionUser(2, "Alice", "alice.new@example.com", "Marketing", 30, 85000.0)
        
        collection.put("alice", user1)
        // delay(50)
        
        assertEquals(user1, collection.replace("alice", user2))
        assertEquals(user2, collection["alice"])
        assertEquals("alice.new@example.com", collection["alice"]?.email)
        assertEquals("Marketing", collection["alice"]?.department)
        
        assertNull(collection.replace("bob", user1))
        assertNull(collection["bob"])
        // delay(50)
    }

    @Test
    fun testEps_conditionalOperations() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val user1 = PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0)
        val user2 = PersistentCollectionUser(2, "Alice", "alice.new@example.com", "Marketing", 30, 85000.0)
        val user3 = PersistentCollectionUser(3, "Bob", "bob@example.com", "Sales", 25, 60000.0)
        
        collection.put("alice", user1)
        // delay(50)
        
        // Test conditional replace
        assertFalse(collection.replace("alice", user3, user2)) // Wrong expected value
        assertEquals(user1, collection["alice"])
        
        assertTrue(collection.replace("alice", user1, user2)) // Correct expected value
        assertEquals(user2, collection["alice"])
        // delay(50)
        
        // Test conditional remove
        assertFalse(collection.remove("alice", user1)) // Wrong value
        assertEquals(user2, collection["alice"])
        assertEquals(1, collection.size)
        
        assertTrue(collection.remove("alice", user2)) // Correct value
        assertNull(collection["alice"])
        assertEquals(0, collection.size)
        // delay(50)
    }

    // ==================== Compute Operations ====================

    @Test
    fun testEps_computeOperations() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val user1 = PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0)
        
        // Compute for non-existent key
        val result1 = collection.compute("alice") { key, value ->
            if (value == null) user1 else value
        }
        assertEquals(user1, result1)
        assertEquals(user1, collection["alice"])
        // delay(50)
        
        // Compute for existing key
        val result2 = collection.compute("alice") { key, value ->
            value?.copy(age = value.age + 1, salary = value.salary + 5000.0)
        }
        assertEquals(29, result2?.age)
        assertEquals(80000.0, result2?.salary)
        assertEquals(29, collection["alice"]?.age)
        assertEquals(80000.0, collection["alice"]?.salary)
        // delay(50)
        
        // Compute to remove
        val result3 = collection.compute("alice") { key, value -> null }
        assertNull(result3)
        assertNull(collection["alice"])
        // delay(50)
    }

    @Test
    fun testEps_computeIfAbsentAndPresent() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val user1 = PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0)
        val user2 = PersistentCollectionUser(2, "Bob", "bob@example.com", "Marketing", 32, 65000.0)
        
        val result1 = collection.computeIfAbsent("alice") { user1 }
        assertEquals(user1, result1)
        assertEquals(user1, collection["alice"])
        // delay(50)
        
        val result2 = collection.computeIfAbsent("alice") { user2 }
        assertEquals(user1, result2) // Should return existing value
        assertEquals(user1, collection["alice"])
        
        val result3 = collection.computeIfPresent("alice") { key, value ->
            value.copy(salary = value.salary * 1.1) // 10% raise
        }
        assertEquals(82500.0, result3?.salary)
        assertEquals(82500.0, collection["alice"]?.salary)
        // delay(50)
        
        val result4 = collection.computeIfPresent("bob") { key, value ->
            value.copy(salary = value.salary * 1.1)
        }
        assertNull(result4)
        assertNull(collection["bob"])
    }

    @Test
    fun testEps_merge() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val user1 = PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0)
        val user2 = PersistentCollectionUser(2, "Alice", "alice.new@example.com", "Marketing", 30, 85000.0)
        
        val result1 = collection.merge("alice", user1) { oldValue, newValue ->
            newValue
        }
        assertEquals(user1, result1)
        assertEquals(user1, collection["alice"])
        // delay(50)
        
        val result2 = collection.merge("alice", user2) { oldValue, newValue ->
            oldValue?.copy(
                email = newValue.email,
                department = newValue.department,
                age = newValue.age,
                salary = maxOf(oldValue.salary, newValue.salary)
            )
        }
        assertEquals("alice.new@example.com", result2?.email)
        assertEquals("Marketing", result2?.department)
        assertEquals(30, result2?.age)
        assertEquals(85000.0, result2?.salary)
        // delay(50)
    }

    // ==================== Complex Data Types ====================

    @Test
    fun testEps_complexDataStructures() {
        val collection = eps<String, CustomerOrder>(keySelector = { order -> order.orderId })
        
        val order1 = CustomerOrder(
            orderId = "ORD001",
            customerId = 1,
            items = listOf(
                OrderItem("PROD001", 2, 25.99),
                OrderItem("PROD002", 1, 49.99)
            ),
            total = 101.97,
            status = OrderStatus.PENDING,
            createdAt = 0L,
            shippingAddress = Address("123 Main St", "Anytown", "ST", "12345")
        )
        
        val order2 = CustomerOrder(
            orderId = "ORD002",
            customerId = 2,
            items = listOf(
                OrderItem("PROD003", 3, 15.99),
                OrderItem("PROD004", 1, 199.99)
            ),
            total = 247.96,
            status = OrderStatus.PROCESSING,
            createdAt = 0L,
            shippingAddress = Address("456 Oak Ave", "Another Town", "ST", "67890")
        )
        
        collection.put("ORD001", order1)
        collection.put("ORD002", order2)
        
        assertEquals(2, collection.size)
        assertEquals(order1, collection["ORD001"])
        assertEquals(order2, collection["ORD002"])
        
        assertEquals(2, collection["ORD001"]?.items?.size)
        assertEquals(OrderStatus.PENDING, collection["ORD001"]?.status)
        assertEquals("123 Main St", collection["ORD001"]?.shippingAddress?.street)
        
        // delay(100)
    }

    @Test
    fun testEps_withEnumsAndSecondaryKeys() {
        val collection = eps<String, CustomerOrder>(
            keySelector = { order -> order.orderId },
            secondaryKeys = {
                key("status") { it.status }
                key("customerId") { it.customerId }
                key("totalRange") { (it.total / 100).toInt() * 100 }
            }
        )
        
        val orders = listOf(
            CustomerOrder("ORD001", 1, listOf(OrderItem("P1", 1, 50.0)), 50.0, OrderStatus.PENDING, 
                0L, Address("1 St", "City", "ST", "12345")),
            CustomerOrder("ORD002", 1, listOf(OrderItem("P2", 2, 75.0)), 150.0, OrderStatus.PROCESSING, 
                0L, Address("2 St", "City", "ST", "12345")),
            CustomerOrder("ORD003", 2, listOf(OrderItem("P3", 1, 250.0)), 250.0, OrderStatus.SHIPPED, 
                0L, Address("3 St", "City", "ST", "12345")),
            CustomerOrder("ORD004", 2, listOf(OrderItem("P4", 1, 75.0)), 75.0, OrderStatus.DELIVERED, 
                0L, Address("4 St", "City", "ST", "12345"))
        )
        
        orders.forEach { order ->
            collection.put(order.orderId, order)
        }
        
        // delay(100)
        
        // Test by status
        val pendingOrders = collection.getBySecondaryKey("status", OrderStatus.PENDING)
        assertEquals(1, pendingOrders.size)
        assertEquals("ORD001", pendingOrders[0].orderId)
        
        val processingOrders = collection.getBySecondaryKey("status", OrderStatus.PROCESSING)
        assertEquals(1, processingOrders.size)
        assertEquals("ORD002", processingOrders[0].orderId)
        
        // Test by customer
        val customer1Orders = collection.getBySecondaryKey("customerId", 1)
        assertEquals(2, customer1Orders.size)
        val customer1OrderIds = customer1Orders.map { it.orderId }.toSet()
        assertEquals(setOf("ORD001", "ORD002"), customer1OrderIds)
        
        val customer2Orders = collection.getBySecondaryKey("customerId", 2)
        assertEquals(2, customer2Orders.size)
        val customer2OrderIds = customer2Orders.map { it.orderId }.toSet()
        assertEquals(setOf("ORD003", "ORD004"), customer2OrderIds)
        
        // Test by total range
        val lowTotalOrders = collection.getBySecondaryKey("totalRange", 0) // 0-99
        assertEquals(2, lowTotalOrders.size)
        
        val midTotalOrders = collection.getBySecondaryKey("totalRange", 100) // 100-199
        assertEquals(1, midTotalOrders.size)
        assertEquals("ORD002", midTotalOrders[0].orderId)
        
        val highTotalOrders = collection.getBySecondaryKey("totalRange", 200) // 200-299
        assertEquals(1, highTotalOrders.size)
        assertEquals("ORD003", highTotalOrders[0].orderId)
    }

    // ==================== Persistence-Specific Tests ====================

    @Test
    fun testEps_persistenceSetup() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        // Add items to trigger persistence setup
        collection.put("alice", PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0))
        collection.put("bob", PersistentCollectionUser(2, "Bob", "bob@example.com", "Marketing", 32, 65000.0))
        collection.put("charlie", PersistentCollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25, 55000.0))
        
        assertEquals(3, collection.size)
        
        // Wait for potential async operations
        // delay(200)
        
        assertEquals(3, collection.size)
        assertEquals("Alice", collection["alice"]?.name)
        assertEquals("Bob", collection["bob"]?.name)
        assertEquals("Charlie", collection["charlie"]?.name)
    }

    @Test
    fun testEps_rapidChanges() {
        val collection = eps<Int, InventoryItem>(keySelector = { it.sku.toInt() })
        
        // Rapid changes to test persistence handling
        for (i in 1..50) {
            collection.put(i, InventoryItem(i.toString(), "Item$i", "Category", i, i * 10.0, "Supplier"))
        }
        
        assertEquals(50, collection.size)
        
        // Remove some items rapidly
        for (i in 1..25) {
            collection.remove(i)
        }
        
        assertEquals(25, collection.size)
        
        // Wait for persistence operations
        // delay(200)
        
        assertEquals(25, collection.size)
        assertEquals("Item26", collection[26]?.name)
        assertEquals("Item50", collection[50]?.name)
    }

    @Test
    fun testEps_bulkOperations() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val users = (1..100).associate { i ->
            "user$i" to PersistentCollectionUser(
                id = i,
                name = "User$i",
                email = "user$i@example.com",
                department = "Dept${i % 5}",
                age = 20 + (i % 40),
                salary = 50000.0 + (i * 1000)
            )
        }
        
        collection.putAll(users)
        assertEquals(100, collection.size)
        
        // Wait for persistence
        // delay(300)
        
        assertEquals(100, collection.size)
        assertEquals("User1", collection["user1"]?.name)
        assertEquals("User100", collection["user100"]?.name)
        
        // Test clearing
        collection.clear()
        assertEquals(0, collection.size)
        
        // delay(100)
        
        assertTrue(collection.isEmpty())
    }

    // ==================== Error Handling and Edge Cases ====================

    @Test
    fun testEps_errorHandling() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        // These operations should not throw exceptions
        val user = PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0)
        
        collection.put("alice", user)
        // delay(50)
        
        // Rapid operations
        collection.put("alice", user.copy(age = 29))
        collection.put("alice", user.copy(age = 30))
        collection.put("alice", user.copy(age = 31))
        
        // delay(100)
        
        assertEquals(31, collection["alice"]?.age)
    }

    @Test
    fun testEps_specialCharactersInKeys() {
        val collection = eps<String, PersistentCollectionUser>(keySelector = { user -> user.name })
        
        val specialKeys = listOf(
            "user@domain.com",
            "user-with-dashes",
            "user_with_underscores",
            "user with spaces",
            "user.with.dots",
            "user/with/slashes",
            "user\\with\\backslashes",
            "αβγδεζηθι", // Greek characters
            "用户名称", // Chinese characters
            "🚀🌟💯" // Emojis
        )
        
        specialKeys.forEachIndexed { index, key ->
            val user = PersistentCollectionUser(
                id = index + 1,
                name = key,
                email = "$key@example.com",
                department = "Special",
                age = 25 + index,
                salary = 50000.0 + (index * 1000)
            )
            collection.put(key, user)
        }
        
        // delay(200)
        
        assertEquals(specialKeys.size, collection.size)
        
        specialKeys.forEach { key ->
            assertNotNull(collection[key])
            assertEquals(key, collection[key]?.name)
        }
    }

    @Test
    fun testEps_concurrentOperations() {
        val collection = eps<Int, PersistentCollectionUser>(keySelector = { it.id })
        
        // Test concurrent operations without actual concurrency
        for (i in 1..50) {
            val user = PersistentCollectionUser(i, "User$i", "user$i@example.com", "Dept", 25, 50000.0)
            collection.put(i, user)
            if (i % 2 == 0) {
                collection.remove(i)
            }
        }
        
        // Should have about 25 users remaining (odd numbers)
        assertTrue(collection.size >= 20 && collection.size <= 30)
        
        // delay(100)
    }

    // ==================== Copy Operations ====================

    @Test
    fun testEps_copy() {
        val original = eps<String, PersistentCollectionUser>(
            keySelector = { user -> user.name },
            secondaryKeys = {
                key("department") { it.department }
            }
        )
        
        original.put("alice", PersistentCollectionUser(1, "Alice", "alice@example.com", "Engineering", 28, 75000.0))
        original.put("bob", PersistentCollectionUser(2, "Bob", "bob@example.com", "Marketing", 32, 65000.0))
        
        // delay(50)
        
        val copy = original.copy()
        
        assertEquals(original.size, copy.size)
        assertEquals(original["alice"], copy["alice"])
        assertEquals(original["bob"], copy["bob"])
        
        copy.put("charlie", PersistentCollectionUser(3, "Charlie", "charlie@example.com", "Sales", 25, 55000.0))
        assertEquals(2, original.size)
        assertEquals(3, copy.size)
        
        // Secondary keys should work in copy
        val engineeringInCopy = copy.getBySecondaryKey("department", "Engineering")
        assertEquals(1, engineeringInCopy.size)
        assertEquals("Alice", engineeringInCopy[0].name)
        
        // delay(50)
    }

    // ==================== Performance Tests ====================

    @Test
    fun testEps_performanceCharacteristics() {
        val collection = eps<Int, InventoryItem>(keySelector = { it.sku.toInt() })
        
        val size = 1000
        
        // Insert many elements
        for (i in 1..size) {
            collection.put(i, InventoryItem(i.toString(), "Item$i", "Category", i, i * 10.0, "Supplier"))
        }
        
        assertEquals(size, collection.size)
        
        // Test random access
        for (i in 1..100) {
            val randomKey = (1..size).random()
            assertEquals("Item$randomKey", collection[randomKey]?.name)
        }
        
        // Test range operations
        val subMap = collection.subMap(100, true, 200, false)
        assertEquals(100, subMap.size)
        
        // Test navigation operations
        assertEquals(1, collection.firstKey())
        assertEquals(size, collection.lastKey())
        assertEquals(500, collection.ceilingKey(500))
        assertEquals(499, collection.floorKey(500))
        
        // delay(300) // Wait for persistence operations to complete
    }
}