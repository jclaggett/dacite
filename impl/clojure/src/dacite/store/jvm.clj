(ns dacite.store.jvm
  "Host-backed content stores for the JVM (and babashka): filesystem and
   LMDB persistence, plus the LMDB meta database used for durable roots.

   These are intentionally kept out of the portable dacite.store core so
   that core can load under SCI hosts (nbb/ClojureScript) that have no
   java.io / java.nio / LMDB. Callers that need durability on the JVM
   require this namespace directly."
  (:require [dacite.store :as store]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.io File]
           [java.nio ByteBuffer]
           [org.lmdbjava Env EnvFlags Dbi DbiFlags PutFlags]))

;; =============================================================================
;; Filesystem store
;; =============================================================================

(defn- hash->path
  "Convert hash to file path with directory sharding."
  [^File base-dir h]
  (let [hex (store/hash->hex h)
        dir1 (subs hex 0 2)
        dir2 (subs hex 2 4)
        filename (str hex ".edn")]
    (io/file base-dir dir1 dir2 filename)))

(defn- ensure-parent-dirs [^File f]
  (let [parent (.getParentFile f)]
    (when-not (.exists parent)
      (.mkdirs parent))))

(defrecord FileStore [base-dir]
  store/IStore
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

  (s-delete [this h]
    (let [f (hash->path base-dir h)]
      (when (.exists f)
        (.delete f)))
    this)

  (s-snapshot [_]
    (let [files (file-seq base-dir)]
      (into {}
            (comp
             (filter #(.isFile ^File %))
             (filter #(.endsWith (.getName ^File %) ".edn"))
             (map (fn [^File f]
                    (let [hex (subs (.getName f) 0 64)
                          h (store/hex->hash hex)
                          v (edn/read-string (slurp f))]
                      [h v]))))
            files)))

  (s-merge [this m]
    (doseq [[h v] m]
      (store/s-put this h v))
    this)

  (s-reset [this]
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
  java.io.Closeable
  (close [_] (.close env))
  store/IStore
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

  (s-delete [this h]
    (with-open [txn (.txnWrite env)]
      (.delete db txn (hash->lmdb-key h))
      (.commit txn))
    this)

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
  (.close ^java.io.Closeable store))
