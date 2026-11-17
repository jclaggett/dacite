(ns dacite.examples.config-test
  "Config: the same domain against mem and file on the JVM.

   The bodies are `dacite.examples.config-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.config-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest domain-on-mem
  (run-check "domain-on-mem"))

(deftest file-reopen-same-hash
  (run-check "file-reopen-same-hash"))

(deftest parse-path-and-cli-value
  (run-check "parse-path-and-cli-value"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.config-test)))))
               "every-check-has-a-deftest"))
      "examples/config_checks.cljc and this file list the same tests"))
