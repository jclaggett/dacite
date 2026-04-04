(ns dacite.core
  "Dacite: Content-addressed data with Clojure-native interfaces.

   This is the public API namespace. All constructors, conversion functions,
   and store management are re-exported here for convenience.

     (d/i64 42)              => DaciteScalar
     (d/vec [1 2 3])         => DaciteVector
     (d/str \"hello\")        => DaciteString
     (d/blob bytes)          => DaciteBlob
     (d/hash-map \"a\" 1)     => DaciteMap

   Boundary crossing:
     (d/dac->clj v)          => plain Clojure data (recursive)
     (d/clj->dac data)       => Dacite values (recursive)

   Use `with-store` for isolated store contexts (testing, transactions).
   Otherwise the global store is used automatically."
  (:refer-clojure :exclude [str vec hash-map])
  (:require [dacite.store :as store]
            [dacite.value.types :as types]
            [dacite.value.scalar :as scalar]
            [dacite.value.collections :as coll]
            [dacite.convert :as convert]))

;; =============================================================================
;; Store management
;; =============================================================================

(defmacro with-store
  "Execute body with an isolated store. init can be an IStore or a map
   (which will be wrapped in a mem-store). Returns [snapshot last-value]."
  [[sym init] & body]
  `(store/with-store [~sym ~init] ~@body))

(def reset-store!
  "Reset the global store to empty. Useful for REPL/testing."
  store/reset-store!)

(def set-store!
  "Replace the global store with a new IStore implementation."
  store/set-store!)

;; =============================================================================
;; Protocol
;; =============================================================================

(def IDaciteHash
  "Protocol for extracting the internal hash from a Dacite value."
  types/IDaciteHash)

(def dacite-hash
  "Return the internal hash of a Dacite value."
  types/dacite-hash)

;; =============================================================================
;; Scalar constructors
;; =============================================================================

(def null       scalar/null)
(def bool       scalar/bool)
(def i8         scalar/i8)
(def i16        scalar/i16)
(def i32        scalar/i32)
(def i64        scalar/i64)
(def u8         scalar/u8)
(def u16        scalar/u16)
(def u32        scalar/u32)
(def u64        scalar/u64)
(def u256       scalar/u256)
(def f32        scalar/f32)
(def f64        scalar/f64)
(def dacite-char scalar/dacite-char)
(def neg        scalar/neg)

;; =============================================================================
;; Collection constructors
;; =============================================================================

(def str        coll/str)
(def blob       coll/blob)
(def vec        coll/vec)
(def vec-of-refs coll/vec-of-refs)
(def hash-map   coll/hash-map)
(def dacite-set coll/dacite-set)

;; =============================================================================
;; Wrapping and unwrapping
;; =============================================================================

(def wrap-hash   coll/wrap-hash)
(def unwrap-hash coll/unwrap-hash)

;; =============================================================================
;; Boundary crossing
;; =============================================================================

(def dac->clj   convert/dac->clj)
(def clj->dac   convert/clj->dac)
