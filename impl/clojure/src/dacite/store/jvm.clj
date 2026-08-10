(ns dacite.store.jvm
  "Host-backed content stores for the JVM: LMDB persistence plus the
   LMDB meta database used for durable roots.

   On-disk layout (content DB):
     key   = 32-byte big-endian content hash
     value = wire-v1 **node payload only** (dacite.wire.binary
             encode-node-bytes / decode-node-bytes). No chunk envelope,
             no literals.

   Root meta DB:
     key   = UTF-8 string (default \"root\")
     value = 32-byte big-endian root hash (same word layout as content keys)

   Filesystem store lives in dacite.store.file (no native deps; works on
   babashka; still EDN on disk). This namespace re-exports file-store for
   backcompat.

   Intentionally out of the pure portable core so SCI/nbb never loads
   java.nio / LMDB."
  (:require [dacite.store :as store]
            [dacite.store.file :as file]
            [dacite.rooted :as rooted]
            [dacite.wire.binary :as bin]
            [clojure.java.io :as io])
  (:import [java.nio ByteBuffer]
           [org.lmdbjava Env EnvFlags Dbi DbiFlags PutFlags]))

(def file-store
  "Re-export of dacite.store.file/file-store for backcompat."
  file/file-store)

;; =============================================================================
;; LMDB store
;; =============================================================================

(defn- hash->bytes
  "32-byte big-endian encoding of a hash vector (copy of buffer array)."
  ^bytes [h]
  (let [buf (doto (ByteBuffer/allocate 32)
              (.putLong (long (nth h 0)))
              (.putLong (long (nth h 1)))
              (.putLong (long (nth h 2)))
              (.putLong (long (nth h 3))))]
    (.array buf)))

(defn- bytes->hash
  "Decode 32-byte big-endian hash vector."
  [^bytes bs]
  (when-not (= 32 (alength bs))
    (throw (ex-info "hash must be 32 bytes" {:n (alength bs)})))
  (let [buf (ByteBuffer/wrap bs)]
    [(.getLong buf) (.getLong buf) (.getLong buf) (.getLong buf)]))

(defn- hash->lmdb-key
  "Convert a 4-long hash to a 32-byte direct ByteBuffer for LMDB key."
  ^ByteBuffer [h]
  (let [buf (ByteBuffer/allocateDirect 32)
        ^bytes bs (hash->bytes h)]
    (.put buf bs)
    (.flip buf)))

(defn- lmdb-key->hash
  "Convert a 32-byte LMDB key ByteBuffer back to a 4-long hash."
  [^ByteBuffer buf]
  (let [bs (byte-array (.remaining buf))]
    (.get buf bs)
    (bytes->hash bs)))

(defn- bytes->direct-bb
  "Copy heap bytes into a direct ByteBuffer for LMDB put."
  ^ByteBuffer [^bytes bs]
  (let [buf (ByteBuffer/allocateDirect (alength bs))]
    (.put buf bs)
    (.flip buf)))

(defn- bb->bytes
  "Copy remaining bytes from a ByteBuffer to a heap byte array."
  ^bytes [^ByteBuffer buf]
  (let [bs (byte-array (.remaining buf))]
    (.get buf bs)
    bs))

(defn- entry->lmdb-val
  "Serialize a store entry [type data] as wire-v1 node payload bytes."
  ^ByteBuffer [entry]
  (bytes->direct-bb (bin/encode-node-bytes entry)))

(defn- lmdb-val->entry
  "Deserialize wire-v1 node payload bytes to a store entry."
  [^ByteBuffer buf]
  (bin/decode-node-bytes (bb->bytes buf)))

(defn- string-meta-key
  ^ByteBuffer [^String key]
  (let [^bytes bs (.getBytes key "UTF-8")
        buf (ByteBuffer/allocateDirect (alength bs))]
    (.put buf bs)
    (.flip buf)))

(defrecord LmdbStore [^Env env ^Dbi db]
  java.io.Closeable
  (close [_] (.close env))
  store/IStore
  (s-get [_ h]
    (with-open [txn (.txnRead env)]
      (when-let [buf (.get db txn (hash->lmdb-key h))]
        (lmdb-val->entry buf))))

  (s-put [this h value]
    (let [^ByteBuffer k (hash->lmdb-key h)
          ^ByteBuffer v (entry->lmdb-val value)]
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
                  v (lmdb-val->entry (.val cursor))]
              (recur (assoc! result k v)))
            (persistent! result))))))

  (s-merge [this m]
    (with-open [txn (.txnWrite env)]
      (doseq [[h v] m]
        (let [^ByteBuffer kb (hash->lmdb-key h)
              ^ByteBuffer vb (entry->lmdb-val v)]
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

   Content values are wire-v1 node payloads (see ns docstring).

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
  "Get a root hash (4-word vector) from the meta db by string key, or nil."
  [store ^String key]
  (let [^Env env (:env store)
        ^Dbi meta-db (:meta-db store)
        ^ByteBuffer k (string-meta-key key)]
    (with-open [txn (.txnRead env)]
      (when-let [buf (.get meta-db txn k)]
        (let [^bytes bs (bb->bytes buf)]
          (bytes->hash bs))))))

(defn lmdb-put-meta!
  "Put a root hash (4-word vector) into the meta db under string key.
   value must be a hash vector (not a store entry)."
  [store ^String key h]
  (let [^Env env (:env store)
        ^Dbi meta-db (:meta-db store)
        ^ByteBuffer k (string-meta-key key)
        ^ByteBuffer v (hash->lmdb-key h)]
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
  "Durable root cell backed by an LMDB store's meta database.
   Values are 32-byte raw hashes (not wire node payloads)."
  ([lmdb] (lmdb-root-cell lmdb "root"))
  ([lmdb k] (->LmdbRootCell lmdb k)))
