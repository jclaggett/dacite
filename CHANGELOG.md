# Changelog

All notable changes to the Dacite reference implementation are documented here.
The project follows [Semantic Versioning](https://semver.org/) with an **alpha**
stability promise: public APIs may still change before 1.0.

## Unreleased

### Public API (breaking, alpha)

- One constructor family: `(v/vector ctx …)`, `(v/map ctx …)`, `(v/i64 ctx n)`,
  … — context is a store, `v/root`, or another value. Dropped `*-via`,
  `*-with-store`, and implicit `*store*` constructors.
- `v/type` and `v/hash` replace `v/value-type` and `v/dacite-hash`.
- Root handle: `v/root`, `v/deref`, `v/swap!`, `v/cas!`, `v/swap-info!`,
  `v/add-watch`. Dropped `ref-` prefix and `ref-reset!`.
- Dropped `v/as-str` (use `v/native`). `dac->clj` / `clj->dac` stay on
  `dacite.convert`, not `dacite.value`.
- Application stores are rooted: `s/mem`, `s/file`, `s/remote`. Apps should
  not call `IStore` (`s-get`, `s-snapshot`, …).

### Documentation

- Book inverted for app authors: [The Dacite way](docs/book/the-dacite-way.md)
  and [Anatomy of a Dacite app](docs/book/building/anatomy.md) lead;
  tutorials teach patterns; cookbook; chapters 1–4 plus pack/HTTP are
  How it works / reference. Roadmap (2026-08-31): then hold for a real app.

### Value explorer

- **`dacite.examples.explorer`** — web tree of the current rooted-store
  root. Expand/collapse vector, map, and set; strings/blobs are truncated
  leaves. Empty root CAS-seeds a type gallery; an existing root is
  browsed as-is.
- `GET /app/explorer/` (todo stays at `/app/`). Directories under `/app/`
  serve `index.html`.
- Browser explorer uses the same pack-filled `GET /node` as todo
  (realized literals, write-back cache). Values are walked locally;
  HTTP ships neighborhood *data*.
- Browser ClojureScript does not re-export `v/scalar` / `v/root`
  (they smash the `dacite.value.scalar` / `.root-ref` namespaces). nbb
  and JVM keep the vars. Rooted re-exports on `dacite.store` are stubs
  in the browser (nbb still requires at call time).
- Tutorial: [docs/book/tutorial/explorer.md](docs/book/tutorial/explorer.md)
- `/app` and `/app/explorer` without a trailing slash **301** to the
  slashed URL so relative `js/main.js` is not the todo bundle.

### Pack GET

- Removed `GET /node/{h}?raw=1` (bare node) and `?nodes=1` (node-only
  pack). GET always returns a pack-filled chunk. `pack-under` with
  budget 0 is a single item (the asked hash).
- Removed `GET /node/{h}?near=` and `store/*pack-near*`. Fill is always
  under the asked hash. Long-string expand is one neighborhood GET via
  `run`/`repeat`, not a parent query param.

### Pack literals (leaf-chunking 2f)

- Layer 1 uses cached **`size-bytes`** (not wire-item size) to choose
  `:literal` vs `:node`. Dry-run hash still required. Layer 2 still seals
  on sent bytes with include-then-seal (~2× cap on non-first items).
- Sequence literal bodies collapse contiguous same-type leaves to nested
  **`run`** (`0x50`) and all-equal **`repeat`** (`0x51`). Item type stays
  the store type (`ft/node`, `vector`, …). Char runs are UTF-8; a 24-`x`
  finger-tree node is a `repeat`, not 24 tagged chars.

### Pack fill (leaf-chunking 2e)

- `pack-under` / `pack-items` seal on **sent** bytes (wire-v1 by default),
  not EDN length of hash words. A 1893-char string GET now BFS-fills ~1k
  of FT/char neighborhood instead of stopping after two spine nodes.
- Literal gate is **≤ 1024** wire bytes per item. Include-then-seal can
  still push a chunk toward ~2k. Remaining-budget skip is deferred.
- Bottom-level `ft/node` of 2–32 leaves rematerializes via `make-node!`
  (not conj-right), so a 24-char node is a small literal instead of an
  856-byte `:node`. After a fat `:node` crosses 1k, BFS continues
  **children-first** up to ~2k so a 64-char string prefix fits in one GET.
### Todo web: long titles

- Pack `host-string` no longer uses `apply str` (CLJS apply of a long
  character seq can throw or stop around 52 args).
- `encode-item` falls back to `:node` if building a literal throws.
- Todo UI shows Add/Toggle/Remove errors instead of failing silently.

### HTTP inbound throttle

- **`dacite.service.throttle`** — per-client token bucket + inflight cap
  so one caller cannot starve another. Empty bucket is **429**
  (`Retry-After`), oversized body is **413**, global slot exhaustion is
  **503**. Client key is `Authorization: Bearer` or remote IP.
- `POST /nodes/get` `:budget` and start lists are clamped.
- `remote-store` retries 429/503 and accepts `:token`.
- CLI: `--throttle off`, `--rate`, `--burst`, `--inflight`, `--max-body`,
  `--threads`.

### nbb LMDB store (optional)

- **`dacite.store.nbb.lmdb`** — named DBs `dacite` / `meta`, 32-byte hash
  keys, wire-v1 node payloads. Same on-disk layout as `dacite.store.jvm`.
  Requires `lmdb` built for **data format v1**
  (`LMDB_DATA_V1=true npm rebuild lmdb`). File-store remains the default.
- **`dacite.wire.binary`** — CLJS collection counts decode as JS numbers
  (not BigInt) so nbb can re-encode trees.

### Directory / blob sync

- **`dacite.examples.sync`** — directory tree of maps + blobs. `ls` does
  not realize file bodies; `cat` uses `v/as-bytes`. Identical files share
  a blob hash. `push` / `pull` use `store/sync-reachable!`.
- **`v/as-bytes`** — blob → host bytes; optional limit; `:dacite/missing`
  when the node is not in the store. CLJS returns a vector of 0–255 ints
  (SCI cannot seq a JS array).
- **`dacite.store.sync/sync-reachable!`** — copy a reachable subgraph
  (packed flush to remotes, per-node otherwise). Re-exported on
  `dacite.store`.
- **nbb `file-store` snapshot** — keys by hex so `s-snapshot` does not
  blow up on BigInt hash words.
- Tutorial: [docs/book/tutorial/sync.md](docs/book/tutorial/sync.md)

### Two-client live

- **`GET /events`** — SSE root announcements on `dacite.service`
- **`dacite.store.remote/watch-root`** — JVM SSE client
- **`v/swap-info!`** — CAS-retry with `{:value :retries}`
- Event log **`watch`** and **`contend`** — two remotes, no lost appends
- Tutorial: [docs/book/tutorial/two-client.md](docs/book/tutorial/two-client.md)

### Event log app

- **`dacite.examples.event-log`** — append-only credit/debit log + derived
  view. Page via `v/subvec`; replay via `nth`. Bench shows one append
  adds a handful of nodes at n=100 and n=2000.
- **`v/subvec`** — `[start, end)` as a new vector; leaves shared;
  O(k log n), does not seq the whole vector.
- Tutorial: [docs/book/tutorial/event-log.md](docs/book/tutorial/event-log.md)

### Versioned notes app

- **`dacite.examples.notes`** — notebook `{doc, history}`; restore reuses a
  historical doc hash; `diff` compares field hashes; `bench` shows a
  title-only edit adds fewer store nodes than a body rewrite.
- Tutorial: [docs/book/tutorial/notes.md](docs/book/tutorial/notes.md)

### Remote config app

- **`dacite.examples.config`** — same domain against a file-rooted store
  and `store/remote-rooted-store` (HTTP). Seed with `ref-cas!` from nil;
  CLI: `show` / `get` / `set` / `add-feature` / `watch`.
- **`v/native`**, **`v/native`**, **`v/pr-str`**, **`v/get-in`**,
  **`v/assoc-in`**, **`v/update`**, **`v/update-in`** — field and path ops
  that stay on Dacite values (not `dac->clj`). `as-str` is `native` then
  stringify. `*string-char-limit*` / an optional limit realizes at most
  that many string characters (`native`/`as-str` throw if longer;
  `pr-str` renders `"prefix…" (n chars)`).
- **`ft-seq` flattens to leaves** — a 100-char string now realizes all
  100 characters (overflowed digits can mix leaves and `ft/node` cells;
  seq used to stop at 52).
- **`dacite.rooted/IRoot`** — local `RootedStore` and remote HTTP wrapper
  share `root` / `cas-root!`. Remote `set-root!` / `ref-reset!` throw.
- Tutorial: [docs/book/tutorial/config.md](docs/book/tutorial/config.md)

### Roadmap

- **Application-driven next phase** — [docs/roadmap.md](docs/roadmap.md)
  rewritten around writing apps that prove the thesis and pull library work.
  Sequence: remote config → versioned notes → event log → two-client live →
  directory/blob sync. `dac->clj` is documented as a JVM emergency hatch, not
  a portable API goal; specific “whys” (print, field access, path updates)
  get targeted value utilities instead.

### API cleanup (two public namespaces)

- **Public surface** is now `dacite.value` + `dacite.store` for application code
- **`*-via` constructors** — `(v/vector peer …)` allocates in the peer’s store
  (peer = Dacite value, root-ref, or `IStore`)
- **Value-level root-ref** — `(v/root rooted)` with `ref-deref` / `ref-swap!` /
  `ref-reset!` / watches; JVM also supports `@` / `swap!` / `reset!`
- **`dacite.store`** re-exports rooted ops and common host ctors (`file-store`,
  `lmdb-store` on JVM)
- **Deprecated** as app requires: `dacite.core`, `dacite.value.api` (alias),
  direct use of `dacite.rooted` / `value.scalar` / `value.collections` in apps

## [0.1.0-alpha] — 2026-07-30

First public alpha of the Clojure / SCI (babashka, nbb) reference library.

### Includes

- **Values** — content-addressed scalars, string, blob, vector, map, set
  (prefer `dacite.value`; older `value.api` / `core` remain as compatibility)
- **Stores** — `IStore`, mem, layered, LRU; file (JVM/bb + nbb); LMDB (JVM);
  rooted stores + GC; client cache (write-back); pack (`flush-from!`); rate-limit;
  stats
- **Wire-v1** — chunk-only binary codec (`dacite.wire.binary`, portable `.cljc`),
  fixtures under `fixtures/wire-v1/`, dual EDN/binary HTTP on the service
- **HTTP remote** — JVM and browser clients; pack GET/POST default to wire-v1 binary
- **Examples** — nbb todo CLI, browser todo demo, hash-parity script
- **Docs on dacite.io** — Install, Hello World (nbb), Values/Stores API reference,
  The Dacite Book

### Known limits (alpha)

- API may change before beta/1.0; pin a git tag/sha for experiments
- Browser remote uses **synchronous XHR** (demo only; blocks the main thread)
- Multi-tenant service productization, authz, and other-language ports are out of scope
- Clojars / Maven coordinates are not required for this alpha (git or local root)

### Wire notes

- Pack transport: `application/vnd.dacite.chunk.v1`
- Novelty PUT bodies, `/root`, and CAS remain EDN by design
- Pass `{:binary false}` on remote clients for legacy EDN packs
