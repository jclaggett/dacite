(ns dacite.rooted.gc-test
  "Root-reachability GC tests on the JVM.

   The bodies are `dacite.rooted.gc-checks`. nbb runs the same checks
   (`npm run rooted:test`). One deftest each, so the JVM suite still names
   the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.rooted.gc-checks :as checks]
            [dacite.store.check :as check]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest mark-reachable-scalar-test
  (run-check "mark-reachable-scalar-test"))

(deftest collect-garbage-scalar-test
  (run-check "collect-garbage-scalar-test"))

(deftest collect-garbage-collection-test
  (run-check "collect-garbage-collection-test"))

(deftest collect-garbage-rooted-store-test
  (run-check "collect-garbage-rooted-store-test"))

(deftest collect-garbage-nil-root-test
  (run-check "collect-garbage-nil-root-test"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.rooted.gc-test)))))
               "every-check-has-a-deftest"))
      "rooted/gc_checks.cljc and this file list the same tests"))
