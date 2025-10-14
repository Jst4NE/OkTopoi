package jst.oktopoi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun <T> runBlockingMultiplatform(block: suspend CoroutineScope.() -> T): T = runBlocking(block = block)
