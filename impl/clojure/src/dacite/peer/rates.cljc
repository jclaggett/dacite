(ns dacite.peer.rates
  "Peer protocol v1: the rate arithmetic, pure and portable.

   Spec: docs/spec/peer-v1.md §12.2 (C01, C02, C03, C45).

   Interest is in thousandths, as on the wire (`peak` 1000 is 1.0). One
   interest curve, `I(t) = peak · exp(−t / tau)`, is
   `dacite.reveal.engine/interest`. A rate is bytes a second."
  (:require [dacite.host :as host]
            [dacite.reveal.engine :as reveal]))

(def peak
  "The peak of a connection: the C01 divisor `max(peak, Σ I)` uses it."
  1000)

(def default-interest
  "What a `get` that names no interest means, and what the client sends: peak
   1.0, tau 10 s, floor 0.05 (thousandths, milliseconds)."
  {"peak" 1000 "tau-ms" 10000 "floor" 50})

(defn interest
  "Interest at `age-ms` of a stream with interest `peak0` and `tau-ms`."
  [peak0 tau-ms age-ms]
  (reveal/interest peak0 tau-ms age-ms))

(defn split
  "C01: `r-max` split by interest. `interests` is a sequence of numbers; returns
   the rate of each, in order: `r-max · I / max(peak, Σ I)`. When the total is
   below `peak`, part of `r-max` goes unused."
  [r-max interests]
  (let [denom (max peak (reduce + 0 interests))]
    (mapv #(/ (* r-max %) denom) interests)))

(defn peer-shares
  "C01 and C02: the rate of each peer. `peers` is {peer-id {:interest Σ I of the
   peer's streams, :cap its send-max (absent: no cap)}}. Each peer gets its C01
   share, at most its cap; what a capped peer cannot use goes to the others in
   proportion to their interest, repeating until no peer is over its cap. The
   sum never exceeds `r-max` and no peer exceeds its cap."
  [r-max peers]
  (let [cap-of #(or (:cap (get peers %)) 1e18)
        int-of #(:interest (get peers %))
        init (zipmap (keys peers) (split r-max (map int-of (keys peers))))]
    (loop [rate init fixed #{}]
      (let [over (filter #(and (not (fixed %)) (> (get rate %) (cap-of %))) (keys rate))]
        (if (empty? over)
          rate
          (let [excess (reduce + (map #(- (get rate %) (cap-of %)) over))
                fixed' (into fixed over)
                rate' (reduce #(assoc %1 %2 (cap-of %2)) rate over)
                free (remove fixed' (keys rate))
                free-interest (reduce + (map int-of free))]
            (recur (if (pos? free-interest)
                     (reduce (fn [m id] (update m id + (/ (* excess (int-of id)) free-interest)))
                             rate' free)
                     rate')
                   fixed')))))))

(defn session-rate
  "C03: the rate a responder paces a session at: the smaller of its own
   `send-max` and the session's budget (the `recv-max` of the latest `get`)."
  [send-max budget]
  (min send-max budget))

(defn recv-max
  "C45: the `recv-max` for a new `get` to a peer whose current C01/C02 share is
   `share`: that share, rounded down, and at least 1 (the wire needs a positive
   number). It does not look at what the other peers were last told: they may still
   be sending at an older, higher `recv-max`, and the client is over `R_max` until
   their next `get` or their interest decays (spec 12.2, known gap)."
  [share]
  (max 1 (host/floor share)))
