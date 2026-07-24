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

(defn- round-trip-hash
  "literal-of → materialize in fresh store; return [original-h got-h form]."
  [st h]
  (let [form (pack/literal-of st h)
        st2 (store/mem-store)
        got (pack/materialize-literal! st2 (:type form) (:body form))]
    [h got form]))

(defn- typed-lit?
  [x]
  (and (map? x) (contains? x :type) (contains? x :body)))

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

(deftest literal-of-and-materialize-scalars
  (let [st (store/mem-store)
        h (scalar/put-scalar! st "i64" 42)
        form (pack/literal-of st h)]
    (is (= "i64" (:type form)))
    (is (= 42 (:body form)))
    (let [[h1 h2] (round-trip-hash st h)]
      (is (= h1 h2)))))

(deftest literal-of-and-materialize-string
  (let [st (store/mem-store)
        s (coll/string-with-store st "hello")
        h (types/dacite-hash s)
        form (pack/literal-of st h)]
    (is (= "string" (:type form)))
    (is (= "hello" (:body form)))
    (is (= h (second (round-trip-hash st h))))))

(deftest literal-of-medium-string
  (let [st (store/mem-store)
        body (apply str (repeat 100 "x"))
        s (coll/string-with-store st body)
        h (types/dacite-hash s)
        form (pack/literal-of st h)]
    (is (= 100 (count (:body form))))
    (is (= body (:body form)))
    (is (= h (second (round-trip-hash st h))))))

(deftest literal-of-vector-is-recursive-typed
  (let [st (store/mem-store)
        v (coll/vector-with-store st 1 2 3)
        h (types/dacite-hash v)
        form (pack/literal-of st h)]
    (is (= "vector" (:type form)))
    (is (every? typed-lit? (:body form))
        "vector elements are recursive {:type :body} forms")
    (is (= ["i64" "i64" "i64"] (mapv :type (:body form))))
    (is (= [1 2 3] (mapv :body (:body form))))
    (is (= h (second (round-trip-hash st h))))))

(deftest literal-of-nested-vector-of-strings
  (let [st (store/mem-store)
        v (coll/vector-with-store st "hello" "world")
        h (types/dacite-hash v)
        form (pack/literal-of st h)]
    (is (= "vector" (:type form)))
    (is (= ["string" "string"] (mapv :type (:body form))))
    (is (= ["hello" "world"] (mapv :body (:body form))))
    (is (= h (second (round-trip-hash st h))))
    (let [item (pack/encode-item st h (store/s-get st h))]
      (is (= :literal (:encoding item)))
      (let [st2 (store/mem-store)]
        (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
        (is (store/s-has? st2 h))))))

(deftest literal-of-map-and-set
  (testing "map"
    (let [st (store/mem-store)
          m (coll/hash-map-with-store st "a" 1 "b" 2)
          h (types/dacite-hash m)
          form (pack/literal-of st h)]
      (is (= "map" (:type form)))
      (is (vector? (:body form)))
      (is (= 2 (count (:body form))))
      (doseq [[k v] (:body form)]
        (is (typed-lit? k))
        (is (typed-lit? v)))
      (is (= h (second (round-trip-hash st h))))))
  (testing "set"
    (let [st (store/mem-store)
          s (coll/dacite-set-with-store st 1 2 3)
          h (types/dacite-hash s)
          form (pack/literal-of st h)]
      (is (= "set" (:type form)))
      (is (= 3 (count (:body form))))
      (is (every? #(= "i64" (:type %)) (:body form)))
      (is (= h (second (round-trip-hash st h)))))))

(deftest literal-of-empty-collections
  (doseq [[label build]
          [["empty string" #(coll/string-with-store % "")]
           ["empty vector" #(coll/vector-with-store %)]
           ["empty map" #(coll/hash-map-with-store %)]
           ["empty set" #(coll/dacite-set-with-store %)]]]
    (let [st (store/mem-store)
          v (build st)
          h (types/dacite-hash v)
          form (pack/literal-of st h)]
      (is (some? form) label)
      (is (= h (second (round-trip-hash st h))) label))))

(deftest literal-of-spine-is-nil
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (range 50))
        snap (store/s-snapshot st)
        ft-hs (keep (fn [[h e]]
                      (when (str/starts-with? (str (types/entry-type e)) "ft/")
                        h))
                    snap)]
    (is (seq ft-hs))
    (doseq [fh ft-hs]
      (is (nil? (pack/literal-of st fh))))))

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
      (is (store/s-has? st2 h)))))

(deftest encode-and-apply-medium-string-literal
  (let [st (store/mem-store)
        body (apply str (repeat 100 "x"))
        s (coll/string-with-store st body)
        h (types/dacite-hash s)
        item (pack/encode-item st h (store/s-get st h))
        {:keys [items]} (pack/encode-reachable st h)]
    (is (= :literal (:encoding item)))
    (is (= 100 (count (:body item))))
    (is (= 1 (count items)))
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
      (is (every? map? (:body item)))
      (let [st2 (store/mem-store)]
        (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
        (is (store/s-has? st2 h)))))
  (testing "map"
    (let [st (store/mem-store)
          m (coll/hash-map-with-store st "a" 1 "b" 2)
          h (types/dacite-hash m)
          item (pack/encode-item st h (store/s-get st h))]
      (is (= :literal (:encoding item)))
      (let [st2 (store/mem-store)]
        (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
        (is (store/s-has? st2 h)))))
  (testing "set"
    (let [st (store/mem-store)
          s (coll/dacite-set-with-store st 1 2 3)
          h (types/dacite-hash s)
          item (pack/encode-item st h (store/s-get st h))]
      (is (= :literal (:encoding item)))
      (let [st2 (store/mem-store)]
        (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
        (is (store/s-has? st2 h))))))

(deftest materialize-accepts-2b-flat-host-bodies
  ;; Wire compat: flat host bodies from 2b still materialize.
  (let [st (store/mem-store)
        v (coll/vector-with-store st 1 2 3)
        h (types/dacite-hash v)
        flat (pack/literal-item h "vector" [1 2 3])
        st2 (store/mem-store)]
    (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [flat])))))
    (is (store/s-has? st2 h))))

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
    (is (= 1 (count items)))
    (is (= :literal (:encoding (first items))))
    (is (= "ab" (:body (first items))))
    (is (>= (count covered) live))
    (is (contains? covered h))))

(deftest ft-internal-nodes-stay-nodes
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (range 50))
        _h (types/dacite-hash v)
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
        (is (pos? (get kinds :nodes-put 0)))
        (is (< (get kinds :node-put 0) 5)
            "seed flush should not issue many single-node PUTs")
        (is (< (:requests d) 40)
            "literal+chunked write-back seed should be far fewer than 2a ~35"))
      (finally
        (stop!)))))

(deftest todo-seed-round-trips-as-literals
  (let [st (store/mem-store)
        t (todo/build st (todo/seed-items))
        h (types/dacite-hash t)
        form (pack/literal-of st h)
        item (pack/encode-item st h (store/s-get st h))]
    (is (= "vector" (:type form)))
    (is (= :literal (:encoding item)))
    (is (= h (second (round-trip-hash st h))))
    (let [{:keys [items]} (pack/encode-reachable st h)]
      (is (= 1 (count items)) "whole todo tree as one literal"))))

;; ---------------------------------------------------------------------------
;; 2c — large trees / blobs: refuse root literal, mix :node + child :literal
;; ---------------------------------------------------------------------------

(deftest large-string-refuses-literal-at-default-budget
  (let [st (store/mem-store)
        body (apply str (repeat 3000 "x"))
        s (coll/string-with-store st body)
        h (types/dacite-hash s)
        entry (store/s-get st h)
        item (pack/encode-item st h entry)
        sum (pack/encode-summary st h)]
    (is (pack/clearly-oversized? entry pack/default-budget))
    (is (false? (boolean (pack/fits-literal? st h))))
    (is (= :node (:encoding item)) "root string over budget is :node")
    (is (pos? (:nodes sum)))
    (is (pos? (:literals sum)) "shared char leaf still a literal")
    (is (> (:items sum) 1) "walk expands FT spine under large string")))

(deftest large-string-literal-when-budget-allows
  (let [st (store/mem-store)
        body (apply str (repeat 3000 "x"))
        s (coll/string-with-store st body)
        h (types/dacite-hash s)
        item (pack/encode-item st h (store/s-get st h) 5000)
        sum (pack/encode-summary st h 5000)]
    (is (= :literal (:encoding item)))
    (is (= 1 (:items sum)))
    (is (= 1 (:literals sum)))))

(deftest large-vector-mixed-node-and-child-literals
  ;; size-bytes of 40 short strings exceeds a tight budget → root :node,
  ;; each string child becomes its own :literal.
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (map #(str "item-" %) (range 40)))
        h (types/dacite-hash v)
        budget 200
        entry (store/s-get st h)
        root-item (pack/encode-item st h entry budget)
        {:keys [items]} (pack/encode-reachable st h #{} budget)
        sum (pack/summarize-items items)
        string-lits (filter (fn [it]
                              (and (= :literal (:encoding it))
                                   (= "string" (:type it))))
                            items)]
    (is (pack/clearly-oversized? entry budget))
    (is (= :node (:encoding root-item)))
    (is (pos? (:nodes sum)))
    (is (= 40 (count string-lits)) "each element string is a literal")
    (is (> (:items sum) 40) "includes vector root + FT spine nodes")
    ;; Receiver can apply all chunks and hold the root hash
    (let [st2 (store/mem-store)
          chunks (pack/pack-items items budget)]
      (doseq [ch chunks]
        (pack/apply-chunk! st2 ch))
      (is (store/s-has? st2 h)))))

(deftest wire-overhead-can-refuse-even-when-size-cue-fits
  ;; Recursive typed body can exceed 2×budget while :size-bytes is still small.
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (map #(str "item-" %) (range 40)))
        h (types/dacite-hash v)
        entry (store/s-get st h)
        ;; cue ~270; wire form ~1375 → refuse at budget 500 (2×=1000)
        budget 500
        item (pack/encode-item st h entry budget)
        sum (pack/encode-summary st h budget)]
    (is (<= (pack/size-cue entry) budget) "size cue alone would allow")
    (is (= :node (:encoding item)) "wire form over 2×budget → :node")
    (is (pos? (:literals sum)) "children still literalized")))

(deftest encode-summary-reports-mixed-stats
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (map str (range 20)))
        h (types/dacite-hash v)
        sum (pack/encode-summary st h 100)]
    (is (= 100 (:budget sum)))
    (is (pos? (:items sum)))
    (is (pos? (:chunks sum)))
    (is (= (+ (:literals sum) (:nodes sum)) (:items sum)))
    (is (pos? (:approx-bytes sum)))))
