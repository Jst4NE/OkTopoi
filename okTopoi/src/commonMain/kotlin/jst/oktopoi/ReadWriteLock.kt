package jst.oktopoi

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.coroutines.resume

/**
 * A suspend-based read-write lock implementation optimized for Kotlin coroutines.
 *
 * This lock allows multiple concurrent readers OR a single writer, but not both simultaneously.
 * It provides non-blocking suspension for coroutines, making it ideal for use in async contexts
 * where traditional blocking locks would be inappropriate.
 *
 * ## Concurrency Model
 *
 * - **Multiple Readers**: Any number of coroutines can hold read locks simultaneously
 * - **Single Writer**: Only one coroutine can hold a write lock at a time
 * - **Reader-Writer Exclusion**: Read and write operations are mutually exclusive
 * - **Write Priority**: Writers wait for all active readers to complete before proceeding
 *
 * ## Performance Characteristics
 *
 * - **Mutex-protected reads**: Read acquisition uses mutex to ensure thread safety
 * - **Optimized writes**: Writers yield briefly before suspending, optimizing for short read operations
 * - **Suspension-based**: No thread blocking - uses coroutine suspension for waiting
 * - **Cancellation-safe**: Properly handles coroutine cancellation during lock acquisition
 *
 * ## Usage Pattern
 *
 * ```kotlin
 * val lock = ReadWriteLock()
 * val data = mutableMapOf<String, String>()
 *
 * // Multiple concurrent readers
 * suspend fun readData(key: String): String? {
 *     return lock.withReadLock {
 *         data[key] // Fast concurrent read access
 *     }
 * }
 *
 * // Exclusive writer
 * suspend fun writeData(key: String, value: String) {
 *     lock.withWriteLock {
 *         data[key] = value // Exclusive write access
 *     }
 * }
 * ```
 *
 * ## Implementation Details
 *
 * - Uses `AtomicInt` for reader counting to avoid lock contention on reads
 * - Writers yield up to `READ_WRITE_LOCK_MAX_YIELD_ATTEMPTS` times before suspending
 * - Employs a `Mutex` to serialize write lock acquisition
 * - Supports cancellation via `CancellableContinuation` for suspended writers
 *
 * @see OkTopoiConstants.READ_WRITE_LOCK_MAX_YIELD_ATTEMPTS for yield configuration
 */
@OptIn(ExperimentalAtomicApi::class)
class ReadWriteLock {
    private val readerCount = AtomicInt(0)
    private val waitingWriter = AtomicReference<CancellableContinuation<Unit>?>(null)
    private val mutex = Mutex()

    /**
     * Executes the given block while holding a read lock.
     *
     * Multiple coroutines can hold read locks simultaneously, allowing concurrent read access.
     * Read locks are mutually exclusive with write locks.
     *
     * @param block the suspend function to execute under read lock
     * @return the result of executing the block
     * @throws kotlin.coroutines.cancellation.CancellationException if the calling coroutine is cancelled
     */
    suspend fun <T> withReadLock(block: suspend () -> T): T {
        mutex.withLock { readerCount.addAndFetch(1) }
        try {
            return block()
        } finally {
            if (readerCount.decrementAndFetch() == 0) {
                waitingWriter.exchange(null)?.resume(Unit)
            }
        }
    }

    /**
     * Executes the given block while holding an exclusive write lock.
     *
     * Only one coroutine can hold a write lock at a time, and write locks are
     * mutually exclusive with read locks. The writer will wait for all active
     * readers to complete before proceeding.
     *
     * The implementation optimizes for short read operations by yielding briefly
     * before suspending, reducing latency when reads complete quickly.
     *
     * @param block the suspend function to execute under write lock
     * @return the result of executing the block
     * @throws kotlin.coroutines.cancellation.CancellationException if the calling coroutine is cancelled
     */
    suspend fun <T> withWriteLock(block: suspend () -> T): T {
        mutex.withLock {
            var yields = 0
            while (readerCount.load() > 0) {
                if (yields < OkTopoiConstants.READ_WRITE_LOCK_MAX_YIELD_ATTEMPTS){
                    yields++
                    yield()
                } else{
                    suspendCancellableCoroutine { continuation ->
                        waitingWriter.store(continuation)
                        continuation.invokeOnCancellation {
                            waitingWriter.store(null)
                        }
                    }
                    return block()
                }
            }

            return block()
        }
    }
}