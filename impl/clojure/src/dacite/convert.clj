(ns dacite.convert
  "Boundary crossing between Dacite values and plain Clojure data.

   dac->clj: recursively convert Dacite → Clojure (scalars unwrap,
     strings → String, blobs → byte arrays, vectors → persistent vectors,
     maps → hash maps, sets → sets). Optionally enforces a max-bytes limit
     (default 1 MB) via O(1) size check.

   clj->dac: convert Clojure → Dacite via coerce-and-store! + wrap-hash.

   Both directions operate in the current store (`dacite.store/*store*`)."
  (:require [dacite.store :as store]
            [dacite.value :as value]
            [dacite.value.types :as types]))

;; =============================================================================
;; dac->clj
;; =============================================================================

(def ^:const default-max-bytes
  "Default maximum byte size dac->clj will materialize (1 MB)."
  1048576)

(defn- dac->clj-unsafe
  "Internal recursive converter (no size check). Dispatches on the value's
   Dacite type, materializing each kind into its concrete Clojure form."
  [x]
  (if (satisfies? types/IDaciteValue x)
    (case (types/dacite-type x)
      "vector" (mapv dac->clj-unsafe (seq x))
      "set"    (into #{} (map dac->clj-unsafe) (seq x))
      "map"    (into {} (map (fn [e]
                               [(dac->clj-unsafe (key e))
                                (dac->clj-unsafe (val e))]))
                     (seq x))
      "string" (if-let [cs (types/realize x)] (apply str cs) "")
      "blob"   (byte-array (map unchecked-byte (types/realize x)))
      ;; scalars realize directly to their native value
      (types/realize x))
    x))

;; ^:export for JS interop
(defn ^:export dac->clj
  "Recursively convert a Dacite value to plain Clojure data.
   Scalars unwrap to their raw value, strings to String, blobs to byte
   arrays, vectors to persistent vectors, maps to persistent hash maps,
   sets to persistent sets.

   Optional max-bytes parameter (default 1 MB) limits the total byte
   size that will be materialized. Checked upfront via O(1) dacite-size.
   Throws ex-info if the value exceeds the limit."
  ([x] (dac->clj x default-max-bytes))
  ([x max-bytes]
   (when (satisfies? types/IDaciteValue x)
     (let [entry (store/s-get (types/dacite-store x) (types/dacite-hash x))
           sb (types/dacite-size entry)]
       (when (> sb max-bytes)
         (throw (ex-info (str "dac->clj: value size " sb
                              " bytes exceeds limit of " max-bytes " bytes")
                         {:size-bytes sb :max-bytes max-bytes})))))
   (dac->clj-unsafe x)))

;; =============================================================================
;; clj->dac
;; =============================================================================

(defn clj->dac
  "Convert plain Clojure data to a Dacite value in the current store.
   Scalars, strings, blobs, vectors, maps, and sets are coerced via
   coerce-and-store! and wrapped at the root; already-Dacite values pass
   through unchanged."
  [x]
  (if (satisfies? types/IDaciteValue x)
    x
    (value/wrap-hash (types/coerce-and-store! store/*store* x))))
