(ns dacite.peer.items-cljs-test
  "nbb side of the peer item checks. Run from the repo root:

     npm run items:test

   The same checks as `dacite.peer.peer-items-test` on the JVM. The reveal
   service half of `the-reveal-service-and-the-peer-layer-accept-the-same-bytes`
   stays on the JVM: `dacite.service.reveal` is the HTTP service.

   It also runs the shared checks in `dacite.peer.literal-limits-checks`
   (`dacite.peer.literal-limits-test` on the JVM)."
  (:require [dacite.peer.items-checks :as checks]
            [dacite.peer.literal-limits-checks :as limits]
            [dacite.store.check :as check]))

(defn -main
  [& _]
  (let [rows (concat (checks/run-all)
                     (map #(check/run-one limits/checks {} %) (keys limits/checks)))
        bad (mapcat (fn [{:keys [name problems]}]
                      (map #(str name ": " %) problems))
                    rows)
        slowest (apply max 0 (map :ms rows))]
    (println "peer item checks:" (count rows) "tests,"
             (count bad) "problems, slowest" slowest "ms")
    (doseq [p bad] (println "  FAIL" p))
    (when (seq bad)
      (js/process.exit 1))
    (println "peer item tests passed")))
