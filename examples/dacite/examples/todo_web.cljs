(ns dacite.examples.todo-web
  "Browser todo UI over Dacite values + HTTP store (service.md protocol).

   Compile from impl/clojure:
     clojure -M:cljs-web

   Serve:
     clojure -M:service"
  (:require [clojure.string :as str]
            [dacite.store.browser :as remote]
            [dacite.value.api :as d]
            [dacite.value.types :as types]
            [dacite.value.collections :as coll]
            [dacite.examples.todo :as todo]
            [dacite.hash :as hash]))

(defonce !state
  (atom {:remote nil
         :todos nil
         :root nil
         :error nil
         :status "loading"}))

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

(defn- render-list! []
  (let [{:keys [todos root error status]} @!state
        root-hex (when root (hash/hash->hex root))
        status-el (by-id "status")]
    (when status-el
      (set! (.-textContent status-el)
            (or error
                (str status
                     (when root-hex (str " · root " (subs root-hex 0 12) "…"))
                     (when todos (str " · " (d/count todos) " items"))))))
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
                   (or (d/seq todos) ()))]
        (set-html! "todo-list" (apply str items))))))

(defn- commit!
  [todos]
  (let [{:keys [remote root]} @!state
        new-h (types/dacite-hash todos)]
    (if (remote/remote-cas-root! remote root new-h)
      (do (swap! !state assoc :todos todos :root new-h :error nil :status "saved")
          (render-list!)
          true)
      (do (swap! !state assoc :error "CAS conflict — reload and retry" :status "conflict")
          (render-list!)
          false))))

(defn- load-or-seed! []
  (let [remote (:remote @!state)
        server-root (remote/remote-get-root remote)]
    (if server-root
      (let [todos (d/get-value remote server-root)]
        (swap! !state assoc :todos todos :root server-root :status "loaded" :error nil)
        (render-list!))
      (let [seeded (todo/build remote (todo/seed-items))
            h (types/dacite-hash seeded)]
        (if (remote/remote-cas-root! remote nil h)
          (do (swap! !state assoc :todos seeded :root h :status "seeded" :error nil)
              (render-list!))
          (do (swap! !state assoc :error "Failed to seed root" :status "error")
              (render-list!)))))))

(defn- on-add! []
  (let [input (by-id "new-title")
        title (when input (str/trim (.-value input)))]
    (when (and title (seq title) (:todos @!state))
      (let [todos' (todo/add-todo (:todos @!state) title false)]
        (when (commit! todos')
          (set! (.-value input) ""))))))

(defn- on-toggle! [i]
  (when-let [todos (:todos @!state)]
    (commit! (todo/toggle-at todos i))))

(defn- on-remove! [i]
  (when-let [todos (:todos @!state)]
    (commit! (todo/remove-at todos i))))

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
  (let [base (or (.-DACITE_API_BASE js/window) "")
        remote (remote/remote-store base)]
    (swap! !state assoc :remote remote :status "connecting")
    (render-list!)
    (try
      (load-or-seed!)
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
      (.addEventListener rel "click" (fn [_] (load-or-seed!))))))

;; Auto-init when loaded as a script after DOM is ready
(if (= "loading" (.-readyState js/document))
  (.addEventListener js/document "DOMContentLoaded" (fn [_] (init!)))
  (init!))
