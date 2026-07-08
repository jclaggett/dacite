# Stores Phase 2: GC and Distribution

**Status:** IN PROGRESS — GC, remote store, and LRU landed; service MVP next.

**Builds on:** [stores-phase-1.md](stores-phase-1.md) (DONE)

## Path choice (2026-07-08)

Phase 2 pursues **both** local completeness and the distribution unlock, in this order:

1. **Value-aware GC** — first feature requiring values + rooted stores together; essential once sync copies subgraphs
2. **Service design doc** — HTTP mapping to `IStore` + rooted-store CAS; unblocks service rewrite
3. **Remote `IStore` + LRU cache** — architectural unlock for distributed use
4. **Service MVP** — new `dacite.service` consuming `dacite.rooted-store` (follows remote store)

Deferred to Phase 2.5 / later:

- Root slot in content map (`[0,0,0,0]` or `type-hash("dacite/root")`)
- Content sync helper (copy reachable nodes to target before `push-ref`)
- True opaque-byte storage in stores
- Remote root watches (SSE)

## 1. Garbage collection

Per [Chapter 4 §4.6](../book/04-rooted-stores/chapter.md): walk from root, mark reachable nodes via `types/child-hashes`, delete detached entries from the content store.

**Namespace:** `dacite.gc`

```clojure
(mark-reachable store root-hash)   ; => #{hash ...}
(collect-garbage! store root-hash) ; => {:removed n :kept n}
(collect-garbage! rooted-store)    ; uses @store as root, delegates to content
```

Requires `s-delete` on `IStore` (new protocol method).

## 2. Remote store

HTTP-backed `IStore` per [service.md](service.md). Composes with layered store:

```clojure
(layered-store (lru-store 1000) (remote-store "https://..."))
```

## 3. LRU cache store

Bounded in-memory store with LRU eviction. Sits in front of remote (or any slow) layer.

## 4. Service MVP (planned)

Rewrite archived `dacite.service` against:

- `dacite.rooted-store` for per-user roots (not ad-hoc meta-db root)
- HTTP node + root CAS endpoints from service design doc
- Dedicated store per user (no shared auth tree)

## Explicitly out of scope for Phase 2

- User types open registry
- Sorted collections
- Spec v0.5 (track separately)
