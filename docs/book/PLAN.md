# The Dacite Book — Plan

## Concept

Reorganized around three conceptual layers (down from five). Each chapter
stands alone; read sequentially for full picture.

## Structure

```
docs/book/
├── 00-preface.md
├── 01-stores/chapter.md          # Layer 1: persistence
├── 02-hash-fusion/chapter.md     # Layer 2: primitive
├── 03-values/chapter.md          # Layer 3: data model
├── archive/                      # Historical chapters (superseded)
│   ├── 04-authorization/         # Layer 4: auth (archived 2026-06-05)
│   └── 05-sharing/               # Layer 5: sharing (archived 2026-06-05)
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
| ~~auth-design §1–5, App A(GC)~~ | ~~04~~ (archived) |
| ~~auth-design §6(peer)~~ | ~~03+04~~ (archived) |
| ~~auth-design §7–9, App B–D~~ | ~~05~~ (archived) |
| ... | ... |

> **Note**: Chapters 4 & 5 (authorization, sharing) archived 2026-06-05.
> See `archive/README.md`. The events-based design supersedes them.

## Reading Order vs Implementation Layering

**Reading order** (the book): stores → hash fusion → values.

**Implementation layering** (the library): two foundational packages with no dependency on the value model — **hash** (fuse, byte table, protocol id) and **stores** (content-addressed persistence, root ref, backends). Everything else builds on one or both:

```
hash ─────────────┐
                  ├──► values
stores ───────────┘
```

Values combine hashing with store-backed nodes.

The book introduces stores before hash for pedagogy; in code, `dacite.store` and `dacite.hash` are peers at the bottom of the stack.

| Book ch. | Topic | Library |
|----------|-------|---------|
| 1 | Stores | `dacite.store` (foundational) |
| 2 | Hash fusion | `dacite.hash` (foundational) |
| 3 | Values | `dacite.value.*` (→ hash + stores) |

## Writing Approach

Intuition → precision → examples → API → properties → guarantees.

## Migration Process

Ongoing; review chapter-by-chapter.

## Open Questions

- Spec subsections per chapter?
- Allium placement?
- Preface dialogue?
