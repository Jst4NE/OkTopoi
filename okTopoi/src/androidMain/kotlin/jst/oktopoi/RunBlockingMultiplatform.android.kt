package jst.oktopoi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

actual fun <T> runBlockingMultiplatform(block: suspend CoroutineScope.() -> T): T {
    return runBlocking(block = block)
}