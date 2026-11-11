(ns dacite.value-cljs-test
  "nbb side of the value-layer tests. Run from the repo root:

     npm run value:test

   Each entry of `suites` is a shared check namespace and the JVM test
   namespace that runs the same checks."
  (:require [cljs.reader :as reader]
            [dacite.core-checks :as core-checks]
            [dacite.store.check :as check]
            [dacite.value.api-checks :as api-checks]
            [dacite.value.collections-checks :as collections-checks]
            [dacite.value.finger-tree-checks :as finger-tree-checks]
            [dacite.value.hamt-collision-checks :as hamt-collision-checks]
            [dacite.value.path-checks :as path-checks]
            [dacite.value.unicode-checks :as unicode-checks]
            [dacite.value-checks :as value-checks]))

(def env
  "Host pieces the checks take. Paths are from the repo root."
  {:read-vectors (fn []
                   (reader/read-string
                    (.readFileSync (js/require "fs") "fixtures/fuse-sl2/vectors.edn" "utf8")))})

(def suites
  "[label checks slow-ms?]: the JVM runs these from dacite.value.<label>-test
   (\"value\" from dacite.value-test, \"core\" from dacite.core-test).
   `slow-ms` holds nbb ceilings above `check/max-ms`, each with a
   JUSTIFICATION on its check."
  [["value" value-checks/checks]
   ["path" path-checks/checks]
   ["api" api-checks/checks]
   ["finger-tree" finger-tree-checks/checks]
   ["collections" collections-checks/checks]
   ["unicode" unicode-checks/checks unicode-checks/slow-ms]
   ["hamt-collision" hamt-collision-checks/checks hamt-collision-checks/slow-ms]
   ["core" core-checks/checks]])

(defn- run [label checks slow-ms]
  (let [rows (mapv #(check/run-one checks env % (get slow-ms % check/max-ms))
                   (sort (keys checks)))
        bad (mapcat (fn [{:keys [name problems]}]
                      (map #(str name ": " %) problems))
                    rows)
        slowest (apply max-key :ms {:ms 0 :name "-"} rows)]
    (println (str label " checks: " (count rows) " tests, " (count bad)
                  " problems, slowest " (:ms slowest) " ms (" (:name slowest) ")"))
    (doseq [p bad] (println "  FAIL" p))
    (count bad)))

(defn -main
  [& _]
  (let [bad (reduce + (map (fn [[label checks slow-ms]] (run label checks slow-ms)) suites))]
    (if (pos? bad)
      (js/process.exit 1)
      (println "value tests passed"))))
