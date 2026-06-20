(ns dacite.value2.scalar
  "Dacite scalar values for the value2 layer.

   A scalar is an atomic, typed value with a canonical byte encoding. It
   persists in the store as a [type-name data] tuple at its value hash,
   where value_hash = fuse(type_hash, fuse_bytes(canonical_bytes)).

   DaciteScalar is a store-aware wrapper of [store, hash]: it carries the
   store that created it, so deref and accessors need no global state."
  (:require [dacite.hash :as hash]
            [dacite.store :as store]
            [dacite.value2.types :as types]
            [dacite.value2.render :as render])
  (:import [clojure.lang IHashEq]
           [java.nio ByteBuffer]))

;; =============================================================================
;; Low-level store helper
;; =============================================================================

(defn put-scalar!
  "Persist a typed scalar [type-name data]. Returns its value hash."
  [store type-name data]
  (let [tv [type-name data]
        h (types/scalar-value-hash tv)]
    (store/s-put store h tv)
    h))

;; =============================================================================
;; DaciteScalar
;; =============================================================================

(deftype DaciteScalar [store _hash]
  types/IDaciteValue
  (dacite-hash [_] _hash)
  (dacite-store [_] store)
  (dacite-type [_] (types/entry-type (store/s-get store _hash)))
  (->clj [_] (types/entry-data (store/s-get store _hash)))

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

  Object
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteScalar other)
         (= _hash (.-_hash ^DaciteScalar other))))
  (toString [_] (pr-str (store/s-get store _hash))))

(defn wrap-scalar
  "Wrap a raw hash (already in store) as a DaciteScalar."
  [store h]
  (->DaciteScalar store h))

;; =============================================================================
;; Constructors (store first, per §3.9)
;; =============================================================================

(defn scalar
  "Create a typed scalar of an arbitrary type name."
  [store type-name data]
  (->DaciteScalar store (put-scalar! store type-name data)))

(defn null "Create a null value."    [store]   (scalar store "null" nil))
(defn bool "Create a boolean value." [store b] (scalar store "bool" b))
(defn i8   "Create an i8 value."     [store n] (scalar store "i8" (byte n)))
(defn i16  "Create an i16 value."    [store n] (scalar store "i16" (short n)))
(defn i32  "Create an i32 value."    [store n] (scalar store "i32" (int n)))
(defn i64  "Create an i64 value."    [store n] (scalar store "i64" (long n)))
(defn u8   "Create a u8 value."      [store n] {:pre [(<= 0 n 255)]}        (scalar store "u8" n))
(defn u16  "Create a u16 value."     [store n] {:pre [(<= 0 n 65535)]}      (scalar store "u16" n))
(defn u32  "Create a u32 value."     [store n] {:pre [(<= 0 n 4294967295)]} (scalar store "u32" n))
(defn u64  "Create a u64 value."     [store n] {:pre [(<= 0 n)]}            (scalar store "u64" n))

(defn u256
  "Create a u256 value from a 32-byte array."
  [store ^bytes data]
  {:pre [(= 32 (alength data))]}
  (scalar store "u256" data))

(defn f32 "Create an f32 value." [store n] (scalar store "f32" (float n)))
(defn f64 "Create an f64 value." [store n] (scalar store "f64" (double n)))

(defn dacite-char
  "Create a char value."
  [store c]
  {:pre [(char? c)]}
  (scalar store "char" c))

(defn negative
  "The negative sentinel used for negative/cofinite sets (§3.5)."
  [store]
  (scalar store "negative" nil))

;; =============================================================================
;; Canonical encoding (multimethod)
;; =============================================================================

(defmethod types/encode-value "null" [_]
  (byte-array 0))

(defmethod types/encode-value "bool" [[_ b]]
  (byte-array [(if b (byte 1) (byte 0))]))

(defmethod types/encode-value "i8" [[_ n]]
  (byte-array [(unchecked-byte n)]))

(defmethod types/encode-value "i16" [[_ n]]
  (let [buf (ByteBuffer/allocate 2)] (.putShort buf (short n)) (.array buf)))

(defmethod types/encode-value "i32" [[_ n]]
  (let [buf (ByteBuffer/allocate 4)] (.putInt buf (int n)) (.array buf)))

(defmethod types/encode-value "i64" [[_ n]]
  (let [buf (ByteBuffer/allocate 8)] (.putLong buf (long n)) (.array buf)))

(defmethod types/encode-value "u8" [[_ n]]
  (byte-array [(unchecked-byte n)]))

(defmethod types/encode-value "u16" [[_ n]]
  (let [buf (ByteBuffer/allocate 2)] (.putShort buf (unchecked-short n)) (.array buf)))

(defmethod types/encode-value "u32" [[_ n]]
  (let [buf (ByteBuffer/allocate 4)] (.putInt buf (unchecked-int n)) (.array buf)))

(defmethod types/encode-value "u64" [[_ n]]
  (let [buf (ByteBuffer/allocate 8)] (.putLong buf (unchecked-long n)) (.array buf)))

(defmethod types/encode-value "u256" [[_ ^bytes data]]
  data)

(defmethod types/encode-value "f32" [[_ n]]
  (let [buf (ByteBuffer/allocate 4)] (.putFloat buf (float n)) (.array buf)))

(defmethod types/encode-value "f64" [[_ n]]
  (let [buf (ByteBuffer/allocate 8)] (.putDouble buf (double n)) (.array buf)))

(defmethod types/encode-value "char" [[_ ch]]
  (.getBytes (str ch) "UTF-8"))

(defmethod types/encode-value "negative" [_]
  (byte-array 0))

;; =============================================================================
;; Sizes (multimethod)
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
(defmethod types/dacite-size "negative" [_] 0)

(defmethod print-method DaciteScalar [v w] (render/print-dacite-value v w))
