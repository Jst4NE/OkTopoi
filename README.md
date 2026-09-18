# OkTopoi

A Kotlin Multiplatform state management library: observable state and keyed collections that
persist themselves to disk and sync bidirectionally with a backend, with first-class Compose
integration and a K2 compiler plugin that removes the boilerplate.

```kotlin
val theme = ep<String> { "dark" }           // persistent state — survives restart
val users = esps<String, User> {            // persistent, synced collection
    key("department") { it.department }     // with a secondary index
}

@Composable
fun UserList() {
    val engineers = users.asSnapshotStateListBySecondaryKey("department" to "Engineering")
    LazyColumn { items(engineers) { UserRow(it) } }   // recomposes on local edits and remote sync
}
```

Nothing declares where `theme` is stored: the compiler plugin injects the owning class and
property name at the construction site, so the store path follows the declaration.

Built for, and running in, a commercial logistics app on Android and JVM desktop — around 30
synced collections, offline-first, on hardware that loses connectivity for hours at a time.

## Concepts

Two families. Individual state has synchronous `.value` access; collections are suspend-only.

| | In-memory | + persistence | + sync |
|---|---|---|---|
| **single value** | `e<T>` | `ep<T>` | `esp<T>` |
| **keyed collection** | `es<K, V>` | `eps<K, V>` | `esps<K, V>` |

Collections carry secondary indexes, so a lookup by a non-primary field stays O(log n) instead of
scanning. Compose helpers (`asSnapshotStateList`, `asSnapshotStateMapBySecondaryKey`,
`asSnapshotStateListWithJoins`, `mergedSnapshotStateList`, …) expose them as snapshot state that
recomposes on change, including changes arriving from sync — and a group's list is a stable
instance, so holding a reference to it stays correct across rebuilds.

**[→ User guide](docs/USER_GUIDE.md)** for the full API, setup and Compose patterns.

## Architecture

```
UnsafeTreeMapCore     pure red-black tree, no locking
    ↓ wrapped by
TreeMap               locking + hook points                E       observable value
    ↓                                                      ↓
Es    change emission                                     Ep      + persistence
    ↓                                                      ↓
Eps   + file persistence                                  Esp     + bidirectional sync
    ↓
Esps  + bidirectional sync
```

Persistence is *persistence-first*: an entry is written to disk before memory is updated, so a
failed write leaves no divergence. Each entry is a file, with an optional `_snapshot.bin` overlay
and compaction for collections large enough that per-file overhead dominates startup.

Behaviour attaches through hooks on the three unsafe primitives that every operation converges on,
rather than by overriding the public API — see
**[design/TREEMAP_HOOKS.md](docs/design/TREEMAP_HOOKS.md)**.

Sync is transport-agnostic: the library has no networking, auth or crypto, only an incoming `Flow`
and an outgoing callback you supply. What it takes to put a real backend behind those is written
up in **[Sync in production](docs/SYNC_IN_PRODUCTION.md)** — why a pushed event must never advance
a watermark, why `updated_at` is not a cursor, and why an undecodable row should freeze a sync
rather than be skipped.

### Compiler plugin

- Injects the declaring class and property name into state created by the factory functions, and
  calls `setup()` — this is what lets `ep<String> { "dark" }` know where to persist.
- `@WrapInRemember` — wraps annotated call sites in a `remember` keyed on the arguments, hoisting
  composition-time code so the Compose slot table stays aligned.
- Inlines the multiplatform `runBlocking` shim.

## Platform support

| Target | Notes |
|---|---|
| JVM (17) | full |
| Android (JVM 11) | full |
| iOS arm64 | full; simulator target not enabled |
| Linux x64 | full |
| wasm-js | in-memory only, no persistence |

Built with Kotlin 2.4.10 and Compose Multiplatform 1.11.1.

## Installation

Releases are published to GitHub Packages, which requires a token with `read:packages` for every
download — GitHub does not serve Maven artifacts anonymously, public or not.

```kotlin
// settings.gradle.kts — the plugin and the library both resolve from here
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.github.com/Jst4NE/OkTopoi") {
            credentials {
                username = providers.gradleProperty("github.actor").get()
                password = providers.gradleProperty("github.token").get()
            }
        }
    }
}
// repeat the same maven { … } block in dependencyResolutionManagement.repositories
```

```kotlin
// build.gradle.kts
plugins {
    id("jst.oktopoi") version "1.0.47"      // compiler plugin — required, not optional:
}                                           // persistent primitives need the identity it injects

dependencies {
    implementation("jst.oktopoi:oktopoi:1.0.47")
}
```

`System.getenv("GITHUB_ACTOR"/"GITHUB_TOKEN")` works in place of Gradle properties, and is what CI
wants.

## Documentation

- [User guide](docs/USER_GUIDE.md) — the API, for consumers
- [Sync in production](docs/SYNC_IN_PRODUCTION.md) — lessons from putting a real backend behind
  `incomingSync`/`outgoingSync`
- [Developer guide](DEVELOPER_GUIDE.md) — internals, for working on the library
- [TreeMap hooks](docs/design/TREEMAP_HOOKS.md) — how persistence, sync and change emission attach
- [Proposals](docs/design/proposals/) — designs written up but not built

## Project status

Actively developed, and used daily in production — but shaped by a single consumer, so read it as
a working library rather than a finished product:

- **The API is not stable.** Minor versions change signatures when the consuming app needs
  something different.
- **Automated tests are the open gap.** The original suites were dropped during a large refactor
  of the collection hierarchy and never rebuilt; correctness work happens against the consuming
  app and through the fixes recorded in the commit history.

## License

Apache 2.0 — see [LICENSE](LICENSE).
