# Chapter 1: Stores

This chapter introduces **stores** — the persistence layer at the bottom of Dacite. A store is two things at once:

1. A **content-addressed map** from 256-bit hashes to byte arrays
2. A **mutable root** (via Clojure's `IRef`) that points at one hash in that map

Each entry in a store is a pair: `hash → bytes`. The store knows nothing about the structure or meaning of the bytes — it simply maps hashes to opaque values.

Chapter 2 explains how hashes are computed. Chapter 3 defines the value model built on top of stores. Here we stay at the persistence layer: the `IStore` protocol, implementations, and sync.

## 1.1 Store Entries

Every entry in a store has the same structure:

```
hash → bytes
```

- **hash**: A 256-bit content hash (4 × 64-bit integers)
- **bytes**: An opaque byte array. The store neither knows nor interprets its structure.

The store is a pure key/value mapping. All interpretation of the stored bytes happens in higher layers (Chapter 3).

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
  (s-get [store hash] "Return bytes or nil")
  (s-put [store hash bytes] "Store bytes at hash, return store")
  (s-has? [store hash] "Check if hash exists")
  (s-snapshot [store] "Return map of all {hash → bytes}")
  (s-merge [store m] "Merge {hash → bytes} into store")
  (s-reset [store] "Clear all entries"))
```

Typical usage at this layer:

```clojure
;; Store a value
(def h (hash-of-value))
(s-put store h some-bytes)

;; Fetch back
(s-get store h)   ;; => some-bytes
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

GC is a Chapter 3 concern: given a hash, the value layer knows how to deserialize the bytes, discover child references, and recurse. The store itself knows nothing about structure.

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
2. Opaque entries: `hash → bytes`
3. Content-addressed `s-get` / `s-put` / `s-has?` / `s-snapshot` / `s-merge` / `s-reset`
4. Composable implementations (memory, disk, layered)
5. Ref push as a sync primitive

Chapter 2 defines how hashes are formed. Chapter 3 builds the value model on top of this layer. Chapter 4 adds authorization on top of the root model.
