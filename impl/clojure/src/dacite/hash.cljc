(ns dacite.hash
  "Hashing primitives for Dacite (portable core).

   Two kinds of value live here:

   - A **state** is a fuse accumulator: a 2x2 matrix over F_p with determinant
     1 (the group SL(2, F_p), p a fixed 127-bit prime), held as `[a b c d]` of
     host bignums. It is 64 bytes on the wire. Fusing is matrix multiplication.
     Measures (`:elements-fuse`) keep full states.
   - A **ref** (a value hash, the key of a node in a store) is a 32-byte
     *digest* of a state, `[w0 w1 w2 w3]` as four 64-bit words (native longs
     on JVM/babashka, BigInt on ClojureScript/nbb). Refs are what stores, the
     wire and the peer protocol carry in 32 bytes.

   A ref goes back into a fuse through `ref->state`: the product of the byte
   generators of its 32 bytes. This is the only way an element enters a
   sequence, so a ref and the node behind it are checked from the same bytes.

   Arithmetic over F_p is in dacite.host (the porting contract); this
   namespace is pure. See docs/design/fuse-sl2.md for the group, the prime and
   the generator table, and docs/spec/peer-v1.md section 3 for the rules.

   Core operations:
   - byte->state: precomputed table mapping each byte (0-255) to a generator
     in SL(2, F_p), shipped as data in dacite.sl2-table (no runtime crypto)
   - fuse: the group operation; fuse-bytes / fuse-str: hash any byte sequence
   - state->ref: the 32-byte digest; ref->state: expand a ref into a state"
  (:require [dacite.host :as host]
            [dacite.sl2-table :as sl2-table]))

(def hash-version
  "Which fuse the hashes come from. 1: the 4x4 unitriangular matrix over
   Z/2^64 (a 256-bit hash was the state; nilpotent, issue #14). 2: SL(2, F_p)
   with a 32-byte digest for refs and a 64-byte state in measures. Persisted
   stores record it through `dacite.store/store-format`; the wire and hello
   versions are not bumped (draft, no values in the wild)."
  2)

;; =============================================================================
;; Byte / long / hex conversion of refs (delegated to host)
;; =============================================================================

(def bytes->longs host/bytes->longs)
(def longs->bytes host/longs->bytes)

(defn hash->int
  "Fold a ref to a 32-bit int (for Java hashCode / Clojure hasheq)."
  [h]
  (host/->int32 (host/bxor64 (host/bxor64 (nth h 0) (nth h 1))
                             (host/bxor64 (nth h 2) (nth h 3)))))

(def hash->hex host/longs->hex)
(def hex->hash host/hex->longs)

;; =============================================================================
;; States: bytes and hex
;; =============================================================================

(def identity-state
  "The empty fuse: the identity matrix."
  host/sl2-identity)

(def state-valid?
  "True when x is a canonical state (four cells in 0..p-1, determinant 1)."
  host/sl2-valid?)

(def state->bytes
  "A state to 64 bytes (vector of ints 0..255): a, b, c, d, 16 big-endian bytes each."
  host/sl2->bytes)

(def bytes->state
  "64 bytes to a state vector. Not validated; check with `state-valid?`."
  host/bytes->sl2)

(defn state->hex
  "A state to a 128-char lowercase hex string."
  [s]
  (apply str (map host/byte->hex (state->bytes s))))

(defn hex->state
  "A 128-char hex string to a state vector (not validated)."
  [hex]
  (bytes->state (mapv (fn [pair] #?(:clj (Integer/parseInt (apply str pair) 16)
                                    :cljs (js/parseInt (apply str pair) 16)))
                      (partition 2 hex))))

;; =============================================================================
;; Fuse (the group operation)
;; =============================================================================

(def fuse
  "Fuse two states: the matrix product in SL(2, F_p). Associative and
   non-commutative, with `identity-state` as identity.

   There is no low-entropy guard: states are group elements, and a repeated
   value A^n returns to the identity only when n is a multiple of A's order,
   which is above 2^119 for every generator and (all but 2^-120 of) all
   products of them."
  host/sl2-mul)

(def fuse-inverse
  "The inverse of a state: fuse(inv(a), a) = fuse(a, inv(a)) = identity.
   The adjugate [d -b -c a], computed without a multiplication."
  host/sl2-inverse)

(defn unfuse
  "Remove b's contribution from a fused state: unfuse(fuse(a, b), b) = a."
  [a b]
  (fuse a (fuse-inverse b)))

;; =============================================================================
;; Fuse sequences
;; =============================================================================

(defn fuse-seq
  "Fuse a sequence of states left-to-right. Returns the identity for empty."
  [states]
  (reduce fuse identity-state states))

;; =============================================================================
;; Byte generator table & fuse-bytes / fuse-str
;; =============================================================================

(def byte->state
  "Generator for each byte value (0-255), indexed by unsigned byte. Parsed
   from the shipped hex table (docs/design/fuse-sl2.md)."
  (mapv hex->state sl2-table/hex-table))

(defn fuse-bytes
  "Fuse the generators of each byte. `bs` is anything reducible over
   byte-sized integers (a vector of ints 0..255, or a host byte array).
   Values are masked to 0..255 so signed host bytes work transparently."
  [bs]
  (reduce (fn [acc b] (host/sl2-mul acc (nth byte->state (bit-and 0xFF b))))
          identity-state
          bs))

(defn fuse-str
  "Fuse the generators of each UTF-8 byte in string s."
  [s]
  (fuse-bytes (host/utf8-bytes s)))

;; =============================================================================
;; Refs: digest of a state, and a ref as an element of a fuse
;; =============================================================================

(def state->ref
  "The 32-byte digest of a state, as a ref: four 64-bit words.

   Pack the four 127-bit cells into N = a + b 2^127 + c 2^254 + d 2^381 and
   fold: (N mod 2^256) xor (N >> 252). A pure function of the state, and the
   only way a value hash is made (docs/design/fuse-sl2.md)."
  host/sl2-digest-words)

(def ^:private ref-state-cache-limit
  "Entries kept before the ref->state cache is dropped and refilled."
  65536)

(def ^:private ref-state-cache (atom {}))

(defn ref->state
  "A ref as an element of a fuse: the product of the generators of its 32
   big-endian bytes (31 matrix products).

   Memoized on the four words: a tree re-reads the same child refs while it
   re-measures pages and re-fuses along a path, and 31 products dominate the
   cost of a fuse step. The cache is a pure function of the ref, so it can
   never change a hash; it is emptied whenever it passes the limit, which
   bounds its memory without any eviction order."
  [ref]
  (let [k #?(:clj (vec ref)
             ;; a JS BigInt word cannot be hashed by `hash`; key on its text
             :cljs (str (nth ref 0) "," (nth ref 1) "," (nth ref 2) "," (nth ref 3)))]
    (or (@ref-state-cache k)
        (let [s (fuse-bytes (longs->bytes ref))]
          (swap! ref-state-cache
                 (fn [m] (if (>= (count m) ref-state-cache-limit) {k s} (assoc m k s))))
          s))))

(defn fuse-refs
  "Fuse a sequence of refs left-to-right (each through `ref->state`)."
  [refs]
  (reduce (fn [acc r] (fuse acc (ref->state r))) identity-state refs))
