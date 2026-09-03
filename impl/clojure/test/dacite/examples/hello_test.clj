(ns dacite.examples.hello-test
  (:require [clojure.test :refer [deftest is]]
            [dacite.examples.hello :as hello]
            [dacite.examples.parity :as parity]
            [dacite.store :as store]
            [dacite.value :as v]))

(deftest hello-vector-and-map
  (let [{:keys [vec map]} (hello/hello (store/mem))]
    (is (= 3 (v/count vec)))
    (is (= 2 (v/count map)))
    (is (= 42 (v/realize (v/get map "hello"))))
    (is (= (v/hash vec) (v/hash (v/get map "vec"))))))

(deftest parity-hash-is-stable
  (let [a (parity/canonical-hex)
        b (parity/canonical-hex)]
    (is (= 64 (count a)))
    (is (re-matches #"[0-9a-f]{64}" a))
    (is (= a b))))
