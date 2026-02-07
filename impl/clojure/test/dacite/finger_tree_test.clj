(ns dacite.finger-tree-test
  "Tests for Dacite pure finger tree implementation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dacite.finger-tree :as ft]
            [dacite.cache :as cache]))

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
          [m1 vh] (ft/add-value m0 [:i64 42])
          tree (ft/conj-right [m1 h0] vh)]
      (is (not (ft/tree-empty? tree)))
      (is (= 1 (ft/tree-count tree)))
      (is (= [:i64 42] (lookup-value (first tree) (ft/tree-first tree))))
      (is (= [:i64 42] (lookup-value (first tree) (ft/tree-last tree))))
      (is (= [[:i64 42]] (tree-values tree))))))

(deftest test-conj-right
  (testing "adding elements to the right"
    (let [tree (ft/from-seq (map #(vector :i64 %) (range 10)))]
      (is (= 10 (ft/tree-count tree)))
      (is (= 0 (value-data (lookup-value (first tree) (ft/tree-first tree)))))
      (is (= 9 (value-data (lookup-value (first tree) (ft/tree-last tree)))))
      (is (= (mapv #(vector :i64 %) (range 10)) (tree-values tree))))))

(deftest test-conj-left
  (testing "adding elements to the left"
    (let [tree (reduce (fn [[m h] v]
                         (let [[m' vh] (ft/add-value m [:i64 v])]
                           (ft/conj-left [m' h] vh)))
                       (ft/finger-tree)
                       (range 10))]
      (is (= 10 (ft/tree-count tree)))
      ;; Elements added left-to-right end up reversed
      (is (= 9 (value-data (lookup-value (first tree) (ft/tree-first tree)))))
      (is (= 0 (value-data (lookup-value (first tree) (ft/tree-last tree)))))
      (is (= (mapv #(vector :i64 %) (reverse (range 10))) (tree-values tree))))))

(deftest test-rest
  (testing "removing first element"
    (let [tree (ft/from-seq (map #(vector :i64 %) (range 5)))
          rest-tree (ft/tree-rest tree)]
      (is (= 4 (ft/tree-count rest-tree)))
      (is (= (mapv #(vector :i64 %) [1 2 3 4]) (tree-values rest-tree))))))

(deftest test-butlast
  (testing "removing last element"
    (let [tree (ft/from-seq (map #(vector :i64 %) (range 5)))
          butlast-tree (ft/tree-butlast tree)]
      (is (= 4 (ft/tree-count butlast-tree)))
      (is (= (mapv #(vector :i64 %) [0 1 2 3]) (tree-values butlast-tree))))))

;; =============================================================================
;; Measure accumulation
;; =============================================================================

(deftest test-measure-accumulation
  (testing "count accumulates correctly"
    (let [tree (ft/from-seq (map #(vector :i64 %) (range 100)))]
      (is (= 100 (ft/tree-count tree)))))

  (testing "size-bytes accumulates correctly via dacite-size"
    (let [[m0 h0] (ft/finger-tree)
          [m1 v1] (ft/add-value m0 [:i8 1])    ;; 1 byte
          [m2 v2] (ft/add-value m1 [:i16 2])   ;; 2 bytes
          [m3 v3] (ft/add-value m2 [:i32 3])   ;; 4 bytes
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
    (let [tree (ft/from-seq (map #(vector :i64 %) (range 1000)))]
      (is (= 1000 (ft/tree-count tree)))
      (is (= 0 (value-data (lookup-value (first tree) (ft/tree-first tree)))))
      (is (= 999 (value-data (lookup-value (first tree) (ft/tree-last tree)))))
      (is (= (mapv #(vector :i64 %) (range 1000)) (tree-values tree)))))

  (testing "tree with 10000 elements"
    (let [tree (ft/from-seq (map #(vector :i64 %) (range 10000)))]
      (is (= 10000 (ft/tree-count tree)))
      (is (= 0 (value-data (lookup-value (first tree) (ft/tree-first tree)))))
      (is (= 9999 (value-data (lookup-value (first tree) (ft/tree-last tree))))))))

;; =============================================================================
;; Deque operations (stack/queue behavior)
;; =============================================================================

(deftest test-deque-operations
  (testing "LIFO (stack) from left"
    (let [[m0 h0] (ft/finger-tree)
          [m1 v1] (ft/add-value m0 [:i64 1])
          [m2 v2] (ft/add-value m1 [:i64 2])
          [m3 v3] (ft/add-value m2 [:i64 3])
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
          [m1 v1] (ft/add-value m0 [:i64 1])
          [m2 v2] (ft/add-value m1 [:i64 2])
          [m3 v3] (ft/add-value m2 [:i64 3])
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
    (let [t1 (ft/from-seq [[:kw :a] [:kw :b] [:kw :c]])
          t2 (ft/from-seq [[:kw :x] [:kw :y] [:kw :z]])
          tc (ft/tree-concat t1 t2)]
      (is (= 6 (ft/tree-count tc)))
      (is (= [[:kw :a] [:kw :b] [:kw :c] [:kw :x] [:kw :y] [:kw :z]]
             (tree-values tc))))))

;; =============================================================================
;; Pure data structure (no cache dependency)
;; =============================================================================

(deftest test-pure-structure
  (testing "tree is pure [map hash] tuple"
    (let [tree (ft/from-seq [[:i64 1] [:i64 2]])]
      (is (vector? tree))
      (is (= 2 (count tree)))
      (is (map? (first tree)))      ;; dacite-map
      (is (vector? (second tree)))  ;; root-hash (4 longs)
      (is (= 4 (count (second tree))))))

  (testing "map contains all nodes"
    (let [[dacite-map _] (ft/from-seq [[:i64 1] [:i64 2]])]
      ;; Should have: empty, 2 values, 2 leaves, digits, deep node, etc.
      (is (> (count dacite-map) 5))))

  (testing "identical inputs produce identical hashes"
    (let [[_ h1] (ft/from-seq [[:i64 42]])
          [_ h2] (ft/from-seq [[:i64 42]])]
      (is (= h1 h2)))))

;; =============================================================================
;; Persistence
;; =============================================================================

(deftest test-persistence
  (testing "persist! commits all values to cache"
    (let [cache (cache/memory-cache-manager)
          tree (ft/from-seq [[:i64 1] [:i64 2] [:i64 3]])
          [dacite-map _] tree
          root-hash (ft/persist! cache tree)]
      ;; All values should now be in the cache
      (is (= (count dacite-map) (:count (cache/stats cache))))
      ;; Root hash should be returned
      (is (vector? root-hash))
      (is (= 4 (count root-hash))))))

;; =============================================================================
;; Property tests
;; =============================================================================

(def gen-small-int (gen/choose 0 100))

(defspec conj-right-preserves-count 50
  (prop/for-all [values (gen/vector gen-small-int)]
                (let [tree (ft/from-seq (map #(vector :i64 %) values))]
                  (= (count values) (ft/tree-count tree)))))

(defspec conj-right-preserves-order 50
  (prop/for-all [values (gen/vector gen-small-int)]
                (let [tree (ft/from-seq (map #(vector :i64 %) values))
                      result (mapv #(value-data (lookup-value (first tree) %))
                                   (ft/to-vec tree))]
                  (= values result))))

(defspec rest-removes-first 50
  (prop/for-all [values (gen/not-empty (gen/vector gen-small-int))]
                (let [tree (ft/from-seq (map #(vector :i64 %) values))
                      rest-tree (ft/tree-rest tree)
                      result (mapv #(value-data (lookup-value (first rest-tree) %))
                                   (ft/to-vec rest-tree))]
                  (= (vec (rest values)) result))))

(defspec butlast-removes-last 50
  (prop/for-all [values (gen/not-empty (gen/vector gen-small-int))]
                (let [tree (ft/from-seq (map #(vector :i64 %) values))
                      butlast-tree (ft/tree-butlast tree)
                      result (mapv #(value-data (lookup-value (first butlast-tree) %))
                                   (ft/to-vec butlast-tree))]
                  (= (vec (butlast values)) result))))

(comment
  (clojure.test/run-tests))
