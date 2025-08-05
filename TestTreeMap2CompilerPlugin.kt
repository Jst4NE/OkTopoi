import jst.oktopoi.TreeMap
import kotlinx.coroutines.runBlocking

/**
 * Test file to verify TreeMap compiler plugin transformations
 * 
 * The compiler plugin should automatically transform TreeMap instance access
 * in suspend contexts to use the suspend view for optimal performance.
 */

class TestContainer {
    val treeMap = TreeMap<String, Int>()
}

fun main() = runBlocking {
    println("Testing TreeMap compiler plugin transformations...")
    
    // Test 1: Local variable transformation
    testLocalVariableTransformation()
    
    // Test 2: Property access transformation  
    testPropertyAccessTransformation()
    
    // Test 3: Mixed context (suspend vs blocking)
    testMixedContexts()
    
    println("All tests completed!")
}

/**
 * Test 1: Local TreeMap variable should be transformed to use suspend view
 * 
 * Expected transformation:
 * suspend fun test() {
 *     val map = TreeMap<String, Int>()
 *     map.get("key")  // Should transform to: map.suspend.get("key")
 * }
 */
suspend fun testLocalVariableTransformation() {
    println("Test 1: Local variable transformation")
    
    val map = TreeMap<String, Int>()
    
    // These calls should be automatically transformed to use suspend view
    map.put("test1", 1)
    map.put("test2", 2)
    map.put("test3", 3)
    
    val result1 = map.get("test1")  // Should use suspend view
    val result2 = map.get("test2")  // Should use suspend view
    
    println("Local variable test - got values: $result1, $result2")
    
    // Test NavigableMap operations
    val firstKey = map.firstKey()   // Should use suspend view
    val lastKey = map.lastKey()     // Should use suspend view
    
    println("NavigableMap operations - first: $firstKey, last: $lastKey")
    
    // Test modern Map API
    val computed = map.computeIfAbsent("test4") { 4 }  // Should use suspend view
    println("Computed value: $computed")
}

/**
 * Test 2: Property access should be transformed to use suspend view
 * 
 * Expected transformation:
 * suspend fun test(container: TestContainer) {
 *     container.treeMap.get("key")  // Should transform to: container.treeMap.suspend.get("key")
 * }
 */
suspend fun testPropertyAccessTransformation() {
    println("Test 2: Property access transformation")
    
    val container = TestContainer()
    
    // These property access calls should be automatically transformed
    container.treeMap.put("prop1", 10)
    container.treeMap.put("prop2", 20)
    
    val result1 = container.treeMap.get("prop1")  // Should use suspend view
    val result2 = container.treeMap.get("prop2")  // Should use suspend view
    
    println("Property access test - got values: $result1, $result2")
    
    // Test with secondary indexes
    val mapWithIndex = TreeMap<String, Person> {
        key("email") { person -> person.email }
    }
    
    val person = Person("John", "john@example.com", 1)
    mapWithIndex.put("person1", person)  // Should use suspend view
    
    val foundPerson = mapWithIndex.getBySecondaryKey("email", "john@example.com")  // Should use suspend view
    println("Found person by email: $foundPerson")
}

/**
 * Test 3: Mixed contexts - suspend vs blocking
 * 
 * The compiler should detect context switches and use appropriate views:
 * - Suspend context: use suspend view
 * - Blocking context (runBlocking): use blocking view
 */
suspend fun testMixedContexts() {
    println("Test 3: Mixed contexts")
    
    val map = TreeMap<String, Int>()
    
    // Suspend context - should use suspend view
    map.put("suspend1", 100)
    val suspendResult = map.get("suspend1")
    println("Suspend context result: $suspendResult")
    
    // Nested blocking context - should use blocking view
    val blockingResult = runBlocking {
        map.put("blocking1", 200)  // Should use blocking view (not transformed)
        map.get("blocking1")       // Should use blocking view (not transformed)
    }
    println("Blocking context result: $blockingResult")
    
    // Back to suspend context - should use suspend view again
    val backToSuspend = map.get("suspend1")  // Should use suspend view
    println("Back to suspend context: $backToSuspend")
}

/**
 * Helper data class for testing secondary indexes
 */
data class Person(val name: String, val email: String, val id: Int)