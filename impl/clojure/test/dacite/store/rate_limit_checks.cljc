(ns dacite.store.rate-limit-checks
  "Token-bucket rate limit checks that run on the JVM and on nbb. The clock
   is fake: nothing sleeps.

   These were the bodies of `dacite.store.rate-limit-test`. The JVM runs them
   from that namespace, one deftest each under its old name. nbb runs them
   from `dacite.store.store-units-cljs-test` (`npm run store-units:test`).
   `write-back-flush-through-rate-limit` stays JVM only: it starts the HTTP
   service (`dacite.service`, `dacite.store.remote`).

   `is` is `dacite.store.check`."
  (:require [dacite.store :as store]
            [dacite.store.check :refer [is throws?]]
            [dacite.store.pack :as pack]
            [dacite.store.rate-limit :as rl]))

(defn fake-clock
  "Mutable clock for tests: now-fn / sleep-fn advance t without wall wait."
  []
  (let [t (atom 0)
        sleeps (atom [])]
    {:now-fn (fn [] @t)
     :sleep-fn (fn [ms]
                 (swap! sleeps conj (long ms))
                 (swap! t + (long ms)))
     :time t
     :sleeps sleeps
     :advance! (fn [ms] (swap! t + (long ms)))}))

(defrecord CountingSink [counter responses]
  pack/IChunkTransport
  (send-chunk! [_ chunk]
    (swap! counter inc)
    (or (first @responses)
        {:ok true :status :partial :created [] :exists []})))

(defn take-tokens-blocks-when-empty
  [_]
  (let [{:keys [now-fn sleep-fn sleeps]} (fake-clock)
        state (atom {:tokens 0.0 :last-ms 0})
        opts {:capacity 2.0 :rate 10.0 :cost 1.0
              :now-fn now-fn :sleep-fn sleep-fn}]
    ;; rate 10/s, need 1 token from 0 → wait ~100ms
    (rl/take-tokens! state opts)
    (is (seq @sleeps))
    (is (>= (reduce + 0 @sleeps) 100))
    (is (< (:tokens @state) 0.01))))

(defn rate-limit-send-chunk-consumes-tokens
  [_]
  (let [{:keys [now-fn sleep-fn sleeps]} (fake-clock)
        n (atom 0)
        sink (->CountingSink n (atom nil))
        limited (rl/rate-limit-store sink
                                     {:capacity 2
                                      :rate 1000.0
                                      :cost 1
                                      :now-fn now-fn
                                      :sleep-fn sleep-fn})
        ch {:dacite.wire/chunk-v1 true :budget 1024 :items []}]
    (pack/send-chunk! limited ch)
    (pack/send-chunk! limited ch)
    (is (= 2 @n))
    (is (empty? @sleeps) "first capacity chunks need no sleep")
    ;; third chunk needs refill
    (pack/send-chunk! limited ch)
    (is (= 3 @n))
    (is (seq @sleeps) "empty bucket sleeps before third send")))

(defn find-chunk-transport-prefers-rate-limit
  [_]
  (let [sink (->CountingSink (atom 0) (atom nil))
        limited (rl/rate-limit-store sink {:capacity 5 :rate 100.0})]
    (is (instance? #?(:clj dacite.store.rate_limit.RateLimitStore
                      :cljs rl/RateLimitStore)
                   (pack/find-chunk-transport limited)))))

(defn istore-passthrough-not-metered
  [_]
  (let [{:keys [now-fn sleep-fn sleeps]} (fake-clock)
        mem (store/mem-store)
        limited (rl/rate-limit-store mem
                                     {:capacity 1
                                      :rate 0.001
                                      :now-fn now-fn
                                      :sleep-fn sleep-fn})
        h [1 0 0 0]]
    (store/await (store/s-put limited h ["i64" 1]))
    (is (= ["i64" 1] (store/await (store/s-get limited h))))
    (is (store/await (store/s-has? limited h)))
    (is (empty? @sleeps) "IStore ops do not take tokens")))

(defn try-take-tokens-does-not-block
  [_]
  (let [{:keys [now-fn advance!]} (fake-clock)
        state (atom {:tokens 1.0 :last-ms 0})
        opts {:capacity 1.0 :rate 10.0 :cost 1.0 :now-fn now-fn}]
    (is (true? (:ok (rl/try-take-tokens! state opts))))
    (let [denied (rl/try-take-tokens! state opts)]
      (is (false? (:ok denied)))
      (is (>= (:retry-after-ms denied) 100)))
    (advance! 100)
    (is (true? (:ok (rl/try-take-tokens! state opts))))))

(defn invalid-opts-throw
  [_]
  (is (throws? #(rl/rate-limit-store (store/mem-store) {:capacity 0 :rate 1})))
  (let [s (rl/rate-limit-store (store/mem-store) {:capacity 1 :rate 1})
        st (:state s)]
    (is (throws? #(rl/take-tokens! st {:capacity 1 :rate 0 :cost 1
                                       :now-fn (constantly 0)
                                       :sleep-fn (fn [_])})))
    (is (throws? #(rl/try-take-tokens! st {:capacity 1 :rate 0 :cost 1
                                           :now-fn (constantly 0)})))))

(def checks
  {"take-tokens-blocks-when-empty" take-tokens-blocks-when-empty
   "rate-limit-send-chunk-consumes-tokens" rate-limit-send-chunk-consumes-tokens
   "find-chunk-transport-prefers-rate-limit" find-chunk-transport-prefers-rate-limit
   "istore-passthrough-not-metered" istore-passthrough-not-metered
   "try-take-tokens-does-not-block" try-take-tokens-does-not-block
   "invalid-opts-throw" invalid-opts-throw})
