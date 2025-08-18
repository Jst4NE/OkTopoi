package jst.oktopoi

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * Persistent E implementation that extends base E with file-based persistence.
 * 
 * This class follows the same architecture pattern as Es/Eps, where persistence
 * concerns are separated into a derived class that overrides state-changing methods
 * to add persistence behavior.
 */
open class Ep<ValueType : Any?> : E<ValueType> {

    private val persisted: PersistedEInfo<ValueType>
    private lateinit var filePath: Path
    private lateinit var fileSystem: FileSystem
    private val persistScope: CoroutineScope

    constructor(
        persisted: PersistedEInfo<ValueType>,
        observing: StateFlow<ValueType>? = null,
        defaultValue: (() -> ValueType?)? = null,
        persistScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
                val rootDir: Path = if (persisted.rootDir == null) {
                    initDefaultIO.filter { it?.second == persisted.fileSystem }.first()!!.first
                } else {
                    initIO.filter { it?.first == persisted.rootDir && it.second == persisted.fileSystem }.first()!!
                    persisted.rootDir
                }

                this@Ep.fileSystem = persisted.fileSystem
                val dirPath = Path(rootDir, callingClassName)
                this@Ep.filePath = Path(dirPath, propertyName)

                fileSystem.createDirectories(dirPath)

                // Load existing value from file if it exists and is not empty
                readFromFile()?.let { content ->
                    super.value = decodeValue(content)
                }

            } catch (e: Exception) {
                throw PersistenceFailedException("Failed to initialize persistence: ${e.message}", e)
            }
        }
    }

    // Override all state-changing methods to add persistence
    
    override var value: ValueType?
        get() = super.value
        set(newValue) {
            val oldValue = super.value
            super.value = newValue
            try {
                persistValue(newValue)
            } catch (e: Exception) {
                super.value = oldValue  // Rollback
                throw PersistenceFailedException("Failed to persist value: ${e.message}", e)
            }
        }

    override suspend fun emit(value: ValueType?) {
        val oldValue = super.value
        try {
            super.emit(value)
            persistValue(value)
        } catch (e: Exception) {
            // Rollback by calling super.emit (bypassing our persistence)
            super.emit(oldValue)
            throw PersistenceFailedException("Failed to persist emitted value: ${e.message}", e)
        }
    }

    override fun tryEmit(value: ValueType?): Boolean {
        val oldValue = super.value
        val result = super.tryEmit(value)
        if (result) {
            try {
                persistValue(value)
            } catch (e: Exception) {
                // Rollback by calling super.tryEmit (bypassing our persistence)
                super.tryEmit(oldValue)
                throw PersistenceFailedException("Failed to persist tryEmit value: ${e.message}", e)
            }
        }
        return result
    }

    override fun compareAndSet(expect: ValueType?, update: ValueType?): Boolean {
        val result = super.compareAndSet(expect, update)
        if (result) {
            try {
                persistValue(update)
            } catch (e: Exception) {
                super.value = expect  // Rollback to expected value
                throw PersistenceFailedException("Failed to persist compareAndSet: ${e.message}", e)
            }
        }
        return result
    }

    // Override convenience methods that change state
    
    override fun set(newValue: ValueType) {
        val oldValue = super.value
        super.set(newValue)
        try {
            persistValue(newValue)
        } catch (e: Exception) {
            super.value = oldValue  // Rollback
            throw PersistenceFailedException("Failed to persist set: ${e.message}", e)
        }
    }

    // setIfDifferent() calls compareAndSet() internally, which already handles persistence
    // No override needed - inherited behavior is correct

    override fun clear() {
        val oldValue = super.value
        super.clear()
        try {
            persistValue(null)
        } catch (e: Exception) {
            super.value = oldValue  // Rollback
            throw PersistenceFailedException("Failed to persist clear: ${e.message}", e)
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
            Logger.e("OkTopoi-Ep", e) { "Error reading from $filePath" }
            null
        }
    }

    /**
     * Encodes the value for persistence.
     * Override this method to customize serialization (e.g., add sync metadata).
     */
    protected open fun encodeValue(value: ValueType?): String {
        return Json.encodeToString(persisted.valueSerializer, value)
    }

    /**
     * Decodes the value from persistence.
     * Override this method to customize deserialization (e.g., extract sync metadata).
     */
    protected open fun decodeValue(content: String): ValueType? {
        return Json.decodeFromString(persisted.valueSerializer, content)
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