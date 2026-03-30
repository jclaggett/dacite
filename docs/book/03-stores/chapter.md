# Chapter 3: Stores

Chapter 2 gave us a complete data model — immutable, content-addressed values with O(1) metadata and clean APIs. But those values live in memory. What happens when you need persistence, distribution, or lazy loading across machines?

This chapter adds **stores** — the persistence layer. A store is a content-addressed key-value system where keys are hashes and values are serialized nodes. Stores compose hierarchically: memory → disk → peers → origin server. Reads walk layers top-down; writes propagate everywhere.

## 3.1 The Store Protocol

Every store implements a minimal protocol:

```
get(hash) → Value | nil
put(hash, value) → Store     // idempotent
has?(hash) → bool
snapshot() → {hash: value}
merge({hash: value}) → Store // bulk insert
reset() → Store              // clear all
```

`put` is idempotent — storing the same content twice is a no-op. Values are serialized bytes; the hash is computed from the logical content, not the serialized form (Chapter 2).

This protocol is **language-agnostic**. Clojure, Rust, TypeScript — all implement the same six functions.

## 3.2 Store Implementations

Stores form a hierarchy, composed as layers:

### Memory Store

An in-memory map: `{hash → serialized-bytes}`. Fast reads/writes, ephemeral. Default for testing and construction.

### File Store (LMDB/Filetree)

Content-addressed filesystem:
- Hashes shard to paths: `base/ab/cdef...`
- LMDB for metadata/indexing, files for blobs
- Durable, but slower than memory

### Layered Store

Composes stores with read-through semantics:

```
Layered([Mem, LMDB, Remote])
```

- **Reads**: Walk top-down, return first hit
- **Writes**: Propagate to all layers
- **Result**: Transparent caching. Misses in memory fetch from disk/network, cache locally.

A remote peer slots in naturally — local layers cache remote fetches automatically.

## 3.3 CacheMap Abstraction

Data structures operate on `[cachemap, root-hash]` tuples. CacheMap wraps any store:

```
cachemap-get(cm, hash) → Value    // lazy fetch
cachemap-assoc(cm, hash, value) → CacheMap  // write-through
cachemap-merge(cm1, cm2) → CacheMap        // share backing store
```

Operate on values as if fully in-memory. Traversals fetch on-demand. Multiple CacheMaps sharing a store see consistent state.

This enables:
- **Lazy loading** — traverse only accessed paths
- **Bounded memory** — evict aggressively, re-fetch on miss
- **Transparency** — same code for memory/disk/remote

## 3.4 Serialization

Stores hold serialized bytes. Dacite defines two formats:

### Binary (Canonical)

Authoritative for hashing/storage. Deterministic, compact, streaming.

```
node = kind-tag (1 byte) + fields
```

**Scalar**: `0x00 + u8(len) + bytes[len]` (max 255 bytes)

**Seq Node**: `0x01 + u8(subtype) + measure (48 bytes) + u8(n-children) + hashes[n]`

**Map Node**: `0x02 + u8(subtype) + measure + type-specific`

**Collection Header**: `0x03 + u8(type) + root-hash + u64(count) + u64(size_bytes)` (50 bytes fixed)

Measures inline: `u64(count) + u64(size_bytes) + hash(32 bytes)`.

Nodes fit in ~1 KB. No unbounded structures.

### JSON (Debug/Interop)

Human-readable, materialized or structural modes. Validates against schemas: `structural.schema.json`, `materialized.schema.json`.

Round-trips preserve hashes.

## 3.5 Distribution Model

Immutable hashes enable **perfect caching** — no invalidation needed.

### Adaptive Fetch

Server uses `size_bytes` to choose response mode:

```
GET /node/{hash}?inline_under=1024&leaf_chunk=4096
```

| Condition | Response |
|-----------|----------|
| `size_bytes ≤ inline_under` | Inline scalars |
| else | Structure (hashes only) |
| uniform scalar leaves | Coalesced chunks |

Client controls thresholds. Blobs/strings fetch as single chunks.

### Sync Protocol

1. Announce root hash
2. Compare roots
3. Walk tree, fetch unknown hashes
4. Skip unchanged subtrees

### Peer Model

Stores layer as: `local-mem → local-disk → peers → origin`. Peers discover via root hashes. No central index — hashes are the index.

## 3.6 Retention and Eviction

Stores are caches at every layer. Evict freely — immutable data re-fetches identically.

**Root pinning**: Mark roots non-evictable (reachable nodes protected).

**Purge**: Delete root. Orphans evict naturally. Shared nodes survive.

## 3.7 API Surface

### Primitives (IStore)

| Function | Signature | Description |
|----------|-----------|-------------|
| `get` | `hash → Value|nil` | Fetch serialized value |
| `put` | `(hash, Value) → Store` | Store (idempotent) |
| `has?` | `hash → bool` | Exists? |
| `snapshot` | `→ {hash: Value}` | All entries |
| `merge` | `{hash: Value} → Store` | Bulk insert |
| `reset` | `→ Store` | Clear |

### Derived (Layered/CacheMap)

| Function | Derivation | Description |
|----------|------------|-------------|
| `layered` | `[Store...] → Store` | Compose layers |
| `cachemap` | `Store → CacheMap` | Lazy assoc/get wrapper |
| `serialize-binary` | `Value → bytes` | Canonical bytes |
| `deserialize-binary` | `bytes → Value` | Reconstruct from bytes |
| `to-json` | `(Value, mode) → string` | Structural/materialized JSON |

### Properties

- `put(h, v); get(h) = v`
- `put(h, v); put(h, v)` idempotent
- Layered reads top-down, writes everywhere
- `cachemap-get(assoc(cm, h, v), h) = v`
- Binary round-trip preserves hash
- JSON round-trip preserves hash

**Depends on Layers 1-2.** First with I/O/state.

## 3.8 What This Layer Provides

1. **Persistence** — values survive restarts
2. **Distribution** — compose local/remote transparently
3. **Laziness** — fetch on-demand, O(1) metadata skips subtrees
4. **Caching** — eternal validity, hierarchical layers
5. **Portability** — IStore protocol language-agnostic

Chapter 4 adds authorization: proof of possession and authenticated stores.
