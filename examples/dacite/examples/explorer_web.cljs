(ns dacite.examples.explorer-web
  "Browser value explorer — Values vs Store kept separate.

   **Store** — HTTP base URL, client-cache policy, root get/CAS, bandwidth.
   **Values** — gallery seed, row-summary, child-page (dacite.examples.explorer).
   **UI** — DOM tree of type+value rows.

   Compile from impl/clojure:
     clojure -M:cljs-explorer

   Serve:
     clojure -M:service
     open http://127.0.0.1:8080/app/explorer/"
  (:require [clojure.string :as str]
            [dacite.store.browser :as browser]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.examples.explorer :as ex]))

(defonce !state
  (atom {:store nil
         :value nil
         :root nil
         :error nil
         :status "loading"
         :expanded #{[]}
         :shown {}
         :bw-totals nil
         :bw-last nil
         :bw-last-label nil}))

;; =============================================================================
;; Store
;; =============================================================================

(defn- api-base
  []
  (or (.-DACITE_API_BASE js/window) ""))

(defn open-store
  []
  ;; Same HTTP path as the todo demo. Pack GET prefers realized literals
  ;; (soft budget 1024): one request is *data*. apply-chunk! installs
  ;; ordinary nodes in the tab's mem cache; the explorer walks *values*
  ;; there.
  (browser/cached-remote-store (api-base) {:policy :write-back}))

(defn get-root
  [st]
  (browser/remote-get-root st))

(defn cas-root!
  [st expected new-hash]
  (browser/remote-cas-root! st expected new-hash))

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
;; =============================================================================

(defn load-or-seed!
  "If server root is set, load it; else CAS the type gallery from nil.
   Returns {:status :loaded|:seeded|:error :value :root :error}."
  [st]
  (if-let [server-root (get-root st)]
    (if-let [val (v/get-value st server-root)]
      {:status :loaded :value val :root server-root :error nil}
      {:status :error
       :value nil
       :root server-root
       :error (str "Root present but value missing: "
                   (subs (store/hash->hex server-root) 0 12) "…")})
    (let [g (ex/gallery-via st)
          h (v/dacite-hash g)]
      (if (cas-root! st nil h)
        {:status :seeded :value g :root h :error nil}
        (if-let [h2 (get-root st)]
          {:status :loaded :value (v/get-value st h2) :root h2 :error nil}
          {:status :error :value nil :root nil :error "Failed to seed root"})))))

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

(defn- encode-step [step]
  (let [[op i] step]
    (str (case op :nth "n" :map-key "k" :map-val "v" "?")
         ":" i)))

(defn- encode-path [path]
  (if (seq path)
    (str/join "/" (map encode-step path))
    ""))

(defn- decode-step [s]
  (let [[op i] (str/split s #":" 2)
        n (js/parseInt i 10)]
    [(case op "n" :nth "k" :map-key "v" :map-val :nth) n]))

(defn- decode-path [s]
  (if (or (nil? s) (= "" s))
    []
    (mapv decode-step (str/split s #"/"))))

(defn- join-html
  "Concatenate HTML fragments without `apply str` (arg-count limit)."
  [xs]
  (.join (to-array xs) ""))

(defn- shown-count [shown path]
  (get shown path ex/page-size))

(defn- summary-text [row]
  (case (:kind row)
    :scalar (:native row)
    :string (str "\"" (:preview row)
                 (when (:truncated? row) "…")
                 "\" (" (:count row) " chars)")
    :blob (str (:count row) " bytes"
               (when (seq (:preview row))
                 (str " 0x" (:preview row)
                      (when (:truncated? row) " …"))))
    (str (:count row))))

(defn- render-row-html
  [row path expanded]
  (let [kind (:kind row)
        open? (contains? expanded path)
        twist (cond
                (contains? #{:vector :map :set} kind)
                (str "<button type=\"button\" class=\"twistie\" data-action=\"toggle\" data-path=\""
                     (escape-html (encode-path path)) "\">"
                     (if open? "▾" "▸") "</button>")
                :else
                "<span class=\"twistie leaf\">•</span>")]
    (str "<div class=\"tree-row\">"
         twist
         "<span class=\"type-badge\">" (escape-html (:type row)) "</span>"
         (when (contains? #{:vector :map :set} kind)
           (str "<span class=\"count\">" (:count row) "</span>"))
         (when (contains? #{:scalar :string :blob} kind)
           (str "<span class=\"" (if (= :scalar kind) "native" "preview") "\">"
                (escape-html (summary-text row)) "</span>"))
         (when (and (:truncated? row) (contains? #{:string :blob} kind))
           "<span class=\"truncated\">truncated</span>")
         "</div>")))

(defn- render-node
  [value path expanded shown]
  (try
    (let [row (ex/row-summary value)
          open? (and (ex/expandable? value) (contains? expanded path))
          head (render-row-html row path expanded)]
      (if-not open?
        (str "<div class=\"tree-node\">" head "</div>")
        (let [limit (shown-count shown path)
              {:keys [items done? total]} (ex/child-page value 0 limit)
              kids
              (case (:kind row)
                :vector
                (join-html (map (fn [{:keys [label value]}]
                                  (let [p (conj path [:nth label])]
                                    (str "<div class=\"indexed\">"
                                         "<span class=\"idx\">" label "</span>"
                                         (render-node value p expanded shown)
                                         "</div>")))
                                items))
                :set
                (join-html (map-indexed
                            (fn [i {:keys [value]}]
                              (render-node value (conj path [:nth i]) expanded shown))
                            items))
                :map
                (join-html (map-indexed
                            (fn [i {:keys [label value]}]
                              (let [kp (conj path [:map-key i])
                                    vp (conj path [:map-val i])]
                                (str "<div class=\"map-entry\">"
                                     (render-node label kp expanded shown)
                                     "<span class=\"count\">→</span>"
                                     (render-node value vp expanded shown)
                                     "</div>")))
                            items))
                "")
              more (when-not done?
                     (str "<button type=\"button\" class=\"more secondary\" data-action=\"more\" data-path=\""
                          (escape-html (encode-path path)) "\">show next "
                          (min ex/page-size (- total limit))
                          "</button>"))]
          (str "<div class=\"tree-node\">" head
               "<div class=\"tree-children\">" kids more "</div></div>"))))
    (catch :default e
      (str "<div class=\"tree-node\"><div class=\"tree-row error\">"
           (escape-html (or (.-message e) (str e)))
           "</div></div>"))))

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

(defn- render-tree! []
  (let [{:keys [value root error status bw-totals bw-last bw-last-label
                expanded shown]} @!state
        root-hex (when root (store/hash->hex root))
        status-el (by-id "status")
        hash-el (by-id "root-hash")]
    (when status-el
      (set! (.-textContent status-el)
            (or error
                (str status
                     (when value (str " · " (v/value-type value)))
                     (when root-hex (str " · " (subs root-hex 0 12) "…"))))))
    (when hash-el
      (set! (.-textContent hash-el)
            (if root-hex (str "hash " root-hex) "")))
    (when-let [el (by-id "bandwidth")]
      (if bw-totals
        (set! (.-textContent el)
              (str "bw · "
                   (format-bw-stats bw-totals)
                   (when bw-last
                     (str " · last " (format-bw-delta bw-last bw-last-label)))))
        (set! (.-textContent el) "bw · (no store traffic yet)")))
    (cond
      error (set-html! "tree" (str "<p class=\"error\">" (escape-html error) "</p>"))
      (nil? value) (set-html! "tree" "<p class=\"muted\">(no root)</p>")
      :else (set-html! "tree" (render-node value [] expanded shown)))))

(defn- apply-value-result!
  [{:keys [status value root error]}]
  (swap! !state assoc
         :value value
         :root root
         :error error
         :status (name status)
         :expanded #{[]}
         :shown {})
  (render-tree!))

(defn- do-load-or-seed! []
  (with-bw "load/seed"
    (fn []
      (let [st (:store @!state)
            result (load-or-seed! st)]
        (apply-value-result! result)
        (:status result)))))

(defn- on-toggle! [path]
  (swap! !state update :expanded
         (fn [exs]
           (if (contains? exs path)
             (disj exs path)
             (conj exs path))))
  (with-bw "expand"
    (fn []
      (render-tree!)
      true)))

(defn- on-more! [path]
  (swap! !state update-in [:shown path] (fnil + ex/page-size) ex/page-size)
  (with-bw "more"
    (fn []
      (render-tree!)
      true)))

(defn- on-tree-click! [e]
  (let [t (.-target e)
        action (.getAttribute t "data-action")
        p-str (.getAttribute t "data-path")]
    (when action
      (let [path (decode-path p-str)]
        (case action
          "toggle" (on-toggle! path)
          "more" (on-more! path)
          nil)))))

(defn ^:export init! []
  (let [st (open-store)]
    (reset-bw-stats!)
    (swap! !state assoc :store st :status "connecting"
           :bw-totals nil :bw-last nil :bw-last-label nil)
    (render-tree!)
    (try
      (do-load-or-seed!)
      (catch :default e
        (swap! !state assoc :error (str "Load failed: " (.-message e))
               :status "error")
        (render-tree!)))
    (when-let [tree (by-id "tree")]
      (.addEventListener tree "click" on-tree-click!))
    (when-let [rel (by-id "reload-btn")]
      (.addEventListener rel "click" (fn [_] (do-load-or-seed!))))))

(if (= "loading" (.-readyState js/document))
  (.addEventListener js/document "DOMContentLoaded" (fn [_] (init!)))
  (init!))
