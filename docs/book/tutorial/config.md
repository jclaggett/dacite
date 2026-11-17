# Persist and update a document

You have a nested config map. You want to change one field, persist it, and
run the **same domain** against a file or an LMDB store. Dacite’s move:
`get-in` / `assoc-in` / `native` on values, a `v/root` for the snapshot,
store wiring in a separate section.

This example is the first claim-proving app in the
[roadmap](../../roadmap.md): a store publishes a root hash; readers
pull only what they need.

You will:

1. Seed and edit config on disk.
2. Watch a second process pick up a new root.

## Prerequisites

- A clone of this repo (see [Install](../getting-started/install.md))
- JDK 17+ and the Clojure CLI
- Optional: Node.js 18+ (`npm run config`) or babashka (`bb config`) for the file store

## The shape

Config is a Dacite map:

```text
{"theme" "dark", "timeout" 30, "features" ["a" "b"]}
```

Domain code lives in `dacite.examples.config` and uses only `dacite.value`
— `get-in` / `assoc-in` / `native` / `v/root`. It never calls
`dacite.convert`. Store wiring (file or LMDB) is a separate section of
the same namespace.

## Local file

From the repo root:

```bash
cd impl/clojure
clojure -M:config -- --reset show
```

Or from the repo root with nbb / babashka (file store only):

```bash
npm run config -- --reset show
bb config --reset show
```

Expected shape:

```text
seeded new store at target/dacite-config
theme:    dark
timeout:  30
features: a, b
root:     <64 hex chars>
```

Edit without leaving the value API:

```bash
clojure -M:config -- set timeout 60
clojure -M:config -- set theme light
clojure -M:config -- add-feature telemetry
clojure -M:config -- get features.0
```

`--path DIR` selects the store directory (content shards + a `ROOT` file).
A second invocation without `--reset` reopens the same root.

## A second process sees the new root

Terminal 1:

```bash
cd impl/clojure
clojure -M:config -- watch
```

Terminal 2:

```bash
cd impl/clojure
clojure -M:config -- set theme solarized
```

`watch` polls the store's root twice a second and reprints when the hash
moves.

The two clients share one content-addressed tree. After an edit they
print the **same root hash**. That is the claim this app is here to
prove.

## Open the store from a REPL

```clojure
(require '[dacite.store :as store]
         '[dacite.value :as v]
         '[dacite.examples.config :as cfg])

;; local file store
(def r (v/root (cfg/open-file "target/dacite-config")))

(cfg/load-or-seed! r)
(v/swap! r cfg/set-path ["timeout"] 60)
(cfg/timeout (v/deref r))
;; => 60
```

One domain namespace. The store wiring is separate.

## What this pulled from the library

| Why | Utility |
|---|---|
| Read `theme` / `timeout` without a 20-line `realize` helper | `v/native` |
| Cap how much string is realized | optional limit or `v/*string-char-limit*` |
| Print a long string without dumping it | `v/pr-str` → `"prefix…" (n chars)` |
| Nested `features.0` | `v/get-in`, `v/assoc-in`, `v/update` |

Field access uses `native`. Debug printing uses `v/pr-str`.

## Next

[History is free](notes.md) — keep the previous document value; restore by hash.
