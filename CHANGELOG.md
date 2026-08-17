# Changelog

All notable changes to the Dacite reference implementation are documented here.
The project follows [Semantic Versioning](https://semver.org/) with an **alpha**
stability promise: public APIs may still change before 1.0.

## Unreleased

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
- **`v/ref-swap-info!`** — CAS-retry with `{:value :retries}`
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
- **`v/native`**, **`v/as-str`**, **`v/pr-str`**, **`v/get-in`**,
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
- **`*-via` constructors** — `(v/vector-via peer …)` allocates in the peer’s store
  (peer = Dacite value, root-ref, or `IStore`)
- **Value-level root-ref** — `(v/root-ref rooted)` with `ref-deref` / `ref-swap!` /
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
