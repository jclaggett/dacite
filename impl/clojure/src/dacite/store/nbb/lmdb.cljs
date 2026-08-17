(ns dacite.store.nbb.lmdb
  "nbb LMDB content store — same on-disk layout as dacite.store.jvm.

   Env directory:
     data.mdb + lock.mdb
     named DBs \"dacite\" (nodes) and \"meta\" (root hashes)

   Keys are 32-byte big-endian hashes. Values are wire-v1 **node
   payloads** (dacite.wire.binary), not EDN and not chunk envelopes.

   Requires the npm `lmdb` addon built for LMDB **data format v1**
   (lmdbjava 0.9.x). Prebuilt `lmdb` binaries are format v2 and cannot
   open a JVM store. From the repo root:

     LMDB_DATA_V1=true npm rebuild lmdb

   Not the default nbb store — file-store stays zero-native."
  (:require [dacite.host :as host]
            [dacite.rooted :as rooted]
            [dacite.store :as store]
            [dacite.wire.binary :as bin]))

(def ^:private default-map-size
  "Match dacite.store.jvm/lmdb-store default (1 GiB)."
  (* 1024 1024 1024))

(defn- hash->buf
  "32-byte big-endian Buffer — same bits as lmdbjava hash->bytes."
  [h]
  (js/Buffer.from (clj->js (host/longs->bytes h))))

(defn- buf->hash
  [buf]
  (host/bytes->longs (vec buf)))

(defn- str-buf
  [s]
  (js/Buffer.from (str s) "utf8"))

(defn- open-env
  [path]
  (let [lmdb (js/require "lmdb")]
    (.open lmdb (str path)
           #js {:maxDbs 2
                :mapSize default-map-size
                :encoding "binary"
                :keyEncoding "binary"})))

(defrecord NbbLmdbStore [env db meta-db]
  store/IStore
  (s-get [_ h]
    (when-let [buf (.get db (hash->buf h))]
      (bin/decode-node-bytes buf)))

  (s-put [this h value]
    (.putSync db (hash->buf h) (js/Buffer.from (bin/encode-node-bytes value)))
    this)

  (s-has? [_ h]
    (some? (.get db (hash->buf h))))

  (s-delete [this h]
    (.removeSync db (hash->buf h))
    this)

  (s-snapshot [_]
    ;; Key by hex: BigInt hash words cannot be CLJS/SCI map keys.
    (let [out (volatile! {})
          it (.iterator (.getRange db #js {:keyEncoding "binary"}))]
      (loop []
        (let [step (.next it)]
          (when-not (.-done step)
            (let [e (.-value step)]
              (vswap! out assoc
                      (store/hash->hex (buf->hash (.-key e)))
                      (bin/decode-node-bytes (.-value e))))
            (recur))))
      @out))

  (s-merge [this m]
    (.transactionSync env
                      (fn []
                        (doseq [[h v] m]
                          (.putSync db (hash->buf h)
                                    (js/Buffer.from (bin/encode-node-bytes v))))))
    this)

  (s-reset [this]
    (.clearSync db)
    this))

(defn lmdb-store
  "Open (or create) an LMDB env at `path`. Same named DBs as JVM."
  [path]
  (let [env (open-env path)
        db (.openDB env #js {:name "dacite"
                             :encoding "binary"
                             :keyEncoding "binary"
                             :create true})
        meta (.openDB env #js {:name "meta"
                               :encoding "binary"
                               :keyEncoding "binary"
                               :create true})]
    (->NbbLmdbStore env db meta)))

(defn lmdb-close
  "Close the environment. Further ops are undefined."
  [st]
  (when-let [env (:env st)]
    (.close env))
  nil)

(defn lmdb-get-meta
  "Root hash from the meta DB, or nil."
  [st k]
  (when-let [buf (.get (:meta-db st) (str-buf k))]
    (buf->hash buf)))

(defn lmdb-put-meta!
  [st k h]
  (.putSync (:meta-db st) (str-buf k) (hash->buf h))
  st)

(defrecord LmdbRootCell [lmdb k]
  rooted/IRootCell
  (rc-get [_] (lmdb-get-meta lmdb k))
  (rc-put! [this h]
    (lmdb-put-meta! lmdb k h)
    this))

(defn lmdb-root-cell
  "Durable root cell in the env's meta DB (default key \"root\")."
  ([lmdb] (lmdb-root-cell lmdb "root"))
  ([lmdb k] (->LmdbRootCell lmdb k)))
