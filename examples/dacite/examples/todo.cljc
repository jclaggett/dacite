(ns dacite.examples.todo
  "Durable todo app on the portable Dacite surface.

   App recipe (single-writer local store):
     1. open-store     — file content store + file root cell
     2. mutate         — build new values; always construct into the
                         receiver's store via *-with-store
     3. commit-todos!  — set-root! to the value's content hash

   Domain + lifecycle are host-agnostic (JVM / babashka / nbb).
   Interactive UI: dacite.examples.todo-ui (nbb + chalk + prompts).

   Run (batch):
     npx nbb -m dacite.examples.todo
     bb todo
     bb todo /tmp/my-todos --reset

   Run (interactive nbb):
     npm run todo"
  (:require [dacite.store :as store]
            [dacite.rooted :as rs]
            [dacite.host :as host]
            [dacite.value.collections :as coll]
            [dacite.value.api :as d]
            [dacite.value.types :as types]
            [dacite.hash :as hash]
            ;; nbb enables :org.babashka/nbb and :cljs; pure browser only :cljs.
            ;; Put nbb first so Node keeps the file store; browser pulls no host-store.
            #?@(:org.babashka/nbb [[dacite.store.nbb :as host-store]]
                :cljs []
                :default [[dacite.store.file :as host-store]])))

(def default-path
  "target/dacite-todo")

;; =============================================================================
;; Domain
;; =============================================================================

(def ^:dynamic *compact-todo-entries*
  "When true, each todo is one store node [\"todo-entry\" {:title s :done b}]
   instead of a Dacite map of string/bool leaves (fewer network nodes)."
  true)

(defn- todos-store
  "Store carried by a todos value. Prefer this over store/*store* so async
   UIs (which drop dynamic bindings) still write into the durable store."
  [todos]
  (types/dacite-store todos))

(declare ->TodoEntry)

(def ^:private todo-entry-type
  "Short type name for compact wire encoding."
  "te")

(deftype TodoEntry [store _hash]
  types/IDaciteValue
  (dacite-hash [_] _hash)
  (dacite-store [_] store)
  (dacite-type [_] todo-entry-type)
  (realize [_]
    (types/entry-data (store/s-get store _hash))))

(defmethod types/wrap-entry "te"
  [_type store h]
  (->TodoEntry store h))

(defmethod types/wrap-entry "todo-entry"
  [_type store h]
  (->TodoEntry store h))

(defmethod types/child-hashes "te"
  [_]
  [])

(defmethod types/child-hashes "todo-entry"
  [_]
  [])

(defn- put-todo-entry!
  "Persist a compact todo-entry node; return its hash.
   Payload is [title done?] (shorter than a keyword map on the wire)."
  [st title done?]
  (let [data [(str title) (boolean done?)]
        payload (host/utf8-bytes (pr-str data))
        h (types/value-hash todo-entry-type (hash/fuse-bytes payload))]
    (store/s-put st h [todo-entry-type data])
    h))

(defn- wrap-todo-entry [st h]
  (->TodoEntry st h))

(defn todo-entry?
  "True if x is a todo map or compact todo-entry."
  [x]
  (and (d/dacite-value? x)
       (or (and (= "map" (d/value-type x))
                (some? (d/get x "title")))
           (#{"te" "todo-entry"} (d/value-type x)))))

(defn add-todo
  "Append {title, done} to todos. New nodes go into todos' store."
  ([todos title] (add-todo todos title false))
  ([todos title done?]
   (let [st (todos-store todos)]
     (if *compact-todo-entries*
       (d/conj todos (wrap-todo-entry st (put-todo-entry! st title done?)))
       (d/conj todos (coll/hash-map-with-store st "title" title "done" done?))))))

(defn- field-native
  "Native host value for map field k, or nil."
  [todo k]
  (try
    (when (todo-entry? todo)
      (if (#{"te" "todo-entry"} (d/value-type todo))
        (let [data (types/realize todo)]
          (cond
            (vector? data)
            (case k "title" (nth data 0 nil) "done" (nth data 1 nil) nil)
            (map? data)
            (case k
              "title" (or (:t data) (:title data))
              "done" (if (contains? data :d) (:d data) (:done data))
              nil)
            :else nil))
        (let [v (d/get todo k)]
          (cond
            (nil? v) nil
            (d/dacite-value? v) (types/realize v)
            :else v))))
    (catch #?(:clj Throwable :cljs :default) _
      nil)))

(defn- compact-title [data]
  (cond
    (vector? data) (nth data 0 nil)
    (map? data) (or (:t data) (:title data))
    :else nil))

(defn- compact-done [data]
  (cond
    (vector? data) (nth data 1 nil)
    (map? data) (if (contains? data :d) (:d data) (:done data))
    :else nil))

(defn title-str
  "Title of a todo entry as a native string."
  [todo]
  (try
    (when-not (todo-entry? todo)
      (throw (ex-info "not a todo entry" {:type (when (d/dacite-value? todo)
                                                  (d/value-type todo))})))
    (if (#{"te" "todo-entry"} (d/value-type todo))
      (or (compact-title (types/realize todo)) "<?no-title?>")
      (let [v (d/get todo "title")]
        (cond
          (nil? v) "<?no-title?>"
          (not (d/dacite-value? v)) (str v)
          :else
          (let [r (types/realize v)]
            (cond
              (string? r) r
              (nil? r) "<?empty?>"
              :else (apply str r))))))
    (catch #?(:clj Throwable :cljs :default) e
      (str "<?" #?(:clj (.getMessage e) :cljs (.-message e)) "?>"))))

(defn done?
  [todo]
  (boolean (field-native todo "done")))

(defn set-done
  "Return a new todo entry with done flag set."
  [todo done?]
  (if (#{"te" "todo-entry"} (d/value-type todo))
    (let [st (types/dacite-store todo)
          data (types/realize todo)
          title (compact-title data)]
      (wrap-todo-entry st (put-todo-entry! st title done?)))
    (d/assoc todo "done" done?)))

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

(defn build
  "Build a todos vector in st from a seq of [title done?] pairs."
  [st items]
  (reduce (fn [todos [title done?]] (add-todo todos title done?))
          (coll/vector-with-store st)
          items))

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
       "root: " (hash/hash->hex (d/dacite-hash todos))))

;; =============================================================================
;; Store lifecycle
;; =============================================================================

#?(:org.babashka/nbb
   (defn open-store
     "Open a durable rooted store at path (Node file store + ROOT)."
     [path]
     (rs/rooted-store (host-store/file-store path)
                      (rs/file-root-cell path)))
   :cljs
   (defn open-store
     "Not available in the browser — use HTTP remote store (todo-web)."
     [_path]
     (throw (js/Error. "todo/open-store is for JVM/nbb file backends only")))
   :default
   (defn open-store
     "Open a durable rooted store at path (sharded .edn content + ROOT file).

   Does not touch store/*store*. All construction uses an explicit store
   (build) or the store on existing values (add-todo / remove-at)."
     [path]
     (rs/rooted-store (host-store/file-store path)
                      (rs/file-root-cell path))))

#?(:org.babashka/nbb
   (defn reset-store-dir!
     "Wipe content + root under path (for --reset demos)."
     [path]
     (let [content (host-store/file-store path)]
       (store/s-reset content)
       (rs/rc-put! (rs/file-root-cell path) nil)
       nil))
   :cljs
   (defn reset-store-dir! [_path] nil)
   :default
   (defn reset-store-dir!
     "Wipe content + root under path (for --reset demos)."
     [path]
     (let [content (host-store/file-store path)]
       (store/s-reset content)
       (rs/rc-put! (rs/file-root-cell path) nil)
       nil)))

(defn load-todos
  "Current todos vector from the rooted store, or nil if root unset."
  [rs]
  (when-let [h (rs/root rs)]
    (d/get-value rs h)))

(defn commit-todos!
  "Persist todos as the store root (single-writer: set-root!).

   Multi-writer / remote: prefer cas-root! or update-root! instead."
  [rs todos]
  (rs/set-root! rs (d/dacite-hash todos))
  todos)

(defn seed-items
  "Initial sample list for a fresh store."
  []
  [["write portable host layer" true]
   ["split the store" true]
   ["run under babashka" false]
   ["run under nbb" false]
   ["durable todo root" false]])

(defn load-or-seed!
  "Load todos from rs, or seed samples and commit. Returns [todos seeded?]."
  [rs]
  (if-let [prior (load-todos rs)]
    [prior false]
    (let [t (build rs (seed-items))]
      (commit-todos! rs t)
      [t true])))

;; =============================================================================
;; Main (batch — all hosts)
;; =============================================================================

(defn parse-args
  "Parse [path] [--reset|-r] from CLI args."
  [args]
  (let [args (->> args (map str) (remove #{"--"}))
        reset? (some #{"--reset" "-r"} args)
        path (or (first (remove #{"--reset" "-r"} args))
                 default-path)]
    {:reset? (boolean reset?)
     :path path}))

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
