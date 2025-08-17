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

@OptIn(ExperimentalAtomicApi::class)
class ReadWriteLock {
    private val readerCount = AtomicInt(0)
    private val waitingWriter = AtomicReference<CancellableContinuation<Unit>?>(null)
    private val mutex = Mutex()

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

    suspend fun <T> withWriteLock(block: suspend () -> T): T {
        mutex.withLock {
            var yields = 0
            while (readerCount.load() > 0) {
                if (yields < OkTopoiConstants.READ_WRITE_LOCK_MAX_YIELD_ATTEMPTS){
                    yields++
                    yield()
                } else{
                    suspendCancellableCoroutine<Unit> { continuation ->
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