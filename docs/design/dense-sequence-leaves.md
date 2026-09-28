# Dense sequence leaves

**Status:** implemented. Digits and nodes are 1k literal pages.

**Related:** `docs/design/boxed-u8-runs.md` (u8 run payload is a copied
byte buffer), `docs/design/leaf-chunking.md` (transport only; durable model
unchanged), `impl/clojure/src/dacite/value/collections.cljc`
(`string-with-store`, `blob-with-store`),
`impl/clojure/src/dacite/value/finger_tree.cljc` (digits 1–32, nodes 2–32).

## What broke

`dacite-library` reset its catalog and ingested Project Gutenberg EPUBs
(Moby Dick #2701, Treasure Island #120, Swiss Family Robinson #11703).
Full books do not fit the current value constructors.

Strings and blobs are finger trees whose leaves are one scalar each.
`string-with-store` does `put-scalar!` per character, then
`ft-from-value-hashes` (conj-right). `blob-with-store` does the same per
byte. A node holds up to 32 child hashes, so one full node is 1,024 bytes
of hashes before its measure. The character or byte scalar is interned
once. The cost is a 32-byte hash slot at every index, plus a node per
handful of slots, plus the object graph built before the root commits.

| Corpus | Realized size | What happened |
|---|---|---|
| Moby Dick text | 1,442,595 characters in 12 spine files, each about 145,000 characters | One book drove the JVM to about 4 GB and a full GC. `data.mdb` stayed 80 KB until commit, because the graph is live until the root is CAS'd. |
| Moby Dick plus Treasure Island text | 1.4M + 445,073 characters | Second book OOM (`Java heap space`) while the first book was still live. |
| Treasure Island illustrated EPUB | 47.1 MB, mostly images | OOM in `blob-with-store`. Each image byte would be its own `u8` scalar. The 257 KB Moby cover is the same shape, smaller. |
| Three openings, zip blob omitted, images over 40 KB dropped | 145,329 + 191,965 + 190,378 characters (about 528,000) and a few small CSS files | Committed. Database 4.6 MB, about 9 bytes per character. |
| One Treasure Island chapter (`nth` of a spine string, about 13 KB of HTML) | 538 HTTP requests, 76 KB received | Shelf of the three books was 16 requests / 9.4 KB. The chapter, not the shelf, is the expensive read. |

Images and other binaries are worse than text. A byte is smaller than the
hash that points at it, and binary bytes are not a tiny alphabet, so they
do not intern down to a few hundred scalars. A 47 MB EPUB is tens of
millions of leaves.

## Need

Sequence leaves must be dense. A leaf should hold a run of characters or
bytes, on the order of 1,024 elements, not one. The finger tree stays the
sequence backbone. Only the leaf width changes.

That is a durable value-model change. `docs/design/leaf-chunking.md` already
packs many small nodes onto the wire and then rebuilds the same one-element
leaves on materialize. Raising the pack budget does not make ingest of a
book cheap.

## What density changes

For 1.4 million characters at 1,024 per leaf: about 1,400 leaves and a
tree a couple of levels deep, instead of 1.4 million hash slots. A 13 KB
chapter is about a dozen leaf reads instead of hundreds of requests.
Construction allocates thousands of objects, not millions.

`nth` still walks by the leaf measure, which has to be the run length, not
the implicit count of 1 used for a bare scalar. The last step reads the
whole leaf. One character costs about 1 KB (ASCII) to 4 KB (non-ASCII)
instead of a few bytes. A reader that wants the chapter is ahead. A caller
that wants one character over-fetches by the leaf width.

Sharing gets coarser. An edit, or a slice that misses a boundary, copies
the edge leaves and shares the interior. Today that edit copies a
logarithmic spine and reuses the existing character scalar.

The string or blob hash must not depend on leaf width. A leaf's
`elements-fuse` has to be the fuse of its elements, the same fuse 1,024
single-element leaves would have produced, and parents must fuse those
results unchanged. Otherwise every existing sequence hash moves and the
chunk size becomes part of the format.

## Leaf density questions
1. How do we pick a the best static number for leave density?
2. What should be the minimum density?
3. Can we implement variable density leaves? Discuss this one with Jonathan.

## Decision

`ft/digit` and `ft/node` are the same kind of page: a **literal** of the
direct children, filled to **1,024 payload bytes**. There is no separate
pointer-page type. A 32-byte `ref` is one payload a run can hold, used when
a child’s own literal does not fit (a large value, or an `ft/*` spine
child). Occupancy is whatever fits. 32 is how many hashes fit; ~1,024 is
how many bytes fit.

A page body is the pack sequence-literal algebra (`run` / `repeat` /
nested value lits) plus `ref`. Mixed pages are like HTML: text runs with
inlined tags and hrefs. Dual-read still accepts legacy `{:children [h…]}`
as a ref run.

Overflow splits on **2/3 and 1/3 of the page payload**, on item and
in-run character boundaries. String and blob constructors pack 1k runs in
one write per page (`ft-from-run`) and assemble `Deep(first, spine, last)`
so overflow never mixes a char run with a ref to another page.

Collection **value hashes are unchanged**: `elements_fuse` is still the
fuse of the logical child hashes. A digit of one ref to another digit
would collide under type+elements_fuse hashing; `as-digit!` reuses a
digit as-is instead of wrapping.

Deferred after this ships: whether digit and node still need distinct
types, now that they hold the same page shape.
