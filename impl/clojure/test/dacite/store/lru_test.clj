(ns dacite.store.lru-test
  (:require [clojure.test :refer [deftest testing is]]
            [dacite.store.lru :as lru]
            [dacite.store :as store]))

(deftest lru-eviction-test
  (testing "evicts least recently used entry at capacity"
    (let [s (lru/lru-store 2)
          h1 [1 0 0 0]
          h2 [2 0 0 0]
          h3 [3 0 0 0]]
      (store/await (store/s-put s h1 :a))
      (store/await (store/s-put s h2 :b))
      (store/await (store/s-get s h1)) ;; touch h1
      (store/await (store/s-put s h3 :c))
      (is (store/await (store/s-has? s h1)))
      (is (not (store/await (store/s-has? s h2))))
      (is (store/await (store/s-has? s h3))))))

(deftest lru-read-through-touch-test
  (testing "get promotes entry"
    (let [s (lru/lru-store 2)
          h1 [1 0 0 0]
          h2 [2 0 0 0]
          h3 [3 0 0 0]]
      (store/await (store/s-put s h1 :a))
      (store/await (store/s-put s h2 :b))
      (is (= :a (store/await (store/s-get s h1))))
      (store/await (store/s-put s h3 :c))
      (is (store/await (store/s-has? s h1)))
      (is (not (store/await (store/s-has? s h2)))))))

(deftest lru-delete-test
  (testing "delete removes entry"
    (let [s (lru/lru-store 10)
          h [1 0 0 0]]
      (store/await (store/s-put s h :x))
      (store/await (store/s-delete s h))
      (is (not (store/await (store/s-has? s h)))))))
