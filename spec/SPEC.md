# Dacite Specification

> Data citing with fused hashing.

**Version:** 0.2.0-draft  
**Status:** Early design  
**Last updated:** 2026-02-10

---

## Overview

Dacite is a system for **distributed immutable data structures** with content-addressed nodes. It enables:

- **Structural sharing** — unchanged subtrees share identity across versions
- **Efficient diffs** — compare hashes to sync only what changed
- **Lazy fetching** — clients pull only the data paths they access
- **Perfect caching** — immutable data never invalidates

## Design Principles

1. **Any value can be a key or value** (Clojure philosophy)
2. **Open type system** — new types can be added without a central registry
3. **Content-addressed** — every value has a 256-bit hash identity
4. **Language-agnostic** — spec defines the format, not the implementation

---

## Hash Representation

All hashes are 256-bit values represented as **4 × 64-bit words** (most significant first):

```
hash = [c0, c1, c2, c3]    (4 × i64, big-endian word order)
```

Word `c0` contains the most mixed bits (from fuse) and is used first for HAMT navigation.

---

## Hashing Scheme

### Two Kinds of Hash

Dacite uses two related but distinct hashes:

1. **Structural hash** — the content-address of a specific node in a tree. Computed from the node's type and serialized data (which includes child hashes). Used for storage, caching, and lazy fetching.

2. **Semantic hash** — the identity of a collection's logical contents, independent of tree structure. Two collections with the same elements in the same order have the same semantic hash, even if their internal tree shapes differ.

Structural hashes are computed automatically via `compute-hash([type, data])`. Semantic hashes are defined per collection type (see Collection Types).

### Type Hashes

Types are identified by the SHA-256 hash of their canonical name:

```
type_hash = sha256(type_name)
```

Type names follow the convention `"dacite.core/<name>"` for built-in types. This allows open extension — anyone can define a type without coordination.

### Value Hashes

Every value has a structural hash computed as:

```
value_hash = fuse(type_hash, data_hash)
```

Where `type_hash` is `sha256(type_name)` and `data_hash` is `sha256(canonical_bytes(data))`.

### Fuse Function

Fuse combines two 256-bit hashes using a 4×4 upper triangular matrix over 64-bit cells.

The output is ordered so that the **most mixed bits appear first** (most significant), optimizing for HAMT navigation which uses leading bits:

```
Input:  a = [a0, a1, a2, a3]  (256 bits as 4 × 64-bit words, MSB first)
        b = [b0, b1, b2, b3]

Output: c = [c0, c1, c2, c3]

c0 = a0 + a3*b2 + b0    ← most bit mixing (MSB, used for HAMT)
c1 = a1 + b1
c2 = a2 + b2
c3 = a3 + b3            ← least bit mixing (LSB)
```

All arithmetic is mod 2^64 (unsigned wraparound). Total: 6 additions, 1 multiplication.

Properties:
- **Deterministic** — same inputs always produce same output
- **Associative** — fuse(a, fuse(b, c)) = fuse(fuse(a, b), c)
- **Non-commutative** — fuse(a, b) ≠ fuse(b, a) for a ≠ b
- **Identity element** — `[0, 0, 0, 0]` is a two-sided identity: fuse(a, 0) = fuse(0, a) = a
- **Fast** — no hash function calls, just integer arithmetic

### Low-Entropy Hash Rejection

Fuse **must reject low-entropy inputs and outputs**. A hash is low-entropy when its lower 32 bits are zero in all four words:

```
low_entropy?(h) =
  (h[0] & 0xFFFFFFFF) == 0 AND
  (h[1] & 0xFFFFFFFF) == 0 AND
  (h[2] & 0xFFFFFFFF) == 0 AND
  (h[3] & 0xFFFFFFFF) == 0
```

The checked fuse operation:

```
fuse(a, b):
  REJECT if low_entropy?(a)     // fail fast on bad input
  REJECT if low_entropy?(b)     // fail fast on bad input
  result = unchecked_fuse(a, b)
  REJECT if low_entropy?(result)
  return result
```

An unchecked variant (`unchecked_fuse`) is available for internal use where inputs are known to be valid (e.g., aggregating pre-validated hashes).

See: [Hash Fusing — Detecting Low Entropy Failures](https://clojurecivitas.github.io/math/hashing/hashfusing.html#detecting-low-entropy-failures)

---

## Leaf Types

Leaf values have bounded size. Built-in leaf types:

| Type | Size (bytes) | Canonical Name | Notes |
|------|-------------|----------------|-------|
| `null` | 0 | `dacite.core/null` | Unit type |
| `bool` | 1 | `dacite.core/bool` | |
| `i8` | 1 | `dacite.core/i8` | Signed integer |
| `i16` | 2 | `dacite.core/i16` | |
| `i32` | 4 | `dacite.core/i32` | |
| `i64` | 8 | `dacite.core/i64` | |
| `i128` | 16 | `dacite.core/i128` | |
| `i256` | 32 | `dacite.core/i256` | |
| `u8` | 1 | `dacite.core/u8` | Unsigned integer |
| `u16` | 2 | `dacite.core/u16` | |
| `u32` | 4 | `dacite.core/u32` | |
| `u64` | 8 | `dacite.core/u64` | |
| `u128` | 16 | `dacite.core/u128` | |
| `u256` | 32 | `dacite.core/u256` | |
| `f32` | 4 | `dacite.core/f32` | IEEE 754 float |
| `f64` | 8 | `dacite.core/f64` | |
| `char` | 1–4 | `dacite.core/char` | UTF-8 encoded codepoint |

### Leaf Hashing Example

```
type_hash = sha256("dacite.core/i64")
data_hash = sha256(to_bytes(42))
leaf_hash = fuse(type_hash, data_hash)
```

---

## Node Types

All values in Dacite are represented as `[type, data]` tuples. Internal tree nodes use the following types:

### Finger Tree Nodes

| Type | Description | Data Fields |
|------|-------------|-------------|
| `:ft/empty` | Empty tree | `{:measure m}` |
| `:ft/leaf` | Single element wrapper | `{:value-hash h, :measure m}` |
| `:ft/digit` | Finger (1–32 children) | `{:children [h...], :measure m}` |
| `:ft/node` | Internal node (2–32 children) | `{:children [h...], :measure m}` |
| `:ft/deep` | Deep tree | `{:left h, :spine h, :right h, :measure m}` |

### HAMT Nodes

| Type | Description | Data Fields |
|------|-------------|-------------|
| `:hamt/empty` | Empty map | `{:measure m}` |
| `:hamt/entry` | Single key-value pair | `{:key-hash h, :key-ref h, :val-ref h, :measure m}` |
| `:hamt/bitmap` | Sparse internal node | `{:bitmap n, :children [h...], :measure m}` |

All child references (`h`) are hashes pointing to other nodes in the content-addressed store. This ensures every node has bounded size regardless of collection size.

---

## Collection Types

### Semantic Hashing

Each collection type defines a **semantic hash** that identifies the collection's logical contents independent of tree structure:

#### Serial Collections (Vectors, Strings, Blobs)

```
semantic_hash = fuse(type_hash, reduce(fuse, element_hashes))
```

Two vectors with the same elements in the same order have the same semantic hash, regardless of how the internal finger tree is structured.

#### Hash Collections (Maps)

```
entry_hash = fuse(key_hash, value_hash)
sorted_entries = sort_by_hash(entries)
semantic_hash = fuse(type_hash, reduce(fuse, sorted_entry_hashes))
```

Sorting by hash provides deterministic ordering without requiring key comparability.

### Strings

A string is a **Finger Tree of UTF-8 chars**.

```
type_name = "dacite.core/string"
semantic_hash = fuse(sha256(type_name), reduce(fuse, char_hashes))
```

### Blobs

A blob is a **Finger Tree of bytes**.

```
type_name = "dacite.core/blob"
semantic_hash = fuse(sha256(type_name), reduce(fuse, byte_hashes))
```

### Vectors

A vector is a **Finger Tree of arbitrary values**.

```
type_name = "dacite.core/vector"
semantic_hash = fuse(sha256(type_name), reduce(fuse, element_hashes))
```

### Maps

A map is a **HAMT (Hash Array Mapped Trie)** with 32-way branching.

- Keys and values can be any Dacite value
- Key position determined by key's value hash
- 5-bit chunks of hash → 32-way branching per level
- Uses **most significant bits first** (c0's upper bits), which have the most entropy from fuse

```
type_name = "dacite.core/map"
semantic_hash = fuse(sha256(type_name), reduce(fuse, sorted_entry_hashes))
```

---

## Finger Tree Structure

Vectors, strings, and blobs are implemented as **Finger Trees** with the following parameters:

### Branching Factor

- **Internal nodes:** 2–32 children
- **Digits (fingers):** 1–32 elements

This high branching factor keeps trees shallow, minimizing network round trips. A tree of 1M elements is only ~4 levels deep.

### Accumulated Measure

Every node caches a **measure** of its subtree:

```
Measure = {
  count: u64,       // number of leaf elements
  size_bytes: u64   // total size in bytes of leaf data
}
```

Measures combine via addition (a monoid):

```
combine(m1, m2) = {
  count: m1.count + m2.count,
  size_bytes: m1.size_bytes + m2.size_bytes
}

identity = { count: 0, size_bytes: 0 }
```

The root's measure gives O(1) access to collection length and total size.

### Node Size Constraint

All nodes have bounded size because children are stored as hashes (32 bytes each), not inline:

- Maximum node payload: 32 children × 32 bytes = 1024 bytes
- Plus measure metadata (~16 bytes)
- Fits within typical TCP packet (~1400 bytes MTU)

---

## HAMT Structure

Maps are implemented as **Hash Array Mapped Tries** with the following parameters:

### Hash Navigation

The key's value hash is consumed 5 bits at a time, from most significant to least:

```
Level 0: bits 255–251 of key_hash (upper 5 bits of c0)
Level 1: bits 250–246
...
Level 51: bits 4–0 of key_hash (lower 5 bits of c3)
```

Each 5-bit chunk selects one of 32 possible child positions.

### Bitmap Indexing

Internal nodes use a 32-bit bitmap to represent which child positions are occupied. The actual children array is compressed — only occupied positions have entries. The child's index in the array is computed as:

```
idx = popcount(bitmap & ((1 << chunk) - 1))
```

### Accumulated Measure

Same as Finger Tree — every node caches `{count, size_bytes}` covering all entries in its subtree.

---

## Storage Layer

### Content-Addressed Store

All nodes are stored and retrieved by their structural hash. The store interface requires only two operations:

```
commit!(value) → hash    // store a value, return its hash
lookup(hash) → value     // retrieve a value by hash
```

Since values are immutable and content-addressed, `commit!` is idempotent — storing the same value twice is equivalent to storing it once. This makes write-through caching safe and semantically pure.

### CacheMap

A CacheMap wraps a content-addressed store as a standard associative map interface:

- `get(hash)` → fetches from store on demand (lazy loading)
- `assoc(hash, value)` → commits to store immediately (write-through)
- `merge(cm1, cm2)` → no-op when sharing the same backing store

Data structures (Finger Trees, HAMTs) operate on `[dacite-map, root-hash]` tuples where `dacite-map` can be either a plain in-memory map or a CacheMap. This abstraction enables:

- **Lazy loading** — only nodes actually traversed are fetched
- **Transparent persistence** — writes flow through to the store
- **Bounded memory** — not all nodes need to be in memory simultaneously

---

## Distribution Model

### Adaptive Fetch (Inline Threshold)

To minimize round trips, the server uses the node's accumulated `size_bytes` to decide the response format:

**Request:**
```
GET /node/{hash}?inline_under={bytes}
```

- `inline_under`: Size threshold in bytes (client-specified, server default: 1024)

**Response modes:**

If `node.measure.size_bytes > inline_under`:
```
StructureResponse {
  kind: "structure",
  type_hash: Hash,
  children: [Hash],       // hashes only, client fetches lazily
  measure: Measure
}
```

If `node.measure.size_bytes <= inline_under`:
```
InlineResponse {
  kind: "inline",
  type_hash: Hash,
  leaves: [Value],        // all leaf values, fully materialized
  measure: Measure
}
```

**Rationale:**
- Large subtrees: return structure, let client fetch what it needs
- Small subtrees: inline everything, avoid multiple round trips
- Client controls threshold based on network conditions (mobile vs. datacenter)
- Default 1KB threshold fits ~1 TCP packet

### Sync Protocol

1. Server announces new root hash
2. Client compares to current root
3. Client walks tree, fetching nodes with unknown hashes
4. Unchanged subtrees (same hash) are skipped

### Caching

Immutable content-addressed data is ideal for caching:
- Hash = eternal identity
- No cache invalidation needed
- Multiple cache tiers work naturally

---

## Serialization

*TODO: Define canonical byte serialization for each type.*

---

## Open Questions

- [ ] Canonical serialization format (CBOR? Custom?)
- [x] Finger Tree branching factor / node size — 2–32 children, 1–32 digits
- [ ] Network protocol details (HTTP? Custom?)
- [ ] Garbage collection / retention policies
- [ ] Set type? Sorted map?
- [x] Hash representation — 4 × i64, MSB first
- [x] Low-entropy check — inputs AND result
- [x] Storage abstraction — CacheMap wrapping content-addressed store
- [ ] Semantic hash computation — when/where to compute and store

---

## Implementations

| Language | Status | Location |
|----------|--------|----------|
| Clojure | Reference impl | `impl/clojure/` |
| Node.js | Planned | `impl/node/` |
| C++ | Planned | `impl/cpp/` |

---

## References

- [Hash Fusing](https://clojurecivitas.github.io/math/hashing/hashfusing.html) — associative non-commutative hash combination
- [Hash Array Mapped Tries](https://en.wikipedia.org/wiki/Hash_array_mapped_trie)
- [Finger Trees](https://en.wikipedia.org/wiki/Finger_tree)
- [Content-addressable storage](https://en.wikipedia.org/wiki/Content-addressable_storage)
