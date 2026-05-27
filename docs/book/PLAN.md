# The Dacite Book — Plan

## Concept

Reorganized around five conceptual layers (expanded from four). Each chapter
stands alone; read sequentially for full picture.

## Structure

```
docs/book/
├── 00-preface.md
├── 01-stores/chapter.md          # Layer 1: persistence (new Ch. 1)
├── 02-hash-fusion/chapter.md     # Layer 2: primitive (was Ch. 1)
├── 03-values/chapter.md          # Layer 3: data model (was Ch. 2)
├── 04-authorization/chapter.md   # Layer 4: PoP, GET/PUT, auth stores, GC
├── 05-sharing/chapter.md         # Layer 5: shares map, claim, conventions
└── appendices/
    A-design-evolution.md
    B-rejected-alternatives.md
    C-development-dialogue.md
    D-roadmap.md
    serialization.md
```

## Source Mapping

| Source | Target |
|--------|--------|
| spec/SPEC.md §Storage–Dist | 01 |
| spec/SPEC.md §Hash–Fuse | 02 |
| spec/SPEC.md §Primitives–HAMT | 03 |
| spec/SPEC.md (serialization) | appendices/serialization.md |
| auth-design §1–5, App A( GC) | 04 |
| auth-design §6(peer) | 03+04 |
| auth-design §7–9, App B–D | 05 |
| ... | ...

## Reading Order vs Implementation Layering

**Reading order** (the book): stores → hash fusion → values → authorization → sharing.

**Implementation layering** (the library): two foundational packages with no dependency on the value model — **hash** (fuse, byte table, protocol id) and **stores** (content-addressed persistence, root ref, backends). Everything else builds on one or both:

```
hash ─────────────┐
                  ├──► values ──► authorized stores ──► sharing (conventions)
stores ───────────┘
```

Values combine hashing with store-backed nodes. Authorization extends stores; sharing is conventions on top of authorized stores. The book introduces stores before hash for pedagogy; in code, `dacite.store` and `dacite.hash` are peers at the bottom of the stack.

| Book ch. | Topic | Library |
|----------|-------|---------|
| 1 | Stores | `dacite.store` (foundational) |
| 2 | Hash fusion | `dacite.hash` (foundational) |
| 3 | Values | `dacite.value.*` (→ hash + stores) |
| 4 | Authorization | `dacite.auth` (→ stores + values) |
| 5 | Sharing | conventions (→ auth + values) |

## Writing Approach

Intuition → precision → examples → API → properties → guarantees.

## Migration Process

Ongoing; review chapter-by-chapter.

## Open Questions

- Spec subsections per chapter?
- Allium placement?
- Preface dialogue?
