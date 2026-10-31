(ns dacite.peer.peer-cljs-test
  "peer-v1 fixtures and framing on nbb. Run from the repo root:

     npm run peer:test

   The same checks as peer_v1_test.clj, minus hash verification of the
   entries (that lives in the JVM-only service namespace until M2)."
  (:require [dacite.peer.conformance :as conf]
            [dacite.peer.examples :as ex]
            [dacite.peer.msg :as msg]))

(defn -main
  [& _]
  (let [{:keys [cases problems]} (conf/run-all)
        framing (conf/run-framing)
        encoded (count (for [{:keys [type stream value]} ex/examples
                             :let [bs (msg/encode-message {:type type :stream stream :value value})]]
                         bs))
        all (into (vec problems) framing)]
    (println "peer-v1 fixtures:" cases "cases," encoded "examples encoded,"
             (count all) "problems")
    (doseq [p all] (println "  FAIL" p))
    (when (or (seq all) (< cases 53))
      (js/process.exit 1))))

