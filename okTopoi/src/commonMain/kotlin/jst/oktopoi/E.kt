package jst.oktopoi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime


@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class E<ValueType : Any?>(
    private val persisted: PersistedEInfo<ValueType>?,
    private val observing: StateFlow<ValueType>?,
    val defaultValue: (() -> ValueType?)?
) : MutableStateFlow<ValueType?> {

    private val state: MutableStateFlow<ValueType?> = MutableStateFlow(defaultValue?.invoke())

    lateinit var callingClassName: String
    lateinit var propertyName: String

    @OptIn(ExperimentalTime::class)
    internal fun setup() {
        if (persisted != null) {
            persistCoroutineScope.launch {

                val rootDir: Path
                if (persisted.rootDir == null) {
                    rootDir =
                        initDefaultIO.filter { it?.second == persisted.fileSystem }.first()!!.first
                } else {
                    initIO.filter { it?.first == persisted.rootDir && it.second == persisted.fileSystem }
                        .first()!!
                    rootDir = persisted.rootDir
                }

                val fileSystem: FileSystem = persisted.fileSystem

                val dirPath = Path(rootDir, callingClassName)
                val filePath = Path(dirPath, propertyName)

                fileSystem.createDirectories(dirPath)

                if (fileSystem.exists(filePath) && (fileSystem.metadataOrNull(filePath)?.size
                        ?: 0) != 0L
                ) {
                    fileSystem.source(filePath)
                        .buffered()
                        .use {
                            state.value = Json.decodeFromString(
                                persisted.valueSerializer,
                                it.readString()
                            )
                        }
                }

                state
                    .drop(1)
                    .onEach { value ->
                        fileSystem.sink(filePath).buffered().use { sink ->
                            sink.writeString(Json.encodeToString(persisted.valueSerializer, value))
                        }
                    }
                    .launchIn(persistCoroutineScope)

            }
        }

        if (observing != null) {
            persistCoroutineScope.launch {
                observing.onEach { state.emit(it) }.collect()
            }
        }
    }

    override var value: ValueType?
        get() = state.value
        set(value) {
            state.value = value
        }

    override fun compareAndSet(expect: ValueType?, update: ValueType?): Boolean {
        return state.compareAndSet(expect, update)
    }

    override val replayCache: List<ValueType?>
        get() = state.replayCache

    override suspend fun collect(collector: FlowCollector<ValueType?>): Nothing {
        return state.collect(collector)
    }

    override val subscriptionCount: StateFlow<Int>
        get() = state.subscriptionCount

    override suspend fun emit(value: ValueType?) {
        return state.emit(value)
    }

    override fun tryEmit(value: ValueType?): Boolean {
        return state.tryEmit(value)
    }

    @ExperimentalCoroutinesApi
    override fun resetReplayCache() {
        return state.resetReplayCache()
    }

    fun get(): ValueType? {
        return value
    }

    fun isEmpty(): Boolean {
        return value === null
    }

    fun set(newValue: ValueType) {
        value = newValue
    }

    fun setIfDifferent(newValue: ValueType?): Boolean {
        var currentValue: ValueType?
        do {
            currentValue = value
            if (currentValue === newValue || currentValue == newValue) {
                return false
            }
        } while (!compareAndSet(currentValue, newValue))
        return true
    }

    fun clear() {
        value = null
    }

    override fun toString(): String {
        return value.toString()
    }

}

data class PersistedEInfo<ValueType : Any?>(
    val valueSerializer: KSerializer<ValueType?>,
    val rootDir: Path? = null,
    val fileSystem: FileSystem
)