(ns dacite.convert
  "Boundary crossing between Dacite values and plain Clojure data.

   dac->clj: recursively convert Dacite → Clojure (scalars unwrap,
     strings → String, vectors → persistent vectors, maps → hash maps).
     Optionally enforces a max-bytes limit (default 1 MB) via O(1) size check.

   clj->dac: recursively convert Clojure → Dacite (auto-coerces scalars,
     wraps vectors/maps/strings into their Dacite equivalents)."
  (:require [dacite.value.types :as types]
            [dacite.store :as store]
            [dacite.value.scalar :as scalar]
            [dacite.value.collections :as coll])
  (:import [dacite.value.scalar DaciteScalar]
           [dacite.value.collections DaciteString DaciteBlob DaciteVector DaciteMap DaciteSet]))

;; =============================================================================
;; dac->clj
;; =============================================================================

(def ^:const default-max-bytes
  "Default maximum byte size dac->clj will materialize (1 MB)."
  1048576)

(defn- dac->clj-unsafe
  "Internal recursive converter (no size check)."
  [x]
  (cond
    (instance? DaciteScalar x) @x
    (instance? DaciteString x) @x
    (instance? DaciteBlob x)   @x
    (instance? DaciteVector x) (mapv dac->clj-unsafe (seq x))
    (instance? DaciteMap x)    (into {} (map (fn [[k v]]
                                               [(dac->clj-unsafe k)
                                                (dac->clj-unsafe v)]))
                                     (seq x))
    (instance? DaciteSet x)   (into #{} (map dac->clj-unsafe) (seq x))
    :else x))

;; ^:export for JS interop
(defn ^:export dac->clj
  "Recursively convert a Dacite value to plain Clojure data.
   Scalars unwrap to their raw value, strings to String,
   vectors to persistent vectors, maps to persistent hash maps.

   Optional max-bytes parameter (default 1 MB) limits the total byte
   size that will be materialized. Checked upfront via O(1) dacite-size.
   Throws ex-info if the value exceeds the limit."
  ([x] (dac->clj x default-max-bytes))
  ([x max-bytes]
   (when (satisfies? types/IDaciteHash x)
     (let [sb (-> (types/dacite-hash x)
                  store/get-store
                  types/dacite-size)]
       (when (> sb max-bytes)
         (throw (ex-info (str "dac->clj: value size " sb
                              " bytes exceeds limit of " max-bytes " bytes")
                         {:size-bytes sb :max-bytes max-bytes})))))
   (dac->clj-unsafe x)))

;; =============================================================================
;; clj->dac
;; =============================================================================

(defn clj->dac
  "Recursively convert plain Clojure data to Dacite values.
   Vectors become DaciteVector, maps become DaciteMap,
   strings become DaciteString, scalars are auto-coerced."
  [x]
  (cond
    (satisfies? types/IDaciteHash x) x
    (vector? x)              (coll/vec-of-refs (mapv (comp types/dacite-hash clj->dac) x))
    (sequential? x)          (coll/vec-of-refs (mapv (comp types/dacite-hash clj->dac) x))
    (set? x)                 (apply coll/dacite-set (map clj->dac x))
    (map? x)                 (let [pairs (mapcat (fn [[k v]] [(clj->dac k) (clj->dac v)]) x)]
                               (apply coll/hash-map pairs))
    (bytes? x)               (coll/blob x)
    (string? x)              (coll/str x)
    (nil? x)                 (scalar/null)
    (instance? Boolean x)    (scalar/bool x)
    (integer? x)             (scalar/i64 x)
    (float? x)               (scalar/f64 (double x))
    (double? x)              (scalar/f64 x)
    (char? x)                (scalar/dacite-char x)
    :else (throw (ex-info "Cannot convert to Dacite value" {:value x :type (type x)}))))
