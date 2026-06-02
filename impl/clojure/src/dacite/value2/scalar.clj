(ns dacite.value2.scalar
  "Dacite scalar types and constructors for value2.

   Pure constructors: each takes a store and returns [store' hash].
   Use make-value (from dacite.value2) to create a DaciteValue from the hash."
  (:require [dacite.store :as store]
            [dacite.value2.types :as types]))

;; =============================================================================
;; Private helper
;; =============================================================================

(defn- put-scalar!
  "Store a scalar value in the store. Returns [store' hash]."
  [store type-name data]
  (let [typed-value [type-name data]
        h (types/typed-value-hash typed-value)]
    [(store/s-put store h typed-value) h]))

;; =============================================================================
;; Scalar constructors
;; =============================================================================

(defn null "Create a null value." [store]
  (put-scalar! store "null" nil))

(defn bool "Create a boolean value." [store b]
  (put-scalar! store "bool" b))

(defn i8 "Create an i8 value." [store n]
  (put-scalar! store "i8" (byte n)))

(defn i16 "Create an i16 value." [store n]
  (put-scalar! store "i16" (short n)))

(defn i32 "Create an i32 value." [store n]
  (put-scalar! store "i32" (int n)))

(defn i64 "Create an i64 value." [store n]
  (put-scalar! store "i64" (long n)))

(defn u8 "Create a u8 value." [store n]
  {:pre [(<= 0 n 255)]}
  (put-scalar! store "u8" n))

(defn u16 "Create a u16 value." [store n]
  {:pre [(<= 0 n 65535)]}
  (put-scalar! store "u16" n))

(defn u32 "Create a u32 value." [store n]
  {:pre [(<= 0 n 4294967295)]}
  (put-scalar! store "u32" n))

(defn u64 "Create a u64 value." [store n]
  {:pre [(<= 0 n)]}
  (put-scalar! store "u64" n))

(defn u256
  "Create a u256 value (e.g. hash as data). Data must be 32-byte array."
  [store ^bytes data]
  {:pre [(= 32 (alength data))]}
  (put-scalar! store "u256" data))

(defn f32 "Create an f32 value." [store n]
  (put-scalar! store "f32" (float n)))

(defn f64 "Create an f64 value." [store n]
  (put-scalar! store "f64" (double n)))

(defn dacite-char "Create a char value." [store c]
  {:pre [(char? c)]}
  (put-scalar! store "char" c))

(defn neg "The negative sentinel." [store]
  (put-scalar! store "negative" nil))
