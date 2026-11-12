(ns dacite.examples.browser-uri-test
  "M6.4 URI rules on the JVM.

   The bodies are `dacite.examples.browser-uri-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.browser-uri-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest pasteable-form-is-the-fragment
  (run-check "pasteable-form-is-the-fragment"))

(deftest host-may-be-a-dotted-name
  (run-check "host-may-be-a-dotted-name"))

(deftest field-accepts-the-peer-together-with-a-urn
  (run-check "field-accepts-the-peer-together-with-a-urn"))

(deftest rejects-an-algorithm-token-and-a-path
  (run-check "rejects-an-algorithm-token-and-a-path"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.browser-uri-test)))))
               "every-check-has-a-deftest"))
      "examples/browser_uri_checks.cljc and this file list the same tests"))
