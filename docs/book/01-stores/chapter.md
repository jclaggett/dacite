# Chapter 1: Stores

This chapter introduces **stores** — the persistence layer at the bottom of Dacite. A store is two things at once:

1. A **content-addressed map** from 256-bit hashes to typed, serialized entries
2. A **mutable root** (via Clojure's `IRef`) that points at one hash in that map

Each entry in a store is a pair: `hash → [type, bytes]`. The **type** is a primitive type tag from a closed set that tells you how to interpret the bytes. The **bytes** are the type-specific payload.

Chapter 2 explains how hashes are computed. Chapter 3 defines the value model built on top of stores. Here we stay at the persistence layer: entry types, the `IStore` protocol, implementations, and sync.

## 1.1 Store Entries

Every entry in a store has the same structure:

```
hash → [type, bytes]
```

- **hash**: A 256-bit content hash (4 × 64-bit integers)
- **type**: A primitive type tag from the closed set defined below
- **bytes**: The serialized payload, whose format depends on the type

This is self-describing at the structural level. Given a hash, you can fetch its type without deserializing the payload. Given the type, you know how to deserialize the bytes.

### Primitive Types (Closed Set)

The primitive types are partitioned into three categories:

**Scalars** — atomic values with no internal structure:
- `:null` — empty, no bytes
- `:bool` — 1 byte (0x00 or 0x01)
- `:i8`, `:i16`, `:i32`, `:i64` — signed integers, big-endian, fixed width
- `:u8`, `:u16`, `:u32`, `:u64` — unsigned integers, big-endian, fixed width
- `:f32`, `:f64` — IEEE 754 floats, big-endian
- `:char` — Unicode scalar value, UTF-8 encoded

**Sequence Nodes** — internal nodes of finger trees (used by vectors, strings, blobs):
- `:ft-empty` — empty sequence
- `:ft-single` — single element
- `:ft-digit` — small sequence fragment (2-4 elements)
- `:ft-node` — internal tree node
- `:ft-deep` — deep tree with left/right spines

**Map Nodes** — internal nodes of hash array mapped tries (used by maps and sets):
- `:hamt-empty` — empty map
- `:hamt-entry` — single key-value entry
- `:hamt-bitmap` — compressed branching node

**Compound** — the bridge from storage to value types:
- `:compound` — a user-facing value (vector, string, blob, map, set) whose specific type is identified by a type hash in its payload

### Binary Format

Each entry is serialized to bytes as:

```
u8(type) + payload
```

The type byte is a single unsigned byte encoding the primitive type. The payload format is type-specific:

- **Scalars**: Fixed-width encoding (e.g., `:i64` → 8 bytes big-endian)
- **Sequence nodes**: Measure (48 bytes) + child hashes (variable)
- **Map nodes**: Measure (48 bytes) + type-specific fields
- **Compound**: Type hash (32 bytes) + root hash (32 bytes) + count (u64) + size-bytes (u64)

The type byte values are assigned in ranges:

```
0x00-0x0F    scalars
0x10-0x1F    sequence nodes
0x20-0x2F    map nodes
0x30-0x3F    compound and reserved
```

### Compound Entries and Value Types

A `:compound` entry carries a **type hash** that identifies its semantic type:

```clojure
;; Compound payload structure
{:type-hash hash    ;; hash of the type definition
 :root hash        ;; hash of the root structural node
 :count u64         ;; element count
 :size-bytes u64}   ;; total serialized size
```

The type hash is a content hash of a type definition. For built-in types, these are precomputed from canonical string representations:

- `"vector"` → a well-known hash
- `"string"` → a well-known hash
- `"blob"` → a well-known hash
- `"map"` → a well-known hash
- `"set"` → a well-known hash

In the future, user-defined types work the same way: store a type definition, get its hash, use that hash in compound entries. The storage layer does not distinguish built-in from user-defined types — it only sees `:compound` entries with type hashes.

## 1.2 Store as Ref

A store implements `clojure.lang.IRef`. You use `deref`, `swap!`, and `reset!` to read and update the current root hash:

```clojure
(def store (dacite/store {:backend (dacite/lmdb "path/to/db")}))

@store
;; => nil or a 4-long hash vector (the current root)

;; Install a new root by hash
(reset! store root-hash)

;; Transform the current root hash
(swap! store (fn [h] (compute-new-root h)))
```

The store is a **ref of a root hash**, not a ref of a Clojure map. Higher-level APIs (Chapter 3) wrap this: they build nodes, store them via `IStore`, and return hashes you can install as the root. Applications needing multiple named roots build that layer themselves.

## 1.3 Store Protocol

Underneath the ref interface, every store implements `IStore` for content-addressed access:

```clojure
(defprotocol IStore
  (fetch [store hash] "Return [type bytes] or nil")
  (store [store type bytes] "Store [type bytes], return content hash")
  (s-type [store hash] "Return primitive type for hash, or nil")
  (s-has? [store hash] "Check if hash exists in backing storage"))
```

Typical usage at this layer:

```clojure
;; Store a typed entry
(def h (store store :i64 (encode-i64 42)))

;; Fetch back
(fetch store h)   ;; => [:i64 <8 bytes>]
(s-type store h)  ;; => :i64
(s-has? store h)  ;; => true
```

Application code usually works through `@store`, `swap!`, and `reset!` on the root, or through value constructors (Chapter 3) that call `store` internally. The public value API always takes `store` as its first argument so persistence stays explicit.

## 1.4 Root-Centric Operation

All interaction with a store flows through its root hash:

```clojure
;; Read current root
(def root @store)

;; Replace root entirely
(reset! store new-root-hash)

;; Transform root (e.g. after building a successor node)
(swap! store (fn [old-root] (build-successor store old-root)))
```

The root entry lives in the backing map at the root hash, just like any other entry. Updating the root does not delete old entries immediately — they become detached until GC runs.

## 1.5 Detached Nodes and Garbage Collection

Any entry reachable from the current root is **live**. Entries that were previously reachable but are no longer part of the root tree are **detached**. They remain in the backing storage until garbage collection runs.

Because entries are self-describing (they carry their type), GC can traverse the tree without external knowledge: given a hash, fetch its type, deserialize just enough to find child hashes, recurse.

## 1.6 Store Implementations

### Memory Store
An atom-backed store. Fast, ephemeral, ideal for testing and construction.

```clojure
(def store (dacite/store {:backend (dacite/memory)}))
```

### LMDB Store
Persistent store using LMDB. The root hash is kept in a small meta database; content lives in the primary data database. Survives restarts.

```clojure
(def store (dacite/store {:backend (dacite/lmdb "path/to/db")}))
```

### Layered Store
Composes multiple stores as a stack. Reads check layers top-down; writes go to all layers by default. Provides transparent caching without changing the ref-based API.

```clojure
(def store (dacite/store {:backend [(dacite/memory)
                                    (dacite/lmdb "path/to/db")]
                          :write-policy :push-all}))
```

**Read**: walk down until found  
**Write** (`swap!`, `reset!`): policy-dependent

#### Write Policies

| Policy | Behavior |
|--------|----------|
| `:push-all` | Write to all layers (default) |
| `:top-only` | Write only to top layer (overlay/COW semantics) |
| custom fn | `(fn [root-hash layers] ...)` returns updated layers |

Two stores are **compatible** if they share the same protocol identifier for hashing (Chapter 2).

## 1.7 Ref Push and Sync

Stores push their root ref to other stores. This is the primitive for asynchronous behavior and synchronization:

```clojure
;; Explicit push
(dacite/push-ref source-store target-store)
```

After a push, the target store's root is `reset!` to the source's current root hash. The underlying storage must already contain the objects (or be synced separately).

Push is intentionally simple: it sends a hash. Applications decide when and where to push. Ref management is an application concern, not a store concern.

## 1.8 What This Layer Provides

1. A single, well-defined root per store, accessed via `IRef`
2. Self-describing entries: `hash → [type, bytes]`
3. A closed set of primitive types for scalars, internal nodes, and compound values
4. Content-addressed `fetch` / `store` / `s-type` / `s-has?`
5. Composable implementations (memory, disk, layered)
6. Ref push as a sync primitive

Chapter 2 defines how node hashes are formed. Chapter 3 builds the value model on top of this layer. Chapter 4 adds authorization on top of the root model.
