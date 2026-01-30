# Dacite Specification

> Data citing with fused hashing.

**Version:** 0.1.0-draft  
**Status:** Early design  
**Last updated:** 2026-01-30

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

Every value (leaf or collection) has a hash computed as:

```
value_hash = fuse(type_hash, data_hash)
```

Where:
- `type_hash` = SHA-256 of type name
- `data_hash` = SHA-256 of serialized value bytes

### Fuse Function

Fuse combines two 256-bit hashes using a 4×4 upper triangular matrix over 64-bit cells:

```
Input:  a = [a0, a1, a2, a3]  (256 bits as 4 × 64-bit words)
        b = [b0, b1, b2, b3]

Output: c = [c0, c1, c2, c3]

c0 = a0 + b0
c1 = a1 + b1
c2 = a2 + b2
c3 = a3 + a0*b1 + b3
```

All arithmetic is mod 2^64. Total: 6 additions, 1 multiplication.

Properties:
- Deterministic
- Non-commutative (fuse(a,b) ≠ fuse(b,a))
- Fast (no hash function calls)

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

### Leaf Hashing

```
type_hash = sha256("dacite.core/i64")  // example
data_hash = sha256(to_bytes(value))
leaf_hash = fuse(type_hash, data_hash)
```

---

## Collection Types

### Strings

A string is a **Finger Tree of UTF-8 chars**.

```
type_hash = sha256("dacite.core/string")
```

### Blobs

A blob is a **Finger Tree of bytes**.

```
type_hash = sha256("dacite.core/blob")
```

### Vectors

A vector is a **Finger Tree of arbitrary values**.

```
type_hash = sha256("dacite.core/vector")
```

### Maps

A map is a **HAMT (Hash Array Mapped Trie)** with 32-way branching.

- Keys and values can be any Dacite value
- Key position determined by key's value_hash
- 5-bit chunks of hash → 32-way branching per level

```
type_hash = sha256("dacite.core/map")
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

- [Hash Array Mapped Tries](https://en.wikipedia.org/wiki/Hash_array_mapped_trie)
- [Finger Trees](https://en.wikipedia.org/wiki/Finger_tree)
- [Content-addressable storage](https://en.wikipedia.org/wiki/Content-addressable_storage)
