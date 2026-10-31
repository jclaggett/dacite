# Dacite peer protocol v1

**Status:** DRAFT (M1.1: messages and framing). Not yet signed off.
**Related:** [wire-v1.md](wire-v1.md) (the byte format this protocol reuses),
[peering design note](../design/peering-design-note.md),
[streaming design note](../design/streaming-design-note.md),
[the plan](../plan/peering.md) (M1.1, M1.2).
**Code:** `dacite.peer.frame` (envelope, packing), `dacite.peer.msg` (messages),
both portable `.cljc`; `dacite.peer.items` (item streams: the literal rule and
verification, JVM `.clj` for now). **Fixtures:** [`fixtures/peer-v1/`](../../fixtures/peer-v1/).

**Not in this draft (M1.2):** the numbered MUST list, rate sharing with the
client's `R_max` and token buckets, flow control (`bufferedAmount`), idle
timeouts, the exact session-end rules. Where this draft touches them, it
says "M1.2".

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
- A blob, if used, is only a map value. (A core limitation, not a protocol one:
  a vector holding a blob and another element does not realize in the reference
  core today. See "Known issues".)
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
| `0x05` | `release` | `Lit` (map) | requester → responder | 0 (session) |
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
malformed frame: close the channel (M1.2 makes this a session error).

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
- the `bufferedAmount` thresholds of M1.2 can be set in whole frames.

`hello.limits.max-frame` lets a peer ask for smaller frames (1024..16384). It
can never raise the limit. The effective size is the smaller of the two.

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
  are about 1 KiB, so this is rare: it happens for a page a store built in one
  piece. Fixture `item-stream-mixed` has one, 39 753 bytes.)
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
leaf pages are literals (the largest 1 076 bytes). `item-stream-one-frame` is a
small vector sent as one 678-byte literal. `item-stream-mixed` is a 1200-element
vector: node items and literals together.

**Node items (decision Q3).** A sender SHOULD keep each node item within one frame
payload, once M1.1a makes every page about 1 KiB. Until then a store that builds a
page in one piece (Known issues) can make a node item span frames, and a receiver
reads those (up to `max-item`, 1 MiB). Receivers keep the 1 MiB `max-item` and do
not refuse a node item for its size. The limit follows: the smallest `max-frame` a
peer may advertise (`frame/min-max-frame`, 1024 today) must be at least the largest
node item plus the 37-byte item header and the 6-byte envelope, so M1.1a raises it
with the page budget. Until then a peer at the minimum frame size may see node
items that span frames.

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
`dacite.peer.items/verify-item!` is the check; `accept-item!` stores each item as it
is verified, in arrival order. (The older `dacite.service.reveal/accept-items!`
does hold a collection header until its root arrives. It is not the peer rule, and
M2.2 replaces it.)

What each kind of item is checked against:

| Item | Verified from |
|---|---|
| literal | Rebuilt from its bytes in a scratch store: its value hash must equal the item's. Complete, so nothing is left to check. A literal holds no `ref`: a `ref` item, or a `run` or `repeat` of `ref`, is `bad-item`. |
| scalar | Its hash from its data. |
| leaf page: `ft/digit` or `ft/node` with every leaf inline (no `ref`) | Its hash, from the claimed measure (`node-hash(type, elements-fuse)`); and the claimed measure must equal the one recomputed from the leaves in its body. A leaf page **verifies alone**. |
| `hamt/entry` | Its hash from the claimed measure, and `elements-fuse = fuse(key-ref, val-ref)`. |
| branch page: `ft/deep`, `hamt/bitmap`, any page with a `ref` (a `ref` item, or a `run` of `ref`) | Its hash, from its claimed measure (local). **Not** that the measure follows from its children: the page holds their hashes, not their measures. Branch nodes are not bound to their children in v1 (section 11, Q1). |
| collection header (`string`, `blob`, `vector`, `map`, `set`) | **Accepted and stored on arrival; checked when its root arrives.** See below. |

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
(`bad-entry`). A failed check removes the header from the store and ends the
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
M1.2 writes those rules down and M2.2 moves the check into the portable core;
today verification is JVM-only. A lookup that finds nothing is `bad-entry`, never a
raw store error.

**Trust.** The hash of a value commits to its ordered elements and not to the
tree that stores them (that is what makes equal values share storage whatever
their chunking). So no check from an item's own bytes can bind a branch node to its
children in v1: see Q1.

## 4. Streams and stream ids

A **stream** is one request and its answer. The requester picks the stream id.

- **Stream 0** is the session: `hello`, `ack`, `release`, and fatal errors.
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
an optimization, not a correctness need.)

## 5. Transport mapping (WebRTC; informative until M3.2)

This section is **informative**. Its "must" and "is expected" are the design
intent; none of it is a conformance rule until M3.2 builds the transport and
M1.2 writes the numbered MUST list. Sections 2, 3, 4 and 6 are the normative
part of this draft.

Reliable, ordered data channels, SCTP over DTLS.

- **Control channel:** label `dacite/v1/control`, opened by the offerer with
  `negotiated: true, id: 0`, so no side waits for the other. Carries stream 0
  (`hello`, `ack`, `release`).
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
| `limits` | map | yes | `max-frame` (1024..16384), `max-item` (bytes, at least 1), `max-streams` (open requests I accept, at least 1). All required. |
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

**Batch size.** An `ack` or `release` holds at most
`min(256, max-hashes-for(max-frame))` hashes, where
`max-hashes-for(f) = min(256, floor((f - 33 - 320) / 32))`: 32 bytes a hash,
33 bytes of header and framing, and 320 kept for a `release` `reason`. At 16384
that is 256; at 1024 it is 20. `msg/max-hashes-for` is the helper, and a test
encodes a full batch at each frame size.

Negotiation (`msg/negotiate`, tested): the version is the highest in both sets; if
there is none, send `error` `unsupported-version` with `fatal: true` and close.
`max-frame` and `max-item` are the smaller of the two sides'. `max-streams` is per
direction: each side's value limits what the *other* may have open. Capabilities are
the intersection. Rates are advertised ceilings; how they are shared with the
client's `R_max` is M1.2.

### 6.2 `get` (0x02, new stream)

Ask for a value as a reveal. The answer is an item stream (6.3).

| Key | Type | Req | Meaning |
|---|---|---|---|
| `hash` | u256 | yes | The root of what I want. |
| `policy` | string | yes | Reveal policy by name. v1 defines `"default"` only (the engine's default order for each value type). Any other name still decodes; the responder answers `error` `unsupported-policy` on that stream (not fatal). |
| `interest` | map | no | `peak`, `tau-ms`, `floor`, all required if present: `I(t) = peak · exp(−t / tau)` with `peak` and `floor` in thousandths. `peak` and `tau-ms` are at least 1 (the reveal engine needs `tau > 0`); `floor` may be 0. A `floor` above `peak` is legal and pauses the reveal at once. Absent: the responder's defaults. The responder may clamp any value to its own limits. |

```clojure
{"hash" h1 "policy" "default"
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
21 00000002                    ; map, 2 pairs
10 00000006 706f6c696379       ; "policy"
10 00000007 64656661756c74     ; "default"
10 00000004 68617368           ; "hash"
0d 0000000000000001 0000000000000002 0000000000000003 00000000000000aa
```

Answers: `item-stream` frames, or `error` `unknown-hash` (the responder does not
hold the hash; non-fatal, the stream ends), `unsupported-policy`, `rate-limited`,
`overloaded`, `too-large`.
Fixtures: `get-default`, `get-interest`, `get-probe`, `get-repeat`.

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
(one literal item, 684 bytes), `item-stream-mixed` (4 frames, 40 164 bytes;
node items and literals, one item spans three frames), `item-stream-partial`
(FIN + PARTIAL), `item-stream-literal-at-cap` and `item-stream-literal-over-cap`
(3.2). Their `message.edn` lists every item's hash, size and encoding, and the JVM
test accepts each stream through `items/accept-items!`.

### 6.4 `ack` (0x04) and `release` (0x05), stream 0

These are the explicit client messages the reveal engine needed (N7, N8). They
are session-wide: they name hashes, not streams, matching `ack-pages` and
`release-pages`.

```clojure
;; ack: I stored these pages; you may stop tracking them as offered
{"hashes" [h1 h2]}
;; release: I do not hold these (reset, evicted); send them again if asked
{"hashes" [h3] "reason" "evicted"}
```

1 to 256 hashes each. `reason` is free text. Fixtures: `ack`, `release`.
When a peer must send them, and what the responder may forget, is M1.2.

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
   with `have?`; `ack` and `release` keep their 256.)
5. **Literal items in an item stream: yes, as the serializer's rule.** An item is a
   node or a literal; the shorter wins; a literal is at most one frame payload, so
   it never spans frames; a larger value falls back to node items. See 3.2.
6. **`have?` and `have` are removed from v1** (code, spec, fixtures, tests and the
   `have` capability, and `limits.max-have`). A `get` with the smallest interest
   covers it (6.2). A bitmap `have?` is a possible later optimization only.
7. **`ack` and `release` stay session-wide, on stream 0.**
8. **PARTIAL on FIN** marks a paused or exhausted reveal. No separate message.
9. **`cancel` stays,** though interest decays like a lease.
10. **Channels:** one control channel, plus one channel per `get`, capped by
    `max-streams`. The single multiplexed pipe mode stays (section 5).

Also decided with these: every item is verified from its own bytes, and nothing
waits or is held (3.3). The three questions that followed (branch nodes not
bound to their children, headers, node items over a frame) are answered in
section 11.

## 10. Known issues

- The reference core cannot realize a vector that holds a blob and another
  element: `(dacite.peer.msg/encode-value [(byte-array 1) nil])` throws
  "cannot intern page-body lit item" (`dacite.value.lit/intern-lit-item!`).
  Not a protocol issue: no v1 message holds a blob, and the profile allows one
  only as a map value. Tracked in
  [jclaggett/dacite#8](https://github.com/jclaggett/dacite/issues/8); the plan
  schedules the fix (M1.1a). When it is fixed, drop this entry and the
  map-value-only rule in 2.1.
- Item verification is JVM-only (`dacite.peer.items`) until M2.2, so the nbb test
  checks framing, packing and item decoding, not entry hashes.
- `lit/item-payload-bytes` counts a `run` or `repeat` of strings or blobs as 0
  bytes, so a store can build a leaf page far over its 1 KiB budget (the 39 753-byte
  page in `item-stream-mixed`). The cap in 3.2 holds for literals regardless;
  such a page can only be sent as a node item that spans frames. Plan: M1.1a.

## 11. Trust, and what v1 leaves for later

These were the three open questions of the S3.3 work. Jonathan has decided them.

1. **Branch nodes are not bound to their children (Q1). v1 assumes good actors and
   makes no wire or hash change.** An item is verified from its own bytes, but a
   branch node (`ft/deep`, `hamt/bitmap`, any page with a `ref`) holds its
   children's hashes, not their measures, and its own hash covers only `(type,
   elements-fuse)`, not the child hashes. A peer that works to deceive can send a
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
     +48 bytes per child ref). A branch then verifies from its own bytes like a
     leaf page. It changes the bytes of branch pages and HAMT bitmaps (a wire-v1
     entry-format change; no value hash changes). Measured on the reference
     serializer: about +1.5% of a whole string, blob or vector of strings, about
     +5% for a vector of ints, and about +51% for a map of 10 000 int keys, where
     a bitmap has about 5 refs and each points at a 56-byte entry. Fuse-only
     (+32 B) is enough for content integrity but leaves count and size
     unauthenticated; count and size alone (+16 B) is not sound.
   - **Fold the child hashes into the node hash.** It makes a value's hash depend
     on how it is chunked, so equal values stop deduplicating across stores and
     hosts, and it changes every existing hash. A different system, not a v1
     tweak. Not planned.

   *The fuse is not a cryptographic hash.* It is a homomorphic monoid hash (a 4x4
   triangular matrix product mod 2^64). Its resistance to an adversary needs its
   own review before peers are untrusted.
2. **Collection headers (Q2): header first, no new field, checked when the root
   arrives.** See 3.3. The review's alternative, an `elements-fuse` field in the
   header, is not taken; neither is the pages-before-header order of the first
   draft, which delayed `count` and `size-bytes` and the start of a read.
3. **Node items over a frame (Q3):** a sender-side SHOULD cap of one payload after
   M1.1a; receivers keep the 1 MiB `max-item`. See 3.2.
