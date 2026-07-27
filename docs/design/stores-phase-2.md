# Stores Phase 2: GC, Distribution, and Transport Packing

**Status:** DONE (core) — GC, remote store, LRU, service MVP, leaf-chunking 2a–2d,
client cache policies, browser todo demo. Follow-ons listed under **Phase 2.5 /
later**.

**Builds on:** [stores-phase-1.md](stores-phase-1.md) (DONE)

**Related:** [service.md](service.md), [leaf-chunking.md](leaf-chunking.md),
[ft-single-elision.md](ft-single-elision.md)

## Path taken

1. **Value-aware GC** — `dacite.rooted.gc` (`mark-reachable`, `collect-garbage!`)
2. **Service design + MVP** — `dacite.service` on rooted stores; node + root CAS HTTP
3. **Remote `IStore` + LRU** — `dacite.store.remote`, `dacite.store.lru`
4. **Client policies** — `dacite.store.client-cache` (`:none`, `:smart-put`, `:write-back`, `:layered`)
5. **Leaf-chunking (transport packing)** — 2a–2d in `dacite.store.pack` (see below)
6. **Bandwidth bench** — `dacite.bench.todo-bw` (+ `--budget-sweep`)

## Shipped checklist

| Item | Status |
|------|--------|
| Value-aware GC | Done |
| Service design doc | Done (living) |
| Remote `IStore` (HTTP) | Done |
| LRU cache store | Done |
| Service MVP (`dacite.service`) | Done |
| Write-back / smart-put client cache | Done |
| Pack write `POST /nodes` (`:node` + `:literal`) | Done |
| Pack-filled `GET /node/{hex}` (BFS `pack-under`) | Done |
| Write novelty (`:created`/`:exists`, `:complete`/`:partial`) | Done |
| Leaf-chunking 2a–2d | Done (default budget **1024**) |
| Intermediate FT/HAMT literals (2c′) | Done |
| Browser todo demo (CLJS remote) | Done |
| `ft/single` elision (implicit leaf singles) | Done (side quest; value layer) |

## 1. Garbage collection

Per [Chapter 4 §4.6](../book/04-rooted-stores/chapter.md): walk from root, mark
reachable nodes via `types/child-hashes`, delete detached entries.

**Namespace:** `dacite.rooted.gc`

```clojure
(mark-reachable store root-hash)   ; => hex-keyed set
(collect-garbage! store root-hash) ; => {:removed n :kept n}
(collect-garbage! rooted-store)    ; uses @store as root
```

## 2. Remote store + service

HTTP-backed `IStore` per [service.md](service.md). Typical composition:

```clojure
(client-cache/wrap (remote/remote-store base-url) :write-back)
```

Service endpoints (MVP): pack-filled `GET /node`, novelty `PUT /node`,
`POST /nodes` (chunk apply), demoted `POST /nodes/get`, `GET /root`,
`POST /root/cas`.

## 3. LRU cache store

Bounded in-memory store with LRU eviction. Sits in front of remote (or any slow)
layer.

## 4. Leaf-chunking (transport packing)

**Doc:** [leaf-chunking.md](leaf-chunking.md) — **2a–2d all Done.**

Wire-only packing: durable values stay pure FT/HAMT. Soft budget default
**1024** bytes (measured). Encode `:literal` when realized content fits;
otherwise `:node` + walk. Intermediate `ft/*` / `hamt/*` may ship as leaf-payload
literals when dry-run hash matches.

```bash
cd impl/clojure
clojure -M:dev -m dacite.bench.todo-bw --policy write-back
clojure -M:dev -m dacite.bench.todo-bw --budget-sweep
```

## Phase 2.5 / later (deferred)

- **Pack as composable store middleware** — [store-composition-pack.md](store-composition-pack.md)
  (literal → pack → [throttle] → HTTP; server unpack mirror; value completeness).
  **Do this before client rate-limit.**
- Rate-limit store (token bucket) under pack — 1 token ≈ 1 chunk; blocked when empty
- Root slot in content map (`[0,0,0,0]` or `type-hash("dacite/root")`)
- Content sync helper (copy reachable nodes to target before `push-ref`)
- True opaque-byte storage in stores
- Remote root watches (SSE)
- Binary wire for pack envelopes (EDN is MVP)
- Configurable layered write policies (`:push-all`, `:top-only`)
- Spec update to v0.5
- User provisioning / multi-tenant auth beyond demo Bearer token

## Explicitly out of scope for Phase 2

- User types open registry
- Sorted collections
- Reintroducing value-layer collection inlining
