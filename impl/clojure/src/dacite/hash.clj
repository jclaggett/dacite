(ns dacite.hash
  "Hashing primitives for Dacite.
   
   Implements:
   - SHA-256 for type and data hashing
   - Fuse function for combining hashes"
  (:import [java.security MessageDigest]
           [java.nio ByteBuffer]))

(defn sha256
  "Compute SHA-256 hash of byte array. Returns 32-byte array."
  ^bytes [^bytes data]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.digest md data)))

(defn sha256-str
  "Compute SHA-256 hash of UTF-8 string. Returns 32-byte array."
  ^bytes [^String s]
  (sha256 (.getBytes s "UTF-8")))

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

(defn low-entropy?
  "Check if a hash has 128 bits of zeros in the lower 32 bits of all four words.
   Such hashes indicate low-entropy input and should be rejected.
   See: https://clojurecivitas.github.io/math/hashing/hashfusing.html#detecting-low-entropy-failures
   
   Input: vector of 4 longs"
  [[c0 c1 c2 c3]]
  (let [mask 0xFFFFFFFF]
    (and (zero? (bit-and c0 mask))
         (zero? (bit-and c1 mask))
         (zero? (bit-and c2 mask))
         (zero? (bit-and c3 mask)))))

(defn unchecked-fuse-longs
  "Fuse two vectors of longs without checking for low-entropy result.
   
   Input: two vectors of 4 longs
   Output: vector of 4 longs"
  [[a0 a1 a2 a3] [b0 b1 b2 b3]]
  [(unchecked-add a0 (unchecked-add (unchecked-multiply a3 b2) b0))
   (unchecked-add a1 b1)
   (unchecked-add a2 b2)
   (unchecked-add a3 b3)])

(defn fuse-longs
  "Fuse two vectors of longs with low-entropy check.
   
   Input: two vectors of 4 longs
   Output: vector of 4 longs
   Throws: ExceptionInfo if result is low-entropy"
  [a b]
  (let [result (unchecked-fuse-longs a b)]
    (when (low-entropy? result)
      (throw (ex-info "Low-entropy hash detected" {:a a :b b :result result})))
    result))

(defn fuse
  "Fuse two hashes using 4×4 upper triangular matrix.
   
   Input: two 32-byte arrays
   Output: 32-byte array
   
   Output ordered so most mixed bits are first (MSB), optimizing for HAMT:
   c0 = a0 + a3*b2 + b0   ← most bit mixing (used for HAMT navigation)
   c1 = a1 + b1  
   c2 = a2 + b2
   c3 = a3 + b3           ← least bit mixing
   
   All arithmetic is mod 2^64 (unchecked).
   Throws on low-entropy result."
  [a b]
  (longs->bytes (fuse-longs (bytes->longs a) (bytes->longs b))))

(defn type-hash
  "Compute hash for a type name."
  ^bytes [^String type-name]
  (sha256-str type-name))

(defn value-hash
  "Compute hash for a value given its type-hash and data bytes."
  ^bytes [^bytes type-hash ^bytes data-bytes]
  (fuse type-hash (sha256 data-bytes)))

;; Pre-computed type hashes for built-in types
(def ^:private builtin-types
  {"null"   "dacite.core/null"
   "bool"   "dacite.core/bool"
   "i8"     "dacite.core/i8"
   "i16"    "dacite.core/i16"
   "i32"    "dacite.core/i32"
   "i64"    "dacite.core/i64"
   "i128"   "dacite.core/i128"
   "i256"   "dacite.core/i256"
   "u8"     "dacite.core/u8"
   "u16"    "dacite.core/u16"
   "u32"    "dacite.core/u32"
   "u64"    "dacite.core/u64"
   "u128"   "dacite.core/u128"
   "u256"   "dacite.core/u256"
   "f32"    "dacite.core/f32"
   "f64"    "dacite.core/f64"
   "char"   "dacite.core/char"
   "string" "dacite.core/string"
   "blob"   "dacite.core/blob"
   "vector" "dacite.core/vector"
   "map"    "dacite.core/map"})

(def builtin-type-hashes
  "Map of type keyword to pre-computed type hash bytes."
  (into {}
        (map (fn [[k v]] [(keyword k) (type-hash v)]))
        builtin-types))

(comment
  ;; Test fuse
  (def a (sha256-str "hello"))
  (def b (sha256-str "world"))
  (vec (fuse a b))
  
  ;; Test fuse-longs (no byte conversion)
  (def al (bytes->longs a))
  (def bl (bytes->longs b))
  (fuse-longs al bl)
  
  ;; Test type hash
  (vec (type-hash "dacite.core/i64")))
