(ns dacite.store.browser
  "Browser remote IStore using synchronous XHR (demo only).

   Same protocol as docs/design/service.md and dacite.store.remote:
   GET/PUT/HEAD/DELETE /node/{hex}, GET /root, POST /root/cas.

   Sync XHR keeps IStore blocking so Dacite value ops work unchanged in the
   browser. Not for production (main-thread blocking)."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.wire :as wire]))

(defn- trim-base [base-url]
  (str/replace base-url #"/$" ""))

(defn- node-url [base-url h]
  (str (trim-base base-url) "/node/" (store/hash->hex h)))

(defn- xhr
  "Synchronous XHR. Returns {:status n :body string}."
  [method url body headers]
  (let [x (js/XMLHttpRequest.)]
    (.open x method url false)
    (doseq [[k v] headers]
      (.setRequestHeader x (name k) (str v)))
    (.send x (when body body))
    {:status (.-status x)
     :body (.-responseText x)}))

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
