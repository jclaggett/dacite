#!/usr/bin/env python3
"""Explicit, checked collision of an SL(2,F_p) byte-string hash with 256 random generators, found in ~sqrt(p) work via the Borel subgroup.
Prints two DIFFERENT byte strings with the same product, then re-verifies them with a fresh product loop.
Same construction as borel_attack.py (see its docstring); here the words are tracked as byte strings.
Found by Grok Bot in the review of PR #15 (about 1.8e7 group multiplications at p ~ 2^36, against ~7e10 for the
subgroup search that the first design note relied on).
With a third argument, writes the prime, the 256 generators and the two strings to that file (EDN); the repo keeps
the result in ../small-prime-collision.edn and the JVM test (hash_test `small-prime-collision`) replays it in
milliseconds, with no search.
usage: borel_collision.py [BITS=36] [SEED=1] [OUT.edn]"""
import random, sys
bits = int(sys.argv[1]) if len(sys.argv) > 1 else 36; seed = int(sys.argv[2]) if len(sys.argv) > 2 else 1
rnd = random.Random(seed)
def isp(m):
    if m < 4: return m in (2, 3)
    d, s = m-1, 0
    while d % 2 == 0: d //= 2; s += 1
    for a in (2,3,5,7,11,13,17,19,23,29,31,37):
        if m % a == 0: return m == a
        x = pow(a, d, m)
        if x in (1, m-1): continue
        for _ in range(s-1):
            x = x*x % m
            if x == m-1: break
        else: return False
    return True
P = rnd.getrandbits(bits) | (1 << (bits-1))
while not isp(P): P += 1
def mul(x, y):
    a, b, c, d = x; e, f, g, h = y
    return ((a*e+b*g) % P, (a*f+b*h) % P, (c*e+d*g) % P, (c*f+d*h) % P)
def rsl2():
    while True:
        a, b, c = rnd.randrange(P), rnd.randrange(P), rnd.randrange(P)
        if a: return (a, b, c, (1 + b*c) * pow(a, -1, P) % P)
G = [rsl2() for _ in range(256)]
def prod(bs):
    m = (1, 0, 0, 1)
    for b in bs: m = mul(m, G[b])
    return m
mults = 0
# phase 1: positive words (byte strings) in the Borel subgroup (upper triangular)
tab, found, L = {}, [], 6
while len(found) < 3:
    for _ in range(4096):
        u = bytes(rnd.randrange(256) for _ in range(L)); v = bytes(rnd.randrange(256) for _ in range(L))
        mu, mv = prod(u), prod(v); mults += 2*L
        if mu[2]: tab.setdefault(mu[0] * pow(mu[2], -1, P) % P, []).append(u)
        if mv[2]:
            for u2 in tab.get(mv[3] * pow(-mv[2] % P, -1, P) % P, ()):
                w = v + u2
                assert prod(w)[2] == 0           # upper triangular: lies in the Borel subgroup
                found.append(w)
bs = found[:3]; M = [prod(w) for w in bs]
# phase 2: arrangements of 10 copies of each b_i (same diagonal); match on the product
seen = {}
base = [i for i in range(3) for _ in range(10)]
while True:
    arr = base[:]; rnd.shuffle(arr)
    m = (1, 0, 0, 1)
    for i in arr: m = mul(m, M[i]); mults += 1
    if m in seen and seen[m] != arr:
        a1, a2 = seen[m], arr; break
    seen[m] = arr
s1 = b"".join(bs[i] for i in a1); s2 = b"".join(bs[i] for i in a2)
assert s1 != s2 and prod(s1) == prod(s2)
print("p = %d (%d bits), 256 random generators" % (P, bits))
print("two different byte strings of length %d and %d with the same product:" % (len(s1), len(s2)))
print("  s1 =", s1.hex()[:80], "...")
print("  s2 =", s2.hex()[:80], "...")
print("  product (both) =", prod(s1))
if len(sys.argv) > 3:
    with open(sys.argv[3], "w") as f:
        f.write(";; A collision of two different byte strings under a product-of-generators hash in SL(2, F_p), p ~ 2^%d.\n" % bits)
        f.write(";; Found by fixtures/fuse-sl2/attack/borel_collision.py %d %d (about %d group multiplications = %.0f * sqrt(p)).\n" % (bits, seed, mults + 2*len(s1), (mults + 2*len(s1)) / P**0.5))
        f.write("{:p %d\n :generators [%s]\n :s1 \"%s\"\n :s2 \"%s\"}\n" % (P, "\n  ".join("[%d %d %d %d]" % g for g in G), s1.hex(), s2.hex()))
print("work: ~%d group multiplications = %.1f * sqrt(p)   (the documented subgroup search costs ~1.8*p = %.2e)" % (mults + 2*len(s1), (mults + 2*len(s1)) / P**0.5, 1.8*P))
