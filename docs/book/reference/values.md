# Values API reference (0.1 alpha)

Practical API for Dacite values as implemented in the Clojure / SCI reference
library. For design intuition, see [Values](../03-values/chapter.md).

**Public namespace:** `dacite.value` (pair with `dacite.store` for stores).

## What is a Dacite value?

A value is **store-aware and content-addressed**:

| Property | Access |
|----------|--------|
| Content hash | `(v/dacite-hash v)` |
| Owning store | `(v/dacite-store v)` |
| Type name | `(v/dacite-type v)` or `(v/value-type v)` |
| Host content | `(v/realize v)` — explicit, never implicit deref |

Values are immutable. Updates return new values that share unchanged nodes with
the old ones. Laziness is natural: you only need the nodes you access.

```clojure
(require '[dacite.value :as v]
         '[dacite.store :as store])
```

---

## Constructors

### Relative (`*-via`) — preferred in domain code

Use an existing Dacite value, a root-ref, or an `IStore` as the peer:

| Form | Role |
|------|------|
| `(v/vector-via peer & xs)` | Vector in peer's store |
| `(v/hash-map-via peer & kvs)` | Map |
| `(v/set-via peer & xs)` | Set |
| `(v/string-via peer s)` / `(v/blob-via peer bs)` | Sequences |
| `(v/i64-via peer n)` (and other scalars) | Typed scalars |

```clojure
(defn add-todo [todos title]
  (v/conj todos (v/hash-map-via todos "title" title "done" false)))
```

### Bootstrap (`*-with-store`)

When there is no peer yet (first allocation):

```clojure
(v/vector-with-store st 1 2 3)
(v/i64-with-store st 42)
```

### REPL convenience

Bare constructors use the dynamic `store/*store*` (default mem store):

```clojure
(v/vector 1 2 3)
(v/hash-map :a 1)
(store/with-store [_ (store/mem-store)]
  (v/vector 1 2 3))
```

---

## Root reference (value-level)

The store layer keeps a mutable **hash**. Wrap it once for value-level ops:

```clojure
(def rooted (store/rooted-store (store/mem-store)))
(def r (v/root-ref rooted))

(v/ref-reset! r (v/vector-via r))
(v/ref-swap! r v/conj (v/i64-via r 1))
(v/ref-deref r)   ; => current Dacite value or nil
```

On the **JVM**, `RootRef` also implements atom interfaces:

```clojure
@r
(swap! r v/conj 2)
(reset! r (v/hash-map-via r "k" "v"))
(add-watch r :ui (fn [k ref old new] …))   ; old/new are values
```

Portable function API (nbb / babashka / all hosts):

| Function | Role |
|----------|------|
| `root-ref` | Wrap a local or remote rooted store |
| `ref-deref` | Current value or nil |
| `ref-reset!` | Unconditional set (**local only** — throws on remote) |
| `ref-swap!` | CAS-retry apply |
| `ref-cas!` | Value-level compare-and-set (use from `nil` to seed a remote) |
| `ref-add-watch` / `ref-remove-watch` | Watch value transitions |

---

## Collection API

First argument is always a Dacite value:

| Function | Role |
|----------|------|
| `dacite-value?` | Predicate |
| `value-type` / `dacite-type` | Type name string |
| `realize` | Host content |
| `dacite-hash` | Content hash |
| `get-value` | Rehydrate hash from store → value (`[h]` or `[st h]`) |
| `count` | Element/entry count, O(1) |
| `empty?` | Zero elements? |
| `seq` | Elements or map entries as wrapped values |
| `nth` | Index into vector/string/blob |
| `get` | Map key, set membership, or vector index |
| `contains?` | Presence of key/index |
| `assoc` | Vector index or map key → new value |
| `dissoc` | Remove map key |
| `conj` | Append / add entry |
| `peek` / `pop` | Vector end |
| `remove-nth` | Vector without index |
| `keys` / `vals` | Map keys or values as wrapped sequences |
| `native` | Host atom for a scalar, or host String for a Dacite string. Collections throw. Optional char `limit` (or `*string-char-limit*`) realizes at most that prefix, then throws if the string is longer. |
| `as-str` | `(str (native x))` — same optional limit. Field-sized text only. |
| `pr-str` | Bounded debug render. Never throws. Long strings: `"prefix…" (n chars)`. |
| `get-in` / `assoc-in` | Nested path lookup / update (creates intermediate maps) |
| `update` / `update-in` | Apply a fn at a key or path; result is assoc'd back |

Example:

```clojure
(let [st (store/mem-store)
      vec (v/vector-with-store st 10 20 30)
      v2  (v/conj vec 40)]
  [(v/count vec) (v/count v2)
   (v/realize (v/nth v2 3))])
;; => [3 4 40]
```

---

## Identity and hashing

- Two values with the same type and content have the **same hash** on every
  host (see `bin/hash-parity.sh`).
- Print / log hashes with `(store/hash->hex h)` and parse with
  `(store/hex->hash s)`.

---

## Not part of the public value API

| Area | Notes |
|------|-------|
| Finger-tree / HAMT node types | Internal store entries |
| Wire codecs | `dacite.wire` / `dacite.wire.binary` |
| `dacite.value.types` / `.scalar` / `.collections` | Implementation |
| `dacite.value.api` | Deprecated alias of this namespace |
| `dacite.core` | Deprecated convenience re-export |

---

## Related

- [Stores API](stores.md)
- [Hello World (nbb)](../tutorial/hello-nbb.md)
- [Remote config](../tutorial/config.md)
- [Versioned notes](../tutorial/notes.md)
- [Install](../getting-started/install.md)
