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

/**
 * Reactive observable collection that extends TreeMap with change notifications and UI integrations.
 *
 * Es (\"Elements\") provides a high-performance indexed collection with reactive change notifications,
 * making it ideal for building reactive UIs and implementing persistence layers. It combines the
 * O(log n) performance of red-black trees with Flow-based reactivity.
 *
 * ## Key Features
 *
 * - **Reactive changes**: All modifications emit change events via `changes` Flow
 * - **High performance**: O(log n) operations with secondary index support  
 * - **UI integration**: Direct conversion to Compose SnapshotStateList
 * - **Multi-criteria filtering**: Efficient secondary index-based queries
 * - **Extensible persistence**: Designed to be extended by Eps for file persistence
 *
 * ## Architecture
 *
 * ```
 * TreeMap (efficient map operations)
 *   ↓ extends
 * Es (+ reactive change notifications)
 *   ↓ extends  
 * Eps (+ file persistence)
 *   ↓ extends
 * Esps (+ bi-directional sync)
 * ```
 *
 * ## Usage Examples
 *
 * ```kotlin
 * // Basic reactive collection
 * val users = es<String, User> { it.id }
 * users[\"alice\"] = User(\"alice\", \"Engineering\", \"Senior\")
 *
 * // With secondary indexes for efficient filtering
 * val employees = es<String, Employee>(
 *     keySelector = { it.employeeId },
 *     secondaryKeys = {
 *         key(\"department\") { it.department }
 *         key(\"level\") { it.level }
 *         key(\"location\") { it.office.city }
 *     }
 * )
 *
 * // Multi-criteria queries (O(log n) per criterion)
 * val seniorEngineers = employees.getBy(
 *     \"department\" to \"Engineering\",
 *     \"level\" to \"Senior\"
 * )
 *
 * // Reactive UI integration
 * val liveList = employees.asSnapshotStateList(
 *     scope = viewModelScope,
 *     filter = { it.value.isActive }
 * )
 *
 * // Observe changes
 * lifecycleScope.launch {
 *     employees.changes.collect { change ->
 *         when (change) {
 *             is TreeMap.MapChange.Put -> println(\"Added: ${change.key}\")
 *             is TreeMap.MapChange.Removed -> println(\"Removed: ${change.key}\")
 *             is TreeMap.MapChange.Cleared -> println(\"Collection cleared\")
 *             is TreeMap.MapChange.Rebuild -> println(\"Collection rebuilt\")
 *         }
 *     }
 * }
 * ```
 *
 * ## Performance Characteristics
 *
 * - **Basic operations**: O(log n) for get/put/remove
 * - **Secondary index queries**: O(log n) per criterion + O(k) for result set
 * - **Change emission**: O(1) for most operations
 * - **UI updates**: Efficient incremental updates to SnapshotStateList
 *
 * ## Thread Safety
 *
 * Es operations are thread-safe through the underlying TreeMap's ReadWriteLock implementation.
 * Multiple readers can access concurrently, while writes are exclusive.
 *
 * @param KeyType the type of keys (must be non-nullable)
 * @param ValueType the type of values stored (must be non-nullable)
 *
 * @see eps for persistent collections
 * @see esps for synchronized collections  
 * @see TreeMap for the underlying map implementation
 */
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

    /**
     * Creates a reactive SnapshotStateList that automatically updates when this collection changes.
     *
     * This function bridges the gap between OkTopoi's reactive collections and Compose UI,
     * providing a SnapshotStateList that reflects changes to the underlying Es in real-time.
     * The list is kept in sync via the `changes` Flow, with efficient incremental updates.
     *
     * ## Performance
     *
     * - **Initial creation**: O(n log n) if custom comparator provided, O(n) otherwise
     * - **Updates**: O(log n) for insertions/updates, O(log n) for removals (uses binary search)
     * - **Memory**: Maintains separate list copy optimized for UI rendering
     *
     * ## Threading
     *
     * The returned list is thread-safe for Compose usage. Updates are applied on the provided
     * CoroutineScope, which should typically be a UI-scoped scope like `viewModelScope`.
     *
     * @param scope CoroutineScope for managing change observation (typically viewModelScope)
     * @param entryComparator custom comparator for entry ordering (null = use key ordering)
     * @param filter optional predicate to include/exclude entries from the list
     * @param initialEntriesProvider custom provider for initial entries (advanced usage)
     * @return reactive SnapshotStateList synchronized with this collection
     *
     * @sample
     * ```kotlin
     * // Basic reactive list
     * val userList = users.asSnapshotStateList(scope = viewModelScope)
     *
     * // With filtering
     * val activeUsers = users.asSnapshotStateList(
     *     scope = viewModelScope,
     *     filter = { it.value.isActive }
     * )
     *
     * // With custom sorting
     * val usersByName = users.asSnapshotStateList(
     *     scope = viewModelScope,
     *     entryComparator = compareBy { it.value.name }
     * )
     * ```
     */
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
     * Creates a reactive SnapshotStateList filtered by multiple secondary key criteria with efficient O(k) initial lookup.
     *
     * This function provides high-performance filtered reactive lists by leveraging secondary indexes.
     * Instead of scanning all entries, it uses TreeMap's secondary index intersection to find matching
     * entries in O(log n) time per criterion, then maintains reactivity for the filtered subset.
     *
     * ## Performance Advantages
     *
     * - **Initial lookup**: O(log n) per criterion vs O(n) for full scan filtering
     * - **Index intersection**: Efficient set operations on primary keys
     * - **Reactive updates**: Only relevant changes trigger UI updates
     * - **Memory efficient**: Indexes store only primary keys, not full objects
     *
     * ## Usage Requirements
     *
     * - All criteria keys must be defined in the collection's secondary indexes
     * - Criteria values are compared using equality (== not ===)
     * - Custom entryComparator is required for this overload
     *
     * @param scope CoroutineScope for managing change observation
     * @param criteria variable number of secondary key criteria as "indexName" to value pairs
     * @param entryComparator comparator for entry ordering (required for this overload)
     * @param filter optional additional predicate filter (applied after secondary key filtering)
     * @return reactive SnapshotStateList with efficient secondary key filtering
     *
     * @sample
     * ```kotlin
     * // Filter by multiple criteria
     * val seniorEngineersInNY = employees.asSnapshotStateListBySecondaryKey(
     *     scope = viewModelScope,
     *     "department" to "Engineering",
     *     "level" to "Senior", 
     *     "location" to "New York",
     *     entryComparator = compareBy { it.value.name }
     * )
     *
     * // With additional filter
     * val activeEmployees = employees.asSnapshotStateListBySecondaryKey(
     *     scope = viewModelScope,
     *     "department" to "Engineering",
     *     entryComparator = compareBy { it.value.startDate },
     *     filter = { it.value.status == "Active" }
     * )
     * ```
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
     * Creates a reactive SnapshotStateList filtered by multiple secondary key criteria with automatic sorting.
     *
     * This convenience overload automatically generates a comparator that sorts entries by
     * the secondary key values in the order they are specified in the criteria. It's ideal
     * when you want the filtered list sorted by the same criteria used for filtering.
     *
     * ## Automatic Sorting Logic
     *
     * The generated comparator compares entries by each criterion in order:
     * 1. Compare by first secondary key value
     * 2. If equal, compare by second secondary key value  
     * 3. Continue until a difference is found
     * 4. If all secondary keys are equal, compare by primary key
     *
     * ## Performance & Requirements
     *
     * - Same O(log n) per criterion performance as the explicit comparator overload
     * - All criteria values must implement Comparable
     * - All criteria keys must exist in secondary indexes
     *
     * @param scope CoroutineScope for managing change observation
     * @param criteria variable number of secondary key criteria with Comparable values
     * @param filter optional additional predicate filter
     * @return reactive SnapshotStateList sorted by secondary key values
     *
     * @sample
     * ```kotlin
     * // Automatic sorting by department, then level, then location
     * val sortedEmployees = employees.asSnapshotStateListBySecondaryKey(
     *     scope = viewModelScope,
     *     "department" to "Engineering",
     *     "level" to "Senior"
     * ) // Results sorted by: department, then level, then primary key
     *
     * // With additional filtering
     * val filteredResults = employees.asSnapshotStateListBySecondaryKey(
     *     scope = viewModelScope,
     *     "location" to "Seattle",
     *     filter = { it.value.yearsExperience >= 5 }
     * )
     * ```
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

