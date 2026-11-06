(ns dacite.store.idb-test
  "IndexedDB store against fake-indexeddb. Run from the repo root:

     npm install
     npx nbb -cp impl/clojure/src:impl/clojure/test -m dacite.store.idb-test"
  (:require [dacite.peer.client :as client]
            [dacite.peer.client-checks :as cc]
            [dacite.peer.msg :as msg]
            [dacite.peer.responder :as responder]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.store.client-cache :as cache]
            [dacite.store.idb :as idb]
            [dacite.value :as v]
            [dacite.rooted :as rs]))

(defn- fail!
  [msg data]
  (throw (ex-info msg data)))

(defn- eq!
  [actual expected msg]
  (when-not (= actual expected)
    (fail! msg {:actual actual :expected expected})))

(defn- db-name
  []
  (str "dacite-idb-" (.toString (random-uuid))))

(defn- roundtrip
  []
  (let [name (db-name)
        mem (store/mem-store)
        node-v (v/i64 mem 42)
        h (v/hash node-v)
        node (store/await (store/s-get mem h))
        st (idb/idb-store name)]
    (-> (idb/drive
         (fn []
           (store/await (store/s-put st h node))
           (eq! (store/await (store/s-get st h)) node "get after put")
           (eq! (store/await (store/s-has? st h)) true "has after put")
           (eq! (store/await (store/s-get st [1 2 3 4])) nil "absent is nil")
           (eq! (store/await (store/s-has? st h)) true "the node is stored")
           (eq! (:entries (store/await (store/local-stats st))) 1 "one entry")))
        (.then (fn []
                 (idb/drive
                  (fn []
                    (store/await (store/s-delete st h))
                    (eq! (store/await (store/s-has? st h)) false "deleted")))))
        (.then (fn [_]
                 (idb/close st)
                 (let [again (idb/idb-store name)]
                   (-> (idb/drive
                        (fn []
                          (eq! (store/await (store/s-get again h)) nil
                               "delete survived reopen")))
                       (.then (fn [_] (idb/close again))))))))))

(defn- put-survives-reopen
  []
  (let [name (db-name)
        mem (store/mem-store)
        node-v (v/i64 mem 7)
        h (v/hash node-v)
        node (store/await (store/s-get mem h))
        st (idb/idb-store name)]
    (-> (idb/drive #(store/await (store/s-put st h node)))
        (.then (fn [_]
                 (idb/close st)
                 (let [again (idb/idb-store name)]
                   (-> (idb/drive
                        (fn []
                          (eq! (store/await (store/s-get again h)) node
                               "put survived reopen")))
                       (.then (fn [_] (idb/close again))))))))))

(defn- rooted-swap
  []
  (let [name (db-name)
        rs (idb/open name)]
    (-> (idb/drive
         (fn []
           (let [r (v/root rs)]
             (v/swap! r (fn [_] (v/i64 r 42)))
             (eq! (v/native (v/deref r)) 42 "swap published 42")
             (eq! (some? (rs/root rs)) true "root hash is set"))))
        (.then (fn [_]
                 (idb/close rs)
                 (let [again (idb/open name)]
                   (-> (idb/drive
                        (fn []
                          (eq! (v/native (v/deref (v/root again))) 42
                               "root survived reopen")))
                       (.then (fn [_] (idb/close again))))))))))

(defn- cache-in-front
  []
  (let [name (db-name)
        local (idb/idb-store name)
        remote (store/mem-store)
        node-v (v/i64 remote 9)
        h (v/hash node-v)
        layered (cache/->SmartCacheStore local remote)]
    (-> (idb/drive
         (fn []
           (eq! (store/await (store/s-get layered h))
                (store/await (store/s-get remote h))
                "miss copies the remote node into IndexedDB")))
        (.then (fn [_]
                 (idb/close local)
                 (let [again (idb/idb-store name)]
                   (-> (idb/drive
                        (fn []
                          (eq! (store/await (store/s-get again h))
                               (store/await (store/s-get remote h))
                               "copied node survived reopen")))
                       (.then (fn [_] (idb/close again))))))))))

(defn- counting-transactions
  "Run `f` with an atom that counts IndexedDB transactions, then restore
   the database prototype."
  [f]
  (let [proto (.-prototype js/IDBDatabase)
        orig (.-transaction proto)
        n (atom 0)]
    (set! (.-transaction proto)
          (fn [names mode]
            ;; The versionchange transaction is the schema upgrade on open.
            (this-as db
                     (when-not (= (str mode) "versionchange")
                       (swap! n inc))
                     (js/Reflect.apply orig db (js/Array. names mode)))))
    (-> (f n)
        (.finally (fn []
                    (set! (.-transaction proto) orig))))))

(defn- batch-merge
  []
  (counting-transactions
   (fn [n]
     (let [name (db-name)
           mem (store/mem-store)
           nodes (mapv (fn [i]
                         (let [h (v/hash (v/i64 mem i))]
                           [h (store/await (store/s-get mem h))]))
                       [1 2 3])
           st (idb/idb-store name {:cache-limit 1})]
       (-> (idb/drive
            (fn []
              (store/await (store/s-merge st (into {} nodes)))))
           (.then (fn [_]
                    (eq! @n 1 "merge uses one transaction")
                    (idb/close st)
                    (let [again (idb/idb-store name)]
                      (-> (idb/drive
                           (fn []
                             (doseq [[h node] nodes]
                               (eq! (store/await (store/s-get again h)) node
                                    "merged node survived reopen"))))
                          (.then (fn [_] (idb/close again))))))))))))

(defn- cache-evicts
  []
  (let [name (db-name)
        mem (store/mem-store)
        a (let [h (v/hash (v/i64 mem 1))]
            [h (store/await (store/s-get mem h))])
        b (let [h (v/hash (v/i64 mem 2))]
            [h (store/await (store/s-get mem h))])
        st (idb/idb-store name {:cache-limit 1})]
    (counting-transactions
     (fn [n]
       (-> (idb/drive #(store/await (store/s-put st (first a) (second a))))
           (.then (fn [_]
                    (idb/drive #(store/await (store/s-put st (first b) (second b))))))
           (.then (fn [_]
                    (reset! n 0)
                    (idb/drive
                     (fn []
                       (eq! (store/await (store/s-get st (first b))) (second b)
                            "newest node stays cached")
                       (eq! (store/await (store/s-get st (first a))) (second a)
                            "evicted node is read back")
                       (eq! @n 1 "only the evicted node hits IndexedDB")))))
           (.then (fn [_] (idb/close st))))))))

(defn- another-writer-is-visible
  []
  (let [name (db-name)
        mem (store/mem-store)
        h (v/hash (v/i64 mem 5))
        node (store/await (store/s-get mem h))
        reader (idb/idb-store name)
        writer (idb/idb-store name)]
    (-> (idb/drive
         (fn []
           (eq! (store/await (store/s-get reader h)) nil "reader starts empty")))
        (.then (fn [_]
                 (idb/drive #(store/await (store/s-put writer h node)))))
        (.then (fn [_]
                 (idb/drive
                  (fn []
                    (eq! (store/await (store/s-get reader h)) node
                         "a later drive sees the other writer's put")))))
        (.then (fn [_]
                 (idb/close reader)
                 (idb/close writer))))))

;; ---------------------------------------------------------------------------
;; Store format tag: meta key "format" holds store/store-format
;; ---------------------------------------------------------------------------

(defn- raw-open
  "The database `name` through the raw IndexedDB API (version 1, nodes + meta)."
  [name]
  (js/Promise.
   (fn [ok err]
     (let [req (.open js/indexedDB name 1)]
       (set! (.-onupgradeneeded req)
             (fn [_]
               (let [db (.-result req)]
                 (.createObjectStore db "nodes")
                 (.createObjectStore db "meta"))))
       (set! (.-onsuccess req) (fn [_] (ok (.-result req))))
       (set! (.-onerror req) (fn [_] (err (.-error req))))))))

(defn- raw-get
  [db store-name k]
  (js/Promise.
   (fn [ok err]
     (let [req (.get (.objectStore (.transaction db #js [store-name] "readonly") store-name) k)]
       (set! (.-onsuccess req) (fn [_] (ok (.-result req))))
       (set! (.-onerror req) (fn [_] (err (.-error req))))))))

(defn- raw-put
  [db store-name k v]
  (js/Promise.
   (fn [ok err]
     (let [tx (.transaction db #js [store-name] "readwrite")]
       (.put (.objectStore tx store-name) v k)
       (set! (.-oncomplete tx) (fn [_] (ok nil)))
       (set! (.-onerror tx) (fn [_] (err (.-error tx))))))))

(defn- mismatch-of
  "Promise of the ex-data of the :store-format-mismatch that `drive`ing `f`
   rejects with. Fails if it resolves, or rejects with anything else."
  [f]
  (.then (idb/drive f)
         (fn [_] (fail! "expected a store format mismatch, got a result" {}))
         (fn [e]
           ;; under SCI (nbb) the error may arrive wrapped, with the original as its cause
           (let [err (or (some #(when (= :store-format-mismatch (:reason (ex-data %))) %)
                               (take-while some? (iterate ex-cause e)))
                         e)
                 d (ex-data err)]
             (eq! (:reason d) :store-format-mismatch
                  (str "reason (not wrapped as unavailable): " (ex-message e)))
             (assoc d :message (ex-message err))))))

(defn- new-store-is-tagged-and-reopen-passes
  []
  (let [name (db-name)
        mem (store/mem-store)
        h (v/hash (v/i64 mem 3))
        node (store/await (store/s-get mem h))
        st (idb/idb-store name)]
    (-> (idb/drive #(store/await (store/s-put st h node)))
        (.then (fn [_] (idb/close st) (raw-open name)))
        (.then (fn [db]
                 (-> (raw-get db "meta" "format")
                     (.then (fn [tag]
                              (eq! tag store/store-format "a new store is tagged")
                              (.close db))))))
        (.then (fn [_]
                 (let [again (idb/idb-store name)]
                   (-> (idb/drive
                        (fn []
                          (eq! (store/await (store/s-get again h)) node
                               "a reopened store passes and keeps its data")))
                       (.then (fn [_]
                                ;; s-reset clears meta too: the tag must come back
                                (idb/drive #(store/await (store/s-reset again)))))
                       (.then (fn [_] (idb/close again))))))))))

(defn- untagged-store-is-a-format-mismatch
  []
  (let [name (db-name)
        mem (store/mem-store)
        h (v/hash (v/i64 mem 4))
        node (store/await (store/s-get mem h))]
    (-> (raw-open name)
        ;; as a store written before tags: a node, no format key
        (.then (fn [db]
                 (-> (raw-put db "nodes" (store/hash->hex h) (js/Uint8Array. #js [1 2 3]))
                     (.then (fn [_] (.close db))))))
        (.then (fn [_]
                 (let [st (idb/idb-store name)]
                   (-> (mismatch-of #(store/await (store/s-get st h)))
                       (.then (fn [d]
                                (eq! (:found d) nil "untagged: found nil")
                                (eq! (:expected d) store/store-format "expected")
                                (when-not (re-find #"untagged" (:message d))
                                  (fail! "message says untagged" d))
                                (idb/close st)))))))
        (.then (fn [_]
                 (raw-open (db-name))))   ; an untagged EMPTY database is just a new store
        (.then (fn [db]
                 (let [name2 (.-name db)]
                   (.close db)
                   (let [st (idb/idb-store name2)]
                     (-> (idb/drive #(store/await (store/s-put st h node)))
                         (.then (fn [_] (idb/close st)))))))))))

(defn- wrong-version-store-is-a-format-mismatch
  []
  (let [name (db-name)
        mem (store/mem-store)
        h (v/hash (v/i64 mem 5))]
    (-> (raw-open name)
        (.then (fn [db]
                 (-> (raw-put db "meta" "format" 7)
                     (.then (fn [_] (.close db))))))
        (.then (fn [_]
                 (let [st (idb/open name)]
                   (-> (mismatch-of #(store/await (store/s-has? st h)))
                       (.then (fn [d]
                                (eq! (:found d) 7 "wrong version: found 7")
                                (idb/close st))))))))))

(defn- promise? [x] (instance? js/Promise x))

(defn- not-settled?
  "True when `store/await` refuses `p` because it has not settled."
  [p]
  (try (store/await p) false
       (catch :default e (= :not-settled (:reason (ex-data e))))))

(defn- sp
  "A host promise of a store promise, which may be a settled map."
  [p]
  (chain/native p))

(defn- promises-outside-drive
  "M1.3b: called outside `drive`, a method that has to wait returns an unsettled
   promise that then fulfils with its result, and never throws `:dacite/suspend`.
   A cache hit is a settled promise. Inside `drive` nothing changed (the other
   tests)."
  []
  (let [name (db-name)
        mem (store/mem-store)
        node-v (v/i64 mem 5)
        h (v/hash node-v)
        node (store/await (store/s-get mem h))
        other (v/hash (v/i64 mem 6))
        st (idb/idb-store name)
        put (store/s-put st h node)]
    (eq! [(promise? put) (not-settled? put)] [true true] "put is an unsettled promise")
    (-> put
        (.then (fn [r]
                 (eq! r st "put fulfils with the store")
                 ;; now a cache hit: settled
                 (eq! (store/await (store/s-get st h)) node "get is settled after put")
                 (idb/close st)
                 (let [again (idb/idb-store name)
                       got (store/s-get again h)]
                   (eq! [(promise? got) (not-settled? got)] [true true] "get of an uncached node is unsettled")
                   (-> got
                       (.then (fn [n]
                                (eq! n node "get fulfils with the node")
                                (eq! (store/await (store/s-get again h)) node "then it is settled (cached)")
                                (-> (sp (store/s-get again other))
                                    (.then (fn [n2]
                                             (eq! n2 nil "an absent node fulfils with nil")
                                             (sp (store/s-has? again other))))
                                    (.then (fn [b]
                                             (eq! b false "has? of an absent node is false")
                                             (sp (store/s-has? again h))))
                                    (.then (fn [b]
                                             (eq! b true "has? of a held node is true")
                                             (sp (store/local-stats again))))
                                    (.then (fn [stats]
                                             (eq! (:entries stats) 1 "stats count the node")
                                             (sp (store/s-has? again h))))
                                    (.then (fn [b]
                                             (eq! b true "the hash is still there")
                                             (sp (store/s-get again h))))
                                    (.then (fn [n]
                                             (eq! n node "the node is still a get")
                                             (sp (store/s-delete again h))))
                                    (.then (fn [r]
                                             (eq! r again "delete fulfils with the store")
                                             (sp (store/s-merge again {h node}))))
                                    (.then (fn [r]
                                             (eq! r again "merge fulfils with the store")
                                             (sp (store/s-reset again))))
                                    (.then (fn [r]
                                             (eq! r again "reset fulfils with the store")
                                             (sp (store/local-stats again))))
                                    (.then (fn [stats]
                                             (eq! (:entries stats) 0 "reset emptied it")
                                             (sp (store/s-has? again h))))
                                    (.then (fn [b]
                                             (eq! b false "the hash is gone after reset")
                                             (idb/close again)))))))))))))

(defn- peer-over-idb
  "M1.3b: the peer client of M1.3 against the IndexedDB store, with no replay
   adapter: frames are delivered back to back, every node of the value is verified
   and put into IndexedDB, and a reopened store holds them."
  []
  (let [name (db-name)
        src (store/mem-store)
        root (v/hash (cc/vector-value src 40))
        items (cc/items-of src root)
        st (idb/idb-store name)
        sent (atom [])
        c (client/client {:store st :send! (fn [p m] (swap! sent conj [p m]))})
        hello (msg/encode-message {:type :hello :stream 0 :value responder/default-hello})]
    (client/open-session! c "p")
    (client/on-frame! c "p" hello)
    (-> (chain/native (client/request! c root))
        (.then (fn [r]
                 (eq! r :requested "request! over idb")
                 (let [sid (:stream (second (first (filter #(= :get (:type (second %))) @sent))))
                       steps (mapv #(client/on-frame! c "p" %) ((cc/honest src) sid root))]
                   (eq! (boolean (some chain/pending? steps)) true "frames wait for IndexedDB")
                   (chain/native (last steps)))))
        (.then (fn [_]
                 (eq! [(client/done? c root) (client/session-status c "p") (client/failed c)]
                      [true :open []] "the value was received and verified")
                 (idb/close st)
                 (let [again (idb/idb-store name)]
                   (js/Promise.all
                    (clj->js (mapv (fn [it] (store/s-get again (:hash it))) items))))))
        (.then (fn [nodes]
                 (eq! (vec nodes) (mapv :entry items) "a reopened store holds every node"))))))

(defn- unavailable?
  "True when `e` (or its cause: nbb wraps an error thrown in a promise callback) is
   `:unavailable`."
  [e]
  (boolean (some #(= :unavailable (:reason (ex-data %))) [e (ex-cause e)])))

(defn- closed-db-fails-the-peer-locally
  "M1.3c: a real IndexedDB failure outside `drive`. The database handle is closed
   under the store, so the next operation fails. The promise rejects `:unavailable`,
   and the peer client maps it to `:store-failed`: the hash fails locally, and the
   session, which did nothing wrong, stays open."
  []
  (let [name (db-name)
        src (store/mem-store)
        root (v/hash (cc/vector-value src 20))
        st (idb/idb-store name)
        sent (atom [])
        c (client/client {:store st :send! (fn [p m] (swap! sent conj [p m]))})
        hello (msg/encode-message {:type :hello :stream 0 :value responder/default-hello})]
    (client/open-session! c "p")
    (client/on-frame! c "p" hello)
    (-> (chain/native (client/request! c root))
        (.then (fn [_]
                 ;; the database is open now: close its handle behind the store's back
                 (.close @(:db st))
                 (-> (store/s-put st [1 2 3 4] (store/await (store/s-get src root)))
                     (.then (fn [_] (fail! "put on a closed database fulfilled" {})))
                     (.catch (fn [e]
                               (eq! (unavailable? e) true "a closed database rejects :unavailable"))))))
        (.then (fn [_]
                 (let [sid (:stream (second (first (filter #(= :get (:type (second %))) @sent))))
                       steps (mapv #(client/on-frame! c "p" %) ((cc/honest src) sid root))]
                   (chain/native (last steps)))))
        (.then (fn [_]
                 (let [[f :as failed] (client/failed c)]
                   (eq! [(mapv :hash failed) (:reason (ex-data (:error f)))
                         (unavailable? (:cause (ex-data (:error f))))]
                        [[root] :store-failed true]
                        "the peer maps the failure to :store-failed")
                   (eq! [(client/session-status c "p") (client/done? c root)] [:open false]
                        "the session lives, the hash is not done")
                   (idb/close st)))))))

(defn- reset-does-not-serve-stale-nodes
  "M1.3c: `s-reset` outside `drive` drops the in-memory nodes at once and a read
   issued while the database is being cleared waits for the clear, so it cannot
   return the old node (before: a cached hit answered with it)."
  []
  (let [name (db-name)
        mem (store/mem-store)
        h (v/hash (v/i64 mem 11))
        node (store/await (store/s-get mem h))
        st (idb/idb-store name)]
    (-> (store/s-put st h node)
        (.then (fn [_]
                 (eq! (store/await (store/s-get st h)) node "cached before the reset")
                 (let [reset (store/s-reset st)
                       got (store/s-get st h)
                       has (store/s-has? st h)]
                   (eq! [(promise? reset) (promise? got) (promise? has)] [true true true]
                        "reads during a reset wait for it")
                   (js/Promise.all #js [reset (sp got) (sp has)]))))
        (.then (fn [rs]
                 (eq! [(aget rs 1) (aget rs 2)] [nil false] "a read during the reset sees it cleared")
                 (-> (store/s-get st h)
                     (.then (fn [n]
                              (eq! n nil "and after it, from the database")
                              (idb/close st)))))))))

(defn- unwrap
  "A host promise of the store method's value. A cache hit is already settled
   (`{:dacite/result ...}`), not a `js/Promise`."
  [p]
  (if (instance? js/Promise p)
    p
    (js/Promise.resolve (store/await p))))

(defn- evicting-store-get-evict-get-again
  "N14c, outside `drive`: put two nodes into a one-node cache, get the newer
   (that evicts the older), get the older again. IndexedDB still has it."
  []
  (let [name (db-name)
        mem (store/mem-store)
        a (let [h (v/hash (v/i64 mem 1))]
            [h (store/await (store/s-get mem h))])
        b (let [h (v/hash (v/i64 mem 2))]
            [h (store/await (store/s-get mem h))])
        st (idb/idb-store name {:cache-limit 1})]
    (-> (unwrap (store/s-put st (first a) (second a)))
        (.then (fn [_] (unwrap (store/s-put st (first b) (second b)))))
        (.then (fn [_] (unwrap (store/s-get st (first b)))))
        (.then (fn [got]
                 (eq! got (second b) "the newest node stays cached")
                 (eq! (.-size @(:cache-order st)) 1 "the older node was evicted")
                 (unwrap (store/s-get st (first a)))))
        (.then (fn [got]
                 (eq! got (second a) "get, evict, get again returns the node")
                 (idb/close st))))))

(defn- sweep-does-not-fill-the-read-cache
  "N14a: an internal sweep walks every hash and does not load those nodes
   into the read cache. A node the cache dropped is still read back when
   the sweep kept it."
  []
  (let [name (db-name)
        mem (store/mem-store)
        nodes (mapv (fn [i]
                      (let [h (v/hash (v/i64 mem i))]
                        [h (store/await (store/s-get mem h))]))
                    (range 5))
        st (idb/idb-store name {:cache-limit 1})
        live (set (map (comp store/hash->hex first) nodes))]
    (-> (unwrap (store/s-merge st (into {} nodes)))
        ;; A get evicts down to the limit. The sweep must not fill it again.
        (.then (fn [_] (unwrap (store/s-get st (first (peek nodes))))))
        (.then (fn [_]
                 (eq! (<= (.-size @(:cache-order st)) 1) true
                      "the read cache is within its limit before the sweep")
                 (let [before (.-size @(:cache-order st))]
                   (-> (unwrap (store/sweep-unmarked st live))
                       (.then (fn [result]
                                (eq! (:removed result) 0 "every known hash is kept")
                                (eq! (.-size @(:cache-order st)) before
                                     "the sweep does not load nodes into the read cache")
                                (unwrap (store/s-get st (ffirst nodes)))))))))
        (.then (fn [got]
                 (eq! got (second (first nodes)) "a node dropped from the cache is read back")
                 (idb/close st))))))

(defn- a-miss-outside-drive-does-not-evict-a-hit
  "N14d: a get of a hash that is not stored, outside `drive`, must not occupy
   a cache slot. The node that was cached stays a hit."
  []
  (counting-transactions
   (fn [n]
     (let [name (db-name)
           mem (store/mem-store)
           h (v/hash (v/i64 mem 3))
           node (store/await (store/s-get mem h))
           st (idb/idb-store name {:cache-limit 1})]
       (-> (unwrap (store/s-put st h node))
           (.then (fn [_] (unwrap (store/s-get st h))))
           (.then (fn [_]
                    (reset! n 0)
                    (unwrap (store/s-get st [9 9 9 9]))))
           (.then (fn [miss]
                    (eq! miss nil "the missing hash is absent")
                    (reset! n 0)
                    (unwrap (store/s-get st h))))
           (.then (fn [got]
                    (eq! got node "the cached node is still a hit")
                    (eq! @n 0 "a miss did not push it out of the cache")
                    (idb/close st))))))))

(defn -main
  [& _]
  (js/require "fake-indexeddb/auto")
  (-> (roundtrip)
      (.then put-survives-reopen)
      (.then rooted-swap)
      (.then cache-in-front)
      (.then batch-merge)
      (.then cache-evicts)
      (.then another-writer-is-visible)
      (.then new-store-is-tagged-and-reopen-passes)
      (.then untagged-store-is-a-format-mismatch)
      (.then wrong-version-store-is-a-format-mismatch)
      (.then promises-outside-drive)
      (.then peer-over-idb)
      (.then closed-db-fails-the-peer-locally)
      (.then reset-does-not-serve-stale-nodes)
      (.then evicting-store-get-evict-get-again)
      (.then sweep-does-not-fill-the-read-cache)
      (.then a-miss-outside-drive-does-not-evict-a-hit)
      (.then (fn [_]
               (println "idb tests passed")))
      (.catch (fn [e]
                (js/console.error e)
                (js/process.exit 1)))))
