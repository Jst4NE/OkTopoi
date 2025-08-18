package jst.oktopoi

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import jst.oktopoi.TreeMap.MapChange.Cleared
import jst.oktopoi.TreeMap.MapChange.Put
import jst.oktopoi.TreeMap.MapChange.Rebuild
import jst.oktopoi.TreeMap.MapChange.Removed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.log
import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.BufferOverflow


open class Es<KeyType : Any, ValueType : Any> : TreeMap<KeyType, ValueType> {

    protected val keySelector: ((ValueType) -> KeyType)?
    
    // Flow-based change notifications for reactive programming
    private val _changeFlow = MutableSharedFlow<MapChange<KeyType, ValueType>>(
        replay = OkTopoiConstants.DEFAULT_CHANGE_FLOW_REPLAY_SIZE, 
        extraBufferCapacity = OkTopoiConstants.DEFAULT_CHANGE_FLOW_BUFFER_SIZE, 
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    
    /**
     * Flow of changes made to this Es.
     * Useful for reactive programming and implementing persistence.
     * 
     * **Backpressure handling:** This flow has a limited buffer. If consumers are too slow
     * and the buffer overflows, an exception will be thrown. Slow consumers should add
     * buffering to their flow chain: `collection.changes.buffer(10000).collect { ... }`
     */
    val changes: SharedFlow<MapChange<KeyType, ValueType>> = _changeFlow.asSharedFlow()

    constructor(
        keySelector: ((ValueType) -> KeyType)?,
        sortingBy: Comparator<KeyType>,
        secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
    ) : super(sortingBy, secondaryKeys) {
        this.keySelector = keySelector
    }

    lateinit var callingClassName: String
    lateinit var propertyName: String

    internal open fun setup() {
        // No-op for non-persisted Es instances
        // Overridden by Eps for persistence functionality
    }
    
    /**
     * Emit a change notification to the flow.
     */
    protected fun emitChange(change: MapChange<KeyType, ValueType>) {
        val success = _changeFlow.tryEmit(change)
        if (!success) {
            throw IllegalStateException("Change flow buffer overflow. Consider increasing buffer size or adding .buffer() to your flow consumer.")
        }
    }
    
    // ========================================================================
    // Override TreeMap methods to add change emission
    // ========================================================================
    
    override fun put(key: KeyType, value: ValueType): ValueType? {
        val oldValue = super.put(key, value)
        emitChange(Put(key, value, oldValue != null, oldValue))
        return oldValue
    }
    
    override fun remove(key: KeyType): ValueType? {
        val oldValue = super.remove(key)
        if (oldValue != null) {
            emitChange(Removed(key, oldValue))
        }
        return oldValue
    }
    
    override fun clear() {
        super.clear()
        emitChange(Cleared())
    }
    
    // putAll delegates to put(), so it automatically gets change emission
    
    // ========================================================================
    // Override advanced Map methods to add missing change emission
    // ========================================================================
    
    override fun putIfAbsent(key: KeyType, value: ValueType): ValueType? {
        val oldValue = super.putIfAbsent(key, value)
        if (oldValue == null) {
            // Value was inserted
            emitChange(Put(key, value, false, null))
        }
        return oldValue
    }
    
    override fun replace(key: KeyType, value: ValueType): ValueType? {
        val oldValue = super.replace(key, value)
        if (oldValue != null) {
            // Value was replaced
            emitChange(Put(key, value, true, oldValue))
        }
        return oldValue
    }
    
    override fun replace(key: KeyType, oldValue: ValueType, newValue: ValueType): Boolean {
        val result = super.replace(key, oldValue, newValue)
        if (result) {
            // Value was replaced
            emitChange(Put(key, newValue, true, oldValue))
        }
        return result
    }
    
    override fun compute(key: KeyType, remappingFunction: (KeyType, ValueType?) -> ValueType?): ValueType? {
        val hadKey = containsKey(key)
        val oldValue = if (hadKey) get(key) else null
        val result = super.compute(key, remappingFunction)
        
        when {
            hadKey && result == null -> {
                // Entry was removed
                emitChange(Removed(key, oldValue!!))
            }
            result != null -> {
                // Entry was added or updated
                emitChange(Put(key, result, hadKey, oldValue))
            }
            // !hadKey && result == null -> no change, no emission
        }
        return result
    }
    
    override fun computeIfAbsent(key: KeyType, mappingFunction: (KeyType) -> ValueType?): ValueType? {
        val hadKey = containsKey(key)
        val result = super.computeIfAbsent(key, mappingFunction)
        if (result != null && !hadKey) {
            // Value was created
            emitChange(Put(key, result, false, null))
        }
        return result
    }
    
    override fun computeIfPresent(key: KeyType, remappingFunction: (KeyType, ValueType) -> ValueType?): ValueType? {
        val hadKey = containsKey(key)
        val oldValue = if (hadKey) get(key) else null
        val result = super.computeIfPresent(key, remappingFunction)
        
        if (result != null && hadKey) {
            // Value was modified
            emitChange(Put(key, result, true, oldValue))
        } else if (result == null && hadKey) {
            // Entry was removed
            emitChange(Removed(key, oldValue!!))
        }
        return result
    }
    
    override fun merge(key: KeyType, value: ValueType, remappingFunction: (ValueType, ValueType) -> ValueType?): ValueType? {
        val hadKey = containsKey(key)
        val oldValue = if (hadKey) get(key) else null
        val result = super.merge(key, value, remappingFunction)
        
        if (result != null) {
            // Value was merged/inserted
            emitChange(Put(key, result, hadKey, oldValue))
        } else if (hadKey) {
            // Entry was removed by merge function returning null
            emitChange(Removed(key, oldValue!!))
        }
        return result
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

