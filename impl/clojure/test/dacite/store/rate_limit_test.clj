(ns dacite.store.rate-limit-test
  "Rate-limit tests on the JVM.

   The bodies are `dacite.store.rate-limit-checks`. nbb runs the same checks
   (`npm run store-units:test`). One deftest each, so the JVM suite still
   names the test it ran before. `write-back-flush-through-rate-limit` stays
   JVM only: it starts the HTTP service (`dacite.service`)."
  (:require [clojure.test :refer [deftest is]]
            [dacite.examples.todo :as todo]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.check :as check]
            [dacite.store.client-cache :as client-cache]
            [dacite.store.rate-limit :as rl]
            [dacite.store.rate-limit-checks :as checks]
            [dacite.store.remote :as remote]
            [dacite.value.types :as types]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest take-tokens-blocks-when-empty
  (run-check "take-tokens-blocks-when-empty"))

(deftest rate-limit-send-chunk-consumes-tokens
  (run-check "rate-limit-send-chunk-consumes-tokens"))

(deftest find-chunk-transport-prefers-rate-limit
  (run-check "find-chunk-transport-prefers-rate-limit"))

(deftest istore-passthrough-not-metered
  (run-check "istore-passthrough-not-metered"))

(deftest try-take-tokens-does-not-block
  (run-check "try-take-tokens-does-not-block"))

(deftest invalid-opts-throw
  (run-check "invalid-opts-throw"))

;; JVM only. See the namespace docstring.

(deftest write-back-flush-through-rate-limit
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [{:keys [now-fn sleep-fn]} (checks/fake-clock)
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
        (is (store/await (store/s-has? raw h)))
        (is (zero? (client-cache/flush-reachable! wb h))))
      (finally
        (stop!)))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.store.rate-limit-test)))))
               "every-check-has-a-deftest"
               "write-back-flush-through-rate-limit"))
      "rate_limit_checks.cljc and this file list the same tests"))
