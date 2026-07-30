# Stores API reference (0.1 alpha)

Practical API for content stores and client composition in the reference
implementation. For design background, see [Content Stores](../01-stores/chapter.md)
and [Rooted Stores](../04-rooted-stores/chapter.md).

## `IStore` protocol

Namespace: `dacite.store`

| Op | Meaning |
|----|---------|
| `(s-get st h)` | Node at hash, or nil |
| `(s-put st h value)` | Store entry; returns store |
| `(s-has? st h)` | Presence |
| `(s-delete st h)` | Remove entry |
| `(s-snapshot st)` | Bulk map of contents (implementation-defined keys) |
| `(s-merge st m)` | Merge map of hash→value |
| `(s-reset st)` | Clear |

Hashes are 4-word vectors (`[w0 w1 w2 w3]`). Helpers:

```clojure
(store/hash->hex h)
(store/hex->hash "…64 hex chars…")
```

Dynamic binding:

```clojure
store/*store*          ; current store
(store/with-store [st (store/mem-store)] …)
(store/set-store! st)
(store/reset-store!)
```

---

## Built-in stores

| Store | Host | Namespace / ctor |
|-------|------|------------------|
| **mem** | all | `(store/mem-store)` |
| **layered** | all | `(store/layered-store [fast … durable])` — read-through, write-through |
| **LRU** | all | `(dacite.store.lru/lru-store n)` |
| **file** | JVM, babashka | `(dacite.store.file/file-store path)` — `{base}/aa/bb/{hex}.edn` |
| **file** | nbb | `(dacite.store.nbb/file-store path)` |
| **LMDB** | JVM | `(dacite.store.jvm/lmdb-store path)` |

---

## Rooted stores

A content store holds immutable nodes. A **root cell** holds one mutable root
hash for application state (compare-and-set, watches, GC).

| Concern | Where |
|---------|--------|
| File root cell | `dacite.rooted` — `{base}/ROOT` hex |
| GC of unreachable nodes | `dacite.rooted.gc` |
| Conceptual model | [Rooted Stores chapter](../04-rooted-stores/chapter.md) |

Typical durable CLI pattern (todo example): open file store + root cell → load
or seed → mutate values → write root.

---

## Client composition (remote / sync)

Interactive clients usually stack:

```text
application Values
    ↓
write-back cache     (dacite.store.client-cache/wrap … :write-back)
    ↓
pack / chunk transport   (IChunkTransport + pack/flush-from!)
    ↓
optional rate-limit      (dacite.store.rate-limit)
    ↓
remote HTTP store        (dacite.store.remote | dacite.store.browser)
```

| Module | Role |
|--------|------|
| `dacite.store.client-cache` | Local mem + flush reachable on CAS / explicit flush |
| `dacite.store.pack` | Soft budget packing, `flush-from!`, `apply-chunk!`, literals |
| `dacite.store.rate-limit` | Throttle send path (outermost `IChunkTransport` wins) |
| `dacite.store.stats` | Bandwidth accounting for store-protocol bodies |
| `dacite.store.remote` | JVM HTTP client (`:binary true` default for packs) |
| `dacite.store.browser` | Browser sync XHR demo client (`:binary true` default) |

`pack/flush-from!` finds the outermost `IChunkTransport` and sends budgeted
chunks (default soft budget **1024** bytes).

---

## Wire: EDN vs wire-v1

| Message | Default |
|---------|---------|
| Pack chunk GET `/node/{hex}` | **wire-v1 binary** (`application/vnd.dacite.chunk.v1`) |
| Pack chunk POST `/nodes` | **wire-v1 binary** |
| Novelty PUT body, `/root`, CAS | **EDN** |

- Spec: [wire-v1](../../spec/wire-v1.md) (repo) / book [Serialization appendix](../appendices/serialization.md)
- Codec: `dacite.wire.binary` (portable `.cljc` — JVM + CLJS/nbb)
- EDN helpers: `dacite.wire` (`read-edn` / `write-edn`)
- Opt out: `{:binary false}` on remote/browser store constructors

Service dual-stack: `dacite.service` honors `Content-Type` / `Accept`.

---

## Alpha quality notes

- HTTP service and remotes are **experimental** but usable for demos and tests
- Browser remote is **sync XHR** (main-thread blocking) — not production networking
- APIs may change before 1.0; see [CHANGELOG](https://github.com/jclaggett/dacite/blob/main/CHANGELOG.md)

---

## Related

- [Values API](values.md)
- [Install](../getting-started/install.md)
- [Hello World (nbb)](../tutorial/hello-nbb.md)
