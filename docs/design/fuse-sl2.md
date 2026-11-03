# The fuse over SL(2, F_p)

Status: implemented (hash version 2, `dacite.store/store-format` 2). Closes
[issue #14](https://github.com/jclaggett/dacite/issues/14).

## Why the old fuse had to go

The old fuse was a 4x4 unitriangular matrix over Z/2^64 (six cells, four used).
Its states form a nilpotent group, so two-letter commutators are central and
collisions come in families. Issue #14 names one: for any elements a, b and any
x, y, z, the sequences `x a b y b a z` and `x b a y a b z` fuse to one state.
The group is too regular to have no such identity; a fix inside it would only
move the family.

## The group

The state is a 2x2 matrix over F_p with determinant 1: the group SL(2, F_p).
Fuse is matrix multiplication mod p.

    p = 87078471178590763933909596057503143987
      = 0x4182b4199657dfb501064dc53ce61833          (127 bits, not Mersenne)

    p - 1 = 2 * 3 * 14513078529765127322318266009583857331      (124-bit prime)
    p + 1 = 2^2 * 19 * 1145769357613036367551442053388199263    (120-bit prime)

|SL(2, F_p)| = p (p^2 - 1) ~ 2^381. Every element is conjugate to a unipotent
element (order p or 2p), or lies in a split torus (order divides p - 1) or a
non-split torus (order divides p + 1). Choosing p with a 124-bit prime in p - 1
and a 120-bit prime in p + 1 means that an element outside the small parts of
those tori has order at least 2^119.

### Verifying the prime and the factorizations

Any of these works with no dependencies beyond Python 3 or OpenSSL:

    openssl prime 87078471178590763933909596057503143987
    openssl prime 14513078529765127322318266009583857331
    openssl prime 1145769357613036367551442053388199263
    python3 -c "p=87078471178590763933909596057503143987; q1=14513078529765127322318266009583857331; q2=1145769357613036367551442053388199263; assert p-1==2*3*q1 and p+1==4*19*q2"

`python3 fixtures/fuse-sl2/reference.py` asserts the two factorizations, then
re-derives the generator table and checks every fixture. The JVM test
`the-prime-and-its-factorizations` (`hash_test.clj`) checks the same with
`BigInteger.isProbablePrime`. The research that picked this prime (two other
127-bit candidates, and the 21- to 41-bit stand-ins used to measure the attack)
is in `fuse-experiments/scaling/` (`primes.txt`, `verify_primes.py`).

## Why SL(2), and not something nilpotent or solvable

- **Not nilpotent.** The commutator of two generic elements is not central, so
  there is no identity like #14's. (The tests look for short relations among the
  generators and find none; that is evidence, not a proof.)
- **A^n returns to the identity only at multiples of the order of A.** Every
  generator in the table has order at least 2^119 (below), so a run of one
  element (all-equal blocks, a vector of one repeated item, a long run of one
  byte) cannot wrap in practice: the minimum real cycle is ~2^120, not 2^64
  (the old fuse) or 2^32 (the old low-entropy guard).
- **Cheap enough.** One fuse is eight multiplications and four reductions of
  127-bit numbers.
- The old fuse's guard (reject a state whose low 32 bits are zero) is gone. It
  guarded a cycle that no longer exists; there is nothing for it to check.

## Reduction

Cells are reduced with the host's `mod` (`BigInteger.mod` on the JVM, `BigInt`
`%` and a sign fix on nbb/CLJS). p has no special form, so there is no cheap
pseudo-Mersenne reduction. A Montgomery reduction (R = 2^128, p' = -p^-1 mod R)
is written as a reference in `hash_test` (`montgomery-reference-agrees-with-the-kernel`)
and agrees with the kernel, and it was measured: on the JVM it matches `mod`
(about 1.0 to 1.2 million products per second on this box), and under SCI (nbb)
`mod` is cheaper, since Montgomery needs more interpreted steps. The kernel is
four host calls (`sl2-mul` in `dacite.host`); a port may use Montgomery or
Barrett as long as it returns the same canonical cells in 0..p-1.

## The byte generators

A byte string hashes as the product of one matrix per byte: 256 generators,
shipped as data in `dacite.sl2-table` (128 hex chars each: a, b, c, d as 16
big-endian bytes). They are derived from the old SHA-256 byte table seeds (label
`dacite/fuse-sl2/v1`) by `dev/dacite/dev/gen_sl2_table.clj` and, independently,
by `fixtures/fuse-sl2/reference.py`, which must (and does) produce the same
table. For byte i and counter k = 0, 1, ...:

    stream = SHA-256("dacite/fuse-sl2/v1" ++ k_be4 ++ old_table[i] ++ j)    j = 0, 1
    a, b, c = 127-bit cells from the stream; require a != 0 and a, b, c < p
    d = (1 + b c) / a  mod p                                  (so ad - bc = 1)

A candidate is kept only if its order is large. With t = a + d:

- t^2 - 4 = 0 (unipotent): rejected.
- t^2 - 4 a nonzero square (split torus; order divides p - 1 = 2 * 3 * q1): kept
  only if g^6 != 1, which forces q1 (124 bits) to divide the order.
- otherwise (non-split; order divides p + 1 = 4 * 19 * q2): kept only if g^76 != 1,
  which forces q2 (120 bits) to divide the order.

So every generator has order at least 2^119, found by two exact powerings, with
no discrete-log work. The 256 are pairwise distinct and pairwise non-commuting
(checked when the table is built, and again in `the-byte-generator-table`).

## A ref is a 32-byte digest; a measure keeps the full state

The store, wire, HAMT and `hex` are all 32-byte refs, and nothing about them
changes shape. A state is 64 bytes. So:

- `state->ref` (the digest): pack the cells as N = a + b 2^127 + c 2^254 + d 2^381
  and take `(N mod 2^256) xor (N >> 252)`. The top bit of each 127-bit cell is
  almost always zero, and no two of those positions are 252 apart, so every
  digest bit depends on at least one uniform input bit. HAMT navigation reads
  5-bit chunks of the digest. The identity state digests to words `[0 2 0 1]`.
- `ref->state` (a ref as an element of a sequence): the product of the byte
  generators of the ref's 32 big-endian bytes (31 products). This is the only
  way an element enters a fuse, so a node is checked from the same bytes the
  store keys it by.
- A measure keeps the full 64-byte state as `elements-fuse` (it is a sum that
  other nodes combine, and a digest would lose that). On the wire a measure is
  `u64 count ++ u64 size_bytes ++ 64 B state` = 80 bytes (it was 48). A
  receiver validates the state: each cell below p and determinant 1, or
  `bad-state` (`bad-entry` in the peer protocol).

Value hashes: `value_hash = digest(fuse(type_state, data_state))`. A HAMT bitmap
node hashes as
`digest(fuse(fuse(type_state, elements_fuse), fuse_bytes(bitmap_be8 ++ [split])))`.

## The scaling-attack result (and what it does not say)

The fuse is still a homomorphic hash, not a cryptographic one. The one known
attack on SL(2, F_p) hashes (the subgroup attack: find positive words whose
product lies in the unipotent subgroup, an abelian group of order p) was
measured on primes of 20 to 34 bits, with 256 random generators, words of 12
letters, and the memoryless claw search. The work to a first hit was ~1.8 p
(fitted exponent 1.00 +- 0.03 against the alternative 1.5), measured at 2^20
through 2^34; the 2^32 and 2^34 rows are only 2 and 1 trials, so the fit rests
on 2^20 to 2^30. Extrapolated to p ~ 2^127 that is ~2^127.8 word evaluations,
and about 2^130 for the variant that finds many hits. No primes above 2^34 were
measured, and 2^127 has not been run. This is **one attack**; a cleverer one
could cost less, and this is not a security proof. Dacite does not claim collision
resistance against an adversary who picks values (see the peer-v1 section on the
fuse); the attack is a reference point for the margin, not a promise.

## Versions

- `dacite.hash/hash-version` = 2 (was 1). Documents which fuse the hashes come
  from.
- `dacite.store/store-format` = 2 (was 1). Every value hash changes, so a store
  written under 1 would silently hold the wrong addresses. A store's tag is
  written at creation and checked at open; a mismatch is refused with
  `:store-format-mismatch`.
- The wire chunk `version` and the peer `hello` versions stay 1. The format
  changed (80-byte measures; refs of the same shape), but the wire is a draft
  and no Dacite values exist in the wild, as with the telescoping HAMT (#12/#13).
  A future release that has users must bump them.

## Tests

`hash_test.clj` covers: the prime and factorizations; the table (distinct,
non-commuting, order bound); the Montgomery reference; the two #14 collisions
(and through values and refs); every string of length 0 to 5 over abcde (and 0 to 6 over abcd) distinct and the
digits 0000 to 9999 distinct; associativity, identity and inverse (properties, 100
cases each); long runs
(A^(2^k) for k to 40 by repeated squaring, none equal to the identity or to a
shorter run, and A^n = I only at a multiple of the order); the digest; the
validation of states; and the cross-platform vectors in
`fixtures/fuse-sl2/vectors.edn`, which the nbb test (`npm run peer:test`) checks
too, so the JVM and nbb agree to the byte.

## Cost

Measured on this box (one core), a fuse step (a matrix product) runs at about
0.7 to 1.0 million per second on the JVM (the old fuse: 2 to 7 million), and
0.6 to 0.7 million on nbb (the old fuse: 0.23 million, since 64-bit wrapping
arithmetic is slow on BigInt). Hashing a ref into a sequence costs 31 products;
`ref->state` is memoized (bounded, emptied at 65,536 entries) because a tree
re-reads the same child refs while it re-measures pages.
