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
          ;; Clean up
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(use-fixtures :each temp-dir-fixture)

;; =============================================================================
;; Hash conversion tests
;; =============================================================================

(deftest test-hash-hex-roundtrip
  (testing "hash to hex and back"
    (let [h [1234567890123456789 -1234567890123456789 0 -1]
          hex (store/hash->hex h)
          back (store/hex->hash hex)]
      (is (= 64 (count hex)))
      (is (= h back)))))

;; =============================================================================
;; Memory store tests
;; =============================================================================

(deftest test-mem-store-basic
  (testing "basic memory store operations"
    (let [s (store/mem-store)
          h [1 2 3 4]
          v {:data "test"}]
      (is (not (store/store-has? s h)))
      (store/store-put s h v)
      (is (store/store-has? s h))
      (is (= v (store/store-get s h)))
      (store/store-delete s h)
      (is (not (store/store-has? s h))))))

(deftest test-mem-store-put-value
  (testing "put-value computes hash"
    (let [s (store/mem-store)
          v {:type "test/int" :data 42}
          h (store/put-value s v)]
      (is (vector? h))
      (is (= 4 (count h)))
      (is (= v (store/get-value s h))))))

(deftest test-mem-store-list
  (testing "list returns all hashes"
    (let [s (store/mem-store)]
      (store/put-value s {:type "a" :data 1})
      (store/put-value s {:type "b" :data 2})
      (store/put-value s {:type "c" :data 3})
      (is (= 3 (count (store/store-list s)))))))

;; =============================================================================
;; File store tests
;; =============================================================================

(deftest test-file-store-basic
  (testing "basic file store operations"
    (let [s (store/file-store (str *temp-dir*))
          h [1 2 3 4]
          v {:data "test"}]
      (is (not (store/store-has? s h)))
      (store/store-put s h v)
      (is (store/store-has? s h))
      (is (= v (store/store-get s h)))
      (store/store-delete s h)
      (is (not (store/store-has? s h))))))

(deftest test-file-store-persistence
  (testing "file store persists across instances"
    (let [path (str *temp-dir*)
          v {:type "test" :data "persistent"}
          ;; Write with one instance
          s1 (store/file-store path)
          h (store/put-value s1 v)
          ;; Read with new instance
          s2 (store/file-store path)]
      (is (= v (store/get-value s2 h))))))

(deftest test-file-store-sharding
  (testing "file store creates sharded directories"
    (let [s (store/file-store (str *temp-dir*))
          v {:type "test" :data 123}
          h (store/put-value s v)
          hex (store/hash->hex h)
          expected-dir (io/file *temp-dir* (subs hex 0 2) (subs hex 2 4))]
      (is (.exists expected-dir))
      (is (.isDirectory expected-dir)))))

;; =============================================================================
;; Tree storage tests
;; =============================================================================

(deftest test-store-tree-simple
  (testing "store simple tree"
    (let [s (store/mem-store)
          tree {:type "dacite.core/vector"
                :measure {:count 2}
                :children [{:type "dacite.core/i64" :data 1}
                           {:type "dacite.core/i64" :data 2}]}
          root (store/store-tree! s tree)]
      ;; Root should be a hash
      (is (vector? root))
      (is (= 4 (count root)))
      ;; Store should have 3 entries (root + 2 children)
      (is (= 3 (count (store/store-list s)))))))

(deftest test-fetch-tree-depth
  (testing "fetch tree with depth control"
    (let [s (store/mem-store)
          tree {:type "root"
                :children [{:type "child1"
                            :children [{:type "grandchild1" :data 1}
                                       {:type "grandchild2" :data 2}]}
                           {:type "child2" :data 3}]}
          root (store/store-tree! s tree)]

      ;; Depth 0: just root, children are hashes
      (let [fetched (store/fetch-tree s root 0)]
        (is (= "root" (:type fetched)))
        (is (every? #(and (vector? %) (= 4 (count %))) (:children fetched))))

      ;; Depth 1: root + immediate children expanded
      (let [fetched (store/fetch-tree s root 1)]
        (is (= "root" (:type fetched)))
        (is (= "child1" (:type (first (:children fetched)))))
        (is (= "child2" (:type (second (:children fetched)))))
        ;; Grandchildren of child1 still hashes
        (is (every? #(and (vector? %) (= 4 (count %)))
                    (:children (first (:children fetched))))))

      ;; Full depth: everything expanded
      (let [fetched (store/fetch-tree s root)]
        (is (= "grandchild1" (:type (first (:children (first (:children fetched)))))))))))

(deftest test-content-addressing
  (testing "same content produces same hash"
    (let [s (store/mem-store)
          v {:type "test" :data 42}
          h1 (store/put-value s v)
          h2 (store/put-value s v)]
      (is (= h1 h2))
      ;; Only one entry in store
      (is (= 1 (count (store/store-list s)))))))

(comment
  (clojure.test/run-tests))
