# Dacite wire format v1

**Status:** DRAFT — normative target for multi-language interop.  
**Legacy:** `dacite.value.serial` and the book serialization appendix are
**directionally related** but **not** the wire-v1 contract.

**Related:** [leaf-chunking.md](../design/leaf-chunking.md) (node vs literal laws),
[service.md](../design/service.md) (HTTP), [portable-core.md](../design/portable-core.md)
(hash identity).

---

## Goals

1. **One transport shape: the chunk** — no separate top-level “single entry” message.
2. **Two item payloads:** durable **node** vs realized **literal** (pack Layer 1).
3. **Easy to reimplement** — fixed big-endian layout, closed type tables, no EDN/JSON required.
4. **Versioned** once per message.
5. **Fixture-testable** — canonical encoding; golden bytes + expected hashes.

**Non-goals (v1):** compression, encryption, multi-chunk streaming frames, root-CAS binary
(can remain EDN until needed).

---

## Design principles

### Chunk-only transport

Every binary HTTP/IPC body is a **chunk message**, except a reveal stream
(below). A single hash is a **one-item chunk**, not a privileged message type.

This keeps one decoder path and steers clients toward pack-filled GET and multi-item
POST rather than per-node PUT storms.

| Pattern | Status |
|---------|--------|
| Pack-filled GET / multi-item POST | Preferred |
| One-item chunk | Allowed (tools, tiny ops) |
| Separate “entry” message type | **Not in v1** |

### Version once per message

| Location | Version? |
|----------|----------|
| Chunk envelope | **Yes** — `version` u8 |
| Each item | No |
| Nested literal | No |

Unknown `version` → reject the message. v1 uses `version = 1`.

### Hashing is logical, not “hash the wire bytes”

Content hashes are defined by the value model (the SL(2, F_p) fuse + type rules,
docs/design/fuse-sl2.md). Wire framing
and length prefixes are **not** part of the content hash. Receivers may verify
`claimed_hash == hash(materialized)` for literals and optionally for nodes.

### Canonical encoding

For each logical item there is **exactly one** legal byte sequence (fixed field
order, no optional padding, map/set iteration order = ascending element/key
**content hash** as in the value layer). Re-encoding a decoded message must
match fixtures byte-for-byte.

---

## Conventions

- **Byte order:** big-endian for multi-byte integers.
- **Hash:** 32 bytes = four big-endian u64 words `(c0, c1, c2, c3)` (same as
  Dacite hash vector order).
- **Lengths:** u32 counts mean “number of following elements/bytes”; never negative.
- **Strings:** UTF-8; invalid UTF-8 is a decode error. Text is a sequence of
  Unicode code points: see [Text](#text-code-points-and-utf-8) below.
- **Reserved fields:** write as zero; ignore unknown **flags** bits only if the
  spec later marks them ignore-OK (v1: any non-zero reserved bit → error).

---

## Text: code points and UTF-8

Text is a sequence of **Unicode scalar values** (code points U+0000..U+10FFFF
except the surrogates U+D800..U+DFFF), and every count of text is a count of
code points (issue #16):

- a `string` of `n` elements has `n` code points; its `count`, the index of
  `nth`, and the bounds of `slice` are code points. A character outside the
  Basic Multilingual Plane (an emoji, say) is **one** element, a `char` of 4
  bytes, not two. A host whose strings are UTF-16 (the JVM, JavaScript) counts
  code points, never UTF-16 code units;
- a `char` is one code point: `dlen` is the 1..4 bytes of its UTF-8, and a
  `char` that decodes to zero or to more than one code point is an error;
- the `n` of a `run` of `char` is the number of code points that its UTF-8
  bytes decode to (a surrogate pair in a host string counts 1: `a😀é𝄞` is `n = 4`
  and `blen = 11`, though a UTF-16 host string holds 6 code units);
- a page of a string never ends inside a character; a page holds whole code
  points, so every page is valid UTF-8 on its own;
- the hash of a string is the fuse of the hashes of its characters, and the
  hash of a character is the hash of the UTF-8 of its one code point. It does not
  depend on paging (`fixtures/fuse-sl2/vectors.edn` `:strings`).

**A lone surrogate is refused.** A string with a UTF-16 surrogate that is not
half of a pair (a high surrogate U+D800..U+DBFF not followed by a low one,
U+DC00..U+DFFF not preceded by a high one) is not a sequence of scalar values
and has no UTF-8 form. An implementation does not encode it and does not repair
it (not as `?`, which the JVM's encoder wrote, nor as U+FFFD, which a JavaScript
`TextEncoder` writes: two hosts, two values for one string). Building a `string`
or a `char` from such text, or encoding it, throws `ex-info` with
`:reason :lone-surrogate` (and `:index`, `:code-unit`); on the wire the same
bytes cannot occur, because UTF-8 has no form for them. A host that holds text
of unknown quality repairs it first (`String.prototype.toWellFormed()` in
JavaScript), which is the caller's choice and not Dacite's.

**Invalid UTF-8 is refused**, not repaired: a bad lead or continuation byte, an
overlong form, an encoded surrogate (CESU-8), a value above U+10FFFF, or a
truncated sequence in a `char`, a `string` or the text of a `run` is a decode
error (`ex-info`, `:reason :invalid-utf8`). A leading U+FEFF is text, kept (a
JavaScript `TextDecoder` drops it unless told not to: nbb and the browser
bundles pass `ignoreBOM`).

`fixtures/fuse-sl2/vectors.edn` has the known answers (`:strings`, `:utf8`,
`:invalid-utf8`, `:rejected-strings`), checked on the JVM, on nbb and by
`fixtures/fuse-sl2/reference.py`.

---

## Message: chunk (only msg_type in v1)

```text
ChunkMessage =
    magic      4 bytes   = 0x44 0x41 0x43 0x31   ; ASCII "DAC1"
    version    u8        = 1
    msg_type   u8        = 0x01                  ; chunk
    flags      u8        = 0                     ; reserved
    n_items    u32
    budget     u32       ; soft pack budget hint; 0 = unspecified
    items      Item × n_items
```

**Total size** is fully determined by walking items (self-delimiting).  
Implementations may reject messages larger than an application limit (DoS).

**HTTP Content-Type (recommended):**

```text
application/vnd.dacite.chunk.v1
```

### Exception: a reveal stream

`POST /reveal` responds with `application/vnd.dacite.item-stream.v1`. The body
is a concatenation of `Item`s and nothing else: no chunk envelope, no count,
and no budget. Each item is one page, a node (`enc = 0x00`). An empty body is
a finished reveal with nothing left to send. Items are self-delimiting, so the
decoder stops on an item boundary. This is not a new message type and not a
multi-chunk streaming frame. Pack GET and `POST /nodes` stay chunks. The
service account is [service.md](../design/service.md).

---

## Item

```text
Item =
    enc        u8        ; 0x00 = node, 0x01 = literal; other = error
    hash       32 bytes  ; claimed content hash of this item
    plen       u32       ; payload length in bytes
    payload    plen bytes
```

| `enc` | Payload | Receiver action |
|-------|---------|-----------------|
| `0x00` | **Node encoding** | Decode store entry; `s-put(hash, entry)` |
| `0x01` | **Literal encoding** | Materialize value; require content hash = `hash`; install resulting nodes |

`plen` must equal the exact byte length of a valid payload; trailing garbage
inside `payload` is an error.

---

## Node encoding (`enc = 0x00`)

Self-describing durable store entry. Layout evolved from legacy `serial.clj`.

```text
Node =
    kind       u8
    … kind-specific …
```

### Kind table

| `kind` | Name | Description |
|--------|------|-------------|
| `0x00` | scalar | Typed scalar (type id + data) |
| `0x01` | ft | Finger-tree spine cell |
| `0x02` | hamt | HAMT spine cell |
| `0x03` | collection | Public collection header (vector/string/blob/map/set) |
| other | — | Error |

### Measure (144 bytes)

Used by `ft` and `hamt` kinds:

```text
Measure =
    count           u64
    size_bytes      u64
    elements_fuse   State128
```

`State128` is a fuse state: the four cells a, b, c, d of a 2x2 matrix over F_p
(p = 75509794529310452520362503714625764980405239500720831432010267419866053829067,
a 256-bit prime), each 32 bytes big-endian, 128 bytes in all. It is **not** a `Hash32`: a ref is the 32-byte digest of a state, a measure
keeps the whole state, because measures are summed by fusing. A receiver checks
that each cell is below p and that `a*d - b*c = 1 (mod p)`; otherwise the item is
refused: a decoder throws an ex-info with `:reason :bad-state` (message "bad fuse
state"; `hash/bad-state!`), and the peer item reader reports it as `bad-entry`
with `:detail :bad-state`. A writer handed an invalid state throws
`:reason :invalid-state` (a program error, not bad input). Under the old fuse
(hash version 1) this field was a 32-byte hash and the measure 48 bytes. See
docs/design/fuse-sl2.md.

### Scalar (`kind = 0x00`)

```text
Scalar =
    type_id    u8      ; see scalar type_id table
    dlen       u8      ; data length 0..255
    data       dlen bytes
```

**Scalar `type_id`:**

| id | Type | `data` layout |
|----|------|----------------|
| `0x00` | `null` | empty (`dlen = 0`) |
| `0x01` | `bool` | 1 byte: `0x00` false, `0x01` true |
| `0x02` | `char` | UTF-8 of one code point, `dlen` 1..4 |
| `0x03` | `i64` | 8 bytes BE two’s complement |
| `0x04` | `f64` | 8 bytes IEEE-754 BE |
| `0x05` | `i8` | 1 byte BE two’s complement |
| `0x06` | `i16` | 2 bytes BE two’s complement |
| `0x07` | `i32` | 4 bytes BE two’s complement |
| `0x08` | `u8` | 1 byte unsigned |
| `0x09` | `u16` | 2 bytes BE unsigned |
| `0x0A` | `u32` | 4 bytes BE unsigned |
| `0x0B` | `u64` | 8 bytes BE unsigned |
| `0x0C` | `f32` | 4 bytes IEEE-754 BE |
| `0x0D` | `u256` | 32 bytes BE (raw limb bytes; same as value layer) |
| `0x0E` | `negative` | empty (`dlen = 0`) — set-theory sentinel |
| `0x0F` | reserved | error if used in v1 |
| other | error | |

Canonical data bytes for hashing remain as defined by the value layer (same as
today’s scalar encodings). Wire includes `type_id` so ports need no out-of-band type.

### Finger tree (`kind = 0x01`)

```text
Ft =
    subtype    u8
    measure    Measure
    n          u8
    children   Hash32 × n
```

| `subtype` | Type | `n` | Meaning of children |
|-----------|------|-----|---------------------|
| `0x00` | `ft/empty` | 0 | — |
| `0x01` | **reserved** (legacy ft/single) | — | **Must reject** |
| `0x02` | `ft/digit` | 1..32 hashes, or **0** + Lit body | child hashes, or packed page body |
| `0x03` | `ft/node` | 2..32 hashes, or **0** + Lit body | child hashes, or packed page body |
| `0x04` | `ft/deep` | 3 | left, spine, right |
| other | error | | |

When `n = 0` on `ft/digit` or `ft/node` and bytes remain after the
measure, the remainder is a sequence of `Lit` items (the page body):
runs, repeats, nested value lits, and `ref` (type id `0x52`). Legacy
pointer pages still use `n` child hashes.

### HAMT (`kind = 0x02`)

```text
HamtEmpty =
    subtype    u8 = 0x00
    measure    Measure

HamtEntry =
    subtype    u8 = 0x01
    measure    Measure
    key_hash   Hash32
    key_ref    Hash32
    val_ref    Hash32

HamtBitmap =
    subtype    u8 = 0x02
    measure    Measure
    bitmap     u32
    split      u8                  ; index of the hash chunk the node branches on, 0..51
    n          u8                  ; = popcount(bitmap), and n >= 2
    children   Hash32 × n
```

`split` is new (issue #12; the draft layout was `bitmap, n, children`, one byte
shorter). A `HamtBitmap` has **two or more** children: a node with one child is
never written. Its children hold the keys that share chunk `split` of the node's
bits, and each child's own `split` is greater. The node hash is
`digest(fuse(fuse(type_state, elements_fuse), fuse_bytes(bitmap_be8 ++ [split])))`,
with `bitmap_be8` the bitmap as 8 big-endian bytes. A receiver checks the hash, a
`split` of 0..51 and `n >= 2` with `n = popcount(bitmap)` from the node's own
bytes. See peer-v1 §3.3 for why the shape is canonical.

### Collection header (`kind = 0x03`)

Public value types that wrap a spine root:

```text
Collection =
    coll_id    u8
    root       Hash32
    count      u64
    size_bytes u64
```

| `coll_id` | Type |
|-----------|------|
| `0x00` | `vector` |
| `0x01` | `string` |
| `0x02` | `blob` |
| `0x03` | `map` |
| `0x04` | `set` |
| other | error |

---

## Literal encoding (`enc = 0x01`)

Recursive **realized** value (leaf-chunking L1–L5). No FT/HAMT spine on the wire.

```text
Lit =
    type_id    u8
    … type-specific payload (not length-prefixed as a whole;
      structure is self-delimiting via type_id) …
```

Unlike node items, a literal payload is **not** wrapped in an extra `plen`
beyond the Item’s `plen` (the Item already bounds the payload). Inside `Lit`,
variable sections use their own length fields.

### Literal `type_id` table

| id | Type | Payload |
|----|------|---------|
| `0x00` | `null` | (empty) |
| `0x01` | `bool` | `u8` 0/1 |
| `0x02` | `char` | `u8 dlen` ++ UTF-8 (1..4) |
| `0x03` | `i64` | `i64` BE |
| `0x04` | `f64` | `f64` BE |
| `0x05` | `i8` | 1 byte BE two’s complement |
| `0x06` | `i16` | 2 bytes BE two’s complement |
| `0x07` | `i32` | 4 bytes BE two’s complement |
| `0x08` | `u8` | 1 byte unsigned |
| `0x09` | `u16` | 2 bytes BE unsigned |
| `0x0A` | `u32` | 4 bytes BE unsigned |
| `0x0B` | `u64` | 8 bytes BE unsigned |
| `0x0C` | `f32` | 4 bytes IEEE-754 BE |
| `0x0D` | `u256` | 32 bytes (no length prefix) |
| `0x0E` | `negative` | (empty) |
| `0x10` | `string` | `u32 n` ++ UTF-8 bytes (`n` is a **byte** count) |
| `0x11` | `blob` | `u32 n` ++ bytes |
| `0x20` | `vector` | `u32 n` ++ `Lit` × n |
| `0x21` | `map` | `u32 n` ++ (`Lit` key ++ `Lit` val) × n |
| `0x22` | `set` | `u32 n` ++ `Lit` × n |
| `0x30` | `ft/empty` | `u32 n` ++ `Lit` × n (usually n=0) |
| `0x31` | `ft/digit` | ordered leaf lits |
| `0x32` | `ft/node` | ordered leaf lits |
| `0x33` | `ft/deep` | ordered leaf lits |
| `0x40` | `hamt/empty` | ordered entry/leaf lits (usually n=0) |
| `0x41` | `hamt/entry` | `Lit` key ++ `Lit` val |
| `0x42` | `hamt/bitmap` | `u32 n` ++ (`Lit` key ++ `Lit` val) × n (same pair layout as `map`) |
| `0x50` | `run` | `inner_type_id u8` ++ `n u32` ++ packed inner payloads (no repeated type tags). **Nested only** — not a store type. |
| `0x51` | `repeat` | `inner_type_id u8` ++ `n u32` ++ one packed inner payload (`n ≥ 2` copies). **Nested only**. |
| `0x52` | `ref` | Hash32 of a child that is not inlined. **Nested only**. |
| other | error | |

`run` / `repeat` pack contiguous same-type leaves inside sequence bodies (`vector`, `set`, `ft/*`, `hamt/empty`). The parent item’s type stays the store type (`ft/node`, `vector`, …). Decode expands to `n` nested lits before materialize.

Packed payload of a `run` (after `inner` + `n`):

| Inner | Packed |
|-------|--------|
| `char` | `u32 blen` ++ UTF-8 bytes that decode to **n** code points |
| `u8` | `n` raw bytes |
| other | `n ×` the inner Lit **body** (no leading `type_id`) |

`repeat` packed payload is one inner body (same as a single `run` element).

**Order:**

- **vector:** sequence order (left to right).  
- **map:** pairs sorted by **ascending content hash of key**.  
- **set:** elements sorted by **ascending content hash of element**.  

Nested values are full `Lit` values (not bare hashes), unless a future version
adds an explicit ref form (not in v1). Sequence bodies may compress contiguous
same-type children as `run` / `repeat` (still complete at the parent hash).

---

## HTTP mapping

| Endpoint | Body | Notes |
|----------|------|--------|
| `GET /node/{hex}` (default) | ChunkMessage | Pack-filled neighborhood under `hex` |
| `POST /nodes` | ChunkMessage | Apply items (write) |
| `PUT /node/{hex}` | ChunkMessage | **One item**, `enc=node`, item hash **must** equal path hash; prefer POST for bulk |
| Debug / EDN | EDN | `application/edn`; not required for ports |

**Client guidance (normative intent):**

- Interactive and bulk clients **should** use pack-filled GET and multi-item POST.  
- Single-item PUT is for tools and tiny ops only — not the performance path.  
- Soft `budget` in the chunk should match the pack policy that produced it (e.g. 1024).

Novelty responses (`:created` / `:exists`) may remain EDN in early binary rollout;
a binary novelty message can be a later `msg_type`.

---

## Anti-patterns

| Avoid | Prefer |
|-------|--------|
| One HTTP request per hash in a loop | One chunk with many items / pack-fill |
| Inventing a second “entry” binary schema | One-item chunk |
| Hashing wire framing | Logical content hash only |
| Non-canonical map/set order | Hash-sorted pairs/elements |
| Relying on EDN tags for interop | This binary format |

---

## Fixtures

Repository path: **`fixtures/wire-v1/`**.

Each case directory contains:

| File | Purpose |
|------|---------|
| `description.json` | Human-readable intent (types, values) |
| `message.hex` | Full ChunkMessage as lowercase hex (no whitespace) |
| `hash.hex` | Primary claimed content hash (64 hex chars), if single-item |
| `hashes.json` | Optional list of hashes after apply |

**Port checklist:**

1. Decode `message.hex` → apply to empty store.  
2. Assert primary hash present (and equals `hash.hex` when provided).  
3. Re-encode the same logical chunk → bytes equal `message.hex`.  

See `fixtures/wire-v1/README.md`.

---

## Relationship to legacy serial

| Legacy `serial.clj` / book appendix | wire-v1 |
|----------------------------------|---------|
| No outer magic/version | Chunk envelope with version |
| Scalar without type on wire | Scalar `type_id` required |
| ft/single subtype 1 | Reserved / reject |
| Collection 50-byte header | Same idea + `set` coll_id |
| Not pack-aware | Item = node \| literal inside chunk |
| Normative for ports | **This document** |

Implementations may keep a legacy decoder for old test data; **new** interop
and fixtures use wire-v1 only.

---

## Persisted stores: the store format tag

A persisted store (file, LMDB, IndexedDB) carries one integer, the **store
format** (`dacite.store/store-format`, now **2**). It is written when the store is
created and checked every time it opens. It is not the wire version: the wire and
`hello` versions are unchanged by it (draft; no Dacite values in the wild).

| Backend | Where the tag is | Created | Checked |
|---|---|---|---|
| File (JVM `store.file`, nbb `store.nbb`) | the file `{base}/FORMAT`, the integer in decimal and a newline | the directory is new, or has no `.edn` node | on `file-store` |
| LMDB (JVM `store.jvm`, nbb `store.nbb.lmdb`) | meta DB key `format` (UTF-8), the integer in decimal as UTF-8 | the content DB is empty and the key is absent | on `lmdb-store`; the env is closed before an error |
| IndexedDB (`store.idb`) | `meta` object store, key `format`, the number | in the schema upgrade that creates the database (or on open, for an untagged database with no nodes) | on the first operation of an existing database |

A store with nodes and no tag (written before tags), or with another number, is
refused with `ex-info` `store format mismatch ...` and `ex-data`
`{:reason :store-format-mismatch :expected 2 :found nil|n :where ...}`, before
any node is read, so it is never a node-hash failure. `s-reset` keeps the tag.
Bump the number when what a store holds changes shape: format 1 is the
`hamt/bitmap` node with a `split` (issue #12); format 2 is the SL(2, F_p) fuse
(issue #14), which changes every value hash and the measure.

---

## Version evolution

- **v1:** as specified here.  
- **v2+:** new `version` value; peers that only know v1 reject.  
- Additive compatible changes within a major version are discouraged while the
  format is young — prefer a major bump and dual-read if needed.

---

## Implementation status

| Piece | Status |
|-------|--------|
| This spec | Normative for public value types (node + literal) |
| Fixtures | `fixtures/wire-v1/` — node + literal goldens per public type |
| Clojure codec | **`dacite.wire.binary`** — portable `.cljc` encode/decode; pack EDN bridge |
| HTTP | Service dual Content-Type; JVM/browser remotes default wire-v1 packs |

## Clojure usage

```clojure
(require '[dacite.wire.binary :as bin])

(bin/encode-chunk
  {:budget 1024
   :items [{:enc :literal
            :hash h
            :literal {:type "i64" :body 42}}]})

(bin/decode-chunk-hex (slurp "fixtures/wire-v1/cases/.../message.hex"))
(bin/apply-chunk-message! store (bin/decode-chunk bytes))
```
