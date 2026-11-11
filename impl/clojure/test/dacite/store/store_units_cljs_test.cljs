(ns dacite.store.store-units-cljs-test
  "nbb side of the pack, chunk, lens, and rate-limit tests. Run from the
   repo root:

     npm run store-units:test

   The checks of `dacite.store.pack-checks`, `dacite.store.chunk-checks`,
   `dacite.store.lens-checks`, and `dacite.store.rate-limit-checks`. The JVM
   runs the same checks from `dacite.store.pack-test`, `dacite.store.chunk-test`,
   `dacite.store.lens-test`, and `dacite.store.rate-limit-test`.

   The host `env` is the one of `npm run storage:test` plus the two calls the
   chunk checks use. The LMDB check needs the `lmdb` addon. When it does not
   load, it is skipped and this exits 2, as `store:test` does."
  (:require [dacite.store :as store]
            [dacite.store.check :as check]
            [dacite.store.chunk-checks :as chunk-checks]
            [dacite.store.lens-checks :as lens-checks]
            [dacite.store.nbb.lmdb :as lmdb]
            [dacite.store.pack-checks :as pack-checks]
            [dacite.store.rate-limit-checks :as rate-limit-checks]
            [dacite.store.storage-cljs-test :as storage]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- edn-file-sizes
  "The byte size of each .edn file under `dir`."
  [dir]
  (letfn [(walk [d]
            (mapcat (fn [name]
                      (let [p (.join path d name)
                            st (.statSync fs p)]
                        (cond
                          (.isDirectory st) (walk p)
                          (.endsWith name ".edn") [(.-size st)]
                          :else [])))
                    (vec (.readdirSync fs d))))]
    (vec (walk dir))))

(def env
  "The nbb stores and file calls the checks use."
  (assoc storage/env
         :edn-file-sizes edn-file-sizes
         :lmdb-entries (fn [st] (:entries (store/await (store/local-stats st))))))

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
        slowest (apply max-key :ms {:ms 0 :name "-"} rows)]
    (println (str label ": " (count rows) " tests, " (count bad)
                  " problems, slowest " (:ms slowest) " ms (" (:name slowest) ")"))
    (doseq [p bad] (println "  FAIL" p))
    (count bad)))

(defn -main
  [& _]
  (let [gap (lmdb-gap)
        bad (+ (run "pack checks" pack-checks/checks gap)
               (run "chunk checks" chunk-checks/checks gap)
               (run "lens checks" lens-checks/checks gap)
               (run "rate-limit checks" rate-limit-checks/checks gap))]
    (when gap
      (println "store-units lmdb SKIP: the native addon did not load.")
      (println (str "  " (ex-message gap))))
    (cond
      (pos? bad) (js/process.exit 1)
      gap (js/process.exit 2)
      :else (println "store unit tests passed"))))
