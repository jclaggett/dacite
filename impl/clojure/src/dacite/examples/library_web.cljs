(ns dacite.examples.library-web
  "Browser library reader — Values vs Store kept separate.

   **Store** — HTTP remote + write-back cache, root get/CAS, bandwidth.
   **Values** — shelf / toc / page via dacite.examples.library.
   **UI** — DOM only.

   Compile from impl/clojure:
     clojure -M:cljs-library

   Serve:
     clojure -M:service
     open http://127.0.0.1:8080/app/library/"
  (:require [clojure.string :as str]
            [dacite.store.browser :as browser]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.examples.library :as lib]))

(defonce !state
  (atom {:store nil
         :library nil
         :root nil
         :error nil
         :status "loading"
         :view :shelf
         :book-i 0
         :chapter 0
         :page 0
         :page-size lib/default-page-size
         :shelf-start 0
         :bw-totals nil
         :bw-last nil
         :bw-last-label nil}))

(def shelf-page-size 32)

;; =============================================================================
;; Store
;; =============================================================================

(defn- api-base
  []
  (or (.-DACITE_API_BASE js/window) ""))

(defn open-store
  []
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
  "Load a library catalog from the server root, or CAS-seed one from nil.
   Does not overwrite a non-library root (todo, explorer gallery, …)."
  [st]
  (if-let [server-root (get-root st)]
    (if-let [val (v/get-value st server-root)]
      (if (lib/library-root? val)
        {:status :loaded :library val :root server-root :error nil}
        {:status :error
         :library nil
         :root server-root
         :error (str "Root is a " (v/type val)
                     ", not a library. Reset the service store or use a dedicated one.")})
      {:status :error
       :library nil
       :root server-root
       :error (str "Root present but value missing: "
                   (subs (store/hash->hex server-root) 0 12) "…")})
    (let [catalog (lib/seed-library st)
          h (v/hash catalog)]
      (if (cas-root! st nil h)
        {:status :seeded :library catalog :root h :error nil}
        (if-let [h2 (get-root st)]
          (let [val (v/get-value st h2)]
            (if (lib/library-root? val)
              {:status :loaded :library val :root h2 :error nil}
              {:status :error :library nil :root h2
               :error "Failed to seed; existing root is not a library"}))
          {:status :error :library nil :root nil :error "Failed to seed root"})))))

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

(defn- current-book
  [{:keys [library book-i]}]
  (when library
    (let [idx (lib/title-index library)
          n (v/count idx)]
      (when (and (pos? n) (<= 0 book-i) (< book-i n))
        (v/nth idx book-i)))))

(defn- render-status! []
  (let [{:keys [library root error status bw-totals bw-last bw-last-label view]} @!state
        root-hex (when root (store/hash->hex root))
        status-el (by-id "status")
        hash-el (by-id "root-hash")]
    (when status-el
      (set! (.-textContent status-el)
            (or error
                (str status
                     (when library
                       (str " · " (v/count (lib/title-index library)) " on the shelf"))
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
    (when-let [nav (by-id "crumb")]
      (set! (.-textContent nav)
            (case view
              :shelf "Shelf"
              :toc "Table of contents"
              :read "Reading"
              "")))))

(defn- render-shelf-html
  [library start]
  (let [idx (lib/title-index library)
        n (v/count idx)
        end (min n (+ start shelf-page-size))
        page (if (>= start n)
               (v/vector library)
               (lib/shelf library start end))
        items (map-indexed
               (fn [j b]
                 (let [i (+ start j)]
                   (str "<li><button type=\"button\" class=\"shelf-item\" data-action=\"open-book\" data-i=\""
                        i "\">"
                        "<span class=\"book-title\">" (escape-html (lib/book-title b)) "</span>"
                        "<span class=\"book-author\">" (escape-html (lib/book-author b)) "</span>"
                        "<span class=\"muted\">"
                        (v/count (lib/book-text b)) " chars · "
                        (v/count (lib/book-chapters b)) " chapters</span>"
                        "</button></li>")))
               (or (v/seq page) ()))
        more (when (< end n)
               (str "<button type=\"button\" class=\"secondary\" data-action=\"shelf-more\">"
                    "show next " (min shelf-page-size (- n end)) "</button>"))]
    (str "<ul class=\"shelf\">"
         (if (seq items)
           (.join (to-array items) "")
           "<li class=\"muted\">(empty shelf)</li>")
         "</ul>"
         (or more ""))))

(defn- render-toc-html
  [book]
  (let [ch (lib/book-chapters book)
        n (v/count ch)
        rows (map (fn [i]
                    (str "<li><button type=\"button\" class=\"toc-item\" data-action=\"open-chapter\" data-i=\""
                         i "\">"
                         (escape-html (lib/chapter-title book i))
                         "<span class=\"muted\"> @" (lib/chapter-start book i) "</span>"
                         "</button></li>"))
                  (range n))]
    (str "<p class=\"lead\">" (escape-html (lib/book-title book))
         "  — " (escape-html (lib/book-author book)) "</p>"
         "<p class=\"muted\">" (v/count (lib/book-text book)) " chars · "
         n " chapters</p>"
         "<p class=\"actions\">"
         "<button type=\"button\" class=\"secondary\" data-action=\"back-shelf\">Shelf</button>"
         "</p>"
         "<ol class=\"toc\">" (.join (to-array rows) "") "</ol>")))

(defn- render-read-html
  [book chapter page page-size]
  (let [nch (v/count (lib/book-chapters book))
        np (lib/pages-in-chapter book chapter page-size)
        body (lib/chapter-page book chapter page page-size)
        prev-ch? (pos? chapter)
        next-ch? (< (inc chapter) nch)
        prev-pg? (pos? page)
        next-pg? (< (inc page) np)]
    (str "<p class=\"lead\">" (escape-html (lib/book-title book)) "</p>"
         "<p class=\"muted\">" (escape-html (lib/chapter-title book chapter))
         " · page " (inc page) " of " np "</p>"
         "<p class=\"actions\">"
         "<button type=\"button\" class=\"secondary\" data-action=\"back-toc\">Contents</button>"
         "<button type=\"button\" class=\"secondary\" data-action=\"prev-page\""
         (when-not prev-pg? " disabled") ">Previous</button>"
         "<button type=\"button\" class=\"secondary\" data-action=\"next-page\""
         (when-not next-pg? " disabled") ">Next</button>"
         "<button type=\"button\" class=\"secondary\" data-action=\"prev-chapter\""
         (when-not prev-ch? " disabled") ">Prev chapter</button>"
         "<button type=\"button\" class=\"secondary\" data-action=\"next-chapter\""
         (when-not next-ch? " disabled") ">Next chapter</button>"
         "</p>"
         "<article class=\"page-body\">" (escape-html body) "</article>")))

(defn- render-view! []
  (render-status!)
  (let [{:keys [library error view chapter page page-size shelf-start] :as st} @!state]
    (cond
      error (set-html! "view" (str "<p class=\"error\">" (escape-html error) "</p>"))
      (nil? library) (set-html! "view" "<p class=\"muted\">(no catalog)</p>")
      :else
      (case view
        :shelf (set-html! "view" (render-shelf-html library shelf-start))
        :toc (if-let [book (current-book st)]
               (set-html! "view" (render-toc-html book))
               (set-html! "view" "<p class=\"error\">Book missing from the shelf.</p>"))
        :read (if-let [book (current-book st)]
                (set-html! "view" (render-read-html book chapter page page-size))
                (set-html! "view" "<p class=\"error\">Book missing from the shelf.</p>"))
        (set-html! "view" "<p class=\"muted\">unknown view</p>")))))

(defn- apply-value-result!
  [{:keys [status library root error]}]
  (swap! !state assoc
         :library library
         :root root
         :error error
         :status (name status)
         :view :shelf
         :book-i 0
         :chapter 0
         :page 0
         :shelf-start 0)
  (render-view!))

(defn- do-load-or-seed! []
  (with-bw "load/seed"
    (fn []
      (let [st (:store @!state)
            result (load-or-seed! st)]
        (apply-value-result! result)
        (:status result)))))

(defn- open-book! [i]
  (swap! !state assoc :view :toc :book-i i :chapter 0 :page 0)
  (with-bw "toc"
    (fn []
      (render-view!)
      true)))

(defn- open-chapter! [i]
  (swap! !state assoc :view :read :chapter i :page 0)
  (with-bw "page"
    (fn []
      (render-view!)
      true)))

(defn- go-page! [p]
  (swap! !state assoc :page p :view :read)
  (with-bw "page"
    (fn []
      (render-view!)
      true)))

(defn- go-chapter! [c]
  (swap! !state assoc :chapter c :page 0 :view :read)
  (with-bw "page"
    (fn []
      (render-view!)
      true)))

(defn- on-view-click! [e]
  (let [t (.-target e)
        btn (.closest t "button")
        action (when btn (.getAttribute btn "data-action"))]
    (when action
      (let [{:keys [chapter page page-size shelf-start] :as st} @!state
            book (current-book st)
            nch (when book (v/count (lib/book-chapters book)))
            np (when book (lib/pages-in-chapter book chapter page-size))]
        (case action
          "open-book" (open-book! (js/parseInt (.getAttribute btn "data-i") 10))
          "open-chapter" (open-chapter! (js/parseInt (.getAttribute btn "data-i") 10))
          "back-shelf" (do (swap! !state assoc :view :shelf)
                           (render-view!))
          "back-toc" (do (swap! !state assoc :view :toc)
                         (render-view!))
          "shelf-more" (do (swap! !state assoc :shelf-start (+ shelf-start shelf-page-size))
                           (render-view!))
          "prev-page" (when (pos? page) (go-page! (dec page)))
          "next-page" (when (and np (< (inc page) np)) (go-page! (inc page)))
          "prev-chapter" (when (pos? chapter) (go-chapter! (dec chapter)))
          "next-chapter" (when (and nch (< (inc chapter) nch)) (go-chapter! (inc chapter)))
          nil)))))

(defn ^:export init! []
  (let [st (open-store)]
    (reset-bw-stats!)
    (swap! !state assoc :store st :status "connecting"
           :bw-totals nil :bw-last nil :bw-last-label nil)
    (render-view!)
    (try
      (do-load-or-seed!)
      (catch :default e
        (swap! !state assoc :error (str "Load failed: " (.-message e))
               :status "error")
        (render-view!)))
    (when-let [view (by-id "view")]
      (.addEventListener view "click" on-view-click!))
    (when-let [rel (by-id "reload-btn")]
      (.addEventListener rel "click" (fn [_] (do-load-or-seed!))))))

(if (= "loading" (.-readyState js/document))
  (.addEventListener js/document "DOMContentLoaded" (fn [_] (init!)))
  (init!))
