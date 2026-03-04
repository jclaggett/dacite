# Type System Refactor Plan

Goal: Break up `core.clj` (the god namespace) into focused modules while keeping the test suite green at every step.

## Phase 1: Extract the Foundation ✅ DONE (commit 275824d)

1. ✅ **Move store into `store.clj`** — `*store*`, `with-store`, `reset-store!`, `set-store!`
2. ✅ **Expand `types.clj`** — add `IDaciteHash`, `encode-value` multimethod, `typed-value-hash`, `node-hash`
3. ✅ **Clean `hash.clj`** — remove `encode-value` and `typed-value-hash`/`node-hash` (they move to types)
4. ✅ **Update `core.clj`** — use the new locations (still the god namespace, but importing from types/store)
5. ✅ **Update test requires** as needed
6. ✅ 295 tests, 1302 assertions, 0 failures, 96.26% form coverage

## Phase 2: Extract the Types ✅ DONE (commit 2e651a7)

1. ✅ **Create `scalar.clj`** — `DaciteScalar` + scalar constructors + utilities (scalar-type, scalar-hash, size-bytes)
2. ✅ **Create `collections.clj`** — all 4 collection deftypes + constructors + internal helpers + wrap-hash/unwrap-hash
3. ✅ **Create `convert.clj`** — `dac->clj`, `clj->dac`
4. ✅ **Slim `core.clj`** to thin re-exports (~90 lines vs ~700 before)
5. ✅ **Update test requires** — instance? checks updated for new class locations
6. ✅ 295 tests, 1302 assertions, 0 failures, 96.30% form coverage

## Final Architecture

```
dacite.hash           Pure hashing (fuse, SHA-256, byte tables)
dacite.types          IDaciteHash, type system (sizes, encoding, hashing)
dacite.store          IStore protocol, implementations, *store* management
dacite.scalar         DaciteScalar + scalar constructors
dacite.collections    DaciteString/Blob/Vector/Map + collection constructors
dacite.convert        dac->clj, clj->dac boundary crossing
dacite.core           Thin re-exports (public API)
dacite.finger-tree    Finger tree internals
dacite.hamt           HAMT internals
dacite.serial         Binary serialization
```

## Notes

- Pre-existing issue: `dacite.dev.inspect-test` references `dacite.dev.inspect` which was never committed. Excluded from test runs.
- `d/*store*` removed from core.clj; tests now reference `store/*store*` directly.
- Dependency graph: hash ← types ← {scalar, store} ← collections ← convert ← core (no cycles).
