(ns dacite.store.rate-limit-test
  "Rate-limit tests on the JVM.

   The bodies are `dacite.store.rate-limit-checks`. nbb runs the same checks
   (`npm run store-units:test`). One deftest each, so the JVM suite still
   names the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.store.rate-limit-checks :as checks]))

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

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.store.rate-limit-test)))))
               "every-check-has-a-deftest"))
      "rate_limit_checks.cljc and this file list the same tests"))
