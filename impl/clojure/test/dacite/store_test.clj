(ns dacite.store-test
  "Tests for Dacite content-addressed storage."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dacite.store :as store]
            [clojure.java.io :as io]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:dynamic *temp-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "dacite-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (binding [*temp-dir* dir]
      (try
        (f)
        (finally
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(use-fixtures :each temp-dir-fixture)

;; =============================================================================
;; Hash conversion
;; =============================================================================

(deftest hash-hex-roundtrip-test
  (testing "hash to hex and back"
    (let [h [1234567890123456789 -1234567890123456789 0 -1]
          hex (store/hash->hex h)
          back (store/hex->hash hex)]
      (is (= 64 (count hex)))
      (is (= h back)))))

;; =============================================================================
;; MemStore
;; =============================================================================

(deftest mem-store-basic-test
  (testing "basic get/put/has?"
    (let [s (store/mem-store)
          h [1 2 3 4]
          v {:data "test"}]
      (is (nil? (store/s-get s h)))
      (is (not (store/s-has? s h)))
      (store/s-put s h v)
      (is (store/s-has? s h))
      (is (= v (store/s-get s h))))))

(deftest mem-store-snapshot-test
  (testing "snapshot returns plain map"
    (let [s (store/mem-store)]
      (store/s-put s [1 2 3 4] :a)
      (store/s-put s [5 6 7 8] :b)
      (let [snap (store/s-snapshot s)]
        (is (map? snap))
        (is (= 2 (count snap)))
        (is (= :a (get snap [1 2 3 4])))))))

(deftest mem-store-merge-test
  (testing "merge adds multiple entries"
    (let [s (store/mem-store)]
      (store/s-merge s {[1 2 3 4] :a [5 6 7 8] :b})
      (is (= :a (store/s-get s [1 2 3 4])))
      (is (= :b (store/s-get s [5 6 7 8]))))))

(deftest mem-store-reset-test
  (testing "reset clears store"
    (let [s (store/mem-store)]
      (store/s-put s [1 2 3 4] :a)
      (store/s-reset s)
      (is (nil? (store/s-get s [1 2 3 4])))
      (is (= {} (store/s-snapshot s))))))

(deftest mem-store-init-test
  (testing "mem-store can be initialized with data"
    (let [s (store/mem-store {[1 2 3 4] :a})]
      (is (= :a (store/s-get s [1 2 3 4]))))))

;; =============================================================================
;; FileStore
;; =============================================================================

(deftest file-store-basic-test
  (testing "basic file store operations"
    (let [s (store/file-store (str *temp-dir*))
          h [1 2 3 4]
          v {:data "test"}]
      (is (not (store/s-has? s h)))
      (store/s-put s h v)
      (is (store/s-has? s h))
      (is (= v (store/s-get s h))))))

(deftest file-store-persistence-test
  (testing "file store persists across instances"
    (let [path (str *temp-dir*)
          h [1 2 3 4]
          v {:data "persistent"}]
      (store/s-put (store/file-store path) h v)
      (is (= v (store/s-get (store/file-store path) h))))))

(deftest file-store-sharding-test
  (testing "file store creates sharded directories"
    (let [s (store/file-store (str *temp-dir*))
          h [1 2 3 4]
          v {:data 123}]
      (store/s-put s h v)
      (let [hex (store/hash->hex h)
            expected-dir (io/file *temp-dir* (subs hex 0 2) (subs hex 2 4))]
        (is (.exists expected-dir))
        (is (.isDirectory expected-dir))))))

(deftest file-store-snapshot-test
  (testing "snapshot reads all files"
    (let [s (store/file-store (str *temp-dir*))]
      (store/s-put s [1 2 3 4] :a)
      (store/s-put s [5 6 7 8] :b)
      (let [snap (store/s-snapshot s)]
        (is (= 2 (count snap)))))))

(deftest file-store-reset-test
  (testing "reset deletes all files"
    (let [s (store/file-store (str *temp-dir*))]
      (store/s-put s [1 2 3 4] :a)
      (store/s-reset s)
      (is (nil? (store/s-get s [1 2 3 4]))))))

;; =============================================================================
;; LayeredStore
;; =============================================================================

(deftest layered-store-read-through-test
  (testing "reads fall through from fast to slow"
    (let [fast (store/mem-store)
          slow (store/mem-store {[1 2 3 4] :from-slow})
          s (store/layered-store fast slow)]
      ;; Not in fast, found in slow
      (is (= :from-slow (store/s-get s [1 2 3 4]))))))

(deftest layered-store-read-through-backfill-test
  (testing "slow-layer hit backfills faster layers"
    (let [fast (store/mem-store)
          slow (store/mem-store {[1 2 3 4] :from-slow})
          s (store/layered-store fast slow)]
      (is (= :from-slow (store/s-get s [1 2 3 4])))
      (is (= :from-slow (store/s-get fast [1 2 3 4]))))))

(deftest layered-store-write-all-test
  (testing "writes go to all layers"
    (let [fast (store/mem-store)
          slow (store/mem-store)
          s (store/layered-store fast slow)]
      (store/s-put s [1 2 3 4] :value)
      (is (= :value (store/s-get fast [1 2 3 4])))
      (is (= :value (store/s-get slow [1 2 3 4]))))))

(deftest layered-store-fast-wins-test
  (testing "fast layer takes precedence"
    (let [fast (store/mem-store {[1 2 3 4] :fast})
          slow (store/mem-store {[1 2 3 4] :slow})
          s (store/layered-store fast slow)]
      (is (= :fast (store/s-get s [1 2 3 4]))))))

(deftest layered-store-has?-test
  (testing "has? checks all layers"
    (let [fast (store/mem-store)
          slow (store/mem-store {[1 2 3 4] :v})
          s (store/layered-store fast slow)]
      (is (store/s-has? s [1 2 3 4]))
      (is (not (store/s-has? s [9 9 9 9]))))))

(deftest layered-store-snapshot-test
  (testing "snapshot merges all layers, fast wins"
    (let [fast (store/mem-store {[1 2 3 4] :fast})
          slow (store/mem-store {[1 2 3 4] :slow [5 6 7 8] :only-slow})
          s (store/layered-store fast slow)
          snap (store/s-snapshot s)]
      (is (= :fast (get snap [1 2 3 4])))
      (is (= :only-slow (get snap [5 6 7 8]))))))

(deftest layered-store-with-file-test
  (testing "mem + file layered store"
    (let [mem (store/mem-store)
          file (store/file-store (str *temp-dir*))
          s (store/layered-store mem file)]
      (store/s-put s [1 2 3 4] {:data "layered"})
      ;; Both have it
      (is (= {:data "layered"} (store/s-get mem [1 2 3 4])))
      (is (= {:data "layered"} (store/s-get file [1 2 3 4])))
      ;; New layered store with empty mem still finds it in file
      (let [s2 (store/layered-store (store/mem-store) file)]
        (is (= {:data "layered"} (store/s-get s2 [1 2 3 4])))))))

(deftest file-store-merge-test
  (testing "merge adds multiple entries to file store"
    (let [s (store/file-store (str *temp-dir*))]
      (store/s-merge s {[1 2 3 4] :a [5 6 7 8] :b})
      (is (= :a (store/s-get s [1 2 3 4])))
      (is (= :b (store/s-get s [5 6 7 8]))))))

(deftest file-store-creates-dir-test
  (testing "file-store creates directory if it doesn't exist"
    (let [path (str *temp-dir* "/nested/subdir")
          s (store/file-store path)]
      (is (.exists (io/file path)))
      (store/s-put s [1 2 3 4] :v)
      (is (= :v (store/s-get s [1 2 3 4]))))))

(deftest layered-store-merge-test
  (testing "merge writes to all layers"
    (let [fast (store/mem-store)
          slow (store/mem-store)
          s (store/layered-store fast slow)]
      (store/s-merge s {[1 2 3 4] :a [5 6 7 8] :b})
      (is (= :a (store/s-get fast [1 2 3 4])))
      (is (= :b (store/s-get slow [5 6 7 8]))))))

(deftest layered-store-reset-test
  (testing "reset clears all layers"
    (let [fast (store/mem-store {[1 2 3 4] :a})
          slow (store/mem-store {[5 6 7 8] :b})
          s (store/layered-store fast slow)]
      (store/s-reset s)
      (is (= {} (store/s-snapshot fast)))
      (is (= {} (store/s-snapshot slow))))))

;; =============================================================================
;; LmdbStore
;; =============================================================================

(deftest lmdb-store-basic-test
  (testing "basic LMDB get/put/has?"
    (let [s (store/lmdb-store (str *temp-dir* "/lmdb"))
          h [1 2 3 4]
          v ["i64" 42]]
      (try
        (is (nil? (store/s-get s h)))
        (is (not (store/s-has? s h)))
        (store/s-put s h v)
        (is (store/s-has? s h))
        (is (= v (store/s-get s h)))
        (finally
          (store/lmdb-close s))))))

(deftest lmdb-store-persistence-test
  (testing "LMDB persists across instances"
    (let [path (str *temp-dir* "/lmdb-persist")
          h [1 2 3 4]
          v ["i64" 99]]
      (let [s (store/lmdb-store path)]
        (try
          (store/s-put s h v)
          (finally
            (store/lmdb-close s))))
      ;; New instance, same path
      (let [s (store/lmdb-store path)]
        (try
          (is (= v (store/s-get s h)))
          (finally
            (store/lmdb-close s)))))))

(deftest lmdb-store-snapshot-test
  (testing "LMDB snapshot returns all entries"
    (let [s (store/lmdb-store (str *temp-dir* "/lmdb-snap"))]
      (try
        (store/s-put s [1 2 3 4] ["i64" 1])
        (store/s-put s [5 6 7 8] ["i64" 2])
        (let [snap (store/s-snapshot s)]
          (is (= 2 (count snap)))
          (is (= ["i64" 1] (get snap [1 2 3 4])))
          (is (= ["i64" 2] (get snap [5 6 7 8]))))
        (finally
          (store/lmdb-close s))))))

(deftest lmdb-store-merge-test
  (testing "LMDB merge writes multiple entries in single txn"
    (let [s (store/lmdb-store (str *temp-dir* "/lmdb-merge"))]
      (try
        (store/s-merge s {[1 2 3 4] ["i64" 10] [5 6 7 8] ["i64" 20]})
        (is (= ["i64" 10] (store/s-get s [1 2 3 4])))
        (is (= ["i64" 20] (store/s-get s [5 6 7 8])))
        (finally
          (store/lmdb-close s))))))

(deftest lmdb-store-reset-test
  (testing "LMDB reset clears all entries"
    (let [s (store/lmdb-store (str *temp-dir* "/lmdb-reset"))]
      (try
        (store/s-put s [1 2 3 4] ["i64" 1])
        (store/s-reset s)
        (is (nil? (store/s-get s [1 2 3 4])))
        (is (= {} (store/s-snapshot s)))
        (finally
          (store/lmdb-close s))))))

(deftest lmdb-store-complex-values-test
  (testing "LMDB handles all node types"
    (let [s (store/lmdb-store (str *temp-dir* "/lmdb-complex"))]
      (try
        ;; Scalar
        (store/s-put s [1 0 0 0] ["i64" 42])
        (is (= ["i64" 42] (store/s-get s [1 0 0 0])))

        ;; Collection header
        (store/s-put s [2 0 0 0] ["vector" {:root [9 8 7 6] :count 3 :size-bytes 24}])
        (is (= ["vector" {:root [9 8 7 6] :count 3 :size-bytes 24}]
               (store/s-get s [2 0 0 0])))

        ;; FT node
        (store/s-put s [3 0 0 0] ["ft/deep" {:left [1 1 1 1]
                                             :spine [2 2 2 2]
                                             :right [3 3 3 3]
                                             :measure {:count 5
                                                       :size-bytes 40
                                                       :elements-fuse [0 0 0 0]}}])
        (let [v (store/s-get s [3 0 0 0])]
          (is (= "ft/deep" (first v)))
          (is (= [1 1 1 1] (:left (second v)))))

        ;; HAMT node
        (store/s-put s [4 0 0 0] ["hamt/entry" {:key-hash [5 5 5 5]
                                                :key-ref [6 6 6 6]
                                                :val-ref [7 7 7 7]
                                                :measure {:count 1
                                                          :size-bytes 8
                                                          :elements-fuse [0 0 0 0]}}])
        (let [v (store/s-get s [4 0 0 0])]
          (is (= "hamt/entry" (first v)))
          (is (= [6 6 6 6] (:key-ref (second v)))))
        (finally
          (store/lmdb-close s))))))

(deftest lmdb-store-with-layered-test
  (testing "LMDB as backend in layered store"
    (let [mem (store/mem-store)
          lmdb (store/lmdb-store (str *temp-dir* "/lmdb-layered"))]
      (try
        (let [s (store/layered-store mem lmdb)]
          (store/s-put s [1 2 3 4] ["i64" 42])
          ;; Both have it
          (is (= ["i64" 42] (store/s-get mem [1 2 3 4])))
          (is (= ["i64" 42] (store/s-get lmdb [1 2 3 4])))
          ;; New layered with empty mem still finds in LMDB
          (let [s2 (store/layered-store (store/mem-store) lmdb)]
            (is (= ["i64" 42] (store/s-get s2 [1 2 3 4])))))
        (finally
          (store/lmdb-close lmdb))))))

;; =============================================================================
;; Content addressing
;; =============================================================================

(deftest content-addressing-test
  (testing "same content same hash means single entry"
    (let [s (store/mem-store)]
      (store/s-put s [1 2 3 4] :v)
      (store/s-put s [1 2 3 4] :v)
      (is (= 1 (count (store/s-snapshot s)))))))
