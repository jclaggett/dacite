(ns dacite.examples.examples-cljs-test
  "nbb side of the example tests. Run from the repo root:

     npm run examples:test

   Each entry of `suites` is a shared check namespace; the JVM runs the same
   checks from `dacite.examples.<label>-test`."
  (:require [dacite.examples.browser-uri-checks :as browser-uri-checks]
            [dacite.examples.config-checks :as config-checks]
            [dacite.examples.event-log-checks :as event-log-checks]
            [dacite.examples.explorer-checks :as explorer-checks]
            [dacite.examples.hello-checks :as hello-checks]
            [dacite.examples.library-checks :as library-checks]
            [dacite.examples.notes-checks :as notes-checks]
            [dacite.examples.sync-checks :as sync-checks]
            [dacite.examples.todo-checks :as todo-checks]
            [dacite.store.check :as check]))

(def suites
  "[label checks slow-ms?]: the JVM runs these from
   dacite.examples.<label>-test. `slow-ms` holds nbb ceilings above
   `check/max-ms`, each with a JUSTIFICATION on its check."
  [["browser-uri" browser-uri-checks/checks]
   ["hello" hello-checks/checks]
   ["todo" todo-checks/checks]
   ["config" config-checks/checks]
   ["notes" notes-checks/checks]
   ["library" library-checks/checks]
   ["sync" sync-checks/checks]
   ["event-log" event-log-checks/checks event-log-checks/slow-ms]
   ["explorer" explorer-checks/checks explorer-checks/slow-ms]])

(def ^:private gallery-seed-ms
  "nbb ceiling for building the shared explorer gallery (about 1 s)."
  check/max-ms)

(defn- gallery-seed-row
  "Build the shared explorer gallery before its checks, timed as its own
   row so no one check carries it: a row {:name :ms :problems}."
  []
  (let [t0 (.now js/Date)
        problems (try (explorer-checks/gallery) []
                      (catch :default e [(str "threw " (ex-message e))]))
        ms (- (.now js/Date) t0)]
    {:name "explorer/(seed the shared gallery)"
     :ms ms
     :problems (cond-> problems
                 (> ms gallery-seed-ms)
                 (conj (str "took " ms " ms, over " gallery-seed-ms)))}))

(def ^:private fs (js/require "fs"))

(def env
  "Host file calls (`sync-checks/ingest-export-roundtrip`)."
  {:mkdirs (fn [p] (.mkdirSync fs p #js {:recursive true}))
   :spit (fn [p s] (.writeFileSync fs p s "utf8"))
   :slurp (fn [p] (.readFileSync fs p "utf8"))
   :rm-rf (fn [p] (.rmSync fs p #js {:recursive true :force true}))})

(defn -main
  [& _]
  (let [rows (into [(gallery-seed-row)]
                   (mapcat (fn [[label checks slow-ms]]
                             (map #(update (check/run-one checks env % (get slow-ms % check/max-ms))
                                           :name (fn [n] (str label "/" n)))
                                  (sort (keys checks)))))
                   suites)
        bad (mapcat (fn [{:keys [name problems]}]
                      (map #(str name ": " %) problems))
                    rows)]
    (println (str "example checks: " (count rows) " tests, " (count bad)
                  " problems, slowest " (check/slowest rows)))
    (doseq [p bad] (println "  FAIL" p))
    (if (seq bad)
      (js/process.exit 1)
      (println "example tests passed"))))
