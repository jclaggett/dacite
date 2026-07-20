(ns dacite.store.browser
  "Browser remote IStore using synchronous XHR (demo only).

   Same protocol as docs/design/service.md and dacite.store.remote:
   GET/PUT/HEAD/DELETE /node/{hex}, GET /root, POST /root/cas.

   Sync XHR keeps IStore blocking so Dacite value ops work unchanged in the
   browser. Not for production (main-thread blocking).

   Bandwidth: every store-protocol XHR records request/response body sizes
   (string length ≈ UTF-8 bytes for ASCII EDN). Static /app assets are not
   counted. See get-stats / measure / format-stats."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.wire :as wire]))

;; =============================================================================
;; Bandwidth accounting (store protocol only)
;; =============================================================================

(defn empty-stats
  "Zeroed counters for store-protocol XHR bodies."
  []
  {:requests 0
   :bytes-sent 0
   :bytes-recv 0
   :by-kind {}})

(defonce ^:private !stats (atom (empty-stats)))

(defn- body-size
  "Approx wire size of a body string (JS string length; fine for ASCII EDN)."
  [s]
  (if (nil? s)
    0
    (count (str s))))

(defn- classify-url
  "Keyword for by-kind bucket from method + URL path."
  [method url]
  (let [path (or (second (re-find #"(?:https?://[^/]+)?(/[^?]*)" (str url)))
                 (str url))
        m (str/upper-case (str method))]
    (cond
      (str/includes? path "/root/cas") :root-cas
      (str/includes? path "/root") :root-get
      (str/includes? path "/node/")
      (case m
        "GET" :node-get
        "PUT" :node-put
        "HEAD" :node-head
        "DELETE" :node-delete
        :node-other)
      :else :other)))

(defn- record-xhr!
  [method url req-body resp-body]
  (let [sent (body-size req-body)
        recv (body-size resp-body)
        kind (classify-url method url)]
    (swap! !stats
           (fn [s]
             (-> s
                 (update :requests inc)
                 (update :bytes-sent + sent)
                 (update :bytes-recv + recv)
                 (update-in [:by-kind kind] (fnil + 0) 1))))
    nil))

(defn get-stats
  "Snapshot of cumulative store-protocol bandwidth stats."
  []
  @!stats)

(defn reset-stats!
  "Clear cumulative stats (e.g. for demos/tests)."
  []
  (reset! !stats (empty-stats)))

(defn- kind-diff
  [before-kinds after-kinds]
  (let [keys* (into (set (keys after-kinds)) (keys before-kinds))]
    (reduce (fn [m k]
              (let [d (- (get after-kinds k 0) (get before-kinds k 0))]
                (if (pos? d) (assoc m k d) m)))
            {}
            keys*)))

(defn stats-diff
  "Delta between two stats snapshots (after − before)."
  [before after]
  {:requests (- (:requests after 0) (:requests before 0))
   :bytes-sent (- (:bytes-sent after 0) (:bytes-sent before 0))
   :bytes-recv (- (:bytes-recv after 0) (:bytes-recv before 0))
   :by-kind (kind-diff (:by-kind before {}) (:by-kind after {}))})

(defn measure
  "Run f, return {:result … :delta stats-diff :totals snapshot}."
  [f]
  (let [before (get-stats)
        result (f)
        after (get-stats)]
    {:result result
     :delta (stats-diff before after)
     :totals after}))

(defn format-bytes
  "Human-readable size (B or KB)."
  [n]
  (let [n (long (or n 0))]
    (if (< n 1024)
      (str n " B")
      (str (.toFixed (/ n 1024.0) 1) " KB"))))

(defn format-stats
  "One-line summary: req · ↑ · ↓ · Σ."
  ([stats] (format-stats stats nil))
  ([stats label]
   (let [up (:bytes-sent stats 0)
         down (:bytes-recv stats 0)
         total (+ up down)
         base (str (:requests stats 0) " req · ↑ " (format-bytes up)
                   " · ↓ " (format-bytes down)
                   " · Σ " (format-bytes total))]
     (if label
       (str base " · last " (format-bytes total) " (" label ")")
       base))))

(defn format-delta
  "One-line last-action cost."
  [delta label]
  (let [up (:bytes-sent delta 0)
        down (:bytes-recv delta 0)
        total (+ up down)]
    (str (format-bytes total)
         " (" (:requests delta 0) " req ↑" (format-bytes up)
         " ↓" (format-bytes down)
         (when label (str " · " label))
         ")")))

;; =============================================================================
;; HTTP
;; =============================================================================

(defn- trim-base [base-url]
  (str/replace base-url #"/$" ""))

(defn- node-url [base-url h]
  (str (trim-base base-url) "/node/" (store/hash->hex h)))

(defn- xhr
  "Synchronous XHR. Returns {:status n :body string}.
   Records body sizes into bandwidth stats."
  [method url body headers]
  (let [x (js/XMLHttpRequest.)]
    (.open x method url false)
    (doseq [[k v] headers]
      (.setRequestHeader x (name k) (str v)))
    (.send x (when body body))
    (let [resp (.-responseText x)]
      (record-xhr! method url body resp)
      {:status (.-status x)
       :body resp})))

(defn- edn-body [body]
  (when (and body (pos? (count body)))
    (wire/read-edn body)))

(defrecord BrowserRemoteStore [base-url headers]
  store/IStore
  (s-get [_ h]
    (let [{:keys [status body]} (xhr "GET" (node-url base-url h) nil headers)]
      (when (= 200 status)
        (wire/read-edn body))))

  (s-put [this h value]
    (let [{:keys [status body]} (xhr "PUT" (node-url base-url h)
                                     (wire/write-edn value)
                                     (assoc headers "Content-Type" "application/edn"))]
      (when (not= 204 status)
        (throw (ex-info "Browser remote s-put failed"
                        {:status status :hash h :body body}))))
    this)

  (s-has? [_ h]
    (= 200 (:status (xhr "HEAD" (node-url base-url h) nil headers))))

  (s-delete [this h]
    (let [{:keys [status]} (xhr "DELETE" (node-url base-url h) nil headers)]
      (when (and (not= 204 status) (not= 404 status))
        (throw (ex-info "Browser remote s-delete failed" {:status status :hash h}))))
    this)

  (s-snapshot [_] {})
  (s-merge [this m]
    (doseq [[h v] m]
      (store/s-put this h v))
    this)
  (s-reset [this] this))

(defn remote-store
  "HTTP-backed store for the browser. base-url e.g. \"\" (same origin) or full URL."
  [base-url & [{:keys [headers] :or {headers {}}}]]
  (->BrowserRemoteStore (or base-url "") headers))

(defn remote-get-root
  "Server root hash vector or nil."
  [remote]
  (let [url (str (trim-base (:base-url remote)) "/root")
        {:keys [status body]} (xhr "GET" url nil (:headers remote))]
    (when (= 200 status)
      (when-let [hex (:root (edn-body body))]
        (store/hex->hash hex)))))

(defn remote-cas-root!
  "CAS root on server. Returns true on success, false on 409."
  [remote expected new-root]
  (let [url (str (trim-base (:base-url remote)) "/root/cas")
        payload {:expected (when expected (store/hash->hex expected))
                 :new (store/hash->hex new-root)}
        {:keys [status body]} (xhr "POST" url (wire/write-edn payload)
                                   (assoc (:headers remote)
                                          "Content-Type" "application/edn"))]
    (cond
      (= 200 status) (true? (:ok (edn-body body)))
      (= 409 status) false
      :else (throw (ex-info "Browser remote CAS failed"
                            {:status status :body body})))))
