# Dacite Roadmap

## 1. Core Data Structures

**Have:** scalar types, strings (finger tree of chars), vectors (finger tree of element hashes), maps (HAMT), blobs (finger tree of bytes)

- [x] **Blob** — finger tree of raw bytes, parallel to string. `d/blob`, `dac->clj`/`clj->dac` support, `size-bytes`
- [x] **Set** — sets are maps where key equals value (`{x x}`). No new type needed. Content addressing means zero storage overhead for the duplicate reference. Convenience functions (`hash-set`, `union`, `intersect`, `diff`, `negate`) go in a utility namespace, not core.
  - **Negative sets**: a `neg` sentinel element inverts set membership, enabling cofinite sets (e.g. blacklists). `{neg 1 2 3}` = "everything except 1, 2, 3". Pure convention, not enforced by core.
- [x] **Refs purged from collections** — collection nodes now store only `{:root h :size-bytes n}`. No flat element vectors. Finger tree `tree-nth` provides O(log n) indexed access via measures.
- [ ] **Sorted map** — deferred. Requires a way to represent comparator functions as data so peers can agree on ordering. Finger tree B-tree machinery exists; the open problem is purely about key comparison.
- [ ] **Sorted set** — same dependency (sorted map where key = value)

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

**Have:** `dacite.serial` namespace with binary serialize/deserialize for all node types.

- [x] **Binary format** — canonical byte encoding per spec v0.4.0-draft
  - Kind 0x00: Scalars (tag + u8 len + canonical bytes)
  - Kind 0x01: Seq nodes (finger tree internals — empty, single, digit, node, deep)
  - Kind 0x02: Map nodes (HAMT internals — empty, entry, bitmap)
  - Kind 0x03: Collections (vector, string, blob, map — 42-byte fixed headers)
- [x] **Scalar encoding** — `encode-value` multimethod with canonical big-endian/IEEE 754/UTF-8
- [ ] **Remote store** — `IStore` implementation backed by a network endpoint; lazy `s-get` fetches nodes on demand. This is the primary transfer mechanism — no bulk walks, just cache-on-access through store layering.
- [ ] **Guarded walk** — explicit opt-in transitive closure walk with safety bounds (depth limit, byte budget, hash count cap). For small-value export/import and debugging. Not the default path.
- [ ] **Content negotiation** — different representations for different transports

## 4. Documentation & Spec

**Have:** spec v0.4.0-draft (with collection serialization), development dialogue, README

**Need:**
- [ ] **API docs** — codox or cljdoc for the public API
- [ ] **Spec update to v0.5** — reflect strings-as-finger-trees, size-bytes, blob type, set type, refs removal
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

- [ ] **Memoize scalar constructors** — includes singleton hashes for `null`, `true`, `false`
- [ ] **Benchmarks** — construction, lookup, `dac->clj`, `size-bytes` at various scales
- [ ] **Print methods** — custom `print-method` for Dacite types so REPL output is readable
- [ ] **Error messages** — better errors when store misses occur (hash not found)
- [ ] **`IReduce` / `IKVReduce`** — remaining Clojure interfaces for efficient reduction

## Test Coverage (as of 2026-02-25)

298+ tests, 1350+ assertions, 0 failures.

| Namespace          | Forms  | Lines  |
|--------------------|--------|--------|
| dacite.core        | ~98%   | 100%   |
| dacite.finger-tree | 100%   | 100%   |
| dacite.hamt        | ~96%   | ~99%   |
| dacite.hash        | ~99%   | ~99%   |
| dacite.serial      | —      | —      |
| dacite.store       | ~88%   | 100%   |
| dacite.types       | 100%   | 100%   |
| **ALL FILES**      | **96.23%** | **99.55%** |

## Suggested Order

```
remote store → set utilities → examples
```

Serialization of individual nodes is done. The next architectural unlock is a **remote store** — an `IStore` backed by a network endpoint, composed via `LayeredStore` for transparent lazy fetching with local caching. Dacite values can be arbitrarily large (larger than any single store), so the default transfer model is lazy node-at-a-time access, not bulk graph walks. Exhaustive walks are an explicit opt-in operation with safety bounds. Set utility functions (`union`, `intersect`, `negate`, etc.) can come whenever — they're pure library code on top of maps.
