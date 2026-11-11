# Dacite Peering (Design Note, Draft)

Status: draft, Oct 29, 2026. Author: Jonathan Claggett.

**Revision (Nov 9, 2026).** Jonathan and Grok Bot set aside origin-integrated signaling and origin peer suggestion for the peering layer. Peering is a channel to a named peer and a fetch by hash. A peer list is a conventional value published at a root, above peering. **Revised Nov 10:** nbb parity is the goal, and demos are parked ([the peering plan](../plan/peering.md)). Peering phase 1 is interest-based transmission over WebRTC. Peering phase 2 is ICE-based discovery for peer lists, after the roots layer. The discovery list below is the Oct 29 first stab. Item 1 is not the next slice. Open question 1 is withdrawn from the peering plan.

## Motivation

Dacite entries are immutable and content-addressed. Any copy of an entry is as good as the original, and a receiver can verify it against its hash. So any client's cache can safely serve other clients, and peers never need to trust each other, invalidate anything, or coordinate.

Now that browser clients have an `IndexedDBStore` (`dacite/store/idb.cljs`) and `IStore` is async, a peer is just another Store tier:

memory -> local store (IndexedDB or LMDB) -> peers

## Peer kinds

### Browser peers
- Serve hash requests from their `IndexedDBStore`.
- Can't listen for connections, so they connect through WebRTC.
- Short-lived and unreliable (tabs close), and limited by browser storage quotas. Treat them as opportunistic.

### Node peers (nbb/Node)
- A small long-running process backed by the nbb LMDB store, speaking WebRTC through a library like `node-datachannel` (or `werift`).
- System admins deploy them at useful physical points in their networks (offices, labs, racks, data centers), like CDN edge nodes anyone can run.
- Stable and large, so they should be preferred over browser peers.
- Headless Chromium running the browser client is a quick prototype path, but the Node peer is the long-term target.

## Transport: WebRTC data channels

- WebRTC is a W3C/IETF standard (`RTCPeerConnection`, `RTCDataChannel`) supported by all current browsers, with mature Node and C++ implementations.
- Data channels carry arbitrary bytes, with reliable/ordered or unreliable/unordered modes, over DTLS/SCTP (encrypted by default).
- Connection setup needs signaling (exchanging offers, answers, and ICE candidates) through some third party. After that, traffic flows directly, staying on the LAN when both peers are on it.
- NAT traversal uses STUN, with TURN relay as a fallback. Relayed traffic is not LAN-local and may not be worth it for caching.
- Peers reuse the Dacite pack/wire format and the rate-based reveal protocol from the streaming note, with one data channel per request stream.

## Peer protocol (sketch)

- ~~`have?` asks whether the peer holds a set of hashes, answered with a bitmap.~~ Removed from v1: a `get` with the smallest interest is the probe. See [peer-v1.md](../spec/peer-v1.md) section 9 (Q6). Optionally, peers exchange a compact summary of their contents, such as a Bloom filter or recent roots.
- `get`/reveal requests a root or hash, and the peer streams entries under the same rate and interest rules as the server.
- Every received entry is verified against its hash before it is stored, and a bad entry ends the session with that peer.
- Peers serve only reads on this protocol. Root updates are the roots layer (a mutable reference on a peer). The Nov 9 plan has no privileged origin in the peering model. See [the peering plan](../plan/peering.md).

## Discovery (first stab)

Discovery finds candidate peers, and signaling connects them. The list below is the Oct 29 order. Nov 9 takes item 1 out of the peering layer. A peer is named by hand (`dacite://<host>#urn:dacite:<hex>`, the Dacite digest). Items 2 and 3 remain later ways to learn that URL (plan Phase 7). They are not peering primitives.

1. **Origin as signaling and rendezvous.** The Oct 29 option: the server a client already talks to relays WebRTC signaling and suggests peers, grouped by public IP, subnet, or a site tag. Set aside for peering (Nov 9). The docs-only draft of that channel is PR #47 (`58a3b28`). It is not the next slice.
2. **Configured peers.** Admins list Node peers by `dacite://` URL in client config. This is predictable and works well for enterprise and HPC deployments. The list is local configuration, not a value the origin serves.
3. **mDNS on the LAN (Node peers only).** Node peers announce a `_dacite._udp` service and learn each other's URLs with no directory. Browsers can't browse mDNS, so they still get a Node peer URL from a person or from config.
4. **Later: a DHT** (hash-to-holder lookup, like IPFS or BitTorrent) for wide-area peering. This is out of scope for now.

The Oct 29 selection rule (prefer Node peers, LAN over WAN, low latency, cap concurrent peers, fall back to the origin) is not a peering primitive. The Value Browser opens the peer the URI names.

## Open questions

1. Does the origin hand out peer lists by root, or only by network locality? **Withdrawn from the peering plan (Nov 9).** The origin is not a peering-layer peer directory. A peer list is a conventional value at a root. See the plan, section 10.
2. Privacy: does asking a peer for a hash reveal what the client is reading? Should probing a peer (a `get` with the smallest interest; `have?` was removed from v1) be restricted to known peers or a site?
3. Access control: should a peer serve any hash it holds, or only to clients authorized for that root?
4. How do peer rates fit into the client's global rate budget and token buckets?
5. Is TURN relay ever worth it for caching, or should peers be direct-only?
6. Should browser peers serve at all by default, or only when the user opts in?

## Prior art

IPFS (Bitswap, DHT), BitTorrent (trackers, peer exchange), WebTorrent (BitTorrent over WebRTC), and CDN edge caches.
