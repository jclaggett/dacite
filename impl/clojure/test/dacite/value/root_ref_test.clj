(ns dacite.value.root-ref-test
  (:require [clojure.test :refer [deftest is]]
            [dacite.store :as store]
            [dacite.value :as v]))

(deftest via-uses-peer-store
  (let [st (store/mem-store)
        peer (v/i64-with-store st 1)
        vec (v/vector-via peer 10 20)
        nested (v/hash-map-via vec "a" 1 "b" (v/i64-via vec 2))]
    (is (= st (v/dacite-store vec)))
    (is (= st (v/dacite-store nested)))
    (is (= 2 (v/count vec)))
    (is (= 2 (v/count nested)))
    (is (= (v/dacite-hash (v/vector-with-store st 10 20))
           (v/dacite-hash vec)))))

(deftest via-accepts-istore
  (let [st (store/mem-store)
        v (v/vector-via st 1 2 3)]
    (is (= st (v/dacite-store v)))
    (is (= 3 (v/count v)))))

(deftest root-ref-reset-swap-deref
  (let [rs (store/rooted-store (store/mem-store))
        r  (v/root-ref rs)]
    (is (nil? (v/ref-deref r)))
    (is (nil? @r))
    (v/ref-reset! r (v/vector-via r))
    (is (zero? (v/count (v/ref-deref r))))
    (v/ref-swap! r v/conj (v/i64-via r 42))
    (is (= 1 (v/count @r)))
    (is (= 42 (v/realize (v/nth @r 0))))
    ;; JVM atom ops
    (swap! r v/conj 7)
    (is (= 2 (v/count @r)))
    (reset! r (v/hash-map-via r "k" "v"))
    (is (= "map" (v/value-type @r)))
    (is (= "v" (apply str (v/realize (v/get @r "k")))))))

(deftest root-ref-cas-and-watch
  (let [rs (store/rooted-store (store/mem-store))
        r  (v/root-ref rs)
        seen (atom [])]
    (v/ref-add-watch r :w (fn [_k _ref old new]
                            (swap! seen conj [(some-> old v/dacite-type)
                                              (some-> new v/dacite-type)])))
    (let [empty (v/vector-via r)
          one   (v/conj empty 1)]
      (is (true? (v/ref-cas! r nil empty)))
      (is (false? (v/ref-cas! r nil one))) ; expected still nil, root moved
      (is (true? (v/ref-cas! r empty one))))
    (is (= [[nil "vector"] ["vector" "vector"]] @seen))
    (v/ref-remove-watch r :w)
    (v/ref-reset! r nil)
    (is (= 2 (count @seen))))) ; no third notification after remove

(deftest root-ref-is-store-carrier
  (let [rs (store/rooted-store (store/mem-store))
        r  (v/root-ref rs)
        v  (v/vector-via r 1 2)]
    (is (v/root-ref? r))
    (is (= (v/store-of r) rs))
    (is (= rs (v/dacite-store v)))))
