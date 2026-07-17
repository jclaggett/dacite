(ns dacite.store.jvm
  "Host-backed content stores for the JVM: LMDB persistence plus the
   LMDB meta database used for durable roots.

   Filesystem store lives in dacite.store.file (no native deps; works on
   babashka). This namespace re-exports file-store for backcompat.

   Intentionally out of the pure portable core so SCI/nbb never loads
   java.nio / LMDB."
  (:require [dacite.store :as store]
            [dacite.store.file :as file]
            [dacite.rooted :as rooted]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.nio ByteBuffer]
           [org.lmdbjava Env EnvFlags Dbi DbiFlags PutFlags]))

(def file-store
  "Re-export of dacite.store.file/file-store for backcompat."
  file/file-store)

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

;; =============================================================================
;; LMDB root cell (IRootCell) — kept here so dacite.rooted stays free of LMDB
;; =============================================================================

(defrecord LmdbRootCell [lmdb k]
  rooted/IRootCell
  (rc-get [_] (lmdb-get-meta lmdb k))
  (rc-put! [this h]
    (lmdb-put-meta! lmdb k h)
    this))

(defn lmdb-root-cell
  "Durable root cell backed by an LMDB store's meta database."
  ([lmdb] (lmdb-root-cell lmdb "root"))
  ([lmdb k] (->LmdbRootCell lmdb k)))
