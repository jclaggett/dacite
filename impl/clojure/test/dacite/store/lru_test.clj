(ns dacite.store.lru-test
  "LRU store tests, on the JVM.

   The bodies are `dacite.store.lru-checks`. nbb runs the same checks
   (`npm run lru:test`). One deftest each, so a failure names the check."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.lru-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [t0 (System/currentTimeMillis)
        problems (vec ((get checks/checks name)))
        ms (- (System/currentTimeMillis) t0)]
    (is (empty? problems) (pr-str problems))
    (is (< ms checks/max-ms) (str name " took " ms " ms"))))

(deftest lru-eviction-test
  (run-check "lru-eviction-test"))

(deftest lru-read-through-touch-test
  (run-check "lru-read-through-touch-test"))

(deftest lru-delete-test
  (run-check "lru-delete-test"))

(deftest lru-real-hash-roundtrip-test
  (run-check "lru-real-hash-roundtrip-test"))

(deftest lru-real-hash-eviction-test
  (run-check "lru-real-hash-eviction-test"))

(deftest lru-real-hash-sweep-test
  (run-check "lru-real-hash-sweep-test"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.store.lru-test)))))
               "every-check-has-a-deftest"))
      "lru_checks.cljc and this file list the same tests"))
