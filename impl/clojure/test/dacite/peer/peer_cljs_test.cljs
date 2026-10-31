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
        reason #(try (%) :accepted (catch :default e (:reason (ex-data e))))
        host (concat
              (when-not (= 256 (count (msg/cut-text (apply str (repeat 300 "x")))))
                ["cut-text does not cut to 256 bytes"])
              (when-not (= :profile (reason #(msg/encode-value {"a" (inc msg/max-int)})))
                ["an integer over 2^53 - 1 is not refused"])
              (when-not (= 2 (get-in (msg/negotiate {"versions" #{1 2} "caps" #{} "limits" {"max-frame" 1 "max-item" 1 "max-streams" 1}}
                                                    {"versions" #{2} "caps" #{} "limits" {"max-frame" 1 "max-item" 1 "max-streams" 1}})
                                     [:ok :version]))
                ["negotiate does not pick the highest common version"]))
        all (into (into (vec problems) framing) host)]
    (println "peer-v1 fixtures:" cases "cases," encoded "examples encoded,"
             (count all) "problems")
    (doseq [p all] (println "  FAIL" p))
    (when (or (seq all) (< cases 50))
      (js/process.exit 1))))

