package jst.oktopoi

import kotlinx.coroutines.CoroutineScope

/**
 * Multiplatform implementation of runBlocking functionality.
 * 
 * On JVM/Native: Uses the standard kotlinx.coroutines.runBlocking
 * On JS: Uses custom implementation with immediate execution
 */
expect fun <T> runBlockingMultiplatform(block: suspend CoroutineScope.() -> T): T