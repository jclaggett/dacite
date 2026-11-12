(ns dacite.store.storage-cljs-test
  "nbb side of the storage tests. Run from the repo root:

     npm run storage:test

   The checks of `dacite.store-checks` and `dacite.store.format-checks`
   with the nbb file store (`dacite.store.nbb`) and the nbb LMDB store
   (`dacite.store.nbb.lmdb`). The JVM runs the same checks from
   `dacite.store-test` and `dacite.store.format-test`.

   The LMDB checks need the `lmdb` addon. When it does not load, this
   reports a skip and exits 2, as `store:test` does."
  (:require [dacite.host :as host]
            [dacite.store-checks :as store-checks]
            [dacite.store.check :as check]
            [dacite.store.format-checks :as format-checks]
            [dacite.store.nbb :as nbb-file]
            [dacite.store.nbb.lmdb :as lmdb]))

(def ^:private fs (js/require "fs"))
(def ^:private os (js/require "os"))
(def ^:private path (js/require "path"))

(defn- str-buf [s] (js/Buffer.from (str s) "utf8"))

(defn- lmdb-raw
  "The bytes under hash `h` in the content DB of LMDB store `st`, or nil."
  [st h]
  (.get (:db st) (js/Buffer.from (clj->js (host/longs->bytes h)))))

(defn- meta-format
  [st]
  (when-let [raw (.get (:meta-db st) (str-buf "format"))]
    (.toString raw "utf8")))

(defn- set-meta-format!
  [st s]
  (if (some? s)
    (.putSync (:meta-db st) (str-buf "format") (str-buf s))
    (.removeSync (:meta-db st) (str-buf "format"))))

(def env
  "The nbb stores and file calls the checks use."
  {:file-store nbb-file/file-store
   :lmdb-store lmdb/lmdb-store
   :lmdb-close lmdb/lmdb-close
   :write-batch lmdb/write-batch
   :lmdb-root-cell lmdb/lmdb-root-cell
   :lmdb-raw lmdb-raw
   :to-ubytes (fn [bs] (vec (js/Array.from bs)))
   :join (fn [& parts] (apply (.-join path) (map str parts)))
   :exists? (fn [p] (.existsSync fs p))
   :dir? (fn [p] (and (.existsSync fs p) (.isDirectory (.statSync fs p))))
   :tmp-dir (fn [prefix] (.mkdtempSync fs (.join path (.tmpdir os) prefix)))
   :rm-rf (fn [p] (.rmSync fs p #js {:recursive true :force true}))
   :slurp (fn [p] (.readFileSync fs p "utf8"))
   :spit (fn [p s] (.writeFileSync fs p s "utf8"))
   :delete (fn [p] (.unlinkSync fs p))
   :lmdb-meta-format meta-format
   :set-lmdb-meta-format! set-meta-format!})

(defn- native-gap?
  "True when `e` is a missing or unloadable LMDB addon, not a store failure."
  [e]
  (boolean (re-find #"Cannot find module|ERR_DLOPEN|\.node|No native build"
                    (str (ex-message e) e))))

(defn- lmdb-gap
  "nil when an LMDB environment opens, else the error that kept it shut."
  []
  (let [dir ((:tmp-dir env) "dacite-lmdb-probe-")]
    (try (lmdb/lmdb-close (lmdb/lmdb-store dir)) nil
         (catch :default e (when (native-gap? e) e))
         (finally ((:rm-rf env) dir)))))

(defn- lmdb-check? [name] (boolean (re-find #"lmdb" name)))

(defn- run [label run-one checks skip-lmdb?]
  (let [names (cond->> (sort (keys checks))
                skip-lmdb? (remove lmdb-check?))
        rows (mapv #(run-one env %) names)
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
        bad (+ (run "store checks" store-checks/run-one store-checks/checks gap)
               (run "format checks" format-checks/run-one format-checks/checks gap))]
    (when gap
      (println "storage lmdb SKIP: the native addon did not load.")
      (println (str "  " (ex-message gap))))
    (cond
      (pos? bad) (js/process.exit 1)
      gap (js/process.exit 2)
      :else (println "storage tests passed"))))
