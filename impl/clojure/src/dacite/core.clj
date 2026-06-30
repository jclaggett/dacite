(ns dacite.core
  "Dacite: Content-addressed data with Clojure-native interfaces.

   This is the public API namespace. All constructors, conversion functions,
   and store management are re-exported here for convenience. The
   implementation is the value layer (Chapter 3): every value carries its
   own store and hash, and host-language content is recovered with an
   explicit `realize` rather than `deref`.

     (d/i64 42)              => DaciteScalar
     (d/vec [1 2 3])         => DaciteVector
     (d/str \"hello\")        => DaciteString
     (d/blob bytes)          => DaciteBlob
     (d/hash-map \"a\" 1)     => DaciteMap

   Content access:
     (d/realize v)           => host-language value (scalars) or a lazy
                                iterable of realized elements (collections)

   Boundary crossing:
     (d/dac->clj v)          => plain Clojure data (recursive)
     (d/clj->dac data)       => Dacite values (recursive)

   Use `with-store` for isolated store contexts (testing, transactions).
   Otherwise the current store (`dacite.store/*store*`) is used."
  (:refer-clojure :exclude [str vec hash-map])
  (:require [dacite.store :as store]
            [dacite.value :as value]
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
  "Reset the current store to empty. Useful for REPL/testing."
  store/reset-store!)

(def set-store!
  "Replace the current store with a new IStore implementation."
  store/set-store!)

;; =============================================================================
;; Accessors
;; =============================================================================

(def IDaciteValue
  "Protocol implemented by every Dacite value."
  types/IDaciteValue)

(def dacite-hash
  "Return the internal hash of a Dacite value."
  types/dacite-hash)

(def dacite-store
  "Return the store a Dacite value is bound to."
  types/dacite-store)

(def dacite-type
  "Return the type tag string of a Dacite value."
  types/dacite-type)

(def realize
  "Recover a Dacite value's content in the host language. Scalars yield
   native values; collections yield a lazy iterable of realized elements."
  types/realize)

;; =============================================================================
;; Scalar constructors
;; =============================================================================

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
(def neg         scalar/negative)

;; =============================================================================
;; Collection constructors
;; =============================================================================

(def str
  "Create a Dacite string from a Java String."
  coll/string)

(def blob
  "Create a Dacite blob from a byte array."
  coll/blob)

(defn vec
  "Create a Dacite vector from a collection of values (auto-coerced or
   Dacite values)."
  [values]
  (apply coll/vector values))

(defn vec-of-refs
  "Create a Dacite vector from raw hashes already present in the current
   store."
  [refs]
  (coll/vec-of-refs-with-store store/*store* refs))

(def hash-map
  "Create a Dacite map from key/value pairs (auto-coerced or Dacite values)."
  coll/hash-map)

(def dacite-set
  "Create a Dacite set from elements (auto-coerced or Dacite values)."
  coll/dacite-set)

;; =============================================================================
;; Wrapping and unwrapping
;; =============================================================================

(defn wrap-hash
  "Wrap a raw hash (already in the current store) in the appropriate Dacite
   value, dispatching on the stored entry's type."
  [h]
  (value/wrap-hash h))

(defn unwrap-hash
  "Extract the raw hash from a Dacite value."
  [x]
  (if (satisfies? types/IDaciteValue x)
    (types/dacite-hash x)
    (throw (ex-info "Not a Dacite value" {:value x}))))

;; =============================================================================
;; Boundary crossing
;; =============================================================================

(def dac->clj convert/dac->clj)
(def clj->dac convert/clj->dac)
