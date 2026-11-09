# Dacite Roadmap

*Last updated: 2026-11-09*

## Current direction

**Peering is the active plan** ([plan/peering.md](plan/peering.md)).
M5.1 compiled the peer for the browser with shadow-cljs. M5.2 and M5.3
are built: the browser reads a value held only by the nbb peer, and the
library reader loads a chapter and a plate from that peer. The next
peering slices are the Value Browser (M6.4–M6.6): a pasted
`dacite://<host>#urn:dacite:<hex>`, scalars and text as they
are, collections as a tree of hashes, a raw/hex pane, then a root URL.
Origin-integrated signaling (the old M6.1–M6.3, including origin
suggest) is set aside. PR #47 is that docs-only draft and is not the
next slice. The page's local tier is IndexedDB (`dacite.store.idb`).
The browser transport is `RTCPeerConnection`
(`dacite.peer.transport.browser`). The earlier `:cljs-web`,
`:cljs-explorer`, and `:cljs-library` bundles stay on `cljs.build.api`.

**Client/server reveal is the work.** The public-domain library is large
enough to show the transport cost, and that cost is the current problem.
A remote chapter or image is one `GET /node/{hex}` per ~1–2 KB chunk.
Browser `fetch` is done: `dacite.store.browser` uses `fetch`, and `drive`
reads `response.body` as the bytes arrive, then runs the value op again
against that exchange. Store methods return promises. The JVM and
babashka block inside `await`, so the public value API stays synchronous
there. ClojureScript walks still replay through `drive`. A page can
open `dacite.store.idb`. The next milestone is the QUIC reveal client.
Pack decode still waits
for the full `Content-Length` body. The demo server is Jetty.
Cleartext HTTP/1.1 is still the `base-url`. HTTP/3 listens on UDP at
that same port number, with a self-signed certificate, and responses
carry `Alt-Svc`. A pack is still one buffered body. Java 21's
`HttpClient` cannot select `HTTP_3` (that arrived in Java 26).

The four layers (content stores, hash fusion, values, rooted stores) plus
pack transport and the demo HTTP service are an alpha vertical slice. Six
claim-proving apps are shipped. The book is inverted for app authors. The
library CLI and browser reader (`/app/library/`) live in
`dacite.examples.library`. Storage chunking is the **default** for
`s/file` and `s/lmdb` (pack-literal flush on root commit). Sequences are
~1,024-byte literal pages, and a `u8` run payload is a copied byte buffer.

`s/mem`, `s/file`, and `s/lmdb` are JVM or nbb. In compiled ClojureScript
those constructors throw. The todo, explorer, and library pages still
read through `dacite.store.browser`. The browser peer page stores
verified entries in IndexedDB (`dacite.store.idb`).

`IStore` methods return a promise on every host. `s-get` fulfills with
nil when this dictionary has no hash. A required read fails as
`:missing`. A store that cannot answer rejects as `:unavailable`.
`finger_tree` and `hamt` await the node before they use it. On the JVM
and babashka that `await` blocks, so count, nth, conj, and print still
return values. ClojureScript and nbb do not yet return promises from
those walks. The browser cannot block, so `drive` throws a promise and
runs the value operation again. The account is
[design/progressive-revelation.md](design/progressive-revelation.md).

Do this next:

1. **QUIC reveal client.** The server speaks HTTP/3. `remote-store` maps
   the reveal rule onto QUIC. One connection stays open. Each focused
   child collection is a stream. Resetting the stream cancels that
   reveal. The root-notice stream stays up beside those reveals. This
   is one carrier of the child and byte limits, not a new demo. Java
   21's `HttpClient` has no `HTTP_3`; the server test uses Jetty's
   HTTP/3 client. The reveal rule in this file is still the byte and
   child limits above. The rate-based account is the same design doc.
   `dacite.reveal` is the default page order, the interest shares, and
   the session clock. A page goes out whole, in wire-item bytes, when
   its reveal's credit covers it. The share is
   `R_max * I / max(peak, sum I)`, and the clock integrates that
   exponential, so a lone reveal decays from the open. Offering a page
   does not hold it. `ack-pages` records the hold, and `forget-pages`
   queues a reset or evicted page again. A hash already held, or still
   offered, is skipped. Asking again for a hash still open resumes the
   pages the client does not hold and restarts that clock at the peak.
   `POST /reveal` is that stream. The body is EDN `{:hash "<64 hex>"}`.
   The response is `application/vnd.dacite.item-stream.v1`: a
   concatenation of wire-v1 node items, no chunk envelope, and no
   `Content-Length`. One connection keeps one session. On HTTP/3 the
   connection is the QUIC session, because each request is its own
   stream object. A stream that finishes acknowledges the pages it
   wrote. A stream that fails cancels that reveal and forgets those
   offers. The HTTP stream still has no client ack message, so that
   completion stands in for one. Peer v1 has `ack` (spec 6.4): a hint the
   responder's sent-hash record may honor. A missing page is a new `get`
   of that hash. `remote-store` opts into the stream
   with `:reveal`: a missing hash is one `POST /reveal` on a kept
   HTTP/3 connection, and the pages land in its local cache. The
   default read is still pack GET. A second `POST /reveal` on that
   connection, while the first response is still open, is a focused
   child. The parent reveal stays open. The new one starts at the
   peak, and a hash either stream has offered is not written on the
   other. Resetting a stream is not the path this milestone tests: a
   write holds the session lock, so an abandoned read can stall that
   connection.
   `GET /events` stays up on cleartext HTTP/1.1 while that parent and
   its focused child are both open. A root CAS in that overlap arrives
   on the notice stream before the parent response ends.
   `peak`, `tau`, `floor`, and `r-max` are placeholders, not chosen
   from load. The pages on that stream are the carrier a later peer
   reuses. Signaling and a peer list stay out of this milestone.

**Stream a pack on one HTTP response** follows the reveal client. It is
the HTTP/1.1 carrier of the byte limit in *How every client reveals a
value*. The server buffers the chunk and sends it with `Content-Length`.
JVM `s-get` reads it with `ofByteArray`.
`watch-root` is the call that pins `HTTP_1_1` and reads `ofInputStream`.
`GET /events` already streams on this server. `POST /nodes/get` stays the
demoted bulk snapshot.

**Peering**, through M5.3, is a channel to a peer the operator named
and a fetch by hash. Manual introduce (the slice-3 room) is enough.
One WebRTC data channel carries one reveal. Each received entry is
checked against its hash before it is stored, and a bad entry ends
that peer. The peer protocol stays read-only. A root is a mutable
reference in the layer above, published by the peer the URL names.
There is no privileged origin in the peering model. The library
reader is the measurement already taken: bytes and time from the
peer against bytes and time from the origin
(`plan/measurements.md`). The account is
[design/peering-design-note.md](design/peering-design-note.md) and
[plan/peering.md](plan/peering.md) Phase 6.

Next on that line is the Value Browser. The operator pastes
`dacite://<host>#urn:dacite:<hex>`. The
fragment is the content name and is not sent as an HTTP path. The
page renders scalars and text as they are, a HAMT or finger tree as
a tree of hashes, and a raw/hex pane. A root URL is the following
slice. A peer list is a value at a root, the same kind of walk.

Later on that same line: mDNS and a configured list of peer URLs, as
ways to learn a URL, and a tab that serves after the user opts in.
A path-form HTTP URI may exist still later, as a fallback when no
peer channel is up. A DHT stays off this road. `GET have` stays
shelved with the other pack query opt-outs. `have?` on the peer
wire stays removed from v1.

Still open on that line: whether asking a peer exposes the read and
who may be asked, whether a peer serves every hash it holds, and
whether a TURN relay is worth it for a cache. Peer rates are
answered (one shared `R_max`). Peer lists by root or by locality
are withdrawn from the peering plan.

The library project waits until the reveal client and the streamed pack
are done. Come back to it for:

1. **EPUB ingest.** `ingest` takes a title and a UTF-8 string. A zip, a
   spine, and resource blobs are not in this example.
2. **Extra indexes.** The catalog has a title index.
3. **Upload form.** The browser reader is shelf, table of contents, and
   page.

A seventh demo is out of scope. The library stays the app that pulled
this communication work, and the app to return to.

## How every client reveals a value

A Dacite value may be very large. Every client, local or remote, reveals
it from its root and decides how far the reveal goes. The rule names no
transport. A byte budget, a chunked body, `fetch`, a QUIC stream, and a
WebRTC data channel are carriers, listed above.

Revealing a hash yields that value's own node: the type, the measure, the
literal body that fits there, and the child hashes. It does not yield the
transitive contents.

The client limits **bytes** of this value's own body. Internal finger-tree
pages belong to this value and count against that limit. A sequence of
characters or a blob of bytes stays in this reveal.

The client limits **children**. A nested map, vector, set, string, or blob
arrives as a hash and stays a hash until the client reveals it. Each
reveal has its own byte limit and its own children.

The client owns the scope. A value outside the scope is not requested.
Cancelling a reveal stops further bytes. Bytes already stored stay in the
cache.

A new document root is a notice of the new hash. The client reveals that
hash and then chooses which children are still in scope. Unchanged child
hashes are already stored, so those reveals are not repeated.

A local client follows the same rule in memory. `nth` and a bounded slice
are reveals. Realizing an entire large value is a reveal with no limit.

The README thesis is still unproven as *utility*:

> structural sharing · fetch only what changed · lazy access · perfect
> caching · versioned snapshots · config push

Shipped examples show that values persist, hashes are stable, and packed
GET/POST can be cheap. They do not yet show that Dacite is a better way to
write a program someone would miss.

**Architecture:** dedicated stores per user. A shared multi-user map stays
off this list. Authorization and sharing chapters remain archived; see
[book/archive/](book/archive/).

**Value model:** store-aware values; constructors persist into a bound
store. Maps and sets are HAMTs. Sequences are finger trees. `ft/deep` is
three hashes. `ft/digit` and `ft/node` are ~1,024-byte literal pages
(`run` / `repeat` / nested lits / `ref`). A child that is already a page
is a hash. Collection hashes are `digest(fuse(type, elements_fuse))` of the logical
leaves, so the hash does not depend on page width. A `u8` run of length
≥ 2 stores a copied `byte[]` (JVM) or `Uint8Array` (ClojureScript). One
byte stays a `u8` scalar. Measure fuses the 256 `u8` scalar hashes in
place.

**Transport:** leaf-chunking packs, soft budget **1024**, pack-filled
`GET /node/{hex}` (no query opt-outs), write novelty `:created` /
`:exists`. Write-back flush uses the same Layer 1 literals as GET.
`pack-under` with budget **0** is a single item. Durable pages are the
value encoding; pack ships those literals. How a client bounds a large
value is *How every client reveals a value*. The open work, in order:
a QUIC reveal client, then a chunked pack GET, then a LAN peer tier.
The library resumes after the reveal client and the streamed pack.
Store methods return promises. `dacite.store.idb` is the page's
IndexedDB dictionary.
Browser `fetch` and the HTTP/3 server are done. ClojureScript value
walks still replay through `drive`.

---

## Process

One application at a time. Each app must falsify or confirm a Dacite claim.
Library work is **pulled by that app**, not queued in advance.

A good next app has all of:

1. A user who would notice if it broke.
2. A persistence story (local, remote, or both).
3. At least one property a plain atom + EDN file cannot match (history,
   partial fetch, cheap sync, two writers, content identity).
4. Domain code that only requires `dacite.value` and `dacite.store`.
5. A measurement (bandwidth, time-to-first-paint, conflict rate,
   domain-vs-wiring line count).

A bad next app is another walkthrough of `conj` / `assoc` / `swap!` on a
tiny tree.

### Rules of engagement

1. **Public API only.** New app code may require `dacite.value` and
   `dacite.store`. If it needs `dacite.store.pack` or
   `dacite.value.collections`, that is a library hole — promote or wrap, do
   not leak.
2. **No app-local `title-str`.** When domain code grows a twenty-line helper
   to read a field, fix `dacite.value` in the same change.
3. **Keep the Values / Store split.** [todo.cljc](../impl/clojure/src/dacite/examples/todo.cljc)
   already has the architecture. Copy that, not a single-file demo.
4. **Measure the claim.** The bandwidth harness (`dacite.bench.todo-bw`) is
   the pattern. Clone it per app. If you cannot measure sharing, lazy fetch,
   or CAS retries, the app is still a demo.
5. **One app to “usable,” then the next.** Do not scaffold a sequence. The
   current app should be runnable from the README.
6. **Write the book from the app.** Tutorials teach the *move* (nested
   document, history, a page of a large vector, …). The book’s job is *how
   to write programs*, not another restatement of the four layers.

---

## Stay on the value: no emergency parachute

`dac->clj` (and `clj->dac`) exist on the JVM as a **last-resort hatch**. Their
presence is a smell: they assume a Dacite value can be realized into RAM, which
is not safe for large or partially available data. A size cap does not make
that sound — it only fails a bit earlier.

Do **not**:

- port `dac->clj` to SCI / nbb / the browser
- treat whole-value host conversion as part of the public application API
- fix “I needed a Clojure value” by dumping the tree

Do this instead: name the **why**, then add a Dacite utility that serves that
why without materializing the whole value.

| Why (so far) | Targeted utility | Status |
|---|---|---|
| REPL / `println` / logs | Bounded render (`dacite.value.render`, `toString`, `print-method`) | Shipped — the model |
| Read a scalar or short string field | `v/native` / `v/native` (optional char limit; `as-str` via `native`) | Shipped (pulled by remote config) |
| Bounded print of long strings | `v/pr-str` (truncates; never dumps the tree) | Shipped (pulled by remote config) |
| Nested document edit | `get-in` / `assoc-in` / `update` / `update-in` | Shipped (pulled by remote config) |
| Show a page of a large vector, string, or blob | `v/slice` | Shipped (pulled by event log; strings/blobs for the library) |
| Export / interop (JSON, files) | A *specific* encoder that streams or bounds | Only if an app needs it |

`realize` stays: scalars yield a host atom; collections yield a **lazy** seq of
realized elements. Consuming the whole seq is an explicit full-traversal, not
a convenience API. `dac->clj` remains in `dacite.convert` for tests and
emergencies. Application code that reaches for it has found a hole in
`dacite.value`.

---

## Where we are (alpha 0.2)

### Value layer (Chapter 3)

- [x] Store-aware value protocol (`IDaciteValue`, `realize`, `dacite-hash`)
- [x] Scalars, strings, blobs, vectors, maps, sets
- [x] Finger-tree and HAMT internal nodes with `child-hashes` for graph walks
- [x] Implicit leaf singles (`ft/single` removed — see [ft-single-elision.md](design/ft-single-elision.md))
- [x] `ft/digit` and `ft/node` are ~1,024-byte literal pages. `ft/deep`
      stays three hashes. See [dense-sequence-leaves.md](design/dense-sequence-leaves.md)
- [x] A `u8` run payload is a copied byte buffer. See
      [boxed-u8-runs.md](design/boxed-u8-runs.md)
- [x] Lazy deep `realize` for partial availability
- [x] Bounded `toString` / REPL print (`dacite.value.render`)
- [x] Public surface `dacite.value` + `dacite.store`; `*-via` constructors;
      value-level `root-ref`
- [x] `dac->clj` / `clj->dac` — JVM hatch only; not a goal (see above)

### Content stores (Chapter 1)

- [x] `IStore` protocol (`s-get`, `s-put`, `s-has?`, `s-delete`, `s-merge`, `s-reset`)
- [x] Mem, file, LMDB stores
- [x] Layered store with read-through backfill
- [x] LMDB `Closeable` for `with-open`
- [x] LRU cache store
- [x] Remote HTTP store + client-cache policies (`:none`, `:smart-put`, `:write-back`, `:layered`)
- [x] Stats / bandwidth instrumentation (`dacite.store.stats`, todo-bw bench)
- [x] Pack composition (`flush-from!`, outermost `IChunkTransport`)
- [x] Rate-limit store (`dacite.store.rate-limit`)
- [x] HTTP inbound throttle (`dacite.service.throttle` — per-client 429)

### Hash fusion (Chapter 2)

- [x] `dacite.hash` — fuse in SL(2, F_p) with a 256-bit prime, generator table, ref digest (hash version 2, issue #14; see [fuse-sl2.md](design/fuse-sl2.md))
- [x] The fuse kernel behind five hex-in, hex-out functions (`dacite.hash/kernel`) with known-answer vectors, so a WASM or native implementation can replace the arithmetic
- [ ] **Review gate: an outside cryptographic review of the SL(2, F_p) fuse before Dacite trusts hashes from untrusted peers.** The best attack we know costs about 60·sqrt(p) (about 2^134 for the 256-bit prime, bounded by 2^128 at the 32-byte ref); an earlier estimate of ~p was wrong, which is why this gate exists. Until it is done, peers are trusted (not adversarial) and the design doc says so.
- [x] The `ref->state` cache is a bounded LRU (`dacite.cache`, 65,536 entries, thread-safe on the JVM) instead of emptying wholesale (issue #17; the cold-stream row of `bench_ops` goes from 6.9 s to 4.8 s on the JVM and 13.1 s to 10.8 s on nbb)
- [x] Bounded memoization of the fuse of a node's children: **tried and not kept** (issue #17). A pairwise memo of `measure-combine` gains 9% to 30% on the JVM for incremental updates and nothing on a bulk build, costs about 60 MB of heap for that gain, and is 20% slower on nbb. A memo keyed on a bitmap node's children hits 0% to 0.2% on builds and assocs, because an unchanged node is shared, not rebuilt. Numbers in [fuse-sl2.md](design/fuse-sl2.md), "The `ref->state` cache, and memoizing the fuse of a node's children". Revisit with a profile of a real workload, or if the kernel changes
- [x] The cache itself was benchmarked against no cache at all (issue #17, PR #19): on the JVM, no cache is 1.25x to 1.6x slower than the cold LRU on vector, conj, rebuild and overflow rows; kept. Table in [fuse-sl2.md](design/fuse-sl2.md)
- [ ] `ref->state` cache after 1.0 (issue #20): the JVM cache's one lock serializes reads (23 ms on 1 thread, 1.2 s on 8; shard it or use a concurrent cache once builds run in parallel), and whether eviction should prefer high-value refs (frequency, subtree size); decide with real workloads
- [ ] Make byte runs cheaper (a blob costs a matrix product per byte; about 10x main on the JVM, see the table in fuse-sl2.md)

### Rooted stores (Chapter 4)

- [x] `dacite.rooted` — `RootedStore`, `IRootCell`, CAS, watches, validators
- [x] `mem-root-cell`, `lmdb-root-cell`, `file-root-cell`
- [x] `push-ref` sync primitive
- [x] Value-aware GC (`dacite.rooted.gc`)
- [x] `sync-reachable!` — copy a reachable subgraph (packed flush to remotes)

See [design/stores-phase-1.md](design/stores-phase-1.md) and
[design/stores-phase-2.md](design/stores-phase-2.md).

### Transport

- [x] Leaf-chunking 2a–2f (budget **1024**) — [leaf-chunking.md](design/leaf-chunking.md)
      Layer 1 gate is cached `size-bytes` + dry-run hash; sequence bodies use
      nested `run` / `repeat`; Layer 2 seals on **sent** (wire-v1) bytes
- [x] Wire-v1 binary packs — [spec/wire-v1.md](spec/wire-v1.md)
- [x] HTTP service MVP (`dacite.service`) — [service.md](design/service.md)
- [x] Pack-filled `GET /node/{hex}` only (no `?raw=`, `?nodes=`, `?near=`)
- [x] Write-back flush (`flush-from!`) uses the same `encode-item` as GET
- [x] `GET /events` SSE root announcements; JVM `watch-root`
- [x] JVM + browser remotes (browser `fetch` inside `drive`, demo only)
- [x] HTTP/3 server (Jetty). Cleartext HTTP/1.1 remains `base-url`.
      HTTP/3 is UDP on that port, `Alt-Svc`, self-signed dev cert

### Documentation

- [x] Book inverted for app authors — [The Dacite way](book/the-dacite-way.md),
      [Anatomy](book/building/anatomy.md), cookbook, pack/HTTP as internals
- [x] Book chapters 1–4 (now “How it works”)
- [x] Values / Stores API reference
- [x] Claim tutorials: Hello World, config, notes, event log, two-client,
      directory sync, value explorer

### Examples today

| App | What it proves | What it does not |
|---|---|---|
| Hello nbb | Constructors, `realize`, hash identity | Persistence, sync, a user |
| [config](../impl/clojure/src/dacite/examples/config.cljc) | Same domain on file + HTTP; two clients, same hash | Multi-writer UX |
| [notes](../impl/clojure/src/dacite/examples/notes.cljc) | Snapshots, restore by hash, title-only sharing | Multi-writer, web UI |
| [event log](../impl/clojure/src/dacite/examples/event_log.cljc) | 2000 events, page via slice, cheap append | Compact/concat, missing-node UX |
| Two-client live | Interleaved appends + SSE watch | Browser UI |
| [sync](../impl/clojure/src/dacite/examples/sync.cljc) | List without bodies; one-file < clone | Opaque-byte file store |
| [cards.clj](../impl/clojure/src/dacite/examples/cards.clj) | LMDB root, `peek`/`pop`/`conj` | Multi-player, history, anything large |
| Todo CLI | Durable file root, Values/Store split | Scale, sync, two writers |
| Browser todo | HTTP + write-back + CAS + bandwidth; `fetch` via `drive` | Two clients, `v/root` |
| [explorer](../impl/clojure/src/dacite/examples/explorer.cljc) | Typed tree of the root; page expand < full seq | Edit, SSE, string/blob “read more” |
| [library](../impl/clojure/src/dacite/examples/library.cljc) | Sets as tables, title index as vector, shelf via `slice`, page via `nth`; plain-text Moby Dick under a 512 MB heap; browser shelf/TOC/reader | EPUB zip ingest, extra indexes, upload form |

Library-pain already visible in those apps (fix in the library, not with
more helpers — **when an app pulls it**):

- ~~`title-str` / `field-native` — ~20 lines to read a string field~~ (now `v/native` / `v/native`)
- ~~Cards shuffle dumps to a host vector~~ (Fisher-Yates via `nth` / `assoc`)
- Browser todo commits at the hash/CAS level, not `v/root`
- Seed catalog at budget 1024 (`clojure -M:library-storage`): snapshot
  **109** file entries, GC-live **85**, chunked **36**. File data is
  **1.48×** live-vs-chunked. The 0.2.0 figures (7,779 / 365 / 48, about
  16×) were the one-element-leaf catalog. See the table under *Pulled by
  the library catalog*.

---

## Application sequence (done)

These six apps are history. Do not start a seventh demo to “complete the set.”

### 1. Remote config — done

**Claim:** the server publishes a root hash; clients pull only what they
need. Same domain against mem, file, and HTTP.

Shipped: [config.cljc](../impl/clojure/src/dacite/examples/config.cljc),
`store/remote-rooted-store`, `v/native` / `v/native` / `get-in` /
`assoc-in` / `update` / `update-in`, tutorial
[config.md](book/tutorial/config.md). Two HTTP clients see the same
config hash (`dacite.examples.config-test`). Seed uses `ref-cas!` from
nil — `ref-reset!` stays local-only.

### 2. Versioned notes — done

**Claim:** every root hash is a complete snapshot; unchanged subtrees are
free.

Shipped: [notes.cljc](../impl/clojure/src/dacite/examples/notes.cljc), tutorial
[notes.md](book/tutorial/notes.md). Notebook is `{doc, history}`; restore
reinstalls the historical doc value (same hash). `bench` / tests show a
title-only edit shares the body hash and adds fewer store nodes than a
body rewrite. Print uses `v/pr-str`. No new library API — path ops and
bounded string render from config were enough.

### 3. Event log + derived view — done

**Claim:** large sequences stay cheap to append; you need not realize the
whole log.

Shipped: [event_log.cljc](../impl/clojure/src/dacite/examples/event_log.cljc),
`v/slice`, tutorial [event-log.md](book/tutorial/event-log.md). Seed
2000 credit/debit events. Page uses `slice`; replay folds with `nth`.
Append node-delta stays small from n=100 to n=2000. A remote test shows
page 0 receives fewer bytes than `seq` of the whole log. Prefix
`slice` has the same hash as a freshly built prefix.

Missing-node errors stayed on the shelf — page/replay did not need a new
error type.

### 4. Two-client live — done

**Claim:** compare-and-set is the whole distributed update story.

Shipped: `GET /events` (SSE), `store.remote/watch-root`,
`v/swap-info!`, event-log `watch` / `contend`, tutorial
[two-client.md](book/tutorial/two-client.md). Two remotes can interleave
appends without lost events; a third process reprints on SSE. The rebase
loop was already `ref-swap!`; `:retries` is the conflict UX.

Two JVM remotes plus an SSE watch prove the claim. The browser demos
use `fetch` inside `drive`.

### 5. Directory / blob sync — done

**Claim:** trees of blobs + maps sync cheaper than a whole-tree copy.

Shipped: [sync.cljc](../impl/clojure/src/dacite/examples/sync.cljc), `v/as-bytes`,
`store/sync-reachable!`, tutorial [sync.md](book/tutorial/sync.md). `ls`
reads `kind`/`size` only; `cat` realizes one blob. Identical files share
a hash. A second local sync copies 0 nodes. Remote GET of one blob
transfers less than GET of that blob plus its siblings. Missing blobs
throw `:dacite/missing`.

Opaque-byte store entries stayed on the shelf — the fetch claim does not
need them.

### 6. Value explorer — done

**Claim:** lazy access — browse the root as typed Dacite values without
realizing the whole tree.

Shipped: [explorer.cljc](../impl/clojure/src/dacite/examples/explorer.cljc),
`/app/explorer/`, tutorial [explorer.md](book/tutorial/explorer.md).
Empty root CAS-seeds a type gallery; an existing root is displayed.
Vectors/maps/sets expand in pages of 32. Strings and blobs are truncated
leaves. A remote test shows the first page of a 128-element vector
transfers less than `seq` of the whole vector.

Edit, SSE live root, and string/blob “read more” stayed on the shelf.

---

## Library work the apps will pull

Treat this as a backlog that **appears**, not a build order. Nothing below
is the next milestone.

**Pulled by config + notes**

| Gap | Why | Status |
|---|---|---|
| `v/native` / `v/native` | Stop writing `title-str` | Done (config) |
| `get-in` / `assoc-in` / `update` / `update-in` | Nested documents | Done (config) |
| Remote `root-ref` | Domain must not drop to hashes for HTTP | Done (`IRoot` + `remote-rooted-store`) |

**Pulled by the event log**

| Gap | Why | Status |
|---|---|---|
| Range / slice on sequences | Pagination without realizing the log | Done (`v/slice` — vector, string, blob) |
| Catchable missing-node errors | Partial availability is currently an exception from `s-get` | **Done** for a required read — `require-node` throws `{:reason :missing}`. `s-get` still fulfills with nil when the hash is absent. The partial-value policy stays deferred |

**Pulled by two clients**

| Gap | Why | Status |
|---|---|---|
| Async remote store | Browser must not block the page | **Done** — `fetch` inside `drive`. `response.body` is read as bytes arrive. Pack decode still waits for the full `Content-Length` body. Store methods return promises; ClojureScript walks still replay through `drive` |
| SSE (or a documented poll helper) | Watches across the network | Done (`GET /events`) |
| Value-level CAS retry that rebases | `ref-swap!` / `ref-swap-info!` | Done (same loop on remote) |

**Pulled by the value explorer**

| Gap | Why | Status |
|---|---|---|
| `/app/` directory `index.html` | Nested static app next to todo | Done (`handle-static`) |
| Browser `require` inside `dacite.store` | JVM ClojureScript forbids it | Done (browser stubs; nbb unchanged) |
| `v/scalar` / `v/root` smash child nses in cljs | Pack literal `put-scalar!` vanished | Done (omit those two defs in the browser) |
| Pack literals hash-mismatch on cljs | JVM strings rematerialize differently | Done (host-string + `put-scalar!` smash); explorer uses default pack |
| String/blob “read more” | Longer prefix, still a leaf | Deferred |
| Async browser store | Expand without blocking the tab | **Done** — todo, explorer, and library call `drive` |

**Pulled by file sync**

| Gap | Why | Status |
|---|---|---|
| Blob ingest / export | Files | Done (`v/as-bytes`, `blob-via`) |
| Copy reachable subgraph | `push-ref` without a sync helper is incomplete | Done (`sync-reachable!`) |
| Opaque-byte store bodies | File store as EDN is a host-local dead end for blobs | Still deferred |

**Pulled by the library catalog**

| Gap | Why | Status |
|---|---|---|
| Pack literals as durable layout | Inner store keeps pack items; raw stores stay exploded | **Done** — `dacite.store.chunk`; `s/file` and `s/lmdb` flush on root commit. Raw `file-store` / `lmdb-store` stay exploded |
| File + LMDB size benchmarks | Compare snapshot, GC-live, and chunked on the seed | **Done** — `dacite.bench.library-storage`, remeasured 2026-10-16. See table below |
| GC on flush / drop build spines | `conj-right` leaves every intermediate node | Overlay still holds construction debris in RAM; inner is pack items. Deferred as a product change |
| Default chunked file/LMDB | One durable layout for app stores | **Done** — defaulted. On this seed, file data is 1.48× live-vs-chunked. The book-scale evidence is plain-text Moby Dick under a 512 MB heap |
| 1k literal pages | One scalar per character made a novel a multi-GB live graph | **Done** — `ft/digit` and `ft/node` hold ~1,024 payload bytes. Gutenberg Moby Dick plain text (~1.26M characters) ingests in about 24 seconds under `-Xmx512m`, peaking near 550 MB RSS, 136 chapters |
| `u8` byte buffers | A blob page kept each byte as a boxed integer | **Done** — copied `byte[]` / `Uint8Array`; measure fuses the 256 `u8` scalar hashes in place. The illustrated EPUB (~47 MB, 157 JPEGs) was measured before this change and has not been re-run |
| Nested root / lens | Clients CAS a subtree; disjoint paths do not reject | **Local `s/lens` done.** HTTP stays document GET/CAS. |
| Reveal requirements | Every client must bound a large value | **Captured** — *How every client reveals a value*. Bytes and children are the two limits. Transport is a carrier |
| Browser `fetch` | The browser store has to read a body while the tab stays responsive | **Done** — `dacite.store.browser` uses `fetch` and `drive`. Independent of HTTP/3 |
| HTTP/3 server | The JDK server is HTTP/1.1 only | **Done** — Jetty. Cleartext HTTP/1.1 stays `base-url`. HTTP/3 is UDP on the same port, `Alt-Svc`, self-signed dev cert. Java 21 `HttpClient` still has no `HTTP_3` |
| Asynchronous IStore | Every store is the same dictionary, local or remote | **Done** — methods return promises. JVM and babashka block in `await`. A missing required node is `:missing`; a store that cannot answer is `:unavailable`. ClojureScript walks still replay through `drive` |
| IndexedDB store | A web client needs a durable store of its own | **Done** — `dacite.store.idb`. `open` is a rooted store; `idb-store` is the content dictionary a `SmartCacheStore` can put in front of a remote. Ops suspend and `drive` replays them |
| QUIC reveal client | A focused child collection is its own stream | **Next** — `POST /reveal` streams wire-v1 node items on one connection (the QUIC session on HTTP/3). A second POST while that response is still open is a focused child: the parent stays open, and a hash either stream has offered is not sent on the other. `GET /events` stays up while those two reveals are open. Resetting a stream is not tested. A finished stream acknowledges its pages; a failed stream cancels and releases them. No client ack or release message yet. `remote-store` opts in with `:reveal` and keeps one HTTP/3 connection; the default read is still pack GET. `peak`, `tau`, `floor`, and `r-max` are placeholders. `dacite.reveal` remains the page order, the shares, and the session clock. The pages on that stream are the carrier the LAN peer tier reuses. The account is [design/progressive-revelation.md](design/progressive-revelation.md) |
| Stream a pack on one GET | A remote chapter or image is one round trip per ~1–2 KB | **After the reveal client** — HTTP/1.1 carrier of the reveal byte limit. Chunked wire-v1 under the asked hash, applied as bytes arrive |
| LAN peering | A second reader on the LAN should take pages from a neighbor's cache | **Through M5.3, built** — a channel to a named peer and a fetch by hash. Manual introduce. One WebRTC data channel carries one reveal. Reads only. **Next: the Value Browser** (M6.4–M6.6). Origin signaling and origin suggest are set aside. A peer list is a value at a root. The account is [plan/peering.md](plan/peering.md) and [design/peering-design-note.md](design/peering-design-note.md) |

Seed catalog, budget 1024, after literal pages (`clojure -M:library-storage`, 2026-10-16):

| Layout | Store | Entries | Wire bytes | Data file | Used pages |
|---|---|---|---|---|---|
| snapshot | file | 109 | 13,522 | 36,871 | — |
| gc-live | file | 85 | 10,770 | 29,111 | — |
| chunked | file | 36 | 7,531 | 19,614 | — |
| snapshot | lmdb | 109 | 13,522 | 245,760 | 49,152 |
| gc-live (in place) | lmdb | 85 | 10,770 | 245,760 | 49,152 |
| gc-live-fresh | lmdb | 85 | 10,770 | 180,224 | 16,384 |
| chunked | lmdb | 36 | 7,531 | 131,072 | 16,384 |

Fair live-vs-chunked on this seed: file data **1.48×**, wire **1.43×**. LMDB used-pages **1.00×** (both 16,384; a catalog this small ties on page rounding). File snapshot/chunked **1.88×**. The 0.2.0 table (7,779 / 365 / 48, about 16× live-vs-chunked, about 380× snapshot-vs-chunked) measured the one-element-leaf catalog. `s/lmdb` writes pack `encode-item` bytes; raw `lmdb-store` still accepts exploded node payloads.

Infrastructure from the old Phase 2.5 list (pack as middleware polish, root
slot, layered write policies, spec v0.5, GET `have`, remaining-budget skip)
stays on the shelf until a real app reaches for it. Pack query opt-outs
(`?raw=`, `?nodes=`, `?near=`) were removed on purpose. A later peer
bitmap is a message on the data channel, not `GET have`.

---

## Deferred

Do **not** start these until a real app would actually use one of them.

- Host collection adapters ([design/host-collection-adapters.md](design/host-collection-adapters.md))
  — JVM sugar; they cannot be the portable API (SCI has no adapters).
- User types ([design/future/user-types.md](design/future/user-types.md))
  — design-only; nothing in the app list needs an open type system.
- Multi-tenant accounts, Bearer productization, backup
  ([service.md](design/service.md)).
- Python / C ports. Hash parity on three Clojure hosts is enough until an
  app is worth porting.
- Spec v0.5 as a standalone milestone.
- Negative sets, sorted map/set, richer `IReduce`.

**Deferred on experience, not on an app:**

- **"When not to use Dacite" (spec/docs section).** Write a spec/docs section on where Dacite is a poor fit, and how to wrap niche formats as opaque blobs. Deferred until we have real experience using Dacite and at least a couple of concrete cases where it fell short. Log such cases as they happen in [notes/dacite-shortfalls.md](notes/dacite-shortfalls.md).

**Post-benchmark (explore, no implementation now):**

- **WebAssembly acceleration (explore).** Start only after hash fusion and
  codec benchmarks exist in nbb and in the browser. Candidates:
  1. **Hash fusion**, the hottest path. It gains from native `i64` and SIMD,
     and avoids the BigInt or 32-bit-pair overhead.
  2. **Wire encode and decode**, but only for bulk work such as hashing or
     verifying whole frames. Per-value JS to WASM boundary cost may cancel
     the gain.

  Design note: keep the core behind a narrow, byte-oriented interface, so one
  WASM module (Rust or Zig) can be swapped in and shared by the nbb and
  browser peers.

**Not needed (eliminated by dedicated stores):**

- [x] ~~Proof of Possession~~
- [x] ~~Authorization layer~~
- [x] ~~Sharing mechanisms~~

---

## Suggested order (from here)

```
Remote config … value explorer                     ✓  (claim demos)
Invert the book for app authors                    ✓
        → The Dacite way, anatomy, tutorials as
          patterns, cookbook, pack/HTTP as internals
Public-domain library                              ✓ CLI + /app/library/
        → catalog root (epubs/books sets, title index)
Storage: chunked durable layout                    ✓ s/file + s/lmdb default
        → seed catalog is 109 / 85 / 36 entries;
          file data 1.48× live-vs-chunked
Lens over a rooted store                           ✓ local s/lens
        → client convenience; HTTP is still document GET/CAS
Literal pages                                      ✓ ft/digit and ft/node ~1k
        → plain-text Moby Dick under a 512 MB heap
u8 run payload                                     ✓ copied byte buffer
        → measure fuses the 256 u8 hashes in place
Reveal requirements                                ✓ this roadmap
        → bytes and children; transport is a carrier
Browser fetch                                      ✓
        → fetch inside drive; replay until IStore awaits
HTTP/3 server                                      ✓ Jetty
        → cleartext HTTP/1.1 stays the base-url;
          HTTP/3 is UDP on that port, Alt-Svc
Asynchronous IStore                                ✓ promises
        → JVM and babashka still block in await;
          ClojureScript walks still replay through drive
IndexedDB store for web clients                    ✓ dacite.store.idb
        → rooted open, or the content store in front of remote
QUIC reveal client                                 ← current
        → POST /reveal streams wire-v1 items on one
          connection. A focused child is a second
          stream while the parent stays open.
          GET /events stays up beside them.
          remote-store opts in with :reveal; the
          default read is still pack GET. Pages on
          that stream are what a peer reuses
Stream a pack on one HTTP response                 after the reveal client
        → HTTP/1.1 carrier of the byte limit
LAN peering                                        through M5.3
        → a named peer and a fetch by hash;
          one data channel carries one reveal;
          manual introduce is enough
Value Browser                                      next (M6.4–M6.6)
        → paste dacite://host#urn:dacite:<hex>;
          scalars and text, collections as hashes,
          a raw/hex pane, then a root URL
Library, resumed                                   after reveal and streamed pack
        → EPUB ingest, extra indexes, upload form
```

Do not invent “app 7” to fill the tree.

---

## Related

- [The Dacite Book](book/) — how to think about data and how to build apps
- [Values API](book/reference/values.md) / [Stores API](book/reference/stores.md)
- [stores-phase-2.md](design/stores-phase-2.md) — shipped store/transport work
- [service.md](design/service.md) — HTTP MVP including SSE `GET /events`
- [leaf-chunking.md](design/leaf-chunking.md) — pack literals, budget 1024
- [dense-sequence-leaves.md](design/dense-sequence-leaves.md) — 1k literal pages
- [boxed-u8-runs.md](design/boxed-u8-runs.md) — `u8` run payload is a copied byte buffer
- [value-realization-and-computation.md](design/value-realization-and-computation.md)
  — `realize`, partial availability, bounded print
- [peering-design-note.md](design/peering-design-note.md) — LAN peer tier:
  WebRTC data channel, a named peer, fetch by hash. Origin signaling
  is set aside. The next demo is the Value Browser in
  [plan/peering.md](plan/peering.md)
