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

(def ^:const negative-sentinel
  "Clojure value returned by `->clj` for the `\"negative\"` scalar sentinel."
  :dacite/negative)

(deftype DaciteScalar [store _hash]
  types/IDaciteValue
  (dacite-hash [_] _hash)
  (dacite-store [_] store)
  (dacite-type [_] (types/entry-type (store/s-get store _hash)))
  (->clj [_]
    (let [entry (store/s-get store _hash)]
      (if (= "negative" (types/entry-type entry))
        negative-sentinel
        (types/entry-data entry))))

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
;; Constructors — explicit (-with-store) and implicit (*store*)
;; =============================================================================

(defn scalar-with-store
  "Create a typed scalar of an arbitrary type name in an explicit store."
  [store type-name data]
  (->DaciteScalar store (put-scalar! store type-name data)))

(defn scalar
  "Create a typed scalar using the current store (*store*)."
  [type-name data]
  (scalar-with-store store/*store* type-name data))

(defn null-with-store
  "Create a null value in an explicit store."
  [store]
  (scalar-with-store store "null" nil))

(defn null
  "Create a null value using the current store."
  []
  (null-with-store store/*store*))

(defn bool-with-store [store b] (scalar-with-store store "bool" b))
(defn bool [b] (bool-with-store store/*store* b))

(defn i8-with-store  [store n] (scalar-with-store store "i8" (byte n)))
(defn i8  [n] (i8-with-store store/*store* n))

(defn i16-with-store [store n] (scalar-with-store store "i16" (short n)))
(defn i16 [n] (i16-with-store store/*store* n))

(defn i32-with-store [store n] (scalar-with-store store "i32" (int n)))
(defn i32 [n] (i32-with-store store/*store* n))

(defn i64-with-store [store n] (scalar-with-store store "i64" (long n)))
(defn i64 [n] (i64-with-store store/*store* n))

(defn u8-with-store  [store n] {:pre [(<= 0 n 255)]}        (scalar-with-store store "u8" n))
(defn u8  [n] {:pre [(<= 0 n 255)]}        (u8-with-store store/*store* n))

(defn u16-with-store [store n] {:pre [(<= 0 n 65535)]}      (scalar-with-store store "u16" n))
(defn u16 [n] {:pre [(<= 0 n 65535)]}      (u16-with-store store/*store* n))

(defn u32-with-store [store n] {:pre [(<= 0 n 4294967295)]} (scalar-with-store store "u32" n))
(defn u32 [n] {:pre [(<= 0 n 4294967295)]} (u32-with-store store/*store* n))

(defn u64-with-store [store n] {:pre [(<= 0 n)]}            (scalar-with-store store "u64" n))
(defn u64 [n] {:pre [(<= 0 n)]}            (u64-with-store store/*store* n))

(defn u256-with-store
  [store ^bytes data]
  {:pre [(= 32 (alength data))]}
  (scalar-with-store store "u256" data))

(defn u256
  [^bytes data]
  {:pre [(= 32 (alength data))]}
  (u256-with-store store/*store* data))

(defn f32-with-store [store n] (scalar-with-store store "f32" (float n)))
(defn f32 [n] (f32-with-store store/*store* n))

(defn f64-with-store [store n] (scalar-with-store store "f64" (double n)))
(defn f64 [n] (f64-with-store store/*store* n))

(defn dacite-char-with-store
  [store c]
  {:pre [(char? c)]}
  (scalar-with-store store "char" c))

(defn dacite-char
  [c]
  {:pre [(char? c)]}
  (dacite-char-with-store store/*store* c))

(defn negative-with-store
  "The negative sentinel used for negative/cofinite sets (§3.5)."
  [store]
  (scalar-with-store store "negative" nil))

(defn negative
  "The negative sentinel in the current store."
  []
  (negative-with-store store/*store*))

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
