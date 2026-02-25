(ns dacite.hash
  "Hashing primitives for Dacite.
   
   Hash representation: [long, long, long, long] (256 bits as 4 × 64-bit longs)
   
   Core operations:
   - byte->hash: precomputed table mapping each byte (0-255) to a hash
   - fuse: combining two hashes (group operation)
   - fuse-str: hash any byte sequence via table lookup + fuse
   
   SHA-256 is used only to seed the byte->hash table."
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

(defn hash->int
  "Convert hash to a 32-bit int (for Java hashCode / Clojure hasheq)."
  [h]
  (unchecked-int (bit-xor (nth h 0) (nth h 1) (nth h 2) (nth h 3))))

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
;; Byte hash table & fuse-str
;; =============================================================================

(def byte->hash
  "Precomputed hash for each byte value (0-255).
   Maps byte value to [long, long, long, long].
   SHA-256 is used as the seed function; any set of 256 distinct
   high-quality 32-byte values would work."
  (into {} (for [i (range 256)
                 :let [b (unchecked-byte i)
                       h (sha256 (byte-array [b]))]]
             [b h])))

(defn fuse-bytes
  "Fuse hashes of each byte in a byte array.
   Returns [long, long, long, long]."
  [^bytes bs]
  (let [len (alength bs)]
    (loop [i 0
           acc [0 0 0 0]]
      (if (< i len)
        (recur (unchecked-inc i)
               (unchecked-fuse acc (byte->hash (aget bs i))))
        acc))))

(defn fuse-str
  "Fuse hashes of each UTF-8 byte in string s.
   Returns [long, long, long, long]."
  [^String s]
  (fuse-bytes (.getBytes s "UTF-8")))

;; =============================================================================
;; Value encoding
;; =============================================================================

;; =============================================================================
;; Canonical value encoding (multimethod)
;; =============================================================================

(defmulti encode-value
  "Encode a typed Dacite value to canonical bytes for hashing.
   
   Dispatches on type-name string. Each scalar type has a fixed-width,
   big-endian, language-agnostic encoding:
   
     null  → 0 bytes
     bool  → 1 byte (0x00 or 0x01)
     i8    → 1 byte signed
     i16   → 2 bytes big-endian signed
     i32   → 4 bytes big-endian signed
     i64   → 8 bytes big-endian signed
     u8    → 1 byte unsigned
     u16   → 2 bytes big-endian unsigned
     u32   → 4 bytes big-endian unsigned
     u64   → 8 bytes big-endian unsigned
     u256  → 32 bytes raw
     f32   → 4 bytes IEEE 754 big-endian
     f64   → 8 bytes IEEE 754 big-endian
     char  → 1-4 bytes UTF-8
   
   Collections (string, vector, map, blob) are hashed via their
   elements_fuse measure, not via encode-value."
  (fn [[type-name _data]] type-name))

;; Null: 0 bytes
(defmethod encode-value "null" [_]
  (byte-array 0))

;; Boolean: 1 byte
(defmethod encode-value "bool" [[_ b]]
  (byte-array [(if b (byte 1) (byte 0))]))

;; Signed integers: big-endian
(defmethod encode-value "i8" [[_ n]]
  (byte-array [(unchecked-byte n)]))

(defmethod encode-value "i16" [[_ n]]
  (let [buf (ByteBuffer/allocate 2)]
    (.putShort buf (short n))
    (.array buf)))

(defmethod encode-value "i32" [[_ n]]
  (let [buf (ByteBuffer/allocate 4)]
    (.putInt buf (int n))
    (.array buf)))

(defmethod encode-value "i64" [[_ n]]
  (let [buf (ByteBuffer/allocate 8)]
    (.putLong buf (long n))
    (.array buf)))

;; Unsigned integers: big-endian (stored as their byte representation)
(defmethod encode-value "u8" [[_ n]]
  (byte-array [(unchecked-byte n)]))

(defmethod encode-value "u16" [[_ n]]
  (let [buf (ByteBuffer/allocate 2)]
    (.putShort buf (unchecked-short n))
    (.array buf)))

(defmethod encode-value "u32" [[_ n]]
  (let [buf (ByteBuffer/allocate 4)]
    (.putInt buf (unchecked-int n))
    (.array buf)))

(defmethod encode-value "u64" [[_ n]]
  (let [buf (ByteBuffer/allocate 8)]
    (.putLong buf (unchecked-long n))
    (.array buf)))

(defmethod encode-value "u256" [[_ ^bytes data]]
  data)

;; Floating point: IEEE 754 big-endian
(defmethod encode-value "f32" [[_ n]]
  (let [buf (ByteBuffer/allocate 4)]
    (.putFloat buf (float n))
    (.array buf)))

(defmethod encode-value "f64" [[_ n]]
  (let [buf (ByteBuffer/allocate 8)]
    (.putDouble buf (double n))
    (.array buf)))

;; Character: UTF-8 bytes
(defmethod encode-value "char" [[_ ch]]
  (.getBytes (str ch) "UTF-8"))

;; Default: fall back to pr-str for types without a canonical encoding.
;; This covers collection types (string, vector, map, blob) when they
;; pass through typed-value-hash in the cache layer. Collection hashes
;; in core use node-hash + elements_fuse instead.
(defmethod encode-value :default [[_ data]]
  (.getBytes (pr-str data) "UTF-8"))

;; =============================================================================
;; Typed value and node hashing
;; =============================================================================

(def ^:private ^bytes null-separator
  "Domain separator byte (0x00) between type name and data.
   Prevents boundary collisions: since type names cannot contain
   null bytes, type ++ 0x00 ++ data is an unambiguous encoding."
  (byte-array [0]))

(def ^:private null-separator-hash
  (fuse-bytes null-separator))

(defn typed-value-hash
  "Compute the hash of a typed value [type-name, data].
   
   A typed value is conceptually seq(type-name, data).
   The hash is: fuse-bytes(type-name-bytes ++ 0x00 ++ encode(data))
   
   type-name is a string (e.g. \"i64\", \"vector\").
   The null byte acts as a domain separator — since type names
   cannot contain null bytes, this prevents boundary collisions."
  [[type-name _data :as typed-value]]
  (let [type-bytes (.getBytes ^String type-name "UTF-8")
        data-bytes (encode-value typed-value)]
    (-> (fuse-bytes type-bytes)
        (unchecked-fuse null-separator-hash)
        (unchecked-fuse (fuse-bytes data-bytes)))))

(defn node-hash
  "Compute the hash of an internal tree node.
   
   node_hash = fuse(fuse-str(node-type-name ++ 0x00), elements-fuse)
   
   type-name is a string (e.g. \"ft/empty\", \"hamt/bitmap\").
   The null byte terminates the type name, preventing collisions
   with longer type names. Uses unchecked-fuse since elements-fuse
   may be [0,0,0,0] for empty nodes."
  [type-name elements-fuse]
  (let [type-bytes (.getBytes ^String type-name "UTF-8")]
    (-> (fuse-bytes type-bytes)
        (unchecked-fuse null-separator-hash)
        (unchecked-fuse elements-fuse))))

;; =============================================================================
;; REPL examples
;; =============================================================================

(comment
  ;; Byte hash table (256 entries, SHA-256 seeded)
  (byte->hash (byte 0))   ;; => [long long long long]
  (byte->hash (byte 65))  ;; => hash for 'A'

  ;; Fuse-str: hash any string via byte table + fuse
  (fuse-str "hello")  ;; => [long long long long]

  ;; Fuse two hashes
  (def a (fuse-str "hello"))
  (def b (fuse-str "world"))
  (fuse a b)  ;; => [long long long long]

  ;; Hex conversion
  (hash->hex (fuse-str "test"))
  (= a (hex->hash (hash->hex a)))

  ;; Typed value hash (with null separator)
  (typed-value-hash ["i64" 42])  ;; => fuse-bytes("i64" ++ 0x00 ++ encode(42))

  ;; Internal node hash
  (node-hash "ft/empty" [0 0 0 0]))
