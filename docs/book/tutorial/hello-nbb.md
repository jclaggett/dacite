# Hello World (nbb)

Build a Dacite vector and map in memory, print their sizes and content hashes,
in about five minutes. No JVM required.

## Prerequisites

- [Node.js](https://nodejs.org/) 18+
- A clone of the [dacite](https://github.com/jclaggett/dacite) repository

```bash
git clone https://github.com/jclaggett/dacite.git
cd dacite
npm install
```

## Run the example

```bash
npm run hello
```

Expected shape of the output:

```text
Dacite Hello World
  vector count : 3
  vector hash  : 58a4799b…
  map count    : 2
  map hash     : 5de2bb0c…
  (get m "hello") realized: 42
Done.
```

The exact hashes are stable across hosts (JVM, babashka, nbb) for the same
value structure — that is part of the Dacite porting contract.

## What the code does

Source: [`examples/dacite/examples/hello.cljs`](https://github.com/jclaggett/dacite/blob/main/examples/dacite/examples/hello.cljs).

1. **Create a mem store** — an in-memory `hash → node` dictionary.

   ```clojure
   (def st (store/mem-store))
   ```

2. **Build values bound to that store** — constructors take the store explicitly
   (portable path used by nbb/SCI):

   ```clojure
   (def v (coll/vector-with-store st 1 2 3))
   (def m (coll/hash-map-with-store st
                                    "hello" (scalar/i64-with-store st 42)
                                    "vec" v))
   ```

3. **Read with `dacite.value.api`** — host-agnostic collection ops:

   ```clojure
   (d/count v)              ; => 3
   (d/get m "hello")        ; => Dacite scalar
   (types/realize …)        ; => 42
   ```

4. **Content hash** — identity is the hash, independent of store location:

   ```clojure
   (store/hash->hex (types/dacite-hash v))
   ```

## Try it in a one-liner

```bash
npx nbb -e "
(require '[dacite.store :as store]
         '[dacite.value.api :as d]
         '[dacite.value.collections :as coll]
         '[dacite.value.types :as types])
(let [st (store/mem-store)
      v  (coll/vector-with-store st 1 2 3)]
  (println (d/count v))
  (println (store/hash->hex (types/dacite-hash v))))
"
```

## Next steps

- **Durable todo** — `npm run todo:batch` or `npm run todo` (file store + root cell)
- **Browser demo** — [examples/web](https://github.com/jclaggett/dacite/blob/main/examples/web/README.md)
- **API reference** — [Values](../reference/values.md) · [Stores](../reference/stores.md)
- **Concepts** — [Content Stores](../01-stores/chapter.md) · [Values](../03-values/chapter.md)
