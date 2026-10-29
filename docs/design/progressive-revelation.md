# Progressive revelation

**Status:** Design. High level only. This revision absorbs the streaming note of Oct 27, 2026 ([streaming-design-note.md](streaming-design-note.md)).

**Related:** [roadmap](../roadmap.md) (*How every client reveals a value*), [leaf-chunking](leaf-chunking.md), [service](service.md).

Pack-filled `GET /node/{hex}` stays as it is. Its soft budget of 1024 sent bytes is a pack rule, not a reveal policy.

## The problem

A Dacite value has to be treated as impossibly large. A book, a catalog, a blob, and a map of those are ordinary values. A read that assumes the value fits in one response, one memory, or one moment of attention will not survive contact with them.

The value is already complete under its hash. Revelation does not grow the value. It grows a client's view of that immutable value, in pieces, over time. A short string uses the same path and finishes on the first step.

A piece is a node the value already has. Page boundaries belong to the stored value, so two clients revealing the same hash see the same cuts. Internal finger-tree and HAMT pages are that value's own body. Nested scalars, strings, blobs, vectors, maps, and sets are subvalues.

The library reader showed the cost of the current path. A remote chapter or image is one `GET /node/{hex}` per chunk of about 1–2 KB, sealed by a budget chosen once. A 5 MB image took hundreds of round trips before it could render. Three pressures set the pace, and they move:

- The client should make few requests.
- The client should receive the parts it wants, and only what it can hold and use.
- The pipe and the server should stay within what they can carry.

The goal is to send the most data, of the most wanted parts, with as few requests as possible. A fixed byte budget cannot see bandwidth, load, or which subvalue the client cares about now.

## Client-based progressive revelation

Progressive revelation is how any client reads a Dacite value. One step of it is a reveal.

The client names the hash a reveal starts from. As that reveal exposes subvalues, the server guesses which of them the client would request next and answers those requests on this same reveal. The guess is the reveal policy for the value's shape. A correct guess is already stored when the client would have asked. The aim is that the client never has to ask. The server stays inside that guess. It does not invent a different shape, and it does not stop at a nested collection as if the hash were a semantic wall.

The client directs attention by which reveals are open.

- A request for a hash is focus, including a request for a subvalue. That reveal starts eager. A refinement is a request that names a tighter hash this reveal has already exposed, or that attaches a policy to a path already in play. A refinement starts eager too.
- Opening a subvalue leaves the parent running. The parent keeps decaying. The two share the ceiling below.
- Interest falling below its floor is the ordinary stop. The reveal pauses on its own. Pieces already stored stay. Unsent pieces stay unsent.
- Cancel is the immediate stop, for the moment the client already knows it is done. It drops that reveal's share of the ceiling at once. Stored pieces stay. Unsent pieces stay unsent.
- A new document root is a notice of a hash. The client reveals that hash when it is in scope. Child hashes already stored are not requested again.

The asked node goes out first: its type, its measure, the literal body that fits there, and its child hashes. The client can focus a child as soon as that child's hash is known, while the parent reveal is still in its eager phase.

Local reads follow the same idea of a client-directed view. `nth` and a bounded slice are reveals with a stride. Realizing an entire large value is a reveal with no limit. A local read has no decay clock. The clock belongs to transmission.

Pieces already stored stay in the cache. Within a session the server also remembers what it has sent; that dedup is [below](#deduplication). A reveal that paused partway resumes at the pages the client does not hold when the client asks for that hash again.

## Rate-based, decaying responses

Transmission is a rate, in bytes per second, shared by the open reveals on a connection. It is not a byte budget chosen before the value has been seen.

The ceiling `R_max` is whichever is smallest at the moment:

- what the pipe can carry,
- what the server can spare,
- what the client will accept.

That ceiling moves. The server computes it. What the client will accept shows up as read-side flow control, and as how fast the client opens requests. This revision has no handshake for `R_max`. Whether client and server should agree on it explicitly stays open. A count of pages is a way for the client to state a preference. It converts to bytes using the page that would actually be sent. Pages differ in size.

The quantum is a whole page. A tick of the rate sends the next page whole. The asked node is the first page of a reveal. Later ticks answer the request the policy guesses the client would make next.

Each open reveal has an interest inside the ceiling. Interest starts at a peak and decays with the age of that request:

```
I_i(t) = peak * exp(-t / tau)
```

`peak` and `tau` belong to the connection. They are tunable, and they are meant to be derived from server load, client throughput, and pipe width. This revision does not choose their values. Every request on the connection uses the same peak, so age alone sets the shares and the newest request takes the burst.

The share of the ceiling is:

```
rate_i = R_max * I_i(t) / max(peak, sum_j I_j(t))
```

While open interest is above one peak, the shares fill `R_max` and stay constant between membership changes. At one peak and below, each rate is `R_max * I / peak`. A lone reveal opens at the ceiling and then follows `R_max * exp(-age / tau)`. Several reveals that together exceed the peak fill the ceiling until they decay to it. `peak` is the connection's peak, and it stays in the denominator of a lone reveal.

A new reveal starts at the peak. Earlier reveals keep the clocks they already have. Opening another reveal renormalizes the shares. A chapter request takes the burst. The book trickles.

Once interest falls below a floor, the reveal pauses and drops out of the sum. The body may still have pages the client does not hold. Asking for that same hash again resumes from those pages and starts at the peak. Credit already earned toward the next page stays. A page the client holds is not sent again. A page offered and not yet acknowledged stays with that offer until `ack-pages` or `release-pages`. When the body ends while the rate is still high, the reveal is finished.

The session clock integrates this exponential. Above one peak the rate is constant between events, so credit grows by `rate * dt`. At or below one peak the integral over a step of `dt` is `rate * tau * (1 - exp(-dt / tau))`. A different curve replaces that integral. Whether the exponential lasts stays open. How `peak`, `tau`, and the floor are chosen stays open.

Two clocks stay distinct.

- The ceiling moves with bandwidth and with server load.
- A reveal's interest moves with the age of that request.

Decay keeps policy order. It does not skip ahead, and it does not pull in a subvalue the policy has not reached.

The share formula bounds bytes. A separate limit bounds how many reveals a client may open, so a burst of new peaks cannot crowd out older reveals or grow session state without limit. The server rate-limits incoming requests per client. The client keeps a token bucket for the same reason, and prefers to refine a reveal already open when tokens run low. Refining spends one request. When the bucket is empty, the client waits. The bucket's parameters, fixed or tuned from the same inputs as `peak` and `tau`, stay open.

Renewal of a reveal the client still wants is a new request. That re-ask is implemented: it restarts the clock at the peak and keeps credit already earned. Raising interest on an open stream, without a new request, stays an open question.

The interest model fits HTTP/3 as one QUIC stream per request, with the server pacing writes to `rate_i`. Flow control remains part of the ceiling. That stream is `POST /reveal`. The body is one EDN map, `{:hash "<64 hex>"}`. The response is `application/vnd.dacite.item-stream.v1`: concatenated wire-v1 node items, one page each, with no chunk envelope and no `Content-Length`. An empty body is a reveal that had nothing left to send. One connection is one session, so a later stream on it skips hashes still offered or held. A second request while the first response is still open is a focused child: the parent stream stays up, and the new reveal starts at the peak. On HTTP/3 the connection is the QUIC session. A stream that finishes acknowledges the pages it wrote. A stream that fails cancels that reveal and releases those offers. There is no client ack or release message yet. `GET /events` stays the root-notice stream. `GET /node/{hex}` stays the pack. `remote-store` opts into this stream with `:reveal`. Its default read is still that pack GET.

## Deduplication

Two layers keep a page from being sent twice.

**This session.** The server remembers two sets for this client, and only for this session. An offered page has been scheduled and not yet acknowledged. A held page is one the client has acknowledged. A traversal skips both and spends its rate on other pages. `ack-pages` moves an offer into the hold set. `release-pages` drops a hash from either set and queues it again on the reveal that offered it, when that reveal is still open. A reset of a page the client never kept, and an eviction of a page it no longer keeps, both return the hash to the reveal that way. A cancelled reveal is removed. A later request sends a released hash again once nothing in the session still offers or holds it. A reveal that paused resumes at the pages the client does not hold.

**Across sessions.** The client asks for roots it does not already hold in full. Listing held hashes on the wire waits for a later revision. A large shared subtree under a newly named root may be sent again.

## Value revelation policies

A policy is a guess about the client's next request. Once a reveal has made some subvalues known, the policy picks which of those the client would ask for next. The server answers that request now, on the reveal already open, so a correct guess is sitting in the cache when the client does ask. Page boundaries stay where the value stored them. The guess only chooses an order among nodes that already exist.

The guess can run only on subvalues already revealed. A new page makes new hashes known, and the policy guesses again from that larger set. Answering one guess reveals further subvalues, and the guess repeats there under that subvalue's own policy.

An explicit request from the client is a better signal than the guess. It starts eager. The parent reveal keeps going at its decaying interest, still answering what it expects the client to want next from what remains. When the guess was right, the client's later request finds the pieces already stored and sends nothing more. When the guess was wrong, the explicit request corrects it.

The policy name does not copy itself downward. Each shape makes its own guess. When a client overrides the policy, the override applies to the path it is attached to. A child with no attachment keeps its own default. A last-most vector of chapters still guesses that each chapter string will be read from the start: the client opens the last chapter, then reads it from the beginning, unless a policy is attached on that chapter path.

The default guess at each shape is the same two steps. First the client would ask for the rest of this value's own pages, so the shape is known. Then the client would ask for subvalue bodies in the order below. Inlined leaves are complete when their page arrives. One large child does not consume the eager burst before the parent has shown the hashes the client might choose among.

### Defaults

**Scalars.** `bool`, the integer and float types, `char`, `u8`, and the other atoms. One node. The whole value.

**String and blob.** These are sequences, not leaves. A finger-tree page of about 1,024 bytes inlines a run of `char` or `u8`, and that page is complete when sent. The guess is **first-most**: the client would next ask for the following page from the left. The pages are the body, so answering that request completes the characters or bytes.

**Vector.** **First-most.** The client would next ask for the following own page from the left, and after those pages, for the body of the earliest element. An inlined scalar is complete on the page. A nested value appears as a hash on that page, and its body is the later guess, under that element's own policy. A vector of chapters is answered as every chapter hash, then chapter bodies from the first.

**Map.** **Keys, then values**, in ascending key-hash order. An entry page reveals a key hash and a value hash. The guess is that the client would next ask for the key, and only after the keys for the value. The client can focus a value as soon as its hash is known, which corrects the guess. A small map spends little of the eager burst on keys and then answers the values. A large map spends the eager burst answering the key set.

**Set.** The stored entry uses the element as both key and value, so the only anticipated request is the element itself. Own pages, then element bodies, in hash order.

### Later policies

These are other guesses the structure can answer. They are not part of the minimum.

- **Sequence last-most.** The right end first. The event log wants this. A deep finger tree already stores left, spine, and right.
- **Sequence first and last, then the spine.** Both ends before the middle. This splits the eager burst between the two ends.
- **Map pairs.** The key body and the value body of one entry before the next key. A large first value outruns the rest of the keys.
- **Random sample.** A repeatable sample needs a seed, and a choice between elements and pages. The walk does not follow the tree.

### Policies as values

A policy may itself be a Dacite value: a traversal order attached to a path. Attaching one tells the server which anticipated requests to answer at that path. The path may be the requested value or a subvalue the client names. The attachment replaces the guess only there.

The value will name a member of the closed set: first-most, last-most, first and last then the spine, map keys then values, map pairs, set element order, and random sample. The representation of that value, and the syntax for attaching it, stay deferred.

### Client choice

The model allows the client to name the guess for a path, from that closed set. Naming it tells the server which anticipated requests to answer. The minimum reveal carries no policy field and no policy value, so the server uses the default guess. Focus is a new request for a hash. The first override worth adding is sequence last-most, when a client of the event log asks for it.

## One dictionary

Every `IStore` is the same dictionary to the value logic, whether the bytes are in memory, on disk, in IndexedDB, or on another machine. Each store method returns a promise of its result. One `await` in the value layer blocks on the JVM and babashka, so `v/nth`, `v/get`, `v/conj`, and `v/seq` still return values there. ClojureScript and nbb return promises once the walks themselves await. Until then `await` only unwraps a promise that is already settled, and `drive` still replays a browser fetch. A mem get is a promise that is already complete. A remote get on the JVM may be a future around the blocking call. The logic does not branch on which store it holds.

`s-get` fulfills with nil when this dictionary has no such hash, so a layered store, a client cache, and a pack cache can fall through to the next store. A required read uses `require-node`. It awaits `s-get` and fails as `:missing` when the node is absent. A store that cannot answer (HTTP error, IndexedDB abort, quota, timeout) rejects. `await` throws that rejection as `:unavailable`, with the cause attached. Optional reads keep the nil. Walks stop crashing on a nil node. The event log's policy for showing part of a value stays later. `drive` stays until the walks actually await.

IndexedDB is the first store built on that protocol. It is a durable dictionary the page can hold, alone or in front of the remote store.

## A Dacite browser

Progressively revealing a value is browsing it. A Dacite browser opens any value at any root and explores it with streaming reveals, policies, and dedup. An app such as the library reader is a view over that browser. The demonstration is opening a very large value and reading at once.

The cache is persistent, as in a web browser. Because content is addressed by hash, a cache entry does not go stale and needs no invalidation.

A link is a content hash. The value it names was realized when it was written, and the hash always means those bytes. Following the link to prefetch is safe. The reveal policy is that prefetch: it answers the request the browser would make next.

The cache belongs in the store stack. An `IndexedDBStore` sits in front of the remote store, in the same layering pattern as `SmartCacheStore` and `WriteBackStore` (`dacite.store.client-cache`). A batch of streamed pages becomes puts, one IndexedDB transaction per batch. `s-merge` is that batch. The in-memory cache keeps a bounded number of nodes this tab has read: 1024 by default, and a limit of 0 does not evict. A hit stays until a later operation needs the room. A miss is remembered only until the current `drive` finishes, so the next drive sees a put from another tab or worker. The store's keys are what the client already holds, which is the client side of dedup across sessions.

`dacite.store.browser` speaks HTTP with `fetch` inside `drive`: a miss suspends, `drive` reads `response.body` as bytes arrive, and the op runs again. The server still sends one `Content-Length` pack per `GET /node/{hex}`. That replay is how a page stays responsive until the walks themselves await. Store methods return promises. `dacite.store.idb` is the page's IndexedDB dictionary, and it suspends through the same `drive`. The server also accepts `POST /reveal` on that same handler. `remote-store` can read it, and the default is still pack GET, so the [roadmap](../roadmap.md) still has the QUIC reveal client as the current milestone.

The [roadmap](../roadmap.md) schedules a LAN peer tier after the reveal stream exists. Entries are immutable and content-addressed, so a peer's cache is a safe source: the receiver checks each entry against its hash. A read falls through memory, then the local store, then one preferred peer, then the origin. One WebRTC data channel carries one reveal, the same session a QUIC stream carries. The origin the client already uses relays signaling and suggests peers by network locality. The peer that gets built is a configured Node process on the nbb LMDB store. Root CAS and `GET /events` stay on the origin. The library resumes after the reveal client and the streamed pack. The account is [peering-design-note.md](peering-design-note.md).

## What this changes in the written reveal rule

The roadmap section *How every client reveals a value* still holds on these points. The client decides how far a read goes. Bytes already stored stay. A new root is a notice of a hash. Local `nth` and a bounded slice are reveals.

The roadmap's reveal section is unchanged. It still describes a child wall, a fixed byte budget, and cancel as the only stop. Current direction now leads with the QUIC reveal client. The page store is `dacite.store.idb`.

Three points change.

Subvalues are part of the reveal. The server answers the requests it guesses the client would make next, so a nested map, vector, set, string, or blob is revealed inside the same request. The client focuses one of them by opening another request, which starts eager, while the parent continues at its decaying interest.

The transmitted bound is a decaying rate in bytes per second, shared across the open reveals, with the candidate curve above. Internal pages remain the value's own body. They are the quanta of that rate.

The ordinary end of a reveal is its interest falling below the floor. Cancel remains the immediate stop when the client already knows it is done.

## Deferred

`POST /reveal` is the server stream, described above. Streaming a pack on one response remains a later way to carry the same byte limit on HTTP/1.1. The default page order, the interest shares, and the session clock are `dacite.reveal`, with no policy field on a request. The clock spends each share in wire-item bytes of whole pages. The share is `R_max * I / max(peak, sum I)`, and the clock integrates that exponential. A hash the client holds, or that the session has offered and not released, is skipped. `ack-pages` records a hold. `release-pages` queues a reset or evicted page again. Asking again for a hash still open resumes the pages the client does not hold and restarts that clock at the peak. That namespace does not open a stream. The service acknowledges a stream when the walk finishes, and releases its offers when the write fails. A message from the client that acks or releases is still absent. `remote-store` can opt into the stream; its default read is pack GET. Server `peak`, `tau`, `floor`, and `r-max` are placeholders (`floor` 0 does not pause a reveal). They are not derived from load or the pipe.

Still open:

- Whether the exponential lasts. The clock integrates this curve. A different curve replaces the integral. How `peak`, `tau`, and the floor are derived from load, throughput, and pipe width stays open.
- Whether a client can raise interest on an open stream without a new request. Asking again is implemented.
- Whether client and server need an explicit agreement on `R_max`.
- Token-bucket parameters, and a numeric cap on how many reveals one client may open.
- The representation of a policy value and the syntax for attaching it to a path.
