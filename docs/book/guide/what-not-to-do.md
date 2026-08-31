# What not to do

These habits fight the [Dacite way](../the-dacite-way.md). If you need
them, name the *why* and add a bounded utility on `dacite.value` instead.

## Dump the tree

Do not call `dac->clj` / `clj->dac` in application code. Do not
`(into {} …)` / `(into [] (v/realize v))` to “get a Clojure value.”
That assumes the tree fits in RAM and is fully local. `dac->clj` stays
on the JVM as a test hatch.

Read a field (`native` / `as-str`), a page (`subvec`), or a blob
(`as-bytes`).

## Host collections as the domain

Do not keep a Clojure map of todos and convert it at the edges. Build
Dacite maps and vectors from the start (`hash-map-via`, `conj`). Cards’
shuffle still dumps to a host vector — that is a hole, not a pattern.

## Twenty-line field readers

Do not write `title-str` that walks nodes by hand. `v/as-str` /
`v/native` exist because config needed them. If a new read is twenty
lines, promote it in the same change.

## Leak internals

Domain code must not require:

- `dacite.value.collections` / `.finger-tree` / `.hamt`
- `dacite.store.pack`
- `dacite.wire` / `dacite.wire.binary`

If you need them, the public API is missing a function.

## Bare `PUT /node` in a write-back app

Write-back clients flush packed chunks on commit. Per-node PUT is the
unwrapped-remote path. Mixing them in one app usually means the domain
started threading hashes.

## Unconditional remote reset

`ref-reset!` is local-only. On HTTP, seed with `ref-cas!` from `nil` and
update with `ref-swap!`.

## Query-string pack opt-outs

`GET /node/{hex}` is always a pack-filled chunk. There is no `?raw=`,
`?nodes=`, or `?near=`. For a single item in process, `pack-under` with
budget 0 — that is not an application API.
