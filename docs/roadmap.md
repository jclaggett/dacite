# Dacite Roadmap

## Current Direction (2026-06-05)

**Architecture shift:** Dacite service provides **dedicated stores per user** (not a shared multi-user store). This eliminates the need for Proof of Possession, authorization, and sharing layers entirely. User isolation by architecture, not convention.

**Value model shift:** All Dacite Values now explicitly carry their Store. Constructors take store as first parameter and return `[store' hash]`. Values are effectively `[store hash]` pairs — you cannot operate on a value without knowing which store it lives in. This is analogous to how in-memory data structures implicitly have access to RAM.

## 0. Value Model Rework (IN PROGRESS)

Reimplementing values with store-awareness and type information.

**Status:**
- [x] Core observation: values need stores (like RAM for data structures)
- [x] `value2/` namespace tree started (`primitive.clj`, `scalar.clj`, `types.clj`)
- [ ] **Type information design** — how should types be captured? (current topic)
- [ ] Seq primitive (finger tree of hashes) — needed for `[type-hash data-hash]` tuples
- [ ] Map primitive (HAMT of hash→hash)
- [ ] Port collection types to new model
- [ ] Port `dac->clj` / `clj->dac` conversion
- [ ] Port test suite

## 1. Core Data Structures

**Have (in old model):** scalar types, strings, vectors, maps, blobs, sets

**Need (in new model):**
- [ ] All collection types rebuilt on `value2` primitives
- [ ] `size-bytes` via finger tree measures (port from old model)
- [ ] Set as `{x x}` maps (port from old model)
- [ ] Negative sets (cofinite sets via `neg` sentinel)

**Explicitly not implementing:**
- [x] ~~Sorted map/set~~ — requires comparator-as-data; complexity disproportionate to value

## 2. Store Protocol

**Have:** `IStore` protocol with `s-get`, `s-put`, `s-has?`, `s-snapshot`, `s-merge`, `s-reset`

**Status:**
- [x] IStore protocol
- [x] Mem store
- [x] File store (content-addressed filesystem with directory sharding)
- [x] Layered store (compose with read-through; writes to all layers)
- [ ] **LRU cache store** — bounded memory with eviction
- [ ] **Remote store** — `IStore` backed by network endpoint. Primary transfer mechanism for cloud service. Lazy `s-get` fetches on demand via store layering.
- [ ] **Read-through / write-through policies** — configurable per layer

**For cloud service:** Remote store is the key unlock. Each user gets a dedicated store on the server, accessed via remote `IStore` implementation.

## 3. Serialization

**Have:** `dacite.serial` with binary format for all node types (spec v0.4.0-draft)

**Status:**
- [x] Binary format (scalars, seq nodes, map nodes, collections)
- [x] Scalar encoding
- [ ] **Remote store serialization** — wire format for `s-get`/`s-put` over network
- [ ] **Guarded walk** — explicit opt-in transitive closure with safety bounds (depth, byte budget, hash count). For export/import, not default path.
- [ ] Content negotiation

## 4. Cloud Service

**Goal:** Dacite service providing dedicated cloud-based Dacite Stores.

**Not needed (architecturally eliminated):**
- [x] ~~Proof of Possession~~ — no shared store, no need to prove key ownership
- [x] ~~Authorization layer~~ — dedicated stores per user
- [x] ~~Sharing mechanisms~~ — out of scope for core; handle at higher protocol level

**Need:**
- [ ] User account management
- [ ] Store provisioning (one store per user)
- [ ] Remote store protocol (HTTP/gRPC/WebSocket?)
- [ ] Authentication (who owns this store?)
- [ ] Store persistence/backup

## 5. Documentation & Spec

**Have:** Spec v0.4.0-draft, development dialogue, README, book chapters 1-3

**Need:**
- [ ] **Spec update to v0.5** — reflect new value model (store-aware, type system)
- [ ] **Book chapter rewrite** — chapters 1-3 updated for new model
- [ ] API docs
- [ ] Architecture guide (store layering, "hashes as pointers", dedicated stores)
- [ ] Tutorial

**Archived:**
- [x] ~~Chapters 4 (authorization) & 5 (sharing)~~ — superseded by dedicated store model. See `docs/book/archive/`.

## 6. Example Use Cases

- [ ] Version-controlled document (map tracking history via hash chains)
- [ ] Event log (append-only vector with hash references)
- [ ] Config management (nested maps with diff/merge)
- [ ] File sync (directory tree as nested maps/blobs)

## Open Questions / Future Work

### User Types (Open Type System)

See `docs/design/future/user-types.md` for full vision.

Brief: future open type system where users define types via shape+operation specs,
both stored as Dacite values. Types themselves have hashes. Core primitive types
are well-known entries in the type registry. Requires current type implementation
to use hash-based type identifiers and dispatch-based encoding/decoding.

**Status:** Design only, not scheduled.

### Other Future Work

- [ ] Sorted map/set (requires comparator-as-data)
- [ ] CRDT-style collaboration examples

- [ ] Memoize scalar constructors (singleton hashes for `null`, `true`, `false`)
- [ ] Benchmarks (construction, lookup, `dac->clj`, `size-bytes`)
- [ ] Print methods for readable REPL output
- [ ] Error messages for store misses
- [ ] `IReduce` / `IKVReduce` interfaces

## Test Coverage (as of 2026-03-19)

345 tests, 1413 assertions, 0 failures.

| Namespace          | Forms  | Lines  |
|--------------------|--------|--------|
| dacite.core        | ~98%   | 100%   |
| dacite.finger-tree | 100%   | 100%   |
| dacite.hamt        | ~96%   | ~99%   |
| dacite.hash        | ~99%   | ~99%   |
| dacite.serial      | —      | —      |
| dacite.store       | ~88%   | 100%   |
| dacite.types       | 100%   | 100%   |
| **ALL FILES**      | **96.72%** | **99.55%** |

**Note:** Test numbers are for old model. Will need rebuild for value2.

## Suggested Order

```
type information design → seq/map primitives → port collections →
port tests → remote store → cloud service MVP
```
