package jst.oktopoi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * WASM-JS implementation that executes coroutines immediately.
 * 
 * Since WASM-JS is single-threaded, we can execute suspend functions
 * that don't actually suspend (like our ReadWriteLock operations) synchronously.
 */
@OptIn(DelicateCoroutinesApi::class)
actual fun <T> runBlockingMultiplatform(block: suspend CoroutineScope.() -> T): T {
    // For WASM-JS, since ReadWriteLock operations don't actually suspend
    // in single-threaded environment, we can execute them immediately
    var result: T? = null
    var exception: Throwable? = null
    var completed = false
    
    // Launch the coroutine
    GlobalScope.launch(Dispatchers.Unconfined) {
        try {
            result = block()
            completed = true
        } catch (e: Throwable) {
            exception = e
            completed = true
        }
    }
    
    // In single-threaded WASM-JS, the coroutine should complete immediately
    // if it doesn't actually suspend
    if (completed) {
        exception?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    
    // If we get here, the coroutine suspended, which shouldn't happen
    throw IllegalStateException("Unexpected suspension in WASM-JS runBlockingMultiplatform")
}