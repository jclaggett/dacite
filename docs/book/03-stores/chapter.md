# Chapter 3: Stores

Chapter 2 gave us a complete data model — immutable, content-addressed values. Those values need a place to live. This chapter introduces **stores** — the persistence layer that holds values and defines a single current **root**.

A store is defined by its root. Like a filesystem, every store has exactly one current root value (typically a map). The store may contain many other nodes that are not reachable from the root; those are simply detached and eligible for future garbage collection.

## 3.1 The Store Protocol

Every store implements a minimal protocol:

```clojure
(defprotocol IStore
  (fetch [store hash] "Return serialized bytes or nil")
  (store [store value] "Store value, return its hash")
  (get-root [store] "Return the current root value")
  (set-root [store new-root] "Replace the root, return updated store"))
```

`fetch` and `store` are the low-level content-addressed operations. Application code rarely uses them directly. The primary surface is `get-root` and `set-root`.

## 3.2 Root-Centric Operation

All interaction with a store flows through its root:

```clojure
(let [s (mem-store)
      initial (d/hash-map "config" (d/hash-map "version" 1))]
  (set-root s initial)
  (get-root s))
```

Higher-level operations are built on top of this pattern:

1. Read the current root with `get-root`
2. Perform a pure transformation on the value
3. Write the new root back with `set-root`

This keeps the store itself simple: a root pointer plus a content-addressed collection of nodes.

## 3.3 Detached Nodes and Garbage Collection

Any node reachable from the current root is live. Nodes that were previously reachable but are no longer part of the root tree are **detached**. They remain in the store until garbage collection runs. This is the normal state of a long-lived store.

## 3.4 Store Implementations

### Memory Store
An atom-backed store. Fast, ephemeral, ideal for testing and construction. The root and all reachable nodes live in memory.

### LMDB Store
Persistent store using LMDB. The root hash is kept in a small meta database; content lives in the primary data database. Survives restarts.

### Layered Store
Composes multiple stores (e.g., memory over LMDB). Reads check layers top-down; writes propagate to all layers. Provides transparent caching without changing the root-centric API.

## 3.5 What This Layer Provides

1. A single, well-defined root for every store
2. Simple `get-root` / `set-root` operations
3. Content-addressed storage underneath
4. Natural support for detached nodes and future GC
5. Composable implementations (memory, disk, layered)

Chapter 4 adds authorization on top of this root model.