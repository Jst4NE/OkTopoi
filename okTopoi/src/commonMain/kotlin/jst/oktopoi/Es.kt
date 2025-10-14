package jst.oktopoi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import co.touchlab.kermit.Logger
import jst.oktopoi.TreeMap.MapChange.Cleared
import jst.oktopoi.TreeMap.MapChange.Put
import jst.oktopoi.TreeMap.MapChange.Rebuild
import jst.oktopoi.TreeMap.MapChange.Removed
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

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

    private val log = Logger.withTag(this::class.simpleName.toString())

    // Flow-based change notifications for reactive programming
    private val _changeFlow = MutableSharedFlow<MapChange<KeyType, ValueType>>(
        replay = 0,
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
        sortingBy: Comparator<KeyType>,
        secondaryKeys: SecondaryIndexBuilder<KeyType, ValueType>.() -> Unit = {}
    ) : super(sortingBy, secondaryKeys)

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
    // Override TreeMap unsafe hooks to add change emission
    // ========================================================================
    
    override fun onAfterPutUnsafe(key: KeyType, newValue: ValueType, oldValue: ValueType?) {
        super.onAfterPutUnsafe(key, newValue, oldValue)  // Call parent (secondary indexes)
        emitChange(Put(key, newValue, oldValue != null, oldValue))
    }
    
    override fun onAfterRemoveUnsafe(key: KeyType, oldValue: ValueType) {
        super.onAfterRemoveUnsafe(key, oldValue)  // Call parent (secondary indexes)
        emitChange(Removed(key, oldValue))
    }
    
    override fun onAfterClearUnsafe() {
        super.onAfterClearUnsafe()  // Call parent (secondary indexes)
        emitChange(Cleared<KeyType, ValueType>())
    }

    /**
     * Creates a reactive SnapshotStateList that automatically updates when this collection changes.
     *
     * This function bridges the gap between OkTopoi's reactive collections and Compose UI,
     * providing a SnapshotStateList that reflects changes to the underlying Es in real-time.
     * The list is kept in sync via the `changes` Flow with efficient incremental updates and
     * lifecycle awareness.
     *
     * ## Performance
     *
     * - Initial creation: O(n log n) if custom comparator provided, O(n) otherwise
     * - Updates: O(log n) for insertions/updates, O(log n) for removals (uses binary search)
     * - Memory: Maintains separate list copy optimized for UI rendering
     *
     * ## Lifecycle & Threading
     *
     * - Collection is lifecycle-aware and runs only while the provided lifecycleOwner is at least
     *   `minActiveState` (defaults to STARTED). On each (re)subscription, a Rebuild event is injected
     *   to reconcile any missed changes while the lifecycle was inactive.
     * - Mutations to the returned list occur on the main thread.
     *
     * ## Important: Parameter Stability
     *
     * **The `entryComparator` and `filter` parameters must be stable across recompositions.**
     * Unstable parameters will cause the LaunchedEffect to restart unnecessarily.
     *
     * ### Manual Wrapping Required
     * 
     * You must manually wrap unstable expressions with `remember { }`:
     * 
     * ```kotlin
     * // ✅ Correct - Direct inline lambda wrapped with remember
     * val list = users.asSnapshotStateList(
     *     filter = remember { { it.value.isActive } }
     * )
     *
     * // ✅ Correct - Comparator factory wrapped with remember
     * val list = users.asSnapshotStateList(
     *     entryComparator = remember { compareBy { it.value.name } }
     * )
     *
     * // ❌ Incorrect - Unstable lambda causes constant recomposition
     * val list = users.asSnapshotStateList(
     *     filter = { it.value.isActive }  // This will recompose constantly!
     * )
     * ```
     *
     * @param entryComparator custom comparator for entry ordering (null = use key ordering).
     *                        **Must be stable across recompositions.**
     * @param filter optional predicate to include/exclude entries from the list.
     *               **Must be stable across recompositions.**
     * @param initialEntriesProvider custom provider for initial entries (advanced usage)
     * @param lifecycleOwner owner that controls collection lifecycle (defaults to LocalLifecycleOwner)
     * @param minActiveState minimum lifecycle state required for collection (defaults to STARTED)
     * @return reactive SnapshotStateList synchronized with this collection
     *
     * @sample
     * ```kotlin
     * // Basic reactive list
     * val userList = users.asSnapshotStateList()
     *
     * // With filtering
     * val activeUsers = users.asSnapshotStateList(
     *     filter = { it.value.isActive }
     * )
     *
     * // With custom sorting
     * val usersByName = users.asSnapshotStateList(
     *     entryComparator = compareBy { it.value.name }
     * )
     * ```
     */
    @Composable
    fun asSnapshotStateList(
        entryComparator: Comparator<Map.Entry<KeyType, ValueType>>? = null,
        filter: ((Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
        initialEntriesProvider: (() -> Collection<Map.Entry<KeyType, ValueType>>) = { entries },
        lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    ): SnapshotStateList<Map.Entry<KeyType, ValueType>> {

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

        // Stable list identity across recompositions
        val list = remember(entryComparator, filter) { getSortedEntries().toMutableStateList() }

        // Create comparator with default fallback
        val entryCmp = remember(entryComparator) { entryComparator ?: Comparator { e1, e2, -> keyComparator.compare(e1.key, e2.key) } }

        LaunchedEffect(entryComparator, filter, minActiveState) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
                changes
                    .onStart { emit(Rebuild()) }
                    .collect { change ->
                        when (change) {
                            is Put -> handlePutInSortedList(list, change, entryCmp, filter)
                            is Removed -> handleRemoveInSortedList(list, change, entryCmp)
                            is Cleared -> list.clear()
                            is Rebuild -> {
                                list.clear()
                                list.addAll(getSortedEntries())
                            }
                        }
                    }
            }
        }

        return list
    }

    /**
     * Creates a reactive SnapshotStateMap that groups entries by a secondary key value.
     *
     * Each map entry contains a SnapshotStateList of entries matching that secondary key value.
     * The map automatically updates as entries are added/removed/modified, providing granular
     * reactivity where only affected groups trigger recomposition.
     *
     * ## How it Works
     *
     * - Returns `SnapshotStateMap<SecondaryKeyValue, SnapshotStateList<Entry<K, V>>>`
     * - Map keys are the distinct values of the specified secondary key
     * - Each SnapshotStateList independently tracks changes for that secondary key value
     * - New secondary key values automatically create new map entries
     * - Empty lists are automatically removed from the map
     *
     * ## Performance
     *
     * - Initial creation: O(n) to populate all secondary key groups
     * - Updates: O(log n) per affected group only
     * - **Granular reactivity**: Only affected SnapshotStateLists recompose
     * - Memory: Map + N lists where N = distinct secondary key values
     *
     * ## Use Cases
     *
     * Perfect for scenarios where you need:
     * - Multiple filtered lists that share the same underlying data
     * - Different UI components showing different subsets
     * - Filtering at parent level with per-group reactive lists
     * - Avoiding duplicate snapshot state list creation
     *
     * ## Lifecycle
     *
     * Collection is lifecycle-aware and runs only while `lifecycleOwner` is at least `minActiveState`.
     * On every (re)subscription, a Rebuild event is injected to reconcile missed changes.
     *
     * @param groupByKey name of the secondary index to group by
     * @param entryComparator comparator for entries within each list.
     *                        **Must be stable across recompositions.**
     * @param filter optional predicate applied to all entries.
     *               **Must be stable across recompositions.**
     * @param lifecycleOwner lifecycle owner (defaults to LocalLifecycleOwner)
     * @param minActiveState minimum lifecycle state (defaults to STARTED)
     * @return reactive SnapshotStateMap grouping entries by secondary key
     *
     * @sample
     * ```kotlin
     * // Group dispatches by journey ID
     * val dispatchesByJourney = dispatches.asSnapshotStateMapBySecondaryKey(
     *     groupByKey = DispatchDto::journeyId.name,
     *     entryComparator = compareBy { it.value.sequenceNumber },
     *     filter = { it.value.progress != ProgressType.COMPLETED }
     * )
     *
     * // Access in JourneyCard - only this card recomposes on changes
     * val dispatches = dispatchesByJourney[journey.id] ?: emptyList()
     * ```
     */
    @Composable
    fun <SK : Any> asSnapshotStateMapBySecondaryKey(
        groupByKey: String,
        entryComparator: Comparator<Map.Entry<KeyType, ValueType>>,
        filter: ((Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
        lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    ): SnapshotStateMap<SK, SnapshotStateList<Map.Entry<KeyType, ValueType>>> {

        // Stable map identity across recompositions
        val map = remember(groupByKey, entryComparator, filter) {
            SnapshotStateMap<SK, SnapshotStateList<Map.Entry<KeyType, ValueType>>>()
        }

        // Helper: get or create list for a secondary key value
        fun getOrCreateList(secondaryKeyValue: SK): SnapshotStateList<Map.Entry<KeyType, ValueType>> {
            return map.getOrPut(secondaryKeyValue) {
                mutableStateListOf()
            }
        }

        // Helper: remove list if empty
        fun removeIfEmpty(secondaryKeyValue: SK) {
            map[secondaryKeyValue]?.let { if (it.isEmpty()) map.remove(secondaryKeyValue) }
        }

        // Helper: rebuild entire map from current state
        suspend fun rebuild() {
            map.clear()
            val grouped = mutableMapOf<SK, MutableList<Map.Entry<KeyType, ValueType>>>()

            withReadLock {
                entries.forEach { entry ->
                    if (filter?.invoke(entry) != false) {
                        (getSecondaryKey(groupByKey, entry.value) as? SK)?.let { secKeyValue ->
                            grouped.getOrPut(secKeyValue) { mutableListOf() }.add(entry)
                        }
                    }
                }
            }

            grouped.forEach { (secKeyValue, entries) ->
                map[secKeyValue] = entries.sortedWith(entryComparator).toMutableStateList()
            }
        }

        LaunchedEffect(groupByKey, entryComparator, filter, minActiveState) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
                changes.onStart { emit(Rebuild()) }.collect { change ->
                    when (change) {
                        is Put -> {
                            val entry = MapEntry(change.key, change.value)
                            val passes = filter?.invoke(entry) != false
                            val secKeyValue = getSecondaryKey(groupByKey, entry.value) as? SK

                            // Handle old value removal (if secondary key changed)
                            if (change.isUpdate && change.oldValue != null) {
                                val oldSecKeyValue = getSecondaryKey(groupByKey, change.oldValue) as? SK
                                oldSecKeyValue?.let { oldKey ->
                                    map[oldKey]?.let { oldList ->
                                        handleRemoveInSortedList(
                                            oldList,
                                            Removed(change.key, change.oldValue),
                                            entryComparator
                                        )
                                        removeIfEmpty(oldKey)
                                    }
                                }
                            }

                            // Add new value if passes filter
                            if (passes && secKeyValue != null) {
                                handlePutInSortedList(
                                    getOrCreateList(secKeyValue),
                                    change,
                                    entryComparator,
                                    null // Filter already applied above
                                )
                            }
                        }

                        is Removed -> {
                            (getSecondaryKey(groupByKey, change.oldValue) as? SK)?.let { secKeyValue ->
                                map[secKeyValue]?.let { list ->
                                    handleRemoveInSortedList(list, change, entryComparator)
                                    removeIfEmpty(secKeyValue)
                                }
                            }
                        }

                        is Cleared -> map.clear()
                        is Rebuild -> rebuild()
                    }
                }
            }
        }

        return map
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
     * - Initial lookup: O(log n) per criterion vs O(n) for full scan filtering
     * - Index intersection: Efficient set operations on primary keys
     * - Reactive updates: Only relevant changes trigger UI updates
     * - Memory efficient: Indexes store only primary keys, not full objects
     *
     * ## Lifecycle
     *
     * Collection is lifecycle-aware and runs only while `lifecycleOwner` is at least `minActiveState`.
     * On every (re)subscription, a Rebuild event is injected to reconcile missed changes.
     *
     * ## Usage Requirements
     *
     * - All criteria keys must be defined in the collection's secondary indexes
     * - Criteria values are compared using equality (== not ===)
     * - Custom entryComparator is required for this overload
     *
     * ## Parameter Stability & Compiler Plugin Support
     *
     * **The `entryComparator` and `filter` parameters must be stable across recompositions.**
     * 
     * The OkTopoi compiler plugin automatically handles certain unstable expressions:
     * - **✅ Auto-wrapped:** Direct lambdas, Comparator constructors, `compareBy`, `nullsFirst`, etc.
     * - **❌ Manual wrapping needed:** Variable references, properties, custom functions
     * 
     * See `asSnapshotStateList` documentation for detailed examples
     *
     * @param criteria variable number of secondary key criteria as "indexName" to value pairs
     * @param entryComparator comparator for entry ordering (required for this overload).
     *                        **Must be stable across recompositions.**
     * @param filter optional additional predicate filter (applied after secondary key filtering).
     *               **Must be stable across recompositions.**
     * @param lifecycleOwner owner that controls collection lifecycle (defaults to LocalLifecycleOwner)
     * @param minActiveState minimum lifecycle state required for collection (defaults to STARTED)
     * @return reactive SnapshotStateList with efficient secondary key filtering
     *
     * @sample
     * ```kotlin
     * // Filter by multiple criteria
     * val seniorEngineersInNY = employees.asSnapshotStateListBySecondaryKey(
     *     "department" to "Engineering",
     *     "level" to "Senior", 
     *     "location" to "New York",
     *     entryComparator = compareBy { it.value.name }
     * )
     *
     * // With additional filter
     * val activeEmployees = employees.asSnapshotStateListBySecondaryKey(
     *     "department" to "Engineering",
     *     entryComparator = compareBy { it.value.startDate },
     *     filter = { it.value.status == "Active" }
     * )
     * ```
     */
    @Composable
    fun asSnapshotStateListBySecondaryKey(
        vararg criteria: Pair<String, Any?>,
        entryComparator: Comparator<Map.Entry<KeyType, ValueType>>,
        filter: ((Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
        lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    ): SnapshotStateList<Map.Entry<KeyType, ValueType>> {

        val initialEntriesProvider: () -> List<MapEntry<KeyType, ValueType>> = remember(*criteria) { {
            val entries = mutableListOf<MapEntry<KeyType, ValueType>>()
            forEachBy(*criteria) { key, value -> entries.add(MapEntry(key, value)) }
            entries
        } }

        val filterWithCriteria: ((Map.Entry<KeyType, ValueType>) -> Boolean) = { entry ->
            val passesFilter = filter?.invoke(entry) != false
            val passesCriteria = criteria.all { (indexName, expectedValue) ->
                getSecondaryKey(indexName, entry.value) == expectedValue
            }
            passesFilter && passesCriteria
        }

        return asSnapshotStateList(
            entryComparator = entryComparator,
            filter = filterWithCriteria,
            initialEntriesProvider = initialEntriesProvider,
            lifecycleOwner = lifecycleOwner,
            minActiveState = minActiveState
        )
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
     * ## Lifecycle
     *
     * Collection is lifecycle-aware and runs only while `lifecycleOwner` is at least `minActiveState`.
     * On every (re)subscription, a Rebuild event is injected to reconcile missed changes.
     *
     * @param criteria variable number of secondary key criteria with Comparable values
     * @param filter optional additional predicate filter.
     *               **Must be stable across recompositions if provided.**
     * @param lifecycleOwner owner that controls collection lifecycle (defaults to LocalLifecycleOwner)
     * @param minActiveState minimum lifecycle state required for collection (defaults to STARTED)
     * @return reactive SnapshotStateList sorted by secondary key values
     *
     * @sample
     * ```kotlin
     * // Automatic sorting by department, then level, then location
     * val sortedEmployees = employees.asSnapshotStateListBySecondaryKey(
     *     "department" to "Engineering",
     *     "level" to "Senior"
     * ) // Results sorted by: department, then level, then primary key
     *
     * // With additional filtering
     * val filteredResults = employees.asSnapshotStateListBySecondaryKey(
     *     "location" to "Seattle",
     *     filter = { it.value.yearsExperience >= 5 }
     * )
     * ```
     */
    @Composable
    fun asSnapshotStateListBySecondaryKey(
        vararg criteria: Pair<String, Comparable<*>?>,
        filter: ((Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
        lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    ): SnapshotStateList<Map.Entry<KeyType, ValueType>> {

        val comparator: Comparator<Map.Entry<KeyType, ValueType>> = remember(*criteria) {
            Comparator { e1, e2 ->
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
        }

        return asSnapshotStateListBySecondaryKey(
            criteria = criteria,
            entryComparator = comparator,
            filter = filter,
            lifecycleOwner = lifecycleOwner,
            minActiveState = minActiveState
        )
    }

    /**
     * Creates a reactive SnapshotStateList with multi-source joins and automatic dependency tracking.
     *
     * This advanced function enables efficient reactive queries across multiple Es collections with
     * granular change propagation. When related data changes, only entries that depend on that
     * specific data are re-evaluated, providing optimal performance for complex multi-table queries.
     *
     * ## How It Works
     *
     * 1. **filterMap execution**: For each primary entry, executes your lambda with a fetch() function
     * 2. **Automatic dependency tracking**: Every fetch() call is recorded as a dependency
     * 3. **Granular reactivity**: When any fetched value changes, only dependent entries re-evaluate
     * 4. **Incremental updates**: Only affected list items trigger recomposition
     *
     * ## Performance
     *
     * - Initial creation: O(n) where n = primary collection size
     * - On data change: O(k) where k = entries depending on changed data (typically << n)
     * - On filter change: O(n) full rebuild (same as asSnapshotStateList)
     * - Memory: O(d) where d = total dependency count across all entries
     *
     * ## Use Cases
     *
     * Perfect for complex queries like:
     * - Multi-table joins (e.g., deliveries joining dropoffs → dispatches → journeys → pickups)
     * - Nested filtering with foreign key relationships
     * - Computed views that depend on multiple collections
     * - Any scenario where `derivedStateOf` would recompute too much
     *
     * ## Example: Deliveries Query
     *
     * ```kotlin
     * val deliveries = Data.dropoffFuels.asSnapshotStateListWithJoins(
     *     entryComparator = compareBy { it.value.journeyDto.startTime },
     *     filterMap = remember(timeRange, filters...) {
     *         { dropoffEntry, fetch ->
     *             val dropoff = dropoffEntry.value
     *
     *             // fetch() tracks all lookups automatically
     *             val dispatch = fetch(Data.dispatches, dropoff.id) ?: return@filterMap null
     *             val journey = fetch(Data.journeys, dispatch.journeyId) ?: return@filterMap null
     *             val pickup = fetch(Data.pickupFuels, dropoff.pickupId) ?: return@filterMap null
     *
     *             // Filters
     *             if (journey.startTime !in timeRange) return@filterMap null
     *             if (!selectedDrivers.contains(journey.driverId)) return@filterMap null
     *
     *             // Build final DTO
     *             FromToFuelDeliveryDto(journey, dispatch, pickup, dropoff)
     *         }
     *     }
     * )
     *
     * // Usage in LazyColumn
     * LazyColumn {
     *     items(deliveries, key = { it.key }) { entry ->
     *         DeliveryCard(delivery = entry.value) // entry.value is FromToFuelDeliveryDto
     *     }
     * }
     *
     * // When journey #123 changes:
     * // → Only dropoffs that fetched journey #123 re-evaluate (e.g., 2 entries)
     * // → Not all 50 deliveries
     * ```
     *
     * ## Dependency Tracking Strategy
     *
     * Dependencies are tracked for ALL entries, even those filtered out. This ensures full reactivity:
     * - If a filtered-out entry's dependencies change such that it should now appear, it will
     * - Example: Journey time changes from 14:00 → 10:00, delivery appears in 9:00-12:00 filter
     *
     * ## Important Notes
     *
     * - **Returns Map.Entry**: Like other asSnapshotStateList methods, returns `Map.Entry<KeyType, T>`
     *   where T is your mapped DTO. Access the value with `entry.value`.
     * - **Null safety**: fetch() returns null when key not found - handle with `?: return@filterMap null`
     * - **Efficiency**: Only fetch what you need - early returns save lookups
     * - **Immutability**: Values should be immutable after storage (standard Es requirement)
     * - **Comparator required**: Must provide entryComparator for sorting the result DTO list
     *
     * @param T the mapped result type (your final DTO)
     * @param entryComparator comparator for sorting the mapped results.
     *                        **Must be stable across recompositions.**
     * @param filterMap lambda that fetches related data, applies filters, and maps to result type.
     *                  Receives the primary entry and a JoinContext with fetch() function.
     *                  Returns null to exclude entry. **Must be stable across recompositions.**
     * @param lifecycleOwner lifecycle owner (defaults to LocalLifecycleOwner)
     * @param minActiveState minimum lifecycle state (defaults to STARTED)
     * @return reactive SnapshotStateList<Map.Entry<KeyType, T>> with granular dependency tracking
     */
    @Composable
    fun <T : Any> asSnapshotStateListWithJoins(
        entryComparator: Comparator<Map.Entry<KeyType, T>>,
        filterMap: (entry: Map.Entry<KeyType, ValueType>, context: JoinContext) -> T?,
        lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    ): SnapshotStateList<Map.Entry<KeyType, T>> {

        // Dependency tracker: tracks which primary keys depend on which (Es, key) pairs
        val dependencyTracker = remember(entryComparator, filterMap) {
            JoinDependencyTracker<KeyType>()
        }

        // Result list with Map.Entry<KeyType, T>
        val resultList = remember(entryComparator, filterMap) {
            mutableStateListOf<Map.Entry<KeyType, T>>()
        }

        // Helper: rebuild entire list from current state
        suspend fun rebuild() {
            resultList.clear()
            dependencyTracker.clear()

            val results = mutableListOf<Map.Entry<KeyType, T>>()

            withReadLock {
                entries.forEach { entry ->
                    val context = JoinContext()
                    val mappedValue = filterMap(entry, context)

                    // Track dependencies regardless of filter result (ensures full reactivity)
                    dependencyTracker.recordDependencies(entry.key, context.dependencies)

                    // Add to results if passed filter
                    if (mappedValue != null) {
                        results.add(MapEntry(entry.key, mappedValue))
                    }
                }
            }

            // Sort and add to list
            resultList.addAll(results.sortedWith(entryComparator))
        }

        // Helper: re-evaluate a specific primary entry
        suspend fun reEvaluateEntry(primaryKey: KeyType) {
            val primaryValue = withReadLock { get(primaryKey) } ?: return
            val entry = MapEntry(primaryKey, primaryValue)

            val context = JoinContext()
            val newMappedValue = filterMap(entry, context)

            // Update dependencies (they may have changed)
            dependencyTracker.recordDependencies(primaryKey, context.dependencies)

            // Find old position in result list
            val oldIndex = resultList.indexOfFirst { it.key == primaryKey }

            if (newMappedValue != null) {
                // Entry should be in list
                val newEntry = MapEntry(primaryKey, newMappedValue)

                if (oldIndex >= 0) {
                    // Update: remove old, insert new in sorted position
                    resultList.removeAt(oldIndex)
                }

                // Find insertion point using binary search
                val insertIndex = findMappedInsertionPoint(resultList, newEntry, entryComparator)
                resultList.add(insertIndex, newEntry)
            } else {
                // Entry should NOT be in list
                if (oldIndex >= 0) {
                    resultList.removeAt(oldIndex)
                }
            }
        }

        // Subscribe to primary collection changes
        LaunchedEffect(entryComparator, filterMap, minActiveState) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
                changes.onStart { emit(Rebuild()) }.collect { change ->
                    when (change) {
                        is Put -> {
                            // Primary entry added/updated → re-evaluate it
                            reEvaluateEntry(change.key)
                        }

                        is Removed -> {
                            // Primary entry removed → remove from results and dependencies
                            val index = resultList.indexOfFirst { it.key == change.key }
                            if (index >= 0) resultList.removeAt(index)
                            dependencyTracker.removePrimaryEntry(change.key)
                        }

                        is Cleared -> {
                            resultList.clear()
                            dependencyTracker.clear()
                        }

                        is Rebuild -> rebuild()
                    }
                }
            }
        }

        // Subscribe to changes from ALL dependency Es collections
        LaunchedEffect(entryComparator, filterMap, minActiveState, dependencyTracker) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
                dependencyTracker.subscribeToAllDependencies { dependencyEs, dependencyKey ->
                    // Find which primary entries depend on this (Es, key) pair
                    val affectedPrimaryKeys = dependencyTracker.getPrimaryKeysDependingOn(dependencyEs, dependencyKey)

                    // Re-evaluate each affected primary entry
                    affectedPrimaryKeys.forEach { primaryKey ->
                        reEvaluateEntry(primaryKey)
                    }
                }
            }
        }

        return resultList
    }

    /**
     * Creates a reactive SnapshotStateMap that groups entries with multi-source joins and automatic dependency tracking.
     *
     * This function combines the capabilities of `asSnapshotStateMapBySecondaryKey()` and `asSnapshotStateListWithJoins()`,
     * enabling efficient reactive queries that:
     * 1. Join data across multiple Es collections with automatic dependency tracking
     * 2. Group the results by a computed key extracted from the mapped DTO
     * 3. Provide granular per-group reactivity - only affected groups recompose
     *
     * ## How It Works
     *
     * 1. **filterMap execution**: For each primary entry, executes your lambda with fetch() function
     * 2. **Automatic dependency tracking**: Every fetch() call is recorded as a dependency
     * 3. **Dynamic grouping**: Groups mapped results by extracting a key from the final DTO
     * 4. **Granular reactivity**: When related data changes, only affected groups are re-evaluated
     * 5. **Incremental updates**: Only affected SnapshotStateLists trigger recomposition
     *
     * ## Performance
     *
     * - Initial creation: O(n) where n = primary collection size
     * - On data change: O(k log k) where k = entries depending on changed data (typically << n)
     * - On filter change: O(n) full rebuild
     * - Memory: O(d) where d = total dependency count + O(g) where g = number of groups
     *
     * ## Use Cases
     *
     * Perfect for scenarios like:
     * - Grouping complex multi-table queries (e.g., dispatches by journey with fuel joins)
     * - Eliminating `derivedStateOf { list.groupBy {...} }` anti-patterns
     * - Per-group reactivity with foreign key relationships
     * - Any scenario where you need both joins AND grouping with optimal reactivity
     *
     * ## Example: Dispatches by Journey
     *
     * ```kotlin
     * // Before: Using asSnapshotStateListWithJoins + derivedStateOf (non-granular reactivity)
     * val dispatchesWithFuel = Data.dispatches.asSnapshotStateListWithJoins<DispatchWithFuel>(...)
     * val dispatchesByJourney by remember {
     *     derivedStateOf {
     *         dispatchesWithFuel.groupBy { it.value.dispatch.journeyId }
     *     }
     * }
     *
     * // After: Using asSnapshotStateMapWithJoins (granular per-journey reactivity)
     * val dispatchesByJourney = Data.dispatches.asSnapshotStateMapWithJoins<Long, DispatchWithFuel>(
     *     groupByKey = { it.dispatch.journeyId },
     *     entryComparator = compareBy { it.value.dispatch.sequenceNumber },
     *     filterMap = remember(filters...) {
     *         { dispatchEntry, fetch ->
     *             val dispatch = dispatchEntry.value
     *
     *             // Fetch related data with automatic dependency tracking
     *             val fuel = when (dispatch.type) {
     *                 DispatchType.PICKUP -> fetch(Data.pickupFuels, dispatch.id) ?: return@filterMap null
     *                 DispatchType.DROPOFF -> fetch(Data.dropoffFuels, dispatch.id) ?: return@filterMap null
     *             }
     *
     *             // Apply filters
     *             if (selectedWarehouses.isNotEmpty() && !selectedWarehouses.contains(dispatch.warehouseId)) {
     *                 return@filterMap null
     *             }
     *
     *             // Build final DTO
     *             DispatchWithFuel(dispatch, fuel)
     *         }
     *     }
     * )
     *
     * // Usage in LazyColumn - only affected journey's card recomposes
     * LazyColumn {
     *     items(journeys, key = { it.key }) { journeyEntry ->
     *         val dispatches = dispatchesByJourney[journeyEntry.value.id] ?: emptyList()
     *         JourneyCard(journey = journeyEntry.value, dispatches = dispatches)
     *     }
     * }
     *
     * // When dispatch #456 changes:
     * // → Only journey #123's dispatch list updates (if dispatch #456 belongs to journey #123)
     * // → Other journeys' lists are unaffected
     * ```
     *
     * ## Dependency Tracking Strategy
     *
     * Dependencies are tracked for ALL primary entries, even those filtered out. This ensures full reactivity:
     * - If a filtered-out entry's dependencies change such that it should now appear, it will
     * - Example: Dispatch warehouse changes from A → B, appears in warehouse B filter
     *
     * ## Important Notes
     *
     * - **Returns Map<SK, List>**: Returns `SnapshotStateMap<SK, SnapshotStateList<Map.Entry<KeyType, T>>>`
     *   where SK is the grouping key type and T is your mapped DTO
     * - **Group key from mapped result**: The groupByKey lambda receives the mapped DTO (type T), not the primary entry
     * - **Null group keys**: If groupByKey returns null, the entry is excluded from all groups
     * - **Empty groups auto-cleanup**: Groups are automatically removed when their list becomes empty
     * - **Null safety**: fetch() returns null when key not found - handle with `?: return@filterMap null`
     * - **Comparator required**: Must provide entryComparator for sorting entries within each group
     *
     * @param SK the grouping key type (extracted from mapped result)
     * @param T the mapped result type (your final DTO)
     * @param groupByKey lambda that extracts the grouping key from the mapped result.
     *                   Returns null to exclude entry from all groups.
     *                   **Must be stable across recompositions.**
     * @param entryComparator comparator for sorting entries within each group.
     *                        **Must be stable across recompositions.**
     * @param filterMap lambda that fetches related data, applies filters, and maps to result type.
     *                  Receives the primary entry and a JoinContext with fetch() function.
     *                  Returns null to exclude entry. **Must be stable across recompositions.**
     * @param lifecycleOwner lifecycle owner (defaults to LocalLifecycleOwner)
     * @param minActiveState minimum lifecycle state (defaults to STARTED)
     * @return reactive SnapshotStateMap<SK, SnapshotStateList<Map.Entry<KeyType, T>>> with granular per-group dependency tracking
     */
    @Composable
    fun <SK : Any, T : Any> asSnapshotStateMapWithJoins(
        groupByKey: (T) -> SK?,
        entryComparator: Comparator<Map.Entry<KeyType, T>>,
        filterMap: (entry: Map.Entry<KeyType, ValueType>, context: JoinContext) -> T?,
        lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    ): SnapshotStateMap<SK, SnapshotStateList<Map.Entry<KeyType, T>>> {

        // Dependency tracker: tracks which primary keys depend on which (Es, key) pairs
        val dependencyTracker = remember(groupByKey, entryComparator, filterMap) {
            JoinDependencyTracker<KeyType>()
        }

        // Result map: grouping key -> SnapshotStateList of mapped entries
        val resultMap = remember(groupByKey, entryComparator, filterMap) {
            SnapshotStateMap<SK, SnapshotStateList<Map.Entry<KeyType, T>>>()
        }

        // Track which group each primary key belongs to (for efficient updates when group key changes)
        val primaryToGroup = remember(groupByKey, entryComparator, filterMap) {
            mutableMapOf<KeyType, SK>()
        }

        // Helper: get or create list for a group
        fun getOrCreateList(groupKey: SK): SnapshotStateList<Map.Entry<KeyType, T>> {
            return resultMap.getOrPut(groupKey) { mutableStateListOf() }
        }

        // Helper: remove list if empty
        fun removeIfEmpty(groupKey: SK) {
            resultMap[groupKey]?.let { if (it.isEmpty()) resultMap.remove(groupKey) }
        }

        // Helper: rebuild entire map from current state
        suspend fun rebuild() {
            resultMap.clear()
            dependencyTracker.clear()
            primaryToGroup.clear()

            val grouped = mutableMapOf<SK, MutableList<Map.Entry<KeyType, T>>>()

            withReadLock {
                entries.forEach { entry ->
                    val context = JoinContext()
                    val mappedValue = filterMap(entry, context)

                    // Track dependencies regardless of filter result (ensures full reactivity)
                    dependencyTracker.recordDependencies(entry.key, context.dependencies)

                    // Add to group if passed filter
                    if (mappedValue != null) {
                        val groupKey = groupByKey(mappedValue)
                        if (groupKey != null) {
                            grouped.getOrPut(groupKey) { mutableListOf() }.add(MapEntry(entry.key, mappedValue))
                            primaryToGroup[entry.key] = groupKey
                        }
                    }
                }
            }

            // Convert grouped lists to SnapshotStateLists and add to map
            grouped.forEach { (groupKey, entries) ->
                resultMap[groupKey] = entries.sortedWith(entryComparator).toMutableStateList()
            }
        }

        // Helper: re-evaluate a specific primary entry
        suspend fun reEvaluateEntry(primaryKey: KeyType) {
            val primaryValue = withReadLock { get(primaryKey) } ?: return
            val entry = MapEntry(primaryKey, primaryValue)

            val context = JoinContext()
            val newMappedValue = filterMap(entry, context)

            // Update dependencies (they may have changed)
            dependencyTracker.recordDependencies(primaryKey, context.dependencies)

            // Get old group key
            val oldGroupKey = primaryToGroup[primaryKey]

            if (newMappedValue != null) {
                // Entry should be in a group
                val newGroupKey = groupByKey(newMappedValue)

                if (newGroupKey != null) {
                    // Remove from old group if key changed
                    if (oldGroupKey != null && oldGroupKey != newGroupKey) {
                        resultMap[oldGroupKey]?.let { list ->
                            val oldIndex = list.indexOfFirst { it.key == primaryKey }
                            if (oldIndex >= 0) list.removeAt(oldIndex)
                            removeIfEmpty(oldGroupKey)
                        }
                    }

                    // Get the list for new group
                    val list = getOrCreateList(newGroupKey)

                    // Remove old entry if exists in same group (update case)
                    if (oldGroupKey == newGroupKey) {
                        val oldIndex = list.indexOfFirst { it.key == primaryKey }
                        if (oldIndex >= 0) list.removeAt(oldIndex)
                    }

                    // Insert new entry in sorted position
                    val newEntry = MapEntry(primaryKey, newMappedValue)
                    val insertIndex = findMappedInsertionPoint(list, newEntry, entryComparator)
                    list.add(insertIndex, newEntry)

                    // Update tracking
                    primaryToGroup[primaryKey] = newGroupKey
                } else {
                    // newGroupKey is null - remove from old group if exists
                    if (oldGroupKey != null) {
                        resultMap[oldGroupKey]?.let { list ->
                            val oldIndex = list.indexOfFirst { it.key == primaryKey }
                            if (oldIndex >= 0) list.removeAt(oldIndex)
                            removeIfEmpty(oldGroupKey)
                        }
                        primaryToGroup.remove(primaryKey)
                    }
                }
            } else {
                // Entry filtered out - remove from old group if exists
                if (oldGroupKey != null) {
                    resultMap[oldGroupKey]?.let { list ->
                        val oldIndex = list.indexOfFirst { it.key == primaryKey }
                        if (oldIndex >= 0) list.removeAt(oldIndex)
                        removeIfEmpty(oldGroupKey)
                    }
                    primaryToGroup.remove(primaryKey)
                }
            }
        }

        // Subscribe to primary collection changes
        LaunchedEffect(groupByKey, entryComparator, filterMap, minActiveState) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
                changes.onStart { emit(Rebuild()) }.collect { change ->
                    when (change) {
                        is Put -> {
                            // Primary entry added/updated → re-evaluate it
                            reEvaluateEntry(change.key)
                        }

                        is Removed -> {
                            // Primary entry removed → remove from group and dependencies
                            val groupKey = primaryToGroup[change.key]
                            if (groupKey != null) {
                                resultMap[groupKey]?.let { list ->
                                    val index = list.indexOfFirst { it.key == change.key }
                                    if (index >= 0) list.removeAt(index)
                                    removeIfEmpty(groupKey)
                                }
                                primaryToGroup.remove(change.key)
                            }
                            dependencyTracker.removePrimaryEntry(change.key)
                        }

                        is Cleared -> {
                            resultMap.clear()
                            dependencyTracker.clear()
                            primaryToGroup.clear()
                        }

                        is Rebuild -> rebuild()
                    }
                }
            }
        }

        // Subscribe to changes from ALL dependency Es collections
        LaunchedEffect(groupByKey, entryComparator, filterMap, minActiveState, dependencyTracker) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
                dependencyTracker.subscribeToAllDependencies { dependencyEs, dependencyKey ->
                    // Find which primary entries depend on this (Es, key) pair
                    val affectedPrimaryKeys = dependencyTracker.getPrimaryKeysDependingOn(dependencyEs, dependencyKey)

                    // Re-evaluate each affected primary entry
                    affectedPrimaryKeys.forEach { primaryKey ->
                        reEvaluateEntry(primaryKey)
                    }
                }
            }
        }

        return resultMap
    }

}

// ============================================================================
// Multi-source join support classes
// ============================================================================

/**
 * Context object provided to filterMap lambda, exposing fetch() for dependency tracking.
 */
class JoinContext internal constructor() {
    internal val dependencies = mutableSetOf<Pair<Es<*, *>, Any>>()

    /**
     * Fetches a value from another Es collection and records the dependency.
     *
     * @param es the Es collection to fetch from
     * @param key the key to fetch
     * @return the value if found, null otherwise
     */
    fun <K : Any, V : Any> fetch(es: Es<K, V>, key: K): V? {
        // Record dependency
        dependencies.add(es to key)

        // Fetch value
        return es[key]
    }
}

/**
 * Tracks dependencies between primary entries and their fetched data.
 *
 * Maps: primaryKey → Set<(Es, dependencyKey)>
 * Reverse index: (Es, dependencyKey) → Set<primaryKey>
 */
private class JoinDependencyTracker<PK : Any> {
    // Forward: which dependencies does each primary entry have?
    private val primaryToDependencies = mutableMapOf<PK, MutableSet<Pair<Es<*, *>, Any>>>()

    // Reverse: which primary entries depend on each (Es, key) pair?
    private val dependencyToPrimaries = mutableMapOf<Pair<Es<*, *>, Any>, MutableSet<PK>>()

    fun recordDependencies(primaryKey: PK, dependencies: Set<Pair<Es<*, *>, Any>>) {
        // Remove old dependencies for this primary key
        primaryToDependencies[primaryKey]?.forEach { oldDep ->
            dependencyToPrimaries[oldDep]?.remove(primaryKey)
            if (dependencyToPrimaries[oldDep]?.isEmpty() == true) {
                dependencyToPrimaries.remove(oldDep)
            }
        }

        // Record new dependencies
        primaryToDependencies[primaryKey] = dependencies.toMutableSet()
        dependencies.forEach { dep ->
            dependencyToPrimaries.getOrPut(dep) { mutableSetOf() }.add(primaryKey)
        }
    }

    fun getPrimaryKeysDependingOn(es: Es<*, *>, key: Any): Set<PK> {
        return dependencyToPrimaries[es to key]?.toSet() ?: emptySet()
    }

    fun removePrimaryEntry(primaryKey: PK) {
        // Remove from reverse index
        primaryToDependencies[primaryKey]?.forEach { dep ->
            dependencyToPrimaries[dep]?.remove(primaryKey)
            if (dependencyToPrimaries[dep]?.isEmpty() == true) {
                dependencyToPrimaries.remove(dep)
            }
        }

        // Remove from forward index
        primaryToDependencies.remove(primaryKey)
    }

    fun clear() {
        primaryToDependencies.clear()
        dependencyToPrimaries.clear()
    }

    /**
     * Subscribe to changes from all Es collections that have dependencies.
     * This function suspends indefinitely, keeping subscriptions alive.
     *
     * Dynamically discovers and subscribes to Es instances as dependencies are recorded,
     * fixing the issue where initial capture would happen before rebuild() populates dependencies.
     */
    suspend fun subscribeToAllDependencies(
        onChange: suspend (es: Es<*, *>, key: Any) -> Unit
    ) {
        // Track which Es instances we've already subscribed to
        val subscribedEs = mutableSetOf<Es<*, *>>()

        kotlinx.coroutines.coroutineScope {
            // Continuously check for new Es instances to subscribe to
            launch {
                while (true) {
                    // Find Es instances that have dependencies but aren't yet subscribed
                    val currentEsInstances = dependencyToPrimaries.keys.map { it.first }.toSet()
                    val newEsInstances = currentEsInstances - subscribedEs

                    // Launch subscription coroutine for each newly discovered Es
                    newEsInstances.forEach { es ->
                        subscribedEs.add(es)
                        launch {
                            es.changes.collect { change ->
                                when (change) {
                                    is TreeMap.MapChange.Put -> {
                                        onChange(es, change.key)
                                    }
                                    is TreeMap.MapChange.Removed -> {
                                        onChange(es, change.key)
                                    }
                                    is TreeMap.MapChange.Cleared -> {
                                        // All dependencies from this Es are affected
                                        // Re-evaluate all primary entries that depend on any key from this Es
                                        dependencyToPrimaries.keys
                                            .filter { it.first == es }
                                            .forEach { (_, depKey) ->
                                                onChange(es, depKey)
                                            }
                                    }
                                    is TreeMap.MapChange.Rebuild -> {
                                        // Similar to Cleared - all dependencies affected
                                        dependencyToPrimaries.keys
                                            .filter { it.first == es }
                                            .forEach { (_, depKey) ->
                                                onChange(es, depKey)
                                            }
                                    }
                                }
                            }
                        }
                    }

                    // Check for new dependencies periodically
                    // Short delay since this is only active during rebuild/updates
                    kotlinx.coroutines.delay(50)
                }
            }

            // Suspend indefinitely - keep subscriptions alive until cancelled
            kotlinx.coroutines.awaitCancellation()
        }
    }
}

/**
 * Helper to find insertion point for a mapped entry in a sorted list.
 */
private fun <K : Any, T : Any> findMappedInsertionPoint(
    list: SnapshotStateList<Map.Entry<K, T>>,
    entry: Map.Entry<K, T>,
    comparator: Comparator<Map.Entry<K, T>>
): Int {
    var low = 0
    var high = list.size

    while (low < high) {
        val mid = (low + high) / 2
        if (comparator.compare(entry, list[mid]) > 0) {
            low = mid + 1
        } else {
            high = mid
        }
    }

    return low
}

// ============================================================================
// Private helper functions for SnapshotStateList operations
// ============================================================================

/**
 * Helper to find entry index in a sorted SnapshotStateList by key.
 * Uses binary search + exact key matching.
 *
 * @return index if found, insertion point if not found and returnInsertionPoint=true, -1 otherwise
 */
private fun <K : Any, V : Any> findIndexInSortedList(
    list: SnapshotStateList<Map.Entry<K, V>>,
    key: K,
    value: V,
    comparator: Comparator<Map.Entry<K, V>>,
    returnInsertionPoint: Boolean = true
): Int {
    val targetEntry = MapEntry(key, value)
    var low = 0
    var high = list.size

    // Binary search for general position
    while (low < high) {
        val mid = (low + high) / 2
        if (comparator.compare(targetEntry, list[mid]) > 0) {
            low = mid + 1
        } else {
            high = mid
        }
    }

    // Exact key search around found position
    var left = low - 1
    var right = low

    while (left >= 0 || right < list.size) {
        if (right < list.size) {
            val entry = list[right]
            if (entry.key == key) return right
            if (comparator.compare(targetEntry, entry) != 0) right = list.size else right++
        }
        if (left >= 0) {
            val entry = list[left]
            if (entry.key == key) return left
            if (comparator.compare(targetEntry, entry) != 0) left = -1 else left--
        }
    }

    return if (returnInsertionPoint) low else -1
}

/**
 * Helper to handle Put event for a single SnapshotStateList.
 * Extracted to share logic between asSnapshotStateList and asSnapshotStateMapBySecondaryKey.
 */
private fun <K : Any, V : Any> handlePutInSortedList(
    list: SnapshotStateList<Map.Entry<K, V>>,
    change: TreeMap.MapChange.Put<K, V>,
    comparator: Comparator<Map.Entry<K, V>>,
    filter: ((Map.Entry<K, V>) -> Boolean)?
) {
    val entry = MapEntry(change.key, change.value)
    val passes = filter?.invoke(entry) != false

    // Remove old value if update
    if (change.isUpdate && change.oldValue != null) {
        val oldIndex = findIndexInSortedList(list, change.key, change.oldValue, comparator, false)
        if (oldIndex >= 0) list.removeAt(oldIndex)
    }

    // Add new value if passes filter
    if (passes) {
        val insertIndex = findIndexInSortedList(list, change.key, change.value, comparator, true)
        list.add(insertIndex, entry)
    }
}

/**
 * Helper to handle Removed event for a single SnapshotStateList.
 */
private fun <K : Any, V : Any> handleRemoveInSortedList(
    list: SnapshotStateList<Map.Entry<K, V>>,
    change: TreeMap.MapChange.Removed<K, V>,
    comparator: Comparator<Map.Entry<K, V>>
) {
    val index = findIndexInSortedList(list, change.key, change.oldValue, comparator, false)
    if (index >= 0) list.removeAt(index)
}

