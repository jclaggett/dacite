(ns dacite.value2.types
  "Dacite type system for value2.

   Two layers:
   1. Wire protocol: leading byte tag for primitive types (0x00-0xFE),
      0xFF indicates user type followed by 32-byte type hash.
   2. Store level: every value is stored as [type-hash data-hash]
      where type-hash is the content hash of the type name (for primitives,
      precomputed).

   This namespace defines:
   - Primitive type byte tags and precomputed type hashes
   - typed-value-hash for content addressing of typed values
   - node-hash for internal tree nodes
   - child-hashes for extracting child references
   - encode-value for canonical byte encoding

   Scalars are stored directly as [type data].
   Collections store metadata like {:root hash :count n :size-bytes n}."
  (:require [dacite.hash :as hash]))

;; =============================================================================
;; Primitive type byte tags
;; =============================================================================

(def ^:const tag-null     (byte 0x00))
(def ^:const tag-bool     (byte 0x01))
(def ^:const tag-i8       (byte 0x02))
(def ^:const tag-i16      (byte 0x03))
(def ^:const tag-i32      (byte 0x04))
(def ^:const tag-i64      (byte 0x05))
(def ^:const tag-u8       (byte 0x06))
(def ^:const tag-u16      (byte 0x07))
(def ^:const tag-u32      (byte 0x08))
(def ^:const tag-u64      (byte 0x09))
(def ^:const tag-u256     (byte 0x0A))
(def ^:const tag-f32      (byte 0x0B))
(def ^:const tag-f64      (byte 0x0C))
(def ^:const tag-char     (byte 0x0D))
(def ^:const tag-negative (byte 0x0E))

;; Map from tag byte to primitive type name string
(def tag->type-name
  "Map from primitive tag byte to type name string."
  {tag-null     "null"
   tag-bool     "bool"
   tag-i8       "i8"
   tag-i16      "i16"
   tag-i32      "i32"
   tag-i64      "i64"
   tag-u8       "u8"
   tag-u16      "u16"
   tag-u32      "u32"
   tag-u64      "u64"
   tag-u256     "u256"
   tag-f32      "f32"
   tag-f64      "f64"
   tag-char     "char"
   tag-negative "negative"})

;; Map from primitive type name string to tag byte
(def type-name->tag
  "Map from type name string to primitive tag byte."
  (into {} (map (fn [[k v]] [v k]) tag->type-name)))

;; =============================================================================
;; Precomputed type hashes for primitives
;; =============================================================================

(defonce ^:private primitive-type-hash-cache
  (atom {}))

(defn primitive-type-hash
  "Return the precomputed type hash for a primitive type name.
   Lazily computes and caches the hash on first access.
   The hash is fuse-str of the type name string bytes."
  [type-name]
  (if-let [cached (get @primitive-type-hash-cache type-name)]
    cached
    (let [h (hash/fuse-str type-name)]
      (swap! primitive-type-hash-cache assoc type-name h)
      h)))

(defn primitive-type-tag
  "Return the primitive type tag byte for a type name, or nil if not primitive."
  [type-name]
  (get type-name->tag type-name))

(defn type-tag->hash
  "Return the precomputed type hash for a primitive tag byte, or nil."
  [tag]
  (when-let [type-name (get tag->type-name tag)]
    (primitive-type-hash type-name)))

(defn type-hash->tag
  "Return the primitive type tag byte for a type hash, or nil if not a primitive hash."
  [type-hash]
  (let [cache @primitive-type-hash-cache]
    (some (fn [[type-name h]]
            (when (= h type-hash)
              (get type-name->tag type-name)))
          cache)))

;; Eagerly precompute all primitive type hashes
(doseq [[_ type-name] tag->type-name]
  (primitive-type-hash type-name))

;; =============================================================================
;; Typed value hash
;; =============================================================================

(defn typed-value-hash
  "Compute the hash of a typed value [type-hash, data-hash].

   hash = fuse(type-hash, data-hash)

   For primitive types, type-hash is the precomputed hash of the type name.
   For user types (future), the type hash is passed directly."
  [type-hash data-hash]
  (hash/unchecked-fuse type-hash data-hash))

;; =============================================================================
;; Node hash
;; =============================================================================

(def ^:private ^bytes null-separator
  "Domain separator byte (0x00) between type name and data."
  (byte-array [0]))

(def ^:private null-separator-hash
  (hash/fuse-bytes null-separator))

(defn node-hash
  "Compute the hash of an internal tree node.

   node_hash = fuse(fuse-str(node-type-name ++ 0x00), elements-fuse)"
  [type-name elements-fuse]
  (let [type-bytes (.getBytes ^String type-name "UTF-8")]
    (-> (hash/fuse-bytes type-bytes)
        (hash/unchecked-fuse null-separator-hash)
        (hash/unchecked-fuse elements-fuse))))

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

   Collections use node-hash + elements_fuse instead."
  (fn [[type-name _data]] type-name))

;; Default: fall back to pr-str for types without a canonical encoding.
(defmethod encode-value :default [[_ data]]
  (.getBytes (pr-str data) "UTF-8"))

;; =============================================================================
;; Scalar encode-value implementations
;; =============================================================================

(defmethod encode-value "null" [_]
  (byte-array 0))

(defmethod encode-value "bool" [[_ b]]
  (byte-array [(if b (byte 1) (byte 0))]))

(defmethod encode-value "i8" [[_ n]]
  (byte-array [(unchecked-byte n)]))

(defmethod encode-value "i16" [[_ n]]
  (let [buf (java.nio.ByteBuffer/allocate 2)]
    (.putShort buf (short n))
    (.array buf)))

(defmethod encode-value "i32" [[_ n]]
  (let [buf (java.nio.ByteBuffer/allocate 4)]
    (.putInt buf (int n))
    (.array buf)))

(defmethod encode-value "i64" [[_ n]]
  (let [buf (java.nio.ByteBuffer/allocate 8)]
    (.putLong buf (long n))
    (.array buf)))

(defmethod encode-value "u8" [[_ n]]
  (byte-array [(unchecked-byte n)]))

(defmethod encode-value "u16" [[_ n]]
  (let [buf (java.nio.ByteBuffer/allocate 2)]
    (.putShort buf (unchecked-short n))
    (.array buf)))

(defmethod encode-value "u32" [[_ n]]
  (let [buf (java.nio.ByteBuffer/allocate 4)]
    (.putInt buf (unchecked-int n))
    (.array buf)))

(defmethod encode-value "u64" [[_ n]]
  (let [buf (java.nio.ByteBuffer/allocate 8)]
    (.putLong buf (unchecked-long n))
    (.array buf)))

(defmethod encode-value "u256" [[_ ^bytes data]]
  data)

(defmethod encode-value "f32" [[_ n]]
  (let [buf (java.nio.ByteBuffer/allocate 4)]
    (.putFloat buf (float n))
    (.array buf)))

(defmethod encode-value "f64" [[_ n]]
  (let [buf (java.nio.ByteBuffer/allocate 8)]
    (.putDouble buf (double n))
    (.array buf)))

(defmethod encode-value "char" [[_ ch]]
  (.getBytes (str ch) "UTF-8"))

(defmethod encode-value "negative" [[_ _]]
  (byte-array []))

;; =============================================================================
;; Child hash extraction (multimethod)
;; =============================================================================

(defmulti child-hashes
  "Extract child hash references from a stored node value.
   Returns an ordered vector of hashes that this node directly references.

   Dispatches on type string."
  (fn [node]
    (when (and (vector? node) (= 2 (count node)))
      (first node))))

(defmethod child-hashes nil [_] nil)
(defmethod child-hashes :default [_] [])

;; Collection types have a :root child
(doseq [t ["vector" "string" "blob" "map" "set"]]
  (defmethod child-hashes t [[_ data]]
    (when-let [root (:root data)]
      [root])))

;; =============================================================================
;; Size computation (multimethod)
;; =============================================================================

(defmulti dacite-size
  "Get the size in bytes of a typed value.
   Dispatches on type string."
  (fn [[type-name _data]] type-name))

;; Default: check for :size-bytes in collection data
(defmethod dacite-size :default [[_ data]]
  (if-let [sb (:size-bytes data)]
    sb
    (count (.getBytes (pr-str data) "UTF-8"))))

;; Scalar sizes
(defmethod dacite-size "null" [_] 0)
(defmethod dacite-size "bool" [_] 1)
(defmethod dacite-size "i8" [_] 1)
(defmethod dacite-size "i16" [_] 2)
(defmethod dacite-size "i32" [_] 4)
(defmethod dacite-size "i64" [_] 8)
(defmethod dacite-size "u8" [_] 1)
(defmethod dacite-size "u16" [_] 2)
(defmethod dacite-size "u32" [_] 4)
(defmethod dacite-size "u64" [_] 8)
(defmethod dacite-size "u256" [_] 32)
(defmethod dacite-size "f32" [_] 4)
(defmethod dacite-size "f64" [_] 8)
(defmethod dacite-size "char" [[_ ch]]
  (count (.getBytes (str ch) "UTF-8")))
(defmethod dacite-size "negative" [_] 0)
