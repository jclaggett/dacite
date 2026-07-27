(ns dacite.store.rate-limit
  "Token-bucket rate limit for store composition — meters packages, not values.

   Sit **under** packing (flush-from! / put-items-chunked!) so each send-chunk!
   costs tokens:

     write-back → pack flush → rate-limit → remote

   Empty bucket blocks (sleeps) until tokens refill. Does not implement value
   completeness; only paces IChunkTransport traffic.

   See docs/design/store-composition-pack.md."
  (:require [dacite.store :as store]
            [dacite.store.pack :as pack]))

(defn- default-now-ms
  "Wall-clock milliseconds."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn- default-sleep-ms
  "Block the current thread/fiber for ms (portable best-effort)."
  [ms]
  (let [ms (long (max 0 ms))]
    (when (pos? ms)
      #?(:clj (Thread/sleep ms)
         :cljs
         ;; Sync XHR demos: busy-wait (no real sleep in browser JS).
         (let [deadline (+ (default-now-ms) ms)]
           (loop []
             (when (< (default-now-ms) deadline)
               (recur))))))))

(defn- refill
  "Continuous refill: tokens = min(capacity, tokens + rate * Δt seconds)."
  [tokens last-ms now-ms capacity rate]
  (let [elapsed-sec (max 0.0 (/ (double (- now-ms last-ms)) 1000.0))
        gained (* (double rate) elapsed-sec)]
    (min (double capacity) (+ (double tokens) gained))))

(defn take-tokens!
  "Block until `cost` tokens are available, then deduct them.

   state — atom of {:tokens double :last-ms long}
   opts  — :capacity :rate :cost :now-fn :sleep-fn

   Returns true when tokens were taken."
  [state {:keys [capacity rate cost now-fn sleep-fn]
          :or {cost 1
               now-fn default-now-ms
               sleep-fn default-sleep-ms}}]
  (let [capacity (double capacity)
        rate (double rate)
        cost (double cost)]
    (when-not (and (pos? capacity) (pos? rate) (pos? cost))
      (throw (ex-info "rate-limit requires positive :capacity, :rate, and :cost"
                      {:capacity capacity :rate rate :cost cost})))
    (loop []
      (let [st @state
            now (long (now-fn))
            tokens' (refill (:tokens st) (:last-ms st) now capacity rate)]
        (if (>= tokens' cost)
          (if (compare-and-set! state st {:tokens (- tokens' cost)
                                          :last-ms now})
            true
            (recur))
          (let [need (- cost tokens')
                wait-ms (long (Math/ceil (* 1000.0 (/ need rate))))]
            (sleep-fn (max 1 wait-ms))
            ;; Account for wall time during sleep (or fake clock advance).
            (let [now2 (long (now-fn))]
              (swap! state
                     (fn [s]
                       {:tokens (refill (:tokens s) (:last-ms s) now2
                                        capacity rate)
                        :last-ms now2})))
            (recur)))))))

(defrecord RateLimitStore [inner state opts]
  pack/IChunkTransport
  (send-chunk! [_ chunk]
    (take-tokens! state opts)
    (pack/send-chunk! (pack/find-chunk-transport inner) chunk))

  store/IStore
  (s-get [_ h] (store/s-get inner h))
  (s-put [this h value] (store/s-put inner h value) this)
  (s-has? [_ h] (store/s-has? inner h))
  (s-delete [this h] (store/s-delete inner h) this)
  (s-snapshot [_] (store/s-snapshot inner))
  (s-merge [this m] (store/s-merge inner m) this)
  (s-reset [this] (store/s-reset inner) this))

(defn rate-limit-store
  "Wrap inner store with a token-bucket limiter on send-chunk!.

   opts:
     :capacity  — max tokens (bucket size), default 10
     :rate      — tokens per second refill, default 10.0
     :cost      — tokens per send-chunk!, default 1
     :now-fn    — () -> epoch-ms (tests inject fake clock)
     :sleep-fn  — (ms) -> nil (tests record/advance clock)

   IStore ops pass through without taking tokens (cache hits, local puts).
   Only IChunkTransport/send-chunk! is metered — pack packages, not nodes.

   Recommended stack:
     (client-cache/wrap
       (rate-limit-store (remote/remote-store url) {:capacity 20 :rate 5.0})
       :write-back)"
  ([inner] (rate-limit-store inner nil))
  ([inner {:keys [capacity rate cost now-fn sleep-fn]
           :or {capacity 10
                rate 10.0
                cost 1
                now-fn default-now-ms
                sleep-fn default-sleep-ms}
           :as opts}]
   (let [capacity (double capacity)
         rate (double rate)
         cost (double (or cost 1))]
     (when-not (and (pos? capacity) (pos? rate) (pos? cost))
       (throw (ex-info "rate-limit requires positive :capacity, :rate, and :cost"
                       {:capacity capacity :rate rate :cost cost})))
     (let [opts' {:capacity capacity
                  :rate rate
                  :cost cost
                  :now-fn now-fn
                  :sleep-fn sleep-fn}
           now (long (now-fn))
           state (atom {:tokens capacity
                        :last-ms now})]
       (->RateLimitStore inner state opts')))))

(defn tokens-available
  "Current token balance after refill to now (for tests/metrics)."
  [^RateLimitStore s]
  (let [{:keys [capacity rate now-fn]} (:opts s)
        st @(:state s)
        now (long (now-fn))]
    (refill (:tokens st) (:last-ms st) now capacity rate)))
