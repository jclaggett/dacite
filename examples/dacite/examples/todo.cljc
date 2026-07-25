(ns dacite.examples.todo
  "Portable todo demo — two concerns, deliberately separated:

   **Values** — domain ops, seed data, read/write the root value. A store
   appears only as a parameter (or as the store carried by a Dacite value).
   This code does not care whether content lives in a file, mem, or HTTP
   remote, or which cache/budget policy is used.

   **Store** — open and configure the durable (or remote) store: path, reset,
   host backend. No todo shape, seed data, or domain mutations.

   Local single-writer recipe:
     1. (open-store path)                 ; Store section
     2. (load-or-seed! rooted)            ; Values section
     3. (add-todo / toggle-at / …) then (commit-todos! rooted todos)

   Hosts: JVM / babashka / nbb (file store). Browser uses todo-web with an
   HTTP store (same Values API, different Store wiring).

   Run (batch):
     npx nbb -m dacite.examples.todo
     bb todo
     bb todo /tmp/my-todos --reset

   Run (interactive nbb):
     npm run todo"
  (:require [dacite.store :as store]
            [dacite.rooted :as rs]
            [dacite.value.collections :as coll]
            [dacite.value.api :as d]
            [dacite.value.types :as types]
            [dacite.hash :as hash]
            ;; nbb enables :org.babashka/nbb and :cljs; pure browser only :cljs.
            ;; Put nbb first so Node keeps the file store; browser pulls no host-store.
            #?@(:org.babashka/nbb [[dacite.store.nbb :as host-store]]
                :cljs []
                :default [[dacite.store.file :as host-store]])))

;; =============================================================================
;; Values
;;
;; Todo list is a Dacite vector of maps {"title" string, "done" bool}.
;; Construction always targets the store on the receiver value (or an explicit
;; store parameter for empty/seed builds). Root load/commit take a store that
;; can resolve hashes and (for local demos) a rooted root cell.
;; =============================================================================

(defn- todos-store
  "Store carried by a todos value (prefer over *store* so async UIs stay durable)."
  [todos]
  (types/dacite-store todos))

(defn todo-entry?
  "True if x is a todo map (has a \"title\" field)."
  [x]
  (and (d/dacite-value? x)
       (= "map" (d/value-type x))
       (some? (d/get x "title"))))

(defn add-todo
  "Append {title, done} to todos. New nodes go into todos' store."
  ([todos title] (add-todo todos title false))
  ([todos title done?]
   (let [st (todos-store todos)]
     (d/conj todos (coll/hash-map-with-store st "title" title "done" done?)))))

(defn- field-native
  "Native host value for map field k, or nil."
  [todo k]
  (try
    (when (todo-entry? todo)
      (let [v (d/get todo k)]
        (cond
          (nil? v) nil
          (d/dacite-value? v) (types/realize v)
          :else v)))
    (catch #?(:clj Throwable :cljs :default) _
      nil)))

(defn title-str
  "Title of a todo entry as a native string."
  [todo]
  (try
    (when-not (todo-entry? todo)
      (throw (ex-info "not a todo entry" {:type (when (d/dacite-value? todo)
                                                  (d/value-type todo))})))
    (let [v (d/get todo "title")]
      (cond
        (nil? v) "<?no-title?>"
        (not (d/dacite-value? v)) (str v)
        :else
        (let [r (types/realize v)]
          (cond
            (string? r) r
            (nil? r) "<?empty?>"
            :else (apply str r)))))
    (catch #?(:clj Throwable :cljs :default) e
      (str "<?" #?(:clj (.getMessage e) :cljs (.-message e)) "?>"))))

(defn done?
  [todo]
  (boolean (field-native todo "done")))

(defn set-done
  "Return a new todo entry with done flag set."
  [todo done?]
  (d/assoc todo "done" done?))

(defn toggle-at
  "Toggle done at index i."
  [todos i]
  (let [t (d/nth todos i)]
    (if (todo-entry? t)
      (d/assoc todos i (set-done t (not (done? t))))
      todos)))

(defn remove-at
  "Remove index i from the todos vector."
  [todos i]
  (d/remove-nth todos i))

(defn open-count
  "Number of incomplete todos."
  [todos]
  (count (filter (fn [t] (and (todo-entry? t) (not (done? t))))
                 (or (d/seq todos) ()))))

(defn seed-items
  "Initial sample list shape: seq of [title done?] pairs (not store-specific)."
  []
  [["write portable host layer" true]
   ["split the store" true]
   ["run under babashka" false]
   ["run under nbb" false]
   ["durable todo root" false]])

(defn build
  "Build a todos vector in `store` from a seq of [title done?] pairs.
   `store` is only a place to allocate nodes — not a root cell."
  [store items]
  (reduce (fn [todos [title done?]] (add-todo todos title done?))
          (coll/vector-with-store store)
          items))

(defn empty-todos
  "Empty todos vector allocated in `store`."
  [store]
  (coll/vector-with-store store))

(defn load-todos
  "Materialize the todos value at `root-hash` from `store`, or nil.
   `store` is any content store (IStore / layered / remote). Does not
   consult a root cell — pass the root hash from your store wiring."
  [store root-hash]
  (when root-hash
    (d/get-value store root-hash)))

(defn todos-hash
  "Content hash of a todos value (for root pointers / CAS)."
  [todos]
  (d/dacite-hash todos))

(defn commit-todos!
  "Single-writer: set the rooted store's root to the hash of `todos`.

   `rooted` is a RootedStore (content + root cell). Multi-writer remotes
   should CAS instead (see todo-web store wiring)."
  [rooted todos]
  (rs/set-root! rooted (todos-hash todos))
  todos)

(defn load-or-seed!
  "Load todos from a RootedStore root, or seed sample data and commit.

   Returns [todos seeded?]. Uses only Values API + RootedStore root get/set —
   no paths, budgets, or HTTP."
  [rooted]
  (if-let [prior (load-todos rooted (rs/root rooted))]
    [prior false]
    (let [t (build rooted (seed-items))]
      (commit-todos! rooted t)
      [t true])))

(defn render
  "Plain-text listing (no ANSI)."
  [todos]
  (str "todos (" (d/count todos) ", "
       (open-count todos) " open):\n"
       (apply str
              (map-indexed
               (fn [i t]
                 (str "  " i ". [" (if (done? t) "x" " ") "] " (title-str t) "\n"))
               (d/seq todos)))
       "root: " (hash/hash->hex (todos-hash todos))))

;; =============================================================================
;; Store
;;
;; File-backed content + root cell. No todo maps, seed lists, or domain ops.
;; Configuration: path on disk (and host-specific file-store implementation).
;; =============================================================================

(def default-path
  "Default directory for sharded content + ROOT file."
  "target/dacite-todo")

#?(:org.babashka/nbb
   (defn open-store
     "Open a durable rooted store at path (Node file store + ROOT)."
     [path]
     (rs/rooted-store (host-store/file-store path)
                      (rs/file-root-cell path)))
   :cljs
   (defn open-store
     "Not available in the browser — wire an HTTP store in todo-web."
     [_path]
     (throw (js/Error. "todo/open-store is for JVM/nbb file backends only")))
   :default
   (defn open-store
     "Open a durable rooted store at path (sharded .edn content + ROOT file)."
     [path]
     (rs/rooted-store (host-store/file-store path)
                      (rs/file-root-cell path))))

#?(:org.babashka/nbb
   (defn reset-store-dir!
     "Wipe content + root under path (demo --reset). No domain knowledge."
     [path]
     (let [content (host-store/file-store path)]
       (store/s-reset content)
       (rs/rc-put! (rs/file-root-cell path) nil)
       nil))
   :cljs
   (defn reset-store-dir! [_path] nil)
   :default
   (defn reset-store-dir!
     "Wipe content + root under path (demo --reset). No domain knowledge."
     [path]
     (let [content (host-store/file-store path)]
       (store/s-reset content)
       (rs/rc-put! (rs/file-root-cell path) nil)
       nil)))

(defn parse-args
  "CLI store options: [path] [--reset|-r]. Defaults path to default-path."
  [args]
  (let [args (->> args (map str) (remove #{"--"}))
        reset? (some #{"--reset" "-r"} args)
        path (or (first (remove #{"--reset" "-r"} args))
                 default-path)]
    {:reset? (boolean reset?)
     :path path}))

;; =============================================================================
;; Main (batch) — wires Store open to Values load-or-seed
;; =============================================================================

(defn -main [& args]
  (let [{:keys [reset? path]} (parse-args args)]
    (when reset?
      (reset-store-dir! path)
      (println "reset store at" path))
    (let [rs (open-store path)
          [todos seeded?] (load-or-seed! rs)]
      (println (if seeded?
                 (str "seeded new store at " path)
                 (str "loaded store at " path)))
      (println (render todos))
      (println "interactive (nbb): npm run todo"))))
