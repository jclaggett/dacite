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

;; =============================================================================
;; Content addressing
;; =============================================================================

(deftest content-addressing-test
  (testing "same content same hash means single entry"
    (let [s (store/mem-store)]
      (store/s-put s [1 2 3 4] :v)
      (store/s-put s [1 2 3 4] :v)
      (is (= 1 (count (store/s-snapshot s)))))))
