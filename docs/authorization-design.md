# Authorization Design — Store Access Control

*Draft: 2026-03-05. Revised: 2026-03-07. From discussion between Jonathan and Gorm.*

## Core Principle

**Knowing a hash does not authorize access to its value.**

Content-addressed systems tempt you into treating hash-knowledge as a capability.
Dacite rejects this. Authorization is structural: you prove you can *reach* a
hash from a root you're entitled to.

## Authentication

Users authenticate to a store service (e.g., `https://dacite.io/store`) using
standard credentials (token, OAuth, etc.). Upon authentication, the server
provides the user's **root hash** and a **session token**.

## Two Stores, Two Auth Levels

A Dacite service exposes two stores to each authenticated client:

### Session Store

- **Authorization:** session token only (no proof chain required)
- **Scope:** per-session, ephemeral — lives and dies with the session
- **Contents:** proof chains, session metadata
- **Purpose:** holds the authorization data itself

The session store doesn't need proof chains because it IS the proof chain
store. Proving you can access proof chains with proof chains would be circular.

### Main Store

- **Authorization:** session token + valid proof chain
- **Scope:** persistent, shared across all users
- **Contents:** all user data (Dacite values)
- **Purpose:** holds the actual data

Every `s-get` against the main store requires a proof chain proving that
the requested hash is reachable from the client's authenticated root.

## Proof Chains

A proof chain is a Dacite vector of hashes `[root, h1, h2, ..., target]`
proving that `target` is reachable from `root` through the DAG structure.

To fetch a node from the main store, the client provides:

1. **Session token** — proves identity and session
2. **Proof chain** — proves structural reachability from root to target

The server verifies each link: node at `h_n` must contain a reference to
`h_{n+1}`.

### Example: Tree and Proof Chain Walk

Consider a Dacite map `{"name" "Alice", "scores" [10, 20, 30]}`:

```
                    ┌─────────────────┐
                    │   map (root)     │
                    │     #R           │
                    └────┬────────┬───┘
                         │        │
              ┌──────────┘        └──────────┐
              ▼                              ▼
    ┌──────────────┐               ┌──────────────┐
    │ hamt/entry   │               │ hamt/entry   │
    │ "name"→      │               │ "scores"→    │
    │  #E1         │               │  #E2         │
    └──┬──────┬────┘               └──┬──────┬────┘
       │      │                       │      │
       ▼      ▼                       ▼      ▼
    ┌─────┐ ┌───────┐             ┌────────┐ ┌──────────┐
    │"name"│ │"Alice"│             │"scores"│ │ vector   │
    │ #K1  │ │ #V1   │             │  #K2   │ │   #V2    │
    └──────┘ └───────┘             └────────┘ └────┬─────┘
                                                   │
                                              ┌────┼────┐
                                              ▼    ▼    ▼
                                           ┌────┐┌────┐┌────┐
                                           │ 10 ││ 20 ││ 30 │
                                           │#S1 ││#S2 ││#S3 │
                                           └────┘└────┘└────┘
```

To read the value `30` (#S3), the client builds a proof chain by walking
from root to target:

```
  Proof chain: [#R, #E2, #V2, #S3]

  Verification (server checks each link):
    #R  contains #E2?  → yes (hamt/entry child of map root)
    #E2 contains #V2?  → yes (val-ref of "scores" entry)
    #V2 contains #S3?  → yes (element of the vector)
    ✓ chain valid — serve node at #S3
```

### Read Flow (client reads from server)

```
  Client                              Server
    │                                    │
    │  1. authenticate                   │
    │ ──────────────────────────────────► │
    │  ◄── session token + root hash #R  │
    │                                    │
    │  2. s-get #S3                      │
    │     proof chain: [#R, #E2, #V2, #S3]
    │ ──────────────────────────────────► │
    │     verify chain against main store│
    │     #R→#E2→#V2→#S3 ✓              │
    │  ◄── ["i64" 30]                    │
    │                                    │
```

### Write Flow (server fetches new nodes from client)

```
  Client                              Server
    │                                    │
    │  1. declare new root #R'           │
    │ ──────────────────────────────────► │
    │                                    │
    │  2. s-get #R'                      │
    │     (server needs new root node)   │
    │  ◄──────────────────────────────── │
    │     proof chain: [#R']             │
    │     verify: trivially valid        │
    │  ──► ["map" {...}]                 │
    │                                    │
    │  3. s-get #E3 (new child)          │
    │  ◄──────────────────────────────── │
    │     proof chain: [#R', #E3]        │
    │     verify: #R' contains #E3? ✓   │
    │  ──► ["hamt/entry" {...}]          │
    │                                    │
    │  4. server already has #K1, #V1... │
    │     (stops walking — no more gets) │
    │                                    │
    │  5. root pointer updated to #R'    │
    │  ◄── ack                           │
    │                                    │
```

### Structural Sharing: Two Users, One Subtree

```
  User A's root          User B's root
    #RA                    #RB
     │                      │
     ├── "docs" ──┐    ┌── "refs" ──┐
     │            ▼    ▼            │
     │      ┌──────────────┐       │
     │      │ shared map   │       │
     │      │    #SM       │       │
     │      └──────┬───────┘       │
     │             │               │
     │        ┌────┼────┐          │
     │        ▼    ▼    ▼          │
     │      [nodes shared by both] │
     │                             │

  User A's chain to #SM: [#RA, ..., #SM]  ✓
  User B's chain to #SM: [#RB, ..., #SM]  ✓

  Same data, independent authorization paths.
  Neither user can access the other's unshared data.
```

### Transmission

- **Small chains (≤32 hashes):** transmitted inline with the request
- **Large chains (>32 hashes):** stored as a Dacite vector in the client's
  session store. The client sends the chain's root hash; the server fetches
  the chain from the session store (no proof chain needed — session store
  access is authorized by session token alone)

### Properties

- **No ACLs.** Authorization is derived from structure.
- **Structural sharing is safe.** Two users may share the same subtree.
  Each proves access through their own root.
- **Natural scoping.** Sharing a subtree root grants access to everything
  below it, nothing above it. Delegation is just sharing a hash.
- **Revocation** is achieved by restructuring: build a new root that omits
  the revoked subtree (e.g., `dissoc`). No negative authorization needed.

## Peer-to-Peer Store Model

Both client and server are stores. The `IStore` protocol is the universal
interface for all data exchange. **Data is always transmitted using proof
chains.**

**Client and server are peers in a network of stores.**

### Reads (client fetches from server)

1. Client builds proof chain from root to target
2. Client stores chain in session store (if large) or sends inline
3. Server verifies chain against its own main store
4. Server returns the requested node

### Writes (server fetches from client)

1. Client modifies data locally, computes new root hash
2. Client declares new root hash to server
3. Server walks from new root, building proof chains as it discovers
   nodes it doesn't have
4. Client verifies server's proof chains against its own store
5. Client serves requested nodes
6. Server updates the user's root pointer

Both directions use proof chains. Both directions use `s-get`. The only
asymmetry is policy: who declares roots and under what conditions.

### Implications

- Data always flows via `s-get` + proof chain — one pattern, both directions
- Session stores handle proof chain exchange without circular auth
- The `IStore` protocol remains the universal interface
- Topologies compose: peers in a network, each with their own session stores

## Writes: Root Replacement

The immutable nature of Dacite values means **all mutations redefine the root
hash.** There is no in-place update.

**`s-put` may not be needed.** The client declares a new root hash; the server
pulls whatever it's missing via proof-chain-authorized `s-get` calls against
the client.

Write authorization is by identity: if your session token entitles you to
update your root pointer, you can declare any new root hash.

## Root Management

Root hash pointers are a **service-layer** concern, not a store-layer concern.
The `IStore` protocol remains purely content-addressed. Root management
(binding a user identity to a root hash, updating root pointers) belongs to a
higher-level service protocol.

## Delegation

A root hash can be designated as an independent entry point with its own
authorization token. Mint a token for a subtree root, hand it to another
party, and they prove access from that subtree root via proof chains.

This is equivalent to giving someone their own "account" rooted at a subtree
of your data.

## Dedicated Stores (Selective Sharing)

Beyond the session store, clients can create **dedicated stores** for
selective sharing scenarios — exposing a curated subset of data to a peer
without revealing anything else. Like showing a hand of cards: you control
exactly what the other side can see.

Dedicated stores are a general-purpose tool, not part of the core auth flow.

## Audit Trail

The sequence of proof chains submitted during a session forms a natural audit
trail: the server knows exactly which paths the client walked from root to
each target. Proof chains are Dacite values (immutable, content-addressed)
and can be retained as access records.

## Garbage Collection

Content-addressed stores are append-only: nodes are added but never modified.
Over time, mutations (new roots replacing old ones) leave orphaned nodes —
subtrees no longer reachable from any active root.

Dacite GC is a **semi-space collector**: live data is identified by walking
all active roots, and everything else is garbage. There are two equivalent
strategies, differing only in where the "mark" lives.

### Strategy 1: Store Migration (Copying Collection)

The mark is **presence in the new store.**

1. Create a new empty store B
2. Walk every active root hash, copying reachable nodes from A to B
3. Swap B in for A
4. Discard A

Everything unreachable simply doesn't get copied.

### Strategy 2: Color Marking (Mark-and-Sweep)

The mark is **a color bit on each node** (red or green).

1. Walk all active root hashes, marking reachable nodes red. New writes
   are also red.
2. When all roots are walked, cull all green nodes.
3. Next cycle: walk roots marking green, new writes green, cull red.
4. Repeat, alternating colors.

### Equivalence

These are the same operation expressed differently:

| | Store Migration | Color Marking |
|---|---|---|
| Mark live | Copy to store B | Set to current color |
| Identify dead | Not in store B | Still previous color |
| Reclaim | Discard store A | Delete previous-color nodes |
| Two spaces | Store A / Store B | Red / Green |

Both are semi-space collectors. Store migration uses two physical stores as
half-spaces; color marking uses two logical spaces (colors) within a single
store.

### Online GC (No Downtime)

Both strategies support online collection without pausing writes:

**Store migration (online):**
- Writes go to store B (the new store)
- Reads try B first, fall back to A
- Background migration walks roots, copying from A to B
- When done, drop A

**Color marking (online):**
- New writes use the current live color
- Background walk marks reachable nodes with the live color
- When walk completes, cull nodes with the dead color
- No second store needed

### Properties

- **No reference counting.** No per-node bookkeeping during normal operations.
- **Cost proportional to live data.** You pay for what you keep, not what you
  discard.
- **Structural sharing preserved.** Shared subtrees are visited once
  (deduplication by hash).
- **Simple correctness.** A node is live if and only if it's reachable from an
  active root. No edge cases.

### Trade-offs

- **Store migration** needs 2× storage during the copy but requires no
  per-node metadata. Conceptually simpler.
- **Color marking** needs only 1 bit per node but requires in-place mutation
  of the KV store (reading/writing color flags) and a scan for the cull step.

### Implementation Considerations

- **Frequency.** Scheduled (nightly), triggered by size threshold, or manual.
- **Scope.** The walk visits every active root — all users, all delegated
  subtree roots. The set of active roots is maintained by the service layer.

The choice between strategies is an implementation detail. The model is the
same: live data is what's reachable; everything else is garbage.

## Relationship to Roadmap

This design sits between:
- **Store Protocol** (§2 in roadmap) — `IStore` is the foundation
- **Remote Store** (§3 in roadmap) — authorization is required before remote
  `s-get` makes sense
- **Content Negotiation** (§3 in roadmap) — proof chain and session store
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
   client. Proof chains + dedicated stores achieve the same isolation without
   per-request server state.

3. **Re-fetch complexity.** Once a hash left the frontier, the peer had to
   re-walk from a parent to re-open it. This added complexity to both
   client and server implementations.

## Appendix B: Design Evolution

The authorization design went through four iterations:

1. **Proof chains only.** Client pre-computes a path from root to target.
   Problem: when the server fetches the chain from the client via `s-get`,
   what scopes the server's access to client data?

2. **Frontier model.** Invented to solve the scoping problem. Each fetch
   reveals children, authorization is transitive. Problem: requires
   per-session server state, which is a scaling concern.

3. **Proof chains + dedicated stores.** Returns to proof chains but solves
   the scoping problem with isolated stores. Server is stateless; client
   controls the surface area.

4. **Session store / main store split.** Recognizes that proof chain data
   needs a different auth level than user data. Session store (token-only
   auth) holds proof chains; main store (token + proof chain) holds data.
   Eliminates the circular auth problem cleanly.

The frontier model was the scaffolding that revealed the real solution —
dedicated stores. The dedicated store pattern then evolved into the
session store / main store split when we recognized that proof chain
exchange needs its own auth domain.
