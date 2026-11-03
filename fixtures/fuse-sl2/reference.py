#!/usr/bin/env python3
"""Independent reference of the SL(2, F_p) fuse (docs/design/fuse-sl2.md), pure Python 3, no dependencies.

Re-derives the 256-generator table from the SHA-256 byte table (the 64-hex seeds in
impl/clojure/src/dacite/byte_table.cljc) exactly as impl/clojure/dev/dacite/dev/gen_sl2_table.clj does, then checks
every vector in vectors.edn (fuse-str states and refs, ref->state, table sample) and the shipped table.

    python3 fixtures/fuse-sl2/reference.py        # from the repo root; exits 1 on any mismatch
"""
import hashlib, re, sys, pathlib

root = pathlib.Path(__file__).resolve().parents[2]
P = 87078471178590763933909596057503143987
Q1 = 14513078529765127322318266009583857331
Q2 = 1145769357613036367551442053388199263
assert P - 1 == 6 * Q1 and P + 1 == 76 * Q2

def mul(x, y):
    a, b, c, d = x; e, f, g, h = y
    return ((a*e + b*g) % P, (a*f + b*h) % P, (c*e + d*g) % P, (c*f + d*h) % P)

I = (1, 0, 0, 1)

def mpow(m, n):
    r = I
    while n:
        if n & 1: r = mul(r, m)
        m = mul(m, m); n >>= 1
    return r

def large_order(g):
    t = (g[0] + g[3]) % P
    disc = (t*t - 4) % P
    if disc == 0: return False
    if pow(disc, (P - 1) // 2, P) == 1: return mpow(g, 6) != I
    return mpow(g, 76) != I

def derive(seed_hex):
    seed = bytes.fromhex(seed_hex)
    k = 0
    mask = (1 << 127) - 1
    while True:
        blocks = [hashlib.sha256(b"dacite/fuse-sl2/v1" + k.to_bytes(4, "big") + seed + bytes([j])).digest() for j in (0, 1)]
        a = int.from_bytes(blocks[0][:16], "big") & mask
        b = int.from_bytes(blocks[0][16:], "big") & mask
        c = int.from_bytes(blocks[1][:16], "big") & mask
        k += 1
        if a >= P or b >= P or c >= P or a == 0: continue
        d = (1 + b*c) * pow(a, -1, P) % P
        g = (a, b, c, d)
        if g != I and large_order(g): return g

def hexstate(s): return "".join("%032x" % v for v in s)
def unhex(h): return tuple(int(h[i:i+32], 16) for i in range(0, 128, 32))

def digest(s):
    n = sum(v << (127 * i) for i, v in enumerate(s))
    dg = (n ^ (n >> 252)) & ((1 << 256) - 1)
    return "%064x" % dg

src = (root / "impl/clojure/src/dacite/byte_table.cljc").read_text()
seeds = re.findall(r'"([0-9a-f]{64})"', src)
assert len(seeds) == 256
table = [derive(s) for s in seeds]
shipped = re.findall(r'"([0-9a-f]{128})"', (root / "impl/clojure/src/dacite/sl2_table.cljc").read_text())
ok = [hexstate(g) for g in table] == shipped
print("table re-derived and equal to the shipped one:", ok)

def fuse_bytes(bs):
    r = I
    for b in bs: r = mul(r, table[b])
    return r

def ref_state(ref_hex): return fuse_bytes(bytes.fromhex(ref_hex))

vec = (root / "fixtures/fuse-sl2/vectors.edn").read_text()
def field(name):  # crude EDN reader for our own flat maps
    return re.findall(r':%s\s+"([^"]*)"' % name, vec)
bad = 0
for m in re.finditer(r'\{:input\s+"([^"]*)",\s+:state\s+"([0-9a-f]+)",\s+:ref\s+"([0-9a-f]+)"\}', vec):
    s, st, rf = m.groups()
    got = fuse_bytes(s.encode("utf-8"))
    if hexstate(got) != st or digest(got) != rf:
        bad += 1; print("MISMATCH fuse-str", repr(s))
for m in re.finditer(r'\{:ref\s+"([0-9a-f]{64})",\s+:state\s+"([0-9a-f]{128})"\}', vec):
    r, st = m.groups()
    if hexstate(ref_state(r)) != st:
        bad += 1; print("MISMATCH ref->state", r)
for m in re.finditer(r'\[(\d+)\s+"([0-9a-f]{128})"\]', vec):
    i, hx = int(m.group(1)), m.group(2)
    if hexstate(table[i]) != hx:
        bad += 1; print("MISMATCH table", i)
print("vectors mismatching:", bad)
sys.exit(0 if ok and bad == 0 else 1)
