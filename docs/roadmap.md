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
| Read a scalar or short string field | `v/native` / `v/str` (names TBD) | Pulled by remote config |
| Nested document edit | `get-in` / `assoc-in` / `update` / `update-in` | Pulled by config + notes |
| Show a page of a large vector | Slice / range that stays on Dacite values | Pulled by event log |
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
- [ ] Tutorials written from real apps (starts with remote config)

### Examples today

| App | What it proves | What it does not |
|---|---|---|
| Hello nbb | Constructors, `realize`, hash identity | Persistence, sync, a user |
| [config.clj](../examples/config.clj) | Tiny mem snippet | Remote swap (its own stated goal); no `-main` |
| [cards.clj](../examples/cards.clj) | LMDB root, `peek`/`pop`/`conj` | Multi-player, history, anything large |
| Todo CLI | Durable file root, Values/Store split | Scale, sync, two writers |
| Browser todo | HTTP + write-back + CAS + bandwidth | Async I/O, two clients, partial load |

Library-pain already visible in those apps (fix in the library, not with
more helpers):

- `title-str` / `field-native` — ~20 lines to read a string field
- Cards shuffle dumps to a host vector
- No `get-in` / `assoc-in` / `update` / `update-in`
- Browser todo commits at the hash/CAS level, not `v/root-ref`
- Sync XHR blocks the main thread

---

## Application sequence

Do not start the next app until the current one is usable from the README.

### 1. Remote config — finish the advertised use case

**Claim:** the server publishes a root hash; clients pull only what they
need. Same domain against mem, file, and HTTP.

Build what [config.clj](../examples/config.clj) already describes: a small
config map (`theme`, `timeout`, nested `features`) with:

- a CLI that reads/writes a local file-rooted store
- the same commands against `clojure -M:service`
- a second process that picks up a new root (poll is enough)

**Pulls, if the API is honest:**

- `v/root-ref` over a remote store
- field access that is not `title-str` (`v/native` / `v/str`, names TBD)
- `v/get-in` / `v/assoc-in` for nested keys
- a documented “open local vs open remote” recipe

**Done when:** one domain namespace, two store wirings, and a test that the
same config hash is visible on both sides. If that takes more than a thin
store adapter, the public API is not ready.

This is the smallest app that can fail the thesis.

### 2. Versioned notes — prove snapshots and sharing

**Claim:** every root hash is a complete snapshot; unchanged subtrees are
free.

A notes app (CLI first, optional web):

- documents as maps (`title`, `body`, `tags`, `edited-at`)
- a **history** of previous root hashes (or `prev` on the state map)
- show / restore / diff two versions
- enough edits that sharing is measurable (same body, changed title → tiny
  new nodes)

**Pulls:**

- path updates (`get-in` / `assoc-in` / `update-in`)
- string/blob ergonomics for bodies longer than a todo title
- a history *recipe* (the library should not invent git; the app shows the
  pattern)
- REPL/`print` that does not dump an entire finger tree (already started
  in `dacite.value.render`)

**Done when:** you can restore version N without rewriting the document, and
a bench shows later versions add little new store data when only one field
changed.

This is the “why not just overwrite a JSON file” answer.

### 3. Event log + derived view — prove append and lazy read

**Claim:** large sequences stay cheap to append; you need not realize the
whole log.

Append-only vector of events, plus a materialized snapshot at the root:

```text
{"log" [...events...], "view" <derived state>}
```

Seed **thousands** of events, not five. UI / CLI pages the log. Replay
rebuilds the view from a prefix hash.

**Pulls:**

- sequential performance at real size
- pagination without `v/seq` of the entire vector
- maybe `concat` / `into` if you ever compact
- honest, catchable errors when a node is not local

**Done when:** appending is independent of log length in the app’s
measurements, and listing page 1 does not fetch the whole log over HTTP.

This is the first app that can make pack budget and lazy `realize` *felt*.

### 4. Two-client live — prove CAS and watches

**Claim:** compare-and-set is the whole distributed update story.

Take notes or todo. Two clients (two browser tabs, or CLI + browser) on one
service root:

- local edit → CAS → retry with rebase
- the other client notices the new root without a manual reload

**Pulls:**

- remote root watches (SSE — sketched in [service.md](design/service.md))
- async browser store (sync XHR cannot survive this)
- a value-level rebase helper: read current, apply domain fn, CAS, loop
- conflict UX that is not “last write wins silently”

**Done when:** two writers can interleave edits without lost updates, and a
watch updates the other UI.

Do not build SSE or async XHR before this app exists. They have no user
until then.

### 5. Directory / blob sync — prove “pull only what you use”

**Claim:** trees of blobs + maps sync cheaper than a whole-tree copy.

A directory mirrored as `{"name" → file|dir}`, files as blobs:

- `put` a tree, `get` one file, list a folder without fetching file bodies
- a second machine / second store receives only missing nodes
- a bandwidth line like the browser todo demo

**Pulls:**

- a real blob ingest path (bytes in, Dacite blob out)
- the deferred **content-sync helper** and likely **opaque-byte store entries**
- partial availability as a user-visible state (“folder listed, file not
  fetched”)
- progress / missing-node errors at the value layer

**Done when:** cloning a tree of many files transfers far less than tar, and
opening one file does not pull siblings.

This is last because it is the app that justifies remaining store-layer
work. Let it pull that work.

---

## Library work the apps will pull

Treat this as a backlog that **appears**, not a build order.

**Pulled by config + notes**

| Gap | Why |
|---|---|
| `v/str` / `v/native` (names TBD) | Stop writing `title-str` |
| `get-in` / `assoc-in` / `update` / `update-in` | Nested documents are unwritable otherwise |
| Remote `root-ref` | Domain must not drop to hashes for HTTP |

**Pulled by the event log**

| Gap | Why |
|---|---|
| Range / slice on vectors | Pagination without realizing the log |
| Catchable missing-node errors | Partial availability is currently an exception from `s-get` |

**Pulled by two clients**

| Gap | Why |
|---|---|
| Async remote store | Browser must not block the page |
| SSE (or a documented poll helper) | Watches across the network |
| Value-level CAS retry that rebases | `ref-swap!` is local; remote needs the same loop |

**Pulled by file sync**

| Gap | Why |
|---|---|
| Blob ingest / export | Files |
| Copy reachable subgraph | `push-ref` without a sync helper is incomplete |
| Opaque-byte store bodies | File store as EDN is a host-local dead end for blobs |

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
Remote config (same domain, file + HTTP)
        → pull: field access, get-in/assoc-in, remote root-ref
Versioned notes (history + restore)
        → pull: path updates, string/blob UX, print
        → first tutorial that is not Hello World
Event log at real size
        → pull: slice/pagination, missing-node errors
        → first time pack + lazy realize matter
Two-client notes or todo
        → pull: async browser, SSE, rebase loop
Directory/blob sync
        → pull: content-sync helper, opaque bytes
```

---

## Related

- [The Dacite Book](book/) — conceptual layers
- [Values API](book/reference/values.md) / [Stores API](book/reference/stores.md)
- [stores-phase-2.md](design/stores-phase-2.md) — shipped store/transport work
- [service.md](design/service.md) — HTTP MVP; SSE still future
- [value-realization-and-computation.md](design/value-realization-and-computation.md)
  — `realize`, partial availability, bounded print
