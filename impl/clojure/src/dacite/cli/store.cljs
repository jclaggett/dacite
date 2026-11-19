(ns dacite.cli.store
  "The store `npx dacite` opens (docs/design/packaging.md §P3). Node only.

   - `data-dir`: `DACITE_HOME` when it is set; otherwise macOS
     `~/Library/Application Support/Dacite`, Linux
     `${XDG_DATA_HOME:-~/.local/share}/dacite`, Windows
     `%LOCALAPPDATA%\\Dacite`. The default store is `<data dir>/store`.
   - Engine: LMDB when the optional `lmdb` addon loads, else the file
     store with a warning. The store dir records its engine and format
     version in the marker file `ENGINE` (`lmdb 2` or `file 1`); a dir
     marked for an engine that cannot open here, or for a format version
     this dacite does not read, is an error, not a silent second store. The LMDB
     env is `<store dir>/lmdb` (LMDB makes that dir itself); the file
     store and its `ROOT` are `<store dir>/file`.
   - `LOCK`: one peer per store. The file holds the peer's pid. A live
     pid is refused; a dead one (`ESRCH`) is taken over with a warning
     by renaming `LOCK.<pid>.tmp` over it and reading it back. Mode 0600
     on POSIX; on Windows the per-user `%LOCALAPPDATA%` ACL does that job.

   `open!` does all of it and returns the content store, its root cell,
   and a `close` that closes the store and removes `LOCK`."
  (:require [clojure.string :as str]
            [dacite.rooted :as rooted]
            [dacite.store.nbb :as nbb]
            [dacite.store.nbb.lmdb :as lmdb]))

(def ^:private fs (js/require "fs"))
(def ^:private os (js/require "os"))
(def ^:private node-path (js/require "path"))

;; =============================================================================
;; Data dir
;; =============================================================================

(defn- present
  "`s` when it is a non-empty string, else nil."
  [s]
  (when (and (string? s) (not= "" s)) s))

(defn data-dir
  "The data dir for `env` (a map of environment variable names to values),
   `platform` (Node's `process.platform`), and `home` (the user's home
   dir). Pure: the paths use `platform`'s separators.

   An empty `DACITE_HOME`, `XDG_DATA_HOME`, or `LOCALAPPDATA` counts as
   unset. A relative `XDG_DATA_HOME` is ignored, as the XDG spec says.
   Without `LOCALAPPDATA`, Windows uses `<home>\\AppData\\Local`."
  [{:keys [env platform home]}]
  (let [p (if (= "win32" platform) (.-win32 node-path) (.-posix node-path))
        dacite-home (present (get env "DACITE_HOME"))
        xdg (present (get env "XDG_DATA_HOME"))
        local (present (get env "LOCALAPPDATA"))]
    (cond
      dacite-home (.resolve p dacite-home)
      (= "darwin" platform) (.join p home "Library" "Application Support" "Dacite")
      (= "win32" platform) (.join p (or local (.join p home "AppData" "Local")) "Dacite")
      (and xdg (.isAbsolute p xdg)) (.join p xdg "dacite")
      :else (.join p home ".local" "share" "dacite"))))

(defn- process-env
  []
  (let [e (.-env js/process)]
    (into {} (map (fn [k] [k (aget e k)])) (js-keys e))))

(defn default-data-dir
  "The data dir for this process (`data-dir` of its env, platform, and home)."
  []
  (data-dir {:env (process-env)
             :platform (.-platform js/process)
             :home (.homedir os)}))

(defn default-store-dir
  "`<data dir>/store` for this process."
  []
  (.join node-path (default-data-dir) "store"))

;; =============================================================================
;; Engine
;; =============================================================================

(def marker-name
  "The engine marker file in a store dir."
  "ENGINE")

(defn lmdb-loads
  "nil when the optional `lmdb` addon loads, else why it did not."
  []
  (try (js/require "lmdb") nil
       (catch :default e (or (ex-message e) (str e)))))

(defn- file-exists? [p] (.existsSync fs p))

(defn- store-error
  [msg data]
  (ex-info msg (merge {:dacite/exit 1} data)))

(defn engine-path
  "Where `engine` keeps its data inside the store dir `dir`."
  [dir engine]
  (.join node-path dir (name engine)))

(def formats
  "The store format version this dacite writes and reads, per engine. The
   marker is one line, the engine, a space, and the version: `lmdb 2`
   (LMDB data format 2, the `lmdb` npm prebuilds) or `file 1`."
  {"lmdb" 2 "file" 1})

(defn marker-line
  "The `ENGINE` line for `engine` (a string): `lmdb 2` or `file 1`."
  [engine]
  (str engine " " (get formats engine)))

(defn parse-marker
  "{:engine string :version int} from an `ENGINE` file's text, or nil when
   it is not `<engine> <version>` (a bare engine name is not a marker)."
  [text]
  (when-let [[_ engine version] (re-matches #"([a-z][a-z0-9-]*) ([1-9][0-9]{0,8})" (str/trim text))]
    {:engine engine :version (js/parseInt version 10)}))

(defn- check-marker!
  "Throw unless `m` (from `parse-marker`) is an engine and version this
   dacite supports."
  [dir marker text m]
  (let [{:keys [engine version]} m
        supported (get formats engine)]
    (cond
      (nil? m)
      (throw (store-error (str "store " dir " has an invalid " marker-name " marker "
                               (pr-str (str/trim text)) "; expected \"lmdb 2\" or \"file 1\"")
                          {:reason :invalid-marker :path marker}))

      (nil? supported)
      (throw (store-error (str "store " dir " uses an unknown engine " (pr-str engine)
                               "; this dacite supports lmdb 2 and file 1")
                          {:reason :unknown-engine}))

      (not= supported version)
      (throw (store-error (str "store " dir " uses " engine " format " version
                               "; this dacite supports " engine " " supported)
                          {:reason :unsupported-version :engine engine :version version})))))

(defn choose-engine!
  "The engine for the store dir `dir` (which exists): {:engine :lmdb|:file
   :warning nil|string}. `lmdb-error` is nil when `lmdb` loads, else why
   not. A marked dir keeps its engine. An unmarked dir with an `lmdb` or
   `file` subdir is marked for that one. An empty dir gets LMDB when it
   loads, else the file store and a warning. The versioned marker
   (`marker-line`) is written when it was missing. Throws when the marker
   is not `<engine> <version>` (a bare `lmdb` or `file` included), names
   an unknown engine or a version other than `formats`, or names `lmdb`
   and it does not load."
  [dir lmdb-error]
  (let [marker (.join node-path dir marker-name)
        text (when (file-exists? marker) (.readFileSync fs marker "utf8"))
        marked (when text
                 (let [m (parse-marker text)]
                   (check-marker! dir marker text m)
                   (:engine m)))
        found (cond
                marked marked
                (file-exists? (engine-path dir :lmdb)) "lmdb"
                (file-exists? (engine-path dir :file)) "file")
        chosen (cond
                 found found
                 lmdb-error "file"
                 :else "lmdb")]
    (when (and (= "lmdb" chosen) lmdb-error)
      (throw (store-error (str "store " dir " is an lmdb store, and lmdb did not load: "
                               lmdb-error)
                          {:reason :wrong-engine})))
    (when-not marked
      (.writeFileSync fs marker (str (marker-line chosen) "\n") "utf8"))
    {:engine (keyword chosen)
     :warning (when (and (not found) lmdb-error)
                (str "lmdb did not load (" lmdb-error "); using the file store"))}))

;; =============================================================================
;; LOCK
;; =============================================================================

(def lock-name
  "The lock file in a store dir."
  "LOCK")

(defn- posix-mode
  "0600 on POSIX; nil on Windows, where Node ignores the mode."
  []
  (when-not (= "win32" (.-platform js/process)) 384)) ; 0600 octal

(defn pid-alive?
  "True when `process.kill(pid, 0)` succeeds or fails with EPERM (alive,
   another user's); false on ESRCH (no such process). Other errors throw."
  [pid]
  (try (.kill js/process pid 0) true
       (catch :default e
         (case (.-code e)
           "EPERM" true
           "ESRCH" false
           (throw e)))))

(defn- read-pid
  "The pid in the lock file `p`, or nil when it holds no pid."
  [p]
  (let [s (str/trim (try (.readFileSync fs p "utf8") (catch :default _ "")))]
    (when (re-matches #"[1-9][0-9]*" s) (js/parseInt s 10))))

(defn- write-new!
  "Create `p` holding `pid`; false when it exists."
  [p pid]
  (let [fd (try (.openSync fs p "wx" (posix-mode))
                (catch :default e
                  (if (= "EEXIST" (.-code e)) nil (throw e))))]
    (when fd
      (try (.writeSync fs fd (str pid "\n"))
           (finally (.closeSync fs fd)))
      true)))

(defn acquire-lock!
  "Take `<dir>/LOCK` for `pid` (default this process). Returns a function
   that removes `LOCK` when it still holds `pid`. Options:
   `:pid`, `:alive?` (default `pid-alive?`), `:warn` (a function of a
   string, default `console.error`), and `:after-rename` (a test hook run
   between the rename and the read-back).

   A `LOCK` with a live pid is refused (throws, `:dacite/exit` 1). One
   with a dead pid, or no pid (a crash while writing it), is taken over
   with the warning `taking over a stale LOCK from pid <pid>`."
  ([dir] (acquire-lock! dir {}))
  ([dir {:keys [pid alive? warn after-rename]
         :or {pid (.-pid js/process)
              alive? pid-alive?
              warn #(js/console.error %)}}]
   (let [p (.join node-path dir lock-name)
         release (fn []
                   (when (= pid (read-pid p))
                     (try (.unlinkSync fs p) (catch :default _ nil)))
                   nil)]
     (when-not (write-new! p pid)
       (let [held (read-pid p)]
         (when (and held (alive? held))
           (throw (store-error (str "store " dir " is in use by pid " held
                                    "; if no dacite is running, delete " p)
                               {:reason :locked :pid held})))
         (warn (str "taking over a stale LOCK from pid " (or held "unknown")))
         (let [tmp (.join node-path dir (str lock-name "." pid ".tmp"))]
           (.writeFileSync fs tmp (str pid "\n") (if-let [m (posix-mode)] #js {:mode m} #js {}))
           (.renameSync fs tmp p)
           (when after-rename (after-rename p))
           (let [now (read-pid p)]
             (when-not (= pid now)
               (throw (store-error (str "store " dir " was taken over by pid " now
                                        " first")
                                   {:reason :lost-takeover :pid now})))))))
     release)))

;; =============================================================================
;; Open
;; =============================================================================

(defn empty-store?
  "True when `content` (an open store of `engine`) holds no entries. Reads
   at most one entry or directory name."
  [engine content]
  (case engine
    :lmdb (.-done (.next (.iterate (.getRange (:db content) #js {:limit 1}))))
    :file (let [base (:base content)]
            (not-any? #(re-matches #"[0-9a-f]{2}" %) (.readdirSync fs base)))))

(defn open!
  "Open the store dir `dir` (created if missing): take `LOCK`, choose the
   engine, and open the content store with its root cell. Returns
   {:engine :dir :path :content :cell :empty? :close}. Options: `:lmdb-error`
   (default `(lmdb-loads)`; nil means lmdb loads) and the `acquire-lock!`
   options. A `:warning` from `choose-engine!` goes to `:warn`. When the
   open fails after `LOCK` was taken, `LOCK` is removed before it throws."
  ([dir] (open! dir {}))
  ([dir opts]
   (let [dir (.resolve node-path dir)
         warn (or (:warn opts) #(js/console.error %))
         _ (.mkdirSync fs dir #js {:recursive true})
         release (acquire-lock! dir (assoc opts :warn warn))]
     (try
       (let [{:keys [engine warning]} (choose-engine! dir (if (contains? opts :lmdb-error)
                                                            (:lmdb-error opts)
                                                            (lmdb-loads)))
             path (engine-path dir engine)
             _ (when warning (warn warning))
             content (case engine
                       :lmdb (lmdb/lmdb-store path)
                       :file (nbb/file-store path))
             cell (case engine
                    :lmdb (lmdb/lmdb-root-cell content)
                    :file (rooted/file-root-cell path))]
         {:engine engine
          :dir dir
          :path path
          :content content
          :cell cell
          :empty? (empty-store? engine content)
          :close (fn []
                   (when (= :lmdb engine)
                     (try (lmdb/lmdb-close content) (catch :default _ nil)))
                   (release))})
       (catch :default e
         (release)
         (throw e))))))
