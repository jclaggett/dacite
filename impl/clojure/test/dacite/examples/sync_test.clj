(ns dacite.examples.sync-test
  "Directory sync on the JVM.

   The bodies are `dacite.examples.sync-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before.

   `remote-one-blob-cheaper-than-all-blobs` stays JVM only: it starts the
   HTTP service and reads through `store/remote` (legacy HTTP)."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.sync-checks :as checks]
            [clojure.java.io :as io]
            [dacite.examples.sync :as fs]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.remote :as remote]
            [dacite.store.stats :as stats]
            [dacite.value :as v]))

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

(deftest remote-one-blob-cheaper-than-all-blobs
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [writer (v/root (store/remote-rooted-store base-url))]
        (fs/load-or-seed! writer)
        (let [tree (v/deref writer)
              h-readme (v/hash (fs/entry-blob (fs/lookup tree ["readme.txt"])))
              h-note (v/hash (fs/entry-blob (fs/lookup tree ["sub" "note.txt"])))
              h-data (v/hash (fs/entry-blob (fs/lookup tree ["data.bin"])))]
          (stats/reset-stats!)
          (let [one (:delta
                     (stats/measure
                      #(store/await (store/s-get (remote/remote-store base-url) h-readme))))
                _ (stats/reset-stats!)
                all (:delta
                     (stats/measure
                      (fn []
                        (let [r (remote/remote-store base-url)]
                          (store/await (store/s-get r h-readme))
                          (store/await (store/s-get r h-note))
                          (store/await (store/s-get r h-data))))))]
            (is (pos? (:bytes-recv one)))
            (is (< (:bytes-recv one) (:bytes-recv all))
                "fetching one file must not pull sibling blobs"))))
      (finally
        (stop!)))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.sync-test)))))
               "every-check-has-a-deftest"
               "remote-one-blob-cheaper-than-all-blobs"))
      "examples/sync_checks.cljc and this file list the same tests"))
