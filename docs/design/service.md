# Dacite Service Design

**Status:** DRAFT — defines the HTTP protocol for remote stores and the service MVP.

**Supersedes:** archived `dacite.server` + `dacite.service` root semantics (those used ad-hoc meta-db roots; new service uses `dacite.rooted`).

## Architecture

Each user gets a **dedicated content store** and **dedicated root**. No shared multi-user map at the service root. Authentication identifies which store to access; isolation is by provisioning, not in-store authorization proofs.

```
Client                          Server
  |                               |
  |  LayeredStore(mem, Remote)    |  RootedStore(LMDB content + root cell)
  |  RootedStore(remote, ...)     |
  |                               |
  +--- GET /node/{hex} --------->|  pack-filled get (default) or ?raw=1
  +--- PUT /node/{hex} --------->|  s-put
  +--- HEAD /node/{hex} -------->|  s-has?
  +--- POST /nodes -------------->|  apply pack chunk (write)
  +--- POST /nodes/get ---------->|  bulk pack (demoted; admin/sync only)
  +--- GET /root ---------------->|  @store (root hash or null)
  +--- POST /root/cas ------------>|  compare-and-set! on rooted store
```

Future: `GET /events` (SSE) for root change notifications — not required for MVP.

## Authentication

MVP: **Bearer token** in `Authorization` header. Token maps to a user id; user id maps to a provisioned store path on the server.

Implementation-defined for MVP tests (static token → single test store).

## Wire format

**Content-Type:** `application/edn` for MVP (matches current store serialization). Binary (`application/octet-stream` via `dacite.value.serial`) is a later refinement.

**Hash encoding:** 64-character lowercase hex (256-bit, 4 × 64-bit words), as produced by `dacite.store/hash->hex`.

## Node endpoints (maps to `IStore`)

### `GET /node/{hash-hex}`

Fetch a stored node. **Pack mode is the default** (leaf-chunking read path):

**Response 200 (default):** one `chunk-v1` envelope. The first/primary item
installs `{hash-hex}`; further items are a **BFS** neighborhood under that
hash until the soft pack budget seals the chunk. Client applies the chunk
locally (`apply-chunk!`) then reads the requested hash.

**Response 200 (`?raw=1`):** bare store node EDN (debug / simple tools).

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

## Bulk operations

### `POST /nodes` — pack chunk put (leaf-chunking 2a)

Apply one **chunk envelope** (EDN). Layer‑1 items for 2a are `:node` only
(hash + store content). See `docs/design/leaf-chunking.md`.

**Request body:**

```clojure
{:dacite.wire/chunk-v1 true
 :budget 1024
 :items [{:encoding :node
          :hash "64-hex"
          :body [type-name data]}
         …]}
```

**Response 200:** `{:ok true :applied n}`

**Response 400:** malformed chunk.

Clients pack until soft budget ≥ `budget` (chunks may approach 2× budget),
then POST each chunk. Write-back flush uses this path when available.

### Deferred

- `POST /nodes/get` — batch / packed fetch
- `GET /walk/{hash}` — guarded transitive fetch
- Layer‑1 `:literal` encodings (2b)

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

## Server MVP (planned)

1. JDK `HttpServer` or similar — minimal, no framework dependency
2. One `RootedStore` per provisioned user (LMDB path per user)
3. Token middleware → select store
4. Handlers delegate to `IStore` / `compare-and-set!`

Archive reference: [impl/clojure/archive/server.clj](../../impl/clojure/archive/server.clj) (node GET only; lacks CAS root, DELETE, auth).

## Open questions

- Per-user LMDB vs single LMDB with prefixed keys?
- Root persistence: meta db vs reserved content slot (see Phase 2.5)
- SSE for `/events` — polling `GET /root` sufficient for MVP?
