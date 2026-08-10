# Changelog

All notable changes to the Dacite reference implementation are documented here.
The project follows [Semantic Versioning](https://semver.org/) with an **alpha**
stability promise: public APIs may still change before 1.0.

## Unreleased

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
