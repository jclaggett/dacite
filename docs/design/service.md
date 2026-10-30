# Dacite Service Design

**Status:** MVP implemented — HTTP protocol for remote stores; leaf-chunking
pack paths and measured soft budget (**1024**) are live; SSE `GET /events`
is live. Productization (multi-user auth/provisioning) remains open.

**Supersedes:** archived `dacite.server` + `dacite.service` root semantics (those used ad-hoc meta-db roots; new service uses `dacite.rooted`).

## Architecture

Each user gets a **dedicated content store** and **dedicated root**. No shared multi-user map at the service root. Authentication identifies which store to access; isolation is by provisioning, not in-store authorization proofs.

```
Client                          Server
  |                               |
  |  LayeredStore(mem, Remote)    |  RootedStore(LMDB content + root cell)
  |  RootedStore(remote, ...)     |
  |                               |
  +--- GET /node/{hex} --------->|  pack-filled get
  +--- PUT /node/{hex} --------->|  s-put
  +--- HEAD /node/{hex} -------->|  s-has?
  +--- POST /nodes -------------->|  apply pack chunk (write)
  +--- POST /nodes/get ---------->|  bulk pack (demoted; admin/sync only)
  +--- POST /reveal ------------->|  one reveal stream (wire-v1 items)
  +--- GET /root ---------------->|  @store (root hash or null)
  +--- POST /root/cas ------------>|  compare-and-set! on rooted store
```

`GET /events` — SSE root announcements (`event: root`, EDN `{:root hex-or-nil}`).
First event is the current root; later events fire on successful CAS.

`POST /reveal` — one focused hash. The body is EDN `{:hash "<64 hex>"}`.
The response is `application/vnd.dacite.item-stream.v1` (concatenated
wire-v1 node items, no `Content-Length`). See [Reveal](#post-reveal).

## Authentication

MVP: **Bearer token** in `Authorization` header. Token maps to a user id; user id maps to a provisioned store path on the server.

Implementation-defined for MVP tests (static token → single test store).

Today a Bearer token is also the **inbound throttle bucket name** (not a
capability). If the header is absent, the bucket is the remote IP. See
[Inbound throttle](#inbound-throttle).

## Wire format

**Content-Type:**

- `application/edn` — default for root CAS, novelty responses, and clients that do not request binary.
- `application/vnd.dacite.chunk.v1` — wire-v1 binary **chunk** bodies for pack-filled
  `GET /node/{hex}` (when `Accept` prefers it) and `POST /nodes` (when
  `Content-Type` is binary). See [wire-v1.md](../spec/wire-v1.md).
- `application/vnd.dacite.item-stream.v1` — `POST /reveal` response only.
  Concatenated wire-v1 items, not a chunk. See [wire-v1.md](../spec/wire-v1.md).

JVM `remote-store` defaults to binary for pack GET/POST; browser demo still uses EDN.

**Hash encoding:** 64-character lowercase hex (256-bit, 4 × 64-bit words), as produced by `dacite.store/hash->hex`.

## Node endpoints (maps to `IStore`)

### `GET /node/{hash-hex}`

Fetch a stored node. Always a pack-filled chunk (leaf-chunking read path):

**Response 200:** one `chunk-v1` envelope. The first/primary item
installs `{hash-hex}`; further items are a **BFS** neighborhood under that
hash until the soft pack budget seals the chunk (**sent** bytes: wire-v1
when `Accept` asks for it, EDN length otherwise). A node whose cached `:size-bytes` is ≤ 1024 (and whose realized form
dry-runs to the claimed hash) is encoded as `:literal` even if the chunk is
already partly full; include-then-seal may then push the body toward ~2k.
Sequence literals pack contiguous same-type leaves as nested `run` /
`repeat`.
Client applies the chunk locally (`apply-chunk!`) then reads the requested
hash.

There is no bare-node or node-only query opt-out. Call `pack-under` with
budget 0 for a single-item chunk (the asked hash only).

**Response 404:** Node not present.

This keeps the unit of interaction content-addressed (**one hash asked**) while
allowing opportunistic under-fill. It avoids a separate “download whole value
tree” API as the primary path.

### `PUT /node/{hash-hex}`

Store a node. Idempotent — same hash + same content is a no-op.

**Request body:** EDN-encoded store value.

**Response 200:** novelty body:

```clojure
{:ok true
 :status :partial    ; newly stored
 :created [hash-hex]
 :exists []
 :applied 1}
;; or, if already present:
{:ok true
 :status :complete
 :created []
 :exists [hash-hex]
 :applied 1}
```

- **`:partial`** — at least one key was new (here: this hash).
- **`:complete`** — server already had every key in the request.

**Response 400:** Malformed body.

The server **does not** verify that `{hash-hex}` matches the content hash of the value (client responsibility). Optional validation may be added later.

### `HEAD /node/{hash-hex}`

Existence check (`s-has?`).

**Response 200:** Present.

**Response 404:** Absent.

### `DELETE /node/{hash-hex}` (optional, for GC)

Remove a node. Used when server runs GC or admin compaction.

**Response 204:** Deleted (or already absent).

MVP client may omit; `s-delete` on remote store uses this when present.

### `POST /nodes` (pack write)

Apply one leaf-chunking chunk (`:node` / `:literal` items). See
`docs/design/leaf-chunking.md`.

**Response 200:**

```clojure
{:ok true
 :applied n
 :nodes n
 :literals n
 :created [hex …]   ; newly installed
 :exists  [hex …]   ; already present (idempotent)
 :status  :partial  ; any created
          ;|:complete ; all existed
 }
```

Clients (e.g. write-back flush) can mark `:exists` roots’ local reachable
subgraphs as already on the server and skip re-uploading them.

### `POST /nodes/get` (bulk pack — demoted)

**Demoted.** Full-subgraph pack under roots can encourage full-value transfer
and unbounded server work (DoS-shaped on large roots). Prefer pack-filled
`GET /node/{hex}` for interactive clients.

Kept for admin/sync/tools: same request/response as before
(`:roots`, `:have`, `:budget` → `:chunks`).

## Reveal

### `POST /reveal`

Stream one focused hash. This is the QUIC reveal carrier. It is not pack
GET. `remote-store` uses it only when opened with `:reveal`.

**Request.** `Content-Type: application/edn`. Body: `{:hash "<64 hex>"}`.
A body that is not that map, or a hash that is not 64 lowercase hex
characters, is **400** with `{:error "malformed reveal"}`.

**Response 200.** `Content-Type: application/vnd.dacite.item-stream.v1`.
No `Content-Length`. The body is a concatenation of wire-v1 **node**
items, one page each, in reveal order. An empty body is a completed
reveal that had nothing left to send. `Cache-Control: no-store`.

**Response 404.** The hash is not in the store. No body, same as
`GET /node/{hex}`.

One HTTP connection keeps one `dacite.reveal` session. A later `POST
/reveal` on that connection shares the rate and skips hashes the session
has offered or holds. A second request sent while the first response is
still open is another reveal in that session: the parent stream stays
up, and the new reveal starts at the peak. On HTTP/3 the connection is
the QUIC session: `ConnectionMetaData.getConnection` is the request
stream, a new object every time, so the session is keyed by the QUIC
session on that stream's endpoint. On HTTP/1.1 the metadata connection
is the TCP connection.

The handler blocks the request thread until the walk finishes, as
`GET /events` does. The stream holds one API throttle slot for that
whole time, not an SSE slot. A body over `:max-body-bytes` is **413**.

When the walk finishes, the server acknowledges the pages that stream
wrote (`ack-pages`) and ends the response. The same clean end happens
when the reveal pauses below the floor, or when the current share
cannot cover the next page. The reveal stays, and a later request
resumes it, keeping credit already earned and the remainder of the
clock that could no longer cover the next page. A second request for a hash that is already open waits on
that reveal and does not replace the stream that is writing it. When
the write fails, or the stream goes 20 seconds with no new page, and
it is the last stream on that reveal, the server cancels the reveal and
releases those offers, so a retry on the same connection can send them.
There is no client-to-server ack or release message yet. That inference
is a gap.

`peak`, `tau`, `floor`, and `r-max` are server configuration, merged
over `:reveal` passed to `start-server!`. The defaults are placeholders:
`{:peak 1.0 :tau 1.0e6 :floor 0.0 :r-max 1.0e7}`. A lone decay can send
at most `r-max * tau` bytes before that clean end. The default `tau`
puts that budget far above a normal value. A floor at or below 0 does
not pause a reveal. The rate is not derived from load or the pipe.
`GET /events` stays up on cleartext HTTP/1.1 while a parent reveal and
a focused child are both open. A root change in that overlap still
arrives on that stream. `GET /node/{hex}` stays the pack.

`dacite.store.remote` opts in with `:reveal {:url h3-url :client client}`.
`:url` is the HTTP/3 origin. `:client` is a started Jetty HTTP/3 client
from `dacite.store.reveal/open-client`, one connection per destination.
Omitted, the store opens that client (`:trust-all` accepts the dev
certificate). A missing local hash is one `POST /reveal`. Items are
read as their bytes arrive, checked, and stored in the pack cache. The
get returns when the stream ends. A second get of a hash that is not
local yet, issued while that stream is still open, is another
`POST /reveal` on the same connection. When that response ends without
the node, the get waits for pages still in flight on the first stream.
A later get of a page that stream stored is local. PUT, DELETE, root CAS, and `watch-root` stay on the
cleartext `base-url` and the JDK client. Without `:reveal`, `s-get` is
still pack GET.

## Root endpoints (maps to rooted store)

### `GET /root`

Current root hash.

**Response 200:** `{:root hash-hex}` or `{:root nil}` if unset.

**Response body (EDN).**

### `POST /root/cas`

Compare-and-set root update — the portable core from Chapter 4.

**Request body (EDN):**

```clojure
{:expected hash-hex-or-nil   ; nil = root currently unset
 :new hash-hex}              ; new root (required)
```

**Response 200:** `{:ok true}` — CAS succeeded.

**Response 409:** `{:ok false}` — root changed concurrently (`expected` mismatch).

**Response 400:** Invalid body.

Unconditional `set-root` is **not** exposed remotely (per Chapter 4 — unsafe under sharing).

### `GET /events`

Server-sent events for root changes. `Content-Type: text/event-stream`.

Each event:

```text
event: root
data: {:root "…64 hex…"}\n
```

or `{:root nil}` when unset. The first frame is the current root. Comment
lines (`: keepalive`) may appear to hold the connection.

JVM clients use `dacite.store.remote/watch-root`. Browser `EventSource`
works on the same path.

## Soft pack budget (leaf-chunking 2d)

**Default: 1024 bytes** (`dacite.store.pack/default-budget`).

Used as the soft Layer‑2 threshold for:

- write-side `pack-items` / write-back flush (`POST /nodes`)
- read-side pack-filled `GET /node/{hex}` (`pack-under` BFS seal)
- Layer‑1 literal vs node policy (size cue / 2× wire refuse)

Measured with `clojure -M:dev -m dacite.bench.todo-bw --budget-sweep`
(encode fixtures + live write-back suite over 256…4096). **1024** is the
smallest budget that minifies interactive todo bandwidth (6 suite requests)
while still splitting clearly oversized values into progressive chunks.
See `docs/design/leaf-chunking.md` §2d for tables.

Clients may pass a larger `:budget` for bulk sync; smaller budgets increase
request count without helping the common interactive path.

## Bulk operations

### `POST /nodes` — pack chunk put

Apply one **chunk envelope** (EDN) with Layer‑1 `:node` and/or `:literal`
items. See `docs/design/leaf-chunking.md`.

**Request body:**

```clojure
{:dacite.wire/chunk-v1 true
 :budget 1024
 :items [/* :node and/or :literal items */]}
```

**Response 200:** novelty body (`:created`, `:exists`, `:status`, counts).
See `POST /nodes` under node endpoints above.

**Response 400:** malformed chunk.

Clients pack until soft budget ≥ `budget` (chunks may approach 2× budget),
then POST each chunk. Write-back flush uses this path when available.

## Inbound throttle

Server policy so one client cannot occupy the process and starve another.
**Not** `dacite.store.rate-limit` (that limiter sleeps on outbound
`send-chunk!`). Empty inbound bucket is **429**, not a held handler thread.

**Client key:** `Authorization: Bearer <id>` if present, else remote IP. The
token is only a bucket name. Two processes on `127.0.0.1` share an IP bucket
unless they send distinct Bearers (`remote-store` `:token`).

**Responses:**

| Status | When | Header |
|---|---|---|
| 429 | per-client rate or inflight exceeded | `Retry-After` (seconds) |
| 413 | request body larger than `:max-body-bytes` | — |
| 503 | global API / SSE slot exhausted | `Retry-After` |

Body (EDN): `{:ok false :error "rate limited" :retry-after-s n}` (or
`"body too large"` / `"server busy"`).

**Defaults** (on for `clojure -M:service`; `:throttle false` disables):

| Cap | Default |
|---|---|
| `:client-rate` | 50 /s |
| `:client-burst` | 100 |
| `:client-inflight` | 8 |
| `:max-body-bytes` | 1 MiB |
| `:max-threads` | 32 |
| `:max-sse` | 16 |
| `:sse-per-client` | 4 |
| `:pack-get-max-budget` | 65536 |
| `:pack-get-max-starts` | 32 |

`OPTIONS` and `/app/*` are unmetered. A `/app/` path that is a directory
serves `index.html` (todo at `/app/`, explorer at `/app/explorer/`).
`GET /events` counts against the SSE
caps only (not the request bucket). `POST /reveal` holds one API slot
for the whole stream. `POST /nodes/get` `:budget` and start
hash lists are **clamped** to the server maxima (still 200).

JVM `remote-store` retries 429/503 up to 8 times using `Retry-After`.

CLI: `--throttle off`, `--rate`, `--burst`, `--inflight`, `--max-body`,
`--threads`.

### Deferred

- `GET /walk/{hash}` — guarded transitive fetch
- Binary wire for pack envelopes

## Client composition

```clojure
(with-open [lmdb (lmdb-store path)]
  (let [remote (remote-store "https://api.example.com" {:token "..."})
        cache  (lru-store 1000)
        content (layered-store cache remote)
        store (rooted-store content (lmdb-root-cell lmdb))]
    ...))
```

Local LMDB root cell caches last-known root for offline reopen; remote is source of truth when online.

## Server

Jetty. Cleartext HTTP/1.1 on the service port, and HTTP/3 on UDP at the
same port number (`Alt-Svc: h3=":<port>"`). The dev certificate is
self-signed for `localhost` and `127.0.0.1`.

Still one process-wide `RootedStore` in this demo. Handlers delegate
to `IStore` / `compare-and-set!`. Not started:

- One `RootedStore` per provisioned user (LMDB path per user)
- Token middleware to select that store

Archive reference: [impl/clojure/archive/server.clj](../../impl/clojure/archive/server.clj) (node GET only; lacks CAS root, DELETE, auth).

## Open questions

- Per-user LMDB vs single LMDB with prefixed keys?
- Root persistence: meta db vs reserved content slot (see Phase 2.5)
- SSE for `/events` — shipped (root announcements; keepalive comments)
