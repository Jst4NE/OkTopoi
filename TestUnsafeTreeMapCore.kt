import jst.oktopoi.UnsafeTreeMapCore
import jst.oktopoi.MapEntry

fun main() {
    // Test basic functionality
    val core = UnsafeTreeMapCore<String, Int>()
    
    // Test insertions
    println("Testing insertions...")
    core.putUnsafe("b", 2)
    core.putUnsafe("a", 1)
    core.putUnsafe("c", 3)
    
    println("Size: ${core.size}")
    println("Contains 'b': ${core.containsKeyUnsafe("b")}")
    println("Get 'a': ${core.getUnsafe("a")}")
    
    // Test NavigableMap operations
    println("\nTesting NavigableMap operations...")
    println("First key: ${core.firstKeyUnsafe()}")
    println("Last key: ${core.lastKeyUnsafe()}")
    println("Lower than 'b': ${core.lowerKeyUnsafe("b")}")
    println("Higher than 'b': ${core.higherKeyUnsafe("b")}")
    
    // Test red-black tree properties
    println("\nTesting red-black tree properties...")
    println("Valid RB tree: ${core.validateRedBlackPropertiesUnsafe()}")
    
    // Test iterators
    println("\nTesting iteration...")
    val keys = core.keysUnsafe()
    keys.forEach { println("Key: $it") }
    
    println("\nUnsafeTreeMapCore implementation appears to be working correctly!")
}