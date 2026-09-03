(ns dacite.examples.todo-web
  "Browser todo UI — Values vs Store kept separate (same split as todo.cljc).

   **Store** — HTTP base URL, client-cache policy, root get/CAS helpers.
   **Values** — seed/load/commit/mutate via dacite.examples.todo; store only
   as a parameter (or the store carried by values).
   **UI** — DOM only; calls Values and Store helpers.

   Compile from impl/clojure:
     clojure -M:cljs-web

   Serve:
     clojure -M:service"
  (:require [clojure.string :as str]
            [dacite.store.browser :as browser]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.examples.todo :as todo]))

(defonce !state
  (atom {:store nil
         :todos nil
         :root nil
         :error nil
         :status "loading"
         :bw-totals nil
         :bw-last nil
         :bw-last-label nil}))

;; =============================================================================
;; Store
;;
;; Transport and performance only: API base, write-back cache, root pointer
;; ops, bandwidth stats. No seed titles, todo maps, or list mutations.
;; =============================================================================

(defn- api-base
  "HTTP origin for the content-store service (empty = same origin)."
  []
  (or (.-DACITE_API_BASE js/window) ""))

(def ^:private client-policy
  "Client-cache policy for the demo remote (see dacite.store.client-cache)."
  :write-back)

(defn open-store
  "HTTP remote store + client-cache. Soft pack budget is pack/default-budget
   (1024) inside the pack layer — not configured here."
  []
  (browser/cached-remote-store (api-base) {:policy client-policy}))

(defn get-root
  "Current server root hash, or nil."
  [store]
  (browser/remote-get-root store))

(defn cas-root!
  "Compare-and-set root. Returns true on success."
  [store expected new-hash]
  (browser/remote-cas-root! store expected new-hash))

(defn reset-bw-stats! []
  (browser/reset-stats!))

(defn measure-bw [f]
  (browser/measure f))

(defn format-bw-stats [totals]
  (browser/format-stats totals))

(defn format-bw-delta [delta label]
  (browser/format-delta delta label))

;; =============================================================================
;; Values
;;
;; Domain + root value load/commit. `store` is only a parameter; policy/URL
;; live in the Store section above.
;; =============================================================================

(defn load-todos
  "Materialize todos at root-hash from store content."
  [store root-hash]
  (todo/load-todos store root-hash))

(defn commit-todos!
  "CAS root from `expected-root` to the hash of `todos`.
   Returns new root hash on success, nil on conflict."
  [store expected-root todos]
  (let [new-h (todo/todos-hash todos)]
    (when (cas-root! store expected-root new-h)
      new-h)))

(defn load-or-seed!
  "If server root is set, load todos; else build seed items and CAS root.
   Returns {:status :loaded|:seeded|:error :todos :root :error}."
  [store]
  (if-let [server-root (get-root store)]
    (if-let [todos (load-todos store server-root)]
      {:status :loaded :todos todos :root server-root :error nil}
      {:status :error
       :todos nil
       :root server-root
       :error (str "Root present but value missing/unloadable: "
                   (subs (store/hash->hex server-root) 0 12) "…")})
    (let [seeded (todo/build store (todo/seed-items))
          h (todo/todos-hash seeded)]
      (if (cas-root! store nil h)
        {:status :seeded :todos seeded :root h :error nil}
        {:status :error :todos nil :root nil :error "Failed to seed root"}))))

;; =============================================================================
;; UI
;; =============================================================================

(defn- by-id [id]
  (.getElementById js/document id))

(defn- set-html! [id html]
  (when-let [n (by-id id)]
    (set! (.-innerHTML n) html)))

(defn- escape-html [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- note-bw!
  [{:keys [delta totals]} label]
  (swap! !state assoc
         :bw-totals totals
         :bw-last delta
         :bw-last-label label)
  (when-let [el (by-id "bandwidth")]
    (set! (.-textContent el)
          (str "bw · "
               (format-bw-stats totals)
               (when delta
                 (str " · last " (format-bw-delta delta label)))))))

(defn- with-bw
  [label f]
  (let [m (measure-bw f)]
    (note-bw! m label)
    (:result m)))

(defn- render-list! []
  (let [{:keys [todos root error status bw-totals bw-last bw-last-label]} @!state
        root-hex (when root (store/hash->hex root))
        status-el (by-id "status")]
    (when status-el
      (set! (.-textContent status-el)
            (or error
                (str status
                     (when root-hex (str " · root " (subs root-hex 0 12) "…"))
                     (when todos (str " · " (v/count todos) " items"))))))
    (when-let [el (by-id "bandwidth")]
      (if bw-totals
        (set! (.-textContent el)
              (str "bw · "
                   (format-bw-stats bw-totals)
                   (when bw-last
                     (str " · last " (format-bw-delta bw-last bw-last-label)))))
        (set! (.-textContent el) "bw · (no store traffic yet)")))
    (if-not todos
      (set-html! "todo-list" "<li class=\"muted\">(no todos loaded)</li>")
      (let [items (map-indexed
                   (fn [i t]
                     (let [done? (todo/done? t)
                           title (escape-html (todo/title-str t))]
                       (str "<li class=\"" (if done? "done" "open") "\">"
                            "<label>"
                            "<input type=\"checkbox\" data-action=\"toggle\" data-i=\"" i "\""
                            (when done? " checked") "/> "
                            "<span class=\"title\">" title "</span>"
                            "</label>"
                            "<button type=\"button\" data-action=\"remove\" data-i=\"" i "\">×</button>"
                            "</li>")))
                   (or (v/seq todos) ()))]
        (set-html! "todo-list" (apply str items))))))

(defn- apply-value-result!
  "Update UI state from a Values load/seed or successful commit."
  [{:keys [status todos root error]}]
  (swap! !state assoc
         :todos todos
         :root root
         :error error
         :status (name status))
  (render-list!))

(defn- persist!
  "Commit todos via CAS; update state on success."
  [todos]
  (let [{:keys [store root]} @!state
        new-h (commit-todos! store root todos)]
    (if new-h
      (do (swap! !state assoc :todos todos :root new-h :error nil :status "saved")
          (render-list!)
          true)
      (do (swap! !state assoc :error "CAS conflict — reload and retry" :status "conflict")
          (render-list!)
          false))))

(defn- with-ui-error
  "Run f; on throw, show the message in the status line (Add used to fail silently)."
  [label f]
  (try
    (f)
    (catch :default e
      (let [msg (str label " failed: " (or (.-message e) e))]
        (js/console.error msg e)
        (swap! !state assoc :error msg :status "error")
        (render-list!)
        nil))))

(defn- do-load-or-seed! []
  (with-bw "load/seed"
    (fn []
      (let [store (:store @!state)
            result (load-or-seed! store)]
        (apply-value-result! result)
        (:status result)))))

(defn- on-add! []
  (let [input (by-id "new-title")
        title (when input (str/trim (.-value input)))]
    (when (and title (seq title) (:todos @!state))
      (with-ui-error "Add"
        (fn []
          (swap! !state assoc :status "saving" :error nil)
          (render-list!)
          (with-bw "add"
            (fn []
              (let [todos' (todo/add-todo (:todos @!state) title false)
                    ok (persist! todos')]
                (when ok
                  (set! (.-value input) ""))
                ok))))))))

(defn- on-toggle! [i]
  (when-let [todos (:todos @!state)]
    (with-ui-error "Toggle"
      (fn []
        (with-bw "toggle"
          (fn []
            (persist! (todo/toggle-at todos i))))))))

(defn- on-remove! [i]
  (when-let [todos (:todos @!state)]
    (with-ui-error "Remove"
      (fn []
        (with-bw "remove"
          (fn []
            (persist! (todo/remove-at todos i))))))))

(defn- on-list-click! [e]
  (let [t (.-target e)
        action (.getAttribute t "data-action")
        i-str (.getAttribute t "data-i")
        i (when i-str (js/parseInt i-str 10))]
    (when (and action (not (js/isNaN i)))
      (case action
        "toggle" (on-toggle! i)
        "remove" (on-remove! i)
        nil))))

(defn ^:export init! []
  (let [store (open-store)]
    (reset-bw-stats!)
    (swap! !state assoc :store store :status "connecting"
           :bw-totals nil :bw-last nil :bw-last-label nil)
    (render-list!)
    (try
      (do-load-or-seed!)
      (catch :default e
        (swap! !state assoc :error (str "Load failed: " (.-message e)) :status "error")
        (render-list!)))
    (when-let [btn (by-id "add-btn")]
      (.addEventListener btn "click" (fn [_] (on-add!))))
    (when-let [input (by-id "new-title")]
      (.addEventListener input "keydown"
                         (fn [e]
                           (when (= "Enter" (.-key e))
                             (.preventDefault e)
                             (on-add!)))))
    (when-let [ul (by-id "todo-list")]
      (.addEventListener ul "click" on-list-click!))
    (when-let [rel (by-id "reload-btn")]
      (.addEventListener rel "click" (fn [_] (do-load-or-seed!))))))

(if (= "loading" (.-readyState js/document))
  (.addEventListener js/document "DOMContentLoaded" (fn [_] (init!)))
  (init!))
