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
            [dacite.value.cache :as cache]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.io File]
           [java.nio ByteBuffer]
           [org.lmdbjava Env EnvFlags Dbi DbiFlags PutFlags]))

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
;; LMDB store
;; =============================================================================

(defn- hash->lmdb-key
  "Convert a 4-long hash to a 32-byte direct ByteBuffer for LMDB key."
  ^ByteBuffer [[a b c d]]
  (let [buf (ByteBuffer/allocateDirect 32)]
    (.putLong buf (long a))
    (.putLong buf (long b))
    (.putLong buf (long c))
    (.putLong buf (long d))
    (.flip buf)))

(defn- lmdb-key->hash
  "Convert a 32-byte LMDB key ByteBuffer back to a 4-long hash."
  [^ByteBuffer buf]
  [(.getLong buf) (.getLong buf) (.getLong buf) (.getLong buf)])

(defn- value->lmdb-val
  "Serialize a store value to a direct ByteBuffer for LMDB."
  ^ByteBuffer [value]
  (let [^bytes bs (.getBytes (pr-str value) "UTF-8")
        buf (ByteBuffer/allocateDirect (alength bs))]
    (.put buf bs)
    (.flip buf)))

(defn- lmdb-val->value
  "Deserialize a LMDB value ByteBuffer to a store value."
  [^ByteBuffer buf]
  (let [bs (byte-array (.remaining buf))]
    (.get buf bs)
    (edn/read-string (String. bs "UTF-8"))))

(defrecord LmdbStore [^Env env ^Dbi db]
  IStore
  (s-get [_ h]
    (with-open [txn (.txnRead env)]
      (when-let [buf (.get db txn (hash->lmdb-key h))]
        (lmdb-val->value buf))))

  (s-put [this h value]
    (let [^ByteBuffer k (hash->lmdb-key h)
          ^ByteBuffer v (value->lmdb-val value)]
      (with-open [txn (.txnWrite env)]
        (.put db txn k v (make-array PutFlags 0))
        (.commit txn)))
    this)

  (s-has? [_ h]
    (with-open [txn (.txnRead env)]
      (some? (.get db txn (hash->lmdb-key h)))))

  (s-snapshot [_]
    (with-open [txn (.txnRead env)]
      (with-open [cursor (.openCursor db txn)]
        (loop [result (transient {})]
          (if (.next cursor)
            (let [k (lmdb-key->hash (.key cursor))
                  v (lmdb-val->value (.val cursor))]
              (recur (assoc! result k v)))
            (persistent! result))))))

  (s-merge [this m]
    (with-open [txn (.txnWrite env)]
      (doseq [[h v] m]
        (let [^ByteBuffer kb (hash->lmdb-key h)
              ^ByteBuffer vb (value->lmdb-val v)]
          (.put db txn kb vb (make-array PutFlags 0))))
      (.commit txn))
    this)

  (s-reset [this]
    (with-open [txn (.txnWrite env)]
      (.drop db txn)
      (.commit txn))
    this))

(defn lmdb-store
  "Create an LMDB-backed content-addressed store with an optional meta db
   for storing root hashes and other metadata.

   path is the directory for the LMDB environment. Created if it
   doesn't exist. Default max size is 1GB.

   Options:
     :max-size  - max database size in bytes (default 1GB)
     :db-name   - database name (default \"dacite\")
     :meta-name - meta database name (default \"meta\")"
  ([path] (lmdb-store path {}))
  ([path {:keys [max-size db-name meta-name]
          :or {max-size (* 1024 1024 1024)
               db-name "dacite"
               meta-name "meta"}}]
   (let [dir (io/file path)]
     (when-not (.exists dir)
       (.mkdirs dir))
     (let [env (-> (Env/create)
                   (.setMapSize max-size)
                   (.setMaxDbs 2)
                   (.open dir (into-array EnvFlags [])))
           db (.openDbi env db-name (into-array DbiFlags [DbiFlags/MDB_CREATE]))
           meta-db (.openDbi env meta-name (into-array DbiFlags [DbiFlags/MDB_CREATE]))]
       (assoc (->LmdbStore env db) :meta-db meta-db)))))

(defn lmdb-get-meta
  "Get a metadata value by string key from the meta db. Returns nil if not found."
  [store ^String key]
  (let [^Env env (:env store)
        ^Dbi meta-db (:meta-db store)
        ^ByteBuffer k (let [bs (.getBytes key "UTF-8")
                            buf (ByteBuffer/allocateDirect (alength bs))]
                        (.put buf bs)
                        (.flip buf))]
    (with-open [txn (.txnRead env)]
      (when-let [buf (.get meta-db txn k)]
        (lmdb-val->value buf)))))

(defn lmdb-put-meta!
  "Put a metadata value by string key into the meta db."
  [store ^String key value]
  (let [^Env env (:env store)
        ^Dbi meta-db (:meta-db store)
        ^ByteBuffer k (let [bs (.getBytes key "UTF-8")
                            buf (ByteBuffer/allocateDirect (alength bs))]
                        (.put buf bs)
                        (.flip buf))
        ^ByteBuffer v (value->lmdb-val value)]
    (with-open [txn (.txnWrite env)]
      (.put meta-db txn k v (make-array PutFlags 0))
      (.commit txn)))
  store)

(defn lmdb-close
  "Close an LMDB store environment. Must be called when done."
  [store]
  (.close ^Env (:env store)))

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

(defn- store->cache-atom
  "Get or create a cache atom for a store. MemStores share their internal atom."
  [store]
  (if (instance? MemStore store)
    (:data store)
    (atom (s-snapshot store))))

(def ^:dynamic *store*
  "Dynamic var holding the current IStore. Initialized with a global
   in-memory store so constructors work without with-store."
  (let [s (mem-store)]
    ;; Bind Layer 2 cache to the MemStore's internal atom
    (alter-var-root #'cache/*cache* (constantly (:data s)))
    s))

(defn reset-store!
  "Reset the global store to empty. Also resets the Layer 2 cache."
  []
  (s-reset *store*)
  (reset! cache/*cache* {}))

(defn set-store!
  "Replace the global store with a new IStore implementation.
   If the store is a MemStore, shares its atom with the cache.
   Otherwise, snapshots the store into a fresh cache atom."
  [new-store]
  (alter-var-root #'*store* (constantly new-store))
  (if (instance? MemStore new-store)
    (alter-var-root #'cache/*cache* (constantly (:data new-store)))
    (alter-var-root #'cache/*cache* (constantly (atom (s-snapshot new-store))))))

(defn get-store
  "Get a value by hash. Checks cache first, falls through to store."
  [h]
  (or (cache/cache-get h)
      (when-let [v (s-get *store* h)]
        (cache/cache-put! h v)
        v)))

(defn put-store!
  "Store a value at hash in both cache and store."
  [h value]
  (cache/cache-put! h value)
  (s-put *store* h value))

(defn snapshot-store
  "Get a plain map snapshot of the cache."
  []
  (cache/cache-snapshot))

(defn merge-store!
  "Merge a map of {hash value} pairs into both cache and store."
  [m]
  (cache/cache-merge! m)
  (s-merge *store* m))

(defmacro bind-store
  "Bind *store* and *cache* together for the duration of body.
   Use this instead of (binding [*store* ...]) to keep layers in sync."
  [store & body]
  `(let [s# ~store]
     (binding [*store* s#
               cache/*cache* (store->cache-atom s#)]
       ~@body)))

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
                   [@cache/*cache* result#]))))
