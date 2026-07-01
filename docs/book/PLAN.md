# The Dacite Book — Plan

## Concept

Reorganized around four conceptual layers. The content store (immutable
persistence) and the rooted store (the one mutable root) are split into
separate chapters that bracket the value model: values depend only on the
content store, while roots build on top of values. Each chapter stands
alone; read sequentially for full picture.

## Structure

```
docs/book/
├── 00-preface.md
├── 01-stores/chapter.md          # Layer 1: content store (immutable persistence)
├── 02-hash-fusion/chapter.md     # Layer 2: primitive
├── 03-values/chapter.md          # Layer 3: data model
├── 04-rooted-stores/chapter.md   # Layer 4: mutable root ref, sync, GC
├── archive/                      # Historical chapters (superseded)
│   ├── 04-authorization/         # auth (archived 2026-06-05)
│   └── 05-sharing/               # sharing (archived 2026-06-05)
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
| spec/SPEC.md §Storage (root/sync) | 04 |
| spec/SPEC.md (serialization) | appendices/serialization.md |
| ~~auth-design §1–5, App A(GC)~~ | ~~(archived)~~ |
| ~~auth-design §6(peer)~~ | ~~(archived)~~ |
| ~~auth-design §7–9, App B–D~~ | ~~(archived)~~ |
| ... | ... |

> **Note**: The old Chapters 4 & 5 (authorization, sharing) were archived
> 2026-06-05; see `archive/README.md`. The events-based design supersedes
> them. Chapter 4 is now **Rooted Stores** (the mutable-root half split out
> of the original Chapter 1).

## Reading Order vs Implementation Layering

**Reading order** (the book): content stores → hash fusion → values → rooted stores.

**Implementation layering** (the library): two foundational packages with no dependency on the value model — **hash** (fuse, byte table, protocol id) and **content stores** (content-addressed persistence, backends). Values build on both. The **rooted store** (mutable root, watches, push) wraps a content store and, for GC, also draws on the value model:

```
hash ─────────────┐
                  ├──► values ──► rooted stores
content stores ───┘        └──────────┘
```

Values combine hashing with content-store-backed nodes. Rooted stores add the one mutable root on top.

The book introduces content stores before hash for pedagogy; in code, `dacite.store` and `dacite.hash` are peers at the bottom of the stack. Both the content store and the rooted store live in `dacite.store`.

| Book ch. | Topic | Library |
|----------|-------|---------|
| 1 | Content stores | `dacite.store` (foundational) |
| 2 | Hash fusion | `dacite.hash` (foundational) |
| 3 | Values | `dacite.value.*` (→ hash + content stores) |
| 4 | Rooted stores | `dacite.store` (rooted store → values for GC) |

## Writing Approach

Intuition → precision → examples → API → properties → guarantees.

## Migration Process

Ongoing; review chapter-by-chapter.

## Open Questions

- Spec subsections per chapter?
- Allium placement?
- Preface dialogue?
