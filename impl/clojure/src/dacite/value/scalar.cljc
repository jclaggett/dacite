(ns dacite.value.scalar
  "Dacite scalar values for the value layer.

   A scalar is an atomic, typed value with a canonical byte encoding. It
   persists in the store as a [type-name data] tuple at its value hash,
   where value_hash = fuse(type_hash, fuse_bytes(canonical_bytes)).

   DaciteScalar is a store-aware wrapper of [store, hash]: it carries the
   store that created it, so deref and accessors need no global state.

   Portable core: the value protocol, encodings (via dacite.host), and
   coercions work on every host. The native clojure.lang.* interfaces
   (hasheq/equals/toString) are a JVM-only adapter guarded by reader
   conditionals; SCI hosts use dacite.value.api for equality etc."
  (:require [dacite.host :as host]
            [dacite.store :as store]
            [dacite.value.types :as types]
            #?@(:bb [] :clj [[dacite.hash :as hash]
                             [dacite.value.render :as render]]))
  #?@(:bb [] :clj [(:import [clojure.lang IHashEq])]))

;; =============================================================================
;; Host numeric casts (portable no-ops on JS; native casts on JVM)
;; =============================================================================

(defn- as-i8 [n] #?(:clj (byte n) :cljs n))
(defn- as-i16 [n] #?(:clj (short n) :cljs n))
(defn- as-i32 [n] #?(:clj (int n) :cljs n))
(defn- as-i64 [n] #?(:clj (long n) :cljs n))
(defn- as-f32 [n] #?(:clj (float n) :cljs n))
(defn- as-f64 [n] #?(:clj (double n) :cljs n))

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

(def negative-sentinel
  "Host-language value returned by `realize` for the `\"negative\"` scalar sentinel."
  :dacite/negative)

(deftype DaciteScalar [store _hash]
  types/IDaciteValue
  (dacite-hash [_] _hash)
  (dacite-store [_] store)
  (dacite-type [_] (types/entry-type (store/s-get store _hash)))
  (realize [_]
    (let [entry (store/s-get store _hash)]
      (if (= "negative" (types/entry-type entry))
        negative-sentinel
        (types/entry-data entry))))

  #?@(:bb []
      :clj
      [IHashEq
       (hasheq [_] (hash/hash->int _hash))

       Object
       (hashCode [_] (hash/hash->int _hash))
       (equals [_ other]
               (and (instance? DaciteScalar other)
                    (= _hash (.-_hash ^DaciteScalar other))))
       (toString [_] (pr-str (store/s-get store _hash)))]))

(defn wrap-scalar
  "Wrap a raw hash (already in store) as a DaciteScalar."
  [store h]
  (->DaciteScalar store h))

(defmethod types/wrap-entry :default
  [_type-name store h]
  (wrap-scalar store h))

(defmethod types/coerce-and-store! :null
  [store _]
  (put-scalar! store "null" nil))

(defmethod types/coerce-and-store! :bool
  [store x]
  (put-scalar! store "bool" x))

(defmethod types/coerce-and-store! :char
  [store x]
  (put-scalar! store "char" x))

(defmethod types/coerce-and-store! :i64
  [store x]
  (put-scalar! store "i64" (as-i64 x)))

(defmethod types/coerce-and-store! :f64
  [store x]
  (put-scalar! store "f64" (as-f64 x)))

(defmethod types/coerce-and-store! :double
  [store x]
  (put-scalar! store "f64" (as-f64 x)))

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

(defn i8-with-store  [store n] (scalar-with-store store "i8" (as-i8 n)))
(defn i8  [n] (i8-with-store store/*store* n))

(defn i16-with-store [store n] (scalar-with-store store "i16" (as-i16 n)))
(defn i16 [n] (i16-with-store store/*store* n))

(defn i32-with-store [store n] (scalar-with-store store "i32" (as-i32 n)))
(defn i32 [n] (i32-with-store store/*store* n))

(defn i64-with-store [store n] (scalar-with-store store "i64" (as-i64 n)))
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
  [store data]
  {:pre [(= 32 (alength data))]}
  (scalar-with-store store "u256" data))

(defn u256
  [data]
  {:pre [(= 32 (alength data))]}
  (u256-with-store store/*store* data))

(defn f32-with-store [store n] (scalar-with-store store "f32" (as-f32 n)))
(defn f32 [n] (f32-with-store store/*store* n))

(defn f64-with-store [store n] (scalar-with-store store "f64" (as-f64 n)))
(defn f64 [n] (f64-with-store store/*store* n))

(defn dacite-char-with-store
  [store c]
  {:pre [#?(:clj (char? c) :cljs (string? c))]}
  (scalar-with-store store "char" c))

(defn dacite-char
  [c]
  {:pre [#?(:clj (char? c) :cljs (string? c))]}
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
;; Constructors relative to a peer (*-via) — use peer’s store
;; =============================================================================

(defn scalar-via
  "Typed scalar allocated in the store of `peer` (value, root-ref, or IStore)."
  [peer type-name data]
  (scalar-with-store (types/store-of peer) type-name data))

(defn null-via
  [peer]
  (null-with-store (types/store-of peer)))

(defn bool-via [peer b] (bool-with-store (types/store-of peer) b))
(defn i8-via  [peer n] (i8-with-store  (types/store-of peer) n))
(defn i16-via [peer n] (i16-with-store (types/store-of peer) n))
(defn i32-via [peer n] (i32-with-store (types/store-of peer) n))
(defn i64-via [peer n] (i64-with-store (types/store-of peer) n))
(defn u8-via  [peer n] (u8-with-store  (types/store-of peer) n))
(defn u16-via [peer n] (u16-with-store (types/store-of peer) n))
(defn u32-via [peer n] (u32-with-store (types/store-of peer) n))
(defn u64-via [peer n] (u64-with-store (types/store-of peer) n))
(defn u256-via [peer data] (u256-with-store (types/store-of peer) data))
(defn f32-via [peer n] (f32-with-store (types/store-of peer) n))
(defn f64-via [peer n] (f64-with-store (types/store-of peer) n))
(defn dacite-char-via [peer c] (dacite-char-with-store (types/store-of peer) c))
(defn negative-via [peer] (negative-with-store (types/store-of peer)))

;; =============================================================================
;; Canonical encoding (multimethod) — portable bytes (vectors of ints 0..255)
;; =============================================================================

(defmethod types/encode-value "null" [_] [])

(defmethod types/encode-value "bool" [[_ b]] [(if b 1 0)])

(defmethod types/encode-value "i8"  [[_ n]] (host/int->bytes-be n 1))
(defmethod types/encode-value "i16" [[_ n]] (host/int->bytes-be n 2))
(defmethod types/encode-value "i32" [[_ n]] (host/int->bytes-be n 4))
(defmethod types/encode-value "i64" [[_ n]] (host/int->bytes-be n 8))

(defmethod types/encode-value "u8"  [[_ n]] (host/int->bytes-be n 1))
(defmethod types/encode-value "u16" [[_ n]] (host/int->bytes-be n 2))
(defmethod types/encode-value "u32" [[_ n]] (host/int->bytes-be n 4))
(defmethod types/encode-value "u64" [[_ n]] (host/int->bytes-be n 8))

(defmethod types/encode-value "u256" [[_ data]] data)

(defmethod types/encode-value "f32" [[_ n]] (host/f32->bytes n))
(defmethod types/encode-value "f64" [[_ n]] (host/f64->bytes n))

(defmethod types/encode-value "char" [[_ ch]] (host/utf8-bytes (str ch)))

(defmethod types/encode-value "negative" [_] [])

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
  (count (host/utf8-bytes (str ch))))
(defmethod types/dacite-size "negative" [_] 0)

#?(:bb nil
   :clj
   (defmethod print-method DaciteScalar [v w] (render/print-dacite-value v w)))
