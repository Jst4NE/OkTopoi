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
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * On-disk header line for a compacted snapshot file ([Eps.SNAPSHOT_FILE]).
 */
@Serializable
internal data class SnapshotHeader(
    val version: Int = 1,
    val createdAtMs: Long,
    val entryCount: Int
)

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

                val allFiles = fileSystem.list(dirPath)
                val snapshotPath = Path(dirPath, SNAPSHOT_FILE)
                val hasSnapshot = allFiles.any { it.name == SNAPSHOT_FILE }

                // Suppress persistence while loading existing entries
                this@Eps.persistenceLoadDepth++
                try {
                    // Phase 1: load compacted snapshot (one big sequential read)
                    if (hasSnapshot) {
                        loadSnapshot(snapshotPath, rootDir)
                    }

                    // Phase 2: apply diff — per-entry files written since last compaction
                    // override snapshot entries (or add new ones)
                    val entryFiles = allFiles.filter { f ->
                        f.name != SNAPSHOT_FILE &&
                        !f.name.endsWith(TOMBSTONE_SUFFIX) &&
                        (fileSystem.metadataOrNull(f)?.size ?: 0) != 0L
                    }
                    entryFiles.forEach { file ->
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

                    // Phase 3: apply tombstones — deletions that happened after the snapshot
                    val tombstoneFiles = allFiles.filter { it.name.endsWith(TOMBSTONE_SUFFIX) }
                    tombstoneFiles.forEach { tomb ->
                        try {
                            val keyJson = tomb.name.removeSuffix(TOMBSTONE_SUFFIX)
                            val key = oktopoiJson.decodeFromString(persisted.keyTypeSerializer, keyJson)
                            removeUnsafe(key)
                        } catch (e: Exception) {
                            Logger.e(e, tag = "OkTopoi-Eps") {
                                "Failed to apply tombstone ${tomb.name} in ${callingClassName}.${propertyName}: ${e.message}"
                            }
                        }
                    }

                    // Auto-compact when there's enough loose stuff to amortize the snapshot write.
                    // We're still inside setup() — single-threaded, persistence suppressed — so we can
                    // call compactUnsafe directly without taking the lock.
                    val staleCount = entryFiles.size + tombstoneFiles.size
                    if (staleCount >= AUTO_COMPACT_THRESHOLD) {
                        try {
                            log.d {
                                "ESP[${callingClassName}.${propertyName}] auto-compacting at setup ($staleCount stale files ≥ $AUTO_COMPACT_THRESHOLD)"
                            }
                            compactUnsafe()
                        } catch (e: Exception) {
                            // Auto-compaction is an optimization — never fail setup because of it
                            Logger.w(e, tag = "OkTopoi-Eps") {
                                "Auto-compaction failed for ${callingClassName}.${propertyName}: ${e.message}. Continuing without snapshot."
                            }
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
        if (fileSystem.exists(filePath)) {
            fileSystem.delete(filePath)
        } else {
            log.d { "File already deleted: $filePath" }
        }
        // If a snapshot exists, the deleted key may still be present in it.
        // Drop a tombstone so the next load knows to remove it.
        if (snapshotExists()) {
            val tombPath = Path(dirPath, "$fileName$TOMBSTONE_SUFFIX")
            try {
                fileSystem.sink(tombPath).buffered().use { /* 0 bytes; existence is the signal */ }
            } catch (e: Exception) {
                log.w(e) { "Failed to write tombstone for $fileName" }
            }
        }
    }

    protected fun snapshotExists(): Boolean = fileSystem.exists(Path(dirPath, SNAPSHOT_FILE))

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

    // ========================================================================
    // Snapshot / compaction
    // ========================================================================

    /**
     * Read a previously-written snapshot file and replay its entries via [fromPersistString].
     * Per-line format: `<serializedKey>\t<entryContent>`. Header is the first line.
     * Single-entry decode failures are logged; a corrupt whole-snapshot file is archived
     * and load falls through to the per-entry scan.
     */
    private fun loadSnapshot(snapshotPath: Path, rootDir: Path) {
        try {
            val content = fileSystem.source(snapshotPath).buffered().use { it.readString() }
            val lines = content.split('\n')
            if (lines.isEmpty() || lines[0].isEmpty()) return
            // First line: header — decoded for diagnostics; structural validation happens implicitly
            oktopoiJson.decodeFromString(SnapshotHeader.serializer(), lines[0])
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val tabIdx = line.indexOf('\t')
                if (tabIdx < 0) continue
                val fileName = line.substring(0, tabIdx)
                val entryContent = line.substring(tabIdx + 1)
                try {
                    fromPersistString(entryContent, fileName)
                } catch (e: Exception) {
                    Logger.e(e, tag = "OkTopoi-Eps") {
                        "Failed to deserialize snapshot entry $fileName in ${callingClassName}.${propertyName}: ${e.message}. Skipping."
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(e, tag = "OkTopoi-Eps") {
                "Snapshot corrupted in ${callingClassName}.${propertyName}: ${e.message}. Archiving and falling back to per-entry scan."
            }
            try {
                archiveCorruptedFile(
                    filePath = snapshotPath,
                    fileSystem = fileSystem,
                    rootDir = rootDir,
                    propertyIdentifier = "${callingClassName}.${propertyName}.snapshot"
                )
            } catch (_: Exception) { /* archive best-effort */ }
        }
    }

    /**
     * Yield the full set of entries that must be captured in the next snapshot.
     * Each pair is (fileName, content) — same wire format as a per-entry file.
     * Subclasses (Esps) override to include entries that exist as on-disk persistence
     * state but aren't present in the in-memory TreeMap (e.g. pending-delete tombstones).
     * Caller holds the write lock.
     */
    protected open fun collectSnapshotEntriesUnsafe(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(sizeUnsafe)
        keysUnsafe().forEach { key ->
            val value = getUnsafe(key) ?: return@forEach
            val fileName = oktopoiJson.encodeToString(persisted.keyTypeSerializer, key)
            val content = serializeEntryForSnapshot(key, value)
            out.add(fileName to content)
        }
        return out
    }

    /**
     * Serialize a single live entry for inclusion in a snapshot.
     * Default emits the value JSON (matching [writeToFile]). Esps overrides to wrap with sync metadata.
     */
    protected open fun serializeEntryForSnapshot(key: KeyType, value: ValueType): String =
        oktopoiJson.encodeToString(persisted.valueTypeSerializer, value)

    /**
     * Compact all per-entry files (and tombstones) into a single snapshot file.
     *
     * Workflow:
     *  1. Write a tmp snapshot containing every current entry.
     *  2. Atomically swap tmp → `_snapshot.bin`.
     *  3. Delete all remaining per-entry files and tombstones in the directory.
     *
     * After this, the next load opens **one** file instead of N. Crash-safe: if the process dies
     * between steps 2 and 3, the leftover per-entry files simply override snapshot entries on load,
     * which yields the same in-memory state.
     *
     * Call this at app shutdown, during quiet periods, or after large bulk writes. Cheap-ish for
     * tens of thousands of entries (one sequential write) but holds the write lock for its duration.
     */
    @OptIn(ExperimentalTime::class)
    suspend fun compact() {
        withWriteLock { compactUnsafe() }
    }

    @OptIn(ExperimentalTime::class)
    protected open fun compactUnsafe() {
        if (!this::dirPath.isInitialized || !this::fileSystem.isInitialized) {
            log.w { "compact() called before setup() — ignoring" }
            return
        }
        val snapshotPath = Path(dirPath, SNAPSHOT_FILE)
        val tmpPath = Path(dirPath, "$SNAPSHOT_FILE$SNAPSHOT_TMP_SUFFIX")

        val entries = collectSnapshotEntriesUnsafe()
        val header = SnapshotHeader(
            createdAtMs = Clock.System.now().toEpochMilliseconds(),
            entryCount = entries.size
        )

        // 1. Write tmp file
        if (fileSystem.exists(tmpPath)) fileSystem.delete(tmpPath)
        fileSystem.sink(tmpPath).buffered().use { sink ->
            sink.writeString(oktopoiJson.encodeToString(SnapshotHeader.serializer(), header))
            sink.writeString("\n")
            entries.forEach { (fileName, content) ->
                sink.writeString(fileName)
                sink.writeString("\t")
                sink.writeString(content)
                sink.writeString("\n")
            }
        }

        // 2. Atomic swap
        if (fileSystem.exists(snapshotPath)) fileSystem.delete(snapshotPath)
        fileSystem.atomicMove(tmpPath, snapshotPath)

        // 3. Sweep stale per-entry files and tombstones
        fileSystem.list(dirPath).forEach { f ->
            if (f.name != SNAPSHOT_FILE) {
                try { fileSystem.delete(f) } catch (e: Exception) {
                    log.w(e) { "compact: failed to delete ${f.name}" }
                }
            }
        }

        log.d { "ESP[${callingClassName}.${propertyName}] compacted ${entries.size} entries into snapshot" }
    }

    companion object {
        internal const val SNAPSHOT_FILE = "_snapshot.bin"
        internal const val SNAPSHOT_TMP_SUFFIX = ".tmp"
        internal const val TOMBSTONE_SUFFIX = ".tomb"

        /**
         * If a setup() load encounters at least this many per-entry files + tombstones, it folds
         * them into a fresh snapshot before returning. Picked for write-rarely-but-grow-large
         * workloads: large enough that tiny collections never pay the snapshot-write cost,
         * small enough that load time on slow filesystems can't drift far from snapshot speed.
         */
        internal const val AUTO_COMPACT_THRESHOLD = 300
    }
}


data class PersistedEsInfo<KeyType: Any, ValueType: Any>(
    val keyTypeSerializer: KSerializer<KeyType>,
    val valueTypeSerializer: KSerializer<ValueType>,
    val rootDir: Path?,
    val fileSystem: FileSystem
)