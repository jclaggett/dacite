(ns dacite.peer.webrtc-cljs-test
  "M3.2 WebRTC checks. Run: npm run webrtc:test"
  (:require [dacite.peer.transport-webrtc-checks :as checks]
            [dacite.store.check :as check]))

(defn -main [& _]
  (-> (checks/run-all)
      (.then
       (fn [results]
         (let [problems (mapcat (fn [{:keys [name problems]}]
                                  (map #(str name ": " %) problems))
                                results)
               slowest (check/slowest results)]
           (println "webrtc checks:" (count results) "tests,"
                    (count problems) "problems, slowest" slowest)
           (doseq [p problems] (println "  FAIL" p))
           (doseq [{:keys [name ms problems]} results]
             (when (seq problems) nil)
             (println " " ms "ms" name))
           (js/process.exit (if (seq problems) 1 0)))))
      (.catch
       (fn [e]
         (println "webrtc checks threw:" (ex-message e) (pr-str (ex-data e)))
         (js/console.error e)
         (js/process.exit 1)))))
