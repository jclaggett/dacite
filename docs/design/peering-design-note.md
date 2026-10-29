# Dacite Peering (Design Note, Draft)

Status: draft, Oct 29, 2026. Author: Jonathan Claggett.

## Motivation

Dacite entries are immutable and content-addressed. Any copy of an entry is as good as the original, and a receiver can verify it against its hash. So any client's cache can safely serve other clients, and peers never need to trust each other, invalidate anything, or coordinate.

Now that browser clients have an `IndexedDBStore` (`dacite/store/idb.cljs`) and `IStore` is async, a peer is just another Store tier:

memory -> local store (IndexedDB or LMDB) -> peers -> origin server

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

- `have?` asks whether the peer holds a set of hashes, answered with a bitmap. Optionally, peers exchange a compact summary of their contents, such as a Bloom filter or recent roots.
- `get`/reveal requests a root or hash, and the peer streams entries under the same rate and interest rules as the server.
- Every received entry is verified against its hash before it is stored, and a bad entry ends the session with that peer.
- Peers serve only reads. Root updates and CAS stay with the origin server.

## Discovery (first stab)

Discovery finds candidate peers, and signaling connects them. Some options, roughly in order of how soon to build them:

1. **Origin as signaling and rendezvous.** The Dacite server a client already talks to also relays WebRTC signaling and suggests peers. It can group clients by network hints such as public IP, subnet, or an admin-assigned site tag. This is the simplest option, works for browsers, and needs no extra infrastructure.
2. **Configured peers.** Admins list Node peers by address or site, either in client config or through the origin. This is predictable and works well for enterprise and HPC deployments.
3. **mDNS on the LAN (Node peers only).** Node peers announce a `_dacite._udp` service and find each other with no server at all. Browsers can't browse mDNS, so they still find Node peers through the origin or through config.
4. **Later: a DHT** (hash-to-holder lookup, like IPFS or BitTorrent) for wide-area peering without an origin. This is out of scope for now.

Selection: prefer Node peers over browser peers, LAN over WAN, and low latency over high. Cap concurrent peers. Fall back to the origin after a short timeout.

## Open questions

1. Does the origin hand out peer lists by root, or only by network locality?
2. Privacy: does asking a peer for a hash reveal what the client is reading? Should `have?` be restricted to known peers or a site?
3. Access control: should a peer serve any hash it holds, or only to clients authorized for that root?
4. How do peer rates fit into the client's global rate budget and token buckets?
5. Is TURN relay ever worth it for caching, or should peers be direct-only?
6. Should browser peers serve at all by default, or only when the user opts in?

## Prior art

IPFS (Bitswap, DHT), BitTorrent (trackers, peer exchange), WebTorrent (BitTorrent over WebRTC), and CDN edge caches.
