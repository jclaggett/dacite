(ns dacite.value-test
  "Scalars, accessors, and store-awareness in the value layer, on the JVM.

   The bodies are `dacite.value-checks`. nbb runs the same checks
   (`npm run value:test`). One deftest each, so the JVM suite still names
   the test it ran before. `scalar-validation` keeps one assertion:
   `v2/char` of a one-letter String fails, since a BMP character is a char.
   A JS host has no separate char type."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store :as store]
            [dacite.store.check :as check]
            [dacite.value :as v2]
            [dacite.value-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest scalar-types-and-realize
  (run-check "scalar-types-and-realize"))

(deftest values-are-not-derefable
  (run-check "values-are-not-derefable"))

(deftest scalar-validation
  (run-check "scalar-validation")
  ;; JVM only: a BMP character is a char, not a String.
  (is (thrown? AssertionError (v2/char (store/mem-store) "a"))))

(deftest content-addressing
  (run-check "content-addressing"))

(deftest values-know-their-store
  (run-check "values-know-their-store"))

(deftest get-value-round-trips
  (run-check "get-value-round-trips"))

(deftest cross-type-content-hash
  (run-check "cross-type-content-hash"))

(deftest content-hash-strips-type
  (run-check "content-hash-strips-type"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.value-test)))))
               "every-check-has-a-deftest"))
      "value_checks.cljc and this file list the same tests"))
