package jst.oktopoi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * JVM implementation using standard kotlinx.coroutines.runBlocking
 */
actual fun <T> runBlockingMultiplatform(block: suspend CoroutineScope.() -> T): T = runBlocking(block = block)