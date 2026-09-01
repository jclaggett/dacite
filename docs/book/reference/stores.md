# Stores API reference (0.1 alpha)

Practical API for content stores and client composition in the reference
implementation. App wiring: [Anatomy](../building/anatomy.md) and
[Same domain, local or HTTP](../guide/local-or-http.md). Internals:
[Content Stores](../01-stores/chapter.md),
[Rooted Stores](../04-rooted-stores/chapter.md).

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
| **LMDB** | JVM | `(dacite.store.jvm/lmdb-store path)` — content values = **wire-v1 node payload** only; keys = 32-byte hash; root meta = 32-byte hash |

---

## Application stores (always rooted)

```clojure
(require '[dacite.store :as s]
         '[dacite.value :as v])

(def r (v/root (s/mem)))
(def r (v/root (s/file "target/my-app")))
(def r (v/root (s/file "target/my-app" {:reset true})))
(def r (v/root (s/remote "http://127.0.0.1:8080")))  ; JVM
(v/cas! r nil seed)
(v/swap! r domain-update)
```

`IStore` (`s-get`, `s-put`, `s-snapshot`, …) is the content dictionary used
by backends. Application code should not call it.

See [Values — root](values.md#root-value-level) and
[Rooted Stores chapter](../04-rooted-stores/chapter.md).

Host ctors on `dacite.store` (JVM): `(store/file-store path)`,
`(store/lmdb-store path)`, `(store/lmdb-root-cell lmdb)`. On **nbb**, use
`(dacite.store.nbb/file-store path)` (SCI cannot re-export circular host
backends cleanly). Optional nbb LMDB (same `data.mdb` layout as JVM, wire-v1
nodes): `(dacite.store.nbb.lmdb/lmdb-store path)` after
`LMDB_DATA_V1=true npm rebuild lmdb` (prebuilt `lmdb` is format v2 and will
not open a lmdbjava env).

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
| `dacite.store.rate-limit` | Throttle **send** path (outermost `IChunkTransport` wins). Server inbound admit is `dacite.service.throttle` (429), not this wrapper. |
| `dacite.store.stats` | Bandwidth accounting for store-protocol bodies |
| `dacite.store.remote` | JVM HTTP client (`:binary true` default for packs); `watch-root` is GET /events |
| `dacite.store.browser` | Browser sync XHR demo client (`:binary true` default) |

`pack/flush-from!` finds the outermost `IChunkTransport` and sends budgeted
chunks (default soft budget **1024** bytes).

---

## Wire: EDN vs wire-v1

| Context | Format |
|---------|--------|
| Pack chunk GET `/node/{hex}` | **wire-v1 chunk** (`application/vnd.dacite.chunk.v1`) |
| Pack chunk POST `/nodes` | **wire-v1 chunk** |
| Novelty PUT body, `/root`, CAS | **EDN** |
| LMDB content values | **wire-v1 node payload only** (no chunk/literal framing) |
| File store on disk | **EDN** (host-local; not multi-lang interop) |

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
- [HTTP service](http.md)
- [Anatomy of a Dacite app](../building/anatomy.md)
- [Install](../getting-started/install.md)
