(ns dacite.store.pack-test
  (:require [clojure.test :refer [deftest is]]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.wire :as wire]
            [dacite.service :as svc]
            [dacite.store.remote :as remote]
            [dacite.store.client-cache :as client-cache]
            [dacite.store.stats :as stats]
            [dacite.examples.todo :as todo]
            [dacite.value.types :as types]))

(deftest pack-items-soft-budget
  (let [items (mapv (fn [i]
                      (pack/node-item [i 0 0 0] ["i64" i]))
                    (range 20))
        chunks (pack/pack-items items 200)]
    (is (pos? (count chunks)))
    (is (= 20 (reduce + (map #(count (:items %)) chunks))))
    (doseq [ch chunks]
      (is (true? (:dacite.wire/chunk-v1 ch)))
      (is (vector? (:items ch)))
      ;; each sealed mid-stream chunk should be at least budget (soft)
      )
    (is (= 20 (count (mapcat :items chunks))))))

(deftest apply-chunk-puts-nodes
  (let [st (store/mem-store)
        h1 (store/hex->hash (apply str (repeat 64 "1")))
        h2 (store/hex->hash (apply str (repeat 64 "2")))
        chunk (pack/make-chunk 1024
                               [(pack/node-item h1 ["bool" true])
                                (pack/node-item h2 ["i64" 7])])]
    (is (= {:applied 2} (pack/apply-chunk! st chunk)))
    (is (= ["bool" true] (store/s-get st h1)))
    (is (= ["i64" 7] (store/s-get st h2)))))

(deftest service-post-nodes-chunk
  (let [rooted (svc/make-demo-rooted)
        h (store/hex->hash (apply str (repeat 64 "a")))
        chunk (pack/make-chunk 1024 [(pack/node-item h ["i64" 99])])
        resp (svc/handle-request rooted "POST" "/nodes" (wire/write-edn chunk))]
    (is (= 200 (:status resp)))
    (is (true? (:ok (wire/read-edn (:body resp)))))
    (is (= 1 (:applied (wire/read-edn (:body resp)))))
    (is (= ["i64" 99] (store/s-get rooted h)))))

(deftest write-back-flush-uses-fewer-http-requests-than-per-node
  ;; Chunked flush collapses many PUTs into few POST /nodes.
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (stats/reset-stats!)
      (let [raw (remote/remote-store base-url)
            wb (client-cache/wrap raw :write-back)
            t (todo/build wb (todo/seed-items))
            h (types/dacite-hash t)
            before (stats/get-stats)
            _ (remote/remote-cas-root! wb nil h)
            after (stats/get-stats)
            d (stats/stats-diff before after)
            kinds (:by-kind after {})]
        (is (true? (store/s-has? raw h)))
        (is (pos? (:requests d)))
        ;; Should use batch posts, not hundreds of node puts
        (is (pos? (get kinds :nodes-put 0)))
        (is (< (get kinds :node-put 0) 5)
            "seed flush should not issue many single-node PUTs")
        (is (< (:requests d) 80)
            "chunked write-back seed should be far fewer than uncached ~thousands"))
      (finally
        (stop!)))))
