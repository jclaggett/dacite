# Chapter 5: Sharing

Chapter 4 gave us **authorized stores** — stores where access is proven structurally from roots. Each user has a scoped tree within the store. How does Alice give Bob read access to a *subtree* without her full root?

No new primitives. Just a **shares map convention** in every root,
plus a `claim` protocol. Users share with users; server shares with
users — uniform mechanism.

## 5.1 Root Structure Convention

```
root = {
  \"value\": <app data>,
  \"shares\": {name: {target: #H, authorized: Set}, ...},
  \"groups\": {name: Set, ...}
}
```

Managed via PUTs.

## 5.2 Claim Protocol

1. Alice PUTs `shares[\"photos\"] = {#T, #{bob}}`
2. Alice → Bob OOB: \"claim photos from me\"
3. Bob → Server: `CLAIM \"photos\" alice`
4. Server: Alice root → shares[\"photos\"] → bob ∈ auth? → #T to Bob roots
5. Bob GET/PUT from #T (read-only on Alice's tree)

```mermaid
sequenceDiagram
    participant A as Alice
    participant B as Bob
    participant S as Server

    Note over A: shares["photos"]:<br/>{target: #T, authorized: #{bob}}

    A->>B: out-of-band: "claim 'photos' from me"
    B->>S: CLAIM "photos" from Alice
    Note over S: look up Alice's root<br/>find shares["photos"]<br/>check bob ∈ authorized ✓<br/>extract #T
    S-->>B: added #T to valid roots
    B->>S: GET/PUT using #T as proof root
```

## 5.3 Server Uses Shares

```
server-root.shares = {
  \"alice\": {#RA, #{alice}},
  \"team\": {#TP, #{alice,bob}}
}
```

Auth = claim server share. No special server state.

```mermaid
graph TD
    SR["Server Root"] --> SV["value: config"]
    SR --> SS["shares"]
    SS --> SA["'alice': {#RA, #{alice}}"]
    SS --> SB["'bob': {#RB, #{bob}}"]
    SS --> ST["'team': {#TP, #{alice,bob,carol}}"]
    SA --> RA["Alice's tree"]
    SB --> RB["Bob's tree"]
    ST --> TP["Team tree"]
    style SR fill:#4a9,stroke:#333,color:#fff
```

## 5.4 Read-Only + Edit Flow

Claim → read/incorporate → *your* share back. Bidirectional, no write-back.

## 5.5 Named Groups

`authorized: \"team\"` → root.groups[\"team\"] = #{alice,bob}

Update once → everywhere.

`\"public\": #{neg}` — cofinite (Ch2).

## 5.6 Share Types

| Type | Auth Set |
|------|----------|
| Private | `#{me}` |
| Direct | `#{me,bob}` |
| Shared | `#{team}` |
| Public | `#{neg}` |

## 5.7 Named Refs

Alice updates target → Bob reclaims latest. Revoke: dissoc/auth change.

## 5.8 Patterns

Photos: update target. Edits: re-share modified. Team: multi-auth share.

## 5.9 Cross-User Sharing

```mermaid
graph TD
    AE["Alice's tree"] --> SM["Shared subtree #SM"]
    BE["Bob's tree"] --> SM
    SM --> N1["shared nodes"]
    SM --> N2["shared nodes"]
    style SM fill:#aa4,stroke:#333,color:#fff
```

Deduplication natural.

## 5.10 Audit

Proof chains = trail.

## 5.11 Eliminated Concepts

| Old | New |
|----|-----|
| Grants/lifecycle | Shares in data |
| Gift queues | Claim-time lookup |
| Service root | Server shares |

## 5.12 API Surface

### Primitives

| Fn | Sig |
|----|-----|
| `claim` | `(Server, id, sharer, name) → #H` |
| `authorized?` | `(Root, name, id) → bool` |

### Derived

`share`, `unshare`, `add-group` — hamt ops.

**Zero new primitives** — conventions atop Ch4.

## 5.13 What This Provides

1. **Subtree sharing** — scoped by construction
2. **Uniform** — server/users same root/protocol
3. **Mutable refs** — via normal PUTs (no protocol mutability)
4. **Composability** — re-share received data

Top of stack: hash → values → stores → auth → sharing.
