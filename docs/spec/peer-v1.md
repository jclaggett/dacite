# Dacite peer protocol v1

**Status:** DRAFT (M1.1: messages and framing; M1.2: policy rules and the conformance list, sections 12 and 13). Not yet signed off. In M1.2, every numbered rule is Decided (Jonathan, Nov 4) or Existing; a few ideas are Deferred (12.7).
**Related:** [wire-v1.md](wire-v1.md) (the byte format this protocol reuses),
[peering design note](../design/peering-design-note.md),
[streaming design note](../design/streaming-design-note.md),
[the plan](../plan/peering.md) (M1.1, M1.2).
**Code:** `dacite.peer.frame` (envelope, packing), `dacite.peer.msg` (messages),
both portable `.cljc`; `dacite.peer.items` (item streams: the literal rule and
verification, JVM `.clj` for now). **Fixtures:** [`fixtures/peer-v1/`](../../fixtures/peer-v1/).

**M1.2 (sections 12 and 13):** the numbered rules (C01 to C45, C04 withdrawn: 41 MUSTs and 3 SHOULDs) with a planned
test for each, rate sharing with the client's `R_max`, token buckets, flow control
(`bufferedAmount`), the lifecycle (there are no idle timeouts), and the session-end
rules. Where sections 2 to 11 touch them, they point to section 12.

The words MUST, SHOULD and MAY are used as in RFC 2119.

---

## 1. What a peer is

A peer holds immutable, hash-verified Dacite entries and serves them to other
peers over a WebRTC data channel. Any peer may ask any other. **Peers are
read-only:** there is no `put`, no root update, no CAS. Those stay with the
origin server. A peer that asks is the *requester*; the one that answers is the
*responder*. A connection is symmetric, so each side is both at once.

The HTTP analogy is for explanation only. Nothing here is HTTP.

| Message | Like | What it does |
|---|---|---|
| `hello` | `OPTIONS` | Say what I speak, what I can do, how fast. |
| `get` | `GET` | Send me this value, as a stream of items. |

## 2. One idea: a message is a small Dacite value

Every message is a small Dacite value encoded in wire-v1.

- A control message is a **map with string keys**. It travels as the wire-v1
  literal (`Lit`, wire-v1.md "Literal encoding") of that map. Small values are
  inline literals, so a control message **needs no hash, no store entry and no
  `Item` header**. The sender never puts it in a store. The receiver never
  looks it up.
- A standalone `Lit` is the same bytes as the payload of a literal `Item`
  (wire-v1.md, "Literal encoding"), but self-delimited by the frame instead of
  by an `Item`'s `plen`. It MUST end exactly at the end of the payload: a byte
  after it is an error (fixture `reject-literal-trailing-byte`).
- There is **one encoder**: `dacite.wire.binary/encode-lit-bytes`. The peer
  layer adds no second serialization. Map and set order is the one wire-v1
  defines (ascending content hash), so equal messages are equal bytes.
- The **envelope** is the only thing added: a message type and a stream id
  (section 3). The type and stream id are not in the value.
- Bulk data is not a message. It is a stream of wire-v1 `Item`s, packed into
  frames (section 3.2).

### 2.1 The v1 value profile

A control message uses only these Dacite types. A receiver MUST reject any
other literal type (reason `profile`).

| Type | Use in messages | Native form (JVM, nbb) |
|---|---|---|
| `null` | not used by the shapes below | `nil` |
| `bool` | flags | boolean |
| `i64` | every number | integer |
| `string` | names, codes, text | string |
| `blob` | no v1 message uses one | `byte[]` / `Uint8Array` |
| `u256` | a hash | `{::u256 "<64 hex>"}` in `dacite.peer.msg` |
| `vector` | ordered lists | vector |
| `set` | capabilities, versions | set |
| `map` | the message, nested records | map with string keys |

Rules:

- **No floats.** `f32`/`f64` are outside the profile, because nbb has one
  number type and cannot tell `1` from `1.0`. Rates are integers (bytes per
  second). Interest is fixed point: `peak` and `floor` in thousandths, `tau` in
  milliseconds.
- **Integers are within ±(2^53 − 1)** (9 007 199 254 740 991), not the full `i64`.
  nbb has one number type, a double, exact only up to there; it would read
  `9007199254740993` as `9007199254740992`, so a value that the two hosts could read
  differently is outside the profile. Both hosts keep the one range: a sender
  refuses to write, and a receiver rejects, anything outside it (`profile`;
  `msg/max-int`). Hashes are `u256` and are not numbers, so this limits no field.
  Negative numbers are legal in the profile but no v1 shape uses one. Every number field below is a non-negative `i64`, and a
  field marked "at least 1" is rejected at 0 (`invalid-message`).
- **Strings are at most 256 UTF-8 bytes** (not characters), and **a set has at
  most 32 elements**. A receiver rejects more (`invalid-message`). A sender cuts
  free text (`agent`, `reason`, `message`) to fit, at a character boundary
  (`msg/cut-text`; the encoder does it), so it never sends a frame the limit would
  make a receiver refuse (fixture `reject-string-too-long`). Cutting is safe
  because nobody parses free text. Names a receiver does parse (`code`, `kind`,
  `policy`, capabilities) are never cut: an over-long one is a sender bug and is
  refused.
- A hash is a `u256` literal (type id `0x0D`, 32 raw bytes, the same bytes as
  the `hash` field of a wire-v1 `Item`).
- A blob may be a map value or a vector element, alone or beside other elements
  (a core limitation, [#8](https://github.com/jclaggett/dacite/issues/8), kept
  a vector holding a blob and another element from realizing before M1.1a).
- **Unknown keys MUST be ignored** by a receiver (so v1 can grow fields).
  **A receiver never rejects a name it does not know:** an unknown capability
  is ignored, an unknown `error` code is read as `internal`, an unknown `hello`
  `kind` is read as `browser`, and an unknown `get` policy is answered with
  `error unsupported-policy` (6.2). (A *sender* writes only names v1 defines, and
  the reference encoder refuses anything else.) A **missing required key** is
  `invalid-message`.
- A sender MUST write the canonical encoding. A receiver MAY reject a literal
  that does not re-encode to the same bytes (reference code does: `not-canonical`).

## 3. The frame

One frame is one data-channel message. Byte order is big-endian, as in wire-v1.

```text
Frame =
    type     u8       ; message type, 0x01..0xFF (0x00 reserved)
    flags    u8       ; bit 0 FIN, bit 1 PARTIAL, bits 2..7 MUST be 0
    stream   u32      ; 0 = the session; 1.. = a request (section 4)
    payload  bytes    ; the rest of the data-channel message
```

The header is 6 bytes. A frame is at most **16 384 bytes**, so the payload is at
most **16 378 bytes**.

| Flag | Name | Meaning |
|---|---|---|
| `0x01` | FIN | Last frame of this message on this stream. |
| `0x02` | PARTIAL | With FIN, on an item stream only: the reveal paused or spent its budget before it finished. Ask again to resume. |

PARTIAL without FIN is invalid. A control message carries FIN and PARTIAL=0.

| Type | Name | Payload | Direction | Stream (rule) |
|---|---|---|---|---|
| `0x01` | `hello` | `Lit` (map) | both, first | 0 (session) |
| `0x02` | `get` | `Lit` (map) | requester → responder | new id, 1 or more (request) |
| `0x03` | `item-stream` | wire-v1 `Item` bytes | responder → requester | the `get`'s id (request) |
| `0x04` | `ack` | `Lit` (map) | requester → responder | 0 (session) |
| `0x05` | `forget` | `Lit` (map) | requester → responder | 0 (session) |
| `0x06` | `cancel` | `Lit` (map) | requester → responder | the request's id (request) |
| `0x07` | `error` | `Lit` (map) | either | the request, or 0 (any) |

Type ids are dense. `have?` and `have` were dropped before v1 shipped (section 9,
Q6), so their ids were never used; a later message takes the next free id.

**Stream rule.** A `session` type MUST be on stream 0. A `request` type MUST be
on a stream of 1 or more. Sender and receiver both enforce it: a frame that
breaks it is refused with reason `wrong-stream` (`frame.cljc` `stream-rule`;
fixtures `reject-request-on-stream-0`,
`reject-ack-on-stream-n`, `reject-hello-on-stream-n`, `reject-cancel-on-stream-0`,
`reject-item-stream-on-stream-0`). Whether the id names an open request (an
`item-stream` answers a `get`, and so on) is session state: see section 4.
An unknown type id is not fatal: skip the frame and answer `error`
`unsupported` on that stream (fixture `unknown-type`). Type `0x00`, a frame
shorter than 6 bytes, a frame over the size limit, or a reserved flag bit is a
malformed frame: close the channel (a protocol violation: it ends the session, C16).

### 3.1 Why 16 KiB

RFC 8831 section 6.6: SCTP user messages may be arbitrarily large, but without
message interleaving (RFC 8260, I-DATA) "sending a large user message can
monopolize the SCTP association", so "the sender SHOULD limit the maximum
message size to 16 KB". Not every browser and Node library interleaves, so 16 KiB
is the one size that is safe for all of them. It also means:

- a frame is about 14 SCTP packets at the 1200-byte initial path MTU, so a
  lost packet delays little;
- a hash-verifying receiver can check an item as soon as its last byte
  arrives, within one frame of the sender;
- an `ack` of 256 hashes (8 KiB of hashes) and every other control message fit
  in one frame with room to spare (a test pins this);
- a literal item (3.2) is at most one frame payload, so it never spans frames;
- the `bufferedAmount` thresholds (12.5) can be set in whole frames.

`hello.limits.max-frame` lets a peer ask for smaller frames (8192..16384). It
can never raise the limit. The effective size is the smaller of the two. The
smallest, 8192 (`frame/min-max-frame`), holds the largest node item a sender
writes (3.2).

### 3.2 Serializing an item stream

There is **one serializer**: wire-v1 (`dacite.wire.binary`, with
`dacite.store.pack` choosing the form). The peer layer adds none. `dacite.peer.msg`
encodes a control message with `pack/literal-of` and `bin/encode-lit-bytes`;
`dacite.peer.frame` writes only the 6-byte envelope and finds item boundaries with
`bin/item-header`, so the `Item` layout is known in one place. Three layers:

1. **Entries.** Raw hash to data, as a store holds them (`ft/digit`, `hamt/entry`,
   a collection header, a scalar, and so on).
2. **wire-v1 serialization.** An `Item` (wire-v1.md) is an entry as it is
   (`enc = 0`, a *node item*) or a **literal** (`enc = 1`): the whole value written
   inline, with no hash entries for anything under it. Inside a literal, wire-v1
   encodes repeated values and types in sequences as `run` and `repeat`. A literal
   is self-contained: it holds no `ref` (a child by hash).
3. **Frames.** `item-stream` frames carry batches of whole serialized items.

**The literal rule (a sender's).** For each value it is about to send, in the
reveal's order, the sender writes the **shorter** of

- the value's literal item, and
- the node items it would otherwise send for that value (the value and everything
  under it that the receiver does not hold),

provided the literal item is at most **one frame payload**: 16 378 bytes, or
`max-frame - 6`, where `max-frame` is the negotiated one (3.1: the smaller of the two
peers' `hello.limits.max-frame`; `msg/negotiate`). The cap is on the
whole item (37-byte header included), so a literal never spans frames. A larger
value is not sent as one literal: the sender writes its node items, and then the
same rule applies to each child, so a big value becomes node items for its
spine and literals for its leaf pages. Equal sizes keep the node items. A literal
covers everything under it, so nothing below it is sent again
(`dacite.peer.items/encode-value`).

A receiver accepts either form for any value (the rule is the sender's), and
refuses a literal item over the cap from its 37-byte header, before the payload
arrives: `literal-too-large`. A literal that holds a `ref` (a `ref` item, or a `run`
or `repeat` of `ref`) is `bad-item`. Both directions use the one negotiated cap. The
stream reader takes it as an argument (`frame/reader`, default 16 KiB until `hello`
has been read), so the session code must pass the negotiated value.

**Packing.** The byte stream of items goes into frames as follows (`frame/chunk-items`):

- Whole items go into a frame while they fit its payload. An item that does not
  fit starts the next frame. So a literal, and any node item under a frame, is in
  one frame.
- A node item bigger than one payload starts a fresh frame and is cut into
  payload-sized pieces; the next item follows its tail. (Leaf pages in a store
  are 4 KiB at most, so this is rare: it happens for a page a store built in one
  piece, or under a larger leaf budget. Fixture `item-stream-node-spans-frames`
  has one, 25 990 bytes.)
- The last frame has FIN (and PARTIAL if the reveal is unfinished). An empty
  stream is one empty FIN frame (fixture `item-stream-empty`).
- A sender MAY flush early and send shorter frames while it paces a stream.
  A receiver MUST NOT rely on the cut: it reassembles **bytes**, not frames.
- A receiver MUST reject an `enc` other than 0 (node) or 1 (literal)
  (`bad-item`; fixture `reject-item-unknown-enc`), an item over `max-item`
  (default 1 MiB, `hello.limits.max-item`) **before** it buffers the payload
  (`item-too-large`), and a literal over the cap (`literal-too-large`; fixture
  `reject-literal-over-cap`). A stream that ends inside an item is an error
  (`truncated-item`).

Fixtures at the cap: `item-stream-literal-at-cap` is a string of 16 336
pseudo-random letters whose literal item is exactly 16 378 bytes, one full frame.
`item-stream-literal-over-cap` is the same string with one more letter: the whole
value is no longer one literal, so its header and spine are node items and its
leaf pages are literals (the largest 8 244 bytes, two pages of 4 KiB). `item-stream-one-frame` is a
small vector sent as one 678-byte literal. `item-stream-mixed` is a 1200-element
vector: node items and literals together.

**Node items (decision Q3, done in M1.1a).** A sender SHOULD keep each node item
within one frame payload. Since M1.1a every page is at most about 4 KiB of wire
bytes (the leaf page budget is 4096, below: it counts a tag on every single
element and a header on every run, so it follows the wire size) and a spine or
bitmap page is at most 32 refs, so the largest node item the reference
serializer writes is under 5 000 bytes (`frame/max-node-item-bytes`; the largest
seen is about 4 200, for pages of alternating one-byte kinds). A property test
builds strings (with characters outside the BMP), blobs, vectors of ints,
strings and blobs, nested vectors, maps (also with large values), mixed vectors
and vectors of alternating one-byte kinds in groups of one to three, and checks
the bound. A store that builds a page in one piece can still make a longer one
(fixture `item-stream-node-spans-frames`), and a receiver reads those, up to
`max-item` (1 MiB): receivers keep `max-item` and do not refuse a node item for
its size. `items/encode-value` **reports** a node item over one payload by
default (the result's `:oversized`, each `{:hash :len}`) and still sends it; with
the option `:strict-node-cap? true` it throws `{:reason :node-too-large}`
instead. The limit follows: the smallest `max-frame` a peer may advertise
(`frame/min-max-frame`) must be at least the largest node item plus the 37-byte
item header and the 6-byte envelope. M1.1a raised it from 1024 to **8192**
(`frame/min-max-frame-fits-node?`, tested), so a peer at the minimum frame size
gets every node item the reference serializer writes in one frame. A page
another store built may be longer, and a sender with a smaller frame (tests
only) sees its node items reported.

**Page sizes (M1.1a).** A leaf page (an `ft/digit` or `ft/node` body) holds at
most **4096 bytes** (`lit/page-budget`); the page-size choice and its
measurements are in `docs/design/leaf-chunking.md` and
`docs/notes/page-size-measurements.md`. The size of a page counts what its items
cost on the wire, as `lit/item-payload-bytes`: a string or blob is its length
**plus 5** (`lit/string-wire-overhead`: a tag and a u32 length), any other single
element its body **plus a one-byte tag** (a `char` also has a length byte), a run
or repeat its **6-byte header** (a `char` run 4 more, for the text length) and
its values, and `null` and `negative` count 1 (so they are never free). Runs and
repeats of strings and blobs count their real sizes, and a repeat counts every
copy (it is cheaper on the wire, which keeps a page bounded in elements). The
count is never below the wire size and is exact for scalars and runs of
fixed-size values (tested against the encoder). Before M1.1a's review this
counted a one-byte element as one byte and a run as free, so a page of
alternating `u8` and `char` was 8 261 bytes on the wire, over the minimum frame.
The page budget does not change any value hash: a node hashes
`(type, elements-fuse)`, so the root hash of a value is the same for any paging.
The hash of an inner page does depend on the paging (it is a different group of
elements), so fixtures that name inner pages were regenerated.

**Open design item: an "encoded byte size" measure.** Sizing stays on payload
bytes. The payload count approximates the wire size (a 4-byte string is 4 payload
bytes plus the 5 now counted, against 9 on the wire; runs and repeats are cheaper
on the wire than their payload). Two ideas for an exact measure, both undecided:

- *(a) A second measure beside `size-bytes`: encoded-size, a sum monoid.* Each
  element contributes the wire-v1 bytes it takes in a page body, and a node's
  value is the sum of its children's. It makes the size cue exact enough to pick
  literal or node and to cut pages by wire size. Analysis: the sum is only an upper
  bound, because a run or repeat spends a few header bytes once and then less per
  element, so the cost of an element depends on its neighbors; the measure would
  have to be defined as "as if each element were its own item". If it joins the
  measure fused into the node hash, every hash changes: a wire and hash bump
  (v2), not an M1.1a change. If it is only stored beside the measure, no hash
  changes, but every node carries 8 more bytes per measure, a receiver cannot check
  it from a spine page's bytes (it depends on the children, like count and size:
  Q1), and it must stay consistent with the encoder.
- *(b) The literal encoding as an in-memory cache, bounded by the cap.* While a
  node's literal item is within the cap, the in-memory store keeps its bytes next to
  the node; above the cap it keeps the mark "too big", and so do the nodes above
  it (a parent's literal contains its children's). Then the literal's length is
  known directly and "shorter wins" is a comparison, not a build. **The literal is
  never stored in any entry, never sent as part of one, and never hashed: it is a
  cache only.** An in-memory store and a durable one therefore differ: the
  durable store keeps no literal bytes and rebuilds or lazily computes the cache
  when a node is read (a miss falls back to building the literal from the node,
  as today, so a cold cache costs time, not correctness). Analysis: no wire change
  and no hash change; the cache is content-addressed, so it needs no
  invalidation. Costs: memory (up to one cap, 16 KiB, per cached node, and a
  literal at each level repeats its children, so bound it by size or an LRU and
  keep only the bottom levels, the "too big" mark being O(1)), and the in-memory
  and durable stores' different cost profile (documented, and never visible in
  results). The concern of putting it in the measure monoid itself, spine nodes
  growing by up to a literal each, does not arise when it is kept in memory only.

Neither is built in M1.1a.

**Earlier draft.** The first draft said item streams carried node items only
(review item F2, and open question 5). That is replaced by this section: literals
are allowed under the cap (decision Q5). The reveal service of today
(`dacite.service.reveal`) still writes node items only, which a v1 receiver
accepts.

### 3.3 Verification of items

**v1 assumes good actors.** It guards against corruption and against a peer that
is wrong, not against one that works to deceive (see "Trust" below and section 11).

Every item is verified by the receiver before it is stored, **from its own bytes
alone**, and from what earlier items already put in the store. A parent's
verification never waits for its children. Each child is verified on its own when
it arrives. **No peer holds a partial value in order to verify.** An item that
cannot be verified from its own bytes is stored and checked later, when the item
it depends on arrives (only a collection header, below); it is never parked.
`dacite.peer.verify/verify-item!` is the check (portable `.cljc`; `dacite.peer.items`
re-exports it); `accept-item!` stores each item as it is verified, in arrival order.
`dacite.peer.client` is the requesting side that uses it: items reach its store only
through `accept-item!`, and a failure ends that peer's session (12.4). (The older `dacite.service.reveal/accept-items!`
does hold a collection header until its root arrives. It is not the peer rule, and
M2.2 replaces it.)

What each kind of item is checked against:

| Item | Verified from |
|---|---|
| literal | Rebuilt from its bytes in a scratch store: its value hash must equal the item's. Complete, so nothing is left to check. A literal holds no `ref`: a `ref` item, or a `run` or `repeat` of `ref`, is `bad-item`. |
| scalar | Its hash from its data. |
| leaf page: `ft/digit` or `ft/node` with every leaf inline (no `ref`) | Its hash, from the claimed measure (`node-hash(type, elements-fuse)` = `digest(fuse(type-state, elements-fuse))`); and the claimed measure must equal the one recomputed from the leaves in its body. A leaf page **verifies alone**. |
| `hamt/entry` | Its hash from the claimed measure, and `elements-fuse = fuse(ref->state(key-ref), ref->state(val-ref))`. |
| branch page: `ft/deep`, `hamt/bitmap`, any page with a `ref` (a `ref` item, or a `run` of `ref`) | Its hash, from its claimed measure (local). **Not** that the measure follows from its children: the page holds their hashes, not their measures. Branch nodes are not bound to their children in v1 (section 11, Q1). A `hamt/bitmap` is also checked for its shape: a `split` of 0..51, two or more children, one per bit of the bitmap. Its hash covers the bitmap and the split. |
| collection header (`string`, `blob`, `vector`, `map`, `set`) | **Accepted and stored on arrival; checked when its root arrives.** See below. |

**The hash (hash version 2, issue #14).** A *state* is a 2x2 matrix over F_p with
determinant 1 (p = 75509794529310452520362503714625764980405239500720831432010267419866053829067,
a 256-bit prime; 128 bytes: four 32-byte big-endian cells); *fuse* is matrix multiplication; a *ref* is the 32-byte
*digest* of a state: each cell as a 256-bit integer, rotated left by 0, 64, 128
and 192 bits for a, b, c, d, and the four xored (the identity digests to `[1 0 0 1]`). A byte string is the product of 256 fixed generator matrices, one per
byte; a ref enters a fuse as `ref->state`, the product of the generators of its 32
bytes. `value_hash = digest(fuse(type_state, data_state))`, where a scalar's data
state is the fuse of its bytes and a collection's is its `elements-fuse`, the
product of its elements' `ref->state` in order. Every state a measure carries is
checked by the receiver: four cells below p and determinant 1, else the item is
bad. The wire decoder (`wire/binary`, JVM and nbb) throws an ex-info with
`:reason :bad-state`; the JVM peer item reader (`store/reveal.clj`) maps that to
`bad-entry` with `:detail :bad-state`, and any other receiver must do the same. The prime, the table, the digest and the reasons are in
`docs/design/fuse-sl2.md`; the known answers every host must reproduce are
`fixtures/fuse-sl2/vectors.edn`. A value hash under version 1 (the old unitriangular
fuse) is never equal to one under version 2: the store format tag (wire-v1,
"Persisted stores") keeps the two apart on disk. The wire `version` and the
`hello` versions are **not** bumped: the format changed (a measure is 144 bytes, was
48), but the wire is a draft and no values are in the wild (as for #12). They must
be bumped by the first release that has users.

**Header first.** A sender writes items in reveal order: a collection's header
before its pages, so the receiver sees `count` and `size-bytes` at once and can
read the start while the rest arrives (decision Q2). No header field is added.

A header cannot be checked alone. Its hash is `fuse(type-hash, root
elements-fuse)`, and its `count` and `size-bytes` are in no hash at all, so the
only check is to recompute them from the root. So the receiver stores the header
when it arrives (`verify-item!` answers `#{:pending}`) and records it as waiting
for its root's hash. When the root is stored, a page or a bare leaf value that is
the one element, the receiver recomputes the root's measure and checks, in this
order: the header's hash (`bad-hash`), then its `count` and `size-bytes`
(`bad-entry`). A failed check removes **that header** from the store (only the
headers that fail: an honest header on the same root stays, verified) and ends the
session (`error` `bad-entry`, fatal). A header whose root is a literal, or inside a literal, is
checked as soon as that literal is stored. Nested headers each wait for their own
root; an outer header over a bare-leaf root is checked against the inner header's
claimed count and size, and the inner one is checked when its root arrives, so the
chain is verified end to end once it is complete.

Nothing is held back: the header is stored and counted as accepted. What the
receiver must do is track the header as *unchecked* until then
(`items/unchecked`), and drop the unchecked headers when the stream ends without
their roots: on a fatal error, a `cancel`, or a PARTIAL stream
(`items/discard-unchecked!`); and on a FIN without PARTIAL, a header whose root never
arrived is `bad-entry` (`items/finish!`). A reader of the store sees an unchecked
header's claimed count and size for the short time before its root arrives; it
cannot read elements through it, because the root is not there.

**Order of everything else.** Pages carry their own measure, so they can arrive in any order
relative to one another. A literal needs no order. A bad item ends the session
with the peer: `error` `bad-entry`, `fatal`.
Section 12.8 numbers those rules (C27 to C33). The check is portable (`dacite.peer.verify`,
M1.2 slice 1), so the JVM and nbb run the same code. A lookup that finds nothing is
`bad-entry`, never a raw store error.

**Trust.** The hash of a value commits to its ordered elements and not to the
tree that stores them (that is what makes equal values share storage whatever
their chunking). So no check from an item's own bytes can bind a branch node to its
children in v1: see Q1.

**HAMT shape (issue #12).** A `hamt/bitmap` is the one node whose hash is not
shape-independent, and its shape is fixed, so a peer can check it. It has two or
more children (never one) and a `split`: the index, 0..51, of the 5-bit hash chunk
it branches on. Its hash is
`digest(fuse(fuse(type_state, elements_fuse), fuse_bytes(bitmap_be8 ++ [split])))`.
The wire layout is `bitmap u32, split u8, n u8, children Hash32 x n` (wire-v1,
`HamtBitmap`). The shape is canonical, which means a node is the HAMT of its own
key set:

- a set of one key is a `hamt/entry`;
- a set K of two or more keys is a node whose `split` is the first chunk at which
  the hashes of K do not all agree, with one child for each value of that chunk,
  in ascending order, each child the node (or entry) for the keys with that value.

So the root of a map is at the longest common chunk prefix of its keys, and a child
splits at a later chunk than its parent. Two keys with one 256-bit hash are treated
as one key (the hash is a content hash): there is no collision bucket, and distinct hashes differ
at a chunk 0..51, so a branch exists at the last chunk (51, which reads one bit) at
the latest. A lookup compares the whole key hash at an entry.

*Proof sketch that the shape does not depend on history.* Let T(K) be the tree
above. Induction on the history of `assoc` and `dissoc`; the claim is that every
operation takes T(K) to T(K') for the new key set K'. Let c be the split of T(K).
*assoc k.* If k agrees with the keys of K before chunk c, then c is still the first
chunk where K + k disagree and the groups are the old groups with k in its own
group (a new entry if that group was empty, else the assoc into that child, which
is T of the group plus k by induction). If k differs from them at a chunk d < c
(the first such), K + k first disagree at d, with two groups: K, which agree at d
because d < c, and {k}. That node is `split d` over T(K) and the entry for k, which
is what `assoc` builds, without any one-child node above T(K). An entry is the
same case with K one key. *dissoc k.* Remove k from its group. If the group still
has keys, the child is T of the smaller group by induction, and there are still two
or more groups, so c is unchanged. If the group is empty and two or more groups
remain, they differ at c and agree before it, so c is unchanged and the node loses
one child. If one group remains, K - k is that group and T(K - k) is its child
itself: `dissoc` returns that child (an entry, or a node that is already canonical)
in the parent's place. The base cases are the empty node and an entry. So any
history that ends with the key set K ends at T(K): the root has the same bytes, and
so does every node under it, because the shape of each is T of its own key set. The
*shape* is a function of the key set; the *bytes* of a node also hold its measure
(count, size-bytes, and the elements-fuse of its entries' key and value refs), so
they are a function of the entries, the keys with their values. Equal entries give
byte-identical nodes, in different maps as well; the same keys with another value
in one entry give the same shape and other bytes below that entry. The value hash does not see any of this: it is the fuse
of the entries, in the order the tree is walked, which is ascending key hash, and
that is unchanged.

**On receipt** (from one item's own bytes) a receiver checks a `hamt/bitmap` for its
hash, its `split` range (0..51), `n >= 2`, `n = popcount(bitmap)` and, at split 51,
a bitmap of exactly bits 0 and 16 (chunk 51 is one bit, read as 0 or 16); and a
`hamt/entry` for its hash, its measure and `key-hash = key-ref`. It cannot check
from one node that the children hold the right keys (the node holds hashes, not
keys): that is Q1 below.

**On a walk** (not when nodes arrive) the reference reader requires a child's split
to exceed its parent's, and throws `bad-hamt-node` otherwise. A node does not know
its parent, so two forged nodes that break the rule each verify alone, and the O(1)
`count` and the collection header check still succeed; the first `get`, `entries`,
`assoc` or `dissoc` that meets them throws. This is within the good-actors rule of
v1 (Q1). Every walk ends in at most 52 nodes, and an entry found outside the path
its hash routes it by makes `assoc` throw `bad-hamt-node` too.

## 4. Streams and stream ids

A **stream** is one request and its answer. The requester picks the stream id.

- **Stream 0** is the session: `hello`, `ack`, `forget`, and fatal errors.
- A request (`get`) opens stream *n* ≥ 1. The answer and any
  `error` or `cancel` for it carry the same *n*.
- *n* is a u32. The peer that sent the WebRTC offer uses odd ids (1, 3, 5, …),
  the peer that answered uses even ids (2, 4, …). An id is used once per
  connection and never reused. Allocating in increasing order is RECOMMENDED but
  not required, because channels are not ordered with respect to each other.
- A responder MUST refuse a request whose id has the wrong parity
  (`bad-stream-parity`), is already used on the connection
  (`duplicate-stream`), or would exceed the `max-streams` it advertised
  (`too-many-streams`). It answers `error malformed` (or `overloaded` for the
  last) on that stream and does not start it. Id 0 is not a request
  (`wrong-stream`).
- These are session rules, kept by a small pure helper, `frame/stream-table`
  (`open-local` allocates this side's next id, `open-remote` accepts or refuses
  the remote's, `close-stream` frees a slot but never frees the id, `live?`
  says whether a frame for an id is still wanted). They are tested in
  `peer-v1-test`; they have no golden frames, because they are about the
  sequence of ids, not the bytes. Frames for a closed id are ignored.
- **A stream id is never the hash.** It does not derive from the request's
  contents. Two requests for one hash are two streams with two ids.

### 4.1 This fixes N1

The N1 flaw (review of Oct 29): the service keyed its stream table by the
reveal id, which was the asked hash, so a second request for the same hash
replaced the first stream, orphaned its completion, and the first request hung
until its timeout cancelled the second. The cause was an identity that two
requests could share.

In peer v1 the identity of a stream is the requester's id, carried in every
frame. The responder keys its stream table by `(connection, stream id)`, so a
repeat ask, a concurrent ask, and a resume after PARTIAL are all new streams
that cannot collide. What the responder does about the *reveal* behind two
streams (attach both to one reveal, or start a second) is its business; the wire
never needs the answer. The JVM service already keys streams by a per-request
id (commit `e143cdb`); this section makes that the protocol rule.
Fixtures `get-default` and `get-repeat` are the same ask on streams 5 and 9.

### 4.2 Lifecycle

A stream ends when the responder sends the last `item-stream` frame (FIN), or
or an `error`. After that the id is closed; frames still in
flight for it are ignored. `cancel` from the requester asks the responder to
stop writing; the responder stops, sends nothing more for that stream, and the
requester ignores frames already in flight. A `cancel` for a closed stream is
ignored. (The streaming note says interest decays like a lease, so `cancel` is
an optimization, not a correctness need: it sets the stream's interest to zero,
12.3, C07.) A stream also ends by itself when its interest falls below the floor
(FIN with PARTIAL, C05); there is no idle timeout (C06).

## 5. Transport mapping (WebRTC; informative until M3.2)

This section is **informative**. Its "must" and "is expected" are the design
intent; none of it is a conformance rule until M3.2 builds the transport and
M1.2 wrote the numbered MUST list (section 13). Sections 2, 3, 4, 6, 12 and 13 are
the normative part of this draft.

Reliable, ordered data channels, SCTP over DTLS.

- **Control channel:** label `dacite/v1/control`, opened by the offerer with
  `negotiated: true, id: 0`, so no side waits for the other. Carries stream 0
  (`hello`, `ack`, `forget`).
- **One channel per `get`**, at most `max-streams` open at once (decision Q10):
  the requester opens a channel labelled
  `dacite/v1/get/<stream id>`. Its first message is the `get` frame; the
  responder answers on the same channel with `item-stream` or `error` frames.
  Every frame on it is expected to carry the id in the label; a receiver
  closes the channel on a mismatch. Either side closes the channel after the stream ends.
- The envelope carries the stream id even though the label already does. That
  keeps one codec for the **single multiplexed pipe mode**, which stays in v1 (the
  in-process loopback of M3.1, a WebSocket): the same frames, every stream on one
  pipe, no label. It also lets `cancel`, `error` and `ack` name a stream from any
  channel.
- The version is in the label (`v1`) and in `hello`.

## 6. Messages

Examples are EDN, in the native model of section 2.1. The `hex` is the whole
frame from the fixture. Fixture names are directories under
`fixtures/peer-v1/cases/`.

### 6.1 `hello` (0x01, stream 0)

Both sides send it as the first message on the control channel, without waiting
for the other. Nothing else is sent (but `error`) until the peer's `hello` has
arrived.

| Key | Type | Req | Meaning |
|---|---|---|---|
| `versions` | set of i64 | yes | Protocol versions I speak. This draft is `#{1}`. |
| `kind` | string | yes | `"node"` or `"browser"`. Selection prefers Node peers. An unknown kind is accepted and read as `"browser"` (fixture `hello-unknown-kind`). |
| `caps` | set of string | yes | Capabilities: `"reveal"` (I answer `get`). Unknown ones are ignored. |
| `limits` | map | yes | `max-frame` (8192..16384), `max-item` (bytes, at least 1), `max-streams` (open requests I accept, at least 1). All required. |
| `rates` | map | yes | `send-max` and `recv-max` (bytes per second I will send / want to receive), `req-rate` and `req-burst` (requests per second and bucket size I accept). All required and at least 1: a peer that serves nothing leaves out the capability instead. |
| `agent` | string | no | Software name and version. Informational. |

```clojure
{"versions" #{1}
 "agent"    "dacite-nbb/0.2.0-alpha"
 "kind"     "node"
 "caps"     #{"reveal"}
 "limits"   {"max-frame" 16384 "max-item" 1048576 "max-streams" 32}
 "rates"    {"send-max" 10000000 "recv-max" 10000000 "req-rate" 20 "req-burst" 40}}
```

Fixtures: `hello-node` (343 bytes), `hello-browser`.

**Batch size.** An `ack` or `forget` holds at most
`min(256, max-hashes-for(max-frame))` hashes, where
`max-hashes-for(f) = min(256, floor((f - 33 - 320) / 32))`: 32 bytes a hash,
33 bytes of header and framing, and 320 kept for a `forget` `reason`. At 16384
that is 256; at 8192 (the smallest allowed) it is 244; at 1024 it would be 20 (a
helper test still checks it, though no peer may ask for that now). `msg/max-hashes-for` is the helper, and a test
encodes a full batch at each frame size.

Negotiation (`msg/negotiate`, tested): the version is the highest in both sets; if
there is none, send `error` `unsupported-version` with `fatal: true` and close.
`max-frame` and `max-item` are the smaller of the two sides'. `max-streams` is per
direction: each side's value limits what the *other* may have open. Capabilities are
the intersection. Rates are advertised ceilings; the client shares its `R_max` across
peers by interest and tells each peer its share in every `get` (`recv-max`, 6.2, 12.2).

### 6.2 `get` (0x02, new stream)

Ask for a value as a reveal. The answer is an item stream (6.3).

| Key | Type | Req | Meaning |
|---|---|---|---|
| `hash` | u256 | yes | The root of what I want. |
| `policy` | string | yes | Reveal policy by name. v1 defines `"default"` only (the engine's default order for each value type). Any other name still decodes; the responder answers `error` `unsupported-policy` on that stream (not fatal). |
| `recv-max` | i64 | yes | Bytes per second I want from you, on this whole session, from now on. At least 1 (`invalid-message` at 0 or absent). The latest `get`'s value is the session budget (C03); a client SHOULD set it to its current C01/C02 share for this peer. |
| `interest` | map | no | `peak`, `tau-ms`, `floor`, all required if present: `I(t) = peak · exp(−t / tau)` with `peak` and `floor` in thousandths. `peak` and `tau-ms` are at least 1 (the reveal engine needs `tau > 0`); `floor` may be 0. A `floor` above `peak` is legal and pauses the reveal at once. Absent: the responder's defaults. The responder may clamp any value to its own limits, and MUST raise a `floor` below 10 and cut `tau-ms` above 60 000 and `peak` above 1000 (12.3, C09). |

```clojure
{"hash" h1 "policy" "default" "recv-max" 750000         ; 750 kB/s from you
 "interest" {"peak" 1000 "tau-ms" 2000 "floor" 50}}   ; peak 1.0, tau 2 s, floor 0.05
```

An unknown policy is a request the responder cannot serve, not a malformed
message, so decoding succeeds and `msg/answer-error` returns the `error`
(fixture `get-unknown-policy` lists the expected answer). The same helper
answers an unknown message type, and a `get` the responder has no `reveal`
capability for, with `unsupported`. A sender never writes an unknown policy:
the reference encoder refuses it.

A policy is a Dacite value in the long run (streaming note); the shape is
deferred, which is why `policy` is a string name now and a later version can
accept a map there.

The whole frame of fixture `get-default`, by hand (`0x0d` is `u256`, then its
32 bytes; `0x02` is `get`):

```hex get-default
02 01 00000005                 ; get, FIN, stream 5
21 00000003                    ; map, 3 pairs
10 00000006 706f6c696379       ; "policy"
10 00000007 64656661756c74     ; "default"
10 00000004 68617368           ; "hash"
0d 0000000000000001 0000000000000002 0000000000000003 00000000000000aa
10 00000008 726563762d6d6178   ; "recv-max"
03 00000000000b71b0            ; i64 750000
```

Answers: `item-stream` frames, or `error` `unknown-hash` (the responder does not
hold the hash; non-fatal, the stream ends), `unsupported-policy`, `rate-limited`,
`overloaded`, `too-large`.
Fixtures: `get-default`, `get-interest`, `get-probe`, `get-repeat` (each with a `recv-max`); rejects `reject-get-recv-max-zero`, `reject-get-missing-recv-max`.

**Asking whether a peer holds something.** v1 has no separate message for it
(an earlier draft had `have?`/`have` with a bitmap; it was removed, decision Q6).
Send a `get` with the smallest interest (`get-probe`: `peak` 1, `tau-ms` 1,
`floor` 0). The answer is the first item, or `error unknown-hash`. Then `cancel`
if nothing more is wanted. A bitmap-style `have?` may come back later as an
optimization (one frame to ask about up to 256 hashes), if probing proves too
costly. v1 does not define it.

### 6.3 `item-stream` (0x03, the `get`'s stream)

Not a Dacite value: the payload is a slice of the item byte stream (3.2).
The responder sends items as its reveal clock allows. The receiver verifies each
item (3.3) and stores it, one at a time. FIN ends the stream. FIN with PARTIAL says the reveal
paused (interest fell below the floor) or spent its budget, and that the same
`get`, as a new stream, resumes it (this closes N2a on the wire).

Fixtures: `item-stream-empty` (one empty frame), `item-stream-one-frame`
(one literal item, 684 bytes), `item-stream-mixed` (3 frames, 41 404 bytes;
node items and literals), `item-stream-node-spans-frames` (a 25 990-byte node
item over one payload, 4 frames), `item-stream-partial`
(FIN + PARTIAL), `item-stream-literal-at-cap` and `item-stream-literal-over-cap`
(3.2). Their `message.edn` lists every item's hash, size and encoding, and the JVM
test accepts each stream through `items/accept-items!`.

### 6.4 `ack` (0x04) and `forget` (0x05), stream 0

These are the explicit client messages the reveal engine needed (N7, N8). They
are session-wide: they name hashes, not streams, matching `ack-pages` and
`forget-pages`.

```clojure
;; ack: I stored these pages; you may stop tracking them as offered
{"hashes" [h1 h2]}
;; forget: I do not hold these (reset, evicted); send them again if asked
{"hashes" [h3] "reason" "evicted"}
```

1 to 256 hashes each. `reason` is free text. Fixtures: `ack`, `forget`.
Both are optional hints and correctness never waits for them; the sender's record is bounded and may be forgotten at any time (12.6, C19 to C23).

### 6.5 `cancel` (0x06, the request's stream)

```clojure
{"reason" "navigated away"}   ; or {}
```

`reason` is optional (fixtures `cancel`, `cancel-bare`: an 11-byte frame, written
out by hand):

```hex cancel-bare
06 01 00000007                 ; cancel, FIN, stream 7
21 00000000                    ; map, 0 pairs
```

### 6.6 `error` (0x07)

On a request's stream it ends that stream. On stream 0 with `fatal: true` it
ends the session, and the sender closes the connection after it.

| Key | Type | Req | Meaning |
|---|---|---|---|
| `code` | string | yes | One of the codes below. A receiver reads an unknown code as `internal` (`msg/error-code`; fixture `error-unknown-code`) and does not reject the message. A sender writes only these. |
| `fatal` | bool | yes | The session ends. |
| `message` | string | no | Text for logs. Never parsed. |
| `retry-ms` | i64 | no | With `rate-limited`/`overloaded`: wait this long. |

| Code | When |
|---|---|
| `unsupported` | Unknown message type, or a capability I lack. |
| `unsupported-version` | No common version (fatal). |
| `unsupported-policy` | `get` named a policy I do not know. |
| `malformed` | A frame or value that breaks this spec. |
| `unknown-hash` | I do not hold it. Not fatal. |
| `bad-entry` | An item failed verification (3.3): its hash, a measure, a header's count or size, or a literal that holds a `ref` (the reference receiver names these `bad-hash`, `bad-entry`, `bad-item`; all are sent as `bad-entry`). Fatal. |
| `too-large` | An item or request over a limit. |
| `rate-limited`, `overloaded` | Back off (`retry-ms`). |
| `cancelled` | Optional answer to `cancel`. |
| `internal` | Anything else. |

Fixtures: `error-unknown-hash`, `error-rate-limited`, `error-bad-entry`.

## 7. A session

```text
A (offerer)                                      B (answerer)
control   hello ----------------------------------->            stream 0
          <----------------------------------- hello
get/3     get {h1 default}  ------------------------>           stream 3, new channel
          <----------- item-stream (16 KiB frames) ...
          <----------- item-stream FIN
control   ack {pages stored} ----------------------->           stream 0
get/5     get {h1 default}  ------------------------>           stream 5: same hash, new id (N1)
```

## 8. Fixtures and tests

`fixtures/peer-v1/` has 50 cases. Each is a directory with `frames.hex` (one
frame per line, lowercase hex) and `message.edn` (what the frames mean).
`message.edn` has `:expect`:

| `:expect` | Port must |
|---|---|
| `:message` | Decode the one frame to the given type, stream and value; encode that value again and get identical bytes. Some cases add `:reads` (how a receiver reads a name it does not know: `:error-code`, `:peer-kind`) and `:answer` (the `error` a responder sends, from `answer-error`). |
| `:item-stream` | Reassemble the frames, split the items, match the listed hashes, sizes and encodings (`:node`, `:literal`); re-pack the items and get identical frames; re-encode each item to identical bytes; no literal item over one frame payload, and none cut by a frame boundary. |
| `:reject` | Refuse the frames, for the given `:reason` (`frame-too-short`, `frame-too-large`, `unknown-type`, `bad-flags`, `partial-without-fin`, `not-fin`, `wrong-type`, `bad-literal`, `not-canonical`, `profile`, `invalid-message`, `bad-item`, `literal-too-large`, `item-too-large`, `truncated-item`, `after-fin`, `wrong-stream`). 27 cases. |
| `:unknown` | Decode as an unknown type, not as an error. |

Goldens come only from the shipped code. Regenerate (JVM, from `impl/clojure`):

```sh
clojure -M:dev -m gen-peer-fixtures
```

Run: `clojure -M:dev:test -n dacite.peer.peer-v1-test -n dacite.peer.peer-items-test`
(JVM; also accepts every item-stream fixture through `items/accept-items!`, tests
the literal rule, the cap, packing and verification, a property test over random
profile values, and size limits) and `npm run peer:test` (nbb; the same fixtures,
byte for byte: the host parity check for the encoder and the packing).

## 9. Resolved decisions

Jonathan's answers to the ten open questions of the first draft. The numbers are
the draft's.

1. **Control body = bare `Lit`: yes.** No `Item` header and hash on a control
   message. A message cannot be stored or named by hash, and does not need to be.
2. **Type in the envelope only:** accepted. A message cannot disagree with its own
   envelope.
3. **No floats in control messages.** Interest stays fixed point (thousandths and
   milliseconds).
4. **16 KiB frames and a 1 MiB `max-item` stay.** (The `have?` cap of 256 went
   with `have?`; `ack` and `forget` keep their 256.)
5. **Literal items in an item stream: yes, as the serializer's rule.** An item is a
   node or a literal; the shorter wins; a literal is at most one frame payload, so
   it never spans frames; a larger value falls back to node items. See 3.2.
6. **`have?` and `have` are removed from v1** (code, spec, fixtures, tests and the
   `have` capability, and `limits.max-have`). A `get` with the smallest interest
   covers it (6.2). A bitmap `have?` is a possible later optimization only.
7. **`ack` and `forget` stay session-wide, on stream 0.** (`forget`, 0x05, was
   named `release` until M1.2; renamed so "release" never means two things, 12.14.)
8. **PARTIAL on FIN** marks a paused or exhausted reveal. No separate message.
9. **`cancel` stays,** though interest decays like a lease.
10. **Channels:** one control channel, plus one channel per `get`, capped by
    `max-streams`. The single multiplexed pipe mode stays (section 5).

Also decided with these: every item is verified from its own bytes, and nothing
waits or is held (3.3). The three questions that followed (branch nodes not
bound to their children, headers, node items over a frame) are answered in
section 11.

## 10. Known issues

- Item verification is JVM-only (`dacite.peer.items`) until M2.2, so the nbb test
  checks framing, packing and item decoding, not entry hashes.
- The pack chunk budget is decided (M1.1a review). `pack/default-budget` is 4096, the leaf
  page size, for the HTTP chunk transport and for peers: a full leaf page is then a literal,
  so the first GET of a long string carries its first characters, not only its header and
  spine. Peer streams are bounded by the frame cap, not by this budget. Durable stores
  (file, LMDB, IndexedDB) keep 1 KiB as `chunk/durable-budget`, a constant of their own,
  because on disk a 4 KiB literal repeats what its entries share.
- Fixed in M1.1a: a vector holding a blob and another element did not realize
  ([#8](https://github.com/jclaggett/dacite/issues/8)); and `lit/item-payload-bytes`
  counted a `run` or `repeat` of strings or blobs as 0, so a store could build a leaf
  page far over its budget (a 39 753-byte page was in the earlier `item-stream-mixed`).

## 11. Trust, and what v1 leaves for later

These were the three open questions of the S3.3 work. Jonathan has decided them.

1. **Branch nodes are not bound to their children (Q1). v1 assumes good actors.**
   An item is verified from its own bytes, but a branch node (`ft/deep`,
   `hamt/bitmap`, any page with a `ref`) holds its children's hashes, not their
   measures, and its own hash covers only `(type, elements-fuse)` (and, for a
   `hamt/bitmap`, its bitmap and its split), not the child hashes. The HAMT chain
   of issue #12 is not fixed by binding children: it is fixed by the telescoping
   shape of §3.3 (no one-child node, a `split` in the node and its hash), which is
   a wire change to `hamt/bitmap` nodes and their hashes, accepted while peer-v1
   is a draft. The wire and `hello` versions are not bumped for it (draft; no values
   in the wild); persisted stores carry a store format tag instead (wire-v1,
   "Persisted stores"). Binding the child hashes into a HAMT node was tried first (a
   one-child node folded its child) and replaced.
   A peer that works to deceive can send a
   page whose hash is right and whose children are other, valid nodes: each child
   verifies against its own hash, and nothing checks that the parent's measure is
   what the children add up to. The same hole is in today's reveal client (the
   origin path). v1 accepts it. Peers are good actors in v1 (a bad entry from a
   careless or broken peer is still caught); no public or adversarial use is
   planned before the hardening below.

   *Later hardening path (no wire change).* As each child arrives, back-check
   that its fused hash still combines, with its siblings as received, to the
   `elements-fuse` the parent recorded and the parent's hash covers; a branch is
   marked bound once its children have all been checked, and a read of an
   unbound branch is the receiver's choice. The same check also gives the count
   and size an ancestor claims.

   *Alternatives considered:*
   - **Child measures in the refs** (hash + count + size-bytes + elements-fuse,
     +48 bytes per child ref under hash version 1; a state is now 128 bytes, so
     +144). A branch then verifies from its own bytes like a
     leaf page. It changes the bytes of branch pages and HAMT bitmaps (a wire-v1
     entry-format change; no value hash changes). Measured on the reference
     serializer, as a share of the whole value: **+48 B a ref costs 4.5% to
     4.7% for a string, blob or vector of strings, 6.6% for a vector of ints,
     and 51% for a map of 10 000 int keys**, where a bitmap has about 5 refs and
     each points at a 56-byte entry; these shares were measured under version 1 and
     are not re-measured for the 144-byte measure. Fuse-only (+32 B; +128 B with a full state) is enough for content
     integrity but leaves count and size unauthenticated (3.0% to 4.4%, and 34%
     for the map); count and size alone (+16 B) is not sound (1.5% to 2.2%, and
     17% for the map).
   - **Fold the child hashes into the node hash.** It makes a value's hash depend
     on how it is chunked, so equal values stop deduplicating across stores and
     hosts, and it changes every existing hash. A different system, not a v1
     tweak. Not planned.

   *The fuse is not a cryptographic hash.* It is a homomorphic group hash (a
   product in SL(2, F_p), p a 256-bit prime). The best attack known to us (words in
   the upper-triangular subgroup, found by a birthday search over the projective
   line) costs about 60 sqrt(p) multiplications, measured on primes of 20 to 40
   bits: about 2^134 for this p, and the 32-byte ref is bounded by 2^128 in any
   case. That is the margin of one attack, not a proof. An earlier estimate of
   about p (1.8 p) for a different attack was wrong. Its resistance to an
   adversary needs an outside cryptographic review before peers are untrusted
   (docs/design/fuse-sl2.md, docs/roadmap.md).
2. **Collection headers (Q2): header first, no new field, checked when the root
   arrives.** See 3.3. The review's alternative, an `elements-fuse` field in the
   header, is not taken; neither is the pages-before-header order of the first
   draft, which delayed `count` and `size-bytes` and the start of a read.
3. **Node items over a frame (Q3):** a sender-side SHOULD cap of one payload after
   M1.1a; receivers keep the 1 MiB `max-item`. Done in M1.1a; the cap reports by
   default and throws only with `:strict-node-cap?`. See 3.2.
4. **Failed header checks (M4 follow-up, M1.1a):** when two or more headers wait on
   one root, each is checked on its own when the root arrives; the failing ones
   are removed and the honest ones stay. The first failure still ends the session.

## 12. Policy rules (M1.2)

This section and section 13 are **normative**. They fold in Jonathan's decisions
of Nov 4, 2026 (`m1.2-decisions-2026-11-04.md`) and write a concrete default for
every item that was still open. Terms follow
[progressive-revelation.md](../design/progressive-revelation.md): `R_max`, interest
`I(t) = peak · exp(−t / tau)`, `floor`, whole-page quanta.

Each rule has an id, **C01** to **C45** (C04 withdrawn; 41 MUSTs and 3 SHOULDs, section 13), and the table in section 13 maps every id
to a planned test. A rule is marked one of two ways:

- **Decided** (Jonathan, Nov 4): folded in as he stated it, or written as a
  default and then approved by him.
- **Existing**: already in sections 2 to 4 before M1.2; numbered here so it has a test.

### 12.1 Guiding principle

Dacite is a solid foundation layer for distributed applications: shared state
across nodes with predictable, reasonable network behavior. Apps built on it
should not have to fear DoS or starvation in most cases. **Simple policies are
gold.**

**The test for every rule here:** *a misbehaving peer can only slow its own
streams.* It can never starve another peer's streams or flood the client. Every
rule below keeps its state per session (one connection to one peer) or per stream,
and charges what a peer asks for to that peer's own budget.

### 12.2 Rates and `R_max`

*Decided.* Answers plan open question 2.

- **C01 (Decided).** A client has **one shared `R_max`**, split
  by interest across all its open requests, whichever peer serves each one:
  `rate_i = R_max · I_i(t) / max(peak, Σ_j I_j(t))`. Dividing by
  `max(peak, Σ I)` rather than `Σ I` means that **when total interest is below
  `peak`, part of `R_max` goes unused**: decayed interest lowers the absolute rate,
  not just the split. That is how decay frees bandwidth (approved by Jonathan, Nov 4).
- **C02 (Decided).** Each peer also caps what it will send (`hello.rates.send-max`).
  A request's rate is `min(interest share, that peer's send max)`. A client MUST
  redistribute the share a capped peer cannot use to its other requests (in
  proportion to their interest, repeating until no uncapped request is over a cap).

*Rejected alternative:* a separate budget per peer. It is simpler, but several peers
together could oversubscribe the client's link, which `R_max` exists to prevent.

**How a responder learns its share** (*Decided*, Jonathan's option A, Nov 4). A
data channel gives the receiver no way to slow the sender, so the responder has to
be told what to send. The client already knows every request's interest (decay is
a function of the request's age), so it can compute each peer's total share, and
it says so in every `get` (`recv-max`, 6.2). There is no separate rate message.

- **C03 (Decided).** A responder MUST NOT send on a session faster than
  `min(its send-max, the session's budget)`. The budget is the `recv-max` of the
  latest `get` on that session, or, before any, `hello.rates.recv-max`. The
  responder splits the session budget among that session's open streams by the
  same formula as C01 (its own connection `peak` is 1000). A client SHOULD set each
  `get`'s `recv-max` to its current C01/C02 share for that peer.
- **C04 (withdrawn).** Was the `rate` session message (type `0x08`). Jonathan chose
  option A (`recv-max` in `get`) on Nov 4, so `0x08` is unassigned and C04 has no
  rule. The id is not reused.
- **C45 (Decided; SHOULD; Jonathan, Nov 4).** A client SHOULD treat the
  `recv-max` it sends a peer as a **commitment** that decays with that peer's
  interest. Under C03 the responder paces the session at
  `recv-max · min(1, ΣI/1000)` (the C01 split with `peak` 1000), so a peer's
  **current commitment** is
  `its last recv-max · min(1, ΣI_peer(t) / 1000)`, where `ΣI_peer(t)` is the
  decayed interest of that peer's open streams. The client can compute it, because
  decay is deterministic (C05). A new `get`'s `recv-max` SHOULD be at most
  `R_max` minus the other peers' current commitments, so the sum of current
  commitments stays at or below `R_max`. This trades the overshoot of a stale
  `recv-max` for a temporary undershoot.

**Known gap: stale `recv-max`.** After client C's share for a peer drops (for
example because another peer became active), that peer keeps sending at the old
`recv-max` until C sends it another `get` or its streams end (they complete, or
interest decays below the floor: at most about 276 s, C10). During the gap the
peer over-sends by at most *old `recv-max` − current share*, and C's total excess
over `R_max` is at most the sum of that, over the peers with stale rates. C goes
over `R_max` for a while, but nothing breaks: **correctness is never affected**,
because every item is still verified (12.8). The cost is congestion: other peers'
streams slow down and C's buffers fill, while WebRTC back-pressure (12.5) still
bounds it. In practice the gap is short, because progressive reveal keeps C
sending `get`s. Accepted for v1 by Jonathan; revisit with the M5.3 measurements. A
session-level rate message is the known fix. **A client that follows C45 removes
the overshoot:** commitments never sum above `R_max`, so the gap becomes a
temporary undershoot instead.

### 12.3 Lifecycle: dwindling interest only

*Decided.*

- **C05 (Decided).** Every response stream's interest decays from the time of its
  request (the responder's receipt of the `get`). The responder MUST end the stream
  when interest falls below the `floor`: the last frame has FIN, and PARTIAL if the
  reveal had more to send (6.3). (The decision words this as "its rate falls below
  the floor"; the floor is a floor on *interest*, which is what decays, and a
  request's rate also depends on the other requests.)
- **C06 (Decided).** There is **no separate idle timeout.** A responder MUST NOT end
  or hold a stream because it is idle. A stalled stream decays away and returns
  its share of `R_max` to the others. Only decay, `cancel`, `error` or the end of
  the session ends a stream.
- **C07 (Decided).** **Cancel is optional.** (The decision calls this "explicit
  release".) It sets the stream's interest to zero; it is a shortcut, and
  correctness never depends on it. On the wire this is `cancel` (section 4.2, 6.5): a responder MUST stop the stream and free its share
  when it gets one, MUST ignore one for a closed stream, and MUST work if one
  never comes. (The `forget` message, 6.4, is a different thing: it names *hashes*
  a client no longer holds, 12.6.)
- **C08 (Decided).** **Renewal is a new request** (a new `get` with a new stream id),
  for the same hash or for a sub hash the client still needs, as progressive
  revelation already works. The responder MUST treat it as independent of the
  stream it renews: a new clock starting at `peak`.
- **Bounded lifetime.** Interest reaches the floor at `t = tau · ln(peak / floor)`,
  so a peer can always compute when it may forget a stream's state. `tau` stays a
  tunable default, to be measured in M5.3 (plan open question 9).
  The spec lets a requester send `floor = 0` (6.2), which has no bound, so:
  - **C09 (Decided).** A responder MUST clamp the requested
    values before using them: `floor` to at least **10** (0.010), `tau-ms` to at
    most **60 000**, and `peak` to at most **1000**. Absent values take the
    responder's defaults, which are within these.
  - **C10 (Decided).** After the clamps a stream lives at most
    `tau · ln(peak / floor)`: at most 60 s · ln 100 ≈ **276 s**. A responder MUST
    discard all state for a stream no later than that after its request.
    (A requester that wants longer renews, C08.)
- **A per-peer cap on open streams** bounds the total state a peer can make another
  hold, as C10 bounds each stream's lifetime.
  - **C11 (Decided).** A responder MUST enforce the
    `limits.max-streams` it advertised: a `get` that would exceed it is refused with
    `error overloaded` (reason `too-many-streams`) and not started (section 4). A
    closed or decayed stream frees its slot at once; its id is never reused.
  - **C12 (Decided).** `max-streams` is at most **64** in v1. A
    receiver reads a larger advertised value as 64. A peer SHOULD advertise 32 (the
    example in 6.1). So a session holds at most 64 streams, each at most 276 s old.

### 12.4 Failure: a bad hash ends the session

*Decided.*

- **C13 (Decided).** A verification failure (an item whose
  hash does not match, and every other failure in 3.3) ends the **whole session**
  with that peer: the receiver sends `error` `bad-entry` with `fatal: true` on
  stream 0 and closes. An honest peer never sends one. The original decision named a
  bad hash; this extends it to every verification failure in
  3.3 (bad state, header count or size, bitmap shape, and so on), approved Nov 4.
- **C14 (Decided).** Everything that peer was serving is treated as unserved. The
  requester MUST drop the peer's unchecked headers (3.3) and re-request what it
  still needs from other peers or the origin. Items already verified and stored
  stay: a verified item is correct whoever sent it.
- **C15 (Decided).** Decay is the only lifecycle rule for **streams** (with the
  shortcuts `cancel` and `error`, C06). A **session** ends only on a verification
  failure (C13) or a protocol violation (C16). This is about trust, not interest. Slowness costs a stream (by decay); lying costs the session.
  Neither affects sessions with other peers: ending a session MUST NOT change the
  state of any other session.

*Decided* (approved Nov 4), for the rest of the session-end rules:

- **C16 (Decided).** A **protocol violation** MUST end the
  session in the same way (`error` `malformed`, `fatal: true`, then close). A
  protocol violation is: a malformed frame (3: type `0x00`, under 6 bytes, over
  the size limit, a reserved flag bit), a frame other than `hello` and `error`
  before the peer's `hello`, a second `hello`, and an `item-stream` for a stream id
  this side never opened (a closed id is only ignored, 4). A *request* that fails
  (`unknown-hash`, `unsupported-policy`, `too-large`, `rate-limited`, `overloaded`,
  an unknown message type) ends that stream only, never the session.

### 12.5 Flow control (`bufferedAmount`)

*Decided* (Jonathan approved the defaults, Nov 4). Thresholds are in whole frames (3.1), of the
negotiated `max-frame`, per data channel (and per pipe, in single-pipe mode).

| Setting | Default | At 16 KiB frames |
|---|---|---|
| high water mark | 4 frames | 64 KiB |
| low (`bufferedAmountLowThreshold`) | 1 frame | 16 KiB |

- **C17 (Decided).** A sender MUST NOT write a frame to a
  channel whose `bufferedAmount` is at or above the high mark, and MUST resume when
  the buffer falls to the low mark (the `bufferedamountlow` event). So at most
  5 frames are ever queued on a channel, and at most 64 streams × 5 frames
  (about 5 MiB) per session. `cancel`, `error`, `ack`, `forget` and `hello`
  are one-frame control messages on their own channel and are not held back by a
  stream channel's buffer.
- **How back-pressure meets `R_max`.** `R_max` and the shares decide *when a stream
  may write*; the buffer only decides whether it *can*. **C18 (Decided; SHOULD).** A stream blocked by C17 SHOULD take no share while blocked:
  its share goes to the other streams of the session, as a capped peer's does (C02).
  Its interest keeps decaying (C05), so a reader that never drains loses its stream.
  It SHOULD NOT bank more than one frame of credit while blocked, so a stream does not
  catch up with a burst when it unblocks. A slow reader therefore holds back only
  its own channel (one channel per `get`, section 5); in single-pipe mode it holds
  back only its own session.

### 12.6 Sent-hash record, `ack` and `forget`

*Decided.*

- **C19 (Decided).** The sender keeps a **per-session record** of hashes it has
  already sent, so it need not send them again in a later response stream. It is
  **strictly a performance optimization, never a correctness requirement**. It is
  bounded, and the sender may forget any part of it at any time. A sender MUST
  complete every request correctly with an empty or a wrong record.
- **C20 (Decided).** The **root** of a `get` MUST always be sent,
  whatever the record says. So a requester that is missing a hash, for any reason,
  gets it by asking for that hash; no record can starve a re-request.
- **C21 (Decided).** The record holds at most **16 384** hashes
  per session; the sender forgets the oldest first when full. A sender MUST remove
  every hash named in a `forget` from the record.
- **C22 (Decided).** The client always verifies what it receives (section 3.3, 12.8)
  and may re-request any hash it is missing, so a forgotten or wrong record costs
  bandwidth only.
- **C23 (Decided).** `ack` is a **hint**. Sending one is
  optional; a sender MAY ignore it, MUST NOT wait for one, and MUST NOT need one
  to finish a stream. An `ack` or `forget` for hashes the receiver does not know is
  ignored, not an error (N10).

How `ack` and `forget` fit (this closes the wire gap of N7/N8): `ack` says *I
stored these* (the sender may keep them in the record longest, or ignore it);
`forget` says *I do not hold these any more* (reset, evicted) so the sender drops
them from the record and will send them again. Both stay session-wide on stream 0
(section 9, decision 7). A client SHOULD send `forget` when it evicts pages it was
sent in the same session, and MAY send `ack` in batches of up to 256.

### 12.7 Reveal order: deterministic in v1

*Decided* (Jonathan, Nov 4): v1 reveals in a fixed order. The stochastic shuffle is
**Deferred** until peering works in a demo (below).

- **C24 (Decided).** A sender reveals child hashes **top-level first, breadth-first
  by level**: everything it sends of level *d* (the asked node is level 0, its
  children are level 1, and so on) comes before anything of level *d + 1*.
- **C25 (Decided).** Within a level the order is **fixed, with no shuffling**: the
  default policy's order (3.2 and the design note): a sequence's pages left to
  right (first-most), a map's or set's entries in key-hash order, keys before
  values. The same request on the same store reveals in the same order. A
  receiver MUST NOT rely on the order within a level anyway (it verifies each item
  on its own, 3.3, so none is needed); the order has no wire field and no effect
  on a hash.
- **C26 (Decided).** A requester may repeatedly show interest
  in the same top-level hash (C08); **each renewal MAY reveal nodes not revealed
  before**. A responder MUST serve a renewal correctly with **no record
  at all** (C19): it walks from the requested hash and its root is always sent
  (C20). The sent-hash record is only an optimization: a responder MAY use it to
  skip nodes it already sent and spend the stream's budget on nodes below them,
  but no rule depends on it. The literal rule (3.2) applies at the node where the
  sender chooses it.

**Deferred until after a working peering demo** (Jonathan, Nov 4; no rule):

- **Stochastic order.** Shuffling the order within a level, uniformly at random
  per stream, so that no single subtree hogs a stream. Its planned test
  (`order-within-a-level-varies-with-seed`) is deferred with it.
- **The carve-out** that went with it: a sequence's own leaf pages would stay left
  to right even when shuffling, so a progressive read of a book does not start
  anywhere. It is moot while the order is fixed.

**Deferred idea: a bloom filter as the sent-hash record** (no rule). A sender could
keep its record (C19, C21) as a bloom filter instead of a hash set, which is far
smaller. That is safe because the record is only an optimization (C19, C26): a
false positive makes the sender skip a node the client lacks, and the client just
asks for it again (C20, C22). Revisit with M5.3 measurements.

### 12.8 Verification before store

*Decided* (approved Nov 4) as numbered MUSTs. Their content is section 3.3,
which M1.1 had already written down; M1.2 only numbers it. The check for each
kind of item is in the table there.

- **C27 (Decided).** A receiver MUST verify every item from its
  own bytes (and what earlier items already put in the store) **before** it stores
  it. No other item is ever in the store. (The one exception, a collection header,
  C31, is tracked as unchecked.)
- **C28 (Decided).** A literal is rebuilt from its bytes in a
  scratch store and its value hash MUST equal the item's. A literal that holds a
  `ref` (a `ref` item, or a `run` or `repeat` of `ref`) is `bad-item`.
- **C29 (Decided).** A node item MUST be checked by kind: a
  scalar's hash from its data; a leaf page's hash from its claimed measure, and the
  measure recomputed from its leaves; a `hamt/entry`'s hash, measure and
  `key-hash = key-ref`; a branch page's hash from its claimed measure, and a
  `hamt/bitmap` also its shape (`split` 0..51, two or more children, `n =
  popcount`, and at `split` 51 exactly bits 0 and 16).
- **C30 (Decided).** Every state in a measure MUST have four cells
  below `p` and determinant 1; otherwise the item is bad (`bad-state`, sent as
  `bad-entry`).
- **C31 (Decided).** A collection header is stored on arrival and
  tracked as unchecked; when its root is stored the receiver MUST check its hash,
  then its `count` and `size-bytes`, and a failure removes that header and ends the
  session (C13). Unchecked headers are dropped when the stream ends without their
  roots (fatal error, `cancel`, PARTIAL); a FIN without PARTIAL and without the root
  is `bad-entry`.
- **C32 (Decided).** Size limits are checked **before** the
  payload is buffered: a frame over the negotiated `max-frame`, an item over
  `max-item` (`item-too-large`), a literal over one frame payload
  (`literal-too-large`), an unknown `enc` (`bad-item`).
- **C33 (Decided).** No peer holds a partial value in order to
  verify. An item that cannot be verified alone is stored and checked later (C31);
  it is never parked. A receiver accepts items in any order within a level (C25).

### 12.9 Read-only

*Decided* (Jonathan approved, Nov 4). Section 1 already says peers are read-only. As
rules:

- **C34 (Decided).** The message set is closed and has no write.
  A peer MUST NOT change its store because of any message except by storing items
  that passed C27 and answer a `get` it sent. There is no `put`, no root update and
  no CAS (those stay with the origin).
- **C35 (Decided).** A requester MUST store an item only from an
  `item-stream` on a stream it opened with a `get` and that is still open. Frames
  for a closed id are ignored; for an id it never opened, C16 applies.
- **C36 (Decided).** A responder answers **from its own store
  only.** It MUST NOT fetch a hash from another peer or the origin on a requester's
  behalf; a hash it does not hold is `error unknown-hash`. (So a requester cannot
  make a peer a relay or an amplifier.)

### 12.10 Stream ids are not the hash

*Existing* (section 4, fixes N1), confirmed here as MUSTs with tests.

- **C37 (Existing).** A stream id MUST NOT derive from the request's contents. The
  requester takes it from its own counter (odd or even by who offered), once per
  connection, never reused.
- **C38 (Existing).** A responder MUST key its stream table by `(session, stream
  id)`. Two requests for one hash are two independent streams.
- **C39 (Existing).** A responder MUST refuse a request whose id has the wrong parity
  (`bad-stream-parity`) or is already used (`duplicate-stream`), with `error
  malformed`, and not start it.

### 12.11 Request rate limits (token buckets)

*Decided* (Jonathan approved 20/s, burst 40, Nov 4). `hello.rates` already carries `req-rate` and
`req-burst` (6.1); this gives them their meaning. Defaults: a peer SHOULD advertise
`req-rate` **20** requests a second and `req-burst` **40**. The alternative is
**50** a second with a burst of **100**, since progressive reveal renews with many
sub-hash `get`s and 20 may slow an honest client deep in a tree; M5.3 measures
which one to keep.

- **C40 (Decided).** A responder MUST keep one token bucket per
  session, filled at `req-rate` a second up to `req-burst`. Each `get` costs one
  token, whether answered or refused. With none left it answers `error
  rate-limited` with `retry-ms` (the time to the next token), does not start the
  stream, and the session goes on. A peer that floods only gets more
  `rate-limited` errors; a flood never ends the session (C15).
- **C41 (Decided; SHOULD).** A requester SHOULD keep a bucket
  of the same size for each peer, from the peer's `hello`, and SHOULD NOT send a
  `get` when it is empty (the responder's bucket, C40, already protects it). (It prefers to renew a stream it already has, C08, when tokens run
  low.)
- **C42 (Decided).** A bucket belongs to one session. No bucket,
  budget, record or clock is shared between sessions, except the client's own
  `R_max` (C01).

Only `get` is metered in v1. `cancel`, `ack` and `forget` are small and
cost the sender their own bandwidth; whether a flood of them needs a bucket is a
question for M3.2, when a real transport shows what they cost.

### 12.12 Existing rules, numbered

- **C43 (Existing).** A `session` type MUST be on stream 0 and a `request` type on
  stream 1 or more; sender and receiver both enforce it (`wrong-stream`, section 3).
- **C44 (Existing).** A receiver MUST reject a control message that uses a literal
  type outside the v1 profile (`profile`, 2.1).

### 12.13 Deferred for M1.2

Plan open questions 1, 3 and 4 are **deferred** for the M1.2 acceptance. Each gets a
reason, a date and a v1 default, so no MUST depends on it. *Decided:* Jonathan approved these deferrals on Nov 4.

- **Question 1, peer lists (does the origin suggest peers by root, or by network
  locality?). Deferred to M6.1.** Reason: peer discovery happens before a session
  exists and has no message in peer v1; the protocol here starts at `hello`. The
  v1 default is locality only (plan M6.1), which needs no per-root knowledge.
- **Question 3, privacy (does asking a peer for a hash reveal what the client
  reads?). Deferred to before M8.1 (the public demo).** Reason: it is a deployment
  policy (which peers a client dials), not a wire rule, and v1 assumes good actors
  (section 11). v1 states the fact: **a peer learns every hash it is asked for**,
  and a probing `get` (6.2) is an ordinary request. Clients connect only to peers
  the origin or an admin names (M6, M7); they do not dial strangers.
- **Question 4, access control (may a peer serve any hash it holds, or only to
  clients authorized for that root?). Deferred.** Reason: it depends on the
  authorization model (`docs/authorization.allium`) and on who the origin is (plan
  open question 7), and both are decided later. The v1 default is the plan's
  non-goal: a peer serves any hash it holds, and a hash is a 256-bit name nobody
  can guess, so knowing it is the only capability. Nothing here prevents a later,
  stricter rule: a responder may answer `unknown-hash` for a hash it holds.

Not in scope here, and tracked elsewhere: the number of sessions a responder accepts
(M6.3, "cap concurrent peers"); the aggregate of every session's `send-max` against
the responder's own uplink.

### 12.14 Where this differs from earlier text

- **`cancel` and `forget`, not "release".** The decision's stream-level
  "explicit release" (set interest to zero) is `cancel` on the wire (C07). The
  session message 0x05 that names hashes the client no longer holds (6.4) was
  called `release` in M1.1; M1.2 renames it `forget` (and the design note's
  `release-pages` to `forget-pages`, the fixture `release` to `forget`) so the word
  "release" no longer means two things. The type id 0x05 is unchanged; the wire is still a draft, so there is no version bump.
- **C13 covers every verification failure,** not only a bad hash (approved Nov 4).
- **C01's formula and C26's wording** go beyond the first decision text; Jonathan
  approved both on Nov 4 (12.2, 12.7).
- **C18 and C41 are SHOULDs:** tuning details, not interoperability rules.
- **No `rate` message.** M1.2's draft proposed one (`0x08`, C04); Jonathan chose `recv-max` in `get` instead (C03), with the stale-rate gap accepted (12.2).
- **C40 never ends a session;** C15 now names the only session-ending rules
  (C13, C16).
- **Offers do not wait for an ack.** The design note says a page offered and not
  acknowledged stays with its offer until `ack-pages` or `forget-pages`. With C19
  and C23 nothing waits: the record is an optimization. M4.2 changes the reveal engine.
- **No idle timeouts.** The M1.2 plan entry listed them; the decision removes them
  (C06).
- **`floor = 0`.** 6.2 lets a requester send it, the design note's placeholder
  server uses it. The responder now clamps it (C09).
- **Order of a reveal.** v1 keeps the design note's deterministic defaults
  (first-most, keys then values) within each level, now level by level (C24, C25).
  The stochastic shuffle in the first M1.2 draft is deferred (12.7).

## 13. Conformance list and test map

Ids run **C01** to **C45**; **C04 is withdrawn** (the `rate` message), so there are
**44** rules: **41** MUST statements (**36** Decided, **5** Existing, sections 2
to 4) and **3** SHOULDs (C18, C41, C45, all Decided), listed in their own table
below. In all, 39 Decided, 0 Proposed and 5 Existing. **Deferred, no id:** the stochastic shuffle and its carve-out (12.7),
whose test `order-within-a-level-varies-with-seed` waits for a working peering
demo, and a bloom-filter sent-hash record. A test name
with "(exists)" is in the repo today; the others are planned. Suites: **JVM**
(`clojure -M:dev:test`), **nbb peer** (`npm run peer:test`, the portable core on
nbb), **browser** (the same portable tests in a browser, M5.1 and M5.2). "Lands in"
is the plan milestone that builds the test. The M1.2 spec PR (#21) wrote no tests.
**M1.2 slice 1** (verify before store, the session-end rule, re-asking) wrote the
ones marked "(exists, slice 1)": portable checks in
`impl/clojure/test/dacite/peer/client_checks.cljc`, run on the JVM by `client_test.clj`
(one `deftest` each) and on nbb by `npm run peer:test`, against the portable
`dacite.peer.verify` and `dacite.peer.client`, with simulated peers and no clock.
**M1.2 slice 2** (rate split, flow control, token buckets, the read-only rule, protocol
violations) wrote the ones marked "(exists, slice 2)": portable checks in
`impl/clojure/test/dacite/peer/flow_checks.cljc`, run on the JVM by `flow_test.clj` and
on nbb by `npm run peer:test`, against `dacite.peer.rates`, `bucket`, `responder`
and the changed `client`, on simulated clocks. The rows for the other ids (C05 to C12,
the lifecycle and the stream cap, and the rest) stay planned.

| Id | Status | MUST (short) | Planned test | Suite | Lands in |
|---|---|---|---|---|---|
| C01 | Decided | One shared R_max split by interest; R_max·I/max(peak, ΣI) leaves R_max unused at low interest | `shared-rmax-splits-by-interest-across-peers` (exists, slice 2: `rates/split`, and the client counts a new stream at peak) | JVM, nbb peer | M1.2 s2 (exists) |
| C02 | Decided | Rate = min(share, peer send-max); unused share redistributed | `capped-peer-share-is-redistributed` (property: sum <= R_max, each <= cap) (exists, slice 2: a hand case and a 300-case property over generated peers) | JVM, nbb peer | M1.2 s2 (exists) |
| C03 | Decided | Responder paces to min(send-max, latest get recv-max); splits by interest | `responder-paces-to-session-budget` (exists, slice 2: simulated clock; budget, `send-max` cap and a 2:1 split) | JVM, nbb peer | M1.2 s2 (exists) |
| C05 | Decided | Stream ends (FIN, PARTIAL) when interest < floor | `stream-ends-partial-when-interest-falls-below-floor` (session clock) | JVM, nbb peer | M4.1 |
| C06 | Decided | No idle timeout; a stalled stream only decays | `stalled-stream-decays-and-frees-its-share` | JVM | M4.1 |
| C07 | Decided | `cancel` is optional; sets interest to 0; correct without it | `cancel-stops-and-frees-share-and-is-never-required` | JVM, nbb peer | M4.1 |
| C08 | Decided | Renewal is a new get, a new clock at peak | `renewal-is-a-new-stream-at-peak` | JVM | M4.1 |
| C09 | Decided | Clamp floor >= 10, tau-ms <= 60 000, peak <= 1000 | `get-interest-is-clamped-by-the-responder` | JVM, nbb peer | M3.1 |
| C10 | Decided | Stream state gone within tau*ln(peak/floor), at most ~276 s | `stream-state-is-forgotten-by-its-lifetime-bound` (simulated clock, floor 0 request) | JVM | M4.2 |
| C11 | Decided | Enforce advertised max-streams; slot frees at once | `too-many-streams-is-refused-and-a-closed-slot-frees` (parity half: `f3-stream-table-parity-and-duplicates`, exists) | JVM, nbb peer | M3.1 |
| C12 | Decided | max-streams capped at 64 (read larger as 64) | `max-streams-above-64-is-read-as-64` | JVM, nbb peer | M3.1 |
| C13 | Decided | Any verification failure (not only a bad hash) ends the whole session (fatal bad-entry) | `bad-entry-ends-the-session` (exists, slice 1: every way of lying, JVM and nbb; item level: `tampering-is-refused`, exists) | JVM, nbb peer (exist); browser | M1.2 s1; browser M5.1 |
| C14 | Decided | A bad peer's streams are unserved and re-requested elsewhere | `bad-peer-streams-are-reasked-from-others` (exists, slice 1: corrupt, lost, without-the-value and honest simulated peers; the slow peer waits for decay, M4.1) | JVM, nbb peer | M1.2 s1 (exists) |
| C15 | Decided | Decay is the only stream lifecycle rule; sessions end only on C13/C16; others untouched | `one-session-ending-leaves-others-untouched` (exists, slice 1: a failure on one session; the decay half waits for M4.1) | JVM, nbb peer | M1.2 s1 (exists) |
| C16 | Decided | Protocol violation ends the session; request errors end only the stream | `protocol-violation-ends-session-request-error-ends-stream` (exists, slice 2: client and responder, four violations each, and request errors) | JVM, nbb peer | M1.2 s2 (exists) |
| C17 | Decided | No write at >= 4 frames buffered; resume at 1 frame | `channel-never-buffers-over-high-mark-and-resumes-at-low` (fake channel; real channel in M3.2) (exists, slice 2: fake channel with hysteresis; the real channel is M3.2) | JVM, nbb peer, browser | M1.2 s2 (exists) |
| C19 | Decided | Sent-hash record: bounded, optional; correct when empty or wrong | `empty-and-wrong-record-still-complete-every-request` (property) | JVM | M4.2 |
| C20 | Decided | The root of a get is always sent | `root-of-a-get-is-sent-even-if-recorded` | JVM, nbb peer | M4.2 |
| C21 | Decided | Record capped at 16 384 (oldest out); `forget` removes hashes | `record-is-capped-and-forget-removes-hashes` | JVM | M4.2 |
| C22 | Decided | Client verifies everything; may re-request a missing hash | `forgotten-hash-is-re-requested-and-verified` | nbb peer | M4.2 |
| C23 | Decided | `ack` is a hint; never awaited or required; unknown hashes ignored | `ack-is-optional-and-unknown-hashes-are-ignored` (N10) | JVM, nbb peer | M4.2 |
| C24 | Decided | Reveal is breadth-first by level, top-level first | `reveal-sends-level-d-before-level-d-plus-1` | JVM, nbb peer | M2.2 |
| C25 | Decided | Fixed order within a level (no shuffle); receiver never relies on it | `order-within-a-level-is-fixed-and-receiver-accepts-any` | JVM, nbb peer | M2.2 |
| C26 | Decided | A renewal may reveal more; correct with no record (record is only an optimization) | `renewal-is-correct-with-no-record-and-may-reveal-more` | JVM, nbb peer | M4.2 |
| C27 | Decided | Verify every item from its own bytes before it is stored | `every-item-stream-fixture-verifies` and `tampering-is-refused` (exist); `nothing-unverified-reaches-the-store` (exists, slice 1) | JVM, nbb peer; browser after M5.1 | M1.2 s1 (exists) |
| C28 | Decided | Literal rebuilt in a scratch store; a `ref` inside is bad-item | `a-run-of-refs-is-a-ref` (exists); `literal-hash-mismatch-is-refused` (exists, slice 1) | JVM, nbb peer | M1.2 s1 (exists) |
| C29 | Decided | Per-kind checks: scalar, leaf page, hamt/entry, branch, hamt/bitmap shape | `tampering-is-refused` (exists); `hamt-bitmap-shape-refusals` (exists, slice 1) | JVM, nbb peer | M1.2 s1 (exists) |
| C30 | Decided | Every state: cells < p and determinant 1, else bad-state | `bad-state-is-refused-as-bad-entry` (exists, slice 1; fixtures `fuse-sl2`) | JVM, nbb peer | M1.2 s1 (exists) |
| C31 | Decided | Header stored on arrival, checked at its root, dropped if unchecked | `a-header-is-accepted-first-and-checked-when-its-root-arrives`, `a-stream-that-ends-early-leaves-no-unchecked-header` (exist); `a-session-whose-stream-ends-early-leaves-no-unchecked-header` (exists, slice 1: PARTIAL drops it, FIN without the root ends the session) | JVM, nbb peer | M1.2 s1 (exists) |
| C32 | Decided | Size limits checked before buffering the payload | `every-fixture-decodes-and-re-encodes` (reject cases, exists); `item-over-max-item-is-refused-before-buffering` | JVM, nbb peer | M3.1 |
| C33 | Decided | Nothing parked; items verify in any order | `pages-verify-in-any-order` (exists); `every-page-verifies-in-any-order` (exists, slice 1) | JVM, nbb peer | M1.2 s1 (exists) |
| C34 | Decided | No write message; the store changes only by verified own-request items | `no-message-changes-the-store-except-verified-items` (exists, slice 2, responder side; client side is `nothing-unverified-reaches-the-store`) | JVM, nbb peer | M1.2 s2 (exists) |
| C35 | Decided | Store items only from an open stream this side opened | `item-on-a-stream-never-opened-ends-the-session`, `item-on-a-closed-stream-is-ignored` (both exist, slice 2) | JVM, nbb peer, browser | M1.2 s2 (exists) |
| C36 | Decided | Answer from own store only; no fetch on behalf; unknown-hash | `responder-never-fetches-on-behalf` (exists, slice 2) | JVM, nbb peer | M1.2 s2 (exists) |
| C37 | Existing | A stream id is never derived from the hash | `stream-id-is-not-the-hash` (exists); `client-stream-ids-are-its-own-counter` (exists, slice 1) | JVM, nbb peer | M1.2 (exists) |
| C38 | Existing | Responder keys streams by (session, id); same hash twice = two streams | `same-hash-twice-is-two-independent-streams` (N1 regression) | JVM, nbb peer | M4.1 |
| C39 | Existing | Wrong parity or duplicate id refused (malformed) | `f3-stream-table-parity-and-duplicates` (exists) | JVM | M1.2 (exists) |
| C40 | Decided | Per-session token bucket on `get`; empty = rate-limited; a flood never ends the session | `get-over-the-bucket-is-rate-limited-and-session-lives` (simulated clock) (exists, slice 2: simulated clock; burst lowered to 10 so nbb stays quick) | JVM, nbb peer | M1.2 s2 (exists) |
| C42 | Decided | Buckets, budgets, records, clocks are per session, never shared | `sessions-share-no-state-except-rmax` (flood one session; others unchanged) | JVM, nbb peer | M6.3 |
| C43 | Existing | Session types on stream 0, request types on stream >= 1 | `f3-message-types-and-streams` (exists; fixtures `reject-*-on-stream-*`) | JVM, nbb peer | M1.2 (exists) |
| C44 | Existing | Literal types outside the v1 profile are rejected | `outside-the-profile` (exists) | JVM, nbb peer | M1.2 (exists) |

**SHOULDs** (tuning details; tested, but not interoperability rules):

| Id | Status | SHOULD (short) | Planned test | Suite | Lands in |
|---|---|---|---|---|---|
| C18 | Decided | A blocked stream takes no share, banks <= 1 frame, keeps decaying | `blocked-stream-takes-no-share-and-banks-one-frame` (exists, slice 2: the share, the one-frame bank and the decay) | JVM, nbb peer | M1.2 s2 (exists) |
| C41 | Decided | Requester keeps its own bucket per peer, never sends on empty | `requester-bucket-never-overspends` (exists, slice 2) | JVM, nbb peer | M1.2 s2 (exists) |
| C45 | Decided | Commitment = last recv-max × min(1, ΣI_peer/1000); new recv-max <= R_max − others' current commitments | `committed-rate-never-exceeds-rmax` (sum of current commitments <= R_max over a simulated clock) (exists, slice 2: client with 3 peers over a simulated minute) | JVM, nbb peer | M1.2 s2 (exists) |
