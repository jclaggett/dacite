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
  "Store carried by a todos value. Prefer this over store/*store* so async
   UIs (which drop dynamic bindings) still write into the durable store."
  [todos]
  (types/dacite-store todos))

(defn todo-entry?
  "True if x looks like a {title, done} todo map."
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
  "Toggle done at index i."
  [todos i]
  (let [t (d/nth todos i)]
    (if (todo-entry? t)
      (d/assoc todos i (set-done t (not (done? t))))
      todos)))

(defn remove-at
  "Remove index i.

   Library gap: value.api has no remove-nth yet, so rebuild the vector
   in the same store."
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

(defn open-store
  "Open a durable rooted store at path (sharded .edn content + ROOT file).

   Does not touch store/*store*. All construction uses an explicit store
   (build) or the store on existing values (add-todo / remove-at)."
  [path]
  (rs/rooted-store (host-store/file-store path)
                   (rs/file-root-cell path)))

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
