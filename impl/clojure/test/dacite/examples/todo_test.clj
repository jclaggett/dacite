(ns dacite.examples.todo-test
  "The todo domain on the JVM.

   The bodies are `dacite.examples.todo-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.todo-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest seed-and-toggle
  (run-check "seed-and-toggle"))

(deftest file-reopen-same-hash
  (run-check "file-reopen-same-hash"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.todo-test)))))
               "every-check-has-a-deftest"))
      "examples/todo_checks.cljc and this file list the same tests"))
