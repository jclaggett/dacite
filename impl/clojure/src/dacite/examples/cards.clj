(ns dacite.examples.cards
  "Two-player card game modeled entirely as Dacite values (value API).

   The deck and each player's hand are Dacite vectors. Game state is a Dacite
   map. Drawing takes the top card off the deck (vector peek/pop) and adds it
   to a hand (vector conj). Every update returns new immutable values in the
   same store.

   Game state persists in an LMDB-backed rooted store (content + root). A
   second run resumes from the saved root unless the store path is removed.

   Run from impl/clojure:
     clojure -M:cards"
  (:require [dacite.store :as store]
            [dacite.value :as v]))

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
  [peer rank suit]
  (v/string peer (str rank suit)))

(defn standard-deck
  "A full 52-card deck as a Dacite vector relative to `peer`."
  [peer]
  (apply v/vector peer
         (for [suit suits rank ranks]
           (card-label peer rank suit))))

(defn shuffle-deck
  "Return a new deck vector with the same cards in random order."
  [deck]
  (apply v/vector deck (shuffle (vec (or (v/seq deck) ())))))

;; =============================================================================
;; Game state
;; =============================================================================

(def ^:private player-keys
  ["player-1" "player-2"])

(defn new-game
  "Initial game relative to `peer`: full deck and empty hands."
  [peer]
  (v/map peer
         "deck" (shuffle-deck (standard-deck peer))
         "player-1" (v/vector peer)
         "player-2" (v/vector peer)))

(defn deck-size [game]
  (v/count (v/get game "deck")))

(defn hand-size [game player-key]
  (v/count (v/get game player-key)))

(defn- show-card [card]
  (apply str (v/realize card)))

(defn- show-hand [hand]
  (mapv show-card (or (v/seq hand) ())))

(defn show-game [game]
  {:deck-size (deck-size game)
   :player-1 (show-hand (v/get game "player-1"))
   :player-2 (show-hand (v/get game "player-2"))})

(defn draw-card
  "Draw one card from the top of the deck into the given player's hand.
   Returns a new game-state map; the deck and hand are new Dacite values."
  [game player-key]
  {:pre [(some #{player-key} player-keys)]}
  (let [deck (v/get game "deck")]
    (when (zero? (v/count deck))
      (throw (ex-info "Deck is empty" {:player player-key})))
    (let [card (v/peek deck)
          deck' (v/pop deck)
          hand' (v/conj (v/get game player-key) card)]
      (-> game
          (v/assoc "deck" deck')
          (v/assoc player-key hand')))))

(defn draw-round
  "Each player draws one card. Player 1 draws first."
  [game]
  (-> game
      (draw-card "player-1")
      (draw-card "player-2")))

;; =============================================================================
;; Store
;; =============================================================================

(defn make-game-ref
  "Wrap a rooted store as a value-level root."
  [rs]
  (v/root rs))

(defn get-game
  "Load the current game from the root, or nil if unset."
  [game-ref]
  (v/deref game-ref))

(defn init-game!
  "Create a new game and commit it as the root. Returns the game."
  [game-ref]
  (let [game (new-game game-ref)]
    (v/cas! game-ref nil game)
    game))

(defn load-or-init-game!
  "Load persisted game state, or initialize and commit a new game."
  [game-ref]
  (or (get-game game-ref) (init-game! game-ref)))

(defn commit-game!
  "Persist the game as the root. Returns the game."
  [game-ref game]
  (v/swap! game-ref (fn [_] game))
  game)

;; =============================================================================
;; Demo
;; =============================================================================

(defn -main
  [& _]
  (println "=== Dacite cards (value) ===")
  (println)
  (let [game-ref (v/root (store/lmdb default-lmdb-path))
        had-root? (some? (v/deref game-ref))
        game0 (load-or-init-game! game-ref)]
    (println (if had-root?
               "Resumed game:"
               "New game:")
             (pr-str (show-game game0)))
    (println)
    (let [final-game
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
              (let [game' (commit-game! game-ref (draw-round game))]
                (println (str "Round " round ":")
                         (pr-str (show-game game')))
                (recur game' (inc round)))))]
      (println)
      (println "Final state:" (pr-str (show-game final-game))))))

(comment
  ;; Primary entry point: clojure -M:cards (from impl/clojure)
  (let [game-ref (v/root (store/lmdb default-lmdb-path))
        game (-> (load-or-init-game! game-ref)
                 (draw-card "player-1")
                 (draw-card "player-2"))]
    (commit-game! game-ref game)
    (show-game game)))
