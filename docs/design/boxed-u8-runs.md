# Boxed u8 runs

**Status:** implemented. A u8 run's `:values` is a copied `byte[]` (JVM) or
`Uint8Array` (ClojureScript). Measure fuses the 256 precomputed `u8`
scalar hashes in place. `nth` boxes the one byte it returns. Collection
hashes stay `fuse(type, elements_fuse)`.

**Related:** `docs/design/dense-sequence-leaves.md` (1k literal pages, shipped
on `de7d334`), `impl/clojure/src/dacite/value/finger_tree.cljc`
(`u8-run-item`, `body-measure`, `ft-from-run`),
`impl/clojure/src/dacite/value/lit.cljc` (`chunk-chars`, `expand-rle-seq`).

## What broke

1k literal pages made full novels fit. They did not make image bytes cheap.

`dacite-library` at Dacite `de7d3342992cf154fa5ebf048950c57ad0dad2bc` ingested
three full Gutenberg EPUBs into one LMDB (`target/try-library`, `data.mdb`
12 MB):

| Book | Time | Peak heap | Payload |
|---|---:|---:|---|
| Moby Dick | 38 s | 424 MB | 1,442,595 HTML chars, 793 KB zip |
| Treasure Island, text edition | 44 s | 468 MB | 445,073 chars, 267 KB zip |
| Swiss Family Robinson | 94 s | 1,176 MB | 759,294 chars, 1.1 MB of resources, 1.5 MB zip |

The 47.1 MB illustrated Treasure Island (157 JPEGs, 49.2 MB of image bytes,
38 spine documents) did not finish as a book.

| Step | Time | Heap |
|---|---:|---|
| Read the zip | 105 ms | 194 MB peak |
| Parse spine and resources | 396 ms | 202 MB peak |
| Store the zip as one blob (49,430,322 bytes) | 98 s | 1,798 MB peak, 1,288 MB still live |
| `catalog-unpacked` of the spine plus 159 resource blobs | not committed | 3 GB cap, still running after about 11 minutes. Uncapped, the same step swapped a 16 GB machine for about 17 minutes |

The zip blob itself succeeded. Unpacking the images on top of that live blob
did not.

## Cause

A character page keeps a Java substring (`chunk-chars` / `subs`). A byte
page does not keep bytes.

`u8-run-item` copies each byte with `Byte/toUnsignedInt` into a vector of
boxed integers, then stores that vector as the run:

```clojure
{:type "run" :body {:of "u8" :values vs}}  ;; vs is vector<Integer>
```

`ft-from-run` builds every page of the blob before writing the tree, and
each page body stays in the mem overlay. Root commit flushes pack items to
LMDB and does not drop the overlay, so the vectors live for the process.

`body-measure` makes it worse on the way in. `expand-rle-seq` turns the run
into one `{:type "u8"}` map per byte, and `item-measure` calls
`scalar-value-hash` on each. `as-node!` rewraps every middle digit and
measures that run again.

A boxed integer is about 16 bytes, plus the reference array. 49 million
bytes is about 1.3 GB live, which matches the heap left after the zip blob.
There are only 256 distinct `u8` values, but the page retains every
occurrence, not the interned scalar.

Illustrated ingest pays that twice. `ingest-bytes` stores the original zip
as one blob, then `catalog-unpacked` stores each extracted JPEG as its own
blob, all inside one `v/swap!`. The second copy is allocated while the first
is still live. Text books stay smaller because their large values are
strings; their zip blobs are under 1.5 MB.

## Need

A u8 run payload should be a copied `byte[]` on the JVM and a `Uint8Array`
in ClojureScript. Measure should scan that array in place and fuse the same
per-byte `u8` scalar hashes a one-element leaf would have produced. Those
256 hashes can be computed once. `nth` boxes the single byte it returns.
The array must be a copy of its range so a later write to the caller's
buffer cannot change a stored page.

Collection hashes stay `fuse(type, elements_fuse)`. The illustrated book
would then retain on the order of 100 MB for the zip plus the extracted
images, instead of two gigabytes of integers. The per-byte fuse remains;
it stops allocating a map and an integer per byte. The app still stores
the zip and the resources.
