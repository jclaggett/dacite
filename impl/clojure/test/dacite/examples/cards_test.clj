(ns dacite.examples.cards-test
  "Cards domain on a mem store."
  (:require [clojure.test :refer [deftest is]]
            [dacite.examples.cards :as cards]
            [dacite.store :as store]
            [dacite.value :as v]))

(defn- card-hashes [deck]
  (into #{} (map v/hash) (v/seq deck)))

(deftest shuffle-empty-and-one-are-identity
  (let [st (store/mem)
        empty (v/vector st)
        one (v/vector st "A♠")]
    (is (= (v/hash empty) (v/hash (cards/shuffle-deck empty))))
    (is (= (v/hash one) (v/hash (cards/shuffle-deck one))))))

(deftest shuffle-is-a-permutation
  (let [st (store/mem)
        deck (cards/standard-deck st)
        shuffled (cards/shuffle-deck deck)]
    (is (= 52 (v/count shuffled)))
    (is (= (card-hashes deck) (card-hashes shuffled)))
    (is (v/dacite-value? shuffled))
    (is (= "vector" (v/type shuffled)))))

(deftest shuffle-with-rng-is-deterministic
  (let [st (store/mem)
        deck (cards/standard-deck st)
        always-0 (fn [_] 0)
        a (cards/shuffle-deck deck always-0)
        b (cards/shuffle-deck deck always-0)]
    (is (= (v/hash a) (v/hash b)))
    (is (= (card-hashes deck) (card-hashes a)))))

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
