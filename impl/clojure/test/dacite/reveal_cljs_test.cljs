(ns dacite.reveal-cljs-test
  "ClojureScript side of the reveal clock. Run from the repo root:

     npx nbb -cp impl/clojure/src:impl/clojure/test -m dacite.reveal-cljs-test

   The floor nudge is the case that differs: `Math.nextUp` is not in
   JavaScript, and a half-ulp bump does not move a power of two."
  (:require [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.value :as v]))

(defn- fail!
  [msg data]
  (throw (ex-info msg data)))

(defn- eq!
  [actual expected msg]
  (when-not (= actual expected)
    (fail! msg {:actual actual :expected expected})))

(defn- near!
  [actual expected msg]
  (when-not (< (js/Math.abs (- actual expected)) 1.0e-6)
    (fail! msg {:actual actual :expected expected})))

(defn- lone-decay
  []
  (let [sh (reveal/shares [{:id :only :opened-at 0}]
                          1
                          {:peak 1.0 :tau 1.0 :floor 0.0 :r-max 100.0})]
    (near! (:only sh) (* 100.0 (js/Math.exp -1.0))
           "a lone reveal decays from the ceiling")))

(defn- floor-at-a-power-of-two
  []
  (let [s (store/mem-store)
        ha (v/hash (v/i64 s 1))
        hb (v/hash (v/i64 s 2))
        params {:peak 1.0 :tau 1.0 :floor (js/Math.exp -1.0) :r-max 1.0}
        a (reveal/open-reveal (reveal/session) s ha 0 params)
        b (reveal/open-reveal (:session a) s hb 0.5 params)
        at-one (reveal/take-pages (:session b) s 1.0 params)
        credit (fn [sess id]
                 (:credit (first (filter #(= id (:id %))
                                         (:reveals sess)))))
        c1 (credit (:session at-one) (:id b))
        later (reveal/take-pages (:session at-one) s 2.0 params)
        c2 (credit (:session later) (:id b))]
    (when-not (> c2 (+ c1 1.0e-6))
      (fail! "the floor at t = 1 must not stop the other reveal"
             {:before c1 :after c2}))))

(defn- page-is-later-than-the-constant-rate
  []
  (let [s (store/mem-store)
        h (v/hash (v/i64 s 42))
        n (double (reveal/page-bytes s h))
        params {:peak 1.0 :tau 100.0 :floor 0.0 :r-max n}
        opened (reveal/open-reveal (reveal/session) s h 0 params)
        short (reveal/take-pages (:session opened) s 1.0 params)
        at (* -100.0 (js/Math.log (- 1.0 (/ 1.0 100.0))))
        due (reveal/take-pages (:session short) s at params)]
    (eq! (count (:pages short)) 0 "constant-rate time is still short")
    (eq! (count (:pages due)) 1 "the integral reaches the page")
    (eq! (:hash (first (:pages due))) h "the page is the scalar")))

(defn -main
  [& _]
  (lone-decay)
  (floor-at-a-power-of-two)
  (page-is-later-than-the-constant-rate)
  (println "reveal cljs tests passed"))
