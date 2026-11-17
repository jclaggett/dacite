(ns dacite.examples.notes-test
  "Versioned notes on the JVM.

   The bodies are `dacite.examples.notes-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.notes-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest seed-and-edit
  (run-check "seed-and-edit"))

(deftest restore-reuses-snapshot-hash
  (run-check "restore-reuses-snapshot-hash"))

(deftest title-only-shares-body
  (run-check "title-only-shares-body"))

(deftest diff-sees-title-not-body
  (run-check "diff-sees-title-not-body"))

(deftest file-reopen-keeps-history
  (run-check "file-reopen-keeps-history"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.notes-test)))))
               "every-check-has-a-deftest"))
      "examples/notes_checks.cljc and this file list the same tests"))
