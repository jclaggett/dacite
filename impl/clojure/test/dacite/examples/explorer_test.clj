(ns dacite.examples.explorer-test
  "Value explorer on the JVM.

   The bodies are `dacite.examples.explorer-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.explorer-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest gallery-covers-every-public-type
  (run-check "gallery-covers-every-public-type"))

(deftest load-or-seed-does-not-overwrite
  (run-check "load-or-seed-does-not-overwrite"))

(deftest row-summary-does-not-seq-children
  (run-check "row-summary-does-not-seq-children"))

(deftest child-page-bounds-vector
  (run-check "child-page-bounds-vector"))

(deftest child-page-map-keys-are-values
  (run-check "child-page-map-keys-are-values"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.explorer-test)))))
               "every-check-has-a-deftest"))
      "examples/explorer_checks.cljc and this file list the same tests"))
