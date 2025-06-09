package jst.oktopoi

import kotlinx.serialization.Serializable

@Serializable
data class TestUser(val id: Int, val name: String, val department: String?)

fun main() {
    println("Testing nullable secondary keys runtime...")
    
    val map = TreeMap<Int, TestUser>(secondaryKeys = {
        key("department") { user -> user.department }
    })
    
    // Add users with null departments
    map.put(1, TestUser(1, "Alice", "Engineering"))
    map.put(2, TestUser(2, "Bob", null))
    map.put(3, TestUser(3, "Charlie", null))
    
    println("Added 3 users")
    
    // This should cause the runtime error you mentioned
    try {
        val nullDepartments = map.getBySecondaryKey("department", null)
        println("Found ${nullDepartments.size} users with null department: ${nullDepartments.map { it.name }}")
    } catch (e: Exception) {
        println("ERROR: ${e.message}")
        e.printStackTrace()
    }
    
    try {
        val engineering = map.getBySecondaryKey("department", "Engineering")
        println("Found ${engineering.size} users in Engineering: ${engineering.map { it.name }}")
    } catch (e: Exception) {
        println("ERROR: ${e.message}")
        e.printStackTrace()
    }
}