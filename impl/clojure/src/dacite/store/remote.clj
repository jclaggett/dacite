(ns dacite.store.remote
  "HTTP-backed IStore for remote node access.

   Implements the node endpoints from docs/design/service.md.
   Compose with layered-store, client-cache, and lru-store for caching.
   Bodies use dacite.wire so #dacite/u64 hash words round-trip.

   Store-protocol body sizes are recorded in dacite.store.stats."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.store.stats :as stats]
            [dacite.store.pack :as pack]
            [dacite.store.client-cache :as client-cache]
            [dacite.rooted.gc :as gc]
            [dacite.wire :as wire])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn- node-url [base-url h]
  (str (str/replace base-url #"/$" "") "/node/" (store/hash->hex h)))

(defn- request [client method url ^bytes body headers]
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
        recv (if resp-body (alength resp-body) 0)]
    (stats/record! (stats/classify-url method url) sent recv)
    {:status (.statusCode resp)
     :body resp-body}))

(defn- edn-request [client method url body headers]
  (let [^bytes bs (when body (.getBytes (wire/write-edn body) "UTF-8"))
        {:keys [status body]} (request client method url bs headers)]
    {:status status
     :data (when (and body (pos? (alength body)))
             (wire/read-edn (String. body "UTF-8")))}))

(defrecord RemoteStore [base-url client headers]
  store/IStore
  (s-get [_ h]
    (let [{:keys [status body]} (request client "GET" (node-url base-url h) nil headers)]
      (when (= 200 status)
        (wire/read-edn (String. body "UTF-8")))))

  (s-put [_ h value]
    (let [{:keys [status]} (request client "PUT" (node-url base-url h)
                                    (.getBytes (wire/write-edn value) "UTF-8")
                                    (assoc headers "Content-Type" "application/edn"))]
      (when (not= 204 status)
        (throw (ex-info "Remote s-put failed" {:status status :hash h}))))
    _)

  (s-has? [_ h]
    (= 200 (:status (request client "HEAD" (node-url base-url h) nil headers))))

  (s-delete [_ h]
    (let [{:keys [status]} (request client "DELETE" (node-url base-url h) nil headers)]
      (when (and (not= 204 status) (not= 404 status))
        (throw (ex-info "Remote s-delete failed" {:status status :hash h}))))
    _)

  (s-snapshot [_]
    {})

  (s-merge [this m]
    (doseq [[h v] m]
      (store/s-put this h v))
    this)

  (s-reset [this]
    this)

  pack/IChunkTransport
  (send-chunk! [this chunk]
    (let [url (str (str/replace base-url #"/$" "") "/nodes")
          {:keys [status data]} (edn-request client "POST" url chunk
                                             (assoc headers "Content-Type" "application/edn"))]
      (when-not (= 200 status)
        (throw (ex-info "Remote send-chunk! failed"
                        {:status status :data data})))
      data)))

(defn- unwrap-remote
  "Peel client-cache / layered wrappers to the underlying RemoteStore."
  [remote]
  (loop [r remote]
    (cond
      (instance? RemoteStore r) r
      (and (record? r) (contains? r :remote)) (recur (:remote r))
      (and (record? r) (contains? r :layers)) (recur (last (:layers r)))
      :else r)))

(defn put-nodes-chunked!
  "Pack Layer-1 node items and POST /nodes chunks (2a)."
  ([remote items]
   (pack/put-items-chunked! (unwrap-remote remote) items))
  ([remote items budget]
   (pack/put-items-chunked! (unwrap-remote remote) items budget)))

(defn remote-store
  "Create an HTTP-backed remote store.

   base-url — server root, e.g. \"http://localhost:8080\"
   opts — {:headers {\"Authorization\" \"Bearer ...\"}}
            :client — optional HttpClient"
  [base-url & [{:keys [headers client]
                :or {headers {}}}]]
  (->RemoteStore base-url
                 (or client (.build (.. (HttpClient/newBuilder)
                                        (connectTimeout (Duration/ofSeconds 10)))))
                 headers))

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
  "Pack-fetch subgraph(s) from the HTTP server into a local destination store.

   remote — remote or client-cache-wrapped store
   roots  — one hash or seq of hashes to materialize
   opts:
     :budget — pack budget (default pack/default-budget)
     :have   — collection of hashes already held (default: all keys in dest)
     :dest   — IStore to apply chunks into (default: client local cache,
               or a fresh mem-store which is returned)

   Returns {:dest store :items n :chunks n :covered n}.
   After success, values under roots can be realized from :dest (or remote
   if it layers local-first)."
  ([remote roots] (fetch-reachable! remote roots nil))
  ([remote roots {:keys [budget have dest]}]
   (let [rs (unwrap-remote remote)
         dest (or dest (local-dest remote) (store/mem-store))
         root-list (cond
                     (nil? roots) []
                     (and (sequential? roots) (not (vector? (first roots)))
                          (string? (first roots)))
                     (mapv store/hex->hash roots)
                     (and (sequential? roots)
                          (or (vector? (first roots)) (nil? (first roots))))
                     (vec (remove nil? roots))
                     :else [roots])
         root-hexes (mapv store/hash->hex root-list)
         have-set (or have
                      (into #{}
                            (map (fn [k]
                                   (if (string? k) (store/hex->hash k) k))
                                 (keys (or (store/s-snapshot dest) {}))))
                      #{})
         have-hexes (mapv store/hash->hex (or have-set #{}))
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
       ;; Write-back: mark covered as already on server so we do not re-upload
       (when (client-cache/write-back-store? remote)
         (let [live (reduce (fn [s h]
                              (into s (gc/mark-reachable dest h)))
                            #{}
                            root-list)]
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
