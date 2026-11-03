#!/usr/bin/env python3
"""Find the prime p of the SL(2, F_p) fuse (docs/design/fuse-sl2.md). Deterministic, pure Python 3.

p is a 256-bit prime with no special form. It is the first prime found scanning up from
a base derived from SHA-256("dacite/fuse-sl2/prime/v2"), such that p - 1 = s1 * q1 and p + 1 = s2 * q2 with q1 and q2 prime and the cofactors s1, s2
below 256 (so q1 and q2 have at least 248 bits). The smooth parts are small on purpose: a
generator g is kept only if g^s1 != I and g^s2 != I, which puts q1 or q2 into its order.

    python3 fixtures/fuse-sl2/find_prime.py        # prints p, s1, q1, s2, q2 and their sizes
"""
import hashlib

SMALL = 1 << 12

def small_primes(n):
    sieve = bytearray([1]) * n
    sieve[0:2] = b"\0\0"
    for i in range(2, int(n ** 0.5) + 1):
        if sieve[i]:
            sieve[i * i::i] = bytearray(len(sieve[i * i::i]))
    return [i for i in range(n) if sieve[i]]

PRIMES = small_primes(SMALL)

def is_prime(n):
    """Miller-Rabin with the first 24 prime bases (deterministic below 3.3e24, and an error
    below 2^-48 for the sizes here; the verifier uses 64 rounds and OpenSSL)."""
    if n < 2:
        return False
    for q in PRIMES[:40]:
        if n % q == 0:
            return n == q
    d, s = n - 1, 0
    while d % 2 == 0:
        d //= 2
        s += 1
    for a in PRIMES[:24]:
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(s - 1):
            x = x * x % n
            if x == n - 1:
                break
        else:
            return False
    return True

def strip_small(n):
    """(small part, rest) with every prime factor below 256 moved to the small part."""
    small = 1
    for q in PRIMES:
        if q >= 256:
            break
        while n % q == 0:
            n //= q
            small *= q
    return small, n

def base():
    seed = hashlib.sha256(b"dacite/fuse-sl2/prime/v2").digest()
    return int.from_bytes(seed, "big") | (1 << 255) | 1

def search():
    p = base()
    while True:
        if is_prime(p):
            s1, q1 = strip_small(p - 1)
            s2, q2 = strip_small(p + 1)
            if s1 < 256 and s2 < 256 and is_prime(q1) and is_prime(q2):
                return p, s1, q1, s2, q2
        p += 2

if __name__ == "__main__":
    p, s1, q1, s2, q2 = search()
    print("p  =", p, "(%d bits)" % p.bit_length())
    print("p  = 0x%x" % p)
    print("p-1 = %d * q1, q1 = %d (%d bits)" % (s1, q1, q1.bit_length()))
    print("p+1 = %d * q2, q2 = %d (%d bits)" % (s2, q2, q2.bit_length()))
