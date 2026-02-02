(ns dacite.hamt-test
  "Tests for Dacite HAMT implementation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dacite.hamt :as hamt]))

;; =============================================================================
;; Basic operations
;; =============================================================================

(deftest test-empty-hamt
  (testing "empty HAMT"
    (let [m (hamt/hamt)]
      (is (= 0 (hamt/hamt-count m)))
      (is (= 0 (hamt/hamt-size-bytes m)))
      (is (nil? (hamt/get-val m "key")))
      (is (= [] (hamt/entries m))))))

(deftest test-single-entry
  (testing "single entry"
    (let [m (hamt/assoc-val (hamt/hamt) "name" "Alice")]
      (is (= 1 (hamt/hamt-count m)))
      (is (= "Alice" (hamt/get-val m "name")))
      (is (nil? (hamt/get-val m "other"))))))

(deftest test-multiple-entries
  (testing "multiple entries"
    (let [m (-> (hamt/hamt)
                (hamt/assoc-val "a" 1)
                (hamt/assoc-val "b" 2)
                (hamt/assoc-val "c" 3))]
      (is (= 3 (hamt/hamt-count m)))
      (is (= 1 (hamt/get-val m "a")))
      (is (= 2 (hamt/get-val m "b")))
      (is (= 3 (hamt/get-val m "c"))))))

(deftest test-overwrite
  (testing "overwriting existing key"
    (let [m1 (hamt/assoc-val (hamt/hamt) "key" "old")
          m2 (hamt/assoc-val m1 "key" "new")]
      (is (= "old" (hamt/get-val m1 "key")))
      (is (= "new" (hamt/get-val m2 "key")))
      (is (= 1 (hamt/hamt-count m1)))
      (is (= 1 (hamt/hamt-count m2))))))

(deftest test-delete
  (testing "delete entry"
    (let [m1 (-> (hamt/hamt)
                 (hamt/assoc-val "a" 1)
                 (hamt/assoc-val "b" 2)
                 (hamt/assoc-val "c" 3))
          m2 (hamt/dissoc-val m1 "b")]
      (is (= 3 (hamt/hamt-count m1)))
      (is (= 2 (hamt/hamt-count m2)))
      (is (= 1 (hamt/get-val m2 "a")))
      (is (nil? (hamt/get-val m2 "b")))
      (is (= 3 (hamt/get-val m2 "c"))))))

(deftest test-delete-nonexistent
  (testing "delete non-existent key"
    (let [m1 (hamt/assoc-val (hamt/hamt) "a" 1)
          m2 (hamt/dissoc-val m1 "b")]
      (is (= 1 (hamt/hamt-count m2)))
      (is (= 1 (hamt/get-val m2 "a"))))))

(deftest test-delete-to-empty
  (testing "delete last entry"
    (let [m1 (hamt/assoc-val (hamt/hamt) "only" "entry")
          m2 (hamt/dissoc-val m1 "only")]
      (is (= 0 (hamt/hamt-count m2)))
      (is (nil? (hamt/get-val m2 "only"))))))

;; =============================================================================
;; Structural sharing (persistence)
;; =============================================================================

(deftest test-persistence
  (testing "structural sharing"
    (let [m1 (-> (hamt/hamt)
                 (hamt/assoc-val "a" 1)
                 (hamt/assoc-val "b" 2))
          m2 (hamt/assoc-val m1 "c" 3)]
      ;; Both maps should work independently
      (is (= 2 (hamt/hamt-count m1)))
      (is (= 3 (hamt/hamt-count m2)))
      (is (nil? (hamt/get-val m1 "c")))
      (is (= 3 (hamt/get-val m2 "c")))
      ;; Original unchanged
      (is (= 1 (hamt/get-val m1 "a")))
      (is (= 1 (hamt/get-val m2 "a"))))))

;; =============================================================================
;; Large maps (to exercise tree structure)
;; =============================================================================

(deftest test-large-map
  (testing "map with 1000 entries"
    (let [m (reduce (fn [m i] (hamt/assoc-val m (str "key" i) i))
                    (hamt/hamt)
                    (range 1000))]
      (is (= 1000 (hamt/hamt-count m)))
      (is (= 0 (hamt/get-val m "key0")))
      (is (= 500 (hamt/get-val m "key500")))
      (is (= 999 (hamt/get-val m "key999")))
      (is (nil? (hamt/get-val m "key1000"))))))

(deftest test-entries
  (testing "entries returns all pairs"
    (let [m (-> (hamt/hamt)
                (hamt/assoc-val "x" 1)
                (hamt/assoc-val "y" 2)
                (hamt/assoc-val "z" 3))
          e (set (hamt/entries m))]
      (is (= 3 (count e)))
      (is (contains? e ["x" 1]))
      (is (contains? e ["y" 2]))
      (is (contains? e ["z" 3])))))

;; =============================================================================
;; Hash chunk extraction
;; =============================================================================

(deftest test-hash-chunk
  (testing "hash chunk extraction"
    ;; Create a known hash pattern
    (let [h [0x1234567890ABCDEF 0xFEDCBA0987654321 0 0]]
      ;; Level 0: bits 63-59 of first long = 0x12 >> 4 & 0x1F = 0x01
      ;; Actually let's compute: 0x1234567890ABCDEF
      ;; Binary of 0x12: 0001 0010
      ;; Bits 63-59: should be 00010 = 2
      (is (= 2 (hamt/hash-chunk h 0)))
      ;; Level 1: bits 58-54
      ;; 0x1234 = 0001 0010 0011 0100
      ;; bits 58-54: 01000 = 8
      (is (= 8 (hamt/hash-chunk h 1))))))

;; =============================================================================
;; Property tests
;; =============================================================================

(def gen-key (gen/such-that #(< (count %) 100) gen/string-alphanumeric))
(def gen-val gen/small-integer)
(def gen-kv (gen/tuple gen-key gen-val))

(defspec lookup-after-insert 100
  (prop/for-all [k gen-key
                 v gen-val]
    (let [m (hamt/assoc-val (hamt/hamt) k v)]
      (= v (hamt/get-val m k)))))

(defspec count-increases-on-new-key 100
  (prop/for-all [k1 gen-key
                 k2 gen-key
                 v1 gen-val
                 v2 gen-val]
    ;; Only test when keys are different
    (if (= k1 k2)
      true  ;; skip this case
      (let [m1 (hamt/assoc-val (hamt/hamt) k1 v1)
            m2 (hamt/assoc-val m1 k2 v2)]
        (= (inc (hamt/hamt-count m1)) (hamt/hamt-count m2))))))

(defspec count-unchanged-on-same-key 100
  (prop/for-all [k gen-key
                 v1 gen-val
                 v2 gen-val]
    (let [m1 (hamt/assoc-val (hamt/hamt) k v1)
          m2 (hamt/assoc-val m1 k v2)]
      (= (hamt/hamt-count m1) (hamt/hamt-count m2)))))

(defspec delete-removes-key 100
  (prop/for-all [k gen-key
                 v gen-val]
    (let [m1 (hamt/assoc-val (hamt/hamt) k v)
          m2 (hamt/dissoc-val m1 k)]
      (nil? (hamt/get-val m2 k)))))

(defspec insert-multiple-all-present 50
  (prop/for-all [kvs (gen/vector gen-kv 1 50)]
    (let [m (reduce (fn [m [k v]] (hamt/assoc-val m k v))
                    (hamt/hamt)
                    kvs)
          ;; Last value for each key wins
          expected (into {} kvs)]
      (every? (fn [[k v]] (= v (hamt/get-val m k))) expected))))

(comment
  (clojure.test/run-tests))
