# Values API reference (0.1 alpha)

Practical API for Dacite values as implemented in the Clojure / SCI reference
library. For design intuition, see [Values](../03-values/chapter.md).

## What is a Dacite value?

A value is **store-aware and content-addressed**:

| Property | Access |
|----------|--------|
| Content hash | `(types/dacite-hash v)` or `(d/dacite-hash v)` |
| Owning store | `(types/dacite-store v)` |
| Type name | `(types/dacite-type v)` — e.g. `"i64"`, `"vector"`, `"map"` |
| Host content | `(types/realize v)` — explicit, never implicit deref |

Values are immutable. Updates return new values that share unchanged nodes with
the old ones. Laziness is natural: you only need the nodes you access.

```clojure
(require '[dacite.value.api :as d]
         '[dacite.value.types :as types]
         '[dacite.store :as store])
```

On the **JVM**, `dacite.core` also provides Clojure-native constructors and
`clojure.core` interop on collection types. On **nbb / babashka**, prefer
`dacite.value.api` and the `*-with-store` constructors.

---

## Constructors

### Portable (nbb / babashka / JVM)

Pass an explicit store:

| Form | Namespace |
|------|-----------|
| `(scalar/null-with-store st)` | `dacite.value.scalar` |
| `(scalar/bool-with-store st b)` | |
| `(scalar/i64-with-store st n)` | also `i8`…`i32`, `u8`…`u64`, `f32`/`f64`, `dacite-char` |
| `(coll/string-with-store st s)` | `dacite.value.collections` |
| `(coll/blob-with-store st bytes)` | |
| `(coll/vector-with-store st & xs)` | elements auto-coerced or Dacite values |
| `(coll/hash-map-with-store st & kvs)` | alternating keys/values |
| `(coll/dacite-set-with-store st & xs)` | |

Plain ints, strings, and keywords are typically coerced when used as elements
or map keys (see `types/extract-hash`).

### JVM convenience (`dacite.core`)

Uses the dynamic `store/*store*` (default mem store):

```clojure
(require '[dacite.core :as dc])

(dc/i64 42)
(dc/str "hello")
(dc/vec [1 2 3])
(dc/hash-map :a 1 :b 2)
(dc/dacite-set 1 2 3)
(dc/realize v)
(dc/dac->clj v)   ; recursive plain Clojure
(dc/clj->dac data)
```

Isolated store context:

```clojure
(dc/with-store [_ (store/mem-store)]
  (dc/vec [1 2 3]))
```

---

## Collection API (`dacite.value.api`)

Portable surface for all hosts. First argument is always a Dacite value.

| Function | Role |
|----------|------|
| `dacite-value?` | Predicate |
| `value-type` | Type name string |
| `realize` | Host content (alias of `types/realize`) |
| `dacite-hash` | Content hash |
| `get-value` | Rehydrate hash from store → value (`[h]` or `[st h]`) |
| `count` | Element/entry count, O(1) |
| `empty?` | Zero elements? |
| `seq` | Elements or map entries as wrapped values; nil if empty |
| `nth` | Index into vector/string/blob (`[v i]` / `[v i not-found]`) |
| `get` | Map key, set membership key, or vector index |
| `contains?` | Presence of key/index |
| `assoc` | Vector index or map key → new value |
| `dissoc` | Remove map key |
| `conj` | Append (vector) / add entry (map/set) |
| `peek` / `pop` | Vector end |
| `remove-nth` | Vector without index |
| `keys` / `vals` | Map keys or values as wrapped sequences |

Example:

```clojure
(let [st (store/mem-store)
      v  (coll/vector-with-store st 10 20 30)
      v2 (d/conj v 40)]
  [(d/count v) (d/count v2)
   (types/realize (d/nth v2 3))])
;; => [3 4 40]
```

---

## Identity and hashing

- Two values with the same type and content have the **same hash** on every
  host (see `bin/hash-parity.sh`).
- Hash is **shape-independent** for equal logical content under Dacite’s
  encoding rules (fused hashing — [Hash Fusion](../02-hash-fusion/chapter.md)).
- Print / log hashes with `(store/hash->hex h)` and parse with
  `(store/hex->hash s)`.

---

## Not part of the public value API

| Area | Notes |
|------|-------|
| Finger-tree / HAMT node types | Internal store entries (`"ft/…"`, `"hamt/…"`) |
| Wire codecs | `dacite.wire` / `dacite.wire.binary` |
| Pack / remote / GC | Store layer — see [Stores](stores.md) |

---

## Related

- [Stores API](stores.md)
- [Hello World (nbb)](../tutorial/hello-nbb.md)
- [Install](../getting-started/install.md)
