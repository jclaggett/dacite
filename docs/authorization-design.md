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

### Authorization via Proof Chains

To fetch any node, a client must provide:

1. **Auth token** — proves identity (who you are)
2. **Proof chain hash** — the hash of a Dacite vector containing
   `[root, h1, h2, ..., target]`, proving that `target` is reachable from
   `root` through the DAG structure

The proof chain is always a Dacite vector. Small chains (≤32 hashes) are
transmitted by value in a single response per the spec. Larger chains are
fetched lazily like any other Dacite value.

The server verifies each link: node at `h_n` must contain a reference to
`h_{n+1}`.

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
- Server needs to verify `c` → fetches the proof chain vector from the
  client's store (via `s-get`)
- Server walks the chain, confirming reachability from root to `h`
- Server returns node at `h`

Both directions use the same `IStore` protocol. The only asymmetry is **policy**:
the server demands proof before serving; the client serves proof freely (to the
server it authenticated with).

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
3. Client sends the new root hash to the server
4. Server uses `s-get` to fetch new/changed nodes from the client's store
5. Server updates the user's root pointer to the new hash

**`s-put` may not be needed.** The client only needs to present a new root
hash. The server pulls whatever it's missing. This preserves the peer model:
data flows via `s-get` in both directions.

### Write Authorization

Authorized by identity: if your auth token entitles you to update your root
pointer, you can present any new root hash. The server fetches the new subtrees
and adopts the new root. No proof chain needed for writes — the act of
updating your own root *is* the authorization.

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
