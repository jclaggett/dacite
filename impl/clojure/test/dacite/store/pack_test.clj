(ns dacite.store.pack-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.wire :as wire]
            [dacite.service :as svc]
            [dacite.store.remote :as remote]
            [dacite.store.client-cache :as client-cache]
            [dacite.store.stats :as stats]
            [dacite.examples.todo :as todo]
            [dacite.value.types :as types]
            [dacite.value.collections :as coll]
            [dacite.value.scalar :as scalar]))

(deftest pack-items-soft-budget
  (let [items (mapv (fn [i]
                      (pack/node-item [i 0 0 0] ["i64" i]))
                    (range 20))
        chunks (pack/pack-items items 200)]
    (is (pos? (count chunks)))
    (is (= 20 (reduce + (map #(count (:items %)) chunks))))
    (doseq [ch chunks]
      (is (true? (:dacite.wire/chunk-v1 ch)))
      (is (vector? (:items ch))))
    (is (= 20 (count (mapcat :items chunks))))))

(deftest apply-chunk-puts-nodes
  (let [st (store/mem-store)
        h1 (store/hex->hash (apply str (repeat 64 "1")))
        h2 (store/hex->hash (apply str (repeat 64 "2")))
        chunk (pack/make-chunk 1024
                               [(pack/node-item h1 ["bool" true])
                                (pack/node-item h2 ["i64" 7])])]
    (is (= 2 (:applied (pack/apply-chunk! st chunk))))
    (is (= 2 (:nodes (pack/apply-chunk! (store/mem-store) chunk))))
    (is (= ["bool" true] (store/s-get st h1)))
    (is (= ["i64" 7] (store/s-get st h2)))))

(deftest encode-and-apply-literal-scalars
  (let [st (store/mem-store)
        h (scalar/put-scalar! st "i64" 42)
        item (pack/encode-item st h (store/s-get st h))]
    (is (= :literal (:encoding item)))
    (is (= "i64" (:type item)))
    (is (= 42 (:body item)))
    (let [st2 (store/mem-store)
          r (pack/apply-chunk! st2 (pack/make-chunk 1024 [item]))]
      (is (= 1 (:literals r)))
      (is (= ["i64" 42] (store/s-get st2 h))))))

(deftest encode-and-apply-literal-string
  (let [st (store/mem-store)
        s (coll/string-with-store st "hello")
        h (types/dacite-hash s)
        item (pack/encode-item st h (store/s-get st h))]
    (is (= :literal (:encoding item)))
    (is (= "string" (:type item)))
    (is (= "hello" (:body item)))
    (let [st2 (store/mem-store)
          r (pack/apply-chunk! st2 (pack/make-chunk 1024 [item]))]
      (is (= 1 (:literals r)))
      (is (store/s-has? st2 h))
      (is (= "string" (types/entry-type (store/s-get st2 h)))))))

(deftest encode-and-apply-medium-string-literal
  ;; Medium strings must use index-based host extract (ft-seq can truncate).
  (let [st (store/mem-store)
        body (apply str (repeat 100 "x"))
        s (coll/string-with-store st body)
        h (types/dacite-hash s)
        item (pack/encode-item st h (store/s-get st h))
        {:keys [items]} (pack/encode-reachable st h)]
    (is (= :literal (:encoding item)))
    (is (= 100 (count (:body item))))
    (is (= body (:body item)))
    (is (= 1 (count items)) "literal string covers FT spine")
    (let [st2 (store/mem-store)]
      (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
      (is (store/s-has? st2 h)))))
(deftest encode-and-apply-literal-vector-map-set
  (testing "vector"
    (let [st (store/mem-store)
          v (coll/vector-with-store st 1 2 3)
          h (types/dacite-hash v)
          item (pack/encode-item st h (store/s-get st h))]
      (is (= :literal (:encoding item)))
      (is (= [1 2 3] (:body item)))
      (let [st2 (store/mem-store)]
        (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
        (is (store/s-has? st2 h)))))
  (testing "map"
    (let [st (store/mem-store)
          m (coll/hash-map-with-store st "a" 1 "b" 2)
          h (types/dacite-hash m)
          item (pack/encode-item st h (store/s-get st h))]
      (is (= :literal (:encoding item)))
      (is (= {"a" 1 "b" 2} (:body item)))
      (let [st2 (store/mem-store)]
        (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
        (is (store/s-has? st2 h)))))
  (testing "set"
    (let [st (store/mem-store)
          s (coll/dacite-set-with-store st 1 2 3)
          h (types/dacite-hash s)
          item (pack/encode-item st h (store/s-get st h))]
      (is (= :literal (:encoding item)))
      (is (= #{1 2 3} (:body item)))
      (let [st2 (store/mem-store)]
        (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
        (is (store/s-has? st2 h))))))

(deftest literal-hash-mismatch-throws
  (let [st (store/mem-store)
        bad (pack/literal-item (store/hex->hash (apply str (repeat 64 "c")))
                               "i64" 1)
        chunk (pack/make-chunk 1024 [bad])]
    (is (thrown-with-msg? Exception #"literal hash mismatch"
                          (pack/apply-chunk! st chunk)))))

(deftest encode-reachable-prefers-literal-and-skips-children
  (let [st (store/mem-store)
        s (coll/string-with-store st "ab")
        h (types/dacite-hash s)
        live (count (store/s-snapshot st))
        {:keys [items covered]} (pack/encode-reachable st h)]
    ;; One literal for the string root, not one item per char/FT node
    (is (= 1 (count items)))
    (is (= :literal (:encoding (first items))))
    (is (= "ab" (:body (first items))))
    (is (>= (count covered) live))
    (is (contains? covered h))))

(deftest ft-internal-nodes-stay-nodes
  (let [st (store/mem-store)
        ;; large enough vector to force FT internal structure, but we only
        ;; check that ft/* entries never encode as :literal
        v (apply coll/vector-with-store st (range 50))
        h (types/dacite-hash v)
        snap (store/s-snapshot st)
        ft-entries (filter (fn [[_ e]]
                             (let [t (types/entry-type e)]
                               (or (str/starts-with? (str t) "ft/")
                                   (str/starts-with? (str t) "hamt/"))))
                           snap)]
    (is (seq ft-entries))
    (doseq [[fh entry] ft-entries]
      (is (= :node (:encoding (pack/encode-item st fh entry)))))))

(deftest service-post-nodes-chunk
  (let [rooted (svc/make-demo-rooted)
        h (store/hex->hash (apply str (repeat 64 "a")))
        chunk (pack/make-chunk 1024 [(pack/node-item h ["i64" 99])])
        resp (svc/handle-request rooted "POST" "/nodes" (wire/write-edn chunk))]
    (is (= 200 (:status resp)))
    (is (true? (:ok (wire/read-edn (:body resp)))))
    (is (= 1 (:applied (wire/read-edn (:body resp)))))
    (is (= ["i64" 99] (store/s-get rooted h)))))

(deftest service-post-nodes-literal
  (let [rooted (svc/make-demo-rooted)
        src (store/mem-store)
        s (coll/string-with-store src "todo")
        h (types/dacite-hash s)
        item (pack/encode-item src h (store/s-get src h))
        chunk (pack/make-chunk 1024 [item])
        resp (svc/handle-request rooted "POST" "/nodes" (wire/write-edn chunk))]
    (is (= :literal (:encoding item)))
    (is (= 200 (:status resp)))
    (is (= 1 (:literals (wire/read-edn (:body resp)))))
    (is (store/s-has? rooted h))))

(deftest write-back-flush-uses-fewer-http-requests-than-per-node
  ;; Chunked flush collapses many PUTs into few POST /nodes; literals cut items.
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
        (is (< (:requests d) 40)
            "literal+chunked write-back seed should be far fewer than 2a ~35"))
      (finally
        (stop!)))))
