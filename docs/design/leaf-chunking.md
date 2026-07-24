# Leaf-chunking (transport packing) design

**Status:** DRAFT — 2a/2b shipped; **realized-value literal law** clarified below
(2b is a partial implementation of that law).

**Related:** `docs/design/service.md` (HTTP store protocol), phase 1 commit removing
`:inline` from `dacite.value.collections`.

## Goal

Cut HTTP **request count** and redundant bytes for remote stores **without**
changing the durable Dacite value model. Collections remain finger trees / HAMTs
in the store. Packing and literal encodings live only on the **wire**.

---

## Law: every value node has a realized literal

**Claim:** Any first-class Dacite **value** node can be represented on the wire as a
**literal**: the node’s type plus the **complete realized content** of that value.
The receiver rebuilds the value through the normal constructor path so that
**`hash(materialized) = claimed hash`**. Intermediate store nodes (finger-tree /
HAMT spine) are **not** part of the literal; they are reconstructed as needed.

This is a property of **Dacite values** (entries that are user-facing values:
scalars, string, blob, vector, map, set — anything with a meaningful
`realize`), not of host language convenience alone and not of internal tree
layout.

### What a literal is

| Aspect | Requirement |
|--------|-------------|
| **Completeness** | The body is the full realized content under the node (recursively for nested values), not a partial range or a spine fragment. |
| **Type** | The value’s type is carried (e.g. `vector` vs `string` vs `i64`) so materialize builds the right kind of value. |
| **No intermediate nodes** | FT/HAMT layout (`ft/*`, `hamt/*`) is omitted. Materialize uses normal builders (`string-with-store`, `vector-with-store`, …); the receiver’s spine may differ in shape. |
| **Hash fidelity** | After materialize, the content hash equals the claimed hash. Content-addressing + deterministic constructors make this hold for all core value types. |

Illustrative wire shapes (tags optional; full type names are fine):

```clojure
;; Scalars
[:i64 42]
[:bool true]
[:char \x]

;; String / blob — full realized payload
[:s "hello"]
[:blob [104 105]]   ; or host bytes where the wire allows

;; Collections — recursive realized elements (not FT/HAMT nodes)
[:v [1 2 3]]
[:v [[:s "hello"] [:s "world"]]]
[:m {[:s "a"] 1, [:s "b"] 2}]
[:set #{1 2 3}]
```

**Recursive:** each nested value is itself a realized literal (or, under a future
budget policy, a hash ref — see truncation). The outer literal still means
“this whole value,” not “this one store cell’s raw `[type data]`.”

### What a literal is not

- Not a dump of the raw store entry with child **hashes** only (that is the
  depth-0 **`:node`** encoding).
- Not a recursive expansion of `ft/deep` / `hamt/bitmap` cells as if they were
  the value (those are implementation spine; reconstruct on materialize).
- Not a leaf-pack / byte-range of a large blob (out of scope; large values use
  `:node` walk or stay over budget as a single item).

### Laws

| # | Law | Meaning |
|---|-----|---------|
| **L1 Universality** | Every first-class value type supports `value → literal` and `literal → value` with hash fidelity. | No core value type is “literal-incapable.” |
| **L2 Realized completeness** | A literal for hash `H` encodes the complete realized content of the value at `H`. | Nested values appear realized (recursively), not as bare child hashes, unless an explicit truncation/ref policy applies. |
| **L3 Type fidelity** | The literal carries enough type information to materialize the same value kind. | e.g. string vs vector of chars are distinct. |
| **L4 Spine freedom** | Materialize need not reproduce the sender’s intermediate FT/HAMT node hashes. | Only the **value** hash `H` must match; spine is reconstructed “as possible.” |
| **L5 Coverage** | Shipping a literal for `H` covers `H` and all store nodes that materialize will create under `H`. | Pack/write-back need not also send those descendants as separate items. |

### Intermediate store nodes (`ft/*`, `hamt/*`)

These entries exist in the durable store and may still move as depth-0 **`:node`**
items when the pack walk descends (e.g. parent not literalized).

**MVP (value literals first):**

- When a parent **value** is sent as a literal, intermediates under it are
  **skipped** (L5) and rebuilt on the receiver.
- When a parent is sent as `:node`, intermediates appear as ordinary node puts
  via `child-hashes` walk.
- L1 for **value** types is the hard requirement for 2b / 2b′.

**Later: intermediate literals (optional, deferred):**

If an intermediate node’s content hash is determined by a **realized measure**
(e.g. FT/HAMT `elements_fuse` over leaves) such that materializing “the
complete realized content under this cell” rebuilds **some** valid spine with
**the same hash**, then that intermediate may also ship as a literal.

| Why | Effect |
|-----|--------|
| Bottom out early | Pack walk can emit one compact literal for an `ft/deep` (or similar) as soon as its realized payload fits, instead of walking every child cell as `:node`. |
| Same laws | Completeness + type + hash fidelity; spine under the intermediate need not match sender layout (L4 applied at that node). |
| Gate | Only where reconstruction is **assured** (hash algebra + constructors). If not assured, keep depth-0 `:node`. |

This is a **packing optimization**, not required for value-level L1. Schedule after
value literals are solid (e.g. post‑2b′ / with 2c large-tree work). Do not block
MVP on intermediate literals.

### Materialize (receiver)

```
materialize(literal):
  case type:
    scalar  → put-scalar!(type, data)
    string  → string-with-store(realized-string)
    blob    → blob-with-store(realized-bytes)
    vector  → vector-with-store(map materialize elements)
    map     → hash-map-with-store(…)
    set     → dacite-set-with-store(…)
  assert content_hash == claimed_hash   ; when strict
  return hash
```

Constructors already allocate whatever FT/HAMT nodes they need. That is the
only reconstruction required.

### Relation to Layer 1 encodings (wire items)

| Item encoding | Meaning |
|---------------|---------|
| **`:node`** | Raw store entry `[type data]` with child hashes; `s-put` as today. Used for spine cells and for values not expanded to literals. |
| **`:literal`** | Realized value form + claimed hash; materialize via constructors (L1–L5). |

Packing **policy** chooses which needed hashes become literals vs nodes (budget,
size, already-flushed set). The **law** is that every value *can* be a literal.

---

## Two layers

### Layer 1 — Per needed hash

For each hash the peer needs, send one item that installs it (a literal may also
install covered descendants via materialize):

```
for needed hash H with entry N = s-get(H):
  if value-node?(N) and fits-literal?(realized(N), budget):
    emit { :encoding :literal, :hash H, :type …, :body realized… }
  else
    emit { :encoding :node, :hash H, :body N }
```

Default **budget = 1024** (tunable; also guides Layer 2). Prefer literal when the
realized form fits and coverage saves descendant sends.

**No leaf-packs / fragments.** Large values: walk with `:node` items, or one
oversized literal if policy allows. No separate “range of a blob” wire kind in MVP.

### Layer 2 — Chunking (pool → ship)

Layer 1 emits a stream of items. Layer 2 only batches them.

**Soft budget (MVP):**

- Append until estimated chunk size ≥ budget, then send and start a new chunk.
- Chunks may be up to about **2 × budget**.
- Flush the last partial chunk when the transfer unit finishes.
- A single oversized item ships alone.

```
needed hashes → Layer 1 (literal | node) → items → Layer 2 pack → HTTP
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
| Fetch pack | `POST /nodes/get` `{:hashes […] :budget 1024}` → chunk(s) |
| Compat | Keep `GET/PUT /node/{hex}` |

## Composition with write-back cache

| Concern | Where |
|---------|--------|
| What is missing / what to flush | Client write-back / local cache |
| Encode each hash as `:literal` or `:node` | Layer 1 |
| Batch until ≥ budget; flush remainder | Layer 2 |
| Mark covered hashes flushed when a literal materializes a subgraph | L5 |

## Parameters

| Name | Default | Role |
|------|---------|------|
| `chunk-size` | 1024 | Soft Layer 2 threshold; cue for literal vs node |
| `pack-enabled?` | true on remote clients | Force single-node path for debugging |

Sweep 256…4096 with `dacite.bench.todo-bw` (and large-blob scenarios).

## Implementation order

| Step | Scope |
|------|--------|
| **2a** | `:node` only + soft-budget chunks. **Done.** |
| **2b** | Realized literals for scalars / string / blob / map / set / vector (host-roundtrip + hash check); write-back coverage. **Done (core value types).** |
| **2b′** | Close gaps vs L1–L5 for **value** types: systematic `to-literal` / `from-literal`, recursive nested literals, property tests per value type. |
| **2c** | Large trees / blobs: when to refuse literal and walk `:node` instead; measure. |
| **2c′** *(defer)* | **Intermediate literals** (`ft/*`, `hamt/*`) where realized content rebuilds to the same node hash — bottom out pack walks earlier. |
| **2d** | Budget sweep; update `service.md` with measured defaults. |

### 2b notes (shipped)

- `encode-item` uses realized host bodies and dry-run hash check.
- Nested values that already round-trip as host EDN work; recursive typed tags
  (`[:v [[:s "a"] …]]`) are the clearer long-term wire form (2b′).
- Spine types stay `:node` only when the walk reaches them; value literals skip them.
- `*verify-literal-hash*` enforces L2 on receive when enabled.

### 2b′ sketch (finish the law)

- Multimethod or registry: `literal-of(store, h) → {:type :body}` for every
  value type; body elements are literals (recursive) for nested values.
- `materialize-literal!` dispatches on type; always constructor path; assert hash.
- Property tests: for random values of each type, `materialize(literal-of(v)) = hash(v)`.
- Pack walk: if literal fits budget, emit it and mark covered; else `:node` + children.

## Non-goals

- Reintroducing value-layer collection inlining (durable store stays pure FT/HAMT)
- Leaf-pack / fragment / conj-into-partial-root protocols
- Hard budget (reject items that would exceed budget)
- Binary wire as the first milestone (EDN envelopes OK for MVP)
- Shipping intermediate FT/HAMT cells inside a value’s literal body

## Open questions (proposed defaults)

1. Strict hash verification on receive: **yes in tests**, optional flag for demos.
2. Wire tags: full type strings (`"vector"`) vs short (`:v`, `:s`) — either, as long as type fidelity (L3) holds.
3. Truncation inside a large collection (some elements as `{:ref hex}`): **defer**; MVP is full realized or fall back to `:node` walk.
4. Claimed hash on the item: **keep** + verify (bugs and hostile peers).
5. Intermediate literals: which spine types have assured reconstruction today (likely those hashed only on `elements_fuse` + type)? Document per type before implementing 2c′.

## Relation to prior work

- **Phase 1:** removed value-layer `:inline` so collections stay pure FT/HAMT.
- **Write-back / stats:** client policy; packing sits under remote flush/fetch.
- **Archived `inline_under`:** superseded by realized literals + Layer 2 chunks.
- **Earlier “structural recursive terms” sketch** (encode `ft/deep` as nested
  terms): **superseded**. Literals are realized values; spine is reconstructed,
  not mirrored on the wire.
