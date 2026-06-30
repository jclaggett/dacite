(ns examples.cards
  "Two-player card game modeled entirely as Dacite values (value API).

   The deck and each player's hand are Dacite vectors. Game state is a Dacite
   map. Drawing takes the top card off the deck (vector peek/pop) and adds it
   to a hand (vector conj). Every update returns new immutable values in the
   same store.

   Run from impl/clojure:
     clojure -M:cards"
  (:require [dacite.store :as store]
            [dacite.value :as v]))

;; =============================================================================
;; Card model
;; =============================================================================

(def ^:private suits
  ["♣" "♦" "♥" "♠"])

(def ^:private ranks
  ["A" "2" "3" "4" "5" "6" "7" "8" "9" "10" "J" "Q" "K"])

(defn- card-label
  "Build a Dacite string card label, e.g. \"K♠\"."
  [rank suit]
  (v/string (str rank suit)))

(defn standard-deck
  "A full 52-card deck as a Dacite vector. The top of the deck is the last
   element (IPersistentStack peek/pop)."
  []
  (apply v/vector
         (for [suit suits rank ranks]
           (card-label rank suit))))

(defn shuffle-deck
  "Return a new deck vector with the same cards in random order."
  [deck]
  (apply v/vector (shuffle (vec (or (seq deck) ())))))

;; =============================================================================
;; Game state
;; =============================================================================

(def ^:private player-keys
  ["player-1" "player-2"])

(defn new-game
  "Initial game: full deck and empty hands for both players."
  []
  (v/hash-map
   "deck" (shuffle-deck (standard-deck))
   "player-1" (v/vector)
   "player-2" (v/vector)))

(defn deck-size [game]
  (count (get game "deck")))

(defn hand-size [game player-key]
  (count (get game player-key)))

(defn- show-card [card]
  (apply str card))

(defn- show-hand [hand]
  (mapv show-card hand))

(defn show-game [game]
  {:deck-size (deck-size game)
   :player-1 (show-hand (-> game (get "player-1") v/realize))
   :player-2 (show-hand (-> game (get "player-2") v/realize))})

(defn draw-card
  "Draw one card from the top of the deck into the given player's hand.
   Returns a new game-state map; the deck and hand are new Dacite values."
  [game player-key]
  {:pre [(some #{player-key} player-keys)]}
  (let [deck (get game "deck")]
    (when (zero? (count deck))
      (throw (ex-info "Deck is empty" {:player player-key})))
    (let [card (peek deck)
          deck' (pop deck)
          hand' (conj (get game player-key) card)]
      (assoc game "deck" deck' player-key hand'))))

(defn draw-round
  "Each player draws one card. Player 1 draws first."
  [game]
  (-> game
      (draw-card "player-1")
      (draw-card "player-2")))

;; =============================================================================
;; Demo
;; =============================================================================

(defn -main
  [& _]
  (println "=== Dacite cards (value) ===")
  (println)
  (store/reset-store!)
  (let [[_ final-game]
        (v/with-store [_ (store/mem-store)]
          (let [game0 (new-game)]
            (println "New game:" (pr-str (show-game game0)))
            (println)
            (loop [game game0
                   round 1]
              (cond
                (zero? (deck-size game))
                (do
                  (println "Deck empty.")
                  game)

                (> round 5)
                (do
                  (println "Stopped after 5 rounds.")
                  game)

                :else
                (let [game' (draw-round game)]
                  (println (str "Round " round ":")
                           (pr-str (show-game game')))
                  (recur game' (inc round)))))))]
    (println)
    (println "Final state:" (pr-str (show-game final-game)))))

(comment
  (store/reset-store!)
  (v/with-store [_ (store/mem-store)]
    (let [g (-> (new-game)
                (draw-card "player-1")
                (draw-card "player-2"))]
      (show-game g))))
