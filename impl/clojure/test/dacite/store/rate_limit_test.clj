(ns dacite.store.rate-limit-test
  (:require [clojure.test :refer [deftest is]]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.store.rate-limit :as rl]
            [dacite.store.client-cache :as client-cache]
            [dacite.service :as svc]
            [dacite.store.remote :as remote]
            [dacite.examples.todo :as todo]
            [dacite.value.types :as types]))

(defn- fake-clock
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

(deftest take-tokens-blocks-when-empty
  (let [{:keys [now-fn sleep-fn sleeps]} (fake-clock)
        state (atom {:tokens 0.0 :last-ms 0})
        opts {:capacity 2.0 :rate 10.0 :cost 1.0
              :now-fn now-fn :sleep-fn sleep-fn}]
    ;; rate 10/s, need 1 token from 0 → wait ~100ms
    (rl/take-tokens! state opts)
    (is (seq @sleeps))
    (is (>= (reduce + 0 @sleeps) 100))
    (is (< (:tokens @state) 0.01))))

(deftest rate-limit-send-chunk-consumes-tokens
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

(deftest find-chunk-transport-prefers-rate-limit
  (let [sink (->CountingSink (atom 0) (atom nil))
        limited (rl/rate-limit-store sink {:capacity 5 :rate 100.0})]
    (is (instance? dacite.store.rate_limit.RateLimitStore
                   (pack/find-chunk-transport limited)))))

(deftest istore-passthrough-not-metered
  (let [{:keys [now-fn sleep-fn sleeps]} (fake-clock)
        mem (store/mem-store)
        limited (rl/rate-limit-store mem
                                     {:capacity 1
                                      :rate 0.001
                                      :now-fn now-fn
                                      :sleep-fn sleep-fn})
        h [1 0 0 0]]
    (store/s-put limited h ["i64" 1])
    (is (= ["i64" 1] (store/s-get limited h)))
    (is (store/s-has? limited h))
    (is (empty? @sleeps) "IStore ops do not take tokens")))

(deftest write-back-flush-through-rate-limit
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [{:keys [now-fn sleep-fn]} (fake-clock)
            raw (remote/remote-store base-url)
            limited (rl/rate-limit-store raw
                                         {:capacity 100
                                          :rate 1000.0
                                          :now-fn now-fn
                                          :sleep-fn sleep-fn})
            wb (client-cache/wrap limited :write-back)
            t (todo/build wb (todo/seed-items))
            h (types/dacite-hash t)
            n (client-cache/flush-reachable! wb h)]
        (is (pos? n))
        (is (store/s-has? raw h))
        (is (zero? (client-cache/flush-reachable! wb h))))
      (finally
        (stop!)))))

(deftest try-take-tokens-does-not-block
  (let [{:keys [now-fn advance!]} (fake-clock)
        state (atom {:tokens 1.0 :last-ms 0})
        opts {:capacity 1.0 :rate 10.0 :cost 1.0 :now-fn now-fn}]
    (is (true? (:ok (rl/try-take-tokens! state opts))))
    (let [denied (rl/try-take-tokens! state opts)]
      (is (false? (:ok denied)))
      (is (>= (:retry-after-ms denied) 100)))
    (advance! 100)
    (is (true? (:ok (rl/try-take-tokens! state opts))))))

(deftest invalid-opts-throw
  (is (thrown? Exception
               (rl/rate-limit-store (store/mem-store) {:capacity 0 :rate 1})))
  (let [s (rl/rate-limit-store (store/mem-store) {:capacity 1 :rate 1})
        st (:state s)]
    (is (thrown? Exception
                 (rl/take-tokens! st {:capacity 1 :rate 0 :cost 1
                                      :now-fn (constantly 0)
                                      :sleep-fn (fn [_])})))
    (is (thrown? Exception
                 (rl/try-take-tokens! st {:capacity 1 :rate 0 :cost 1
                                          :now-fn (constantly 0)})))))
