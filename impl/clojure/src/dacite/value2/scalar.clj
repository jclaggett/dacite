(ns dacite.value2.scalar
  "Dacite scalar types built on primitive raw bytes.

   Scalars are typed values: [type-name, canonical-data-bytes].
   They are constructed by:
   1. Storing the type-name as a raw UTF-8 string
   2. Storing the canonical data bytes
   3. Storing [type-hash, data-hash] at fuse(type-hash, data-hash)

   This gives us the open-ended type system: any type-name string can be
   used, and the type's semantics are determined by how consumers decode
   the raw data bytes.

   NOTE: This namespace is temporarily disabled while primitive.clj is
   being completed (seq and map primitives needed for typed values)."
  (:require [dacite.value2.primitive :as prim]
            [dacite.hash :as hash]
            [dacite.store :as store]))

;; =============================================================================
;; Typed value helper (disabled until seq primitive is ready)
;; =============================================================================

;; NOTE: typed values require the seq primitive to store [type-hash data-hash]
;; as a 2-element sequence. Until seq is implemented, typed constructors are
;; commented out. Raw primitives in primitive.clj are ready for use.

(comment

  (defn- typed
    "Store a typed value [type-name, data-bytes].
     Returns [store' hash] where hash = fuse(type-hash, data-hash)."
    [store type-name ^bytes data-bytes]
    (let [[store' type-hash] (prim/raw-bytes store (.getBytes ^String type-name "UTF-8"))
          [store'' data-hash] (prim/raw-bytes store' data-bytes)
          tuple-hash (hash/unchecked-fuse type-hash data-hash)
          store''' (store/s-put store'' tuple-hash [type-hash data-hash])]
      [store''' tuple-hash]))

  ;; =============================================================================
  ;; Scalar constructors
  ;; =============================================================================

  (defn null "Create a null value." [store]
    (typed store "null" (byte-array 0)))

  (defn bool "Create a boolean value." [store b]
    (typed store "bool" (byte-array [(if b (byte 1) (byte 0))])))

  (defn i8 "Create an i8 value." [store n]
    (typed store "i8" (prim/i8->bytes n)))

  (defn i16 "Create an i16 value." [store n]
    (typed store "i16" (prim/i16->bytes n)))

  (defn i32 "Create an i32 value." [store n]
    (typed store "i32" (prim/i32->bytes n)))

  (defn i64 "Create an i64 value." [store n]
    (typed store "i64" (prim/i64->bytes n)))

  (defn u8 "Create a u8 value." [store n]
    {:pre [(<= 0 n 255)]}
    (typed store "u8" (prim/u8->bytes n)))

  (defn u16 "Create a u16 value." [store n]
    {:pre [(<= 0 n 65535)]}
    (typed store "u16" (prim/u16->bytes n)))

  (defn u32 "Create a u32 value." [store n]
    {:pre [(<= 0 n 4294967295)]}
    (typed store "u32" (prim/u32->bytes n)))

  (defn u64 "Create a u64 value." [store n]
    {:pre [(<= 0 n)]}
    (typed store "u64" (prim/u64->bytes n)))

  (defn u256
    "Create a u256 value (e.g. hash as data). Data must be 32-byte array."
    [store ^bytes data]
    {:pre [(= 32 (alength data))]}
    (typed store "u256" data))

  (defn f32 "Create an f32 value." [store n]
    (typed store "f32" (prim/f32->bytes n)))

  (defn f64 "Create an f64 value." [store n]
    (typed store "f64" (prim/f64->bytes n)))

  (defn dacite-char "Create a char value." [store c]
    {:pre [(char? c)]}
    (typed store "char" (prim/char->bytes c)))

  (defn neg "The negative sentinel." [store]
    (typed store "negative" (byte-array 0)))) ; end comment
