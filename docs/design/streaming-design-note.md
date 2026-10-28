# Dacite Streaming: Rate-Based Reveal (Design Note, Draft)

Status: draft, Oct 27, 2026. Author: Jonathan Claggett. Absorbed into [progressive-revelation.md](progressive-revelation.md). This file remains the draft that prompted that revision.

## Problem

The library app experiment (built to gain experience with Dacite Values) showed that fetching ~1-2 KB chunks one request at a time is far too slow. A 5 MB image required hundreds of round trips before it could render. The server must send much more data per client request.

The goal: send the most data, of the most wanted parts, of a very large Dacite value, with as few client requests as possible. Analogy: a good waiter anticipates what the diner wants before they ask. Ideally the client never has to ask.

## Constraints

- A Dacite value can be enormous (e.g., an entire library of books). The client can't, and doesn't want to, receive all of it.
- Clients must therefore say which sub-values they want.
- Tension: too many client requests vs. too much data from the server.

## Key idea: rates, not byte limits

Both client requests and server responses are limited by rate (bytes/second), not just by size. Rates are governed by:

- server load,
- client ability,
- pipe width.

## Decaying interest per request

Each request opens a stream whose interest starts high and decays over time, trickling to a stop. Peak and decay are tunable and derived from the governing inputs above.

A candidate formulation:

- `I_i(t) = peak * exp(-t / tau)`, stopping below a floor.
- All requests share the connection's global max rate `R_max`:
  `rate_i = R_max * I_i(t) / sum_j I_j(t)`.

Consequences:

- No explicit cancel is needed. Unwanted streams fade on their own (interest acts like a lease).
- Renewal: each request has its own decay. Continued interest is expressed by new requests, all sharing `R_max`.
- Priority: concurrent streams share bandwidth by interest, so the newest request gets the lion's share.
- Maps well onto HTTP/3: one QUIC stream per request, with server-side pacing.

## Fairness and request rate limiting

To keep bursts of requests from starving older streams:

- The server rate-limits incoming requests per client.
- The client uses its own token bucket for the same reason, and should prefer refining existing requests when tokens run low.

## Deduplication

Two sources of duplication:

1. **Across requests in one session.** The server tracks the set of entries already sent to this client. This state lasts only for the session. It also lets a traversal prune already-sent subtrees and spend its rate on new data.
2. **Data the client already holds.** No explicit protocol for now. The client asks for novel roots, and interest in duplicated data naturally wanes. Remaining cost: a large shared subtree under a new root may be resent; acceptable for v1.

## Reveal policies

For each request, a policy decides what to reveal first. For example, a sequence (string) may reveal its first items, its last items, or a random sample.

- There are default policies per value type.
- The client can override policies for specific values and sub-values.
- Policies are themselves Dacite values (a traversal order attachable to a path).
- Detailed shape: deferred.

## Open questions

1. Interest function: is exponential decay right? How are `peak`, `tau`, and the floor chosen from server load, client throughput, and pipe width?
2. Can a client bump interest on an existing request, or only issue new requests?
3. How do client and server measure and agree on `R_max`?
4. Token bucket parameters: fixed, or tuned per session from the same inputs?
5. Reveal policy representation and override syntax (deferred).

## Framing: a Dacite browser

Progressively revealing a Dacite value is browsing it, in the original sense of the word. A web browser is a client for a large graph of linked documents: it fetches what you look at, prefetches what you'll likely want, and follows links lazily. A Dacite value is a content-addressed tree with the same shape, and reveal policies play the role of a page's layout deciding what shows first.

Implication: the product may be a general Dacite browser that opens any value at any root and explores it with streaming, reveal policies, and dedup built in. Apps such as the library app become custom views over it. Demo story: open a terabyte-scale value and start reading instantly.

Where Dacite browsing matches or improves on web browsing:

1. **Persistent cache.** Dacite values are highly cacheable, so like web browsers, Dacite browsers need a persistent cache. Because content is addressed by hash, cache entries never go stale and need no invalidation.
2. **Links are safe to follow automatically.** Dacite values are static: every part was concretely realized at some point, even if parts can later be lost. A link (content hash) always means the same bytes. Unlike hyperlinks, whose targets can change, it is safe, and necessary, for the browser to follow links automatically and prefetch ahead of the user.

## Browser cache as a Dacite Store

The "Dacite way" to cache in the browser is to treat IndexedDB as just another Store (an `IndexedDBStore`), layered in front of the remote store like the existing `SmartCacheStore`/`WriteBackStore` in `dacite.store.client-cache`. Received stream batches become puts (one IndexedDB transaction per batch), and the store's keys are the client's "what I already have" set.

Blocker: `IStore` (`dacite/store.cljc`) is synchronous today (`s-get`, `s-put`, `s-has?`, ...). The browser remote store (`dacite/store/browser.cljs`) uses synchronous XHR to keep it blocking, and is marked demo-only. IndexedDB is async-only, and any get can mean a server request, so the store protocol needs an async form (at least on the ClojureScript side). Synchronous one-at-a-time XHR is also a likely contributor to the slow library-app image load.

## Future direction: LAN peer caching

Because Dacite entries are immutable and content-addressed, any peer's cache is a safe source: a receiver verifies each entry against its hash, so peers never need to be trusted. This leverages a core Dacite design choice (immutable, highly cacheable data).

Idea: Alice and Bob on the same LAN each offer a Dacite Store fronting their local cache (e.g., IndexedDB), and check LAN peers before going to internet servers. Store tiers become: memory, then local IndexedDB, then LAN peers, then the internet server.

Constraint: a web page can't listen for incoming connections. Options:

- **WebRTC data channels:** browser-to-browser, peer to peer. Needs a small signaling server for introductions, after which traffic stays on the LAN. Pure browser, works today.
- **Local companion process:** a small native/Node service per machine (e.g., the existing nbb LMDB store) serving a Dacite Store over HTTP, discovered via mDNS. More robust, but requires an install.

Prior art: IPFS, BitTorrent peer caching.
