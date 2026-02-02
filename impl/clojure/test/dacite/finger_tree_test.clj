(ns dacite.finger-tree-test
  "Tests for Dacite finger tree implementation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dacite.finger-tree :as ft]
            [dacite.hash :as hash]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn make-test-leaf
  "Create a leaf for testing with value and computed hash."
  [value]
  (let [bytes (.getBytes (str value) "UTF-8")
        size (count bytes)
        hash-longs (hash/bytes->longs (hash/sha256 bytes))]
    (ft/make-leaf value size hash-longs)))

(defn make-test-leaves
  "Create a sequence of test leaves from values."
  [values]
  (map make-test-leaf values))

;; =============================================================================
;; Basic operations
;; =============================================================================

(deftest test-empty-tree
  (testing "empty tree"
    (let [tree (ft/finger-tree)]
      (is (ft/ft-empty? tree))
      (is (= 0 (ft/tree-count tree)))
      (is (= 0 (ft/tree-size-bytes tree)))
      (is (nil? (ft/tree-first tree)))
      (is (nil? (ft/tree-last tree)))
      (is (= [] (ft/to-vec tree))))))

(deftest test-single-element
  (testing "single element tree"
    (let [leaf (make-test-leaf 42)
          tree (ft/conj-right (ft/finger-tree) leaf)]
      (is (not (ft/ft-empty? tree)))
      (is (= 1 (ft/tree-count tree)))
      (is (= 42 (:value (ft/tree-first tree))))
      (is (= 42 (:value (ft/tree-last tree))))
      (is (= [42] (ft/to-vec tree))))))

(deftest test-conj-right
  (testing "adding elements to the right"
    (let [leaves (make-test-leaves (range 10))
          tree (ft/from-seq leaves)]
      (is (= 10 (ft/tree-count tree)))
      (is (= 0 (:value (ft/tree-first tree))))
      (is (= 9 (:value (ft/tree-last tree))))
      (is (= (range 10) (ft/to-vec tree))))))

(deftest test-conj-left
  (testing "adding elements to the left"
    (let [leaves (make-test-leaves (range 10))
          tree (reduce ft/conj-left (ft/finger-tree) leaves)]
      (is (= 10 (ft/tree-count tree)))
      ;; Elements added left-to-right end up reversed
      (is (= 9 (:value (ft/tree-first tree))))
      (is (= 0 (:value (ft/tree-last tree))))
      (is (= (reverse (range 10)) (ft/to-vec tree))))))

(deftest test-rest
  (testing "removing first element"
    (let [leaves (make-test-leaves (range 5))
          tree (ft/from-seq leaves)
          rest-tree (ft/tree-rest tree)]
      (is (= 4 (ft/tree-count rest-tree)))
      (is (= [1 2 3 4] (ft/to-vec rest-tree))))))

(deftest test-butlast
  (testing "removing last element"
    (let [leaves (make-test-leaves (range 5))
          tree (ft/from-seq leaves)
          butlast-tree (ft/tree-butlast tree)]
      (is (= 4 (ft/tree-count butlast-tree)))
      (is (= [0 1 2 3] (ft/to-vec butlast-tree))))))

;; =============================================================================
;; Measure accumulation
;; =============================================================================

(deftest test-measure-accumulation
  (testing "count accumulates correctly"
    (let [leaves (make-test-leaves (range 100))
          tree (ft/from-seq leaves)]
      (is (= 100 (ft/tree-count tree)))))
  
  (testing "size-bytes accumulates correctly"
    (let [leaves (make-test-leaves ["a" "bb" "ccc"])
          tree (ft/from-seq leaves)
          ;; "a" = 1 byte, "bb" = 2 bytes, "ccc" = 3 bytes
          expected-size (+ 1 2 3)]
      (is (= 3 (ft/tree-count tree)))
      (is (= expected-size (ft/tree-size-bytes tree))))))

;; =============================================================================
;; Large trees (trigger spine usage)
;; =============================================================================

(deftest test-large-tree
  (testing "tree with 1000 elements"
    (let [leaves (make-test-leaves (range 1000))
          tree (ft/from-seq leaves)]
      (is (= 1000 (ft/tree-count tree)))
      (is (= 0 (:value (ft/tree-first tree))))
      (is (= 999 (:value (ft/tree-last tree))))
      (is (= (range 1000) (ft/to-vec tree)))))
  
  (testing "tree with 10000 elements"
    (let [leaves (make-test-leaves (range 10000))
          tree (ft/from-seq leaves)]
      (is (= 10000 (ft/tree-count tree)))
      (is (= 0 (:value (ft/tree-first tree))))
      (is (= 9999 (:value (ft/tree-last tree)))))))

;; =============================================================================
;; Deque operations (stack/queue behavior)
;; =============================================================================

(deftest test-deque-operations
  (testing "LIFO (stack) from left"
    (let [tree (-> (ft/finger-tree)
                   (ft/conj-left (make-test-leaf 1))
                   (ft/conj-left (make-test-leaf 2))
                   (ft/conj-left (make-test-leaf 3)))]
      (is (= 3 (:value (ft/tree-first tree))))
      (is (= 2 (:value (ft/tree-first (ft/tree-rest tree)))))
      (is (= 1 (:value (ft/tree-first (ft/tree-rest (ft/tree-rest tree))))))))
  
  (testing "FIFO (queue) - add right, take left"
    (let [tree (-> (ft/finger-tree)
                   (ft/conj-right (make-test-leaf 1))
                   (ft/conj-right (make-test-leaf 2))
                   (ft/conj-right (make-test-leaf 3)))]
      (is (= 1 (:value (ft/tree-first tree))))
      (is (= 2 (:value (ft/tree-first (ft/tree-rest tree)))))
      (is (= 3 (:value (ft/tree-first (ft/tree-rest (ft/tree-rest tree)))))))))

;; =============================================================================
;; Property tests
;; =============================================================================

(def gen-small-int (gen/choose 0 100))

(defspec conj-right-preserves-count 50
  (prop/for-all [values (gen/vector gen-small-int)]
    (let [leaves (make-test-leaves values)
          tree (ft/from-seq leaves)]
      (= (count values) (ft/tree-count tree)))))

(defspec conj-right-preserves-order 50
  (prop/for-all [values (gen/vector gen-small-int)]
    (let [leaves (make-test-leaves values)
          tree (ft/from-seq leaves)]
      (= values (ft/to-vec tree)))))

(defspec rest-removes-first 50
  (prop/for-all [values (gen/not-empty (gen/vector gen-small-int))]
    (let [leaves (make-test-leaves values)
          tree (ft/from-seq leaves)
          rest-tree (ft/tree-rest tree)]
      (= (rest values) (ft/to-vec rest-tree)))))

(defspec butlast-removes-last 50
  (prop/for-all [values (gen/not-empty (gen/vector gen-small-int))]
    (let [leaves (make-test-leaves values)
          tree (ft/from-seq leaves)
          butlast-tree (ft/tree-butlast tree)]
      ;; Use vec to normalize both sides (butlast returns nil for single element)
      (= (vec (butlast values)) (vec (ft/to-vec butlast-tree))))))

(comment
  (clojure.test/run-tests))
