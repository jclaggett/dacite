(ns dacite.store
  "Content-addressed storage for Dacite values (portable core).

   The IStore protocol defines the minimal interface for value storage.
   All Dacite operations go through this protocol, allowing stores to
   be swapped, layered, or backed by different media without changing
   application code.

   Portable built-in implementations (this namespace):
   - mem-store:     in-memory atom-backed store (default)
   - layered-store: compose stores with read-through / write-through
   - lru-store:     bounded in-memory store (dacite.store.lru)

   Host-backed implementations live outside the portable core:
   - file-store, lmdb-store: dacite.store.jvm (JVM/babashka only)
   - remote-store:            dacite.store.remote (JVM only for now)

   Global store management:
   - *store*:      dynamic var holding the current store
   - with-store:   execute body with an isolated store
   - reset-store!: clear the current store
   - set-store!:   replace the current store"
  (:require [dacite.hash :as hash]))

;; =============================================================================
;; Hash utilities (re-exported from dacite.hash)
;; =============================================================================

(def hash->hex hash/hash->hex)
(def hex->hash hash/hex->hash)

;; =============================================================================
;; Store protocol
;; =============================================================================

(defprotocol IStore
  "Protocol for content-addressed storage."
  (s-get [this h] "Retrieve value by hash. Returns nil if not found.")
  (s-put [this h value] "Store value at hash. Returns the store (or this for mutable stores).")
  (s-has? [this h] "Check if hash exists.")
  (s-delete [this h] "Remove entry at hash. Returns the store.")
  (s-snapshot [this] "Return the current contents as a plain map. Used for bulk reads during construction.")
  (s-merge [this m] "Merge a map of {hash value} pairs into the store.")
  (s-reset [this] "Clear all entries. Returns the store."))

;; =============================================================================
;; In-memory store
;; =============================================================================

(defn ^:private mkey
  "Internal map key for a hash. On JVM a hash is a vector of longs and is
   used directly. On ClojureScript a hash is a vector of BigInts, which
   cannot be a map key (BigInt is a primitive and can't carry the hash uid),
   so we key by the canonical hex string instead. Cross-host storage is
   never compared directly (only root hashes are), so this is internal."
  [h]
  #?(:clj h :cljs (hash/hash->hex h)))

(defrecord MemStore [data]
  IStore
  (s-get [_ h] (get @data (mkey h)))
  (s-put [this h value] (swap! data assoc (mkey h) value) this)
  (s-has? [_ h] (contains? @data (mkey h)))
  (s-delete [this h] (swap! data dissoc (mkey h)) this)
  (s-snapshot [_] @data)
  (s-merge [this m] (swap! data merge m) this)
  (s-reset [this] (reset! data {}) this))

(defn mem-store
  "Create an in-memory content-addressed store."
  ([] (->MemStore (atom {})))
  ([init] (->MemStore (atom init))))

;; =============================================================================
;; Layered store
;; =============================================================================

(defrecord LayeredStore [layers]
  ;; layers is a vector of stores, first = fastest (e.g. mem), last = most durable
  IStore
  (s-get [_ h]
    (loop [seen []
           [layer & more] layers]
      (when layer
        (if-let [v (s-get layer h)]
          (do (doseq [faster seen] (s-put faster h v))
              v)
          (recur (conj seen layer) more)))))

  (s-put [this h value]
    (doseq [layer layers]
      (s-put layer h value))
    this)

  (s-has? [_ h]
    (some #(s-has? % h) layers))

  (s-delete [this h]
    (doseq [layer layers]
      (s-delete layer h))
    this)

  (s-snapshot [_]
    (reduce (fn [acc layer]
              (merge acc (s-snapshot layer)))
            {}
            (reverse layers)))

  (s-merge [this m]
    (doseq [layer layers]
      (s-merge layer m))
    this)

  (s-reset [this]
    (doseq [layer layers]
      (s-reset layer))
    this))

(defn layered-store
  "Create a layered store. Reads fall through from first to last.
   Writes go to all layers.

   Example: (layered-store (mem-store) (file-store \"/tmp/dacite\"))"
  [& layers]
  (->LayeredStore (vec layers)))

;; =============================================================================
;; Current store management
;; =============================================================================

(def ^:dynamic *store*
  "Dynamic var holding the current IStore. Initialized with an in-memory
   store so constructors work without an explicit with-store."
  (mem-store))

(defn reset-store!
  "Reset the current store to empty."
  []
  (s-reset *store*))

(defn set-store!
  "Replace the current store with a new IStore implementation."
  [new-store]
  #?(:clj (alter-var-root #'*store* (constantly new-store))
     :cljs (set! *store* new-store)))

(defn get-store
  "Get a value by hash from the current store. Returns nil if absent."
  [h]
  (s-get *store* h))

(defn put-store!
  "Store a value at hash in the current store."
  [h value]
  (s-put *store* h value))

(defn snapshot-store
  "Return a plain {hash value} map snapshot of the current store."
  []
  (s-snapshot *store*))

(defn merge-store!
  "Merge a map of {hash value} pairs into the current store."
  [m]
  (s-merge *store* m))

(defmacro bind-store
  "Bind *store* to the given store for the duration of body."
  [store & body]
  `(binding [*store* ~store]
     ~@body))

(defmacro with-store
  "Execute body with an isolated store. init can be an IStore or a map
   (which will be wrapped in a mem-store). Returns [snapshot last-value]."
  [[sym init] & body]
  `(let [store# (let [i# ~init]
                  (if (satisfies? IStore i#)
                    i#
                    (mem-store i#)))
         ~sym store#]
     (bind-store store#
                 (let [result# (do ~@body)]
                   [(s-snapshot store#) result#]))))
