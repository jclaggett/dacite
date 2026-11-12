(ns dacite.examples.examples-cljs-test
  "nbb side of the example tests. Run from the repo root:

     npm run examples:test

   Each entry of `suites` is a shared check namespace; the JVM runs the same
   checks from `dacite.examples.<label>-test`."
  (:require [dacite.examples.browser-uri-checks :as browser-uri-checks]
            [dacite.examples.config-checks :as config-checks]
            [dacite.examples.hello-checks :as hello-checks]
            [dacite.examples.library-checks :as library-checks]
            [dacite.examples.notes-checks :as notes-checks]
            [dacite.examples.sync-checks :as sync-checks]
            [dacite.examples.todo-checks :as todo-checks]
            [dacite.store.check :as check]))

(def suites
  "[label checks]: the JVM runs these from dacite.examples.<label>-test."
  [["browser-uri" browser-uri-checks/checks]
   ["hello" hello-checks/checks]
   ["todo" todo-checks/checks]
   ["config" config-checks/checks]
   ["notes" notes-checks/checks]
   ["library" library-checks/checks]
   ["sync" sync-checks/checks]])

(def ^:private fs (js/require "fs"))

(def env
  "Host file calls (`sync-checks/ingest-export-roundtrip`)."
  {:mkdirs (fn [p] (.mkdirSync fs p #js {:recursive true}))
   :spit (fn [p s] (.writeFileSync fs p s "utf8"))
   :slurp (fn [p] (.readFileSync fs p "utf8"))
   :rm-rf (fn [p] (.rmSync fs p #js {:recursive true :force true}))})

(defn -main
  [& _]
  (let [rows (vec (mapcat (fn [[label checks]]
                            (map #(update (check/run-one checks env %) :name
                                          (fn [n] (str label "/" n)))
                                 (sort (keys checks))))
                          suites))
        bad (mapcat (fn [{:keys [name problems]}]
                      (map #(str name ": " %) problems))
                    rows)]
    (println (str "example checks: " (count rows) " tests, " (count bad)
                  " problems, slowest " (check/slowest rows)))
    (doseq [p bad] (println "  FAIL" p))
    (if (seq bad)
      (js/process.exit 1)
      (println "example tests passed"))))
