(ns dacite.finger-tree-test
  "Tests for Dacite cached finger tree implementation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dacite.finger-tree :as ft]
            [dacite.cache :as cache]))

;; =============================================================================
;; Test fixture - fresh cache for each test
;; =============================================================================

(def ^:dynamic *cache* nil)

(defn with-cache [f]
  (binding [*cache* (cache/memory-cache-manager)]
    (f)))

(use-fixtures :each with-cache)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn commit-value
  "Commit a value to the cache and return [hash size-bytes]."
  [value]
  (let [size (count (pr-str value))
        hash (cache/commit! *cache* [:test value])]
    [hash size]))

(defn make-tree
  "Create a tree from a sequence of values (commits each value first)."
  [values]
  (ft/from-seq *cache* (map commit-value values)))

(defn lookup-value
  "Look up a value-hash and return the actual value."
  [value-hash]
  (when value-hash
    (second (cache/lookup *cache* value-hash))))

(defn tree-values
  "Get all values from a tree (looking up each hash)."
  [tree]
  (mapv lookup-value (ft/to-vec tree)))

;; =============================================================================
;; Basic operations
;; =============================================================================

(deftest test-empty-tree
  (testing "empty tree"
    (let [tree (ft/finger-tree *cache*)]
      (is (ft/tree-empty? tree))
      (is (= 0 (ft/tree-count tree)))
      (is (= 0 (ft/tree-size-bytes tree)))
      (is (nil? (ft/tree-first tree)))
      (is (nil? (ft/tree-last tree)))
      (is (= [] (ft/to-vec tree))))))

(deftest test-single-element
  (testing "single element tree"
    (let [[h size] (commit-value 42)
          tree (ft/conj-right (ft/finger-tree *cache*) h size)]
      (is (not (ft/tree-empty? tree)))
      (is (= 1 (ft/tree-count tree)))
      (is (= 42 (lookup-value (ft/tree-first tree))))
      (is (= 42 (lookup-value (ft/tree-last tree))))
      (is (= [42] (tree-values tree))))))

(deftest test-conj-right
  (testing "adding elements to the right"
    (let [tree (make-tree (range 10))]
      (is (= 10 (ft/tree-count tree)))
      (is (= 0 (lookup-value (ft/tree-first tree))))
      (is (= 9 (lookup-value (ft/tree-last tree))))
      (is (= (vec (range 10)) (tree-values tree))))))

(deftest test-conj-left
  (testing "adding elements to the left"
    (let [tree (reduce (fn [t v]
                         (let [[h size] (commit-value v)]
                           (ft/conj-left t h size)))
                       (ft/finger-tree *cache*)
                       (range 10))]
      (is (= 10 (ft/tree-count tree)))
      ;; Elements added left-to-right end up reversed
      (is (= 9 (lookup-value (ft/tree-first tree))))
      (is (= 0 (lookup-value (ft/tree-last tree))))
      (is (= (vec (reverse (range 10))) (tree-values tree))))))

(deftest test-rest
  (testing "removing first element"
    (let [tree (make-tree (range 5))
          rest-tree (ft/tree-rest tree)]
      (is (= 4 (ft/tree-count rest-tree)))
      (is (= [1 2 3 4] (tree-values rest-tree))))))

(deftest test-butlast
  (testing "removing last element"
    (let [tree (make-tree (range 5))
          butlast-tree (ft/tree-butlast tree)]
      (is (= 4 (ft/tree-count butlast-tree)))
      (is (= [0 1 2 3] (tree-values butlast-tree))))))

;; =============================================================================
;; Measure accumulation
;; =============================================================================

(deftest test-measure-accumulation
  (testing "count accumulates correctly"
    (let [tree (make-tree (range 100))]
      (is (= 100 (ft/tree-count tree)))))

  (testing "size-bytes accumulates correctly"
    (let [[h1 _] (commit-value "a")
          [h2 _] (commit-value "bb")
          [h3 _] (commit-value "ccc")
          tree (-> (ft/finger-tree *cache*)
                   (ft/conj-right h1 1)
                   (ft/conj-right h2 2)
                   (ft/conj-right h3 3))]
      (is (= 3 (ft/tree-count tree)))
      (is (= 6 (ft/tree-size-bytes tree))))))

;; =============================================================================
;; Large trees (trigger spine usage)
;; =============================================================================

(deftest test-large-tree
  (testing "tree with 1000 elements"
    (let [tree (make-tree (range 1000))]
      (is (= 1000 (ft/tree-count tree)))
      (is (= 0 (lookup-value (ft/tree-first tree))))
      (is (= 999 (lookup-value (ft/tree-last tree))))
      (is (= (vec (range 1000)) (tree-values tree)))))

  (testing "tree with 10000 elements"
    (let [tree (make-tree (range 10000))]
      (is (= 10000 (ft/tree-count tree)))
      (is (= 0 (lookup-value (ft/tree-first tree))))
      (is (= 9999 (lookup-value (ft/tree-last tree)))))))

;; =============================================================================
;; Deque operations (stack/queue behavior)
;; =============================================================================

(deftest test-deque-operations
  (testing "LIFO (stack) from left"
    (let [[h1 s1] (commit-value 1)
          [h2 s2] (commit-value 2)
          [h3 s3] (commit-value 3)
          tree (-> (ft/finger-tree *cache*)
                   (ft/conj-left h1 s1)
                   (ft/conj-left h2 s2)
                   (ft/conj-left h3 s3))]
      (is (= 3 (lookup-value (ft/tree-first tree))))
      (is (= 2 (lookup-value (ft/tree-first (ft/tree-rest tree)))))
      (is (= 1 (lookup-value (ft/tree-first (ft/tree-rest (ft/tree-rest tree))))))))

  (testing "FIFO (queue) - add right, take left"
    (let [[h1 s1] (commit-value 1)
          [h2 s2] (commit-value 2)
          [h3 s3] (commit-value 3)
          tree (-> (ft/finger-tree *cache*)
                   (ft/conj-right h1 s1)
                   (ft/conj-right h2 s2)
                   (ft/conj-right h3 s3))]
      (is (= 1 (lookup-value (ft/tree-first tree))))
      (is (= 2 (lookup-value (ft/tree-first (ft/tree-rest tree)))))
      (is (= 3 (lookup-value (ft/tree-first (ft/tree-rest (ft/tree-rest tree)))))))))

;; =============================================================================
;; Concatenation
;; =============================================================================

(deftest test-concat
  (testing "concatenating two trees"
    (let [t1 (make-tree [:a :b :c])
          t2 (make-tree [:x :y :z])
          tc (ft/tree-concat t1 t2)]
      (is (= 6 (ft/tree-count tc)))
      (is (= [:a :b :c :x :y :z] (tree-values tc))))))

;; =============================================================================
;; Cache integration
;; =============================================================================

(deftest test-cache-integration
  (testing "nodes are committed to cache"
    (let [_ (make-tree (range 10))
          stats (cache/stats *cache*)]
      ;; Should have multiple entries (values, leaves, digits, deep nodes)
      (is (> (:count stats) 20))))  ;; 10 values + 10 leaves + structure

  (testing "identical value-hashes produce same tree hash"
    (let [cache1 (cache/memory-cache-manager)
          cache2 (cache/memory-cache-manager)
          ;; Commit same value to both caches
          h1 (cache/commit! cache1 [:test 42])
          h2 (cache/commit! cache2 [:test 42])
          t1 (ft/from-seq cache1 [[h1 8]])
          t2 (ft/from-seq cache2 [[h2 8]])]
      ;; Same value-hash should produce same tree structure
      (is (= h1 h2))
      (is (= (:root-hash t1) (:root-hash t2))))))

;; =============================================================================
;; Property tests
;; =============================================================================

(def gen-small-int (gen/choose 0 100))

(defn commit-values-to-cache
  "Commit values to a cache and return [hash size] pairs."
  [cache values]
  (mapv (fn [v]
          (let [size (count (pr-str v))
                hash (cache/commit! cache [:test v])]
            [hash size]))
        values))

(defspec conj-right-preserves-count 50
  (prop/for-all [values (gen/vector gen-small-int)]
                (let [cache (cache/memory-cache-manager)
                      pairs (commit-values-to-cache cache values)
                      tree (ft/from-seq cache pairs)]
                  (= (count values) (ft/tree-count tree)))))

(defspec conj-right-preserves-order 50
  (prop/for-all [values (gen/vector gen-small-int)]
                (let [cache (cache/memory-cache-manager)
                      pairs (commit-values-to-cache cache values)
                      tree (ft/from-seq cache pairs)
                      result-hashes (ft/to-vec tree)
                      result-values (mapv #(second (cache/lookup cache %)) result-hashes)]
                  (= values result-values))))

(defspec rest-removes-first 50
  (prop/for-all [values (gen/not-empty (gen/vector gen-small-int))]
                (let [cache (cache/memory-cache-manager)
                      pairs (commit-values-to-cache cache values)
                      tree (ft/from-seq cache pairs)
                      rest-tree (ft/tree-rest tree)
                      result-hashes (ft/to-vec rest-tree)
                      result-values (mapv #(second (cache/lookup cache %)) result-hashes)]
                  (= (vec (rest values)) result-values))))

(defspec butlast-removes-last 50
  (prop/for-all [values (gen/not-empty (gen/vector gen-small-int))]
                (let [cache (cache/memory-cache-manager)
                      pairs (commit-values-to-cache cache values)
                      tree (ft/from-seq cache pairs)
                      butlast-tree (ft/tree-butlast tree)
                      result-hashes (ft/to-vec butlast-tree)
                      result-values (mapv #(second (cache/lookup cache %)) result-hashes)]
                  (= (vec (butlast values)) result-values))))

(comment
  (clojure.test/run-tests))
