# Dacite Specification

> Data citing with fused hashing.

**Version:** 0.3.0-draft  
**Status:** Early design  
**Last updated:** 2026-02-11

---

## Overview

Dacite is a system for **distributed immutable data structures** with content-addressed nodes. It enables:

- **Structural sharing** — unchanged subtrees share identity across versions
- **Efficient diffs** — compare hashes to sync only what changed
- **Lazy fetching** — clients pull only the data paths they access
- **Perfect caching** — immutable data never invalidates

## Design Principles

1. **Types are data** — types are not a separate concept; they are values in the data model
2. **Three primitives** — leaf, seq, map; everything else is built from these
3. **Content-addressed** — every value has a 256-bit hash identity
4. **Language-agnostic** — spec defines the format, not the implementation
5. **Open type system** — new types require no central registry; a type name is just a seq of chars

---

## Hash Representation

All hashes are 256-bit values represented as **4 × 64-bit words** (most significant first):

```
hash = [c0, c1, c2, c3]    (4 × i64, big-endian word order)
```

Word `c0` contains the most mixed bits (from fuse) and is used first for HAMT navigation.

---

## Fuse Function

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

### Group Structure (Inverse and Unfuse)

The fuse operation forms a **group** over `(Z/2^64)^4`. Every hash has a unique two-sided inverse:

```
inv([a0, a1, a2, a3]) = [a3*a2 - a0, -a1, -a2, -a3]
```

Such that:

```
fuse(inv(a), a) = fuse(a, inv(a)) = [0, 0, 0, 0]
```

All arithmetic is mod 2^64. The inverse costs 1 multiply + 4 negations.

**Unfuse** removes a known component from a fused hash:

```
unfuse(fused, b) = fuse(fused, inv(b))
```

Given `fused = fuse(a, b)`, then `unfuse(fused, b) = a`. To strip from the left instead: `fuse(inv(a), fused) = b`.

The group structure enables:
- **Cross-type equality** — strip type hashes to compare raw content (see Typed Values)
- **Hash recovery** — recover one component of a fused pair when the other is known
- **Incremental re-hashing** — update a fused chain without recomputing from scratch

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
  REJECT if low_entropy?(a)
  REJECT if low_entropy?(b)
  result = unchecked_fuse(a, b)
  REJECT if low_entropy?(result)
  return result
```

An unchecked variant (`unchecked_fuse`) is available for internal use where inputs are known to be valid (e.g., combining measures).

See: [Hash Fusing — Detecting Low Entropy Failures](https://clojurecivitas.github.io/math/hashing/hashfusing.html#detecting-low-entropy-failures)

---

## Primitives

Dacite has exactly **three primitive kinds**. Everything in the system is built from these:

### Leaf

A **leaf** is an atomic, bounded-size value. It is the only primitive that contains raw data (bytes) rather than references to other values.

```
leaf_hash = sha256(canonical_bytes)
```

Leaves are **untyped** at the primitive level. A byte with value 97 and a char 'a' may be the same leaf (same bytes, same hash). Types give meaning to leaves (see Typed Values).

Examples of leaf data:
- A single byte (0–255)
- A UTF-8 encoded character (1–4 bytes)
- A big-endian integer (1–32 bytes depending on width)
- An IEEE 754 float (4 or 8 bytes)
- A boolean (1 byte: 0x00 or 0x01)
- Null (0 bytes)

### Seq

A **seq** is an ordered collection of references, implemented as a Finger Tree. It is the universal building block for ordered data.

A seq's hash is its **semantic hash**, derived from the accumulated measure:

```
seq_hash = root.measure.elements_fuse
```

Two seqs with the same elements in the same order have the same hash, regardless of internal tree structure. Different tree shapes for the same logical sequence normalize to the same hash in the content-addressed store.

### Map

A **map** is an unordered collection of key-value pairs, implemented as a HAMT.

A map's hash is its semantic hash, which is insertion-order independent because the HAMT traversal order is determined by key hashes (ascending):

```
map_hash = root.measure.elements_fuse
```

Where each entry contributes `fuse(key_hash, value_hash)` to the measure.

---

## Typed Values

### Convention

A **typed value** is a 2-element seq:

```
typed_value = seq(type_name, data)
```

Where:
- **Position 0** — the type name (a seq of char leaves)
- **Position 1** — the data (any primitive: leaf, seq, or map)

This is a structural convention, not enforced by the storage layer. The system treats typed values as ordinary seqs; the "typed" interpretation is applied by consumers.

### Type Names

A type name is an **untyped seq of char leaves**:

```
type_name("string") = seq('s', 't', 'r', 'i', 'n', 'g')
```

Each character is a raw leaf (UTF-8 bytes). The type name's hash is the seq's semantic hash (elements-fuse of its char leaf hashes).

Type names are self-documenting: given a typed value, read position 0 to discover its type. No external registry needed.

Convention for built-in types: bare names (e.g., `"string"`, `"i64"`, `"vector"`). User-defined types should use namespaced names (e.g., `"myapp/user"`) to avoid collisions.

### Semantic Hash of Typed Values

The semantic hash of a typed value is its seq's elements-fuse:

```
typed_hash = fuse(h(type_name), h(data))
```

This is just the normal seq semantic hash — the type name and data are the two elements.

### Cross-Type Equality

Two typed values with different types but the same underlying data can be compared in **O(1)** by stripping the type name hash from the left:

```
content_hash(typed_value) = fuse(inv(h(type_name)), typed_hash)
```

Example: a string `"abc"` and a vector of chars `['a', 'b', 'c']`:

```
string_content = fuse(inv(h("string")), string_hash)
vector_content = fuse(inv(h("vector")), vector_hash)

string_content == vector_content   // true — same underlying data
```

This works because both have the same data seq (a seq of char leaves), and the group structure of fuse allows cleanly removing the type prefix.

### Built-in Types

All built-in types follow the `seq(type_name, data)` convention:

| Type Name | Data | Description |
|-----------|------|-------------|
| `"null"` | null leaf (0 bytes) | Unit type |
| `"bool"` | 1-byte leaf | Boolean |
| `"i8"` … `"i256"` | 1–32 byte leaf (big-endian signed) | Signed integers |
| `"u8"` … `"u256"` | 1–32 byte leaf (big-endian unsigned) | Unsigned integers |
| `"f32"`, `"f64"` | 4 or 8 byte leaf (IEEE 754) | Floating point |
| `"char"` | 1–4 byte leaf (UTF-8) | Unicode character |
| `"string"` | seq of char leaves | UTF-8 string |
| `"blob"` | seq of byte leaves | Binary data |
| `"vector"` | seq of arbitrary values | Ordered collection |
| `"map"` | map (HAMT) | Key-value collection |

---

## Internal Structures

Seqs and maps are implemented using tree structures. The internal nodes of these trees are **not** user-facing values — they are implementation machinery stored in the content-addressed store.

### Node Hashing

Internal nodes are hashed using their type and semantic content:

```
node_hash = fuse(sha256(node_type_name), node.measure.elements_fuse)
```

This means nodes with the same type and the same logical elements produce the same hash, regardless of internal tree shape. Different structural arrangements of the same sequence normalize to a single hash in the store. This is correct because nodes with the same elements are functionally interchangeable.

**Collision resistance:** Since leaf hashes are SHA-256 based, the `elements_fuse` chain has ~2^96 birthday-bound collision resistance (from the additive structure of fuse components c1–c3). This is weaker than SHA-256's ~2^128 but astronomically beyond practical attack.

### Finger Tree Nodes (Seq)

| Node Type | Description | Data Fields |
|-----------|-------------|-------------|
| `:ft/empty` | Empty seq | `{measure}` |
| `:ft/leaf` | Single element wrapper | `{value_hash, measure}` |
| `:ft/digit` | Finger (1–32 children) | `{children: [hash...], measure}` |
| `:ft/node` | Internal node (2–32 children) | `{children: [hash...], measure}` |
| `:ft/deep` | Deep tree | `{left, spine, right, measure}` |

### HAMT Nodes (Map)

| Node Type | Description | Data Fields |
|-----------|-------------|-------------|
| `:hamt/empty` | Empty map | `{measure}` |
| `:hamt/entry` | Single key-value pair | `{key_hash, key_ref, val_ref, measure}` |
| `:hamt/bitmap` | Sparse internal node | `{bitmap, children: [hash...], measure}` |

All child references are hashes pointing to other nodes in the content-addressed store. This ensures every node has bounded size regardless of collection size.

### Measure Monoid

Every internal node caches a **measure** of its subtree:

```
Measure = {
  count: u64,           // number of leaf elements
  size_bytes: u64,      // total size in bytes of leaf data
  elements_fuse: Hash   // running fuse of all element hashes
}
```

Measures combine as a monoid:

```
combine(m1, m2) = {
  count: m1.count + m2.count,
  size_bytes: m1.size_bytes + m2.size_bytes,
  elements_fuse: unchecked_fuse(m1.elements_fuse, m2.elements_fuse)
}

identity = { count: 0, size_bytes: 0, elements_fuse: [0, 0, 0, 0] }
```

The `elements_fuse` field uses `unchecked_fuse` because the identity element `[0, 0, 0, 0]` is technically low-entropy; this is safe since measures are internal bookkeeping.

For **seq leaves**, `elements_fuse` equals the element's hash. For **map entries**, `elements_fuse` equals `fuse(key_ref, val_ref)`.

The root's measure gives **O(1)** access to count, total size, and semantic hash.

---

## Finger Tree Parameters

### Branching Factor

- **Internal nodes:** 2–32 children
- **Digits (fingers):** 1–32 elements

This high branching factor keeps trees shallow, minimizing network round trips. A tree of 1M elements is only ~4 levels deep.

### Node Size Constraint

All nodes have bounded size because children are stored as hashes (32 bytes each), not inline:

- Maximum node payload: 32 children × 32 bytes = 1024 bytes
- Plus measure metadata (~48 bytes with elements_fuse)
- Fits within typical TCP packet (~1400 bytes MTU)

---

## HAMT Parameters

### Hash Navigation

The key's hash is consumed 5 bits at a time, from most significant to least:

```
Level 0: bits 255–251 of key_hash (upper 5 bits of c0)
Level 1: bits 250–246
...
Level 51: bits 4–0 of key_hash (lower 5 bits of c3)
```

Each 5-bit chunk selects one of 32 possible child positions.

### Bitmap Indexing

Internal nodes use a 32-bit bitmap to represent which child positions are occupied. The actual children array is compressed — only occupied positions have entries:

```
idx = popcount(bitmap & ((1 << chunk) - 1))
```

### Traversal Order

HAMT traversal visits children in bitmap order (ascending chunk index), which corresponds to ascending key hash order. This makes `elements_fuse` deterministic regardless of insertion order.

---

## Storage Layer

### Content-Addressed Store

All nodes are stored and retrieved by their structural hash:

```
commit!(value) → hash    // store a value, return its hash
lookup(hash) → value     // retrieve a value by hash
```

Since values are immutable and content-addressed, `commit!` is idempotent.

### CacheMap

A CacheMap wraps a content-addressed store as a standard associative map interface:

- `get(hash)` → fetches from store on demand (lazy loading)
- `assoc(hash, value)` → commits to store immediately (write-through)
- `merge(cm1, cm2)` → no-op when sharing the same backing store

Data structures operate on `[dacite-map, root-hash]` tuples where `dacite-map` can be either a plain in-memory map or a CacheMap. This abstraction enables:

- **Lazy loading** — only nodes actually traversed are fetched
- **Transparent persistence** — writes flow through to the store
- **Bounded memory** — not all nodes need to be in memory simultaneously

---

## Distribution Model

### Adaptive Fetch (Inline Threshold)

The server uses the node's accumulated `size_bytes` to decide the response format:

**Request:**
```
GET /node/{hash}?inline_under={bytes}
```

**Response modes:**

If `node.measure.size_bytes > inline_under`:
```
StructureResponse {
  kind: "structure",
  children: [Hash],       // hashes only, client fetches lazily
  measure: Measure
}
```

If `node.measure.size_bytes <= inline_under`:
```
InlineResponse {
  kind: "inline",
  leaves: [Value],        // all leaf values, fully materialized
  measure: Measure
}
```

Default threshold: 1024 bytes (~1 TCP packet). Client controls threshold based on network conditions.

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

*TODO: Define canonical byte serialization for each primitive kind.*

---

## Open Questions

- [ ] Canonical serialization format (CBOR? Custom?)
- [ ] Network protocol details (HTTP? Custom?)
- [ ] Garbage collection / retention policies
- [ ] Set type? Sorted map?
- [ ] Leaf size limits — maximum bytes for a single leaf?
- [x] Finger Tree branching factor / node size — 2–32 children, 1–32 digits
- [x] Hash representation — 4 × i64, MSB first
- [x] Low-entropy check — inputs AND result
- [x] Storage abstraction — CacheMap wrapping content-addressed store
- [x] Semantic hash computation — cached in measure monoid via `elements_fuse`
- [x] Type system — types are data; typed values are `seq(type_name, data)`

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
- [Hash Array Mapped Tries](https://grokipedia.com/wiki/Hash_array_mapped_trie)
- [Finger Trees](https://grokipedia.com/wiki/Finger_tree)
- [Content-addressable storage](https://grokipedia.com/wiki/Content-addressable_storage)
