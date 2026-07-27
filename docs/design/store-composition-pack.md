# Store composition: packing (and future throttle)

**Status:** DRAFT — design for composing literal representation and packing as
store middleware under `IStore`. Rate limiting is **deferred** until this
composition is clean.

**Related:** [leaf-chunking.md](leaf-chunking.md) (Layer 1/2 laws, budget 1024),
[service.md](service.md) (HTTP), [stores-phase-2.md](stores-phase-2.md).

## Goal

Make **literal encoding** and **packing** first-class, composable store layers so:

1. Write-back / domain code stay free of encode internals.
2. Future layers (throttle, binary codec, compression) plug in at clear boundaries.
3. The server can run a **mirrored** unpack pipeline into primitive `s-put`.
4. The server can signal **completeness** so the client stops sending for a value
   (back pressure / early termination).

**Not in this doc:** implementing a token-bucket store (see §Throttle later).

---

## Pipeline

### Client (write)

```text
Application / write-back cache
        │  s-put (local) · flush-reachable(root)
        ▼
┌───────────────────┐
│  Pack store       │  Layer 1: entry → :node | :literal
│  (middleware)     │  Layer 2: items → soft-budget chunks
└─────────┬─────────┘
          │  send-chunk!
          ▼
┌───────────────────┐
│  Throttle (later) │  1 token ≈ 1 package (chunk)
└─────────┬─────────┘
          │
          ▼
┌───────────────────┐
│  Transport        │  POST /nodes, GET /node (pack-filled), …
│  (remote/browser) │
└───────────────────┘
```

Recommended stack:

```text
client-cache (write-back | smart-put | …)
  → pack store
  → [rate-limit store]     ; future
  → remote / browser       ; pure HTTP + apply-chunk! locally if needed
```

### Server (receive) — mirror

```text
HTTP POST /nodes (chunk)
        ▼
┌───────────────────┐
│  Unpack middleware│  chunk → items (already one request body)
└─────────┬─────────┘
          ▼
┌───────────────────┐
│  Unliteralize     │  :literal → materialize-literal! → many s-put
│                   │  :node    → single s-put(hash, body)
└─────────┬─────────┘
          ▼
┌───────────────────┐
│  Durable IStore   │  mem / file / LMDB (primitive puts only)
└───────────────────┘
```

Today `apply-chunk!` already does unliteralize + `s-put` in one place. The
design goal is to treat that as **server-side middleware** with the same Layer 1
laws as the client encode path—not as an ad-hoc handler body.

Symmetric naming (conceptual):

| Client | Server |
|--------|--------|
| literal-of / encode-item | materialize-literal! |
| pack-items / seal chunk | parse chunk envelope |
| send-chunk! | receive-chunk! → apply |
| (future) throttle send | (future) admit / 429 / delay |

Read path (pack-filled GET) is the dual: server **pack-under** (literal? + pack)
from durable store; client **apply-chunk!** into local cache.

---

## Why composition first (before throttle)

Packing is currently a **side path**:

- Write-back `flush-reachable!` open-codes `encode-reachable` + `put-items-chunked!`
- `unwrap-remote` peels client-cache (and would peel any middle layer) to find
  `RemoteStore` for chunk POST and CAS
- Remote `s-put` still does single-node PUT; pack is not on every write path

A rate-limit `IStore` wrapping remote would **miss** chunk POSTs and CAS unless
those paths also call through middleware. Throttle should sit **below** packing
so the metered unit is a **package**, not a raw node op.

```text
Wrong:   cache → rate-limit → remote     (limits PUTs; flush bypasses via unwrap)
Right:   cache → pack → rate-limit → remote   (limits send-chunk! / HTTP)
```

---

## Contracts

### Layer 1 (literal?) — pure, already in `pack.cljc`

- `encode-item` / `literal-of` / dry-run hash gate / size cue (2c)
- Intermediate FT/HAMT leaf payloads (2c′)
- Coverage law L5: a literal covers the subgraph materialize will create

### Layer 2 (packing) — pure, already in `pack.cljc`

- Soft budget default **1024** (2d)
- `pack-items`, `make-chunk`, `apply-chunk!`

### Transport boundary

Keep / refine:

```clojure
(defprotocol IChunkTransport
  (send-chunk! [this chunk]
    "POST one chunk. Returns novelty map when available."))
```

Optional later:

```clojure
(defprotocol IPackSource
  (fetch-pack! [this h have budget]
    "One pack-filled neighborhood for h (client read assist)."))
```

Middleware that sits above transport **must implement `send-chunk!` by
delegation** (possibly after its own work). No `unwrap-remote` past middleware
on the data path.

### Pack store (`IStore` wrapper) — to build

Wraps an inner store that supports chunk send (and ordinary `s-get`/`s-put`
fallback if needed).

**Write models:**

| | Behavior | When |
|--|----------|------|
| **W1 Buffering** | `s-put` encodes and enqueues; seal at budget; `flush!` ships remainder | End-state pipeline |
| **W2 Flush-driven** | Network pack only via `flush-from!(local, root, skip)`; write-back calls this API | Smaller first step |
| **W3 Eager** | One item → one chunk per put | Avoid (defeats packing) |

**Recommendation:** ship **W2** first (centralize encode/pack, kill bypass, keep
write-back flush semantics), design APIs so **W1** can absorb mid-stream sealing
later.

**Read:** pass through to inner; pack-filled GET may remain on remote for now
(server-side `pack-under`). Absorb applied neighborhood into client local as today.

### Write-back becomes cache policy only

- `s-put` → local only (unchanged)
- `flush-reachable!` → pack-store / `flush-from!` on the remote leg
- Does **not** import Layer-1 policy details beyond “covered hashes” / novelty

---

## Completeness and back pressure

### Problem

Without feedback, a client may keep packing and POSTing subgraph nodes the
server already has, or continue a multi-chunk upload after the **logical value**
the client cares about is fully present. That wastes tokens (when throttled) and
bandwidth.

### What we already have

Novelty on `POST /nodes` and `PUT /node`:

```clojure
{:ok true
 :status :partial | :complete   ; any created vs all existed
 :created [hex …]
 :exists  [hex …]
 :applied n}
```

Write-back already expands `flushed` from `:exists` via local `mark-reachable`.
That is **hash-level** completeness for known roots, not “this client job is done.”

### Desired signals (design)

Three nested notions of “done”:

| Level | Meaning | Client action |
|-------|---------|----------------|
| **Item exists** | Server already had this hash (`:exists`) | Skip re-send; mark covered |
| **Chunk complete** | Every item in the chunk was `:exists` (`:status :complete`) | Optional: backoff or stop related work |
| **Value complete** | Server holds a full materializable value at root `H` (all nodes needed for `H`) | Stop packing under `H`; no more puts for that job |

**Value complete** is the back-pressure signal that matters for large multi-chunk
uploads.

#### Option V1 — Client-driven (today + tighten)

Client tracks `covered` / `flushed` from novelty. When `encode-reachable`
returns no items, value is complete **from the client’s view**. Server does not
need a new message. Weakness: client must already know what it intended to send;
server cannot say “I already have `H` fully—stop.”

#### Option V2 — Server root-has / HEAD

Before or during flush, client `HEAD`/`s-has?` root hash `H`. If present,
**assume** value complete only if the server never stores partial values under a
hash (true for content-addressing: `H` exists iff that node exists; nested
missing children can still break realize). So **has?(H) is necessary but not
sufficient** for “fully recursive value available” unless the server guarantees
closed subgraphs on every put (hard) or tracks a **closure bit**.

#### Option V3 — Closure / “value ready” bit (stronger)

Server maintains, for selected hashes (e.g. flushed roots or CAS candidates):

```clojure
{:hash H :closure :complete | :partial | :unknown}
```

Updated when:

- A literal for `H` is applied (L5 → complete for that materialize closure)
- A `:node` put for `H` lands and all `child-hashes` are present transitively
- Or an explicit verification walk succeeds

Response extension (chunk or dedicated endpoint):

```clojure
{:ok true
 :status :partial
 :created […]
 :exists […]
 :value-status {H-hex :complete, …}}  ; optional map for roots client cares about
```

Client: if `value-status[H] = :complete`, mark entire reachable set flushed and
**stop** further chunks for that flush job.

#### Option V4 — Job / stream id

Client opens an upload job `{:root H :have #{…}}`; server returns
`{:remaining n}` or `{:complete true}` after each chunk. Heavier protocol;
useful for multi-chunk progressive pack later.

### Recommendation for composition phase

1. **Keep and document** novelty `:exists` / `:complete` as the primary back
   pressure for **items and chunks** (already partially wired in write-back).
2. **Design** for **value-level** completeness next to pack-store flush:
   - Prefer **V3** long-term (closure bit + optional `value-status` in novelty).
   - Near-term: treat “literal applied for `H`” and “encode-reachable empty”
     as complete; server may add `:value-status` without breaking clients that
     ignore it.
3. Throttle (later) uses the same stop condition: no more `send-chunk!` for a
   completed root → no tokens spent.

Back pressure is therefore not only “slow down,” but **stop when the server
says the value is whole**.

---

## Server middleware shape

Conceptually, service `POST /nodes` becomes:

```text
parse body → validate chunk envelope
  → (optional) admit / rate-limit inbound
  → apply-chunk!   ; unliteralize + s-put into rooted content store
  → compute novelty (+ optional value-status)
  → respond
```

`apply-chunk!` should remain the **only** place that turns wire items into
store entries (single implementation of unliteralize). Server-side “pack
middleware” is then mostly: envelope handling, novelty accounting, closure
updates—not a second materialize path.

Inbound throttle (connections, body size, chunk rate) is **server policy**,
orthogonal to client token-bucket under pack.

---

## Open decisions

| # | Question | Lean |
|---|----------|------|
| 1 | W1 vs W2 for first pack store | **W2 first**, API-ready for W1 |
| 2 | Who owns `flushed` / skip set? | Write-back atom; pack store accepts `skip` and returns `covered` + novelty |
| 3 | Read pack-under location | Stay on remote/server for now |
| 4 | Value completeness protocol | Novelty + optional `value-status` (V3); no job id yet |
| 5 | Protocol surface | `IChunkTransport` + `flush-from!` (or pack-store method); avoid growing core `IStore` until needed |

---

## Implementation phases

| Phase | Deliverable |
|-------|-------------|
| **P0** | This design doc |
| **P1** | `send-chunk!` delegation; stop data-path `unwrap-remote` past middleware |
| **P2** | Pack store (W2): `flush-from!` = encode-reachable + pack-items + send-chunk; write-back calls it |
| **P3** | Tests: todo-bw / pack parity; composition order |
| **P4** | Optional `value-status` / closure bit on server novelty |
| **P5** | Rate-limit store under pack (`send-chunk!` takes tokens, block on empty) |
| **P6** | Optional W1 buffering pack store |

---

## Success criteria

- Documented client and **mirrored server** pipelines
- Pack invocation centralized; write-back is cache policy + flush hook only
- No unwrap that skips middleware on chunk send
- Novelty / completeness story covers stop-sending (back pressure), not only slow-sending
- Throttle has a clear insertion point **below** packing; not built yet

---

## Relation to leaf-chunking 2a–2d

Leaf-chunking laws, budgets, and encode/materialize stay in `pack.cljc`.
This doc only changes **where** those functions are invoked (store middleware
and service receive path), not the value model or default budget **1024**.
