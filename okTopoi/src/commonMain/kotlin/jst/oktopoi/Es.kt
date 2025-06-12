package jst.oktopoi

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import jst.oktopoi.TreeMap.MapChange.Cleared
import jst.oktopoi.TreeMap.MapChange.Put
import jst.oktopoi.TreeMap.MapChange.Rebuild
import jst.oktopoi.TreeMap.MapChange.Removed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime


class Es<KeyType : Any, ValueType : Any> : TreeMap<KeyType, ValueType> {

    private val persisted: PersistedEsInfo<KeyType, ValueType>?
    private val keySelector: ((ValueType) -> KeyType)?

    constructor(
        persisted: PersistedEsInfo<KeyType, ValueType>?,
        keySelector: ((ValueType) -> KeyType)?,
        sortingBy: Comparator<KeyType>?,
        secondaryKeys: SecondaryKeyBuilder<ValueType>.() -> Unit = {}
    ) : super(sortingBy, secondaryKeys) {
        this.persisted = persisted
        this.keySelector = keySelector
    }

    lateinit var callingClassName: String
    lateinit var propertyName: String

    @OptIn(ExperimentalTime::class)
    internal fun setup() {
        if (persisted != null) {
            persistCoroutineScope.launch {

                val rootDir: Path
                if (persisted.rootDir == null) {
                    rootDir =
                        initDefaultIO.filter { it?.second == persisted.fileSystem }.first()!!.first
                } else {
                    initIO.filter { it?.first == persisted.rootDir && it.second == persisted.fileSystem }
                        .first()!!
                    rootDir = persisted.rootDir
                }

                val fileSystem: FileSystem = persisted.fileSystem

                val dirPath = Path(rootDir, callingClassName, propertyName)

                fileSystem.createDirectories(dirPath)

                fileSystem.list(dirPath).filter { (fileSystem.metadataOrNull(it)?.size ?: 0) != 0L }
                    .forEach {
                        fileSystem.source(it)
                            .buffered()
                            .use { source ->
                                val value = Json.decodeFromString(
                                    persisted.valueTypeSerializer,
                                    source.readString()
                                )
                                this@Es.put(
                                    keySelector?.invoke(value) ?: Json.decodeFromString(
                                        persisted.keyTypeSerializer,
                                        it.name
                                    ), value
                                )
                            }
                    }

                this@Es.changes.onEach { change ->
                    fileSystem.createDirectories(dirPath)
                    when (change) {
                        is Put -> {
                            fileSystem.sink(
                                Path(
                                    dirPath,
                                    Json.encodeToString(persisted.keyTypeSerializer, change.key)
                                )
                            )
                                .buffered().use {
                                    it.writeString(
                                        Json.encodeToString(
                                            persisted.valueTypeSerializer,
                                            change.value
                                        )
                                    )
                                }
                        }

                        is Removed -> fileSystem.delete(
                            Path(
                                dirPath,
                                Json.encodeToString(persisted.keyTypeSerializer, change.key)
                            )
                        )

                        is Cleared -> fileSystem.list(dirPath).forEach { fileSystem.delete(it) }
                        is Rebuild -> {
                            fileSystem.list(dirPath).forEach { fileSystem.delete(it) }
                            // Optimized: Use single lock with unsafe entry traversal
                            rwLock.withReadLock {
                                forEachEntryUnsafe { key, value ->
                                    fileSystem.sink(
                                        Path(
                                            dirPath,
                                            Json.encodeToString(persisted.keyTypeSerializer, key)
                                        )
                                    )
                                        .buffered().use {
                                            it.writeString(
                                                Json.encodeToString(
                                                    persisted.valueTypeSerializer,
                                                    value
                                                )
                                            )
                                        }
                                }
                            }
                        }
                    }
                }.launchIn(persistCoroutineScope)

            }
        }
    }

    /**
     * Suspend version of asSnapshotStateList() for optimal performance in coroutine contexts.
     * Creates reactive state list under read lock for thread safety.
     */
    suspend fun asSnapshotStateListSuspend(
        scope: CoroutineScope,
        entryComparator: Comparator<Map.Entry<KeyType, ValueType>>? = null,
        filter: ((Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
    ): SnapshotStateList<Map.Entry<KeyType, ValueType>> = rwLock.withReadLock {
        data class Entry<K, V>(override val key: K, override val value: V) : Map.Entry<K, V>

        @Suppress("UNCHECKED_CAST")
        val entryCmp = entryComparator ?: run {
            val keyCmp = comparator ?: Comparator { k1, k2 ->
                (k1 as Comparable<KeyType>).compareTo(k2)
            }
            Comparator { e1, e2 -> keyCmp.compare(e1.key, e2.key) }
        }


        suspend fun sortedSuspend(): List<Map.Entry<KeyType, ValueType>> = rwLock.withReadLock {
            // Optimized: Use unsafe node filtering with single lock  
            val filteredNodes = if (filter != null) {
                filterNodesUnsafe { node ->
                    val entry = Entry(node.key, node.value)
                    filter.invoke(entry)
                }
            } else {
                filterNodesUnsafe { true } // Get all nodes
            }
            filteredNodes.map { Entry(it.key, it.value) }.sortedWith(entryCmp)
        }

        val list = sortedSuspend().toMutableStateList()

        // Set up reactive updates outside the lock
        scope.launch {
            changes.collect { change ->
                when (change) {
                    is Put -> {
                        val entry = Entry(change.key, change.value)
                        val passes = filter?.invoke(entry) != false
                        
                        if (change.isUpdate) {
                            // Find and remove old entry, then add new one if it passes filter
                            val oldEntry = change.oldValue?.let { Entry(change.key, it) }
                            if (oldEntry != null) {
                                val oldIndex = list.indexOfFirst { it.key == change.key }
                                if (oldIndex >= 0) {
                                    list.removeAt(oldIndex)
                                }
                            }
                            if (passes) {
                                // Find correct insertion point using binary search
                                var insertIndex = 0
                                while (insertIndex < list.size && entryCmp.compare(entry, list[insertIndex]) > 0) {
                                    insertIndex++
                                }
                                list.add(insertIndex, entry)
                            }
                        } else if (passes) {
                            // New entry - find correct insertion point
                            var insertIndex = 0
                            while (insertIndex < list.size && entryCmp.compare(entry, list[insertIndex]) > 0) {
                                insertIndex++
                            }
                            list.add(insertIndex, entry)
                        }
                    }
                    is Removed -> {
                        // Find and remove the entry with matching key
                        val removeIndex = list.indexOfFirst { it.key == change.key }
                        if (removeIndex >= 0) {
                            list.removeAt(removeIndex)
                        }
                    }
                    is Cleared -> list.clear()
                    is Rebuild -> {
                        list.clear()
                        rwLock.withReadLock { list.addAll(sortedSuspend()) }
                    }
                }
            }
        }

        list
    }

    /**
     * Suspend version of getBySecondaryKey for convenient access.
     */
    suspend fun bySecondaryKeySuspend(keyName: String, keyValue: Any): List<ValueType> = 
        getBySecondaryKeySuspend(keyName, keyValue)

    /**
     * Suspend batch operations inherited from TreeMap with Es-specific optimizations.
     */
    override suspend fun putAllSuspend(vararg entries: Pair<KeyType, ValueType>) = super.putAllSuspend(*entries)
    override suspend fun removeAllSuspend(vararg keys: KeyType) = super.removeAllSuspend(*keys)

    /**
     * Es-specific range operations.
     */
    override suspend fun rangeSuspend(
        from: KeyType, fromInclusive: Boolean,
        to: KeyType, toInclusive: Boolean
    ): Map<KeyType, ValueType> = super.rangeSuspend(from, fromInclusive, to, toInclusive)

    fun asSnapshotStateList(
        scope: CoroutineScope,
        entryComparator: Comparator<Map.Entry<KeyType, ValueType>>? = null,
        filter: ((Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
    ): SnapshotStateList<Map.Entry<KeyType, ValueType>> {

        data class Entry<K, V>(override val key: K, override val value: V) : Map.Entry<K, V>

        @Suppress("UNCHECKED_CAST")
        val entryCmp = entryComparator ?: run {
            val keyCmp = comparator ?: Comparator { k1, k2 ->
                (k1 as Comparable<KeyType>).compareTo(k2)
            }
            Comparator { e1, e2 -> keyCmp.compare(e1.key, e2.key) }
        }

        suspend fun sortedSuspend(): List<Map.Entry<KeyType, ValueType>> = rwLock.withReadLock {
            // Optimized: Use unsafe node filtering with single lock  
            val filteredNodes = if (filter != null) {
                filterNodesUnsafe { node ->
                    val entry = Entry(node.key, node.value)
                    filter.invoke(entry)
                }
            } else {
                filterNodesUnsafe { true } // Get all nodes
            }
            filteredNodes.map { Entry(it.key, it.value) }.sortedWith(entryCmp)
        }
        
        fun sorted(): List<Map.Entry<KeyType, ValueType>> = runBlockingMultiplatform { 
            sortedSuspend()
        }

        val list = sorted().toMutableStateList()

        fun findIndex(key: KeyType, value: ValueType, returnInsertionPoint: Boolean = true): Int {
            val targetEntry = Entry(key, value)
            var low = 0
            var high = list.size

            while (low < high) {
                val mid = (low + high) / 2
                val midEntry = list[mid]

                if (entryCmp.compare(targetEntry, midEntry) > 0) {
                    low = mid + 1
                } else {
                    high = mid
                }
            }

            var left = low - 1
            var right = low

            while (left >= 0 || right < list.size) {
                if (right < list.size) {
                    val entry = list[right]
                    if (entry.key == key) return right
                    if (entryCmp.compare(targetEntry, entry) != 0) right = list.size else right++
                }

                if (left >= 0) {
                    val entry = list[left]
                    if (entry.key == key) return left
                    if (entryCmp.compare(targetEntry, entry) != 0) left = -1 else left--
                }
            }

            return if (returnInsertionPoint) low else -1
        }

        scope.launch {
            changes.collect { change ->
                when (change) {
                    is Put -> {
                        val entry = Entry(change.key, change.value)
                        val passes = filter?.invoke(entry) != false

                        if (change.isUpdate) {
                            val oldIndex = findIndex(change.key, change.oldValue!!, false)
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
                        list.clear(); list.addAll(sorted())
                    }
                }
            }
        }

        return list
    }

    fun <SecondaryKeyType : Comparable<SecondaryKeyType>> asSnapshotStateListBySecondaryKey(
        scope: CoroutineScope,
        secondaryKeyName: String,
        secondaryKeyValue: SecondaryKeyType? = null,
        entryComparator: Comparator<Map.Entry<KeyType, ValueType>>? = null,
        filter: ((Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
    ): SnapshotStateList<out Map.Entry<KeyType, ValueType>> {
        return asSnapshotStateListBySecondaryKeyInternal(
            scope = scope,
            secondaryKeyName = secondaryKeyName,
            secondaryKeyValue = secondaryKeyValue,
            entryComparator = entryComparator,
            filter = filter,
            isComparable = true,
            secondaryKeyComparator = null
        )
    }

    fun <SecondaryKeyType> asSnapshotStateListBySecondaryKey(
        scope: CoroutineScope,
        secondaryKeyName: String,
        secondaryKeyComparator: Comparator<SecondaryKeyType>,
        secondaryKeyValue: SecondaryKeyType? = null,
        entryComparator: Comparator<Map.Entry<KeyType, ValueType>>? = null,
        filter: ((Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
    ): SnapshotStateList<out Map.Entry<KeyType, ValueType>> {
        return asSnapshotStateListBySecondaryKeyInternal(
            scope = scope,
            secondaryKeyName = secondaryKeyName,
            secondaryKeyValue = secondaryKeyValue,
            entryComparator = entryComparator,
            filter = filter,
            isComparable = false,
            secondaryKeyComparator = secondaryKeyComparator
        )
    }

    private fun <SecondaryKeyType> asSnapshotStateListBySecondaryKeyInternal(
        scope: CoroutineScope,
        secondaryKeyName: String,
        secondaryKeyValue: SecondaryKeyType? = null,
        entryComparator: Comparator<Map.Entry<KeyType, ValueType>>? = null,
        filter: ((Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
        isComparable: Boolean,
        secondaryKeyComparator: Comparator<SecondaryKeyType>? = null
    ): SnapshotStateList<out Map.Entry<KeyType, ValueType>> {

        data class Entry<K, V>(override val key: K, override val value: V) : Map.Entry<K, V>

        // Find the secondary key spec
        val secondaryKeySpec = secondaryKeysSpec.find { it.name == secondaryKeyName }
            ?: throw IllegalArgumentException("Secondary key '$secondaryKeyName' not found")

        @Suppress("UNCHECKED_CAST")
        val keyExtractor = secondaryKeySpec.keyExtractor as (ValueType) -> SecondaryKeyType?

        // Default comparator: sort by secondary key first, then by primary key
        val entryCmp = entryComparator ?: run {

            val secondaryKeyCmp: Comparator<SecondaryKeyType?> = if (isComparable) {
                // Use natural comparison for Comparable secondary keys
                Comparator { sk1, sk2 ->
                    when {
                        sk1 == null && sk2 == null -> 0
                        sk1 == null -> 1  // nulls last
                        sk2 == null -> -1
                        else -> {
                            @Suppress("UNCHECKED_CAST")
                            (sk1 as Comparable<SecondaryKeyType>).compareTo(sk2)
                        }
                    }
                }
            } else {
                // Use provided comparator for non-Comparable secondary keys
                Comparator { sk1, sk2 ->
                    when {
                        sk1 == null && sk2 == null -> 0
                        sk1 == null -> 1  // nulls last
                        sk2 == null -> -1
                        else -> secondaryKeyComparator!!.compare(sk1, sk2)
                    }
                }
            }

            Comparator<Map.Entry<KeyType, ValueType>> { e1, e2 ->
                // First compare by secondary key
                val sk1 = keyExtractor(e1.value)
                val sk2 = keyExtractor(e2.value)

                val secondaryComparison = secondaryKeyCmp.compare(sk1, sk2)

                // If secondary keys are equal, fall back to primary key comparison
                if (secondaryComparison != 0) {
                    secondaryComparison
                } else {
                    (comparator ?: Comparator { k1, k2 ->
                        @Suppress("UNCHECKED_CAST")
                        (k1 as Comparable<KeyType>).compareTo(k2)
                    }).compare(e1.key, e2.key)
                }
            }
        }

        fun getFilteredEntries(): List<Map.Entry<KeyType, ValueType>> = runBlockingMultiplatform {
            rwLock.withReadLock {
                val entries = if (secondaryKeyValue != null) {
                    // Optimized: Use unsafe methods for batch secondary index lookup
                    val primaryKeys = secondaryIndexes[secondaryKeyName]?.get(secondaryKeyValue) ?: emptySet()
                    primaryKeys.mapNotNull { key ->
                        findNodeUnsafe(key)?.let { node ->
                            val entry = Entry(key, node.value)
                            if (filter?.invoke(entry) != false) entry else null
                        }
                    }
                } else {
                    // Optimized: Use unsafe node filtering for maximum efficiency
                    val filteredNodes = if (filter != null) {
                        filterNodesUnsafe { node ->
                            val entry = Entry(node.key, node.value)
                            filter.invoke(entry)
                        }
                    } else {
                        filterNodesUnsafe { true } // Get all nodes
                    }
                    filteredNodes.map { Entry(it.key, it.value) }
                }

                entries.sortedWith(entryCmp)
            }
        }

        val list = getFilteredEntries().map { Entry(it.key, it.value) }.toMutableStateList()

        // Use the same sophisticated findIndex as the original
        fun findIndex(key: KeyType, value: ValueType, returnInsertionPoint: Boolean = true): Int {
            val targetEntry = Entry(key, value)
            var low = 0
            var high = list.size

            while (low < high) {
                val mid = (low + high) / 2
                val midEntry = list[mid]

                if (entryCmp.compare(targetEntry, midEntry) > 0) {
                    low = mid + 1
                } else {
                    high = mid
                }
            }

            var left = low - 1
            var right = low

            while (left >= 0 || right < list.size) {
                if (right < list.size) {
                    val entry = list[right]
                    if (entry.key == key) return right
                    if (entryCmp.compare(targetEntry, entry) != 0) right = list.size else right++
                }

                if (left >= 0) {
                    val entry = list[left]
                    if (entry.key == key) return left
                    if (entryCmp.compare(targetEntry, entry) != 0) left = -1 else left--
                }
            }

            return if (returnInsertionPoint) low else -1
        }

        scope.launch {
            changes.collect { change ->
                when (change) {
                    is Put -> {
                        val entry = Entry(change.key, change.value)
                        val newSecondaryKey = keyExtractor(change.value)
                        val matchesFilter = secondaryKeyValue == null || newSecondaryKey == secondaryKeyValue
                        val passes = matchesFilter && filter?.invoke(entry) != false

                        if (change.isUpdate && change.oldValue != null) {
                            val oldSecondaryKey = keyExtractor(change.oldValue)
                            val oldMatchesFilter = secondaryKeyValue == null || oldSecondaryKey == secondaryKeyValue

                            // Remove old entry if it was in the list
                            if (oldMatchesFilter) {
                                val oldIndex = findIndex(change.key, change.oldValue, returnInsertionPoint = false)
                                if (oldIndex >= 0) {
                                    list.removeAt(oldIndex)
                                }
                            }

                            // Add new entry if it passes
                            if (passes) {
                                val insertIndex = findIndex(change.key, change.value)
                                list.add(insertIndex, entry)
                            }
                        } else if (passes) {
                            // New entry
                            val insertIndex = findIndex(change.key, change.value)
                            list.add(insertIndex, entry)
                        }
                    }

                    is Removed -> {
                        val index = findIndex(change.key, change.oldValue, returnInsertionPoint = false)
                        if (index >= 0) {
                            list.removeAt(index)
                        }
                    }

                    is Cleared -> {
                        list.clear()
                    }

                    is Rebuild -> {
                        list.clear()
                        list.addAll(getFilteredEntries().map { Entry(it.key, it.value) })
                    }
                }
            }
        }

        return list
    }

}

data class PersistedEsInfo<KeyType: Any, ValueType: Any>(
    val keyTypeSerializer: KSerializer<KeyType>,
    val valueTypeSerializer: KSerializer<ValueType>,
    val rootDir: Path?,
    val fileSystem: FileSystem
)
