# Chapter 1: Content Stores

This chapter introduces the **content store** — the persistence layer at the bottom of Dacite. A content store is a single, simple thing:

> An immutable, content-addressed dictionary: `hash → value`.

Each entry maps a 256-bit content hash to a stored value. The store knows nothing about the structure or meaning of what it holds — it maps hashes to opaque payloads and nothing more. It has no notion of a "current" value, no mutable state, and no root; it only ever grows as new entries are added.

That deliberate simplicity is what the rest of Dacite builds on. Chapter 2 explains how hashes are computed. Chapter 3 defines the value model — trees of nodes that live as entries in a content store. Chapter 4 adds a single mutable **root** on top of a content store, turning this immutable dictionary into something that can evolve and synchronize over time. Here we stay at the immutable persistence layer: the `IStore` protocol, its implementations, and content addressing.

## 1.1 Store Entries

Every entry in a content store has the same shape:

```
hash → value
```

- **hash**: A 256-bit content hash (4 × 64-bit integers). How it is derived from the content is Chapter 2's subject.
- **value**: An opaque serialized payload. The store neither knows nor interprets its structure.

The store is a pure key/value mapping. All interpretation of a stored entry happens in higher layers (Chapter 3). Because the key is a hash *of* the value, entries are **immutable**: a given hash always maps to the same content, so writing the same content twice is idempotent and two callers that produce the same content share one entry.

> **Representation note.** Conceptually an entry's value is opaque bytes. The reference implementation currently stores serialized Dacite node values (EDN), and the LMDB backend keeps the bytes of that serialization. A true opaque byte-array representation is a later refinement tied to the serialization format (see the serialization appendix); it does not change the content-store contract described here.

## 1.2 The Store Protocol

Every content store implements `IStore`:

```clojure
(defprotocol IStore
  (s-get [store hash]  "Return the stored value, or nil")
  (s-put [store hash value] "Store value at hash, return the store")
  (s-has? [store hash] "Check if hash exists")
  (s-snapshot [store] "Return a map of all {hash → value}")
  (s-merge [store m] "Merge {hash → value} into the store")
  (s-reset [store] "Clear all entries"))
```

Typical usage at this layer:

```clojure
;; Store a value at its hash
(s-put store h node)

;; Fetch it back
(s-get store h)   ;; => node, or nil if absent
(s-has? store h)  ;; => true
```

Application code rarely calls `s-get` / `s-put` directly. Instead it uses the value constructors of Chapter 3, which persist nodes into a store on your behalf. The public value API always takes a store (implicitly via a current-store binding, or explicitly as the first argument) so that persistence stays visible rather than hidden.

## 1.3 Content Addressing

Because a store is addressed by content hash, it has two useful properties for free:

- **Deduplication.** Identical content produces an identical hash, which maps to a single entry. Storing the same node from two places costs one entry, not two.
- **Idempotent writes.** `s-put` of content that is already present is a no-op in effect — the hash and value are unchanged.

```clojure
(s-put store h node)
(s-put store h node)   ;; same hash, same value — still one entry
```

This is the foundation that lets higher layers share structure aggressively: an edited collection reuses every unchanged node, because unchanged nodes hash the same and therefore *are* the same entry.

## 1.4 Implementations

All content stores implement `IStore`, so they are interchangeable. Each has its own constructor function — there is no single unified wrapper; you pick a backend by calling its constructor.

### Memory Store
An atom-backed store. Fast, ephemeral, ideal for testing and for building values before they are persisted elsewhere.

```clojure
(def s (store/mem-store))
```

### File Store
Filesystem persistence, one file per entry, with two levels of directory sharding by hash prefix to keep directories small. Survives restarts.

```clojure
(def s (store/file-store "path/to/dir"))
```

### LMDB Store
Persistent store backed by LMDB. Content entries live in the primary database. (A small meta database is also created; rooted stores in Chapter 4 use it to persist a root hash — the content store itself never touches it.)

```clojure
(def s (store/lmdb-store "path/to/db"))
```

### Layered Store
Composes several stores into a stack, fastest first (e.g. memory in front of LMDB). It provides transparent caching without changing the `IStore` API.

```clojure
(def s (store/layered-store (store/mem-store)
                            (store/lmdb-store "path/to/db")))
```

- **Read** (`s-get`): walk layers front to back until a layer has the entry. On a hit in a slower layer, the entry is **read through** — backfilled into the faster layers it was missing from — so repeat reads are fast.
- **Write** (`s-put`, `s-merge`): write to **all** layers. This keeps the durable back layer authoritative and the fast front layer warm.

A single write policy (write-to-all) is intentional at this layer; richer per-layer policies are out of scope for the content store.

## 1.5 What This Layer Provides

1. An immutable, content-addressed dictionary: `hash → value`.
2. A minimal protocol — `s-get` / `s-put` / `s-has?` / `s-snapshot` / `s-merge` / `s-reset`.
3. Content addressing, and with it free deduplication and idempotent writes.
4. Composable backends: memory, file, LMDB, and layered caching — all interchangeable.

There is deliberately **no** mutable state here: no current value, no root. That single moving part is added in Chapter 4.

Chapter 2 defines how the hashes that key this dictionary are formed. Chapter 3 builds the value model whose nodes live as entries in a content store. Chapter 4 wraps a content store with a mutable root to make it evolve and synchronize.
