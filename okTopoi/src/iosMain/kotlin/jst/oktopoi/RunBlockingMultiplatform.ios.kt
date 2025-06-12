package jst.oktopoi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * iOS Native implementation using standard kotlinx.coroutines.runBlocking
 */
actual fun <T> runBlockingMultiplatform(block: suspend CoroutineScope.() -> T): T = runBlocking(block = block)