package jst.oktopoi

import jst.oktopoi.TreeMap.SecondaryIndexBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.StateFlow

/**
 * Exception thrown when persistence operations fail.
 * Indicates that data could not be written to disk, ensuring fail-fast behavior for data integrity.
 */
class PersistenceFailedException(message: String, cause: Throwable) : RuntimeException(message, cause)

internal val persistCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
internal val initDefaultIO = MutableStateFlow<Pair<Path, FileSystem>?>(null)
internal val initIO = MutableStateFlow<Pair<Path, FileSystem>?>(null)

fun initDefaultIO(defaultRootDir: Path, fileSystem: FileSystem = SystemFileSystem) {
    Logger.d("OkTopoi-Init") { "initDefaultIO called with rootDir: $defaultRootDir, fileSystem: $fileSystem" }
    initDefaultIO.value = Pair(defaultRootDir, fileSystem)
    Logger.d("OkTopoi-Init") { "initDefaultIO set successfully" }
}

fun initRootDirIO(initRootDir: Path, fileSystem: FileSystem = SystemFileSystem) {
    Logger.d("OkTopoi-Init") { "initRootDirIO called with rootDir: $initRootDir, fileSystem: $fileSystem" }
    initIO.value = Pair(initRootDir, fileSystem)
    Logger.d("OkTopoi-Init") { "initRootDirIO set successfully" }
}

inline fun <reified ValueType : Any?> e(noinline defaultValue: (() -> ValueType?)? = null): E<ValueType> {
    return E<ValueType>(
        observing = null,
        defaultValue = defaultValue
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified ValueType : Any?> ep(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline defaultValue: (() -> ValueType?)? = null
): Ep<ValueType> {
    return Ep<ValueType>(
        persisted = PersistedEInfo(
            serializer<ValueType?>(),
            rootDir,
            fileSystem
        ),
        observing = null,
        defaultValue = defaultValue,
        persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )
}

inline fun <reified KeyType : Comparable<KeyType>, reified ValueType : Any> es(
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
): Es<KeyType, ValueType> {
    return Es<KeyType, ValueType>(
        keySelector = keySelector,
        sortingBy = Comparator { k1, k2 -> k1.compareTo(k2) },
        secondaryKeys = secondaryKeys
    )
}

inline fun <reified KeyType : Any, reified ValueType : Any> es(
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    comparator: Comparator<KeyType>,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
): Es<KeyType, ValueType> {
    return Es<KeyType, ValueType>(
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
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
): Eps<KeyType, ValueType> {
    return Eps<KeyType, ValueType>(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
        keySelector = keySelector,
        sortingBy = Comparator { k1, k2 -> k1.compareTo(k2) },
        secondaryKeys = secondaryKeys,
        persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified KeyType : Any, reified ValueType : Any> eps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    sortingBy: Comparator<KeyType>,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
): Eps<KeyType, ValueType> {
    return Eps<KeyType, ValueType>(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
        keySelector = keySelector,
        sortingBy = sortingBy,
        secondaryKeys = secondaryKeys,
        persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified KeyType : Comparable<KeyType>, reified ValueType : Any> esps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {},
    incomingSync: Flow<Triple<KeyType, ValueType?, Long>>,
    noinline outgoingSync: suspend (KeyType, ValueType?, Long) -> Unit,
    syncActive: Flow<Boolean> = MutableStateFlow(true),
    syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
): Esps<KeyType, ValueType> {
    return Esps(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
        keySelector = keySelector,
        sortingBy = { k1, k2 -> k1.compareTo(k2) },
        secondaryKeys = secondaryKeys,
        incomingSync = incomingSync,
        outgoingSync = outgoingSync,
        syncActive = syncActive,
        syncScope = syncScope
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified KeyType : Any, reified ValueType : Any> esps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    sortingBy: Comparator<KeyType>,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {},
    incomingSync: Flow<Triple<KeyType, ValueType?, Long>>,
    noinline outgoingSync: suspend (KeyType, ValueType?, Long) -> Unit,
    syncActive: Flow<Boolean> = MutableStateFlow(true),
    syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
): Esps<KeyType, ValueType> {
    return Esps(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
        keySelector = keySelector,
        sortingBy = sortingBy,
        secondaryKeys = secondaryKeys,
        incomingSync = incomingSync,
        outgoingSync = outgoingSync,
        syncActive = syncActive,
        syncScope = syncScope
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified KeyType : Comparable<KeyType>, reified ValueType : Any> CoroutineScope.esps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {},
    incomingSync: Flow<Triple<KeyType, ValueType?, Long>>,
    noinline outgoingSync: suspend (KeyType, ValueType?, Long) -> Unit,
    syncActive: Flow<Boolean> = MutableStateFlow(true)
): Esps<KeyType, ValueType> {
    return Esps(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
        keySelector = keySelector,
        sortingBy = { k1, k2 -> k1.compareTo(k2) },
        secondaryKeys = secondaryKeys,
        incomingSync = incomingSync,
        outgoingSync = outgoingSync,
        syncActive = syncActive,
        syncScope = this
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified KeyType : Any, reified ValueType : Any> CoroutineScope.esps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    sortingBy: Comparator<KeyType>,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {},
    incomingSync: Flow<Triple<KeyType, ValueType?, Long>>,
    noinline outgoingSync: suspend (KeyType, ValueType?, Long) -> Unit,
    syncActive: Flow<Boolean> = MutableStateFlow(true)
): Esps<KeyType, ValueType> {
    return Esps(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
        keySelector = keySelector,
        sortingBy = sortingBy,
        secondaryKeys = secondaryKeys,
        incomingSync = incomingSync,
        outgoingSync = outgoingSync,
        syncActive = syncActive,
        syncScope = this
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified ValueType : Any?> esp(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline defaultValue: (() -> ValueType?)? = null,
    incomingSync: Flow<Triple<String, ValueType?, Long>>,
    noinline outgoingSync: suspend (String, ValueType?, Long) -> Unit,
    syncActive: Flow<Boolean> = MutableStateFlow(true),
    syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
): Esp<ValueType> {
    return Esp<ValueType>(
        persisted = PersistedEInfo(
            serializer<ValueType?>(),
            rootDir,
            fileSystem
        ),
        observing = null,
        defaultValue = defaultValue,
        incomingSync = incomingSync,
        outgoingSync = outgoingSync,
        syncActive = syncActive,
        syncScope = syncScope
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified ValueType : Any?> CoroutineScope.esp(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline defaultValue: (() -> ValueType?)? = null,
    incomingSync: Flow<Triple<String, ValueType?, Long>>,
    noinline outgoingSync: suspend (String, ValueType?, Long) -> Unit,
    syncActive: Flow<Boolean> = MutableStateFlow(true)
): Esp<ValueType> {
    return Esp<ValueType>(
        persisted = PersistedEInfo(
            serializer<ValueType?>(),
            rootDir,
            fileSystem
        ),
        observing = null,
        defaultValue = defaultValue,
        incomingSync = incomingSync,
        outgoingSync = outgoingSync,
        syncActive = syncActive,
        syncScope = this
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified ValueType : Any?> CoroutineScope.ep(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline defaultValue: (() -> ValueType?)? = null
): Ep<ValueType> {
    return Ep<ValueType>(
        persisted = PersistedEInfo(
            serializer<ValueType?>(),
            rootDir,
            fileSystem
        ),
        observing = null,
        defaultValue = defaultValue,
        persistScope = this
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified KeyType : Comparable<KeyType>, reified ValueType : Any> CoroutineScope.eps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
): Eps<KeyType, ValueType> {
    return Eps<KeyType, ValueType>(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
        keySelector = keySelector,
        sortingBy = Comparator { k1, k2 -> k1.compareTo(k2) },
        secondaryKeys = secondaryKeys,
        persistScope = this
    )
}

@OptIn(InternalSerializationApi::class)
inline fun <reified KeyType : Any, reified ValueType : Any> CoroutineScope.eps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline keySelector: ((ValueType) -> KeyType)? = null,
    sortingBy: Comparator<KeyType>,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
): Eps<KeyType, ValueType> {
    return Eps<KeyType, ValueType>(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
        keySelector = keySelector,
        sortingBy = sortingBy,
        secondaryKeys = secondaryKeys,
        persistScope = this
    )
}