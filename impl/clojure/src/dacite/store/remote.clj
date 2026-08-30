(ns dacite.store.remote
  "HTTP-backed IStore for remote node access.

   Implements the node endpoints from docs/design/service.md.
   Compose with layered-store, client-cache, and lru-store for caching.

   Wire:
     :binary (default true) — pack GET and POST /nodes use wire-v1 binary
       (application/vnd.dacite.chunk.v1). Novelty / root CAS stay EDN.
     :binary false — EDN packs (legacy demos).

   GET /node/{hex} returns a pack chunk by default (BFS under the hash).
   s-get applies the chunk into a local pack cache, then returns the node.

   Store-protocol body sizes are recorded in dacite.store.stats."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.store.stats :as stats]
            [dacite.store.pack :as pack]
            [dacite.store.client-cache :as client-cache]
            [dacite.rooted :as rs]
            [dacite.rooted.gc :as gc]
            [dacite.wire :as wire]
            [dacite.wire.binary :as bin])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse HttpRequest$BodyPublishers HttpResponse$BodyHandlers HttpClient$Version]
           [java.time Duration]
           [java.io BufferedReader InputStreamReader]))

(defn- node-url
  ([base-url h] (node-url base-url h nil))
  ([base-url h query]
   (str (str/replace base-url #"/$" "")
        "/node/" (store/hash->hex h)
        (when query (str "?" query)))))

(def ^:private max-retry-after-status
  "How many times to retry 429/503 before giving up."
  8)

(defn- retry-after-ms
  "Retry-After header (delta-seconds) → milliseconds. Default 1000."
  [^HttpResponse resp]
  (let [opt (.firstValue (.headers resp) "Retry-After")]
    (if (.isPresent opt)
      (try
        (* 1000 (max 1 (Long/parseLong (str/trim (.get opt)))))
        (catch Exception _ 1000))
      1000)))

(defn- request
  ([client method url body headers]
   (request client method url body headers 0))
  ([client method url ^bytes body headers attempt]
   (let [body-publisher (if body
                          (HttpRequest$BodyPublishers/ofByteArray body)
                          (HttpRequest$BodyPublishers/noBody))
         builder (.. (HttpRequest/newBuilder)
                     (uri (URI/create url))
                     (method method body-publisher))
         builder (reduce (fn [b [k v]]
                           (.header b (name k) (str v)))
                         builder
                         headers)
         ^HttpRequest req (.build builder)
         ^HttpResponse resp (.send client req (HttpResponse$BodyHandlers/ofByteArray))
         ^bytes resp-body (.body resp)
         sent (if body (alength body) 0)
         recv (if resp-body (alength resp-body) 0)
         status (.statusCode resp)]
     (stats/record! (stats/classify-url method url) sent recv)
     (if (and (#{429 503} status) (< attempt max-retry-after-status))
       (do
         (Thread/sleep (retry-after-ms resp))
         (request client method url body headers (inc attempt)))
       {:status status
        :body resp-body}))))

(defn- edn-request [client method url body headers]
  (let [^bytes bs (when body (.getBytes (wire/write-edn body) "UTF-8"))
        {:keys [status body]} (request client method url bs headers)]
    {:status status
     :data (when (and body (pos? (alength body)))
             (wire/read-edn (String. body "UTF-8")))}))

(defn- apply-get-body!
  "Install GET /node body into pack-local (chunk or raw node). Return node at h.
   body-bytes — response body; binary? — wire-v1 chunk when true."
  [pack-local h ^bytes body-bytes binary?]
  (when (and body-bytes (pos? (alength body-bytes)))
    (cond
      (or binary? (bin/dac1-magic? body-bytes))
      (let [chunk (bin/decode-pack-edn body-bytes)]
        (pack/apply-chunk! pack-local chunk)
        (store/s-get pack-local h))

      :else
      (let [data (wire/read-edn (String. body-bytes "UTF-8"))]
        (cond
          (pack/chunk? data)
          (do (pack/apply-chunk! pack-local data)
              (store/s-get pack-local h))

          (some? data)
          (do (store/s-put pack-local h data)
              data)

          :else nil)))))

(defrecord RemoteStore [base-url client headers pack-local binary?]
  store/IStore
  (s-get [_ h]
    (or (store/s-get pack-local h)
        (let [hdrs (if binary?
                     (assoc headers "Accept" bin/content-type-chunk-v1)
                     (assoc headers "Accept" "application/edn"))
              near store/*pack-near*
              q (when (and near
                           (not= (store/hash->hex near) (store/hash->hex h)))
                  (str "near=" (store/hash->hex near)))
              {:keys [status body]} (request client "GET" (node-url base-url h q) nil hdrs)]
          (when (= 200 status)
            (apply-get-body! pack-local h body binary?)))))

  (s-put [_ h value]
    (let [{:keys [status body]} (request client "PUT" (node-url base-url h)
                                         (.getBytes (wire/write-edn value) "UTF-8")
                                         (assoc headers "Content-Type" "application/edn"))]
      ;; 200 + novelty body (preferred); 204 legacy
      (when-not (or (= 200 status) (= 204 status))
        (throw (ex-info "Remote s-put failed" {:status status :hash h})))
      (store/s-put pack-local h value))
    _)

  (s-has? [_ h]
    (or (store/s-has? pack-local h)
        (= 200 (:status (request client "HEAD" (node-url base-url h) nil headers)))))

  (s-delete [_ h]
    (let [{:keys [status]} (request client "DELETE" (node-url base-url h) nil headers)]
      (when (and (not= 204 status) (not= 404 status))
        (throw (ex-info "Remote s-delete failed" {:status status :hash h})))
      (store/s-delete pack-local h))
    _)

  (s-snapshot [_]
    (store/s-snapshot pack-local))

  (s-merge [this m]
    (doseq [[h v] m]
      (store/s-put this h v))
    this)

  (s-reset [this]
    (store/s-reset pack-local)
    this)

  pack/IChunkTransport
  (send-chunk! [this chunk]
    (let [url (str (str/replace base-url #"/$" "") "/nodes")
          data
          (if binary?
            (let [{:keys [status body]}
                  (request client "POST" url
                           (bin/encode-pack-edn chunk)
                           (assoc headers
                                  "Content-Type" bin/content-type-chunk-v1
                                  "Accept" "application/edn"))]
              (when-not (= 200 status)
                (throw (ex-info "Remote send-chunk! failed" {:status status})))
              (when (and body (pos? (alength ^bytes body)))
                (wire/read-edn (String. ^bytes body "UTF-8"))))
            (let [{:keys [status data]}
                  (edn-request client "POST" url chunk
                               (assoc headers "Content-Type" "application/edn"))]
              (when-not (= 200 status)
                (throw (ex-info "Remote send-chunk! failed"
                                {:status status :data data})))
              data))]
      (pack/apply-chunk! pack-local chunk)
      data)))

(declare remote-get-root remote-cas-root!)

(defrecord RemoteRootedStore [content]
  store/IStore
  (s-get [_ h] (store/s-get content h))
  (s-put [this h value]
    (store/s-put content h value)
    this)
  (s-has? [_ h] (store/s-has? content h))
  (s-delete [this h]
    (store/s-delete content h)
    this)
  (s-snapshot [_] (store/s-snapshot content))
  (s-merge [this m]
    (store/s-merge content m)
    this)
  (s-reset [this]
    (store/s-reset content)
    this)

  pack/IChunkTransport
  (send-chunk! [_ chunk]
    (pack/send-chunk! (pack/find-chunk-transport content) chunk))

  rs/IRoot
  (-root [_] (remote-get-root content))
  (-cas-root! [_ expected new] (remote-cas-root! content expected new))
  (-set-root! [_ _]
    (throw (ex-info
            "set-root! is not offered on a remote store (unsafe under sharing). Use cas-root! or value-level ref-cas! / ref-swap!."
            {:op :set-root!}))))

(defn- unwrap-remote
  "Peel wrappers to the underlying RemoteStore for base-url / client fields.

   Not used on the chunk send path — that uses pack/find-chunk-transport so
   middleware implementing IChunkTransport is not bypassed."
  [remote]
  (loop [r remote]
    (cond
      (instance? RemoteStore r) r
      (instance? RemoteRootedStore r) (recur (:content r))
      (and (record? r) (contains? r :remote)) (recur (:remote r))
      (and (record? r) (contains? r :inner)) (recur (:inner r))
      (and (record? r) (contains? r :layers)) (recur (last (:layers r)))
      :else r)))

(defn put-nodes-chunked!
  "Pack Layer-1 items and POST /nodes via outermost IChunkTransport."
  ([remote items]
   (pack/put-items-chunked! remote items))
  ([remote items budget]
   (pack/put-items-chunked! remote items budget)))

(defn- merge-token-headers
  "If :token is set, add Authorization: Bearer <token> unless already present."
  [headers token]
  (let [headers (or headers {})]
    (if (and token (not (some (fn [k] (= "authorization" (str/lower-case (name k))))
                              (keys headers))))
      (assoc headers "Authorization" (str "Bearer " token))
      headers)))

(defn remote-store
  "Create an HTTP-backed remote store.

   base-url — server root, e.g. \"http://localhost:8080\"
   opts — {:headers {…}
           :token string          ; Authorization: Bearer <token> (bucket name)
           :client HttpClient
           :binary true|false  ; default true — wire-v1 for pack GET/POST}

   429 and 503 responses retry up to 8 times using Retry-After (seconds)."
  [base-url & [{:keys [headers client binary token]
                :or {headers {}
                     binary true}}]]
  (->RemoteStore base-url
                 (or client (.build (.. (HttpClient/newBuilder)
                                        (connectTimeout (Duration/ofSeconds 10)))))
                 (merge-token-headers headers token)
                 (store/mem-store)
                 (boolean binary)))

(defn- client-cache-write-back? [remote]
  (client-cache/write-back-store? remote))

(defn- client-cache-flush! [remote root-h]
  (client-cache/flush-reachable! remote root-h))

(defn- local-dest
  "Local cache store inside client-cache wrappers, if any."
  [remote]
  (cond
    (client-cache/write-back-store? remote) (:local remote)
    (and (record? remote) (contains? remote :local) (contains? remote :remote)
         (not (instance? RemoteStore remote)))
    (:local remote)
    :else nil))

(defn fetch-reachable!
  "Bulk pack-fetch (demoted). Prefer normal s-get pack-fill for interactive use.

   POST /nodes/get — full remaining subgraph under roots. Kept for admin/sync
   and tests; can be expensive (DoS-shaped) on large roots."
  ([remote roots] (fetch-reachable! remote roots nil))
  ([remote roots {:keys [budget have dest]}]
   (let [rs (unwrap-remote remote)
         dest (or dest (local-dest remote) (:pack-local rs) (store/mem-store))
         root-list (cond
                     (nil? roots) []
                     (and (sequential? roots) (string? (first roots)))
                     (mapv store/hex->hash roots)
                     (and (sequential? roots) (vector? (first roots)))
                     (vec (remove nil? roots))
                     :else [roots])
         root-hexes (mapv store/hash->hex root-list)
         have-hexes (mapv store/hash->hex
                          (or have
                              (map (fn [k]
                                     (if (string? k) (store/hex->hash k) k))
                                   (keys (or (store/s-snapshot dest) {})))))
         url (str (str/replace (:base-url rs) #"/$" "") "/nodes/get")
         body {:roots root-hexes
               :have have-hexes
               :budget (or budget pack/default-budget)}
         {:keys [status data]} (edn-request (:client rs) "POST" url body
                                            (assoc (:headers rs)
                                                   "Content-Type" "application/edn"))]
     (when-not (= 200 status)
       (throw (ex-info "Remote pack-get failed" {:status status :data data})))
     (let [chunks (or (:chunks data) [])]
       (doseq [ch chunks]
         (pack/apply-chunk! dest ch))
       (when (client-cache/write-back-store? remote)
         (let [live (reduce (fn [s h]
                              (into s (gc/mark-reachable dest h)))
                            #{}
                            root-list)]
           ;; live is hex keys — flushed set is hex-keyed on all hosts
           (swap! (:flushed remote) into live)))
       {:dest dest
        :items (:items data 0)
        :chunks (count chunks)
        :covered (:covered data 0)
        :budget (:budget data)}))))

(defn remote-get-root
  "Fetch the server's current root hash. Returns hash vector or nil."
  [remote]
  (let [rs (unwrap-remote remote)
        url (str (str/replace (:base-url rs) #"/$" "") "/root")
        {:keys [status data]} (edn-request (:client rs) "GET" url nil (:headers rs))]
    (when (= 200 status)
      (when-let [hex (:root data)]
        (store/hex->hash hex)))))

(defn remote-rooted-store
  "HTTP content store plus the server root, for `dacite.value/root-ref`.

   Same domain code as a local `store/rooted-store`. `root` and `cas-root!`
   hit GET /root and POST /root/cas. `set-root!` / `ref-reset!` throw
   (local-only under sharing).

   base-url — server origin, e.g. \"http://127.0.0.1:8080\"
   opts — {:policy :write-back|:none|:layered|:smart-put  ; default :write-back
           :binary true|false
           :token string
           :headers {…}
           :client HttpClient}"
  [base-url & [{:keys [policy] :or {policy :write-back} :as opts}]]
  (let [raw (remote-store base-url (dissoc (or opts {}) :policy))
        content (if (or (nil? policy) (= policy :none))
                  raw
                  (client-cache/wrap raw policy))]
    (->RemoteRootedStore content)))

(defn remote-cas-root!
  "Compare-and-set root on the server. Returns true on success.

   When remote is wrapped in a write-back client cache, flushes nodes
   reachable from new-root to the network store before CAS."
  [remote expected new-root]
  (when (client-cache-write-back? remote)
    (client-cache-flush! remote new-root))
  (let [rs (unwrap-remote remote)
        url (str (str/replace (:base-url rs) #"/$" "") "/root/cas")
        body {:expected (when expected (store/hash->hex expected))
              :new (store/hash->hex new-root)}
        {:keys [status data]} (edn-request (:client rs) "POST" url body (:headers rs))]
    (cond
      (= 200 status) (true? (:ok data))
      (= 409 status) false
      :else (throw (ex-info "Remote CAS root failed" {:status status :data data})))))

(defn watch-root
  "Subscribe to GET /events (SSE). `f` is (fn [root-hash-or-nil]).

   Returns {:stop! (fn [])}. The first event is the current root. Runs
   the read loop on a future; stop! cancels it."
  [remote f]
  (let [rs (unwrap-remote remote)
        url (str (str/replace (:base-url rs) #"/$" "") "/events")
        running (atom true)
        client (:client rs)
        builder (.. (HttpRequest/newBuilder)
                    (uri (URI/create url))
                    (version HttpClient$Version/HTTP_1_1)
                    (GET)
                    (header "Accept" "text/event-stream"))
        builder (reduce (fn [b [k v]]
                          (.header b (name k) (str v)))
                        builder
                        (:headers rs))
        req (.build builder)
        fut (future
              (try
                (let [^HttpResponse resp
                      (.send client req (HttpResponse$BodyHandlers/ofInputStream))]
                  (when (and @running (= 200 (.statusCode resp)))
                    (with-open [rdr (BufferedReader.
                                     (InputStreamReader. (.body resp) "UTF-8"))]
                      (loop [event nil data nil]
                        (when @running
                          (let [line (.readLine rdr)]
                            (cond
                              (nil? line) nil

                              (or (str/blank? line)
                                  (str/starts-with? line ":"))
                              (do
                                (when (and data (or (nil? event) (= event "root")))
                                  (let [m (wire/read-edn data)
                                        h (when-let [hex (:root m)]
                                            (store/hex->hash hex))]
                                    (f h)))
                                (recur nil nil))

                              (str/starts-with? line "event:")
                              (recur (str/trim (subs line 6)) data)

                              (str/starts-with? line "data:")
                              (recur event (str/trim (subs line 5)))

                              :else
                              (recur event data))))))))
                (catch Exception e
                  (when @running
                    (throw e)))))]
    {:stop! (fn []
              (reset! running false)
              (future-cancel fut))}))
