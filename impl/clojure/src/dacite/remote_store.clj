(ns dacite.remote-store
  "HTTP-backed IStore for remote node access.

   Implements the node endpoints from docs/design/service.md.
   Compose with layered-store and lru-store for caching."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dacite.store :as store])
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
        ^HttpResponse resp (.send client req (HttpResponse$BodyHandlers/ofByteArray))]
    {:status (.statusCode resp)
     :body (.body resp)}))

(defn- edn-request [client method url body headers]
  (let [^bytes bs (when body (.getBytes (pr-str body) "UTF-8"))
        {:keys [status body]} (request client method url bs headers)]
    {:status status
     :data (when (pos? (alength body))
             (edn/read-string (String. body "UTF-8")))}))

(defrecord RemoteStore [base-url client headers]
  store/IStore
  (s-get [_ h]
    (let [{:keys [status body]} (request client "GET" (node-url base-url h) nil headers)]
      (when (= 200 status)
        (edn/read-string (String. body "UTF-8")))))

  (s-put [_ h value]
    (let [{:keys [status]} (request client "PUT" (node-url base-url h)
                                    (.getBytes (pr-str value) "UTF-8")
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
    this))

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

(defn remote-get-root
  "Fetch the server's current root hash. Returns hash vector or nil."
  [remote]
  (let [url (str (str/replace (:base-url remote) #"/$" "") "/root")
        {:keys [status data]} (edn-request (:client remote) "GET" url nil (:headers remote))]
    (when (= 200 status)
      (when-let [hex (:root data)]
        (store/hex->hash hex)))))

(defn remote-cas-root!
  "Compare-and-set root on the server. Returns true on success."
  [remote expected new-root]
  (let [url (str (str/replace (:base-url remote) #"/$" "") "/root/cas")
        body {:expected (when expected (store/hash->hex expected))
              :new (store/hash->hex new-root)}
        {:keys [status data]} (edn-request (:client remote) "POST" url body (:headers remote))]
    (cond
      (= 200 status) (true? (:ok data))
      (= 409 status) false
      :else (throw (ex-info "Remote CAS root failed" {:status status :data data})))))
