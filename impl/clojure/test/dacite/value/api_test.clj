(ns dacite.value.api-test
  "Portable collection API on `dacite.value`.

   SCI hosts (babashka/nbb) use this functional surface, so these tests
   also check that it matches the JVM native collection interfaces."
  (:require [clojure.test :refer [deftest is testing]]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.value.types :as types]))

(defn- realized [x]
  (when x (v/realize x)))

(deftest vector-api-test
  (let [st (store/mem)]
    (testing "count / nth / get / seq"
      (let [vec (v/vector st 10 20 30)]
        (is (= 3 (v/count vec)))
        (is (= 20 (realized (v/nth vec 1))))
        (is (= 30 (realized (v/get vec 2))))
        (is (= :none (v/get vec 9 :none)))
        (is (= [10 20 30] (map realized (v/seq vec))))
        (is (v/contains? vec 0))
        (is (not (v/contains? vec 9)))))
    (testing "conj / assoc / peek / pop"
      (let [vec (v/vector st 1 2 3)]
        (is (= 4 (v/count (v/conj vec 4))))
        (is (= 99 (realized (v/nth (v/assoc vec 0 99) 0))))
        (is (= 3 (realized (v/peek vec))))
        (is (= 2 (v/count (v/pop vec))))
        (is (v/empty? (v/pop (v/pop (v/pop vec)))))))
    (testing "remove-nth"
      (let [vec (v/vector st 1 2 3 4 5)]
        (is (= [1 2 4 5] (map realized (v/seq (v/remove-nth vec 2)))))
        (is (= [2 3 4 5] (map realized (v/seq (v/remove-nth vec 0)))))
        (is (= [1 2 3 4] (map realized (v/seq (v/remove-nth vec 4)))))
        (is (= [1 2 3 4 5] (map realized (v/seq vec))))
        (is (thrown? Exception (v/remove-nth vec 5)))
        (is (thrown? Exception (v/remove-nth vec -1)))))))

(deftest map-api-test
  (let [st (store/mem)
        m (v/map st "a" 1 "b" 2)]
    (is (= 2 (v/count m)))
    (is (= 1 (realized (v/get m "a"))))
    (is (= :none (v/get m "z" :none)))
    (is (v/contains? m "b"))
    (is (= 3 (realized (v/get (v/assoc m "c" 3) "c"))))
    (is (= 1 (v/count (v/dissoc m "a"))))
    (is (= #{"a" "b"} (set (map #(apply str (realized %)) (v/keys m)))))
    (is (= #{1 2} (set (map realized (v/vals m)))))
    (is (= 3 (realized (v/get (v/conj m ["c" 3]) "c"))))))

(deftest set-api-test
  (let [st (store/mem)
        s (v/set st 1 2 3)]
    (is (= 3 (v/count s)))
    (is (v/contains? s 2))
    (is (not (v/contains? s 9)))
    (is (= 2 (realized (v/get s 2))))
    (is (= 4 (v/count (v/conj s 4))))
    (is (= #{1 2 3} (set (map realized (v/seq s)))))))

(deftest parity-with-native-jvm-test
  (let [st (store/mem)
        vec (v/vector st 5 6 7)
        m (v/map st "x" 1)]
    (is (= (count vec) (v/count vec)))
    (is (= (types/dacite-hash (nth vec 1)) (types/dacite-hash (v/nth vec 1))))
    (is (= (types/dacite-hash (get m "x")) (types/dacite-hash (v/get m "x"))))
    (is (= (types/dacite-hash (conj vec 8)) (types/dacite-hash (v/conj vec 8))))))

(deftest get-value-test
  (let [st (store/mem)
        vec (v/vector st 1 2 3)
        h (v/hash vec)
        v' (v/get-value st h)]
    (is (some? v'))
    (is (= h (v/hash v')))
    (is (= [1 2 3] (map realized (v/seq v'))))
    (is (nil? (v/get-value st (store/hex->hash (apply str (repeat 64 "0"))))))))
