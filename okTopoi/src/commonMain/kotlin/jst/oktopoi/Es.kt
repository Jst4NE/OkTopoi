package jst.oktopoi

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import jst.oktopoi.TreeMap.MapChange.Cleared
import jst.oktopoi.TreeMap.MapChange.Put
import jst.oktopoi.TreeMap.MapChange.Rebuild
import jst.oktopoi.TreeMap.MapChange.Removed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.math.log
import kotlin.time.ExperimentalTime
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.onSubscription


class Es<KeyType : Any, ValueType : Any> : TreeMap<KeyType, ValueType> {

    private val persisted: PersistedEsInfo<KeyType, ValueType>?
    private val keySelector: ((ValueType) -> KeyType)?

    constructor(
        persisted: PersistedEsInfo<KeyType, ValueType>?,
        keySelector: ((ValueType) -> KeyType)?,
        sortingBy: Comparator<KeyType>,
        secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
    ) : super(sortingBy, secondaryKeys) {
        this.persisted = persisted
        this.keySelector = keySelector
    }

    lateinit var callingClassName: String
    lateinit var propertyName: String

    @OptIn(ExperimentalTime::class)
    internal fun setup() {
        if (persisted != null) {
            runBlockingMultiplatform {
                try {
                    val rootDir: Path
                    if (persisted.rootDir == null) {
                        rootDir = initDefaultIO.filter { it?.second == persisted.fileSystem }.first()!!.first
                    } else {
                        initIO.filter { it?.first == persisted.rootDir && it.second == persisted.fileSystem }
                            .first()!!
                        rootDir = persisted.rootDir
                    }

                    val fileSystem: FileSystem = persisted.fileSystem

                    val dirPath = Path(rootDir, callingClassName, propertyName)
                    fileSystem.createDirectories(dirPath)

                    val existingFiles = fileSystem.list(dirPath).filter { (fileSystem.metadataOrNull(it)?.size ?: 0) != 0L }
                    
                    existingFiles.forEach { file ->
                        fileSystem.source(file)
                            .buffered()
                            .use { source ->
                                val content = source.readString()
                                val value = Json.decodeFromString(
                                    persisted.valueTypeSerializer,
                                    content
                                )
                                val key = keySelector?.invoke(value) ?: Json.decodeFromString(
                                    persisted.keyTypeSerializer,
                                    file.name
                                )
                                this@Es.put(key, value)
                            }
                    }

                    
                    // Ensure persistence collector is active before setup() completes
                    val collectorStarted = CompletableDeferred<Unit>()
                    
                    this@Es.changes
                        .onSubscription {
                            collectorStarted.complete(Unit)
                        }
                        .onEach { change ->
                            try {
                                fileSystem.createDirectories(dirPath)
                                when (change) {
                                    is Put -> {
                                        val fileName = Json.encodeToString(persisted.keyTypeSerializer, change.key)
                                        val filePath = Path(dirPath, fileName)
                                        val content = Json.encodeToString(persisted.valueTypeSerializer, change.value)

                                        fileSystem.sink(filePath)
                                            .buffered().use {
                                                it.writeString(content)
                                            }
                                    }

                                    is Removed -> {
                                        val fileName = Json.encodeToString(persisted.keyTypeSerializer, change.key)
                                        val filePath = Path(dirPath, fileName)
                                        fileSystem.delete(filePath)
                                    }

                                    is Cleared -> {
                                        fileSystem.list(dirPath).forEach { fileSystem.delete(it) }
                                    }

                                    is Rebuild -> {
                                        fileSystem.list(dirPath).forEach { fileSystem.delete(it) }
                                        // Rebuild: Save all current entries
                                        this@Es.forEach { (key, value) ->
                                            val fileName = Json.encodeToString(persisted.keyTypeSerializer, key)
                                            val filePath = Path(dirPath, fileName)
                                            val content = Json.encodeToString(persisted.valueTypeSerializer, value)


                                            fileSystem.sink(filePath)
                                                .buffered().use {
                                                    it.writeString(content)
                                                }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Logger.e("OkTopoi-Es", e) { "Error processing change: $change" }
                            }
                        }.launchIn(persistCoroutineScope)
                    
                    // Wait for collector to start before completing setup
                    collectorStarted.await()

                } catch (e: Exception) {
                    Logger.e("OkTopoi-Es", e) { "Error in setup: $e" }
                }
            }
        }
    }

    fun asSnapshotStateList(
        scope: CoroutineScope,
        entryComparator: Comparator<Map.Entry<KeyType, ValueType>>? = null,
        filter: ((Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
        initialEntriesProvider: (() -> Collection<Map.Entry<KeyType, ValueType>>) = { entries },
    ): SnapshotStateList<Map.Entry<KeyType, ValueType>> {
        
        // Create comparator with default fallback
        val entryCmp = entryComparator ?: Comparator { e1, e2, -> keyComparator.compare(e1.key, e2.key) }

        fun getSortedEntries(): Collection<Map.Entry<KeyType, ValueType>> {
            // Use efficient provider if available, otherwise default to all entries
            val baseEntries = initialEntriesProvider.invoke()
            
            // Apply filter if provided
            val filteredEntries = filter?.let { baseEntries.filter(it) } ?: baseEntries
            
            // Sort only if custom comparator provided (TreeMap's primary key order is already correct)
            return if (entryComparator != null) {
                filteredEntries.sortedWith(entryComparator)  // Custom order needed
            } else {
                filteredEntries  // TreeMap's primary key order is already correct
            }
        }

        val list = getSortedEntries().toMutableStateList()

        fun findIndex(key: KeyType, value: ValueType, returnInsertionPoint: Boolean = true): Int {
            return BinarySearchUtils.findIndexForMapEntries(list, key, value, entryCmp, returnInsertionPoint)
        }

        scope.launch {
            changes.collect { change ->
                when (change) {
                    is Put -> {
                        val entry = MapEntry(change.key, change.value)
                        val passes = filter?.invoke(entry) != false

                        if (change.isUpdate && change.oldValue != null) {
                            val oldIndex = findIndex(change.key, change.oldValue, false)
                            if (oldIndex >= 0) list.removeAt(oldIndex)
                            if (passes) list.add(findIndex(change.key, change.value), entry)
                        } else if (passes) {
                            list.add(findIndex(change.key, change.value), entry)
                        }
                    }

                    is Removed -> {
                        val index = findIndex(change.key, change.oldValue, false)
                        if (index >= 0) list.removeAt(index)
                    }

                    is Cleared -> list.clear()
                    is Rebuild -> {
                        list.clear()
                        list.addAll(getSortedEntries())
                    }
                }
            }
        }

        return list
    }

    /**
     * Creates a reactive SnapshotStateList filtered by multiple secondary key criteria with proper O(k) initial lookup.
     * Uses TreeMap's secondary index intersection for efficient filtering and delegates to base asSnapshotStateList.
     */
    fun asSnapshotStateListBySecondaryKey(
        scope: CoroutineScope,
        vararg criteria: Pair<String, Any?>,
        entryComparator: Comparator<Map.Entry<KeyType, ValueType>>,
        filter: ((Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
    ): SnapshotStateList<Map.Entry<KeyType, ValueType>> {
        
        val initialEntriesProvider: () -> List<MapEntry<KeyType, ValueType>> = {
            getPrimaryKeysBySecondaryKey(*criteria)
                .map { key -> MapEntry(key, this[key]!!) }
        }

        return asSnapshotStateList(scope, entryComparator, filter, initialEntriesProvider)
    }

    /**
     * Creates a reactive SnapshotStateList filtered by multiple secondary key criteria with proper O(k) initial lookup.
     * Uses TreeMap's secondary index intersection for efficient filtering and delegates to base asSnapshotStateList.
     * Automatically creates a comparator that compares by secondary key values in order.
     */
    fun asSnapshotStateListBySecondaryKey(
        scope: CoroutineScope,
        vararg criteria: Pair<String, Comparable<*>?>,
        filter: ((Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
    ): SnapshotStateList<Map.Entry<KeyType, ValueType>> {

        val comparator: Comparator<Map.Entry<KeyType, ValueType>> = Comparator<Map.Entry<KeyType, ValueType>> { e1, e2 ->
            // Compare by each secondary key in order until we find a difference
            for ((indexName, _) in criteria) {
                val secCmp = compareValues(
                    getSecondaryKey(indexName, e1.value) as? Comparable<*>,
                    getSecondaryKey(indexName, e2.value) as? Comparable<*>
                )
                if (secCmp != 0) return@Comparator secCmp
            }
            // If all secondary keys are equal, compare by primary key
            keyComparator.compare(e1.key, e2.key)
        }

        return asSnapshotStateListBySecondaryKey(scope, criteria = criteria, comparator, filter)
    }

}

data class PersistedEsInfo<KeyType: Any, ValueType: Any>(
    val keyTypeSerializer: KSerializer<KeyType>,
    val valueTypeSerializer: KSerializer<ValueType>,
    val rootDir: Path?,
    val fileSystem: FileSystem
)
