#!/usr/bin/env python3
"""Independent reference of the SL(2, F_p) fuse (docs/design/fuse-sl2.md), pure Python 3, no dependencies.

Checks the prime and both factorizations (its own Miller-Rabin, 64 random bases), re-derives the
256-generator table from the SHA-256 byte table (the 64-hex seeds in
impl/clojure/src/dacite/byte_table.cljc) exactly as impl/clojure/dev/dacite/dev/gen_sl2_table.clj does,
checks the order of every generator, then checks every vector in vectors.edn (fuse-str states and refs,
ref->state, table sample), the kernel-boundary vectors (`:kernel`), the text vectors (`:strings`, `:utf8`,
`:invalid-utf8`, `:rejected-strings`: code points, UTF-8, lone surrogates; issue #16) and the shipped table.

    python3 fixtures/fuse-sl2/reference.py        # from the repo root; exits 1 on any mismatch
"""
import hashlib, random, re, sys, pathlib

root = pathlib.Path(__file__).resolve().parents[2]
P = 75509794529310452520362503714625764980405239500720831432010267419866053829067
Q1 = 12584965754885075420060417285770960830067539916786805238668377903311008971511
Q2 = 18877448632327613130090625928656441245101309875180207858002566854966513457267
S1, S2 = 6, 4

def is_probable_prime(n, rounds=64, rnd=random.Random(20260521)):
    if n < 4: return n in (2, 3)
    if n % 2 == 0: return False
    d, s = n - 1, 0
    while d % 2 == 0: d //= 2; s += 1
    for _ in range(rounds):
        a = rnd.randrange(2, n - 1)
        x = pow(a, d, n)
        if x in (1, n - 1): continue
        for _ in range(s - 1):
            x = x * x % n
            if x == n - 1: break
        else:
            return False
    return True

assert P.bit_length() == 256 and Q1.bit_length() == 253 and Q2.bit_length() == 254
assert P - 1 == S1 * Q1 and P + 1 == S2 * Q2
assert is_probable_prime(P) and is_probable_prime(Q1) and is_probable_prime(Q2), "p, q1, q2 must be prime"
print("p (256 bits), q1 = (p-1)/6 (253 bits), q2 = (p+1)/4 (254 bits) are prime")

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
    """True when q1 or q2 (at least 253 bits) divides the order of g."""
    t = (g[0] + g[3]) % P
    disc = (t*t - 4) % P
    if disc == 0: return False
    if pow(disc, (P - 1) // 2, P) == 1: return mpow(g, S1) != I
    return mpow(g, S2) != I

def derive(seed_hex):
    seed = bytes.fromhex(seed_hex)
    k = 0
    while True:
        blocks = [hashlib.sha256(b"dacite/fuse-sl2/v2" + k.to_bytes(4, "big") + seed + bytes([j])).digest() for j in (0, 1, 2)]
        a, b, c = (int.from_bytes(x, "big") for x in blocks)
        k += 1
        if a >= P or b >= P or c >= P or a == 0: continue
        d = (1 + b*c) * pow(a, -1, P) % P
        g = (a, b, c, d)
        if g != I and large_order(g): return g

def hexstate(s): return "".join("%064x" % v for v in s)
def unhex(h): return tuple(int(h[i:i+64], 16) for i in range(0, 256, 64))

def rotl(n, r): return ((n << r) | (n >> (256 - r))) & ((1 << 256) - 1)

def digest(s):
    """a xor rotl64(b) xor rotl128(c) xor rotl192(d), cells as 256-bit integers."""
    dg = 0
    for i, v in enumerate(s): dg ^= rotl(v, 64 * i)
    return "%064x" % dg

src = (root / "impl/clojure/src/dacite/byte_table.cljc").read_text()
seeds = re.findall(r'"([0-9a-f]{64})"', src)
assert len(seeds) == 256
table = [derive(s) for s in seeds]
shipped = re.findall(r'"([0-9a-f]{256})"', (root / "impl/clojure/src/dacite/sl2_table.cljc").read_text())
ok = [hexstate(g) for g in table] == shipped
print("table re-derived and equal to the shipped one:", ok)

def fuse_bytes(bs):
    r = I
    for b in bs: r = mul(r, table[b])
    return r

def ref_state(ref_hex): return fuse_bytes(bytes.fromhex(ref_hex))

vec = (root / "fixtures/fuse-sl2/vectors.edn").read_text(encoding="utf-8")
def field(name):  # crude EDN reader for our own flat maps
    return re.findall(r':%s\s+"([^"]*)"' % name, vec)
bad = 0
for m in re.finditer(r'\{:input\s+"([^"]*)",\s+:state\s+"([0-9a-f]+)",\s+:ref\s+"([0-9a-f]+)"\}', vec):
    s, st, rf = m.groups()
    got = fuse_bytes(s.encode("utf-8"))
    if hexstate(got) != st or digest(got) != rf:
        bad += 1; print("MISMATCH fuse-str", repr(s))
for m in re.finditer(r'\{:ref\s+"([0-9a-f]{64})",\s+:state\s+"([0-9a-f]{256})"\}', vec):
    r, st = m.groups()
    if hexstate(ref_state(r)) != st:
        bad += 1; print("MISMATCH ref->state", r)
for m in re.finditer(r'\[(\d+)\s+"([0-9a-f]{256})"\]', vec):
    i, hx = int(m.group(1)), m.group(2)
    if hexstate(table[i]) != hx:
        bad += 1; print("MISMATCH table", i)
print("vectors mismatching:", bad)

# --- The kernel boundary (dacite.hash/kernel): every :kernel vector, recomputed here ---------------
def read_edn(text):
    """Just enough EDN for vectors.edn: maps, vectors, strings, keywords, integers, true/false/nil."""
    pos = 0
    def ws():
        nonlocal pos
        while pos < len(text) and (text[pos] in " \t\r\n," or text[pos] == ";"):
            if text[pos] == ";":
                while pos < len(text) and text[pos] != "\n": pos += 1
            else: pos += 1
    def val():
        nonlocal pos
        ws(); c = text[pos]
        if c == "{":
            pos += 1; m = {}
            while True:
                ws()
                if text[pos] == "}": pos += 1; return m
                k = val(); m[k] = val()
        if c == "[":
            pos += 1; v = []
            while True:
                ws()
                if text[pos] == "]": pos += 1; return v
                v.append(val())
        if c == '"':
            pos += 1; out = []
            while text[pos] != '"':
                if text[pos] == "\\": pos += 1
                out.append(text[pos]); pos += 1
            pos += 1; return "".join(out)
        j = pos
        while pos < len(text) and text[pos] not in " \t\r\n,]}": pos += 1
        tok = text[j:pos]
        return {"true": True, "false": False, "nil": None}.get(tok, tok) if not tok.lstrip("-").isdigit() else int(tok)
    return val()

K = read_edn(vec)[":kernel"]
def valid_hex(h):
    if len(h) != 256 or re.fullmatch(r"[0-9a-f]+", h) is None: return False
    s = unhex(h)
    return all(v < P for v in s) and (s[0]*s[3] - s[1]*s[2]) % P == 1
kbad = 0
def chk(what, got, want):
    global kbad
    if got != want: kbad += 1; print("KERNEL MISMATCH", what)
for e in K[":state-mul"]:
    assert valid_hex(e[":a"]) and valid_hex(e[":b"])
    chk(":state-mul", hexstate(mul(unhex(e[":a"]), unhex(e[":b"]))), e[":product"])
for e in K[":bytes->state"]:
    chk(":bytes->state", hexstate(fuse_bytes(bytes.fromhex(e[":bytes"]))), e[":state"])
for e in K[":ref->state"]:
    chk(":ref->state", hexstate(ref_state(e[":ref"])), e[":state"])
for e in K[":state->ref"]:
    chk(":state->ref", digest(unhex(e[":state"])), e[":ref"])
for e in K[":state-valid?"]:
    chk(":state-valid? " + e[":state"][:16], valid_hex(e[":state"]), e[":valid"])
for e in K[":refused"]:   # every refused input is not a state (or not a ref / bytes)
    f, args = e[":fn"], e[":args"]
    if f in (":state-mul", ":state->ref"): chk(f + " refused", all(valid_hex(a) for a in args), False)
    elif f == ":ref->state": chk(f + " refused", len(args[0]) == 64, False)
    elif f == ":bytes->state": chk(f + " refused", re.fullmatch(r"([0-9a-f]{2})*", args[0]) is not None, False)
n = sum(len(v) for v in K.values())
print("kernel vectors: %d checked, %d mismatching" % (n, kbad))

# --- Text (issue #16): a string is a sequence of code points; a lone surrogate is refused --------------
# value_hash(type, data_state) = digest(type_state * data_state), type_state = fuse_bytes(name ++ 0x00).
# A char is the value hash of the UTF-8 of one code point; a string's data state is the product, in
# order, of ref->state of each of its characters (the fuse of the elements). Paging does not enter.
def value_ref(type_name, data_state):
    return digest(mul(fuse_bytes(type_name.encode("utf-8") + b"\0"), data_state))

def char_ref(cp):
    return value_ref("char", fuse_bytes(chr(cp).encode("utf-8")))

def string_ref(text):
    st = I
    for c in text: st = mul(st, ref_state(char_ref(ord(c))))
    return value_ref("string", st)

V = read_edn(vec)
tbad = 0
def tchk(what, got, want):
    global tbad
    if got != want: tbad += 1; print("TEXT MISMATCH", what, got, want)
for e in V[":strings"]:
    text = "".join(t * n for t, n in e[":parts"])
    tchk("count " + e[":name"], len(text), e[":count"])   # Python str: one element per code point
    if ":code-points" in e: tchk("code points " + e[":name"], [ord(c) for c in text], e[":code-points"])
    tchk("ref " + e[":name"], string_ref(text), e[":ref"])
for e in V[":scalars"]:
    if e[":type"] == "char":
        assert len(e[":value"]) == 1
        tchk("char " + repr(e[":value"]), char_ref(ord(e[":value"])), e[":ref"])
for e in V[":utf8"]:
    tchk("utf8 encode " + repr(e[":text"]), e[":text"].encode("utf-8").hex(), e[":bytes"])
    tchk("utf8 decode " + e[":bytes"], bytes.fromhex(e[":bytes"]).decode("utf-8"), e[":text"])
for e in V[":invalid-utf8"]:
    try: bytes.fromhex(e[":bytes"]).decode("utf-8"); refused = False
    except UnicodeDecodeError: refused = True
    tchk("invalid utf8 " + e[":bytes"], refused, True)
def has_lone_surrogate(units):
    i = 0
    while i < len(units):
        u = units[i]
        if 0xD800 <= u < 0xDC00 and i + 1 < len(units) and 0xDC00 <= units[i + 1] < 0xE000: i += 2; continue
        if 0xD800 <= u < 0xE000: return True
        i += 1
    return False
for e in V[":rejected-strings"]:
    tchk("rejected " + e[":name"], has_lone_surrogate(e[":units"]), True)
    tchk("reason " + e[":name"], e[":reason"], ":lone-surrogate")
print("text vectors: %d strings, %d chars, %d utf8, %d invalid utf8, %d rejected; %d mismatching" % (
    len(V[":strings"]), sum(1 for e in V[":scalars"] if e[":type"] == "char"), len(V[":utf8"]),
    len(V[":invalid-utf8"]), len(V[":rejected-strings"]), tbad))
sys.exit(0 if ok and bad == 0 and kbad == 0 and tbad == 0 else 1)
