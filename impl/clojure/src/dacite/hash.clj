(ns dacite.hash
  "Hashing primitives for Dacite.
   
   Hash representation: [long, long, long, long] (256 bits as 4 × 64-bit longs)
   
   Implements:
   - SHA-256 for type and data hashing
   - Fuse function for combining hashes"
  (:import [java.security MessageDigest]
           [java.nio ByteBuffer]))

;; =============================================================================
;; Low-level SHA-256 (returns bytes)
;; =============================================================================

(defn sha256-bytes
  "Compute SHA-256 hash of byte array. Returns 32-byte array."
  ^bytes [^bytes data]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.digest md data)))

;; =============================================================================
;; Byte/Long conversion
;; =============================================================================

(defn bytes->longs
  "Convert 32-byte array to 4 longs (256 bits → 4 × 64 bits)."
  [^bytes b]
  (let [buf (ByteBuffer/wrap b)]
    [(.getLong buf)
     (.getLong buf)
     (.getLong buf)
     (.getLong buf)]))

(defn longs->bytes
  "Convert 4 longs to 32-byte array."
  ^bytes [[a b c d]]
  (let [buf (ByteBuffer/allocate 32)]
    (.putLong buf a)
    (.putLong buf b)
    (.putLong buf c)
    (.putLong buf d)
    (.array buf)))

;; =============================================================================
;; Hash/hex conversion
;; =============================================================================

(defn hash->hex
  "Convert hash (4 longs) to 64-char hex string."
  [h]
  (let [bytes (longs->bytes h)]
    (apply str (map #(format "%02x" (bit-and % 0xFF)) bytes))))

(defn hex->hash
  "Convert 64-char hex string to hash (vector of 4 longs)."
  [hex]
  (let [bytes (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16))
                               (partition 2 hex)))]
    (bytes->longs bytes)))

;; =============================================================================
;; SHA-256 (returns longs - standard hash form)
;; =============================================================================

(defn sha256
  "Compute SHA-256 hash of byte array. Returns [long, long, long, long]."
  [^bytes data]
  (bytes->longs (sha256-bytes data)))

(defn sha256-str
  "Compute SHA-256 hash of UTF-8 string. Returns [long, long, long, long]."
  [^String s]
  (sha256 (.getBytes s "UTF-8")))

;; =============================================================================
;; Fuse (combines two hashes)
;; =============================================================================

(defn unchecked-fuse
  "Fuse two hashes using 4×4 upper triangular matrix.
   
   Input: two vectors of 4 longs
   Output: vector of 4 longs
   
   Output ordered so most mixed bits are first (MSB), optimizing for HAMT:
   c0 = a0 + a3*b2 + b0   ← most bit mixing (used for HAMT navigation)
   c1 = a1 + b1
   c2 = a2 + b2
   c3 = a3 + b3           ← least bit mixing
   
   All arithmetic is mod 2^64 (unchecked)."
  [[a0 a1 a2 a3] [b0 b1 b2 b3]]
  [(unchecked-add a0 (unchecked-add (unchecked-multiply a3 b2) b0))
   (unchecked-add a1 b1)
   (unchecked-add a2 b2)
   (unchecked-add a3 b3)])

(defn low-entropy?
  "Check if a hash has 128 bits of zeros in the lower 32 bits of all four words.
   Such hashes indicate low-entropy input and should be rejected.
   See: https://clojurecivitas.github.io/math/hashing/hashfusing.html#detecting-low-entropy-failures
   
   Input: vector of 4 longs"
  [hash]
  (->> hash
       (map #(bit-and 0xFFFFFFFF %))
       (every? zero?)))

(defn fuse
  "Fuse two hashes using 4×4 upper triangular matrix.
   
   Input: two vectors of 4 longs
   Output: vector of 4 longs
   
   Output ordered so most mixed bits are first (MSB), optimizing for HAMT:
   c0 = a0 + a3*b2 + b0   ← most bit mixing (used for HAMT navigation)
   c1 = a1 + b1
   c2 = a2 + b2
   c3 = a3 + b3           ← least bit mixing
   
   All arithmetic is mod 2^64 (unchecked).
   Throws on low-entropy inputs or result."
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
  "Compute the inverse of a hash under fuse.
   
   fuse(inv(a), a) = fuse(a, inv(a)) = [0, 0, 0, 0]
   
   The fuse operation forms a group over (Z/2^64)^4, so every
   element has a unique two-sided inverse. The formula is:
   
   inv([a0, a1, a2, a3]) = [a3*a2 - a0, -a1, -a2, -a3]
   
   All arithmetic is mod 2^64 (unchecked)."
  [[a0 a1 a2 a3]]
  [(unchecked-subtract (unchecked-multiply a3 a2) a0)
   (unchecked-negate a1)
   (unchecked-negate a2)
   (unchecked-negate a3)])

(defn unfuse
  "Remove b's contribution from a fused hash.
   
   unfuse(fuse(a, b), b) = a
   
   Equivalent to fuse(a, inv(b)). Useful for recovering one
   component when you know the fused result and the other input."
  [a b]
  (unchecked-fuse a (fuse-inverse b)))

;; =============================================================================
;; Fuse sequences
;; =============================================================================

(defn fuse-seq
  "Fuse a sequence of hashes left-to-right.
   Returns identity [0,0,0,0] for empty sequence.
   Uses unchecked-fuse since identity is low-entropy."
  [hashes]
  (reduce unchecked-fuse [0 0 0 0] hashes))

;; =============================================================================
;; Value encoding
;; =============================================================================

(defn encode-value
  "Encode a Clojure value to bytes for hashing.
   Currently uses pr-str + UTF-8. TODO: canonical serialization per spec."
  ^bytes [value]
  (.getBytes (pr-str value) "UTF-8"))

;; =============================================================================
;; Typed value hashing
;; =============================================================================

(defn typed-value-hash
  "Compute the hash of a typed value [type-kw, data].
   
   A typed value is conceptually seq(type-name, data), where
   type-name is a seq of char leaves. The hash is:
   
     fuse(fuse-seq(map sha256 type-name-chars), sha256(encode(data)))
   
   Uses unchecked-fuse since type-name fuse-seq may be low-entropy
   for very short names (safe in practice)."
  [[type-kw data]]
  (let [type-name (if (keyword? type-kw)
                    (name type-kw)
                    (str type-kw))
        tnh (fuse-seq (map #(sha256 (.getBytes (str %) "UTF-8")) type-name))
        lh  (sha256 (encode-value data))]
    (unchecked-fuse tnh lh)))

;; =============================================================================
;; Internal node hashing
;; =============================================================================

(defn node-hash
  "Compute the hash of an internal tree node.
   
   node_hash = fuse(sha256(node-type-name), elements-fuse)
   
   Uses unchecked-fuse since elements-fuse may be [0,0,0,0]
   for empty nodes."
  [type-kw elements-fuse]
  (let [type-str (if (keyword? type-kw)
                   (str (namespace type-kw) "/" (name type-kw))
                   (str type-kw))]
    (unchecked-fuse (sha256-str type-str) elements-fuse)))

;; =============================================================================
;; REPL examples
;; =============================================================================

(comment
  ;; SHA-256 returns longs
  (sha256-str "hello")  ;; => [long long long long]

  ;; Fuse two hashes
  (def a (sha256-str "hello"))
  (def b (sha256-str "world"))
  (fuse a b)  ;; => [long long long long]

  ;; Hex conversion
  (hash->hex (sha256-str "test"))
  (= a (hex->hash (hash->hex a)))

  ;; Fuse a sequence of hashes
  (fuse-seq [(sha256-str "a") (sha256-str "b")])

  ;; Typed value hash
  (typed-value-hash [:i64 42])  ;; => fuse(fuse-seq(char-hashes), sha256(encode(42)))

  ;; Internal node hash
  (node-hash :ft/empty [0 0 0 0]))
