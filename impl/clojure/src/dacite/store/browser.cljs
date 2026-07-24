(ns dacite.store.browser
  "Browser remote IStore using synchronous XHR (demo only).

   Same protocol as docs/design/service.md and dacite.store.remote:
   GET/PUT/HEAD/DELETE /node/{hex}, GET /root, POST /root/cas.

   Sync XHR keeps IStore blocking so Dacite value ops work unchanged in the
   browser. Not for production (main-thread blocking).

   Bandwidth: every store-protocol XHR records request/response body sizes
   via dacite.store.stats. Static /app assets are not counted.

   Prefer (client-cache/wrap (remote-store base) :smart-put) for the demo."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.store.stats :as stats]
            [dacite.store.pack :as pack]
            [dacite.store.client-cache :as client-cache]
            [dacite.wire :as wire]))

;; Re-export stats API for todo-web and demos
(def empty-stats stats/empty-stats)
(def get-stats stats/get-stats)
(def reset-stats! stats/reset-stats!)
(def stats-diff stats/stats-diff)
(def measure stats/measure)
(def format-bytes stats/format-bytes)
(def format-stats stats/format-stats)
(def format-delta stats/format-delta)

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
    (let [resp (.-responseText x)
          sent (if body (count (str body)) 0)
          recv (if resp (count (str resp)) 0)]
      (stats/record! (stats/classify-url method url) sent recv)
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
  (s-reset [this] this)

  pack/IChunkTransport
  (send-chunk! [this chunk]
    (let [url (str (trim-base base-url) "/nodes")
          {:keys [status body]} (xhr "POST" url (wire/write-edn chunk)
                                     (assoc headers "Content-Type" "application/edn"))]
      (when-not (= 200 status)
        (throw (ex-info "Browser send-chunk! failed"
                        {:status status :body body})))
      (when (and body (pos? (count body)))
        (wire/read-edn body)))))

(defn remote-store
  "HTTP-backed store for the browser. base-url e.g. \"\" (same origin) or full URL."
  [base-url & [{:keys [headers] :or {headers {}}}]]
  (->BrowserRemoteStore (or base-url "") headers))

(defn cached-remote-store
  "Remote store with client cache (default :write-back)."
  [base-url & [{:keys [headers policy]
                :or {headers {}
                     policy :write-back}}]]
  (client-cache/wrap (remote-store base-url {:headers headers}) policy))

(defn- unwrap-remote [remote]
  (loop [r remote]
    (cond
      (instance? BrowserRemoteStore r) r
      (and (record? r) (contains? r :remote)) (recur (:remote r))
      (and (record? r) (contains? r :layers)) (recur (last (:layers r)))
      :else r)))

(defn- local-dest [remote]
  (cond
    (client-cache/write-back-store? remote) (:local remote)
    (and (record? remote) (contains? remote :local) (contains? remote :remote)
         (not (instance? BrowserRemoteStore remote)))
    (:local remote)
    :else nil))

(defn fetch-reachable!
  "Pack-fetch subgraph from server into local dest (see dacite.store.remote)."
  ([remote roots] (fetch-reachable! remote roots nil))
  ([remote roots {:keys [budget have dest]}]
   (let [rs (unwrap-remote remote)
         dest (or dest (local-dest remote) (store/mem-store))
         root-list (cond
                     (nil? roots) []
                     (and (sequential? roots)
                          (string? (first roots)))
                     (mapv store/hex->hash roots)
                     (and (sequential? roots)
                          (vector? (first roots)))
                     (vec (remove nil? roots))
                     :else [roots])
         root-hexes (mapv store/hash->hex root-list)
         have-set (or have
                      (into #{}
                            (map (fn [k]
                                   (if (string? k) (store/hex->hash k) k))
                                 (keys (or (store/s-snapshot dest) {})))))
         have-hexes (mapv store/hash->hex have-set)
         url (str (trim-base (:base-url rs)) "/nodes/get")
         payload {:roots root-hexes
                  :have have-hexes
                  :budget (or budget pack/default-budget)}
         {:keys [status body]} (xhr "POST" url (wire/write-edn payload)
                                    (assoc (:headers rs)
                                           "Content-Type" "application/edn"))]
     (when-not (= 200 status)
       (throw (ex-info "Browser pack-get failed" {:status status :body body})))
     (let [data (edn-body body)
           chunks (or (:chunks data) [])]
       (doseq [ch chunks]
         (pack/apply-chunk! dest ch))
       (when (client-cache/write-back-store? remote)
         ;; mark as flushed: avoid re-upload of just-fetched subgraph
         (swap! (:flushed remote)
                into
                (map store/hex->hash
                     (map :hash (mapcat :items chunks)))))
       {:dest dest
        :items (:items data 0)
        :chunks (count chunks)
        :covered (:covered data 0)
        :budget (:budget data)}))))

(defn remote-get-root
  "Server root hash vector or nil."
  [remote]
  (let [rs (unwrap-remote remote)
        url (str (trim-base (:base-url rs)) "/root")
        {:keys [status body]} (xhr "GET" url nil (:headers rs))]
    (when (= 200 status)
      (when-let [hex (:root (edn-body body))]
        (store/hex->hash hex)))))

(defn remote-cas-root!
  "CAS root on server. Returns true on success, false on 409.
   Write-back caches flush reachable nodes before CAS."
  [remote expected new-root]
  (when (client-cache/write-back-store? remote)
    (client-cache/flush-reachable! remote new-root))
  (let [rs (unwrap-remote remote)
        url (str (trim-base (:base-url rs)) "/root/cas")
        payload {:expected (when expected (store/hash->hex expected))
                 :new (store/hash->hex new-root)}
        {:keys [status body]} (xhr "POST" url (wire/write-edn payload)
                                   (assoc (:headers rs)
                                          "Content-Type" "application/edn"))]
    (cond
      (= 200 status) (true? (:ok (edn-body body)))
      (= 409 status) false
      :else (throw (ex-info "Browser remote CAS failed"
                            {:status status :body body})))))
