@file:Suppress("UNCHECKED_CAST")

package jst.oktopoi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.jvm.JvmName
import kotlin.reflect.KProperty1

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
@OptIn(ExperimentalAtomicApi::class)
open class Es<KeyType : Any, ValueType : Any> : TreeMap<KeyType, ValueType> {

    private val log = Logger.withTag(this::class.simpleName.toString())

    // Flow-based change notifications for reactive programming
    private val _changeFlow = MutableSharedFlow<MapChange<KeyType, ValueType>>(
        replay = 0,
        extraBufferCapacity = OkTopoiConstants.DEFAULT_CHANGE_FLOW_BUFFER_SIZE,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    // Depth counter for bulk change suppression. When > 0, individual Put/Remove events are
    // suppressed and a single Rebuild is emitted when depth returns to 0.
    private val bulkChangeDepth = AtomicInt(0)

    // Number of the last change emitted (see [TreeMap.MapChange.seq]). Put/Removed/Cleared are
    // numbered under the write lock, so their numbers follow both the map's mutation order and
    // the change stream's order.
    private val changeSeq = AtomicLong(0L)

    /** The number of the last change emitted — read around a snapshot (see [MapChange.seq]). */
    internal fun lastChangeSeq(): Long = changeSeq.load()

    // Catch-up after a change-buffer overflow (see [emitChange]).
    private val catchUpPending = AtomicBoolean(false)
    private val lastDroppedSeq = AtomicLong(0L)
    private val catchUpScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    
    /**
     * Flow of changes made to this Es.
     * Useful for reactive programming and implementing persistence.
     * 
     * **Backpressure handling:** each subscriber may fall at most
     * [OkTopoiConstants.DEFAULT_CHANGE_FLOW_BUFFER_SIZE] changes behind. Beyond that, changes
     * cannot reach it; writes still succeed, and a single catch-up [MapChange.Rebuild] is
     * delivered once there is room (see [emitChange]). Consumers must handle Rebuild by
     * re-reading current state; never `conflate()` this flow.
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
     * Begins a bulk operation. While depth > 0, individual Put/Remove change events are
     * suppressed. Call [endBulkChanges] when done — it emits a single Rebuild so consumers
     * re-read the full state once rather than processing thousands of individual events.
     */
    fun beginBulkChanges() {
        bulkChangeDepth.incrementAndFetch()
    }

    /**
     * Ends a bulk operation. Emits a single Rebuild event when the outermost bulk scope closes.
     */
    fun endBulkChanges() {
        if (bulkChangeDepth.decrementAndFetch() == 0) {
            emitChange(Rebuild())
        }
    }

    /**
     * Emit a change notification to the flow.
     * Suppressed (except for Rebuild) while inside a bulk operation.
     *
     * If a subscriber is a full buffer behind, the change cannot be delivered to it. Throwing
     * would fail a write that has already been applied (in memory, on disk, in sync state) and
     * still lose the event, so instead the change is recorded as dropped and a catch-up
     * [Rebuild] is scheduled (see [deliverCatchUpRebuild]).
     */
    protected fun emitChange(change: MapChange<KeyType, ValueType>) {
        if (bulkChangeDepth.load() > 0 && change !is Rebuild) return
        change.seq = changeSeq.incrementAndFetch()
        if (_changeFlow.tryEmit(change)) return

        while (true) {
            val dropped = lastDroppedSeq.load()
            if (change.seq <= dropped || lastDroppedSeq.compareAndSet(dropped, change.seq)) break
        }
        if (catchUpPending.compareAndSet(false, true)) {
            log.w {
                "Change buffer full on ${this::class.simpleName}: a subscriber is " +
                    "${OkTopoiConstants.DEFAULT_CHANGE_FLOW_BUFFER_SIZE} changes behind; " +
                    "delivering a catch-up Rebuild once it has room"
            }
            catchUpScope.launch { deliverCatchUpRebuild() }
        }
    }

    /**
     * Emits Rebuilds, suspending until there is room, until one is numbered after every dropped
     * change. A Rebuild covers every change numbered before it: that change was applied before
     * its number was taken, and a consumer answers the Rebuild — later — by re-reading current
     * state. [catchUpPending] stays set while a Rebuild is being delivered, so one coroutine
     * serves a whole overflow episode; a drop after it resets the flag starts a new one.
     */
    private suspend fun deliverCatchUpRebuild() {
        while (true) {
            val rebuild = Rebuild<KeyType, ValueType>()
            rebuild.seq = changeSeq.incrementAndFetch()
            _changeFlow.emit(rebuild)
            catchUpPending.store(false)
            if (lastDroppedSeq.load() < rebuild.seq) return
            if (!catchUpPending.compareAndSet(false, true)) return
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
     * ## Parameter Stability
     *
     * `entryComparator`, `filter` and `initialEntriesProvider` are memoized by the OkTopoi compiler
     * plugin (`@WrapInRemember`), keyed on the values they capture, so write them inline. The list
     * rebuilds when one of those values changes — e.g. a search query the filter reads. See the
     * guide's "Parameter stability in Compose" for what counts as a capture.
     *
     * @param entryComparator custom comparator for entry ordering (null = use key ordering)
     * @param filter optional predicate to include/exclude entries from the list
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
        @WrapInRemember entryComparator: Comparator<Map.Entry<KeyType, ValueType>>? = null,
        @WrapInRemember filter: (suspend (Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
        @WrapInRemember initialEntriesProvider: (suspend () -> Collection<Map.Entry<KeyType, ValueType>>) = { entries() },
        lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    ): SnapshotStateList<Map.Entry<KeyType, ValueType>> {

        suspend fun getSortedEntries(): Collection<Map.Entry<KeyType, ValueType>> {
            // Use efficient provider if available, otherwise default to all entries
            val baseEntries = initialEntriesProvider.invoke()
            
            // Apply filter if provided
            val filteredEntries = filter?.let { f -> baseEntries.filter { f.invoke(it) } } ?: baseEntries
            
            // Sort only if custom comparator provided (TreeMap's primary key order is already correct)
            return if (entryComparator != null) {
                filteredEntries.sortedWith(entryComparator)  // Custom order needed
            } else {
                filteredEntries  // TreeMap's primary key order is already correct
            }
        }

        // Stable list identity across recompositions
        val list = remember { mutableStateListOf<Map.Entry<KeyType, ValueType>>() }

        // Create comparator with default fallback
        val entryCmp = remember(entryComparator) { entryComparator ?: Comparator { e1, e2, -> keyComparator.compare(e1.key, e2.key) } }

        LaunchedEffect(entryComparator, filter, minActiveState) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
                // Subscribe first, then snapshot: a change written while the snapshot is read is
                // queued rather than lost, and [window] tells which queued changes it contains.
                val window = SnapshotWindow()
                changes
                    .onSubscription { emit(Rebuild()) }
                    .collect { change ->
                        if (window.contains(change)) return@collect
                        val uncertain = window.isUncertain(change)
                        when (change) {
                            is Put ->
                                if (uncertain) replaceKeyInSortedList(list, change.key, change.value, entryCmp, filter)
                                else handlePutInSortedList(list, change, entryCmp, filter)
                            is Removed ->
                                if (uncertain) replaceKeyInSortedList(list, change.key, null, entryCmp, filter)
                                else handleRemoveInSortedList(list, change, entryCmp)
                            is Cleared -> list.clear()
                            is Rebuild -> {
                                val entries = window.snapshot(this@Es) { getSortedEntries() }
                                list.clear()
                                list.addAll(entries)
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
     * // Group orderItems by order ID
     * val itemsByOrder = orderItems.asSnapshotStateMapBySecondaryKey(
     *     groupByKey = ItemDto::orderId.name,
     *     entryComparator = compareBy { it.value.sequenceNumber },
     *     filter = { it.value.progress != ProgressType.COMPLETED }
     * )
     *
     * // Access in OrderCard - only this card recomposes on changes
     * val orderItems = itemsByOrder[order.id] ?: emptyList()
     * ```
     */
    @Composable
    fun <SK : Any> asSnapshotStateMapBySecondaryKey(
        groupByKey: String,
        @WrapInRemember entryComparator: Comparator<Map.Entry<KeyType, ValueType>>,
        @WrapInRemember filter: (suspend (Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
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

        // Helper: rebuild entire map from current state.
        // Reconciles in place (see [reconcileGroupedLists]) so each group's SnapshotStateList
        // instance stays stable across rebuilds — consumers may hold a child-list reference.
        suspend fun rebuild(window: SnapshotWindow) {
            val grouped = window.snapshot(this@Es) {
                val grouped = mutableMapOf<SK, MutableList<Map.Entry<KeyType, ValueType>>>()

                // Use suspend API to get entries (prevents runBlocking)
                entries().forEach { entry ->
                    if (filter?.invoke(entry) != false) {
                        (getSecondaryKey(groupByKey, entry.value) as? SK)?.let { secKeyValue ->
                            grouped.getOrPut(secKeyValue) { mutableListOf() }.add(entry)
                        }
                    }
                }
                grouped
            }

            reconcileGroupedLists(map, grouped.mapValues { (_, entries) -> entries.sortedWith(entryComparator) })
        }

        // Helper: put [key]'s row into the state [value] (null: absent) without trusting any
        // previous value — for changes the last snapshot may already contain (see [SnapshotWindow]).
        suspend fun replaceKey(key: KeyType, value: ValueType?) {
            // Filter first (it suspends), then remove and re-insert without suspending, dropping
            // the old group only after the re-insert (keeps a one-entry group's list instance).
            val entry = value?.let { MapEntry(key, it) }
            val newGroup = if (entry != null && filter?.invoke(entry) != false) {
                getSecondaryKey(groupByKey, entry.value) as? SK
            } else null

            var oldGroup: SK? = null
            for ((secKeyValue, list) in map.entries.toList()) {
                val index = list.indexOfFirst { it.key == key }
                if (index >= 0) {
                    list.removeAt(index)
                    oldGroup = secKeyValue
                    break
                }
            }
            if (entry != null && newGroup != null) {
                val list = getOrCreateList(newGroup)
                list.add(findMappedInsertionPoint(list, entry, entryComparator), entry)
            }
            oldGroup?.let { removeIfEmpty(it) }
        }

        LaunchedEffect(groupByKey, entryComparator, filter, minActiveState) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
                // Subscribe first, then snapshot — see asSnapshotStateList.
                val window = SnapshotWindow()
                changes.onSubscription { emit(Rebuild()) }.collect { change ->
                    if (window.contains(change)) return@collect
                    if (window.isUncertain(change)) {
                        when (change) {
                            is Put -> replaceKey(change.key, change.value)
                            is Removed -> replaceKey(change.key, null)
                            is Cleared -> clearGroupedLists(map)
                            is Rebuild -> rebuild(window)
                        }
                        return@collect
                    }
                    when (change) {
                        is Put -> {
                            val entry = MapEntry(change.key, change.value)
                            val passes = filter?.invoke(entry) != false
                            val secKeyValue = getSecondaryKey(groupByKey, entry.value) as? SK

                            // Handle old value removal (if secondary key changed)
                            var oldGroup: SK? = null
                            if (change.isUpdate && change.oldValue != null) {
                                val oldSecKeyValue = getSecondaryKey(groupByKey, change.oldValue) as? SK
                                oldSecKeyValue?.let { oldKey ->
                                    map[oldKey]?.let { oldList ->
                                        handleRemoveInSortedList(
                                            oldList,
                                            Removed(change.key, change.oldValue),
                                            entryComparator
                                        )
                                        oldGroup = oldKey
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

                            // Drop the old group only after the re-insert: an update within a
                            // one-entry group must keep that group's list instance (a consumer
                            // may hold it), not drop it and create a new one.
                            oldGroup?.let { removeIfEmpty(it) }
                        }

                        is Removed -> {
                            (getSecondaryKey(groupByKey, change.oldValue) as? SK)?.let { secKeyValue ->
                                map[secKeyValue]?.let { list ->
                                    handleRemoveInSortedList(list, change, entryComparator)
                                    removeIfEmpty(secKeyValue)
                                }
                            }
                        }

                        is Cleared -> clearGroupedLists(map)
                        is Rebuild -> rebuild(window)
                    }
                }
            }
        }

        return map
    }

/**
     * [asSnapshotStateMapBySecondaryKey] grouped by the index named after [groupBy]
     * (see [TreeMap.SecondaryIndexBuilder.key]). The group key type is inferred from the property.
     *
     * ```kotlin
     * val chambersByTrailer = chambers.asSnapshotStateMapBySecondaryKey(
     *     groupBy = ChamberDto::trailerId,
     *     entryComparator = compareBy { it.value.chamberNo },
     * )
     * ```
     */
    @Composable
    fun <SK : Any> asSnapshotStateMapBySecondaryKey(
        groupBy: KProperty1<ValueType, SK?>,
        @WrapInRemember entryComparator: Comparator<Map.Entry<KeyType, ValueType>>,
        @WrapInRemember filter: (suspend (Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
        lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    ): SnapshotStateMap<SK, SnapshotStateList<Map.Entry<KeyType, ValueType>>> =
        asSnapshotStateMapBySecondaryKey(
            groupByKey = groupBy.name,
            entryComparator = entryComparator,
            filter = filter,
            lifecycleOwner = lifecycleOwner,
            minActiveState = minActiveState,
        )

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
        @WrapInRemember entryComparator: Comparator<Map.Entry<KeyType, ValueType>>,
        @WrapInRemember filter: (suspend (Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
        lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    ): SnapshotStateList<Map.Entry<KeyType, ValueType>> {

        return asSnapshotStateList(
            entryComparator = entryComparator,
            filter = { entry ->
                val passesFilter = filter?.invoke(entry) != false
                val passesCriteria = criteria.all { (indexName, expectedValue) ->
                    getSecondaryKey(indexName, entry.value) == expectedValue
                }
                passesFilter && passesCriteria
            },
            initialEntriesProvider = {
                val entries = mutableListOf<MapEntry<KeyType, ValueType>>()
                forEachBy(*criteria) { key, value -> entries.add(MapEntry(key, value)) }
                entries
            },
            lifecycleOwner = lifecycleOwner,
            minActiveState = minActiveState,
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
        @WrapInRemember filter: (suspend (Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
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
     * [asSnapshotStateListBySecondaryKey] with criteria naming each index by its property
     * (see [TreeMap.SecondaryIndexBuilder.key]): `Item::orderId to order.id`.
     */
    @Composable
    @JvmName("asSnapshotStateListBySecondaryKeyProperty")
    fun asSnapshotStateListBySecondaryKey(
        vararg criteria: Pair<KProperty1<ValueType, *>, Any?>,
        @WrapInRemember entryComparator: Comparator<Map.Entry<KeyType, ValueType>>,
        @WrapInRemember filter: (suspend (Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
        lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    ): SnapshotStateList<Map.Entry<KeyType, ValueType>> =
        asSnapshotStateListBySecondaryKey(
            criteria = *criteria.byIndexName(),
            entryComparator = entryComparator,
            filter = filter,
            lifecycleOwner = lifecycleOwner,
            minActiveState = minActiveState,
        )

    /**
     * [asSnapshotStateListBySecondaryKey] (sorted by the criteria) with criteria naming each
     * index by its property (see [TreeMap.SecondaryIndexBuilder.key]).
     */
    @Composable
    @JvmName("asSnapshotStateListBySecondaryKeySortedProperty")
    fun asSnapshotStateListBySecondaryKey(
        vararg criteria: Pair<KProperty1<ValueType, *>, Comparable<*>?>,
        @WrapInRemember filter: (suspend (Map.Entry<KeyType, ValueType>) -> Boolean)? = null,
        lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    ): SnapshotStateList<Map.Entry<KeyType, ValueType>> =
        asSnapshotStateListBySecondaryKey(
            criteria = *criteria.byIndexName(),
            filter = filter,
            lifecycleOwner = lifecycleOwner,
            minActiveState = minActiveState,
        )

    /**
     * Creates a reactive State for a single key that automatically updates when the value changes.
     *
     * This function provides reactive access to individual entries, ideal for Composables that need
     * to display or react to a single entity. The State will update whenever the value associated
     * with the key is added, updated, or removed.
     *
     * ## Performance
     *
     * - Initial fetch: O(log n)
     * - Updates: O(1) filtering on change events
     * - Memory: Single State<ValueType?> object per key
     *
     * ## Reactivity
     *
     * The State updates on:
     * - Put: When the key is added or updated
     * - Removed: When the key is removed (value becomes null)
     * - Cleared: All keys removed (value becomes null)
     * - Rebuild: Full resync (refetches current value)
     *
     * ## Usage
     *
     * ```kotlin
     * @Composable
     * fun AgentCard(agentId: Long) {
     *     val agent by Data.users.asState(agentId)
     *
     *     Text(agent?.let { "${it.firstName} ${it.lastName}" } ?: "Unknown")
     * }
     * ```
     *
     * @param key the key to observe
     * @return reactive State that updates when the value changes
     */
    @Composable
    fun asState(key: KeyType): State<ValueType?> {
        val state = remember(key) { mutableStateOf<ValueType?>(null) }

        LaunchedEffect(key) {
            // Subscribe first, then fetch (the injected Rebuild), so a change written in between
            // is queued rather than lost.
            changes.onSubscription { emit(Rebuild()) }.collect { change ->
                when (change) {
                    is Put -> if (change.key == key) state.value = change.value
                    is Removed -> if (change.key == key) state.value = null
                    is Cleared -> state.value = null
                    is Rebuild -> state.value = get(key)
                }
            }
        }

        return state
    }

    /**
     * Creates a non-reactive State snapshot of a value at a specific key.
     *
     * This function fetches the current value for a key and returns it as a State, but does NOT
     * subscribe to changes. The State will not update if the value changes later. Use this when
     * you need the value in a Composable context but don't need reactivity.
     *
     * ## Performance
     *
     * - Initial fetch: O(log n)
     * - Updates: None (non-reactive)
     * - Memory: Single State<ValueType?> object per key
     *
     * ## Use Cases
     *
     * - Displaying static/historical data that won't change
     * - Fetching related entities in a LaunchedEffect or derived computation
     * - When reactivity would cause unwanted recompositions
     *
     * ## Usage
     *
     * ```kotlin
     * @Composable
     * fun OrderHeader(orderId: Long) {
     *     val order by Data.orders.getState(orderId)
     *
     *     // Displays order info at time of composition, won't update if order changes
     *     Text("Order from ${order?.startTime}")
     * }
     * ```
     *
     * @param key the key to fetch
     * @return non-reactive State with the current value (or null if not found)
     */
    @Composable
    fun getState(key: KeyType): State<ValueType?> {
        val state = remember(key) { mutableStateOf<ValueType?>(null) }

        LaunchedEffect(key) {
            state.value = get(key)
        }

        return state
    }

    /**
     * Creates a reactive State that transforms a value at a specific key using a suspend function.
     *
     * This function fetches the value for a key and applies a transformation, then returns the result
     * as a State. The State automatically updates when either the source value changes OR when any
     * of the specified dependencies change. This is ideal for computing derived values that depend
     * on both the entity data and external context (like formatting, locale, timezone, etc.).
     *
     * ## Key Features
     *
     * - **Reactive to source changes**: Updates automatically when the value at the key changes
     * - **Reactive to dependencies**: Updates when any dependency changes (e.g., locale, timezone)
     * - **Suspend transform**: Can call other suspend functions (e.g., fetching related data)
     * - **Null-safe**: Handles null values gracefully, passing them to the transform
     * - **Lifecycle-aware**: Properly cancels when the composition leaves
     *
     * ## Performance
     *
     * - Initial fetch + transform: O(log n) + transform cost
     * - On source change: O(log n) + transform cost
     * - On dependency change: transform cost only (no fetch needed)
     * - Memory: Single State<T?> object per usage
     *
     * ## Use Cases
     *
     * - Computing display names that require async lookups
     * - Formatting values based on locale/timezone/user preferences
     * - Deriving values that depend on related entities
     * - Any transformation requiring suspend functions
     *
     * ## Usage
     *
     * ```kotlin
     * // Basic usage - async transformation
     * @Composable
     * fun OrderHeader(orderId: Long) {
     *     val displayName by Data.orders.mapState(orderId) { order ->
     *         order?.getDisplayName()  // suspend function
     *     }
     *     Text(displayName ?: "Unknown")
     * }
     *
     * // With dependencies - recompute when context changes
     * @Composable
     * fun FormattedDate(orderId: Long) {
     *     val timezone = TimeZone.currentSystemDefault()
     *     val locale = LocaleList.current[0]
     *
     *     val formattedDate by Data.orders.mapState(orderId, timezone, locale) { order ->
     *         order?.startTime?.format(timezone, locale)
     *     }
     *     Text(formattedDate ?: "")
     * }
     *
     * // Chaining async operations
     * @Composable
     * fun AgentName(orderId: Long) {
     *     val agentName by Data.orders.mapState(orderId) { order ->
     *         order?.let {
     *             val agent = Data.users.get(it.agentId)
     *             "${agent?.firstName} ${agent?.lastName}"
     *         }
     *     }
     *     Text(agentName ?: "No agent")
     * }
     * ```
     *
     * ## Comparison with `asState`
     *
     * - `asState(key)`: Returns raw value, no transformation
     * - `mapState(key) { transform }`: Returns transformed value with suspend support
     *
     * @param key the key to observe
     * @param dependencies optional dependencies that trigger re-transformation when changed
     *        Values [transform] captures need not be listed: the plugin gives it a new identity
     *        when one changes, which recomputes too. List only triggers it does not capture.
     * @param transform suspend function to transform the value (receives null if key not found)
     * @return reactive State that updates when source or dependencies change
     */
    @Composable
    fun <T> mapState(
        key: KeyType,
        vararg dependencies: Any?,
        @WrapInRemember transform: suspend (ValueType?) -> T?
    ): State<T?> {
        val sourceState = asState(key)
        val transformedState = remember { mutableStateOf<T?>(null) }

        // Keyed on transform too: the plugin gives it a new identity when a value it captures
        // changes, so a change a caller didn't list in [dependencies] still recomputes.
        LaunchedEffect(sourceState.value, transform, *dependencies) {
            transformedState.value = transform(sourceState.value)
        }

        return transformedState
    }

    /**
     * Creates a reactive State by transforming the entire Es collection using a suspend function.
     *
     * Unlike other `mapState` overloads that operate on a single key, this variant passes the
     * entire Es collection to your transform function. This is ideal for aggregations, filtering,
     * or any operation that needs to query multiple entries at once.
     *
     * **Important**: This does NOT automatically track changes to the collection. It only
     * re-executes when the specified `dependencies` change. For reactive collection queries,
     * prefer `asSnapshotStateList()` or `asSnapshotStateListWithJoins()`.
     *
     * ## Key Features
     *
     * - **Collection-wide access**: Transform operates on entire Es, can query any entries
     * - **Manual reactivity**: Only re-executes when dependencies change (not on data changes)
     * - **Suspend support**: Can perform async operations during transformation
     * - **Aggregation-friendly**: Perfect for counts, sums, or multi-entry computations
     *
     * ## Use Cases
     *
     * - Computing aggregates (count, sum, average) based on external filters
     * - Finding specific entries based on complex criteria
     * - Operations that depend on external state but don't need automatic reactivity
     * - One-time or manually-triggered queries
     *
     * ## Example
     *
     * ```kotlin
     * @Composable
     * fun StatisticsPanel(selectedAgentId: Long?, selectedMonth: Int) {
     *     // Recompute when filter changes, not when orders change
     *     val completedCount by Data.orders.mapState(selectedAgentId, selectedMonth) { orders ->
     *         orders.values()
     *             .filter { it.agentId == selectedAgentId }
     *             .filter { it.startTime.month == selectedMonth }
     *             .count { it.status == OrderStatus.COMPLETED }
     *     }
     *     Text("Completed: $completedCount")
     * }
     *
     * // Alternative with suspend operations
     * @Composable
     * fun TotalDistance(agentId: Long) {
     *     val distance by Data.orders.mapState(agentId) { orders ->
     *         orders.values()
     *             .filter { it.agentId == agentId }
     *             .sumOf { order ->
     *                 // Can call suspend functions
     *                 order.computeDistance()
     *             }
     *     }
     *     Text("Total: ${distance}km")
     * }
     * ```
     *
     * ## When NOT to Use
     *
     * - **Reactive lists**: Use `asSnapshotStateList()` instead for automatic updates
     * - **Single entity**: Use `mapState(key)` if you only need one entry
     * - **Joined queries**: Use `asSnapshotStateListWithJoins()` for multi-collection queries
     *
     * @param dependencies optional dependencies that trigger re-transformation when changed
     *        Values [transform] captures need not be listed: the plugin gives it a new identity
     *        when one changes, which recomputes too. List only triggers it does not capture.
     * @param transform suspend function that receives the Es collection and returns computed value
     * @return reactive State that updates only when dependencies change
     */
    @Composable
    fun <T> mapState(
        vararg dependencies: Any?,
        @WrapInRemember transform: suspend (Es<KeyType, ValueType>) -> T?
    ): State<T?> {
        val transformedState = remember { mutableStateOf<T?>(null) }

        // Keyed on transform too — see mapState(key, …).
        LaunchedEffect(transform, *dependencies) {
            transformedState.value = transform(this@Es)
        }

        return transformedState
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
     * - Multi-table joins (e.g., shipments joining shipments → orderItems → orders → customers)
     * - Nested filtering with foreign key relationships
     * - Computed views that depend on multiple collections
     * - Any scenario where `derivedStateOf` would recompute too much
     *
     * ## Example: Shipments Query
     *
     * ```kotlin
     * val shipments = Data.shipmentProducts.asSnapshotStateListWithJoins(
     *     entryComparator = compareBy { it.value.orderDto.startTime },
     *     filterMap = remember(timeRange, filters...) {
     *         { shipmentEntry, fetch ->
     *             val shipment = shipmentEntry.value
     *
     *             // fetch() tracks all lookups automatically
     *             val item = fetch(Data.orderItems, shipment.id) ?: return@filterMap null
     *             val order = fetch(Data.orders, item.orderId) ?: return@filterMap null
     *             val customer = fetch(Data.customerProducts, shipment.customerId) ?: return@filterMap null
     *
     *             // Filters
     *             if (order.startTime !in timeRange) return@filterMap null
     *             if (!selectedAgents.contains(order.agentId)) return@filterMap null
     *
     *             // Build final DTO
     *             FromToProductShipmentDto(order, item, customer, shipment)
     *         }
     *     }
     * )
     *
     * // Usage in LazyColumn
     * LazyColumn {
     *     items(shipments, key = { it.key }) { entry ->
     *         ShipmentCard(shipment = entry.value) // entry.value is FromToProductShipmentDto
     *     }
     * }
     *
     * // When order #123 changes:
     * // → Only shipments that fetched order #123 re-evaluate (e.g., 2 entries)
     * // → Not all 50 shipments
     * ```
     *
     * ## Dependency Tracking Strategy
     *
     * Dependencies are tracked for ALL entries, even those filtered out. This ensures full reactivity:
     * - If a filtered-out entry's dependencies change such that it should now appear, it will
     * - Example: Order time changes from 14:00 → 10:00, shipment appears in 9:00-12:00 filter
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
        @WrapInRemember entryComparator: Comparator<Map.Entry<KeyType, T>>,
        @WrapInRemember filterMap: suspend (entry: Map.Entry<KeyType, ValueType>, context: JoinContext) -> T?,
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

        // Serializes resultList mutations across the two LaunchedEffects below
        // (primary-collection changes and dependency changes). Both call suspend
        // helpers whose filterMap suspends at every fetch(); on the UI itemer
        // those suspension points let a reEvaluateEntry() interleave with a
        // rebuild() — which clears the list up front and re-adds at the end —
        // producing a transient duplicate key that crashes LazyColumn/LazyRow.
        val mutationMutex = remember(entryComparator, filterMap) { Mutex() }

        // Helper: rebuild entire list from current state
        suspend fun rebuild() = mutationMutex.withLock {
            dependencyTracker.clear()

            val results = mutableListOf<Map.Entry<KeyType, T>>()

            // Use suspend API to get entries (prevents runBlocking)
            entries().forEach { entry ->
                val context = JoinContext(dependencyTracker)
                val mappedValue = filterMap(entry, context)

                // Track dependencies regardless of filter result (ensures full reactivity)
                dependencyTracker.recordDependencies(entry.key, context.dependencies)

                // Add to results if passed filter
                if (mappedValue != null) {
                    results.add(MapEntry(entry.key, mappedValue))
                }
            }

            // Swap in one step, after every suspending filterMap: clearing up front would let a
            // frame render the list empty or half-built while the rebuild runs.
            val sorted = results.sortedWith(entryComparator)
            resultList.clear()
            resultList.addAll(sorted)
        }

        // Helper: re-evaluate a specific primary entry
        suspend fun reEvaluateEntry(primaryKey: KeyType) = mutationMutex.withLock {
            val primaryValue = get(primaryKey) ?: return@withLock
            val entry = MapEntry(primaryKey, primaryValue)

            val context = JoinContext(dependencyTracker)
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
                changes.onSubscription { emit(Rebuild()) }.collect { change ->
                    when (change) {
                        is Put -> {
                            // Primary entry added/updated → re-evaluate it
                            reEvaluateEntry(change.key)
                        }

                        is Removed -> mutationMutex.withLock {
                            // Primary entry removed → remove from results and dependencies
                            val index = resultList.indexOfFirst { it.key == change.key }
                            if (index >= 0) resultList.removeAt(index)
                            dependencyTracker.removePrimaryEntry(change.key)
                        }

                        is Cleared -> mutationMutex.withLock {
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
                dependencyTracker.subscribeToAllDependencies { dependencyEs, change ->
                    // Resolve under the mutation lock, so an evaluation still running (its
                    // dependencies are recorded when it finishes) completes first and is found.
                    val affected = mutationMutex.withLock {
                        dependencyTracker.primaryKeysAffectedBy(dependencyEs, change)
                    }
                    affected.forEach { primaryKey -> reEvaluateEntry(primaryKey) }
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
     * - Grouping complex multi-table queries (e.g., orderItems by order with product joins)
     * - Eliminating `derivedStateOf { list.groupBy {...} }` anti-patterns
     * - Per-group reactivity with foreign key relationships
     * - Any scenario where you need both joins AND grouping with optimal reactivity
     *
     * ## Example: Itemes by Order
     *
     * ```kotlin
     * // Before: Using asSnapshotStateListWithJoins + derivedStateOf (non-granular reactivity)
     * val itemesWithProduct = Data.orderItems.asSnapshotStateListWithJoins<ItemWithProduct>(...)
     * val itemsByOrder by remember {
     *     derivedStateOf {
     *         itemesWithProduct.groupBy { it.value.item.orderId }
     *     }
     * }
     *
     * // After: Using asSnapshotStateMapWithJoins (granular per-order reactivity)
     * val itemsByOrder = Data.orderItems.asSnapshotStateMapWithJoins<Long, ItemWithProduct>(
     *     groupByKey = { it.item.orderId },
     *     entryComparator = compareBy { it.value.item.sequenceNumber },
     *     filterMap = remember(filters...) {
     *         { itemEntry, fetch ->
     *             val item = itemEntry.value
     *
     *             // Fetch related data with automatic dependency tracking
     *             val product = when (item.type) {
     *                 ItemType.PHYSICAL -> fetch(Data.products, item.productId) ?: return@filterMap null
     *                 ItemType.DIGITAL -> fetch(Data.licenses, item.productId) ?: return@filterMap null
     *             }
     *
     *             // Apply filters
     *             if (selectedSuppliers.isNotEmpty() && !selectedSuppliers.contains(item.supplierId)) {
     *                 return@filterMap null
     *             }
     *
     *             // Build final DTO
     *             ItemWithProduct(item, product)
     *         }
     *     }
     * )
     *
     * // Usage in LazyColumn - only affected order's card recomposes
     * LazyColumn {
     *     items(orders, key = { it.key }) { orderEntry ->
     *         val orderItems = itemsByOrder[orderEntry.value.id] ?: emptyList()
     *         OrderCard(order = orderEntry.value, orderItems = orderItems)
     *     }
     * }
     *
     * // When item #456 changes:
     * // → Only order #123's item list updates (if item #456 belongs to order #123)
     * // → Other orders' lists are unaffected
     * ```
     *
     * ## Dependency Tracking Strategy
     *
     * Dependencies are tracked for ALL primary entries, even those filtered out. This ensures full reactivity:
     * - If a filtered-out entry's dependencies change such that it should now appear, it will
     * - Example: Item supplier changes from A → B, appears in supplier B filter
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
        @WrapInRemember groupByKey: (T) -> SK?,
        @WrapInRemember entryComparator: Comparator<Map.Entry<KeyType, T>>,
        @WrapInRemember filterMap: suspend (entry: Map.Entry<KeyType, ValueType>, context: JoinContext) -> T?,
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

        // Serializes resultMap/primaryToGroup mutations across the two LaunchedEffects
        // below. As in asSnapshotStateListWithJoins, rebuild() clears everything up
        // front and refills at the end while filterMap suspends at every fetch(); an
        // interleaved reEvaluateEntry() would otherwise re-add an entry that rebuild
        // then adds again, producing a transient duplicate key that crashes LazyColumn/Row.
        val mutationMutex = remember(groupByKey, entryComparator, filterMap) { Mutex() }

        // Helper: get or create list for a group
        fun getOrCreateList(groupKey: SK): SnapshotStateList<Map.Entry<KeyType, T>> {
            return resultMap.getOrPut(groupKey) { mutableStateListOf() }
        }

        // Helper: remove list if empty
        fun removeIfEmpty(groupKey: SK) {
            resultMap[groupKey]?.let { if (it.isEmpty()) resultMap.remove(groupKey) }
        }

        // Helper: rebuild entire map from current state.
        // Reconciles in place (see [reconcileGroupedLists]) so each group's SnapshotStateList
        // instance stays stable across rebuilds — consumers may hold a child-list reference.
        suspend fun rebuild() = mutationMutex.withLock {
            dependencyTracker.clear()
            primaryToGroup.clear()

            val grouped = mutableMapOf<SK, MutableList<Map.Entry<KeyType, T>>>()

            // Use suspend API to get entries (prevents runBlocking)
            entries().forEach { entry ->
                val context = JoinContext(dependencyTracker)
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

            reconcileGroupedLists(resultMap, grouped.mapValues { (_, entries) -> entries.sortedWith(entryComparator) })
        }

        // Helper: re-evaluate a specific primary entry
        suspend fun reEvaluateEntry(primaryKey: KeyType) = mutationMutex.withLock {
            val primaryValue = get(primaryKey) ?: return@withLock
            val entry = MapEntry(primaryKey, primaryValue)

            val context = JoinContext(dependencyTracker)
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
                changes.onSubscription { emit(Rebuild()) }.collect { change ->
                    when (change) {
                        is Put -> {
                            // Primary entry added/updated → re-evaluate it
                            reEvaluateEntry(change.key)
                        }

                        is Removed -> mutationMutex.withLock {
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

                        is Cleared -> mutationMutex.withLock {
                            clearGroupedLists(resultMap)
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
                dependencyTracker.subscribeToAllDependencies { dependencyEs, change ->
                    // Resolve under the mutation lock, so an evaluation still running (its
                    // dependencies are recorded when it finishes) completes first and is found.
                    val affected = mutationMutex.withLock {
                        dependencyTracker.primaryKeysAffectedBy(dependencyEs, change)
                    }
                    affected.forEach { primaryKey -> reEvaluateEntry(primaryKey) }
                }
            }
        }

        return resultMap
    }

    /**
     * Creates a reactive SnapshotStateMap where each value is transformed using a suspend function.
     *
     * This method transforms each entry in the Es collection independently, providing efficient
     * granular reactivity. When an entry changes, only that entry's transformation re-executes.
     * When dependencies change, all entries are re-transformed.
     *
     * ## Key Features
     *
     * - **Granular reactivity**: Only affected entries re-transform when data changes
     * - **Suspend transformation**: Can call suspend functions during transformation
     * - **Null filtering**: Null results are automatically excluded from the result map
     * - **Dependency tracking**: Re-transform all entries when dependencies change
     * - **Efficient updates**: O(1) updates when single entries change
     *
     * ## Use Cases
     *
     * - Pre-computing display names for all entities in a collection
     * - Transforming entities with suspend operations (fetch related data, format, etc.)
     * - Creating lookup maps with transformed values
     * - Any scenario where you need a reactive map of transformed values
     *
     * ## Example - Display Names
     *
     * ```kotlin
     * @Composable
     * fun SupplierSelector() {
     *     // Pre-compute display names for all suppliers
     *     val supplierDisplayNames = Data.suppliers.asSnapshotStateMapTransformed { entry ->
     *         entry.value.getDisplayName()  // suspend function
     *     }
     *
     *     val suppliers = Data.suppliers.asSnapshotStateList()
     *
     *     SearchableExposedDropdownMenu(
     *         items = suppliers.map { it.value },
     *         displayText = { supplier ->
     *             // Fast O(1) lookup of pre-computed display name
     *             supplierDisplayNames[supplier.id] ?: supplier.name
     *         }
     *     )
     * }
     * ```
     *
     * ## Example - With Dependencies
     *
     * ```kotlin
     * @Composable
     * fun LocalizedEntityNames(locale: Locale) {
     *     // Re-transform all when locale changes
     *     val entityNames = Data.entities.asSnapshotStateMapTransformed(locale) { entry ->
     *         entry.value.getLocalizedName(locale)  // suspend, depends on locale
     *     }
     *
     *     // entityNames automatically updates when:
     *     // - Individual entity changes (only that entry re-transforms)
     *     // - Locale changes (all entries re-transform)
     * }
     * ```
     *
     * ## Null Handling
     *
     * If the transform returns null for an entry, that entry is excluded from the result map.
     * When an entry is updated and transform returns null, it's removed from the map.
     *
     * ## Performance
     *
     * - Initial population: O(n) where n = number of entries
     * - Single entry update: O(1) + transform cost
     * - Dependency change: O(n) (re-transforms all entries)
     * - Memory: O(n) for the result map
     *
     * ## Comparison with Similar Methods
     *
     * - `mapState(key)`: Single entry transformation
     * - `asSnapshotStateMapTransformed()`: Entire collection transformation (this method)
     * - `asSnapshotStateListWithJoins()`: List with multi-source joins
     * - `rememberSuspendTransformed()`: Transform a single object you already have
     *
     * @param R the type of the transformed result values
     * @param dependencies optional dependencies that trigger re-transformation of all entries when changed
     *        Values [transform] captures need not be listed: the plugin gives it a new identity
     *        when one changes, which recomputes too. List only triggers it does not capture.
     * @param transform suspend function that transforms each entry (receives full Map.Entry with key and value)
     * @return reactive SnapshotStateMap<KeyType, R> that updates when entries or dependencies change
     */
    @Composable
    fun <R : Any> asSnapshotStateMapTransformed(
        vararg dependencies: Any?,
        @WrapInRemember transform: suspend (Map.Entry<KeyType, ValueType>) -> R?
    ): SnapshotStateMap<KeyType, R> {
        val resultMap = remember { mutableStateMapOf<KeyType, R>() }

        // Keyed on transform too — see mapState(key, …).
        LaunchedEffect(transform, *dependencies) {
            // Subscribe first, then build (the injected Rebuild clears and refills on every
            // (re)start), so a change written while the map is built is queued rather than lost.
            changes.onSubscription { emit(TreeMap.MapChange.Rebuild()) }.collect { change ->
                when (change) {
                    is TreeMap.MapChange.Put -> {
                        // Create Map.Entry for the changed value
                        val entry = object : Map.Entry<KeyType, ValueType> {
                            override val key = change.key
                            override val value = change.value
                        }
                        val transformed = transform(entry)
                        if (transformed != null) {
                            resultMap[change.key] = transformed
                        } else {
                            resultMap.remove(change.key)
                        }
                    }
                    is TreeMap.MapChange.Removed -> {
                        resultMap.remove(change.key)
                    }
                    is TreeMap.MapChange.Cleared -> {
                        resultMap.clear()
                    }
                    is TreeMap.MapChange.Rebuild -> {
                        // Rebuild entire map off to the side, then swap in one step: clearing
                        // first would let a frame render it empty while transform() suspends.
                        val rebuilt = mutableMapOf<KeyType, R>()
                        entries().forEach { entry ->
                            val transformed = transform(entry)
                            if (transformed != null) {
                                rebuilt[entry.key] = transformed
                            }
                        }
                        resultMap.clear()
                        resultMap.putAll(rebuilt)
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
 * Represents a dependency tracked during join operations.
 *
 * Dependencies can be either:
 * - Key-based: Track changes to a specific entry (es, key)
 * - Query-based: Track changes to entries matching secondary key criteria
 */
sealed interface JoinDependency {
    val es: Es<*, *>

    /**
     * Dependency on a specific entry by primary key.
     * Triggered when the entry with this key is added, updated, or removed.
     */
    data class KeyDependency(
        override val es: Es<*, *>,
        val key: Any
    ) : JoinDependency

    /**
     * Dependency on a query by secondary key criteria.
     * Triggered when ANY entry is added/updated/removed that either:
     * - Previously matched the criteria (old value)
     * - Now matches the criteria (new value)
     *
     * This catches:
     * - Modifications to matching entries
     * - New entries that match the criteria
     * - Entries removed that matched the criteria
     * - Entries changing from matching to non-matching (or vice versa)
     */
    data class QueryDependency(
        override val es: Es<*, *>,
        val criteria: List<Pair<String, Any?>>
    ) : JoinDependency {

        /**
         * Check if a value matches this query's criteria.
         * Uses the Es's secondary key extraction to evaluate each criterion.
         */
        fun <V : Any> matches(value: V): Boolean {
            @Suppress("UNCHECKED_CAST")
            val typedEs = es as Es<*, V>

            return criteria.all { (indexName, expectedValue) ->
                typedEs.getSecondaryKey(indexName, value) == expectedValue
            }
        }
    }
}

/**
 * Context object provided to filterMap lambda, exposing fetch() and fetchBy() for dependency tracking.
 */
class JoinContext internal constructor(private val tracker: JoinDependencyTracker<*>) {
    internal val dependencies = mutableSetOf<JoinDependency>()

    // Collections already reported to [tracker] in this evaluation (see [noteRead]).
    private var noted: MutableSet<Es<*, *>>? = null

    /**
     * Before the first read from [es] in this evaluation, reports [es]'s change counter to the
     * tracker, read before the value is — see [JoinDependencyTracker.subscribeToAllDependencies].
     */
    private suspend fun noteRead(es: Es<*, *>) {
        val set = noted ?: HashSet<Es<*, *>>(4).also { noted = it }
        if (set.add(es)) tracker.noteRead(es, es.lastChangeSeq())
    }

    /**
     * Fetches a value from another Es collection and records the dependency.
     *
     * @param es the Es collection to fetch from
     * @param key the key to fetch
     * @return the value if found, null otherwise
     */
    suspend fun <K : Any, V : Any> fetch(es: Es<K, V>, key: K): V? {
        // Record key-based dependency
        dependencies.add(JoinDependency.KeyDependency(es, key))
        noteRead(es)

        // Fetch value using suspend API (non-blocking)
        return es.get(key)
    }

    /**
     * Fetches entries from another Es collection by secondary key criteria and records the query dependency.
     *
     * This enables building nested structures (e.g., Order → Stops → Itemes) with automatic reactivity.
     * When ANY entry matching the criteria changes (added, updated, removed), the dependent entry re-evaluates.
     *
     * ## Reactivity
     *
     * The query dependency tracks:
     * - **Existing entries modified**: If a matching entry's value changes
     * - **New entries added**: If a new entry matches the criteria
     * - **Entries removed**: If a matching entry is deleted
     * - **Criteria match changes**: If an entry changes from matching to non-matching (or vice versa)
     *
     * ## Example
     *
     * ```kotlin
     * val orders = Data.orders.asSnapshotStateListWithJoins<OrderWithStops>(
     *     entryComparator = compareBy { it.value.order.startTime },
     *     filterMap = remember(filters...) {
     *         { orderEntry, context ->
     *             val order = orderEntry.value
     *
     *             // Fetch all orderItems for this order with automatic dependency tracking
     *             val orderItems = context.fetchBy(
     *                 Data.orderItems,
     *                 ItemDto::orderId.name to order.id
     *             )
     *
     *             // Group by stop (supplier)
     *             val orders = orderItems.groupBy { it.value.supplierId }
     *
     *             OrderWithStops(order, orders)
     *         }
     *     }
     * )
     *
     * // When item #456 is added/updated/removed:
     * // → Only orders that have a query dependency on "orderId == X" re-evaluate
     * // → If item #456 belongs to order #123, only order #123 re-evaluates
     * ```
     *
     * ## Performance
     *
     * - Initial fetch: O(log n) per criterion (uses secondary index)
     * - On change: O(q) where q = number of query dependencies that might match (typically small)
     * - Matching check: O(c) where c = number of criteria (typically 1-3)
     *
     * @param es the Es collection to fetch from
     * @param criteria secondary key criteria as "indexName" to value pairs
     * @return list of matching entries (may be empty)
     */
    suspend fun <K : Any, V : Any> fetchBy(
        es: Es<K, V>,
        vararg criteria: Pair<String, Any?>
    ): List<Map.Entry<K, V>> {
        // Record query-based dependency
        dependencies.add(JoinDependency.QueryDependency(es, criteria.toList()))
        noteRead(es)

        // Fetch matching entries using secondary index (efficient O(log n) per criterion)
        // getBy returns Collection<V>, we need to convert to List<Map.Entry<K, V>>
        val entries = mutableListOf<Map.Entry<K, V>>()
        es.forEachBy(*criteria) { key, value ->
            entries.add(MapEntry(key, value))
        }
        return entries
    }

    /**
     * [fetchBy] with criteria naming each index by its property
     * (see [TreeMap.SecondaryIndexBuilder.key]): `fetchBy(items, Item::orderId to order.id)`.
     */
    @JvmName("fetchByProperty")
    suspend fun <K : Any, V : Any> fetchBy(
        es: Es<K, V>,
        vararg criteria: Pair<KProperty1<V, *>, Any?>
    ): List<Map.Entry<K, V>> = fetchBy(es, *criteria.byIndexName())
}

/**
 * Tracks dependencies between primary entries and their fetched data.
 *
 * Supports both key-based and query-based dependencies:
 * - Key dependencies: Track specific (Es, key) pairs
 * - Query dependencies: Track entries matching secondary key criteria
 *
 * Maps: primaryKey → Set<JoinDependency>
 * Reverse indices:
 * - keyToPrimaries: (Es, key) → Set<primaryKey>  (for key dependencies)
 * - queryDeps: List<(primaryKey, QueryDependency)>  (for query dependencies)
 *
 * **Thread Safety**: This class uses Mutex for proper synchronization, making it safe
 * to use from any itemer (Itemers.Main, Default, IO, etc.). All public methods
 * are suspend functions to enable non-blocking synchronization.
 */
internal class JoinDependencyTracker<PK : Any> {
    private val mutex = Mutex()

    // Forward: which dependencies does each primary entry have?
    private val primaryToDependencies = mutableMapOf<PK, MutableSet<JoinDependency>>()

    // Reverse index for key dependencies: (Es, key) → Set<primaryKey>
    private val keyToPrimaries = mutableMapOf<Pair<Es<*, *>, Any>, MutableSet<PK>>()

    // List of all query dependencies: (primaryKey, QueryDependency)
    // Stored as list for efficient iteration during change matching
    private val queryDeps = mutableListOf<Pair<PK, JoinDependency.QueryDependency>>()

    suspend fun recordDependencies(primaryKey: PK, dependencies: Set<JoinDependency>) {
        mutex.withLock {
            // Remove old dependencies for this primary key
            primaryToDependencies[primaryKey]?.forEach { oldDep ->
                when (oldDep) {
                    is JoinDependency.KeyDependency -> {
                        // Remove from key-based reverse index
                        keyToPrimaries[oldDep.es to oldDep.key]?.remove(primaryKey)
                        if (keyToPrimaries[oldDep.es to oldDep.key]?.isEmpty() == true) {
                            keyToPrimaries.remove(oldDep.es to oldDep.key)
                        }
                    }
                    is JoinDependency.QueryDependency -> {
                        // Remove from query dependencies list
                        queryDeps.removeAll { it.first == primaryKey && it.second == oldDep }
                    }
                }
            }

            // Record new dependencies
            primaryToDependencies[primaryKey] = dependencies.toMutableSet()
            dependencies.forEach { dep ->
                when (dep) {
                    is JoinDependency.KeyDependency -> {
                        // Add to key-based reverse index
                        keyToPrimaries.getOrPut(dep.es to dep.key) { mutableSetOf() }.add(primaryKey)
                    }
                    is JoinDependency.QueryDependency -> {
                        // Add to query dependencies list
                        queryDeps.add(primaryKey to dep)
                    }
                }
            }
        }
    }

    /**
     * The primary keys a [change] to [es] may affect: for Put/Removed, those depending on the
     * changed key or with a query the old or new value matches; for Cleared/Rebuild, every
     * primary key depending on [es] at all.
     *
     * A view calls this where it serializes entry evaluation (under its mutation lock, or in its
     * event loop), so an evaluation still running — whose dependencies are recorded only when it
     * finishes — completes first and is found.
     */
    suspend fun primaryKeysAffectedBy(es: Es<*, *>, change: TreeMap.MapChange<*, *>): Set<PK> =
        mutex.withLock {
            val result = mutableSetOf<PK>()
            when (change) {
                is Put<*, *>, is Removed<*, *> -> {
                    val key = if (change is Put<*, *>) change.key else (change as Removed<*, *>).key
                    val oldValue = if (change is Put<*, *>) change.oldValue else (change as Removed<*, *>).oldValue
                    val newValue = (change as? Put<*, *>)?.value
                    keyToPrimaries[es to (key as Any)]?.let { result.addAll(it) }
                    queryDeps.forEach { (primaryKey, queryDep) ->
                        if (queryDep.es == es &&
                            (oldValue?.let { queryDep.matches(it) } == true ||
                                newValue?.let { queryDep.matches(it) } == true)
                        ) result.add(primaryKey)
                    }
                }
                is Cleared<*, *>, is Rebuild<*, *> -> {
                    keyToPrimaries.forEach { (esAndKey, primaries) ->
                        if (esAndKey.first == es) result.addAll(primaries)
                    }
                    queryDeps.forEach { (primaryKey, queryDep) -> if (queryDep.es == es) result.add(primaryKey) }
                }
            }
            result
        }

    suspend fun removePrimaryEntry(primaryKey: PK) {
        mutex.withLock {
            // Remove from reverse indices
            primaryToDependencies[primaryKey]?.forEach { dep ->
                when (dep) {
                    is JoinDependency.KeyDependency -> {
                        keyToPrimaries[dep.es to dep.key]?.remove(primaryKey)
                        if (keyToPrimaries[dep.es to dep.key]?.isEmpty() == true) {
                            keyToPrimaries.remove(dep.es to dep.key)
                        }
                    }
                    is JoinDependency.QueryDependency -> {
                        queryDeps.removeAll { it.first == primaryKey && it.second == dep }
                    }
                }
            }

            // Remove from forward index
            primaryToDependencies.remove(primaryKey)
        }
    }

    suspend fun clear() {
        mutex.withLock {
            primaryToDependencies.clear()
            keyToPrimaries.clear()
            queryDeps.clear()
        }
    }

    // Dependency collections [subscribeToAllDependencies] has launched a subscription for, those
    // whose subscription is registered, and — for collections not yet registered — the earliest
    // change counter read before a fetch from them (see [noteRead]).
    private val launched = mutableSetOf<Es<*, *>>()
    private val subscribed = mutableSetOf<Es<*, *>>()
    private val firstRead = mutableMapOf<Es<*, *>, Long>()
    private val newDependency = Channel<Unit>(Channel.CONFLATED)

    /**
     * Called by [JoinContext] before its first read from [es] in an evaluation, with [es]'s change
     * counter read before that read. For a collection not yet subscribed, keeps the earliest
     * such counter and wakes [subscribeToAllDependencies] to subscribe to it.
     */
    suspend fun noteRead(es: Es<*, *>, seq: Long) {
        mutex.withLock {
            if (es in subscribed) return@withLock
            val earliest = firstRead[es]
            if (earliest == null || seq < earliest) firstRead[es] = seq
            if (es !in launched) newDependency.trySend(Unit)
        }
    }

    /**
     * Subscribes to every collection entries have read from, as soon as the first read is noted
     * ([noteRead]), and hands each of their changes to [onDependencyChange]; suspends until
     * cancelled. The caller resolves the affected entries with [primaryKeysAffectedBy].
     *
     * A read precedes its subscription, so a change written in between would be missed. The
     * subscription compares the collection's change counter, once registered, with the one read
     * before the earliest read: if it moved, it hands on a [Rebuild] of that collection, so its
     * dependents re-evaluate. Normally nothing was written in between and nothing re-evaluates.
     */
    suspend fun subscribeToAllDependencies(
        onDependencyChange: suspend (es: Es<*, *>, change: TreeMap.MapChange<*, *>) -> Unit,
    ) {
        try {
            coroutineScope {
                while (true) {
                    val toSubscribe = mutex.withLock {
                        firstRead.keys.filter { it !in launched }.also { launched += it }
                    }
                    toSubscribe.forEach { es ->
                        launch {
                            es.changes
                                .onSubscription {
                                    val since = mutex.withLock {
                                        subscribed += es
                                        firstRead.remove(es)
                                    }
                                    if (since == null || es.lastChangeSeq() > since) {
                                        emit(TreeMap.MapChange.Rebuild())
                                    }
                                }
                                .collect { change -> onDependencyChange(es, change) }
                        }
                    }
                    // Wait for a read from a collection not yet launched.
                    newDependency.receive()
                }
            }
        } finally {
            // Must run before the view's next run calls noteRead, or a collection still marked
            // subscribed would never be re-subscribed. It does: cancellation resumes this on the
            // main thread ahead of the later ON_START, and the Mutex is fair. Revisit if this
            // ever runs off the main thread or outside repeatOnLifecycle.
            withContext(NonCancellable) {
                mutex.withLock {
                    launched.clear()
                    subscribed.clear()
                }
            }
        }
    }
}

/**
 * Helper to find insertion point for a mapped entry in a sorted list.
 */
internal fun <K : Any, T : Any> findMappedInsertionPoint(
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
private suspend fun <K : Any, V : Any> handlePutInSortedList(
    list: SnapshotStateList<Map.Entry<K, V>>,
    change: TreeMap.MapChange.Put<K, V>,
    comparator: Comparator<Map.Entry<K, V>>,
    filter: (suspend (Map.Entry<K, V>) -> Boolean)?
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
 * Puts [key]'s row in [list] into the state [value] (null: absent) without trusting any previous
 * value: finds the current row by key (O(n)). For changes the last snapshot may already contain
 * (see [SnapshotWindow]) — never leaves two rows for one key.
 */
private suspend fun <K : Any, V : Any> replaceKeyInSortedList(
    list: SnapshotStateList<Map.Entry<K, V>>,
    key: K,
    value: V?,
    comparator: Comparator<Map.Entry<K, V>>,
    filter: (suspend (Map.Entry<K, V>) -> Boolean)?
) {
    // Filter first (it suspends), then remove and re-insert without suspending.
    val entry = value?.let { MapEntry(key, it) }
    val passes = entry != null && filter?.invoke(entry) != false
    val index = list.indexOfFirst { it.key == key }
    if (index >= 0) list.removeAt(index)
    if (passes) list.add(findMappedInsertionPoint(list, entry!!, comparator), entry)
}

/**
 * Which queued changes a sorted view's last snapshot already contains.
 *
 * The view subscribes to [Es.changes] before reading its snapshot, so no change is lost; a change
 * written while the snapshot was being read is then both queued and possibly in the snapshot.
 * [snapshot] brackets the read with the change counter ([TreeMap.MapChange.seq]):
 *  - `seq <= before`: happened before the read began — the snapshot has it, skip it ([contains]);
 *  - `before < seq <= after`: happened during the read — apply it by key, trusting no previous
 *    value ([isUncertain]);
 *  - `seq > after`: happened after — apply it normally.
 * A change numbered 0 (the view's own injected Rebuild) is never contained.
 */
private class SnapshotWindow {
    private var before = 0L
    private var after = 0L

    suspend fun <T> snapshot(es: Es<*, *>, read: suspend () -> T): T {
        before = es.lastChangeSeq()
        return read().also { after = es.lastChangeSeq() }
    }

    fun contains(change: TreeMap.MapChange<*, *>): Boolean = change.seq in 1..before

    fun isUncertain(change: TreeMap.MapChange<*, *>): Boolean = change.seq in (before + 1)..after
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

// ============================================================================
// General-purpose transformation utilities
// ============================================================================

/**
 * Creates a reactive State by transforming a value with a regular (non-suspend) function.
 *
 * The transformation is recomputed immediately when the value or dependencies change.
 * Use this for fast, synchronous transformations (formatting, string operations, calculations, etc.).
 *
 * ## Key Features
 *
 * - **Immediate computation**: Transform is evaluated synchronously during composition
 * - **No null intermediate state**: State is created with the transformed value already computed
 * - **Reactive to changes**: Recomputes when value or dependencies change
 * - **Type-safe**: Full Kotlin type inference support
 *
 * ## Performance
 *
 * - Initial computation: Synchronous, happens immediately during composition
 * - On value/dependency change: O(1) + transform cost
 * - Memory: Single State<R?> object per usage
 *
 * ## Use Cases
 *
 * - Simple transformations that don't require suspend (uppercase, formatting, etc.)
 * - Extracting/combining properties from an object
 * - Mathematical calculations or string operations
 * - Transformations that depend on external context (locale, theme, user preferences)
 *
 * ## Example
 *
 * ```kotlin
 * @Composable
 * fun SupplierCard(supplier: SupplierDto) {
 *     val locale = Locale.current
 *     val theme = MaterialTheme.colorScheme
 *
 *     // Simple property access
 *     val formattedName by rememberTransformed(supplier) {
 *         it.name.uppercase()
 *     }
 *
 *     // Captured state reacts automatically — `locale` is tracked by the
 *     // OkTopoi compiler plugin and keyed into remember.
 *     val localizedName by rememberTransformed(supplier) {
 *         it.name.uppercase(locale)
 *     }
 *
 *     // Combining multiple properties
 *     val summary by rememberTransformed(supplier) {
 *         "${it.name} (${it.partner.company})"
 *     }
 *
 *     Text(formattedName)
 * }
 * ```
 *
 * ## Comparison with rememberSuspendTransformed
 *
 * - `rememberTransformed`: For synchronous, fast transformations (no suspend)
 * - `rememberSuspendTransformed`: For async transformations (with suspend support)
 *
 * @param T the type of the input value
 * @param R the type of the transformed result
 * @param value the value to transform
 * @param transform function to transform the value (receives value, returns transformed result or null).
 *   Reactive state captured inside the lambda is tracked by the OkTopoi compiler plugin and keyed
 *   automatically — no manual dependency list required.
 * @return reactive State that updates when value or any captured reactive state changes
 */
@Composable
fun <T, R> rememberTransformed(
    value: T,
    @WrapInRemember transform: (T) -> R?
): State<R?> {
    return remember(value, transform) {
        mutableStateOf(transform(value))
    }
}

/**
 * Creates a reactive State by transforming a value with a suspend function.
 *
 * The transformation is recomputed asynchronously when the value or dependencies change.
 * Use this for transformations that need to call suspend functions (database lookups,
 * async computations, etc.).
 *
 * ## Key Features
 *
 * - **Async transformation**: Can call suspend functions during transformation
 * - **Reactive to changes**: Re-executes when value or dependencies change
 * - **Lifecycle-aware**: Properly cancels when composition leaves
 * - **Null-safe**: Handles null results gracefully
 *
 * ## Performance
 *
 * - Initial computation: Async, happens in LaunchedEffect
 * - On value/dependency change: Async re-execution + transform cost
 * - Memory: Single State<R?> object per usage
 *
 * ## Use Cases
 *
 * - Calling suspend extension functions on objects (getDisplayName, computeProgress, etc.)
 * - Fetching related data from Es collections or databases
 * - Any async transformation or computation
 * - Transformations requiring I/O operations
 *
 * ## Example
 *
 * ```kotlin
 * @Composable
 * fun SupplierCard(supplier: SupplierDto) {
 *     // Call suspend extension function
 *     val displayName by rememberSuspendTransformed(supplier) {
 *         it.getDisplayName()  // suspend function
 *     }
 *
 *     // Fetch related data
 *     val partnerName by rememberSuspendTransformed(supplier) {
 *         val partner = Data.partners.get(it.partnerId)
 *         partner?.company ?: "Unknown"
 *     }
 *
 *     // Complex async computation
 *     val shellInfo by rememberSuspendTransformed(supplier) {
 *         supplier.externalId?.let { stationId ->
 *             val station = Data.shellStations.get(stationId)
 *             station?.let { "${it.name} - ${it.city}" }
 *         }
 *     }
 *
 *     Text(displayName ?: supplier.name)
 *     Text(partnerName)
 * }
 *
 * // Captured reactive state is tracked automatically
 * @Composable
 * fun OrderProgress(order: OrderDto, refresh: Boolean) {
 *     // `refresh` is captured by the lambda — the plugin keys remember on it,
 *     // so flipping it re-runs the transform.
 *     val progress by rememberSuspendTransformed(order) {
 *         if (refresh) it.computeProgress() else it.cachedProgress()
 *     }
 *
 *     CircularProgressIndicator(progress = progress ?: 0f)
 * }
 * ```
 *
 * ## Comparison with rememberTransformed
 *
 * - `rememberTransformed`: For synchronous, fast transformations (no suspend)
 * - `rememberSuspendTransformed`: For async transformations (with suspend support)
 *
 * ## Comparison with mapState
 *
 * - `mapState(key) { transform }`: Fetches from Es collection by key, then transforms
 * - `rememberSuspendTransformed(value) { transform }`: Transforms an object you already have
 *
 * Use `rememberSuspendTransformed` when you already have the object and just need to transform it.
 * Use `mapState` when you need to fetch the object from an Es collection first.
 *
 * @param T the type of the input value
 * @param R the type of the transformed result
 * @param value the value to transform
 * @param transform suspend function to transform the value (receives value, returns transformed result or null).
 *   Reactive state captured inside the lambda is tracked by the OkTopoi compiler plugin and keyed
 *   automatically — no manual dependency list required.
 * @return reactive State that updates when value or any captured reactive state changes
 */
@Composable
fun <T, R> rememberSuspendTransformed(
    value: T,
    @WrapInRemember transform: suspend (T) -> R?
): State<R?> {
    val state = remember { mutableStateOf<R?>(null) }

    LaunchedEffect(value, transform) {
        state.value = transform(value)
    }

    return state
}

