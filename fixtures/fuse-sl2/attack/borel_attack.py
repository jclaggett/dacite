#!/usr/bin/env python3
"""Collision search against a product-of-random-generators hash in SL(2, F_p) with cost ~ sqrt(p), by way of the Borel subgroup.
(This is a structural attack on the *design* (SL(2,F_p), random generators, positive words); it does not use the PR's table
or p, only the group.)  Two phases:
  1. Find positive words that are UPPER TRIANGULAR (lie in the Borel subgroup B = stabiliser of the point oo of P^1(F_p)):
     pick random positive words u, v; v*u is in B  iff  u.oo = v^-1.oo.  Both sides are points of P^1 (p+1 of them), so a
     birthday match over two lists of ~sqrt(p) words finds one: cost ~ sqrt(p), not ~p.
  2. B is metabelian. Take k>=2 such words b_i = [[t_i, y_i],[0, 1/t_i]]. Two arrangements of the same multiset of b_i have the
     same diagonal and differ only in the corner y (a value in F_p), so a birthday match over ~sqrt(p) arrangements gives two
     DIFFERENT positive words with the same product = a collision of the hash (for any byte strings that map to those words).
Total ~ c*sqrt(p) group multiplications, versus the ~1.8p of the subgroup search documented in docs/design/fuse-sl2.md.
usage: borel_attack.py BITS [TRIALS]"""
import random, sys, time
def next_prime(n):
    def isp(m):
        if m < 2: return False
        for q in (2,3,5,7,11,13): 
            if m % q == 0: return m == q
        d, s = m-1, 0
        while d % 2 == 0: d //= 2; s += 1
        for a in (2,3,5,7,11,13,17,19,23,29,31,37):
            x = pow(a, d, m)
            if x in (1, m-1): continue
            for _ in range(s-1):
                x = x*x % m
                if x == m-1: break
            else: return False
        return True
    while not isp(n): n += 1
    return n
def run(bits, seed):
    rnd = random.Random(seed); P = next_prime(rnd.getrandbits(bits) | (1 << (bits-1)))
    def mul(x, y):
        a, b, c, d = x; e, f, g, h = y
        return ((a*e+b*g) % P, (a*f+b*h) % P, (c*e+d*g) % P, (c*f+d*h) % P)
    def rnd_sl2():
        while True:
            a, b, c = rnd.randrange(P), rnd.randrange(P), rnd.randrange(P)
            if a: return (a, b, c, (1 + b*c) * pow(a, -1, P) % P)
    G = [rnd_sl2() for _ in range(256)]
    ops = 0
    def rword(L):
        nonlocal ops
        m = G[rnd.randrange(256)]
        for _ in range(L-1): m = mul(m, G[rnd.randrange(256)]); ops += 1
        return m
    # phase 1: find k positive words in B. u*oo = (a:c) projective point a/c ; v^-1*oo = (d:-c_v). match a_u/c_u = d_v/(-c_v)
    L = 6; k = 3; found = []
    tab = {}
    while len(found) < k:
        for _ in range(4096):
            u = rword(L); v = rword(L)
            if u[2] != 0:
                tab.setdefault(u[0] * pow(u[2], -1, P) % P, []).append(u)
            if v[2] != 0:
                key = v[3] * pow(-v[2] % P, -1, P) % P         # point of v^-1 . oo
                for u2 in tab.get(key, ()):
                    w = mul(v, u2); ops += 1
                    assert w[2] == 0
                    found.append(w)
    bs = found[:k]
    # phase 2: arrangements of a fixed multiset of the b_i (same exponent counts => same diagonal); collide on the matrix
    counts = [10]*k; base = [i for i, n in enumerate(counts) for _ in range(n)]
    seen = {}
    while True:
        arr = base[:]; rnd.shuffle(arr)
        m = (1, 0, 0, 1)
        for i in arr: m = mul(m, bs[i]); ops += 1
        key = m
        if key in seen and seen[key] != arr:
            return P, ops
        seen[key] = arr
if __name__ == "__main__":
    bits = int(sys.argv[1]); trials = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    res = []
    for t in range(trials):
        P, ops = run(bits, t)
        res.append(ops / P ** 0.5)
    import statistics
    print("p ~ 2^%d: ops/sqrt(p) over %d trials: median %.1f  (min %.1f max %.1f)" % (bits, trials, statistics.median(res), min(res), max(res)))
