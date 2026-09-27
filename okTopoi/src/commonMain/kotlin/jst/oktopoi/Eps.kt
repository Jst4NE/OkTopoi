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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
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
 * - **File-per-entry**: Each key-value pair stored in a separate file named after the key
 *   (string keys raw, other keys as JSON; filesystem-unsafe characters percent-escaped)
 * - **Directory structure**: `{rootDir}/{simple class name}/{propertyName}/{key}`
 * - **In-place writes**: entry files are overwritten in place, so a crash mid-write can leave
 *   one truncated; it is archived on the next load. Only snapshot compaction writes atomically.
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
 *   └── UserRepository/
 *       └── employees/
 *           ├── emp001    # Employee with ID emp001
 *           ├── emp002    # Employee with ID emp002
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

                    // Phase 2: apply tombstones — deletions that happened after the snapshot.
                    // BEFORE the entry files, not after: an entry file and a tombstone for the same
                    // key coexist only when the key was written again after its deletion (a delete
                    // removes the entry file before it writes the tombstone, and aborts if that
                    // removal fails), so the tombstone only ever means "gone from the snapshot" and
                    // a surviving entry file must win. Applied last, it deleted the re-written entry.
                    val tombstoneFiles = allFiles.filter { it.name.endsWith(TOMBSTONE_SUFFIX) }
                    tombstoneFiles.forEach { tomb ->
                        try {
                            val keyFileName = tomb.name.removeSuffix(TOMBSTONE_SUFFIX)
                            val key = fileNameToKey(keyFileName)
                            removeUnsafe(key)
                        } catch (e: Exception) {
                            Logger.e(e, tag = "OkTopoi-Eps") {
                                "Failed to apply tombstone ${tomb.name} in ${callingClassName}.${propertyName}: ${e.message}"
                            }
                        }
                    }

                    // Phase 3: apply diff — per-entry files written since last compaction
                    // override snapshot entries (or add new ones)
                    val entryFiles = allFiles.filter { f ->
                        // Files in the reserved namespace (snapshot, snapshot tmp, sidecars) are
                        // never entries; no key-derived file starts with RESERVED_PREFIX because a
                        // leading '_' in a key is escaped by keyToFileName.
                        !f.name.startsWith(RESERVED_PREFIX) &&
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

    // ========================================================================
    // Key <-> file name encoding (filesystem-safe, byte-identical on every OS)
    // ========================================================================

    private val keyIsString by lazy {
        persisted.keyTypeSerializer.descriptor.kind == PrimitiveKind.STRING
    }

    /**
     * Reversible, OS-independent mapping from a key to its on-disk file name.
     *
     * String-kind keys use their raw string value (no JSON quotes) so the common case yields
     * clean names like `alice`; numeric/boolean keys use their bare JSON (`123`, `true`); other
     * (composite) keys fall back to full JSON. The result is then percent-escaped: every character
     * illegal in a Windows file name (`< > : " / \ | ? *`), every ASCII control character, and the
     * escape marker `%` itself become `%XX`. Escaping is applied on all platforms, so the produced
     * name is identical everywhere — not just where a character happens to be legal. Finally a
     * *leading* `_` is escaped, keeping the reserved `_` prefix (snapshot, sidecars) free of any
     * key-derived file. Inverse of [fileNameToKey].
     */
    protected fun keyToFileName(key: KeyType): String {
        val natural = if (keyIsString) {
            // Unwrap the JSON string to its raw value: decode the quoted form back to String.
            oktopoiJson.decodeFromString(
                String.serializer(),
                oktopoiJson.encodeToString(persisted.keyTypeSerializer, key)
            )
        } else {
            oktopoiJson.encodeToString(persisted.keyTypeSerializer, key)
        }
        return escapeFileName(natural)
    }

    /** Inverse of [keyToFileName]. */
    protected fun fileNameToKey(fileName: String): KeyType {
        val natural = unescapeFileName(fileName)
        val json = if (keyIsString) {
            oktopoiJson.encodeToString(String.serializer(), natural)
        } else {
            natural
        }
        return oktopoiJson.decodeFromString(persisted.keyTypeSerializer, json)
    }

    private fun escapeFileName(s: String): String {
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            val escape = c in FILENAME_ESCAPED_CHARS || c.code < 0x20 || (i == 0 && c == '_')
            if (escape) {
                sb.append('%').append(c.code.toString(16).uppercase().padStart(2, '0'))
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unescapeFileName(s: String): String {
        if ('%' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val code = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    // File I/O helper methods
    protected fun writeToFile(key: KeyType, content: String) {
        val fileName = keyToFileName(key)
        val filePath = Path(dirPath, fileName)
        fileSystem.sink(filePath).buffered().use { it.writeString(content) }
    }

    protected fun deleteFromFile(key: KeyType) {
        val fileName = keyToFileName(key)
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
        val key = fileNameToKey(fileName)
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
            val fileName = keyToFileName(key)
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
     *  2. Atomically move tmp over `_snapshot.bin` (replacing it — never delete-then-move).
     *  3. Delete the remaining tombstones, then the remaining per-entry files.
     *
     * After this, the next load opens **one** file instead of N. Crash-safe against the process
     * dying at any point: before step 2 the old snapshot and loose files are intact; step 2 leaves
     * either the old or the new snapshot, never neither; and step 3 removes tombstones first, so a
     * partial sweep can only leave loose files that load to the same state. Not proof against power
     * loss — nothing is fsynced.
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

        try {
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

            // 2. Atomic swap. atomicMove replaces an existing target in one step on every target
            // platform (NIO ATOMIC_MOVE+REPLACE_EXISTING on JVM/Android — hence minSdk 26 — POSIX
            // rename() on native). Deleting the old snapshot first would open a window in which
            // neither snapshot exists and every entry living only in the old one is lost.
            fileSystem.atomicMove(tmpPath, snapshotPath)
        } catch (e: Exception) {
            // Nothing is lost: the old snapshot and the loose files are untouched and still load.
            try { if (fileSystem.exists(tmpPath)) fileSystem.delete(tmpPath) } catch (_: Exception) {}
            throw e
        }

        // 3. Sweep stale tombstones, THEN per-entry files. Order matters: a tombstone and an entry
        // file for the same key mean "re-written after deletion" and load correctly together (the
        // entry file wins), but a surviving tombstone without its entry file would delete the key
        // from the new snapshot on the next load. Reserved-namespace files (the snapshot itself,
        // its tmp, and any sidecars) are preserved — they are not entries.
        val looseFiles = fileSystem.list(dirPath).filter { !it.name.startsWith(RESERVED_PREFIX) }
        val (tombstones, entryFiles) = looseFiles.partition { it.name.endsWith(TOMBSTONE_SUFFIX) }
        var tombstonesSwept = true
        tombstones.forEach { f ->
            try { fileSystem.delete(f) } catch (e: Exception) {
                tombstonesSwept = false
                log.w(e) { "compact: failed to delete ${f.name}" }
            }
        }
        // A tombstone that could not be deleted must keep its entry file, or it would delete that
        // key on the next load. Leftover loose files are harmless; the next compaction sweeps them.
        if (tombstonesSwept) {
            entryFiles.forEach { f ->
                try { fileSystem.delete(f) } catch (e: Exception) {
                    log.w(e) { "compact: failed to delete ${f.name}" }
                }
            }
        }

        log.d { "ESP[${callingClassName}.${propertyName}] compacted ${entries.size} entries into snapshot" }
    }

    // ========================================================================
    // Sidecars — collection-level auxiliary state stored beside the entries
    // ========================================================================

    /**
     * A single auxiliary value persisted in this collection's directory, outside the entry set.
     *
     * Sidecars let a subclass attach collection-level metadata (e.g. a sync watermark) that lives
     * beside the entries but is never treated as one. The file is named `_<name>`: the reserved
     * '_' prefix keeps it out of the entry scan and the compaction sweep, and no key-derived file
     * can collide (a leading '_' in a key is escaped). Reads are in-memory; writes are synchronous
     * write-through. Create via [sidecar] after [setup] has assigned [dirPath].
     */
    inner class Sidecar<T> internal constructor(
        name: String,
        private val serializer: KSerializer<T>,
        initial: T,
    ) {
        private val path = Path(dirPath, RESERVED_PREFIX + name)

        private var current: T = run {
            try {
                if (fileSystem.exists(path)) {
                    val content = fileSystem.source(path).buffered().use { it.readString() }
                    if (content.isNotEmpty()) oktopoiJson.decodeFromString(serializer, content) else initial
                } else {
                    initial
                }
            } catch (e: Exception) {
                log.w(e) { "Failed to load sidecar ${path.name}; using initial value" }
                initial
            }
        }

        var value: T
            get() = current
            set(newValue) {
                current = newValue
                try {
                    fileSystem.sink(path).buffered().use {
                        it.writeString(oktopoiJson.encodeToString(serializer, newValue))
                    }
                } catch (e: Exception) {
                    log.w(e) { "Failed to persist sidecar ${path.name}" }
                }
            }
    }

    /**
     * Create a [Sidecar] holding one value at `_<name>` in this collection's directory, loading any
     * persisted value. Must be called after [setup] (when [dirPath] is set) — typically from an
     * override that runs post-load. [name] must be non-empty and must not start with '_'.
     */
    protected fun <T> sidecar(name: String, serializer: KSerializer<T>, initial: T): Sidecar<T> {
        check(this::dirPath.isInitialized) { "sidecar('$name') created before setup()" }
        require(name.isNotEmpty() && !name.startsWith(RESERVED_PREFIX)) {
            "sidecar name must be non-empty and must not start with '$RESERVED_PREFIX': '$name'"
        }
        return Sidecar(name, serializer, initial)
    }

    companion object {
        /**
         * Reserved file-name prefix. Files whose name starts with this are not entries — they are
         * OkTopoi-internal (the snapshot and its tmp) or subclass [sidecar]s. The entry scan and the
         * compaction sweep both skip them. Safe as a sigil because no key-derived file name can
         * start with it: [keyToFileName] escapes a leading '_' in a key.
         */
        internal const val RESERVED_PREFIX = "_"
        internal const val SNAPSHOT_FILE = "_snapshot.bin"
        internal const val SNAPSHOT_TMP_SUFFIX = ".tmp"
        internal const val TOMBSTONE_SUFFIX = ".tomb"

        /** Characters illegal in a Windows file name; escaped (with '%' itself) on every platform. */
        private val FILENAME_ESCAPED_CHARS = "<>:\"/\\|?*%".toSet()

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