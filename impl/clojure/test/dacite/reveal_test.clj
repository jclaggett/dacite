(ns dacite.reveal-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]
            [dacite.value.types :as types]))

(defn- type-of [store h]
  (types/entry-type (store/require-node store h)))

(defn- idx [order h]
  (.indexOf ^clojure.lang.PersistentVector (vec order) h))

(deftest scalar-is-one-node
  (let [s (store/mem-store)
        n (v/i64 s 42)
        h (v/hash n)]
    (is (= [h] (reveal/page-order s h)))))

(deftest empty-vector-has-no-element-body
  (let [s (store/mem-store)
        h (v/hash (v/vector s))
        order (reveal/page-order s h)]
    (is (= h (first order)))
    (is (every? #(or (= "vector" %) (str/starts-with? % "ft/"))
                (map #(type-of s %) order)))))

(deftest inlined-scalars-stay-on-their-page
  (let [s (store/mem-store)
        nums (mapv #(v/i64 s %) (range 40))
        order (reveal/page-order s (v/hash (apply v/vector s nums)))
        types (mapv #(type-of s %) order)]
    (is (= "vector" (first types)))
    (is (some #(str/starts-with? % "ft/") types))
    (is (not (some #{"i64"} types)))))

(deftest bare-leaf-root-is-still-sent
  (let [s (store/mem-store)
        n (v/i64 s 7)
        order (reveal/page-order s (v/hash (v/vector s n)))]
    (is (= ["vector" "i64"] (mapv #(type-of s %) order)))
    (is (= (v/hash n) (second order)))))

(deftest vector-owns-its-pages-before-a-nested-body
  (let [s (store/mem-store)
        a (v/vector s (v/i64 s 1))
        b (v/vector s (v/i64 s 2))
        outer (v/vector s a b)
        order (reveal/page-order s (v/hash outer))
        ia (idx order (v/hash a))
        ib (idx order (v/hash b))]
    (is (= (v/hash outer) (first order)))
    (is (< 0 ia ib))
    (doseq [page (rest (reveal/page-order s (v/hash a)))]
      (let [ip (idx order page)]
        (is (< ia ip ib))))
    (is (= (count order) (count (set order))))))

(deftest long-string-pages-are-first-most
  (let [s (store/mem-store)
        text (str (apply str (repeat 2000 "a"))
                  (apply str (repeat 2000 "z")))
        h (v/hash (v/string s text))
        order (reveal/page-order s h)
        digits (filterv #(= "ft/digit" (type-of s %)) order)
        leaves (fn [page]
                 (mapv #(v/native (v/wrap-hash s %))
                       (ft/ft-leaves s page)))]
    (is (= "string" (type-of s (first order))))
    (is (> (count digits) 1))
    (is (= \a (first (leaves (first digits)))))
    (is (not (some #{\z} (leaves (first digits)))))
    (is (= \z (peek (leaves (peek digits)))))))

(deftest map-sends-keys-before-values-in-hamt-order
  (let [s (store/mem-store)
        k1 (v/i64 s 1)
        k2 (v/i64 s 100)
        k3 (v/i64 s 7)
        m (v/map s k1 (v/string s "a")
                 k2 (v/string s "b")
                 k3 (v/string s "c"))
        order (reveal/page-order s (v/hash m))
        root (:root (types/entry-data (store/require-node s (v/hash m))))
        entries (hamt/hamt-entries s root)
        key-at (mapv #(idx order (first %)) entries)
        val-at (mapv #(idx order (second %)) entries)]
    (is (= (v/hash m) (first order)))
    (is (apply < key-at))
    (is (every? #(< (apply max key-at) %) val-at))
    (is (= (count order) (count (set order))))))

(deftest set-sends-each-element-once
  (let [s (store/mem-store)
        a (v/i64 s 1)
        b (v/i64 s 2)
        c (v/i64 s 3)
        st (v/set s a b c)
        order (reveal/page-order s (v/hash st))
        root (:root (types/entry-data (store/require-node s (v/hash st))))
        elements (mapv first (hamt/hamt-entries s root))]
    (is (= elements (mapv #(nth order (idx order %)) elements)))
    (is (= 1 (count (filter #{(v/hash a)} order))))
    (is (= 1 (count (filter #{(v/hash b)} order))))
    (is (= 1 (count (filter #{(v/hash c)} order))))))

(deftest omit-sent-keeps-unsent-order
  (is (= [:b :d] (reveal/omit-sent [:a :b :c :d] #{:a :c}))))

(deftest interest-decays-from-the-peak
  (is (= 4.0 (reveal/interest 4 10 0)))
  (is (< (Math/abs (- (/ 4.0 Math/E) (reveal/interest 4 10 10))) 1e-9))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tau"
                        (reveal/interest 1 0 1))))

(deftest shares-follow-age-floor-and-cancel
  (testing "the newer request takes the larger share"
    (let [sh (reveal/shares [{:id :old :opened-at 0}
                             {:id :new :opened-at 10}]
                            10
                            {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 100.0})]
      (is (> (:new sh) (:old sh)))
      (is (< (Math/abs (- 100.0 (+ (:new sh) (:old sh)))) 1e-6))))
  (testing "below the floor the reveal drops out and the other takes the ceiling"
    (let [sh (reveal/shares [{:id :old :opened-at 0}
                             {:id :new :opened-at 100}]
                            100
                            {:peak 1.0 :tau 10.0 :floor 0.05 :r-max 80.0})]
      (is (= [:new] (keys sh)))
      (is (< (Math/abs (- 80.0 (:new sh))) 1e-6))))
  (testing "cancel drops that share at once"
    (let [sh (reveal/shares [{:id :a :opened-at 0 :cancelled? true}
                             {:id :b :opened-at 0}]
                            0
                            {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 50.0})]
      (is (= [:b] (keys sh)))
      (is (< (Math/abs (- 50.0 (:b sh))) 1e-9))))
  (testing "no reveal in the sum"
    (is (= {} (reveal/shares [{:id :a :opened-at 0 :done? true}]
                             0
                             {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 10.0})))))

(deftest next-page-goes-to-the-largest-share
  (let [params {:peak 1.0 :tau 10.0 :floor 0.01 :r-max 100.0}
        reveals [{:id :old :opened-at 0}
                 {:id :new :opened-at 10}]]
    (is (= :new (reveal/next-reveal reveals 10 params)))
    (is (nil? (reveal/next-reveal [{:id :old :opened-at 0 :cancelled? true}]
                                  0 params)))))
