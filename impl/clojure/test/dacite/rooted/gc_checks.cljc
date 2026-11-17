(ns dacite.rooted.gc-checks
  "Root-reachability GC checks that run on the JVM and on nbb.

   These were the bodies of `dacite.rooted.gc-test`. The JVM runs them from
   that namespace, one deftest each under its old name. nbb runs them from
   `dacite.rooted-cljs-test` (`npm run rooted:test`). Setting the root of a
   rooted store is `reset!` on the JVM and `rs/set-root!` on nbb (see
   `dacite.rooted-checks`).

   `is` and `testing` are `dacite.store.check`."
  (:require [dacite.rooted :as rs]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.check :refer [is testing]]
            [dacite.value :as v]))

(defn- reset-root! [s h] #?(:clj (reset! s h) :cljs (rs/set-root! s h)))

(defn mark-reachable-scalar-test
  [_]
  (testing "scalar root marks only itself"
    (let [s (store/mem-store)
          val (v/i64 s 42)
          h (v/hash val)]
      (is (= #{(gc/hash-key h)} (gc/mark-reachable s h))))))

(defn collect-garbage-scalar-test
  [_]
  (testing "removes detached scalar nodes"
    (let [s (store/mem-store)
          live-v (v/i64 s 42)
          live-h (v/hash live-v)
          dead-v (v/i64 s 99)
          dead-h (v/hash dead-v)]
      (is (= {:removed 1 :kept 1} (gc/collect-garbage! s live-h)))
      (is (store/await (store/s-has? s live-h)))
      (is (not (store/await (store/s-has? s dead-h)))))))

(defn collect-garbage-collection-test
  [_]
  (testing "removes detached collection tree"
    (let [s (store/mem-store)
          v1 (v/vector s 1 2)
          h1 (v/hash v1)
          dead (v/vector s 3 4)
          dead-h (v/hash dead)
          result (gc/collect-garbage! s h1)]
      (is (pos? (:removed result)))
      (is (= (:kept result) (count (gc/mark-reachable s h1))))
      (is (store/await (store/s-has? s h1)))
      (is (not (store/await (store/s-has? s dead-h)))))))

(defn collect-garbage-rooted-store-test
  [_]
  (testing "collect-garbage! on rooted store uses content + current root"
    (let [s (rs/rooted-store (store/mem-store))
          dead (v/i64 s 0)
          _ (v/hash dead)
          live (v/i64 s 1)
          live-h (v/hash live)]
      (reset-root! s live-h)
      (is (= {:removed 1 :kept 1} (rs/collect-garbage! s)))
      (is (store/await (store/s-has? s live-h))))))

(defn collect-garbage-nil-root-test
  [_]
  (testing "nil root removes everything"
    (let [s (store/mem-store)]
      (v/i64 s 42)
      (is (= {:removed 1 :kept 0} (gc/collect-garbage! s nil))))))

(def checks
  {"mark-reachable-scalar-test" mark-reachable-scalar-test
   "collect-garbage-scalar-test" collect-garbage-scalar-test
   "collect-garbage-collection-test" collect-garbage-collection-test
   "collect-garbage-rooted-store-test" collect-garbage-rooted-store-test
   "collect-garbage-nil-root-test" collect-garbage-nil-root-test})
