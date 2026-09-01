(ns dacite.rooted.gc-test
  (:require [clojure.test :refer [deftest testing is]]
            [dacite.rooted.gc :as gc]
            [dacite.rooted :as rs]
            [dacite.store :as store]
            [dacite.value :as v]))

(deftest mark-reachable-scalar-test
  (testing "scalar root marks only itself"
    (let [s (store/mem-store)
          val (v/i64 s 42)
          h (v/hash val)]
      (is (= #{(gc/hash-key h)} (gc/mark-reachable s h))))))

(deftest collect-garbage-scalar-test
  (testing "removes detached scalar nodes"
    (let [s (store/mem-store)
          live-v (v/i64 s 42)
          live-h (v/hash live-v)
          dead-v (v/i64 s 99)
          dead-h (v/hash dead-v)]
      (is (= {:removed 1 :kept 1} (gc/collect-garbage! s live-h)))
      (is (store/s-has? s live-h))
      (is (not (store/s-has? s dead-h))))))

(deftest collect-garbage-collection-test
  (testing "removes detached collection tree"
    (let [s (store/mem-store)
          v1 (v/vector s 1 2)
          h1 (v/hash v1)]
      (v/vector s 3 4)
      (let [before (count (store/s-snapshot s))
            result (gc/collect-garbage! s h1)
            after (count (store/s-snapshot s))]
        (is (< after before))
        (is (pos? (:removed result)))
        (is (store/s-has? s h1))))))

(deftest collect-garbage-rooted-store-test
  (testing "collect-garbage! on rooted store uses content + current root"
    (let [s (rs/rooted-store (store/mem-store))
          dead (v/i64 s 0)
          _ (v/hash dead)
          live (v/i64 s 1)
          live-h (v/hash live)]
      (reset! s live-h)
      (is (= {:removed 1 :kept 1} (rs/collect-garbage! s)))
      (is (store/s-has? s live-h)))))

(deftest collect-garbage-nil-root-test
  (testing "nil root removes everything"
    (let [s (store/mem-store)]
      (v/i64 s 42)
      (is (= {:removed 1 :kept 0} (gc/collect-garbage! s nil))))))
