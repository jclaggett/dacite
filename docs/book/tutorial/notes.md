# History is free

You want every edit to remain a complete snapshot, and a title-only change
must not rewrite the body. Dacite’s move: keep the previous **document
value** (same hash) in a history vector; restore by installing that value
again. Unchanged fields keep their content hash — you do not invent git.

This is the second claim-proving app in the
[roadmap](../../roadmap.md).

You will:

1. Seed a note with a long body.
2. Change only the title and see the body hash stay put.
3. Restore an older snapshot by installing the same value again.

## Prerequisites

- A clone of this repo (see [Install](../getting-started/install.md))
- JDK 17+ and the Clojure CLI, or Node.js 18+ / babashka for the file store

## The shape

The root is a **notebook**, not a bare document:

```text
{"doc"     {"title" "…" "body" "…" "tags" […] "edited-at" n}
 "history" [previous-doc …]}
```

`history` is newest-previous first. A restore **reuses** a historical
doc value — same hash, no rewrite. The library does not invent git; the
app shows the recipe.

Domain code lives in `dacite.examples.notes` and uses only `dacite.value`.
Bodies print with `v/pr-str` so a long string is not dumped into RAM.

## Local file

```bash
cd impl/clojure
clojure -M:notes -- --reset show
```

Or from the repo root:

```bash
npm run notes -- --reset show
bb notes --reset show
```

Expected shape:

```text
seeded new store at target/dacite-notes
title:      Welcome
edited-at:  0
tags:       intro
body:       "Dacite notes keep every snapshot…" (n chars)
doc:        <12 hex chars>
history:    0 previous
root:       <64 hex chars>
```

Edit, list, diff, restore:

```bash
clojure -M:notes -- set title Hello
clojure -M:notes -- add-tag demo
clojure -M:notes -- list
clojure -M:notes -- diff 0 1
clojure -M:notes -- restore 1
```

Version **0** is current, **1** is the previous snapshot, and so on.
`diff` compares field hashes — a title-only edit reports `title` and
`edited-at`, not `body`.

`--path DIR` selects the store directory. The same `--url` recipe as
[Persist and update a document](config.md) works if the HTTP service is running.

## Sharing bench

```bash
clojure -M:notes -- bench
```

Typical output:

```text
sharing bench
  title-only:   +N nodes
  body-rewrite: +M nodes
  body shared after title edit: true
```

`M` is larger than `N` because the seed body is a real paragraph. The
title-only path allocates a new title string, a new doc map, and a
history conj. The body node is the same hash.

## What this pulled

| Why | Utility |
|---|---|
| Nested doc / history edits | `v/assoc`, `v/conj`, `v/nth` (path ops already shipped) |
| Print a long body | `v/pr-str` — `"prefix…" (n chars)` |
| Compare versions without realizing bodies | field content hashes |
| Restore without rewrite | install the historical value; hash identity is the proof |

No `dac->clj`. History is a vector of documents in the same store.

## Next

[Large sequences stay cheap](event-log.md) — page with `subvec`; do not
`seq` the whole log.
