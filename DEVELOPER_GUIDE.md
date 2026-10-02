# OkTopoi Developer Guide

**Target Audience:** Developers joining the OkTopoi project

## Table of Contents
- [Project Overview](#project-overview)
- [Architecture Overview](#architecture-overview)
- [Core Components](#core-components)
- [Build System](#build-system)
- [Development Workflow](#development-workflow)
- [Performance Characteristics](#performance-characteristics)
- [Platform Support](#platform-support)
- [Contributing Guidelines](#contributing-guidelines)

## Project Overview

OkTopoi is a **Kotlin Multiplatform state management library** with advanced compiler plugin support and persistence capabilities. It provides reactive state management with optional file persistence and bi-directional synchronization.

### Key Features
- 🔄 **Reactive State Management**: Observable state containers with change flows
- 💾 **File Persistence**: Automatic state persistence with kotlinx-serialization
- 🔀 **Bi-directional Sync**: Real-time synchronization between instances
- 🗺️ **Advanced Collections**: Red-black tree with O(log n) operations and secondary indexing
- ⚡ **Compiler Optimizations**: K2 compiler plugin for performance enhancements
- 🌐 **Multiplatform**: JVM, Android, iOS, Linux x64, and WebAssembly support

### Project Structure
```
OkTopoi/
├── okTopoi/                    # Core multiplatform library
│   ├── src/commonMain/         # Shared Kotlin code
│   ├── src/jvmMain/           # JVM-specific implementations
│   ├── src/iosMain/           # iOS-specific implementations
│   └── src/wasmJsMain/        # WebAssembly-specific implementations
├── compiler-plugin/           # Kotlin K2 compiler plugin
├── gradle-plugin/            # Gradle plugin for compiler integration
└── docs/                     # Documentation and guides
```

## Architecture Overview

### Module Architecture
```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   gradle-plugin │    │  compiler-plugin │    │     okTopoi     │
│                 │    │                  │    │   (core lib)    │
│  Gradle integration │    │ K2 IR transforms │    │ State management│
└─────────────────┘    └──────────────────┘    └─────────────────┘
         │                        │                        │
         └──────────── Build-time integration ─────────────┘
```

### State Management Hierarchy

OkTopoi follows a clean inheritance-based architecture with single responsibility:

#### Individual State (E/Ep/Esp)
```
E (observable state)
  ↓ extends
Ep (+ file persistence)  
  ↓ extends
Esp (+ bi-directional sync)
```

#### Collections (TreeMap/Es/Eps/Esps)
```
UnsafeTreeMapCore (red-black tree algorithms)
  ↓ extends
TreeMap (+ thread safety + secondary indexing)
  ↓ extends  
Es (+ reactive change flows)
  ↓ extends
Eps (+ file persistence)
  ↓ extends
Esps (+ bi-directional sync)
```

## Core Components

### 1. State Containers

#### E (Observable State)
```kotlin
val counter = e(0)  // Create observable state
counter.value = 42  // Update state
counter.collect { value -> println("New value: $value") }  // Observe changes
```

**Features:**
- Implements `MutableStateFlow<T>`
- Reactive updates via Kotlin coroutines Flow
- Lightweight with no persistence overhead

#### Ep (Persisted State)
```kotlin
val settings = ep<UserSettings>(defaultValue = { UserSettings() })
settings.value = newSettings  // Automatically persisted to file
```

**Features:**
- Extends `E` with file persistence
- Uses kotlinx-serialization for encoding/decoding
- Automatic background persistence with `persistCoroutineScope`
- Platform-specific file system abstraction

#### Esp (Sync-enabled Persisted State)
```kotlin
val syncedData = esp<GameState>(
    incomingSync = incomingFlow,
    outgoingSync = { key, value, timestamp -> syncToServer(key, value, timestamp) }
)
```

**Features:**
- Extends `Ep` with bi-directional synchronization
- Timestamp-based conflict resolution
- Automatic sync triggering on state changes

### 2. Collections (TreeMap/Es)

#### TreeMap (Thread-Safe Red-Black Tree)
```kotlin
val userMap = TreeMap<String, User>()
// All TreeMap operations are suspend functions
suspend fun example() {
    userMap.put("john", User("John", "john@example.com"))
    val user = userMap.get("john")  // O(log n) lookup
}
```

**Architecture Pattern:** `suspend→lock→unsafe`

TreeMap uses a layered approach:
1. **Public API** - All methods are suspend functions
2. **Lock Layer** - ReadWriteLock for thread safety using `rwLock.withReadLock/WriteLock`
3. **Unsafe Core** - Thread-unsafe red-black tree algorithms in `UnsafeTreeMapCore`

```kotlin
// All public TreeMap operations are suspend functions
override suspend fun get(key: K): V? =
    rwLock.withReadLock { getUnsafe(key) }

override suspend fun put(key: K, value: V): V? =
    rwLock.withWriteLock { putUnsafe(key, value) }

// Unsafe core (thread-unsafe algorithms)
class UnsafeTreeMapCore {
    fun getUnsafe(key: K): V? { /* red-black tree implementation */ }
    fun putUnsafe(key: K, value: V): V? { /* red-black tree implementation */ }
}
```

**Important:** All TreeMap/Es/Eps/Esps operations are suspend functions and must be called from a coroutine context. Only E/Ep/Esp state containers have synchronous `.value` property access.

#### Secondary Indexing
```kotlin
val userMap = TreeMap<String, User> {
    key("email") { user -> user.email }
    key("department") { user -> user.department }
    key("level") { user -> user.level }
}

// All queries are suspend functions
suspend fun example() {
    // O(log n) secondary key lookup
    val user = userMap.getBy("email" to "john@example.com")

    // Multi-criteria filtering
    val seniorEngineers = userMap.getBy(
        "department" to "Engineering",
        "level" to "Senior"
    )
}
```

#### Es (Reactive Collections)
```kotlin
val users = es<String, User> { user -> user.id }

// Reactive UI integration
val userList = users.asSnapshotStateList()

// Secondary key filtering
val engineersList = users.asSnapshotStateListBySecondaryKey(
    "department" to "Engineering"
)
```

### 3. Compose Integration

OkTopoi provides extensive Compose integration through reactive functions that convert Es collections into Compose-compatible state objects. These functions enable efficient UI updates with granular reactivity.

#### Core Compose Functions

**`asSnapshotStateList()`** - Convert to reactive list
```kotlin
@Composable
fun UserList() {
    val users = Data.users.asSnapshotStateList(
        entryComparator = compareBy { it.value.name },
        filter = remember { { it.value.isActive } }
    )

    LazyColumn {
        items(users, key = { it.key }) { entry ->
            UserCard(user = entry.value)
        }
    }
}
```

**`asSnapshotStateListBySecondaryKey()`** - Filtered reactive list
```kotlin
@Composable
fun EngineersList() {
    val engineers = Data.employees.asSnapshotStateListBySecondaryKey(
        "department" to "Engineering",
        "level" to "Senior",
        entryComparator = compareBy { it.value.name }
    )
}
```

**`asSnapshotStateMapBySecondaryKey()`** - Grouped reactive map
```kotlin
@Composable
fun OrderItemsScreen() {
    val itemsByOrder = Data.orderItems.asSnapshotStateMapBySecondaryKey<Long>(
        groupByKey = "orderId",
        entryComparator = compareBy { it.value.lineNumber }
    )

    // Only affected order cards recompose
    orders.forEach { order ->
        val items = itemsByOrder[order.id] ?: emptyList()
        OrderCard(order, items)
    }
}
```

**`asState(key)`** - Single entry reactive state
```kotlin
@Composable
fun UserCard(userId: String) {
    val user by Data.users.asState(userId)
    Text(user?.name ?: "Unknown")
}
```

**`mapState(key)`** - Transform single entry
```kotlin
@Composable
fun UserDisplayName(userId: String) {
    val displayName by Data.users.mapState(userId) { user ->
        user?.let { "${it.firstName} ${it.lastName}" }
    }
    Text(displayName ?: "Unknown")
}
```

#### Advanced Join Functions

**`asSnapshotStateListWithJoins()`** - Multi-collection reactive joins
```kotlin
@Composable
fun ShipmentsList() {
    val shipments = Data.shipments.asSnapshotStateListWithJoins<ShipmentDto>(
        entryComparator = compareBy { it.value.timestamp },
        filterMap = remember(timeRange) {
            { shipmentEntry, fetch ->
                val shipment = shipmentEntry.value

                // Automatic dependency tracking
                val orderItem = fetch(Data.orderItems, shipment.id) ?: return@filterMap null
                val order = fetch(Data.orders, orderItem.orderId) ?: return@filterMap null
                val customer = fetch(Data.customers, shipment.customerId) ?: return@filterMap null

                // Filters
                if (order.placedAt !in timeRange) return@filterMap null

                // Build DTO
                ShipmentDto(order, orderItem, customer, shipment)
            }
        }
    )
}
```

**`asSnapshotStateMapWithJoins()`** - Grouped joins with granular reactivity
```kotlin
@Composable
fun OrdersWithItems() {
    val itemsByOrder = Data.orderItems.asSnapshotStateMapWithJoins<Long, ItemWithProduct>(
        groupByKey = { it.item.orderId },
        entryComparator = compareBy { it.value.item.lineNumber },
        filterMap = remember {
            { itemEntry, fetch ->
                val item = itemEntry.value
                val product = fetch(Data.products, item.productId) ?: return@filterMap null
                ItemWithProduct(item, product)
            }
        }
    )
}
```

#### Helper Transformation Functions

**`rememberTransformed()`** - Synchronous transformation
```kotlin
@Composable
fun SupplierCard(supplier: SupplierDto) {
    val formattedName by rememberTransformed(supplier) {
        it.name.uppercase()
    }
    Text(formattedName)
}
```

**`rememberSuspendTransformed()`** - Async transformation
```kotlin
@Composable
fun SupplierCard(supplier: SupplierDto) {
    val displayName by rememberSuspendTransformed(supplier) {
        it.getDisplayName()  // suspend function
    }
    Text(displayName ?: supplier.name)
}
```

#### Key Features

- **Granular Reactivity**: Only affected UI components recompose when data changes
- **Lifecycle Awareness**: Automatically handles lifecycle states (defaults to STARTED)
- **Efficient Updates**: O(log n) insertions/removals using binary search
- **Multi-source Joins**: Automatic dependency tracking across multiple Es collections
- **Parameter Stability**: Use `remember {}` to wrap unstable parameters

#### Snapshot and subscription order

Every view built on `changes` must **subscribe before it reads its snapshot**:
`changes.onSubscription { emit(Rebuild()) }`, or a collector launched with
`CoroutineStart.UNDISPATCHED` before the build. Reading first and subscribing second loses any
change written in between, until the next rebuild.

Subscribing first means a change written *during* the snapshot read is both queued and possibly
already in the snapshot. Views that re-read the current value by key (joins, merged lists,
`asState`, `asSnapshotStateMapTransformed`, the `Esps` sync-state flows) absorb that for free.
The sorted views (`asSnapshotStateList`, `asSnapshotStateMapBySecondaryKey`) apply a change by
trusting its `oldValue`, which would duplicate a row, so they bracket the read with the change
counter (`SnapshotWindow`): every emitted change carries an internal `MapChange.seq`, assigned
under the write lock after the mutation. Changes numbered at or below the counter read before
the snapshot are skipped, those numbered within the read are applied by key (an O(n) scan, but
the window is short), and later ones take the normal O(log n) path.

Join dependencies follow the same rule, with the read before the subscription by necessity: a
view learns which collections an entry depends on only by running its `filterMap`. `JoinContext`
therefore reports each collection's change counter, read before its first fetch from it, to the
`JoinDependencyTracker`, which subscribes at once (woken by that report, no polling) and, once
registered, compares the counter: if it moved, it hands on a `Rebuild` of that collection so its
dependents re-evaluate. Affected entries are resolved where the view serializes evaluation (the
join's mutation lock, the merged view's event loop), so an evaluation still running — its
dependencies are recorded when it finishes — completes first and is found.

#### Performance Characteristics

- **asSnapshotStateList**: O(n) initial, O(log n) per update
- **asSnapshotStateListBySecondaryKey**: O(log n) initial lookup, O(log n) per update
- **asSnapshotStateMapBySecondaryKey**: O(n) initial, O(log n) per group update
- **asSnapshotStateListWithJoins**: O(n) initial, O(k) updates where k = affected entries
- **asState**: O(log n) fetch, O(1) per update

### 4. Compiler Plugin

The OkTopoi compiler plugin (OktopoiIrGenerationExtension) applies IR transformations:

#### A. OktopoiTransformer
Injects metadata for E/Es instance detection using the `@OktopoiNewInstanceFactory` annotation. This allows the library to identify instances created via factory functions.

#### B. RunBlockingMultiplatform Inlining
Inlines platform-specific blocking calls for better performance:
```kotlin
// Source code
runBlockingMultiplatform {
    delay(100)
    doWork()
}

// On JVM/Android/iOS (inlined to)
runBlocking {
    delay(100)
    doWork()
}

// On WASM-JS (preserves custom implementation)
runBlockingMultiplatform {  // Custom single-threaded implementation
    delay(100)
    doWork()
}
```

#### Platform Detection
Uses type-safe Kotlin compiler APIs:
```kotlin
private fun isWasmJsPlatform(): Boolean {
    val platform = pluginContext.platform ?: return false
    
    return try {
        platform.componentPlatforms.any { component ->
            when {
                component is WasmPlatform -> true
                JsPlatforms.allJsPlatforms.any { it == component } -> true
                else -> false
            }
        }
    } catch (_: Exception) {
        false  // Conservative fallback
    }
}
```

## Build System

### Build Commands
```bash
# Build all modules
./gradlew build

# Build specific modules
./gradlew :okTopoi:build
./gradlew :compiler-plugin:build
./gradlew :gradle-plugin:build

# Assemble without tests
./gradlew assemble

# Test multiplatform builds
./gradlew :okTopoi:compileKotlinJvm
./gradlew :okTopoi:compileKotlinWasmJs
./gradlew :okTopoi:iosX64MainKlibrary
```

### Debug Logging Tag

TreeMap includes logging for error reporting and debugging:

- Tag: "OkTopoi-TreeMap"
- Used primarily for error reporting (e.g., missing secondary index definitions)

Check these logs if you encounter unexpected behavior or errors.

### Dependency Configuration
```kotlin
// In your build.gradle.kts
plugins {
    id("jst.oktopoi.gradle-plugin") version "1.0.0"  // Enables compiler plugin
}

dependencies {
    implementation("jst.oktopoi:oktopoi:1.0.0")
}
```

### Gradle Plugin Integration
The gradle plugin automatically:
- Applies the compiler plugin to Kotlin compilations
- Configures multiplatform support
- Sets up proper dependency resolution

## Development Workflow

### Setup and Initialization

**Required Setup for Persistent State:**
Before using any persistent functions (`ep`, `eps`, `esps`, `esp`), you must initialize storage locations using one of these functions:

#### `initDefaultIO()` - Single Storage Location
```kotlin
initDefaultIO(defaultRootDir: Path, fileSystem: FileSystem = SystemFileSystem)
```
- **Use when:** All persistent state uses the same root directory
- **Call once:** In your application's main function or initialization block
- **Effect:** Sets the default storage location for all persistent functions without explicit `rootDir`

#### `initRootDirIO()` - Multiple Storage Locations  
```kotlin
initRootDirIO(initRootDir: Path, fileSystem: FileSystem = SystemFileSystem)
```
- **Use when:** You need different root directories for different data types
- **Call multiple times:** Once per unique rootDir/fileSystem combination
- **Effect:** Enables using specific `rootDir` parameters in persistent functions

#### Platform-Specific Setup Examples
```kotlin
// JVM Development
initDefaultIO(Path("./dev-data"))

// JVM Production  
initDefaultIO(Path("/opt/myapp/data"))

// Android
initDefaultIO(Path(context.getExternalFilesDir(null)!!.absolutePath))

// iOS
initDefaultIO(Path(NSDocumentDirectory(), "MyApp"))

// Linux Server
initDefaultIO(Path("/var/lib/myapp"))

// Multiple locations example
initDefaultIO(Path("/app/data"))           // For general data
initRootDirIO(Path("/app/cache"))          // For cache data  
initRootDirIO(Path("/secure/credentials")) // For sensitive data
```

#### Error Handling and Troubleshooting
- **Missing initialization:** If you use persistent functions without calling init functions, `setup()` will suspend indefinitely waiting for initialization
- **File permissions:** Ensure the application has read/write permissions to the specified directories
- **Directory creation:** OkTopoi will automatically create directories as needed
- **FileSystem selection:** Use `SystemFileSystem` for real file I/O, or provide custom implementations for testing

### Code Style Guidelines
- **Naming Conventions**: Follow Kotlin conventions (camelCase, descriptive names)
- **API Design**: Keep public APIs minimal and intuitive (e.g., `e()`, `ep()`, `es()`)
- **Documentation**: Use KDoc for public APIs
- **Error Handling**: Fail-fast approach with clear error messages
- **Thread Safety**: Follow `suspend→lock→unsafe` pattern for TreeMap operations

## Performance Characteristics

### Time Complexities
| Operation | TreeMap | Secondary Index | Reactive Updates |
|-----------|---------|-----------------|------------------|
| Get/Put/Remove | O(log n) | O(log n) | O(log n) |
| Secondary key lookup | O(log n) | O(1) | O(log n) |
| Multi-criteria filter | O(k log k) | O(k) | O(k log k) |
| Iteration | O(n) | O(n) | O(n) |

### Space Complexities
- **TreeMap storage**: O(n)
- **Secondary indexes**: O(n) per index
- **Reactive lists**: O(n) for snapshots

### Optimizations Implemented
- **Single lock operations**: Bulk operations use single lock acquisition
- **Efficient reactive updates**: Binary search for list insertions/removals
- **Compiler optimizations**: Suspend context detection and platform-specific inlining
- **Memory efficient**: No unnecessary object allocations in hot paths

## Platform Support

### Supported Platforms
- **JVM**: Full feature support including file I/O and coroutines
- **Android**: Full support with Android-specific optimizations
- **iOS**: Native iOS support (x64, ARM64, Simulator ARM64)
- **Linux x64**: Native Linux support
- **WebAssembly (WASM-JS)**: Web support with custom threading model

### Platform-Specific Features
- **File System**: Uses kotlinx-io for cross-platform file operations
- **Concurrency**: Platform-appropriate coroutine dispatchers
- **Serialization**: kotlinx-serialization for all platforms
- **Performance**: Platform-specific compiler optimizations

### Expect/Actual Declarations
```kotlin
// Common
expect fun platformSpecificOperation(): String

// JVM implementation
actual fun platformSpecificOperation(): String = "JVM implementation"

// JS implementation  
actual fun platformSpecificOperation(): String = "JS implementation"
```

## Contributing Guidelines

### Pull Request Process
1. **Fork** the repository
2. **Create** feature branch from `main`
3. **Implement** changes following code style guidelines
4. **Add documentation** for new functionality
5. **Run** build verification: `./gradlew build`
6. **Submit** pull request with clear description

### Code Review Criteria
- ✅ **Functionality**: Does it work as intended?
- ✅ **Performance**: Maintains O(log n) characteristics?
- ✅ **Thread Safety**: Follows established patterns?
- ✅ **Multiplatform**: Works on all supported platforms?
- ✅ **Documentation**: Public APIs are documented?

### Architecture Decisions
When making significant changes, consider:
- **Performance impact**: Profile changes with realistic data sizes
- **Multiplatform compatibility**: Verify behavior across all target platforms
- **Memory usage**: Avoid introducing memory leaks
- **Error handling**: Maintain fail-fast philosophy

## Common Development Patterns

### Adding New State Container Features
```kotlin
// 1. Extend base class
open class MyCustomState<T>(
    // ... parameters
) : E<T>(observing, defaultValue) {
    
    // 2. Override state-changing methods
    override var value: T?
        set(newValue) {
            // Custom logic before state change
            super.value = newValue
            // Custom logic after state change
        }
}

// 3. Provide factory function
inline fun <reified T> myCustomState(): MyCustomState<T> = MyCustomState(...)
```

### Adding TreeMap Operations

When adding new operations to TreeMap, follow this pattern:

```kotlin
// 1. Add unsafe implementation to UnsafeTreeMapCore
fun someOperationUnsafe(param: P): R {
    // Thread-unsafe algorithm implementation
    // No locking - assumes caller holds appropriate lock
}

// 2. Add suspend public API method to TreeMap
suspend fun someOperation(param: P): R =
    rwLock.withReadLock { someOperationUnsafe(param) }
    // Use withWriteLock for mutations
```

**Key principles:**
- Unsafe methods in `UnsafeTreeMapCore` have no locking
- Public API methods in `TreeMap` are always suspend functions
- Wrap unsafe calls with appropriate lock (`withReadLock` or `withWriteLock`)
- Use hooks (`onBeforePutUnsafe`, `onAfterPutUnsafe`, etc.) for extensions

---

For usage, see the [user guide](docs/USER_GUIDE.md); for why the collection hooks are shaped the
way they are, see [TreeMap hooks](docs/design/TREEMAP_HOOKS.md).
