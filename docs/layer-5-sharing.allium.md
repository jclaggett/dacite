# Layer 5: Sharing — Allium Spec v1.0

## Problem Statement

Layer 4 (authorization) secures stores via proof-of-possession from personal roots. But users need to *share subtrees*: Alice grants Bob read access to `#H` (e.g., `/photos`) without full root `#RA`. Bob must prove access via *Alice's* structure, not his own `#RB`.

Challenges:
- No hash leaks grant access.
- Updates propagate (named refs, not frozen hashes).
- Uniform: users share like servers.
- No new primitives (conventions atop layer 4).
- Revocation: restructure (dissoc/update auth).

## Core Model

**Root Convention** (every user/server root):
```
{:value <app-data>
 :shares {name → {:target Hash, :authorized Set|NamedSet}}
 :groups {name → Set}}
```
- `shares`: outbound grants (Alice → others).
- `groups`: reusable auth sets (`:authorized \"team\"` → `groups[\"team\"]`).
- Sets: maps `{x x}` or `neg`-inverted cofinite (Ch2).

**Session Extension** (layer 4 + inbound grants):
```
Session = {:identity ID, :store IStore, :grants [{:hash Hash, :authorized Set}]}
```
- Own root: `{:hash #R, :authorized #{me}}`.
- Claims add `{:#H, authorized}`.

**Claim Op:** Bob claims Alice's share → server verifies → adds to Bob's session grants.

## Protocols & Invariants

### Claim Verification
```
(defn authorized? [root name id]
  (let [share (get-in root [:shares name])
        auth-set (resolve-set share.authorized root.groups)]
    (contains? auth-set id)))
```
Invariant: `claim` succeeds iff `(authorized? alice-root name bob)`.

### PUT Integration (layer 4 challenge)
For new root `#R'`, challenge hashes → respond from *any* session grant (own + claimed).

Invariant: Server holds all reachable-from-grants data → GC live = union(authorized roots).

## API Surface

### Primitives (dacite.share ns)
```clojure
(defprotocol IShare
  (claim! [this id sharer name] → Hash)  ; Add to session.grants
  (authorized? [this root name id] → bool))

(defn resolve-set [named-or-set groups] → Set)
```
Server impl integrates with `IService`/`IAuthStore`.

### Derived (service.clj)
```clojure
(share! service name target authorized)  ; PUT to root.shares
(unshare! service name)
(add-to-group! service name id)
```
Hamt ops + claim trigger.

### Client Helpers
```clojure
(claim-share client sharer-name)  ; OOB name exchange → claim!
```

## Examples

1. **Direct Share:**
   ```
   Alice: (share! \"photos\" #photos #{bob})
          → root.shares[\"photos\"] = {:target #photos, :authorized #{bob}}
   Bob: (claim-share alice \"photos\") → session.grants += {:#photos #{bob-alice}}
   ```

2. **Public:** `:authorized #{neg}` (everyone).

3. **Group:** `:authorized \"team\"`, `groups[\"team\"] = #{alice bob}`.

4. **Server Onboarding:**
   ```
   server.shares[\"alice\"] = {:#RA #{alice}}
   alice: (claim-share \"server\" \"alice\")
   ```

## Open Questions (Resolved)
- Lifecycle: No grants state—convention-only, claim-time lookup.
- Pinning: Path-pinned via root.shares updates.
- Write-back: Re-share modified subtree (PR model).
- Cross-share: Natural DAG sharing.

## Next: Integration Tests
- Claim → GET subtree.
- Unauthorized claim fails.
- PUT via claimed grant.
- Group update propagates.

**Depends:** Layers 1-4. **Unlocks:** Multi-user apps, peer sync.