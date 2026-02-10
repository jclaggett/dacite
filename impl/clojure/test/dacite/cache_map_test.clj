(ns dacite.cache-map-test
  "Tests for CacheMap - a Clojure map backed by CacheManager."
  (:require [clojure.test :refer [deftest is testing]]
            [dacite.cache :as cache]
            [dacite.cache-map :as cm]
            [dacite.hash :as hash]
            [dacite.finger-tree :as ft]
            [dacite.hamt :as hamt]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn make-cm []
  (cm/cache-map (cache/memory-cache-manager)))

(defn commit!
  "Commit a value to a CacheMap. Returns hash."
  [m value]
  (let [h (hash/compute-hash value)]
    (.assoc ^dacite.cache_map.CacheMap m h value)
    h))

;; =============================================================================
;; Basic map operations
;; =============================================================================

(deftest test-create
  (testing "cache-map creation"
    (let [m (make-cm)]
      (is (cm/cache-map? m))
      (is (some? (cm/manager m)))
      (is (= 0 (count m))))))

(deftest test-assoc-and-get
  (testing "assoc commits to cache, get retrieves"
    (let [m (make-cm)
          value [:i64 42]
          h (hash/compute-hash value)
          m' (assoc m h value)]
      ;; assoc returns the same CacheMap (write-through)
      (is (identical? m m'))
      ;; get retrieves the value
      (is (= [:i64 42] (get m h)))
      (is (= 1 (count m))))))

(deftest test-get-missing
  (testing "get returns nil for missing keys"
    (let [m (make-cm)
          h (hash/sha256-str "nonexistent")]
      (is (nil? (get m h)))
      (is (= :default (get m h :default))))))

(deftest test-contains
  (testing "containsKey works"
    (let [m (make-cm)
          value [:i64 42]
          h (hash/compute-hash value)]
      (is (not (contains? m h)))
      (commit! m value)
      (is (contains? m h)))))

(deftest test-idempotent-assoc
  (testing "assoc same value twice is idempotent"
    (let [m (make-cm)
          value [:i64 42]
          h (commit! m value)]
      (commit! m value)
      (is (= 1 (count m)))
      (is (= [:i64 42] (get m h))))))

(deftest test-multiple-values
  (testing "multiple values stored and retrieved"
    (let [m (make-cm)]
      (doseq [i (range 100)]
        (commit! m [:i64 i]))
      (is (= 100 (count m)))
      (doseq [i (range 100)]
        (let [v [:i64 i]
              h (hash/compute-hash v)]
          (is (= v (get m h))))))))

(deftest test-merge
  (testing "merge of two CacheMaps sharing backing store"
    (let [m (make-cm)
          h1 (commit! m [:i64 1])
          h2 (commit! m [:i64 2])
          merged (merge m m)]
      (is (= [:i64 1] (get merged h1)))
      (is (= [:i64 2] (get merged h2))))))

(deftest test-seq-returns-nil
  (testing "seq returns nil (enumeration not supported)"
    (let [m (make-cm)]
      (commit! m [:i64 1])
      (is (nil? (seq m))))))

(deftest test-string-representation
  (testing "toString returns useful description"
    (let [m (make-cm)]
      (is (string? (str m)))
      (is (.contains (str m) "CacheMap")))))

;; =============================================================================
;; Integration with finger-tree
;; =============================================================================

(deftest test-finger-tree-with-cache-map
  (testing "finger tree operations work with CacheMap"
    (let [m (make-cm)
          ;; Create empty tree node
          empty-node [:ft/empty {:measure {:count 0 :size-bytes 0}}]
          empty-hash (commit! m empty-node)
          ;; Build tree by adding values
          tree (reduce (fn [[dm rh] val]
                         (let [[dm' vh] (ft/add-value dm val)]
                           (ft/conj-right [dm' rh] vh)))
                       [m empty-hash]
                       [[:i64 10] [:i64 20] [:i64 30]])]
      (is (= 3 (ft/tree-count tree)))
      ;; Values are in the cache
      (is (pos? (count m))))))

;; =============================================================================
;; Integration with HAMT
;; =============================================================================

(deftest test-hamt-with-cache-map
  (testing "HAMT operations work with CacheMap"
    (let [m (make-cm)
          ;; Create empty HAMT node in cache
          empty-node [:hamt/empty {:measure {:count 0 :size-bytes 0}}]
          empty-hash (commit! m empty-node)
          ;; Add key and value
          [_ k-ref] (hamt/add-value m [:string "hello"])
          [_ v-ref] (hamt/add-value m [:i64 42])
          key-hash (hash/sha256-str "hello")
          ;; Insert into HAMT
          h (hamt/assoc-val [m empty-hash] key-hash k-ref v-ref)]
      (is (= 1 (hamt/hamt-count h)))
      (is (= v-ref (hamt/get-val h key-hash)))
      ;; All nodes are in the cache
      (is (pos? (count m))))))

(comment
  (clojure.test/run-tests))
