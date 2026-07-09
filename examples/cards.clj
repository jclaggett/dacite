(ns examples.cards
  "Two-player card game modeled entirely as Dacite values (value API).

   The deck and each player's hand are Dacite vectors. Game state is a Dacite
   map. Drawing takes the top card off the deck (vector peek/pop) and adds it
   to a hand (vector conj). Every update returns new immutable values in the
   same store.

   Game state persists in an LMDB-backed rooted store (content + root). A
   second run resumes from the saved root unless the store path is removed.

   Run from impl/clojure:
     clojure -M:cards"
  (:require [dacite.rooted :as rs]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.value.types :as types]))

(def ^:private default-lmdb-path
  "target/dacite-cards")

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
;; Store
;; =============================================================================

(defn make-cards-store
  "Wrap an open LMDB store as a rooted store (content + root cell)."
  [lmdb]
  (rs/rooted-store lmdb (rs/lmdb-root-cell lmdb)))

(defn get-game
  "Load the current game from the rooted store, or nil if root is unset."
  [store]
  (when-let [h @store]
    (v/get-value-with-store store h)))

(defn init-game!
  "Create a new game and commit its hash as the store root."
  [store]
  (let [game (new-game)]
    (reset! store (types/dacite-hash game))
    game))

(defn load-or-init-game!
  "Load persisted game state, or initialize and commit a new game."
  [store]
  (or (get-game store) (init-game! store)))

(defn commit-game!
  "Persist the game as the store root. Returns the game."
  [store game]
  (reset! store (types/dacite-hash game))
  game)

;; =============================================================================
;; Demo
;; =============================================================================

(defn -main
  [& _]
  (println "=== Dacite cards (value) ===")
  (println)
  (with-open [lmdb (store/lmdb-store default-lmdb-path)]
    (let [store (make-cards-store lmdb)
          [_ final-game]
          (v/with-store [_ store]
            (let [had-root? (some? @store)
                  game0 (load-or-init-game! store)]
              (println (if had-root?
                         "Resumed game:"
                         "New game:")
                       (pr-str (show-game game0)))
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
                  (let [game' (commit-game! store (draw-round game))]
                    (println (str "Round " round ":")
                             (pr-str (show-game game')))
                    (recur game' (inc round)))))))]
      (println)
      (println "Final state:" (pr-str (show-game final-game))))))

(comment
  ;; Primary entry point: clojure -M:cards (from impl/clojure)
  (with-open [lmdb (store/lmdb-store default-lmdb-path)]
    (let [store (make-cards-store lmdb)]
      (v/with-store [_ store]
        (let [game (-> (load-or-init-game! store)
                       (draw-card "player-1")
                       (draw-card "player-2"))]
          (commit-game! store game)
          (show-game game))))))
