package jst.oktopoi

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

class ThreadSafetyPerformanceTest {

    @Test
    fun testConcurrentReadPerformance() = runTest {
        val map = TreeMap<Int, String>()
        
        // Populate with test data
        repeat(1000) { i ->
            map.put(i, "value_$i")
        }
        
        // Measure concurrent reads
        val readTime = measureTime {
            val jobs = (1..10).map { 
                async {
                    repeat(1000) { i ->
                        map.getSuspend(i % 1000)
                    }
                }
            }
            jobs.awaitAll()
        }
        
        println("Concurrent reads (10 threads × 1000 reads): ${readTime}")
    }

    @Test
    fun testConcurrentWritePerformance() = runTest {
        val map = TreeMap<Int, String>()
        
        // Measure concurrent writes
        val writeTime = measureTime {
            val jobs = (1..5).map { threadId ->
                async {
                    repeat(200) { i ->
                        val key = threadId * 1000 + i
                        map.putSuspend(key, "value_$key")
                    }
                }
            }
            jobs.awaitAll()
        }
        
        println("Concurrent writes (5 threads × 200 writes): ${writeTime}")
        assertEquals(1000, map.size)
    }

    @Test
    fun testMixedWorkloadPerformance() = runTest {
        val map = TreeMap<Int, String>()
        
        // Pre-populate
        repeat(500) { i ->
            map.put(i, "initial_$i")
        }
        
        val mixedTime = measureTime {
            val readers = (1..8).map {
                async {
                    repeat(1000) { i ->
                        map.getSuspend(i % 500)
                    }
                }
            }
            
            val writers = (1..2).map { threadId ->
                async {
                    repeat(100) { i ->
                        val key = threadId * 1000 + i
                        map.putSuspend(key, "new_$key")
                    }
                }
            }
            
            (readers + writers).awaitAll()
        }
        
        println("Mixed workload (8 readers + 2 writers): ${mixedTime}")
        assertEquals(700, map.size) // 500 initial + 200 new
    }

    @Test
    fun testSecondaryKeyPerformance() = runTest {
        val es = Es<Int, TestData>(
            persisted = null,
            keySelector = { it.id },
            sortingBy = null
        ) {
            key("category") { it.category }
        }

        // Populate with test data
        repeat(1000) { i ->
            es.put(i, TestData(i, "category_${i % 10}", "data_$i"))
        }

        val secondaryKeyTime = measureTime {
            val jobs = (1..10).map {
                async {
                    repeat(100) { i ->
                        es.getBySecondaryKeySuspend("category", "category_${i % 10}")
                    }
                }
            }
            jobs.awaitAll()
        }

        println("Secondary key queries (10 threads × 100 queries): ${secondaryKeyTime}")
    }

    @Test
    fun testOperatorSyntaxPerformance() = runTest {
        val map = TreeMap<Int, String>()
        
        val operatorTime = measureTime {
            val jobs = (1..5).map { threadId ->
                async {
                    repeat(200) { i ->
                        val key = threadId * 1000 + i
                        map[key] = "value_$key"  // Uses suspend operator
                        val retrieved = map[key]  // Uses suspend operator
                    }
                }
            }
            jobs.awaitAll()
        }
        
        println("Operator syntax performance (5 threads × 200 ops): ${operatorTime}")
        assertEquals(1000, map.size)
    }

    @Test
    fun testBlockingApiPerformance() {
        val map = TreeMap<Int, String>()
        
        // Compare blocking API vs suspend API
        val blockingTime = measureTime {
            repeat(1000) { i ->
                map.put(i, "blocking_$i")  // Uses runBlocking internally
                map.get(i)  // Uses runBlocking internally
            }
        }
        
        val suspendTime = measureTime {
            runBlockingMultiplatform {
                repeat(1000) { i ->
                    map.putSuspend(i + 1000, "suspend_${i + 1000}")
                    map.getSuspend(i + 1000)
                }
            }
        }
        
        println("Blocking API time: $blockingTime")
        println("Suspend API time: $suspendTime")
        
        val ratio = blockingTime.inWholeNanoseconds.toDouble() / suspendTime.inWholeNanoseconds
        println("Blocking API is ${ratio}x slower than suspend API")
    }

    data class TestData(
        val id: Int,
        val category: String,
        val data: String
    )
}