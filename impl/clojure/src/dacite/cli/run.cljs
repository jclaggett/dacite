(ns dacite.cli.run
  "The run file and port reuse for `npx dacite` (docs/design/packaging.md
   §P2, §P1 Storage). Node only.

   A peer that starts writes `<data dir>/run/<port>.json` with what a
   second `npx dacite` on the same port needs to print the URL again:

     {\"dacite\": \"<package version>\", \"proto\": 1, \"pid\": <pid>,
      \"port\": <port>, \"url\": \"http://127.0.0.1:<port>/app/\",
      \"signal\": \"ws://…/signal\", \"room\": \"…\", \"token\": \"…\",
      \"open\": \"<the Value Browser URL with the fragment>\",
      \"store\": \"<store dir>\" or null, \"engine\": \"lmdb\"|\"file\"|\"memory\",
      \"value\": \"<hex>\"}

   It is written to `<port>.json.<pid>.tmp` (mode 0600 on POSIX) and
   renamed over the run file, and removed on a clean exit when it still
   holds our pid. On POSIX the data dir and `run/` are created mode 0700,
   and `run/` is set to 0700 when it already exists (it holds tokens). On
   Windows mode bits do not apply; the per-user `%LOCALAPPDATA%` ACL is
   the equivalent.

   `reuse` decides what a second `npx dacite` does once the port answered
   `GET /dacite/version` as our peer, same version: print the run file's
   URL, or refuse (no run file, a stale one, or another store)."
  (:require [dacite.cli.store :as cli-store]))

(def ^:private fs (js/require "fs"))
(def ^:private node-path (js/require "path"))

(def proto
  "The `/dacite/version` protocol number this peer speaks."
  1)

(defn- posix? [] (not= "win32" (.-platform js/process)))

(def ^:private private-dir-mode 448) ; 0700 octal
(def ^:private private-file-mode 384) ; 0600 octal

(defn ensure-private-dir!
  "Create `dir` (and missing parents) mode 0700 on POSIX when it does not
   exist. An existing dir is left as it is. Returns `dir`."
  [dir]
  (when-not (.existsSync fs dir)
    (.mkdirSync fs dir (if (posix?)
                         #js {:recursive true :mode private-dir-mode}
                         #js {:recursive true})))
  dir)

(defn run-dir
  "`<data dir>/run`."
  [data-dir]
  (.join node-path data-dir "run"))

(defn run-path
  "`<data dir>/run/<port>.json`."
  [data-dir port]
  (.join node-path (run-dir data-dir) (str port ".json")))

(defn ensure-run-dir!
  "The data dir and `run/`, created 0700 on POSIX; `run/` is set to 0700
   when it already exists. Returns the run dir."
  [data-dir]
  (ensure-private-dir! data-dir)
  (let [d (ensure-private-dir! (run-dir data-dir))]
    (when (posix?) (.chmodSync fs d private-dir-mode))
    d))

(def ^:private run-keys
  [:dacite :proto :pid :port :url :signal :room :token :open :store :engine :value])

(defn write-run-file!
  "Write `info` (a map with at least `:port` and `:pid`; see the ns doc)
   to `<data dir>/run/<port>.json` atomically: a temp file, mode 0600 on
   POSIX, renamed over it. Returns the path."
  [data-dir info]
  (ensure-run-dir! data-dir)
  (let [p (run-path data-dir (:port info))
        tmp (str p "." (:pid info) ".tmp")
        obj (reduce (fn [o k]
                      (when (contains? info k) (aset o (name k) (clj->js (get info k))))
                      o)
                    #js {} run-keys)
        body (js/JSON.stringify obj nil 2)]
    (.writeFileSync fs tmp (str body "\n")
                    (if (posix?) #js {:mode private-file-mode} #js {}))
    (.renameSync fs tmp p)
    p))

(defn read-run-file
  "The run file for `port` as a keyword map, nil when there is none, or
   `{:invalid reason}` when it is not a JSON object with an integer pid."
  [data-dir port]
  (let [p (run-path data-dir port)]
    (when (.existsSync fs p)
      (try
        (let [m (js->clj (js/JSON.parse (.readFileSync fs p "utf8")) :keywordize-keys true)]
          (if (and (map? m) (integer? (:pid m)))
            m
            {:invalid "no pid"}))
        (catch :default e {:invalid (or (ex-message e) (str e))})))))

(defn remove-run-file!
  "Remove the run file for `port` when it holds `pid`. Returns true when
   it removed it."
  [data-dir port pid]
  (let [m (read-run-file data-dir port)]
    (if (and m (= pid (:pid m)))
      (do (try (.unlinkSync fs (run-path data-dir port)) (catch :default _ nil))
          true)
      false)))

(defn requested-store
  "What the bin's `store` options ask for, in run-file terms:
   {:engine \"memory\" :store nil} for `{:memory true}`, else {:store dir}
   (the default store dir for `{}`)."
  [{:keys [memory dir]}]
  (if memory
    {:engine "memory" :store nil}
    {:store (.resolve node-path (or dir (cli-store/default-store-dir)))}))

(defn- store-name
  [{:keys [engine store]}]
  (if (= "memory" engine) "an in-memory store" (str "the store " store)))

(defn reuse
  "What a second `npx dacite` prints when `port` answered as our peer,
   same version, with pid `peer-pid`. Returns {:lines [...]} (print them,
   exit 0) or {:error msg} (print it, exit 1). Options: `:data-dir`,
   `:port`, `:peer-pid`, `:store` (the bin's store options), and `:alive?`
   (default `dacite.cli.store/pid-alive?`).

   Refused: no run file (another user's peer, or a stale file removed),
   an unreadable one, a stale one (its pid is not running, or is not the
   peer that answered), or a peer serving another store than asked for."
  [{:keys [data-dir port peer-pid store alive?] :or {alive? cli-store/pid-alive?}}]
  (let [p (run-path data-dir port)
        m (read-run-file data-dir port)
        stop " Stop it or pass --port."
        want (requested-store (or store {}))
        theirs {:engine (:engine m) :store (some->> (:store m) (.resolve node-path))}]
    (cond
      (nil? m)
      {:error (str "dacite (pid " peer-pid ") is running on port " port
                   ", but there is no run file " p
                   " (another user's peer, or a stale file)." stop)}

      (:invalid m)
      {:error (str "dacite (pid " peer-pid ") is running on port " port
                   ", but its run file " p " is unreadable (" (:invalid m) ")." stop)}

      (not (alive? (:pid m)))
      {:error (str "the run file " p " is stale: pid " (:pid m) " is not running, and pid "
                   peer-pid " holds port " port "." stop)}

      (not= peer-pid (:pid m))
      {:error (str "the run file " p " is stale: it names pid " (:pid m) ", and pid "
                   peer-pid " holds port " port "." stop)}

      (not= (if (= "memory" (:engine want)) "memory" (:store want))
            (if (= "memory" (:engine theirs)) "memory" (:store theirs)))
      {:error (str "dacite (pid " peer-pid ") on port " port " serves " (store-name theirs)
                   ", not " (store-name want) "." stop)}

      :else
      {:lines (cond-> [(str "dacite already running " (:url m) " (pid " (:pid m) ")")
                       (if (:store m)
                         (str "store " (:engine m) " " (:store m))
                         (str "store " (:engine m)))]
                (:value m) (conj (str "value " (:value m)))
                true (conj (str "open " (:open m)))
                (:value m) (conj (str "paste dacite://local#urn:dacite:" (:value m))))})))

(defn- js-map [o] (js->clj o :keywordize-keys true))

(defn bin-api
  "The functions `bin/dacite.mjs` calls, taking and returning JS objects.
   `dataDir()`; `writeRun(info)` (path); `removeRun(port, pid)`;
   `reuse({port, peerPid, store})` ({lines} or {error})."
  []
  #js {:dataDir cli-store/default-data-dir
       :writeRun (fn [info] (write-run-file! (cli-store/default-data-dir) (js-map info)))
       :removeRun (fn [port pid] (remove-run-file! (cli-store/default-data-dir) port pid))
       :reuse (fn [o]
                (let [{:keys [port peerPid store]} (js-map o)]
                  (clj->js (reuse {:data-dir (cli-store/default-data-dir)
                                   :port port :peer-pid peerPid :store store}))))})
