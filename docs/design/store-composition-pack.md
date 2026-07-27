# Store composition: packing (and future throttle)

**Status:** IN PROGRESS — design + first composition APIs (`flush-from!`,
`find-chunk-transport`). Rate limiting is **deferred** until composition is
clean.

**Related:** [leaf-chunking.md](leaf-chunking.md) (Layer 1/2 laws, budget 1024),
[service.md](service.md) (HTTP), [stores-phase-2.md](stores-phase-2.md).

## Goal

Make **literal encoding** and **packing** first-class, composable store layers so:

1. Write-back / domain code stay free of encode internals.
2. Future layers (throttle, binary codec, compression) plug in at clear boundaries.
3. The server can run a **mirrored** unpack pipeline into primitive `s-put`.
4. Transport feedback stays at the **node/chunk** grain; **value completeness**
   (recursive materializability) stays a **value-layer** concern—not an `IStore`
   feature.

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

## Presence vs completeness (layer boundary)

### Suspicion (accepted)

**Value completeness** (can we fully realize / walk the value at `H`?) does
**not** belong in the lower Store layer.

A store is a content-addressed **node map**. It answers:

| Question | Store-native? |
|----------|----------------|
| Is entry `H` present? | Yes — `s-has?`, novelty `:exists` |
| Did this put/chunk install anything new? | Yes — `:created` / `:status :partial\|:complete` |
| Can realize(value at `H`) finish without more fetches? | **No** — needs type-aware walk / value laws |

Recursive materializability depends on `child-hashes`, literal L5 coverage, and
constructor graphs—that is **value-layer** (or rooted/sync policy above the
content store), not `IStore`.

Putting “closure bits” or `value-status` *inside* the store protocol would:

- Couple media stores to value semantics  
- Tempt partial indexes that drift from the node map  
- Blur the Values vs Store split used in the todo demo  

### What the Store / pack layer *does* provide

**Node- and chunk-level transport feedback only:**

```clojure
{:ok true
 :status :partial | :complete   ; this request’s items: any new vs all already present
 :created [hex …]
 :exists  [hex …]
 :applied n}
```

- **`:exists`** — idempotent node presence (skip re-send of those hashes)  
- **`:status :complete`** — every *item in this chunk/request* already existed  
  (not “the whole value tree is ready”)  
- Write-back may use `:exists` + local graph knowledge to grow a **client-side**
  skip set; that set is a cache of presence, not a store feature  

**Throttle (later)** meters packages (`send-chunk!`); it does not decide value
readiness.

### Where value completeness lives

| Concern | Layer |
|---------|--------|
| Node present | Store / novelty |
| Chunk items all existed | Store / novelty (`:status`) |
| Subgraph closed for realize(`H`) | **Value** (walk / realize / optional helper above store) |
| “Flush job done; safe to CAS root” | **Client policy** (write-back + value checks), not IStore |
| Stop packing under `H` | **Client/value policy** using local intent + presence feedback |

Optional later helpers (e.g. `value-closed? [store h]` that walks
`child-hashes`) can live next to GC / value APIs—they **read** a store, they
do not extend `IStore` with a completeness field.

### Back pressure without store-level completeness

1. **Slow down** — token bucket under pack (future): pace `send-chunk!`.  
2. **Skip nodes** — novelty `:exists` (store-native).  
3. **Stop the job** — client/value policy: e.g. `encode-reachable` empty, or a
   value-layer closure check *before* CAS—not a new store primitive.

Server does not need to advertise “value complete” on the store protocol for
composition of pack middleware. If a product later wants a ready-check API, it
can be a **rooted/value service** endpoint (walk + answer), separate from
`IStore`.

---

## Server middleware shape

Conceptually, service `POST /nodes` becomes:

```text
parse body → validate chunk envelope
  → (optional) admit / rate-limit inbound
  → apply-chunk!   ; unliteralize + s-put into content store
  → compute novelty (created / exists / request status)
  → respond
```

`apply-chunk!` remains the **only** place that turns wire items into store
entries (single unliteralize implementation). No closure index in the durable
store for MVP composition work.

Inbound throttle (connections, body size, chunk rate) is **server policy**,
orthogonal to client token-bucket under pack.

---

## Open decisions

| # | Question | Lean |
|---|----------|------|
| 1 | W1 vs W2 for first pack store | **W2 first**, API-ready for W1 |
| 2 | Who owns `flushed` / skip set? | Write-back atom; pack store accepts `skip` and returns `covered` + novelty |
| 3 | Read pack-under location | Stay on remote/server for now |
| 4 | Value completeness | **Out of Store layer** — client/value policy only; store keeps presence/novelty |
| 5 | Protocol surface | `IChunkTransport` + `flush-from!` (or pack-store method); avoid growing core `IStore` until needed |

---

## Implementation phases

| Phase | Deliverable |
|-------|-------------|
| **P0** | This design doc — **done** |
| **P1** | `find-chunk-transport` / `as-chunk-transport`; chunk path does not unwrap past middleware — **done** |
| **P2** | `flush-from!` (W2); write-back calls it; `wrap-chunk-transport` helper — **done** |
| **P3** | Tests: todo-bw / pack parity; middleware sees every `send-chunk!` |
| **P4** | Rate-limit store under pack (`send-chunk!` takes tokens, block on empty) |
| **P5** | Optional W1 buffering pack store |
| **Later** | Value-layer closure helpers / CAS-ready checks (not IStore) |

---

## Success criteria

- Documented client and **mirrored server** pipelines
- Pack invocation centralized; write-back is cache policy + flush hook only
- No unwrap that skips middleware on chunk send
- Store layer limited to **node presence + chunk novelty**; no value-completeness API on `IStore`
- Throttle has a clear insertion point **below** packing; not built yet

---

## Relation to leaf-chunking 2a–2d

Leaf-chunking laws, budgets, and encode/materialize stay in `pack.cljc`.
This doc only changes **where** those functions are invoked (store middleware
and service receive path), not the value model or default budget **1024**.
