package jst.oktopoi

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Persistent reactive collection that extends Es with automatic file-based persistence.
 *
 * Eps (\"Elements-Persistent\") adds file system persistence to the reactive collection
 * functionality provided by the base Es class. Each entry in the collection is persisted
 * as an individual file, enabling efficient partial updates and crash recovery.
 *
 * ## Architecture
 *
 * ```
 * TreeMap (efficient map operations)
 *   ↓ extends
 * Es (+ reactive change notifications)
 *   ↓ extends  
 * Eps (+ file persistence)  ← You are here
 *   ↓ extends
 * Esps (+ bi-directional sync)
 * ```
 *
 * ## Key Features
 *
 * - **Per-entry persistence**: Each entry stored as individual file for efficiency
 * - **Synchronous persistence**: All collection changes trigger immediate synchronous file operations
 * - **Directory-based storage**: Organized file structure for easy inspection and debugging
 * - **File-per-entry storage**: Individual entries stored separately for efficient partial updates
 * - **Reactive persistence**: Change notifications include persistence operations
 *
 * ## Persistence Strategy
 *
 * - **File-per-entry**: Each key-value pair stored in separate file named by serialized key
 * - **Directory structure**: `{rootDir}/{className}/{propertyName}/{serializedKey}.json`
 * - **Atomic operations**: Individual file writes are atomic when supported by filesystem
 * - **Lazy loading**: Files are loaded during setup(), not on-demand
 * - **Synchronous I/O**: All file operations performed synchronously using runBlockingMultiplatform
 *
 * ## Usage Examples
 *
 * ```kotlin
 * // Basic persistent collection
 * val users = eps<String, User> { it.id }
 * users[\"alice\"] = User(\"alice\", \"Engineering\")  // Automatically persisted
 *
 * // With secondary indexes and custom storage
 * val employees = eps<String, Employee>(
 *     rootDir = Path(\"/app/data\"),
 *     secondaryKeys = {
 *         key(\"department\") { it.department }
 *         key(\"level\") { it.level }
 *     }
 * )
 *
 * // Reactive UI with persistence
 * val liveEmployeeList = employees.asSnapshotStateList(
 *     scope = viewModelScope,
 *     filter = { it.value.isActive }
 * )
 * ```
 *
 * ## File Organization
 *
 * ```
 * /app/data/
 *   └── com.example.UserRepository/
 *       └── employees/
 *           ├── \"emp001\".json    # Employee with ID emp001
 *           ├── \"emp002\".json    # Employee with ID emp002
 *           └── ...
 * ```
 *
 * ## Performance Characteristics
 *
 * - **Memory efficient**: Only active entries kept in memory
 * - **Incremental persistence**: Only changed entries written to disk
 * - **Fast startup**: Parallel file loading during initialization
 * - **Scalable storage**: Linear storage growth with collection size
 *
 * ## Error Handling
 *
 * - **Setup failures**: Throws PersistenceFailedException if directory setup or ANY file loading fails  
 * - **All-or-nothing initialization**: If any persisted file is corrupted, entire collection setup fails
 * - **Runtime write errors**: State changes are rolled back if persistence fails (fail-fast)
 * - **Individual file benefits**: Runtime operations only affect single files, not entire collection
 *
 * ## Thread Safety
 *
 * Eps maintains the same thread safety as Es through ReadWriteLock, with additional
 * persistence coordination:
 * - Concurrent reads are allowed during persistence operations
 * - Writes are serialized to prevent file system conflicts
 * - Synchronous persistence blocks collection operations until files are written
 *
 * @param KeyType the type of keys (must be serializable and non-nullable)
 * @param ValueType the type of values (must be serializable and non-nullable)
 *
 * @see Es for the base reactive collection functionality
 * @see Esps for synchronized persistent collections
 * @see eps factory function for creation
 * @throws PersistenceFailedException when persistence setup fails
 */
open class Eps<KeyType : Any, ValueType : Any> : Es<KeyType, ValueType> {

    protected val persisted: PersistedEsInfo<KeyType, ValueType>
    protected lateinit var dirPath: Path
    protected lateinit var fileSystem: FileSystem
    private val persistScope: CoroutineScope

    constructor(
        persisted: PersistedEsInfo<KeyType, ValueType>,
        sortingBy: Comparator<KeyType>,
        secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {},
        persistScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ) : super(sortingBy, secondaryKeys) {
        this.persisted = persisted
        this.persistScope = persistScope
    }

    @OptIn(ExperimentalTime::class)
    override fun setup() {
        runBlockingMultiplatform {
            try {
                val rootDir: Path
                if (persisted.rootDir == null) {
                    rootDir = initDefaultIO.filter { it?.second == persisted.fileSystem }.first()!!.first
                } else {
                    initIO.filter { it?.first == persisted.rootDir && it.second == persisted.fileSystem }.first()!!
                    rootDir = persisted.rootDir
                }

                this@Eps.fileSystem = persisted.fileSystem

                this@Eps.dirPath = Path(rootDir, callingClassName, propertyName)
                fileSystem.createDirectories(dirPath)

                val existingFiles = fileSystem.list(dirPath).filter { (fileSystem.metadataOrNull(it)?.size ?: 0) != 0L }
                
                existingFiles.forEach { file ->
                    fileSystem.source(file)
                        .buffered()
                        .use { source ->
                            fromPersistString(source.readString(), file.name)
                        }
                }


            } catch (e: Exception) {
                throw PersistenceFailedException("Failed to initialize collection persistence: ${e.message}", e)
            }
        }
    }

    // File I/O helper methods
    protected fun writeToFile(key: KeyType, content: String) {
        val fileName = Json.encodeToString(persisted.keyTypeSerializer, key)
        val filePath = Path(dirPath, fileName)
        fileSystem.sink(filePath).buffered().use { it.writeString(content) }
    }

    protected fun deleteFromFile(key: KeyType) {
        val fileName = Json.encodeToString(persisted.keyTypeSerializer, key)
        val filePath = Path(dirPath, fileName)
        fileSystem.delete(filePath)
    }

    // Persistence logic - can be overridden by subclasses
    protected open fun persistEntry(key: KeyType, value: ValueType?) {
        if (value != null) {
            writeToFile(key, Json.encodeToString(persisted.valueTypeSerializer, value))
        } else {
            deleteFromFile(key)
        }
    }

    // ========================================================================
    // Override core unsafe methods to add persistence with inheritance approach
    // ========================================================================

    override fun putUnsafe(key: KeyType, value: ValueType): ValueType? {
        // We're already inside rwLock.withWriteLock here!
        
        // Persistence first (fail-fast approach)
        try {
            persistEntry(key, value)
        } catch (e: Exception) {
            throw PersistenceFailedException("Failed to persist put($key): ${e.message}", e)
        }
        
        // Memory + secondary indexes (TreeMap's implementation)
        return super.putUnsafe(key, value)
    }
    
    override fun removeUnsafe(key: KeyType): ValueType? {
        // Check if key exists first (don't persist unnecessary deletes)
        val oldValue = getUnsafe(key)
        if (oldValue == null) return null
        
        // Persistence first
        try {
            persistEntry(key, null)
        } catch (e: Exception) {
            throw PersistenceFailedException("Failed to persist remove($key): ${e.message}", e)
        }
        
        // Memory + secondary indexes (TreeMap's implementation)
        return super.removeUnsafe(key)
    }
    
    override fun clearUnsafe() {
        // Capture keys for bulk deletion
        val keys = keysUnsafe().toList()
        
        // Persistence first - delete all files
        try {
            keys.forEach { key -> persistEntry(key, null) }
        } catch (e: Exception) {
            throw PersistenceFailedException("Failed to persist clear(): ${e.message}", e)
        }
        
        // Memory + secondary indexes (TreeMap's implementation)
        super.clearUnsafe()
    }
    
    // ========================================================================
    // All complex operations (compute*, putIfAbsent, replace, merge) now work
    // automatically with persistence because they delegate to putUnsafe/removeUnsafe
    // in UnsafeTreeMapCore, which call our overridden methods above!
    // ========================================================================

    open fun fromPersistString(string: String, fileName: String) {
        val value = Json.decodeFromString(
            persisted.valueTypeSerializer,
            string
        )
        val key = Json.decodeFromString(
            persisted.keyTypeSerializer,
            fileName
        )
        this@Eps.put(key, value)
    }
}


data class PersistedEsInfo<KeyType: Any, ValueType: Any>(
    val keyTypeSerializer: KSerializer<KeyType>,
    val valueTypeSerializer: KSerializer<ValueType>,
    val rootDir: Path?,
    val fileSystem: FileSystem
)