# Dacite Roadmap

## 1. Core Data Structures

**Have:** scalar types, strings (finger tree of chars), vectors (finger tree of element hashes), maps (HAMT)

**Need:**
- [ ] **Blob** — finger tree of raw bytes, parallel to string. Use case: binary data, files, images
- [ ] **Set** — HAMT-backed, keys only (no values). `d/hash-set`, supports `conj`, `disj`, `contains?`
- [ ] **Sorted map** — B-tree or red-black tree backed, ordered by key hash. `d/sorted-map`, supports `subseq`, `rsubseq`
- [ ] **Sorted set** — same backing, keys only
- [ ] **Nil/unit cleanup** — should `null` be a singleton hash?

## 2. Store Protocol

**Have:** `*store*` as an atom holding a plain map

**Need:**
- [ ] **IStore protocol** — minimal interface: `fetch`, `store`, maybe `contains?`
- [ ] **Atom store** — current behavior, wrapped in the protocol
- [ ] **Layered store** — compose stores: `(layered-store mem-store disk-store remote-store)`
- [ ] **Disk store** — persistence to local filesystem (content-addressed directory structure like git objects)
- [ ] **LRU cache store** — bounded memory with eviction, backed by a slower store
- [ ] **Read-through / write-through policies** — configurable per layer
- [ ] **Lazy fetch** — types that resolve their data on access, not on construction

## 3. Serialization

**Have:** nothing — values only exist in-memory Clojure maps

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

## Suggested Order

```
blob → store protocol → disk store → set → serialization → sorted map → examples
```

Blob is straightforward (parallel to string). Store protocol is the architectural unlock — everything else builds on it. Disk store proves the protocol works. Sets and serialization open up real use cases.
