(ns dacite.value2.primitive
  "Untyped primitive values for Dacite.

   Three primitive kinds:
   - raw     : byte arrays, stored directly by their content hash
   - seq     : finger tree of hashes (for vectors, strings, blobs)
   - map     : HAMT of hash→hash (for maps, sets)

   These are the foundational building blocks. Everything else (typed scalars,
   typed collections, the open-ended type system itself) is built on top.

   All constructors are pure: [store input] → [store' hash].

   Design note on typed values:
   A typed value [type-name, data] is stored as a primitive seq of two hashes:
   - hash of the raw type-name string
   - hash of the raw data bytes

   The typed value's content-address is fuse(type-hash, data-hash).
   This is the same as hash fusion for sequence elements, giving a uniform
   treatment: typed values are just 2-element sequences with special semantics."
  (:require [dacite.store :as store]
            [dacite.hash :as hash]))

;; =============================================================================
;; Raw primitives
;; =============================================================================

(defn raw-bytes
  "Store a raw byte array. Returns [store' hash].

   The hash is computed directly from the bytes via SHA-256.
   This is the most primitive operation — everything else bottoms out here."
  [store ^bytes data]
  (let [h (hash/sha256 data)
        store' (store/s-put store h data)]
    [store' h]))

;; =============================================================================
;; Small helpers for numeric → bytes
;; =============================================================================

(defn i8->bytes [n]
  (byte-array [(unchecked-byte n)]))

(defn i16->bytes [n]
  (let [buf (java.nio.ByteBuffer/allocate 2)]
    (.putShort buf (short n))
    (.array buf)))

(defn i32->bytes [n]
  (let [buf (java.nio.ByteBuffer/allocate 4)]
    (.putInt buf (int n))
    (.array buf)))

(defn i64->bytes [n]
  (let [buf (java.nio.ByteBuffer/allocate 8)]
    (.putLong buf (long n))
    (.array buf)))

(defn f32->bytes [n]
  (let [buf (java.nio.ByteBuffer/allocate 4)]
    (.putFloat buf (float n))
    (.array buf)))

(defn f64->bytes [n]
  (let [buf (java.nio.ByteBuffer/allocate 8)]
    (.putDouble buf (double n))
    (.array buf)))

(defn u8->bytes [n]
  (byte-array [(unchecked-byte n)]))

(defn u16->bytes [n]
  (let [buf (java.nio.ByteBuffer/allocate 2)]
    (.putShort buf (unchecked-short n))
    (.array buf)))

(defn u32->bytes [n]
  (let [buf (java.nio.ByteBuffer/allocate 4)]
    (.putInt buf (unchecked-int n))
    (.array buf)))

(defn u64->bytes [n]
  (let [buf (java.nio.ByteBuffer/allocate 8)]
    (.putLong buf (unchecked-long n))
    (.array buf)))

(defn char->bytes [ch]
  (.getBytes (str ch) "UTF-8"))

;; =============================================================================
;; Convenience: store typed numeric scalars via raw-bytes
;; =============================================================================

(defn raw-i8   [store n] (raw-bytes store (i8->bytes n)))
(defn raw-i16  [store n] (raw-bytes store (i16->bytes n)))
(defn raw-i32  [store n] (raw-bytes store (i32->bytes n)))
(defn raw-i64  [store n] (raw-bytes store (i64->bytes n)))
(defn raw-f32  [store n] (raw-bytes store (f32->bytes n)))
(defn raw-f64  [store n] (raw-bytes store (f64->bytes n)))
(defn raw-u8   [store n] (raw-bytes store (u8->bytes n)))
(defn raw-u16  [store n] (raw-bytes store (u16->bytes n)))
(defn raw-u32  [store n] (raw-bytes store (u32->bytes n)))
(defn raw-u64  [store n] (raw-bytes store (u64->bytes n)))
(defn raw-char [store c] (raw-bytes store (char->bytes c)))

(defn raw-u256
  "Store a 32-byte value (e.g. a hash as data)."
  [store ^bytes data]
  {:pre [(= 32 (alength data))]}
  (raw-bytes store data))

(defn raw-null
  "Store the null value (0 bytes)."
  [store]
  (raw-bytes store (byte-array 0)))

(defn raw-bool
  "Store a boolean as 1 byte (0x00 or 0x01)."
  [store b]
  (raw-bytes store (byte-array [(if b (byte 1) (byte 0))])))

;; =============================================================================
;; String → raw bytes (UTF-8)
;; =============================================================================

(defn raw-string
  "Store a UTF-8 string as raw bytes.

   This is the primitive under string values. The typed layer will wrap
   this with type info and split large strings across blob boundaries."
  [store ^String s]
  (raw-bytes store (.getBytes s "UTF-8")))

;; =============================================================================
;; Typed values (2-element seq of [type-hash, data-hash])
;; =============================================================================

(defn typed
  "Create a typed value from primitive parts.

   Given a type-name string and data (already stored as raw bytes),
   stores a 2-element structure [type-hash, data-hash] and returns
   its fused hash.

   Used by: scalar constructors, collection constructors, user-defined types.

   The hash is fuse(type-hash, data-hash) — same as any 2-element seq.
   The type tag lives in the first element (a raw string hash)."
  [store type-name ^bytes data-bytes]
  (let [[store' type-hash] (raw-string store type-name)
        [store'' data-hash] (raw-bytes store' data-bytes)
        ;; Store the tuple so it can be fetched by its fused hash
        tuple-hash (hash/unchecked-fuse type-hash data-hash)
        store''' (store/s-put store'' tuple-hash [type-hash data-hash])]
    [store''' tuple-hash]))

(defn typed-from-hashes
  "Create a typed value from existing hashes.

   Used when both type and data are already in the store.
   Returns [store' tuple-hash]."
  [store type-hash data-hash]
  (let [tuple-hash (hash/unchecked-fuse type-hash data-hash)
        store' (store/s-put store tuple-hash [type-hash data-hash])]
    [store' tuple-hash]))

;; =============================================================================
;; Fetching raw values
;; =============================================================================

(defn fetch-raw
  "Fetch raw bytes from the store by hash.
   Returns the byte array or nil if not found."
  [store h]
  (store/s-get store h))

;; =============================================================================
;; Primitive predicates (by inspecting stored value type)
;; =============================================================================

(defn raw?
  "Check if a hash points to a raw byte array in the store.
   (Heuristic: value is a byte array, not a vector/map.)"
  [store h]
  (bytes? (store/s-get store h)))

;; =============================================================================
;; REPL examples
;; =============================================================================

(comment
  ;; Raw bytes
  (let [store (store/mem-store)
        [store' h] (raw-bytes store (.getBytes "hello" "UTF-8"))]
    [(hash/hash->hex h)
     (String. (fetch-raw store' h) "UTF-8")])
  ;; => ["2cf24..." "hello"]

  ;; Raw i64
  (let [store (store/mem-store)
        [store' h] (raw-i64 store 42)]
    [(hash/hash->hex h)
     (let [buf (java.nio.ByteBuffer/wrap (fetch-raw store' h))]
       (.getLong buf))])
  ;; => ["..." 42]

  ;; Raw bool
  (let [store (store/mem-store)
        [store' h-true] (raw-bool store true)
        [store'' h-false] (raw-bool store' false)]
    [(seq (fetch-raw store'' h-true))
     (seq (fetch-raw store'' h-false))])
  ;; => [(1) (0)]
  )
