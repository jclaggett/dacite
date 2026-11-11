(ns dacite.value.root-ref-test
  "Root ref tests on the JVM.

   The bodies are `dacite.value.root-ref-checks`. On the JVM they include
   the atom ops on a root ref (`@r`, `swap!`, `reset!`). nbb runs the same
   checks with the function API (`npm run rooted:test`). One deftest each,
   so the JVM suite still names the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.value.root-ref-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest via-uses-peer-store
  (run-check "via-uses-peer-store"))

(deftest via-accepts-istore
  (run-check "via-accepts-istore"))

(deftest root-ref-reset-swap-deref
  (run-check "root-ref-reset-swap-deref"))

(deftest root-ref-cas-and-watch
  (run-check "root-ref-cas-and-watch"))

(deftest root-ref-is-store-carrier
  (run-check "root-ref-is-store-carrier"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.value.root-ref-test)))))
               "every-check-has-a-deftest"))
      "value/root_ref_checks.cljc and this file list the same tests"))
