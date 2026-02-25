# Dacite Roadmap

## 1. Core Data Structures

**Have:** scalar types, strings (finger tree of chars), vectors (finger tree of element hashes), maps (HAMT), blobs (finger tree of bytes)

- [x] **Blob** — finger tree of raw bytes, parallel to string. `d/blob`, `dac->clj`/`clj->dac` support, `size-bytes`
- [x] **Set** — sets are maps where key equals value (`{x x}`). No new type needed. Content addressing means zero storage overhead for the duplicate reference. Convenience functions (`hash-set`, `union`, `intersect`, `diff`, `negate`) go in a utility namespace, not core.
  - **Negative sets**: a `neg` sentinel element inverts set membership, enabling cofinite sets (e.g. blacklists). `{neg 1 2 3}` = "everything except 1, 2, 3". Pure convention, not enforced by core.
- [ ] **Sorted map** — B-tree or red-black tree backed, ordered by key hash. `d/sorted-map`, supports `subseq`, `rsubseq`
- [ ] **Sorted set** — same backing (sorted map where key = value)
- [ ] **Nil/unit cleanup** — should `null` be a singleton hash?

## 2. Store Protocol

**Have:** `IStore` protocol with `s-get`, `s-put`, `s-has?`, `s-snapshot`, `s-merge`, `s-reset`. Three implementations.

- [x] **IStore protocol** — minimal interface in `dacite.store`
- [x] **Mem store** — atom-backed in-memory store (default)
- [x] **File store** — content-addressed filesystem with directory sharding (like git objects)
- [x] **Layered store** — compose stores with read-through; writes go to all layers
- [ ] **LRU cache store** — bounded memory with eviction, backed by a slower store
- [ ] **Read-through / write-through policies** — configurable per layer
- [ ] **Lazy fetch** — types that resolve their data on access, not on construction

## 3. Serialization

**Have:** nothing — values only exist in-memory Clojure maps or EDN on disk

**Need:**
- [ ] **Wire format** — binary serialization of `[type data]` tuples for network/disk
- [ ] **Import/export** — serialize a value + its transitive closure from the store
- [ ] **Partial transfer** — "I have these hashes, send me what I'm missing"
- [ ] **Content negotiation** — different representations for different transports

## 4. Documentation & Spec

**Have:** spec v0.4.0-draft, development dialogue, README

**Need:**
- [ ] **API docs** — codox or cljdoc for the public API
- [ ] **Spec update to v0.5** — reflect strings-as-finger-trees, size-bytes, blob type, set type
- [ ] **Architecture guide** — the store layering story, "hashes as pointers" mental model
- [ ] **Tutorial** — build something real with Dacite step by step

## 5. Example Use Cases

Concrete things to build that prove the architecture:
- [ ] **Version-controlled document** — a map that tracks its own history via hash chains
- [ ] **CRDT-style collaboration** — two peers editing the same structure, merging via content addressing
- [ ] **File sync** — represent a directory tree as nested maps/blobs, sync between stores
- [ ] **Event log** — append-only vector where each entry references the previous hash (blockchain-lite)
- [ ] **Config management** — nested maps with diff/merge operations

## 6. Performance & Polish

- [ ] **Memoize scalar constructors**
- [ ] **Benchmarks** — construction, lookup, `dac->clj`, `size-bytes` at various scales
- [ ] **Print methods** — custom `print-method` for Dacite types so REPL output is readable
- [ ] **Error messages** — better errors when store misses occur (hash not found)
- [ ] **`IReduce` / `IKVReduce`** — remaining Clojure interfaces for efficient reduction

## Test Coverage (as of 2026-02-24)

293 tests, 1370 assertions, 0 failures.

| Namespace        | Forms  | Lines  |
|------------------|--------|--------|
| dacite.cache     | 99.10% | 100%   |
| dacite.cache-map | 94.59% | 97.44% |
| dacite.core      | 98.46% | 100%   |
| dacite.finger-tree | 100% | 100%   |
| dacite.hamt      | 95.79% | 98.95% |
| dacite.hash      | 99.14% | 98.95% |
| dacite.store     | 87.56% | 100%   |
| dacite.types     | 100%   | 100%   |
| **ALL FILES**    | **97.41%** | **99.64%** |

## Suggested Order

```
serialization → sorted map → set utilities → examples
```

Store protocol, blob, and sets (as maps) are done. Serialization is the next architectural unlock for network/disk transfer. Set utility functions (`union`, `intersect`, `negate`, etc.) can come whenever — they're pure library code on top of maps.
