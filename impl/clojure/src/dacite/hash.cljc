(ns dacite.hash
  "Hashing primitives for Dacite (portable core).

   Two kinds of value live here:

   - A **state** is a fuse accumulator: a 2x2 matrix over F_p with determinant
     1 (the group SL(2, F_p), p a fixed 256-bit prime), held as `[a b c d]` of
     host bignums. It is 128 bytes on the wire. Fusing is matrix multiplication.
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
   - state->ref: the 32-byte digest; ref->state: expand a ref into a state

   The arithmetic is replaceable behind five hex-in, hex-out functions, `kernel`
   at the end of this file (docs/design/fuse-sl2.md, \"The kernel boundary\")."
  (:require [dacite.host :as host]
            [dacite.sl2-table :as sl2-table]))

(def hash-version
  "Which fuse the hashes come from. 1: the 4x4 unitriangular matrix over
   Z/2^64 (a 256-bit hash was the state; nilpotent, issue #14). 2: SL(2, F_p)
   with a 32-byte digest for refs and a 128-byte state in measures. Persisted
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
  "A state to 128 bytes (vector of ints 0..255): a, b, c, d, 32 big-endian bytes each."
  host/sl2->bytes)

(def bytes->state
  "128 bytes to a state vector. Not validated; check with `state-valid?`."
  host/bytes->sl2)

(defn bad-state!
  "Refuse bytes that claim to be a fuse state but are not in SL(2, F_p): a cell
   is not below p, or the determinant is not 1. Throws ex-info with the message
   \"bad fuse state\" and ex-data `{:reason :bad-state ...data}`. A wire decoder
   throws it for a measure it reads; the peer item reader reports it as
   `bad-entry` (`:detail :bad-state`, docs/spec/peer-v1.md section 3.3)."
  [data]
  (throw (ex-info "bad fuse state: a cell is not below p or the determinant is not 1"
                  (assoc data :reason :bad-state))))

(defn invalid-state!
  "The writer's counterpart of `bad-state!`: a program tried to encode a value
   that is not a state in SL(2, F_p). Throws ex-info with
   `:reason :invalid-state` and the offending state in `:state`."
  [data]
  (throw (ex-info "cannot encode a fuse state that is not in SL(2, F_p)"
                  (assoc data :reason :invalid-state))))

(defn state->hex
  "A state to a 256-char lowercase hex string."
  [s]
  (apply str (map host/byte->hex (state->bytes s))))

(defn- hex-bytes!
  "Lowercase-or-uppercase hex of whole bytes to a vector of ints, or `bad-state!`."
  [hex]
  (when-not (and (string? hex) (even? (count hex)) (re-matches #"[0-9a-fA-F]*" hex))
    (bad-state! {:hex hex}))
  (mapv (fn [pair] #?(:clj (Integer/parseInt (apply str pair) 16)
                      :cljs (js/parseInt (apply str pair) 16)))
        (partition 2 hex)))

(defn hex->state
  "A 256-char hex string to a state vector (not validated by value; the
   length is checked)."
  [hex]
  (when-not (= (* 2 host/sl2-state-bytes) (count hex))
    (bad-state! {:hex-length (count hex)}))
  (bytes->state (hex-bytes! hex)))

;; =============================================================================
;; Fuse (the group operation)
;; =============================================================================

(def fuse
  "Fuse two states: the matrix product in SL(2, F_p). Associative and
   non-commutative, with `identity-state` as identity.

   There is no low-entropy guard: states are group elements, and a repeated
   value A^n returns to the identity only when n is a multiple of A's order,
   which is above 2^252 for every generator and (all but 2^-250 of) all
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

   digest = a xor rotl64(b) xor rotl128(c) xor rotl192(d), each cell read as a
   256-bit integer and rotated left by whole 64-bit words. A pure function of
   the state, and the only way a value hash is made (docs/design/fuse-sl2.md)."
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

;; =============================================================================
;; The kernel boundary
;; =============================================================================

(defn- valid-state-of-hex!
  [hex]
  (let [s (hex->state hex)]
    (when-not (state-valid? s) (bad-state! {}))
    s))

(def kernel
  "THE FUSE KERNEL: the whole of what a different implementation (a WASM
   module, a native library) must provide to replace the arithmetic here. It is
   five pure functions over **hex strings**, so nothing about the host's bignum
   or its state representation crosses the boundary:

   | key             | in                      | out                    |
   |-----------------|-------------------------|------------------------|
   | `:state-mul`    | two states (256 hex)    | their product (256 hex)|
   | `:bytes->state` | any bytes (hex, any len)| fuse of their generators (256 hex) |
   | `:ref->state`   | a ref (64 hex)          | `:bytes->state` of its 32 bytes (256 hex) |
   | `:state->ref`   | a state (256 hex)       | its digest (64 hex)    |
   | `:state-valid?` | a hex string            | true iff it is 256 hex of four canonical cells (< p) with determinant 1 |

   A state is a, b, c, d as 32-byte big-endian cells (docs/design/fuse-sl2.md);
   a product is canonical (every cell in 0..p-1); lowercase hex; the empty
   `:bytes->state` input gives the identity. The first four throw `bad-state!`
   on input that is not a state. The generator table, the prime and the digest
   are fixed by the design doc, not chosen by the implementation.

   The exact boundary is tested, not described:
   `fixtures/fuse-sl2/vectors.edn` `:kernel` holds known answers for each
   function; `dacite.kernel-vectors/problems` runs them against any map of these
   five functions (this one, on the JVM and on nbb, or a WASM wrapper), and
   `fixtures/fuse-sl2/reference.py` re-checks them in pure Python.

   Everything else in Dacite (`fuse`, `fuse-bytes`, `ref->state`, node hashes,
   the wire) goes through the functions of this namespace and never touches
   a cell, so swapping the kernel means swapping `host/sl2-*` and nothing else.
   This map is the hex-level view of the same functions; the Clojure code calls
   the in-memory forms for speed."
  {:state-mul (fn [a b]
                (state->hex (fuse (valid-state-of-hex! a) (valid-state-of-hex! b))))
   :bytes->state (fn [hex]
                   (state->hex (fuse-bytes (hex-bytes! hex))))
   :ref->state (fn [hex]
                 (when-not (= 64 (count hex)) (bad-state! {:hex-length (count hex)}))
                 (hex-bytes! hex)
                 (state->hex (ref->state (hex->hash hex))))
   :state->ref (fn [hex]
                 (hash->hex (state->ref (valid-state-of-hex! hex))))
   :state-valid? (fn [hex]
                   (and (string? hex)
                        (= (* 2 host/sl2-state-bytes) (count hex))
                        (boolean (re-matches #"[0-9a-f]+" hex))
                        (state-valid? (hex->state hex))))})
