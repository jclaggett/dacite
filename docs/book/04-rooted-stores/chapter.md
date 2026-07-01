# Chapter 4: Rooted Stores

The first three chapters describe an entirely immutable world. A content store (Chapter 1) is a dictionary that only grows; hash fusion (Chapter 2) gives every piece of content a permanent identity; values (Chapter 3) are trees of nodes stored under those hashes. Nothing there ever changes — a value, once stored, is stored forever.

But useful systems change over time. A configuration is edited, a document is revised, a peer learns that "the current state" is now something new. Dacite expresses all of that with **one** mutable cell layered on top of the immutable world:

> A **rooted store** wraps a content store and adds a single mutable **root hash** — a reference that points at one value in the store.

Everything underneath stays immutable. The root is the only thing that moves. "Changing" the data means computing a new value (a new tree of immutable nodes, sharing all the unchanged ones) and then pointing the root at its hash.

A rooted store also implements `IStore`, delegating every content operation to the store it wraps. So it is a drop-in wherever a content store is expected — the current-store binding, a service's main store — while additionally exposing the root.

## 4.1 A Ref Over a Root Hash

A rooted store implements Clojure's reference interfaces (`IDeref`, `IRef`, `IAtom2`), so the root is read and updated with the ordinary ref operators:

```clojure
(def store (rooted-store (lmdb-store "path/to/db")))

@store
;; => nil, or a 4-long hash vector (the current root)

;; Install a new root by hash
(reset! store root-hash)

;; Transform the current root hash
(swap! store (fn [old-root] (compute-new-root old-root)))
```

The crucial point: a rooted store is a **ref of a root hash**, not a ref of a Clojure map. `@store` gives you a hash; to see the value it names you hand that hash to the value layer. This keeps the mutable surface tiny — a single hash — no matter how large the value tree beneath it.

## 4.2 Root-Centric Operation

All evolution of a rooted store flows through its root:

```clojure
;; Read the current root
(def root @store)

;; Build a successor value, then point the root at it
(swap! store (fn [old-root]
               (let [m  (get-value store old-root)      ; Chapter 3: rehydrate the value
                     m' (assoc m "count" 42)]           ; structural edit, new nodes stored
                 (dacite-hash m'))))                     ; return the new root hash
```

Because the value layer stores every node it builds into the wrapped content store, by the time `swap!` returns the new root hash, all the nodes that hash reaches are already durably present. Installing the root is the last, atomic step — it never leaves the store pointing at content that isn't there.

Replacing the root does not delete the old tree. The previous root and any nodes unique to it simply become unreferenced (see §4.5).

## 4.3 Watches and Validators

Because the root is a proper ref, you can observe and constrain its transitions:

```clojure
;; Fire a callback whenever the root changes
(add-watch store ::sync
           (fn [_key _store old-root new-root]
             (println "root moved" old-root "->" new-root)))

;; Reject invalid roots
(set-validator! store (fn [root] (or (nil? root) (valid-root? root))))
```

Watches are the hook that makes a rooted store *reactive*: a change to the root can drive a re-render, a log entry, or a push to a peer (§4.6). The reference passed to a watch is the rooted store itself, so a single watcher can inspect the store it fired on.

## 4.4 Durability: the Root Cell

The root must outlive the process. A rooted store persists it through a small abstraction, the **root cell**, which knows how to load and store a single hash:

- **`mem-root-cell`** — an atom; ephemeral, for tests and REPL use.
- **`lmdb-root-cell`** — persists the root in the LMDB meta database, reusing the same environment as an LMDB content store.

```clojure
;; Ephemeral root (default)
(rooted-store content-store)

;; Durable root persisted alongside LMDB content
(def lmdb (lmdb-store "path/to/db"))
(rooted-store lmdb (lmdb-root-cell lmdb))
```

On construction the rooted store seeds its in-memory root from the cell; on every successful mutation it flushes the new root back to the cell. Restarting the process and reopening the store recovers the last root.

Note the clean separation: the **content store** persists nodes; the **root cell** persists the one hash that says which node is current. They can share a backend (LMDB) but are distinct responsibilities.

## 4.5 Detached Nodes and Garbage Collection

Any entry reachable from the current root is **live**. Entries that were reachable under a previous root but are not part of the current tree are **detached**. They remain in the content store until garbage collection removes them.

GC is fundamentally a value-aware traversal, and it needs both halves of this layering: the **root** (Chapter 4) as the starting point, and the **value model** (Chapter 3) to deserialize a node and discover its child references. Starting from `@store`, mark every reachable node; anything unmarked is detached and may be reclaimed. The content store itself knows nothing about reachability — it only maps hashes to values — which is exactly why GC lives up here with roots and values rather than down in the content store.

## 4.6 Ref Push and Sync

The root ref is the primitive for synchronization between stores. **Push** copies one store's root onto another:

```clojure
(push-ref source target)
```

After a push, `target`'s root is `reset!` to `@source`, and `target`'s watches fire. Push deliberately sends only a **hash** — it assumes the underlying content is already present in the target or is synced separately. Deciding when and where to push, and how to move the content that a new root depends on, are application concerns; the rooted store provides only the mechanism.

This is the seam through which peers communicate state changes: a source computes a new value, installs it as its root, and pushes that hash; watchers on the target react to the new root and fetch whatever content they don't yet have.

## 4.7 What This Layer Provides

1. A single, well-defined mutable **root** per store, accessed via `deref` / `reset!` / `swap!`.
2. **Watches and validators** on root transitions — the basis for reactive behavior.
3. **Durable roots** via a root cell (memory or LMDB), separate from content persistence.
4. **`IStore` delegation**, so a rooted store is a drop-in content store that also has a root.
5. **`push-ref`** as a minimal sync primitive between stores.

With this, Dacite has both halves of its model: an immutable world of content-addressed values, and one mutable pointer that lets that world evolve and be shared. Future work (see the roadmap) builds distribution and event flows on exactly this root-and-push foundation.
