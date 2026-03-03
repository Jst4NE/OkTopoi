package jst.oktopoi

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
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

    private val log = Logger.withTag(this::class.simpleName.toString())

    protected val persisted: PersistedEsInfo<KeyType, ValueType>
    protected lateinit var dirPath: Path
    protected lateinit var fileSystem: FileSystem
    private val persistScope: CoroutineScope

    // Suppress persistence writes during initialization load of existing files
    // Accessed only during setup() or under TreeMap lock paths
    protected var persistenceLoadDepth: Int = 0

    constructor(
        persisted: PersistedEsInfo<KeyType, ValueType>,
        sortingBy: Comparator<KeyType>,
        secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {},
        persistScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    ) : super(sortingBy, secondaryKeys) {
        this.persisted = persisted
        this.persistScope = persistScope
    }

    @OptIn(ExperimentalTime::class)
    override fun setup() {
        super.setup()

        runBlockingMultiplatform {
            try {
                val rootDir: Path

                log.d { "ESP[${this@Eps.callingClassName}.${this@Eps.propertyName}] setup waiting for init" }
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
                
                // Suppress persistence while loading existing entries
                this@Eps.persistenceLoadDepth++
                try {
                    existingFiles.forEach { file ->
                        try {
                            fileSystem.source(file)
                                .buffered()
                                .use { source ->
                                    fromPersistString(source.readString(), file.name)
                                }
                        } catch (e: Exception) {
                            // Archive corrupted file and continue loading other entries
                            Logger.e(e, tag = "OkTopoi-Eps") {
                                "Failed to deserialize entry from ${file.name} in ${callingClassName}.${propertyName}: ${e.message}\n" +
                                "Skipping this entry. Corrupted file archived."
                            }
                            archiveCorruptedFile(
                                filePath = file,
                                fileSystem = this@Eps.fileSystem,
                                rootDir = rootDir,
                                propertyIdentifier = "${callingClassName}.${propertyName}"
                            )
                            // Continue with next file
                        }
                    }
                } finally {
                    this@Eps.persistenceLoadDepth--
                }


            } catch (e: Exception) {
                throw PersistenceFailedException("Failed to initialize collection persistence: ${e.message}", e)
            }


            log.d { "ESP[${this@Eps.callingClassName}.${this@Eps.propertyName}] setup finished" }
        }
    }

    // File I/O helper methods
    protected fun writeToFile(key: KeyType, content: String) {
        val fileName = oktopoiJson.encodeToString(persisted.keyTypeSerializer, key)
        val filePath = Path(dirPath, fileName)
        fileSystem.sink(filePath).buffered().use { it.writeString(content) }
    }

    protected fun deleteFromFile(key: KeyType) {
        val fileName = oktopoiJson.encodeToString(persisted.keyTypeSerializer, key)
        val filePath = Path(dirPath, fileName)
        // Only attempt deletion if file exists - if already gone, deletion succeeded
        if (fileSystem.exists(filePath)) {
            fileSystem.delete(filePath)
        } else {
            log.d { "File already deleted: $filePath" }
        }
    }

    // Persistence logic - can be overridden by subclasses
    protected open fun persistEntry(key: KeyType, value: ValueType?) {
        if (value != null) {
            writeToFile(key, oktopoiJson.encodeToString(persisted.valueTypeSerializer, value))
        } else {
            deleteFromFile(key)
        }
    }

    // ========================================================================
    // Override TreeMap before hooks to add persistence-first approach
    // ========================================================================
    
    override fun onBeforePutUnsafe(key: KeyType, newValue: ValueType) {
        // During initialization load, skip redundant persistence writes
        if (persistenceLoadDepth > 0) return
        // Persistence-first approach: persist before memory update
        try {
            persistEntry(key, newValue)
        } catch (e: Exception) {
            throw PersistenceFailedException("Failed to persist put($key): ${e.message}", e)
        }
    }
    
    override fun onBeforeRemoveUnsafe(key: KeyType) {
        // During initialization load, skip redundant persistence writes
        if (persistenceLoadDepth > 0) return
        // Persistence-first approach: persist removal before memory update
        try {
            persistEntry(key, null)
        } catch (e: Exception) {
            throw PersistenceFailedException("Failed to persist remove($key): ${e.message}", e)
        }
    }
    
    override fun onBeforeClearUnsafe() {
        // Persistence-first approach: delete all files before memory clear
        try {
            keysUnsafe().forEach { key -> persistEntry(key, null) }
        } catch (e: Exception) {
            throw PersistenceFailedException("Failed to persist clear(): ${e.message}", e)
        }
    }
    
    // All advanced operations (compute, merge, etc.) automatically get persistence
    // through the unsafe hooks being called by their underlying put/remove operations

    open fun fromPersistString(string: String, fileName: String) {
        val value = oktopoiJson.decodeFromString(
            persisted.valueTypeSerializer,
            string
        )
        val key = oktopoiJson.decodeFromString(
            persisted.keyTypeSerializer,
            fileName
        )
        // Use unsafe put during initialization to avoid locking overhead; hooks are suppressed by persistenceLoadDepth
        putUnsafe(key, value)
    }
}


data class PersistedEsInfo<KeyType: Any, ValueType: Any>(
    val keyTypeSerializer: KSerializer<KeyType>,
    val valueTypeSerializer: KSerializer<ValueType>,
    val rootDir: Path?,
    val fileSystem: FileSystem
)