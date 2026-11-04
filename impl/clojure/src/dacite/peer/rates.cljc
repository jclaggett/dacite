(ns dacite.peer.rates
  "Peer protocol v1: the rate arithmetic, pure and portable.

   Spec: docs/spec/peer-v1.md §12.2 (C01, C02, C03, C45).

   Interest is in thousandths, as on the wire (`peak` 1000 is 1.0). One
   interest curve, `I(t) = peak · exp(−t / tau)`, is `dacite.reveal/interest`.
   A rate is bytes a second."
  (:require [dacite.reveal :as reveal]))

(def peak
  "The peak of a connection: the C01 divisor `max(peak, Σ I)` uses it, and so
   does the C45 commitment, `min(1, Σ I / peak)`."
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

(defn commitment
  "C45: what a peer is committed to send now. Under C03 the responder paces at
   `recv-max · min(1, Σ I / peak)`, where Σ I is the interest of the peer's open
   streams now, so the client can compute it. `nil` last `recv-max`: nothing."
  [last-recv-max sum-interest]
  (if last-recv-max
    (* last-recv-max (min 1 (/ sum-interest peak)))
    0))

(defn- floor-long [x]
  #?(:clj (long (Math/floor (double x))) :cljs (js/Math.floor x)))

(defn recv-max
  "C45 with C01/C02: the `recv-max` for a new `get` to a peer whose share is
   `share`: at most that share, and at most `r-max` minus the others' current
   `commitments` (a number), but at least 1 (the wire needs one). So the sum of
   current commitments stays at or below `r-max`, except that a floor of 1 byte
   a second may add a byte when nothing is left."
  [r-max share others-commitment]
  (max 1 (floor-long (min share (- r-max others-commitment)))))
