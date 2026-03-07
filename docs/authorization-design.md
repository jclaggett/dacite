# Authorization Design — Store Access Control

*Draft: 2026-03-05. Revised: 2026-03-06. From discussion between Jonathan and Gorm.*

## Core Principle

**Knowing a hash does not authorize access to its value.**

Content-addressed systems tempt you into treating hash-knowledge as a capability.
Dacite rejects this. Authorization is structural: you prove you can *reach* a hash
from a root you're entitled to.

## Model

### Authentication

Users authenticate to a store service (e.g., `https://dacite.io/store`) using
standard credentials (token, OAuth, etc.). Upon authentication, the server
declares the user's **root hash** — the entry point to their data.

### Authorization via Frontier

Dacite uses a single authorization model for all data exchange: the **frontier
model.** Both client and server use the same model when serving data to a peer.

**Rule: each `s-get` response implicitly authorizes the next level down.**

1. A store declares a root hash to a peer (e.g., server declares the user's
   root hash upon authentication)
2. Peer does `s-get root` → gets node containing hashes `[h1, h2, h3]`
3. `h1`, `h2`, `h3` are now authorized (revealed by an authorized node)
4. Peer does `s-get h2` → gets node containing `[h4, h5]`
5. `h4`, `h5` are now authorized
6. And so on

The serving store maintains a **frontier set** per session: the set of child
hashes that have been revealed but not yet fetched. When a hash is fetched, it
is removed from the frontier and its children are added.

**There is no separate proof chain mechanism.** The sequence of `s-get` calls
from root to target *is* the proof — verified step by step as it happens. The
server witnesses the path in real time through the frontier.

### Frontier Properties

- **Bounded size.** The frontier contains only unretrieved leaf-most hashes.
  Its maximum size is bounded by branching factor × depth being explored
  (e.g., ~32 for a HAMT level), not the total tree size.
- **Session-scoped.** The frontier is discarded when the session ends. A TTL
  or session timeout handles cleanup.
- **No pre-traversal.** Neither side needs to traverse the tree upfront to
  authorize access. Authorization emerges naturally from the fetch path.
- **Re-fetch requires re-walk.** Once a hash leaves the frontier (it was
  fetched and its children replaced it), the peer cannot re-fetch it without
  first re-fetching parent hashes to re-open the frontier. This is acceptable
  — any change to the user's value changes the root hash, requiring a fresh
  walk from root regardless.
- **Symmetric.** The same model applies in both directions. Server maintains
  a frontier for each client session; client maintains a frontier for each
  server session.

### Future: Batched Fetches

The frontier model requires the client to walk from root to target, which
means O(depth) round trips for deeply nested values. Future optimization:
batch multiple `s-get` calls in a single request to reduce round trips.
The frontier still advances the same way — each response reveals children
for the next batch.

### Properties

- **No ACLs.** Authorization is derived from structure. If a node is in your
  tree, you can reach it.
- **Structural sharing is safe.** Two users may share the same subtree (same
  hash). Each proves access through their own root. The server doesn't care
  that the underlying node is shared — authorization paths are independent.
- **Natural scoping.** Sharing a subtree root with someone grants access to
  everything below it, nothing above it. Delegation is just sharing a hash.
- **Revocation** is achieved by restructuring: build a new root that omits the
  revoked subtree (e.g., `dissoc`). The old root hash becomes inaccessible
  once the user's root pointer is updated. No negative authorization needed.

## Peer-to-Peer Store Model

Both client and server are stores. The `IStore` protocol is the universal
interface for all data exchange.

**Client and server are peers in a network of stores.**

- Client authenticates → server declares client's root hash
- Client walks from root to target via `s-get` calls (frontier model)
- For writes: client declares new root → server walks new subtree via `s-get`
  from client (same frontier model, reversed)

Both directions use the same `IStore` protocol and the same frontier
authorization model. The only asymmetry is **policy**: who declares roots
and under what conditions.

### Implications

- **One authorization concept.** No proof chains, no ACLs, no capability
  tokens — just the frontier.
- No special "auth channel" — just store operations
- No push step — data flows via `s-get` in both directions
- Topologies compose: peers in a network, each maintaining frontiers for
  their active sessions
- The `IStore` protocol remains the universal interface

## Writes: Root Replacement via s-get

The immutable nature of Dacite values means **all mutations redefine the root
hash.** There is no in-place update.

Write flow:

1. Client fetches current value from server, makes changes locally (e.g.,
   `assoc` a new key into a map)
2. Client computes new root hash for the modified value
3. Client declares the new root hash to the server
4. Server walks the new subtree via `s-get` from the client (frontier model)
5. Server updates the user's root pointer to the new hash

**`s-put` may not be needed.** The client only needs to declare a new root
hash. The server pulls whatever it's missing. This preserves the peer model:
data flows via `s-get` in both directions.

### Write Authorization

Authorized by identity: if your auth token entitles you to update your root
pointer, you can declare any new root hash. The server fetches the new subtrees
and adopts the new root.

### Root Management

Root hash pointers are a service-layer concern, not a store-layer concern.
The `IStore` protocol remains purely content-addressed. Root management
(binding a user identity to a root hash, updating root pointers) belongs to
a higher-level service protocol.

## Delegation

A root hash can be designated as an independent entry point with its own
authorization token. This enables delegation without the full authentication
flow: mint a token for a subtree root, hand it to another party, and they
begin their frontier walk from that subtree root.

This is equivalent to giving someone their own "account" rooted at a subtree
of your data.

## Proof Chain as Audit Trail

The sequence of `s-get` calls in a session naturally forms an audit trail:
the server knows exactly which path the client walked from root to target.
This can be retained as an access log.

## Relationship to Roadmap

This design sits between:
- **Store Protocol** (§2 in roadmap) — `IStore` is the foundation
- **Remote Store** (§3 in roadmap) — authorization is required before remote
  `s-get` makes sense
- **Content Negotiation** (§3 in roadmap) — frontier behavior could be a
  negotiated aspect of the transport

Authorization should be specced before or alongside the remote store
implementation.
