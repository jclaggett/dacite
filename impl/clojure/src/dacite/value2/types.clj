(ns dacite.value2.types
  "Dacite type system for value2.

   All Dacite values are [type, data] tuples stored in a content-addressed store.
   This namespace defines:
   - typed-value-hash for content addressing of typed values
   - node-hash for internal tree nodes
   - child-hashes for extracting child references
   - encode-value for canonical byte encoding
   
   Scalars are stored directly as [type data].
   Collections store metadata like {:root hash :count n :size-bytes n}."
  (:require [dacite.hash :as hash]))

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
;; Typed value and node hashing
;; =============================================================================

(def ^:private ^bytes null-separator
  "Domain separator byte (0x00) between type name and data."
  (byte-array [0]))

(def ^:private null-separator-hash
  (hash/fuse-bytes null-separator))

(defn typed-value-hash
  "Compute the hash of a typed value [type-name, data].

   hash = fuse-bytes(type-name-bytes ++ 0x00 ++ encode(data))"
  [[type-name _data :as typed-value]]
  (let [type-bytes (.getBytes ^String type-name "UTF-8")
        data-bytes (encode-value typed-value)]
    (-> (hash/fuse-bytes type-bytes)
        (hash/unchecked-fuse null-separator-hash)
        (hash/unchecked-fuse (hash/fuse-bytes data-bytes)))))

(defn node-hash
  "Compute the hash of an internal tree node.

   node_hash = fuse(fuse-str(node-type-name ++ 0x00), elements-fuse)"
  [type-name elements-fuse]
  (let [type-bytes (.getBytes ^String type-name "UTF-8")]
    (-> (hash/fuse-bytes type-bytes)
        (hash/unchecked-fuse null-separator-hash)
        (hash/unchecked-fuse elements-fuse))))

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
