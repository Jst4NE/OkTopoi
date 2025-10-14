package jst.oktopoi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

// On WASM/JS, use Default as a stand-in for IO
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

@OptIn(DelicateCoroutinesApi::class)
actual fun <T> runBlockingMultiplatform(block: suspend CoroutineScope.() -> T): T {
    var result: T? = null
    var exception: Throwable? = null
    var completed = false

    GlobalScope.launch(Dispatchers.Unconfined) {
        try {
            result = block()
            completed = true
        } catch (e: Throwable) {
            exception = e
            completed = true
        }
    }

    if (completed) {
        exception?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    throw IllegalStateException("Unexpected suspension in WASM-JS runBlockingMultiplatform")
}
