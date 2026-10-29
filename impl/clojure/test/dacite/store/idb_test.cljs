(ns dacite.store.idb-test
  "IndexedDB store against fake-indexeddb. Run from the repo root:

     npm install
     npx nbb -cp impl/clojure/src:impl/clojure/test -m dacite.store.idb-test"
  (:require [dacite.store :as store]
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
           (let [snap (store/await (store/s-snapshot st))]
             (eq! (get snap (store/hash->hex h)) node "snapshot has the node"))))
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
      (.then (fn [_]
               (println "idb tests passed")))
      (.catch (fn [e]
                (js/console.error e)
                (js/process.exit 1)))))
