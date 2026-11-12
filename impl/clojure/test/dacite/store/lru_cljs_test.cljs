(ns dacite.store.lru-cljs-test
  "nbb side of the LRU store tests. Run from the repo root:

     npm run lru:test

   The same checks as `dacite.store.lru-test` on the JVM."
  (:require [dacite.store.lru-checks :as checks]
            [dacite.store.check :as check]))

(defn -main
  [& _]
  (let [rows (checks/run-all)
        bad (mapcat (fn [{:keys [name problems]}]
                      (map #(str name ": " %) problems))
                    rows)
        slowest (check/slowest rows)]
    (println "lru store checks:" (count rows) "tests,"
             (count bad) "problems, slowest" slowest)
    (doseq [p bad] (println "  FAIL" p))
    (when (seq bad)
      (js/process.exit 1))
    (println "lru store tests passed")))
