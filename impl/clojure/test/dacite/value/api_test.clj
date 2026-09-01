(ns dacite.value.api-test
  "Tests for the portable functional collection API (dacite.value.api).

   These exercise the same surface SCI hosts (babashka/nbb) rely on, so
   they double as a contract check that the functional layer matches the
   JVM native collection interfaces."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dacite.value.types :as types]
            [dacite.value.collections :as coll]
            [dacite.value.api :as d]
            [dacite.store :as store]))

(use-fixtures :each
  (fn [f]
    (store/bind-store (store/mem-store) (f))))

(defn- realized [v]
  (when v (types/realize v)))

(deftest vector-api-test
  (testing "count / nth / get / seq"
    (let [v (coll/vector 10 20 30)]
      (is (= 3 (d/count v)))
      (is (= 20 (realized (d/nth v 1))))
      (is (= 30 (realized (d/get v 2))))
      (is (= :none (d/get v 9 :none)))
      (is (= [10 20 30] (map realized (d/seq v))))
      (is (d/contains? v 0))
      (is (not (d/contains? v 9)))))
  (testing "conj / assoc / peek / pop"
    (let [v (coll/vector 1 2 3)]
      (is (= 4 (d/count (d/conj v 4))))
      (is (= 99 (realized (d/nth (d/assoc v 0 99) 0))))
      (is (= 3 (realized (d/peek v))))
      (is (= 2 (d/count (d/pop v))))
      (is (d/empty? (d/pop (d/pop (d/pop v)))))))
  (testing "remove-nth"
    (let [v (coll/vector 1 2 3 4 5)]
      (is (= [1 2 4 5] (map realized (d/seq (d/remove-nth v 2)))))
      (is (= [2 3 4 5] (map realized (d/seq (d/remove-nth v 0)))))
      (is (= [1 2 3 4] (map realized (d/seq (d/remove-nth v 4)))))
      (is (= [1 2 3 4 5] (map realized (d/seq v))))
      (is (thrown? Exception (d/remove-nth v 5)))
      (is (thrown? Exception (d/remove-nth v -1))))))
(deftest map-api-test
  (testing "get / contains? / assoc / dissoc / keys / vals"
    (let [m (coll/hash-map "a" 1 "b" 2)]
      (is (= 2 (d/count m)))
      (is (= 1 (realized (d/get m "a"))))
      (is (= :none (d/get m "z" :none)))
      (is (d/contains? m "b"))
      (is (= 3 (realized (d/get (d/assoc m "c" 3) "c"))))
      (is (= 1 (d/count (d/dissoc m "a"))))
      ;; string keys realize to a lazy char seq; join them for comparison
      (is (= #{"a" "b"} (set (map #(apply str (realized %)) (d/keys m)))))
      (is (= #{1 2} (set (map realized (d/vals m)))))
      (is (= 3 (realized (d/get (d/conj m ["c" 3]) "c")))))))

(deftest set-api-test
  (testing "get / contains? / conj"
    (let [s (coll/dacite-set 1 2 3)]
      (is (= 3 (d/count s)))
      (is (d/contains? s 2))
      (is (not (d/contains? s 9)))
      (is (= 2 (realized (d/get s 2))))
      (is (= 4 (d/count (d/conj s 4))))
      (is (= #{1 2 3} (set (map realized (d/seq s))))))))

(deftest parity-with-native-jvm-test
  (testing "functional API agrees with native clojure.core on the JVM"
    (let [v (coll/vector 5 6 7)
          m (coll/hash-map "x" 1)]
      (is (= (count v) (d/count v)))
      (is (= (types/dacite-hash (nth v 1)) (types/dacite-hash (d/nth v 1))))
      (is (= (types/dacite-hash (get m "x")) (types/dacite-hash (d/get m "x"))))
      (is (= (types/dacite-hash (conj v 8)) (types/dacite-hash (d/conj v 8)))))))

(deftest get-value-test
  (testing "rehydrate a collection from its content hash"
    (let [v (coll/vector 1 2 3)
          h (types/dacite-hash v)
          v' (d/get-value h)]
      (is (some? v'))
      (is (= h (d/hash v')))
      (is (= [1 2 3] (map realized (d/seq v'))))
      (is (nil? (d/get-value (store/hex->hash (apply str (repeat 64 "0")))))))))
