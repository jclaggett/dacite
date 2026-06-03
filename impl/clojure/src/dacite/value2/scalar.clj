(ns dacite.value2.scalar
  "Dacite scalar types built on primitive raw bytes.

   Scalars are typed values: [type-name, canonical-data-bytes].
   They are constructed by:
   1. Encoding the data to canonical bytes
   2. Calling primitive/typed with the type name and bytes

   This gives us the open-ended type system: any type-name string can be
   used, and the type's semantics are determined by how consumers decode
   the raw data bytes."
  (:require [dacite.value2.primitive :as prim]))

;; =============================================================================
;; Scalar constructors
;; =============================================================================

(defn null "Create a null value." [store]
  (prim/typed store "null" (byte-array 0)))

(defn bool "Create a boolean value." [store b]
  (prim/typed store "bool" (byte-array [(if b (byte 1) (byte 0))])))

(defn i8 "Create an i8 value." [store n]
  (prim/typed store "i8" (prim/i8->bytes n)))

(defn i16 "Create an i16 value." [store n]
  (prim/typed store "i16" (prim/i16->bytes n)))

(defn i32 "Create an i32 value." [store n]
  (prim/typed store "i32" (prim/i32->bytes n)))

(defn i64 "Create an i64 value." [store n]
  (prim/typed store "i64" (prim/i64->bytes n)))

(defn u8 "Create a u8 value." [store n]
  {:pre [(<= 0 n 255)]}
  (prim/typed store "u8" (prim/u8->bytes n)))

(defn u16 "Create a u16 value." [store n]
  {:pre [(<= 0 n 65535)]}
  (prim/typed store "u16" (prim/u16->bytes n)))

(defn u32 "Create a u32 value." [store n]
  {:pre [(<= 0 n 4294967295)]}
  (prim/typed store "u32" (prim/u32->bytes n)))

(defn u64 "Create a u64 value." [store n]
  {:pre [(<= 0 n)]}
  (prim/typed store "u64" (prim/u64->bytes n)))

(defn u256
  "Create a u256 value (e.g. hash as data). Data must be 32-byte array."
  [store ^bytes data]
  {:pre [(= 32 (alength data))]}
  (prim/typed store "u256" data))

(defn f32 "Create an f32 value." [store n]
  (prim/typed store "f32" (prim/f32->bytes n)))

(defn f64 "Create an f64 value." [store n]
  (prim/typed store "f64" (prim/f64->bytes n)))

(defn dacite-char "Create a char value." [store c]
  {:pre [(char? c)]}
  (prim/typed store "char" (prim/char->bytes c)))

(defn neg "The negative sentinel." [store]
  (prim/typed store "negative" (byte-array 0)))
