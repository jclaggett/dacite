# Appendix: Serialization

This appendix defines the canonical binary format used for storage and network transfer in Dacite. It is intentionally low-level and reference-oriented.

## Binary Format

Every serialized node begins with a 1-byte **kind tag**:

| Tag | Kind          | Description                     |
|-----|---------------|---------------------------------|
| 0x00 | Scalar       | Raw bytes (atomic value)       |
| 0x01 | Seq node     | Finger tree internal node      |
| 0x02 | Map node     | HAMT internal node             |
| 0x03 | Collection   | Top-level typed collection header |

### Scalar

```
scalar = 0x00 ++ u8(len) ++ bytes[len]
```

Max size: 255 bytes. Hash = `fuse_bytes(raw bytes)` (framing is *not* part of the hash).

### Measure (common)

Appears in seq and map nodes:

```
measure = u64(count) ++ u64(size_bytes) ++ hash(elements_fuse)
```

(8 + 8 + 32 = 48 bytes)

### Seq Nodes (kind 0x01)

```
seq_node = 0x01
        ++ u8(subtype)
        ++ measure
        ++ u8(n_children)
        ++ hash[n_children]
```

**Subtypes:**
- 0x00: empty
- 0x01: single
- 0x02: digit (1–32 children)
- 0x03: internal node (2–32 children)
- 0x04: deep (left, spine, right)

### Map Nodes (kind 0x02)

```
map_node = 0x02
        ++ u8(subtype)
        ++ measure
        ++ ... (type specific)
```

**Subtypes:**
- 0x00: empty
- 0x01: entry (`key_hash ++ key_ref ++ val_ref`)
- 0x02: bitmap (`u32(bitmap) ++ u8(n) ++ hash[n]`)

### Collections (kind 0x03)

```
collection = 0x03
          ++ u8(collection_type)
          ++ hash(root)
          ++ u64(count)
          ++ u64(size_bytes)
```

**Collection types:**
- 0x00: vector
- 0x01: string
- 0x02: blob
- 0x03: map

The collection header is always exactly 50 bytes.

## Hash Computation Rules

Hashes are computed from *logical content*, not serialized bytes:

- Scalar: `fuse_bytes(data)`
- Typed value: `fuse(fuse_str(type_name ++ 0x00), data_hash)`
- Seq/Map node: `fuse(fuse_str(node_type ++ 0x00), elements_fuse)` (with HAMT bitmap exception)

---

*Extracted and adapted from old SPEC.md. This file is the new canonical reference for wire/storage format.*

