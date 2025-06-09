package jst.oktopoi

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
data class EdgeCaseTestData(
    val id: String = "",
    val nullableField: String? = null,
    val emptyList: List<String> = emptyList(),
    val emptyMap: Map<String, String> = emptyMap(),
    val unicodeContent: String = "",
    val largeNumber: Long = 0L,
    val specialChars: String = ""
)

class EdgeCasesAndErrorHandlingTest {

    private val testRootDir = Path("/tmp/oktopoi-edge-cases-test")
    
    @BeforeTest
    fun setup() {
        // Initialize IO for persistence tests
        initDefaultIO(testRootDir, SystemFileSystem)
        initRootDirIO(testRootDir, SystemFileSystem)
    }

    // ==================== Null and Empty Value Handling ====================

    @Test
    fun testEdgeCases_nullValues() {
        val state = e<String?>()
        val persistentState = ep<String?>()
        
        // Test null handling in regular state
        assertNull(state.value)
        assertTrue(state.isEmpty())
        
        state.value = null
        assertNull(state.value)
        assertTrue(state.isEmpty())
        
        state.value = "not_null"
        assertEquals("not_null", state.value)
        assertFalse(state.isEmpty())
        
        state.value = null
        assertNull(state.value)
        assertTrue(state.isEmpty())
        
        // Test null handling in persistent state
        assertNull(persistentState.value)
        assertTrue(persistentState.isEmpty())
        
        persistentState.value = null
        assertNull(persistentState.value)
        assertTrue(persistentState.isEmpty())
        
        persistentState.value = "persistent_not_null"
        assertEquals("persistent_not_null", persistentState.value)
        assertFalse(persistentState.isEmpty())
        
        persistentState.value = null
        assertNull(persistentState.value)
        assertTrue(persistentState.isEmpty())
    }

    @Test
    fun testEdgeCases_emptyCollections() {
        val collection = es<String, String>()
        val persistentCollection = eps<String, String>()
        
        // Test empty collections
        assertTrue(collection.isEmpty())
        assertEquals(0, collection.size)
        assertTrue(collection.keys.isEmpty())
        assertTrue(collection.values.isEmpty())
        assertTrue(collection.entries.isEmpty())
        
        assertTrue(persistentCollection.isEmpty())
        assertEquals(0, persistentCollection.size)
        assertTrue(persistentCollection.keys.isEmpty())
        assertTrue(persistentCollection.values.isEmpty())
        assertTrue(persistentCollection.entries.isEmpty())
        
        // Operations on empty collections should not throw
        assertNull(collection.remove("nonexistent"))
        assertNull(persistentCollection.remove("nonexistent"))
        assertFalse(collection.containsKey("any"))
        assertFalse(persistentCollection.containsKey("any"))
        assertFalse(collection.containsValue("any"))
        assertFalse(persistentCollection.containsValue("any"))
        
        // Clear empty collections should work
        collection.clear()
        persistentCollection.clear()
        assertTrue(collection.isEmpty())
        assertTrue(persistentCollection.isEmpty())
    }

    @Test
    fun testEdgeCases_emptyStrings() {
        val state = e<String>()
        val collection = es<String, String>()
        
        // Test empty strings
        state.value = ""
        assertEquals("", state.value)
        assertFalse(state.isEmpty()) // Empty string is not null
        assertEquals("", state.toString())
        
        // Test empty strings as keys and values in collections
        collection.put("", "empty_key")
        collection.put("empty_value", "")
        collection.put("", "") // Both empty
        
        assertEquals(2, collection.size) // "" key overwrites previous
        assertEquals("", collection[""])
        assertEquals("", collection["empty_value"])
        assertTrue(collection.containsKey(""))
        assertTrue(collection.containsKey("empty_value"))
        assertTrue(collection.containsValue(""))
    }

    @Test
    fun testEdgeCases_whitespaceStrings() {
        val state = e<String>()
        val collection = es<String, String>()
        
        val whitespaceStrings = listOf(
            " ", // single space
            "  ", // multiple spaces
            "\t", // tab
            "\n", // newline
            "\r", // carriage return
            "\r\n", // Windows line ending
            "\t\n\r ", // mixed whitespace
            "   \t\n\r   " // complex whitespace
        )
        
        // Test whitespace in state
        whitespaceStrings.forEach { ws ->
            state.value = ws
            assertEquals(ws, state.value)
            assertFalse(state.isEmpty())
        }
        
        // Test whitespace as keys and values in collections
        whitespaceStrings.forEachIndexed { index, ws ->
            collection.put("key$index", ws)
            collection.put(ws, "value$index")
        }
        
        assertEquals(whitespaceStrings.size * 2, collection.size)
        
        whitespaceStrings.forEachIndexed { index, ws ->
            assertEquals(ws, collection["key$index"])
            assertEquals("value$index", collection[ws])
        }
    }

    // ==================== Unicode and Special Characters ====================

    @Test
    fun testEdgeCases_unicodeCharacters() {
        val state = ep<String>()
        val collection = eps<String, String>()
        
        val unicodeStrings = listOf(
            "αβγδεζηθικλμνξοπρστυφχψω", // Greek
            "абвгдеёжзийклмнопрстуфхцчшщъыьэюя", // Cyrillic
            "你好世界", // Chinese
            "こんにちは世界", // Japanese
            "안녕하세요 세계", // Korean
            "مرحبا بالعالم", // Arabic
            "हैलो वर्ल्ड", // Hindi
            "🌍🌎🌏🚀🌟💯🎉🔥❤️🌈", // Emojis
            "👨‍👩‍👧‍👦👩‍💻🧑‍🚀", // Complex emojis
            "🇺🇸🇬🇧🇩🇪🇫🇷🇯🇵", // Flag emojis
            "𝕌𝕟𝕚𝕔𝕠𝕕𝕖", // Mathematical symbols
            "♠♥♦♣♪♫♬", // Symbols
            "①②③④⑤⑥⑦⑧⑨⑩" // Circled numbers
        )
        
        // Test unicode in state
        unicodeStrings.forEach { unicode ->
            state.value = unicode
            assertEquals(unicode, state.value)
        }
        
        // Test unicode in collections
        unicodeStrings.forEachIndexed { index, unicode ->
            collection.put("unicode$index", unicode)
            collection.put(unicode, "value$index")
        }
        
        assertEquals(unicodeStrings.size * 2, collection.size)
        
        unicodeStrings.forEachIndexed { index, unicode ->
            assertEquals(unicode, collection["unicode$index"])
            assertEquals("value$index", collection[unicode])
        }
    }

    @Test
    fun testEdgeCases_specialCharacters() {
        val state = e<String>()
        val collection = es<String, String>()
        
        val specialStrings = listOf(
            "!@#$%^&*()_+-=", // Special symbols
            "[]{}|\\:;\"'<>?", // Brackets and quotes
            ".,/~`", // Punctuation
            "\\/\"'`", // Escape-prone characters
            "file://path/to/file", // URL-like
            "C:\\Users\\Name\\Documents", // Windows path
            "/usr/local/bin/app", // Unix path
            "user@domain.com", // Email-like
            "SELECT * FROM table WHERE id = 1;", // SQL-like
            "{\"key\": \"value\", \"number\": 42}", // JSON-like
            "<tag attribute=\"value\">content</tag>", // XML-like
            "<!-- comment -->", // HTML comment
            "function() { return 'code'; }", // Code-like
            "\\x41\\x42\\x43", // Hex escape sequences
            "\\u0041\\u0042\\u0043" // Unicode escape sequences
        )
        
        specialStrings.forEach { special ->
            state.value = special
            assertEquals(special, state.value)
            
            collection.put("special_${special.hashCode()}", special)
            collection.put(special, "value_${special.hashCode()}")
        }
        
        assertEquals(specialStrings.size * 2, collection.size)
    }

    // ==================== Large Data Handling ====================

    @Test
    fun testEdgeCases_largeStrings() {
        val state = e<String>()
        
        val largeSizes = listOf(1000, 5000, 10000, 50000)
        
        largeSizes.forEach { size ->
            val largeString = "x".repeat(size)
            state.value = largeString
            assertEquals(size, state.value?.length)
            assertEquals(largeString, state.value)
        }
        
        // Test very large string
        val veryLargeString = "Large content ".repeat(10000) // ~140KB
        state.value = veryLargeString
        assertEquals(veryLargeString, state.value)
        assertTrue(state.value!!.length > 100000)
    }

    @Test
    fun testEdgeCases_largeCollections() {
        val collection = es<Int, String>()
        
        // Test large number of items
        val itemCount = 10000
        
        for (i in 1..itemCount) {
            collection.put(i, "value_$i")
        }
        
        assertEquals(itemCount, collection.size)
        
        // Verify random access still works
        val testKeys = listOf(1, 100, 1000, 5000, 10000)
        testKeys.forEach { key ->
            assertEquals("value_$key", collection[key])
        }
        
        // Test navigation on large collection
        assertEquals(1, collection.firstKey())
        assertEquals(itemCount, collection.lastKey())
        assertEquals(5000, collection.ceilingKey(5000))
        assertEquals(4999, collection.lowerKey(5000))
    }

    @Test
    fun testEdgeCases_deepNesting() {
        val state = ep<Map<String, Map<String, List<EdgeCaseTestData>>>>()
        
        val deeplyNested = mapOf(
            "level1a" to mapOf(
                "level2a" to (1..100).map { i ->
                    EdgeCaseTestData(
                        id = "nested_$i",
                        nullableField = if (i % 2 == 0) "even_$i" else null,
                        emptyList = if (i % 3 == 0) listOf("item$i") else emptyList(),
                        emptyMap = if (i % 5 == 0) mapOf("key$i" to "value$i") else emptyMap(),
                        unicodeContent = "Content $i: αβγ 🌍",
                        largeNumber = i.toLong() * 1000000L,
                        specialChars = "Special!@#$%^&*()$i"
                    )
                },
                "level2b" to emptyList()
            ),
            "level1b" to mapOf(
                "level2c" to listOf(
                    EdgeCaseTestData(id = "single", unicodeContent = "Single item with 🎉 emoji")
                )
            ),
            "empty_level1" to emptyMap()
        )
        
        state.value = deeplyNested
        assertEquals(deeplyNested, state.value)
        
        // Verify deep access
        assertEquals(100, state.value?.get("level1a")?.get("level2a")?.size)
        assertEquals(0, state.value?.get("level1a")?.get("level2b")?.size)
        assertEquals(1, state.value?.get("level1b")?.get("level2c")?.size)
        assertEquals(0, state.value?.get("empty_level1")?.size)
        assertEquals("nested_1", state.value?.get("level1a")?.get("level2a")?.get(0)?.id)
        assertEquals("Single item with 🎉 emoji", state.value?.get("level1b")?.get("level2c")?.get(0)?.unicodeContent)
    }

    // ==================== Numerical Edge Cases ====================

    @Test
    fun testEdgeCases_numericalLimits() {
        val intState = e<Int>()
        val longState = e<Long>()
        val doubleState = e<Double>()
        val floatState = e<Float>()
        
        // Test integer limits
        intState.value = Int.MAX_VALUE
        assertEquals(Int.MAX_VALUE, intState.value)
        
        intState.value = Int.MIN_VALUE
        assertEquals(Int.MIN_VALUE, intState.value)
        
        intState.value = 0
        assertEquals(0, intState.value)
        
        // Test long limits
        longState.value = Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, longState.value)
        
        longState.value = Long.MIN_VALUE
        assertEquals(Long.MIN_VALUE, longState.value)
        
        // Test double limits and special values
        doubleState.value = Double.MAX_VALUE
        assertEquals(Double.MAX_VALUE, doubleState.value)
        
        doubleState.value = Double.MIN_VALUE
        assertEquals(Double.MIN_VALUE, doubleState.value)
        
        doubleState.value = Double.POSITIVE_INFINITY
        assertEquals(Double.POSITIVE_INFINITY, doubleState.value)
        
        doubleState.value = Double.NEGATIVE_INFINITY
        assertEquals(Double.NEGATIVE_INFINITY, doubleState.value)
        
        doubleState.value = Double.NaN
        assertTrue(doubleState.value?.isNaN() == true)
        
        // Test float limits
        floatState.value = Float.MAX_VALUE
        assertEquals(Float.MAX_VALUE, floatState.value)
        
        floatState.value = Float.MIN_VALUE
        assertEquals(Float.MIN_VALUE, floatState.value)
        
        floatState.value = Float.POSITIVE_INFINITY
        assertEquals(Float.POSITIVE_INFINITY, floatState.value)
        
        floatState.value = Float.NEGATIVE_INFINITY
        assertEquals(Float.NEGATIVE_INFINITY, floatState.value)
        
        floatState.value = Float.NaN
        assertTrue(floatState.value?.isNaN() == true)
    }

    @Test
    fun testEdgeCases_numericalOperationsInCollections() {
        val intCollection = es<Int, Double>()
        
        // Test with various numerical keys and values
        val testData = mapOf(
            Int.MAX_VALUE to Double.MAX_VALUE,
            Int.MIN_VALUE to Double.MIN_VALUE,
            0 to 0.0,
            1 to Double.POSITIVE_INFINITY,
            -1 to Double.NEGATIVE_INFINITY,
            42 to Double.NaN
        )
        
        testData.forEach { (key, value) ->
            intCollection.put(key, value)
        }
        
        assertEquals(testData.size, intCollection.size)
        
        testData.forEach { (key, value) ->
            val retrieved = intCollection[key]
            if (value.isNaN()) {
                assertTrue(retrieved?.isNaN() == true)
            } else {
                assertEquals(value, retrieved)
            }
        }
        
        // Test navigation with extreme values
        assertEquals(Int.MIN_VALUE, intCollection.firstKey())
        assertEquals(Int.MAX_VALUE, intCollection.lastKey())
    }

    // ==================== Secondary Key Edge Cases ====================

    @Test
    fun testEdgeCases_secondaryKeysWithNulls() {
        val collection = es<String, EdgeCaseTestData>(
            secondaryKeys = {
                key("nullableField") { it.nullableField }
                key("firstChar") { it.id.firstOrNull() }
                key("emptyCheck") { if (it.emptyList.isEmpty()) null else "has_items" }
                key("conditionalValue") { data ->
                    when {
                        data.largeNumber > 1000000 -> "large"
                        data.largeNumber > 0 -> "small"
                        else -> null
                    }
                }
            }
        )
        
        val testItems = listOf(
            EdgeCaseTestData("item1", "field1", emptyList(), emptyMap(), "", 2000000L),
            EdgeCaseTestData("item2", null, listOf("a"), emptyMap(), "", 500L),
            EdgeCaseTestData("", "field3", emptyList(), emptyMap(), "", 0L),
            EdgeCaseTestData("item4", null, emptyList(), emptyMap(), "", -100L)
        )
        
        testItems.forEachIndexed { index, item ->
            collection.put("key$index", item)
        }
        
        // Test null secondary key lookups
        val nullFields = collection.getBySecondaryKey("nullableField", null)
        assertEquals(2, nullFields.size) // item2 and item4
        
        val nonNullFields = collection.getBySecondaryKey("nullableField", "field1")
        assertEquals(1, nonNullFields.size)
        assertEquals("item1", nonNullFields[0].id)
        
        // Test first char with empty string
        val nullFirstChar = collection.getBySecondaryKey("firstChar", null)
        assertEquals(1, nullFirstChar.size) // empty string item
        
        val iFirstChar = collection.getBySecondaryKey("firstChar", 'i')
        assertEquals(3, iFirstChar.size) // item1, item2, item4
        
        // Test conditional secondary keys
        val emptyItems = collection.getBySecondaryKey("emptyCheck", null)
        assertEquals(3, emptyItems.size) // All except item2
        
        val hasItems = collection.getBySecondaryKey("emptyCheck", "has_items")
        assertEquals(1, hasItems.size)
        assertEquals("item2", hasItems[0].id)
        
        val largeNumbers = collection.getBySecondaryKey("conditionalValue", "large")
        assertEquals(1, largeNumbers.size)
        assertEquals("item1", largeNumbers[0].id)
        
        val smallNumbers = collection.getBySecondaryKey("conditionalValue", "small")
        assertEquals(1, smallNumbers.size)
        assertEquals("item2", smallNumbers[0].id)
        
        val nullConditional = collection.getBySecondaryKey("conditionalValue", null)
        assertEquals(2, nullConditional.size) // item with empty id and item4
    }

    @Test
    fun testEdgeCases_secondaryKeysComplexTypes() {
        val collection = es<String, EdgeCaseTestData>(
            secondaryKeys = {
                key("mapKeys") { data -> data.emptyMap.keys.firstOrNull() }
                key("listSize") { data -> data.emptyList.size }
                key("contentWords") { data -> data.unicodeContent.split(" ").size }
                key("numberRange") { data -> 
                    when {
                        data.largeNumber < 0 -> "negative"
                        data.largeNumber == 0L -> "zero"
                        data.largeNumber < 1000 -> "small"
                        data.largeNumber < 1000000 -> "medium"
                        else -> "large"
                    }
                }
            }
        )
        
        val complexItems = listOf(
            EdgeCaseTestData(
                id = "complex1",
                emptyList = listOf("a", "b", "c"),
                emptyMap = mapOf("key1" to "value1", "key2" to "value2"),
                unicodeContent = "Hello world test",
                largeNumber = 500L
            ),
            EdgeCaseTestData(
                id = "complex2",
                emptyList = emptyList(),
                emptyMap = emptyMap(),
                unicodeContent = "Single",
                largeNumber = 0L
            ),
            EdgeCaseTestData(
                id = "complex3",
                emptyList = listOf("x"),
                emptyMap = mapOf("first" to "value"),
                unicodeContent = "Multiple words in this content string",
                largeNumber = 5000000L
            )
        )
        
        complexItems.forEachIndexed { index, item ->
            collection.put("complex$index", item)
        }
        
        // Test map keys
        val key1Items = collection.getBySecondaryKey("mapKeys", "key1")
        assertEquals(1, key1Items.size)
        assertEquals("complex1", key1Items[0].id)
        
        val firstItems = collection.getBySecondaryKey("mapKeys", "first")
        assertEquals(1, firstItems.size)
        assertEquals("complex3", firstItems[0].id)
        
        val nullMapKeys = collection.getBySecondaryKey("mapKeys", null)
        assertEquals(1, nullMapKeys.size)
        assertEquals("complex2", nullMapKeys[0].id)
        
        // Test list sizes
        val emptyLists = collection.getBySecondaryKey("listSize", 0)
        assertEquals(1, emptyLists.size)
        
        val singleItemLists = collection.getBySecondaryKey("listSize", 1)
        assertEquals(1, singleItemLists.size)
        
        val threeItemLists = collection.getBySecondaryKey("listSize", 3)
        assertEquals(1, threeItemLists.size)
        
        // Test content words
        val oneWord = collection.getBySecondaryKey("contentWords", 1)
        assertEquals(1, oneWord.size)
        assertEquals("complex2", oneWord[0].id)
        
        val multipleWords = collection.getBySecondaryKey("contentWords", 6)
        assertEquals(1, multipleWords.size)
        assertEquals("complex3", multipleWords[0].id)
        
        // Test number ranges
        val zeroRange = collection.getBySecondaryKey("numberRange", "zero")
        assertEquals(1, zeroRange.size)
        assertEquals("complex2", zeroRange[0].id)
        
        val smallRange = collection.getBySecondaryKey("numberRange", "small")
        assertEquals(1, smallRange.size)
        assertEquals("complex1", smallRange[0].id)
        
        val largeRange = collection.getBySecondaryKey("numberRange", "large")
        assertEquals(1, largeRange.size)
        assertEquals("complex3", largeRange[0].id)
    }

    // ==================== Concurrent Modification Edge Cases ====================

    @Test
    fun testEdgeCases_concurrentModificationExceptions() {
        val collection = es<Int, String>()
        
        for (i in 1..100) {
            collection.put(i, "value$i")
        }
        
        // Test key iterator concurrent modification
        val keyIterator = collection.keys.iterator()
        keyIterator.next()
        collection.put(101, "new_value")
        
        assertFailsWith<TreeMap.ConcurrentModificationException> {
            keyIterator.next()
        }
        
        // Test value iterator concurrent modification
        val valueIterator = collection.values.iterator()
        valueIterator.next()
        collection.remove(50)
        
        assertFailsWith<TreeMap.ConcurrentModificationException> {
            valueIterator.next()
        }
        
        // Test entry iterator concurrent modification
        val entryIterator = collection.entries.iterator()
        entryIterator.next()
        collection.clear()
        
        assertFailsWith<TreeMap.ConcurrentModificationException> {
            entryIterator.next()
        }
    }

    @Test
    fun testEdgeCases_iteratorEdgeCases() {
        val collection = es<String, Int>()
        
        // Test iterator on empty collection
        val emptyIterator = collection.keys.iterator()
        assertFalse(emptyIterator.hasNext())
        assertFailsWith<NoSuchElementException> {
            emptyIterator.next()
        }
        
        // Add some data
        collection.put("a", 1)
        collection.put("b", 2)
        collection.put("c", 3)
        
        // Test iterator remove edge cases
        val iterator = collection.keys.iterator()
        
        // Remove without next should fail
        assertFailsWith<IllegalStateException> {
            iterator.remove()
        }
        
        // Normal remove
        val firstKey = iterator.next()
        iterator.remove()
        assertEquals(2, collection.size)
        assertNull(collection[firstKey])
        
        // Remove again without next should fail
        assertFailsWith<IllegalStateException> {
            iterator.remove()
        }
        
        // Continue iteration
        val secondKey = iterator.next()
        assertEquals(2, collection.size)
        assertTrue(collection.containsKey(secondKey))
    }

    // ==================== Default Value Edge Cases ====================

    @Test
    fun testEdgeCases_defaultValueExceptions() {
        // Test exception in default value computation
        assertFailsWith<RuntimeException> {
            val state = e<String> { 
                throw RuntimeException("Default value error")
            }
            // Accessing value should trigger default computation
            state.value
        }
        
        // Test null default value
        val nullDefaultState = e<String?> { null }
        assertNull(nullDefaultState.value)
        assertTrue(nullDefaultState.isEmpty())
        
        // Test complex default value
        val complexDefaultState = e<List<String>> { 
            listOf("default1", "default2", "default3")
        }
        assertEquals(listOf("default1", "default2", "default3"), complexDefaultState.value)
        assertFalse(complexDefaultState.isEmpty())
    }

    // ==================== SubMap Edge Cases ====================

    @Test
    fun testEdgeCases_subMapInvalidRanges() {
        val collection = es<String, Int>()
        
        collection.put("a", 1)
        collection.put("b", 2)
        collection.put("c", 3)
        
        // Invalid range should throw
        assertFailsWith<IllegalArgumentException> {
            collection.subMap("z", true, "a", false)
        }
        
        // Valid but empty ranges should work
        val emptySubMap = collection.subMap("x", true, "z", false)
        assertTrue(emptySubMap.isEmpty())
        assertEquals(0, emptySubMap.size)
        
        // Single element ranges
        val singleElementSubMap = collection.subMap("b", true, "c", false)
        assertEquals(1, singleElementSubMap.size)
        assertTrue(singleElementSubMap.containsKey("b"))
        assertFalse(singleElementSubMap.containsKey("c"))
    }

    @Test
    fun testEdgeCases_toString() {
        val state = e<String>()
        assertEquals("null", state.toString())
        
        state.value = "test"
        assertEquals("test", state.toString())
        
        state.value = ""
        assertEquals("", state.toString())
        
        state.value = null
        assertEquals("null", state.toString())
        
        // Test with complex objects
        val objectState = e<EdgeCaseTestData>()
        assertEquals("null", objectState.toString())
        
        objectState.value = EdgeCaseTestData(id = "test", unicodeContent = "Test 🎉")
        assertTrue(objectState.toString().contains("test"))
        assertTrue(objectState.toString().contains("Test 🎉"))
    }

    // ==================== Type Safety Edge Cases ====================

    @Test
    fun testEdgeCases_typeSafety() {
        // These should all compile and work correctly
        val stringState = e<String>()
        val nullableStringState = e<String?>()
        val intState = e<Int>()
        val nullableIntState = e<Int?>()
        val listState = e<List<String>>()
        val mapState = e<Map<String, Int>>()
        val complexState = e<EdgeCaseTestData>()
        
        // Basic operations should work with all types
        stringState.value = "test"
        nullableStringState.value = null
        intState.value = 42
        nullableIntState.value = null
        listState.value = listOf("a", "b", "c")
        mapState.value = mapOf("key" to 123)
        complexState.value = EdgeCaseTestData(id = "test")
        
        assertEquals("test", stringState.value)
        assertNull(nullableStringState.value)
        assertEquals(42, intState.value)
        assertNull(nullableIntState.value)
        assertEquals(listOf("a", "b", "c"), listState.value)
        assertEquals(mapOf("key" to 123), mapState.value)
        assertEquals("test", complexState.value?.id)
    }
}