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
| Fetch pack | `POST /nodes/get` `{:roots […] :have […] :budget 1024}` → `{:chunks […] …}` |
| Compat | Keep `GET/PUT /node/{hex}` |

**Read path (shipped):** server runs the same `encode-reachable` walk; client
`fetch-reachable!` applies chunks into the local cache, then realizes from
local-first stores. Reload uses pack-fetch instead of per-node GET.

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
| **2b′** | Systematic `literal-of` / `materialize-literal!`, recursive nested `{:type :body}` forms, tests per value type + nested/empty/todo. **Done.** |
| **2c** | Large trees / blobs: cheap size cue gate; refuse root literal and walk `:node` + child literals; `encode-summary`. **Done.** |
| **2c′** | **Intermediate literals** (`ft/*`, `hamt/*`) as ordered leaf/entry payloads; rebuild + dry-run hash gate. **Done.** |
| **2d** | Budget sweep; update `service.md` with measured defaults. |

### 2b notes (shipped)

- `encode-item` uses realized host bodies and dry-run hash check.
- Nested values that already round-trip as host EDN work; recursive typed tags
  (`[:v [[:s "a"] …]]`) are the clearer long-term wire form (2b′).
- Spine types stay `:node` only when the walk reaches them; value literals skip them.
- `*verify-literal-hash*` enforces L2 on receive when enabled.

### 2b′ notes (shipped)

- `literal-of` returns `{:type t :body b}` for every value type; collection
  bodies are recursive typed forms (not flat host EDN alone).
- `materialize-literal!` always uses constructors; accepts recursive forms and
  2b flat host bodies for wire compat.
- `encode-item` / `encode-reachable` use `literal-of` + dry-run hash check + budget.
- Spine types (`ft/*`, `hamt/*`) gain leaf-payload forms in 2c′ (dry-run gated).

### 2c notes (shipped)

**Refuse-literal policy** (`encode-item`):

1. Spine / non-value → `:node`
2. **`size-cue` (`:size-bytes`) > budget** → `:node` without building `literal-of` or dry-run
3. Build realized form; if wire size > **2 × budget** → `:node` (typed overhead can exceed cue)
4. Dry-run hash fail → `:node`
5. Else `:literal` (covers whole subgraph)

**Mixed walk:** large parent as `:node` → descend `child-hashes`; small children still `:literal`.

**Helpers:** `clearly-oversized?`, `encode-summary` / `summarize-items` for benches.

**Measured examples (default or tight budget):**

| Case | Behavior |
|------|----------|
| 3000-char string @ 1024 | root `:node` + FT nodes + char literal(s) |
| 40 short strings @ 200 | root `:node` + 40 string literals + spine nodes |
| same vector @ 500 | size cue fits but recursive wire > 2×budget → mixed walk |
| todo seed @ 1024 | still one root `:literal` |

### 2c′ notes (shipped)

Intermediate spine nodes may ship as literals whose **body is ordered leaf
content** (value literals), not the raw child-hash spine:

| Type | Body | Rebuild |
|------|------|---------|
| `ft/empty` | `[]` | `ft-empty` |
| `ft/single` | `[leaf-lit]` | single of leaf |
| `ft/digit` | leaf lits | digit of singles |
| `ft/deep` (and try `ft/node`) | leaf lits | `ft-from-value-hashes` (conj-right) |
| `hamt/empty` | `[]` | empty |
| `hamt/entry` | `[k-lit v-lit]` | entry node |
| `hamt/bitmap` | `[[k v]…]` | `hamt-from-entries` (only when routing matches) |

**Gate:** same as values — budget + dry-run `materialize = claimed hash`. Bitmaps
that do not round-trip stay `:node`.

**Win:** large parent as `:node` → walk hits an `ft/digit` / `ft/deep` that fits
→ one intermediate literal instead of many child cells.

## Non-goals (MVP)

- Reintroducing value-layer collection inlining (durable store stays pure FT/HAMT)
- Leaf-pack / fragment / conj-into-partial-root protocols
- Hard budget (reject items that would exceed budget)
- Binary wire as the first milestone (EDN envelopes OK for MVP)
- Shipping intermediate FT/HAMT cells inside a value’s literal body

## Future: binary wire for literals

EDN is fine for correctness and demos; a later **binary pack codec** can cut
literal bytes without changing the value law (L1–L5) or store model.

| Idea | Notes |
|------|--------|
| **Protobuf (or similar)** | Schema for chunk envelope + recursive typed literals (type tag + payload; nested messages for coll elements). |
| **What shrinks** | Type strings → small enums/varints; hex hashes → 32 raw bytes; strings/blobs as length-delimited bytes; repeated elements without EDN map overhead. |
| **What stays the same** | Realized completeness, type fidelity, hash check on materialize, soft-budget chunking policy. |
| **Interop** | Keep EDN path for debug/tools; negotiate codec (e.g. `Content-Type`) on `POST /nodes`. |

Not scheduled; pursue after 2c/2d once literal shapes and budgets are stable.

## Open questions (proposed defaults)

1. Strict hash verification on receive: **yes in tests**, optional flag for demos.
2. Wire tags: full type strings (`"vector"`) vs short (`:v`, `:s`) — either, as long as type fidelity (L3) holds.
3. Truncation inside a large collection (some elements as `{:ref hex}`): **defer**; MVP is full realized or fall back to `:node` walk.
4. Claimed hash on the item: **keep** + verify (bugs and hostile peers).
5. Intermediate literals (2c′): FT digit/single/deep and many hamt bitmaps assured via rebuild+dry-run; some bitmaps stay `:node` when routing differs.

## Relation to prior work

- **Phase 1:** removed value-layer `:inline` so collections stay pure FT/HAMT.
- **Write-back / stats:** client policy; packing sits under remote flush/fetch.
- **Archived `inline_under`:** superseded by realized literals + Layer 2 chunks.
- **Earlier “structural recursive terms” sketch** (encode `ft/deep` as nested
  terms): **superseded**. Literals are realized values; spine is reconstructed,
  not mirrored on the wire.
