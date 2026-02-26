(ns dacite.finger-tree-test
  "Tests for Dacite pure finger tree implementation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dacite.finger-tree :as ft]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn lookup-value
  "Look up a value-hash in a dacite-map and return the actual value."
  [dacite-map value-hash]
  (when value-hash
    (get dacite-map value-hash)))

(defn tree-values
  "Get all values from a tree (looking up each hash)."
  [[dacite-map _ :as tree]]
  (mapv #(lookup-value dacite-map %) (ft/to-vec tree)))

(defn value-data
  "Extract the data portion from a dacite value [type data]."
  [[_ data]]
  data)

;; =============================================================================
;; Basic operations
;; =============================================================================

(deftest test-empty-tree
  (testing "empty tree"
    (let [tree (ft/finger-tree)]
      (is (ft/tree-empty? tree))
      (is (= 0 (ft/tree-count tree)))
      (is (= 0 (ft/tree-size-bytes tree)))
      (is (nil? (ft/tree-first tree)))
      (is (nil? (ft/tree-last tree)))
      (is (= [] (ft/to-vec tree))))))

(deftest test-single-element
  (testing "single element tree"
    (let [[m0 h0] (ft/finger-tree)
          [m1 vh] (ft/add-value m0 ["i64" 42])
          tree (ft/conj-right [m1 h0] vh)]
      (is (not (ft/tree-empty? tree)))
      (is (= 1 (ft/tree-count tree)))
      (is (= ["i64" 42] (lookup-value (first tree) (ft/tree-first tree))))
      (is (= ["i64" 42] (lookup-value (first tree) (ft/tree-last tree))))
      (is (= [["i64" 42]] (tree-values tree))))))

(deftest test-conj-right
  (testing "adding elements to the right"
    (let [tree (ft/from-seq (map #(vector "i64" %) (range 10)))]
      (is (= 10 (ft/tree-count tree)))
      (is (= 0 (value-data (lookup-value (first tree) (ft/tree-first tree)))))
      (is (= 9 (value-data (lookup-value (first tree) (ft/tree-last tree)))))
      (is (= (mapv #(vector "i64" %) (range 10)) (tree-values tree))))))

(deftest test-conj-left
  (testing "adding elements to the left"
    (let [tree (reduce (fn [[m h] v]
                         (let [[m' vh] (ft/add-value m ["i64" v])]
                           (ft/conj-left [m' h] vh)))
                       (ft/finger-tree)
                       (range 10))]
      (is (= 10 (ft/tree-count tree)))
      ;; Elements added left-to-right end up reversed
      (is (= 9 (value-data (lookup-value (first tree) (ft/tree-first tree)))))
      (is (= 0 (value-data (lookup-value (first tree) (ft/tree-last tree)))))
      (is (= (mapv #(vector "i64" %) (reverse (range 10))) (tree-values tree))))))

(deftest test-rest
  (testing "removing first element"
    (let [tree (ft/from-seq (map #(vector "i64" %) (range 5)))
          rest-tree (ft/tree-rest tree)]
      (is (= 4 (ft/tree-count rest-tree)))
      (is (= (mapv #(vector "i64" %) [1 2 3 4]) (tree-values rest-tree))))))

(deftest test-butlast
  (testing "removing last element"
    (let [tree (ft/from-seq (map #(vector "i64" %) (range 5)))
          butlast-tree (ft/tree-butlast tree)]
      (is (= 4 (ft/tree-count butlast-tree)))
      (is (= (mapv #(vector "i64" %) [0 1 2 3]) (tree-values butlast-tree))))))

;; =============================================================================
;; Measure accumulation
;; =============================================================================

(deftest test-measure-accumulation
  (testing "count accumulates correctly"
    (let [tree (ft/from-seq (map #(vector "i64" %) (range 100)))]
      (is (= 100 (ft/tree-count tree)))))

  (testing "size-bytes accumulates correctly via dacite-size"
    (let [[m0 h0] (ft/finger-tree)
          [m1 v1] (ft/add-value m0 ["i8" 1])    ;; 1 byte
          [m2 v2] (ft/add-value m1 ["i16" 2])   ;; 2 bytes
          [m3 v3] (ft/add-value m2 ["i32" 3])   ;; 4 bytes
          tree (-> (ft/conj-right [m3 h0] v1)
                   (ft/conj-right v2)
                   (ft/conj-right v3))]
      (is (= 3 (ft/tree-count tree)))
      (is (= 7 (ft/tree-size-bytes tree))))))

;; =============================================================================
;; Large trees (trigger spine usage)
;; =============================================================================

(deftest test-large-tree
  (testing "tree with 1000 elements"
    (let [tree (ft/from-seq (map #(vector "i64" %) (range 1000)))]
      (is (= 1000 (ft/tree-count tree)))
      (is (= 0 (value-data (lookup-value (first tree) (ft/tree-first tree)))))
      (is (= 999 (value-data (lookup-value (first tree) (ft/tree-last tree)))))
      (is (= (mapv #(vector "i64" %) (range 1000)) (tree-values tree)))))

  (testing "tree with 10000 elements"
    (let [tree (ft/from-seq (map #(vector "i64" %) (range 10000)))]
      (is (= 10000 (ft/tree-count tree)))
      (is (= 0 (value-data (lookup-value (first tree) (ft/tree-first tree)))))
      (is (= 9999 (value-data (lookup-value (first tree) (ft/tree-last tree))))))))

;; =============================================================================
;; Deque operations (stack/queue behavior)
;; =============================================================================

(deftest test-deque-operations
  (testing "LIFO (stack) from left"
    (let [[m0 h0] (ft/finger-tree)
          [m1 v1] (ft/add-value m0 ["i64" 1])
          [m2 v2] (ft/add-value m1 ["i64" 2])
          [m3 v3] (ft/add-value m2 ["i64" 3])
          tree (-> (ft/conj-left [m3 h0] v1)
                   (ft/conj-left v2)
                   (ft/conj-left v3))]
      (is (= 3 (value-data (lookup-value (first tree) (ft/tree-first tree)))))
      (is (= 2 (value-data (lookup-value (first (ft/tree-rest tree))
                                         (ft/tree-first (ft/tree-rest tree))))))
      (is (= 1 (value-data (lookup-value (first (ft/tree-rest (ft/tree-rest tree)))
                                         (ft/tree-first (ft/tree-rest (ft/tree-rest tree)))))))))

  (testing "FIFO (queue) - add right, take left"
    (let [[m0 h0] (ft/finger-tree)
          [m1 v1] (ft/add-value m0 ["i64" 1])
          [m2 v2] (ft/add-value m1 ["i64" 2])
          [m3 v3] (ft/add-value m2 ["i64" 3])
          tree (-> (ft/conj-right [m3 h0] v1)
                   (ft/conj-right v2)
                   (ft/conj-right v3))]
      (is (= 1 (value-data (lookup-value (first tree) (ft/tree-first tree)))))
      (is (= 2 (value-data (lookup-value (first (ft/tree-rest tree))
                                         (ft/tree-first (ft/tree-rest tree))))))
      (is (= 3 (value-data (lookup-value (first (ft/tree-rest (ft/tree-rest tree)))
                                         (ft/tree-first (ft/tree-rest (ft/tree-rest tree))))))))))

;; =============================================================================
;; Concatenation
;; =============================================================================

(deftest test-concat
  (testing "concatenating two trees"
    (let [t1 (ft/from-seq [["kw" "a"] ["kw" "b"] ["kw" "c"]])
          t2 (ft/from-seq [["kw" "x"] ["kw" "y"] ["kw" "z"]])
          tc (ft/tree-concat t1 t2)]
      (is (= 6 (ft/tree-count tc)))
      (is (= [["kw" "a"] ["kw" "b"] ["kw" "c"] ["kw" "x"] ["kw" "y"] ["kw" "z"]]
             (tree-values tc))))))

;; =============================================================================
;; Pure data structure (no cache dependency)
;; =============================================================================

(deftest test-pure-structure
  (testing "tree is pure [map hash] tuple"
    (let [tree (ft/from-seq [["i64" 1] ["i64" 2]])]
      (is (vector? tree))
      (is (= 2 (count tree)))
      (is (map? (first tree)))      ;; dacite-map
      (is (vector? (second tree)))  ;; root-hash (4 longs)
      (is (= 4 (count (second tree))))))

  (testing "map contains all nodes"
    (let [[dacite-map _] (ft/from-seq [["i64" 1] ["i64" 2]])]
      ;; Should have: empty, 2 values, 2 leaves, digits, deep node, etc.
      (is (> (count dacite-map) 5))))

  (testing "identical inputs produce identical hashes"
    (let [[_ h1] (ft/from-seq [["i64" 42]])
          [_ h2] (ft/from-seq [["i64" 42]])]
      (is (= h1 h2)))))

;; =============================================================================
;; Property tests
;; =============================================================================

(def gen-small-int (gen/choose 0 100))

(defspec conj-right-preserves-count 50
  (prop/for-all [values (gen/vector gen-small-int)]
                (let [tree (ft/from-seq (map #(vector "i64" %) values))]
                  (= (count values) (ft/tree-count tree)))))

(defspec conj-right-preserves-order 50
  (prop/for-all [values (gen/vector gen-small-int)]
                (let [tree (ft/from-seq (map #(vector "i64" %) values))
                      result (mapv #(value-data (lookup-value (first tree) %))
                                   (ft/to-vec tree))]
                  (= values result))))

(defspec rest-removes-first 50
  (prop/for-all [values (gen/not-empty (gen/vector gen-small-int))]
                (let [tree (ft/from-seq (map #(vector "i64" %) values))
                      rest-tree (ft/tree-rest tree)
                      result (mapv #(value-data (lookup-value (first rest-tree) %))
                                   (ft/to-vec rest-tree))]
                  (= (vec (rest values)) result))))

(defspec butlast-removes-last 50
  (prop/for-all [values (gen/not-empty (gen/vector gen-small-int))]
                (let [tree (ft/from-seq (map #(vector "i64" %) values))
                      butlast-tree (ft/tree-butlast tree)
                      result (mapv #(value-data (lookup-value (first butlast-tree) %))
                                   (ft/to-vec butlast-tree))]
                  (= (vec (butlast values)) result))))

;; =============================================================================
;; Edge cases for coverage
;; =============================================================================

(deftest test-rest-on-empty
  (testing "tree-rest on empty tree returns empty tree"
    (let [empty-tree (ft/finger-tree)
          rest-tree (ft/tree-rest empty-tree)]
      (is (ft/tree-empty? rest-tree))
      (is (= 0 (ft/tree-count rest-tree))))))

(deftest test-butlast-on-empty
  (testing "tree-butlast on empty tree returns empty tree"
    (let [empty-tree (ft/finger-tree)
          butlast-tree (ft/tree-butlast empty-tree)]
      (is (ft/tree-empty? butlast-tree))
      (is (= 0 (ft/tree-count butlast-tree))))))

(deftest test-butlast-spine-pulling
  (testing "tree-butlast that exhausts right digit and pulls from spine"
    ;; Create tree with enough elements to have spine nodes
    ;; Digit max = 32, so ~65 elements means: left(32) + spine(1 node) + right(32)
    ;; Then butlast repeatedly to exhaust right digit and trigger spine pull
    (let [tree (ft/from-seq (map #(vector "i64" %) (range 65)))
          ;; Remove 33 elements from right (exhausts right digit, pulls from spine)
          tree-after (nth (iterate ft/tree-butlast tree) 33)]
      (is (= 32 (ft/tree-count tree-after)))
      ;; Values should be the first 32
      (is (= (mapv #(vector "i64" %) (range 32)) (tree-values tree-after))))))

(deftest test-rest-spine-pulling
  (testing "tree-rest that exhausts left digit and pulls from spine"
    ;; Similar setup for tree-rest
    (let [tree (ft/from-seq (map #(vector "i64" %) (range 65)))
          ;; Remove 33 elements from left (exhausts left digit, pulls from spine)
          tree-after (nth (iterate ft/tree-rest tree) 33)]
      (is (= 32 (ft/tree-count tree-after)))
      ;; Values should be the last 32 (indices 33-64)
      (is (= (mapv #(vector "i64" %) (range 33 65)) (tree-values tree-after))))))

(deftest test-conj-left-overflow
  (testing "conj-left with >32 elements triggers left digit overflow"
    ;; Add 40 elements via conj-left to trigger left digit overflow
    (let [tree (reduce (fn [[m h] v]
                         (let [[m' vh] (ft/add-value m ["i64" v])]
                           (ft/conj-left [m' h] vh)))
                       (ft/finger-tree)
                       (range 40))]
      (is (= 40 (ft/tree-count tree)))
      ;; Elements added left end up reversed
      (is (= (mapv #(vector "i64" %) (reverse (range 40))) (tree-values tree))))))

;; =============================================================================
;; Semantic hash (elements-fuse)
;; =============================================================================

(deftest test-elements-fuse-empty
  (testing "empty tree has identity elements-fuse"
    (is (= [0 0 0 0] (ft/tree-elements-fuse (ft/finger-tree))))))

(deftest test-elements-fuse-single
  (testing "single element tree has that element's hash as elements-fuse"
    (let [[m0 h0] (ft/finger-tree)
          value ["i64" 42]
          [m1 vh] (ft/add-value m0 value)
          tree (ft/conj-right [m1 h0] vh)]
      (is (= vh (ft/tree-elements-fuse tree))))))

(deftest test-elements-fuse-order-matters
  (testing "different element orders produce different elements-fuse"
    (let [t1 (ft/from-seq [["i64" 1] ["i64" 2] ["i64" 3]])
          t2 (ft/from-seq [["i64" 3] ["i64" 2] ["i64" 1]])]
      (is (not= (ft/tree-elements-fuse t1) (ft/tree-elements-fuse t2))))))

(deftest test-elements-fuse-structure-independent
  (testing "same logical sequence → same elements-fuse regardless of construction"
    ;; Build same sequence two ways: conj-right vs from-seq
    (let [values [["i64" 10] ["i64" 20] ["i64" 30]]
          t1 (ft/from-seq values)
          t2 (reduce (fn [[m h] v]
                       (let [[m' vh] (ft/add-value m v)]
                         (ft/conj-right [m' h] vh)))
                     (ft/finger-tree)
                     values)]
      (is (= (ft/tree-elements-fuse t1) (ft/tree-elements-fuse t2))))))

(deftest test-elements-fuse-concat
  (testing "concat produces same fuse as building from full sequence"
    (let [vs1 (map #(vector "i64" %) (range 5))
          vs2 (map #(vector "i64" %) (range 5 10))
          t-full (ft/from-seq (concat vs1 vs2))
          t-concat (ft/tree-concat (ft/from-seq vs1) (ft/from-seq vs2))]
      (is (= (ft/tree-elements-fuse t-full) (ft/tree-elements-fuse t-concat))))))

;; =============================================================================
;; tree-nth tests
;; =============================================================================

(deftest test-tree-nth-basic
  (testing "nth on small trees"
    (let [values (map #(vector "i64" %) (range 5))
          tree (ft/from-seq values)
          expected (ft/to-vec tree)]
      (dotimes [i 5]
        (is (= (nth expected i) (ft/tree-nth tree i)))))))

(deftest test-tree-nth-single
  (testing "nth on single-element tree"
    (let [tree (ft/from-seq [["i64" 42]])
          vh (ft/tree-nth tree 0)]
      (is (some? vh)))))

(deftest test-tree-nth-out-of-bounds
  (testing "nth throws on out of bounds"
    (let [tree (ft/from-seq [["i64" 1] ["i64" 2]])]
      (is (thrown? IndexOutOfBoundsException (ft/tree-nth tree -1)))
      (is (thrown? IndexOutOfBoundsException (ft/tree-nth tree 2)))
      (is (thrown? IndexOutOfBoundsException (ft/tree-nth tree 100))))))

(deftest test-tree-nth-empty
  (testing "nth throws on empty tree"
    (let [tree (ft/finger-tree)]
      (is (thrown? IndexOutOfBoundsException (ft/tree-nth tree 0))))))

(defspec tree-nth-matches-to-vec 50
  (prop/for-all [n (gen/choose 1 200)]
                (let [values (map #(vector "i64" %) (range n))
                      tree (ft/from-seq values)
                      expected (ft/to-vec tree)]
                  (every? #(= (nth expected %) (ft/tree-nth tree %)) (range n)))))

;; =============================================================================
;; tree-seq-lazy tests
;; =============================================================================

(deftest test-tree-seq-lazy-matches-to-vec
  (testing "lazy seq produces same elements as to-vec"
    (let [values (map #(vector "i64" %) (range 20))
          tree (ft/from-seq values)]
      (is (= (ft/to-vec tree) (vec (ft/tree-seq-lazy tree)))))))

(deftest test-tree-seq-lazy-empty
  (testing "lazy seq of empty tree is empty"
    (is (empty? (ft/tree-seq-lazy (ft/finger-tree))))))

(comment
  (clojure.test/run-tests))
