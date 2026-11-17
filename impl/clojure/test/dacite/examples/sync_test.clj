(ns dacite.examples.sync-test
  "Directory sync on the JVM.

   The bodies are `dacite.examples.sync-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.sync-checks :as checks]
            [clojure.java.io :as io]))

(def ^:private env
  "Host file calls for `ingest-export-roundtrip`."
  {:mkdirs (fn [p] (.mkdirs (io/file p)))
   :spit (fn [p s] (spit (io/file p) s))
   :slurp (fn [p] (slurp (io/file p)))
   :rm-rf (fn [p] (let [d (io/file p)]
                    (when (.exists d)
                      (doseq [f (reverse (file-seq d))]
                        (.delete ^java.io.File f)))))})

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest sample-tree-shares-identical-blobs
  (run-check "sample-tree-shares-identical-blobs"))

(deftest ls-does-not-need-as-bytes
  (run-check "ls-does-not-need-as-bytes"))

(deftest cat-round-trip
  (run-check "cat-round-trip"))

(deftest second-local-sync-copies-nothing
  (run-check "second-local-sync-copies-nothing"))

(deftest missing-blob-is-catchable
  (run-check "missing-blob-is-catchable"))

(deftest file-reopen-keeps-tree
  (run-check "file-reopen-keeps-tree"))

(deftest ingest-export-roundtrip
  (run-check "ingest-export-roundtrip"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.sync-test)))))
               "every-check-has-a-deftest"))
      "examples/sync_checks.cljc and this file list the same tests"))
