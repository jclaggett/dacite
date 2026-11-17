(ns dacite.rooted-cljs-test
  "nbb side of the rooted store, GC, and root ref tests. Run from the repo root:

     npm run rooted:test

   The checks of `dacite.rooted-checks`, `dacite.rooted.gc-checks`, and
   `dacite.value.root-ref-checks` with the nbb file and LMDB stores. The JVM
   runs the same checks from `dacite.rooted-test`, `dacite.rooted.gc-test`,
   and `dacite.value.root-ref-test`. The LMDB checks need
   the `lmdb` addon. When it does not load, they are skipped and this exits
   2, as `store:test` does."
  (:require [dacite.rooted-checks :as rooted-checks]
            [dacite.rooted.gc-checks :as gc-checks]
            [dacite.store.check :as check]
            [dacite.store.nbb.lmdb :as lmdb]
            [dacite.store.storage-cljs-test :as storage]
            [dacite.value.root-ref-checks :as root-ref-checks]))

(def env storage/env)

(defn- lmdb-gap
  "nil when an LMDB environment opens, else the error that kept it shut."
  []
  (let [dir ((:tmp-dir env) "dacite-lmdb-probe-")]
    (try (lmdb/lmdb-close (lmdb/lmdb-store dir)) nil
         (catch :default e
           (when (re-find #"Cannot find module|ERR_DLOPEN|\.node|No native build"
                          (str (ex-message e) e))
             e))
         (finally ((:rm-rf env) dir)))))

(defn- run [label checks skip-lmdb?]
  (let [names (cond->> (sort (keys checks))
                skip-lmdb? (remove #(re-find #"lmdb" %)))
        rows (mapv #(check/run-one checks env %) names)
        bad (mapcat (fn [{:keys [name problems]}]
                      (map #(str name ": " %) problems))
                    rows)
        slowest (check/slowest rows)]
    (println (str label ": " (count rows) " tests, " (count bad)
                  " problems, slowest " slowest))
    (doseq [p bad] (println "  FAIL" p))
    (count bad)))

(defn -main
  [& _]
  (let [gap (lmdb-gap)
        bad (+ (run "rooted checks" rooted-checks/checks gap)
               (run "gc checks" gc-checks/checks gap)
               (run "root ref checks" root-ref-checks/checks gap))]
    (when gap
      (println "rooted lmdb SKIP: the native addon did not load.")
      (println (str "  " (ex-message gap))))
    (cond
      (pos? bad) (js/process.exit 1)
      gap (js/process.exit 2)
      :else (println "rooted tests passed"))))
