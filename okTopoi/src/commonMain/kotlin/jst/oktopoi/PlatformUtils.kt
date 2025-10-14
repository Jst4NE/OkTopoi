package jst.oktopoi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

// Unified expect declarations
expect val ioDispatcher: CoroutineDispatcher

expect fun <T> runBlockingMultiplatform(block: suspend CoroutineScope.() -> T): T
