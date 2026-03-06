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
provides the user's **root hash** — the entry point to their data.

### Two Authorization Models

Dacite uses two complementary authorization models depending on the direction
of data flow:

1. **Proof chain model** — used when requesting data from a store
2. **Frontier model** — used when serving data to a peer

These are not competing models. They address different roles in the same
interaction.

### Authorization via Proof Chains (Requesting Data)

To fetch a node from a store, a client must provide:

1. **Auth token** — proves identity (who you are)
2. **Proof chain hash** — the hash of a Dacite vector containing
   `[root, h1, h2, ..., target]`, proving that `target` is reachable from
   `root` through the DAG structure

The proof chain is always a Dacite vector. Small chains (≤32 hashes) are
transmitted by value in a single response per the spec. Larger chains are
fetched lazily like any other Dacite value.

The server verifies each link: node at `h_n` must contain a reference to
`h_{n+1}`.

**The requester always bears the burden of proof.**

### Authorization via Frontier (Serving Data)

When a store serves data to a peer (e.g., a client serving proof chains or
new subtrees to a server), it uses the **frontier model** to scope what the
peer may access.

The interaction begins when the serving side declares a root hash (e.g., a
proof chain hash or a new root being proposed). The peer may then fetch that
root and discover child hashes within it.

**Rule: each `s-get` response implicitly authorizes the next level down.**

1. Client declares root hash `r` to server
2. Server does `s-get r` → gets node containing hashes `[h1, h2, h3]`
3. `h1`, `h2`, `h3` are now authorized (they were revealed by an authorized
   node)
4. Server does `s-get h2` → gets node containing `[h4, h5]`
5. `h4`, `h5` are now authorized
6. And so on

The serving store maintains a **frontier set**: the set of child hashes that
have been revealed but not yet fetched. When a hash is fetched, it is removed
from the frontier and its children are added.

#### Frontier Properties

- **Bounded size.** The frontier contains only unretrieved leaf-most hashes.
  Its maximum size is bounded by branching factor × depth being explored
  (e.g., ~32 for a HAMT level), not the total tree size.
- **Session-scoped.** The frontier is discarded when the session ends. A TTL
  or session timeout handles cleanup.
- **No pre-traversal.** Neither side needs to traverse the tree upfront to
  authorize access. Authorization emerges naturally from the fetch path.
- **Re-fetch requires re-walk.** Once a hash leaves the frontier (it was
  fetched and its children replaced it), the peer cannot re-fetch it without
  first re-fetching the parent hashes to re-open the frontier. This is
  acceptable and may be unavoidable — the frontier is a one-pass window.

**The server bears the burden of scoping its own fetches.**

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

- Client requests hash `h` with auth token and proof chain hash `c`
- Server needs the proof chain → does `s-get c` from client (frontier model:
  client authorizes server to walk the chain)
- Server verifies the chain, confirming reachability from root to `h`
- Server returns node at `h`

Both directions use the same `IStore` protocol. The only asymmetry is **policy**:
the requester provides proof chains; the server provides data after verification.

### Implications

- No special "auth channel" — just store operations
- No push step — server pulls what it needs
- Topologies compose: a third peer could hold proof chains, or authorization
  policies could themselves be Dacite values shared across the network
- The `IStore` protocol remains the universal interface

## Writes: Root Replacement via s-get

The immutable nature of Dacite values means **all mutations redefine the root
hash.** There is no in-place update.

Write flow:

1. Client fetches current value from server, makes changes locally (e.g.,
   `assoc` a new key into a map)
2. Client computes new root hash for the modified value
3. Client declares the new root hash to the server
4. Server uses `s-get` to fetch new/changed nodes from the client's store
   (frontier model: client authorizes server to walk the new subtree)
5. Server updates the user's root pointer to the new hash

**`s-put` may not be needed.** The client only needs to declare a new root
hash. The server pulls whatever it's missing. This preserves the peer model:
data flows via `s-get` in both directions.

### Write Authorization

Authorized by identity: if your auth token entitles you to update your root
pointer, you can declare any new root hash. The server fetches the new subtrees
and adopts the new root. No proof chain needed for writes — the act of
updating your own root *is* the authorization.

### Root Management

Root hash pointers are a service-layer concern, not a store-layer concern.
The `IStore` protocol remains purely content-addressed. Root management
(binding a user identity to a root hash, updating root pointers) belongs to
a higher-level service protocol.

## Delegation

A root hash can be designated as an independent entry point with its own
authorization token. This enables delegation without proof chains: mint a
token for a subtree root, hand it to another party, and they access that
subtree directly.

This is equivalent to giving someone their own "account" rooted at a subtree
of your data.

## Server-Side Proof Caching

The server may cache verified proof chains in its own store. Implementation
details (separate store, TTL policies, eviction) are left to the
implementation. The important property: caching is an optimization, not a
requirement. The protocol works without it.

## Proof Chain as Audit Trail

Since proof chains are Dacite values (immutable, content-addressed), they
naturally form an audit log of what paths were used to access what data.
The server can retain proof chain hashes as access records.

## Relationship to Roadmap

This design sits between:
- **Store Protocol** (§2 in roadmap) — `IStore` is the foundation
- **Remote Store** (§3 in roadmap) — authorization is required before remote
  `s-get` makes sense
- **Content Negotiation** (§3 in roadmap) — proof chains could be a negotiated
  aspect of the transport

Authorization should be specced before or alongside the remote store
implementation.
