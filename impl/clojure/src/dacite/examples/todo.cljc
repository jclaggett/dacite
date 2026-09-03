(ns dacite.examples.todo
  "Portable todo demo — two concerns, deliberately separated:

   **Values** — domain ops, seed data, root swap/CAS. Uses only
   `dacite.value` (no IStore plumbing in domain fns).

   **Store** — open and configure the durable (or remote) store: path, reset,
   host backend. No todo shape, seed data, or domain mutations.

   Local single-writer recipe:
     1. (open-store path)              ; Store section
     2. (def todos (v/root rs))    ; Value section
     3. (load-or-seed! todos)
     4. (v/swap! todos add-todo \"milk\")

   Hosts: JVM / babashka / nbb (file store). Browser uses todo-web with an
   HTTP store (same Values API, different Store wiring).

   Run (batch):
     npx nbb -m dacite.examples.todo
     bb todo
     bb todo /tmp/my-todos --reset

   Run (interactive nbb):
     npm run todo"
  (:require [dacite.store :as store]
            [dacite.value :as v]))

;; =============================================================================
;; Values
;;
;; Todo list is a Dacite vector of maps {"title" string, "done" bool}.
;; Domain ops take/return values; new nodes go into the peer value's store.
;; Root access uses `v/root`.
;; =============================================================================

(defn todo-entry?
  "True if x is a todo map (has a \"title\" field)."
  [x]
  (and (v/dacite-value? x)
       (= "map" (v/type x))
       (some? (v/get x "title"))))

(defn add-todo
  "Append {title, done} to todos. New nodes go into todos' store."
  ([todos title] (add-todo todos title false))
  ([todos title done?]
   (v/conj todos (v/map todos "title" title "done" done?))))

(defn title-str
  "Title of a todo entry as a native string."
  [todo]
  (if-not (todo-entry? todo)
    (throw (ex-info "not a todo entry" {:type (when (v/dacite-value? todo)
                                                (v/type todo))}))
    (or (v/native (v/get todo "title")) "<?no-title?>")))

(defn done?
  [todo]
  (boolean (v/native (v/get todo "done"))))

(defn set-done
  "Return a new todo entry with done flag set."
  [todo done?]
  (v/assoc todo "done" done?))

(defn toggle-at
  "Toggle done at index i."
  [todos i]
  (let [t (v/nth todos i)]
    (if (todo-entry? t)
      (v/assoc todos i (set-done t (not (done? t))))
      todos)))

(defn remove-at
  "Remove index i from the todos vector."
  [todos i]
  (v/remove-nth todos i))

(defn open-count
  "Number of incomplete todos."
  [todos]
  (count (filter (fn [t] (and (todo-entry? t) (not (done? t))))
                 (or (v/seq todos) ()))))

(defn seed-items
  "Initial sample list shape: seq of [title done?] pairs (not store-specific)."
  []
  [["write portable host layer" true]
   ["split the store" true]
   ["run under babashka" false]
   ["run under nbb" false]
   ["durable todo root" false]])

(defn build
  "Build a todos vector relative to `peer` (store, root, or value)
   from a seq of [title done?] pairs."
  [peer items]
  (reduce (fn [todos [title done?]] (add-todo todos title done?))
          (v/vector peer)
          items))

(defn empty-todos
  "Empty todos vector allocated relative to `peer`."
  [peer]
  (v/vector peer))

(defn load-todos
  "Materialize the todos value at `root-hash` from `store`, or nil.
   Used by remote/web wiring that coordinates root hashes separately."
  [st root-hash]
  (when root-hash
    (v/get-value st root-hash)))

(defn todos-hash
  "Content hash of a todos value (for root pointers / CAS)."
  [todos]
  (v/hash todos))

(defn load-or-seed!
  "Load todos from a root, or seed sample data and commit.

   `todos-ref` is a `v/root`. Returns [todos seeded?]."
  [todos-ref]
  (if-let [prior (v/deref todos-ref)]
    [prior false]
    (let [t (build todos-ref (seed-items))]
      (v/cas! todos-ref nil t)
      [t true])))

(defn commit-todos!
  "Single-writer: CAS-install `todos` as the root. Returns todos."
  [todos-ref todos]
  (v/swap! todos-ref (fn [_] todos))
  todos)

(defn render
  "Plain-text listing (no ANSI)."
  [todos]
  (str "todos (" (v/count todos) ", "
       (open-count todos) " open):\n"
       (apply str
              (map-indexed
               (fn [i t]
                 (str "  " i ". [" (if (done? t) "x" " ") "] " (title-str t) "\n"))
               (v/seq todos)))
       "root: " (store/hash->hex (v/hash todos))))

;; =============================================================================
;; Store
;;
;; File-backed content + root cell. No todo maps, seed lists, or domain ops.
;; =============================================================================

(def default-path
  "Default directory for sharded content + ROOT file."
  "target/dacite-todo")

(defn open-store
  "Open a durable rooted file store at path. Browser todo uses todo-web."
  [path]
  (store/file path))

(defn reset-store-dir!
  "Wipe content + root under path (demo --reset)."
  [path]
  (store/file path {:reset true})
  nil)

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
          todos-ref (v/root rs)
          [todos seeded?] (load-or-seed! todos-ref)]
      (println (if seeded?
                 (str "seeded new store at " path)
                 (str "loaded store at " path)))
      (println (render todos))
      (println "interactive (nbb): npm run todo"))))
