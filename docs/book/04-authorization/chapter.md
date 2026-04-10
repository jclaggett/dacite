# Chapter 4: Authorization

Chapter 3 gave us stores -- persistence and distribution across machines.
But stores don't care *who* is reading or writing. Any party with network
access can fetch any hash. In a single-user system, that's fine. In a
multi-user system, it's "knowing a hash is authorization."

Dacite rejects this. This chapter adds **authorization** -- proof of
possession, authenticated stores, and the GET/PUT protocols. Together they
give secure access control without ACLs or capabilities -- just structural
proofs over the DAG.

## 4.1 Core Principle

**Knowing a hash does not authorize access to its value.**

Hashes leak -- in logs, URLs, errors. A hash is an *address*, not a *key*.
Dacite's authorization is **structural**: prove you *possess* the data.

## 4.2 Proof of Possession

Every access requires proof of legitimate possession. Two forms:

### Data Possession

Produce the bytes; they hash correctly. Strongest proof.

### Structural Possession

Proof chain: `[root, h1, ..., target]`. Server verifies each parent-to-child
link in the DAG.

### Example

```mermaid
graph TD
    R["map (root) #R"] --> E1["entry #E1\n'name' -> 'Alice'"]
    R --> E2["entry #E2\n'scores' -> vector"]
    E2 --> V2["vector #V2"]
    V2 --> S1["10"]
    V2 --> S2["20"]
    V2 --> S3["30 #S3"]
    style R fill:#4a9,stroke:#333,color:#fff
    style S3 fill:#49a,stroke:#333,color:#fff
```

Chain `[#R, #E2, #V2, #S3]`; three lookups confirm reachability.

## 4.3 Two Kinds of Stores (Future)

> **Not yet implemented.** This section describes the target design.

Breaks proof chain circularity:

### Unauthenticated, Read-Only (Session Stores)

No identity; immutable; hold proof chains/metadata. In the current
implementation, `dedicated-store` serves this role -- a scoped mem-store
containing only nodes along a proof chain.

### Authenticated, Modifiable (Main Stores)

Identity-bound; root-managed; user data.

| Store | Auth | Contents |
|-------|------|----------|
| Session | Unauth RO | Proofs/metadata |
| Main | Auth mod | Data |

## 4.4 Reading (GET)

Structural possession only:

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server

    C->>S: authenticate
    S-->>C: session token + root hash #R

    C->>S: GET #S3, chain: #R -> #E2 -> #V2 -> #S3
    Note over S: verify chain #R to #S3 valid
    S-->>C: value of #S3
```

Stateless/scoped by DAG.

## 4.5 Writing (PUT / Root Update)

Writing is expressed as a **root replacement**. The client does not send a separate “new root” declaration. Instead, it begins a proof stream whose *first proof* is for the new root itself.

- If the first proof is a **chain proof**, the last hash in the chain becomes the new root.
- If the first proof is a **data proof**, the hash of that node becomes the new root.

The client then walks the new tree in deterministic DFS order (via `child-hashes`). For each node, it sends either:
- A **data proof** for newly created or modified nodes, or
- A **chain proof** from the *old* authorized root for unchanged subtrees.

The server validates each proof as it arrives. When the DFS traversal completes (stack is empty), the server atomically updates the user’s root to the new hash.

This eliminates a round-trip and removes special-case root declaration logic. Because every node in the new tree must be proven from either new data or a valid chain from the client’s *current* root, hash-capture attacks are prevented.

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server

    C->>S: Begin PUT proof stream (first proof = new root)

    Note over C,S: Client walks new tree in DFS order

    C->>S: Proof for new root (chain or data)
    Note over S: Validates proof. New root hash = resolved hash.
    S-->>C: OK

    C->>S: Next proof in DFS order...
    S-->>C: OK

    Note over S: DFS stack empty — transition complete
    S-->>C: Root updated
```

| Client sends | Server action |
|--------------|---------------|
| Data proof | Verify hash, store node, push children onto DFS stack |
| Chain proof | Verify chain from old root, skip subtree |

GET is a strict subset of this PUT protocol.

## 4.6 Garbage Collection (Future)

> **Not yet implemented.** This section describes the target design.

Liveness = reachable from authorized roots.

Semi-space collector (two strategies, equivalent):

| | Migration | Color Mark |
|--|-----------|------------|
| Mark | Copy to B | Current color |
| Dead | Absent B | Old color |
| Reclaim | Discard A | Delete old |

Online; cost proportional to live data; preserves sharing.

## 4.7 API Surface

### Implemented

| Function | Signature | Description |
|----------|-----------|-------------|
| `build-proof-chain` | `(Store, root, target) -> [Hash] or nil` | BFS path from root to target |
| `verify-proof-chain` | `(Store, [Hash]) -> bool` | Link-by-link chain verification |
| `dedicated-store` | `(Store, [Hash]) -> Store` | Scoped mem-store with chain nodes only |
| `validate-proof` | `(Store, valid-roots, hash, Proof) -> Value or nil` | Verify one proof (chain or data) |
| `verify-transition` | `(Store, valid-roots, new-root, prover) -> Result` | DFS walk, validate proofs via prover fn |
| `apply-transition` | `(Store, valid-roots, new-root, prover) -> Result` | Verify + merge new nodes into store |

The `prover` argument is `(fn [hash] -> {:type :chain, :chain [...]} | {:type :data, :value ...})`.
In Layer 4, `valid-roots` is `#{user-root}`. Layer 5 extends this with share roots.

### Supporting (Layer 2)

| Function | Description |
|----------|-------------|
| `child-hashes` | Ordered vector of child hash references for any node type |

### Properties

- Chain verifies reachability
- PUT requires all hashes possessed (data or structural)
- Invariant: server has all data reachable from old root
- DFS order is deterministic from `child-hashes` ordering
- GC: live = root-reachable (future)

**Depends on Layers 1-3.** Verification logic atop stores.

## 4.8 What This Layer Provides

1. **Secure stores** -- proof of possession prevents hash-as-capability
2. **Stateless auth** -- roots + chains, no session state
3. **Uniform proof model** -- GET/PUT/GC all derive from one concept
4. **Peer-ready** -- both directions use same proof protocol
5. **Client-driven** -- server validates, client controls proof ordering

Chapter 5 adds sharing conventions: shares map atop authorized stores.
