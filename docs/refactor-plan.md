# Type System Refactor Plan

Goal: Break up `core.clj` (the god namespace) into focused modules while keeping the test suite green at every step.

## Phase 1: Extract the Foundation

1. **Move store into `store.clj`** — `*store*`, `with-store`, `reset-store!`, `set-store!`
2. **Expand `types.clj`** — add `IDaciteHash`, `encode-value` multimethod, `typed-value-hash`, `node-hash`
3. **Clean `hash.clj`** — remove `encode-value` and `typed-value-hash`/`node-hash` (they move to types)
4. **Update `core.clj`** — use the new locations (still the god namespace, but importing from types/store)
5. **Update test requires** as needed
6. ✅ Test suite pass + commit

## Phase 2: Extract the Types

1. **Create `scalar.clj`** — `DaciteScalar` + scalar constructors + scalar multimethod implementations
2. **Create `collections.clj`** — all 4 collection deftypes + constructors + internal helpers
3. **Create `convert.clj`** — `dac->clj`, `clj->dac`
4. **Slim `core.clj`** to thin re-exports
5. **Update test requires**
6. ✅ Test suite pass + commit
