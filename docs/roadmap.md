# Dacite Roadmap

*Last updated: 2026-08-16*

## Current direction

**Write applications.** The four layers (content stores, hash fusion, values,
rooted stores) plus pack transport and a demo HTTP service are an alpha
vertical slice. The next phase is not more store middleware. It is programs
that **prove the claims** and **pull library work from friction**.

The README thesis is still unproven as *utility*:

> structural sharing · fetch only what changed · lazy access · perfect
> caching · versioned snapshots · config push

Shipped examples show that values persist and hashes are stable. They do not
yet show that Dacite is a better way to write a program.

**Architecture (unchanged):** dedicated stores per user, not a shared
multi-user map. Authorization and sharing chapters remain archived; see
[book/archive/](book/archive/).

**Value model (unchanged):** store-aware values; constructors persist into a
bound store; collections are finger trees / HAMTs with bare value hashes at
the leaves.

**Transport (unchanged):** leaf-chunking packs, soft budget **1024**, pack-filled
`GET /node/{hex}`, write novelty `:created` / `:exists`.

---

## Process

One application at a time. Each app must falsify or confirm a Dacite claim.
Library work is **pulled by that app**, not queued in advance.

A good next app has all of:

1. A user who would notice if it broke.
2. A persistence story (local, remote, or both).
3. At least one property a plain atom + EDN file cannot match (history,
   partial fetch, cheap sync, two writers, content identity).
4. Domain code that only requires `dacite.value` and `dacite.store`.
5. A measurement (bandwidth, time-to-first-paint, conflict rate,
   domain-vs-wiring line count).

A bad next app is another walkthrough of `conj` / `assoc` / `ref-swap!` on a
tiny tree.

### Rules of engagement

1. **Public API only.** New app code may require `dacite.value` and
   `dacite.store`. If it needs `dacite.store.pack` or
   `dacite.value.collections`, that is a library hole — promote or wrap, do
   not leak.
2. **No app-local `title-str`.** When domain code grows a twenty-line helper
   to read a field, fix `dacite.value` in the same change.
3. **Keep the Values / Store split.** [todo.cljc](../examples/dacite/examples/todo.cljc)
   already has the architecture. Copy that, not a single-file demo.
4. **Measure the claim.** The bandwidth harness (`dacite.bench.todo-bw`) is
   the pattern. Clone it per app. If you cannot measure sharing, lazy fetch,
   or CAS retries, the app is still a demo.
5. **One app to “usable,” then the next.** Do not scaffold the whole
   sequence. The current app should be runnable from the README.
6. **Write the tutorial from the app.** The book stops at Hello World. After
   each app, add the corresponding tutorial. The book’s job becomes *how to
   write programs*, not another restatement of the four layers.

---

## Stay on the value: no emergency parachute

`dac->clj` (and `clj->dac`) exist on the JVM as a **last-resort hatch**. Their
presence is a smell: they assume a Dacite value can be realized into RAM, which
is not safe for large or partially available data. A size cap does not make
that sound — it only fails a bit earlier.

Do **not**:

- port `dac->clj` to SCI / nbb / the browser
- treat whole-value host conversion as part of the public application API
- fix “I needed a Clojure value” by dumping the tree

Do this instead: name the **why**, then add a Dacite utility that serves that
why without materializing the whole value.

| Why (so far) | Targeted utility | Status |
|---|---|---|
| REPL / `println` / logs | Bounded render (`dacite.value.render`, `toString`, `print-method`) | Shipped — the model |
| Read a scalar or short string field | `v/native` / `v/as-str` (optional char limit; `as-str` via `native`) | Shipped (pulled by remote config) |
| Bounded print of long strings | `v/pr-str` (truncates; never dumps the tree) | Shipped (pulled by remote config) |
| Nested document edit | `get-in` / `assoc-in` / `update` / `update-in` | Shipped (pulled by remote config) |
| Show a page of a large vector | `v/subvec` | Shipped (pulled by event log) |
| Export / interop (JSON, files) | A *specific* encoder that streams or bounds | Only if an app needs it |

`realize` stays: scalars yield a host atom; collections yield a **lazy** seq of
realized elements. Consuming the whole seq is an explicit full-traversal, not
a convenience API. `dac->clj` remains in `dacite.convert` for tests and
emergencies. Application code that reaches for it has found a hole in
`dacite.value`.

---

## Where we are (alpha 0.1)

### Value layer (Chapter 3)

- [x] Store-aware value protocol (`IDaciteValue`, `realize`, `dacite-hash`)
- [x] Scalars, strings, blobs, vectors, maps, sets
- [x] Finger-tree and HAMT internal nodes with `child-hashes` for graph walks
- [x] Implicit leaf singles (`ft/single` removed — see [ft-single-elision.md](design/ft-single-elision.md))
- [x] Lazy deep `realize` for partial availability
- [x] Bounded `toString` / REPL print (`dacite.value.render`)
- [x] Public surface `dacite.value` + `dacite.store`; `*-via` constructors;
      value-level `root-ref`
- [x] `dac->clj` / `clj->dac` — JVM hatch only; not a goal (see above)

### Content stores (Chapter 1)

- [x] `IStore` protocol (`s-get`, `s-put`, `s-has?`, `s-snapshot`, `s-merge`, `s-reset`, `s-delete`)
- [x] Mem, file, LMDB stores
- [x] Layered store with read-through backfill
- [x] LMDB `Closeable` for `with-open`
- [x] LRU cache store
- [x] Remote HTTP store + client-cache policies (`:none`, `:smart-put`, `:write-back`, `:layered`)
- [x] Stats / bandwidth instrumentation (`dacite.store.stats`, todo-bw bench)
- [x] Pack composition (`flush-from!`, outermost `IChunkTransport`)
- [x] Rate-limit store (`dacite.store.rate-limit`)

### Hash fusion (Chapter 2)

- [x] `dacite.hash` — fuse, byte table, low-entropy rejection

### Rooted stores (Chapter 4)

- [x] `dacite.rooted` — `RootedStore`, `IRootCell`, CAS, watches, validators
- [x] `mem-root-cell`, `lmdb-root-cell`, `file-root-cell`
- [x] `push-ref` sync primitive
- [x] Value-aware GC (`dacite.rooted.gc`)

See [design/stores-phase-1.md](design/stores-phase-1.md) and
[design/stores-phase-2.md](design/stores-phase-2.md).

### Transport

- [x] Leaf-chunking 2a–2d (budget **1024**) — [leaf-chunking.md](design/leaf-chunking.md)
- [x] Wire-v1 binary packs — [spec/wire-v1.md](spec/wire-v1.md)
- [x] HTTP service MVP (`dacite.service`) — [service.md](design/service.md)
- [x] JVM + browser remotes (browser is **sync XHR**, demo only)

### Documentation

- [x] Book chapters 1–4
- [x] Values / Stores API reference
- [x] Hello World (nbb) tutorial
- [x] Remote config tutorial (`docs/book/tutorial/config.md`)
- [x] Versioned notes tutorial (`docs/book/tutorial/notes.md`)
- [x] Event log tutorial (`docs/book/tutorial/event-log.md`)
- [x] Two-client live tutorial (`docs/book/tutorial/two-client.md`)
- [x] Directory/blob sync tutorial (`docs/book/tutorial/sync.md`)

### Examples today

| App | What it proves | What it does not |
|---|---|---|
| Hello nbb | Constructors, `realize`, hash identity | Persistence, sync, a user |
| [config](../examples/dacite/examples/config.cljc) | Same domain on file + HTTP; two clients, same hash | Multi-writer UX, SSE |
| [notes](../examples/dacite/examples/notes.cljc) | Snapshots, restore by hash, title-only sharing | Multi-writer, web UI |
| [event log](../examples/dacite/examples/event_log.cljc) | 2000 events, page via subvec, cheap append | Compact/concat, missing-node UX |
| Two-client live | Interleaved appends + SSE watch | Async browser store |
| [sync](../examples/dacite/examples/sync.cljc) | List without bodies; one-file < clone | Opaque-byte file store |
| [cards.clj](../examples/cards.clj) | LMDB root, `peek`/`pop`/`conj` | Multi-player, history, anything large |
| Todo CLI | Durable file root, Values/Store split | Scale, sync, two writers |
| Browser todo | HTTP + write-back + CAS + bandwidth | Async I/O, two clients, partial load |

Library-pain already visible in those apps (fix in the library, not with
more helpers):

- ~~`title-str` / `field-native` — ~20 lines to read a string field~~ (now `v/as-str` / `v/native`)
- Cards shuffle dumps to a host vector
- Browser todo commits at the hash/CAS level, not `v/root-ref`
- Sync XHR blocks the main thread

---

## Application sequence

Do not start the next app until the current one is usable from the README.

### 1. Remote config — done

**Claim:** the server publishes a root hash; clients pull only what they
need. Same domain against mem, file, and HTTP.

Shipped: [config.cljc](../examples/dacite/examples/config.cljc),
`store/remote-rooted-store`, `v/native` / `v/as-str` / `get-in` /
`assoc-in` / `update` / `update-in`, tutorial
[config.md](book/tutorial/config.md). Two HTTP clients see the same
config hash (`dacite.examples.config-test`). Seed uses `ref-cas!` from
nil — `ref-reset!` stays local-only.

### 2. Versioned notes — done

**Claim:** every root hash is a complete snapshot; unchanged subtrees are
free.

Shipped: [notes.cljc](../examples/dacite/examples/notes.cljc), tutorial
[notes.md](book/tutorial/notes.md). Notebook is `{doc, history}`; restore
reinstalls the historical doc value (same hash). `bench` / tests show a
title-only edit shares the body hash and adds fewer store nodes than a
body rewrite. Print uses `v/pr-str`. No new library API — path ops and
bounded string render from config were enough.

### 3. Event log + derived view — done

**Claim:** large sequences stay cheap to append; you need not realize the
whole log.

Shipped: [event_log.cljc](../examples/dacite/examples/event_log.cljc),
`v/subvec`, tutorial [event-log.md](book/tutorial/event-log.md). Seed
2000 credit/debit events. Page uses `subvec`; replay folds with `nth`.
Append node-delta stays small from n=100 to n=2000. A remote test shows
page 0 receives fewer bytes than `seq` of the whole log. Prefix
`subvec` has the same hash as a freshly built prefix.

Missing-node errors stayed on the shelf — page/replay did not need a new
error type.

### 4. Two-client live — done

**Claim:** compare-and-set is the whole distributed update story.

Shipped: `GET /events` (SSE), `store.remote/watch-root`,
`v/ref-swap-info!`, event-log `watch` / `contend`, tutorial
[two-client.md](book/tutorial/two-client.md). Two remotes can interleave
appends without lost events; a third process reprints on SSE. The rebase
loop was already `ref-swap!`; `:retries` is the conflict UX.

Async browser store stayed on the shelf — two JVM remotes plus SSE prove
the claim; sync XHR remains the browser demo.

### 5. Directory / blob sync — done

**Claim:** trees of blobs + maps sync cheaper than a whole-tree copy.

Shipped: [sync.cljc](../examples/dacite/examples/sync.cljc), `v/as-bytes`,
`store/sync-reachable!`, tutorial [sync.md](book/tutorial/sync.md). `ls`
reads `kind`/`size` only; `cat` realizes one blob. Identical files share
a hash. A second local sync copies 0 nodes. Remote GET of one blob
transfers less than GET of that blob plus its siblings. Missing blobs
throw `:dacite/missing`.

Opaque-byte store entries stayed on the shelf — the fetch claim does not
need them.

---

## Library work the apps will pull

Treat this as a backlog that **appears**, not a build order.

**Pulled by config + notes**

| Gap | Why | Status |
|---|---|---|
| `v/as-str` / `v/native` | Stop writing `title-str` | Done (config) |
| `get-in` / `assoc-in` / `update` / `update-in` | Nested documents | Done (config) |
| Remote `root-ref` | Domain must not drop to hashes for HTTP | Done (`IRoot` + `remote-rooted-store`) |

**Pulled by the event log**

| Gap | Why | Status |
|---|---|---|
| Range / slice on vectors | Pagination without realizing the log | Done (`v/subvec`) |
| Catchable missing-node errors | Partial availability is currently an exception from `s-get` | Still deferred |

**Pulled by two clients**

| Gap | Why | Status |
|---|---|---|
| Async remote store | Browser must not block the page | Still deferred |
| SSE (or a documented poll helper) | Watches across the network | Done (`GET /events`) |
| Value-level CAS retry that rebases | `ref-swap!` / `ref-swap-info!` | Done (same loop on remote) |

**Pulled by file sync**

| Gap | Why | Status |
|---|---|---|
| Blob ingest / export | Files | Done (`v/as-bytes`, `blob-via`) |
| Copy reachable subgraph | `push-ref` without a sync helper is incomplete | Done (`sync-reachable!`) |
| Opaque-byte store bodies | File store as EDN is a host-local dead end for blobs | Still deferred |

Infrastructure from the old Phase 2.5 list (pack polish, root slot,
layered write policies, spec v0.5) stays on the shelf until an app above
reaches for it.

---

## Deferred

Do **not** start these until two or three apps exist and you would actually
use one of them.

- Host collection adapters ([design/host-collection-adapters.md](design/host-collection-adapters.md))
  — JVM sugar; they cannot be the portable API (SCI has no adapters).
- User types ([design/future/user-types.md](design/future/user-types.md))
  — design-only; nothing in the app list needs an open type system.
- Multi-tenant accounts, Bearer productization, backup
  ([service.md](design/service.md)).
- Python / C ports. Hash parity on three Clojure hosts is enough until an
  app is worth porting.
- Spec v0.5 as a standalone milestone.
- Negative sets, sorted map/set, richer `IReduce`.

**Not needed (eliminated by dedicated stores):**

- [x] ~~Proof of Possession~~
- [x] ~~Authorization layer~~
- [x] ~~Sharing mechanisms~~

---

## Suggested order (from here)

```
Remote config (same domain, file + HTTP)           ✓
        → pulled: field access, get-in/assoc-in, remote root-ref
Versioned notes (history + restore)                ✓
        → reused: path updates, pr-str; recipe is history vector of docs
Event log at real size                             ✓
        → pulled: v/subvec; append cost stays flat; page < full seq on HTTP
Two-client live                                    ✓
        → pulled: GET /events, watch-root, ref-swap-info!
Directory/blob sync                                ✓
        → pulled: v/as-bytes, sync-reachable!; opaque bytes still deferred
```

---

## Related

- [The Dacite Book](book/) — conceptual layers
- [Values API](book/reference/values.md) / [Stores API](book/reference/stores.md)
- [stores-phase-2.md](design/stores-phase-2.md) — shipped store/transport work
- [service.md](design/service.md) — HTTP MVP; SSE still future
- [value-realization-and-computation.md](design/value-realization-and-computation.md)
  — `realize`, partial availability, bounded print
