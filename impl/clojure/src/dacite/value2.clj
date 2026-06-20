(ns dacite.value2
  "Public API for the value2 layer (Chapter 3): a closed set of six user
   value kinds — scalars, vectors, strings, blobs, maps, and sets — built
   on stores (Chapter 1) and fuse (Chapter 2).

   The value2 refactor's defining property: every value carries its own
   store and hash (§3.1). Constructors take the store first; the resulting
   value knows where it belongs, so operations like conj/assoc persist
   transparently into the same store without threading it through.

       (def v (dacite.value2/vector store 1 2 3))
       (dacite-hash v)   ; => [c0 c1 c2 c3]
       (dacite-store v)  ; => the owning store
       (dacite-type v)   ; => \"vector\"
       (->clj v)         ; => content as a plain Clojure value

   Use partial to bind a store for convenience:

       (def vec3 (partial dacite.value2/vector store))
       (vec3 1 2 3)"
  (:refer-clojure :exclude [vector hash-map set])
  (:require [dacite.value2.types :as types]
            [dacite.value2.scalar :as scalar]
            [dacite.value2.collections :as coll]))

;; =============================================================================
;; Accessors
;; =============================================================================

(def dacite-hash  types/dacite-hash)
(def dacite-store types/dacite-store)
(def dacite-type  types/dacite-type)

(def ->clj
  "Realize a Dacite value as a plain Clojure value. Dacite values are
   immutable values (not references), so this conversion is an explicit
   call rather than a deref.

   Scalars realize to their language value. Collections realize to a lazy
   seq of realized elements (sub-collections become nested lazy seqs);
   maps realize to a lazy seq of realized [k v] pairs. Empty collections
   yield nil. The seq is lazy, so only the part you consume is fetched —
   keeping large values partially available."
  types/->clj)

(defn content-hash
  "Strip a value's type tag to recover its data hash (§3.3). Two values of
   different types but identical content share a content hash, in O(1)."
  [v]
  (types/content-hash (dacite-type v) (dacite-hash v)))

(def wrap-hash coll/wrap-hash)

(defn get-value
  "Look up a hash in a store and return the corresponding Dacite value
   (a store-aware wrapper), or nil if the hash is not present."
  [store h]
  (coll/get-value store h))

;; =============================================================================
;; Scalar constructors
;; =============================================================================

(def scalar      scalar/scalar)
(def null        scalar/null)
(def bool        scalar/bool)
(def i8          scalar/i8)
(def i16         scalar/i16)
(def i32         scalar/i32)
(def i64         scalar/i64)
(def u8          scalar/u8)
(def u16         scalar/u16)
(def u32         scalar/u32)
(def u64         scalar/u64)
(def u256        scalar/u256)
(def f32         scalar/f32)
(def f64         scalar/f64)
(def dacite-char scalar/dacite-char)
(def negative    scalar/negative)

;; Generic aliases per the §3.9 table.
(def dac-int   scalar/i64)
(def dac-float scalar/f64)

;; =============================================================================
;; Collection constructors
;; =============================================================================

(def string   coll/string)
(def blob     coll/blob)
(def vector   coll/vector)
(def hash-map coll/hash-map)
(def set      coll/dacite-set)

;; =============================================================================
;; Set operations (§3.5)
;; =============================================================================

(def set-member?    coll/set-member?)
(def set-complement coll/set-complement)
(def set-union      coll/set-union)
(def set-intersect  coll/set-intersect)
(def set-difference coll/set-difference)
