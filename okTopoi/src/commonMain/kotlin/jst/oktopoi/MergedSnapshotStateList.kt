@file:Suppress("UNCHECKED_CAST")

package jst.oktopoi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import jst.oktopoi.TreeMap.MapChange.Cleared
import jst.oktopoi.TreeMap.MapChange.Put
import jst.oktopoi.TreeMap.MapChange.Rebuild
import jst.oktopoi.TreeMap.MapChange.Removed
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

// ============================================================================
// Multi-collection merge source definitions
// ============================================================================

/**
 * Defines a source collection for [mergedSnapshotStateList].
 *
 * Each source wraps an [Es] collection and a projection function that maps its entries
 * to the common result type [T]. The projection receives a [JoinContext] for fetching
 * related data from other collections with automatic dependency tracking.
 *
 * @param K the key type of the source Es collection
 * @param V the value type of the source Es collection
 * @param T the common result type that all sources project into
 * @param es the source Es collection
 * @param project projection function that maps an entry to the common type.
 *                 Returns null to exclude the entry. Receives a [JoinContext] for
 *                 fetching related data with automatic dependency tracking.
 */
class MergeSource<K : Any, V : Any, T : Any>(
    val es: Es<K, V>,
    val project: suspend (entry: Map.Entry<K, V>, context: JoinContext) -> T?,
)

/**
 * Defines a source collection for [mergedSnapshotStateMap] with group key extraction.
 *
 * Each source wraps an [Es] collection, a secondary index name for grouping, and a
 * projection function. The secondary index is used for efficient group key extraction
 * and change routing — when an entry changes, only the affected group's list is updated.
 *
 * @param K the key type of the source Es collection
 * @param V the value type of the source Es collection
 * @param G the group key type (must be the same across all sources)
 * @param T the common result type that all sources project into
 * @param es the source Es collection
 * @param groupKey the name of the secondary index used for grouping.
 *                 The secondary index must be defined on the Es collection.
 * @param project projection function that maps an entry to the common type.
 *                 Returns null to exclude the entry. Receives a [JoinContext] for
 *                 fetching related data with automatic dependency tracking.
 */
class GroupedMergeSource<K : Any, V : Any, G : Any, T : Any>(
    val es: Es<K, V>,
    val groupKey: String,
    val project: suspend (entry: Map.Entry<K, V>, context: JoinContext) -> T?,
)

/**
 * Convenience extension to create a [MergeSource] from an Es collection.
 *
 * ```kotlin
 * Data.stops.asMergeSource { entry, ctx ->
 *     val driver = ctx.fetch(Data.drivers, entry.value.driverId)
 *     TimelineItem.Stop(entry.value, driver)
 * }
 * ```
 */
fun <K : Any, V : Any, T : Any> Es<K, V>.asMergeSource(
    project: suspend (entry: Map.Entry<K, V>, context: JoinContext) -> T?,
) = MergeSource(this, project)

/**
 * Convenience extension to create a [GroupedMergeSource] from an Es collection.
 *
 * ```kotlin
 * Data.stops.asGroupedMergeSource("truckId") { entry, ctx ->
 *     TimelineItem.Stop(entry.value)
 * }
 * ```
 */
fun <K : Any, V : Any, G : Any, T : Any> Es<K, V>.asGroupedMergeSource(
    groupKey: String,
    project: suspend (entry: Map.Entry<K, V>, context: JoinContext) -> T?,
) = GroupedMergeSource<K, V, G, T>(this, groupKey, project)

// ============================================================================
// Composite key for tracking items across multiple sources
// ============================================================================

/**
 * Composite key that uniquely identifies an item across multiple merge sources.
 * Combines the source index with the original key from that source's Es collection.
 */
private data class MergeKey(
    val sourceIndex: Int,
    val originalKey: Any,
)

// ============================================================================
// Sealed type for channeled events from multiple sources
// ============================================================================

/**
 * Events channeled from source change flows and dependency changes into a single collector.
 * Using a channel ensures all mutations to the SnapshotStateList happen on a single coroutine.
 */
private sealed interface MergeEvent {
    /** A source collection emitted a change. */
    data class SourceChange(
        val sourceIndex: Int,
        val change: TreeMap.MapChange<*, *>,
    ) : MergeEvent

    /** A join dependency changed — re-evaluate affected merge keys. */
    data class DependencyChange(
        val affectedKeys: Set<MergeKey>,
    ) : MergeEvent

    /** Full rebuild requested (e.g., on initial subscription). */
    data object RebuildAll : MergeEvent
}

// ============================================================================
// mergedSnapshotStateList
// ============================================================================

/**
 * Creates a reactive [SnapshotStateList] by merging entries from multiple [Es] collections.
 *
 * Each source's entries are projected to a common type [T] and combined into a single sorted list.
 * Changes to any source trigger granular updates — only the affected item is re-projected and
 * repositioned, not the entire list.
 *
 * ## How It Works
 *
 * 1. All source change flows are merged via a [Channel] into a single collector
 * 2. Each entry is projected to type [T] using its source's projection function
 * 3. Entries are maintained in sorted order using binary search (O(log n) insert/remove)
 * 4. Join dependencies are tracked via [JoinDependencyTracker] — when related data changes,
 *    only affected items are re-evaluated
 *
 * ## Performance
 *
 * - Initial build: O(N) projections + O(N log N) sort, where N = total entries across all sources
 * - Single item change: O(1) project + O(log N) binary search
 * - Join dependency change: O(k log N) where k = affected items
 * - Memory: O(N) for the list + O(D) for dependency tracking
 *
 * ## Example
 *
 * ```kotlin
 * val timeline = mergedSnapshotStateList(
 *     Data.stops.asMergeSource { entry, ctx ->
 *         val driver = ctx.fetch(Data.drivers, entry.value.driverId)
 *         TimelineItem.Stop(entry.value, driver, sortKey = entry.value.sequence)
 *     },
 *     Data.driverAssignments.asMergeSource { entry, _ ->
 *         TimelineItem.DriverChange(entry.value, sortKey = entry.value.sequence)
 *     },
 *     Data.unmatchedFtlEvents.asMergeSource { entry, ctx ->
 *         val stops = ctx.fetchBy(Data.stops, "truckId" to entry.value.truckId)
 *         val sortKey = interpolatePosition(stops, entry.value.timestamp)
 *         TimelineItem.UnmatchedFtl(entry.value, sortKey = sortKey)
 *     },
 *     comparator = compareBy { it.sortKey },
 * )
 * ```
 *
 * @param T the common result type that all sources project into
 * @param sources the merge sources to combine
 * @param comparator comparator for sorting the merged list. **Must be stable across recompositions.**
 * @param lifecycleOwner lifecycle owner (defaults to LocalLifecycleOwner)
 * @param minActiveState minimum lifecycle state (defaults to STARTED)
 * @return reactive [SnapshotStateList] containing projected items from all sources, sorted by [comparator]
 */
@Composable
fun <T : Any> mergedSnapshotStateList(
    vararg sources: MergeSource<*, *, T>,
    comparator: Comparator<T>,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
): SnapshotStateList<T> {

    val dependencyTracker = remember(comparator, *sources) {
        JoinDependencyTracker<MergeKey>()
    }

    val resultList = remember(comparator, *sources) {
        mutableStateListOf<T>()
    }

    // Internal mapping: MergeKey → current projected value (needed for removal by binary search)
    val keyToValue = remember(comparator, *sources) {
        mutableMapOf<MergeKey, T>()
    }

    // Channel for serializing all mutations from multiple source flows + dependency flows
    val eventChannel = remember(comparator, *sources) {
        Channel<MergeEvent>(Channel.UNLIMITED)
    }

    // --- Helper: project a single entry from a source ---
    suspend fun projectEntry(
        sourceIndex: Int,
        key: Any,
        value: Any,
    ): T? {
        val source = sources[sourceIndex] as MergeSource<Any, Any, T>
        val entry = MapEntry(key, value)
        val context = JoinContext()
        val projected = source.project(entry, context)
        val mergeKey = MergeKey(sourceIndex, key)
        dependencyTracker.recordDependencies(mergeKey, context.dependencies)
        return projected
    }

    // --- Helper: remove an item from the sorted list ---
    fun removeFromList(mergeKey: MergeKey) {
        val oldValue = keyToValue.remove(mergeKey) ?: return
        val index = findInsertionPointInList(resultList, oldValue, comparator, mergeKey, keyToValue)
        if (index >= 0 && index < resultList.size && resultList[index] === oldValue) {
            resultList.removeAt(index)
        } else {
            // Fallback: linear scan (shouldn't normally happen)
            val fallbackIndex = resultList.indexOfFirst { it === oldValue }
            if (fallbackIndex >= 0) resultList.removeAt(fallbackIndex)
        }
    }

    // --- Helper: insert an item into the sorted list ---
    fun insertIntoList(mergeKey: MergeKey, value: T) {
        keyToValue[mergeKey] = value
        val insertIndex = findValueInsertionPoint(resultList, value, comparator)
        resultList.add(insertIndex, value)
    }

    // --- Helper: re-evaluate a single entry ---
    suspend fun reEvaluateEntry(sourceIndex: Int, key: Any) {
        val typedEs = sources[sourceIndex].es as Es<Any, Any>
        val currentValue = typedEs.get(key)
        val mergeKey = MergeKey(sourceIndex, key)

        // Remove old projected value
        removeFromList(mergeKey)

        if (currentValue != null) {
            val projected = projectEntry(sourceIndex, key, currentValue)
            if (projected != null) {
                insertIntoList(mergeKey, projected)
            }
        } else {
            // Entry was removed from source
            dependencyTracker.removePrimaryEntry(mergeKey)
        }
    }

    // --- Helper: full rebuild ---
    suspend fun rebuild() {
        resultList.clear()
        keyToValue.clear()
        dependencyTracker.clear()

        val allItems = mutableListOf<Pair<MergeKey, T>>()

        sources.forEachIndexed { sourceIndex, source ->
            val typedEs = source.es as Es<Any, Any>
            typedEs.entries().forEach { entry ->
                val projected = projectEntry(sourceIndex, entry.key, entry.value)
                if (projected != null) {
                    val mergeKey = MergeKey(sourceIndex, entry.key)
                    allItems.add(mergeKey to projected)
                }
            }
        }

        allItems.sortWith(compareBy(comparator) { it.second })
        allItems.forEach { (mergeKey, value) ->
            keyToValue[mergeKey] = value
            resultList.add(value)
        }
    }

    // --- Collector coroutine: single consumer of all events ---
    LaunchedEffect(comparator, *sources, minActiveState) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
            // Initial rebuild
            rebuild()

            coroutineScope {
                // Launch source change listeners that feed into the channel
                sources.forEachIndexed { sourceIndex, source ->
                    launch {
                        source.es.changes.collect { change ->
                            eventChannel.send(MergeEvent.SourceChange(sourceIndex, change))
                        }
                    }
                }

                // Launch dependency tracker subscription
                launch {
                    dependencyTracker.subscribeToAllDependencies(
                        onDependencyChange = { dependencyEs, dependencyKey, oldValue, newValue ->
                            val affectedKeys = dependencyTracker.getPrimaryKeysDependingOn<Any>(
                                dependencyEs, dependencyKey, oldValue, newValue
                            )
                            if (affectedKeys.isNotEmpty()) {
                                eventChannel.send(MergeEvent.DependencyChange(affectedKeys))
                            }
                        },
                        onBatchReevaluate = { primaryKeys ->
                            if (primaryKeys.isNotEmpty()) {
                                eventChannel.send(MergeEvent.DependencyChange(primaryKeys))
                            }
                        }
                    )
                }

                // Single collector — all list mutations happen here
                for (event in eventChannel) {
                    when (event) {
                        is MergeEvent.SourceChange -> {
                            val sourceIndex = event.sourceIndex
                            when (val change = event.change) {
                                is Put<*, *> -> {
                                    reEvaluateEntry(sourceIndex, change.key as Any)
                                }
                                is Removed<*, *> -> {
                                    val mergeKey = MergeKey(sourceIndex, change.key as Any)
                                    removeFromList(mergeKey)
                                    dependencyTracker.removePrimaryEntry(mergeKey)
                                }
                                is Cleared<*, *> -> {
                                    // Remove all entries from this source
                                    val keysToRemove = keyToValue.keys.filter { it.sourceIndex == sourceIndex }
                                    keysToRemove.forEach { mergeKey ->
                                        removeFromList(mergeKey)
                                        dependencyTracker.removePrimaryEntry(mergeKey)
                                    }
                                }
                                is Rebuild<*, *> -> {
                                    // Rebuild just this source
                                    val keysToRemove = keyToValue.keys.filter { it.sourceIndex == sourceIndex }
                                    keysToRemove.forEach { mergeKey ->
                                        removeFromList(mergeKey)
                                        dependencyTracker.removePrimaryEntry(mergeKey)
                                    }
                                    val typedEs = sources[sourceIndex].es as Es<Any, Any>
                                    typedEs.entries().forEach { entry ->
                                        val projected = projectEntry(sourceIndex, entry.key, entry.value)
                                        if (projected != null) {
                                            insertIntoList(MergeKey(sourceIndex, entry.key), projected)
                                        }
                                    }
                                }
                            }
                        }
                        is MergeEvent.DependencyChange -> {
                            event.affectedKeys.forEach { mergeKey ->
                                reEvaluateEntry(mergeKey.sourceIndex, mergeKey.originalKey)
                            }
                        }
                        is MergeEvent.RebuildAll -> {
                            rebuild()
                        }
                    }
                }
            }
        }
    }

    return resultList
}

// ============================================================================
// mergedSnapshotStateMap
// ============================================================================

/**
 * Creates a reactive [SnapshotStateMap] by merging entries from multiple [Es] collections,
 * grouped by a secondary index key.
 *
 * This is the grouped variant of [mergedSnapshotStateList]. Instead of producing a single flat list,
 * it produces a map of group key → sorted list. Each source declares which secondary index provides
 * the group key. When an entry changes, only the affected group's [SnapshotStateList] is updated,
 * providing per-group granular reactivity.
 *
 * This is ideal for scenarios like a truck table where each truck column shows a merged timeline —
 * one subscription per source Es handles all trucks, and a stop change only touches that stop's
 * truck group.
 *
 * ## How It Works
 *
 * 1. All source change flows are merged via a [Channel] into a single collector
 * 2. For each entry, the group key is extracted via the source's secondary index
 * 3. The entry is projected to type [T] and inserted into the group's sorted list
 * 4. When an entry changes, the old group key and new group key are compared:
 *    - Same group: remove old, insert new in the same group's list
 *    - Different group: remove from old group, insert into new group
 *    - Empty groups are automatically cleaned up
 *
 * ## Performance
 *
 * - Initial build: O(N) projections + O(N log N) sort, where N = total entries across all sources
 * - Single item change: O(1) group key extraction + O(1) project + O(log M) binary search,
 *   where M = group size
 * - Join dependency change: O(k log M) where k = affected items
 * - Memory: O(N) for all lists + O(G) for group map + O(D) for dependency tracking
 *
 * ## Example
 *
 * ```kotlin
 * val truckTimelines: SnapshotStateMap<Long, SnapshotStateList<TimelineItem>> =
 *     mergedSnapshotStateMap(
 *         GroupedMergeSource(Data.stops, "truckId") { entry, ctx ->
 *             val driver = ctx.fetch(Data.drivers, entry.value.driverId)
 *             TimelineItem.Stop(entry.value, driver)
 *         },
 *         GroupedMergeSource(Data.driverAssignments, "truckId") { entry, _ ->
 *             TimelineItem.DriverChange(entry.value)
 *         },
 *         GroupedMergeSource(Data.unmatchedFtlEvents, "truckId") { entry, ctx ->
 *             val stops = ctx.fetchBy(Data.stops, "truckId" to entry.value.truckId)
 *             val sortKey = interpolatePosition(stops, entry.value.timestamp)
 *             TimelineItem.UnmatchedFtl(entry.value, sortKey = sortKey)
 *         },
 *         comparator = compareBy { it.sortKey },
 *     )
 *
 * // In the truck table — only affected truck column recomposes:
 * LazyRow {
 *     items(visibleTruckIds) { truckId ->
 *         TruckColumn(truckTimelines[truckId] ?: emptyList())
 *     }
 * }
 * ```
 *
 * @param G the group key type (extracted from secondary index, same across all sources)
 * @param T the common result type that all sources project into
 * @param sources the grouped merge sources to combine
 * @param comparator comparator for sorting entries within each group. **Must be stable across recompositions.**
 * @param lifecycleOwner lifecycle owner (defaults to LocalLifecycleOwner)
 * @param minActiveState minimum lifecycle state (defaults to STARTED)
 * @return reactive [SnapshotStateMap] mapping group keys to sorted [SnapshotStateList]s
 */
@Composable
fun <G : Any, T : Any> mergedSnapshotStateMap(
    vararg sources: GroupedMergeSource<*, *, G, T>,
    comparator: Comparator<T>,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
): SnapshotStateMap<G, SnapshotStateList<T>> {

    val dependencyTracker = remember(comparator, *sources) {
        JoinDependencyTracker<MergeKey>()
    }

    val resultMap = remember(comparator, *sources) {
        mutableStateMapOf<G, SnapshotStateList<T>>()
    }

    // Track: MergeKey → (groupKey, projected value) for efficient updates
    val keyToGroupAndValue = remember(comparator, *sources) {
        mutableMapOf<MergeKey, Pair<G, T>>()
    }

    val eventChannel = remember(comparator, *sources) {
        Channel<MergeEvent>(Channel.UNLIMITED)
    }

    // --- Helpers ---

    fun getOrCreateList(groupKey: G): SnapshotStateList<T> {
        return resultMap.getOrPut(groupKey) { mutableStateListOf() }
    }

    fun removeIfEmpty(groupKey: G) {
        resultMap[groupKey]?.let { if (it.isEmpty()) resultMap.remove(groupKey) }
    }

    fun extractGroupKey(sourceIndex: Int, value: Any): G? {
        val source = sources[sourceIndex] as GroupedMergeSource<Any, Any, G, T>
        return source.es.getSecondaryKey(source.groupKey, value) as? G
    }

    suspend fun projectEntry(sourceIndex: Int, key: Any, value: Any): T? {
        val source = sources[sourceIndex] as GroupedMergeSource<Any, Any, G, T>
        val entry = MapEntry(key, value)
        val context = JoinContext()
        val projected = source.project(entry, context)
        val mergeKey = MergeKey(sourceIndex, key)
        dependencyTracker.recordDependencies(mergeKey, context.dependencies)
        return projected
    }

    fun removeFromGroup(mergeKey: MergeKey) {
        val (oldGroup, oldValue) = keyToGroupAndValue.remove(mergeKey) ?: return
        val list = resultMap[oldGroup] ?: return
        val index = list.indexOfFirst { it === oldValue }
        if (index >= 0) list.removeAt(index)
        removeIfEmpty(oldGroup)
    }

    fun insertIntoGroup(mergeKey: MergeKey, groupKey: G, value: T) {
        keyToGroupAndValue[mergeKey] = groupKey to value
        val list = getOrCreateList(groupKey)
        val insertIndex = findValueInsertionPoint(list, value, comparator)
        list.add(insertIndex, value)
    }

    suspend fun reEvaluateEntry(sourceIndex: Int, key: Any) {
        val typedEs = sources[sourceIndex].es as Es<Any, Any>
        val currentValue = typedEs.get(key)
        val mergeKey = MergeKey(sourceIndex, key)

        removeFromGroup(mergeKey)

        if (currentValue != null) {
            val groupKey = extractGroupKey(sourceIndex, currentValue)
            if (groupKey != null) {
                val projected = projectEntry(sourceIndex, key, currentValue)
                if (projected != null) {
                    insertIntoGroup(mergeKey, groupKey, projected)
                }
            }
        } else {
            dependencyTracker.removePrimaryEntry(mergeKey)
        }
    }

    suspend fun rebuild() {
        resultMap.clear()
        keyToGroupAndValue.clear()
        dependencyTracker.clear()

        val grouped = mutableMapOf<G, MutableList<Pair<MergeKey, T>>>()

        sources.forEachIndexed { sourceIndex, source ->
            val typedEs = source.es as Es<Any, Any>
            typedEs.entries().forEach { entry ->
                val groupKey = extractGroupKey(sourceIndex, entry.value)
                if (groupKey != null) {
                    val projected = projectEntry(sourceIndex, entry.key, entry.value)
                    if (projected != null) {
                        val mergeKey = MergeKey(sourceIndex, entry.key)
                        keyToGroupAndValue[mergeKey] = groupKey to projected
                        grouped.getOrPut(groupKey) { mutableListOf() }.add(mergeKey to projected)
                    }
                }
            }
        }

        grouped.forEach { (groupKey, items) ->
            items.sortWith(compareBy(comparator) { it.second })
            resultMap[groupKey] = items.map { it.second }.toMutableStateList()
        }
    }

    // --- Collector coroutine ---
    LaunchedEffect(comparator, *sources, minActiveState) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
            rebuild()

            coroutineScope {
                // Launch source change listeners
                sources.forEachIndexed { sourceIndex, source ->
                    launch {
                        source.es.changes.collect { change ->
                            eventChannel.send(MergeEvent.SourceChange(sourceIndex, change))
                        }
                    }
                }

                // Launch dependency tracker subscription
                launch {
                    dependencyTracker.subscribeToAllDependencies(
                        onDependencyChange = { dependencyEs, dependencyKey, oldValue, newValue ->
                            val affectedKeys = dependencyTracker.getPrimaryKeysDependingOn<Any>(
                                dependencyEs, dependencyKey, oldValue, newValue
                            )
                            if (affectedKeys.isNotEmpty()) {
                                eventChannel.send(MergeEvent.DependencyChange(affectedKeys))
                            }
                        },
                        onBatchReevaluate = { primaryKeys ->
                            if (primaryKeys.isNotEmpty()) {
                                eventChannel.send(MergeEvent.DependencyChange(primaryKeys))
                            }
                        }
                    )
                }

                // Single collector
                for (event in eventChannel) {
                    when (event) {
                        is MergeEvent.SourceChange -> {
                            val sourceIndex = event.sourceIndex
                            when (val change = event.change) {
                                is Put<*, *> -> {
                                    reEvaluateEntry(sourceIndex, change.key as Any)
                                }
                                is Removed<*, *> -> {
                                    val mergeKey = MergeKey(sourceIndex, change.key as Any)
                                    removeFromGroup(mergeKey)
                                    dependencyTracker.removePrimaryEntry(mergeKey)
                                }
                                is Cleared<*, *> -> {
                                    val keysToRemove = keyToGroupAndValue.keys.filter { it.sourceIndex == sourceIndex }
                                    keysToRemove.forEach { mergeKey ->
                                        removeFromGroup(mergeKey)
                                        dependencyTracker.removePrimaryEntry(mergeKey)
                                    }
                                }
                                is Rebuild<*, *> -> {
                                    val keysToRemove = keyToGroupAndValue.keys.filter { it.sourceIndex == sourceIndex }
                                    keysToRemove.forEach { mergeKey ->
                                        removeFromGroup(mergeKey)
                                        dependencyTracker.removePrimaryEntry(mergeKey)
                                    }
                                    val typedEs = sources[sourceIndex].es as Es<Any, Any>
                                    typedEs.entries().forEach { entry ->
                                        val groupKey = extractGroupKey(sourceIndex, entry.value)
                                        if (groupKey != null) {
                                            val projected = projectEntry(sourceIndex, entry.key, entry.value)
                                            if (projected != null) {
                                                insertIntoGroup(MergeKey(sourceIndex, entry.key), groupKey, projected)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is MergeEvent.DependencyChange -> {
                            event.affectedKeys.forEach { mergeKey ->
                                reEvaluateEntry(mergeKey.sourceIndex, mergeKey.originalKey)
                            }
                        }
                        is MergeEvent.RebuildAll -> {
                            rebuild()
                        }
                    }
                }
            }
        }
    }

    return resultMap
}

// ============================================================================
// Private helpers
// ============================================================================

/**
 * Binary search for insertion point in a sorted list of values.
 */
private fun <T> findValueInsertionPoint(
    list: List<T>,
    value: T,
    comparator: Comparator<T>,
): Int {
    var low = 0
    var high = list.size

    while (low < high) {
        val mid = (low + high) / 2
        if (comparator.compare(value, list[mid]) > 0) {
            low = mid + 1
        } else {
            high = mid
        }
    }

    return low
}

/**
 * Find the index of a value in a sorted list, using binary search to find the general area
 * and then identity comparison to find the exact item.
 * Returns the index if found via identity match, or the insertion point if not found.
 */
private fun <T> findInsertionPointInList(
    list: List<T>,
    value: T,
    comparator: Comparator<T>,
    mergeKey: MergeKey,
    keyToValue: Map<MergeKey, T>,
): Int {
    var low = 0
    var high = list.size

    while (low < high) {
        val mid = (low + high) / 2
        if (comparator.compare(value, list[mid]) > 0) {
            low = mid + 1
        } else {
            high = mid
        }
    }

    // Search around the binary search result for identity match
    var left = low - 1
    var right = low

    while (left >= 0 || right < list.size) {
        if (right < list.size) {
            if (list[right] === value) return right
            if (comparator.compare(value, list[right]) != 0) right = list.size else right++
        }
        if (left >= 0) {
            if (list[left] === value) return left
            if (comparator.compare(value, list[left]) != 0) left = -1 else left--
        }
    }

    return low
}
