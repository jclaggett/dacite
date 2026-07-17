(ns dacite.examples.todo-ui
  "Interactive durable todo CLI for nbb.

   Uses Node libraries (chalk for color, prompts for keyboard menus) on top
   of the portable dacite.examples.todo domain + file-backed rooted store.

   Run from repo root (after npm install):
     npm run todo
     npx nbb -m dacite.examples.todo-ui
     npx nbb -m dacite.examples.todo-ui -- /tmp/my-todos"
  (:require [clojure.string :as str]
            [dacite.examples.todo :as todo]
            [dacite.hash :as hash]
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
    (let [n (todo/todo-count todos)
          open (todo/open-count todos)
          root-hex (hash/hash->hex (todo/todos-hash todos))]
      (println)
      (println (c "bold.white" "Dacite todos")
               (c "dim" (str "  " path)))
      (println (c "dim" (str n " items · " open " open · root "
                             (subs root-hex 0 12) "…")))
      (println)
      (if (zero? n)
        (println (c "yellow" "  (empty — add a todo)"))
        (doseq [[i t] (map-indexed vector (todo/todo-seq todos))]
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
        (todo/todo-seq todos)))

(defn- action-toggle!
  [rs todos]
  (if (zero? (todo/todo-count todos))
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
          (todo/commit-todos! rs t')
          (println (c "green" (str "Toggled #" index)))
          t')))))

(defn- action-add!
  [rs todos]
  (p/let [{:keys [title]}
          (ask {:type "text"
                :name "title"
                :message "New todo title"
                :validate (fn [v]
                            (if (str/blank? (str v))
                              "Title required"
                              true))})]
    (let [title (some-> title str str/trim)]
      (if (str/blank? title)
        todos
        (try
          (let [t' (todo/add-todo todos title false)]
            (todo/commit-todos! rs t')
            (println (c "green" (str "Added: " title)))
            t')
          (catch :default e
            (println (c "red" (str "Add failed: " (.-message e))))
            todos))))))
(defn- action-remove!
  [rs todos]
  (if (zero? (todo/todo-count todos))
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
                                    (todo/title-str (todo/todo-nth todos index))
                                    "\"?")
                      :initial false})]
          (if ok
            (let [t' (todo/remove-at todos index)]
              (todo/commit-todos! rs t')
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
  [rs path todos]
  (print-list! path todos)
  (p/loop [todos todos]
    (p/let [{:keys [action]} (action-menu)]
      (case action
        "quit"
        (do (println (c "dim" "Saved. Bye."))
            (println (c "dim" (str "root " (hash/hash->hex (todo/todos-hash todos)))))
            nil)

        "refresh"
        (do (print-list! path todos)
            (p/recur todos))

        "toggle"
        (p/let [t' (action-toggle! rs todos)]
          (print-list! path t')
          (p/recur t'))

        "add"
        (p/let [t' (action-add! rs todos)]
          (print-list! path t')
          (p/recur t'))

        "remove"
        (p/let [t' (action-remove! rs todos)]
          (print-list! path t')
          (p/recur t'))

        ;; cancelled (Ctrl-C / Esc often yields empty)
        (do (println (c "dim" "Cancelled."))
            nil)))))

(defn -main [& args]
  (let [{:keys [reset? path]} (todo/parse-args args)]
    (when reset?
      (todo/reset-store-dir! path)
      (println (c "yellow" (str "reset store at " path))))
    ;; open-store sets store/*store* for the process — needed because
    ;; prompts are async and dynamic bind-store does not survive await.
    (let [rs (todo/open-store path)
          [todos seeded?] (todo/load-or-seed! rs)]
      (when seeded?
        (println (c "green" (str "seeded new store at " path))))
      (-> (run-loop! rs path todos)
          (.catch (fn [e]
                    (when-not (= (.-message e) "cancelled")
                      (js/console.error e))
                    nil))))))