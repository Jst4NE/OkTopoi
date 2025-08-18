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
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

open class Eps<KeyType : Any, ValueType : Any> : Es<KeyType, ValueType> {

    protected val persisted: PersistedEsInfo<KeyType, ValueType>
    protected lateinit var dirPath: Path
    protected lateinit var fileSystem: FileSystem
    private val persistScope: CoroutineScope

    constructor(
        persisted: PersistedEsInfo<KeyType, ValueType>,
        keySelector: ((ValueType) -> KeyType)?,
        sortingBy: Comparator<KeyType>,
        secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {},
        persistScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ) : super(keySelector, sortingBy, secondaryKeys) {
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
        val key = keySelector?.invoke(value) ?: Json.decodeFromString(
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