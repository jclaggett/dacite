# The Dacite Book — Plan

## Concept

Reorganized around five conceptual layers (expanded from four). Each chapter
stands alone; read sequentially for full picture.

## Structure

```
docs/book/
├── 00-preface.md
├── 01-hash-fusion/chapter.md     # Layer 1: primitive
├── 02-values/chapter.md          # Layer 2: data model
├── 03-stores/chapter.md          # Layer 3: persistence/distribution
├── 04-authorization/chapter.md   # Layer 4: PoP, GET/PUT, auth stores, GC
├── 05-sharing/chapter.md         # Layer 5: shares map, claim, conventions
└── appendices/
    A-design-evolution.md
    B-rejected-alternatives.md
    C-development-dialogue.md
    D-roadmap.md
```

## Source Mapping

| Source | Target |
|--------|--------|
| spec/SPEC.md §Hash–Fuse | 01 |
| spec/SPEC.md §Primitives–HAMT | 02 |
| spec/SPEC.md §Storage–Dist | 03 |
| auth-design §1–5, App A( GC) | 04 |
| auth-design §6(peer) | 03+04 |
| auth-design §7–9, App B–D | 05 |
| ... | ...

## Library Layering

Layer 1: hash
Layer 2: values (→hash)
Layer 3: stores (→1+2)
Layer 4: auth (→1-3)
Layer 5: sharing (→1-4, conventions only)

## Writing Approach

Intuition → precision → examples → API → properties → guarantees.

## Migration Process

Ongoing; review chapter-by-chapter.

## Open Questions

- Spec subsections per chapter?
- Allium placement?
- Preface dialogue?
