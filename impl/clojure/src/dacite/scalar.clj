(ns dacite.scalar
  "Dacite scalar types and constructors.

   DaciteScalar wraps a content-addressed hash pointing to a typed value
   in the store. Implements IDeref (to unwrap), IHashEq, IFn (zero-arg
   returns deref), and the IDaciteHash protocol.

   Scalar constructors: null, bool, i8-i64, u8-u256, f32, f64, dacite-char."
  (:require [dacite.hash :as hash]
            [dacite.types :as types]
            [dacite.store :as store])
  (:import [clojure.lang IDeref IHashEq IFn]
           [java.nio ByteBuffer]))

;; =============================================================================
;; Private store helper
;; =============================================================================

(defn- put-typed!
  "Store a typed value. Returns its content-addressed hash."
  [value]
  (let [h (types/typed-value-hash value)]
    (store/put-store! h value)
    h))

;; =============================================================================
;; DaciteScalar
;; =============================================================================

(deftype DaciteScalar [^:unsynchronized-mutable _hash]
  types/IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_]
    (let [[_type-kw data] (store/get-store _hash)]
      data))

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

  Object
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteScalar other)
         (= _hash (.-_hash ^DaciteScalar other))))
  (toString [_]
    (let [[type-kw data] (store/get-store _hash)]
      (pr-str [type-kw data])))

  IFn
  (invoke [this] (deref this)))

;; =============================================================================
;; Scalar constructors
;; =============================================================================

(defn- scalar [type-kw data]
  (->DaciteScalar (put-typed! [type-kw data])))

(defn null "Create a null value."    []  (scalar "null" nil))
(defn bool "Create a boolean value." [b] (scalar "bool" b))
(defn i8   "Create an i8 value."     [n] (scalar "i8" (byte n)))
(defn i16  "Create an i16 value."    [n] (scalar "i16" (short n)))
(defn i32  "Create an i32 value."    [n] (scalar "i32" (int n)))
(defn i64  "Create an i64 value."    [n] (scalar "i64" (long n)))
(defn u8   "Create a u8 value."      [n] {:pre [(<= 0 n 255)]}        (scalar "u8" n))
(defn u16  "Create a u16 value."     [n] {:pre [(<= 0 n 65535)]}      (scalar "u16" n))
(defn u32  "Create a u32 value."     [n] {:pre [(<= 0 n 4294967295)]} (scalar "u32" n))
(defn u64  "Create a u64 value."     [n] {:pre [(<= 0 n)]}            (scalar "u64" n))

(defn u256
  "Create a u256 value (e.g. hash as data). Data must be 32-byte array."
  [^bytes data]
  {:pre [(= 32 (alength data))]}
  (scalar "u256" data))

(defn f32  "Create an f32 value."    [n] (scalar "f32" (float n)))
(defn f64  "Create an f64 value."    [n] (scalar "f64" (double n)))

(defn dacite-char
  "Create a char value."
  [c]
  {:pre [(char? c)]}
  (scalar "char" c))

;; =============================================================================
;; Factory (for use by other namespaces that need to construct from raw hash)
;; =============================================================================

(defn wrap-scalar
  "Construct a DaciteScalar from a raw hash (already in store)."
  [h]
  (->DaciteScalar h))

;; =============================================================================
;; dacite-size implementations for scalar types
;; =============================================================================

(defmethod types/dacite-size "null" [_] 0)
(defmethod types/dacite-size "bool" [_] 1)
(defmethod types/dacite-size "i8" [_] 1)
(defmethod types/dacite-size "i16" [_] 2)
(defmethod types/dacite-size "i32" [_] 4)
(defmethod types/dacite-size "i64" [_] 8)
(defmethod types/dacite-size "i128" [_] 16)
(defmethod types/dacite-size "i256" [_] 32)
(defmethod types/dacite-size "u8" [_] 1)
(defmethod types/dacite-size "u16" [_] 2)
(defmethod types/dacite-size "u32" [_] 4)
(defmethod types/dacite-size "u64" [_] 8)
(defmethod types/dacite-size "u128" [_] 16)
(defmethod types/dacite-size "u256" [_] 32)
(defmethod types/dacite-size "f32" [_] 4)
(defmethod types/dacite-size "f64" [_] 8)
(defmethod types/dacite-size "char" [[_ ch]]
  (count (.getBytes (str ch) "UTF-8")))

;; =============================================================================
;; encode-value implementations for scalar types
;; =============================================================================

(defmethod types/encode-value "null" [_]
  (byte-array 0))

(defmethod types/encode-value "bool" [[_ b]]
  (byte-array [(if b (byte 1) (byte 0))]))

(defmethod types/encode-value "i8" [[_ n]]
  (byte-array [(unchecked-byte n)]))

(defmethod types/encode-value "i16" [[_ n]]
  (let [buf (ByteBuffer/allocate 2)]
    (.putShort buf (short n))
    (.array buf)))

(defmethod types/encode-value "i32" [[_ n]]
  (let [buf (ByteBuffer/allocate 4)]
    (.putInt buf (int n))
    (.array buf)))

(defmethod types/encode-value "i64" [[_ n]]
  (let [buf (ByteBuffer/allocate 8)]
    (.putLong buf (long n))
    (.array buf)))

(defmethod types/encode-value "u8" [[_ n]]
  (byte-array [(unchecked-byte n)]))

(defmethod types/encode-value "u16" [[_ n]]
  (let [buf (ByteBuffer/allocate 2)]
    (.putShort buf (unchecked-short n))
    (.array buf)))

(defmethod types/encode-value "u32" [[_ n]]
  (let [buf (ByteBuffer/allocate 4)]
    (.putInt buf (unchecked-int n))
    (.array buf)))

(defmethod types/encode-value "u64" [[_ n]]
  (let [buf (ByteBuffer/allocate 8)]
    (.putLong buf (unchecked-long n))
    (.array buf)))

(defmethod types/encode-value "u256" [[_ ^bytes data]]
  data)

(defmethod types/encode-value "f32" [[_ n]]
  (let [buf (ByteBuffer/allocate 4)]
    (.putFloat buf (float n))
    (.array buf)))

(defmethod types/encode-value "f64" [[_ n]]
  (let [buf (ByteBuffer/allocate 8)]
    (.putDouble buf (double n))
    (.array buf)))

(defmethod types/encode-value "char" [[_ ch]]
  (.getBytes (str ch) "UTF-8"))
