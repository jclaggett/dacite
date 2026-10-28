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
   make `v/swap!` apply its function twice."
  (:require [dacite.rooted :as rooted]
            [dacite.store :as store]
            [dacite.store.chunk :as chunk]
            [dacite.wire.binary :as bin]))

(def ^:private node-store "nodes")
(def ^:private meta-store "meta")
(def ^:private root-key "root")
(def ^:private absent ::absent)
(def ^:private unset ::unset)

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

(defn- remember!
  [cache h node]
  (swap! cache assoc (store/hash->hex h) (if (some? node) node absent)))

(defn- cached-node
  "Node for a cache hit, or nil when the hit is an absence."
  [v]
  (when-not (= v absent) v))

(defn- decode-node
  [h bs]
  (when (present? bs)
    (bin/decode-store-value h (bin/as-wire-bytes bs))))

(defrecord IdbStore [name db opening cache snap-ready reset-latch
                     root-ready root durable-cas durable-set keep retained]
  store/IStore
  (s-get [this h]
    (let [k (store/hash->hex h)]
      (cond
        (contains? @cache k)
        (store/completed (cached-node (get @cache k)))

        @snap-ready
        (store/completed nil)

        :else
        (suspend
         (reject-cause
          (-> (ensure-db this)
              (.then (fn [db*] (idb-get db* node-store k)))
              (.then (fn [bs]
                       (remember! cache h (decode-node h bs)))))
          h)))))

  (s-put [this h value]
    (let [k (store/hash->hex h)]
      (if (= (get @cache k ::miss) value)
        (store/completed this)
        (suspend
         (reject-cause
          (-> (ensure-db this)
              (.then (fn [db*]
                       (idb-put db* node-store k
                                (bin/encode-store-value value))))
              (.then (fn [_]
                       (swap! cache assoc k value))))
          h)))))

  (s-has? [this h]
    (let [k (store/hash->hex h)]
      (cond
        (contains? @cache k)
        (store/completed (not= (get @cache k) absent))

        @snap-ready
        (store/completed false)

        :else
        (suspend
         (reject-cause
          (-> (ensure-db this)
              (.then (fn [db*] (idb-get db* node-store k)))
              (.then (fn [bs]
                       (remember! cache h (decode-node h bs)))))
          h)))))

  (s-delete [this h]
    (let [k (store/hash->hex h)]
      (if (= (get @cache k ::miss) absent)
        (store/completed this)
        (suspend
         (reject-cause
          (-> (ensure-db this)
              (.then (fn [db*] (idb-del db* node-store k)))
              (.then (fn [_]
                       (swap! cache assoc k absent))))
          h)))))

  (s-snapshot [this]
    (if @snap-ready
      (store/completed
       (into {} (remove (fn [[_ v]] (= v absent))) @cache))
      (suspend
       (reject-cause
        (-> (ensure-db this)
            (.then idb-all)
            (.then (fn [raw]
                     (let [decoded
                           (into {}
                                 (map (fn [[k bs]]
                                        (let [h (store/hex->hash k)]
                                          [k (decode-node h bs)])))
                                 raw)]
                       (swap! cache merge decoded)
                       (reset! snap-ready true)))))
        nil))))

  (s-merge [this m]
    (doseq [[h v] m]
      (store/await (store/s-put this h v)))
    (store/completed this))

  (s-reset [this]
    (if @reset-latch
      (do (reset! cache {})
          (reset! snap-ready true)
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
  "Content dictionary in IndexedDB database `name`."
  [name]
  (->IdbStore (str name)
              (atom nil)
              (atom nil)
              (atom {})
              (atom false)
              (atom false)
              (atom false)
              (atom nil)
              (atom nil)
              (atom unset)
              (atom nil)
              (atom false)))

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

   Returns a promise of `f`'s value. `f` must be synchronous."
  [f]
  (let [steps (atom 0)]
    (js/Promise.
     (fn [ok err]
       (letfn [(step []
                 (if (>= @steps max-suspensions)
                   (err (ex-info "indexeddb drive exceeded suspensions"
                                 {:suspensions max-suspensions}))
                   (do
                     (swap! steps inc)
                     (try
                       (ok (f))
                       (catch :default e
                         (if-let [p (:dacite/suspend (ex-data e))]
                           (-> p (.then step) (.catch err))
                           (err e)))))))]
         (step))))))
