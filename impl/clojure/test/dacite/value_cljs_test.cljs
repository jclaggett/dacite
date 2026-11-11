(ns dacite.value-cljs-test
  "nbb side of the value-layer tests. Run from the repo root:

     npm run value:test

   Each entry of `suites` is a shared check namespace and the JVM test
   namespace that runs the same checks."
  (:require [dacite.store.check :as check]
            [dacite.value.api-checks :as api-checks]
            [dacite.value.finger-tree-checks :as finger-tree-checks]
            [dacite.value.path-checks :as path-checks]))

(def suites
  "[label checks]: the JVM runs these from dacite.value.<label>-test."
  [["path" path-checks/checks]
   ["api" api-checks/checks]
   ["finger-tree" finger-tree-checks/checks]])

(defn- run [label checks]
  (let [rows (mapv #(check/run-one checks {} %) (sort (keys checks)))
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
  (let [bad (reduce + (map (fn [[label checks]] (run label checks)) suites))]
    (if (pos? bad)
      (js/process.exit 1)
      (println "value tests passed"))))
