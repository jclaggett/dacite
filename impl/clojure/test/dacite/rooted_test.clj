(ns dacite.rooted-test
  "Rooted store and root cell tests on the JVM.

   The bodies are `dacite.rooted-checks`, with the JVM file and LMDB stores.
   On the JVM they use the atom conveniences of a rooted store (`@s`,
   `reset!`, `swap!`, `compare-and-set!`, `add-watch`, `set-validator!`).
   nbb runs the same checks with the function API (`npm run rooted:test`).
   One deftest each, so the JVM suite still names the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.rooted-checks :as checks]
            [dacite.store-test :as store-test]
            [dacite.store.check :as check]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks store-test/env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest mem-root-cell-test
  (run-check "mem-root-cell-test"))

(deftest lmdb-root-cell-test
  (run-check "lmdb-root-cell-test"))

(deftest rooted-store-seed-test
  (run-check "rooted-store-seed-test"))

(deftest rooted-store-reset-test
  (run-check "rooted-store-reset-test"))

(deftest rooted-store-swap-test
  (run-check "rooted-store-swap-test"))

(deftest rooted-store-cas-success-test
  (run-check "rooted-store-cas-success-test"))

(deftest rooted-store-cas-conflict-test
  (run-check "rooted-store-cas-conflict-test"))

(deftest rooted-store-watch-test
  (run-check "rooted-store-watch-test"))

(deftest rooted-store-validator-test
  (run-check "rooted-store-validator-test"))

(deftest rooted-store-durability-test
  (run-check "rooted-store-durability-test"))

(deftest file-root-cell-durability-test
  (run-check "file-root-cell-durability-test"))

(deftest portable-root-api-test
  (run-check "portable-root-api-test"))

(deftest rooted-store-delegates-content-test
  (run-check "rooted-store-delegates-content-test"))

(deftest push-ref-success-test
  (run-check "push-ref-success-test"))

(deftest push-ref-cas-conflict-test
  (run-check "push-ref-cas-conflict-test"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.rooted-test)))))
               "every-check-has-a-deftest"))
      "rooted_checks.cljc and this file list the same tests"))
