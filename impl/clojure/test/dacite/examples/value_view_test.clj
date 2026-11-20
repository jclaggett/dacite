(ns dacite.examples.value-view-test
  "M6.5 Value Browser rendering on the JVM.

   The bodies are `dacite.examples.value-view-checks`; nbb runs the same
   checks (`npm run examples:test`). One deftest each."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.value-view-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest scalars-render-as-themselves
  (run-check "scalars-render-as-themselves"))

(deftest strings-render-as-text
  (run-check "strings-render-as-text"))

(deftest map-and-vector-step-one-hash-at-a-time
  (run-check "map-and-vector-step-one-hash-at-a-time"))

(deftest tree-reaches-every-entry
  (run-check "tree-reaches-every-entry"))

(deftest raw-pane-shows-the-entry
  (run-check "raw-pane-shows-the-entry"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.value-view-test)))))
               "every-check-has-a-deftest"))
      "examples/value_view_checks.cljc and this file list the same tests"))
