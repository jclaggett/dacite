(ns dacite.scalar
  "Dacite scalar types and constructors.

   DaciteScalar wraps a content-addressed hash pointing to a typed value
   in the store. Implements IDeref (to unwrap), IHashEq, IFn (zero-arg
   returns deref), and the IDaciteHash protocol.

   Scalar constructors: null, bool, i8-i64, u8-u256, f32, f64, dacite-char."
  (:require [dacite.hash :as hash]
            [dacite.types :as types]
            [dacite.store :as store])
  (:import [clojure.lang IDeref IHashEq IFn]))

;; =============================================================================
;; Private store helpers
;; =============================================================================

(defn- s-get
  "Get a value from the current store by hash."
  [h]
  (store/s-get store/*store* h))

(defn- s-put!
  "Store a typed value. Returns its content-addressed hash."
  [value]
  (let [h (types/typed-value-hash value)]
    (store/s-put store/*store* h value)
    h))

;; =============================================================================
;; DaciteScalar
;; =============================================================================

(deftype DaciteScalar [^:unsynchronized-mutable _hash]
  types/IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_]
    (let [[_type-kw data] (s-get _hash)]
      data))

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

  Object
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteScalar other)
         (= _hash (.-_hash ^DaciteScalar other))))
  (toString [_]
    (let [[type-kw data] (s-get _hash)]
      (pr-str [type-kw data])))

  IFn
  (invoke [this] (deref this)))

;; =============================================================================
;; Scalar constructors
;; =============================================================================

(defn- scalar [type-kw data]
  (->DaciteScalar (s-put! [type-kw data])))

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


