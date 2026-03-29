# The Dacite Book — Plan

## Concept

The documentation reorganizes around Dacite's four conceptual layers.
Each part is a chapter that builds on the previous — a reader can stop
at any layer and have a complete understanding of Dacite at that level.

The existing docs stay in place. Content migrates into this structure
as each chapter is written. Old files become references until fully
subsumed, then get replaced with pointers or removed.

## Structure

```
docs/book/
├── PLAN.md              ← this file
├── 00-preface.md        ← what Dacite is, who it's for, how to read this
│
├── 01-hash-fusion/
│   └── chapter.md       ← the cryptographic primitive
│                          • 256-bit hashes as 4×64-bit words
│                          • fuse function (upper triangular matrix)
│                          • group structure (inverse, unfuse)
│                          • byte hashing, string hashing
│                          • low-entropy rejection
│                          • protocol ID
│
├── 02-values/
│   └── chapter.md       ← data model
│                          • three primitives: scalar, seq, map
│                          • typed values (convention, type names, semantic hash)
│                          • built-in types: null, bool, int, float, char, string,
│                            vector, map, set, blob, neg
│                          • negative sets (cofinite sets)
│                          • internal structures: finger trees, HAMT
│                          • node hashing, measure monoid
│                          • parameters (branching factor, HAMT hash navigation)
│
├── 03-stores/
│   └── chapter.md       ← persistence and distribution
│                          • IStore protocol (fetch/store/has?)
│                          • store implementations (mem, file, LMDB, layered)
│                          • cache map
│                          • serialization (binary format, JSON format)
│                          • distribution model (adaptive fetch, sync, caching)
│                          • peer-to-peer store model
│
├── 04-sharing/
│   └── chapter.md       ← authorization and multi-user
│                          • core principle: knowing ≠ authorized
│                          • proof of possession (data form, structural form)
│                          • two kinds of stores (authenticated, unauthenticated)
│                          • GET protocol (proof chains)
│                          • PUT protocol (structural proofs)
│                          • root convention: {value, shares, groups}
│                          • shares map, named groups, named references
│                          • claim protocol
│                          • share types (private, direct, shared, public)
│                          • structural sharing across users
│                          • audit trail
│                          • garbage collection (semi-space collector)
│
└── appendices/
    ├── A-design-evolution.md    ← both auth and sharing evolution
    ├── B-rejected-alternatives.md
    ├── C-development-dialogue.md  ← the existing narrative
    └── D-roadmap.md
```

## Source Mapping

Where existing content maps to the new structure:

| Source | Target |
|--------|--------|
| `spec/SPEC.md` §Hash–§Fuse | `01-hash-fusion/chapter.md` |
| `spec/SPEC.md` §Primitives–§HAMT Parameters | `02-values/chapter.md` |
| `spec/SPEC.md` §Storage–§Serialization | `03-stores/chapter.md` |
| `spec/SPEC.md` §Distribution | `03-stores/chapter.md` |
| `authorization-design.md` §1–§5 | `04-sharing/chapter.md` (proof of possession, GET, PUT) |
| `authorization-design.md` §6 | `03-stores/chapter.md` (peer model) |
| `authorization-design.md` §7 | `04-sharing/chapter.md` (shares map) |
| `authorization-design.md` §8–§9 | `04-sharing/chapter.md` (structural sharing, audit) |
| `authorization-design.md` Appendix A (GC) | `04-sharing/chapter.md` (GC) |
| `authorization-design.md` Appendix B–D | `appendices/` |
| `development-dialogue.md` | `appendices/C-development-dialogue.md` |
| `roadmap.md` | `appendices/D-roadmap.md` |
| `sharing-design-notes.md` | archived (subsumed by §7 and Appendix D) |
| `thoughts-2026-02-27.md` | archived (historical) |
| `authorization.allium` | TBD — formal spec track, possibly per-chapter |
| `spec/schema/*.json` | `03-stores/` (serialization schemas) |

## Library Layering

Each chapter maps to an independent library with a clean API boundary:

```
Layer 1: hash    — zero dependencies, pure math
Layer 2: values  — depends on hash only
Layer 3: stores  — depends on hash + values
Layer 4: sharing — depends on hash + values + stores
```

This is a dependency chain, not a monolith. Each layer can be
implemented, tested, and ported independently. A Rust or C port starts
with Layer 1 (a weekend of integer arithmetic), adds Layer 2 (data
structures, still no I/O), then Layer 3 (where platform-specific
choices like LMDB vs SQLite diverge). Layer 4 is protocol-level and
can share a spec across languages.

Each chapter ends with an **API Surface** section listing the public
functions, their signatures, and testable properties — serving as both
documentation and a module specification for implementors.

## Writing Approach

Each chapter should:
1. **Stand alone** — readable without the others (but references previous chapters)
2. **Start with intuition** — what problem does this layer solve?
3. **Build to precision** — spec-level detail after the intuition lands
4. **Include examples** — concrete Dacite values, not just abstractions
5. **End with API surface** — public functions, signatures, test properties
6. **End with guarantees** — what does this layer provide to the next?

## Tone

Technical but accessible. Jonathan's "development dialogue" voice —
two people thinking together, but polished into a cohesive narrative.
Not a dry spec. Not a tutorial. A book you'd actually read.

## Migration Process

1. Write chapter drafts by reorganizing + editing existing content
2. Review with Jonathan (chapter by chapter)
3. Once a chapter is accepted, mark source sections as "migrated"
4. After all chapters done, old files become historical references
5. SPEC.md may remain as the formal/terse companion to the book

## Open Questions

- Should each chapter have a formal "spec" subsection (terse, precise)
  alongside the narrative? Or keep SPEC.md as a parallel track?
- Does the allium spec live alongside chapters or in its own track?
- Should the preface include the development dialogue narrative, or
  keep that as a standalone appendix?
- Chapter numbering: 1-indexed (human-friendly for a book) or
  0-indexed (programmer-friendly)?
