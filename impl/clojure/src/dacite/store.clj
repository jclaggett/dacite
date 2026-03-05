(ns dacite.store
  "Content-addressed storage for Dacite values.

   The Store protocol defines the minimal interface for value storage.
   All Dacite operations go through this protocol, allowing stores to
   be swapped, layered, or backed by different media (memory, disk,
   network) without changing application code.

   Built-in implementations:
   - mem-store: In-memory atom-backed store (default)
   - file-store: Filesystem persistence with directory sharding
   - layered-store: Compose stores with read-through / write-through

   Global store management:
   - *store*: Dynamic var holding the current store
   - with-store: Execute body with an isolated store
   - reset-store!: Clear the global store
   - set-store!: Replace the global store"
  (:require [dacite.hash :as hash]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.io File]))

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
  (s-snapshot [this] "Return the current contents as a plain map. Used for bulk reads during construction.")
  (s-merge [this m] "Merge a map of {hash value} pairs into the store.")
  (s-reset [this] "Clear all entries. Returns the store."))

;; =============================================================================
;; In-memory store
;; =============================================================================

(defrecord MemStore [data]
  IStore
  (s-get [_ h] (get @data h))
  (s-put [this h value] (swap! data assoc h value) this)
  (s-has? [_ h] (contains? @data h))
  (s-snapshot [_] @data)
  (s-merge [this m] (swap! data merge m) this)
  (s-reset [this] (reset! data {}) this))

(defn mem-store
  "Create an in-memory content-addressed store."
  ([] (->MemStore (atom {})))
  ([init] (->MemStore (atom init))))

;; =============================================================================
;; Filesystem store
;; =============================================================================

(defn- hash->path
  "Convert hash to file path with directory sharding."
  [^File base-dir h]
  (let [hex (hash->hex h)
        dir1 (subs hex 0 2)
        dir2 (subs hex 2 4)
        filename (str hex ".edn")]
    (io/file base-dir dir1 dir2 filename)))

(defn- ensure-parent-dirs [^File f]
  (let [parent (.getParentFile f)]
    (when-not (.exists parent)
      (.mkdirs parent))))

(defrecord FileStore [base-dir]
  IStore
  (s-get [_ h]
    (let [f (hash->path base-dir h)]
      (when (.exists f)
        (edn/read-string (slurp f)))))

  (s-put [this h value]
    (let [f (hash->path base-dir h)]
      (ensure-parent-dirs f)
      (spit f (pr-str value))
      this))

  (s-has? [_ h]
    (.exists (hash->path base-dir h)))

  (s-snapshot [this]
    ;; Expensive: reads all files into memory. Use sparingly.
    (let [files (file-seq base-dir)]
      (into {}
            (comp
             (filter #(.isFile ^File %))
             (filter #(.endsWith (.getName ^File %) ".edn"))
             (map (fn [^File f]
                    (let [hex (subs (.getName f) 0 64)
                          h (hex->hash hex)
                          v (edn/read-string (slurp f))]
                      [h v]))))
            files)))

  (s-merge [this m]
    (doseq [[h v] m]
      (s-put this h v))
    this)

  (s-reset [this]
    ;; Delete all .edn files
    (doseq [^File f (file-seq base-dir)
            :when (and (.isFile f) (.endsWith (.getName f) ".edn"))]
      (.delete f))
    this))

(defn file-store
  "Create a file-based content-addressed store."
  [path]
  (let [dir (io/file path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    (->FileStore dir)))

;; =============================================================================
;; Layered store
;; =============================================================================

(defrecord LayeredStore [layers]
  ;; layers is a vector of stores, first = fastest (e.g. mem), last = most durable
  IStore
  (s-get [_ h]
    (loop [[layer & rest] layers]
      (when layer
        (if-let [v (s-get layer h)]
          ;; TODO: populate faster layers on read-through
          v
          (recur rest)))))

  (s-put [this h value]
    ;; Write to all layers
    (doseq [layer layers]
      (s-put layer h value))
    this)

  (s-has? [_ h]
    (some #(s-has? % h) layers))

  (s-snapshot [_]
    ;; Merge all layers, first layer wins on conflicts
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
  (->LayeredStore (clojure.core/vec layers)))

;; =============================================================================
;; Global store management
;; =============================================================================

(def ^:dynamic *store*
  "Dynamic var holding the current IStore. Initialized with a global
   in-memory store so constructors work without with-store."
  (mem-store))

(defmacro with-store
  "Execute body with an isolated store. init can be an IStore or a map
   (which will be wrapped in a mem-store). Returns [snapshot last-value]."
  [[sym init] & body]
  `(let [~sym (let [i# ~init]
                (if (satisfies? IStore i#)
                  i#
                  (mem-store i#)))]
     (binding [*store* ~sym]
       (let [result# (do ~@body)]
         [(s-snapshot *store*) result#]))))

(defn reset-store!
  "Reset the global store to empty. Useful for REPL/testing."
  []
  (s-reset *store*))

(defn set-store!
  "Replace the global store with a new IStore implementation."
  [new-store]
  (alter-var-root #'*store* (constantly new-store)))

;; =============================================================================
;; Convenience accessors on *store*
;; =============================================================================

(defn get-store
  "Get a value from *store* by hash."
  [h]
  (s-get *store* h))

(defn put-store!
  "Store a value at hash in *store*. Returns the store."
  [h value]
  (s-put *store* h value))

(defn merge-store!
  "Merge a map of {hash value} pairs into *store*."
  [m]
  (s-merge *store* m))

(defn snapshot-store
  "Get a plain map snapshot of *store*."
  []
  (s-snapshot *store*))
