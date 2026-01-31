# Dacite Specification

> Data citing with fused hashing.

**Version:** 0.1.0-draft  
**Status:** Early design  
**Last updated:** 2026-01-31

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

## Hashing Scheme

### Type Hashes

Types are identified by the SHA-256 hash of their canonical name:

```
type_hash = sha256(type_name)
```

This allows open extension — anyone can define a type without coordination.

### Value Hashes

Every value has a hash computed as:

```
value_hash = fuse(type_hash, data_hash)
```

Where `type_hash` is the SHA-256 of the type name, and `data_hash` differs by value kind:

#### Leaf Values

For leaf values (bounded-size primitives):

```
data_hash = sha256(to_bytes(value))
```

#### Serial Collections (Vectors, Strings, Blobs)

For ordered collections, `data_hash` is the sequential fuse of all child value hashes:

```
data_hash = fuse(child₀_hash, fuse(child₁_hash, fuse(child₂_hash, ...)))
```

Or equivalently, left-folded:

```
data_hash = reduce(fuse, child_hashes)
```

This ensures that collection identity depends on order and contents, but is independent of internal tree structure (critical for Finger Trees).

#### Hash Collections (Maps)

For unordered-by-insertion collections like maps, `data_hash` is the fuse of all entry hashes **sorted by hash value**:

```
entry_hash = fuse(key_hash, value_hash)
sorted_entries = sort_by_hash(entries)
data_hash = reduce(fuse, map(entry_hash, sorted_entries))
```

Sorting by hash provides deterministic ordering without requiring key comparability.

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
- **Fast** — no hash function calls, just integer arithmetic

### Low-Entropy Hash Rejection

Hashes with **128 bits of zeros in the lower 32 bits of all four words** must be rejected. Specifically, reject any hash where:

```
(c0 & 0xFFFFFFFF) == 0 AND
(c1 & 0xFFFFFFFF) == 0 AND
(c2 & 0xFFFFFFFF) == 0 AND
(c3 & 0xFFFFFFFF) == 0
```

This detects low-entropy failures that can occur when fusing repeated or degenerate values. See: [Hash Fusing — Detecting Low Entropy Failures](https://clojurecivitas.github.io/math/hashing/hashfusing.html#detecting-low-entropy-failures)

When a low-entropy hash is detected, implementations should:
1. Reject the operation, OR
2. Inject entropy (e.g., by including position indices in the fuse)

---

## Leaf Types

Leaf values have bounded size. Built-in leaf types:

| Type | Size | Notes |
|------|------|-------|
| `null` | 0 bits | Unit type |
| `bool` | 1 bit | |
| `i8`, `i16`, `i32`, `i64`, `i128`, `i256` | signed integers | |
| `u8`, `u16`, `u32`, `u64`, `u128`, `u256` | unsigned integers | |
| `f32`, `f64` | IEEE 754 floats | |
| `char` | 1-4 bytes | UTF-8 codepoint |

### Leaf Hashing Example

```
type_hash = sha256("dacite.core/i64")
data_hash = sha256(to_bytes(42))
leaf_hash = fuse(type_hash, data_hash)
```

---

## Collection Types

### Strings

A string is a **Finger Tree of UTF-8 chars**.

```
type_hash = sha256("dacite.core/string")
data_hash = reduce(fuse, char_hashes)
```

### Blobs

A blob is a **Finger Tree of bytes**.

```
type_hash = sha256("dacite.core/blob")
data_hash = reduce(fuse, byte_hashes)
```

### Vectors

A vector is a **Finger Tree of arbitrary values**.

```
type_hash = sha256("dacite.core/vector")
data_hash = reduce(fuse, element_hashes)
```

### Maps

A map is a **HAMT (Hash Array Mapped Trie)** with 32-way branching.

- Keys and values can be any Dacite value
- Key position determined by key's value_hash
- 5-bit chunks of hash → 32-way branching per level
- Uses **most significant bits first** (c0's upper bits), which have the most entropy from fuse

```
type_hash = sha256("dacite.core/map")
data_hash = reduce(fuse, sorted_entry_hashes)
```

---

## Serialization

*TODO: Define canonical byte serialization for each type.*

---

## Distribution Model

### Content-Addressed Storage

Every node is stored and retrieved by its hash:

```
GET /blob/{hash} → bytes
```

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

## Use Cases

### Configuration Management

- Server maintains configuration as Dacite map
- Clients lazily fetch only the config paths they use
- Updates: server announces new root, clients sync diffs
- Version pinning: client can stick to a known root hash

### (More use cases TBD)

---

## Open Questions

- [ ] Canonical serialization format (CBOR? Custom?)
- [ ] Finger Tree branching factor / node size
- [ ] Network protocol details (HTTP? Custom?)
- [ ] Garbage collection / retention policies
- [ ] Set type? Sorted map?

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
