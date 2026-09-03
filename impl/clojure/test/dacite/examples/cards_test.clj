(ns dacite.examples.cards-test
  "Cards domain on a mem store — shuffle aside, the rest is public API."
  (:require [clojure.test :refer [deftest is]]
            [dacite.examples.cards :as cards]
            [dacite.store :as store]
            [dacite.value :as v]))

(deftest deal-and-draw
  (let [r (v/root (store/mem))
        game (cards/new-game r)]
    (is (= 52 (cards/deck-size game)))
    (is (= 0 (cards/hand-size game "player-1")))
    (let [g1 (cards/draw-round game)]
      (is (= 50 (cards/deck-size g1)))
      (is (= 1 (cards/hand-size g1 "player-1")))
      (is (= 1 (cards/hand-size g1 "player-2")))
      (v/cas! r nil g1)
      (is (= (v/hash g1) (v/hash (v/deref r)))))))

(deftest load-or-init-on-mem
  (let [r (v/root (store/mem))]
    (is (nil? (cards/get-game r)))
    (let [game (cards/load-or-init-game! r)]
      (is (= 52 (cards/deck-size game)))
      (is (= (v/hash game) (v/hash (cards/get-game r)))))))
