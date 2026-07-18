# Host Collection Adapters

**Status:** DRAFT — design only; implementation phased after agreement.

**Related:** [portable-core.md](portable-core.md) (functional API + host matrix),
value layer in The Dacite Book (Chapter 3).

## Summary

Dacite values are content-addressed and store-backed. Semantics live in shared
`.cljc` operations (`dacite.value.collections`, finger-tree, HAMT).

**Host collection adapters** make those values participate in the host
language’s normal collection dispatch:

| Host | Mechanism |
|------|-----------|
| JVM Clojure | `clojure.lang.*` interfaces on `deftype` |
| Compiled ClojureScript | `cljs.core` protocols on the same deftypes |
| SCI (babashka, nbb) | **No adapter** — use `dacite.value.api` |
| Future ports (Python, C, …) | Language-native adapters later; not this doc |

The **canonical, portable surface** remains `dacite.value.api` (and the same
operations under other language names). Adapters are **host sugar**: one
implementation of behavior, multiple entry points.

```
  App (portable / SCI / ports)     App (idiomatic JVM / compiled CLJS)
           │                                    │
           ▼                                    ▼
   dacite.value.api (fn)              count / conj / get / nth
           │                                    │
           └──────────────┬─────────────────────┘
                          ▼
              coll ops + IDaciteValue + store
```

---

## Goals

1. On JVM and compiled CLJS, Dacite vectors/maps/sets work with core
   functions that dispatch on interfaces or protocols (`count`, `conj`,
   `nth`, `get`, `assoc`, `seq`, …).
2. **Single semantic implementation** — adapter methods only call shared
   coll helpers (`vec-conj`, `map-get`, `extract-hash`, …).
3. **Content-addressed equality** — `=` / `equiv` for two Dacite values of
   the same type is equality of content hashes (and thus of store-backed
   structure), not element-wise comparison to host collections.
4. Construction and updates **persist into the receiver’s store** (via
   `extract-hash` / `ensure-in-store!`).

## Non-goals (v1)

- Full parity with `PersistentVector` / CLJS `PersistentVector` (metadata,
  transients, every `IKVReduce` / transducer edge case).
- Promising that `(conj dacite-vec x)` works under **SCI** (babashka, nbb)
  without a separate, evidence-based spike.
- Replacing or deprecating `dacite.value.api`.
- Cross-language adapters (Python `Sequence`, etc.) — same *idea*, later
  docs.

---

## Host matrix

| Host | Adapter technology | Intent |
|------|-------------------|--------|
| **JVM Clojure** | `clojure.lang.IPersistentVector`, `IPersistentMap`, `Counted`, `Indexed`, `ILookup`, `Seqable`, `IHashEq`, … | **Exists** on deftypes behind `#?@(:bb [] :clj […])`. Complete gaps, document, test. |
| **Compiled CLJS** | `cljs.core/ICounted`, `IIndexed`, `ILookup`, `ICollection`, `IAssociative`, `IStack`, `IVector`, `IMap`, `ISet`, `ISeqable`, `IHash`, `IEquiv`, `IFn`, … | **Add** on the same deftypes under `#?(:cljs …)`. |
| **babashka (SCI)** | — | Keep interfaces **off** (`:bb []`). Use functional API. |
| **nbb (SCI on Node)** | — | Not full CLJS protocol dispatch for core. Use functional API. |
| **Python / C / …** | Host-specific | Out of scope here; mirror the dual-layer idea. |

**nbb vs compiled CLJS:** Implementing `ICounted` may not make `(count v)`
hit that protocol under SCI. Treat **compiled CLJS** and **nbb** as
different hosts. Do not claim “ClojureScript support” without naming which.

---

## Architecture rules

1. **Portable core first** — hashing, trees, store I/O, `IDaciteValue`, and
   functional ops must not depend on JVM interfaces or cljs protocols.
2. **Adapters are thin** — no second copy of finger-tree/HAMT logic inside
   interface methods.
3. **Reader conditionals** — JVM: `#?@(:bb [] :clj […])` (babashka matches
   `:clj` but must skip interfaces). CLJS: `#?(:cljs […])` for protocols;
   never load `clojure.lang.*` on cljs.
4. **Return types stay Dacite** — `conj` / `assoc` / `pop` return new
   Dacite values in the same store, not host PersistentVector/Map.

---

## Required surface (v1)

### Vector

| Capability | JVM | CLJS protocol |
|------------|-----|----------------|
| count | `Counted` | `ICounted` |
| nth | `Indexed` | `IIndexed` |
| lookup by index | `ILookup` | `ILookup` |
| seq | `Seqable` | `ISeqable` |
| conj (append) | `IPersistentCollection` | `ICollection` |
| empty | `IPersistentCollection` | `IEmptyableCollection` |
| peek / pop | `IPersistentStack` | `IStack` |
| assoc by index | `Associative` / `IPersistentVector` | `IAssociative` / `IVector` |
| as function | `IFn` | `IFn` |
| hash / equiv | `IHashEq`, `Object` | `IHash`, `IEquiv` |
| sequential marker | `Sequential` | `ISequential` |

### Map

| Capability | JVM | CLJS protocol |
|------------|-----|----------------|
| count | `Counted` | `ICounted` |
| lookup | `ILookup` | `ILookup` |
| assoc | `Associative` / `IPersistentMap` | `IAssociative` |
| dissoc / without | `IPersistentMap` | `IMap` |
| seq entries | `Seqable` | `ISeqable` |
| conj pair | `IPersistentCollection` | `ICollection` |
| empty | `IPersistentCollection` | `IEmptyableCollection` |
| as function | `IFn` | `IFn` |
| hash / equiv | `IHashEq`, `Object` | `IHash`, `IEquiv` |
| map marker | `MapEquivalence` | (CLJS map hierarchy as needed) |

### Set

| Capability | JVM | CLJS protocol |
|------------|-----|----------------|
| count | `Counted` | `ICounted` |
| contains / get | `ILookup` | `ILookup` |
| conj | `IPersistentCollection` | `ICollection` |
| seq | `Seqable` | `ISeqable` |
| empty | `IPersistentCollection` | `IEmptyableCollection` |
| as function | `IFn` | `IFn` |
| hash / equiv | `IHashEq` | `IHash`, `IEquiv` |
| set marker | **`IPersistentSet` (gap today)** | `ISet` |

### String / blob (minimum)

| Capability | JVM | CLJS |
|------------|-----|------|
| count | `Counted` | `ICounted` |
| seq of chars/bytes | `Seqable` | `ISeqable` |
| CharSequence | JVM string only (optional polish) | — |

---

## Semantics that intentionally differ from host collections

| Topic | Host collections | Dacite |
|-------|------------------|--------|
| **`=` / equiv** | Structural, element-wise | Same type + **same content hash** |
| **`=` vs host coll** | n/a | **False** even if elements “match” when realized |
| **hasheq / hash** | Structural | Derived from content hash (`hash->int`) |
| **conj / assoc of plain data** | Stays ephemeral | **Coerced into the receiver’s store** (`extract-hash`, `ensure-in-store!`) |
| **seq / get elements** | Host values | **Wrapped Dacite values** (lazy store access) |
| **realize** | n/a | Explicit host projection (`types/realize` / `d/realize`) |
| **meta** | Common | **Not in v1** |
| **transients** | Common | **Not in v1** |

These differences are features of a content-addressed store-backed model.
Document them next to any “drop-in vector” marketing so callers are not
surprised.

### Equality (decision for v1)

**Keep hash-identity equivalence.**

Rationale: two values with the same hash are the same immutable datum in
the store; comparing large trees element-wise is expensive and can pull
from the network. Interop recipes that need “same elements as a Clojure
vector” should `realize` (with care) or walk via `d/seq` and compare
explicitly.

Revisit only if real apps force painful interop with code that assumes
`(= coll other-coll)` across representations.

---

## Current JVM inventory (as of this writing)

Implemented behind `#?@(:bb [] :clj […])` in `dacite.value.collections`
(and scalars for `IHashEq` where applicable):

| Type | Present (high level) | Notable gaps |
|------|----------------------|--------------|
| **DaciteVector** | Counted, Seqable, ILookup, Indexed, IPersistentCollection, IPersistentStack, Associative, IPersistentVector, Sequential, IFn, IHashEq | `equiv` only vs DaciteVector; some `assoc` paths rebuild from full seq; no IObj/meta; no IKVReduce |
| **DaciteMap** | Counted, Seqable, ILookup, IPersistentCollection, Associative, IPersistentMap, MapEquivalence, IFn, IHashEq | `equiv` only vs DaciteMap; no IKVReduce / reduce-kv specialization |
| **DaciteSet** | Counted, Seqable, ILookup, IPersistentCollection, IFn, IHashEq | **No `IPersistentSet` / `disjoin`** surface; incomplete set interop |
| **DaciteString** | Counted, Seqable, CharSequence, IHashEq | Not a full IPersistentVector of chars |
| **DaciteBlob** | Counted, Seqable, IHashEq | Minimal |

Rooted stores already expose a **portable function API** plus JVM
`IAtom`/`IDeref` conveniences (`dacite.rooted`) — analogous split.

---

## Implementation phases

### Phase 0 — This document

Land design + link from portable-core. No behavior change required.

### Phase 1 — JVM complete + tests

- Fill clear gaps (e.g. `IPersistentSet` / `disjoin` for sets if straightforward).
- Tests: for each op, native core fn on Dacite value **agrees with**
  `dacite.value.api` (same content hash of result, same store).
- Document equality and “elements are Dacite wrappers” in the book or
  API docstrings.
- Keep `:bb []` exclusion unless a dedicated SCI spike says otherwise.

### Phase 2 — Compiled CLJS protocols

- Add `#?(:cljs …)` protocol implementations mirroring Phase 1 semantics.
- Requires a **compiled** CLJS test path (not only nbb). If the repo has
  no shadow-cljs/figwheel setup yet, add a minimal one or test via a
  small cljs target — call out in the implementing PR.
- Hash parity with JVM still validated via portable functional path /
  `bin/hash-parity.sh` (nbb), not via protocol dispatch.

### Phase 3 — Optional SCI spike (may close as “won’t do”)

- Spike: can babashka or nbb dispatch `count`/`get`/`conj` to custom
  types/protocols usefully?
- Success criterion: a small demo without `d/` on that host.
- Default expectation: **close as non-goal** and keep teaching `d/*`.

---

## Relation to examples and apps

| Code style | When |
|------------|------|
| `d/conj`, `d/get`, … | Portable examples (todo), SCI, any code shared across hosts |
| `(conj v x)`, `(get m k)` | JVM-only or compiled-CLJS-only modules, after Phase 1/2 tests |

Apps must still handle **store affinity**: plain data conj’d onto a Dacite
collection is stored in that collection’s store. Multi-store processes
should not rely on a process-global `*store*` alone (see todo example
recipe and `ensure-in-store!`).

---

## Open questions

1. **Set API completeness** — is `IPersistentSet` + `disjoin` required for
   v1 or can sets stay “lookup + conj + seq” until needed?
2. **Compiled CLJS toolchain** — introduce shadow-cljs (or similar) for
   Phase 2 tests, or wait until a CLJS product target exists?
3. **Deep equiv to host collections** — any app that needs it before we
   stick to hash-identity forever?
4. **Performance** — prioritize fixing vector `assoc` rebuild paths as part
   of Phase 1 or track separately?

---

## References

- Implementation: `impl/clojure/src/dacite/value/collections.cljc`
- Portable API: `impl/clojure/src/dacite/value/api.cljc`
- Portability contract: [portable-core.md](portable-core.md)
- Store-aware extract: `dacite.value.types/extract-hash`, `ensure-in-store!`
