(ns dacite.peer.bucket
  "A token bucket, pure and portable: the request limit of spec 12.11 (C40,
   C41). A bucket holds up to `burst` tokens and refills at `rate` a second;
   each `get` costs one. Time is passed in (milliseconds), so a test runs on a
   simulated clock.")

(defn bucket
  "A full bucket: `rate` tokens a second, up to `burst`, as of `now-ms`."
  [rate burst now-ms]
  {:rate rate :burst burst :tokens burst :at now-ms})

(defn- refill [{:keys [rate burst tokens at] :as b} now-ms]
  (assoc b
         :tokens (min burst (+ tokens (* rate (/ (max 0 (- now-ms at)) 1000))))
         :at (max at now-ms)))

(defn take-token
  "Spend a token at `now-ms`. Returns [bucket' ok? retry-ms]: ok? is false when
   none is left (the bucket is unchanged but for the refill), and retry-ms is the
   time until one is."
  [b now-ms]
  (let [{:keys [rate tokens] :as b} (refill b now-ms)]
    (if (>= tokens 1)
      [(assoc b :tokens (- tokens 1)) true 0]
      [b false (long (+ 1 (quot (* 1000 (- 1 tokens)) rate)))])))

(defn has-token?
  "True when a token is there at `now-ms`."
  [b now-ms]
  (>= (:tokens (refill b now-ms)) 1))
