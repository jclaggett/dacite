# Anatomy of a Dacite app

Every portable example in this repo splits **Values** from **Store**. Domain
code builds and updates Dacite values. Wiring opens a **rooted** store.
The two meet at `v/root`.

Copy this shape. Do not start from a single namespace that both `assoc`s
fields and chooses a file path.

## Public API only

```clojure
(require '[dacite.value :as v]
         '[dacite.store :as s])
```

If a domain function needs `dacite.value.collections`, `dacite.store.pack`,
or `dacite.convert`, that is a library hole. Promote a function on
`dacite.value` (or wrap the store) in the same change. See
[The Dacite way](../the-dacite-way.md).

## 1. Domain functions take and return values

Constructors take a **context** first — a store, a root, or another value.
New nodes persist into that store:

```clojure
(defn add-todo [todos title]
  (v/conj todos (v/map todos "title" title "done" false)))
```

Reads name a field, not the whole tree:

```clojure
(v/native (v/get todo "title"))
(boolean (v/native (v/get todo "done")))
```

## 2. Bootstrap once

```clojure
(require '[dacite.store :as s]
         '[dacite.value :as v])

(def rs (s/mem))
(def r (v/root rs))
(def doc (v/map r
                "title" (v/string r "Hello")
                "done" false))

(v/native (v/get doc "title"))
;; => "Hello"

(s/hash->hex (v/hash doc))
```

Every constructor takes the context. There is no implicit `*store*` form.

## 3. One rooted store, one `v/root`

```clojure
(v/cas! r nil doc)          ; seed empty root (local and remote)
(v/swap! r add-todo "milk") ; CAS-retry
(v/deref r)                 ; current Dacite value, or nil
```

| Op | Use |
|----|-----|
| `deref` | Current value |
| `swap!` | Apply a function; retry on conflict |
| `cas!` | Compare-and-set (seed from `nil`) |
| `swap-info!` | Same loop as `swap!`, plus `{:retries n}` |

There is no unconditional `reset!` on the value API. Seed with `cas!` from
`nil`; update with `swap!`.

On the JVM, `@r` and `swap!` on the root object still work.

## 4. Store section is a different concern

```clojure
(s/mem)
(s/file "target/my-app")
(s/file "target/my-app" {:reset true})
(s/remote "http://127.0.0.1:8080")   ; JVM; write-back by default
```

The domain namespace should still compile if you swap file for HTTP.

On write-back HTTP, puts stay in local memory until commit. Flush packs
literals and `POST /nodes`, then `POST /root/cas`. Domain code does not
call pack.

## 5. The whole loop

```text
(def rs (s/file path))     ; Store section
(def r (v/root rs))        ; Value section
(load-or-seed! r)
(v/swap! r domain-op …)
```

`load-or-seed!` is `deref` or, if empty, `cas!` from `nil`.

That is the architecture in
[todo.cljc](https://github.com/jclaggett/dacite/blob/main/impl/clojure/src/dacite/examples/todo.cljc)
and the other portable examples.

## Next

- [First values](../tutorial/hello-nbb.md) — constructors and hashes, no root.
- [Persist and update a document](../tutorial/config.md) — nested map, file or HTTP.
- [Values API](../reference/values.md) / [Stores API](../reference/stores.md).
