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

/**
 * Creates an observable state container for a single value.
 * 
 * This is the simplest form of state management in OkTopoi - just reactive state without 
 * persistence or synchronization.
 * 
 * @param ValueType the type of value to store (can be nullable)
 * @param defaultValue optional factory function to provide initial/default value
 * @return observable state container that emits changes via StateFlow
 * 
 * @sample
 * ```kotlin
 * // Simple state
 * val counter = e { 0 }
 * 
 * // Nullable state  
 * val userName = e<String?> { null }
 * 
 * // Usage
 * counter.value = 42
 * userName.value = "Alice"
 * ```
 */
inline fun <reified ValueType : Any?> e(noinline defaultValue: (() -> ValueType?)? = null): E<ValueType> {
    return E<ValueType>(
        observing = null,
        defaultValue = defaultValue
    )
}

// ================================================================================================
// FACTORY FUNCTIONS - State Management & Collections
// ================================================================================================

/**
 * # OkTopoi Factory Function Decision Guide
 * 
 * Choose the right factory function for your use case:
 * 
 * ## Single Values:
 * - **`e()`** - Observable state only
 * - **`ep()`** - Observable + File persistence  
 * - **`esp()`** - Observable + File persistence + Bidirectional sync
 * 
 * ## Collections:
 * - **`es()`** - Observable indexed collections
 *   - Use `es<ComparableKey, Value>()` for natural ordering (String, Int, etc.)
 *   - Use `es<AnyKey, Value>(comparator)` for custom ordering
 * - **`eps()`** - Observable + File persistence
 *   - Same ordering options as `es()`
 * - **`esps()`** - Observable + File persistence + Bidirectional sync
 *   - Same ordering options as `es()`
 * 
 * ## Scoping:
 * - Most functions have `CoroutineScope.function()` variants
 * - Use scoped versions to tie lifecycle to a specific scope
 * - Non-scoped versions create their own background scope
 * 
 * ## Quick Examples:
 * ```kotlin
 * // Simple observable state
 * val counter = e { 0 }
 * 
 * // Persistent user preferences
 * val prefs = ep<UserSettings> { UserSettings.default() }
 * 
 * // Collection with natural ordering
 * val users = es<String, User> { it.id }
 * 
 * // Collection with custom ordering + persistence
 * val tasks = eps<Task, TaskInfo>(
 *     keySelector = { it.task },
 *     comparator = compareBy { it.priority }
 * )
 * ```
 */

/**
 * Creates a persistent state container that automatically saves/loads values to/from disk.
 * 
 * This extends basic observable state with file system persistence. All state changes are 
 * automatically persisted to disk. If the file exists on creation, the persisted value is loaded.
 * 
 * **Important:** Persistence setup failures throw PersistenceFailedException. If persistence
 * cannot be established, the function fails fast rather than creating a non-persistent fallback.
 * 
 * @param ValueType the type of value to store (must be serializable)
 * @param rootDir root directory for persistence files (null = use default from initDefaultIO)
 * @param fileSystem file system implementation to use (default: SystemFileSystem)
 * @param defaultValue factory function for initial value when no persisted data exists
 * @return persistent state container with automatic disk synchronization
 * @throws PersistenceFailedException if persistence setup fails (directory creation, file access, etc.)
 * 
 * @sample
 * ```kotlin
 * // Basic persistent state
 * val userPrefs = ep<UserSettings> { UserSettings.default() }
 * 
 * // Custom storage location
 * val config = ep<AppConfig>(
 *     rootDir = Path("/custom/config"),
 *     defaultValue = { AppConfig.defaultConfig() }
 * )
 * 
 * // Usage - automatically persisted
 * userPrefs.value = newSettings  // Automatically saved to disk
 * val current = userPrefs.value  // Loaded from disk if available
 * ```
 * 
 * @see initDefaultIO to configure default storage location
 * @see e for non-persistent state
 * @see esp for synchronized persistent state
 */
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

/**
 * Creates an observable indexed collection with natural key ordering.
 * 
 * Use this overload when your KeyType implements Comparable (String, Int, Long, etc.).
 * The collection will be sorted using the natural ordering of keys.
 * 
 * Performance: O(log n) for get/put/remove operations, O(1) for secondary index lookups.
 * 
 * @param KeyType the key type (must implement Comparable for natural ordering)
 * @param ValueType the value type stored in the collection
 * @param keySelector function to extract keys from values (null = values used directly as keys)
 * @param secondaryKeys configuration block to define secondary indexes for fast filtering
 * @return observable collection with efficient operations and reactive change notifications
 * 
 * @sample
 * ```kotlin
 * // Collection keyed by comparable type (String)
 * val users = es<String, User> { user -> user.id }
 * 
 * // With secondary indexes for fast filtering
 * val employees = es<String, Employee>(
 *     keySelector = { it.employeeId },
 *     secondaryKeys = {
 *         key("department") { it.department }
 *         key("level") { it.level }
 *         key("location") { it.office.city }
 *     }
 * )
 * 
 * // Usage
 * employees["emp123"] = Employee("John", "Engineering", "Senior")
 * val engineering = employees.getBy("department" to "Engineering")
 * val seniorEngineers = employees.getBy(
 *     "department" to "Engineering",
 *     "level" to "Senior"
 * )
 * ```
 * 
 * @see es(comparator) for custom key ordering
 * @see eps for persistent collections
 * @see esps for synchronized collections
 */
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

/**
 * Creates an observable indexed collection with custom key ordering.
 * 
 * Use this overload when you need custom sorting logic or when your KeyType doesn't 
 * implement Comparable. The collection will be sorted using your provided comparator.
 * 
 * Performance: O(log n) for get/put/remove operations, O(1) for secondary index lookups.
 * 
 * @param KeyType the key type (can be any type, doesn't need to be Comparable)
 * @param ValueType the value type stored in the collection
 * @param keySelector function to extract keys from values (null = values used directly as keys)
 * @param comparator custom comparator for key ordering
 * @param secondaryKeys configuration block to define secondary indexes for fast filtering
 * @return observable collection with efficient operations and reactive change notifications
 * 
 * @sample
 * ```kotlin
 * // Custom object keys with specific ordering
 * val tasksByPriority = es<Task, TaskDetails>(
 *     keySelector = { it.task },
 *     comparator = compareBy<Task> { it.priority }.thenBy { it.dueDate }
 * )
 * 
 * // Reverse alphabetical ordering for strings
 * val reverseUsers = es<String, User>(
 *     keySelector = { it.name },
 *     comparator = compareByDescending { it }
 * )
 * 
 * // Complex multi-criteria sorting
 * val products = es<Product, ProductInfo>(
 *     keySelector = { it.product },
 *     comparator = compareBy<Product> { it.category }
 *         .thenBy { it.price }
 *         .thenBy { it.name },
 *     secondaryKeys = {
 *         key("category") { it.product.category }
 *         key("inStock") { it.quantity > 0 }
 *     }
 * )
 * ```
 * 
 * @see es() for natural key ordering (when KeyType is Comparable)
 * @see eps for persistent collections
 * @see esps for synchronized collections
 */
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

/**
 * Creates a persistent indexed collection with comparable keys using natural ordering.
 * 
 * This function creates a collection that automatically persists all changes to the file system.
 * Elements are sorted by their natural ordering (keys must implement Comparable).
 * Supports secondary indexing for efficient multi-criteria lookups.
 * 
 * **Required Setup:** Call `initDefaultIO(rootDir)` before using this function, or provide explicit `rootDir`.
 * 
 * Performance: O(log n) for get/put/remove operations, O(1) for secondary index lookups.
 * 
 * @param KeyType the type of keys (must implement Comparable)
 * @param ValueType the type of values to store
 * @param rootDir root directory for persistence files (null = use default from initDefaultIO)
 * @param fileSystem file system implementation to use (default: SystemFileSystem)
 * @param keySelector function to extract keys from values, or null for manual key assignment
 * @param secondaryKeys configuration block for secondary indexes
 * 
 * @see initDefaultIO to configure default storage location
 * @see initRootDirIO to configure specific storage location
 * @see es for non-persistent collections
 * @see esps for synchronized persistent collections
 */
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

/**
 * Creates a persistent indexed collection with custom key ordering.
 * 
 * This function creates a collection that automatically persists all changes to the file system.
 * Elements are sorted using your provided comparator - use this when keys don't implement Comparable
 * or when you need custom ordering logic.
 * 
 * **Required Setup:** Call `initDefaultIO(rootDir)` before using this function, or provide explicit `rootDir`.
 * 
 * Performance: O(log n) for get/put/remove operations, O(1) for secondary index lookups.
 * 
 * @param KeyType the type of keys (any type)
 * @param ValueType the type of values to store  
 * @param rootDir root directory for persistence files (null = use default from initDefaultIO)
 * @param fileSystem file system implementation to use (default: SystemFileSystem)
 * @param keySelector function to extract keys from values, or null for manual key assignment
 * @param comparator custom comparator for key ordering
 * @param secondaryKeys configuration block for secondary indexes
 * 
 * @see initDefaultIO to configure default storage location
 * @see initRootDirIO to configure specific storage location  
 * @see es for non-persistent collections
 * @see esps for synchronized persistent collections
 */
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

/**
 * Creates a synchronized persistent indexed collection with comparable keys using natural ordering.
 * 
 * This function creates a collection that automatically persists all changes to the file system
 * AND synchronizes bidirectionally with other instances through provided sync flows.
 * Elements are sorted by their natural ordering (keys must implement Comparable).
 * 
 * **Required Setup:** Call `initDefaultIO(rootDir)` before using this function, or provide explicit `rootDir`.
 * 
 * Performance: O(log n) for get/put/remove operations, O(1) for secondary index lookups.
 * 
 * @param KeyType the type of keys (must implement Comparable)
 * @param ValueType the type of values to store
 * @param rootDir root directory for persistence files (null = use default from initDefaultIO)
 * @param fileSystem file system implementation to use (default: SystemFileSystem)
 * @param keySelector function to extract keys from values, or null for manual key assignment
 * @param secondaryKeys configuration block for secondary indexes
 * @param incomingSync flow of incoming changes from remote sources
 * @param outgoingSync function to send local changes to remote sources
 * @param syncActive flow controlling whether sync is active (default: always on)
 * @param syncScope coroutine scope for sync operations
 * 
 * @see initDefaultIO to configure default storage location
 * @see initRootDirIO to configure specific storage location
 * @see eps for persistent collections without sync
 * @see es for non-persistent collections
 */
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

/**
 * Creates a synchronized persistent observable state container.
 * 
 * This function creates a state container that automatically persists all changes to the file system
 * AND synchronizes bidirectionally with other instances through provided sync flows.
 * State changes are reactive and can be observed using Flow operations.
 * 
 * **Required Setup:** Call `initDefaultIO(rootDir)` before using this function, or provide explicit `rootDir`.
 * 
 * @param ValueType the type of value to store (can be nullable)
 * @param rootDir root directory for persistence files (null = use default from initDefaultIO)
 * @param fileSystem file system implementation to use (default: SystemFileSystem)
 * @param defaultValue factory function for initial value when no persisted data exists
 * @param incomingSync flow of incoming changes from remote sources  
 * @param outgoingSync function to send local changes to remote sources
 * @param syncActive flow controlling whether sync is active (default: always on)
 * @param syncScope coroutine scope for sync operations
 * 
 * @see initDefaultIO to configure default storage location
 * @see initRootDirIO to configure specific storage location
 * @see ep for persistent state without sync
 * @see e for non-persistent state
 */
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

// TreeMap factory functions for type-safe creation

/**
 * Creates a TreeMap with comparable keys using natural ordering.
 * Returns a high-performance TreeMap with O(log n) operations and secondary indexing.
 */
fun <K : Comparable<K>, V : Any> treeMap(
    configure: TreeMap.SecondaryIndexBuilder<K, V>.() -> Unit = {}
): TreeMap<K, V> {
    return TreeMap<K, V>(
        keyComparator = compareBy { it }
    ).apply {
        TreeMap.SecondaryIndexBuilder(this).configure()
    }
}

/**
 * Creates a TreeMap with custom key comparator for any key type.
 * Returns a high-performance TreeMap with O(log n) operations and secondary indexing.
 */
fun <K : Any, V : Any> treeMap(
    comparator: Comparator<K>,
    configure: TreeMap.SecondaryIndexBuilder<K, V>.() -> Unit = {}
): TreeMap<K, V> {
    return TreeMap<K, V>(
        keyComparator = comparator
    ).apply {
        TreeMap.SecondaryIndexBuilder(this).configure()
    }
}

