(ns dacite.hash
  "Hashing primitives for Dacite (portable core).

   Hash representation: [w0 w1 w2 w3] — 256 bits as four 64-bit words
   (native longs on JVM/babashka, BigInt on ClojureScript/nbb). All the
   host-specific arithmetic lives in dacite.host; this namespace is pure.

   Core operations:
   - byte->hash: precomputed table mapping each byte (0-255) to a hash,
     shipped as data in dacite.byte-table (SHA-256 seeded, no runtime crypto)
   - fuse: combining two hashes (group operation)
   - fuse-bytes / fuse-str: hash any byte sequence via table lookup + fuse"
  (:require [dacite.host :as host]
            [dacite.byte-table :as byte-table]))

;; =============================================================================
;; Byte / long / hex conversion (delegated to host)
;; =============================================================================

(def bytes->longs host/bytes->longs)
(def longs->bytes host/longs->bytes)

(defn hash->int
  "Fold a hash to a 32-bit int (for Java hashCode / Clojure hasheq)."
  [h]
  (host/->int32 (host/bxor64 (host/bxor64 (nth h 0) (nth h 1))
                             (host/bxor64 (nth h 2) (nth h 3)))))

(def hash->hex host/longs->hex)
(def hex->hash host/hex->longs)

;; =============================================================================
;; Fuse (combines two hashes)
;; =============================================================================

(defn unchecked-fuse
  "Fuse two hashes using the 4x4 upper triangular matrix.

   Input: two vectors of 4 words. Output: vector of 4 words.

   Output ordered so most mixed bits are first (MSB), optimizing for HAMT:
   c0 = a0 + a3*b2 + b0   <- most bit mixing (used for HAMT navigation)
   c1 = a1 + b1
   c2 = a2 + b2
   c3 = a3 + b3           <- least bit mixing

   All arithmetic is mod 2^64 (unchecked)."
  [[a0 a1 a2 a3] [b0 b1 b2 b3]]
  [(host/add64 a0 (host/add64 (host/mul64 a3 b2) b0))
   (host/add64 a1 b1)
   (host/add64 a2 b2)
   (host/add64 a3 b3)])

(defn low-entropy?
  "True if the lower 32 bits of all four words are zero. Such hashes
   indicate low-entropy input and should be rejected.
   See: https://clojurecivitas.github.io/math/hashing/hashfusing.html#detecting-low-entropy-failures"
  [hash]
  (every? (fn [w] (host/word-zero? (host/low32 w))) hash))

(defn fuse
  "Fuse two hashes (see unchecked-fuse). Throws on low-entropy inputs or
   result."
  [a b]
  (when (low-entropy? a)
    (throw (ex-info "Low-entropy input hash (a)" {:a a :b b})))
  (when (low-entropy? b)
    (throw (ex-info "Low-entropy input hash (b)" {:a a :b b})))
  (let [result (unchecked-fuse a b)]
    (when (low-entropy? result)
      (throw (ex-info "Low-entropy result hash" {:a a :b b :result result})))
    result))

;; =============================================================================
;; Fuse inverse (group structure)
;; =============================================================================

(defn fuse-inverse
  "The inverse of a hash under fuse: fuse(inv(a), a) = fuse(a, inv(a)) = 0.

   inv([a0 a1 a2 a3]) = [a3*a2 - a0, -a1, -a2, -a3]  (mod 2^64)."
  [[a0 a1 a2 a3]]
  [(host/sub64 (host/mul64 a3 a2) a0)
   (host/neg64 a1)
   (host/neg64 a2)
   (host/neg64 a3)])

(defn unfuse
  "Remove b's contribution from a fused hash: unfuse(fuse(a, b), b) = a."
  [a b]
  (unchecked-fuse a (fuse-inverse b)))

;; =============================================================================
;; Fuse sequences
;; =============================================================================

(defn fuse-seq
  "Fuse a sequence of hashes left-to-right. Returns identity for empty."
  [hashes]
  (reduce unchecked-fuse host/zero-hash hashes))

;; =============================================================================
;; Byte hash table & fuse-bytes / fuse-str
;; =============================================================================

(def byte->hash
  "Precomputed hash for each byte value (0-255), indexed by unsigned byte.
   Parsed from the shipped hex table (SHA-256 seeded)."
  (mapv host/hex->longs byte-table/hex-table))

(defn fuse-bytes
  "Fuse hashes of each byte. `bs` is anything reducible over byte-sized
   integers (a vector of ints 0..255, or a host byte array). Values are
   masked to 0..255 so signed host bytes work transparently."
  [bs]
  (reduce (fn [acc b] (unchecked-fuse acc (nth byte->hash (bit-and 0xFF b))))
          host/zero-hash
          bs))

(defn fuse-str
  "Fuse hashes of each UTF-8 byte in string s."
  [s]
  (fuse-bytes (host/utf8-bytes s)))
