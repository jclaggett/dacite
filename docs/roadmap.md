# Dacite Roadmap

*Last updated: 2026-07-25*

## Current Direction

**Architecture:** Dacite service provides **dedicated stores per user** (not a
shared multi-user store). User isolation by architecture, not convention.
Authorization and sharing chapters are archived; see [book/archive/](book/archive/).

**Value model:** Store-aware values — every Dacite value carries its store and
hash. Constructors persist into a bound store (`dacite.store/*store*` or
`-with-store` variants). Collections use finger trees / HAMTs; **leaf elements
are bare value hashes** (no `ft/single` adapter).

**Transport:** Leaf-chunking packs soft-budget chunks of `:node` / `:literal`
items over HTTP. Pack-filled `GET /node/{hex}` is the primary read path; write
novelty reports `:created` / `:exists`. Soft budget default **1024** bytes.

## Completed

### Value layer (Chapter 3)

- [x] Store-aware value protocol (`IDaciteValue`, `realize`, `dacite-hash`)
- [x] Scalars, strings, blobs, vectors, maps, sets
- [x] Finger-tree and HAMT internal nodes with `child-hashes` for graph walks
- [x] Implicit leaf singles (`ft/single` removed — see [ft-single-elision.md](design/ft-single-elision.md))
- [x] `dac->clj` / `clj->dac` conversion
- [x] Lazy deep `realize` for partial availability

### Content stores (Chapter 1)

- [x] `IStore` protocol (`s-get`, `s-put`, `s-has?`, `s-snapshot`, `s-merge`, `s-reset`, `s-delete`)
- [x] Mem, file, LMDB stores
- [x] Layered store with read-through backfill
- [x] LMDB `Closeable` for `with-open`
- [x] LRU cache store
- [x] Remote HTTP store + client-cache policies (`:none`, `:smart-put`, `:write-back`, `:layered`)
- [x] Stats / bandwidth instrumentation (`dacite.store.stats`, todo-bw bench)

### Hash fusion (Chapter 2)

- [x] `dacite.hash` — fuse, byte table, low-entropy rejection

### Rooted stores (Chapter 4) — Phase 1 done

- [x] `dacite.rooted` — `RootedStore`, `IRootCell`, CAS, watches, validators
- [x] `mem-root-cell`, `lmdb-root-cell`
- [x] `push-ref` sync primitive
- [x] Value-aware GC (`dacite.rooted.gc`)
- [x] Examples: [cards.clj](../examples/cards.clj) (LMDB), [config.clj](../examples/config.clj) (mem)

See [design/stores-phase-1.md](design/stores-phase-1.md).

### Stores Phase 2 — done (core)

See [design/stores-phase-2.md](design/stores-phase-2.md).

| Item | Status |
|------|--------|
| Doc reconciliation | Done |
| Value-aware GC | Done |
| Service design doc | Done |
| Remote `IStore` (HTTP) | Done |
| LRU cache store | Done |
| Service MVP rewrite | Done |
| Leaf-chunking 2a–2d (pack encode/decode, budget **1024**) | Done |
| Pack-filled GET / write novelty | Done |
| Browser todo demo (CLJS) | Done |
| Content sync helper (copy reachable subgraph) | Deferred (2.5) |
| Root slot in content map | Deferred (2.5) |
| True opaque-byte storage in stores | Deferred (2.5) |

### Leaf-chunking (transport packing)

Full plan in [design/leaf-chunking.md](design/leaf-chunking.md) — **shipped**.

| Step | Summary |
|------|---------|
| 2a | Soft-budget `:node` chunks over `POST /nodes` |
| 2b / 2b′ | Realized typed literals for value types |
| 2c / 2c′ | Large-value refuse + intermediate FT/HAMT leaf-payload literals |
| 2d | Budget sweep; default **1024** |

Primary read: pack-filled `GET /node/{hex}` (BFS `pack-under`).  
Primary write (write-back): chunked `POST /nodes` with novelty.

### Documentation

- [x] Book chapters 1–4 (content stores, hash, values, rooted stores)
- [x] Archived superseded auth/sharing chapters
- [x] Service + leaf-chunking design docs aligned with implementation

## Next (Phase 2.5 / polish)

| Priority | Item | Notes |
|----------|------|--------|
| High | Binary pack wire (optional codec) | EDN is correct; binary shrinks envelopes |
| Medium | Content sync helper | Copy reachable subgraph before `push-ref` |
| Medium | Remote root watches (SSE) | Design already sketched in service.md |
| Medium | Opaque-byte store entries | Store body as bytes end-to-end |
| Low | Root slot in content map | Special hash for root metadata |
| Low | Configurable layered write policies | `:push-all`, `:top-only` |
| — | Spec v0.5 | Track separately |

## Store protocol — remaining

- [ ] Configurable layered write policies (`:push-all`, `:top-only`)
- [ ] Remote root watches (SSE/WebSocket) — transport-specific

## Serialization

- [x] `dacite.value.serial` binary format (spec v0.4.0-draft)
- [x] HTTP remote path using EDN + pack chunks (MVP)
- [ ] Binary codec for pack envelopes (align with serial)
- [ ] Guarded walk for bulk export/import
- [ ] Spec update to v0.5

## Cloud service

**Not needed (eliminated by dedicated stores):**

- [x] ~~Proof of Possession~~
- [x] ~~Authorization layer~~
- [x] ~~Sharing mechanisms~~

**Need (productization):**

- [ ] User account management
- [ ] Store provisioning (one store per user)
- [ ] Authentication (Bearer token / mTLS) beyond demo
- [ ] Store persistence/backup on server

See [design/service.md](design/service.md).

## Examples

- [x] Cards (LMDB rooted store)
- [x] Config (mem)
- [x] Todo (file / nbb / babashka portable)
- [x] Browser todo (HTTP service + CLJS pack client)
- [ ] Event log (append-only vector)
- [ ] Version-controlled document
- [ ] Config management with remote store
- [ ] File sync (directory tree as maps/blobs)

## Long horizon

- User types ([design/future/user-types.md](design/future/user-types.md)) — design only
- Negative sets (cofinite)
- Sorted map/set (comparator-as-data)
- Richer REPL printing, `IReduce`

## Test coverage (2026-07-25)

376 tests, 1812 assertions, 0 failures (`clojure -M:dev:cov` from `impl/clojure`).

## Suggested order (from here)

```
Phase 2.5: binary pack wire and/or content-sync helper
        → remote root watches (SSE)
        → opaque bytes + root slot
        → spec v0.5
Product: multi-user provisioning + real auth
```
