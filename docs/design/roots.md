# Dacite Roots (Design Note, Draft)

Status: draft, Nov 5, 2026. Owner: Jonathan Claggett (design lead). Prepared by the Dacite Programmer bot for Jonathan's review.
**Spec only: no code, no frozen wire format.** Message shapes below are sketches to think with, not a protocol.

## Purpose

Entries are immutable and content-addressed, so peering (`docs/spec/peer-v1.md`) moves them safely between peers that do not trust each other. A **root** is the one mutable thing: a hash that names "the current value" of a store. Today the origin keeps it (`GET /root`, `POST /root/cas`, `GET /events` in `docs/design/service.md`). Peers serve only reads (`peering-design-note.md`, `peer-v1.md` C34, C36), so nothing in peering says how a peer learns the current root, or how a change gets to the origin.

The roots layer covers three things:

1. **Publish** a public root: a server says "my current root is H".
2. **Query** it: a peer asks a server for its current public root, and may watch for changes.
3. **Suggest**: a trusted peer says "I have a new root R that you may want", and the server, if it agrees, **pulls R with `get`** (the ordinary peer protocol, which verifies every entry) and updates its own root itself.

A suggestion carries no data and changes nothing by itself. All the bytes still travel as verified `get` streams, and the server stays the only place a root changes.

## One connection, its own channel

Roots does not open its own connection. It shares the peer connection (one `RTCPeerConnection`, one handshake) and uses its own **named channel, `"dacite-roots"`**, beside `"dacite-peer"`. The channel model, the transport interface and the close rules are in the WebRTC plan: `docs/plan/peering.md` (M3.1 and M3.2) and `docs/spec/peer-v1.md` section 5 (`dacite.peer.transport`: `ITransport`, `IChannel`).

What that means for this note:

- **Peering never sees a roots message, and roots never sees a peer frame.** Different channel, different codec, different session state. A garbage message on `dacite-roots` cannot end a peer session (the M3.1 check `peer-never-sees-roots-messages` pins this from the peering side).
- **Each channel's close ends only that layer.** Closing `dacite-roots` leaves the peer session open (and the reverse). A connection close ends both.
- **Flow control and message size are per channel**, as for peering: the roots channel has its own `bufferedAmount` and its own `maxMessageSize`. Roots messages are small (a hash and a few fields), so neither should matter in practice.
- **A suggestion makes the server a `get` client of the suggester.** The pull goes over `dacite-peer` of the same connection (or any other peer the server asks). That needs a node that is both a client and a responder on one connection, which M3.1 slice 1 does not build (one client talks to one responder per channel). It is a dependency of the suggest flow, not of publish and query.

## Message shapes (sketch)

Illustrative only; the names, the keys, the codec and the framing are all open. The likely direction is the same as peer-v1 section 2: a message is a small Dacite value (a map with string keys) in the wire-v1 profile, with a message type and an id in a small envelope, so there is one codec and one set of fixtures across layers. Hashes would be hash references as in peer-v1.

```clojure
;; Query and publish. Request/response by id.
{:type :root-query   :id 1}                              ;; "what is your public root?"
{:type :root         :id 1 :value {"root" <hash-ref | nil>
                                   "seq"  <u64, required>}}   ;; monotonic; see below

;; Watch: the peer asks to hear changes (what GET /events does over SSE today).
{:type :root-watch   :id 2}
{:type :root         :id 2 :value {"root" <hash-ref | nil>
                                   "seq"  <u64, required>}}   ;; and again on each change
{:type :root-unwatch :id 2}

;; Suggest: "R is a root you may want".
{:type :root-suggest :id 3 :value {"root" <hash-ref>
                                   "base" <hash-ref | nil>      ;; CAS expectation; see below
                                   "note" <short string, optional>}}
{:type :root-suggest-result :id 3 :value {"status" "pulling"}}  ;; accepted for consideration
{:type :root-suggest-result :id 3 :value {"status" "applied" "root" <hash-ref> "seq" <u64>}}
{:type :root-suggest-result :id 3 :value {"status" "declined" "reason" "stale-base"}}

{:type :error :id 3 :value {"code" "..." "message" "..."}}
```

Notes on the sketch:

- **One public root per server, or named roots?** `service.md` gives each user a dedicated store and root, so "the public root" of a connection is clear today. Whether a `"slot"` or name key is needed (several roots on one server) is open.
- **`seq` is required and only goes up.** Every `root` message carries a server-assigned sequence number that increases on every successful root change (including the first set from nil). Watchers keep the highest `seq` they have seen and **drop** any `root` whose `seq` is less than or equal to it, so a late or reordered delivery cannot roll a watcher backwards. `seq` replaces the earlier optional `as-of`.
- **`base` is the CAS expectation, checked when the root is applied.** It is the root the suggester started from. `base` of `nil` means "there is no root yet" (the empty store), not "I do not care". The server does **not** check `base` when the pull starts: a concurrent writer may move the root while the pull is in flight. The check is at apply time, as part of the server's own CAS. If the current root is no longer `base`, the suggestion is declined with `stale-base` (the concurrent write wins). The nodes already pulled stay in the store as an unreferenced cache until GC; they are not rolled back.
- **`status` may be several steps.** A suggestion that is pulling and verifying takes time, so a result may come as `"pulling"` first and `"applied"` or `"declined"` later (or the suggester just watches for the new root and its `seq`).
- **Roots are claims, not proofs.** A hash proves content (every entry verifies), never that a root is *current*. A root reported by a peer is a hint: it may be stale or a lie about currency, though never about content. Only the origin's answer is authoritative for "current". What a client does with a peer-reported root (use it for a fast first read, confirm with the origin) is a client policy, not a wire rule.

## The suggest flow

```
suggester (trusted peer)                       server (root authority)
  |-- dacite-roots: root-suggest {R, base} --->|
  |                                            |  trust: allowed? rate ok? size/quota budget ok?
  |<-- root-suggest-result {pulling} ----------|  (base is NOT checked yet)
  |<== dacite-peer: get R  (the server is the client here) ==|
  |=== item-stream: R's entries ==============>|  every entry verified before store (C27 to C33);
  |                                            |  a bad entry ends the peer session (C13)
  |                                            |  closure check: R is fully stored and verified
  |                                            |  CAS now: current root must still equal base
  |                                            |  (nil base = no root yet); else stale-base
  |<-- root-suggest-result {applied | declined}|  pulled nodes stay as cache if declined
  |<-- root {R, seq} to every watcher ---------|
```

1. The suggestion arrives on `dacite-roots`. The server checks its trust policy first: is this peer allowed to suggest, is it under its rate limit, and does a **size/quota budget** for this pull look acceptable (an upper bound on bytes or entries the server is willing to take for this suggestion)? It may decline without reading further. It does not check `base` yet.
2. The server pulls R with `get` over `dacite-peer`, the usual way: read-only for the suggester, rate-limited, every entry verified before it is stored, a bad entry ends that peer session (peer-v1 section 12). A suggester that lies about R only wastes its own session. The budget from step 1 bounds how much it will take.
3. The server checks that what it holds is a complete, verified value (the value-layer closure check that `store-composition-pack.md` says must come before a CAS), then makes its **own** root update by the same compare-and-set it uses for any writer. **That CAS is where `base` is checked:** the current root must still equal `base` (`nil` only when there is no root yet). A concurrent write that moved the root yields `stale-base`. On decline, the nodes already pulled are left in the store as an **unreferenced cache** until ordinary GC; they are not deleted as part of the decline.
4. The result is reported to the suggester, and the new root (with its new `seq`) to watchers.

## Trust model (sketch)

- **The server remains the authority for root updates.** A root changes only by the server's own CAS, under the server's own authorization rules (`docs/authorization.allium`). A suggestion is advice. It cannot write to the server's store (peers are read-only) and cannot move the root.
- **Who may suggest.** By default nobody: an unknown peer may query and watch, as it may `get`, and its suggestions are dropped (or answered `declined`). The server accepts suggestions from peers it **trusts**: peers named in its configuration (admin-configured peers, M7.1), or, later, peers that prove they act for a user who may write that root. How a peer is identified and what it must prove is the authentication question (out of scope here); the room token of the first signaling server is *not* a credential for this.
- **A trusted peer can still be wrong.** The server verifies everything it pulls and may decline for any reason (stale `base` at apply time, content it does not want, rate limits, the size/quota budget). Trust decides who may be *heard*, never what is accepted without checking. The size/quota budget is part of the trust check before the pull, so a trusted peer cannot fill the store by suggesting a huge value.
- **Privacy.** A query tells the server who asked; a watch tells it who is interested. Same note as peering open question 3.
- **No new data path.** All content moves through `get` and its verification. If roots were removed, peering would be unchanged.

## Out of scope for now

- The full protocol: message set, keys, codec, framing, error codes, versioning, fixtures, conformance rules.
- Authentication and authorization details: how a suggester proves who it is or whom it acts for; per-root permissions; signed roots; how this meets `authorization.allium`.
- Any implementation, including the combined client-and-responder node the suggest pull needs.
- Multiple named roots per server; history, snapshots or merging of suggested roots; conflict policy beyond "decline a stale `base`".
- Root discovery (finding who publishes a root for what), peer lists, mDNS (peering plan M6 and M7).
- Replacing the origin's `GET /root`, `POST /root/cas` and `GET /events`: they stay as they are; this layer is for peers on a WebRTC connection.

## Open questions

1. Is a root a named slot or the single root of a store? (Decide with the first user of query.)
2. Should an answer to `root-query` be signed by the server, so a peer can relay it and a client can check it without asking the origin?
3. May a server pull a suggested root from a peer other than the suggester (the suggester only names the hash)? Probably yes: `get` already picks peers.
4. Does the server need the suggester to stay connected while it pulls, or may it finish from other peers?
5. How much of an unfinished suggestion survives a roots-channel close (the pull continues, the result is lost)?
6. Should `base` mismatch ever be accepted, say for a fast-forward the server can prove (R contains `base`'s value)? (Default: no; decline with `stale-base`.)
7. How is the size/quota budget for a suggestion expressed (bytes, entry count, depth), and is it advertised to the suggester?

## Where this connects

- `docs/plan/peering.md`: M3.1 and M3.2 (the approved WebRTC plan, named channels, slices), the milestone this layer rides on; phase 6 (signaling and the origin), phase 7.
- `docs/spec/peer-v1.md`: section 5 (named channels, `maxMessageSize` clamp, close rules), section 6 (`get`), section 12 (verification, read-only rule).
- `docs/design/peering-design-note.md`: peers serve only reads; root updates and CAS stay with the origin.
- `docs/design/service.md`: today's root endpoints (`GET /root`, `POST /root/cas`, `GET /events`).
- `docs/authorization.allium`: the authorization model a root update must still satisfy.
