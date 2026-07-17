(ns dacite.examples.todo
  "A durable todo app on the portable Dacite surface.

   Domain + store lifecycle are host-agnostic (JVM / babashka / nbb).
   Interactive CLI with color and prompts lives in dacite.examples.todo-ui
   (nbb + npm packages: chalk, prompts).

   Uses:
     - dacite.value.api  — functional collection ops + get-value
     - dacite.rooted     — root / set-root! (portable fn API)
     - host file store   — JVM/bb: store.file; nbb: store.nbb
     - file-root-cell    — {base}/ROOT hex file

   Run (batch / non-interactive):
     npx nbb -m dacite.examples.todo
     bb todo
     bb todo /tmp/my-todos --reset

   Run (interactive, nbb — colored + toggleable):
     npm run todo
     npx nbb -m dacite.examples.todo-ui"
  (:require [dacite.store :as store]
            [dacite.rooted :as rs]
            [dacite.value.collections :as coll]
            [dacite.value.api :as d]
            [dacite.value.types :as types]
            [dacite.hash :as hash]
            #?@(:cljs [[dacite.store.nbb :as host-store]]
                :default [[dacite.store.file :as host-store]])))

(def default-path
  "target/dacite-todo")

;; =============================================================================
;; Domain
;; =============================================================================

(defn- todos-store
  "Content store carried by a todos value (never rely on *store* alone —
   async UIs drop dynamic bindings after the first await)."
  [todos]
  (types/dacite-store todos))

(defn todo-entry?
  "True if x looks like a {title, done} todo map value."
  [x]
  (and (d/dacite-value? x)
       (= "map" (d/value-type x))
       (some? (d/get x "title"))))

(defn add-todo
  "Return a new todos vector with {title, done} appended.

   New map nodes are written into the same store as `todos` (not whatever
   *store* happens to be bound — critical for nbb async prompts)."
  ([todos title] (add-todo todos title false))
  ([todos title done?]
   (let [st (todos-store todos)]
     (d/conj todos (coll/hash-map-with-store st "title" title "done" done?)))))

(defn- field-native
  "Native host value for map field k, or nil. Never throws on missing/corrupt."
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
  "The title of a todo entry as a native string."
  [todo]
  (if-let [v (field-native todo "title")]
    (if (string? v) v (apply str v))
    "<?>"))

(defn done?
  [todo]
  (boolean (field-native todo "done")))
(defn set-done
  "Return a new todo entry with done flag set."
  [todo done?]
  (d/assoc todo "done" done?))

(defn toggle-at
  "Toggle done flag of the todo at index i."
  [todos i]
  (let [t (d/nth todos i)]
    (if (todo-entry? t)
      (d/assoc todos i (set-done t (not (done? t))))
      todos)))

(defn remove-at
  "Remove the todo at index i (rebuild vector; portable API has no disj-by-index)."
  [todos i]
  (let [st (todos-store todos)
        n (d/count todos)]
    (loop [j 0
           acc (coll/vector-with-store st)]
      (if (>= j n)
        acc
        (recur (inc j)
               (if (= j i)
                 acc
                 (d/conj acc (d/nth todos j))))))))

(defn todo-count [todos]
  (d/count todos))

(defn todo-nth [todos i]
  (d/nth todos i))

(defn todo-seq [todos]
  (d/seq todos))

(defn todos-hash [todos]
  (d/dacite-hash todos))

(defn open-count
  "Number of incomplete todos."
  [todos]
  (count (filter (fn [t] (and (todo-entry? t) (not (done? t))))
                 (or (todo-seq todos) ()))))

(defn build
  "Build a todos vector in st from a seq of [title done?] pairs."
  ([items] (build store/*store* items))
  ([st items]
   (reduce (fn [todos [title done?]] (add-todo todos title done?))
           (coll/vector-with-store st)
           items)))

(defn render
  "A plain-text listing of the todos (no ANSI)."
  [todos]
  (str "todos (" (todo-count todos) ", "
       (open-count todos) " open):\n"
       (apply str
              (map-indexed
               (fn [i t]
                 (str "  " i ". [" (if (done? t) "x" " ") "] " (title-str t) "\n"))
               (todo-seq todos)))
       "root: " (hash/hash->hex (todos-hash todos))))

;; =============================================================================
;; Store lifecycle
;; =============================================================================

(defn open-store
  "Open a durable rooted store at path (content files + ROOT).

   Also installs the store as store/*store* for the process so constructors
   that use the dynamic var (and nbb async callbacks) write into the same
   durable store. Prefer value-local stores (add-todo) when possible."
  [path]
  (let [content (host-store/file-store path)
        cell (rs/file-root-cell path)
        rs (rs/rooted-store content cell)]
    (store/set-store! rs)
    rs))

(defn load-todos
  "Load the current todos vector from the rooted store, or nil."
  [rs]
  (when-let [h (rs/root rs)]
    (d/get-value rs h)))

(defn commit-todos!
  "Persist todos as the store root. Returns todos."
  [rs todos]
  (rs/set-root! rs (todos-hash todos))
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
  "Load todos from rs, or seed sample items and commit. Returns [todos seeded?]."
  [rs]
  (if-let [prior (load-todos rs)]
    [prior false]
    (let [t (build rs (seed-items))]
      (commit-todos! rs t)
      [t true])))
(defn reset-store-dir!
  "Wipe content + root under path (for --reset demos)."
  [path]
  (let [content (host-store/file-store path)]
    (store/s-reset content)
    (rs/rc-put! (rs/file-root-cell path) nil)
    nil))

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