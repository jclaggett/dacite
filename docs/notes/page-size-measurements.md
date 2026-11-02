# Leaf page size measurements (M1.1a item 5)

**Decision (Jonathan, Nov 2 2026): 4 KiB** (`lit/page-budget` 4096, a string or blob counted as its length + 5). Reasons and consequences: `docs/design/leaf-chunking.md` 2g.

Bench: `impl/clojure/dev/page_size_bench.clj` (one JVM per budget: `BUDGETS=4096 clojure -M:dev -i dev/page_size_bench.clj`) and `impl/clojure/dev/page_size_degenerate.clj` (the shapes where payload and wire size differ most). Numbers below are from the final code (the +5 accounting, the incremental page append), measured Nov 2 2026 on the box.

**The value hash is the same at every budget** (7 of 7 shapes, at all four budgets, in each run), **and the same as on `main`** before this work (8 shapes compared; a test pins seven).

Per shape and `lit/page-budget`: nodes and bytes of a full reveal (`reveal/page-order`), the largest node item, the peer item stream (`items/encode-value`: items, bytes, largest item), nodes and bytes a one-element edit adds to a store that holds the old value, build time and encode time (ms).

| shape | budget | nodes | node bytes | max node item | stream items | stream bytes | max stream item | edit adds (nodes / bytes) | build ms | encode ms |
|---|---|---|---|---|---|---|---|---|---|---|
| string 100 KB | 1024 | 108 | 113 915 | 1 122 | 108 | 109 361 | 1 076 | 7 / 3 453 | 105 | 2 498 |
|  | 4096 | 29 | 103 681 | 4 194 | 29 | 102 531 | 4 148 | 5 / 6 791 | 134 | 2 827 |
|  | 8192 | 17 | 102 121 | 8 290 | 17 | 101 523 | 8 244 | 5 / 11 868 | 98 | 1 927 |
|  | 16384 | 11 | 101 341 | 16 482 | 11 | 101 249 | 16 482 | 5 / 22 599 | 88 | 1 231 |
| blob 300 KB | 1024 | 312 | 339 229 | 1 118 | 287 | 323 653 | 14 384 | 7 / 3 033 | 228 | 25 264 |
|  | 4096 | 78 | 309 751 | 4 190 | 78 | 306 347 | 4 144 | 5 / 8 351 | 170 | 139 |
|  | 8192 | 41 | 305 089 | 8 286 | 41 | 303 387 | 8 240 | 5 / 12 628 | 307 | 119 |
|  | 16384 | 23 | 302 821 | 16 478 | 23 | 302 729 | 16 478 | 5 / 22 975 | 258 | 257 947 |
| vector 4k ints | 1024 | 55 | 38 836 | 830 | 8 | 32 690 | 15 872 | 5 / 2 067 | 86 | 172 |
|  | 4096 | 17 | 34 054 | 3 124 | 17 | 33 456 | 3 124 | 5 / 6 409 | 94 | 179 |
|  | 8192 | 11 | 33 298 | 5 660 | 11 | 32 976 | 5 660 | 5 / 11 673 | 112 | 258 |
|  | 16384 | 8 | 32 914 | 11 022 | 8 | 32 684 | 10 976 | 4 / 11 477 | 82 | 114 |
| vector 8k i64 | 1024 | 103 | 76 884 | 894 | 59 | 68 902 | 15 184 | 7 / 3 071 | 165 | 433 |
|  | 4096 | 28 | 67 440 | 3 894 | 28 | 66 336 | 3 848 | 5 / 6 761 | 179 | 358 |
|  | 8192 | 17 | 66 054 | 5 852 | 17 | 65 456 | 5 852 | 5 / 11 865 | 232 | 331 |
|  | 16384 | 11 | 65 298 | 11 124 | 11 | 64 976 | 11 124 | 5 / 22 601 | 161 | 302 |
| vector 300 x 1KB str | 1024 | 318 | 339 679 | 1 118 | 318 | 325 879 | 1 118 | 7 / 2 976 | 638 | 770 |
|  | 4096 | 228 | 327 213 | 3 177 | 80 | 307 696 | 5 068 | 7 / 7 215 | 685 | 529 |
|  | 8192 | 192 | 322 601 | 8 228 | 46 | 304 948 | 11 092 | 5 / 13 925 | 683 | 560 |
|  | 16384 | 194 | 322 355 | 16 292 | 109 | 309 694 | 16 292 | 5 / 23 849 | 712 | 581 |
| vector 2k x 12B str | 1024 | 58 | 39 208 | 894 | 8 | 32 690 | 16 048 | 30 / 20 413 | 161 | 256 |
|  | 4096 | 17 | 34 054 | 3 918 | 17 | 33 456 | 3 872 | 11 / 23 241 | 286 | 221 |
|  | 8192 | 11 | 33 298 | 6 398 | 11 | 32 976 | 6 352 | 8 / 27 871 | 412 | 209 |
|  | 16384 | 8 | 32 914 | 11 534 | 8 | 32 684 | 11 488 | 5 / 22 351 | 550 | 161 |
| map 1k int->int | 1024 | 3 185 | 344 739 | 1 116 | 1 328 | 128 603 | 1 116 | 6 / 2 226 | 76 | 306 |
|  | 4096 | 3 185 | 344 739 | 1 116 | 1 328 | 128 603 | 1 116 | 6 / 2 226 | 226 | 649 |
|  | 8192 | 3 185 | 344 739 | 1 116 | 1 328 | 128 603 | 1 116 | 6 / 2 226 | 307 | 598 |
|  | 16384 | 3 185 | 344 739 | 1 116 | 1 328 | 128 603 | 1 116 | 6 / 2 226 | 136 | 405 |

Reading it:

- **1 KiB to 4 KiB:** a 100 KB string goes from 108 to 29 nodes (3.7 times fewer) and 9% fewer bytes; a 300 KB blob 312 to 78 and 9% fewer; 4 000 ints 55 to 17; 8 000 i64 103 to 28. A one-element edit adds about twice the bytes (3 453 to 6 791 for the string). Build time is about the same.
- **Largest node item at 4 KiB:** 4 194 bytes (string), under the 8192 minimum frame and one payload. At 16 KiB it is 16 482, over one payload (16 378): not allowed. At 8 KiB (8 290) the minimum frame would have to be 16 KiB.
- **Max stream item** at 1 KiB is 14 to 16 KB for blobs, 4k ints and 2k short strings: the literal rule packs many 1 KiB pages into one literal. At 4 KiB it is at most 5 068.
- **Encode time.** A 100 KB string takes about 2.5 s to encode at 1 KiB and 2.8 s at 4 KiB, whatever the budget: the sender builds the root's literal (100 KB) to learn it is over the cap. A cheaper pre-filter is not safe, because a literal of repeated values is much shorter than its payload (`literal-gate`). Open. The 300 KB blob took 25 s at 1 KiB and 258 s at 16 KiB, against 0.14 s at 4 KiB: the first is the old per-element rebuild of 1 KiB pages and the second the same at 16 KiB; at 4 KiB the incremental append is what makes it quick (before it, the 8k i64 vector built in 3.9 s; now 0.18 s).

Degenerate shapes (`page_size_degenerate.clj`; nodes and largest node item in bytes; 2 000 or 4 000 elements):

| shape | 1024 | 2048 | 4096 | 8192 |
|---|---|---|---|---|
| 1-char strings x2000 | 21 / 664 | 14 / 1 432 | 9 / 3 264 | 5 / 5 539 |
| 4-char strings x2000 | 31 / 886 | 18 / 1 636 | 12 / 2 660 | 8 / 6 374 |
| 200-byte blobs x400 | 388 / 1 076 | 299 / 1 522 | 173 / 3 628 | 102 / 8 248 |
| bools x4000 | 9 / 814 | 8 / 1 459 | 5 / 4 093 | 5 / 4 093 |
| empty strings x2000 | 9 / 184 | 9 / 184 | 9 / 184 | 5 / 184 |
| nulls x4000 | 9 / 184 | 7 / 184 | 5 / 184 | 5 / 184 |

At 4 096 the largest node item over all of these is 4 093 bytes. With the old accounting (a string or blob in a run counted 0) a page of these could be unbounded; with it (+5 per string and blob, 1 per null) every page stays within its budget on the wire, even 1-character strings (6 payload bytes, 6 wire bytes each).
