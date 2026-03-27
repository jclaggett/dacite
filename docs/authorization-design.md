# Authorization Design — Store Access Control

*Draft: 2026-03-19. From discussion between Jonathan, Gorm, and
[Chouser](https://github.com/chouser).*

## 1. Core Principle

**Knowing a hash does not authorize access to its value.**

Content-addressed systems tempt you into treating hash-knowledge as a
capability. Dacite rejects this. Authorization is structural: you prove
you *possess* the data, not merely that you know its address.

## 2. Proof of Possession

Proof of Possession is Dacite's single authorization concept. Every
access — read or write — requires the requester to prove they legitimately
possess the data in question. There are exactly two forms:

### 2.1 Data Possession

You have the actual value. You can produce the bytes, and they hash to the
claimed address. This is the strongest form of proof — if you can hand
someone the data, you obviously possess it.

### 2.2 Structural Possession

You can prove the hash is **reachable** from a root you're authorized to
access. This is proven via a **proof chain**: an ordered sequence of hashes
from root to target where each step is a parent→child relationship in the
DAG.

```mermaid
graph LR
    R["#R (root)"] --> H1["#H1"] --> H2["#H2"] --> T["#T (target)"]
    style R fill:#4a9,stroke:#333,color:#fff
    style T fill:#49a,stroke:#333,color:#fff
```

The server verifies each link: the node at `h_n` must contain a reference
to `h_{n+1}`. If every link checks out, the requester has proven structural
possession of the target.

### 2.3 Proof Chains

A proof chain is a Dacite vector of hashes `[root, h1, h2, ..., target]`.
Consider a map `{"name" "Alice", "scores" [10, 20, 30]}`:

```mermaid
graph TD
    R["map (root) #R"] --> E1["entry #E1\n'name' → 'Alice'"]
    R --> E2["entry #E2\n'scores' → vector"]
    E1 --> K1["'name' #K1"]
    E1 --> V1["'Alice' #V1"]
    E2 --> K2["'scores' #K2"]
    E2 --> V2["vector #V2"]
    V2 --> S1["10 #S1"]
    V2 --> S2["20 #S2"]
    V2 --> S3["30 #S3"]
    style R fill:#4a9,stroke:#333,color:#fff
    style S3 fill:#49a,stroke:#333,color:#fff
```

To prove possession of `30` (#S3), the proof chain is `[#R, #E2, #V2, #S3]`.
Verification:

| Link | Check | Result |
|------|-------|--------|
| #R → #E2 | #R contains #E2? | ✓ (entry child of map root) |
| #E2 → #V2 | #E2 contains #V2? | ✓ (val-ref of "scores" entry) |
| #V2 → #S3 | #V2 contains #S3? | ✓ (element of the vector) |

## 3. Two Kinds of Stores

Dacite distinguishes two kinds of stores based on their authentication
and mutation requirements:

### 3.1 Unauthenticated, Read-Only Stores

- **No identity required** — any party with access can read
- **Immutable** — values are written once, never modified
- **Limited scope** — hold proof chains, session metadata, coordination data
- **Purpose** — facilitate authorization without circular dependencies

These stores exist because proof chains themselves need to be exchanged,
and requiring proof chains to access proof chains would be circular.
An unauthenticated store breaks this cycle.

### 3.2 Authenticated, Modifiable Stores

- **Identity required** — bound to an authenticated session
- **Root-managed** — a service layer maintains root hash pointers
- **General purpose** — hold all user data (Dacite values)
- **Purpose** — the primary data store

Every read requires proof of structural possession (proof chain from the
user's root). Every write requires full proof of possession (data or
structural).

### 3.3 How They Compose

In a typical client-server session:

| Store | Auth Level | Contents |
|-------|-----------|----------|
| Session store | Unauthenticated, read-only | Proof chains, metadata |
| Main store | Authenticated, modifiable | User data |

The session store holds the authorization data itself. The main store holds
the data being authorized. This separation is what makes the system
non-circular.

## 4. Reading (GET)

Reading requires **structural possession only** — a proof chain from the
reader's authorized root to the target hash.

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server

    C->>S: authenticate
    S-->>C: session token + root hash #R

    C->>S: GET #S3, chain: [#R, #E2, #V2, #S3]
    Note over S: verify chain against main store<br/>#R→#E2→#V2→#S3 ✓
    S-->>C: value of #S3
```

The server verifies each link in the proof chain against its own main
store. If valid, it serves the requested node.

### 4.1 Properties

- **Stateless verification.** The server needs no per-session state beyond
  the user's root hash. Any server with access to the main store can verify
  any proof chain.
- **Scoped by construction.** A proof chain can only reach nodes that are
  structurally below the root. No ACLs needed — the DAG structure *is* the
  access control.

## 5. Writing (PUT)

Writing requires **full proof of possession** — both forms. This is
strictly stronger than reading, because the writer must account for every
hash referenced by the new root.

### 5.1 The Problem: Hash Capture

Without PUT-side authorization, a malicious user can exploit a shared store.

```mermaid
graph TD
    RA["Alice's root #RA"] --> AD["Alice's data"]
    RB["Bob's root #RB"] --> BD["Bob's data"]
    RA2["Alice declares #RB as her root"] --> BD
    style RA2 fill:#a44,stroke:#333,color:#fff
    style BD fill:#a44,stroke:#333,color:#fff
```

In the simplest case, if Alice learns Bob's root hash `#RB`, she declares
it as her own root. The server accepts — it has all the nodes. Alice now
has a structurally valid proof chain from "her" root to everything in
Bob's tree. **Knowing a hash has become a capability.**

### 5.2 The Solution: Prove You Had It

When a client declares a new root, every referenced hash must be
**legitimately possessed**. The server walks the new root and, for each
hash it encounters, issues a uniform challenge:

**"Prove you possess #H."**

The server does not reveal whether it already has the data. The client
responds with whichever form of proof it has:

- **Data possession** — send the node value. The server verifies it
  hashes correctly.
- **Structural possession** — send a proof chain from the old root.
  The server verifies each link.

The client's choice is natural: if it created the node fresh, it has no
proof chain and sends the data. If the node existed in its previous tree,
it sends a chain (cheaper than retransmitting).

### 5.3 Root Transition Protocol

The old root remains valid until the new root is fully verified. The
transition is atomic.

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server

    C->>S: declare new root #R' (old root #R stays active)

    loop walk new root #R'
        S->>C: prove you possess #H
        alt client has data (new node)
            C-->>S: node data for #H
            Note over S: data possession ✓
        else client has chain (existing node)
            C-->>S: proof chain from #R to #H
            Note over S: structural possession ✓
        end
    end

    Note over S: all hashes verified<br/>root pointer: #R → #R'
    S-->>C: ack, new root #R'
```

The server never discloses its internal state. The client cannot deduce
which hashes the server already has.

### 5.4 The Server Always Has Structurally-Proven Data

A subtle but critical property: if the client sends a structural proof
chain (from old root to #H), the server is **guaranteed to already have
the data at #H**.

Why? The proof chain proves #H is reachable from the client's old root.
The server stored the client's old root and its entire reachable tree —
that's what previous PUT verification ensured. So every hash reachable
from the old root is already in the server's store.

This means the two proof forms partition cleanly:

| Client sends | What it means | Server's state |
|---|---|---|
| Node data | Client created this node | Server didn't have it; now it does |
| Proof chain | Node existed in client's previous tree | Server already has it |

There is no degenerate case where the client sends a valid proof chain
for data the server lacks. The invariant is maintained inductively: each
verified PUT ensures the server has all reachable data, which makes the
next PUT's structural proofs sound.

### 5.5 Why Hash Capture Fails

When Alice pushes a root referencing Bob's hash `#BD`:

1. The server challenges: "prove you possess `#BD`"
2. Alice cannot provide the data (she doesn't have Bob's node contents)
3. Alice cannot provide a proof chain (no path from her old root `#RA` to `#BD`)
4. **Put rejected.**

Alice never learns whether the server had `#BD` or not. The challenge is
the same either way.

### 5.6 Why Structural Sharing Works

When Alice mutates her own data (e.g., `assoc "key" new-value`), the new
root shares most subtrees with the old root. For shared subtrees, Alice
proves reachability from her old root — trivially valid, since she built
it. For new nodes, she provides the data. No re-transmission of unchanged
subtrees.

### 5.7 GET vs PUT

| | GET (Read) | PUT (Write) |
|---|---|---|
| **Proves** | Structural possession only | Full possession (both forms) |
| **Mechanism** | Proof chain from current root | Value provision OR proof chain from old root |
| **Prevents** | Reading outside your tree | Capturing data outside your tree |

GET is a subset of PUT. Both are applications of proof of possession.

## 6. Peer-to-Peer Store Model

Client and server are both stores. Data flows via `s-get` + proof of
possession in both directions.

```mermaid
graph LR
    C["Client Store"] <-->|"s-get + proof of possession"| S["Server Store"]
    style C fill:#49a,stroke:#333,color:#fff
    style S fill:#4a9,stroke:#333,color:#fff
```

### 6.1 Reads: Client Fetches from Server

1. Client builds proof chain from root to target
2. Client stores chain in session store (if large) or sends inline
3. Server verifies chain, returns the node

### 6.2 Writes: Server Fetches from Client

1. Client declares new root
2. Server walks from new root, requesting nodes it needs
3. Client verifies server's proof chains against its own store
4. Client serves requested nodes
5. Server verifies possession, updates root pointer

Both directions use proof chains. Both use `s-get`. The only asymmetry
is policy: who declares roots and under what conditions.

### 6.3 Implications

- One protocol pattern for both directions
- Unauthenticated stores handle proof chain exchange without circular auth
- The `IStore` protocol remains the universal interface
- Topologies compose: peers in a network, each with their own session stores

## 7. Sharing — Giving as the Primitive

Sharing in Dacite is an **act of giving**, not an ongoing authorization
relationship. There are no grants, scoped sessions, or delegation tokens.
There is one operation: **send a PR** — prove you possess a value and
offer it to someone.

This design emerged from a key observation: earlier models (grants with
lifecycle management, scoped delegation sessions, hash-pinned vs
path-pinned references) were smuggling **mutability** back into a
content-addressed system. The complexity was a signal, not a feature.

### 7.1 The PR Primitive — Proof Chain Token

A PR is a **proof chain token**: the hash `#PC` of a Dacite vector `PC = [#RA, ..., #T]` where #T is the shared subtree root.

**Protocol:**

1. Alice builds proof chain `PC` from her root `#RA` to target `#T`, stores it (unauthenticated store), gets `#PC`.
2. Alice → Bob (out-of-band): "`#PC` from me".
3. Bob → Server: "Add target of `#PC` (from Alice) to my valid roots".
4. Server:
   - Verifies `#PC` reachable from Alice's root `#RA`.
   - Fetches `PC` vector.
   - Extracts `#T = PC.last`.
   - Adds `#T` to Bob's valid roots (service root tracks per-user sets).
   - Responds: "Added `#T`".
5. Bob PUTs new root, using structural proofs from `#T` (alongside his own root).
6. Bob requests cleanup of `#T` (optional, after incorporating).

```mermaid
sequenceDiagram
    participant A as Alice
    participant B as Bob
    participant S as Server

    Note over A: PC = [#RA, ..., #T]<br/>#PC = hash(PC)

    A->>B: out-of-band: `#PC`
    B->>S: claim `#PC` from Alice
    Note over S: fetch/validate PC<br/>extract #T ✓
    S-->>B: added #T
    B->>S: PUT #RB' (proofs from #T)
    Note over S: normal verification ✓
```

**Properties:**
- **No leaks** — Bob learns `#T` only post-validation.
- **Alice offline OK** — server checks her current root.
- **Revocation automatic** — Alice restructures → `#PC` unreachable → claim fails.
- **Minimal** — one hash out-of-band, no new auth concepts.


### 7.2 Two Ways to Give

Mirroring the two forms of proof of possession, there are two ways
Alice can give a value to Bob:

| Method | When | Cost |
|--------|------|------|
| **Data** | Alice sends the raw bytes | Bandwidth proportional to value size |
| **Proof chain** | Alice proves reachability from her root | Bandwidth proportional to tree depth |

The proof chain form is where content addressing shines. Alice doesn't
retransmit a large subtree — she provides a path from her root to the
subtree hash. The server already has the data (it verified Alice's
earlier PUT). Bob's acceptance triggers a normal PUT where Bob references
the same hashes, proving possession via the PR he received.

### 7.3 What the Recipient Does (Policy, Not Protocol)

The protocol delivers a PR. What happens next is entirely the
recipient's concern:

- **Accept immediately** — merge into their tree via a normal PUT
- **Stage for review** — place in a client-side "inbox" subtree
- **Auto-accept from trusted senders** — client-side policy
- **Ignore or reject** — no action required

None of these are protocol concepts. The server doesn't know or care
about inboxes, acceptance policies, or trust relationships. It verified
possession and delivered the PR. Done.

### 7.4 Common Sharing Patterns

All sharing patterns reduce to sequences of PRs:

**Alice shares photos with Bob:**
Alice sends a PR containing her photos subtree. Bob accepts. Bob now
has the photos under his own root.

**Bob edits Alice's document:**
Alice sends Bob a PR with the document. Bob modifies it locally, sends
Alice a PR with the updated version. Alice reviews and merges. Two
independent acts of giving — no delegation session required.

**Living shared folder:**
Alice periodically sends Bob new PRs as her subtree evolves. Each PR
is a discrete, content-addressed value. No mutable references, no
auto-updating grants. Alice decides when to share; Bob decides when
to accept.

**Revoking access:**
Alice simply stops sending PRs. Bob retains whatever he already
accepted (the data is immutable and under his root), but receives
no further updates. There is nothing to revoke — there was never an
ongoing authorization to withdraw.

### 7.5 What This Eliminates

The PR-as-sharing model replaces several concepts from earlier designs:

| Eliminated | Replaced by |
|------------|------------|
| Grants (with lifecycle, creation, revocation) | PR — a single act |
| Scoped sessions | Not needed — recipient has their own root |
| Delegation as a distinct concept | Two-way PRs |
| Hash-pinned vs path-pinned debate | Irrelevant — each PR is a specific value |
| Grant storage and indexing | Not needed |
| Session model changes | None — session still has one root |

### 7.6 Properties

- **No new authorization concepts.** PRs use the existing proof of
  possession mechanism. No changes to GET or PUT verification.
- **No mutable state.** Each PR is a content-addressed value. No
  references, no pointers, no lifecycle.
- **Symmetric.** Alice → Bob and Bob → Alice use the same primitive.
- **Composable.** Bob can re-share received values with Carol via
  another PR. No special permissions needed — if Bob has it under
  his root, he can prove possession and give it.
- **Minimal protocol surface.** The server gains one operation: route
  a verified PR to a recipient.

## 8. Service Root Management

The service layer manages multiple users' roots. This is a **policy
decision**, not a fundamental of the authorization model.

### 8.1 Single Root Map

The service root is a Dacite map tracking per-user roots **plus valid PR targets**:

```
{service-root: {
  users:    {alice: #RA, bob: #RB}
  valid-roots: {bob: [#RB, #T1, #T2]}  ; PR targets Bob claimed
}}
```

Each user authenticates and receives session bound to their root. PR claims add temporary extra roots to `valid-roots[username]`.

```mermaid
graph TD
    SR["Service Root #SR"] --> U["users"]
    SR --> VR["valid-roots"]
    U --> AE["alice: #RA"]
    U --> BE["bob: #RB"]
    VR --> BT["bob: [#RB, #T1, #T2]"]
    style SR fill:#4a9,stroke:#333,color:#fff
```

During PUT, proofs accepted from *any* hash in user's `valid-roots` set.


### 8.2 Service-Layer Concern

Root hash pointers are managed by the service layer, not the store layer.
The `IStore` protocol remains purely content-addressed. Root management —
binding identities to root hashes, updating root pointers, persisting
roots, and routing PRs — belongs to the service protocol.

### 8.3 Structural Sharing Across Users

```mermaid
graph TD
    AE["Alice's tree"] --> SM["Shared subtree #SM"]
    BE["Bob's tree"] --> SM
    SM --> N1["shared nodes"]
    SM --> N2["shared nodes"]
    style SM fill:#aa4,stroke:#333,color:#fff
```

When Alice gives Bob a subtree via PR, and Bob accepts it into his
tree, both roots reference the same underlying nodes. This is natural
content-addressed deduplication — no special sharing mechanism needed.
Each user proves access through their own root. Neither can access the
other's unshared data.

## 9. Audit Trail

The sequence of proof chains submitted during a session forms a natural
audit trail: the server knows exactly which paths the client walked.
Proof chains are Dacite values (immutable, content-addressed) and can be
retained as access records.

---

## Acknowledgments

[Chouser](https://github.com/chouser) contributed key insights during a
collaboration session on 2026-03-06:

- Stateless server verification (§4.1) — the observation that proof chains
  eliminate the need for per-session server state
- GC equivalence (Appendix A) — recognizing that color marking and store
  migration are equivalent semi-space collection strategies
- Decoupling identity and authorization — the distinction between who you
  are and what you can reach

§7 was substantially simplified on 2026-03-26. The original delegation
model (scoped sessions, grant lifecycle, hash-pinned vs path-pinned
references) was replaced by a single primitive: sharing as giving via
PRs. The complexity of the earlier model was a signal that it was
reintroducing mutability into a content-addressed system.

---

## Appendix A: Garbage Collection

Content-addressed stores are append-only. Mutations leave orphaned nodes —
subtrees no longer reachable from the service root.

Authorization defines what's "live": a node is live if and only if it's
reachable from the service root. Everything else is garbage. This makes
GC a direct consequence of the authorization model.

Dacite GC is a **semi-space collector** with two equivalent strategies:

### Store Migration (Copying Collection)

The mark is **presence in the new store.**

1. Create a new empty store B
2. Walk from the service root, copying reachable nodes from A to B
3. Swap B in for A; discard A

### Color Marking (Mark-and-Sweep)

The mark is **a color bit** on each node (red or green).

1. Walk from the service root, marking reachable nodes with the current
   color. New writes also use the current color.
2. Cull all nodes with the previous color.
3. Alternate colors each cycle.

### Equivalence

| | Store Migration | Color Marking |
|---|---|---|
| Mark live | Copy to new store | Set to current color |
| Identify dead | Absent from new store | Previous color |
| Reclaim | Discard old store | Delete previous-color nodes |
| Two spaces | Store A / Store B | Red / Green |

Both support online collection without pausing writes. Store migration
routes reads through both stores during the copy; color marking uses the
live color for new writes while the background walk proceeds.

### Properties

- **No reference counting.** No per-node bookkeeping during normal operations.
- **Cost proportional to live data.** You pay for what you keep.
- **Structural sharing preserved.** Shared subtrees are visited once.
- **Single walk.** One service root means one walk covers all users' data.

## Appendix B: Rejected Alternative — Frontier Model

An intermediate design used a **frontier model**. Each `s-get` response
implicitly authorized the next level down via a per-session frontier set.
This eliminated proof chains but required server-side state per session,
complicating scaling and failover. Proof chains + unauthenticated stores
achieve the same isolation statelessly.

## Appendix C: Design Evolution

1. **Proof chains only.** Problem: what scopes the server's access to
   client data when fetching proof chains?
2. **Frontier model.** Solved scoping but added per-session server state.
3. **Proof chains + dedicated stores.** Stateless, client controls
   surface area.
4. **Session store / main store split.** Recognized that proof chain
   data needs a different auth level. Session store (unauthenticated)
   holds proof chains; main store (authenticated) holds data.
5. **Proof of Possession as unifying concept.** GET and PUT are both
   applications of the same principle. Two forms (data, structural)
   cover all cases.
