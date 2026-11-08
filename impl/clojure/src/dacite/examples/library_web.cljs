(ns dacite.examples.library-web
  "Browser library reader — Values vs Store kept separate.

   **Store** — HTTP remote + write-back cache, root get/CAS, bandwidth.
   **Peer** — the same catalog from an nbb peer over the slice-3 room,
   falling back to that HTTP origin when the peer does not deliver in time.
   **Values** — shelf / toc / page via dacite.examples.library.
   **UI** — DOM only.

   Compile from impl/clojure:
     clojure -M:cljs-library

   Serve:
     clojure -M:service
     open http://127.0.0.1:8080/app/library/

   Peer-assisted (M5.3), origin and nbb peer already holding the catalog:
     http://127.0.0.1:8080/app/library/?chapter=0&signal=ws://127.0.0.1:8787&room=TOKEN

   `chapter` opens that chapter of the first book and its plate. That is
   the measured read. `peer-timeout` (milliseconds) replaces the wait."
  (:require [clojure.string :as str]
            [dacite.examples.library :as lib]
            [dacite.peer.client :as client]
            [dacite.peer.link :as link]
            [dacite.peer.transport :as t]
            [dacite.peer.transport.browser :as rtc]
            [dacite.store :as store]
            [dacite.store.browser :as browser]
            [dacite.store.chain :as chain]
            [dacite.value :as v]))

(defonce !state
  (atom {:store nil
         :library nil
         :root nil
         :error nil
         :status "loading"
         :source nil
         :view :shelf
         :book-i 0
         :chapter 0
         :page 0
         :page-size lib/default-page-size
         :shelf-start 0
         :bw-totals nil
         :bw-last nil
         :bw-last-label nil
         :peer-bytes 0
         :peer-gets 0
         :peer-frames 0}))

(def shelf-page-size 32)

(def ^:private default-peer-timeout-ms
  "Wait for the nbb peer, handshake included, after the origin has named
   the root. Then read that root from the origin."
  15000)

(defonce ^:private t-read (atom 0))

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

(defn format-bw-stats [totals]
  (browser/format-stats totals))

(defn format-bw-delta [delta label]
  (browser/format-delta delta label))

;; =============================================================================
;; Values
;; =============================================================================

(defn load-or-seed!
  "Load a library catalog from `server-root`, or CAS-seed one from nil.
   The caller resolves `server-root` once per `drive`.
   Does not overwrite a non-library root (todo, explorer gallery, …)."
  [st server-root]
  (if server-root
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

(declare render-view!)

(defn- with-bw
  "Drive `f` over fetch and note bandwidth. Returns a promise.
   A cache miss suspends inside `f`; the tab is not blocked."
  [label f]
  (let [before (browser/get-stats)]
    (-> (browser/drive f)
        (.then (fn [result]
                 (let [after (browser/get-stats)]
                   (note-bw! {:delta (browser/stats-diff before after)
                              :totals after}
                             label))
                 result))
        (.catch (fn [e]
                  (let [msg (str label " failed: "
                                 (or (ex-message e) (.-message e) e))]
                    (js/console.error msg e)
                    (swap! !state assoc :error msg :status "error")
                    (try
                      (render-view!)
                      (catch :default render-e
                        (js/console.error render-e)))
                    nil))))))

(defn- current-book
  [{:keys [library book-i]}]
  (when library
    (let [idx (lib/title-index library)
          n (v/count idx)]
      (when (and (pos? n) (<= 0 book-i) (< book-i n))
        (v/nth idx book-i)))))

(defn- render-status! []
  (let [{:keys [library root error status source bw-totals bw-last bw-last-label view]} @!state
        root-hex (when root (store/hash->hex root))
        status-el (by-id "status")
        hash-el (by-id "root-hash")]
    (when status-el
      (set! (.-textContent status-el)
            (or error
                (str status
                     (when source (str " · " source))
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

(defn- byte-len [bs]
  (cond
    (nil? bs) 0
    (instance? js/Uint8Array bs) (.-length bs)
    :else (count bs)))

(defn- as-u8 [bs]
  (if (instance? js/Uint8Array bs)
    bs
    (let [n (count bs)
          out (js/Uint8Array. n)]
      (dotimes [i n]
        (aset out i (bit-and (nth bs i) 255)))
      out)))

(defn- b64 [bs]
  (let [u8 (as-u8 bs)
        n (.-length u8)
        chars (js/Array. n)]
    (dotimes [i n]
      (aset chars i (.fromCharCode js/String (aget u8 i))))
    (js/btoa (.join chars ""))))

(defn- plate-html
  "The book's PNG, or an empty string when it has no plate."
  [book]
  (if-let [img (lib/book-image book)]
    (let [bs (v/as-bytes img)
          n (byte-len bs)]
      (str "<figure class=\"plate\">"
           "<img id=\"plate\" alt=\"Catalog plate\" width=\"16\" height=\"16\" data-bytes=\""
           n "\" src=\"data:image/png;base64," (b64 bs) "\"/>"
           "<figcaption class=\"muted\">plate · " n " bytes</figcaption>"
           "</figure>"))
    ""))

(defn- render-read-html
  [book chapter page page-size]
  (let [nch (v/count (lib/book-chapters book))
        np (lib/pages-in-chapter book chapter page-size)
        body (lib/chapter-page book chapter page page-size)
        plate (plate-html book)
        prev-ch? (pos? chapter)
        next-ch? (< (inc chapter) nch)
        prev-pg? (pos? page)
        next-pg? (< (inc page) np)]
    (str "<p class=\"lead\">" (escape-html (lib/book-title book)) "</p>"
         "<p class=\"muted\">" (escape-html (lib/chapter-title book chapter))
         " · page " (inc page) " of " np "</p>"
         plate
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

(defn- set-attr! [id k v]
  (when-let [el (by-id id)]
    (.setAttribute el k (str v))))

(defn- query-param
  "A non-empty query value, or nil. `URLSearchParams.get` returns
   JavaScript null when the key is absent, and null is truthy here."
  [k]
  (let [v (.get (js/URLSearchParams. (.-search js/location)) k)]
    (when (and (string? v) (pos? (count (str/trim v))))
      (str/trim v))))

(defn- parse-nat
  "A non-negative integer parsed from `s`, or nil. Zero is a value."
  [s]
  (when (string? s)
    (let [n (js/parseInt s 10)]
      (when-not (js/isNaN n)
        (when (>= n 0) n)))))

(defn- measured-chapter
  "Chapter index from the `chapter` query, or nil when this load is the shelf."
  []
  (parse-nat (query-param "chapter")))

(defn- peer-query
  "The M5.3 room, or nil when this load does not ask a peer."
  []
  (let [signal (query-param "signal")
        room (query-param "room")]
    (when (and signal room)
      {:signal signal :room room
       :timeout (or (parse-nat (query-param "peer-timeout"))
                    default-peer-timeout-ms)})))

(defn- mark-rendered!
  "Record the read that put the chapter and the plate on the page.
   Round trips are origin store-protocol requests plus peer `get`s.
   Bytes from the origin are response bodies of those requests. Bytes
   from the peer are `dacite-peer` messages received. Time to first
   render is `performance.now` (milliseconds since navigation)."
  [source]
  (let [stats (browser/get-stats)
        origin-requests (:requests stats 0)
        origin-bytes (:bytes-recv stats 0)
        {:keys [peer-bytes peer-gets peer-frames root]} @!state
        trips (+ origin-requests peer-gets)
        now (js/performance.now)
        ttf (js/Math.round now)
        read-ms (js/Math.round (- now @t-read))
        img (by-id "plate")
        image-bytes (if img (or (.getAttribute img "data-bytes") "0") "0")]
    (set-attr! "readout" "data-status" "rendered")
    (set-attr! "readout" "data-mode" source)
    (set-attr! "readout" "data-round-trips" trips)
    (set-attr! "readout" "data-origin-requests" origin-requests)
    (set-attr! "readout" "data-gets" peer-gets)
    (set-attr! "readout" "data-frames" peer-frames)
    (set-attr! "readout" "data-bytes-peer" peer-bytes)
    (set-attr! "readout" "data-bytes-origin" origin-bytes)
    (set-attr! "readout" "data-ttf-ms" ttf)
    (set-attr! "readout" "data-read-ms" read-ms)
    (set-attr! "readout" "data-image-bytes" image-bytes)
    (when root
      (set-attr! "readout" "data-root" (store/hash->hex root)))
    (when-let [el (by-id "readout")]
      (set! (.-textContent el)
            (str source
                 " · " trips " round trips"
                 " · peer " peer-bytes " B"
                 " · origin " origin-bytes " B"
                 " · " ttf " ms")))
    (js/console.log "rendered" source trips peer-bytes origin-bytes ttf)))

(defn- apply-value-result!
  [{:keys [status library root error]} source]
  (let [ch (measured-chapter)]
    (swap! !state assoc
           :library library
           :root root
           :error error
           :status (name status)
           :source source
           :view (if (and library (some? ch) (nil? error)) :read :shelf)
           :book-i 0
           :chapter (or ch 0)
           :page 0
           :shelf-start 0))
  (render-view!)
  (when (and library (nil? error) (some? (measured-chapter)))
    (mark-rendered! source)))

(defn- do-load-or-seed! []
  (let [phase (atom :decide)
        seen (atom nil)
        source (:source @!state "origin")]
    (with-bw "load/seed"
      (fn []
        (let [st (:store @!state)]
          (when (= :decide @phase)
            (reset! seen (get-root st))
            (reset! phase :apply))
          (let [result (load-or-seed! st @seen)]
            (apply-value-result! result source)
            (:status result)))))))

(defn- on-view-click! [e]
  (let [t (.-target e)
        btn (.closest t "button")
        action (when btn (.getAttribute btn "data-action"))]
    (when action
      (let [applied (atom false)
            label (case action
                    ("open-book" "back-toc") "toc"
                    ("back-shelf" "shelf-more") "shelf"
                    "page")]
        (with-bw label
          (fn []
            (when-not @applied
              (let [{:keys [chapter page page-size shelf-start] :as st} @!state
                    book (current-book st)
                    nch (when book (v/count (lib/book-chapters book)))
                    np (when book (lib/pages-in-chapter book chapter page-size))
                    i (js/parseInt (.getAttribute btn "data-i") 10)]
                (case action
                  "open-book" (swap! !state assoc :view :toc :book-i i :chapter 0 :page 0)
                  "open-chapter" (swap! !state assoc :view :read :chapter i :page 0)
                  "back-shelf" (swap! !state assoc :view :shelf)
                  "back-toc" (swap! !state assoc :view :toc)
                  "shelf-more" (swap! !state assoc :shelf-start (+ shelf-start shelf-page-size))
                  "prev-page" (when (pos? page)
                                (swap! !state assoc :page (dec page) :view :read))
                  "next-page" (when (and np (< (inc page) np))
                                (swap! !state assoc :page (inc page) :view :read))
                  "prev-chapter" (when (pos? chapter)
                                   (swap! !state assoc :chapter (dec chapter) :page 0 :view :read))
                  "next-chapter" (when (and nch (< (inc chapter) nch))
                                   (swap! !state assoc :chapter (inc chapter) :page 0 :view :read))
                  nil)
                (reset! applied true)))
            (render-view!)
            true))))))

;; =============================================================================
;; Peer, then the origin
;; =============================================================================

(defn- counting-channel
  "An `IChannel` that counts messages and bytes received, then forwards."
  [ch recv frames]
  (reify t/IChannel
    (channel-name [_] (t/channel-name ch))
    (send! [_ bs] (t/send! ch bs))
    (on-message! [_ f]
      (t/on-message! ch (fn [bs]
                          (swap! recv + (.-length bs))
                          (swap! frames inc)
                          (f bs))))
    (on-close! [_ f] (t/on-close! ch f))
    (buffered-amount [_] (t/buffered-amount ch))
    (max-message-size [_] (t/max-message-size ch))
    (max-frame-limit [_] (t/max-frame-limit ch))
    (channel-open? [_] (t/channel-open? ch))
    (close! [_] (t/close! ch))))

(defn- attach-counted! [links c conn recv frames]
  (let [opened (t/open-channel! conn t/peer-channel)
        attach (fn [ch]
                 (link/attach-client! links c "nbb"
                                      (counting-channel ch recv frames)
                                      :offerer))]
    (if (instance? js/Promise opened)
      (.then opened attach)
      (js/Promise.resolve (attach opened)))))

(defn- poll! [pred ms limit]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(step [i]
               (try
                 (cond
                   (pred) (resolve i)
                   (>= i limit)
                   (reject (ex-info "timed out" {:reason :timeout :steps i}))
                   :else (js/setTimeout #(step (inc i)) ms))
                 (catch :default e (reject e))))]
       (step 0)))))

(defn- err-text [e]
  (let [reason (:reason (ex-data e))
        msg (or (ex-message e) (.-message e) (str e))]
    (if reason (str msg " (" (name reason) ")") msg)))

(defn- stop-peer! [timer peer c]
  (when timer (js/clearInterval timer))
  (try (when c (client/close-session! c "nbb")) (catch :default _ nil))
  (try
    (when (and peer (:conn peer) (t/connection-open? (:conn peer)))
      (t/close-connection! (:conn peer)))
    (catch :default _ nil)))

(defn- read-root [st]
  (browser/drive #(get-root st)))

(defn- fail-readout! [msg]
  (set-attr! "readout" "data-status" "failed")
  (set-attr! "readout" "data-mode" (or (:source @!state) ""))
  (when-let [el (by-id "readout")]
    (set! (.-textContent el) msg))
  (swap! !state assoc :error msg :status "error")
  (try (render-view!) (catch :default e (js/console.error e))))

(defn- read-from-peer! [st {:keys [signal room timeout]}]
  (let [mem (store/mem-store)
        links (link/links)
        base (link/client-opts links)
        gets (atom 0)
        recv (atom 0)
        frames (atom 0)
        send! (fn [peer m]
                (when (= :get (:type m)) (swap! gets inc))
                ((:send! base) peer m))
        c (client/client (merge {:store mem :now #(.now js/Date)}
                                base
                                {:send! send!}))
        timer (js/setInterval #(client/tick! c) 25)
        ;; The offer starts now, so the handshake overlaps the root GET.
        ;; The timeout below starts once that hash is known.
        peer (rtc/create-peer :offerer {:ice-servers []
                                        :ws {:url signal :room room}})
        settled (atom false)
        remember! (fn []
                    (swap! !state assoc
                           :peer-bytes @recv
                           :peer-gets @gets
                           :peer-frames @frames))
        fallback! (fn [why]
                    (when (compare-and-set! settled false true)
                      (js/console.log "peer fallback" (str why))
                      (stop-peer! timer peer c)
                      (remember!)
                      (swap! !state assoc :source "origin-fallback"
                             :status "reading from the origin")
                      (render-status!)
                      (do-load-or-seed!)))
        succeed! (fn [root-hash]
                   (when (compare-and-set! settled false true)
                     (stop-peer! timer peer c)
                     (remember!)
                     (try
                       (let [catalog (v/get-value mem root-hash)]
                         (if (lib/library-root? catalog)
                           (apply-value-result! {:status :loaded
                                                 :library catalog
                                                 :root root-hash
                                                 :error nil}
                                                "peer")
                           (do (reset! settled false)
                               (fallback! "peer value is not a library"))))
                       (catch :default e
                         (reset! settled false)
                         (fallback! (err-text e))))))]
    (swap! !state assoc :status "reading the root" :source "peer")
    (render-status!)
    (-> (read-root st)
        (.then (fn [root-hash]
                 (if (nil? root-hash)
                   (fallback! "origin has no root")
                   (let [steps (max 1 (quot timeout 25))
                         delivery
                         (-> (:signaling peer)
                             (.then (fn [_]
                                      (when @settled
                                        (throw (ex-info "stopped" {:reason :stopped})))
                                      (attach-counted! links c (:conn peer) recv frames)))
                             (.then (fn [_]
                                      (poll! (fn []
                                               (when @settled
                                                 (throw (ex-info "stopped" {:reason :stopped})))
                                               (when (= :ended (client/session-status c "nbb"))
                                                 (throw (ex-info "the session ended"
                                                                 {:reason :session-ended})))
                                               (some? (get-in @(:state c)
                                                              [:sessions "nbb" :hello])))
                                             25 steps)))
                             (.then (fn [_]
                                      (chain/native (client/request! c root-hash))))
                             (.then (fn [_]
                                      (poll! (fn []
                                               (when @settled
                                                 (throw (ex-info "stopped" {:reason :stopped})))
                                               (when (= :ended (client/session-status c "nbb"))
                                                 (throw (ex-info "the session ended"
                                                                 {:reason :session-ended})))
                                               (client/done? c root-hash))
                                             25 steps)))
                             (.then (fn [_] root-hash))
                             (.catch (fn [e]
                                       (when-not @settled
                                         (fallback! (err-text e)))
                                       :timeout)))
                         clock (js/Promise.
                                (fn [resolve]
                                  (js/setTimeout #(resolve :timeout) timeout)))]
                     (swap! !state assoc :status "asking the peer")
                     (render-status!)
                     (-> (js/Promise.race #js [delivery clock])
                         (.then (fn [v]
                                  (cond
                                    @settled nil
                                    (= v :timeout) (fallback! "timed out")
                                    :else (succeed! v)))))))))
        (.catch (fn [e]
                  (if @settled
                    nil
                    (fail-readout! (str "failed: " (err-text e)))))))))

(defn ^:export init! []
  (let [st (open-store)
        q (peer-query)]
    (reset! t-read (js/performance.now))
    (reset-bw-stats!)
    (swap! !state assoc :store st :status "connecting" :source (if q "peer" "origin")
           :bw-totals nil :bw-last nil :bw-last-label nil
           :peer-bytes 0 :peer-gets 0 :peer-frames 0)
    (render-view!)
    (if q
      (read-from-peer! st q)
      (do-load-or-seed!))
    (when-let [view (by-id "view")]
      (.addEventListener view "click" on-view-click!))
    (when-let [rel (by-id "reload-btn")]
      (.addEventListener rel "click"
                         (fn [_]
                           (swap! !state assoc :source "origin")
                           (do-load-or-seed!))))))

(defonce started? (atom false))

(when (compare-and-set! started? false true)
  (if (= "loading" (.-readyState js/document))
    (.addEventListener js/document "DOMContentLoaded" (fn [_] (init!)))
    (init!)))
