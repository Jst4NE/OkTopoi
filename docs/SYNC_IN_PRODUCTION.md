# Sync in production: notes from building on `esps`

OkTopoi's `esps` takes two parameters and deliberately says nothing about what sits behind them:

```kotlin
incomingSync: Flow<Triple<K, V?, Long>>          // changes arriving from elsewhere
outgoingSync: suspend (K, V?, Long) -> Unit      // local changes leaving
```

That boundary is the whole design: the library owns the local store — ordering, indexes,
persistence, change emission — and the adapter owns the network. These are notes from writing one
such adapter, against Postgres with a realtime change feed, for an app that has been in daily
production use on phones and desktops since 2025. Roughly 30 tables, one collection each.

Nothing here is specific to a backend vendor. They are the mistakes that cost the most to find.

## 1. Delivery is not completeness

A realtime change feed is a delivery mechanism, not a completeness guarantee. Postgres change
streams are explicitly best-effort: no replay, no gap detection, no promise that a reconnect
resumes where the socket died.

The first version of the adapter used the feed as its cursor. Each arriving row advanced a
"sync floor", and on restart the floor was re-derived as `max(entry.updated_at)` over the local
store. Both are the same mistake in two disguises.

The failure looks like this. A row is written server-side while the client's socket is blipping,
and its event is dropped. A later row arrives normally and pushes the floor past the gap. The
catch-up query is `updated_at > floor`, so it now starts *above* the missing row. The row is
buried — not late, not retried, permanently invisible — and it survives restarts, because the
floor is re-derived from a local store that never contained it. In the field this presented as
data that existed on the server, was visible to another user, and simply never appeared for one
client until someone wiped local state.

The fix is a rule:

> **Only an authoritative fetch may advance the watermark.** A pushed event may fill the store,
> but it may never certify a window as complete.

Concretely:

- The realtime feed still feeds the store, for latency. It is treated as untrusted — it may drop
  anything with no consequence.
- A separate durable watermark is the floor, persisted on its own (OkTopoi's `sidecar()` exists
  for exactly this: a value that lives in the collection's directory but outside its entry set).
  It is **never** derived from entry timestamps, so a delivered row sitting above a hole cannot
  drag the floor past it on the next restart.
- A periodic reconcile pass is what advances it. Each beat sends the server the ids and versions
  the client already holds above its watermark; the server replies with only what is missing or
  staler, plus tombstones, plus a horizon to certify to. The client applies the diff and moves
  its watermark to that horizon.

Sending the known set rather than re-pulling the window is what keeps the beat cheap: the steady
state transfers almost nothing, and the cost scales with divergence rather than with table size.

The deeper point is that **the store must be able to say which window it knows to be complete**,
and that claim has to come from a query whose result is authoritative, not from the arrival of
events.

## 2. A timestamp is not a cursor

`updated_at = now()` looks like a natural cursor. It isn't, because in Postgres `now()` is the
*transaction start* time while the row becomes visible only at *commit*. A write that begins at
12:00:00 and commits at 12:00:09 carries a timestamp nine seconds in the past at the moment it
appears.

So a client that fetches everything and certifies up to the newest row it saw will bury any
transaction that was in flight during the fetch — its rows land below the advanced watermark,
where nothing looks again. This is the same burial as §1, arriving through a different door.

The repair is to bound every authoritative fetch by a **horizon**: the start time of the oldest
in-flight transaction, minus a margin (falling back to "now minus a second" when the database is
idle). Fetch `updated_at <= horizon`, certify to `horizon`, and leave anything above it for the
next pass. It is self-tuning — close to now when quiet, exactly as conservative as the longest
running write when busy.

A related constraint: a watermark **can only stop between timestamps, never inside one**. If
10,000 rows share a single `updated_at` — which is exactly what one bulk `UPDATE` produces — a
paginated fetch cannot stop halfway through them and record a meaningful floor. Either the whole
group is taken, or the cursor is wrong. This makes "touch rows server-side to push them to
clients" a sharp tool: batch such touches, or a single statement becomes one indivisible group.

## 3. Freeze, don't skip

Migrations reach the whole fleet the moment they are applied. App releases do not: phones update
when they update. So there is always a window where an old build is reading a new schema, and
sooner or later it meets a row it cannot decode — a new enum value, a tightened type, a field it
has no default for.

The instinct is to skip the row and keep going. That is the wrong call, and it took a scare to
see why: **a skipped row is an invisible hole.** The client looks healthy, the watermark advances
past the row, and nothing ever revisits it. The user sees data that is quietly incomplete, which
in an operational app is worse than data that is obviously stale.

The alternative is to freeze. On the first undecodable row:

- Apply the clean prefix and stop. Because the fetch is ordered by `(updated_at, pk)`, the
  watermark remains a valid frontier at the last good row.
- Do not mark initial sync complete, and do not certify any horizon.
- Park the **whole** inbound pipeline, not just that one collection. Mixed generations across
  collections produce cross-table inconsistencies — a child row whose parent is missing — which
  are harder to reason about than a snapshot that is simply old.

Two details make freezing survivable rather than fatal. **Outbound writes keep flowing**, so a
frozen client still uploads what its user does — the freeze belongs to the inbound pipeline, not
to sync as a whole. And **a small config collection stays exempt**, so the server can still tell a
frozen fleet something (including "the schema moved, you must update"). The one pipeline capable
of un-freezing the fleet must never run through the machinery the freeze stops — the app updater
here was eventually moved off the sync engine entirely for that reason.

Nothing about the freeze is persisted. A fixed build decodes the row, never freezes, and the
watermark catches up the whole frozen window on its own.

## 4. A rejected write must not vanish

Not every local change is accepted. A foreign-key violation or a constraint failure is not
retryable — retrying sends the same rejection forever.

The first implementation dropped the offending entry from the local store. That is defensible
(the server is authoritative) and wrong in practice: the server still holds the *previous*
version of that row, so dropping it locally invents a deletion no one asked for. The correct
rollback target is the last value the server confirmed, which means each pending edit has to
carry a snapshot of what it replaced. OkTopoi does this in `Esps` — the snapshot is captured on
the first local edit, first-edit-wins, and costs nothing on disk for synced entries.

Then surface it. A silent revert is a bug report that arrives three weeks later as "it forgot what
I typed". A toast naming the collection and the server's reason is enough to turn it into a
question someone asks immediately.

## 5. Echo suppression is a server-side problem

Every local write comes back through the change feed as an event. Applying it is usually harmless
but wasteful, and if projections are attached to change emission it causes visible flicker.

Filtering by "did I write this?" requires the server to record *who* wrote each row — a
`updated_by_user_id`-style column, set by the same statement — and the client to subscribe with a
`neq` filter on it.

The trap: **SQL comparisons against `NULL` are not false, they are unknown**, so a `neq` filter
drops rows whose author column is null — which is every row written by a server-side process,
trigger or scheduled job. Those are exactly the rows a client most needs. Either set a sentinel
author for machine writes, or exempt the table from echo filtering; the one thing that doesn't
work is assuming the filter behaves like Kotlin's `!=`.

## 6. Cutoffs fit streams, not state

Syncing everything forever is not viable on a phone, so collections get a retention floor: only
fetch rows newer than N days. This works beautifully for event-shaped data, where a row is born at
its event and written near event time.

It fails silently for **state-shaped** data, whose rows stay authoritative without ever being
rewritten. A row recording "this equipment is currently assigned here", written four months ago
and never touched since, is still true — and falls outside every rolling window. The symptom was a
screen that showed "nothing assigned" while the server held a perfectly good assignment.

The question to ask before giving any collection a cutoff: *can a row be old and still current?*
If yes, it is state, not stream, and it syncs in full.

## What belongs where

After all of this, the division of labour that held up:

| Concern | Owner |
|---|---|
| Ordering, indexes, local queries | OkTopoi (`Es`) |
| Durability, crash-safety, snapshot/compaction | OkTopoi (`Eps`) |
| Pending-write tracking, rollback snapshots, change emission | OkTopoi (`Esps`) |
| Which window is known complete, and what advances it | the adapter |
| Schema skew and what to do when decoding fails | the adapter |
| Retryable vs terminal errors, echo suppression, retention | the adapter |

The library's job is to make the local store trustworthy. Everything above is about making the
*claim of completeness* trustworthy, and that claim can only be made by whoever talks to the
server.
