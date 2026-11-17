(ns dacite.wire.binary-test
  "Drive the shipped wire-v1 codec against goldens and live public values.

   The checks are `dacite.wire.binary-checks`, shared with nbb
   (`npm run wire:test`); each runs here as a deftest under its old name."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [dacite.store.check :as check]
            [dacite.wire.binary :as bin]
            [dacite.wire.binary-checks :as checks]))

(def ^:private env
  "Host pieces the checks take (see dacite.wire.binary-checks)."
  {:fixture-ids bin/list-fixture-ids
   :load-fixture bin/load-fixture
   :manifest #(json/parse-string
               (slurp (io/file (bin/fixture-root) "manifest.json"))
               true)})

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest run-repeat-have-wire-ids
  (run-check "run-repeat-have-wire-ids"))

(deftest u8-run-lit-keeps-raw-bytes
  (run-check "u8-run-lit-keeps-raw-bytes"))

(deftest run-repeat-lit-bytes-round-trip
  (run-check "run-repeat-lit-bytes-round-trip"))

(deftest public-types-have-wire-ids
  (run-check "public-types-have-wire-ids"))

(deftest all-fixtures-decode-and-reencode
  (run-check "all-fixtures-decode-and-reencode"))

(deftest fixture-manifest-covers-public-types
  (run-check "fixture-manifest-covers-public-types"))

(deftest live-public-scalars-node-and-literal
  (run-check "live-public-scalars-node-and-literal"))

(deftest live-public-collections-node-and-literal
  (run-check "live-public-collections-node-and-literal"))

(deftest live-ft-hamt-nodes-and-literals
  (run-check "live-ft-hamt-nodes-and-literals"))

(deftest fixture-literal-i64-42-matches-and-round-trips
  (run-check "fixture-literal-i64-42-matches-and-round-trips"))

(deftest reject-bad-magic-and-version
  (run-check "reject-bad-magic-and-version"))

(deftest pack-edn-bridge-round-trip
  (run-check "pack-edn-bridge-round-trip"))

(deftest spot-check-new-scalar-and-collection
  (run-check "spot-check-new-scalar-and-collection"))

(deftest a-measure-state-outside-sl2-is-bad-state
  (run-check "a-measure-state-outside-sl2-is-bad-state"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.wire.binary-test)))))
               "every-check-has-a-deftest"))
      "binary_checks.cljc and this file list the same tests"))
