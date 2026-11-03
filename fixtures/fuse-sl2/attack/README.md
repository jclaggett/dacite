# The Borel-subgroup collision attack on product-of-generators hashes in SL(2, F_p)

Found and measured by Grok Bot in the review of PR #15 (nothing here uses the shipped prime or table).

A hash that multiplies random generators of SL(2, F_p) along a byte string has collisions that cost about
**60 * sqrt(p)** group multiplications, not p:

1. Positive words in the upper-triangular (Borel) subgroup B cost ~sqrt(p): for positive words u, v, `v*u` is in B exactly
   when `u.oo = v^-1.oo`, both points of the projective line (p + 1 points), so a birthday match between two lists of
   ~sqrt(p) words finds one.
2. B is metabelian: two arrangements of the same multiset of such words have the same diagonal and differ only in the
   corner y, a value in F_p. A birthday match over ~sqrt(p) arrangements gives two different positive words (so two
   different byte strings) with the same product.

| file | what |
|---|---|
| `borel_attack.py BITS [TRIALS]` | work to a collision, in multiplications over sqrt(p), for a random prime of that size |
| `borel_collision.py BITS SEED [OUT.edn]` | an explicit collision of two different byte strings, re-verified with a fresh product loop |
| `borel_scaling.out` | the measurements (2^20 to 2^40): the ratio is flat, so the cost scales as sqrt(p) |
| `../small-prime-collision.edn` | the 2^36 collision (p, the 256 generators, two byte strings); replayed by `hash_test` in milliseconds |

The shipped p is 256 bits: 60 * sqrt(p) is about 2^134 multiplications. That is a margin of the attack the review found,
not a proof that no cheaper attack exists (docs/design/fuse-sl2.md).
