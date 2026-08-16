# Remote config

A small config map as Dacite values — the same domain against a **local
file** store and an **HTTP** store. This is the first application in the
[roadmap](../../roadmap.md): the server publishes a root hash; clients
pull only what they need.

You will:

1. Seed and edit config on disk.
2. Point the same commands at `clojure -M:service`.
3. Watch a second process pick up a new root.

## Prerequisites

- A clone of this repo (see [Install](../getting-started/install.md))
- JDK 17+ and the Clojure CLI for the service and `--url` client
- Optional: Node.js 18+ (`npm run config`) or babashka (`bb config`) for the file store

## The shape

Config is a Dacite map:

```text
{"theme" "dark", "timeout" 30, "features" ["a" "b"]}
```

Domain code lives in `dacite.examples.config` and uses only `dacite.value`
— `get-in` / `assoc-in` / `native` / `as-str` / `root-ref`. It never calls
`dac->clj`. Store wiring (file path vs HTTP URL) is a separate section of
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

## Same commands against the service

Terminal 1 — start the HTTP content store:

```bash
cd impl/clojure
clojure -M:service --port 8080 --store mem
```

Terminal 2 — the same CLI, `--url` instead of `--path`:

```bash
cd impl/clojure
clojure -M:config -- --url http://127.0.0.1:8080 show
clojure -M:config -- --url http://127.0.0.1:8080 set timeout 90
```

`store/remote-rooted-store` implements the same root protocol as a local
rooted store, so `v/root-ref`, `v/ref-swap!`, and `v/ref-cas!` work
unchanged. `v/ref-reset!` is local-only and throws on a remote store;
the app seeds with compare-and-set from `nil`.

## A second process sees the new root

Terminal 3:

```bash
cd impl/clojure
clojure -M:config -- --url http://127.0.0.1:8080 watch
```

Back in terminal 2:

```bash
clojure -M:config -- --url http://127.0.0.1:8080 set theme solarized
```

`watch` polls `GET /root` twice a second and reprints when the hash
moves. (Push watches come later, when a two-client live app needs them.)

The two clients share one content-addressed tree. After an edit they
print the **same root hash**. That is the claim this app is here to
prove.

## Open local vs open remote

```clojure
(require '[dacite.store :as store]
         '[dacite.value :as v]
         '[dacite.examples.config :as cfg])

;; local
(def r (v/root-ref (cfg/open-file "target/dacite-config")))

;; remote (JVM)
(def r (v/root-ref (store/remote-rooted-store "http://127.0.0.1:8080")))

(cfg/load-or-seed! r)
(v/ref-swap! r cfg/set-path ["timeout"] 60)
(cfg/timeout (v/ref-deref r))
;; => 60
```

One domain namespace. Two store wirings.

## What this pulled from the library

| Why | Utility |
|---|---|
| Read `theme` / `timeout` without a 20-line `realize` helper | `v/native`, `v/as-str` (`as-str` is `native` then stringify) |
| Cap how much string is realized | optional limit or `v/*string-char-limit*` — `native`/`as-str` throw if longer |
| Print a long string without dumping it | `v/pr-str` → `"prefix…" (n chars)` |
| Nested `features.0` | `v/get-in`, `v/assoc-in`, `v/update` |
| Same `root-ref` on HTTP | `store/remote-rooted-store` (`IRoot`) |

`dac->clj` is not on this path. Field access uses `native` / `as-str`.
Debug printing uses `v/pr-str` or bounded `print-method`.

## Next

[Versioned notes](../../roadmap.md) — treat each root hash as a snapshot
you can restore.
