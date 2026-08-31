# Anatomy of a Dacite app

Every portable example in this repo splits **Values** from **Store**. Domain
code builds and updates Dacite values. Wiring opens a store and commits a
root. The two meet at a `root-ref`.

Copy this shape. Do not start from a single namespace that both `assoc`s
fields and chooses a file path.

## Public API only

Application code may require:

```clojure
(require '[dacite.value :as v]
         '[dacite.store :as store])
```

If a domain function needs `dacite.value.collections`, `dacite.store.pack`,
or `dac->clj`, that is a library hole. Promote a function on `dacite.value`
(or wrap the store) in the same change. See [The Dacite way](../the-dacite-way.md).

## 1. Domain functions take and return values

New nodes are allocated **relative to a peer** that already has a store —
another value, a `root-ref`, or an `IStore`:

```clojure
(defn add-todo [todos title]
  (v/conj todos (v/hash-map-via todos "title" title "done" false)))
```

`hash-map-via` / `vector-via` / `string-via` / `i64-via` persist into the
peer’s store. You do not thread the store through every call.

Reads name a field, not the whole tree:

```clojure
(v/as-str (v/get todo "title"))
(boolean (v/native (v/get todo "done")))
```

## 2. Bootstrap once

The first allocation needs an explicit store. After that, `*-via` is enough.

At a REPL (nbb or JVM):

```clojure
(require '[dacite.store :as store]
         '[dacite.value :as v])

(def st (store/mem-store))
(def doc (v/hash-map-via st
                         "title" (v/string-via st "Hello")
                         "done" false))

(v/as-str (v/get doc "title"))
;; => "Hello"

(store/hash->hex (v/dacite-hash doc))
```

Bare constructors such as `(v/vector 1 2 3)` use the dynamic `store/*store*`
(a mem store by default). Prefer `*-via` / `*-with-store` in real programs so
the store is obvious.

## 3. One rooted store, one `root-ref`

The store keeps a mutable **hash**. Wrap it once for value-level ops:

```clojure
(def rs (store/rooted-store st))
(def r (v/root-ref rs))

(v/ref-cas! r nil doc)          ; seed empty root (works locally and remote)
(v/ref-swap! r add-todo "milk") ; CAS-retry
(v/ref-deref r)                 ; current Dacite value, or nil
```

| Op | Use |
|----|-----|
| `ref-deref` | Current value |
| `ref-swap!` | Apply a function; retry on conflict |
| `ref-cas!` | Compare-and-set at the value level (seed from `nil`) |
| `ref-reset!` | Unconditional set — **local only**; remote throws |
| `ref-swap-info!` | Same loop as `ref-swap!`, plus `{:retries n}` |

Local single-writer apps may `ref-reset!` after each edit. Anything that
might race (HTTP, two processes) uses `ref-swap!` or `ref-cas!`.

## 4. Store section is a different concern

Open mem, file, LMDB, or HTTP **without mentioning the domain type**:

```clojure
;; local durable
(store/rooted-store (store/file-store "target/my-app"))

;; HTTP (JVM); write-back is the usual client policy
(store/remote-rooted-store "http://127.0.0.1:8080")
```

The domain namespace should still compile if you swap file for HTTP. Config
and notes already do this with a `--url` flag.

On write-back HTTP, `s-put` stays in local memory until commit. Flush packs
literals and `POST /nodes`, then `POST /root/cas`. Domain code does not call
`flush-from!` or import pack. `remote-cas-root!` / `ref-swap!` on a write-back
store do that underneath.

## 5. The whole loop

```text
(open-store …)           ; Store section
(def r (v/root-ref rs))  ; Value section
(load-or-seed! r)
(v/ref-swap! r domain-op …)
```

`load-or-seed!` is `ref-deref` or, if empty, build a value via the ref and
`ref-cas!` from `nil` (remote) / `ref-reset!` (local).

That is the architecture in
[todo.cljc](https://github.com/jclaggett/dacite/blob/main/examples/dacite/examples/todo.cljc),
[config.cljc](https://github.com/jclaggett/dacite/blob/main/examples/dacite/examples/config.cljc),
and the other portable examples.

## Next

- [First values](../tutorial/hello-nbb.md) — constructors and hashes, no root.
- [Persist and update a document](../tutorial/config.md) — nested map, file or HTTP.
- [Values API](../reference/values.md) / [Stores API](../reference/stores.md).
