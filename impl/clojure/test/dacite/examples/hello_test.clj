(ns dacite.examples.hello-test
  "The hello example on the JVM.

   The bodies are `dacite.examples.hello-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before.

   `parity-hash-is-stable` stays here: `npm run parity:test` already checks
   `dacite.examples.parity/canonical-hex` on nbb against the golden hash
   (its `canonical` case)."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.hello-checks :as checks]
            [dacite.examples.parity :as parity]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest hello-vector-and-map
  (run-check "hello-vector-and-map"))

(deftest parity-hash-is-stable
  (let [a (parity/canonical-hex)
        b (parity/canonical-hex)]
    (is (= 64 (count a)))
    (is (re-matches #"[0-9a-f]{64}" a))
    (is (= a b))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.hello-test)))))
               "every-check-has-a-deftest"
               "parity-hash-is-stable"))
      "examples/hello_checks.cljc and this file list the same tests"))
