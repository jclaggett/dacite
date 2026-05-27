# Chapter 1: Stores

This chapter introduces **stores** — the persistence layer at the bottom of Dacite. A store is two things at once:

1. A **content-addressed map** from 256-bit hashes to serialized node bytes
2. A **mutable root** (via Clojure's `IRef`) that points at one hash in that map

The root node is stored in the backing map like any other entry. The store's ref holds only the root hash. Like a filesystem, every store has exactly one current root. The backing storage may contain many other nodes not reachable from the root; those are **detached** and eligible for garbage collection.

Chapter 2 explains how hashes are computed. Chapter 3 defines the value model built on top of stores. Here we stay at the persistence layer: refs, the `IStore` protocol, implementations, and sync.

## 1.1 Store as Ref

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

## 1.2 Store Protocol

Underneath the ref interface, every store implements `IStore` for content-addressed access:

```clojure
(defprotocol IStore
  (fetch [store hash] "Return serialized bytes or nil")
  (store [store bytes] "Store bytes, return content hash")
  (s-has? [store hash] "Check if hash exists in backing storage"))
```

Typical usage at this layer:

```clojure
(def h (store store serialized-bytes))
(fetch store h)   ;; => serialized-bytes
(s-has? store h)  ;; => true
```

Application code usually works through `@store`, `swap!`, and `reset!` on the root, or through value constructors (Chapter 3) that call `store` internally. The public value API always takes `store` as its first argument so persistence stays explicit.

## 1.3 Root-Centric Operation

All interaction with a store flows through its root hash:

```clojure
;; Read current root
(def root @store)

;; Replace root entirely
(reset! store new-root-hash)

;; Transform root (e.g. after building a successor node)
(swap! store (fn [old-root] (build-successor store old-root)))
```

The root's serialized bytes live in the backing map at the root hash, just like any other node. Updating the root does not delete old nodes immediately — they become detached until GC runs.

## 1.4 Detached Nodes and Garbage Collection

Any node reachable from the current root is **live**. Nodes that were previously reachable but are no longer part of the root tree are **detached**. They remain in the backing storage until garbage collection runs.

## 1.5 Store Implementations

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

## 1.6 Ref Push and Sync

Stores push their root ref to other stores. This is the primitive for asynchronous behavior and synchronization:

```clojure
;; Explicit push
(dacite/push-ref source-store target-store)
```

After a push, the target store's root is `reset!` to the source's current root hash. The underlying storage must already contain the objects (or be synced separately).

Push is intentionally simple: it sends a hash. Applications decide when and where to push. Ref management is an application concern, not a store concern.

## 1.7 What This Layer Provides

1. A single, well-defined root per store, accessed via `IRef`
2. Simple `swap!` / `reset!` on the root hash
3. Content-addressed `fetch` / `store` / `s-has?` underneath
4. Composable implementations (memory, disk, layered)
5. Ref push as a sync primitive

Chapter 2 defines how node hashes are formed. Chapter 3 builds the value model on top of this layer. Chapter 4 adds authorization on top of the root model.
