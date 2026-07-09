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
  +--- GET /node/{hex} --------->|  s-get
  +--- PUT /node/{hex} --------->|  s-put
  +--- HEAD /node/{hex} -------->|  s-has?
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

Fetch a stored node.

**Response 200:** EDN body — the raw store value (e.g. `["vector" {:root [...] :count 5 ...}]`).

**Response 404:** Node not present.

### `PUT /node/{hash-hex}`

Store a node. Idempotent — same hash + same content is a no-op.

**Request body:** EDN-encoded store value.

**Response 204:** Stored.

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

## Bulk operations (deferred)

- `POST /nodes` — batch put
- `GET /walk/{hash}` — guarded transitive fetch

Use layered read-through + lazy `s-get` for MVP instead.

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
