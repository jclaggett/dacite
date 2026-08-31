(ns dacite.store.pack-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.wire :as wire]
            [dacite.wire.binary :as bin]
            [dacite.service :as svc]
            [dacite.store.remote :as remote]
            [dacite.store.client-cache :as client-cache]
            [dacite.store.stats :as stats]
            [dacite.examples.todo :as todo]
            [dacite.value.types :as types]
            [dacite.value.collections :as coll]
            [dacite.value.finger-tree :as ft]
            [dacite.value.scalar :as scalar]
            [dacite.value :as v]))

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
    (let [r (pack/apply-chunk! st chunk)]
      (is (= 2 (:applied r)))
      (is (= 2 (:nodes r)))
      (is (= :partial (:status r)))
      (is (= 2 (count (:created r))))
      (is (empty? (:exists r))))
    (let [r2 (pack/apply-chunk! st chunk)]
      (is (= :complete (:status r2)))
      (is (empty? (:created r2)))
      (is (= 2 (count (:exists r2)))))
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
        form (pack/literal-of st h)
        run (first (:body form))]
    (is (= "vector" (:type form)))
    (is (= 1 (count (:body form))) "homogeneous i64s collapse to one run")
    (is (= "run" (:type run)))
    (is (= "i64" (get-in run [:body :of])))
    (is (= [1 2 3] (get-in run [:body :values])))
    (is (= h (second (round-trip-hash st h))))))

(deftest literal-of-nested-vector-of-strings
  (let [st (store/mem-store)
        v (coll/vector-with-store st "hello" "world")
        h (types/dacite-hash v)
        form (pack/literal-of st h)
        run (first (:body form))]
    (is (= "vector" (:type form)))
    (is (= "run" (:type run)))
    (is (= "string" (get-in run [:body :of])))
    (is (= ["hello" "world"] (get-in run [:body :values])))
    (is (= h (second (round-trip-hash st h))))
    (let [item (pack/encode-item st h (store/s-get st h))]
      (is (= :literal (:encoding item)))
      (let [st2 (store/mem-store)]
        (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
        (is (store/s-has? st2 h))))))

(deftest rle-lits-n1-stays-unwrapped
  (let [st (store/mem-store)
        v (coll/vector-with-store st 42)
        form (pack/literal-of st (types/dacite-hash v))
        el (first (:body form))]
    (is (= 1 (count (:body form))))
    (is (= "i64" (:type el)))
    (is (= 42 (:body el)))))

(deftest rle-lits-mixed-types-split-runs
  (let [st (store/mem-store)
        v (coll/vector-with-store st 1 2 "z")
        form (pack/literal-of st (types/dacite-hash v))
        body (:body form)]
    (is (= 2 (count body)))
    (is (= "run" (:type (nth body 0))))
    (is (= "i64" (get-in (nth body 0) [:body :of])))
    (is (= [1 2] (get-in (nth body 0) [:body :values])))
    (is (= "string" (:type (nth body 1))))
    (is (= "z" (:body (nth body 1))))
    (is (= (types/dacite-hash v) (second (round-trip-hash st (types/dacite-hash v)))))))

(deftest rle-char-run-on-string-spine
  (let [st (store/mem-store)
        s (coll/string-with-store st "abcdefghij")
        snap (store/s-snapshot st)
        ft (filter (fn [[_ e]]
                     (str/starts-with? (str (types/entry-type e)) "ft/"))
                   snap)]
    (is (seq ft))
    (doseq [[fh _] ft]
      (let [form (pack/literal-of st fh)]
        (is (some? form))
        (when (pos? (count (or (:body form) [])))
          (let [run (first (:body form))]
            (when (= "run" (:type run))
              (is (= "char" (get-in run [:body :of])))
              (is (string? (get-in run [:body :values]))))))))
    (is (= (types/dacite-hash s)
           (second (round-trip-hash st (types/dacite-hash s)))))))

(deftest rle-all-equal-chars-are-repeat
  (let [st (store/mem-store)
        chs (mapv #(types/dacite-hash (scalar/dacite-char-with-store st %))
                  (repeat 24 \x))
        nh (ft/ft-node-from-value-hashes st chs)
        form (pack/literal-of st nh)
        rep (first (:body form))]
    (is (= "repeat" (:type rep)) (pr-str form))
    (is (= "char" (get-in rep [:body :of])))
    (is (= 24 (get-in rep [:body :n])))
    (is (= \x (get-in rep [:body :value])))
    (is (= nh (second (round-trip-hash st nh))))))

(deftest rle-mixed-chars-are-run-not-split-repeats
  (let [st (store/mem-store)
        chs (mapv #(types/dacite-hash (scalar/dacite-char-with-store st %))
                  (seq "aaabbc"))
        dh (ft/ft-digit-from-value-hashes st chs)
        form (pack/literal-of st dh)
        run (first (:body form))]
    (is (= "run" (:type run)) (pr-str form))
    (is (= "char" (get-in run [:body :of])))
    (is (= "aaabbc" (get-in run [:body :values])))
    (is (nil? (some #(= "repeat" (:type %)) (:body form)))
        "do not split mixed text into per-letter repeats")
    (is (= dh (second (round-trip-hash st dh))))))

(deftest rle-bool-vector-all-false-is-repeat
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (repeat 8 false))
        h (types/dacite-hash v)
        form (pack/literal-of st h)
        rep (first (:body form))]
    (is (= "repeat" (:type rep)))
    (is (= "bool" (get-in rep [:body :of])))
    (is (= 8 (get-in rep [:body :n])))
    (is (false? (get-in rep [:body :value])))
    (is (= h (second (round-trip-hash st h))))))

(deftest rle-char-run-wire-round-trip
  (let [st (store/mem-store)
        _s (coll/string-with-store st (apply str (repeat 24 \x)))
        snap (store/s-snapshot st)
        nodes (filter (fn [[_ e]]
                        (#{"ft/node" "ft/digit"} (types/entry-type e)))
                      snap)]
    (is (seq nodes))
    (doseq [[fh _] (take 2 nodes)]
      (let [form (pack/literal-of st fh)
            item (pack/literal-item fh (:type form) (:body form))
            ch (pack/make-chunk 1024 [item])
            back (bin/decode-pack-edn (bin/encode-pack-edn ch))
            st2 (store/mem-store)]
        (is (some #(#{"run" "repeat"} (:type %)) (:body form))
            (str "spine literal should contain a char run/repeat, got " (pr-str form)))
        (pack/apply-chunk! st2 back)
        (is (store/s-has? st2 fh))))))

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
          form (pack/literal-of st h)
          run (first (:body form))]
      (is (= "set" (:type form)))
      (is (= "run" (:type run)))
      (is (= "i64" (get-in run [:body :of])))
      (is (= 3 (count (get-in run [:body :values]))))
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

(deftest literal-of-spine-is-intermediate-form
  ;; 2c′: spine nodes have leaf-payload literals (not nil).
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (range 20))
        snap (store/s-snapshot st)
        ft-hs (keep (fn [[h e]]
                      (when (str/starts-with? (str (types/entry-type e)) "ft/")
                        h))
                    snap)]
    (is (seq ft-hs))
    (doseq [fh ft-hs]
      (let [form (pack/literal-of st fh)]
        (is (some? form))
        (is (str/starts-with? (str (:type form)) "ft/"))
        (is (vector? (:body form)))))))

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
    (is (contains? covered (store/hash->hex h)))))

(deftest ft-internal-nodes-may-be-intermediate-literals
  ;; 2c′: spine nodes with fitting leaf payloads can be :literal when rebuild matches.
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (range 20))
        _h (types/dacite-hash v)
        snap (store/s-snapshot st)
        ft-entries (filter (fn [[_ e]]
                             (str/starts-with? (str (types/entry-type e)) "ft/"))
                           snap)
        encs (mapv (fn [[fh entry]]
                     (:encoding (pack/encode-item st fh entry)))
                   ft-entries)]
    (is (seq ft-entries))
    (is (some #{:literal} encs) "at least some ft nodes become intermediate literals")
    (is (every? #{:literal :node} encs))))

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
    ;; 2c′ may bottom out at intermediate FT lits covering several strings,
    ;; or emit per-string literals — either way we get many literals.
    (is (or (pos? (count string-lits))
            (pos? (:literals sum)))
        "child content arrives as literals (strings and/or ft/*)")
    (is (> (:items sum) 1) "walk expands under large vector root")
    ;; Receiver can apply all chunks and hold the root hash
    (let [st2 (store/mem-store)
          chunks (pack/pack-items items budget)]
      (doseq [ch chunks]
        (pack/apply-chunk! st2 ch))
      (is (store/s-has? st2 h)))))

(deftest size-bytes-at-budget-is-literal-one-over-is-node
  (let [st (store/mem-store)
        fit (coll/string-with-store st (apply str (repeat 1024 \x)))
        over (coll/string-with-store st (apply str (repeat 1025 \x)))
        fh (types/dacite-hash fit)
        oh (types/dacite-hash over)]
    (is (= 1024 (pack/size-cue (store/s-get st fh))))
    (is (= :literal (:encoding (pack/encode-item st fh (store/s-get st fh)))))
    (is (pack/clearly-oversized? (store/s-get st oh) pack/default-budget))
    (is (= :node (:encoding (pack/encode-item st oh (store/s-get st oh)))))))

(deftest type-run-of-short-strings-fits-tight-budget
  ;; Pre-RLE: 40 tagged string lits exceeded 500 wire bytes while size-bytes
  ;; still fit. A string run is dense enough that the root is a literal.
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (map #(str "item-" %) (range 40)))
        h (types/dacite-hash v)
        entry (store/s-get st h)
        budget 500
        item (pack/encode-item st h entry budget)
        form (pack/literal-of st h)]
    (is (<= (pack/size-cue entry) budget) "size cue fits")
    (is (= :literal (:encoding item)))
    (is (= "run" (:type (first (:body form)))))
    (is (= "string" (get-in (first (:body form)) [:body :of])))))

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

;; ---------------------------------------------------------------------------
;; 2c′ — intermediate FT/HAMT literals
;; ---------------------------------------------------------------------------

(deftest intermediate-ft-digit-round-trip
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (range 12))
        snap (store/s-snapshot st)
        singles (filter (fn [[_ e]] (= "ft/single" (types/entry-type e))) snap)
        digits (filter (fn [[_ e]] (= "ft/digit" (types/entry-type e))) snap)]
    (is (empty? singles) "ft/single fully removed")
    (is (seq digits))
    (doseq [[fh _] (take 3 digits)]
      (let [[h1 h2 form] (round-trip-hash st fh)]
        (is (= "ft/digit" (:type form)))
        (is (vector? (:body form)))
        (is (= h1 h2) "digit of bare leaves rebuilds to same hash")))))

(deftest intermediate-ft-deep-round-trip
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (range 20))
        snap (store/s-snapshot st)
        deeps (filter (fn [[_ e]] (= "ft/deep" (types/entry-type e))) snap)]
    (is (seq deeps))
    (doseq [[fh _] deeps]
      (let [[h1 h2 form] (round-trip-hash st fh)]
        (is (= "ft/deep" (:type form)))
        (is (= h1 h2))))))

(deftest intermediate-literal-bottom-out-reduces-items
  ;; Large vector under tight budget: without intermediate lits, many FT nodes;
  ;; with 2c′, digit/deep leaves can collapse into fewer items.
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (range 40))
        h (types/dacite-hash v)
        budget 200
        sum (pack/encode-summary st h budget)
        ft-lits (filter (fn [it]
                          (and (= :literal (:encoding it))
                               (str/starts-with? (str (:type it)) "ft/")))
                        (:items (pack/encode-reachable st h #{} budget)))]
    (is (= :node (:encoding (pack/encode-item st h (store/s-get st h) budget))))
    (is (pos? (count ft-lits)) "walk bottoms out at intermediate ft literals")
    (is (pos? (:literals sum)))
    ;; Apply mixed pack and recover root
    (let [st2 (store/mem-store)
          {:keys [items]} (pack/encode-reachable st h #{} budget)]
      (doseq [ch (pack/pack-items items budget)]
        (pack/apply-chunk! st2 ch))
      (is (store/s-has? st2 h)))))

(deftest intermediate-hamt-entry-round-trip
  (let [st (store/mem-store)
        m (coll/hash-map-with-store st "a" 1 "b" 2)
        snap (store/s-snapshot st)
        entries (filter (fn [[_ e]] (= "hamt/entry" (types/entry-type e))) snap)]
    (is (seq entries))
    (doseq [[eh _] entries]
      (let [[h1 h2 form] (round-trip-hash st eh)]
        (is (= "hamt/entry" (:type form)))
        (is (= h1 h2))))))

(deftest pack-under-bfs-single-chunk
  (let [st (store/mem-store)
        t (todo/build st (todo/seed-items))
        h (types/dacite-hash t)
        ch (pack/pack-under st h #{} 1024)]
    (is (true? (:dacite.wire/chunk-v1 ch)))
    (is (= 1 (count (:items ch))) "small todo root is one literal chunk")
    (let [st2 (store/mem-store)]
      (pack/apply-chunk! st2 ch)
      (is (store/s-has? st2 h)))
    (let [back (bin/decode-pack-edn (bin/encode-pack-edn ch))
          st3 (store/mem-store)]
      (is (= :literal (:encoding (first (:items back)))))
      (pack/apply-chunk! st3 back)
      (is (store/s-has? st3 h)
          "wire-v1 literal of a todo vector rematerializes at the claimed hash")))
  (let [st (store/mem-store)
        s (coll/string-with-store st (apply str (repeat 3000 \x)))
        h (types/dacite-hash s)
        ch (pack/pack-under st h #{} 1024)
        n-wire (pack/wire-chunk-size ch)
        encs (mapv :encoding (:items ch))
        types (mapv (fn [it]
                      (or (:type it)
                          (when (= :node (:encoding it))
                            (first (:body it)))))
                    (:items ch))]
    (is (true? (:dacite.wire/chunk-v1 ch)))
    (is (> (count (:items ch)) 2)
        "wire-sized seal BFS-es past string+ft/deep into digits/chars")
    (is (some #{:literal} encs)
        (str "neighborhood includes literals, got " types))
    (is (>= n-wire 1024)
        "large string seals one soft-budget chunk on sent (wire) bytes")
    (is (<= n-wire (* 2 1024))
        "include-then-seal overshoot stays within ~2×budget")))

(deftest long-string-pack-covers-explorer-preview-prefix
  ;; 24-leaf ft/nodes used to ship as ~856-byte :nodes (conj-right dry-run
  ;; fail). Rebuilding via make-node! makes them literals; children-first
  ;; fill then puts a 64-char prefix in one GET.
  (let [st (store/mem-store)
        s (coll/string-with-store st (apply str (repeat 1893 \x)))
        h (types/dacite-hash s)
        ch (pack/pack-under st h)
        st2 (store/mem-store)
        _ (pack/apply-chunk! st2 ch)
        root (:root (types/entry-data (store/s-get st2 h)))
        n-hit (count (filter #(store/s-has? st2 %)
                             (take 64 (ft/ft-seq st2 root))))]
    (is (>= n-hit 64)
        (str "explorer 64-char preview should be in the first pack; got " n-hit))
    (is (some (fn [it]
                (and (= :literal (:encoding it))
                     (= "ft/node" (:type it))))
              (:items ch))
        "bottom-level ft/node of char leaves is a literal")
    (let [st3 (store/mem-store)
          back (bin/decode-pack-edn (bin/encode-pack-edn ch))]
      (pack/apply-chunk! st3 back)
      (is (store/s-has? st3 h)
          "wire-v1 ft/node literal applies at the claimed hash"))))

(deftest pack-under-budget-zero-is-single-item
  (let [st (store/mem-store)
        v (coll/vector-with-store st 1 2 3)
        h (types/dacite-hash v)
        ch (pack/pack-under st h #{} 0)]
    (is (= 1 (count (:items ch)))
        "budget 0 seals after the asked hash")
    (let [st2 (store/mem-store)]
      (pack/apply-chunk! st2 ch)
      (is (store/s-has? st2 h)))))

(deftest remote-s-get-pack-fill
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [raw (remote/remote-store base-url)
            t (todo/build raw (todo/seed-items))
            h (types/dacite-hash t)
            _ (remote/remote-cas-root! raw nil h)
            cold (remote/remote-store base-url)]
        (stats/reset-stats!)
        (let [before (stats/get-stats)
              node (store/s-get cold h)
              after (stats/get-stats)
              d (stats/stats-diff before after)
              kinds (:by-kind after {})]
          (is (some? node))
          (is (= "vector" (types/entry-type node)))
          (is (pos? (get kinds :node-get 0)))
          (is (< (:requests d) 5)
              "pack-filled root get should not fan out many requests")
          ;; Further access hits pack-local
          (stats/reset-stats!)
          (store/s-get cold h)
          (is (zero? (:requests (stats/get-stats))))))
      (finally
        (stop!)))))

(deftest find-chunk-transport-outermost-wins
  (let [inner (reify pack/IChunkTransport
                (send-chunk! [_ _] {:ok true :layer :inner}))
        mid (pack/wrap-chunk-transport inner)
        outer (pack/wrap-chunk-transport mid)]
    (is (identical? outer (pack/find-chunk-transport outer)))
    (is (= :inner (:layer (pack/send-chunk! outer {:dacite.wire/chunk-v1 true :items []})))
        "delegating middleware forwards to real sink")))

(defrecord CountingChunkTransport [inner counter]
  pack/IChunkTransport
  (send-chunk! [_ chunk]
    (swap! counter inc)
    (pack/send-chunk! inner chunk)))

(deftest flush-from-and-middleware-see-every-chunk
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [raw (remote/remote-store base-url)
            n (atom 0)
            counted (->CountingChunkTransport raw n)
            local (store/mem-store)
            t (todo/build local (todo/seed-items))
            h (types/dacite-hash t)
            result (pack/flush-from! counted local h #{})]
        (is (pos? (:items result)))
        (is (pos? (:chunks result)))
        (is (= (:chunks result) @n)
            "every sealed chunk hits outermost send-chunk!")
        (is (store/s-has? raw h))
        ;; Second flush skips covered hashes
        (reset! n 0)
        (let [r2 (pack/flush-from! counted local h (:covered result))]
          (is (zero? (:items r2)))
          (is (zero? @n))))
      (finally
        (stop!)))))

(deftest write-back-flush-uses-flush-from
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [raw (remote/remote-store base-url)
            n (atom 0)
            counted (->CountingChunkTransport raw n)
            wb (client-cache/wrap counted :write-back)
            t (todo/build wb (todo/seed-items))
            h (types/dacite-hash t)
            uploaded (client-cache/flush-reachable! wb h)]
        (is (pos? uploaded))
        (is (pos? @n) "write-back flush sends via IChunkTransport middleware")
        (is (store/s-has? raw h))
        (is (zero? (client-cache/flush-reachable! wb h))
            "second flush is a no-op"))
      (finally
        (stop!)))))

(deftest intermediate-hamt-bitmap-round-trip-when-assured
  (let [st (store/mem-store)
        m (apply coll/hash-map-with-store st
                 (mapcat (fn [i] [(str "k" i) i]) (range 12)))
        snap (store/s-snapshot st)
        bitmaps (filter (fn [[_ e]] (= "hamt/bitmap" (types/entry-type e))) snap)
        results (mapv (fn [[bh _]]
                        (let [form (pack/literal-of st bh)
                              ok? (and form
                                       (= bh (pack/materialize-literal!
                                              (store/mem-store)
                                              (:type form)
                                              (:body form))))]
                          ok?))
                      bitmaps)]
    (is (seq bitmaps))
    ;; At least the full map root bitmap should round-trip; some intermediate
    ;; bitmaps may not (routing) — encode dry-run keeps those as :node.
    (is (some true? results) "some bitmaps assured")
    (let [encs (mapv (fn [[bh e]]
                       (:encoding (pack/encode-item st bh e)))
                     bitmaps)]
      (is (every? #{:literal :node} encs))
      (is (some #{:literal} encs)))))

(deftest host-string-is-not-truncated
  (let [st (store/mem-store)
        s (apply str (repeat 200 "x"))
        dv (coll/string-with-store st s)
        form (pack/literal-of st (types/dacite-hash dv))]
    (is (= "string" (:type form)))
    (is (= 200 (count (:body form)))
        "host-string must keep every character (not apply-str's 52-arg chunk)")))

(deftest long-todo-title-flushes-over-http
  (let [title (apply str (repeat 400 "and going "))
        rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted
                                                     :throttle false})]
    (try
      (let [r (v/root-ref (store/remote-rooted-store base-url {:policy :write-back}))]
        (v/ref-cas! r nil (todo/empty-todos r))
        (v/ref-swap! r todo/add-todo title false)
        (let [cold (v/root-ref (store/remote-rooted-store base-url {:policy :none}))
              t (v/ref-deref cold)]
          (is (= 1 (v/count t)))
          (is (= (count title) (count (todo/title-str (v/nth t 0)))))))
      (finally
        (stop!)))))
