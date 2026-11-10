(ns dacite.store.nbb.lmdb
  "nbb LMDB content store — same on-disk layout as dacite.store.jvm.

   Env directory:
     data.mdb + lock.mdb
     named DBs \"dacite\" (nodes) and \"meta\" (root hashes)

   Keys are 32-byte big-endian hashes. Values are wire-v1 node payloads
   or pack Layer-1 items (same dual codec as dacite.store.jvm).

   Store format tag: meta DB key \"format\", the integer in decimal as UTF-8,
   as in dacite.store.jvm. Written when the content DB is empty and untagged,
   checked on open (`:store-format-mismatch`).

   Requires the npm `lmdb` addon built for LMDB **data format v1**
   (lmdbjava 0.9.x). Prebuilt `lmdb` binaries are format v2 and cannot
   open a JVM store. `npm rebuild` keeps that prebuild: format v1 is a
   from-source compile, and Node loads `build/Release` ahead of it.
   From the repo root:

     npm run lmdb:v1

   Not the default nbb store — file-store stays zero-native."
  (:require [dacite.host :as host]
            [dacite.rooted :as rooted]
            [dacite.store :as store]
            [dacite.store.bytes :as bytes]
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

(defn write-batch
  "Write `pairs` in one LMDB transaction. `pairs` is a map or a
   sequence of [hash value]. Not an IStore operation. A settled promise of `st`."
  [st pairs]
  (.transactionSync (:env st)
                    (fn []
                      (doseq [[h v] pairs]
                        (.putSync (:db st) (hash->buf h)
                                  (js/Buffer.from (bin/encode-store-value v))))))
  (store/completed st))

(defrecord NbbLmdbStore [env db meta-db]
  store/IStore
  (s-get [_ h]
    (store/completed
     (when-let [buf (.get db (hash->buf h))]
       (bin/decode-store-value h buf))))

  (s-put [this h value]
    (.putSync db (hash->buf h) (js/Buffer.from (bin/encode-store-value value)))
    (store/completed this))

  (s-has? [_ h]
    (store/completed (some? (.get db (hash->buf h)))))

  (s-delete [this h]
    (.removeSync db (hash->buf h))
    (store/completed this))

  (s-reset [this]
    (.clearSync db)
    (store/completed this))

  store/ISweep
  (sweep-unmarked [_ live]
    ;; Keys only. Values stay in LMDB and are not returned.
    (let [it (.iterate (.getRange db #js {:keyEncoding "binary"}))
          drop (volatile! [])]
      (loop []
        (let [step (.next it)]
          (when-not (.-done step)
            (let [k (.-key (.-value step))
                  hex (store/hash->hex (buf->hash k))]
              (when-not (contains? live hex)
                (vswap! drop conj (js/Buffer.from k))))
            (recur))))
      (doseq [k @drop]
        (.removeSync db k))
      (store/completed {:removed (count @drop)})))

  store/ILocalStats
  (local-stats [_]
    (let [it (.iterate (.getRange db #js {:keyEncoding "binary"}))]
      (store/completed
       (loop [entries 0 edn 0 wire 0]
         (let [step (.next it)]
           (if (.-done step)
             {:entries entries :edn-bytes edn :wire-bytes wire}
             (let [entry (.-value step)
                   h (buf->hash (.-key entry))
                   v (bin/decode-store-value h (.-value entry))]
               (recur (inc entries)
                      (+ edn (bytes/edn-bytes v))
                      (+ wire (bytes/wire-bytes v)))))))))))

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
    (try
     (let [raw (.get meta (str-buf "format"))
          s (when raw (.toString raw "utf8"))
          n (when s (js/parseInt s 10))
          tag (when s (if (and (not (js/isNaN n)) (= s (str n))) n s))]
      (cond
        (some? tag) (store/check-store-format! path tag)
        (not (.-done (.next (.iterate (.getRange db #js {:keyEncoding "binary" :limit 1})))))
        (throw (store/store-format-mismatch path nil))
        :else (.putSync meta (str-buf "format") (str-buf store/store-format))))
     (catch :default e (.close env) (throw e)))
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
