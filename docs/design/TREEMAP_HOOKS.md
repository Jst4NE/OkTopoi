# TreeMap hook architecture

How `Es`, `Eps` and `Esps` attach behaviour (change emission, persistence, sync) to map
mutations, and why those hooks live where they do.

## The convergence point

`TreeMap` exposes a wide surface: `put`, `remove`, `clear`, but also compound operations
like `compute`, `merge` and `putIfAbsent`, plus mutation through the `keys`/`values`/`entries`
views and their iterators. Every one of them ultimately funnels into three unsafe
primitives on `UnsafeTreeMapCore`:

```
putUnsafe · removeUnsafe · clearUnsafe
```

Subclasses could have overridden each public method instead. That was the original design,
and it had two problems: a compound operation like `compute` would either miss the override
or run the remapping function twice, and every new operation added to `TreeMap` was another
method three subclasses had to remember to override.

Hooking the unsafe primitives instead means one mechanism catches everything. A new compound
operation inherits change emission, persistence and sync for free, because it cannot mutate
the tree without going through them.

## The hooks

Defined in `TreeMap`, no-ops or index maintenance by default:

```kotlin
protected open fun onBeforePutUnsafe(key: K, newValue: V)
protected open fun onAfterPutUnsafe(key: K, newValue: V, oldValue: V?)
protected open fun onBeforeRemoveUnsafe(key: K)
protected open fun onAfterRemoveUnsafe(key: K, oldValue: V)
protected open fun onBeforeClearUnsafe()
protected open fun onAfterClearUnsafe()
```

`TreeMap` makes the unsafe methods `final` and wraps each primitive:

```kotlin
final override fun putUnsafe(key: K, value: V): V? {
    onBeforePutUnsafe(key, value)               // persistence (Eps)
    val oldValue = super.putUnsafe(key, value)  // memory update (UnsafeTreeMapCore)
    onAfterPutUnsafe(key, value, oldValue)      // secondary indexes + change emission
    return oldValue
}
```

The ordering is load-bearing and must not change: **before hooks → memory update → after hooks.**

## Who overrides what

| Layer | Hooks | Purpose |
|-------|-------|---------|
| `TreeMap` | after | secondary index maintenance |
| `Es` | after | emit `Put` / `Removed` / `Cleared` changes |
| `Eps` | before | persist to disk *before* memory changes |
| `Esps` | inherits both | sync metadata rides on `Eps.persistEntry` |

`Es` calls `super` first so index maintenance happens before subscribers observe the change:

```kotlin
override fun onAfterPutUnsafe(key: KeyType, newValue: ValueType, oldValue: ValueType?) {
    super.onAfterPutUnsafe(key, newValue, oldValue)
    emitChange(Put(key, newValue, oldValue != null, oldValue))
}
```

`Eps` persists in the *before* hook, so a failed write aborts the operation and memory is
never ahead of disk:

```kotlin
override fun onBeforePutUnsafe(key: KeyType, newValue: ValueType) {
    if (persistenceLoadDepth > 0) return   // don't re-write what we are loading
    try {
        persistEntry(key, newValue)
    } catch (e: Exception) {
        throw PersistenceFailedException("Failed to persist put($key): ${e.message}", e)
    }
}
```

## Design decisions

**Before hooks take only the new value.** Persistence never needs the previous value, and
requiring it would force an extra `getUnsafe()` lookup on every write. After hooks receive
`oldValue` for free, since the unsafe operation returns it.

**Persistence goes in before hooks, emission in after hooks.** Persistence-first is what
makes a crash safe: if the write throws, the in-memory tree is untouched. Emission has the
opposite requirement — subscribers must only see changes that actually happened.

**Secondary indexes are maintained by `TreeMap`'s own after hooks,** not by a special case
inside the unsafe methods. One mechanism, applied consistently, including for `TreeMap`'s
own responsibilities.

**Hooks live in `TreeMap`, not `UnsafeTreeMapCore`.** `UnsafeTreeMapCore` stays pure
red-black tree logic with no knowledge of persistence or reactivity, and `TreeMap` is both
the extensibility layer and the place where locking happens — so hooks run under the write
lock that guards the mutation.

**Compound operations get no special handling.** `compute`, `merge` and friends work purely
by delegation to the primitives.

## Rules when extending

1. Always call `super` in a hook, or you silently drop the behaviour of every layer beneath you.
2. Never reorder before/memory/after.
3. A before hook may throw to abort the operation; an after hook must not.
4. Mutating the map from inside a hook re-enters the primitives — don't.
5. Route new mutation paths through the unsafe primitives rather than touching
   `UnsafeTreeMapCore` directly. `TreeIterator.remove` once called `deleteNode()` directly and
   orphaned every secondary index entry for the removed key.

## Historical note

The hooks were introduced in September 2025 for a different reason than the one above: the
compiler plugin then rewrote `TreeMap` calls in suspend contexts onto a `map.suspend.*` view,
and that rewrite bypassed subclass method overrides. Hooking the primitives fixed it, because
the view still had to go through them.

That suspend-view mechanism is gone — the map's operations are plain `suspend` functions now,
and `TreeMapSuspendTransformer` was removed in November 2025. The hook architecture outlived
its original motivation because the convergence-point argument stands on its own.
