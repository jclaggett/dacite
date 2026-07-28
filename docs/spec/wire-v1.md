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

Every binary HTTP/IPC body is a **chunk message**. A single hash is a **one-item
chunk**, not a privileged message type.

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

Content hashes are defined by the value model (fuse + type rules). Wire framing
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
- **Strings:** UTF-8; invalid UTF-8 is a decode error.
- **Reserved fields:** write as zero; ignore unknown **flags** bits only if the
  spec later marks them ignore-OK (v1: any non-zero reserved bit → error).

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

### Measure (48 bytes)

Used by `ft` and `hamt` kinds:

```text
Measure =
    count           u64
    size_bytes      u64
    elements_fuse   Hash32
```

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
| `0x05`–`0x0F` | reserved integers/floats | (future fixed sizes) |
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
| `0x02` | `ft/digit` | 1..32 | child hashes (leaves or nodes) |
| `0x03` | `ft/node` | 2..32 | child hashes |
| `0x04` | `ft/deep` | 3 | left, spine, right |
| other | error | | |

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
    n          u8
    children   Hash32 × n
```

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
| `0x10` | `string` | `u32 n` ++ UTF-8 bytes |
| `0x11` | `blob` | `u32 n` ++ bytes |
| `0x20` | `vector` | `u32 n` ++ `Lit` × n |
| `0x21` | `map` | `u32 n` ++ (`Lit` key ++ `Lit` val) × n |
| `0x22` | `set` | `u32 n` ++ `Lit` × n |
| other | error | |

**Order:**

- **vector:** sequence order (left to right).  
- **map:** pairs sorted by **ascending content hash of key**.  
- **set:** elements sorted by **ascending content hash of element**.  

Nested values are full `Lit` values (not bare hashes), unless a future version
adds an explicit ref form (not in v1).

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

## Version evolution

- **v1:** as specified here.  
- **v2+:** new `version` value; peers that only know v1 reject.  
- Additive compatible changes within a major version are discouraged while the
  format is young — prefer a major bump and dual-read if needed.

---

## Implementation status

| Piece | Status |
|-------|--------|
| This spec | Draft |
| Fixtures | `fixtures/wire-v1/` (growing golden cases) |
| Clojure codec | **`dacite.wire.binary`** — encode/decode chunk, node, literal; fixture tests |
| HTTP default binary | Planned after more fixtures + dual Content-Type |

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
