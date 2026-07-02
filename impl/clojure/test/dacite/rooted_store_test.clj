(ns dacite.rooted-store-test
  "Tests for rooted stores and root cells."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [dacite.rooted-store :as rs]
            [dacite.store :as store]
            [clojure.java.io :as io]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:dynamic *temp-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "dacite-rooted-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (binding [*temp-dir* dir]
      (try
        (f)
        (finally
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(use-fixtures :each temp-dir-fixture)

;; =============================================================================
;; Root cell
;; =============================================================================

(deftest mem-root-cell-test
  (testing "mem root cell roundtrip"
    (let [cell (rs/mem-root-cell [1 2 3 4])]
      (is (= [1 2 3 4] (rs/rc-get cell)))
      (rs/rc-put! cell [5 6 7 8])
      (is (= [5 6 7 8] (rs/rc-get cell))))))

(deftest lmdb-root-cell-test
  (testing "lmdb root cell persists across instances"
    (let [path (str *temp-dir* "/lmdb-root-cell")
          lmdb (store/lmdb-store path)
          cell (rs/lmdb-root-cell lmdb)]
      (try
        (rs/rc-put! cell [1 2 3 4])
        (is (= [1 2 3 4] (rs/rc-get (rs/lmdb-root-cell lmdb))))
        (finally
          (store/lmdb-close lmdb))))))

;; =============================================================================
;; Rooted store — core ref operations
;; =============================================================================

(deftest rooted-store-seed-test
  (testing "constructor seeds root from cell"
    (let [s (rs/rooted-store (store/mem-store) (rs/mem-root-cell [1 2 3 4]))]
      (is (= [1 2 3 4] @s)))))

(deftest rooted-store-reset-test
  (testing "reset! installs a new root"
    (let [s (rs/rooted-store (store/mem-store))]
      (reset! s [1 2 3 4])
      (is (= [1 2 3 4] @s)))))

(deftest rooted-store-swap-test
  (testing "swap! transforms the root"
    (let [s (rs/rooted-store (store/mem-store))]
      (reset! s [1 0 0 0])
      (swap! s (fn [h] (vec (map inc h))))
      (is (= [2 1 1 1] @s)))))

(deftest rooted-store-cas-success-test
  (testing "compare-and-set! succeeds when expected matches"
    (let [s (rs/rooted-store (store/mem-store))
          r (do (reset! s [1 2 3 4]) @s)]
      (is (true? (compare-and-set! s r [5 6 7 8])))
      (is (= [5 6 7 8] @s)))))

(deftest rooted-store-cas-conflict-test
  (testing "compare-and-set! fails when root moved"
    (let [s (rs/rooted-store (store/mem-store))]
      (reset! s [1 2 3 4])
      (is (false? (compare-and-set! s [9 9 9 9] [5 6 7 8])))
      (is (= [1 2 3 4] @s)))))

;; =============================================================================
;; Watches and validators
;; =============================================================================

(deftest rooted-store-watch-test
  (testing "watch fires with the rooted store as ref"
    (let [s (rs/rooted-store (store/mem-store))
          seen (atom nil)]
      (add-watch s :test (fn [k r o n] (reset! seen {:k k :r r :o o :n n})))
      (reset! s [1 2 3 4])
      (is (= :test (:k @seen)))
      (is (identical? s (:r @seen)))
      (is (nil? (:o @seen)))
      (is (= [1 2 3 4] (:n @seen)))
      (remove-watch s :test)
      (reset! seen nil)
      (reset! s [5 6 7 8])
      (is (nil? @seen)))))

(deftest rooted-store-validator-test
  (testing "validator rejects invalid roots"
    (let [s (rs/rooted-store (store/mem-store))]
      (set-validator! s vector?)
      (reset! s [1 2 3 4])
      (is (thrown? IllegalStateException (reset! s "not-a-hash"))))))

;; =============================================================================
;; Durability
;; =============================================================================

(deftest rooted-store-durability-test
  (testing "root survives reopen via lmdb root cell"
    (let [path (str *temp-dir* "/lmdb-durable")
          lmdb (store/lmdb-store path)
          cell (rs/lmdb-root-cell lmdb)]
      (try
        (let [s1 (rs/rooted-store (store/mem-store) cell)]
          (reset! s1 [1 2 3 4]))
        (let [s2 (rs/rooted-store (store/mem-store) cell)]
          (is (= [1 2 3 4] @s2)))
        (finally
          (store/lmdb-close lmdb))))))

;; =============================================================================
;; IStore delegation
;; =============================================================================

(deftest rooted-store-delegates-content-test
  (testing "content ops delegate to inner store"
    (let [content (store/mem-store)
          s (rs/rooted-store content)]
      (store/s-put s [1 2 3 4] :value)
      (is (= :value (store/s-get content [1 2 3 4])))
      (is (= :value (store/s-get s [1 2 3 4]))))))

;; =============================================================================
;; push-ref
;; =============================================================================

(deftest push-ref-success-test
  (testing "push-ref moves target root to source root"
    (let [source (rs/rooted-store (store/mem-store))
          target (rs/rooted-store (store/mem-store))
          r1 [1 2 3 4]
          r2 [5 6 7 8]]
      (reset! source r2)
      (reset! target r1)
      (is (true? (rs/push-ref source target)))
      (is (= r2 @target)))))

(deftest push-ref-cas-conflict-test
  (testing "push-ref returns false when target root no longer matches"
    (let [source (rs/rooted-store (store/mem-store))
          target (rs/rooted-store (store/mem-store))
          r1 [1 2 3 4]
          r2 [5 6 7 8]
          r3 [9 9 9 9]]
      (reset! source r2)
      (reset! target r1)
      (reset! target r3)
      (is (false? (compare-and-set! target r1 r2)))
      (is (= r3 @target)))))
