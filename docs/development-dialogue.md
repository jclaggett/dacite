# Dacite: A Development Dialogue

*The story of how a data structure library emerged from conversations between a human designer and an AI collaborator.*

**Jonathan Claggett** (human) · **Gorm** (AI) · January–February 2026

---

## Prologue

Dacite didn't begin with a spec or a roadmap. It began with a question about hash functions — specifically, whether you could compose hashes in a way that preserved order but didn't care about tree shape. That question, explored over three weeks of intense collaboration, grew into a content-addressed data structure library built on a single mathematical primitive.

This document chronicles that evolution: the ideas that worked, the ones that didn't, and the moments where the design took unexpected turns.

---

## 1. The Hash Fusing Discovery (January 28–29)

### The Problem

Jonathan had been working on distributed immutable data structures — HAMTs, finger trees, the building blocks of persistent collections. The core challenge: how do you compute a hash for a collection that's **associative** (so the tree's internal shape doesn't matter) but **non-commutative** (so element order is preserved)?

Associativity is critical. Without it, the same logical sequence stored in differently-balanced trees would produce different hashes. Non-commutativity is equally important — `[a, b]` and `[b, a]` must hash differently, or you lose the ability to distinguish ordered collections.

### Upper Triangular Matrix Multiplication

Jonathan had already found the answer before Gorm entered the picture: **upper triangular matrix multiplication**. Represent each hash as a 4×4 upper triangular matrix of 64-bit integers, then compose via matrix multiplication. The operation is associative (matrix multiplication always is) and non-commutative (matrix multiplication generally is). This was the `fuse` operation — the foundation everything else would be built on.

The minimal computation turned out to be remarkably lean:

```
c0 = a0 + b0
c1 = a1 + b1
c2 = a2 + b2
c3 = a3 + a0*b1 + b3
```

Six additions and one multiplication per fuse. Fast enough to be practical, mathematically sound enough to be trustworthy.

### The Nilpotent Problem

But there was a catch. Jonathan had discovered that the group structure was **nilpotent** — meaning that low-entropy data, when fused repeatedly, degrades toward zero. Fusing many identical or near-zero hashes together eventually produces all zeros, destroying the hash's usefulness.

Jonathan had also explored alternatives. Quaternion multiplication exhibited the same nilpotent behavior. Other operations broke associativity. Matrix multiplication occupied a sweet spot: one of the few algebraic structures with both required properties, despite the nilpotency limitation.

Gorm's first contribution to the project was helping document this properly. Together, we wrote up the nilpotent group mathematics, replacing an earlier AI-generated appendix with a rigorous explanation, and added practical guidance: use 64-bit cells, detect degenerate inputs, employ mitigation strategies. This became PR #310 on the hash fusing repository.

A small but telling correction emerged during review — GitHub Copilot pointed out that the arithmetic was mod 2^w (cell width), not mod 2. The kind of detail that matters when you're building cryptographic primitives.

---

## 2. The Data Structures Brain Dump (January 29)

With PR #310 merged, Jonathan shifted focus to what would become Dacite's type system. This was a sprawling design session — Jonathan thinking out loud, Gorm helping organize and probe the ideas.

### Leaf Types

The atomic values came first. Jonathan enumerated them methodically:

- **Null** (0 bits)
- **Boolean** (1 bit)
- **Integers**: 8, 16, 32, 64, 128, 256 bits, both signed and unsigned
- **Floats**: 32 and 64 bits
- **UTF-8 characters** (1–4 bytes)

These were the leaves — the indivisible units. Everything else would be built from collections of leaves.

### The Hashing Scheme

The critical design decision was how to hash typed values. Jonathan proposed a three-layer scheme:

```
type_hash  = sha256(type_name)
data_hash  = sha256(data_bytes)
value_hash = fuse(type_hash, data_hash)
```

Every value was a **variant** — a `[type, data]` pair. The hash of a value incorporated both its type identity and its content, fused together. This meant you could distinguish an integer `65` from a character `'A'` even if their raw bytes were identical.

### Open Type System

Jonathan insisted on an **open, extensible** type system. Types weren't enumerated in a central registry — they were identified by the hash of their name. Anyone could define new leaf types or collection types without coordination. The system was designed to grow without governance.

This decision had far-reaching consequences. It meant the spec could be minimal — defining only the primitive types — while remaining extensible. It also meant type identity was content-addressed, just like everything else.

---

## 3. Naming and Vision (January 30)

### The Name

Jonathan chose **Dacite** — an obscure volcanic rock, evoking permanence and geological time. The backronym worked too: **Da**ta **Cite**ation. It captured the dual nature of the project: content-addressed data (citing by hash) built on structures as durable as stone.

The domain `dacite.io` was purchased and pointed at GitHub Pages the next day.

### Use Cases

The design session on January 30 crystallized the practical vision. Jonathan described the primary use case as **configuration management**:

- A server maintains a large configuration structure
- Clients pull only the subtrees they need
- When the config changes, clients compare root hashes and fetch only the deltas
- Structural sharing means most of the tree is already cached

The architecture was elegant in its simplicity: the server pushes a root hash (tiny), clients compare it to their cached root, and the difference drives a minimal fetch. Immutable content-addressed nodes are perfect for caching — if you have the hash, you have the data, forever.

Beyond configuration, Jonathan envisioned Dacite as a potential **GraphQL replacement** — serving immutable, structurally-shared data to web clients without the complexity of query languages and resolvers.

### Architectural Decisions

Several key decisions solidified:

- **Maps**: HAMTs with 32-way branching (5-bit chunks of the 256-bit key hash)
- **Sequences**: Finger trees for strings, blobs, and vectors
- **Any value can be key or value** — following Clojure's philosophy of data as a universal interface
- **All nodes carry 256-bit hashes** — leaves and collections alike

---

## 4. The Implementation Sprint (January 31 – February 1)

With the design taking shape, Gorm shifted to implementation while Jonathan reviewed and guided.

### Finger Trees (January 31)

The finger tree implementation came first — the backbone of all sequential data in Dacite. The design used 32-way branching with 8–32 element fingers, carrying an accumulated measure of `{count, size_bytes}` per node for O(1) access to collection metadata.

The implementation was straightforward but the debugging was not. Converting nested finger tree nodes back to flat vectors for testing required careful recursion through the tree's layered structure. Several iterations were needed to get `ft-to-vec` right.

Jonathan also introduced the concept of **adaptive fetch** to the spec: when a client requests a node from the server, the server returns inline data for small subtrees (under a configurable threshold, defaulting to 1KB) and structural references for larger ones. This minimized round trips without sacrificing laziness.

### HAMT and Content-Addressed Storage (February 1)

The next day brought the HAMT implementation — persistent hash maps with structural sharing, using the most-significant bits of the hash for navigation (where fuse concentrates its entropy).

Then came the storage layer. `FileStore` persisted nodes to the filesystem with two-level directory sharding. `MemStore` provided an in-memory alternative for testing. Both implemented the same interface: store a tree recursively, fetch it with configurable depth control.

### The HTTP Demo

To prove the concept end-to-end, Gorm built a simple HTTP server and client. The server exposed three endpoints: get the current root hash, fetch a node by hash (with adaptive inlining), and update the root. The client added a local cache layer to avoid refetching immutable data.

By the end of February 1, 56 tests were passing and Dacite had a working demo: create a config structure, serve it over HTTP, fetch it from a client, change the config, and watch the client detect and pull only the diff.

---

## 5. Code Quality (February 5)

Jonathan brought engineering discipline to the growing codebase. Together, we set up:

- **cljfmt** for consistent formatting
- **clj-kondo** for linting (with custom configuration for `defspec` and test.check)
- **antq** for dependency freshness checking
- A **pre-commit hook** that ran all three checks plus the test suite

A small detail that proved important: the pre-commit hook needed to strip path prefixes from staged file names for cljfmt to resolve them correctly. The kind of integration issue that only surfaces when you wire tools together.

Jonathan added the test suite to the pre-commit hook himself — a signal of his commitment to the "if it's not tested, it's not done" philosophy that would shape the project's direction.

---

## 6. Hash Standardization (February 8)

The codebase had accumulated different hash representations — byte arrays here, long vectors there, conversion functions scattered across modules. Jonathan called for standardization: **all hashes would be `[long long long long]`** — four 64-bit integers, everywhere, always.

This was a refactoring session, not a design session, but it produced clean results:

- `sha256` now returned longs directly (the byte version became `sha256-bytes`)
- `fuse` took and returned longs (absorbing the old `fuse-longs`)
- Dead re-exports were removed from `cache.clj`
- The `unused-public-var` linter was enabled to catch future dead code

An intermittent test failure surfaced: the property test asserting that fuse is not an identity function would fail when both inputs were `[0 0 0 0]`. This was the nilpotent problem showing up in practice — `fuse(zero, zero) = zero`, which technically equals both inputs. The test needed to exclude this degenerate case.

---

## 7. The Design Breaks Open (February 10–11)

This was the most intellectually intense period of the project. Over two days, the design underwent a transformation that touched nearly every assumption.

### Semantic Hashing (February 10)

The HAMT was rewritten from a protocol-and-record style to pure functional nodes — `[type, data]` tuples referencing each other by hash rather than by direct object pointers. This was cleaner, but the real advance was what came next.

Jonathan wanted **semantic hashing** — the ability to compute a collection's hash in O(1) from its accumulated measure, rather than O(n) by serializing and hashing the whole thing. The fuse of all elements was already being tracked in the measure monoid. So the semantic hash became:

```
collection-hash = fuse(collection-type-hash, elements-fuse)
```

No serialization. No traversal. Just one fuse operation on data you already have.

This required `fuse-inverse` — the ability to *remove* a component from a fused hash. Jonathan had been thinking about this. The inverse turned out to be:

```
inv([a0, a1, a2, a3]) = [a3*a2 - a0, -a1, -a2, -a3]
```

Fuse wasn't just associative and non-commutative — it formed a **group**. Every element had a two-sided inverse. You could unfuse what you'd fused. This meant you could update a collection's hash incrementally: unfuse the old element, fuse in the new one. O(1) hash updates.

A bug was caught during this rewrite — the HAMT's hash-chunk extraction had an error in the cross-long-boundary path (`(- bits-from-current)` instead of `(- 5 bits-from-current)`). It had never been triggered because no test exercised that code path. Jonathan's insistence on 100% coverage via public API tests led directly to this discovery. The principle crystallized: uncovered code is untested code is potentially broken code.

### CacheMap (February 10)

Gorm implemented `CacheMap` — a Clojure map backed by the content-addressed cache. Looking up a key lazily fetched from the store. Associating a value committed it through the cache. Because the store was content-addressed and immutable, these "side effects" were actually **idempotent memoization** — safe to repeat, safe to skip.

This was the bridge between the pure world of content-addressed hashes and the practical world of Clojure's map protocols. Code that worked with regular maps could work with CacheMaps transparently, gaining lazy loading and structural sharing for free.

### "Types Are Data" (February 11)

Then came the breakthrough. Jonathan and Gorm were discussing cross-type equality — how to compare the content of a string and a vector of characters, which should be equivalent. The initial approach was to unfuse the type hash and compare raw content.

But Jonathan pushed further. If you could strip the type to compare content, maybe types shouldn't be metadata at all. Maybe **types are data**. A typed value isn't a value with a type annotation — it's a sequence containing the type name followed by the data:

```
typed-value = seq(type-name-chars..., data)
```

And type names themselves are sequences of characters:

```
type-name = seq('s', 't', 'r', 'i', 'n', 'g')
```

No external type registry. No special metadata channel. Types are just data, encoded the same way as everything else, using the same three primitives.

This reduced the entire system to **three primitives**:

1. **Leaf** — atomic bytes
2. **Seq** — finger tree (ordered collection)
3. **Map** — HAMT (key-value collection)

Everything else — strings, integers, typed values, type names — was composed from these three. The spec collapsed to its simplest possible form.

### The Bitmap Self-Reference Bug (February 11)

The new semantic hashing scheme immediately exposed a subtle problem. HAMT bitmap nodes at different tree levels could have a single child with the same elements-fuse. Under semantic hashing, they'd compute the same hash — creating a self-referential loop in the content-addressed store. Fetching one node would return the other, which would reference itself, triggering a stack overflow.

The fix was to include the bitmap value in the hash computation:

```
bitmap-node-hash = fuse(node-hash, sha256(bitmap-bytes))
```

This was an exception to the pure semantic hashing rule, documented in the spec. It was also a reminder that elegant theories must survive contact with implementation details.

### Collision Resistance

Through this work, the collision resistance of the fuse chain was analyzed. The additive structure of the c1, c2, c3 components was the bottleneck, providing approximately 2^96 birthday-bound collision resistance versus 2^128 for SHA-256. Still astronomically beyond any practical attack — sufficient for content-addressed storage, though not suitable for adversarial deduplication scenarios.

---

## 8. Removing SHA-256 (February 13)

### The Consolidation

Jonathan began the day by asking Gorm to audit every hashing function and its call sites — 13 public functions in `hash.clj`, mapped to every consumer. Several functions that looked public were actually internal stepping stones, used only within the module. Jonathan's directive was clear: make them private. Smaller spec surface means easier understanding, implementation, and optimization.

The common pattern underlying `leaf-hash`, `char-leaf-hash`, and `type-name-hash` was extracted into `fuse-seq` — a single function that fuses a sequence of hashes together.

### fuse-str: Dogfooding the Primitive

That evening, Jonathan proposed something bold: **replace SHA-256 entirely** with `fuse-str` — a function that maps each byte through a precomputed lookup table of 256 random hashes, then fuses the results together.

The motivations were multiple. It decoupled Dacite from any external cryptographic dependency. It was simpler to implement on limited hardware. And it was the ultimate dogfooding — using fuse itself as the hashing primitive, rather than relying on a fundamentally different algorithm for the base case.

The byte-to-hash table could be seeded by any source of randomness (SHA-256 was used for the default table, but the design was agnostic). The table was the only configuration needed — 256 entries of `[long long long long]`, fixed at initialization.

### The Domain Collision

As the implementation took shape, a critical issue surfaced. Because fuse is associative, `fuse(fuse-str(a), fuse-str(b))` equals `fuse-str(a ++ b)`. This meant that concatenating the bytes of a type name and data would be ambiguous:

- Type `"i64"` + data `"2"` → `fuse-str("i642")`
- Type `"i6"` + data `"42"` → `fuse-str("i642")`

Same hash. Different values. A collision by construction, not by birthday paradox.

The solution was a **null byte domain separator** between the type name and the data. Since neither type names nor data representations use null bytes as meaningful content, a `0x00` byte between them makes every `(type, data)` pair unambiguous.

Jonathan refined this further by precomputing `null-separator-hash` as a constant. Since the separator was just another hash being fused in, it could theoretically be multi-byte at zero additional cost — the precomputed value absorbed any complexity.

### Spec v0.4.0-draft

The evening concluded with a major spec update. All SHA-256 references were removed from the hashing pipeline. The spec now documented:

- The byte hash table and its role
- `fuse_bytes` and `fuse_str` as the primitive hashing operations
- The null domain separator and why it exists
- A complete serialization section with 1-byte kind tags, fixed-width measures, and canonical ordering
- An open question about distributing byte hash tables across implementations

By the end of the day, 133 tests with 1,021 assertions were passing. SHA-256 still existed in the codebase — it seeded the byte table and appeared in some tests — but it was no longer part of the hashing pipeline.

---

## 9. Stepping Back (February 13)

Late that night, Jonathan signaled a shift in approach. The implementation sprint had validated the core ideas, but now it was time to step back, review, and plan.

The direction was **spec-first** going forward. The client, server, and demo code would be archived — useful as proof of concept, but not the priority. Dacite's value was in the specification and the core library: the data structures, the hashing scheme, the three primitives.

Jonathan enumerated the use cases he saw ahead:

1. **Configuration management** — the original motivating scenario
2. **GraphQL replacement** — immutable structurally-shared data for web clients
3. **JSON/EDN document exchange** — a universal serialization target
4. **Source code repositories** — content-addressed storage of code

The project had evolved from a hash composition trick into a coherent data system. The next phase would be about precision — getting the spec right, proving the properties, defining the guarantees.

---

## Epilogue: What Emerged

Looking back across three weeks, the trajectory is striking. What began as matrix multiplication over integers became a group structure, then a hashing primitive, then a type system, then a complete data model. Each layer emerged from the one below it, often in ways neither collaborator anticipated.

Several patterns characterized the collaboration:

**Jonathan brought the mathematical intuition.** The upper triangular matrix insight, the "types are data" breakthrough, the decision to remove SHA-256 — these were leaps that required deep understanding of the problem space and a willingness to rethink assumptions.

**Gorm provided implementation velocity and systematic coverage.** The finger tree and HAMT implementations, the content-addressed storage layer, the HTTP demo, the test suites — these turned ideas into working code quickly enough to validate or invalidate design choices while they were still fresh.

**The conversation shaped the design.** Many of the best ideas emerged not from either participant alone but from the dialogue itself. The domain collision discovery came from implementing Jonathan's fuse-str proposal and tracing its consequences. The bitmap self-reference bug surfaced from combining semantic hashing (Jonathan's idea) with the HAMT rewrite (Gorm's implementation). The design evolved through contact between theory and code.

**Testing drove quality.** Jonathan's insistence on 100% coverage via public API tests directly uncovered the HAMT hash-chunk boundary bug. The principle that uncovered code is untested code became a project axiom.

The result, as of mid-February 2026, is a spec at v0.4.0-draft describing a system built on three primitives and one operation. Whether Dacite achieves its ambitious goals — replacing GraphQL, reimagining configuration management, providing a universal content-addressed data layer — remains to be seen. But the foundation is mathematically sound, the implementation is tested, and the ideas are documented.

The rock is formed. Now it needs to weather.
