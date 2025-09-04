package jst.oktopoi

import co.touchlab.kermit.Logger
import jst.oktopoi.TreeMap.SecondaryIndexBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

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


// ================================================================================================
// FACTORY FUNCTIONS - State Management & Collections
// ================================================================================================

/**
 * # OkTopoi Factory Function Decision Guide
 * 
 * Choose the right factory function for your use case:
 * 
 * ## Single Values:
 * - **`e { defaultValue }`** - Observable non-null state (defaultValue required)
 * - **`e<T?>()`** - Observable nullable state (defaultValue optional)
 * - **`ep { defaultValue }`** - Persistent non-null state (defaultValue required)
 * - **`ep<T?>()`** - Persistent nullable state (defaultValue optional)  
 * - **`esp { defaultValue }`** - Synchronized persistent non-null state (defaultValue required)
 * - **`esp<T?>()`** - Synchronized persistent nullable state (defaultValue optional)
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
 * // Non-null observable state (type-safe)
 * val counter = e { 0 }                    // E<Int> - never null
 * val name = e { "default" }               // E<String> - never null
 * val current: Int = counter.value         // Int, not Int?
 * 
 * // Nullable observable state
 * val userName = e<String?> { "guest" }    // E<String?> - can be null  
 * val userAge = e<Int?>()                  // E<Int?> - starts null
 * val currentName: String? = userName.value // String?
 * 
 * // Persistent user preferences (non-null)
 * val prefs = ep { UserSettings.default() }    // Ep<UserSettings> - never null
 * 
 * // Persistent nullable state
 * val token = ep<String?> { "default-token" }  // Ep<String?> - can be null
 * 
 * // Collection with natural ordering
 * val users = es<String, User> { it.id }
 * 
 * // Collection with custom ordering + persistence
 * val tasks = eps<Task, TaskInfo>(
 *     comparator = compareBy { it.priority }
 * )
 * ```
 */


/**
 * Creates an observable state container for nullable values.
 *
 * This overload is for nullable types where the defaultValue is optional.
 * The state container can hold null values and provides type-safe nullable access.
 *
 * @param ValueType the nullable type of value to store
 * @param defaultValue optional factory function to provide initial/default value
 * @return observable state container with nullable type safety
 *
 * @sample
 * ```kotlin
 * // Nullable state with default
 * val userName = e<String?> { "guest" }
 * userName.value = null                    // Allowed
 * val current: String? = userName.value    // String?
 *
 * // Nullable state without default
 * val userAge = e<Int?>()                  // Starts as null
 * userAge.value = 25
 * ```
 *
 * @see e(defaultValue) for non-null state containers
 */
@OktopoiNewInstanceFactory
inline fun <reified ValueType : Any?> e(observing: StateFlow<ValueType>? = null, noinline defaultValue: (() -> ValueType)? = null): E<ValueType> {
    return E(
        observing = observing,
        defaultValue = defaultValue
    )
}

/**
 * Creates an observable state container for non-null values.
 *
 * This overload is for non-null types where the defaultValue is required.
 * The state container cannot hold null values and provides type-safe non-null access.
 *
 * @param ValueType the non-null type of value to store
 * @param defaultValue required factory function that must return a non-null value
 * @return observable state container with non-null type safety
 *
 * @sample
 * ```kotlin
 * // Non-null state (defaultValue required)
 * val counter = e { 0 }                    // E<Int> - never null
 * val name = e { "default" }               // E<String> - never null
 * counter.value = 42                       // Int (not Int?)
 * val current: Int = counter.value         // Type-safe non-null access
 *
 * // This would be a compile error:
 * // val broken = e<String>()              // Missing required defaultValue
 * // counter.value = null                  // Cannot assign null to non-null type
 * ```
 *
 * @see e() for nullable state containers
 */
@OktopoiNewInstanceFactory
inline fun <reified ValueType : Any> e(noinline defaultValue: (() -> ValueType)): E<ValueType> {
    return E(
        observing = null,
        defaultValue = defaultValue
    )
}

/**
 * Creates a persistent state container for nullable values.
 * 
 * This overload is for nullable types where the defaultValue is optional.
 * All state changes are automatically persisted to disk with type-safe nullable access.
 * 
 * **Important:** Persistence setup failures throw PersistenceFailedException. If persistence
 * cannot be established, the function fails fast rather than creating a non-persistent fallback.
 * 
 * @param ValueType the nullable type of value to store (must be serializable)
 * @param defaultValue optional factory function for initial value when no persisted data exists
 * @param observing optional external StateFlow to observe and mirror  
 * @param rootDir root directory for persistence files (null = use default from initDefaultIO)
 * @param fileSystem file system implementation to use (default: SystemFileSystem)
 * @return persistent state container with nullable type safety
 * @throws PersistenceFailedException if persistence setup fails (directory creation, file access, etc.)
 * 
 * @sample
 * ```kotlin
 * // Nullable persistent state with default
 * val userName = ep<String?> { "guest" }
 * userName.value = null                    // Allowed
 * val current: String? = userName.value    // String?
 * 
 * // Nullable persistent state without default  
 * val userAge = ep<Int?>()                 // Starts as null
 * userAge.value = 25
 * 
 * // With external observation
 * val synced = ep<Data?>(observing = externalFlow)
 * ```
 * 
 * @see ep(defaultValue) for non-null persistent state
 * @see initDefaultIO to configure default storage location
 * @see e for non-persistent state
 * @see esp for synchronized persistent state
 */
@OptIn(InternalSerializationApi::class)
@OktopoiNewInstanceFactory
inline fun <reified ValueType : Any?> ep(
    observing: StateFlow<ValueType>? = null,
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline defaultValue: (() -> ValueType?)? = null
): Ep<ValueType> {
    return Ep(
        persisted = PersistedEInfo(
            serializer<ValueType?>(),
            rootDir,
            fileSystem
        ),
        observing = observing,
        defaultValue = defaultValue,
        persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )
}

/**
 * Creates a persistent state container for non-null values.
 * 
 * This overload is for non-null types where the defaultValue is required.
 * All state changes are automatically persisted to disk with type-safe non-null access.
 * The state container cannot hold null values.
 * 
 * **Important:** Persistence setup failures throw PersistenceFailedException. If persistence
 * cannot be established, the function fails fast rather than creating a non-persistent fallback.
 * 
 * @param ValueType the non-null type of value to store (must be serializable)
 * @param defaultValue required factory function that must return a non-null value
 * @param rootDir root directory for persistence files (null = use default from initDefaultIO)
 * @param fileSystem file system implementation to use (default: SystemFileSystem)
 * @return persistent state container with non-null type safety
 * @throws PersistenceFailedException if persistence setup fails (directory creation, file access, etc.)
 * 
 * @sample
 * ```kotlin
 * // Non-null persistent state (defaultValue required)
 * val counter = ep { 0 }                   // Ep<Int> - never null
 * val config = ep { AppConfig.default() }  // Ep<AppConfig> - never null
 * counter.value = 42                       // Int (not Int?)
 * val current: Int = counter.value         // Type-safe non-null access
 * 
 * // This would be a compile error:
 * // val broken = ep<String>()             // Missing required defaultValue
 * // counter.value = null                  // Cannot assign null to non-null type
 * ```
 * 
 * @see ep() for nullable persistent state
 * @see initDefaultIO to configure default storage location
 * @see e for non-persistent state
 * @see esp for synchronized persistent state
 */
@OptIn(InternalSerializationApi::class)
@OktopoiNewInstanceFactory
inline fun <reified ValueType : Any> ep(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline defaultValue: () -> ValueType
): Ep<ValueType> {
    return Ep(
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
@OktopoiNewInstanceFactory
inline fun <reified KeyType : Comparable<KeyType>, reified ValueType : Any> es(
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
): Es<KeyType, ValueType> {
    return Es(
        sortingBy = { k1, k2 -> k1.compareTo(k2) },
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
 * @param comparator custom comparator for key ordering
 * @param secondaryKeys configuration block to define secondary indexes for fast filtering
 * @return observable collection with efficient operations and reactive change notifications
 * 
 * @sample
 * ```kotlin
 * // Custom object keys with specific ordering
 * val tasksByPriority = es<Task, TaskDetails>(
 *     comparator = compareBy<Task> { it.priority }.thenBy { it.dueDate }
 * )
 * 
 * // Reverse alphabetical ordering for strings
 * val reverseUsers = es<String, User>(
 *     comparator = compareByDescending { it }
 * )
 * 
 * // Complex multi-criteria sorting
 * val products = es<Product, ProductInfo>(
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
@OktopoiNewInstanceFactory
inline fun <reified KeyType : Any, reified ValueType : Any> es(
    comparator: Comparator<KeyType>,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
): Es<KeyType, ValueType> {
    return Es(
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
 * @param secondaryKeys configuration block for secondary indexes
 * 
 * @see initDefaultIO to configure default storage location
 * @see initRootDirIO to configure specific storage location
 * @see es for non-persistent collections
 * @see esps for synchronized persistent collections
 */
@OptIn(InternalSerializationApi::class)
@OktopoiNewInstanceFactory
inline fun <reified KeyType : Comparable<KeyType>, reified ValueType : Any> eps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
): Eps<KeyType, ValueType> {
    return Eps(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
        sortingBy = naturalOrder(),
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
 * @param sortingBy custom comparator for key ordering
 * @param secondaryKeys configuration block for secondary indexes
 * 
 * @see initDefaultIO to configure default storage location
 * @see initRootDirIO to configure specific storage location  
 * @see es for non-persistent collections
 * @see esps for synchronized persistent collections
 */
@OptIn(InternalSerializationApi::class)
@OktopoiNewInstanceFactory
inline fun <reified KeyType : Any, reified ValueType : Any> eps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    sortingBy: Comparator<KeyType>,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
): Eps<KeyType, ValueType> {
    return Eps(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
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
@OktopoiNewInstanceFactory
inline fun <reified KeyType : Comparable<KeyType>, reified ValueType : Any> esps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
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
        sortingBy = { k1, k2 -> k1.compareTo(k2) },
        secondaryKeys = secondaryKeys,
        incomingSync = incomingSync,
        outgoingSync = outgoingSync,
        syncActive = syncActive,
        syncScope = syncScope
    )
}

@OptIn(InternalSerializationApi::class)
@OktopoiNewInstanceFactory
inline fun <reified KeyType : Any, reified ValueType : Any> esps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
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
        sortingBy = sortingBy,
        secondaryKeys = secondaryKeys,
        incomingSync = incomingSync,
        outgoingSync = outgoingSync,
        syncActive = syncActive,
        syncScope = syncScope
    )
}

@OptIn(InternalSerializationApi::class)
@OktopoiNewInstanceFactory
inline fun <reified KeyType : Comparable<KeyType>, reified ValueType : Any> CoroutineScope.esps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
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
        sortingBy = naturalOrder(),
        secondaryKeys = secondaryKeys,
        incomingSync = incomingSync,
        outgoingSync = outgoingSync,
        syncActive = syncActive,
        syncScope = this
    )
}

@OptIn(InternalSerializationApi::class)
@OktopoiNewInstanceFactory
inline fun <reified KeyType : Any, reified ValueType : Any> CoroutineScope.esps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
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
        sortingBy = sortingBy,
        secondaryKeys = secondaryKeys,
        incomingSync = incomingSync,
        outgoingSync = outgoingSync,
        syncActive = syncActive,
        syncScope = this
    )
}

/**
 * Creates a synchronized persistent state container for nullable values.
 * 
 * This overload is for nullable types where the defaultValue is optional.
 * The state container automatically persists all changes to the file system AND synchronizes
 * bidirectionally with other instances through provided sync flows.
 * 
 * **Required Setup:** Call `initDefaultIO(rootDir)` before using this function, or provide explicit `rootDir`.
 * 
 * @param ValueType the nullable type of value to store (must be serializable)
 * @param defaultValue optional factory function for initial value when no persisted data exists
 * @param observing optional external StateFlow to observe and mirror
 * @param rootDir root directory for persistence files (null = use default from initDefaultIO)
 * @param fileSystem file system implementation to use (default: SystemFileSystem)
 * @param incomingSync flow of incoming changes from remote sources  
 * @param outgoingSync function to send local changes to remote sources
 * @param syncActive flow controlling whether sync is active (default: always on)
 * @param syncScope coroutine scope for sync operations
 * 
 * @sample
 * ```kotlin
 * // Nullable synchronized persistent state
 * val userName = esp<String?>(
 *     defaultValue = { "guest" },
 *     incomingSync = userSyncFlow,
 *     outgoingSync = { key, value, timestamp -> sendToRemote(key, value, timestamp) }
 * )
 * userName.value = null                    // Allowed
 * val current: String? = userName.value    // String?
 * ```
 * 
 * @see esp(defaultValue, ...) for non-null synchronized persistent state
 * @see initDefaultIO to configure default storage location
 * @see ep for persistent state without sync
 * @see e for non-persistent state
 */
@OptIn(InternalSerializationApi::class)
@OktopoiNewInstanceFactory
inline fun <reified ValueType : Any?> esp(
    observing: StateFlow<ValueType>? = null,
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    incomingSync: Flow<Triple<String, ValueType?, Long>>,
    noinline outgoingSync: suspend (String, ValueType?, Long) -> Unit,
    syncActive: Flow<Boolean> = MutableStateFlow(true),
    syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    noinline defaultValue: (() -> ValueType?)? = null
): Esp<ValueType> {
    return Esp(
        persisted = PersistedEInfo(
            serializer<ValueType?>(),
            rootDir,
            fileSystem
        ),
        observing = observing,
        defaultValue = defaultValue,
        incomingSync = incomingSync,
        outgoingSync = outgoingSync,
        syncActive = syncActive,
        syncScope = syncScope
    )
}

/**
 * Creates a synchronized persistent state container for non-null values.
 * 
 * This overload is for non-null types where the defaultValue is required.
 * The state container automatically persists all changes to the file system AND synchronizes
 * bidirectionally with other instances through provided sync flows.
 * The state container cannot hold null values.
 * 
 * **Required Setup:** Call `initDefaultIO(rootDir)` before using this function, or provide explicit `rootDir`.
 * 
 * @param ValueType the non-null type of value to store (must be serializable)
 * @param defaultValue required factory function that must return a non-null value
 * @param rootDir root directory for persistence files (null = use default from initDefaultIO)
 * @param fileSystem file system implementation to use (default: SystemFileSystem)
 * @param incomingSync flow of incoming changes from remote sources  
 * @param outgoingSync function to send local changes to remote sources
 * @param syncActive flow controlling whether sync is active (default: always on)
 * @param syncScope coroutine scope for sync operations
 * 
 * @sample
 * ```kotlin
 * // Non-null synchronized persistent state (defaultValue required)
 * val counter = esp(
 *     defaultValue = { 0 },
 *     incomingSync = counterSyncFlow,
 *     outgoingSync = { key, value, timestamp -> sendToRemote(key, value, timestamp) }
 * )
 * counter.value = 42                       // Int (not Int?)
 * val current: Int = counter.value         // Type-safe non-null access
 * 
 * // This would be a compile error:
 * // val broken = esp<String>(...)         // Missing required defaultValue
 * // counter.value = null                  // Cannot assign null to non-null type
 * ```
 * 
 * @see esp() for nullable synchronized persistent state
 * @see initDefaultIO to configure default storage location
 * @see ep for persistent state without sync
 * @see e for non-persistent state
 */
@OptIn(InternalSerializationApi::class)
@OktopoiNewInstanceFactory
inline fun <reified ValueType : Any> esp(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    incomingSync: Flow<Triple<String, ValueType?, Long>>,
    noinline outgoingSync: suspend (String, ValueType?, Long) -> Unit,
    syncActive: Flow<Boolean> = MutableStateFlow(true),
    syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    noinline defaultValue: () -> ValueType
): Esp<ValueType> {
    return Esp(
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
@OktopoiNewInstanceFactory
inline fun <reified ValueType : Any?> CoroutineScope.esp(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline defaultValue: (() -> ValueType?)? = null,
    incomingSync: Flow<Triple<String, ValueType?, Long>>,
    noinline outgoingSync: suspend (String, ValueType?, Long) -> Unit,
    syncActive: Flow<Boolean> = MutableStateFlow(true)
): Esp<ValueType> {
    return Esp(
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
@OktopoiNewInstanceFactory
inline fun <reified ValueType : Any?> CoroutineScope.ep(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline defaultValue: (() -> ValueType?)? = null
): Ep<ValueType> {
    return Ep(
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
@OktopoiNewInstanceFactory
inline fun <reified KeyType : Comparable<KeyType>, reified ValueType : Any> CoroutineScope.eps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
): Eps<KeyType, ValueType> {
    return Eps(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
        sortingBy = { k1, k2 -> k1.compareTo(k2) },
        secondaryKeys = secondaryKeys,
        persistScope = this
    )
}

@OptIn(InternalSerializationApi::class)
@OktopoiNewInstanceFactory
inline fun <reified KeyType : Any, reified ValueType : Any> CoroutineScope.eps(
    rootDir: Path? = null,
    fileSystem: FileSystem = SystemFileSystem,
    sortingBy: Comparator<KeyType>,
    noinline secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
): Eps<KeyType, ValueType> {
    return Eps(
        persisted = PersistedEsInfo(
            serializer<KeyType>(),
            serializer<ValueType>(),
            rootDir,
            fileSystem
        ),
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
    configure: SecondaryIndexBuilder<K, V>.() -> Unit = {}
): TreeMap<K, V> {
    return TreeMap<K, V>(
        keyComparator = naturalOrder()
    ).apply {
        SecondaryIndexBuilder(this).configure()
    }
}

/**
 * Creates a TreeMap with custom key comparator for any key type.
 * Returns a high-performance TreeMap with O(log n) operations and secondary indexing.
 */
fun <K : Any, V : Any> treeMap(
    comparator: Comparator<K>,
    configure: SecondaryIndexBuilder<K, V>.() -> Unit = {}
): TreeMap<K, V> {
    return TreeMap<K, V>(
        keyComparator = comparator
    ).apply {
        SecondaryIndexBuilder(this).configure()
    }
}

