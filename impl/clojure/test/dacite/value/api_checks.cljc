(ns dacite.value.api-checks
  "Portable collection API on `dacite.value`, on the JVM and on nbb.

   SCI hosts (babashka/nbb) use this functional surface. These were the
   bodies of `dacite.value.api-test`. The JVM runs them from that namespace,
   one deftest each under its old name. nbb runs them from
   `dacite.value-cljs-test` (`npm run value:test`).
   `parity-with-native-jvm-test` stays JVM only: it compares the API with
   the JVM's native collection interfaces (`count`, `nth`, `get`, `conj` on
   a Dacite value), which a SCI type does not implement.

   `is` and `testing` are `dacite.store.check`."
  (:require [dacite.store :as store]
            [dacite.store.check :refer [is testing throws?]]
            [dacite.value :as v]))

(defn- realized [x]
  (when x (v/realize x)))

(defn vector-api-test
  [_]
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
        (is (throws? #(v/remove-nth vec 5)))
        (is (throws? #(v/remove-nth vec -1)))))))

(defn map-api-test
  [_]
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

(defn set-api-test
  [_]
  (let [st (store/mem)
        s (v/set st 1 2 3)]
    (is (= 3 (v/count s)))
    (is (v/contains? s 2))
    (is (not (v/contains? s 9)))
    (is (= 2 (realized (v/get s 2))))
    (is (= 4 (v/count (v/conj s 4))))
    (is (= #{1 2 3} (set (map realized (v/seq s)))))))

(defn get-value-test
  [_]
  (let [st (store/mem)
        vec (v/vector st 1 2 3)
        h (v/hash vec)
        v' (v/get-value st h)]
    (is (some? v'))
    (is (= h (v/hash v')))
    (is (= [1 2 3] (map realized (v/seq v'))))
    (is (nil? (v/get-value st (store/hex->hash (apply str (repeat 64 "0"))))))))

(def checks
  {"vector-api-test" vector-api-test
   "map-api-test" map-api-test
   "set-api-test" set-api-test
   "get-value-test" get-value-test})
