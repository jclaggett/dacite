(ns dacite.examples.todo-ui
  "Interactive durable todo CLI for nbb.

   Wires **Store** (path / open-store) to **Values** (load-or-seed, domain
   ops, commit-todos!) from dacite.examples.todo. UI only — no pack budgets
   or seed data here.

   Run from repo root (after npm install):
     npm run todo
     npx nbb -m dacite.examples.todo-ui
     npx nbb -m dacite.examples.todo-ui -- /tmp/my-todos"
  (:require [clojure.string :as str]
            [dacite.examples.todo :as todo]
            [dacite.store :as store]
            [dacite.value :as v]
            [promesa.core :as p]))

;; ---------------------------------------------------------------------------
;; Node deps (CommonJS — chalk@4 and prompts work with js/require under nbb)
;; ---------------------------------------------------------------------------

(def ^:private chalk (js/require "chalk"))
(def ^:private prompts (js/require "prompts"))

(defn- c
  "Call a chalk style chain. style is a string like \"green\" or \"dim.strikethrough\"."
  [style s]
  (let [fn-or-obj (reduce (fn [obj part]
                            (aget obj part))
                          chalk
                          (str/split (name style) #"\."))]
    (fn-or-obj (str s))))

(defn- ask
  "Run a prompts.js questionnaire; returns a promise of a cljs map."
  [questions]
  (p/let [res (prompts (clj->js (if (map? questions) [questions] questions)
                                :keyword-fn name))]
    (js->clj res :keywordize-keys true)))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- line-for
  [i t]
  (try
    (let [done? (todo/done? t)
          mark (if done? "x" " ")
          title (todo/title-str t)
          body (str "  " i ". [" mark "] " title)]
      (if done?
        (c "dim.green" body)
        (c "cyan" body)))
    (catch :default e
      (c "red" (str "  " i ". [!] (unreadable entry: " (.-message e) ")")))))

(defn- print-list!
  [path todos]
  (try
    (let [n (v/count todos)
          open (todo/open-count todos)
          root-hex (store/hash->hex (v/hash todos))]
      (println)
      (println (c "bold.white" "Dacite todos")
               (c "dim" (str "  " path)))
      (println (c "dim" (str n " items · " open " open · root "
                             (subs root-hex 0 12) "…")))
      (println)
      (if (zero? n)
        (println (c "yellow" "  (empty — add a todo)"))
        (doseq [[i t] (map-indexed vector (v/seq todos))]
          (println (line-for i t))))
      (println))
    (catch :default e
      (println (c "red" (str "Failed to render list: " (.-message e)))))))

;; ---------------------------------------------------------------------------
;; Actions (each returns updated todos; already committed when needed)
;; ---------------------------------------------------------------------------

(defn- choices-for-todos
  [todos]
  (into []
        (map-indexed
         (fn [i t]
           {:title (str (if (todo/done? t) "[x] " "[ ] ") (todo/title-str t))
            :value i}))
        (v/seq todos)))

(defn- action-toggle!
  [todos-ref todos]
  (if (zero? (v/count todos))
    (do (println (c "yellow" "Nothing to toggle."))
        (p/resolved todos))
    (p/let [{:keys [index]}
            (ask {:type "select"
                  :name "index"
                  :message "Toggle which todo?"
                  :choices (choices-for-todos todos)})]
      (if (nil? index)
        todos
        (let [t' (todo/toggle-at todos index)]
          (todo/commit-todos! todos-ref t')
          (println (c "green" (str "Toggled #" index)))
          t')))))

(defn- action-add!
  [todos-ref todos]
  (p/let [{:keys [title]}
          (ask {:type "text"
                :name "title"
                :message "New todo title"
                :validate (fn [x]
                            (if (str/blank? (str x))
                              "Title required"
                              true))})]
    (let [title (some-> title str str/trim)]
      (if (str/blank? title)
        todos
        (try
          (let [t' (todo/add-todo todos title false)]
            (todo/commit-todos! todos-ref t')
            (println (c "green" (str "Added: " title)))
            t')
          (catch :default e
            (println (c "red" (str "Add failed: " (.-message e))))
            todos))))))

(defn- action-remove!
  [todos-ref todos]
  (if (zero? (v/count todos))
    (do (println (c "yellow" "Nothing to remove."))
        (p/resolved todos))
    (p/let [{:keys [index]}
            (ask {:type "select"
                  :name "index"
                  :message "Remove which todo?"
                  :choices (choices-for-todos todos)})]
      (if (nil? index)
        todos
        (p/let [{:keys [ok]}
                (ask {:type "confirm"
                      :name "ok"
                      :message (str "Delete \""
                                    (todo/title-str (v/nth todos index))
                                    "\"?")
                      :initial false})]
          (if ok
            (let [t' (todo/remove-at todos index)]
              (todo/commit-todos! todos-ref t')
              (println (c "red" (str "Removed #" index)))
              t')
            todos))))))

(defn- action-menu
  []
  (ask {:type "select"
        :name "action"
        :message "What next?"
        :choices [{:title "Toggle a todo" :value "toggle"}
                  {:title "Add a todo" :value "add"}
                  {:title "Remove a todo" :value "remove"}
                  {:title "Refresh list" :value "refresh"}
                  {:title "Quit" :value "quit"}]}))

;; ---------------------------------------------------------------------------
;; Main loop
;; ---------------------------------------------------------------------------

(defn- run-loop!
  [todos-ref path todos]
  (print-list! path todos)
  (p/loop [todos todos]
    (p/let [{:keys [action]} (action-menu)]
      (case action
        "quit"
        (do (println (c "dim" "Saved. Bye."))
            (println (c "dim" (str "root " (store/hash->hex (v/hash todos)))))
            nil)

        "refresh"
        (do (print-list! path todos)
            (p/recur todos))

        "toggle"
        (p/let [t' (action-toggle! todos-ref todos)]
          (print-list! path t')
          (p/recur t'))

        "add"
        (p/let [t' (action-add! todos-ref todos)]
          (print-list! path t')
          (p/recur t'))

        "remove"
        (p/let [t' (action-remove! todos-ref todos)]
          (print-list! path t')
          (p/recur t'))

        ;; cancelled (Ctrl-C / Esc often yields empty)
        (do (println (c "dim" "Cancelled."))
            nil)))))

(defn -main [& args]
  ;; Store: path + open/reset only
  (let [{:keys [reset? path]} (todo/parse-args args)]
    (when reset?
      (todo/reset-store-dir! path)
      (println (c "yellow" (str "reset store at " path))))
    (let [rs (todo/open-store path)
          todos-ref (v/root rs)
          [todos seeded?] (todo/load-or-seed! todos-ref)]
      (when seeded?
        (println (c "green" (str "seeded new store at " path))))
      (-> (run-loop! todos-ref path todos)
          (.catch (fn [e]
                    (when-not (= (.-message e) "cancelled")
                      (js/console.error e))
                    nil))))))
