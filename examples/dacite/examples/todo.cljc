(ns dacite.examples.todo
  "A tiny durable todo app on the portable Dacite surface.

   Uses:
     - dacite.value.api  — functional collection ops + get-value
     - dacite.rooted     — root / set-root! (portable fn API)
     - host file store   — JVM/bb: store.file; nbb: store.nbb
     - file-root-cell    — {base}/ROOT hex file (same on both hosts)

   The same source runs on the JVM, babashka, and nbb. First run seeds
   sample todos and commits the root; later runs resume and apply one
   demo mutation so durability is visible.

   Run:
     npx nbb -m dacite.examples.todo
     npx nbb -m dacite.examples.todo -- --reset
     bb todo
     clojure -Sdeps '{:paths [\"impl/clojure/src\" \"examples\"]}' -M -m dacite.examples.todo"
  (:require [dacite.store :as store]
            [dacite.rooted :as rs]
            [dacite.value.collections :as coll]
            [dacite.value.api :as d]
            [dacite.value.types :as types]
            [dacite.hash :as hash]
            #?@(:cljs [[dacite.store.nbb :as host-store]]
                :default [[dacite.store.file :as host-store]])))

(def ^:private default-path
  "target/dacite-todo")

;; =============================================================================
;; Domain
;; =============================================================================

(defn add-todo
  "Return a new todos vector with {title, done} appended."
  [todos title done?]
  (d/conj todos (coll/hash-map "title" title "done" done?)))

(defn title-str
  "The title of a todo entry as a native string."
  [todo]
  (apply str (types/realize (d/get todo "title"))))

(defn done?
  [todo]
  (boolean (types/realize (d/get todo "done"))))

(defn set-done
  "Return a new todo entry with done flag set."
  [todo done?]
  (d/assoc todo "done" done?))

(defn toggle-first-open
  "Toggle the first incomplete todo to done; if all done, leave as-is."
  [todos]
  (let [n (d/count todos)]
    (loop [i 0]
      (if (>= i n)
        todos
        (let [t (d/nth todos i)]
          (if (done? t)
            (recur (inc i))
            (d/assoc todos i (set-done t true))))))))

(defn build
  "Build a todos vector in the bound store from a seq of [title done?] pairs."
  [items]
  (reduce (fn [todos [title done?]] (add-todo todos title done?))
          (coll/vector)
          items))

(defn render
  "A plain-text listing of the todos."
  [todos]
  (str "todos (" (d/count todos) "):\n"
       (apply str
              (map (fn [t]
                     (str "  [" (if (done? t) "x" " ") "] " (title-str t) "\n"))
                   (d/seq todos)))
       "root: " (hash/hash->hex (d/dacite-hash todos))))

;; =============================================================================
;; Store lifecycle
;; =============================================================================

(defn open-store
  "Open a durable rooted store at path (content files + ROOT)."
  [path]
  (let [content (host-store/file-store path)
        cell (rs/file-root-cell path)]
    (rs/rooted-store content cell)))

(defn load-todos
  "Load the current todos vector from the rooted store, or nil."
  [rs]
  (when-let [h (rs/root rs)]
    (d/get-value rs h)))

(defn commit-todos!
  "Persist todos as the store root. Returns todos."
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

(defn reset-store-dir!
  "Wipe content + root under path (for --reset demos)."
  [path]
  (let [content (host-store/file-store path)]
    (store/s-reset content)
    (rs/rc-put! (rs/file-root-cell path) nil)
    nil))

;; =============================================================================
;; Main
;; =============================================================================

(defn- parse-args [args]
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
    (let [rs (open-store path)]
      (store/bind-store rs
                        (let [prior (load-todos rs)
                              todos (if prior
                                      (let [t' (toggle-first-open prior)]
                                        (println "resumed from" path)
                                        (commit-todos! rs t')
                                        t')
                                      (let [t (build (seed-items))]
                                        (println "seeded new store at" path)
                                        (commit-todos! rs t)
                                        t))]
                          (println (render todos))
                          (println "(re-run to resume; pass --reset to wipe)"))))))
