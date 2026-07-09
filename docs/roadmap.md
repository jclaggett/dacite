# Dacite Roadmap

*Last updated: 2026-07-08*

## Current Direction

**Architecture:** Dacite service provides **dedicated stores per user** (not a shared multi-user store). User isolation by architecture, not convention. Authorization and sharing chapters are archived; see [book/archive/](book/archive/).

**Value model:** Store-aware values — every Dacite value carries its store and hash. Constructors persist into a bound store (`dacite.store/*store*` or `-with-store` variants).

## Completed

### Value layer (Chapter 3)

- [x] Store-aware value protocol (`IDaciteValue`, `realize`, `dacite-hash`)
- [x] Scalars, strings, blobs, vectors, maps, sets
- [x] Finger-tree and HAMT internal nodes with `child-hashes` for graph walks
- [x] `dac->clj` / `clj->dac` conversion
- [x] Lazy deep `realize` for partial availability

### Content stores (Chapter 1)

- [x] `IStore` protocol (`s-get`, `s-put`, `s-has?`, `s-snapshot`, `s-merge`, `s-reset`, `s-delete`)
- [x] Mem, file, LMDB stores
- [x] Layered store with read-through backfill
- [x] LMDB `Closeable` for `with-open`

### Hash fusion (Chapter 2)

- [x] `dacite.hash` — fuse, byte table, low-entropy rejection

### Rooted stores (Chapter 4) — Phase 1 done

- [x] `dacite.rooted` — `RootedStore`, `IRootCell`, CAS, watches, validators
- [x] `mem-root-cell`, `lmdb-root-cell`
- [x] `push-ref` sync primitive
- [x] Examples: [cards.clj](../examples/cards.clj) (LMDB), [config.clj](../examples/config.clj) (mem)

See [design/stores-phase-1.md](design/stores-phase-1.md).

### Documentation

- [x] Book chapters 1–4 (content stores, hash, values, rooted stores)
- [x] Archived superseded auth/sharing chapters

## In Progress — Phase 2

See [design/stores-phase-2.md](design/stores-phase-2.md).

| Item | Status |
|------|--------|
| Doc reconciliation | Done |
| Value-aware GC | Done |
| Service design doc | Done |
| Remote `IStore` (HTTP) | Done |
| LRU cache store | Done |
| Service MVP rewrite | Planned |
| Content sync helper (copy reachable subgraph) | Planned |
| Root slot in content map | Planned |
| True opaque-byte storage in stores | Planned |

## Store protocol — remaining

- [ ] Configurable layered write policies (`:push-all`, `:top-only`)
- [ ] Remote root watches (SSE/WebSocket) — transport-specific

## Serialization

- [x] `dacite.value.serial` binary format (spec v0.4.0-draft)
- [ ] Wire format aligned with remote store HTTP API
- [ ] Guarded walk for bulk export/import
- [ ] Spec update to v0.5

## Cloud service

**Not needed (eliminated by dedicated stores):**

- [x] ~~Proof of Possession~~
- [x] ~~Authorization layer~~
- [x] ~~Sharing mechanisms~~

**Need:**

- [ ] User account management
- [ ] Store provisioning (one store per user)
- [ ] Authentication (Bearer token / mTLS)
- [ ] Store persistence/backup on server

See [design/service.md](design/service.md).

## Examples (future)

- [ ] Event log (append-only vector)
- [ ] Version-controlled document
- [ ] Config management with remote store
- [ ] File sync (directory tree as maps/blobs)

## Long horizon

- User types ([design/future/user-types.md](design/future/user-types.md)) — design only
- Negative sets (cofinite)
- Sorted map/set (comparator-as-data)
- Benchmarks, richer REPL printing, `IReduce`

## Test coverage (2026-07-08)

318 tests, 1285 assertions, 0 failures (`clojure -M:dev:test` from `impl/clojure`).

## Suggested order

```
Phase 2: GC + remote store + LRU → service MVP → content sync → root slot
         → opaque bytes → spec v0.5
```
