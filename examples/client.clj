(ns examples.client
  "Example Dacite HTTP client.

   A simple client that talks to examples.server. Demonstrates
   the full flow: login, read from the main store, write with session
   store proxy.

   Usage:
     (def c (client \"http://localhost:8080\"))
     (login! c \"alice\" \"secret\")
     (get-root c)
     (push-nodes! c {hash node, ...})
     (update-root! c new-root-hash)
     (get-node c target-hash)"
  (:require [cheshire.core :as che]
            [clojure.edn :as edn]
            [dacite.hash :as hash]
            [dacite.store :as store]
            [dacite.value.types :as types]
            [dacite.value.finger-tree]
            [dacite.value.hamt])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

;; =============================================================================
;; HTTP utilities
;; =============================================================================

(def ^:private http-client (HttpClient/newHttpClient))

(defn- json-post [base-url path body]
  (let [req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str base-url path)))
                (.header "Content-Type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString
                        (che/generate-string body)))
                (.build))
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (che/parse-string (.body resp) true)}))

(defn- json-get
  ([base-url path] (json-get base-url path {}))
  ([base-url path headers]
   (let [builder (-> (HttpRequest/newBuilder)
                     (.uri (URI/create (str base-url path)))
                     (.GET))]
     (doseq [[k v] headers]
       (.header builder k v))
     (let [req (.build builder)
           resp (.send http-client req (HttpResponse$BodyHandlers/ofString))]
       {:status (.statusCode resp)
        :body (che/parse-string (.body resp) true)}))))

;; =============================================================================
;; Client state
;; =============================================================================

(defn client
  "Create a client connected to a Dacite server.
   Returns a map (atom) with connection state."
  [base-url]
  (atom {:base-url base-url
         :token nil
         :root-hash nil   ;; hex string from server
         :local-store (store/mem-store)}))

;; =============================================================================
;; Auth
;; =============================================================================

(defn login!
  "Authenticate with the server. Stores session token and root hash."
  [client-atom user password]
  (let [{:keys [base-url]} @client-atom
        {:keys [status body]} (json-post base-url "/auth/login"
                                         {:user user :password password})]
    (if (= 200 status)
      (do (swap! client-atom assoc
                 :token (:token body)
                 :root-hash (:root-hash body))
          body)
      body)))

(defn logout! [client-atom]
  (let [{:keys [base-url token]} @client-atom]
    (json-post base-url "/auth/logout" {:token token})
    (swap! client-atom assoc :token nil :root-hash nil)
    nil))

;; =============================================================================
;; Read operations
;; =============================================================================

(defn get-root
  "Get the current root hash (hex) from the server."
  [client-atom]
  (let [{:keys [base-url token]} @client-atom
        {:keys [status body]} (json-get base-url "/root"
                                        {"Authorization" token})]
    (if (= 200 status)
      (:root-hash body)
      body)))

(defn get-node
  "Fetch a node from the main store. Authorized by session token."
  [client-atom target-hash]
  (let [{:keys [base-url token]} @client-atom
        hex-target (hash/hash->hex target-hash)
        {:keys [status body]} (json-get base-url (str "/store/" hex-target)
                                        {"Authorization" token})]
    (if (= 200 status)
      (edn/read-string (:value body))
      body)))

;; =============================================================================
;; Write operations
;; =============================================================================

(defn push-nodes!
  "Push nodes to the session store (proxy) on the server.
   nodes is a map of {hash-bytes -> node-value}."
  [client-atom nodes]
  (let [{:keys [base-url token]} @client-atom
        hex-nodes (into {}
                        (map (fn [[h v]]
                               [(hash/hash->hex h) (pr-str v)]))
                        nodes)
        {:keys [status body]} (json-post base-url "/session/nodes"
                                         {:token token :nodes hex-nodes})]
    (if (= 200 status)
      body
      body)))

(defn update-root!
  "Declare a new root hash. Server will walk session store to pull new nodes.
   Returns the server's response."
  [client-atom new-root-hash]
  (let [{:keys [base-url token]} @client-atom
        hex-root (hash/hash->hex new-root-hash)
        {:keys [status body]} (json-post base-url "/root"
                                         {:token token :root-hash hex-root})]
    (when (= 200 status)
      (swap! client-atom assoc :root-hash hex-root))
    body))

;; =============================================================================
;; High-level: fetch entire value into local store
;; =============================================================================

(defn fetch-all!
  "Fetch the entire tree rooted at the session's root hash into a local
   mem-store. Walks the tree breadth-first. Returns [local-store root-hash-bytes]."
  [client-atom]
  (let [root-hex (get-root client-atom)
        root-h (hash/hex->hash root-hex)
        local (store/mem-store)]
    (loop [queue (conj clojure.lang.PersistentQueue/EMPTY root-h)
           visited #{}]
      (if (empty? queue)
        [local root-h]
        (let [h (peek queue)
              queue' (pop queue)]
          (if (visited h)
            (recur queue' visited)
            (let [node (get-node client-atom h)]
              (if (map? node) ;; error response
                (do (println "Warning: failed to fetch" (hash/hash->hex h) node)
                    (recur queue' (conj visited h)))
                (do
                  (store/s-put local h node)
                  (let [children (types/child-hashes node)
                        new-hashes (when children (remove visited children))]
                    (recur (into queue' (or new-hashes []))
                           (conj visited h))))))))))))

;; =============================================================================
;; High-level: build locally, push, update
;; =============================================================================

(defn push-value!
  "Build a Dacite value locally, push all its nodes to the server's
   session store, and update the root. Returns the server's response.

   Usage:
     (push-value! c (fn [store]
                      (binding [dacite.store/*store* store]
                        (dacite.core/hash-map \"name\" \"Alice\"))))"
  [client-atom build-fn]
  (let [local-store (store/mem-store)
        value (binding [store/*store* local-store]
                (build-fn local-store))
        root-hash (types/dacite-hash value)
        all-nodes (store/s-snapshot local-store)
        push-result (push-nodes! client-atom all-nodes)]
    (if (:ok push-result)
      (update-root! client-atom root-hash)
      push-result)))
