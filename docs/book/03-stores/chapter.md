# Chapter 3: Stores

Chapter 2 gave us a complete data model — immutable, content-addressed values with O(1) metadata and clean APIs. But those values live in a cache (a plain Clojure map in an atom). What happens when you need persistence, distribution, or lazy loading across machines?

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

### Memory Store

An in-memory atom over a map: `{hash → serialized-value}`. Fast reads/writes, ephemeral. Default for testing and construction.

A MemStore's internal atom is shared directly with the Layer 2 cache (§3.3), so value constructors and store operations see the same data with zero synchronization overhead.

### File Store

Content-addressed filesystem with directory sharding:

```
base/ab/cd/abcdef....edn
```

Each hash maps to a two-level directory structure. Values stored as EDN. Durable, but slower than memory.

### LMDB Store

LMDB-backed persistent store with optional meta database:

- **Data db**: `hash → serialized-value` (32-byte keys, EDN values)
- **Meta db**: `string-key → value` (for root hashes, metadata)

Supports configurable max size, database names. Requires explicit `lmdb-close` when done.

### Layered Store

Composes stores with read-through semantics:

```
(layered-store (mem-store) (lmdb-store "/tmp/dacite"))
```

- **Reads**: Walk top-down, return first hit
- **Writes**: Propagate to all layers
- **Result**: Transparent caching. Misses in memory fetch from disk/network, cache locally.

A remote peer slots in naturally — local layers cache remote fetches automatically.

## 3.3 Cache Bridge

Layer 2 values operate on a cache — a dynamic var `*cache*` holding an atom over a plain map. Layer 3 bridges stores to this cache so that value operations and store operations stay in sync.

### How the Bridge Works

The convenience functions in the store namespace write to **both** cache and store:

- `get-store` checks the cache first, falls through to the store on miss, and populates the cache on hit
- `put-store!` writes to both cache and store
- `merge-store!` merges into both

For MemStores, the bridge is zero-overhead: the store's internal atom **is** the cache atom. No copying, no synchronization. For other store types (File, LMDB, Layered), a separate cache atom is created from a snapshot.

### Binding Stores

Any code that rebinds `*store*` must also rebind `*cache*` to keep them in sync. Two macros handle this:

**`bind-store`** — binds both `*store*` and `*cache*` for the duration of body:

```clojure
(store/bind-store my-store
  (d/hash-map "key" "value"))
```

**`with-store`** — creates an isolated store context, returns `[snapshot result]`:

```clojure
(store/with-store [s (mem-store)]
  (d/hash-map "key" "value"))
;; => [{hash1 [...], hash2 [...], ...} <DaciteMap>]
```

Never use `(binding [store/*store* ...])` directly — use `bind-store` or `with-store` instead.

## 3.4 Serialization

Stores hold serialized values. Dacite defines two formats:

### Binary (Canonical)

Authoritative for hashing/storage. Deterministic, compact, streaming.

```
node = kind-tag (1 byte) + fields
```

**Scalar**: `0x00 + u8(type-len) + type-bytes + u8(val-len) + val-bytes`

**Seq Node**: `0x01 + u8(subtype) + measure (48 bytes) + u8(n-children) + hashes[n]`

**Map Node**: `0x02 + u8(subtype) + type-specific fields`

**Collection Header**: `0x03 + u8(type) + root-hash + u64(count) + u64(size_bytes)`

Measures are 48 bytes: `u64(count) + u64(size_bytes) + hash(32 bytes)`.

Nodes fit in ~1 KB. No unbounded structures.

See [Appendix: Serialization](../appendices/serialization.md) for the complete binary format specification.

### JSON (Interop)

Round-trips through `clj->dac` / `dac->clj` and Cheshire. Preserves hashes through the value layer's content-addressing.

## 3.5 Distribution Model (Future)

> **Not yet implemented.** This section describes the target design.

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

## 3.6 Retention and Eviction (Future)

> **Not yet implemented.** This section describes the target design.

Stores are caches at every layer. Evict freely — immutable data re-fetches identically.

**Root pinning**: Mark roots non-evictable (reachable nodes protected).

**Purge**: Delete root. Orphans evict naturally. Shared nodes survive.

## 3.7 API Surface

### Primitives (IStore)

| Function | Signature | Description |
|----------|-----------|-------------|
| `get` | `hash → Value\|nil` | Fetch serialized value |
| `put` | `(hash, Value) → Store` | Store (idempotent) |
| `has?` | `hash → bool` | Exists? |
| `snapshot` | `→ {hash: Value}` | All entries |
| `merge` | `{hash: Value} → Store` | Bulk insert |
| `reset` | `→ Store` | Clear |

### Convenience (cache-bridged)

| Function | Description |
|----------|-------------|
| `get-store` | Cache-first lookup, falls through to store |
| `put-store!` | Write to both cache and store |
| `merge-store!` | Bulk write to both |
| `snapshot-store` | Cache snapshot |
| `bind-store` | Bind `*store*` + `*cache*` together |
| `with-store` | Isolated store context, returns `[snapshot result]` |

### Constructors

| Function | Description |
|----------|-------------|
| `mem-store` | In-memory atom-backed store |
| `file-store` | Filesystem with directory sharding |
| `lmdb-store` | LMDB-backed persistent store |
| `layered-store` | Compose stores with read-through |

### Serialization

| Function | Description |
|----------|-------------|
| `serialize` | Value → canonical bytes |
| `deserialize` | Bytes → value |
| `json->dacite` | JSON string → Dacite value |
| `dacite->json` | Dacite value → JSON string |

### Properties

- `put(h, v); get(h) = v`
- `put(h, v); put(h, v)` idempotent
- Layered reads top-down, writes everywhere
- Binary round-trip preserves hash
- JSON round-trip preserves hash
- Cache and store always in sync within a binding context

**Depends on Layers 1–2.** First layer with I/O and state.

## 3.8 What This Layer Provides

1. **Persistence** — values survive restarts (LMDB, file store)
2. **Distribution** — compose local/remote transparently (future)
3. **Laziness** — fetch on-demand, O(1) metadata skips subtrees
4. **Caching** — eternal validity, hierarchical layers
5. **Portability** — IStore protocol is language-agnostic
6. **Cache bridge** — Layer 2 values work transparently with any store backend

Chapter 4 adds authorization: proof of possession and authenticated access.
