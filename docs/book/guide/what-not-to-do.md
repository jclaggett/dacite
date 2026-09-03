# What not to do

These habits fight the [Dacite way](../the-dacite-way.md).

## Dump the tree

Do not reach for `dacite.convert` (`dac->clj` / `clj->dac`) in application
code. Do not `(into [] (v/realize v))` to “get a Clojure value.” That
assumes the tree fits in RAM and is fully local.

Read a field (`native`), a page (`subvec`), or a blob (`as-bytes`).

## Host collections as the domain

Build Dacite maps and vectors from the start (`v/map`, `v/conj`). Shuffle
a vector with `nth` / `assoc`, not by dumping to a host collection.

## Leak internals

Domain code must not require:

- `dacite.value.collections` / `.finger-tree` / `.hamt`
- `dacite.store.pack`
- `dacite.wire` / `dacite.wire.binary`
- `IStore` methods (`s-get`, `s-snapshot`, `s-reset`, …)

If you need them, the public API is missing a function.

## Unconditional reset

There is no `reset!` on the value API. Seed with `cas!` from `nil`; update
with `swap!`.

## Query-string pack opt-outs

`GET /node/{hex}` is always a pack-filled chunk. There is no `?raw=`,
`?nodes=`, or `?near=`.
