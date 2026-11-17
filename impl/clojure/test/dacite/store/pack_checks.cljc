(ns dacite.store.pack-checks
  "Pack Layer-1 checks (chunks, literals, run-length forms, budgets) that
   run on the JVM and on nbb.

   These were the bodies of `dacite.store.pack-test`. The JVM runs them from
   that namespace, one deftest each under its old name. nbb runs them from
   `dacite.store.store-units-cljs-test` (`npm run store-units:test`).
   The seven tests that start the HTTP service (`dacite.service`,
   `dacite.store.remote`) stay JVM only, in `dacite.store.pack-test`.

   `is` and `testing` are `dacite.store.check`: a check records problems,
   it does not count assertions."
  (:require [clojure.string :as str]
            [dacite.examples.todo :as todo]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.check :refer [is testing throws?]]
            [dacite.store.pack :as pack]
            [dacite.value.collections :as coll]
            [dacite.value.finger-tree :as ft]
            [dacite.value.scalar :as scalar]
            [dacite.value.types :as types]
            [dacite.wire.binary :as bin]))

(defn- host-bytes
  "Host bytes for the unsigned ints `nums`."
  [nums]
  #?(:clj (byte-array (map unchecked-byte nums))
     :cljs (js/Uint8Array.from (clj->js nums))))

(defn- host-bytes?
  [x]
  #?(:clj (bytes? x) :cljs (instance? js/Uint8Array x)))

(defn- nodes-under
  "Pairs of [hash node] reachable from `roots`. One `s-get` per live hash."
  [st & roots]
  (mapv (fn [hk]
          (let [h (gc/->hash hk)]
            [h (store/await (store/s-get st h))]))
        (reduce (fn [s root]
                  (into s (gc/mark-reachable st root)))
                #{}
                roots)))

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

(defn pack-items-soft-budget
  [_]
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

(defn apply-chunk-puts-nodes
  [_]
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
    (is (= ["bool" true] (store/await (store/s-get st h1))))
    (is (= ["i64" 7] (store/await (store/s-get st h2))))))

(defn literal-of-and-materialize-scalars
  [_]
  (let [st (store/mem-store)
        h (scalar/put-scalar! st "i64" 42)
        form (pack/literal-of st h)]
    (is (= "i64" (:type form)))
    (is (= 42 (:body form)))
    (let [[h1 h2] (round-trip-hash st h)]
      (is (= h1 h2)))))

(defn literal-of-and-materialize-string
  [_]
  (let [st (store/mem-store)
        s (coll/string-with-store st "hello")
        h (types/dacite-hash s)
        form (pack/literal-of st h)]
    (is (= "string" (:type form)))
    (is (= "hello" (:body form)))
    (is (= h (second (round-trip-hash st h))))))

(defn literal-of-medium-string
  [_]
  (let [st (store/mem-store)
        body (apply str (repeat 100 "x"))
        s (coll/string-with-store st body)
        h (types/dacite-hash s)
        form (pack/literal-of st h)]
    (is (= 100 (count (:body form))))
    (is (= body (:body form)))
    (is (= h (second (round-trip-hash st h))))))

(defn literal-of-vector-is-recursive-typed
  [_]
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

(defn literal-of-nested-vector-of-strings
  [_]
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
    (let [item (pack/encode-item st h (store/await (store/s-get st h)))]
      (is (= :literal (:encoding item)))
      (let [st2 (store/mem-store)]
        (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
        (is (store/await (store/s-has? st2 h)))))))

(defn rle-lits-n1-stays-unwrapped
  [_]
  (let [st (store/mem-store)
        v (coll/vector-with-store st 42)
        form (pack/literal-of st (types/dacite-hash v))
        el (first (:body form))]
    (is (= 1 (count (:body form))))
    (is (= "i64" (:type el)))
    (is (= 42 (:body el)))))

(defn rle-lits-mixed-types-split-runs
  [_]
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

(defn rle-char-run-on-string-spine
  [_]
  (let [st (store/mem-store)
        s (coll/string-with-store st "abcdefghij")
        snap (nodes-under st (types/dacite-hash s))
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

(defn rle-all-equal-chars-are-repeat
  [_]
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

(defn rle-mixed-chars-are-run-not-split-repeats
  [_]
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

(defn rle-bool-vector-all-false-is-repeat
  [_]
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

(defn rle-char-run-wire-round-trip
  [_]
  (let [st (store/mem-store)
        s (coll/string-with-store st (apply str (repeat 24 \x)))
        snap (nodes-under st (types/dacite-hash s))
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
        (is (store/await (store/s-has? st2 fh)))))))

(defn literal-of-map-and-set
  [_]
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

(defn literal-of-empty-collections
  [_]
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

(defn literal-of-spine-is-intermediate-form
  [_]
  ;; 2c′: spine nodes have leaf-payload literals (not nil).
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (range 20))
        snap (nodes-under st (types/dacite-hash v))
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

(defn encode-and-apply-literal-scalars
  [_]
  (let [st (store/mem-store)
        h (scalar/put-scalar! st "i64" 42)
        item (pack/encode-item st h (store/await (store/s-get st h)))]
    (is (= :literal (:encoding item)))
    (is (= "i64" (:type item)))
    (is (= 42 (:body item)))
    (let [st2 (store/mem-store)
          r (pack/apply-chunk! st2 (pack/make-chunk 1024 [item]))]
      (is (= 1 (:literals r)))
      (is (= ["i64" 42] (store/await (store/s-get st2 h)))))))

(defn encode-and-apply-literal-string
  [_]
  (let [st (store/mem-store)
        s (coll/string-with-store st "hello")
        h (types/dacite-hash s)
        item (pack/encode-item st h (store/await (store/s-get st h)))]
    (is (= :literal (:encoding item)))
    (is (= "string" (:type item)))
    (is (= "hello" (:body item)))
    (let [st2 (store/mem-store)
          r (pack/apply-chunk! st2 (pack/make-chunk 1024 [item]))]
      (is (= 1 (:literals r)))
      (is (store/await (store/s-has? st2 h))))))

(defn encode-and-apply-medium-string-literal
  [_]
  (let [st (store/mem-store)
        body (apply str (repeat 100 "x"))
        s (coll/string-with-store st body)
        h (types/dacite-hash s)
        item (pack/encode-item st h (store/await (store/s-get st h)))
        {:keys [items]} (pack/encode-reachable st h)]
    (is (= :literal (:encoding item)))
    (is (= 100 (count (:body item))))
    (is (= 1 (count items)))
    (let [st2 (store/mem-store)]
      (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
      (is (store/await (store/s-has? st2 h))))))

(defn encode-and-apply-literal-vector-map-set
  [_]
  (testing "vector"
    (let [st (store/mem-store)
          v (coll/vector-with-store st 1 2 3)
          h (types/dacite-hash v)
          item (pack/encode-item st h (store/await (store/s-get st h)))]
      (is (= :literal (:encoding item)))
      (is (every? map? (:body item)))
      (let [st2 (store/mem-store)]
        (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
        (is (store/await (store/s-has? st2 h))))))
  (testing "map"
    (let [st (store/mem-store)
          m (coll/hash-map-with-store st "a" 1 "b" 2)
          h (types/dacite-hash m)
          item (pack/encode-item st h (store/await (store/s-get st h)))]
      (is (= :literal (:encoding item)))
      (let [st2 (store/mem-store)]
        (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
        (is (store/await (store/s-has? st2 h))))))
  (testing "set"
    (let [st (store/mem-store)
          s (coll/dacite-set-with-store st 1 2 3)
          h (types/dacite-hash s)
          item (pack/encode-item st h (store/await (store/s-get st h)))]
      (is (= :literal (:encoding item)))
      (let [st2 (store/mem-store)]
        (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [item])))))
        (is (store/await (store/s-has? st2 h)))))))

(defn materialize-accepts-2b-flat-host-bodies
  [_]
  ;; Wire compat: flat host bodies from 2b still materialize.
  (let [st (store/mem-store)
        v (coll/vector-with-store st 1 2 3)
        h (types/dacite-hash v)
        flat (pack/literal-item h "vector" [1 2 3])
        st2 (store/mem-store)]
    (is (= 1 (:literals (pack/apply-chunk! st2 (pack/make-chunk 1024 [flat])))))
    (is (store/await (store/s-has? st2 h)))))

(defn literal-hash-mismatch-throws
  [_]
  (let [st (store/mem-store)
        bad (pack/literal-item (store/hex->hash (apply str (repeat 64 "c")))
                               "i64" 1)
        chunk (pack/make-chunk 1024 [bad])]
    (is (throws? #"literal hash mismatch" #(pack/apply-chunk! st chunk)))))

(defn encode-reachable-prefers-literal-and-skips-children
  [_]
  (let [st (store/mem-store)
        s (coll/string-with-store st "ab")
        h (types/dacite-hash s)
        live (count (gc/mark-reachable st h))
        {:keys [items covered]} (pack/encode-reachable st h)]
    (is (= 1 (count items)))
    (is (= :literal (:encoding (first items))))
    (is (= "ab" (:body (first items))))
    (is (>= (count covered) live))
    (is (contains? covered (store/hash->hex h)))))

(defn ft-internal-nodes-may-be-intermediate-literals
  [_]
  ;; 2c′: spine nodes with fitting leaf payloads can be :literal when rebuild matches.
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (range 20))
        _h (types/dacite-hash v)
        snap (nodes-under st (types/dacite-hash v))
        ft-entries (filter (fn [[_ e]]
                             (str/starts-with? (str (types/entry-type e)) "ft/"))
                           snap)
        encs (mapv (fn [[fh entry]]
                     (:encoding (pack/encode-item st fh entry)))
                   ft-entries)]
    (is (seq ft-entries))
    (is (some #{:literal} encs) "at least some ft nodes become intermediate literals")
    (is (every? #{:literal :node} encs))))

(defn todo-seed-round-trips-as-literals
  [_]
  (let [st (store/mem-store)
        t (todo/build st (todo/seed-items))
        h (types/dacite-hash t)
        form (pack/literal-of st h)
        item (pack/encode-item st h (store/await (store/s-get st h)))]
    (is (= "vector" (:type form)))
    (is (= :literal (:encoding item)))
    (is (= h (second (round-trip-hash st h))))
    (let [{:keys [items]} (pack/encode-reachable st h)]
      (is (= 1 (count items)) "whole todo tree as one literal"))))

(defn multi-page-blob-round-trips-as-byte-runs
  [_]
  (let [st (store/mem-store)
        n 9000                          ; a few 4 KiB leaf pages
        nums (mapv #(bit-and 0xff %) (range n))
        blob (coll/blob-with-store st (host-bytes nums))
        h (types/dacite-hash blob)
        {:keys [items]} (pack/encode-reachable st h)
        runs (vec (for [it items
                        :when (= :literal (:encoding it))
                        :let [body (:body it)]
                        item (if (sequential? body) body [])
                        :when (and (map? item)
                                   (= "run" (:type item))
                                   (= "u8" (get-in item [:body :of])))]
                    (get-in item [:body :values])))
        st2 (store/mem-store)]
    (is (seq runs))
    (is (every? host-bytes? runs))
    (doseq [ch (pack/pack-items items)]
      (pack/apply-chunk! st2 ch))
    (is (store/await (store/s-has? st2 h)))
    (is (= 255 (types/realize (coll/seq-nth st2 h 255))))
    (is (= 128 (types/realize (coll/seq-nth st2 h 128))))
    (is (= (bit-and 0xff (dec n)) (types/realize (coll/seq-nth st2 h (dec n)))))))

(defn large-string-refuses-literal-at-default-budget
  [_]
  (let [st (store/mem-store)
        body (apply str (repeat 12000 "x")) ; three 4 KiB leaf pages, over the 4 KiB default
        s (coll/string-with-store st body)
        h (types/dacite-hash s)
        entry (store/await (store/s-get st h))
        item (pack/encode-item st h entry)
        sum (pack/encode-summary st h)]
    (is (pack/clearly-oversized? entry pack/default-budget))
    (is (false? (boolean (pack/fits-literal? st h))))
    (is (= :node (:encoding item)) "root string over budget is :node")
    (is (pos? (:nodes sum)))
    (is (pos? (:literals sum)) "shared char leaf still a literal")
    (is (> (:items sum) 1) "walk expands FT spine under large string")))

(defn large-string-literal-when-budget-allows
  [_]
  (let [st (store/mem-store)
        body (apply str (repeat 3000 "x"))
        s (coll/string-with-store st body)
        h (types/dacite-hash s)
        item (pack/encode-item st h (store/await (store/s-get st h)) 5000)
        sum (pack/encode-summary st h 5000)]
    (is (= :literal (:encoding item)))
    (is (= 1 (:items sum)))
    (is (= 1 (:literals sum)))))

(defn large-vector-mixed-node-and-child-literals
  [_]
  ;; size-bytes of 40 short strings exceeds a tight budget → root :node,
  ;; each string child becomes its own :literal.
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (map #(str "item-" %) (range 40)))
        h (types/dacite-hash v)
        budget 200
        entry (store/await (store/s-get st h))
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
      (is (store/await (store/s-has? st2 h))))))

(defn size-bytes-at-budget-is-literal-one-over-is-node
  [_]
  (let [st (store/mem-store)
        fit (coll/string-with-store st (apply str (repeat pack/default-budget \x)))
        over (coll/string-with-store st (apply str (repeat (inc pack/default-budget) \x)))
        fh (types/dacite-hash fit)
        oh (types/dacite-hash over)]
    (is (= pack/default-budget (pack/size-cue (store/await (store/s-get st fh)))))
    (is (= :literal (:encoding (pack/encode-item st fh (store/await (store/s-get st fh))))))
    (is (pack/clearly-oversized? (store/await (store/s-get st oh)) pack/default-budget))
    (is (= :node (:encoding (pack/encode-item st oh (store/await (store/s-get st oh))))))))

(defn type-run-of-short-strings-fits-tight-budget
  [_]
  ;; Pre-RLE: 40 tagged string lits exceeded 500 wire bytes while size-bytes
  ;; still fit. A string run is dense enough that the root is a literal.
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (map #(str "item-" %) (range 40)))
        h (types/dacite-hash v)
        entry (store/await (store/s-get st h))
        budget 500
        item (pack/encode-item st h entry budget)
        form (pack/literal-of st h)]
    (is (<= (pack/size-cue entry) budget) "size cue fits")
    (is (= :literal (:encoding item)))
    (is (= "run" (:type (first (:body form)))))
    (is (= "string" (get-in (first (:body form)) [:body :of])))))

(defn encode-summary-reports-mixed-stats
  [_]
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (map str (range 20)))
        h (types/dacite-hash v)
        sum (pack/encode-summary st h 100)]
    (is (= 100 (:budget sum)))
    (is (pos? (:items sum)))
    (is (pos? (:chunks sum)))
    (is (= (+ (:literals sum) (:nodes sum)) (:items sum)))
    (is (pos? (:approx-bytes sum)))))

(defn intermediate-ft-digit-round-trip
  [_]
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (range 12))
        snap (nodes-under st (types/dacite-hash v))
        singles (filter (fn [[_ e]] (= "ft/single" (types/entry-type e))) snap)
        digits (filter (fn [[_ e]] (= "ft/digit" (types/entry-type e))) snap)]
    (is (empty? singles) "ft/single fully removed")
    (is (seq digits))
    (doseq [[fh _] (take 3 digits)]
      (let [[h1 h2 form] (round-trip-hash st fh)]
        (is (= "ft/digit" (:type form)))
        (is (vector? (:body form)))
        (is (= h1 h2) "digit of bare leaves rebuilds to same hash")))))

(defn intermediate-ft-deep-round-trip
  [_]
  (let [st (store/mem-store)
        v (apply coll/vector-with-store st (range 20))
        snap (nodes-under st (types/dacite-hash v))
        deeps (filter (fn [[_ e]] (= "ft/deep" (types/entry-type e))) snap)]
    (is (seq deeps))
    (doseq [[fh _] deeps]
      (let [[h1 h2 form] (round-trip-hash st fh)]
        (is (= "ft/deep" (:type form)))
        (is (= h1 h2))))))

(defn intermediate-literal-bottom-out-reduces-items
  [_]
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
    (is (= :node (:encoding (pack/encode-item st h (store/await (store/s-get st h)) budget))))
    (is (pos? (count ft-lits)) "walk bottoms out at intermediate ft literals")
    (is (pos? (:literals sum)))
    ;; Apply mixed pack and recover root
    (let [st2 (store/mem-store)
          {:keys [items]} (pack/encode-reachable st h #{} budget)]
      (doseq [ch (pack/pack-items items budget)]
        (pack/apply-chunk! st2 ch))
      (is (store/await (store/s-has? st2 h))))))

(defn intermediate-hamt-entry-round-trip
  [_]
  (let [st (store/mem-store)
        m (coll/hash-map-with-store st "a" 1 "b" 2)
        snap (nodes-under st (types/dacite-hash m))
        entries (filter (fn [[_ e]] (= "hamt/entry" (types/entry-type e))) snap)]
    (is (seq entries))
    (doseq [[eh _] entries]
      (let [[h1 h2 form] (round-trip-hash st eh)]
        (is (= "hamt/entry" (:type form)))
        (is (= h1 h2))))))

(defn pack-under-bfs-single-chunk
  [_]
  (let [st (store/mem-store)
        t (todo/build st (todo/seed-items))
        h (types/dacite-hash t)
        ch (pack/pack-under st h #{} 1024)]
    (is (true? (:dacite.wire/chunk-v1 ch)))
    (is (= 1 (count (:items ch))) "small todo root is one literal chunk")
    (let [st2 (store/mem-store)]
      (pack/apply-chunk! st2 ch)
      (is (store/await (store/s-has? st2 h))))
    (let [back (bin/decode-pack-edn (bin/encode-pack-edn ch))
          st3 (store/mem-store)]
      (is (= :literal (:encoding (first (:items back)))))
      (pack/apply-chunk! st3 back)
      (is (store/await (store/s-has? st3 h))
          "wire-v1 literal of a todo vector rematerializes at the claimed hash")))
  (let [st (store/mem-store)
        s (coll/string-with-store st (apply str (repeat 12000 \x)))
        h (types/dacite-hash s)
        budget pack/default-budget
        ch (pack/pack-under st h #{} budget)
        n-wire (pack/wire-chunk-size ch)
        encs (mapv :encoding (:items ch))
        types (mapv (fn [it]
                      (or (:type it)
                          (when (= :node (:encoding it))
                            (first (:body it)))))
                    (:items ch))]
    (is (true? (:dacite.wire/chunk-v1 ch)))
    (is (> (count (:items ch)) 2)
        "wire-sized seal BFS-es past string+ft/deep into digits")
    (is (some #{:literal} encs)
        (str "neighborhood includes literals, got " types))
    (is (some #{"ft/digit"} types)
        "4k char pages ship as ft/digit literals")
    (is (pos? n-wire))
    (is (<= n-wire (+ budget 4400))
        "include-then-seal overshoot stays within the budget plus one 4 KiB page item")))

(defn long-string-pack-covers-explorer-preview-prefix
  [_]
  ;; 24-leaf ft/nodes used to ship as ~856-byte :nodes (conj-right dry-run
  ;; fail). Rebuilding via make-node! makes them literals; children-first
  ;; fill then puts a 64-char prefix in one GET.
  (let [st (store/mem-store)
        s (coll/string-with-store st (apply str (repeat 5000 \x)))
        h (types/dacite-hash s)
        ch (pack/pack-under st h)
        st2 (store/mem-store)
        _ (pack/apply-chunk! st2 ch)
        root (:root (types/entry-data (store/await (store/s-get st2 h))))
        n-hit (count (filter #(store/await (store/s-has? st2 %))
                             (take 64 (ft/ft-seq st2 root))))]
    (is (>= n-hit 64)
        (str "explorer 64-char preview should be in the first pack; got " n-hit))
    (is (some (fn [it]
                (and (= :literal (:encoding it))
                     (= "ft/digit" (:type it))))
              (:items ch))
        "4k char pages ship as ft/digit literals")
    (let [st3 (store/mem-store)
          back (bin/decode-pack-edn (bin/encode-pack-edn ch))]
      (pack/apply-chunk! st3 back)
      (is (store/await (store/s-has? st3 h))
          "wire-v1 digit literal applies at the claimed hash"))))

(defn pack-under-budget-zero-is-single-item
  [_]
  (let [st (store/mem-store)
        v (coll/vector-with-store st 1 2 3)
        h (types/dacite-hash v)
        ch (pack/pack-under st h #{} 0)]
    (is (= 1 (count (:items ch)))
        "budget 0 seals after the asked hash")
    (let [st2 (store/mem-store)]
      (pack/apply-chunk! st2 ch)
      (is (store/await (store/s-has? st2 h))))))

(defn find-chunk-transport-outermost-wins
  [_]
  (let [inner (reify pack/IChunkTransport
                (send-chunk! [_ _] {:ok true :layer :inner}))
        mid (pack/wrap-chunk-transport inner)
        outer (pack/wrap-chunk-transport mid)]
    (is (identical? outer (pack/find-chunk-transport outer)))
    (is (= :inner (:layer (pack/send-chunk! outer {:dacite.wire/chunk-v1 true :items []})))
        "delegating middleware forwards to real sink")))

(defn intermediate-hamt-bitmap-round-trip-when-assured
  [_]
  (let [st (store/mem-store)
        m (apply coll/hash-map-with-store st
                 (mapcat (fn [i] [(str "k" i) i]) (range 12)))
        snap (nodes-under st (types/dacite-hash m))
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
    ;; Every bitmap is canonical for its keys (issue #12), so every one round-trips.
    (is (every? true? results) "all bitmaps assured")
    (let [encs (mapv (fn [[bh e]]
                       (:encoding (pack/encode-item st bh e)))
                     bitmaps)]
      (is (every? #{:literal :node} encs))
      (is (some #{:literal} encs)))))

(defn host-string-is-not-truncated
  [_]
  (let [st (store/mem-store)
        s (apply str (repeat 200 "x"))
        dv (coll/string-with-store st s)
        form (pack/literal-of st (types/dacite-hash dv))]
    (is (= "string" (:type form)))
    (is (= 200 (count (:body form)))
        "host-string must keep every character (not apply-str's 52-arg chunk)")))

(def checks
  {"pack-items-soft-budget" pack-items-soft-budget
   "apply-chunk-puts-nodes" apply-chunk-puts-nodes
   "literal-of-and-materialize-scalars" literal-of-and-materialize-scalars
   "literal-of-and-materialize-string" literal-of-and-materialize-string
   "literal-of-medium-string" literal-of-medium-string
   "literal-of-vector-is-recursive-typed" literal-of-vector-is-recursive-typed
   "literal-of-nested-vector-of-strings" literal-of-nested-vector-of-strings
   "rle-lits-n1-stays-unwrapped" rle-lits-n1-stays-unwrapped
   "rle-lits-mixed-types-split-runs" rle-lits-mixed-types-split-runs
   "rle-char-run-on-string-spine" rle-char-run-on-string-spine
   "rle-all-equal-chars-are-repeat" rle-all-equal-chars-are-repeat
   "rle-mixed-chars-are-run-not-split-repeats" rle-mixed-chars-are-run-not-split-repeats
   "rle-bool-vector-all-false-is-repeat" rle-bool-vector-all-false-is-repeat
   "rle-char-run-wire-round-trip" rle-char-run-wire-round-trip
   "literal-of-map-and-set" literal-of-map-and-set
   "literal-of-empty-collections" literal-of-empty-collections
   "literal-of-spine-is-intermediate-form" literal-of-spine-is-intermediate-form
   "encode-and-apply-literal-scalars" encode-and-apply-literal-scalars
   "encode-and-apply-literal-string" encode-and-apply-literal-string
   "encode-and-apply-medium-string-literal" encode-and-apply-medium-string-literal
   "encode-and-apply-literal-vector-map-set" encode-and-apply-literal-vector-map-set
   "materialize-accepts-2b-flat-host-bodies" materialize-accepts-2b-flat-host-bodies
   "literal-hash-mismatch-throws" literal-hash-mismatch-throws
   "encode-reachable-prefers-literal-and-skips-children" encode-reachable-prefers-literal-and-skips-children
   "ft-internal-nodes-may-be-intermediate-literals" ft-internal-nodes-may-be-intermediate-literals
   "todo-seed-round-trips-as-literals" todo-seed-round-trips-as-literals
   "multi-page-blob-round-trips-as-byte-runs" multi-page-blob-round-trips-as-byte-runs
   "large-string-refuses-literal-at-default-budget" large-string-refuses-literal-at-default-budget
   "large-string-literal-when-budget-allows" large-string-literal-when-budget-allows
   "large-vector-mixed-node-and-child-literals" large-vector-mixed-node-and-child-literals
   "size-bytes-at-budget-is-literal-one-over-is-node" size-bytes-at-budget-is-literal-one-over-is-node
   "type-run-of-short-strings-fits-tight-budget" type-run-of-short-strings-fits-tight-budget
   "encode-summary-reports-mixed-stats" encode-summary-reports-mixed-stats
   "intermediate-ft-digit-round-trip" intermediate-ft-digit-round-trip
   "intermediate-ft-deep-round-trip" intermediate-ft-deep-round-trip
   "intermediate-literal-bottom-out-reduces-items" intermediate-literal-bottom-out-reduces-items
   "intermediate-hamt-entry-round-trip" intermediate-hamt-entry-round-trip
   "pack-under-bfs-single-chunk" pack-under-bfs-single-chunk
   "long-string-pack-covers-explorer-preview-prefix" long-string-pack-covers-explorer-preview-prefix
   "pack-under-budget-zero-is-single-item" pack-under-budget-zero-is-single-item
   "find-chunk-transport-outermost-wins" find-chunk-transport-outermost-wins
   "intermediate-hamt-bitmap-round-trip-when-assured" intermediate-hamt-bitmap-round-trip-when-assured
   "host-string-is-not-truncated" host-string-is-not-truncated})
