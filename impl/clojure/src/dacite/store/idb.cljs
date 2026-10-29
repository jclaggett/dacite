(ns dacite.store.idb
  "IndexedDB content store for a web page.

   One database, two object stores: `nodes` (hex hash → wire-v1 bytes)
   and `meta` (the root hash). `idb-store` is the content dictionary.
   `open` is a rooted store with the same pack-literal flush as `s/file`.

   IndexedDB completes later. Methods throw `:dacite/suspend` with a
   promise, and `drive` runs the operation again. The in-memory cache
   makes the replay settle. `dacite.store.browser/drive` catches the
   same exception, so one drive can cover this store and HTTP together.

   A rooted compare-and-set publishes the new root only on the replay
   that finds the meta write already durable. Publishing earlier would
   make `v/swap!` apply its function twice.

   The cache keeps a bounded number of nodes that this tab has read.
   A miss is remembered only until the current `drive` finishes, so the
   next drive sees a put from another tab or worker. `s-merge` writes
   its batch in one transaction."
  (:require [dacite.rooted :as rooted]
            [dacite.store :as store]
            [dacite.store.chunk :as chunk]
            [dacite.wire.binary :as bin]))

(def ^:private node-store "nodes")
(def ^:private meta-store "meta")
(def ^:private root-key "root")
(def ^:private unset ::unset)

(def ^:private default-cache-limit
  "Nodes kept in memory. Zero means the cache does not evict."
  1024)

(def ^:dynamic *drive-epoch*
  "Identity of the `drive` currently replaying an operation.

   Misses are cached under this epoch so the replay can settle, and a
   later drive does not trust them."
  nil)

(def ^:private max-suspensions
  "Safety cap so a missed cache write cannot retry forever."
  10000)

(defn- present?
  [x]
  (and (some? x) (not (undefined? x)) (not= x "")))

(defn- suspend
  [p]
  (throw (ex-info "indexeddb suspended" {:dacite/suspend p})))

(defn- fail
  [h cause]
  (ex-info "store unavailable"
           {:reason :unavailable :hash h :cause cause}))

(defn- reject-cause
  [p h]
  (.catch p (fn [e]
              (throw (fail h e)))))

(defn- request-promise
  [req]
  (js/Promise.
   (fn [ok err]
     (set! (.-onsuccess req) (fn [_] (ok (.-result req))))
     (set! (.-onerror req) (fn [_] (err (.-error req)))))))

(defn- tx-complete
  [tx]
  (js/Promise.
   (fn [ok err]
     (set! (.-oncomplete tx) (fn [_] (ok nil)))
     (set! (.-onerror tx) (fn [_] (err (.-error tx))))
     (set! (.-onabort tx)
           (fn [_]
             (err (or (.-error tx) (js/Error. "indexeddb aborted"))))))))

(defn- open-db
  [name]
  (js/Promise.
   (fn [ok err]
     (let [req (.open js/indexedDB (str name) 1)]
       (set! (.-onupgradeneeded req)
             (fn [_]
               (let [db (.-result req)
                     names (.-objectStoreNames db)]
                 (when-not (.contains names node-store)
                   (.createObjectStore db node-store))
                 (when-not (.contains names meta-store)
                   (.createObjectStore db meta-store)))))
       (set! (.-onsuccess req) (fn [_] (ok (.-result req))))
       (set! (.-onerror req) (fn [_] (err (.-error req))))))))

(defn- ensure-db
  [st]
  (if-let [db @(:db st)]
    (js/Promise.resolve db)
    (or @(:opening st)
        (let [p (-> (open-db (:name st))
                    (.then (fn [db]
                             (reset! (:db st) db)
                             db)))]
          (reset! (:opening st) p)
          p))))

(defn- idb-get
  [db store-name key]
  (let [tx (.transaction db #js [store-name] "readonly")]
    (request-promise (.get (.objectStore tx store-name) key))))

(defn- idb-put
  [db store-name key value]
  (let [tx (.transaction db #js [store-name] "readwrite")]
    (.put (.objectStore tx store-name) value key)
    (tx-complete tx)))

(defn- idb-put-many
  "One readwrite transaction for a batch of hex-key / wire-byte pairs."
  [db pairs]
  (let [tx (.transaction db #js [node-store] "readwrite")
        os (.objectStore tx node-store)]
    (doseq [[k v] pairs]
      (.put os v k))
    (tx-complete tx)))

(defn- idb-del
  [db store-name key]
  (let [tx (.transaction db #js [store-name] "readwrite")]
    (.delete (.objectStore tx store-name) key)
    (tx-complete tx)))

(defn- idb-clear
  [db]
  (let [tx (.transaction db #js [node-store meta-store] "readwrite")]
    (.clear (.objectStore tx node-store))
    (.clear (.objectStore tx meta-store))
    (tx-complete tx)))

(defn- idb-all
  [db]
  (js/Promise.
   (fn [ok err]
     (let [tx (.transaction db #js [node-store] "readonly")
           req (.openCursor (.objectStore tx node-store))
           acc (volatile! {})]
       (set! (.-onerror req) (fn [_] (err (.-error req))))
       (set! (.-onsuccess req)
             (fn [_]
               (if-let [cur (.-result req)]
                 (do (vswap! acc assoc (.-key cur) (.-value cur))
                     (.continue cur))
                 (ok @acc))))))))

(defn- decode-node
  [h bs]
  (when (present? bs)
    (bin/decode-store-value h (bin/as-wire-bytes bs))))

(defn- hit?
  [v]
  (and (map? v) (contains? v :node)))

(defn- fresh-miss?
  "A miss recorded by the drive that is replaying right now."
  [v]
  (and (map? v)
       (some? *drive-epoch*)
       (= (:miss v) *drive-epoch*)))

(defn- pinned?
  "A cache entry written by the drive now replaying. Evicting it would
   make that replay write again."
  [v]
  (and (some? *drive-epoch*)
       (or (= (:epoch v) *drive-epoch*)
           (= (:miss v) *drive-epoch*))))

(defn- cache-store!
  "Remember `value` without evicting. The IndexedDB callback runs outside
   `drive`'s binding, so eviction happens on the next replay instead."
  [st k value]
  (swap! (:cache st) assoc k value)
  (swap! (:cache-order st)
         (fn [order]
           (conj (into [] (remove #(= k %)) order) k))))

(defn- evict-while!
  "Drop the oldest unpinned entries until the cache is within its limit.

   `keep-key` is the hex key this call is about to use. Evicting it would
   turn a hit into another IndexedDB read. Entries written by the drive
   now replaying stay too: dropping those would make the replay write again."
  [st keep-key]
  (let [limit (:cache-limit st)]
    (when (pos? limit)
      (loop []
        (let [order @(:cache-order st)]
          (when (> (count order) limit)
            (let [victim (first (remove (fn [k]
                                          (or (= k keep-key)
                                              (pinned? (get @(:cache st) k))))
                                        order))]
              (when victim
                (swap! (:cache st) dissoc victim)
                (swap! (:cache-order st)
                       (fn [o] (into [] (remove #(= victim %)) o)))
                (recur)))))))))

(defn- cache-touch!
  [st k]
  (swap! (:cache-order st)
         (fn [order]
           (conj (into [] (remove #(= k %)) order) k)))
  (evict-while! st k))

(defn- keepv
  "Pairs `f` keeps. Defined out here because the IdbStore field `keep`
   shadows clojure.core/keep inside the record methods."
  [f coll]
  (into [] (keep f) coll))

(defrecord IdbStore [name db opening cache cache-order cache-limit
                     snap snap-epoch reset-latch
                     root-ready root durable-cas durable-set keep retained]
  store/IStore
  (s-get [this h]
    (evict-while! this (store/hash->hex h))
    (let [k (store/hash->hex h)
          v (get @cache k ::none)
          epoch *drive-epoch*]
      (cond
        (hit? v)
        (do (cache-touch! this k)
            (store/completed (:node v)))

        (fresh-miss? v)
        (store/completed nil)

        :else
        (suspend
         (reject-cause
          (-> (ensure-db this)
              (.then (fn [db*] (idb-get db* node-store k)))
              (.then (fn [bs]
                       (if-let [node (decode-node h bs)]
                         (cache-store! this k {:node node :epoch epoch})
                         (cache-store! this k {:miss epoch})))))
          h)))))

  (s-put [this h value]
    (evict-while! this (store/hash->hex h))
    (let [k (store/hash->hex h)
          v (get @cache k)
          epoch *drive-epoch*]
      (if (and (hit? v) (= (:node v) value))
        (store/completed this)
        (suspend
         (reject-cause
          (-> (ensure-db this)
              (.then (fn [db*]
                       (idb-put db* node-store k
                                (bin/encode-store-value value))))
              (.then (fn [_]
                       (cache-store! this k {:node value :epoch epoch}))))
          h)))))

  (s-has? [this h]
    (evict-while! this (store/hash->hex h))
    (let [k (store/hash->hex h)
          v (get @cache k ::none)
          epoch *drive-epoch*]
      (cond
        (hit? v)
        (do (cache-touch! this k)
            (store/completed true))

        (fresh-miss? v)
        (store/completed false)

        :else
        (suspend
         (reject-cause
          (-> (ensure-db this)
              (.then (fn [db*] (idb-get db* node-store k)))
              (.then (fn [bs]
                       (if-let [node (decode-node h bs)]
                         (cache-store! this k {:node node :epoch epoch})
                         (cache-store! this k {:miss epoch})))))
          h)))))

  (s-delete [this h]
    (evict-while! this (store/hash->hex h))
    (let [k (store/hash->hex h)
          epoch *drive-epoch*]
      (if (fresh-miss? (get @cache k))
        (store/completed this)
        (suspend
         (reject-cause
          (-> (ensure-db this)
              (.then (fn [db*] (idb-del db* node-store k)))
              (.then (fn [_]
                       (cache-store! this k {:miss epoch}))))
          h)))))

  (s-snapshot [this]
    (if (and (some? *drive-epoch*) (= @snap-epoch *drive-epoch*))
      (store/completed @snap)
      (let [epoch *drive-epoch*]
        (suspend
         (reject-cause
          (-> (ensure-db this)
              (.then idb-all)
              (.then (fn [raw]
                       (let [decoded
                             (into {}
                                   (keepv (fn [[k bs]]
                                            (let [h (store/hex->hash k)
                                                  node (decode-node h bs)]
                                              (when node [k node])))
                                          raw))]
                         (reset! snap decoded)
                         (reset! snap-epoch epoch)
                         (reset! cache {})
                         (reset! cache-order [])
                         (doseq [[k node] decoded]
                           (cache-store! this k {:node node :epoch epoch}))))))
          nil)))))

  (s-merge [this m]
    (evict-while! this nil)
    (let [epoch *drive-epoch*
          pending (keepv (fn [[h v]]
                           (let [k (store/hash->hex h)
                                 cached (get @cache k)]
                             (when-not (and (hit? cached)
                                            (= (:node cached) v))
                               [k v])))
                         m)]
      (if (empty? pending)
        (store/completed this)
        (suspend
         (reject-cause
          (-> (ensure-db this)
              (.then (fn [db*]
                       (idb-put-many db*
                                     (map (fn [[k v]]
                                            [k (bin/encode-store-value v)])
                                          pending))))
              (.then (fn [_]
                       (doseq [[k v] pending]
                         (cache-store! this k {:node v :epoch epoch})))))
          nil)))))

  (s-reset [this]
    (if @reset-latch
      (do (reset! cache {})
          (reset! cache-order [])
          (reset! snap {})
          (reset! snap-epoch nil)
          (reset! reset-latch false)
          (reset! root-ready true)
          (reset! root nil)
          (reset! durable-cas nil)
          (reset! durable-set unset)
          (store/completed this))
      (suspend
       (reject-cause
        (-> (ensure-db this)
            (.then idb-clear)
            (.then (fn [_]
                     (reset! reset-latch true))))
        nil)))))

(defn idb-store
  "Content dictionary in IndexedDB database `name`.

   `:cache-limit` is how many nodes stay in memory. `0` does not evict."
  ([name] (idb-store name {}))
  ([name {:keys [cache-limit] :or {cache-limit default-cache-limit}}]
   (->IdbStore (str name)
               (atom nil)
               (atom nil)
               (atom {})
               (atom [])
               cache-limit
               (atom {})
               (atom nil)
               (atom false)
               (atom false)
               (atom nil)
               (atom nil)
               (atom unset)
               (atom nil)
               (atom false))))

(defn- ensure-root!
  [st]
  (when-not @(:root-ready st)
    (suspend
     (reject-cause
      (-> (ensure-db st)
          (.then (fn [db] (idb-get db meta-store root-key)))
          (.then (fn [hex]
                   (reset! (:root st)
                           (when (present? hex)
                             (store/hex->hash (str hex))))
                   (reset! (:root-ready st) true))))
      nil))))

(defn- write-root!
  [st h done]
  (suspend
   (reject-cause
    (-> (ensure-db st)
        (.then (fn [db]
                 (idb-put db meta-store root-key
                          (if h (store/hash->hex h) ""))))
        (.then (fn [_] (done))))
    h)))

(defrecord IdbRooted [idb content]
  rooted/IRoot
  (-root [_]
    (ensure-root! idb)
    @(:root idb))

  (-cas-root! [_ expected new]
    (ensure-root! idb)
    (cond
      (= @(:durable-cas idb) [expected new])
      (do (when (and new (not @(:retained idb)))
            (chunk/retain-inner! content @(:keep idb))
            (reset! (:retained idb) true))
          (reset! (:root idb) new)
          true)

      (not= @(:root idb) expected)
      false

      :else
      (do (when new
            (let [{:keys [keep]} (chunk/put-reachable! content new)]
              (reset! (:keep idb) keep)
              (reset! (:retained idb) false)))
          (write-root! idb new
                       #(reset! (:durable-cas idb) [expected new])))))

  (-set-root! [this new]
    (ensure-root! idb)
    (cond
      (= @(:root idb) new) new
      (= @(:durable-set idb) new)
      (do (reset! (:root idb) new) new)

      :else
      (do (when new
            (let [{:keys [keep]} (chunk/put-reachable! content new)]
              (reset! (:keep idb) keep)))
          (write-root! idb new #(reset! (:durable-set idb) new)))))

  store/IStore
  (s-get [_ h] (store/s-get content h))
  (s-put [this h value]
    (store/await (store/s-put content h value))
    (store/completed this))
  (s-has? [_ h] (store/s-has? content h))
  (s-delete [this h]
    (store/await (store/s-delete content h))
    (store/completed this))
  (s-snapshot [_] (store/s-snapshot content))
  (s-merge [this m]
    (store/await (store/s-merge content m))
    (store/completed this))
  (s-reset [this]
    (store/await (store/s-reset content))
    (store/completed this)))

(defn open
  "Rooted IndexedDB store. Pack-literal inner; flush on root commit.

   `name` is the database name. Operations run inside `drive`."
  [name]
  (let [inner (idb-store name)]
    (->IdbRooted inner (chunk/chunked inner))))

(defn close
  "Close the database. Further operations are undefined."
  [st]
  (let [idb (if (instance? IdbRooted st) (:idb st) st)]
    (when-let [db @(:db idb)]
      (.close db)
      (reset! (:db idb) nil)
      (reset! (:opening idb) nil)))
  nil)

(defn drive
  "Run `f`, waiting out each IndexedDB suspension and then running it again.

   Returns a promise of `f`'s value. `f` must be synchronous. One epoch
   covers every replay, so a miss found on the first pass settles the next."
  [f]
  (let [steps (atom 0)
        epoch (random-uuid)]
    (js/Promise.
     (fn [ok err]
       (letfn [(step []
                 (if (>= @steps max-suspensions)
                   (err (ex-info "indexeddb drive exceeded suspensions"
                                 {:suspensions max-suspensions}))
                   (do
                     (swap! steps inc)
                     (try
                       (ok (binding [*drive-epoch* epoch] (f)))
                       (catch :default e
                         (if-let [p (:dacite/suspend (ex-data e))]
                           (-> p (.then step) (.catch err))
                           (err e)))))))]
         (step))))))
