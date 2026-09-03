# Values API reference (0.1 alpha)

Practical API for Dacite values as implemented in the Clojure / SCI reference
library. For how to *use* values, start at [The Dacite way](../the-dacite-way.md)
and the [cookbook](../guide/read.md). Internals:
[Values](../03-values/chapter.md).

**Public namespace:** `dacite.value` (pair with `dacite.store` for stores).

## What is a Dacite value?

A value is **store-aware and content-addressed**:

| Property | Access |
|----------|--------|
| Content hash | `(v/hash v)` |
| Owning store | `(v/dacite-store v)` |
| Type name | `(v/type v)` |
| Host content | `(v/realize v)` — explicit, never implicit deref |

Values are immutable. Updates return new values that share unchanged nodes with
the old ones. Laziness is natural: you only need the nodes you access.

```clojure
(require '[dacite.value :as v]
         '[dacite.store :as s])
```

---

## Constructors

Every constructor takes a **context** first: a rooted store, a `v/root`,
or another Dacite value. New nodes go into that store.

| Form | Role |
|------|------|
| `(v/vector ctx & xs)` | Vector |
| `(v/map ctx & kvs)` | Map |
| `(v/set ctx & xs)` | Set |
| `(v/string ctx s)` / `(v/blob ctx bs)` | Sequences |
| `(v/i64 ctx n)` (and other scalars) | Typed scalars |

```clojure
(defn add-todo [todos title]
  (v/conj todos (v/map todos "title" title "done" false)))

(v/vector rs 1 2 3)
(v/i64 rs 42)
```

---

## Root (value-level)

The store layer keeps a mutable **hash**. Wrap it once:

```clojure
(def r (v/root (s/mem)))

(v/cas! r nil (v/vector r))
(v/swap! r v/conj (v/i64 r 1))
(v/deref r)   ; => current Dacite value or nil
```

On the **JVM**, the root also implements atom interfaces (`@r`, `swap!`).
There is no portable unconditional reset — seed with `cas!` from `nil`.

| Function | Role |
|----------|------|
| `root` | Wrap a rooted store |
| `deref` | Current value or nil |
| `swap!` | CAS-retry apply |
| `swap-info!` | Same, plus `{:value new :retries n}` |
| `cas!` | Compare-and-set (seed from `nil`) |
| `add-watch` / `remove-watch` | Watch value transitions |

---

## Collection API

First argument is always a Dacite value:

| Function | Role |
|----------|------|
| `dacite-value?` | Predicate |
| `type` | Type name string |
| `realize` | Host content |
| `hash` | Content hash |
| `get-value` | Rehydrate hash from store → value (`[st h]`) |
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
| `slice` | `[start, end)` of a vector, string, or blob (same type; shared leaves; O(k log n)) |
| `keys` / `vals` | Map keys or values as wrapped sequences |
| `native` | Host atom for a scalar, or host String for a Dacite string. Collections throw. Optional char `limit` (or `*string-char-limit*`) realizes at most that prefix, then throws if the string is longer. |
| `as-bytes` | Host bytes for a blob. Optional limit; missing nodes throw `:dacite/missing`. |
| `pr-str` | Bounded debug render. Never throws. Long strings: `"prefix…" (n chars)`. |
| `get-in` / `assoc-in` | Nested path lookup / update (creates intermediate maps) |
| `update` / `update-in` | Apply a fn at a key or path; result is assoc'd back |

Example:

```clojure
(let [st (s/mem)
      vec (v/vector st 10 20 30)
      v2  (v/conj vec 40)]
  [(v/count vec) (v/count v2)
   (v/realize (v/nth v2 3))])
;; => [3 4 40]
```

---

## Identity and hashing

- Two values with the same type and content have the **same hash** on every
  host (see `bin/hash-parity.sh`).
- Print / log hashes with `(s/hash->hex h)` and parse with
  `(s/hex->hash s)`.

---

## Not part of the public value API

| Area | Notes |
|------|-------|
| Finger-tree / HAMT node types | Internal store entries |
| Wire codecs | `dacite.wire` / `dacite.wire.binary` |
| `dacite.value.types` / `.scalar` / `.collections` | Implementation |
| `dacite.convert` | JVM test hatch (`dac->clj` / `clj->dac`), not app code |

---

## Related

- [Stores API](stores.md)
- [The Dacite way](../the-dacite-way.md)
- [Anatomy of a Dacite app](../building/anatomy.md)
- [Cookbook](../guide/read.md)
- [Install](../getting-started/install.md)
