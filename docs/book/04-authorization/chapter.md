# Chapter 4: Authorization

Chapter 3 gave us stores — persistence and distribution across machines. But stores don't care *who* is reading or writing. Any party with network access can fetch any hash. In a single-user system, that's fine. In a multi-user system, it's "knowing a hash is authorization."

Dacite rejects this. This chapter adds **authorization** — proof of possession, authenticated stores, and the GET/PUT protocols. Together they give secure access control without ACLs or capabilities — just structural proofs over the DAG.

## 4.1 The Authorization Challenge

In a conventional operating system, memory protection is relatively straightforward. The kernel allocates regions of RAM to a process and uses hardware memory management units (MMUs) to prevent that process from reading or writing addresses outside its allocation. A memory address is only meaningful *within* a protected context.

```mermaid
graph TD
    subgraph OS [Traditional OS]
        Kernel[Kernel\nMemory Protection]
        ProcessA[Process A\nAllocated Range]
        ProcessB[Process B\nAllocated Range]
        Kernel --- ProcessA
        Kernel --- ProcessB
    end
    style Kernel fill:#4a9,stroke:#333,color:#fff
```

Dacite faces a fundamentally harder problem.

All data is **content-addressed**. A hash is not a private location granted by a central authority — it is a globally unique, publicly shareable pointer into an immutable DAG that may be referenced by many users simultaneously. Knowing a hash gives you an address, but there is no kernel standing behind it to enforce ownership.

```mermaid
graph TD
    subgraph Dacite [Dacite Content-Addressed DAG]
        RootA[User A Root #RA]
        RootB[User B Root #RB]
        Shared[Shared Subtree #S]
        RootA --> Shared
        RootB --> Shared
    end
    style Shared fill:#a84,stroke:#333,color:#fff
```

Because hashes are public and data is immutable and widely shareable, we cannot rely on simple address-based protection. We must prove **legitimate structural possession** — that a requester can demonstrate they are authorized to access a value from *their own* authorized root.

This chapter introduces the mechanisms Dacite uses to solve this problem: proof chains, authenticated stores, and the GET/PUT protocols built on structural proofs.

## 4.2 Core Principle

**Knowing a hash does not authorize access to its value.**

Hashes leak — in logs, URLs, errors. A hash is an *address*, not a *key*.
Dacite's authorization is **structural**: prove you *possess* the data.

## 4.3 Proof of Possession

Every access in Dacite requires a proof of legitimate possession. There are two fundamental forms of proof:

### Data Possession (Strongest)

The client simply sends the raw bytes. The server hashes them and confirms the hash matches the requested address. This is the most direct form of proof — the client is demonstrating it physically holds the data.

### Structural Possession (Proof Chains)

When the client does not want to send the full data (or the data is very large), it can send a **proof chain** — an ordered list of hashes from an authorized root down to the target value.

A proof chain has the form:

`[#R, #h1, #h2, ..., #target]`

For each consecutive pair in the chain, the server verifies that the parent node *actually contains* the child hash. This proves the target is reachable from the root through a valid path in the DAG.

**Example Proof Chain**

```mermaid
graph TD
    R["map (root) #R"] --> E1["entry #E1\n'name' → 'Alice'"]
    R --> E2["entry #E2\n'scores' → vector"]
    E2 --> V2["vector #V2"]
    V2 --> S1["10"]
    V2 --> S2["20"]
    V2 --> S3["30 #S3"]
    style R fill:#4a9,stroke:#333,color:#fff
    style S3 fill:#49a,stroke:#333,color:#fff
```

A client wanting value `#S3` can send the chain `[#R, #E2, #V2, #S3]`. The server performs three lookups to confirm each link is valid.

Proof chains allow efficient verification without sending large subtrees. They are the foundation of both reading and writing in Dacite.

## 4.4 Reading (GET)

Reading is the simpler case. A client authenticates and receives its current root hash. To read a value, it sends a proof chain from that root to the desired target.

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server

    C->>S: authenticate
    S-->>C: session token + current root hash #R

    C->>S: GET #S3 with proof chain [#R → #E2 → #V2 → #S3]
    Note over S: Verify each link in the chain
    S-->>C: Value at #S3
```

The server only needs to validate the chain — it does not need to trust the client beyond that. This gives strong, stateless authorization.

## 4.5 Writing (PUT / Root Update)

Writing is expressed as a **root replacement**. The client does not send a separate “new root” declaration. Instead, it begins a proof stream whose *first proof* is for the new root itself.

- If the first proof is a **chain proof**, the last hash in the chain becomes the new root.
- If the first proof is a **data proof**, the hash of that node becomes the new root.

The client then walks the *new* tree in deterministic DFS order (via `child-hashes`). For each node encountered, it sends either:
- A **data proof** for newly created or modified nodes, or
- A **chain proof** from the *old* authorized root for unchanged subtrees.

The server validates each proof as it arrives. When the DFS traversal completes (the stack is empty), the server atomically updates the user’s root to the new hash.

This approach eliminates a round-trip and removes special-case logic. Because every node in the new tree must be proven from either new data or a valid chain from the client’s *current* root, hash-capture attacks are prevented.

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server

    C->>S: Begin PUT proof stream (first proof = new root)

    Note over C,S: Client walks new tree in DFS order

    C->>S: Proof for new root (chain or data)
    Note over S: Validates. New root hash = resolved hash.
    S-->>C: OK

    C->>S: Next proof in DFS order...
    S-->>C: OK

    Note over S: DFS stack empty — transition complete
    S-->>C: Root updated
```

| Client sends     | Server action                                      |
|------------------|----------------------------------------------------|
| Data proof       | Verify hash, store node, push children onto stack |
| Chain proof      | Verify chain from old root, skip subtree           |

GET is a strict subset of this PUT protocol.

## 4.6 Two Kinds of Stores (Future)

> **Not yet implemented.** This section describes the target design.

To break the circularity inherent in proof chains (you need the root to prove the root), Dacite distinguishes between two kinds of stores:

### Unauthenticated, Read-Only (Session Stores)

These stores have no identity. They are immutable and exist only to hold proof chains and metadata during a session. The current `dedicated-store` implementation serves this role — it is a scoped in-memory store containing only the nodes along a given proof chain.

### Authenticated, Modifiable (Main Stores)

These are identity-bound and root-managed. They contain the actual user data and are the stores whose roots are updated during PUT operations.

| Store Type       | Authentication | Mutability | Purpose                     |
|------------------|----------------|------------|-----------------------------|
| Session Store    | None           | Immutable  | Hold proofs during requests |
| Main Store       | Identity-bound | Modifiable | Persistent user data        |

This separation allows clean layering: proofs are validated in session stores, while root updates happen in main stores.

## 4.7 Garbage Collection (Future)

> **Not yet implemented.** This section describes the target design.

Liveness is defined as reachability from any authorized root.

We plan to use a semi-space collector with two equivalent strategies:

| Strategy     | Mark               | Dead               | Reclaim             |
|--------------|--------------------|--------------------|---------------------|
| Migration    | Copy to space B    | Absent from B      | Discard space A     |
| Color Mark   | Current color      | Old color          | Delete old color    |

The collector runs online, cost is proportional to live data, and structural sharing is preserved.

## 4.8 API Surface

### Implemented

| Function                | Signature                                      | Description                                      |
|-------------------------|------------------------------------------------|--------------------------------------------------|
| `build-proof-chain`     | `(Store, root, target) -> [Hash] or nil`       | BFS path from root to target                     |
| `verify-proof-chain`    | `(Store, [Hash]) -> bool`                      | Link-by-link chain verification                  |
| `dedicated-store`       | `(Store, [Hash]) -> Store`                     | Scoped store with only chain nodes               |
| `validate-proof`        | `(Store, valid-roots, hash, Proof) -> Value?`  | Verify one proof (chain or data)                 |
| `verify-transition`     | `(Store, valid-roots, prover) -> Result`       | DFS walk validating proofs                       |
| `apply-transition`      | `(Store, valid-roots, prover) -> Result`       | Verify + merge new nodes and update root         |

The `prover` is a function `(fn [hash] -> {:type :chain, :chain [...]} | {:type :data, :value ...})`.

### Supporting (Layer 2)

- `child-hashes` — returns ordered vector of child hash references for any node type.

### Key Properties

- Proof chains verify structural reachability
- PUT requires full possession (data or chain from current root)
- Server maintains invariant: it holds all data reachable from authorized roots
- DFS order is deterministic based on `child-hashes`
- Garbage collection will be based on root reachability

**Depends on Layers 1–3.** All verification logic is built on top of the store abstraction.

## 4.9 What This Layer Provides

1. **Secure stores** — proof of possession prevents “hash-as-capability” attacks
2. **Stateless authorization** — roots + chains, no server-side session state required
3. **Uniform proof model** — GET, PUT, and future GC all derive from the same concept
4. **Peer-ready** — the same proof protocol works in both directions
5. **Client-driven** — the server validates; the client controls proof ordering and tree shape

Chapter 5 builds on this foundation by introducing sharing conventions (shares map, groups, and delegation) layered atop authorized stores.
