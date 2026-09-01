(ns dacite.value.root-ref-test
  (:require [clojure.test :refer [deftest is]]
            [dacite.store :as store]
            [dacite.value :as v]))

(deftest via-uses-peer-store
  (let [st (store/mem-store)
        peer (v/i64 st 1)
        vec (v/vector peer 10 20)
        nested (v/map vec "a" 1 "b" (v/i64 vec 2))]
    (is (= st (v/dacite-store vec)))
    (is (= st (v/dacite-store nested)))
    (is (= 2 (v/count vec)))
    (is (= 2 (v/count nested)))
    (is (= (v/hash (v/vector st 10 20))
           (v/hash vec)))))

(deftest via-accepts-istore
  (let [st (store/mem-store)
        v (v/vector st 1 2 3)]
    (is (= st (v/dacite-store v)))
    (is (= 3 (v/count v)))))

(deftest root-ref-reset-swap-deref
  (let [rs (store/rooted-store (store/mem-store))
        r  (v/root rs)]
    (is (nil? (v/deref r)))
    (is (nil? @r))
    (v/cas! r nil (v/vector r))
    (is (zero? (v/count (v/deref r))))
    (v/swap! r v/conj (v/i64 r 42))
    (is (= 1 (v/count @r)))
    (is (= 42 (v/realize (v/nth @r 0))))
    ;; JVM atom ops
    (swap! r v/conj 7)
    (is (= 2 (v/count @r)))
    (reset! r (v/map r "k" "v"))
    (is (= "map" (v/type @r)))
    (is (= "v" (apply str (v/realize (v/get @r "k")))))))

(deftest root-ref-cas-and-watch
  (let [rs (store/rooted-store (store/mem-store))
        r  (v/root rs)
        seen (atom [])]
    (v/add-watch r :w (fn [_k _ref old new]
                        (swap! seen conj [(some-> old v/type)
                                          (some-> new v/type)])))
    (let [empty (v/vector r)
          one   (v/conj empty 1)]
      (is (true? (v/cas! r nil empty)))
      (is (false? (v/cas! r nil one))) ; expected still nil, root moved
      (is (true? (v/cas! r empty one))))
    (is (= [[nil "vector"] ["vector" "vector"]] @seen))
    (v/remove-watch r :w)
    (v/cas! r (v/deref r) nil)
    (is (= 2 (count @seen))))) ; no third notification after remove

(deftest root-ref-is-store-carrier
  (let [rs (store/rooted-store (store/mem-store))
        r  (v/root rs)
        v  (v/vector r 1 2)]
    (is (v/root? r))
    (is (= (v/store-of r) rs))
    (is (= rs (v/dacite-store v)))))
