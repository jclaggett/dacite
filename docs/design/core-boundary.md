# Core boundary inventory

**Status:** Inventory of the tree as of Nov 6, 2026. M2.1 classified
every namespace. M2.2 moved the host-neutral reveal engine into `core`.
M2.3 added the parity suite under `impl/clojure/test` and
`fixtures/parity`. Those are not source namespaces, so the counts below
are unchanged.

The portability contract (what a host must provide, and what the portable
core is allowed to call) is [portable-core.md](portable-core.md). This
document is the **inventory**: every namespace on the Clojure source path,
labeled `core`, `host`, or `server-only`.

Counted here: **84 namespaces** (7 `core`, 73 `host`, 4 `server-only`) under
`impl/clojure/src` and `impl/clojure/dev`. `impl/clojure/test` is not
inventoried; tests may require any class. `impl/clojure/archive` is not on
the classpath.

## Rules

A namespace is **`core`** when all of these hold:

1. The file is `.cljc`.
2. After comments and strings are removed, the file has no host marker:
   a reader conditional (`#?`), `js/`, `java.`, `clojure.java`,
   `clojure.lang`, `:import`, `cljs.`, `goog.`, `byte-array`, `bytes?`,
   `System/`, `Math/`, `Thread/` or `ThreadLocal`, `Integer/`, `Long/`,
   `Double/`, or `Character/`.
3. The file does not load code at runtime (`require`, `requiring-resolve`,
   `load`).
4. Every libspec in `:require`, `:require-macros`, and `:use` — **every
   reader-conditional branch**, not only the JVM branch — is one of:
   another `core` namespace, **`dacite.host`**, or a portable
   `clojure.*` / `cljs.*` library other than `clojure.java.*` and
   `clojure.lang.*`.

`dacite.host` is **`host`**. It is the portability contract in
[portable-core.md](portable-core.md): the one host namespace `core` may
require. `dacite.reveal.engine` and `dacite.peer.rates` are `core` and
require it. They have no host marker of their own.

A namespace is **`server-only`** when its name is `dacite.service` or
`dacite.service.*`. That is the HTTP service process. A JVM HTTP *client*
of that service (`dacite.store.remote`, `dacite.store.reveal`) is `host`.

Everything else is **`host`**. That includes `.clj` and `.cljs` files, and
`.cljc` files that still contain host interop. A `.cljc` file with no
marker of its own is still `host` when it requires a host namespace: the
core set is closed under requires.

The lint reads libspecs. It does not expand prefix libspecs (`[dacite.store
[file :as file]]`). None are used. A new one fails the test until it is
written out as full names.

## Browser tooling

nbb interprets `.cljc`. A browser needs a compiled ClojureScript build.
The repo already has three one-shot bundles, each a single `:main` via
`cljs.build.api`:

- `:cljs-web` → `dacite.examples.todo-web`
- `:cljs-explorer` → `dacite.examples.explorer-web`
- `:cljs-library` → `dacite.examples.library-web`

There is no shadow-cljs, Figwheel, or ClojureScript test runner config.

**Recommendation: shadow-cljs** for the compiled browser target (M5) and
for any compiled-CLJS test of this core. One config can hold several
builds, npm libraries, and a test runner. [host-collection-adapters.md](host-collection-adapters.md)
already names shadow-cljs for the compiled-CLJS protocol work. Leave the
three `:cljs-*` aliases on `cljs.build.api` until a later change migrates
the example bundles. This milestone does not add the toolchain.

## Core

Pure `.cljc`. No host marker, no dynamic load, and no require outside
`core` and `dacite.host`.

| Namespace | Class | File | Rationale |
|-----------|-------|------|-----------|
| `dacite.byte-table` | `core` | `impl/clojure/src/dacite/byte_table.cljc` | Shipped byte-to-hash seed table. Data only, no requires. |
| `dacite.examples` | `core` | `impl/clojure/src/dacite/examples.cljc` | Example index. Docstring only; the namespaces it names are host. |
| `dacite.peer.bucket` | `core` | `impl/clojure/src/dacite/peer/bucket.cljc` | Token bucket. Time is an argument, so there is no host clock. |
| `dacite.peer.rates` | `core` | `impl/clojure/src/dacite/peer/rates.cljc` | C01/C02 rate split. Interest is `dacite.reveal.engine/interest`. `floor` is `dacite.host/floor`. |
| `dacite.peer.transport` | `core` | `impl/clojure/src/dacite/peer/transport.cljc` | `ITransport` and `IChannel` protocols and the channel names. No I/O. |
| `dacite.reveal.engine` | `core` | `impl/clojure/src/dacite/reveal/engine.cljc` | Page order, interest, shares, the session clock, session state, ack and release. Node lookup is a function map. `exp`, `log`, `next-up`, `finite?`, `infinite?`, and `hash-key` are `dacite.host`. |
| `dacite.sl2-table` | `core` | `impl/clojure/src/dacite/sl2_table.cljc` | Shipped SL(2) generator table. Data only, no requires. |

## Host: `.cljc` that still has host interop

These files are shared source, and they still branch on the host. They are
`host`, not `core`, until that interop moves behind `dacite.host`.

| Namespace | Class | File | Rationale |
|-----------|-------|------|-----------|
| `bench-ops` | `host` | `impl/clojure/dev/bench_ops.cljc` | Dev timings. `System/nanoTime` and `js/performance.now`, plus byte arrays. |
| `dacite.cache` | `host` | `impl/clojure/src/dacite/cache.cljc` | LRU: `LinkedHashMap` on the JVM, a JS `Map` on ClojureScript. |
| `dacite.examples.config` | `host` | `impl/clojure/src/dacite/examples/config.cljc` | Config example. `Long/parseLong` and `js/parseInt`, and a JVM watch loop. |
| `dacite.examples.event-log` | `host` | `impl/clojure/src/dacite/examples/event_log.cljc` | Event-log example. Same integer split, and a `:clj` require of `dacite.store.remote`. |
| `dacite.examples.explorer` | `host` | `impl/clojure/src/dacite/examples/explorer.cljc` | Explorer domain. `byte-array` and `js/Uint8Array` for the sample blob. |
| `dacite.examples.library` | `host` | `impl/clojure/src/dacite/examples/library.cljc` | Library example. `clojure.java.io`, Node `Buffer`, and a `:clj` require of `dacite.store.chunk`. |
| `dacite.examples.notes` | `host` | `impl/clojure/src/dacite/examples/notes.cljc` | Notes example. `Long/parseLong` and `js/parseInt`. |
| `dacite.examples.sync` | `host` | `impl/clojure/src/dacite/examples/sync.cljc` | Sync example. `clojure.java.io` on the JVM and Node `fs` on ClojureScript. |
| `dacite.hash` | `host` | `impl/clojure/src/dacite/hash.cljc` | Fuse algebra on `dacite.host`, plus `Integer/parseInt` and `js/parseInt`, and it requires `dacite.cache`. |
| `dacite.host` | `host` | `impl/clojure/src/dacite/host.cljc` | The portability contract: words, bytes, UTF-8, and scalar codecs via Java and JS. The one host namespace `core` may require. |
| `dacite.peer.client` | `host` | `impl/clojure/src/dacite/peer/client.cljc` | Peer client. `System/currentTimeMillis` and `js/Date`. |
| `dacite.peer.frame` | `host` | `impl/clojure/src/dacite/peer/frame.cljc` | Frame bytes: `byte-array` and `Uint8Array`, `System/arraycopy`. |
| `dacite.peer.link` | `host` | `impl/clojure/src/dacite/peer/link.cljc` | Wires a session to a channel. A `js/Promise` branch when the channel opens asynchronously. |
| `dacite.peer.msg` | `host` | `impl/clojure/src/dacite/peer/msg.cljc` | Control messages. `bytes?`, `Long`/`Integer`, and JS string code points. |
| `dacite.peer.responder` | `host` | `impl/clojure/src/dacite/peer/responder.cljc` | Peer responder. Same host clock split as the client. |
| `dacite.peer.transport.mem` | `host` | `impl/clojure/src/dacite/peer/transport/mem.cljc` | In-memory transport. Catches `Throwable` or `:default`. Requires `dacite.wire.binary`. |
| `dacite.peer.verify` | `host` | `impl/clojure/src/dacite/peer/verify.cljc` | Item checks. `clojure.lang.ExceptionInfo` or `ExceptionInfo`. |
| `dacite.rooted` | `host` | `impl/clojure/src/dacite/rooted.cljc` | Root cell. `clojure.java.io`, Node `fs`, and JVM `clojure.lang` ref interfaces. |
| `dacite.store` | `host` | `impl/clojure/src/dacite/store.cljc` | `IStore` and the memory store, plus futures, JS promises, and backend constructors loaded with `requiring-resolve`. |
| `dacite.store.bound` | `host` | `impl/clojure/src/dacite/store/bound.cljc` | Literal-size bound. Catches `clojure.lang.ExceptionInfo` or the CLJS default. |
| `dacite.store.chain` | `host` | `impl/clojure/src/dacite/store/chain.cljc` | Promise chaining. JVM `CompletionStage` imports and `js/Promise`. |
| `dacite.store.lens` | `host` | `impl/clojure/src/dacite/store/lens.cljc` | Root lens. JVM `clojure.lang` atom interfaces and `js/Error`. |
| `dacite.store.pack` | `host` | `impl/clojure/src/dacite/store/pack.cljc` | Pack codec. Reader-conditional byte arrays and `Throwable` catches. |
| `dacite.store.rate-limit` | `host` | `impl/clojure/src/dacite/store/rate_limit.cljc` | Outbound token bucket. `System/currentTimeMillis`, `Thread/sleep`, and `js/Date`. |
| `dacite.store.stats` | `host` | `impl/clojure/src/dacite/store/stats.cljc` | Bandwidth counters. JVM `format` via a reader conditional. |
| `dacite.value` | `host` | `impl/clojure/src/dacite/value.cljc` | Public value API. JVM `byte-array` blob branch and a `:clj` require of `dacite.value.render`. |
| `dacite.value.collections` | `host` | `impl/clojure/src/dacite/value/collections.cljc` | Collections. JVM `clojure.lang` interfaces and `java.util.Map$Entry`, behind reader conditionals. |
| `dacite.value.finger-tree` | `host` | `impl/clojure/src/dacite/value/finger_tree.cljc` | Finger tree. Byte arrays, `Uint8Array`, and a `ThreadLocal` page memo. |
| `dacite.value.lit` | `host` | `impl/clojure/src/dacite/value/lit.cljc` | Sequence literals. Java arrays, `java.util.Arrays`, and `Uint8Array`. |
| `dacite.value.root-ref` | `host` | `impl/clojure/src/dacite/value/root_ref.cljc` | Value-level root. JVM `IDeref` / `IAtom` / `IRef`. |
| `dacite.value.scalar` | `host` | `impl/clojure/src/dacite/value/scalar.cljc` | Scalars go through `dacite.host`. JVM `IHashEq` and `dacite.value.render` stay behind reader conditionals. |
| `dacite.value.types` | `host` | `impl/clojure/src/dacite/value/types.cljc` | Value hashing and `coerce-kind`. JVM `Boolean`, `char?`, and `bytes?` branches. |
| `dacite.wire` | `host` | `impl/clojure/src/dacite/wire.cljc` | EDN tagged `u64`. `Long` and JS `BigInt`; `clojure.edn` or `cljs.reader`. |
| `dacite.wire.binary` | `host` | `impl/clojure/src/dacite/wire/binary.cljc` | Wire v1 codec. Java byte arrays, `Uint8Array`, and `clojure.java.io` on the JVM. |

## Host: `.cljc` with no direct interop, outside the core closure

No host marker in the file. Each one requires a namespace in the table
above, so it is `host` until those dependencies are `core`.

| Namespace | Class | File | Rationale |
|-----------|-------|------|-----------|
| `dacite.examples.hello` | `host` | `impl/clojure/src/dacite/examples/hello.cljc` | Hello example, domain only. Requires `dacite.store` and `dacite.value`. |
| `dacite.examples.parity` | `host` | `impl/clojure/src/dacite/examples/parity.cljc` | Cross-host hash example. Requires `dacite.store` and `dacite.value`. |
| `dacite.examples.todo` | `host` | `impl/clojure/src/dacite/examples/todo.cljc` | Todo domain. Requires `dacite.store` and `dacite.value`. |
| `dacite.reveal` | `host` | `impl/clojure/src/dacite/reveal.cljc` | Store adapter for `dacite.reveal.engine`. Builds the node source from `dacite.store`, `dacite.value.types`, `dacite.value.hamt`, and `dacite.wire.binary`. No host marker of its own. |
| `dacite.rooted.gc` | `host` | `impl/clojure/src/dacite/rooted/gc.cljc` | Mark-and-sweep in portable Clojure. Requires `dacite.store` and `dacite.value.types`. |
| `dacite.store.chunk` | `host` | `impl/clojure/src/dacite/store/chunk.cljc` | Chunked overlay. Requires `dacite.store`, `dacite.store.pack`, and `dacite.rooted.gc`. |
| `dacite.store.client-cache` | `host` | `impl/clojure/src/dacite/store/client_cache.cljc` | Client cache policies. Requires `dacite.store`, `dacite.store.pack`, and `dacite.rooted.gc`. |
| `dacite.store.lru` | `host` | `impl/clojure/src/dacite/store/lru.cljc` | LRU content store over `IStore`. Requires `dacite.store`. |
| `dacite.store.sync` | `host` | `impl/clojure/src/dacite/store/sync.cljc` | Copy a reachable subgraph. Requires `dacite.store`, `dacite.store.pack`, and `dacite.rooted.gc`. |
| `dacite.value.hamt` | `host` | `impl/clojure/src/dacite/value/hamt.cljc` | HAMT math goes through `dacite.host` and the file has no host marker. Requires `dacite.hash`, `dacite.store`, and `dacite.value.types`. |

## Host: JVM, Node, and browser files

`.clj` and `.cljs` do not load on every host, so they are not `core`.

| Namespace | Class | File | Rationale |
|-----------|-------|------|-----------|
| `bench-cp` | `host` | `impl/clojure/dev/bench_cp.clj` | JVM code-point microbench (`System/nanoTime`). |
| `dacite.bench.library-storage` | `host` | `impl/clojure/src/dacite/bench/library_storage.clj` | JVM file and LMDB size bench, including `lmdbjava`. |
| `dacite.bench.todo-bw` | `host` | `impl/clojure/src/dacite/bench/todo_bw.clj` | JVM service bandwidth bench. Requires `dacite.service`. |
| `dacite.convert` | `host` | `impl/clojure/src/dacite/convert.clj` | JVM boundary between Dacite values and Clojure data (`byte-array`). |
| `dacite.dev.gen-byte-table` | `host` | `impl/clojure/dev/dacite/dev/gen_byte_table.clj` | JVM SHA-256 regeneration of `dacite.byte-table`. |
| `dacite.dev.gen-sl2-table` | `host` | `impl/clojure/dev/dacite/dev/gen_sl2_table.clj` | JVM regeneration of `dacite.sl2-table`. |
| `dacite.dev.inspect` | `host` | `impl/clojure/dev/dacite/dev/inspect.clj` | JVM reflection over `clojure.lang` collection interfaces. |
| `dacite.examples.cards` | `host` | `impl/clojure/src/dacite/examples/cards.clj` | JVM cards example (`clojure -M:cards`), LMDB through the store API. |
| `dacite.examples.explorer-web` | `host` | `impl/clojure/src/dacite/examples/explorer_web.cljs` | Compiled browser explorer. DOM and `dacite.store.browser`. |
| `dacite.examples.library-web` | `host` | `impl/clojure/src/dacite/examples/library_web.cljs` | Compiled browser library reader. DOM and `dacite.store.browser`. |
| `dacite.examples.lmdb-bench` | `host` | `impl/clojure/src/dacite/examples/lmdb_bench.cljs` | nbb file-versus-LMDB timings. Node `fs` and `dacite.store.nbb.lmdb`. |
| `dacite.examples.todo-ui` | `host` | `impl/clojure/src/dacite/examples/todo_ui.cljs` | nbb todo CLI. `promesa` and Node. |
| `dacite.examples.todo-web` | `host` | `impl/clojure/src/dacite/examples/todo_web.cljs` | Compiled browser todo UI. DOM and `dacite.store.browser`. |
| `dacite.json` | `host` | `impl/clojure/src/dacite/json.clj` | JVM JSON via Cheshire, over `dacite.convert`. |
| `dacite.peer.items` | `host` | `impl/clojure/src/dacite/peer/items.clj` | JVM-only peer item writer. Calls `dacite.service.reveal/encode-page`. |
| `dacite.peer.transport.webrtc` | `host` | `impl/clojure/src/dacite/peer/transport/webrtc.cljs` | nbb WebRTC adapter over `node-datachannel`. |
| `dacite.store.browser` | `host` | `impl/clojure/src/dacite/store/browser.cljs` | Browser remote store. `fetch` and `Uint8Array`. |
| `dacite.store.file` | `host` | `impl/clojure/src/dacite/store/file.clj` | Filesystem store for the JVM and babashka (`java.io`). |
| `dacite.store.idb` | `host` | `impl/clojure/src/dacite/store/idb.cljs` | IndexedDB content store. |
| `dacite.store.jvm` | `host` | `impl/clojure/src/dacite/store/jvm.clj` | JVM LMDB store (`lmdbjava`) and the LMDB root cell. |
| `dacite.store.nbb` | `host` | `impl/clojure/src/dacite/store/nbb.cljs` | nbb filesystem store (Node `fs`). |
| `dacite.store.nbb.lmdb` | `host` | `impl/clojure/src/dacite/store/nbb/lmdb.cljs` | nbb LMDB store (Node `lmdb` addon, format v1). |
| `dacite.store.remote` | `host` | `impl/clojure/src/dacite/store/remote.clj` | JVM HTTP client of the service (`java.net.http`). |
| `dacite.store.reveal` | `host` | `impl/clojure/src/dacite/store/reveal.clj` | JVM HTTP/3 reveal client (Jetty). Calls `dacite.service.reveal/accept-ready!`. |
| `dacite.value.render` | `host` | `impl/clojure/src/dacite/value/render.clj` | JVM `print-method` and bounded `toString` (`java.io.Writer`). |
| `dacite.value.serial` | `host` | `impl/clojure/src/dacite/value/serial.clj` | Legacy JVM node codec (`java.nio.ByteBuffer`). Prefer `dacite.wire.binary`. |
| `gen-fuse-fixtures` | `host` | `impl/clojure/dev/gen_fuse_fixtures.clj` | JVM writer for `fixtures/fuse-sl2`. |
| `gen-peer-fixtures` | `host` | `impl/clojure/dev/gen_peer_fixtures.clj` | JVM writer for `fixtures/peer-v1` (Cheshire, `clojure.java.io`). |
| `gen-wire-fixtures` | `host` | `impl/clojure/dev/gen_wire_fixtures.clj` | JVM writer for `fixtures/wire-v1`. |

## Server-only

The HTTP service process. Names are `dacite.service` and `dacite.service.*`.

| Namespace | Class | File | Rationale |
|-----------|-------|------|-----------|
| `dacite.service` | `server-only` | `impl/clojure/src/dacite/service.clj` | Jetty HTTP/1.1 and HTTP/3 server, static files, and the node routes. |
| `dacite.service.main` | `server-only` | `impl/clojure/src/dacite/service/main.clj` | Process entry (`clojure -M:service`). |
| `dacite.service.reveal` | `server-only` | `impl/clojure/src/dacite/service/reveal.clj` | `POST /reveal` session writing a `java.io.OutputStream`. |
| `dacite.service.throttle` | `server-only` | `impl/clojure/src/dacite/service/throttle.clj` | Inbound HTTP admission for `dacite.service`. |

### Edges the lint allows

The lint is one way: `core` may not require `host` or `server-only`. A
`host` namespace may require a `server-only` one. Three do, and this
milestone leaves them where they are:

- `dacite.peer.items` requires `dacite.service.reveal` (`encode-page`).
- `dacite.store.reveal` requires `dacite.service.reveal` (`accept-ready!`).
- `dacite.bench.todo-bw` requires `dacite.service`.

## Scripts that are not namespaces

These files are on the dev path and are loaded with `-i`. They have no
`ns` form, so they are not in the tables above. Adding an `ns` form means
adding a row.

| File | Note |
|------|------|
| `impl/clojure/dev/page_size_bench.clj` | JVM page-size bench (`clojure -M:dev -i dev/page_size_bench.clj`). |
| `impl/clojure/dev/page_size_degenerate.clj` | JVM degenerate leaf-shape bench. |
| `impl/clojure/dev/time_tests.clj` | Times the JVM suite (CONTRIBUTING.md). |

## How the lint test works

`impl/clojure/test/dacite/core_boundary_test.clj` is part of the JVM suite
(`cognitect.test-runner` picks up every `*_test.clj`). GitHub Actions
`.github/workflows/test.yml` runs the nbb suites only; the JVM suite stays
local.

```bash
cd impl/clojure && clojure -M:dev:test -n dacite.core-boundary-test
```

`inventory-matches-the-tree` walks `impl/clojure/src` and
`impl/clojure/dev`, parses every `ns` form (all reader-conditional
branches), derives the class from the rules above, and fails if the tables
in this file disagree: a missing namespace, a wrong file, a wrong class, a
`core` namespace that requires a `host` or `server-only` namespace, or a
script that grew an `ns` form without a row.

The negative case does not edit the tree. It builds a two-namespace graph
in memory:

- `core-namespace-must-not-require-a-host-namespace`: `dacite.pure`
  (`core`) requires `dacite.store.file` (`host`). The checker reports
  `:forbidden-dependency`.
- `core-namespace-must-not-require-a-server-only-namespace`: the same, with
  `dacite.service`.
- `reader-conditional-branches-are-all-visible`: a `#?(:cljs …)` require of
  a host namespace fails even though the test runs on the JVM.
- `core-may-require-the-host-contract`: a require of `dacite.host` does not
  fail.

A namespace marked `core` that acquired a host marker, or a new `.cljc`
file whose requires stay inside `core`, fails `inventory-matches-the-tree`
until this document is updated to match.
