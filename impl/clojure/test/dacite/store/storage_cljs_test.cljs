(ns dacite.store.storage-cljs-test
  "nbb side of the storage tests. Run from the repo root:

     npm run storage:test

   The checks of `dacite.store-checks` and `dacite.store.format-checks`
   with the nbb file store (`dacite.store.nbb`) and the nbb LMDB store
   (`dacite.store.nbb.lmdb`). The JVM runs the same checks from
   `dacite.store-test` and `dacite.store.format-test`.

   It also opens the stores in a fresh nbb process (`cold-start-problems`):
   the runner loads every namespace up front, which hid that `store/file`
   threw on a cold start.

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

;; =============================================================================
;; Cold start: the stores open in a fresh nbb process
;; =============================================================================

(def ^:private cold-entries
  "[label requires]: the ns requires of a fresh nbb process, in order. The
   first is an app's usual order; the second loads `dacite.rooted.gc` before
   `dacite.store`, as `dacite.examples.notes` does."
  [["store first" "[dacite.store :as s] [dacite.value :as v]"]
   ["gc first" "[dacite.rooted.gc] [dacite.store :as s] [dacite.value :as v]"]])

(defn- cold-source
  "A script that opens `store/file` (and `store/lmdb` unless `skip-lmdb?`),
   commits a root, reopens it, then runs `sync-reachable!` and `lens`. It
   prints \"cold ok\" at the end."
  [requires skip-lmdb?]
  (str "(ns cold (:require " requires "))\n"
       "(def os (js/require \"os\")) (def fs (js/require \"fs\")) (def path (js/require \"path\"))\n"
       "(defn tmp [] (.mkdtempSync fs (.join path (.tmpdir os) \"dacite-cold-\")))\n"
       "(defn reopens? [open]\n"
       "  (let [d (tmp) r (v/root (open d))]\n"
       "    (v/cas! r nil (v/vector r 1 2 3))\n"
       "    (let [h (v/hash (v/deref r)) ok (= h (v/hash (v/deref (v/root (open d)))))]\n"
       "      (.rmSync fs d #js {:recursive true :force true}) ok)))\n"
       "(do\n"
       "  (println \"file reopens\" (reopens? s/file))\n"
       (if skip-lmdb? "" "  (println \"lmdb reopens\" (reopens? s/lmdb))\n")
       "  (let [src (s/mem) r (v/root src)]\n"
       "    (v/cas! r nil (v/vector r \"a\" \"b\"))\n"
       "    (println \"sync copies\" (pos? (:copied (s/sync-reachable! src (s/mem-store) (s/root src))))))\n"
       "  (println \"lens\" (some? (s/lens (s/mem) [\"k\"])))\n"
       "  (println \"cold ok\"))\n"))

;; JUSTIFICATION for the time (rule: a check over about 1.5 s needs one): each
;; cold start is a child nbb process, so most of its time is harness: node and
;; nbb starting and loading dacite.store, dacite.value and the store
;; namespaces from source (about 0.9 s here, 1758 and 1759 ms on CI, #61). The
;; work it checks, opening and reopening two stores, is a few tens of ms.
(def ^:private cold-ceiling-ms
  "nbb ceiling for one cold start, about 2x CI."
  3500)

(defn- cold-start-problems
  "Run each of `cold-entries` in a fresh nbb process. A `require` inside a
   function is async on nbb, so a store namespace that `dacite.store` only
   resolves at call time must already be loaded; a runner that loads
   everything up front cannot see that, a fresh process can."
  [skip-lmdb?]
  (let [cp (js/require "child_process")
        ;; under the repo, so `js/require` finds node_modules (lmdb)
        _ (.mkdirSync fs "target" #js {:recursive true})
        dir (.mkdtempSync fs (.join path "target" "dacite-cold-src-"))]
    (try
      (vec
       (mapcat
        (fn [[label requires]]
          (let [f (.join path dir "cold.cljs")
                _ (.writeFileSync fs f (cold-source requires skip-lmdb?) "utf8")
                t0 (.now js/Date)
                res (.spawnSync cp (.-execPath js/process)
                                #js ["node_modules/nbb/cli.js" "-cp" "impl/clojure/src" f]
                                #js {:encoding "utf8"})
                ms (- (.now js/Date) t0)
                out (str (.-stdout res) (.-stderr res))
                want (cond-> ["file reopens true" "sync copies true" "lens true" "cold ok"]
                       (not skip-lmdb?) (conj "lmdb reopens true"))
                missing (remove #(.includes out %) want)]
            (println (str "cold start (" label "): " ms " ms"))
            (cond-> []
              (or (not= 0 (.-status res)) (seq missing))
              (conj (str label ": exit " (.-status res) ", missing " (pr-str (vec missing))
                         "\n" out))
              (> ms cold-ceiling-ms)
              (conj (str label ": took " ms " ms, over " cold-ceiling-ms)))))
        cold-entries))
      (finally ((:rm-rf env) dir)))))

(defn -main
  [& _]
  (let [gap (lmdb-gap)
        cold (cold-start-problems gap)
        _ (doseq [p cold] (println "  FAIL cold start" p))
        bad (+ (count cold)
               (run "store checks" store-checks/run-one store-checks/checks gap)
               (run "format checks" format-checks/run-one format-checks/checks gap))]
    (when gap
      (println "storage lmdb SKIP: the native addon did not load.")
      (println (str "  " (ex-message gap))))
    (cond
      (pos? bad) (js/process.exit 1)
      gap (js/process.exit 2)
      :else (println "storage tests passed"))))
