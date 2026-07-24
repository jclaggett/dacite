# Leaf-chunking (transport packing) design

**Status:** DRAFT — design approved for 2a/2b shipping; **universal recursive literals** formalized below (not fully implemented; 2b is a partial semantic subset).

**Related:** `docs/design/service.md` (HTTP store protocol), phase 1 commit removing `:inline` from `dacite.value.collections`.

## Goal

Cut HTTP **request count** and redundant bytes for remote stores **without** changing the durable Dacite value model. Collections remain finger trees / HAMTs in the store. Packing and literal encodings live only on the **wire**.

---

## Law: universal recursive node terms

**Claim:** Every durable Dacite store entry can be represented on the wire as a
**recursive node term**, and packing is the policy of how deep to expand that
term before falling back to hash references.

This is a property of **nodes** (content-addressed `[type data]` entries), not
of host language values. Host/`coerce-and-store!` forms are an optional sugar
layer on top, not the law.

### Grammar

A **term** is either a stop (hash only) or a typed node with recursive data:

```
Term      :=  {:ref Hex}                          ; stop expansion — peer must already have / will get this hash
           |  {:node Type DataTerm}               ; expand this entry

DataTerm  :=  the store entry's data, with every
              child-hash *slot* replaced by a Term
```

Equivalently, as a compact EDN sketch (tags are illustrative):

```clojure
;; Fully expanded vector of two strings (semantic sugar — see below)
[:v [[:s "hello"] [:s "world"]]]

;; Same idea structurally (type names match the store):
[:node "vector" {:root <ft-term> :count 2 :size-bytes …}]
[:node "ft/deep" {:left <digit-term> :spine <term> :right <digit-term> :measure …}]
[:node "ft/single" {:value-hash <char-or-value-term> :measure …}]
[:node "char" \h]

;; Truncation: expand the vector root but stop at the finger-tree root
[:node "vector" {:root {:ref "a1b2…"} :count 2 :size-bytes …}]
```

**Child slots** are exactly the positions reported by `types/child-hashes` for
that entry type (`:root` on collections; `:children` / `:left`/`:spine`/`:right`
on FT nodes; HAMT fields; empty for scalars).

### Laws

| # | Law | Meaning |
|---|-----|---------|
| **L1 Existence** | For every store `S` and hash `H` present in `S`, there exists at least one term `T` such that `materialize(T) = H`. | No node type is “literal-incapable.” Internals (`ft/*`, `hamt/*`) and core value types are included. |
| **L2 Hash fidelity** | If `T` was produced from `(S, H)` by replacing child slots with either `{:ref child}` or a faithful term for that child, then `materialize(T) = H`. | Structural terms are not approximate: they rebuild the **same content hash**, not merely “similar” host data. |
| **L3 Truncation** | Any child slot may be `{:ref Hex}` instead of a nested term. | Expansion depth is a **packing policy**, not a type-system limit. Depth 0 (all children refs) is exactly today’s simple node put. |
| **L4 Coverage** | A fully expanded term for `H` covers `H` and every hash reachable by replacing nested terms; covered hashes need not be sent as separate items. | Write-back / pack walks use this to skip descendants already inline. |
| **L5 Typed entries** | Every store type with a stable `[type data]` shape and `child-hashes` participates. | Encoding is dispatch on type name; structural terms do not require host coerce. |

**Not required:** the receiver’s intermediate spine to match the sender’s byte-for-byte when a *semantic* sugar form is used (see below).  
**Required for structural terms:** identity hash `H` after materialize.

### Structural term vs semantic sugar

| Kind | What it encodes | Round-trip | Status |
|------|-----------------|------------|--------|
| **Structural term** | Exact store `[type data]` shape; children are terms or refs | Always (L2) | **The law** — target for all node types |
| **Semantic sugar** | Host-shaped shortcuts, e.g. `[:s "hello"]`, `[:v [1 2 3]]`, plain EDN maps | Only when `hash(rebuild) = H` | Optional compression; subset of terms |

Examples of sugar that desugar to structural terms:

```clojure
[:s "hi"]
;; → string node + ft spine + char scalars, same as string-with-store

[:v [[:s "hello"] [:s "world"]]]
;; → vector root whose leaves are string value hashes, etc.
```

**2b (shipped)** implements only a **semantic** subset: scalars, string, blob,
map/set/vector of host-roundtrippable data, via `coerce-and-store!`, with a
dry-run hash check. It does **not** yet implement structural terms for `ft/*`,
`hamt/*`, or domain types — those correctly stay `:node` until structural
encode/materialize exists.

### Materialize (receiver)

```
materialize(term):
  if term is {:ref h}:
    require h already in store (or schedule fetch); return h
  if term is {:node type data-term}:
    data' = map child slots: materialize(slot)
    entry = [type data']
    h = content_hash(entry)   ; same algebra as s-put path
    s-put(h, entry)
    return h
```

For **semantic sugar**, expand to a structural term (or call the normal
constructor path) first, then assert `h = claimed`.

### Relation to Layer 1 encodings (wire items)

| Item encoding | Interpretation |
|---------------|----------------|
| `:node` + body | Structural term at **depth 0**: body is the raw `[type data]` with child **hashes only** (no nested terms). |
| `:literal` (2b) | Semantic sugar body + claimed hash; materialize via constructors / coerce. |
| `:literal` (target) | Structural or sugar **term** + claimed hash; recursive materialize; L2. |

So “simple node” is not a different *kind* of value — it is the **minimum
expansion** of the universal term.

---

## Two layers

### Layer 1 — Value representation (per needed hash)

For each **hash** the peer needs, send **exactly one** item that installs that
hash (and, if expanded, may install covered descendants):

| Form | Wire | Receiver |
|------|------|----------|
| **Simple node** | hash + store content (`[type data]`, children as hashes) | `s-put(hash, content)` — depth-0 structural term |
| **Literal / term** | hash + recursive term (structural or sugar) | `materialize(term)`; require result hash = claimed |

**Decision rule (policy, not law):**

```
for needed hash H with entry N = s-get(H):
  if expand?(H, budget, …):
    emit term item for H (structural preferred; sugar when smaller and L2 holds)
  else
    emit depth-0 node item for H
```

Default **budget = 1024** (tunable; also guides Layer 2). Prefer expansion when
the term fits and coverage saves descendant sends.

**No leaf-packs / fragments.** Large values are handled by walking the tree and
encoding **each needed node** as depth-0 or expanded term. Chunking batches
those items. There is no separate “range of a blob” wire kind.

### Layer 2 — Chunking (pool → ship)

Layer 1 emits a **stream of items**. Layer 2 only batches them.

**Soft budget (MVP):**

- Append items to an open chunk until **chunk size ≥ budget**, then **send** and start a new chunk.
- Chunks may be up to about **2 × budget** (the item that crossed the threshold can nearly fill another budget).
- When the transfer unit is done (e.g. write-back flush finished), **flush** the last partial chunk even if size &lt; budget.
- A single oversized item ships alone (may exceed 2× budget only if one item is that large).

```
needed hashes → Layer 1 encode (term depth policy) → items → Layer 2 pack (≥ budget → HTTP) → flush remainder
```

**Envelope sketch (EDN):**

```clojure
{:dacite.wire/chunk-v1 true
 :budget 1024
 :items [/* {:encoding :node|:literal, :hash …, …} */]}
```

**HTTP sketch:**

| Role | Idea |
|------|------|
| Send | `POST /nodes` — one body = one chunk |
| Fetch pack | `POST /nodes/get` `{:hashes […] :budget 1024}` → one or more chunks |
| Compat | Keep `GET/PUT /node/{hex}` |

## Composition with write-back cache

| Concern | Where |
|---------|--------|
| What is missing / what to flush | Client write-back / local cache |
| Encode each hash as depth-0 node or expanded term | Layer 1 |
| Batch until ≥ budget; flush remainder | Layer 2 |
| Mark covered hashes flushed when a term expands | L4 |

## Parameters

| Name | Default | Role |
|------|---------|------|
| `chunk-size` | 1024 | Soft Layer 2 threshold; cue for Layer 1 expand vs depth-0 |
| `pack-enabled?` | true on remote clients | Force single-node path for debugging |

Sweep 256…4096 with `dacite.bench.todo-bw` (and later large-blob scenarios).

## Implementation order

| Step | Scope |
|------|--------|
| **2a** | Layer 1 depth-0 `:node` only + Layer 2 soft-budget chunks. **Done.** |
| **2b** | Semantic sugar literals for round-tripping host forms; hash check; write-back skip covered. **Done (partial vs law).** |
| **2b′** | **Structural recursive terms** for every core type (`ft/*`, `hamt/*`, collections, scalars). L1–L5. Replace “can’t literal” gaps. |
| **2c** | Large trees / blobs: expansion policy + mix of depth-0 and terms; measure. |
| **2d** | Budget sweep; update `service.md` and this doc with measured defaults. |
| **Sugar** | Compact tags (`:s`, `:v`, …) as pure encoders to structural terms when smaller. |

### 2b notes (shipped semantic subset)

- `encode-item` emits semantic `:literal` only when host body **round-trips** to the claimed hash (dry-run materialize).
- Tree internals (`ft/*`, `hamt/*`) stay depth-0 `:node` until **2b′** (semantic sugar has no host form for them).
- `encode-reachable` walks from the root; semantic literals cover local subgraphs when round-trip holds.
- Receiver `apply-chunk!` materializes via `coerce-and-store!` / `put-scalar!` and asserts hash match when `*verify-literal-hash*` is true.

### 2b′ sketch (structural — next)

- `term-of(store, h, budget)`: build `{:node type data-term}` expanding children while under budget; otherwise `{:ref hex}` or depth-0 item.
- `materialize-term!`: recursive install; no host coerce required for FT/HAMT.
- Semantic sugar becomes an optimization that must prove L2 or desugar to structural first.

## Non-goals

- Reintroducing value-layer collection inlining (durable store stays pure FT/HAMT)
- Leaf-pack / fragment / conj-into-partial-root protocols
- Hard budget (reject items that would exceed budget)
- Binary wire as the first milestone (EDN envelopes OK for MVP)
- Requiring semantic host forms for every type (structural terms are enough for L1)

## Open questions (proposed defaults)

1. Strict hash verification on receive: **yes in tests**, optional flag for demos.  
2. Wire tag style: full type strings (`"ft/deep"`) vs short tags (`:ft/deep`, `:s`) — short tags as sugar only.  
3. Measures inside FT/HAMT data: ship as stored (required for exact entry bytes) vs recompute on materialize (must match hash algebra). **Default: ship as stored in structural terms** so materialize is `s-put` shaped.  
4. Claimed hash on the item vs recomputed-only: keep claimed hash + verify (detects bugs and hostile peers).

## Relation to prior work

- **Phase 1:** removed `:inline` / `:inline-refs` from `dacite.value` so collections stay pure FT/HAMT.  
- **Bandwidth suite / write-back:** orthogonal client policy; chunking sits under remote flush/fetch.  
- **Archived `inline_under`:** early GET expand idea; superseded by universal recursive terms + Layer 2 chunks.  
- **2b semantic literals:** useful bandwidth win for host-shaped data; not a substitute for L1 on all node types.
