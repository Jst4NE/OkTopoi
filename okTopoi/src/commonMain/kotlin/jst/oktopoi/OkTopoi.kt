package jst.oktopoi

import jst.oktopoi.TreeMap.SecondaryKeyBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

internal val persistCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
internal val initDefaultIO = MutableStateFlow<Pair<Path, FileSystem>?>(null)
internal val initIO = MutableStateFlow<Pair<Path, FileSystem>?>(null)

fun initDefaultIO(defaultRootDir: Path, fileSystem: FileSystem = SystemFileSystem) {
    initDefaultIO.value = Pair(defaultRootDir, fileSystem)
}

fun initRootDirIO(initRootDir: Path, fileSystem: FileSystem = SystemFileSystem) {
    initIO.value = Pair(initRootDir, fileSystem)
}

inline fun <reified ValueType : Any?> e(noinline defaultValue: (() -> ValueType?)? = null): E<ValueType> {
    return E<ValueType>(
        persisted = null,
        defaultValue = defaultValue,
        observing = null
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified ValueType : Any?> ep(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline defaultValue: (() -> ValueType?)? = null
): E<ValueType> {
    return E<ValueType>(
        persisted = PersistedEInfo(
            serializer<ValueType?>(),
            rootDir,
            fileSystem
        ),
        defaultValue = defaultValue,
        observing = null
    )
}

inline fun <reified KeyType : Comparable<KeyType>, reified ValueType : Any> es(
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    noinline secondaryKeys: SecondaryKeyBuilder<ValueType>.() -> Unit = {}
): Es<KeyType, ValueType> {
    return Es<KeyType, ValueType>(
        persisted = null,
        keySelector = keySelector,
        sortingBy = null,
        secondaryKeys = secondaryKeys
    )
}

inline fun <reified KeyType : Any, reified ValueType : Any> es(
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    comparator: Comparator<KeyType>,
    noinline secondaryKeys: SecondaryKeyBuilder<ValueType>.() -> Unit = {}
): Es<KeyType, ValueType> {
    return Es<KeyType, ValueType>(
        persisted = null,
        keySelector = keySelector,
        sortingBy = comparator,
        secondaryKeys = secondaryKeys
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified KeyType : Comparable<KeyType>, reified ValueType : Any> eps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    noinline secondaryKeys: SecondaryKeyBuilder<ValueType>.() -> Unit = {}
): Es<KeyType, ValueType> {
    return Es<KeyType, ValueType>(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
        keySelector = keySelector,
        sortingBy = null,
        secondaryKeys = secondaryKeys
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified KeyType : Any, reified ValueType : Any> eps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    sortingBy: Comparator<KeyType>,
    noinline secondaryKeys: SecondaryKeyBuilder<ValueType>.() -> Unit = {}
): Es<KeyType, ValueType> {
    return Es<KeyType, ValueType>(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
        keySelector = keySelector,
        sortingBy = sortingBy,
        secondaryKeys = secondaryKeys
    )
}