(ns dacite.examples.config
  "Portable config app — Values vs Store kept separate (same split as todo).

   **Values** — a config map {\"theme\" string, \"timeout\" i64,
   \"features\" vector of strings}. Domain ops take/return Dacite values.

   **Store** — open a local file-rooted store or an HTTP remote-rooted
   store. Same domain against either.

   Run:
     clojure -M:config -- show
     clojure -M:config -- --path target/dacite-config set timeout 60
     clojure -M:config -- --url http://127.0.0.1:8080 show
     clojure -M:config -- --url http://127.0.0.1:8080 watch
     bb config show
     npx nbb -m dacite.examples.config -- show
     npx nbb -m dacite.examples.config -- --lmdb show"
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.value :as v]
            #?@(:org.babashka/nbb [[dacite.store.nbb :as host-store]
                                   [dacite.store.nbb.lmdb :as lmdb]]
                :cljs []
                :default [[clojure.java.io :as io]])))

;; =============================================================================
;; Values
;; =============================================================================

(defn default-config
  "Seed config relative to `peer` (value, root-ref, or IStore)."
  [peer]
  (v/hash-map-via peer
                  "theme" "dark"
                  "timeout" 30
                  "features" (v/vector-via peer "a" "b")))

(defn load-or-seed!
  "Load config from a value-level root-ref, or CAS-seed defaults.

   Uses `ref-cas!` from nil (not `ref-reset!`) so the same code works
   against a remote store. Returns [config seeded?]."
  [cfg-ref]
  (if-let [prior (v/ref-deref cfg-ref)]
    [prior false]
    (let [cfg (default-config cfg-ref)]
      (if (v/ref-cas! cfg-ref nil cfg)
        [cfg true]
        [(v/ref-deref cfg-ref) false]))))

(defn theme
  "Theme name as a host string."
  [cfg]
  (v/as-str (v/get cfg "theme")))

(defn timeout
  "Timeout as a host number."
  [cfg]
  (v/native (v/get cfg "timeout")))

(defn features
  "Features vector (Dacite value)."
  [cfg]
  (v/get cfg "features"))

(defn get-path
  "Look up a nested path (seq of keys/indexes)."
  [cfg ks]
  (v/get-in cfg ks))

(defn set-path
  "Assoc a nested path. Returns a new config value."
  [cfg ks val]
  (v/assoc-in cfg ks val))

(defn add-feature
  "Append a feature name to the features vector."
  [cfg name]
  (v/update cfg "features" v/conj name))

(defn config-hash
  "Content hash of a config value."
  [cfg]
  (v/dacite-hash cfg))

(defn render
  "Plain-text listing. Field access via native/as-str — not dac->clj."
  [cfg]
  (let [fs (or (v/seq (features cfg)) ())]
    (str "theme:    " (theme cfg) "\n"
         "timeout:  " (timeout cfg) "\n"
         "features: " (str/join ", " (map v/as-str fs)) "\n"
         "root:     " (store/hash->hex (config-hash cfg)) "\n")))

(defn render-field
  "Print one path: scalars/strings via native/as-str; collections as a listing."
  [cfg ks]
  (let [x (get-path cfg ks)]
    (cond
      (nil? x) "(missing)"
      (not (v/dacite-value? x)) (str x)
      (= "vector" (v/value-type x))
      (str/join ", " (map v/as-str (or (v/seq x) ())))
      (#{"map" "set" "blob"} (v/value-type x))
      (str "(" (v/value-type x) ", " (v/count x) " entries)")
      :else (str (v/native x)))))

;; =============================================================================
;; Store
;; =============================================================================

(def default-path
  "Default directory for sharded content + ROOT file."
  "target/dacite-config")

#?(:org.babashka/nbb
   (defn open-file
     [path]
     (store/rooted-store (host-store/file-store path)
                         (store/file-root-cell path)))
   :cljs
   (defn open-file
     [_path]
     (throw (js/Error. "config/open-file is for JVM/nbb file backends only")))
   :default
   (defn open-file
     [path]
     (store/rooted-store (store/file-store path)
                         (store/file-root-cell path))))

#?(:clj
   (defn open-remote
     "HTTP remote + server root. Same `v/root-ref` as a local file store."
     [url]
     (store/remote-rooted-store url))
   :default
   (defn open-remote
     [_url]
     (throw (ex-info "remote config store is JVM-only (java.net.http)" {}))))

(defn open-mem
  "Ephemeral rooted store (tests, REPL)."
  []
  (store/rooted-store (store/mem-store)))

#?(:org.babashka/nbb
   (defn reset-store-dir!
     [path]
     (let [content (host-store/file-store path)]
       (store/s-reset content)
       (store/rc-put! (store/file-root-cell path) nil)
       nil))
   :cljs
   (defn reset-store-dir! [_path] nil)
   :default
   (defn reset-store-dir!
     [path]
     (let [content (store/file-store path)]
       (store/s-reset content)
       (store/rc-put! (store/file-root-cell path) nil)
       nil)))

#?(:org.babashka/nbb
   (defn open-lmdb
     [path]
     (let [st (lmdb/lmdb-store path)]
       (store/rooted-store st (lmdb/lmdb-root-cell st))))
   :clj
   (defn open-lmdb
     [path]
     (let [st (store/lmdb-store path)]
       (store/rooted-store st (store/lmdb-root-cell st))))
   :default
   (defn open-lmdb
     [_path]
     (throw (ex-info "LMDB is nbb or JVM only" {}))))

#?(:org.babashka/nbb
   (defn reset-lmdb-dir!
     [path]
     (let [fs (js/require "fs")]
       (when (.existsSync fs path)
         (.rmSync fs path #js {:recursive true :force true}))
       nil))
   :clj
   (defn reset-lmdb-dir!
     [path]
     (let [dir (io/file path)]
       (when (.exists dir)
         (doseq [f (reverse (file-seq dir))]
           (.delete ^java.io.File f)))
       nil))
   :default
   (defn reset-lmdb-dir! [_path] nil))

(defn local-path
  "Directory for the local store. `--lmdb` without `--path` uses `<default>-lmdb`."
  [{:keys [lmdb? path]}]
  (if (and lmdb? (= path default-path))
    (str default-path "-lmdb")
    path))

(defn parse-path
  "Split a dotted CLI path. Numeric segments become integers (vector indexes)."
  [s]
  (mapv (fn [seg]
          (if (re-matches #"-?\d+" seg)
            #?(:clj (Long/parseLong seg)
               :cljs (js/parseInt seg 10))
            seg))
        (str/split (str s) #"\.")))

(defn parse-cli-value
  "Host value for `set`: integers stay numbers, otherwise a string."
  [s]
  (let [s (str s)]
    (if (re-matches #"-?\d+" s)
      #?(:clj (Long/parseLong s)
         :cljs (js/parseInt s 10))
      s)))

(defn parse-args
  "CLI: [--path DIR | --url URL] [--lmdb] [--reset|-r] [show|get PATH|set PATH VAL|add-feature NAME|watch]"
  [args]
  (let [args (->> args (map str) (remove #{"--"}))]
    (loop [args args
           acc {:reset? false
                :lmdb? false
                :path default-path
                :url nil
                :cmd "show"
                :cmd-args []}]
      (if-not (seq args)
        acc
        (let [a (first args)
              more (rest args)]
          (cond
            (or (= a "--reset") (= a "-r"))
            (recur more (assoc acc :reset? true))

            (= a "--lmdb")
            (recur more (assoc acc :lmdb? true))

            (= a "--path")
            (recur (rest more) (assoc acc :path (first more)))

            (= a "--url")
            (recur (rest more) (assoc acc :url (first more)))

            (#{"show" "get" "set" "add-feature" "watch"} a)
            (assoc acc :cmd a :cmd-args (vec more))

            :else
            (assoc acc :cmd "show" :cmd-args (vec args))))))))

(defn open-store
  "File-rooted, LMDB-rooted, or HTTP remote-rooted."
  [{:keys [url lmdb?] :as opts}]
  (cond
    url (open-remote url)
    lmdb? (open-lmdb (local-path opts))
    :else (open-file (:path opts))))

;; =============================================================================
;; Main
;; =============================================================================

(defn- print-cfg! [label cfg]
  (when label
    (println label))
  (print (render cfg))
  (flush))

#?(:clj
   (defn- watch-loop!
     [cfg-ref]
     (println "watching root (Ctrl-C to stop)…")
     (loop [prev (some-> (v/ref-deref cfg-ref) config-hash)]
       (Thread/sleep 500)
       (let [cur (some-> (v/ref-deref cfg-ref) config-hash)]
         (when (not= prev cur)
           (if-let [cfg (v/ref-deref cfg-ref)]
             (print-cfg! "updated:" cfg)
             (println "root cleared")))
         (recur cur))))
   :default
   (defn- watch-loop!
     [_cfg-ref]
     (throw (ex-info "watch is JVM-only" {}))))

(defn -main [& args]
  (let [{:keys [reset? path url lmdb? cmd cmd-args] :as opts} (parse-args args)
        local (local-path opts)]
    (when (and reset? url)
      (throw (ex-info "--reset is for the local store only" {:url url})))
    (when reset?
      (if lmdb?
        (reset-lmdb-dir! local)
        (reset-store-dir! path))
      (println "reset store at" local))
    (let [rs (open-store opts)
          cfg-ref (v/root-ref rs)
          [cfg seeded?] (load-or-seed! cfg-ref)]
      (when lmdb?
        (println "lmdb" local))
      (when seeded?
        (println (if url
                   (str "seeded remote at " url)
                   (str "seeded new store at " local))))
      (case cmd
        "show"
        (print-cfg! nil (v/ref-deref cfg-ref))

        "get"
        (let [ks (parse-path (or (first cmd-args) ""))]
          (when (empty? ks)
            (throw (ex-info "get requires a path, e.g. theme or features.0" {})))
          (println (render-field (v/ref-deref cfg-ref) ks)))

        "set"
        (let [ks (parse-path (first cmd-args))
              val (parse-cli-value (second cmd-args))]
          (when (or (empty? ks) (nil? (second cmd-args)))
            (throw (ex-info "set requires PATH VALUE" {:args cmd-args})))
          (let [cfg' (v/ref-swap! cfg-ref set-path ks val)]
            (print-cfg! nil cfg')))

        "add-feature"
        (let [name (or (first cmd-args)
                       (throw (ex-info "add-feature requires a name" {})))
              cfg' (v/ref-swap! cfg-ref add-feature name)]
          (print-cfg! nil cfg'))

        "watch"
        (do
          (print-cfg! nil (or (v/ref-deref cfg-ref) cfg))
          (watch-loop! cfg-ref))))))
