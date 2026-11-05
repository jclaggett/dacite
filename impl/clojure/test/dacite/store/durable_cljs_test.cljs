(ns dacite.store.durable-cljs-test
  "M3.3 runner. From the repo root:

     npm run lmdb:v1     ; format v1, once per machine (see dacite.store.nbb.lmdb)
     npm run store:test

   File, LMDB and IndexedDB (fake-indexeddb) each run the shared store checks
   and a peer that serves the library fixture. LMDB is skipped only when the
   native addon cannot be loaded; the script still runs file and IndexedDB and
   says why LMDB was skipped."
  (:require [dacite.store.durable-checks :as checks]
            [dacite.store.idb :as idb]
            [dacite.store.nbb :as nbb]
            [dacite.store.nbb.lmdb :as lmdb]))

(js/require "fake-indexeddb/auto")

(defn- now [] (.now js/Date))

(defn- file-backend []
  (let [fs (js/require "fs")
        os (js/require "os")
        path (js/require "path")
        dirs (atom [])]
    {:fresh (fn []
              (let [d (.mkdtempSync fs (.join path (.tmpdir os) "dacite-m33-file-"))]
                (swap! dirs conj d)
                d))
     :open (fn [place _opts] (nbb/file-store place))
     :close (fn [_st] nil)
     :cleanup (fn []
                (doseq [d @dirs]
                  (.rmSync fs d #js {:recursive true :force true})))}))

(defn- lmdb-backend []
  (let [fs (js/require "fs")
        os (js/require "os")
        path (js/require "path")
        dirs (atom [])]
    {:fresh (fn []
              (let [d (.mkdtempSync fs (.join path (.tmpdir os) "dacite-m33-lmdb-"))]
                (swap! dirs conj d)
                d))
     :open (fn [place _opts] (lmdb/lmdb-store place))
     :close (fn [st] (try (lmdb/lmdb-close st) (catch :default _ nil)))
     :cleanup (fn []
                (doseq [d @dirs]
                  (.rmSync fs d #js {:recursive true :force true})))}))

(defn- idb-backend []
  {:fresh (fn [] (str "dacite-m33-" (random-uuid)))
   :open (fn [place opts]
           (if (contains? opts :cache-limit)
             (idb/idb-store place {:cache-limit (:cache-limit opts)})
             (idb/idb-store place)))
   :close idb/close
   :cleanup (fn [] nil)})

(defn- native-gap?
  "True when `e` is a missing or unloadable LMDB addon, not a store failure."
  [e]
  (boolean (re-find #"Cannot find module|ERR_DLOPEN|\\.node|No native build"
                    (str (ex-message e) e))))

(defn- lmdb-probe
  "`:ok`, or `[:skip e]` / `[:fail e]` from opening one environment."
  []
  (let [fs (js/require "fs")
        os (js/require "os")
        path (js/require "path")
        dir (.mkdtempSync fs (.join path (.tmpdir os) "dacite-lmdb-probe-"))]
    (try
      (let [st (lmdb/lmdb-store dir)]
        (lmdb/lmdb-close st)
        :ok)
      (catch :default e
        (if (native-gap? e) [:skip e] [:fail e]))
      (finally
        (try (.rmSync fs dir #js {:recursive true :force true})
             (catch :default _ nil))))))

(defn- report [label kind ms problems nodes]
  (println (str "m3.3 " label " " kind ": " (count problems) " problems, " ms " ms"
                (when nodes (str ", " nodes " library nodes"))))
  (doseq [p problems]
    (println (str "  FAIL " p))))

(defn- run-one [label backend]
  (let [t0 (now)]
    (-> (checks/store-problems backend)
        (.then (fn [{:keys [problems]}]
                 (let [store-ms (- (now) t0)
                       store-problems problems
                       t1 (now)]
                   (-> (checks/peer-problems backend)
                       (.then (fn [{:keys [problems nodes]}]
                                (report label "store" store-ms store-problems nil)
                                (report label "peer" (- (now) t1) problems nodes)
                                {:problems (into (vec store-problems) problems)}))))))
        (.catch (fn [e]
                  (report label "threw" (- (now) t0) [(str e)] nil)
                  {:problems [(str label " " e)]}))
        (.finally (fn []
                    (try ((:cleanup backend))
                         (catch :default e
                           (println (str "m3.3 " label " cleanup: " e)))))))))

(defn -main
  [& _]
  (let [lmdb (lmdb-probe)
        skip (when (and (vector? lmdb) (= :skip (first lmdb))) (second lmdb))
        fail (when (and (vector? lmdb) (= :fail (first lmdb))) (second lmdb))]
    (when skip
      (println "m3.3 lmdb SKIP: the native addon did not load.")
      (println (str "  " (ex-message skip)))
      (println "  Compile format v1 with: npm run lmdb:v1")
      (println "  File and IndexedDB still run."))
    (-> (run-one "file" (file-backend))
        (.then (fn [file]
                 (if fail
                   (do (println (str "m3.3 lmdb FAIL: " (ex-message fail)))
                       {:problems (conj (:problems file) (str "lmdb " (ex-message fail)))})
                   (if skip
                     file
                     (-> (run-one "lmdb" (lmdb-backend))
                         (.then (fn [lmdb-result]
                                  {:problems (into (:problems file) (:problems lmdb-result))})))))))
        (.then (fn [so-far]
                 (-> (run-one "idb" (idb-backend))
                     (.then (fn [idb]
                              (let [problems (into (:problems so-far) (:problems idb))]
                                (println (str "store tests: " (count problems) " problems"
                                              (when skip " (lmdb skipped)")))
                                (when (or (seq problems) skip)
                                  (when (seq problems) (js/process.exit 1))
                                  (when skip (js/process.exit 2)))))))))
        (.catch (fn [e]
                  (js/console.error e)
                  (js/process.exit 1))))))
