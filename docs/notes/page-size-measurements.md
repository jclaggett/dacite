# Leaf page size measurements (M1.1a item 5), work in progress

Bench: `impl/clojure/dev/page_size_bench.clj` (one JVM per budget: `BUDGETS=4096 clojure -M:dev -i dev/page_size_bench.clj`).
Degenerate shapes: `/tmp/degen.clj` (to be committed as a bench option). The value hash is the same at every budget (28 of 28 shape/budget rows).

Per shape and `lit/page-budget`: nodes and bytes of a full reveal (`reveal/page-order`), the largest node item, the peer item stream (`items/encode-value`), bytes added by a one-element edit, build and encode time.

| shape | budget | nodes | node bytes | max node item | stream items | stream bytes | edit adds (nodes / bytes) | build ms |
|---|---|---|---|---|---|---|---|---|
| string 100 KB | 1024 | 108 | 113 915 | 1 122 | 108 | 109 361 | 7 / 3 453 | 208 |
| | 4096 | 29 | 103 681 | 4 194 | 29 | 102 531 | 5 / 6 791 | 256 |
| | 8192 | 17 | 102 121 | 8 290 | 17 | 101 523 | 5 / 11 868 | 253 |
| | 16384 | 11 | 101 341 | **16 482** | 11 | 101 249 | 5 / 22 599 | 232 |
| blob 300 KB | 1024 | 312 | 339 229 | 1 118 | 287 | 323 653 | 7 / 3 033 | 338 |
| | 4096 | 78 | 309 751 | 4 190 | 78 | 306 347 | 5 / 8 351 | 739 |
| | 8192 | 41 | 305 089 | 8 286 | 41 | 303 387 | 5 / 12 628 | 1 387 |
| | 16384 | 23 | 302 821 | **16 478** | 23 | 302 729 | 5 / 22 975 | 2 477 |
| vector 4k ints | 1024 | 55 | 38 836 | 830 | 8 | 32 690 | 5 / 2 067 | 593 |
| | 4096 | 17 | 34 054 | 3 124 | 17 | 33 456 | 5 / 6 409 | 1 824 |
| | 8192 | 11 | 33 298 | 5 660 | 11 | 32 976 | 5 / 11 673 | 3 437 |
| | 16384 | 8 | 32 914 | 11 022 | 8 | 32 684 | 4 / 11 477 | 5 973 |
| vector 8k i64 | 1024 | 103 | 76 884 | 894 | 59 | 68 902 | 7 / 3 071 | 1 092 |
| | 4096 | 28 | 67 440 | 3 894 | 28 | 66 336 | 5 / 6 761 | 3 931 |
| | 8192 | 17 | 66 054 | 5 852 | 17 | 65 456 | 5 / 11 865 | 7 497 |
| | 16384 | 11 | 65 298 | 11 124 | 11 | 64 976 | 5 / 22 601 | 13 166 |
| vector 300 x 1 KB strings | 1024 | 318 | 339 679 | 1 118 | 318 | 325 879 | 7 / 2 976 | 1 002 |
| | 4096 | 270 | 331 502 | 3 209 | 72 | 307 056 | 7 / 6 991 | 1 272 |
| | 8192 | 236 | 327 185 | 8 228 | 44 | 304 768 | 5 / 13 861 | 1 713 |
| | 16384 | 236 | 326 777 | 16 516 | 129 | 311 016 | 5 / 23 977 | 2 815 |
| vector 2k x 12 B strings | 1024 | 43 | 37 319 | 1 070 | 32 | 34 691 | 6 / 2 424 | 790 |
| | 4096 | 14 | 33 676 | 3 940 | 14 | 33 216 | 5 / 8 133 | 2 546 |
| | 8192 | 9 | 33 041 | 10 238 | 8 | 32 684 | 6 / 25 470 | 5 031 |
| | 16384 | 5 | 32 548 | **17 518** | 5 | 32 456 | 3 / 17 785 | 8 441 |
| map 1k int->int | any | 3 185 | 344 739 | 1 116 | 1 328 | 128 603 | 6 / 2 226 | ~100 (HAMT, not paged by this budget) |

Worst-case wire/payload under payload sizing: a string costs its length + 5 bytes on the wire (tag and u32 length), so 1-character strings cost 6x their payload. The per-node cap is one frame payload (16 378 B).
