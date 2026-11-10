(ns dacite.store.mem-cljs-test
  "nbb side of the in-memory store tests. Run from the repo root:

     npm run mem:test

   The same checks as `dacite.store.mem-test` on the JVM."
  (:require [dacite.store.mem-checks :as checks]))

(defn -main
  [& _]
  (let [rows (checks/run-all)
        bad (mapcat (fn [{:keys [name problems]}]
                      (map #(str name ": " %) problems))
                    rows)
        slowest (apply max 0 (map :ms rows))]
    (println "mem store checks:" (count rows) "tests,"
             (count bad) "problems, slowest" slowest "ms")
    (doseq [p bad] (println "  FAIL" p))
    (when (seq bad)
      (js/process.exit 1))
    (println "mem store tests passed")))
