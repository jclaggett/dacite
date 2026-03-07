# Authorization Design — Store Access Control

*Draft: 2026-03-05. Revised: 2026-03-06. From discussion between Jonathan and Gorm.*

## Core Principle

**Knowing a hash does not authorize access to its value.**

Content-addressed systems tempt you into treating hash-knowledge as a capability.
Dacite rejects this. Authorization is structural: you prove you can *reach* a
hash from a root you're entitled to.

## Authentication

Users authenticate to a store service (e.g., `https://dacite.io/store`) using
standard credentials (token, OAuth, etc.). Upon authentication, the server
provides the user's **root hash** — the entry point to their data.

## Authorization: Proof Chains with Dedicated Stores

### Proof Chains

To fetch a node, the client provides:

1. **Auth token** — proves identity (who you are)
2. **Proof chain root hash** — the hash of a Dacite vector containing
   `[root, h1, h2, ..., target]`, proving that `target` is reachable from
   the client's root through the DAG structure

The proof chain is always a Dacite vector. Small chains (≤32 hashes) are
transmitted by value in a single response per the spec. Larger chains are
fetched lazily by the server from the client.

The server verifies each link: node at `h_n` must contain a reference to
`h_{n+1}`. The server is stateless — each request is verified independently.

### Dedicated Stores (Sandboxed Interaction)

When the server needs to fetch the proof chain from the client (via `s-get`),
a question arises: what prevents the server from accessing other client data?

The answer: the client creates a **dedicated store** containing only the nodes
relevant to the interaction. The server can freely `s-get` anything from this
store — there is nothing else in it.

1. Client builds the proof chain (a Dacite vector of hashes)
2. Client creates a dedicated store containing *only* the chain's nodes
3. Client provides the server with the chain root hash and access to the
   dedicated store
4. Server fetches and verifies the chain from the dedicated store
5. Server returns the requested node

The dedicated store is a **sandbox per interaction** — the client controls
exactly what surface area the server can see. No frontier tracking, no
per-session state on the server.

### Properties

- **Stateless server.** Each request is self-contained. No per-session state,
  no frontier sets to maintain. Any server instance can handle any request.
- **No ACLs.** Authorization is derived from structure. If a node is reachable
  from your root, you can reach it.
- **Client-scoped isolation.** The dedicated store exposes only what the client
  chooses. The server cannot access unrelated client data.
- **Structural sharing is safe.** Two users may share the same subtree (same
  hash). Each proves access through their own root. The server doesn't care
  that the underlying node is shared — authorization paths are independent.
- **Natural scoping.** Sharing a subtree root grants access to everything below
  it, nothing above it. Delegation is just sharing a hash.
- **Revocation** is achieved by restructuring: build a new root that omits the
  revoked subtree (e.g., `dissoc`). No negative authorization needed.

## Peer-to-Peer Store Model

Both client and server are stores. The `IStore` protocol is the universal
interface for all data exchange.

**Client and server are peers in a network of stores.**

- **Reads:** Client authenticates → receives root hash → builds proof chain →
  creates dedicated store with chain nodes → server verifies chain via
  `s-get` from dedicated store → server returns requested node
- **Writes:** Client builds new value locally → creates dedicated store with
  new/changed subtree nodes → declares new root hash → server fetches new
  nodes via `s-get` from dedicated store → server updates root pointer

Both directions use the same `IStore` protocol. The dedicated store pattern
ensures isolation in both cases.

### Implications

- No special "auth channel" — just store operations
- No server-side session state — stateless verification
- Topologies compose: peers in a network, each creating dedicated stores
  as needed for interactions
- The `IStore` protocol remains the universal interface

## Writes: Root Replacement

The immutable nature of Dacite values means **all mutations redefine the root
hash.** There is no in-place update.

1. Client fetches current value from server, makes changes locally
2. Client computes new root hash for the modified value
3. Client creates a dedicated store containing the new/changed subtree nodes
4. Client declares the new root hash to the server
5. Server fetches new nodes via `s-get` from the dedicated store
6. Server updates the user's root pointer to the new hash

**`s-put` may not be needed.** The client declares a new root hash and provides
a dedicated store; the server pulls whatever it's missing.

Write authorization is by identity: if your auth token entitles you to update
your root pointer, you can declare any new root hash.

## Root Management

Root hash pointers are a **service-layer** concern, not a store-layer concern.
The `IStore` protocol remains purely content-addressed. Root management
(binding a user identity to a root hash, updating root pointers) belongs to a
higher-level service protocol.

## Delegation

A root hash can be designated as an independent entry point with its own
authorization token. Mint a token for a subtree root, hand it to another party,
and they prove access from that subtree root via proof chains.

This is equivalent to giving someone their own "account" rooted at a subtree
of your data.

## Audit Trail

Proof chains are Dacite values (immutable, content-addressed). The server can
retain proof chain hashes as access records, forming a natural audit log of
what paths were used to access what data.

## Garbage Collection: Store Migration

Content-addressed stores are append-only: nodes are added but never modified.
Over time, mutations (new roots replacing old ones) leave orphaned nodes —
subtrees no longer reachable from any active root.

Rather than tracking references or marking nodes, Dacite uses **store
migration** — a copying garbage collector:

1. Create a new empty store B
2. Walk every active root hash, copying reachable nodes from A to B
3. Swap B in for A
4. Discard A

Everything unreachable — orphaned subtrees, old versions, abandoned nodes —
simply doesn't get copied.

### Properties

- **No bookkeeping.** No reference counts, no mark bits, no tombstones. The
  walk is the GC.
- **Cost proportional to live data.** You pay for what you keep, not what you
  discard. Same property as a copying garbage collector.
- **Structural sharing preserved.** If two roots share a subtree, the shared
  nodes are copied once (store B deduplicates by hash on insert).
- **Simple correctness.** A node is live if and only if it's reachable from an
  active root. No edge cases.

### Implementation Considerations

- **Offline vs. online.** Simple version pauses writes during migration. Online
  version writes to both A and B during the copy, then swaps.
- **Frequency.** Scheduled (nightly), triggered by size threshold, or manual.
- **Scope.** The walk visits every active root — all users, all delegated
  subtree roots. The set of active roots is maintained by the service layer.

These are implementation details. The model is straightforward: live data is
what's reachable; everything else is garbage.

## Relationship to Roadmap

This design sits between:
- **Store Protocol** (§2 in roadmap) — `IStore` is the foundation
- **Remote Store** (§3 in roadmap) — authorization is required before remote
  `s-get` makes sense
- **Content Negotiation** (§3 in roadmap) — proof chain and dedicated store
  behavior could be negotiated aspects of the transport

Authorization should be specced before or alongside the remote store
implementation.

---

## Appendix A: Rejected Alternative — Frontier Model

An intermediate draft used a **frontier model** for authorization. This
section documents the approach and why it was replaced.

### How It Worked

Each `s-get` response implicitly authorized the next level down. The serving
store maintained a **frontier set** per session: child hashes revealed but not
yet fetched. When a hash was fetched, it left the frontier and its children
were added.

This eliminated proof chains entirely — the walk from root to target *was*
the authorization, verified step by step.

### Why It Was Rejected

1. **Server-side statefulness.** The frontier set is per-session state that
   the server must maintain. This complicates scaling, load balancing, and
   failover. Stateless servers are simpler and more robust.

2. **Dedicated stores solve the same problem better.** The frontier was
   invented to scope what the server could access when fetching from the
   client. A dedicated store achieves the same isolation without any
   server state — the client simply doesn't put anything else in it.

3. **Re-fetch complexity.** Once a hash left the frontier, the peer had to
   re-walk from a parent to re-open it. This added complexity to both
   client and server implementations.

## Appendix B: Design Evolution

The authorization design went through three iterations:

1. **Proof chains only.** Client pre-computes a path from root to target.
   Problem: when the server fetches the chain from the client via `s-get`,
   what scopes the server's access to client data?

2. **Frontier model.** Invented to solve the scoping problem. Each fetch
   reveals children, authorization is transitive. Problem: requires
   per-session server state, which is a scaling concern.

3. **Proof chains + dedicated stores.** Returns to proof chains but solves
   the scoping problem differently: the client creates an isolated store
   containing only the relevant nodes. Server is stateless; client controls
   the surface area. This is the current design.

The frontier model was the scaffolding that revealed the real solution —
dedicated stores. Similar to how LISP was meant to be scaffolding for a
"real" language, until `eval` showed that the scaffolding was the thing.
