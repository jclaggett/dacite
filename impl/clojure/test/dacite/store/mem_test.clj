(ns dacite.store.mem-test
  "In-memory store tests that the nbb peer was failing.

   The bodies are `dacite.store.mem-checks`. nbb runs the same checks
   (`npm run mem:test`). One deftest each, so the JVM suite still names
   the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.mem-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [t0 (System/currentTimeMillis)
        problems (vec ((get checks/checks name)))
        ms (- (System/currentTimeMillis) t0)]
    (is (empty? problems) (pr-str problems))
    (is (< ms checks/max-ms) (str name " took " ms " ms"))))

(deftest mem-store-merge-test
  (run-check "mem-store-merge-test"))

(deftest mem-store-init-test
  (run-check "mem-store-init-test"))

(deftest layered-store-read-through-test
  (run-check "layered-store-read-through-test"))

(deftest layered-store-read-through-backfill-test
  (run-check "layered-store-read-through-backfill-test"))

(deftest layered-store-fast-wins-test
  (run-check "layered-store-fast-wins-test"))

(deftest layered-store-has?-test
  (run-check "layered-store-has?-test"))

(deftest layered-store-reads-test
  (run-check "layered-store-reads-test"))

(deftest layered-store-merge-test
  (run-check "layered-store-merge-test"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.store.mem-test)))))
               "every-check-has-a-deftest"))
      "mem_checks.cljc and this file list the same tests"))
