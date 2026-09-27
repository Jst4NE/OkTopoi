# OkTopoi User Guide

Guide for application developers using the OkTopoi library. For OkTopoi internals and development, see [DEVELOPER_GUIDE.md](../DEVELOPER_GUIDE.md).

## Core Concepts

OkTopoi provides reactive state management with optional persistence and synchronization. There are two families of primitives:

**Individual state** — holds a single value:
- `e<T>` — Observable state (in-memory, resets on restart)
- `ep<T>` — Persistent state (saved to disk, survives restarts)
- `esp<T>` — Synchronized persistent state (+ bidirectional sync)

**Collections** — keyed maps with secondary indexing:
- `es<K, V>` — Observable collection (in-memory)
- `eps<K, V>` — Persistent collection (saved to disk)
- `esps<K, V>` — Synchronized persistent collection (+ bidirectional sync)

**Key rule:** Individual state (`e`/`ep`/`esp`) has synchronous `.value` access. Collections (`es`/`eps`/`esps`) have **suspend-only** API for all operations.

## Setup

Before using any persistent primitives (`ep`, `eps`, `esps`, `esp`), initialize storage:

```kotlin
// Single storage location (most common)
initDefaultIO(defaultRootDir = Path("/app/data"))

// Multiple storage locations
initDefaultIO(Path("/app/data"))           // Default for most data
initRootDirIO(Path("/secure/credentials")) // Specific location for sensitive data
```

If initialization is missing, `setup()` blocks indefinitely waiting for it — so initialize before
the first persistent value is touched. Before persisting anything you can't afford to lose, read
[Things to know](#things-to-know).

## Individual State

### e — Observable State

```kotlin
val counter = e { 0 }
counter.value = 42              // Synchronous read/write
counter.collect { println(it) } // Flow-based observation
```

### ep — Persistent State

```kotlin
val theme = ep<String> { "dark" }
theme.value = "light"  // Automatically persisted to disk

// With explicit storage location
val secrets = ep<String>(rootDir = Path("/secure")) { "default" }
```

### Common operations on E/Ep

```kotlin
state.value                           // Get current value
state.value = newValue                // Set new value
state.setIfDifferent(newValue)        // Set only if changed (returns Boolean)
state.compareAndSet(expected, update) // Atomic compare-and-set
state.isEmpty()                       // Check if null/default
state.clear()                         // Reset to default
```

### Observing state in Compose

```kotlin
// As Compose state (recomposes on change)
val theme by Data.Settings.theme.collectAsStateWithLifecycle()

// Update
Data.Settings.theme.value = "dark"
```

## Collections

### Creating collections

```kotlin
// Basic observable collection
val users = es<String, User>()

// With secondary indices
val users = es<String, User> {
    key("department") { it.department }
    key("level") { it.level }
}

// Persistent collection
val users = eps<String, User> {
    key("department") { it.department }
}

// Synchronized persistent collection (e.g., with Supabase)
val users = esps<String, User>(
    secondaryKeys = { key("department") { it.department } },
    incomingSync = incomingFlow,
    outgoingSync = { key, value, timestamp -> sendToRemote(key, value, timestamp) }
)
```

### Data access (all suspend)

```kotlin
// Basic CRUD
val user = users.get("alice")          // Get by primary key
users.put("alice", User(...))          // Insert/update
users.remove("alice")                  // Delete
val allEntries = users.entries()       // All entries
val allKeys = users.keys()             // All keys

// Secondary index queries
val engineers = users.getBy("department" to "Engineering")
val seniorEngineers = users.getBy("department" to "Engineering", "level" to "Senior")

// Functional operations
val names = users.map { it.value.name }
val active = users.filter { it.value.isActive }
val emails = users.mapNotNull { it.value.email }

// Iterate by secondary key
users.forEachBy("department" to "Engineering") { key, user ->
    println("$key: ${user.name}")
}
```

## Compose Integration

OkTopoi provides `@Composable` functions on collections for building reactive UIs. These are **not** suspend functions — they handle coroutine management internally.

### asSnapshotStateList — Reactive list

The most common pattern. Renders all entries with optional filtering and sorting.

```kotlin
@Composable
fun UserList() {
    val users = Data.users.asSnapshotStateList(
        entryComparator = compareBy { it.value.name.lowercase() },
        filter = { entry ->
            searchQuery.isEmpty() ||
            entry.value.name.contains(searchQuery, ignoreCase = true)
        }
    )

    LazyColumn {
        items(users, key = { it.key }) { entry ->
            UserCard(user = entry.value)
        }
    }
}
```

**Returns:** `SnapshotStateList<Map.Entry<K, V>>`

**Key points:**
- Compose only recomposes affected items when data changes
- Lambda arguments (`filter`, `entryComparator`) are automatically memoized by the OkTopoi compiler plugin — captured variables from the enclosing scope are used as cache keys
- Works seamlessly with `LazyColumn`'s `items()` with `key`

### asSnapshotStateListBySecondaryKey — Filtered by index

Efficiently filters by secondary index, then optionally applies additional filter/sort.

```kotlin
@Composable
fun EngineerList() {
    val engineers = Data.users.asSnapshotStateListBySecondaryKey(
        "department" to "Engineering",
        entryComparator = compareBy { it.value.name }
    )
}
```

### asSnapshotStateMapBySecondaryKey — Grouped reactive map

Groups entries by a secondary index key. Each group is an independent `SnapshotStateList` — only affected groups trigger recomposition.

**Use for parent-child relationships** (e.g., orders with line items, categories with products):

```kotlin
@Composable
fun OrdersScreen() {
    // Group items by orderId
    val itemsByOrder = Data.orderItems.asSnapshotStateMapBySecondaryKey<Long>(
        groupByKey = OrderItemDto::orderId.name,
        entryComparator = compareBy { it.value.sequenceNumber },
        filter = { entry ->
            selectedCategory == null || entry.value.category == selectedCategory
        }
    )

    // Only show orders that have matching items
    val orders = Data.orders.asSnapshotStateList(
        entryComparator = compareByDescending { it.value.createdAt },
        filter = { entry ->
            itemsByOrder[entry.value.id]?.isNotEmpty() == true
        }
    )

    LazyColumn {
        items(orders, key = { it.key }) { orderEntry ->
            val items = itemsByOrder[orderEntry.value.id] ?: emptyList()
            OrderCard(order = orderEntry.value, items = items)
        }
    }
}
```

**Returns:** `SnapshotStateMap<SK, SnapshotStateList<Map.Entry<K, V>>>`

**Key benefits:**
- Granular reactivity — only affected groups recompose
- O(log n) insertions/updates within groups
- Empty groups automatically cleaned up
- Each group's `SnapshotStateList` is a stable instance, safe to hold/capture — see [Group-list instance stability](#group-list-instance-stability)

**Anti-pattern to avoid:**
```kotlin
// BAD — fetching children inside each card causes all cards to recompose
@Composable
fun OrderCard(order: OrderDto) {
    val items = Data.orderItems.getBy(OrderItemDto::orderId.name to order.id) // suspend in every card!
}

// GOOD — fetch once at parent level, pass to children
val itemsByOrder = Data.orderItems.asSnapshotStateMapBySecondaryKey<Long>(...)
OrderCard(order = order, items = itemsByOrder[order.id] ?: emptyList())
```

### asSnapshotStateListWithJoins — Multi-collection joins

Joins data across multiple collections with **automatic dependency tracking**. Only re-evaluates entries when their specific dependencies change.

```kotlin
@Composable
fun EnrichedOrderList() {
    val enrichedOrders = Data.orders.asSnapshotStateListWithJoins<EnrichedOrder>(
        entryComparator = compareByDescending { it.value.createdAt },
        filterMap = { entry, context ->
            val order = entry.value

            // Time filter
            if (order.createdAt !in dateRange) return@asSnapshotStateListWithJoins null

            // Fetch related data — dependencies are automatically tracked
            val customer = context.fetch(Data.customers, order.customerId)
                ?: return@asSnapshotStateListWithJoins null
            val items = context.fetchBy(Data.orderItems, OrderItemDto::orderId.name to order.id)

            EnrichedOrder(order, customer, items.map { it.value })
        }
    )
}
```

**Returns:** `SnapshotStateList<Map.Entry<K, T>>` where `T` is your mapped type

**JoinContext methods:**
- `context.fetch(collection, key)` — fetch single entry by primary key (suspend)
- `context.fetchBy(collection, criteria...)` — fetch entries by secondary index (suspend)

Both methods track dependencies: when a fetched entity changes, only the entries that depend on it are re-evaluated.

### asSnapshotStateMapWithJoins — Grouped joins with granular reactivity

Combines multi-collection joins with grouping. The most powerful pattern — use when you need joins AND grouping.

```kotlin
@Composable
fun OrdersByCustomer() {
    val ordersByCustomer = Data.orders.asSnapshotStateMapWithJoins<Long, EnrichedOrder>(
        groupByKey = { it.customerId },  // Extract grouping key from mapped result
        entryComparator = compareByDescending { it.value.createdAt },
        filterMap = { entry, context ->
            val order = entry.value
            if (statusFilter != null && order.status != statusFilter) return@asSnapshotStateMapWithJoins null

            val items = context.fetchBy(Data.orderItems, OrderItemDto::orderId.name to order.id)
            EnrichedOrder(order, items.map { it.value })
        }
    )
}
```

**Returns:** `SnapshotStateMap<SK, SnapshotStateList<Map.Entry<K, T>>>`

**Why use this over `asSnapshotStateListWithJoins` + `derivedStateOf { groupBy }`:**
```kotlin
// BAD — non-granular reactivity, all groups recompose on ANY change
val allOrders = Data.orders.asSnapshotStateListWithJoins<EnrichedOrder>(...)
val byCustomer by remember { derivedStateOf { allOrders.groupBy { it.value.customerId } } }

// GOOD — granular per-group reactivity
val byCustomer = Data.orders.asSnapshotStateMapWithJoins<Long, EnrichedOrder>(
    groupByKey = { it.customerId }, ...
)
```

### mergedSnapshotStateList — Multi-collection merge

All previous reactive APIs are driven by a **single** Es collection. `mergedSnapshotStateList` solves a different problem: merging entries from **multiple** collections into one sorted reactive list.

Each source projects its entries to a common type `T`. Changes are granular — modifying one entry only re-projects and repositions that item, not the entire list.

```kotlin
@Composable
fun CustomerActivity(customerId: Long) {
    val activity = mergedSnapshotStateList(
        Data.orders.asMergeSource { entry, ctx ->
            val order = entry.value
            if (order.customerId != customerId) return@asMergeSource null
            val agent = ctx.fetch(Data.users, order.agentId)
            ActivityItem.Order(order, agent, sortKey = order.sequence.toDouble())
        },
        Data.payments.asMergeSource { entry, _ ->
            val payment = entry.value
            if (payment.customerId != customerId) return@asMergeSource null
            ActivityItem.Payment(payment, sortKey = payment.sequence.toDouble())
        },
        Data.unlinkedEvents.asMergeSource { entry, ctx ->
            val event = entry.value
            if (event.customerId != customerId) return@asMergeSource null
            // Fetch orders to compute position between sequence anchors
            val orders = ctx.fetchBy(Data.orders, "customerId" to customerId)
            val sortKey = interpolatePosition(orders, event.timestamp)
            ActivityItem.Unlinked(event, sortKey = sortKey)
        },
        comparator = compareBy { it.sortKey },
    )

    LazyColumn {
        items(activity, key = { it.id }) { item ->
            ActivityRow(item)
        }
    }
}
```

**Returns:** `SnapshotStateList<T>` (not `Map.Entry` — you control the type directly)

**JoinContext** is available in each source's projection, with the same `fetch()`/`fetchBy()` dependency tracking as the single-collection join APIs.

### mergedSnapshotStateMap — Grouped multi-collection merge

The grouped variant of `mergedSnapshotStateList`. Instead of one flat list, it produces `SnapshotStateMap<G, SnapshotStateList<T>>` — one sorted list per group. Each source declares which secondary index provides the group key.

This is more efficient than calling `mergedSnapshotStateList` per group, because there is **one subscription per source Es** regardless of group count.

```kotlin
@Composable
fun CustomerBoard() {
    val activityByCustomer: SnapshotStateMap<Long, SnapshotStateList<ActivityItem>> =
        mergedSnapshotStateMap(
            GroupedMergeSource(Data.orders, "customerId") { entry, ctx ->
                val agent = ctx.fetch(Data.users, entry.value.agentId)
                ActivityItem.Order(entry.value, agent)
            },
            GroupedMergeSource(Data.payments, "customerId") { entry, _ ->
                ActivityItem.Payment(entry.value)
            },
            GroupedMergeSource(Data.unlinkedEvents, "customerId") { entry, ctx ->
                val orders = ctx.fetchBy(Data.orders, "customerId" to entry.value.customerId)
                val sortKey = interpolatePosition(orders, entry.value.timestamp)
                ActivityItem.Unlinked(entry.value, sortKey = sortKey)
            },
            comparator = compareBy { it.sortKey },
        )

    LazyRow {
        items(visibleCustomerIds) { customerId ->
            CustomerColumn(activityByCustomer[customerId] ?: emptyList())
        }
    }
}
```

**Returns:** `SnapshotStateMap<G, SnapshotStateList<T>>`

**All sources must share the same group key type `G`** (e.g., all group by `Long` customer ID). The secondary index name can differ per source, but the extracted value type must be the same.

### Group-list instance stability

`asSnapshotStateMapBySecondaryKey`, `asSnapshotStateMapWithJoins`, and `mergedSnapshotStateMap` all return `SnapshotStateMap<G, SnapshotStateList<…>>`. For all three, **a group's `SnapshotStateList` is a stable instance for as long as that group is non-empty.** Internal updates — including the full rebuilds that fire on collector re-subscription (a `LocalLifecycleOwner` STARTED transition, e.g. returning to a screen or app foreground) — reconcile the list's *contents* in place; they do **not** replace the list object.

This guarantee is what makes the common consumer pattern safe: read a group's list once and pass it down, even capturing it in a `remember`/`derivedStateOf`.

```kotlin
// Safe: `items` stays the live, self-updating instance across data changes AND rebuilds.
val items = activityByCustomer[customerId] ?: return
CustomerColumn(items = items)

@Composable
fun CustomerColumn(items: SnapshotStateList<ActivityItem>) {
    // Safe: this binds to the stable instance; later inserts into the group are observed here.
    val visible by remember { derivedStateOf { items.filter { it.isVisible } } }
}
```

> If the per-group instance were swapped on rebuild instead, an unkeyed `remember { derivedStateOf { …items… } }` like the one above would silently keep reading the orphaned old list — the classic "new row doesn't appear until I leave the screen and come back" bug. Reconciling in place is what prevents it.

Reactivity stays granular: only groups whose contents actually changed recompose; unchanged groups are skipped.

Two behaviors to know:
- A group with no items has **no map entry** — `map[key]` is `null` until the first item arrives, and the key is removed again when the group empties. Read `map[key] ?: emptyList()`.
- If you hold a reference to a group's list and that group later empties, the key is removed from the map and your held reference becomes a detached, empty list. Re-read `map[key]` (don't cache it past an empty transition) to observe the group reappearing.

### Pre-filtering for performance

Both `MergeSource` and `GroupedMergeSource` accept an optional `filter` parameter — a plain (non-suspend, no JoinContext) function that runs **before** the expensive `project` call. Use it to cheaply reject entries based on their own fields:

```kotlin
GroupedMergeSource(
    es = Data.orders,
    groupKey = "customerFk",
    filter = { it.value.sequence >= visibleSequenceCutoff },  // fast rejection
    project = { entry, ctx ->
        // Only runs for entries that pass filter
        val agent = ctx.fetch(Data.users, entry.value.agentId)
        ActivityItem.Order(entry.value, agent)
    }
)
```

This is useful when source collections contain large amounts of historical data but only a time window is displayed. Entries rejected by `filter` skip the projection entirely and are not tracked for join dependencies. The `project` function can still return null for filtering that depends on joined data.

### asState — Single entry reactive state

```kotlin
@Composable
fun UserProfile(userId: String) {
    val user by Data.users.asState(userId)
    Text(user?.name ?: "Loading...")
}
```

### Choosing the right pattern

| Scenario | Function |
|---|---|
| Simple list with filter/sort | `asSnapshotStateList()` |
| List filtered by secondary index | `asSnapshotStateListBySecondaryKey()` |
| Parent-child (one collection) | `asSnapshotStateMapBySecondaryKey()` |
| Multi-collection joins (flat list) | `asSnapshotStateListWithJoins()` |
| Multi-collection joins (grouped) | `asSnapshotStateMapWithJoins()` |
| Merge N collections into one list | `mergedSnapshotStateList()` |
| Merge N collections, grouped | `mergedSnapshotStateMap()` |
| Single entry display | `asState()` |

### Helper functions

**rememberSuspendTransformed** — transform a value using suspend functions:
```kotlin
@Composable
fun CustomerLabel(customerId: Long) {
    val name by rememberSuspendTransformed(customerId) {
        Data.customers.get(it)?.name
    }
    Text(name ?: "...")
}
```

**rememberTransformed** — synchronous transformation:
```kotlin
@Composable
fun FormattedPrice(price: Double) {
    val formatted by rememberTransformed(price) { "$%.2f".format(it) }
    Text(formatted)
}
```

## Parameter stability in Compose

Lambda and comparator arguments passed to OkTopoi's Compose integration functions are automatically memoized by the OkTopoi compiler plugin. The plugin analyzes each argument's captures — variables it reads from the enclosing composable scope — and uses them as cache keys. The memoized value is invalidated and the lambda recreated only when those captures change.

Write lambdas inline without `remember` wrappers:

```kotlin
// `searchQuery` and `selectedStatus` are captured automatically and used as cache keys
val items = Data.items.asSnapshotStateList(
    entryComparator = compareBy { it.value.name },
    filter = { entry ->
        val item = entry.value
        (searchQuery.isEmpty() || item.name.contains(searchQuery, ignoreCase = true)) &&
        (selectedStatus == null || item.status == selectedStatus)
    }
)
```

This applies to all lambda and comparator parameters on `asSnapshotStateList`, `asSnapshotStateListBySecondaryKey`, `asSnapshotStateMapBySecondaryKey`, `asSnapshotStateListWithJoins`, `asSnapshotStateMapWithJoins`, `asSnapshotStateMapTransformed`, `mapState`, `mergedSnapshotStateList`, `mergedSnapshotStateMap`, `rememberTransformed`, and `rememberSuspendTransformed`.

## Schema Evolution

OkTopoi uses a JSON configuration optimized for forward/backward compatibility of persisted data.

**Safe DTO changes (won't break persistence):**
- Add field with default: `val newField: String = "default"`
- Add nullable field: `val newField: String? = null`
- Remove field (automatically ignored on read)
- Change default value (only affects entries without explicit values)
- Change nullable to non-nullable with default (null coerced to default)
- Remove enum value (coerced to default enum value)

**Breaking changes (trigger error archiving):**
- Add required field without default: `val newField: String`
- Change field type: `Int` to `String`
- Rename field (use `@SerialName("oldName")` annotation instead)

## Error Archiving

When deserialization fails, OkTopoi automatically:
1. Moves the corrupted file to `.oktopoi-errors/` with a timestamp
2. Logs detailed error information
3. Continues execution (falls back to default for single values, skips entry for collections)
4. Preserves files for manual investigation/recovery

This prevents app crashes from corrupted persistence files while keeping debugging evidence.

## Things to know

Behaviour worth knowing before you persist anything you can't afford to lose.

### Storage location follows the declaration

A persistent value is stored at `rootDir/<class name>/<property name>` — a file for `ep`/`esp`,
a directory for `eps`/`esps`. The compiler plugin derives both names from where the value is
declared, and there is currently no way to override them. So:

- **Renaming a property, or the class or object that declares it, points it at a new, empty
  location.** The old data stays on disk but is no longer read, and the default comes back.
  Rename deliberately, or move the files alongside the change.
- **The class part is the simple name**, without the package. Two classes with the same simple
  name share a location, and properties declared outside any class all land in `Unknown/`. Keep
  the names of state-holding classes unique.

### Single-value writes block the caller

Assigning `.value` on `ep` or `esp` writes the file before it returns. Avoid doing it in a hot
path on the main thread. Collection operations are suspend functions and don't block.

### Writes are ordered, not atomic

Persistence comes first: a write that fails with an exception never reaches memory. But files
are overwritten in place (only [startup compaction](#startup-compaction-large-collections-on-slow-io)
writes through a temp file and a rename), so a crash or process kill *during* a write can leave
that file truncated. On the next start it fails to decode and is handled as in
[Error Archiving](#error-archiving): a single value returns to its default, a collection entry is
skipped.

There is also no atomicity across entries. Several `put`s are persisted one by one, so a crash
between them leaves some applied and some not.

### How sync resolves conflicts

Conflicts are resolved per entry, by timestamp: the newer one wins, and the value is replaced
whole — there is no field-level merge. For `esps`:

- no local edit pending → the incoming change is applied;
- a pending local edit at least as new → the incoming change is ignored, and the local edit
  stays queued to be sent (a pending deletion counts as an edit made when it was deleted);
- an incoming change newer than the pending local edit → the incoming change is applied **and
  the local edit is discarded without being sent.**

Local edits are stamped with the device clock; incoming timestamps are whatever your
`incomingSync` supplies, typically server time. Clock skew between the two therefore affects the
outcome. This model fits data with one writer per entry; if two clients can edit the same entry
concurrently, resolve that in your adapter or on the server.

Your own writes are judged by value, not by timestamp. When `outgoingSync` returns, the entry is
marked synced only if it still holds the value that was sent; if it was edited while the write was
in flight, the newer edit stays queued. If your backend returns the stored row (for example with
server-computed fields), apply it with `fromSyncIfUnchanged(key, sent, value, timestamp)` from
inside `outgoingSync` rather than `fromSync`: it applies only while the entry still holds `sent`,
so clock skew can neither discard the response nor let it overwrite a newer edit.

A write that fails with an error your `isSyncErrorRetryable` classifies as non-retryable is a
separate case (by default every error is retried): the entry is rolled back
to its last confirmed value (or removed, if it was never confirmed), the failed write is archived
under `.oktopoi-sync-errors/`, and the protected `onSyncErrorArchived` hook fires so a subclass
can surface it. The same by-value rule applies: if the entry was edited again while the rejected
write was in flight, nothing is rolled back — the newer edit gets its own attempt — and only the
archive record is written.

## Startup Compaction (large collections on slow IO)

`Eps`/`Esps` persist each entry as a separate file. This is fast and simple at small scale, but on filesystems where per-file overhead is high (cloud-mounted drives, antivirus per-file scanning), loading tens of thousands of entries at startup can be slow — opening 60k files is 60k syscalls and 60k AV checks.

To address this, OkTopoi maintains an optional compacted `_snapshot.bin` file alongside the per-entry files. On startup, loading proceeds in three phases:

1. Read `_snapshot.bin` if present (one sequential read).
2. Apply per-entry files written since the snapshot (they override snapshot entries).
3. Apply tombstones (`.tomb` files) for deletions made after the snapshot.

Per-entry files and tombstones accumulate during normal operation; compaction folds them back into a fresh snapshot so the next startup reads a single file.

### Automatic compaction

When `setup()` finishes loading, if there are ≥ 300 stale per-entry files + tombstones, OkTopoi automatically compacts before returning. This is self-healing: after the first cold load of an existing data directory, subsequent boots are fast.

### Manual compaction

Call `eps.compact()` at quiet moments (e.g. app shutdown, after large bulk imports) to fold the current state into a fresh snapshot immediately:

```kotlin
suspend fun onShutdown() {
    users.compact()
}
```

`compact()` holds the write lock for its duration. Crash-safe: a partial compaction simply means the next load reads both the snapshot and the leftover per-entry files, which produces the same final state.

### Disk layout

```
/app/data/UserRepository/users/
  _snapshot.bin           # compacted entries (optional)
  emp042                  # per-entry write since last compaction (file name = the key)
  emp077.tomb             # 0-byte tombstone for a deletion since last compaction
```

The format is internal — applications should not read or write these files directly.
