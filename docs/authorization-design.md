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
declares the user's **root hash** — the entry point to their data.

## Authorization: The Frontier Model

Dacite uses a single authorization model for all data exchange: the **frontier
model.** Both sides of any interaction use the same model when serving data.

### How It Works

A session begins when one store declares a root hash to a peer. The peer may
then walk the tree by fetching nodes, and each fetch reveals the next level:

1. Store declares root hash `r` to peer
2. Peer does `s-get r` → gets node containing child hashes `[h1, h2, h3]`
3. `h1`, `h2`, `h3` are now authorized (revealed by an authorized node)
4. Peer does `s-get h2` → gets node containing `[h4, h5]`
5. `h4`, `h5` are now authorized
6. And so on, down the tree

The serving store maintains a **frontier set** per session: the set of child
hashes that have been revealed but not yet fetched. When a hash is fetched, it
is removed from the frontier and its children are added.

### Properties

- **No ACLs.** Authorization is derived from structure. If a node is reachable
  from your root, you can reach it.
- **Symmetric.** The same model applies in both directions. Server maintains a
  frontier for each client; client maintains a frontier for each server.
- **Bounded.** The frontier contains only unretrieved leaf-most hashes. Its
  maximum size is bounded by branching factor × exploration depth (e.g., ~32
  for a HAMT level), not the total tree size.
- **Session-scoped.** The frontier is discarded when the session ends.
- **No pre-traversal.** Neither side needs to traverse the tree upfront.
  Authorization emerges naturally from the fetch path.
- **Re-fetch requires re-walk.** Once a hash leaves the frontier, the peer
  must re-walk from a parent to re-open it. This is acceptable — any mutation
  changes the root hash, requiring a fresh walk regardless.
- **Structural sharing is safe.** Two users may share the same subtree (same
  hash). Each accesses it through their own root via their own frontier.
- **Natural scoping.** Sharing a subtree root grants access to everything below
  it, nothing above it. Delegation is just sharing a hash.
- **Revocation** is achieved by restructuring: build a new root that omits the
  revoked subtree (e.g., `dissoc`). No negative authorization needed.

### Future: Batched Fetches

Walking from root to a deeply nested target requires O(depth) round trips.
Future optimization: batch multiple `s-get` calls in a single request. The
frontier advances the same way — each response reveals children for the next
batch.

## Peer-to-Peer Store Model

Both client and server are stores. The `IStore` protocol is the universal
interface for all data exchange.

**Client and server are peers in a network of stores.**

- **Reads:** Client authenticates → server declares root hash → client walks
  tree via `s-get` (server maintains frontier)
- **Writes:** Client declares new root hash → server walks new subtree via
  `s-get` from client (client maintains frontier)

Both directions use the same `IStore` protocol and the same frontier model.
The only asymmetry is **policy**: who declares roots and under what conditions.

## Writes: Root Replacement

The immutable nature of Dacite values means **all mutations redefine the root
hash.** There is no in-place update.

1. Client fetches current value from server, makes changes locally
2. Client computes new root hash for the modified value
3. Client declares the new root hash to the server
4. Server walks the new subtree via `s-get` from the client (frontier model)
5. Server updates the user's root pointer to the new hash

**`s-put` may not be needed.** The client declares a new root hash; the server
pulls whatever it's missing. Data flows via `s-get` in both directions.

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
and they begin their frontier walk from that subtree root.

This is equivalent to giving someone their own "account" rooted at a subtree
of your data.

## Audit Trail

The sequence of `s-get` calls in a session naturally forms an audit trail: the
server knows exactly which path the client walked from root to target. This can
be retained as an access log.

## Relationship to Roadmap

This design sits between:
- **Store Protocol** (§2 in roadmap) — `IStore` is the foundation
- **Remote Store** (§3 in roadmap) — authorization is required before remote
  `s-get` makes sense
- **Content Negotiation** (§3 in roadmap) — frontier behavior could be a
  negotiated aspect of the transport

Authorization should be specced before or alongside the remote store
implementation.

## Garbage Collection: Store Migration

Content-addressed stores are append-only: nodes are added but never modified.
Over time, mutations (new roots replacing old ones) leave orphaned nodes —
subtrees no longer reachable from any active root.

### Approach: Copying Collection

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

---

## Appendix A: Rejected Alternative — Proof Chain Model

An earlier draft used a **proof chain** model for read authorization. This
section documents the approach and why it was rejected in favor of the
frontier model.

### How It Worked

To fetch a node, the client would pre-compute a **proof chain**: a Dacite
vector of hashes `[root, h1, h2, ..., target]` showing that the target is
reachable from the client's root through the DAG structure. The client would
send the proof chain hash alongside its auth token and the target hash. The
server would verify each link in the chain before returning the target node.

For short chains (≤32 hashes), the entire chain would be transmitted by value
in a single request. For longer chains, the chain itself — being a Dacite
vector — would be fetched lazily by the server from the client's store.

### Why It Was Rejected

1. **Unnecessary complexity.** The proof chain is a second authorization
   mechanism alongside the frontier. The frontier model alone is sufficient
   for both reads and writes.

2. **Redundant work.** The proof chain is equivalent to the sequence of
   `s-get` calls the client makes when walking from root to target. The
   frontier model captures the same proof implicitly, verified step by step.

3. **Client burden.** The client must pre-compute and transmit the chain,
   adding complexity to the client implementation.

4. **The frontier is simpler.** One model, both directions, same rules. The
   proof chain added a second concept that served the same purpose — trading
   round trips for upfront computation. Future batched `s-get` can recover
   the round-trip savings without a separate mechanism.
