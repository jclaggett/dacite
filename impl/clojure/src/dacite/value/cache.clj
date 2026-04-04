(ns dacite.value.cache
  "Layer 2 cache: a dynamic var holding an atom over a plain map.

   All value-layer code reads/writes through this cache. Layer 3 (stores)
   can rebind *cache* to an atom backed by a store that implements
   Clojure map interfaces, making the value layer store-agnostic.")

;; =============================================================================
;; Cache dynamic var
;; =============================================================================

(def ^:dynamic *cache*
  "Dynamic var holding an atom wrapping a plain map {hash -> value}.
   Layer 3 may rebind this to an atom over a store-backed map."
  (atom {}))

;; =============================================================================
;; Cache operations
;; =============================================================================

(defn cache-get
  "Look up a hash in the current cache."
  [h]
  (get @*cache* h))

(defn cache-put!
  "Store a value at hash in the current cache. Returns the hash."
  [h value]
  (swap! *cache* assoc h value)
  h)

(defn cache-snapshot
  "Return the current cache contents as a plain map."
  []
  @*cache*)

(defn cache-merge!
  "Merge a map of {hash value} pairs into the current cache."
  [m]
  (swap! *cache* merge m))

(defn cache-reset!
  "Reset the current cache to empty."
  []
  (reset! *cache* {}))

;; =============================================================================
;; with-cache macro
;; =============================================================================

(defmacro with-cache
  "Execute body with an isolated cache. init should be a map (or nil for empty).
   Returns [final-cache-map result]."
  [[sym init] & body]
  `(let [cache-atom# (atom (or ~init {}))
         ~sym cache-atom#]
     (binding [*cache* cache-atom#]
       (let [result# (do ~@body)]
         [@*cache* result#]))))
