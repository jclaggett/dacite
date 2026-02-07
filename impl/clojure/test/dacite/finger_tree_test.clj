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

(defn make-tree
  "Create a tree from a sequence of values."
  [values]
  (ft/from-seq *cache* (map #(vector % (count (str %))) values)))

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
    (let [tree (ft/conj-right (ft/finger-tree *cache*) 42 8)]
      (is (not (ft/tree-empty? tree)))
      (is (= 1 (ft/tree-count tree)))
      (is (= 42 (ft/tree-first tree)))
      (is (= 42 (ft/tree-last tree)))
      (is (= [42] (ft/to-vec tree))))))

(deftest test-conj-right
  (testing "adding elements to the right"
    (let [tree (make-tree (range 10))]
      (is (= 10 (ft/tree-count tree)))
      (is (= 0 (ft/tree-first tree)))
      (is (= 9 (ft/tree-last tree)))
      (is (= (vec (range 10)) (ft/to-vec tree))))))

(deftest test-conj-left
  (testing "adding elements to the left"
    (let [tree (reduce #(ft/conj-left %1 %2 8) (ft/finger-tree *cache*) (range 10))]
      (is (= 10 (ft/tree-count tree)))
      ;; Elements added left-to-right end up reversed
      (is (= 9 (ft/tree-first tree)))
      (is (= 0 (ft/tree-last tree)))
      (is (= (vec (reverse (range 10))) (ft/to-vec tree))))))

(deftest test-rest
  (testing "removing first element"
    (let [tree (make-tree (range 5))
          rest-tree (ft/tree-rest tree)]
      (is (= 4 (ft/tree-count rest-tree)))
      (is (= [1 2 3 4] (ft/to-vec rest-tree))))))

(deftest test-butlast
  (testing "removing last element"
    (let [tree (make-tree (range 5))
          butlast-tree (ft/tree-butlast tree)]
      (is (= 4 (ft/tree-count butlast-tree)))
      (is (= [0 1 2 3] (ft/to-vec butlast-tree))))))

;; =============================================================================
;; Measure accumulation
;; =============================================================================

(deftest test-measure-accumulation
  (testing "count accumulates correctly"
    (let [tree (make-tree (range 100))]
      (is (= 100 (ft/tree-count tree)))))

  (testing "size-bytes accumulates correctly"
    (let [tree (-> (ft/finger-tree *cache*)
                   (ft/conj-right "a" 1)
                   (ft/conj-right "bb" 2)
                   (ft/conj-right "ccc" 3))]
      (is (= 3 (ft/tree-count tree)))
      (is (= 6 (ft/tree-size-bytes tree))))))

;; =============================================================================
;; Large trees (trigger spine usage)
;; =============================================================================

(deftest test-large-tree
  (testing "tree with 1000 elements"
    (let [tree (make-tree (range 1000))]
      (is (= 1000 (ft/tree-count tree)))
      (is (= 0 (ft/tree-first tree)))
      (is (= 999 (ft/tree-last tree)))
      (is (= (vec (range 1000)) (ft/to-vec tree)))))

  (testing "tree with 10000 elements"
    (let [tree (make-tree (range 10000))]
      (is (= 10000 (ft/tree-count tree)))
      (is (= 0 (ft/tree-first tree)))
      (is (= 9999 (ft/tree-last tree))))))

;; =============================================================================
;; Deque operations (stack/queue behavior)
;; =============================================================================

(deftest test-deque-operations
  (testing "LIFO (stack) from left"
    (let [tree (-> (ft/finger-tree *cache*)
                   (ft/conj-left 1 8)
                   (ft/conj-left 2 8)
                   (ft/conj-left 3 8))]
      (is (= 3 (ft/tree-first tree)))
      (is (= 2 (ft/tree-first (ft/tree-rest tree))))
      (is (= 1 (ft/tree-first (ft/tree-rest (ft/tree-rest tree)))))))

  (testing "FIFO (queue) - add right, take left"
    (let [tree (-> (ft/finger-tree *cache*)
                   (ft/conj-right 1 8)
                   (ft/conj-right 2 8)
                   (ft/conj-right 3 8))]
      (is (= 1 (ft/tree-first tree)))
      (is (= 2 (ft/tree-first (ft/tree-rest tree))))
      (is (= 3 (ft/tree-first (ft/tree-rest (ft/tree-rest tree))))))))

;; =============================================================================
;; Concatenation
;; =============================================================================

(deftest test-concat
  (testing "concatenating two trees"
    (let [t1 (make-tree [:a :b :c])
          t2 (make-tree [:x :y :z])
          tc (ft/tree-concat t1 t2)]
      (is (= 6 (ft/tree-count tc)))
      (is (= [:a :b :c :x :y :z] (ft/to-vec tc))))))

;; =============================================================================
;; Cache integration
;; =============================================================================

(deftest test-cache-integration
  (testing "nodes are committed to cache"
    (let [_ (make-tree (range 10))
          stats (cache/stats *cache*)]
      ;; Should have multiple entries (empty, leaves, digits, deep nodes)
      (is (> (:count stats) 10))))

  (testing "identical values produce same hash"
    (let [cache1 (cache/memory-cache-manager)
          cache2 (cache/memory-cache-manager)
          t1 (ft/from-seq cache1 [[42 8]])
          t2 (ft/from-seq cache2 [[42 8]])]
      ;; Same value should produce same root hash
      (is (= (:root-hash t1) (:root-hash t2))))))

;; =============================================================================
;; Property tests
;; =============================================================================

(def gen-small-int (gen/choose 0 100))

(defspec conj-right-preserves-count 50
  (prop/for-all [values (gen/vector gen-small-int)]
                (let [cache (cache/memory-cache-manager)
                      tree (ft/from-seq cache (map #(vector % 8) values))]
                  (= (count values) (ft/tree-count tree)))))

(defspec conj-right-preserves-order 50
  (prop/for-all [values (gen/vector gen-small-int)]
                (let [cache (cache/memory-cache-manager)
                      tree (ft/from-seq cache (map #(vector % 8) values))]
                  (= values (ft/to-vec tree)))))

(defspec rest-removes-first 50
  (prop/for-all [values (gen/not-empty (gen/vector gen-small-int))]
                (let [cache (cache/memory-cache-manager)
                      tree (ft/from-seq cache (map #(vector % 8) values))
                      rest-tree (ft/tree-rest tree)]
                  (= (vec (rest values)) (ft/to-vec rest-tree)))))

(defspec butlast-removes-last 50
  (prop/for-all [values (gen/not-empty (gen/vector gen-small-int))]
                (let [cache (cache/memory-cache-manager)
                      tree (ft/from-seq cache (map #(vector % 8) values))
                      butlast-tree (ft/tree-butlast tree)]
                  (= (vec (butlast values)) (ft/to-vec butlast-tree)))))

(comment
  (clojure.test/run-tests))
