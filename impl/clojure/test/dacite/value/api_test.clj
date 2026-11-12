(ns dacite.value.api-test
  "Portable collection API on `dacite.value`, on the JVM.

   The bodies are `dacite.value.api-checks`. nbb runs the same checks
   (`npm run value:test`). One deftest each, so the JVM suite still names
   the test it ran before. `parity-with-native-jvm-test` stays JVM only: it
   checks the API against the JVM's native collection interfaces."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store :as store]
            [dacite.store.check :as check]
            [dacite.value :as v]
            [dacite.value.api-checks :as checks]
            [dacite.value.types :as types]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest vector-api-test
  (run-check "vector-api-test"))

(deftest map-api-test
  (run-check "map-api-test"))

(deftest set-api-test
  (run-check "set-api-test"))

(deftest get-value-test
  (run-check "get-value-test"))

;; JVM only. A SCI type has no native collection interfaces to compare.
(deftest parity-with-native-jvm-test
  (let [st (store/mem)
        vec (v/vector st 5 6 7)
        m (v/map st "x" 1)]
    (is (= (count vec) (v/count vec)))
    (is (= (types/dacite-hash (nth vec 1)) (types/dacite-hash (v/nth vec 1))))
    (is (= (types/dacite-hash (get m "x")) (types/dacite-hash (v/get m "x"))))
    (is (= (types/dacite-hash (conj vec 8)) (types/dacite-hash (v/conj vec 8))))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.value.api-test)))))
               "every-check-has-a-deftest"
               "parity-with-native-jvm-test"))
      "value/api_checks.cljc and this file list the same tests"))
