# Chapter 1: Hash Fusion

Everything in Dacite is built on a single operation: **fuse**. It
combines two 256-bit hashes into a new 256-bit hash using nothing more
than integer arithmetic. No SHA-256 at runtime, no hash function calls
in the critical path — just six additions and a multiplication.

This chapter introduces fuse, its algebraic properties, and how it
turns raw bytes into content addresses.

## 1.1 Hashes as Four Words

A Dacite hash is 256 bits, represented as four 64-bit unsigned integers
in big-endian word order:

```
hash = [c0, c1, c2, c3]
```

Word `c0` holds the most mixed bits (from fuse) and is used first for
HAMT navigation. Word `c3` holds the least mixed bits.

## 1.2 The Fuse Operation

Fuse takes two hashes and produces a third:

```
Input:  a = [a0, a1, a2, a3]
        b = [b0, b1, b2, b3]

Output: c = [c0, c1, c2, c3]

c0 = a0 + a3*b2 + b0    ← most bit mixing (single multiply)
c1 = a1 + b1
c2 = a2 + b2
c3 = a3 + b3             ← least bit mixing (simple addition)
```

All arithmetic wraps at 2^64. The total cost: 6 additions, 1
multiplication.

### Properties

These properties are not incidental — the entire system depends on them:

- **Associative** — `fuse(a, fuse(b, c)) = fuse(fuse(a, b), c)`.
  This means tree shape doesn't affect the hash. A balanced tree and
  a left-degenerate tree over the same leaf sequence produce the same
  root hash.
- **Non-commutative** — `fuse(a, b) ≠ fuse(b, a)` (for a ≠ b).
  Order matters. `[x, y]` and `[y, x]` have different hashes.
- **Identity** — `[0, 0, 0, 0]` is a two-sided identity.
  `fuse(a, 0) = fuse(0, a) = a`. Empty sequences hash to the identity.
- **Fast** — no hash function calls, just integer arithmetic. This
  matters when every node in a tree computes a fuse on construction.

### Why Associativity Matters

Associativity is the foundation of structural sharing. Consider a
sequence `[a, b, c, d]`. Its hash is:

```
fuse(fuse(fuse(a, b), c), d)
```

But because fuse is associative, any parenthesization gives the same
result:

```
fuse(fuse(a, b), fuse(c, d))    — balanced tree
fuse(a, fuse(b, fuse(c, d)))    — right-degenerate tree
```

This means two stores can organize the same data differently (different
tree shapes for performance) and still agree on the root hash.

### Why Non-Commutativity Matters

If fuse were commutative, `[a, b]` and `[b, a]` would hash the same.
Sequences would be indistinguishable from sets. Order-sensitive data
structures (vectors, strings) require that `fuse(a, b) ≠ fuse(b, a)`.

## 1.3 Group Structure

Fuse forms a **group** over (ℤ/2^64)^4. Every hash has a unique
inverse:

```
inv([a0, a1, a2, a3]) = [a3*a2 - a0, -a1, -a2, -a3]
```

Such that `fuse(inv(a), a) = fuse(a, inv(a)) = [0, 0, 0, 0]`.

Cost: 1 multiply + 4 negations.

### Unfuse

Given `fused = fuse(a, b)`, if you know `b`, you can recover `a`:

```
unfuse(fused, b) = fuse(fused, inv(b)) = a
```

Strip from the left: `fuse(inv(a), fused) = b`.

### What the Group Enables

- **Cross-type equality** — strip a type tag hash to compare raw content
  (see Chapter 2, Typed Values).
- **Hash recovery** — recover one component of a fused pair when the
  other is known.
- **Incremental re-hashing** — update a fused chain without recomputing
  from scratch. Replace an element by unfusing the old and fusing the new.

## 1.4 The Byte Hash Table

Dacite doesn't hash bytes directly with fuse. Instead, it uses a
precomputed lookup table mapping each byte value (0–255) to a 256-bit
hash:

```
byte_hash: byte → Hash    (256 entries)
```

The default table is seeded using SHA-256:
`byte_hash[i] = sha256(byte_array([i]))`. But any set of 256 distinct,
high-quality 32-byte values works. This decouples Dacite from any
specific hash function at runtime — SHA-256 is used once at build time
to generate the table, never again.

### Hashing Bytes and Strings

All data hashing reduces to table lookups and fuses:

```
fuse_bytes(bs) = reduce(unchecked_fuse, [0,0,0,0], map(byte_hash, bs))
fuse_str(s)    = fuse_bytes(utf8_bytes(s))
```

Because fuse is associative:
`fuse(fuse_str(a), fuse_str(b)) = fuse_str(a ++ b)`

Composing fused results is equivalent to fusing the concatenation.
This is both a feature (tree nodes can combine child hashes) and a
constraint (domain separators are needed between fields — see
Chapter 2).

## 1.5 Protocol ID

The byte hash table is a **build-time constant**, not stored inside the
content-addressed space. The table's own hash serves as a protocol
identifier:

```
protocol_id = fuse_bytes(concat(table[0], table[1], ..., table[255]))
```

The table hashes itself: each row is 32 bytes, concatenated into 8,192
bytes, and `fuse_bytes` (which uses the table) produces the ID.

Two stores are compatible if and only if they share the same protocol
ID. Implementations check this on first contact.

## 1.6 Low-Entropy Rejection

Fuse must reject inputs and outputs where the lower 32 bits are zero
in all four words:

```
low_entropy?(h) =
  (h[0] & 0xFFFFFFFF) == 0 AND
  (h[1] & 0xFFFFFFFF) == 0 AND
  (h[2] & 0xFFFFFFFF) == 0 AND
  (h[3] & 0xFFFFFFFF) == 0
```

The checked fuse:

```
fuse(a, b):
  REJECT if low_entropy?(a)
  REJECT if low_entropy?(b)
  result = unchecked_fuse(a, b)
  REJECT if low_entropy?(result)
  return result
```

An unchecked variant exists for internal use where inputs are known
valid (e.g., combining measures within finger tree nodes).

## 1.7 What This Layer Provides

Hash fusion gives the rest of Dacite three guarantees:

1. **Content identity** — any value, at any scale, reduces to a 256-bit
   hash. Same content → same hash, always.
2. **Tree-shape independence** — associativity means the hash captures
   *what* is stored, not *how* it's organized.
3. **Decomposability** — the group structure means hashes can be taken
   apart, not just composed. This enables typed values, incremental
   updates, and cross-type comparisons.

The next chapter builds data structures on this foundation.
