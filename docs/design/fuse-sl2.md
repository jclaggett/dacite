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

    p = 75509794529310452520362503714625764980405239500720831432010267419866053829067
      = 0xa6f107770d78545e22069b32e798d267aa12b38be8c28a84036bd6b4e0ed61cb
                                                  (256 bits, about 0.65 * 2^256, no special form)

    p - 1 = 2 * 3 * q1
    q1 = 12584965754885075420060417285770960830067539916786805238668377903311008971511   (253-bit prime)

    p + 1 = 2^2 * q2
    q2 = 18877448632327613130090625928656441245101309875180207858002566854966513457267   (254-bit prime)

|SL(2, F_p)| = p (p^2 - 1), about 2^766. Every element is conjugate to a unipotent
element (order p or 2p), or lies in a split torus (order divides p - 1) or a
non-split torus (order divides p + 1). With a 253-bit prime in p - 1 and a
254-bit prime in p + 1, an element outside the small parts of those tori has
order at least 2^252.

The prime is found by `fixtures/fuse-sl2/find_prime.py`, which is deterministic:
start at SHA-256("dacite/fuse-sl2/prime/v2") with the top bit and the low bit set,
scan upward by 2, and take the first p that is prime with p - 1 = s1 * q1 and
p + 1 = s2 * q2, where q1 and q2 are prime and s1, s2 are numbers below 256
(here s1 = 6 and s2 = 4). Nothing is hidden in it: anyone can re-run the
search.

### Verifying the prime and the factorizations

Any of these works with no dependencies beyond Python 3 or OpenSSL:

    openssl prime 75509794529310452520362503714625764980405239500720831432010267419866053829067
    openssl prime 12584965754885075420060417285770960830067539916786805238668377903311008971511
    openssl prime 18877448632327613130090625928656441245101309875180207858002566854966513457267
    python3 -c "p=75509794529310452520362503714625764980405239500720831432010267419866053829067; q1=12584965754885075420060417285770960830067539916786805238668377903311008971511; q2=18877448632327613130090625928656441245101309875180207858002566854966513457267; assert p-1==6*q1 and p+1==4*q2"

`python3 fixtures/fuse-sl2/reference.py` asserts the primality of all three
(Miller-Rabin of its own) and the two factorizations, then re-derives the
generator table and checks every fixture. The JVM test
`the-prime-and-its-factorizations` (`hash_test.clj`) checks the same with
`BigInteger.isProbablePrime`. The primes were also checked with OpenSSL and
sympy while choosing them.

## Why SL(2), and not something nilpotent or solvable

- **Not nilpotent.** The commutator of two generic elements is not central, so
  there is no identity like #14's. (The tests look for short relations among the
  generators and find none; that is evidence, not a proof.) The group has
  solvable subgroups, though, and that is where the attack below lives.
- **A^n returns to the identity only at multiples of the order of A.** Every
  generator in the table has order at least 2^252 (below), so a run of one
  element (all-equal blocks, a vector of one repeated item, a long run of one
  byte) cannot wrap in practice: the minimum real cycle is ~2^252, not 2^64
  (the old fuse) or 2^32 (the old low-entropy guard).
- **Cheap enough.** One fuse is eight multiplications and four reductions of
  256-bit numbers (cost below).
- The old fuse's guard (reject a state whose low 32 bits are zero) is gone. It
  guarded a cycle that no longer exists; there is nothing for it to check.

## Reduction

Cells are reduced with the host's `mod` (`BigInteger.mod` on the JVM, `BigInt`
`%` and a sign fix on nbb/CLJS). p has no special form, so there is no cheap
pseudo-Mersenne reduction. A Montgomery reduction (R = 2^264, p' = -p^-1 mod R;
R = 2^256 would not do: p > 2^255, so a product could need two final
subtractions) is written as a reference in `hash_test`
(`montgomery-reference-agrees-with-the-kernel`) and agrees with the kernel. It was
not benchmarked at 256 bits; the kernel keeps `mod`. The kernel is four host calls
(`sl2-mul` in `dacite.host`); a port may use Montgomery or Barrett as long as it
returns the same canonical cells in 0..p-1.

## The byte generators

A byte string hashes as the product of one matrix per byte: 256 generators,
shipped as data in `dacite.sl2-table` (256 hex chars each: a, b, c, d as 32
big-endian bytes). They are derived from the old SHA-256 byte table seeds (label
`dacite/fuse-sl2/v2`) by `dev/dacite/dev/gen_sl2_table.clj` and, independently,
by `fixtures/fuse-sl2/reference.py`, which must (and does) produce the same
table. For byte i and counter k = 0, 1, ...:

    block_j = SHA-256("dacite/fuse-sl2/v2" ++ k_be4 ++ old_table[i] ++ j_u8)    j = 0, 1, 2
    a, b, c = block_0, block_1, block_2, each read as a 256-bit big-endian integer
    reject if a >= p, b >= p, c >= p or a = 0
    d = (1 + b c) / a  mod p                                  (so ad - bc = 1)

(`old_table[i]` is the 32-byte entry i of the version-1 byte table, only a
stable per-byte seed.) A candidate is kept only if its order is large. With
t = a + d:

- t^2 - 4 = 0 (unipotent): rejected.
- t^2 - 4 a nonzero square (split torus; order divides p - 1 = 6 * q1): kept
  only if g^6 != 1, which forces q1 (253 bits) to divide the order.
- otherwise (non-split; order divides p + 1 = 4 * q2): kept only if g^4 != 1,
  which forces q2 (254 bits) to divide the order.

So every generator has order at least 2^252, found by two exact powerings, with
no discrete-log work. The 256 are pairwise distinct and pairwise non-commuting
(checked when the table is built, and again in `the-byte-generator-table`).

## A ref is a 32-byte digest; a measure keeps the full state

The store, wire, HAMT and `hex` are all 32-byte refs, and nothing about them
changes shape. A state is 128 bytes (four 32-byte big-endian cells). So:

- `state->ref` (the digest): read each cell as a 256-bit integer, rotate cell
  a by 0, b by 64, c by 128 and d by 192 bits (left, whole 64-bit words), and xor
  the four. Word k of the digest is then the xor of word k of a, word k + 1 of b,
  word k + 2 of c and word k + 3 of d (indices mod 4), so every digest word takes
  exactly one word from each cell, and at a different position each time. A cell
  is below p ~ 0.65 * 2^256, so only the top word of a cell is skewed (its top
  bits are less often one), and each digest word has exactly one top word among
  its four. The identity state digests to words `[1 0 0 1]`. The digest is a
  fold of a fixed algebraic object, not a random oracle; `digest-bits-are-uniform`
  checks that each of the 256 bits is set 44% to 56% of the time over 3,000
  states, which is a sanity check and not a proof of uniformity. HAMT navigation
  reads 5-bit chunks of the digest.
  The digest is xor-linear and not injective (32 bytes of a 128-byte state),
  which is fine for a ref and would not be for a state: **the 32-byte digest is
  never fed back in as a state.** A ref that becomes an element of a fuse goes
  through `ref->state` below, which builds a different state from its bytes.
- `ref->state` (a ref as an element of a sequence): the product of the byte
  generators of the ref's 32 big-endian bytes (31 products). This is the only
  way an element enters a fuse, so a node is checked from the same bytes the
  store keys it by.
- A measure keeps the full 128-byte state as `elements-fuse` (it is a sum that
  other nodes combine, and a digest would lose that). On the wire a measure is
  `u64 count ++ u64 size_bytes ++ 128 B state` = 144 bytes (it was 48 under the
  old fuse and 80 in the first draft of this change). A receiver validates the
  state: each cell below p and determinant 1, or it refuses with an ex-info
  whose `:reason` is `:bad-state` (`hash/bad-state!`, message "bad fuse state").
  The peer item reader (`store/reveal.clj`) turns that into `bad-entry` with
  `:detail :bad-state`. A writer that is handed an invalid state throws
  `:reason :invalid-state` instead: that is a program error, not bad input.

Value hashes: `value_hash = digest(fuse(type_state, data_state))`. A HAMT bitmap
node hashes as
`digest(fuse(fuse(type_state, elements_fuse), fuse_bytes(bitmap_be8 ++ [split])))`.

## The kernel boundary (for a WASM or native implementation)

All fuse arithmetic sits behind five pure functions, `dacite.hash/kernel`. They
take and return **hex strings**, so no host bignum or state representation
crosses the boundary, and a module that exports them can replace the arithmetic
without touching anything else.

| function | input | output |
|---|---|---|
| `:state-mul` | two states (256 hex) | their product (256 hex), every cell in 0..p-1 |
| `:bytes->state` | bytes (hex, any length, may be empty) | the product of their generators (256 hex); empty is the identity |
| `:ref->state` | a ref (64 hex) | `:bytes->state` of its 32 bytes (256 hex) |
| `:state->ref` | a state (256 hex) | its digest (64 hex) |
| `:state-valid?` | any string | true iff it is 256 lowercase hex of four cells below p with determinant 1 |

A state is a, b, c, d as 32-byte big-endian cells; hex is lowercase. The first
four refuse input that is not a state (the JVM and nbb throw `:bad-state`; a
wrapper may throw anything, the vectors only check that it throws). The prime,
the 256-entry generator table and the digest are fixed by this document, not
chosen by the implementation: a module that needs the table ships it (it is the
hex data in `dacite.sl2-table`, and `fixtures/fuse-sl2/reference.py` re-derives it).

Nothing outside `dacite.hash` and `dacite.host` reads a cell: the value types,
HAMT, finger tree, wire and store call `fuse`, `fuse-bytes`, `ref->state`,
`state->ref` and `state-valid?` of `dacite.hash`, which are the in-memory forms of
the same five functions. Swapping the kernel means swapping `dacite.host/sl2-*`
(`sl2-mul`, `sl2-valid?`, `sl2->bytes`, `bytes->sl2`, `sl2-digest-words`).

**The vectors test exactly this boundary.** `fixtures/fuse-sl2/vectors.edn`
`:kernel` has 101 known answers: products of generators, of random-looking states,
of states with cells at p - 1, of inverses (to the identity); byte strings of
0 to 1,000 bytes, including all 256 byte values; refs; digests; valid and invalid
states (a cell equal to p or above it, determinant 2, all zero, all `f`, short,
not hex); and inputs that must be refused. `dacite.kernel-vectors/problems` runs
them against any map of the five functions:

- the JVM test `the-kernel-boundary-agrees-with-the-known-answers` and nbb
  (`npm run peer:test`) run it against `hash/kernel`;
- a WASM wrapper runs `(kernel-vectors/problems wasm-kernel vectors)` with
  `wasm-kernel` a map of the same five keys;
- `python3 fixtures/fuse-sl2/reference.py` recomputes every one of them in pure
  Python, so the answers do not come from the code under test.

The test also shows the checker fails on a wrong kernel. The higher-level vectors
in the same file (`:fuse-str`, `:scalars`, the table sample) test the layers above
the kernel and stay as they were.

## The collision attack, and what it says about the margin

The fuse is still a homomorphic hash, not a cryptographic one. Dacite does not
claim collision resistance against an adversary who picks values (see the
peer-v1 section on the fuse).

**The attack that sets the margin.** A byte string hashes as a product of
generators in SL(2, F_p). The upper-triangular subgroup B (the Borel subgroup)
is metabelian and acts on the projective line. For positive words u and v,
`v * u` is in B exactly when `u.oo = v^-1.oo`, two points of a set of p + 1, so
a birthday match between two lists of about sqrt(p) words finds one. Inside B,
two arrangements of the same multiset of such words have the same diagonal and
differ only in the corner entry, a value in F_p, so a second birthday match over
about sqrt(p) arrangements gives two different words with the same product, and
so two different byte strings with the same state. The measured cost is about
**60 * sqrt(p)** group multiplications (random primes, random generators):

| p | multiplications / sqrt(p) |
|---|---|
| 2^20 | 75 (median of 7) |
| 2^24 | 62 (median of 7) |
| 2^28 | 55 (median of 7) |
| 2^32 | 53 (median of 7) |
| 2^36 | 92 (one run) |
| 2^40 | 43 (one run) |

The 2^20 to 2^32 rows are Grok Bot's measurements in the review of PR #15; the
2^36 and 2^40 rows were added here (one run each). The ratio is flat, so the cost
scales as sqrt(p). Code, the raw numbers and a README are in
`fixtures/fuse-sl2/attack/`.

**What this corrects.** An earlier version of this document (the 127-bit
prime) estimated the cost of the best known attack at about 1.8 p, from a
search for words in the unipotent subgroup measured on primes of 20 to 34 bits.
That estimate was wrong: the attack above is a square root cheaper, and it
shows up at once for 127 bits (about 60 * sqrt(p) is about 2^69.5, so about
2^70), far from the 2^128 claimed. That is why the prime is now 256 bits.

**At 256 bits.** 60 * sqrt(p) is about 2^134 multiplications for this attack,
and an attack on the 32-byte ref (the digest) is bounded by the birthday bound
2^128 in any case. So the margin is about 2^128. That is the margin of one known
attack, **not a proof of security**: a cleverer attack could cost less, and a
different group structure (the tori, the unipotent elements, the 2 and 3 in
p - 1) could give one. Dacite requires an outside cryptographic review before
trusting hashes from untrusted peers (docs/roadmap.md, "Review gate").

**A collision you can run.** `fixtures/fuse-sl2/small-prime-collision.edn` holds
the attack's output for a 36-bit prime (p = 39231795719, 256 random generators,
two different byte strings of 360 bytes each with the same product). The
test `small-prime-collision` (`hash_test.clj`) replays it in a few milliseconds,
so the weakness of a small prime is a test, not a claim. Run
`python3 fixtures/fuse-sl2/attack/borel_collision.py 36 1` to find it again (about
18 million multiplications).

## Versions

- `dacite.hash/hash-version` = 2 (was 1). Documents which fuse the hashes come
  from.
- `dacite.store/store-format` = 2 (was 1). Every value hash changes, so a store
  written under 1 would silently hold the wrong addresses. A store's tag is
  written at creation and checked at open; a mismatch is refused with
  `:store-format-mismatch`.
- The wire chunk `version` and the peer `hello` versions stay 1. The format
  changed (144-byte measures; refs of the same shape), but the wire is a draft
  and no Dacite values exist in the wild, as with the telescoping HAMT (#12/#13).
  A future release that has users must bump them.

## Tests

`hash_test.clj` covers: the prime and factorizations; the table (distinct,
non-commuting, order bound); the Montgomery reference; the two #14 collisions
(and through values and refs); the 36-bit Borel collision
(`small-prime-collision`); every string of length 0 to 5 over abcde (and 0 to 6
over abcd) distinct and the digits 0000 to 9999 distinct; associativity,
identity and inverse (properties, 100 cases each); long runs (A^(2^k) for k to
40 by repeated squaring, none equal to the identity or to a shorter run, and
A^n = I only at a multiple of the order); the digest; the validation of states;
the kernel boundary; and the cross-platform vectors in `fixtures/fuse-sl2/vectors.edn` (including p),
which the nbb test (`npm run peer:test`) checks too, so the JVM and nbb agree to
the byte. The `:bad-state` error is tested at the wire decoder
(`wire/binary_test.clj`), in a serialized measure (`value/serial_test.clj`), in
the peer item reader (`service/reveal_test.clj`) and on nbb (`peer_cljs_test`).

## Cost

Measured on this box (one core). A fuse step (a matrix product) is about 1.7 us
on the JVM (0.6 million per second) and about 1.9 us on nbb. Hashing a ref into
a sequence costs 31 products; `ref->state` is memoized in a bounded LRU
(`dacite.cache`, 65,536 entries, safe across threads on the JVM; issue #17), so a
workload with more live refs than that evicts the least recently used ref, one
per miss. It used to be emptied wholesale when full, which dropped every hot ref
at once.

What matters is the end-to-end cost, not the step. Each row builds the value
from nothing and takes its hash (median of 7 runs after 3 warm-ups, from
`impl/clojure/dev/bench_ops.cljc`):

| operation | JVM main | JVM 127-bit (7371cda) | JVM 256-bit | nbb main | nbb 256-bit |
|---|---|---|---|---|---|
| map, 3,000 i64 entries | 186 ms | 432 ms | 595 ms | 6.2 s | 6.5 s |
| vector, 10,000 i64 | 199 ms | 482 ms | 598 ms | 4.3 s | 4.6 s |
| string, 100,000 chars | 53 ms | 199 ms | 180 ms | 0.91 s | 0.56 s |
| blob, 500 KB | 69 ms | 781 ms | 687 ms | 3.5 s | 1.3 s |
| 500 assocs on a 1,000-entry map | 77 ms | 188 ms | 260 ms | 3.5 s | 3.4 s |

On the JVM the 256-bit fuse is about 3x slower than main for collections and
strings and about 10x slower for a blob (a blob pays one matrix product per
byte, and a byte was nearly free before). On nbb, where interpreter overhead
dominates, it is about the same as main for collections, and faster for strings
and blobs than main's 64-bit wraparound on BigInt. The first draft of this change called the fuse "as
fast"; that was wrong, and an earlier "6x slower per step" figure was a
micro-measurement that said nothing about whole operations. Making byte runs
cheaper (a table of products of byte pairs, or a wider leaf) is future work.

A blob costs 1.01 products per byte at 500 KB (1.10 at 40 KB). It cost 2.03
before the review of PR #15 found that `ft-from-run` measured every page's
bytes twice, once when it built the page as a digit and once when it re-wrapped
the digit as an `ft/node` (`as-node!`; `as-digit!` did the same the other way).
Both now pass the measure the page already holds, which is the same value, so no
hash changes (`a-blob-costs-about-one-product-per-byte` counts the products).

### The `ref->state` cache, and memoizing the fuse of a node's children (issue #17)

**The cache.** `ref->state` (31 products) is memoized per ref. The old cache
emptied itself at 65,536 entries; it is now a bounded LRU (`dacite.cache`:
a `LinkedHashMap` in access order behind a lock on the JVM, a `Map` on nbb).
Hashes cannot change, since a hit returns what a miss would compute. The
`ref->state` row below reads 20,000 hot refs between the refs of a 100,000-ref
cold stream; the wholesale-clear cache loses the hot set at every flush, the LRU
keeps it, and what is left is the cost of the 100,000 cold refs themselves.

| row (impl/clojure/dev/bench_ops.cljc) | JVM before | JVM after | nbb before | nbb after |
|---|---|---|---|---|
| `ref->state`: 20,000 hot refs + 100,000-ref cold stream | 6.9 s | 4.8 s | 13.1 s | 10.8 s |
| map, 3,000 i64 entries | 634 ms | 613 ms | not run | not run |
| vector, 10,000 i64 | 652 ms | 667 ms | not run | not run |
| string, 100,000 chars | 176 ms | 181 ms | not run | not run |
| blob, 500 KB | 675 ms | 741 ms | not run | not run |
| 500 assocs on a 1,000-entry map | 259 ms | 279 ms | not run | not run |
| shared: 20 maps, 1 assoc each on one 2,000-entry map | 378 ms | 407 ms | 4.11 s | 4.20 s |
| shared: one 1,500-entry map built 5 times | 1434 ms | 1479 ms | not run | not run |
| shared: 400 assocs on a 1,000-entry map, hashing each | 254 ms | 263 ms | 2.66 s | 2.64 s |
| shared: 300 conj on a 3,000-element vector, hashing each | 197 ms | 208 ms | not run | not run |

Median of 7 after 3 warm-ups, one core, on a box with another worker running, so
differences under about 8% are noise: the rows that fit in the cache moved by
that much in both directions on repeat runs (500 assocs: 259 / 293 ms before,
279 / 302 ms after; blob: 675 / 735 before, 741 / 725 after). A hit in the LRU
(a lock and a list move) costs about what a hit in the old map did.

**Memoizing the fuse of a node's children: tried, not kept.** About 40% of the
`fuse` calls in a build repeat an earlier pair of states (80,037 of 195,537 for
the 3,000-entry map, 33,913 of 88,813 for the 10,000-element vector, 32,283 of
78,125 for 400 assocs, almost all of them (73,438 of 80,037 on the map) not involving the identity). So the experiment was
the pairwise memo: `measure-combine` in `value/hamt.cljc` and
`value/finger_tree.cljc` called a `fuse` memoized in a bounded LRU keyed on the
two states. A second variant keyed `make-bitmap!` on the list of its children's
refs: that hit 0% to 0.2% of the time on a build or on assocs (0 of 8,121 nodes
building the 3,000-entry map, 2 of 1,054 over 400 assocs), because a node whose children are all unchanged is
never rebuilt, since it is shared; it hit 80% only when the same map is built
again in the same store, so it was dropped without timing.

Pairwise memo, JVM (median ms, 7 runs; limit in entries; the memo is
60 MB of heap at 65,536 entries):

| row | no memo | 4,096 | 65,536 |
|---|---|---|---|
| map, 3,000 i64 entries | 582 | 590 | 608 |
| 500 assocs on a 1,000-entry map | 273 | 242 | 195 |
| shared: 20 maps, 1 assoc each | 367 | 346 | 271 |
| shared: one 1,500-entry map built 5 times | 1445 | 1336 | 1017 |
| shared: 400 assocs, hashing each | 251 | 229 | 195 |
| shared: 300 conj on a 3,000-element vector | 200 | 200 | 182 |

nbb (a JS `Map` keys on strings, so the key is the eight cells as decimal text),
median ms:

| row | no memo | 4,096 | 65,536 |
|---|---|---|---|
| 500 assocs on a 1,000-entry map | 2826 | 3406 | 3404 |
| shared: 20 maps, 1 assoc each | 4044 | 4825 | 4692 |

**Decision: not kept.** It helps the JVM by 9% to 30% on workloads that rebuild
along a path or rebuild shared structure, and not at all on a bulk build (582 to
608 ms), but it needs about 60 MB of heap for the 65,536 entries that give the
gain (the 4,096-entry cache, 4 MB, gains 0% to 11%, about the noise). On nbb it
is 20% slower, because turning two states into a key costs more than the 1.9 us
product it saves, so a portable version would pay for it on one host to win on
the other. A cache that is a win only on the JVM, only for incremental updates,
at 60 MB, is not worth the portable code. Revisit it with a WASM or native fuse
kernel (roadmap): the product gets cheaper and a key gets no cheaper, so the
case only gets weaker; what would change it is a profile of a real workload, with
a hit rate above what these rows show, on a store that is memory-bound or on
the JVM only.