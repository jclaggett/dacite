# Dacite peer protocol v1

**Status:** DRAFT (M1.1: messages and framing). Not yet signed off.
**Related:** [wire-v1.md](wire-v1.md) (the byte format this protocol reuses),
[peering design note](../design/peering-design-note.md),
[streaming design note](../design/streaming-design-note.md),
[the plan](../plan/peering.md) (M1.1, M1.2).
**Code:** `dacite.peer.frame` (envelope, chunking), `dacite.peer.msg` (messages),
both portable `.cljc`. **Fixtures:** [`fixtures/peer-v1/`](../../fixtures/peer-v1/).

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
| `have?` | `HEAD` | Do you hold these hashes? Answer: a bitmap. |
| `get` | `GET` | Send me this value, as a stream of entries. |

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
- Bulk data is not a message. It is a stream of wire-v1 `Item`s, cut into
  frames (section 5).

### 2.1 The v1 value profile

A control message uses only these Dacite types. A receiver MUST reject any
other literal type (reason `profile`).

| Type | Use in messages | Native form (JVM, nbb) |
|---|---|---|
| `null` | not used by the shapes below | `nil` |
| `bool` | flags | boolean |
| `i64` | every number | integer |
| `string` | names, codes, text | string |
| `blob` | the `have` bitmap | `byte[]` / `Uint8Array` |
| `u256` | a hash | `{::u256 "<64 hex>"}` in `dacite.peer.msg` |
| `vector` | ordered lists | vector |
| `set` | capabilities, versions | set |
| `map` | the message, nested records | map with string keys |

Rules:

- **No floats.** `f32`/`f64` are outside the profile, because nbb has one
  number type and cannot tell `1` from `1.0`. Rates are integers (bytes per
  second). Interest is fixed point: `peak` and `floor` in thousandths, `tau` in
  milliseconds.
- **No integer over `i64`**; negative numbers are legal in the profile but no
  v1 shape uses one. Every number field below is a non-negative `i64`, and a
  field marked "at least 1" is rejected at 0 (`invalid-message`).
- **Strings are at most 256 UTF-8 bytes** (not characters), and **a set has at
  most 32 elements**. A receiver rejects more (`invalid-message`). A sender cuts
  free text (`reason`, `message`, `agent`) to fit, so it never sends a frame the
  limit would make a receiver refuse (fixture `reject-string-too-long`).
- A hash is a `u256` literal (type id `0x0D`, 32 raw bytes, the same bytes as
  the `hash` field of a wire-v1 `Item`).
- A blob is used only as a map value. (A core limitation, not a protocol one:
  a vector holding a blob and another element does not realize in the reference
  core today. See "Known issues".)
- **Unknown keys MUST be ignored** by a receiver (so v1 can grow fields).
  **A receiver never rejects a name it does not know:** an unknown capability
  is ignored, an unknown `error` code is read as `internal`, an unknown `hello`
  `kind` is read as `browser`, and an unknown `get` policy is answered with
  `error unsupported-policy` (6.3). (A *sender* writes only names v1 defines, and
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
| `0x02` | `have?` | `Lit` (map) | requester → responder | new id, 1 or more (request) |
| `0x03` | `have` | `Lit` (map) | responder → requester | the `have?`'s id (request) |
| `0x04` | `get` | `Lit` (map) | requester → responder | new id, 1 or more (request) |
| `0x05` | `item-stream` | wire-v1 `Item` bytes | responder → requester | the `get`'s id (request) |
| `0x06` | `ack` | `Lit` (map) | requester → responder | 0 (session) |
| `0x07` | `release` | `Lit` (map) | requester → responder | 0 (session) |
| `0x08` | `cancel` | `Lit` (map) | requester → responder | the request's id (request) |
| `0x09` | `error` | `Lit` (map) | either | the request, or 0 (any) |

**Stream rule.** A `session` type MUST be on stream 0. A `request` type MUST be
on a stream of 1 or more. Sender and receiver both enforce it: a frame that
breaks it is refused with reason `wrong-stream` (`frame.cljc` `stream-rule`;
fixtures `reject-request-on-stream-0`, `reject-have-query-on-stream-0`,
`reject-ack-on-stream-n`, `reject-hello-on-stream-n`, `reject-cancel-on-stream-0`,
`reject-item-stream-on-stream-0`). Whether the id names an open request (a
`have` answers a `have?`, and so on) is session state: see section 4.
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
- a `have?` of 256 hashes (8 KiB of hashes) and every other control message fit
  in one frame with room to spare (a test pins this);
- the `bufferedAmount` thresholds of M1.2 can be set in whole frames.

`hello.limits.max-frame` lets a peer ask for smaller frames (1024..16384). It
can never raise the limit. The effective size is the smaller of the two.

### 3.2 Chunking an item stream

An item stream is the wire-v1 reveal stream (wire-v1.md, "Exception: a reveal
stream"): concatenated `Item`s, no chunk envelope, no count, no budget. Peer v1
puts that **byte stream** into `item-stream` frames:

- Frames on one stream carry consecutive slices of the byte stream. A slice may
  end inside an item. A receiver MUST reassemble by bytes, not by frames.
- The last frame has FIN (and PARTIAL if the reveal is unfinished). An empty
  stream is one frame with an empty payload and FIN (fixture `item-stream-empty`).
- A sender MAY flush early and send short frames while a reveal is paced.
  The reference chunker (`frame/chunk-items`) fills every frame to 16 378
  payload bytes except the last. The fixtures use it.
- An `Item` header is 37 bytes (`enc`, 32-byte hash, `plen` u32). A receiver
  MUST reject any `enc` but 0 (node): an unknown value (fixture
  `reject-item-unknown-enc`) and a literal, `enc = 1` (fixture
  `reject-item-literal`), both with reason `bad-item`. It MUST reject an item
  whose total size is over `max-item` (default 1 MiB, `hello.limits.max-item`) **before** it buffers the
  payload. This is the bound N12 asks for.
- A stream that ends inside an item is an error (`truncated-item`).
- v1 item streams carry **node items** (`enc = 0x00`) only, as the reveal
  service does today. The check is on the item header, so a literal item is
  refused as soon as its 37th byte arrives.

A real node can exceed one frame. In fixture `item-stream-three-frames`, a
reveal of a 1200-element vector sends five pages; the last is one 39 753-byte
`ft` page with its leaves inline, so it spans all three frames (16 378 bytes of
it in each of the first two).

### 3.3 Verification of items

Every item is hash-verified by the receiver before it is stored. The check is
the store layer's, not the framing's (today `dacite.service.reveal/accept-items!`:
a node whose hash depends on children not yet held waits until they arrive; a
mismatch is refused and not stored). A bad entry ends the session with the
peer: `error` `bad-entry`, `fatal`. M1.2 writes those rules down and M2.2 moves
the check into the portable core.

## 4. Streams and stream ids

A **stream** is one request and its answer. The requester picks the stream id.

- **Stream 0** is the session: `hello`, `ack`, `release`, and fatal errors.
- A request (`have?` or `get`) opens stream *n* ≥ 1. The answer and any
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
the `have` answer, or an `error`. After that the id is closed; frames still in
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
  (`hello`, `ack`, `release`), and `have?`/`have`.
- **One channel per `get`:** the requester opens a channel labelled
  `dacite/v1/get/<stream id>`. Its first message is the `get` frame; the
  responder answers on the same channel with `item-stream` or `error` frames.
  Every frame on it is expected to carry the id in the label; a receiver
  closes the channel on a mismatch. Either side closes the channel after the stream ends.
- The envelope carries the stream id even though the label already does. That
  keeps one codec for a single multiplexed pipe (the in-process loopback of M3.1,
  a WebSocket) and lets `cancel`, `error` and `ack` name a stream from any channel.
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
| `caps` | set of string | yes | Capabilities: `"have"` (I answer `have?`), `"reveal"` (I answer `get`). Unknown ones are ignored. |
| `limits` | map | yes | `max-frame` (1024..16384), `max-item` (bytes, at least 1), `max-streams` (open requests I accept, at least 1), `max-have` (hashes per `have?`, 1..256). All required. |
| `rates` | map | yes | `send-max` and `recv-max` (bytes per second I will send / want to receive), `req-rate` and `req-burst` (requests per second and bucket size I accept). All required and at least 1: a peer that serves nothing leaves out the capability instead. |
| `agent` | string | no | Software name and version. Informational. |

```clojure
{"versions" #{1}
 "agent"    "dacite-nbb/0.2.0-alpha"
 "kind"     "node"
 "caps"     #{"have" "reveal"}
 "limits"   {"max-frame" 16384 "max-item" 1048576 "max-streams" 32 "max-have" 256}
 "rates"    {"send-max" 10000000 "recv-max" 10000000 "req-rate" 20 "req-burst" 40}}
```

Fixtures: `hello-node` (343 bytes), `hello-browser`.

**Batch size.** A `have?`, `ack` or `release` holds at most
`min(256, max-have of the responder, max-hashes-for(max-frame))` hashes, where
`max-hashes-for(f) = min(256, floor((f - 33 - 320) / 32))`: 32 bytes a hash,
33 bytes of header and framing, and 320 kept for a `release` `reason`. At 16384
that is 256; at 1024 it is 20. `msg/max-hashes-for` is the helper, and a test
encodes a full batch at each frame size.

Negotiation: the version is the highest in both sets; if there is none, send
`error` `unsupported-version` with `fatal: true` and close. Limits are the
smaller of the two sides'. Capabilities are the intersection. Rates are
advertised ceilings; how they are shared with the client's `R_max` is M1.2.

### 6.2 `have?` (0x02, new stream) and `have` (0x03, same stream)

`have?` asks which of 1 to `max-have` hashes (never more than 256) the responder
holds *as entries*. The answer is a bitmap.

```clojure
;; have?  stream 1
{"hashes" [h1 h2 h3]}            ; each h is a u256
;; have   stream 1
{"count" 3 "bits" <blob a0>}     ; bits 1 0 1: holds h1 and h3
```

`count` equals the number of hashes asked. (That the count matches the request
is a session rule, checked against the open `have?`; the decoder only checks
that `bits` fits `count`. M1.2 lists it.) Hash *i* is bit `0x80 >> (i mod 8)`
of byte `i div 8`. `bits` is exactly `ceil(count / 8)` bytes and spare bits are
zero. A set bit says the responder holds that one entry; it says nothing about
the entries below it (open question 6).

The frame, written out by hand from the layout rules (wire-v1.md type ids
`0x10` string, `0x11` blob, `0x03` i64, `0x21` map; type `0x03` is `have`).
The test `spec-hex-blocks-match-fixtures` compares this block, and the one in
6.3, with the fixture `frames.hex`. The only thing not derived by hand is the
order of the pairs (ascending content hash of the key string), which comes
from the encoder.

```hex have-reply
03 01 00000001                 ; have, FIN, stream 1
21 00000002                    ; map, 2 pairs
10 00000004 62697473           ; "bits"
11 00000001 a0                 ; blob, 1 byte: 1010 0000
10 00000005 636f756e74         ; "count"
03 0000000000000003            ; i64 3
```

Fixtures: `have-query`, `have-reply`, `have-reply-9` (nine hashes, two bytes).
A responder without the `have` capability answers `error` `unsupported`.

### 6.3 `get` (0x04, new stream)

Ask for a value as a reveal. The answer is an item stream (6.4).

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
answers an unknown message type and a `get` or `have?` the responder has no
capability for, with `unsupported`. A sender never writes an unknown policy:
the reference encoder refuses it.

A policy is a Dacite value in the long run (streaming note); the shape is
deferred, which is why `policy` is a string name now and a later version can
accept a map there.

The whole frame of fixture `get-default`, by hand (`0x0d` is `u256`, then its
32 bytes; `0x04` is `get`):

```hex get-default
04 01 00000005                 ; get, FIN, stream 5
21 00000002                    ; map, 2 pairs
10 00000006 706f6c696379       ; "policy"
10 00000007 64656661756c74     ; "default"
10 00000004 68617368           ; "hash"
0d 0000000000000001 0000000000000002 0000000000000003 00000000000000aa
```

Answers: `item-stream` frames, or `error` `unknown-hash` (the responder does not
hold the hash; non-fatal, the stream ends), `unsupported-policy`, `rate-limited`,
`overloaded`, `too-large`.
Fixtures: `get-default`, `get-interest`, `get-repeat`.

### 6.4 `item-stream` (0x05, the `get`'s stream)

Not a Dacite value: the payload is a slice of the item byte stream (3.2).
The responder sends pages as its reveal clock allows. The receiver verifies each
item (3.3) and stores it. FIN ends the stream. FIN with PARTIAL says the reveal
paused (interest fell below the floor) or spent its budget, and that the same
`get`, as a new stream, resumes it (this closes N2a on the wire).

Fixtures: `item-stream-empty` (one empty frame), `item-stream-one-frame`
(5 pages, 1178 bytes), `item-stream-three-frames` (3 frames, 40 250 bytes; one
item spans all of them), `item-stream-partial` (FIN + PARTIAL). Their
`message.edn` lists every item's hash and size, and the test verifies the
hashes against the entries on the JVM.

### 6.5 `ack` (0x06) and `release` (0x07), stream 0

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

### 6.6 `cancel` (0x08, the request's stream)

```clojure
{"reason" "navigated away"}   ; or {}
```

`reason` is optional (fixtures `cancel`, `cancel-bare`: an 11-byte frame).

### 6.7 `error` (0x09)

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
| `bad-entry` | An entry failed its hash check (fatal). |
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
control   have? {h1 h2 h3}  ----------------------->            stream 1
          <------------------------------ have {bits 101}
get/3     get {h1 default}  ------------------------>           stream 3, new channel
          <----------- item-stream (16 KiB frames) ...
          <----------- item-stream FIN
control   ack {pages stored} ----------------------->           stream 0
get/5     get {h1 default}  ------------------------>           stream 5: same hash, new id (N1)
```

## 8. Fixtures and tests

`fixtures/peer-v1/` has 53 cases. Each is a directory with `frames.hex` (one
frame per line, lowercase hex) and `message.edn` (what the frames mean).
`message.edn` has `:expect`:

| `:expect` | Port must |
|---|---|
| `:message` | Decode the one frame to the given type, stream and value; encode that value again and get identical bytes. Some cases add `:reads` (how a receiver reads a name it does not know: `:error-code`, `:peer-kind`) and `:answer` (the `error` a responder sends, from `answer-error`). |
| `:item-stream` | Reassemble the frames, split the items, match the listed hashes and sizes; re-cut the stream and get identical frames; re-encode each item to identical bytes. |
| `:reject` | Refuse the frames, for the given `:reason` (`frame-too-short`, `frame-too-large`, `unknown-type`, `bad-flags`, `partial-without-fin`, `not-fin`, `wrong-type`, `bad-literal`, `not-canonical`, `profile`, `invalid-message`, `bad-item`, `item-too-large`, `truncated-item`, `after-fin`, `wrong-stream`). 30 cases. |
| `:unknown` | Decode as an unknown type, not as an error. |

Goldens come only from the shipped code. Regenerate (JVM, from `impl/clojure`):

```sh
clojure -M:dev -m gen-peer-fixtures
```

Run: `clojure -M:dev:test -n dacite.peer.peer-v1-test` (JVM; also checks entry
hashes, a property test over random profile values, and size limits) and
`npm run peer:test` (nbb; the same fixtures, byte for byte: the host
parity check for the encoder).

## 9. Open questions for Jonathan

1. **Control body = bare `Lit`.** No `Item` header and hash on a control
   message (saves 37 bytes and the sender's hash). Cost: a message cannot be
   stored or named by hash. Is that right?
2. **Type in the envelope only,** not inside the value. Keeps a message from
   disagreeing with its own envelope. OK?
3. **No floats in v1 messages,** so interest is fixed point (thousandths and
   milliseconds). Or allow `f64` and give nbb a typed number?
4. **16 KiB frames, 1 MiB `max-item`.** Confirm both, and the `have?` cap of 256.
5. **Literal items in an item stream.** v1 sends node items only (what the
   reveal engine produces). Allow literal items so a peer can inline small
   leaves, as pack-filled GET does?
6. **`have` says "I hold this entry,"** not "I hold everything below it." Add a
   second `complete` bitmap? It also decides how much `have?` leaks (peering
   open question 3).
7. **`ack`/`release` are session-wide on stream 0.** Do we want them per stream?
8. **PARTIAL on FIN** for a paused or exhausted reveal. Or a separate message?
9. **Keep `cancel`,** given that interest already decays like a lease?
10. **Channels:** one per `get` plus a control channel, and stream ids by offerer
    parity, as sketched in section 5. Or everything on one channel?

## 10. Known issues

- The reference core cannot realize a vector that holds a blob and another
  element: `(dacite.peer.msg/encode-value [(byte-array 1) nil])` throws
  "cannot intern page-body lit item" (`dacite.value.lit/intern-lit-item!`).
  Not a protocol issue: v1 uses a blob only as a map value. Tracked in
  [jclaggett/dacite#8](https://github.com/jclaggett/dacite/issues/8); when it
  is fixed, drop this entry and the map-value-only rule in 2.1.
- Entry verification is JVM-only (`dacite.service.reveal`) until M2.2, so the
  nbb test checks framing and hashes-as-listed, not entry hashes.
