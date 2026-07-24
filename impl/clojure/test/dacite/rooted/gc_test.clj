(ns dacite.rooted.gc-test
  (:require [clojure.test :refer [deftest testing is]]
            [dacite.rooted.gc :as gc]
            [dacite.rooted :as rs]
            [dacite.store :as store]
            [dacite.value :as v]))

(deftest mark-reachable-scalar-test
  (testing "scalar root marks only itself"
    (let [s (store/mem-store)]
      (v/with-store [st s]
        (let [val (v/i64 42)
              h (v/dacite-hash val)]
          (is (= #{(gc/hash-key h)} (gc/mark-reachable s h))))))))

(deftest collect-garbage-scalar-test
  (testing "removes detached scalar nodes"
    (let [s (store/mem-store)]
      (v/with-store [st s]
        (let [live-v (v/i64 42)
              live-h (v/dacite-hash live-v)
              dead-v (v/i64 99)
              dead-h (v/dacite-hash dead-v)]
          (is (= {:removed 1 :kept 1} (gc/collect-garbage! s live-h)))
          (is (store/s-has? s live-h))
          (is (not (store/s-has? s dead-h))))))))

(deftest collect-garbage-collection-test
  (testing "removes detached collection tree"
    (let [s (store/mem-store)]
      (v/with-store [st s]
        (let [v1 (v/vector 1 2)
              h1 (v/dacite-hash v1)]
          (v/vector 3 4)
          (let [before (count (store/s-snapshot s))
                result (gc/collect-garbage! s h1)
                after (count (store/s-snapshot s))]
            (is (< after before))
            (is (pos? (:removed result)))
            (is (store/s-has? s h1))))))))

(deftest collect-garbage-rooted-store-test
  (testing "collect-garbage! on rooted store uses content + current root"
    (let [s (rs/rooted-store (store/mem-store))]
      (store/with-store [_ s]
        (let [dead (v/i64 0)
              _ (v/dacite-hash dead)
              live (v/i64 1)
              live-h (v/dacite-hash live)]
          (reset! s live-h)
          (is (= {:removed 1 :kept 1} (rs/collect-garbage! s)))
          (is (store/s-has? s live-h)))))))

(deftest collect-garbage-nil-root-test
  (testing "nil root removes everything"
    (let [s (store/mem-store)]
      (v/with-store [st s]
        (v/i64 42)
        (is (= {:removed 1 :kept 0} (gc/collect-garbage! s nil)))))))
