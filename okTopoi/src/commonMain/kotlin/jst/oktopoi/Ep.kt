@file:Suppress("UNCHECKED_CAST")

package jst.oktopoi

import co.touchlab.kermit.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
 * Persistent state container that extends E with automatic file-based persistence.
 *
 * Ep (\"Element-Persistent\") adds file system persistence to the reactive state management
 * provided by the base E class. All state changes are automatically persisted to disk,
 * and the persisted value is loaded during initialization if available.
 *
 * ## Architecture
 *
 * ```
 * E (observable state)
 *   ↓ extends
 * Ep (+ file persistence)  ← You are here
 *   ↓ extends
 * Esp (+ bi-directional sync)
 * ```
 *
 * ## Key Features
 *
 * - **Blocking persistence**: All state changes block the calling thread until file writes complete
 * - **Initialization loading**: Restores persisted values on startup
 * - **Serialization**: Uses kotlinx-serialization for type-safe persistence
 * - **Error handling**: Robust error handling with PersistenceFailedException
 * - **Extensible design**: Designed to be extended by Esp for synchronization
 *
 * ## Persistence Behavior
 *
 * - **Write-through**: State changes block the caller until file writes complete using runBlockingMultiplatform
 * - **Persistence first**: the file is written before the value changes; if the write fails,
 *   [PersistenceFailedException] is thrown and the value is unchanged
 * - **Read-on-startup**: Persisted values loaded during setup() initialization
 * - **In-place writes**: the file is overwritten in place, so a crash mid-write can leave it
 *   truncated; it is archived on the next load and the default is used.
 * - **Synchronous I/O**: All file operations block until completion for consistency
 *
 * ## Usage Examples
 *
 * ```kotlin
 * // Basic persistent state
 * val userSettings = ep<UserPreferences> { UserPreferences.default() }
 * userSettings.value = newPreferences  // Automatically persisted
 *
 * // Custom storage location
 * val appConfig = ep<Configuration>(
 *     rootDir = Path("/custom/config")
 * ) { Configuration.default() }
 *
 * // Observing changes (includes persisted changes)
 * lifecycleScope.launch {
 *     userSettings.collect { settings ->
 *         updateUI(settings)
 *     }
 * }
 * ```
 *
 * ## File Management
 *
 * - **File location**: `{rootDir}/{simple class name}/{propertyName}`
 * - **Format**: JSON using kotlinx-serialization
 * - **Encoding**: UTF-8 text encoding
 * - **Error handling**: File I/O errors throw PersistenceFailedException
 *
 * ## Thread Safety
 *
 * Ep maintains the same thread safety guarantees as the base E class, with additional
 * considerations for file I/O:
 * - State reads are immediate and thread-safe
 * - File writes are performed synchronously and block the calling thread
 * - Concurrent state changes are serialized, so the file and the value change in the same order
 *
 * ## Error Handling
 *
 * - **Setup failures**: Throws PersistenceFailedException during initialization (fail-fast)
 * - **Write failures**: PersistenceFailedException thrown; the value is unchanged
 * - **Read failures**: Missing/empty files use the default; an undecodable file is archived and
 *   the default is used
 *
 * @param ValueType the type of value to persist (must be serializable)
 *
 * @see E for the base observable state functionality
 * @see Esp for synchronized persistent state
 * @see ep factory function for creation
 * @throws PersistenceFailedException when persistence setup fails
 */
open class Ep<ValueType : Any?> : E<ValueType> {

    private val persisted: PersistedEInfo<ValueType>
    private lateinit var filePath: Path
    private lateinit var fileSystem: FileSystem
    private val persistScope: CoroutineScope

    private val log = Logger.withTag(this::class.simpleName.toString())

    constructor(
        persisted: PersistedEInfo<ValueType>,
        observing: StateFlow<ValueType>? = null,
        defaultValue: (() -> ValueType?)? = null,
        persistScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    ) : super(observing, defaultValue) {
        this.persisted = persisted
        this.persistScope = persistScope
    }

    @OptIn(ExperimentalTime::class)
    override fun setup() {
        // Call parent setup for observing functionality
        super.setup()
        
        // Initialize persistence
        runBlockingMultiplatform {
            try {

                log.d { "EP[${this@Ep.callingClassName}.${this@Ep.propertyName}] setup waiting for init" }

                val rootDir = awaitRootDir(persisted.rootDir, persisted.fileSystem, "${this@Ep.callingClassName}.${this@Ep.propertyName}")

                this@Ep.fileSystem = persisted.fileSystem
                val dirPath = Path(rootDir, callingClassName)
                this@Ep.filePath = Path(dirPath, propertyName)

                fileSystem.createDirectories(dirPath)

                // Load existing value from file if it exists and is not empty
                readFromFile()?.let { content ->
                    try {
                        super.value = decodeValue(content) as ValueType
                    } catch (e: Exception) {
                        // Archive corrupted file and log error, but don't crash
                        Logger.e(e, tag = "OkTopoi-Ep") {
                            "Failed to deserialize ${callingClassName}.${propertyName}: ${e.message}\n" +
                            "Using default value instead. Corrupted file archived."
                        }
                        archiveCorruptedFile(
                            filePath = this@Ep.filePath,
                            fileSystem = this@Ep.fileSystem,
                            rootDir = rootDir,
                            propertyIdentifier = "${callingClassName}.${propertyName}"
                        )
                        // super.value remains at default (from parent E constructor)
                    }
                }

            } catch (e: Exception) {
                throw PersistenceFailedException("Failed to initialize persistence: ${e.message}", e)
            }

            log.d { "EP[${this@Ep.callingClassName}.${this@Ep.propertyName}] setup finished" }
        }
    }

    // Every change writes the file first and updates the value only once that succeeded, so a
    // failed write throws and no collector ever sees the value. The lock keeps file and value in
    // the same order when several threads write; it is reentrant (Esp's encodeValue nests inside).
    // set() and clear() are E's, which assign through the value setter below.
    private val writeLock = SynchronizedObject()

    override var value: ValueType
        get() = super.value
        set(newValue) {
            synchronized(writeLock) {
                persistOrThrow(newValue)
                super.value = newValue
            }
        }

    override suspend fun emit(value: ValueType) {
        this.value = value
    }

    override fun tryEmit(value: ValueType): Boolean {
        this.value = value
        return true
    }

    override fun compareAndSet(expect: ValueType, update: ValueType): Boolean =
        synchronized(writeLock) {
            if (super.value != expect) return@synchronized false
            persistOrThrow(update)
            super.compareAndSet(expect, update)
        }

    private fun persistOrThrow(value: ValueType) {
        try {
            persistValue(value)
        } catch (e: Exception) {
            throw PersistenceFailedException("Failed to persist value: ${e.message}", e)
        }
    }

    // Protected accessors for derived classes
    
    /**
     * Gets the value serializer for use in derived classes.
     */
    protected val valueSerializer: KSerializer<ValueType?>
        get() = persisted.valueSerializer
    
    // Protected methods for pure file I/O operations (can be overridden by Esp)
    
    /**
     * Writes content to the persistence file.
     * Override this method to customize persistence format (e.g., for sync metadata).
     */
    protected open fun writeToFile(content: String) {
        if (!::filePath.isInitialized) return
        
        runBlockingMultiplatform {
            fileSystem.sink(filePath).buffered().use { sink ->
                sink.writeString(content)
            }
        }
    }

    /**
     * Reads content from the persistence file.
     * Override this method to customize persistence format loading.
     */
    protected open fun readFromFile(): String? {
        if (!::filePath.isInitialized) return null
        
        return try {
            if (fileSystem.exists(filePath) && (fileSystem.metadataOrNull(filePath)?.size ?: 0) != 0L) {
                fileSystem.source(filePath).buffered().use { it.readString() }
            } else null
        } catch (e: Exception) {
            Logger.e(e, tag = "OkTopoi-Ep") { "Error reading from $filePath" }
            null
        }
    }

    /**
     * Encodes the value for persistence.
     * Override this method to customize serialization (e.g., add sync metadata).
     */
    protected open fun encodeValue(value: ValueType?): String {
        return oktopoiJson.encodeToString(persisted.valueSerializer, value)
    }

    /**
     * Decodes the value from persistence.
     * Override this method to customize deserialization (e.g., extract sync metadata).
     */
    protected open fun decodeValue(content: String): ValueType? {
        return oktopoiJson.decodeFromString(persisted.valueSerializer, content)
    }

    /**
     * Persists the given value to the file system.
     * This method is called automatically by all state-changing operations.
     */
    protected open fun persistValue(value: ValueType?) {
        val content = encodeValue(value)
        writeToFile(content)
    }
}

/**
 * Data class containing persistence information for E values.
 */
data class PersistedEInfo<ValueType : Any?>(
    val valueSerializer: KSerializer<ValueType?>,
    val rootDir: Path? = null,
    val fileSystem: FileSystem
)