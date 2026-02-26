# Dacite Specification

> Data citing with fused hashing.

**Version:** 0.4.0-draft  
**Status:** Early design  
**Last updated:** 2026-02-14

---

## Overview

Dacite is a system for **distributed immutable data structures** with content-addressed nodes. It enables:

- **Structural sharing** — unchanged subtrees share identity across versions
- **Efficient diffs** — compare hashes to sync only what changed
- **Lazy fetching** — clients pull only the data paths they access
- **Perfect caching** — immutable data never invalidates

## Design Principles

1. **Types are data** — types are not a separate concept; they are values in the data model
2. **Three primitives** — scalar, seq, map; everything else is built from these
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

### Byte Hash Table

Dacite hashing is built on a precomputed table mapping each byte value (0–255) to a 256-bit hash:

```
byte_hash: byte → Hash    (256 entries)
```

The default table is seeded using SHA-256: `byte_hash[i] = sha256(byte_array([i]))`. However, any set of 256 distinct, high-quality 32-byte values may be used. This decouples dacite references from any specific hash function at runtime.

### fuse_bytes / fuse_str

All data hashing is performed by mapping bytes through the table and fusing:

```
fuse_bytes(bs) = reduce(unchecked_fuse, [0,0,0,0], map(byte_hash, bs))
fuse_str(s)    = fuse_bytes(utf8_bytes(s))
```

Properties inherited from fuse:
- **Deterministic** — same bytes always produce the same hash
- **Order-sensitive** — different byte orderings produce different hashes
- **Associative** — `fuse(fuse_str(a), fuse_str(b)) = fuse_str(a ++ b)`
- **No hash function at runtime** — only table lookups and integer arithmetic

The associativity property means composing fuse results is equivalent to fusing the concatenated bytes. This is both a feature (composability) and a constraint (domain separators are needed between fields — see Typed Values).

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

### Scalar

A **scalar** is an atomic, bounded-size value. It is the only primitive that contains raw data (bytes) rather than references to other values. Maximum size: 255 bytes (u8 length).

```
scalar_hash = fuse_bytes(canonical_bytes)
```

Scalars are **untyped** at the primitive level. A byte with value 97 and a char 'a' may be the same scalar (same bytes, same hash). Types give meaning to scalars (see Typed Values).

Examples of scalar data:
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
- **Position 0** — the type name (a seq of char scalars)
- **Position 1** — the data (any primitive: scalar, seq, or map)

This is a structural convention, not enforced by the storage layer. The system treats typed values as ordinary seqs; the "typed" interpretation is applied by consumers.

### Type Names

A type name is an **untyped seq of char scalars**:

```
type_name("string") = seq('s', 't', 'r', 'i', 'n', 'g')
```

Each character is a raw scalar (UTF-8 bytes). The type name's hash is the seq's semantic hash (elements-fuse of its char scalar hashes).

Type names are self-documenting: given a typed value, read position 0 to discover its type. No external registry needed.

Convention for built-in types: bare names (e.g., `"string"`, `"i64"`, `"vector"`). User-defined types should use namespaced names (e.g., `"myapp/user"`) to avoid collisions.

### Semantic Hash of Typed Values

The semantic hash of a typed value uses a **null domain separator** between the type name and data:

```
typed_hash = fuse_bytes(type_name_bytes ++ 0x00 ++ encode(data))
```

Equivalently (by fuse associativity):

```
typed_hash = fuse(fuse(fuse_str(type_name), fuse_bytes([0x00])), fuse_bytes(encode(data)))
```

The null separator is essential: without it, `fuse(fuse_str(a), fuse_str(b)) = fuse_str(a ++ b)` would cause boundary collisions (e.g., type `"i64"` with data `"2"` would collide with type `"i6"` with data `"42"`). Since type names cannot contain null bytes, the separator makes the encoding unambiguous.

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

This works because both have the same data seq (a seq of char scalars), and the group structure of fuse allows cleanly removing the type prefix.

### Built-in Types

All built-in types follow the `seq(type_name, data)` convention:

| Type Name | Data | Description |
|-----------|------|-------------|
| `"null"` | null scalar (0 bytes) | Unit type |
| `"bool"` | 1-byte scalar | Boolean |
| `"i8"` … `"i256"` | 1–32 byte scalar (big-endian signed) | Signed integers |
| `"u8"` … `"u256"` | 1–32 byte scalar (big-endian unsigned) | Unsigned integers |
| `"f32"`, `"f64"` | 4 or 8 byte scalar (IEEE 754) | Floating point |
| `"char"` | 1–4 byte scalar (UTF-8) | Unicode character |
| `"string"` | seq of char scalars | UTF-8 string |
| `"blob"` | seq of byte scalars | Binary data |
| `"vector"` | seq of arbitrary values | Ordered collection |
| `"map"` | map (HAMT) | Key-value collection |

### Sets (Convention)

A **set** is a map where every key maps to itself:

```
set({a, b, c}) = map({a: a, b: b, c: c})
```

There is no `"set"` type. Sets are ordinary `"map"` values. Content addressing means the duplicate key/value reference costs zero additional storage — both slots point to the same hash.

Membership test: `get(set, x) != nil`.

#### Negative Sets (Cofinite Sets)

A **negative set** represents "everything except these elements." It uses a distinguished sentinel element `neg` as a key:

```
negative_set({a, b}) = map({neg: neg, a: a, b: b})
```

Where `neg` is a typed value `["neg", null]` — a null scalar with the type name `"neg"`.

Membership test for a negative set: `x` is a member if `get(set, x) == nil` (inverted logic). The presence of the `neg` key signals the inversion.

**Set operations with positive (pos) and negative (neg) sets:**

In the table below, A and B refer to the **stored elements** (excluding the `neg` sentinel). For positive sets, stored elements are the members. For negative sets, stored elements are the *excluded* members.

| A | B | union | intersect | difference (A \ B) |
|-----|-----|-------------|-------------|---------------------|
| pos | pos | A ∪ B | A ∩ B | A \ B |
| neg | neg | neg (A ∩ B) | neg (A ∪ B) | B \ A |
| pos | neg | neg (B \ A) | A \ B | A ∩ B |
| neg | pos | neg (A \ B) | B \ A | neg (A ∪ B) |

**Other operations:**

| Operation | Definition |
|-----------|------------|
| `complement(A)` | Toggle the `neg` sentinel (add or remove) |
| `member?(A, x)` | pos: `get(A, x) != nil` · neg: `get(A, x) == nil` |

All set operations reduce to map operations (merge, intersect-keys, difference-keys) plus toggling the `neg` sentinel. This is a pure convention — the store and core types know nothing about sets or `neg`. Utility functions implement the convention.

---

## Internal Structures

Seqs and maps are implemented using tree structures. The internal nodes of these trees are **not** user-facing values — they are implementation machinery stored in the content-addressed store.

### Node Hashing

Internal nodes are hashed using their type name (with a null domain separator) and semantic content:

```
node_hash = fuse(fuse_str(node_type_name ++ 0x00), node.measure.elements_fuse)
```

The null byte terminates the type name, preventing collisions with longer type names. This means nodes with the same type and the same logical elements produce the same hash, regardless of internal tree shape. Different structural arrangements of the same sequence normalize to a single hash in the store. This is correct because nodes with the same elements are functionally interchangeable.

**Exception — HAMT bitmap nodes:** Bitmap nodes include the bitmap value in their hash because it determines routing structure. Two bitmaps with the same elements but different bitmaps route lookups differently and are NOT interchangeable:

```
hamt_bitmap_hash = fuse(node_hash, fuse_bytes(bitmap_as_8_bytes))
```

Without this, single-child bitmap nodes at different HAMT levels would collide (same elements-fuse, same type), creating self-referential loops in the store.

**Collision resistance:** The fuse-based hash chain has ~2^96 birthday-bound collision resistance (from the additive structure of fuse components c1–c3). This is weaker than SHA-256's ~2^128 but astronomically beyond practical attack. The security bound is independent of how the byte hash table is seeded.

### Finger Tree Nodes (Seq)

| Node Type | Description | Data Fields |
|-----------|-------------|-------------|
| `"ft/empty"` | Empty seq | `{measure}` |
| `"ft/single"` | Single element wrapper | `{value_hash, measure}` |
| `"ft/digit"` | Finger (1–32 children) | `{children: [hash...], measure}` |
| `"ft/node"` | Internal node (2–32 children) | `{children: [hash...], measure}` |
| `"ft/deep"` | Deep tree | `{left, spine, right, measure}` |

### HAMT Nodes (Map)

| Node Type | Description | Data Fields |
|-----------|-------------|-------------|
| `"hamt/empty"` | Empty map | `{measure}` |
| `"hamt/entry"` | Single key-value pair | `{key_hash, key_ref, val_ref, measure}` |
| `"hamt/bitmap"` | Sparse internal node | `{bitmap, children: [hash...], measure}` |

All child references are hashes pointing to other nodes in the content-addressed store. This ensures every node has bounded size regardless of collection size.

### Measure Monoid

Every internal node caches a **measure** of its subtree:

```
Measure = {
  count: u64,           // number of scalar elements
  size_bytes: u64,      // total size in bytes of scalar data
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

For **seq scalars**, `elements_fuse` equals the element's hash. For **map entries**, `elements_fuse` equals `fuse(key_ref, val_ref)`.

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
  values: [Value],        // all scalar values, fully materialized
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

Dacite defines two serialization formats:

- **Binary** — canonical byte format for storage and wire transfer
- **JSON** — human-readable format for debugging, inspection, and interop with web clients

Both formats represent the same logical data. The binary format is authoritative for hashing and storage; the JSON format is a convenience layer.

---

### Binary Format

The binary format is designed for:

- **Determinism** — the same logical value always serializes to the same bytes
- **Self-description** — each node carries its kind tag; no external schema needed
- **Simplicity** — fixed-width fields where possible, minimal framing
- **Streaming** — nodes can be read sequentially without backtracking

#### Encoding Conventions

- **Integers** are big-endian (network byte order)
- **Hashes** are 32 bytes (4 × i64, big-endian)
- **Child counts** are unsigned 8-bit (u8), max 32 children per node
- **Bitmaps** are unsigned 32-bit (u32)

#### Node Kinds

Every serialized node begins with a 1-byte **kind tag**:

| Tag | Kind | Description |
|-----|------|-------------|
| `0x00` | Scalar | Raw bytes (atomic value) |
| `0x01` | Seq node | Finger tree internal node |
| `0x02` | Map node | HAMT internal node |
| `0x03` | Collection | Top-level typed collection header |

#### Scalar Serialization

A scalar is the simplest node: a kind tag, a u8 length, and raw bytes.

```
scalar = 0x00 ++ u8(len) ++ bytes[len]
```

Maximum scalar size: 255 bytes. The scalar's hash is `fuse_bytes(bytes)` — computed over the raw data bytes only, not the framing.

**Size examples:**

| Scalar | Serialized size |
|--------|----------------|
| Null (0 bytes) | 2 bytes (tag + u8 zero) |
| Boolean | 3 bytes |
| i64 | 10 bytes |
| i256 | 34 bytes |
| UTF-8 char | 3–6 bytes |

#### Seq Node Serialization

Seq nodes represent finger tree internals. Each node carries its structural type, measure, and child references:

```
seq_node = 0x01
        ++ u8(node_type)         // structural subtype
        ++ measure               // cached measure (48 bytes)
        ++ u8(n_children)        // number of child references (0–32)
        ++ hash[n_children]      // child hashes (32 bytes each)
```

Seq node subtypes:

| Subtype | Value | Children |
|---------|-------|----------|
| Empty | `0x00` | 0 |
| Single | `0x01` | 1 (the element hash) |
| Digit | `0x02` | 1–32 |
| Internal node | `0x03` | 2–32 |
| Deep | `0x04` | 3 (left, spine, right) |

**Size range:** 50 bytes (empty) to 1,074 bytes (32 children).

#### Map Node Serialization

Map nodes represent HAMT internals:

```
map_node = 0x02
        ++ u8(node_type)         // structural subtype
        ++ measure               // cached measure (48 bytes)
        ++ <type-specific fields>
```

Map node subtypes:

| Subtype | Value | Fields after measure |
|---------|-------|---------------------|
| Empty | `0x00` | (none) |
| Entry | `0x01` | `hash(key_hash) ++ hash(key_ref) ++ hash(val_ref)` |
| Bitmap | `0x02` | `u32(bitmap) ++ u8(n_children) ++ hash[n_children]` |

For entries, `key_hash` is the routing hash (used for HAMT navigation), while `key_ref` and `val_ref` point to the stored key and value nodes.

**Size range:** 50 bytes (empty) to 1,079 bytes (32-child bitmap).

#### Collection Serialization

Collections are top-level typed values whose structure is stored as a tree of seq or map nodes. A collection header records the collection type, a root hash pointing into the tree, and the total size in bytes:

```
collection = 0x03
          ++ u8(collection_type)   // which collection
          ++ hash(root)            // root of the internal tree
          ++ u64(count)            // number of elements
          ++ u64(size_bytes)       // total materialized size in bytes
```

Collection subtypes:

| Subtype | Value | Root points to | Description |
|---------|-------|----------------|-------------|
| Vector | `0x00` | Finger tree root | Ordered collection of arbitrary values |
| String | `0x01` | Finger tree root | Seq of char scalars |
| Blob | `0x02` | Finger tree root | Seq of byte scalars |
| Map | `0x03` | HAMT root | Key-value collection |

The `root` hash references an existing seq node (for vector/string/blob) or map node (for map) already in the store. The collection header does **not** duplicate any element references — it is a thin typed wrapper over the tree root.

The `count` field caches the number of elements in the collection. The `size_bytes` field caches the total byte size of all leaf scalars. Both are available in O(1) from the root's measure, and are stored here so the collection header is self-contained — no tree traversal needed for basic queries.

**Fixed size:** 1 + 1 + 32 + 8 + 8 = **50 bytes** for every collection, regardless of element count.

The collection's hash is computed as:

```
collection_hash = fuse(fuse_str(type_name ++ 0x00), root.measure.elements_fuse)
```

This is the standard typed value hash — the collection type name fused with the semantic hash of the underlying tree.

#### Measure Serialization

Measures appear inline in seq and map nodes:

```
measure = u64(count) ++ u64(size_bytes) ++ hash(elements_fuse)
```

Fixed size: 8 + 8 + 32 = **48 bytes**.

#### Typed Value Serialization

Scalar typed values (e.g., `["i64" 42]`) are serialized as kind `0x00` (scalar) — the type information lives in the store mapping (hash → typed value), not in the serialized bytes.

Collection typed values (string, vector, blob, map) are serialized as kind `0x03` (collection) — a thin header with the collection subtype, root hash, and cached size.

In both cases, the type name is part of the **hash computation** (via the null-separated domain prefix), not the serialized payload. The store maps each hash to its full `[type_name, data]` entry; serialization encodes only the data portion.

#### Hash Computation from Serialized Form

A node's hash is **not** computed from its serialized bytes. Hashes are computed from the logical content:

- **Scalar:** `fuse_bytes(data_bytes)`
- **Seq/Map nodes:** `fuse(fuse_str(node_type_name ++ 0x00), elements_fuse)` (with the HAMT bitmap exception)
- **Typed values:** `fuse_bytes(type_name_bytes ++ 0x00 ++ encode(data))`

This means two different serialization formats (or versions) can interoperate as long as they compute the same logical hashes. The serialization is a transport/storage concern; the hash is a semantic concern.

#### Canonical Ordering

For deterministic serialization:
- Seq children are serialized in order (left to right)
- Map children are serialized in bitmap order (ascending bit index)
- No optional fields — every field is always present
- No padding or alignment bytes

---

### JSON Format

The JSON format provides a human-readable representation of dacite values. It is intended for:

- **Debugging** — inspect tree structure and content
- **Web clients** — receive and render dacite data without a binary parser
- **Interop** — exchange dacite values with JSON-native systems (REST APIs, browsers)

#### Design Principles

- Each JSON object has a `"kind"` field: `"scalar"`, `"seq"`, or `"map"`
- Hashes are hex-encoded strings (64 characters)
- Two modes: **structural** (references as hashes) and **materialized** (values inlined)

#### Structural Mode

Structural JSON mirrors the content-addressed store. Each node is a self-contained object with hash references to children:

**Scalar:**
```json
{
  "kind": "scalar",
  "hash": "a1b2c3d4...",
  "bytes": "base64-encoded-data",
  "size": 8
}
```

**Seq node:**
```json
{
  "kind": "seq",
  "subtype": "deep",
  "hash": "e5f6a7b8...",
  "measure": {
    "count": 1000,
    "size_bytes": 8000,
    "elements_fuse": "1234abcd..."
  },
  "children": ["hash1...", "hash2...", "hash3..."]
}
```

**Map node:**
```json
{
  "kind": "map",
  "subtype": "bitmap",
  "hash": "c9d0e1f2...",
  "measure": {
    "count": 5,
    "size_bytes": 40,
    "elements_fuse": "5678efab..."
  },
  "bitmap": 671088640,
  "children": ["hash1...", "hash2..."]
}
```

**Map entry:**
```json
{
  "kind": "map",
  "subtype": "entry",
  "hash": "f3a4b5c6...",
  "measure": { "count": 1, "size_bytes": 16, "elements_fuse": "..." },
  "key_hash": "routing-hash...",
  "key_ref": "stored-key-hash...",
  "val_ref": "stored-val-hash..."
}
```

#### Materialized Mode

Materialized JSON inlines values recursively, producing familiar nested structures. This is the format web clients and JSON consumers will typically use:

**Typed scalar:**
```json
{
  "kind": "typed",
  "type": "i64",
  "value": 42
}
```

**String (typed seq of char scalars):**
```json
{
  "kind": "typed",
  "type": "string",
  "value": "hello world"
}
```

**Vector:**
```json
{
  "kind": "typed",
  "type": "vector",
  "value": [
    {"kind": "typed", "type": "i64", "value": 1},
    {"kind": "typed", "type": "string", "value": "two"},
    {"kind": "typed", "type": "bool", "value": true}
  ]
}
```

**Map:**
```json
{
  "kind": "typed",
  "type": "map",
  "value": {
    "name": {"kind": "typed", "type": "string", "value": "Alice"},
    "age": {"kind": "typed", "type": "u8", "value": 30}
  }
}
```

Note: In materialized map JSON, keys are rendered as strings for JSON compatibility. The actual dacite key is a typed value; the string rendering is lossy but practical.

#### Hybrid Mode

For large trees, a hybrid approach inlines small subtrees and uses hash references for large ones. The `inline_under` parameter from the Distribution Model controls the threshold:

```json
{
  "kind": "seq",
  "subtype": "deep",
  "hash": "e5f6a7b8...",
  "measure": {"count": 1000000, "size_bytes": 8000000, "elements_fuse": "..."},
  "children": [
    {"kind": "typed", "type": "string", "value": "small inline value"},
    {"kind": "ref", "hash": "large-subtree-hash...", "measure": {"count": 500000, "...": "..."}}
  ]
}
```

The `"ref"` kind signals an un-fetched subtree. Clients can request materialization by following the hash.

#### JSON Schema

Formal JSON Schema definitions are provided alongside this spec:

- **[`structural.schema.json`](schema/structural.schema.json)** — validates structural mode nodes (scalars, seq nodes, map nodes with hash references)
- **[`materialized.schema.json`](schema/materialized.schema.json)** — validates materialized mode values (typed values with inlined data, including hybrid `ref` nodes)

Notes:
- The `hash` field is optional in materialized mode (clients may not need it)
- The `ref` kind in materialized schema supports hybrid mode (partially materialized trees)
- Custom typed values use `typed_custom` with a freeform `type` string

#### JSON ↔ Binary Equivalence

A value round-tripped through JSON and binary must produce the same hash. The JSON format is purely a presentation concern; the authoritative hash is always computed from the logical content per the binary format rules.

Implementations should provide:
- `to_json(hash, store, mode)` → JSON string
- `from_json(json_string)` → store entries + root hash

---

## Open Questions

- [ ] Network protocol details (HTTP? Custom?)
- [ ] Garbage collection / retention policies
- [ ] Set type? Sorted map?
- [x] Scalar size limits — u8 length, max 255 bytes
- [ ] Byte hash table distribution — how do implementations agree on the table? Hardcoded? Negotiated?
- [x] Canonical serialization format — custom binary format (see Serialization)
- [x] Hashing decoupled from SHA-256 — byte hash table + fuse (SHA-256 is only the default seed)
- [x] Domain separation — null byte (0x00) separator between type name and data
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
- [Hash Array Mapped Tries](https://grokipedia.com/page/hash_tree_persistent_data_structure)
- [Finger Trees](https://grokipedia.com/page/Finger_tree)
- [Content-addressable storage](https://grokipedia.com/page/Content-addressable_storage)
