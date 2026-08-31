# First values

You want a small vector and a map, their sizes, and a stable identity —
without a file or a server. Build them as Dacite values. **Count**, **get**,
and the **content hash** are the whole API you need here.

Five minutes. nbb only; no JVM.

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

2. **Build values** — bootstrap with an explicit store, then `*-via` for peers:

   ```clojure
   (def v (v/vector-with-store st 1 2 3))
   (def m (v/hash-map-via v
                          "hello" (v/i64-via v 42)
                          "vec" v))
   ```

3. **Read with `dacite.value`** — collection ops take the value first:

   ```clojure
   (v/count v)              ; => 3
   (v/get m "hello")        ; => Dacite scalar
   (v/realize …)            ; => 42
   ```

4. **Content hash** — identity is the hash, independent of store location:

   ```clojure
   (store/hash->hex (v/dacite-hash v))
   ```

## Try it in a one-liner

```bash
npx nbb -e "
(require '[dacite.store :as store]
         '[dacite.value :as v])
(let [st (store/mem-store)
      vec (v/vector-with-store st 1 2 3)]
  (println (v/count vec))
  (println (store/hash->hex (v/dacite-hash vec))))
"
```

## Next steps

- [Anatomy of a Dacite app](../building/anatomy.md) — add a root and persist
- [Persist and update a document](config.md) — nested map, file or HTTP
- [Values API](../reference/values.md) — constructors, `*-via`, collection ops
