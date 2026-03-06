# Authorization Design — Store Access Control

*Draft: 2026-03-05. From discussion between Jonathan and Gorm.*

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
2. **Proof chain** — a sequence of hashes `[root, h1, h2, ..., target]` proving
   that `target` is reachable from `root` through the DAG structure

The server verifies each link: node at `h_n` must contain a reference to `h_{n+1}`.

### Properties

- **No ACLs.** Authorization is derived from structure. If a node is in your tree,
  you can reach it.
- **Structural sharing is safe.** Two users may share the same subtree (same hash).
  Each proves access through their own root. The server doesn't care that the
  underlying node is shared — authorization paths are independent.
- **Natural scoping.** Sharing a subtree root with someone grants access to
  everything below it, nothing above it. Delegation is just sharing a hash.
- **Revocation** is possible by restructuring: change your tree so the revoked
  subtree is no longer reachable from your root.

## Proof Chain Optimization

For shallow values, the proof chain is short and can be sent inline in a
request header.

For deeply nested values (chain length > 32), the proof chain itself is
represented as a **Dacite vector** — a sequence of hashes stored as a Dacite
value. The request includes only the chain's root hash. The server fetches
chain segments as needed.

**The authorization proof is itself a Dacite value.** Turtles all the way down.

### Chunking Strategy

| Chain length | Strategy                                    |
|-------------|---------------------------------------------|
| ≤ 32        | Inline in request header                    |
| > 32        | Dacite vector; send chain root hash only    |

## Peer-to-Peer Store Model

To resolve the bootstrap problem (server needs the proof chain, but the chain
is a Dacite value requiring authorization to access), the client advertises
itself as a store to the server.

**Client and server are peers in a network of stores.**

- Client requests hash `h` with proof chain hash `c`
- Server needs to verify `c` → fetches chain segments from client's store
  (via the same `IStore` protocol)
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

## Open Questions

1. **Server-side proof caching.** Once the server verifies you can reach `h5`
   from root, should it cache that fact for the session? Avoids re-verification
   on repeated access. Trade-off: memory vs. round trips.

2. **Write authorization.** Does `s-put` use the same chain model? Writing new
   nodes doesn't have a pre-existing path from root. Possibly: write is
   authorized by proving you own the root that *will* reference the new node
   (i.e., you're building a new version of your tree).

3. **Chain verification cost.** Walking the chain requires fetching and
   inspecting intermediate nodes. For large trees this could be expensive.
   Could Merkle inclusion proofs compress the verification?

4. **Delegation tokens.** Could a user mint a signed token saying "bearer may
   access anything reachable from hash X"? This would avoid chain transmission
   entirely for delegated access, at the cost of introducing a signing layer.

5. **Negative authorization.** Can you *exclude* subtrees? E.g., share your
   root but mark certain branches as off-limits. This would require explicit
   deny rules, breaking the pure structural model.

6. **Proof chain as audit trail.** Since the chain is a Dacite value, it's
   immutable and content-addressed. This naturally creates an audit log of
   what paths were used to access what data.

## Relationship to Roadmap

This design sits between:
- **Store Protocol** (§2 in roadmap) — `IStore` is the foundation
- **Remote Store** (§3 in roadmap) — authorization is required before remote
  `s-get` makes sense
- **Content Negotiation** (§3 in roadmap) — proof chains could be a negotiated
  aspect of the transport

Authorization should be specced before or alongside the remote store
implementation.
