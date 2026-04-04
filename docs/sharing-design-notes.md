# Sharing Design — Working Notes

*Prepared 2026-03-24 for tonight's discussion. Based on the open
questions at the bottom of `authorization.allium`.*

## The Problem

The current model handles single-user access cleanly: your root, your
tree, proof chains scope everything. But when Alice wants to share a
subtree with Bob, the model doesn't yet have a clean answer for how
Bob proves possession of data that Alice put there.

Today's PUT verification says: "every hash under your new root must be
either (a) uploaded by you, or (b) reachable from your old root." But
if Alice shares a subtree with Bob, the data is reachable from *Alice's*
root, not Bob's. Bob can't provide a structural proof from his own old
root, and he doesn't have the raw data to upload.

## The Proposed Direction

From the Allium doc's open questions:

> Unify self-roots and sharing grants. Bob's old root is just a grant
> where `authorized = {bob}`. Sharing grants are additional grants with
> broader authorization sets. Walk-and-pull becomes: "is every hash
> either uploaded or reachable from some grant that authorizes the
> requesting user?"

This reframes everything: there's no special "your root" — there are
only **grants**, and your own root happens to be a grant where you're
the sole authorized party.

## Key Questions

### 1. What is a grant?

Minimal shape: `{hash, authorized-set}`

- `hash` — a root hash in the store (the top of a shared subtree)
- `authorized-set` — the set of identities that can use this grant
  for structural proofs

Your "own root" becomes `{your-root-hash, {you}}`.
A sharing grant might be `{subtree-hash, {alice, bob}}`.

**Open:** Is the authorized-set just identities, or could it be
something more abstract (roles, groups, "anyone with this token")?
Probably start with identities and generalize later.

### 2. How do grants change PUT verification?

Current rule (from `RespondWithChain`):
> Chain root must equal the transition's old root.

Proposed generalization:
> Chain root must equal the hash of **some grant that authorizes
> the requesting identity**.

This means during walk-and-challenge, the client can respond to
"prove you possess #H" with a proof chain from *any* grant they're
authorized for — not just their own old root.

The three current verification cases collapse:
- **Uploaded data** → data proof (unchanged)
- **From own old root** → structural proof from own-root grant
- **From shared subtree** → structural proof from sharing grant

All three are the same check: "valid proof chain from an authorized
grant."

### 3. Where do grants live?

Several options:

**a) In the service root map (alongside user entries)**
- Service root becomes `{users: {alice: #A, bob: #B}, grants: [...]}`
- Simple, but mixes concerns — grants are authorization metadata,
  not user data

**b) On the session itself**
- Session carries a set of grant hashes the identity can use
- Server populates these at session creation
- Clean separation, but requires the server to know all grants
  at auth time

**c) In an unauthenticated store (like proof chains)**
- Grants are coordination metadata, arguably similar to proof chains
- Avoids circular auth (you don't need auth to learn what you're
  authorized for)

Leaning toward (b) — the session already carries a root hash and
scope, extending it to carry a set of authorized grant roots feels
natural.

### 4. Grant lifecycle

**Creation:** Alice creates a grant `{#S, {alice, bob}}` where #S is
a subtree of her tree. This is a service-layer operation — Alice tells
the service "Bob can use #S as a proof chain root."

**Update:** When Alice PUTs a new root, her subtree at the granted
path may have a new hash #S'. Does the grant auto-update? Options:
- **Hash-pinned:** Grant stays at #S. Bob sees a frozen snapshot.
  Alice must explicitly issue a new grant for #S'.
- **Path-pinned:** Grant refers to "Alice's subtree at path X" and
  resolves to whatever hash is there after Alice's latest PUT.
  Bob always sees Alice's latest version.

The Allium doc asks this directly: "Should sharing grants attach to
specific hashes or to named references that the owner can update?"

**Hash-pinned** is simpler and more explicit. It's also more
consistent with the content-addressed philosophy — a hash IS a
specific value. But it means sharing a living, evolving subtree
requires Alice to reissue grants after every PUT.

**Path-pinned** is more ergonomic for "Alice shares her /photos
folder with Bob and Bob always sees the latest." But it introduces
indirection and couples Bob's access to Alice's mutation schedule.

**Hybrid?** Grants are hash-pinned, but the service layer can
auto-reissue when the owner PUTs a new root. The grant mechanism
stays clean; the convenience is a service-layer policy.

**Revocation:** Alice stops issuing the grant. If hash-pinned, Bob
retains access to the frozen snapshot (the data is immutable and he
has proof chains for it). If path-pinned, Alice's next PUT naturally
moves the reference and the old grant is dead.

This connects to the existing revocation-by-restructuring model —
Alice rebuilds her tree without the shared subtree and stops
reissuing the grant.

### 5. Does GET need to change?

Current GET requires a proof chain from the session's root hash.
If the session now carries multiple grant roots, GET becomes:
"proof chain from *any* of the session's authorized roots."

This is a minor generalization — the verification logic is the same,
just checking against a set instead of a single root.

### 6. Interaction with delegation

The current delegation model (§7 of the design doc) gives the
delegatee a scoped session at a subtree. This looks very similar to
a grant — a delegation is essentially `{subtree-hash, {delegatee}}`.

Can we unify delegation and sharing grants? A delegation might just
be a grant with write-back capability attached. The read-only part
is identical.

### 7. Structural sharing between grantors

If Alice and Bob both share subtrees with Carol, and those subtrees
overlap (content-addressed deduplication), Carol's grants give her
two independent paths to the same underlying data. This is fine —
proof chains are path-specific, so Carol proves access through
whichever grant root she received the data from.

## Summary of the Uniform Model

```
Before:  Session = {identity, store, root_hash, scope}
After:   Session = {identity, store, grants: Set<{hash, authorized}>}
         where one grant is "your own root"
```

PUT verification becomes:
> For each challenged hash, client provides either:
> (a) the data itself, or
> (b) a proof chain from any grant hash that authorizes this identity

GET verification becomes:
> Proof chain from any grant hash that authorizes this identity

One mechanism. No special cases.

---

*Ready to discuss and refine tonight.*
