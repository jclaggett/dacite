(ns dacite.value2.primitive
  "Untyped primitive values for Dacite.

   Three primitive kinds:
   - raw     : byte arrays, stored directly by their content hash
   - seq     : finger tree of hashes (for vectors, strings, blobs)
   - map     : HAMT of hash→hash (for maps, sets)

   These are the foundational building blocks. Everything else (typed scalars,
   typed collections, the open-ended type system itself) is built on top.

   All constructors are pure: [store input] → [store' hash]."
  (:require [dacite.store :as store]
            [dacite.hash :as hash]))

;; =============================================================================
;; Raw primitives
;; =============================================================================

(defn store-bytes
  "Store a raw byte array. Returns [store' hash].

   The hash is computed from the bytes via fuse-bytes (dogfooding the
   hash fusion operation rather than using sha256 directly)."
  [store ^bytes data]
  (let [h (hash/fuse-bytes data)
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

(defn raw-i8   [store n] (store-bytes store (i8->bytes n)))
(defn raw-i16  [store n] (store-bytes store (i16->bytes n)))
(defn raw-i32  [store n] (store-bytes store (i32->bytes n)))
(defn raw-i64  [store n] (store-bytes store (i64->bytes n)))
(defn raw-f32  [store n] (store-bytes store (f32->bytes n)))
(defn raw-f64  [store n] (store-bytes store (f64->bytes n)))
(defn raw-u8   [store n] (store-bytes store (u8->bytes n)))
(defn raw-u16  [store n] (store-bytes store (u16->bytes n)))
(defn raw-u32  [store n] (store-bytes store (u32->bytes n)))
(defn raw-u64  [store n] (store-bytes store (u64->bytes n)))
(defn raw-char [store c] (store-bytes store (char->bytes c)))

(defn raw-u256
  "Store a 32-byte value (e.g. a hash as data)."
  [store ^bytes data]
  {:pre [(= 32 (alength data))]}
  (store-bytes store data))

(defn raw-null
  "Store the null value (0 bytes)."
  [store]
  (store-bytes store (byte-array 0)))

(defn raw-bool
  "Store a boolean as 1 byte (0x00 or 0x01)."
  [store b]
  (store-bytes store (byte-array [(if b (byte 1) (byte 0))])))

;; =============================================================================
;; REPL examples
;; =============================================================================

(comment
  ;; Raw bytes
  (let [store (store/mem-store)
        [store' h] (store-bytes store (.getBytes "hello" "UTF-8"))]
    [(hash/hash->hex h)
     (String. (store/s-get store' h) "UTF-8")])
  ;; => ["..." "hello"]

  ;; Raw i64
  (let [store (store/mem-store)
        [store' h] (raw-i64 store 42)]
    [(hash/hash->hex h)
     (let [buf (java.nio.ByteBuffer/wrap (store/s-get store' h))]
       (.getLong buf))])
  ;; => ["..." 42]

  ;; Raw bool
  (let [store (store/mem-store)
        [store' h-true] (raw-bool store true)
        [store'' h-false] (raw-bool store' false)]
    [(seq (store/s-get store'' h-true))
     (seq (store/s-get store'' h-false))]))
  ;; => [(1) (0)]

