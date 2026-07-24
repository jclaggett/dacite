# Leaf-chunking (transport packing) design

**Status:** DRAFT — design approved; **2a implemented** (`POST /nodes`, `dacite.store.pack`, write-back chunked flush).

**Related:** `docs/design/service.md` (HTTP store protocol), phase 1 commit removing `:inline` from `dacite.value.collections`.

## Goal

Cut HTTP **request count** and redundant bytes for remote stores **without** changing the durable Dacite value model. Collections remain finger trees / HAMTs in the store. Packing and literal encodings live only on the **wire**.

## Two layers

### Layer 1 — Value representation (per node)

For each **hash** the peer needs, send **exactly one** of:

| Form | Wire | Receiver |
|------|------|----------|
| **Simple node** | hash + store content (`[type data]`, children as hashes) | `s-put(hash, content)` — same as today’s single-node protocol |
| **Literal** | hash + type + host/rebuild body | Recreate a node so **`hash(recreated) = claimed hash`**, with correct **type** and **measures** (count / size-bytes); FT/HAMT laws hold for whatever children the receiver materializes |

**Not required:** identical child hashes or the same internal spine as the sender.  
**Required:** the node at the claimed hash is a valid Dacite entry of that type with that identity hash.

**Decision rule (MVP):**

```
for needed hash H with entry N = s-get(H):
  if fits-literal?(N, budget):   ; use dacite-size / :size-bytes when present
    emit { :hash H, :encoding :literal, :type …, :body … }
  else
    emit { :hash H, :encoding :node, :body N }
```

Default **budget = 1024** (tunable; also guides Layer 2). Prefer literal when the whole node (or a host form that rebuilds to the same hash) fits.

**No leaf-packs / fragments.** Large values are handled by walking the tree and encoding **each needed node** as simple or literal. Chunking batches those items. There is no separate “range of a blob” wire kind.

**Receiver (`:literal`):** materialize via the normal value path (e.g. `coerce-and-store!`); optional strict mode asserts root hash equals `H`. Intermediate nodes may differ from the sender’s store.

### Layer 2 — Chunking (pool → ship)

Layer 1 emits a **stream of items**. Layer 2 only batches them.

**Soft budget (MVP):**

- Append items to an open chunk until **chunk size ≥ budget**, then **send** and start a new chunk.
- Chunks may be up to about **2 × budget** (the item that crossed the threshold can nearly fill another budget).
- When the transfer unit is done (e.g. write-back flush finished), **flush** the last partial chunk even if size &lt; budget.
- A single oversized item ships alone (may exceed 2× budget only if one item is that large).

```
needed hashes → Layer 1 encode → items → Layer 2 pack (≥ budget → HTTP) → flush remainder
```

**Envelope sketch (EDN):**

```clojure
{:dacite.wire/chunk-v1
 {:budget 1024
  :items [/* {:encoding :node|:literal, :hash …, …} */]}}
```

**HTTP sketch (after implementation):**

| Role | Idea |
|------|------|
| Send | `POST /nodes` (or `/pack`) — one body = one chunk |
| Fetch pack | `POST /nodes/get` `{:hashes […] :budget 1024}` → one or more chunks |
| Compat | Keep `GET/PUT /node/{hex}` |

## Composition with write-back cache

| Concern | Where |
|---------|--------|
| What is missing / what to flush | Client write-back / local cache |
| Encode each hash as `:node` or `:literal` | Layer 1 |
| Batch until ≥ budget; flush remainder | Layer 2 |

## Parameters

| Name | Default | Role |
|------|---------|------|
| `chunk-size` | 1024 | Soft Layer 2 threshold; cue for Layer 1 literal vs node |
| `pack-enabled?` | true on remote clients | Force single-node path for debugging |

Sweep 256…4096 with `dacite.bench.todo-bw` (and later large-blob scenarios) after 2a.

## Implementation order

| Step | Scope |
|------|--------|
| **2a** | Layer 1 `:node` only + Layer 2 accumulate-until-≥-budget (batch put; optional batch get). Measure request collapse on write-back flush / reload. |
| **2b** | Layer 1 `:literal` for nodes that fit; receiver recreate + hash check. |
| **2c** | Large trees: walk + mix of node/literal only; large-blob scenario. |
| **2d** | Budget sweep; update `service.md` and this doc with measured defaults. |

## Non-goals

- Reintroducing value-layer collection inlining
- Leaf-pack / fragment / conj-into-partial-root protocols
- Hard budget (reject items that would exceed budget)
- Binary wire as the first milestone (EDN envelopes OK for MVP)

## Open questions (proposed defaults)

1. Strict hash verification on receive: **yes in tests**, optional flag for demos.  
2. Literal for collections: prefer host/`coerce-and-store!` when it preserves root hash; else `:node`.  
3. Domain compact `te` todo-entry: keep for now; may later be pure Layer‑1 literals of small maps.

## Relation to prior work

- **Phase 1:** removed `:inline` / `:inline-refs` from `dacite.value` so collections stay pure FT/HAMT.  
- **Bandwidth suite / write-back:** orthogonal client policy; chunking sits under remote flush/fetch.  
- **Archived `inline_under`:** early GET expand idea; supersede with this two-layer pack model rather than mixing into value types.
