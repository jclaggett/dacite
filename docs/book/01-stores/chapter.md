# Chapter 3: Stores

Chapter 2 gave us a complete data model — immutable, content-addressed values. Those values need a place to live. This chapter introduces **stores** — the persistence layer that holds values and provides a single mutable **root** via Clojure's `IRef` interface.

A store is a ref whose dereferenced value is a Dacite value (typically a map). Like a filesystem, every store has exactly one current root value. The store's backing storage may contain many other nodes not reachable from the root; those are simply detached and eligible for future garbage collection.

## 3.1 Store as Ref

A store implements `clojure.lang.IRef`. You use `deref`, `swap!`, and `reset!` to interact with it:

```clojure
(def store (dacite/store {:backend (dacite/lmdb "path/to/db")}))

@store
;; => #dacite/map{}  (or nil if empty)

(swap! store assoc "users" (dacite/vector store [...]))
(reset! store (dacite/hash-map store :a 1))
```

The store is a **ref of a Dacite value**, not a ref of a map of refs. Applications needing multiple named roots build that layer themselves.

## 3.2 Value Constructors Take a Store

All Dacite value constructors take the store as their first argument:

```clojure
(dacite/vector store 1 2 3)
(dacite/hash-map store :a 1 :b 2)
(dacite/string store "hello")
```

This makes the store dependency explicit. For convenience, use `partial`:

```clojure
(def my-vec (partial dacite/vector store))
(def my-map (partial dacite/hash-map store))

(my-vec 1 2 3)
(my-map :x 42)
```

## 3.3 Store Protocol

Underneath the ref interface, every store implements `IStore` for content-addressed access:

```clojure
(defprotocol IStore
  (fetch [store hash] "Return serialized bytes or nil")
  (store [store value] "Store value, return its hash")
  (s-has? [store hash] "Check if hash exists in backing storage"))
```

Application code rarely uses these directly. The primary surface is `@store`, `swap!`, and `reset!`.

## 3.4 Root-Centric Operation

All interaction with a store flows through its root:

```clojure
;; Read-transform-write cycle
(swap! store assoc-in ["config" "version"] 2)

;; Reset to a fresh value
(reset! store (dacite/hash-map store "users" [] "posts" []))
```

The root value is stored in the backing content-addressed storage just like any other value. The store's ref holds its hash.

## 3.5 Detached Nodes and Garbage Collection

Any node reachable from the current root is live. Nodes that were previously reachable but are no longer part of the root tree are **detached**. They remain in the backing storage until garbage collection runs.

## 3.6 Store Implementations

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

## 3.7 Ref Push and Sync

Stores push their root ref to other stores. This is the primitive for asynchronous behavior and synchronization:

```clojure
;; Explicit push
(dacite/push-ref source-store target-store)
```

After a push, the target store's root is `reset!` to the source's current root hash. The underlying storage must already contain the objects (or be synced separately).

Push is intentionally simple: it sends a hash. Applications decide when and where to push. Ref management is an application concern, not a store concern.

## 3.8 What This Layer Provides

1. A single, well-defined root per store, accessed via `IRef`
2. Simple `swap!` / `reset!` operations
3. Explicit store parameter in constructors
4. Content-addressed storage underneath
5. Composable implementations (memory, disk, layered)
6. Ref push as a sync primitive

Chapter 4 adds authorization on top of this root model.
