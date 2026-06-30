(ns dacite.value
  "Public API for the value layer (Chapter 3): a closed set of six user
   value kinds — scalars, vectors, strings, blobs, maps, and sets — built
   on stores (Chapter 1) and fuse (Chapter 2).

   The value refactor's defining property: every value carries its own
   store and hash (§3.1). Most work uses implicit constructors that persist
   into the current store (`dacite.store/*store*`, defaulting to an in-memory
   store for REPL use):

       (def v (dacite.value/vector 1 2 3))
       (dacite-hash v)   ; => [c0 c1 c2 c3]
       (dacite-store v)  ; => the owning store
       (dacite-type v)   ; => \"vector\"
       (realize v)         ; => lazy iterable of realized elements

   When the store must be explicit — tests, migration, multiple stores —
   use the `-with-store` variants:

       (dacite.value/vector-with-store store 1 2 3)

   Bind an isolated store with `dacite.store/with-store` for tests and
   transactions."
  (:refer-clojure :exclude [vector hash-map set])
  (:require [dacite.store :as store]
            [dacite.value.types :as types]
            [dacite.value.scalar :as scalar]
            [dacite.value.collections :as coll]))

;; Implicit constructors use `dacite.store/*store*`. Bind it with
;; `with-store` below (or `store/bind-store`) for isolated contexts.

(defmacro with-store
  "Execute body with an isolated store. init can be an IStore or a map
   (wrapped in a mem-store). Returns [snapshot last-value]."
  [[sym init] & body]
  `(store/with-store [~sym ~init] ~@body))

;; =============================================================================
;; Accessors
;; =============================================================================

(def dacite-hash  types/dacite-hash)
(def dacite-store types/dacite-store)
(def dacite-type  types/dacite-type)

(def realize
  "Expose a Dacite value's content in the host language. Dacite values are
   immutable values (not references), so this conversion is an explicit call
   rather than a deref.

   Scalars yield native values. Collections yield a lazy iterable of realized
   elements (sub-collections become nested lazy iterables); maps yield a lazy
   iterable of realized [k v] pairs. Empty collections yield nil. The iterable
   is lazy, so only the part you consume is fetched — keeping large values
   partially available."
  types/realize)

(defn content-hash
  "Strip a value's type tag to recover its data hash (§3.3). Two values of
   different types but identical content share a content hash, in O(1)."
  [v]
  (types/content-hash (dacite-type v) (dacite-hash v)))

(def wrap-hash coll/wrap-hash)

(defn get-value
  "Look up a hash in the current store and return the corresponding Dacite
   value, or nil if the hash is not present."
  [h]
  (coll/get-value h))

(defn get-value-with-store
  "Look up a hash in an explicit store and return the corresponding Dacite
   value, or nil if the hash is not present."
  [store h]
  (coll/get-value-with-store store h))

;; =============================================================================
;; Scalar constructors (implicit + explicit)
;; =============================================================================

(def scalar              scalar/scalar)
(def scalar-with-store   scalar/scalar-with-store)
(def null                scalar/null)
(def null-with-store     scalar/null-with-store)
(def bool                scalar/bool)
(def bool-with-store     scalar/bool-with-store)
(def i8                  scalar/i8)
(def i8-with-store       scalar/i8-with-store)
(def i16                 scalar/i16)
(def i16-with-store      scalar/i16-with-store)
(def i32                 scalar/i32)
(def i32-with-store      scalar/i32-with-store)
(def i64                 scalar/i64)
(def i64-with-store      scalar/i64-with-store)
(def u8                  scalar/u8)
(def u8-with-store       scalar/u8-with-store)
(def u16                 scalar/u16)
(def u16-with-store      scalar/u16-with-store)
(def u32                 scalar/u32)
(def u32-with-store      scalar/u32-with-store)
(def u64                 scalar/u64)
(def u64-with-store      scalar/u64-with-store)
(def u256                scalar/u256)
(def u256-with-store     scalar/u256-with-store)
(def f32                 scalar/f32)
(def f32-with-store      scalar/f32-with-store)
(def f64                 scalar/f64)
(def f64-with-store      scalar/f64-with-store)
(def dacite-char         scalar/dacite-char)
(def dacite-char-with-store scalar/dacite-char-with-store)
(def negative            scalar/negative)
(def negative-with-store scalar/negative-with-store)
(def negative-sentinel   scalar/negative-sentinel)

;; Generic aliases per the §3.9 table.
(def dac-int   scalar/i64)
(def dac-int-with-store scalar/i64-with-store)
(def dac-float scalar/f64)
(def dac-float-with-store scalar/f64-with-store)

;; =============================================================================
;; Collection constructors (implicit + explicit)
;; =============================================================================

(def string            coll/string)
(def string-with-store coll/string-with-store)
(def blob              coll/blob)
(def blob-with-store   coll/blob-with-store)
(def vector            coll/vector)
(def vector-with-store coll/vector-with-store)
(def hash-map          coll/hash-map)
(def hash-map-with-store coll/hash-map-with-store)
(def set               coll/dacite-set)
(def set-with-store    coll/dacite-set-with-store)

;; =============================================================================
;; Set operations (§3.5)
;; =============================================================================

(def set-member?    coll/set-member?)
(def set-complement coll/set-complement)
(def set-union      coll/set-union)
(def set-intersect  coll/set-intersect)
(def set-difference coll/set-difference)
