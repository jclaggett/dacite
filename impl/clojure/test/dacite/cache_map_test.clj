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
  (let [h (hash/typed-value-hash value)]
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
          h (hash/typed-value-hash value)
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
          h (hash/typed-value-hash value)]
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
              h (hash/typed-value-hash v)]
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

(deftest test-entry-at
  (testing "entryAt returns MapEntry for existing key"
    (let [m (make-cm)
          h (commit! m [:i64 42])
          entry (.entryAt ^clojure.lang.Associative m h)]
      (is (some? entry))
      (is (= h (key entry)))
      (is (= [:i64 42] (val entry)))))
  (testing "entryAt returns nil for missing key"
    (let [m (make-cm)]
      (is (nil? (.entryAt ^clojure.lang.Associative m [0 0 0 0]))))))

(deftest test-unsupported-ops
  (testing "assocEx throws"
    (let [m (make-cm)]
      (is (thrown? UnsupportedOperationException
                   (.assocEx ^clojure.lang.IPersistentMap m [0 0 0 0] :x)))))
  (testing "without throws"
    (let [m (make-cm)]
      (is (thrown? UnsupportedOperationException
                   (.without ^clojure.lang.IPersistentMap m [0 0 0 0]))))))

(deftest test-cons-vector
  (testing "cons with [k v] vector"
    (let [m (make-cm)
          value [:i64 99]
          h (hash/typed-value-hash value)
          m' (conj m [h value])]
      (is (identical? m m'))
      (is (= value (get m h))))))

(deftest test-cons-map-entry
  (testing "cons with MapEntry"
    (let [m (make-cm)
          value [:i64 77]
          h (hash/typed-value-hash value)
          entry (clojure.lang.MapEntry/create h value)
          m' (conj m entry)]
      (is (identical? m m'))
      (is (= value (get m h))))))

(deftest test-merge-with-regular-map
  (testing "merge with a regular Clojure map"
    (let [m (make-cm)
          value [:i64 55]
          h (hash/typed-value-hash value)
          m' (merge m {h value})]
      (is (= value (get m' h))))))

(deftest test-empty
  (testing "empty returns a new CacheMap with same backing store"
    (let [m (make-cm)
          _ (commit! m [:i64 1])
          e (.empty ^clojure.lang.IPersistentCollection m)]
      (is (cm/cache-map? e))
      ;; Still backed by same cache, so values still accessible
      (is (= [:i64 1] (get e (hash/typed-value-hash [:i64 1])))))))

(deftest test-equiv
  (testing "equiv is identity-based"
    (let [m (make-cm)]
      (is (.equiv ^clojure.lang.IPersistentCollection m m))
      (is (not (.equiv ^clojure.lang.IPersistentCollection m (make-cm)))))))

(deftest test-meta
  (testing "meta and withMeta"
    (let [m (make-cm)]
      (is (nil? (meta m)))
      (let [m' (with-meta m {:foo :bar})]
        (is (= {:foo :bar} (meta m')))))))

(deftest test-iterator
  (testing "iterator returns empty iterator"
    (let [m (make-cm)
          it (.iterator ^Iterable m)]
      (is (not (.hasNext it))))))

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
          ;; Create empty tree and seed the CacheMap with it
          [plain-map empty-hash] (ft/finger-tree)
          _ (run! (fn [[h v]] (cache/store! (cm/manager m) h v)) plain-map)
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
          ;; Create empty HAMT and seed the CacheMap
          [plain-map empty-hash] (hamt/hamt)
          _ (run! (fn [[h v]] (cache/store! (cm/manager m) h v)) plain-map)
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
