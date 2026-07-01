(ns examples.server
  "Example Dacite HTTP server.

   A thin HTTP layer over dacite.service. Demonstrates how a Dacite
   store service works in practice.

   Endpoints:
     POST /auth/login         {user, password} → {token, root-hash}
     POST /auth/logout        {token}          → {ok}

     GET  /store/:hex-hash    (Authorization: token) → {value node}

     POST /session/nodes      {token, nodes: {hex-hash: node, ...}}
                              → {ok, count}

     GET  /session/nodes/:hex {token} → {value node}

     POST /root               {token, root-hash: hex} → {ok, nodes-pulled, root-hash}
     GET  /root               {token} → {root-hash}

   Run with: (examples.server/start! service-atom port)"
  (:require [cheshire.core :as che]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [dacite.hash :as hash]
            [dacite.service :as svc])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

;; =============================================================================
;; HTTP utilities
;; =============================================================================

(defn- read-body [^HttpExchange exchange]
  (let [is (.getRequestBody exchange)
        bytes (.readAllBytes is)]
    (when (pos? (alength bytes))
      (che/parse-string (String. bytes "UTF-8") true))))

(defn- send-response [^HttpExchange exchange status body-map]
  (let [json-bytes (.getBytes (che/generate-string body-map) "UTF-8")
        headers (.getResponseHeaders exchange)]
    (.set headers "Content-Type" "application/json")
    (.sendResponseHeaders exchange status (alength json-bytes))
    (let [os (.getResponseBody exchange)]
      (.write os json-bytes)
      (.close os))))

(defn- get-header [^HttpExchange exchange header]
  (.getFirst (.getRequestHeaders exchange) header))

(defn- path-parts [^HttpExchange exchange]
  (let [path (.getPath (.getRequestURI exchange))]
    (vec (rest (str/split path #"/")))))

(defn- hex->hash-safe [hex]
  (try
    (hash/hex->hash hex)
    (catch Exception _ nil)))

;; =============================================================================
;; Route handlers
;; =============================================================================

(defn- handle-login [service exchange]
  (let [{:keys [user password]} (read-body exchange)
        result (svc/login service user password)]
    (if result
      (send-response exchange 200
                     {:token (:token result)
                      :root-hash (some-> (:root-hash result) hash/hash->hex)})
      (send-response exchange 401 {:error "invalid credentials"}))))

(defn- handle-logout [service exchange]
  (let [{:keys [token]} (read-body exchange)]
    (svc/logout service token)
    (send-response exchange 200 {:ok true})))

(defn- handle-store-get [service exchange hex-hash]
  (let [token (get-header exchange "Authorization")
        target-hash (hex->hash-safe hex-hash)]
    (cond
      (nil? token)
      (send-response exchange 401 {:error "missing token"})

      (nil? target-hash)
      (send-response exchange 400 {:error "invalid hash"})

      :else
      (let [result (svc/session-get service token target-hash)]
        (if (:error result)
          (send-response exchange 403 {:error (name (:error result))})
          (send-response exchange 200
                         {:value (pr-str (:value result))}))))))

(defn- handle-session-put-nodes [service exchange]
  (let [{:keys [token nodes]} (read-body exchange)]
    (if (nil? token)
      (send-response exchange 401 {:error "missing token"})
      (let [count (atom 0)]
        (doseq [[hex-str node-str] nodes]
          (let [h (hex->hash-safe (name hex-str))
                ;; nodes are EDN-encoded Dacite node values
                node (edn/read-string node-str)]
            (when (and h node)
              (svc/session-put service token h node)
              (swap! count inc))))
        (send-response exchange 200 {:ok true :count @count})))))

(defn- handle-session-get-node [service exchange hex-hash]
  (let [token (get-header exchange "Authorization")
        h (hex->hash-safe hex-hash)]
    (cond
      (nil? token)
      (send-response exchange 401 {:error "missing token"})

      (nil? h)
      (send-response exchange 400 {:error "invalid hash"})

      :else
      (let [result (svc/session-get-node service token h)]
        (if (:error result)
          (send-response exchange 403 {:error (name (:error result))})
          (send-response exchange 200
                         {:value (pr-str (:value result))}))))))

(defn- handle-update-root [service exchange]
  (let [{:keys [token root-hash]} (read-body exchange)]
    (cond
      (nil? token)
      (send-response exchange 401 {:error "missing token"})

      (nil? root-hash)
      (send-response exchange 400 {:error "missing root-hash"})

      :else
      (let [h (hex->hash-safe root-hash)
            result (svc/update-root service token h)]
        (if (:error result)
          (send-response exchange 400
                         {:error (name (:error result))
                          :hash (some-> (:hash result) hash/hash->hex)})
          (send-response exchange 200
                         {:ok true
                          :nodes-pulled (:nodes-pulled result)
                          :root-hash (hash/hash->hex (:root-hash result))}))))))

(defn- handle-get-root [service exchange]
  (let [token (get-header exchange "Authorization")]
    (if (nil? token)
      (send-response exchange 401 {:error "missing token"})
      (let [session (get-in @service [:sessions token])]
        (if (nil? session)
          (send-response exchange 403 {:error "invalid session"})
          ;; Return the user's subtree root, not the service root
          (send-response exchange 200
                         {:root-hash (some-> (:root-hash session)
                                             hash/hash->hex)}))))))

;; =============================================================================
;; Router
;; =============================================================================

(defn- route [service ^HttpExchange exchange]
  (let [method (.getRequestMethod exchange)
        parts (path-parts exchange)]
    (try
      (cond
        ;; POST /auth/login
        (and (= "POST" method) (= ["auth" "login"] parts))
        (handle-login service exchange)

        ;; POST /auth/logout
        (and (= "POST" method) (= ["auth" "logout"] parts))
        (handle-logout service exchange)

        ;; GET /store/<hex>
        (and (= "GET" method) (= "store" (first parts)) (= 2 (count parts)))
        (handle-store-get service exchange (second parts))

        ;; POST /session/nodes
        (and (= "POST" method) (= ["session" "nodes"] parts))
        (handle-session-put-nodes service exchange)

        ;; GET /session/nodes/<hex>
        (and (= "GET" method)
             (= "session" (first parts))
             (= "nodes" (second parts))
             (= 3 (count parts)))
        (handle-session-get-node service exchange (nth parts 2))

        ;; POST /root
        (and (= "POST" method) (= ["root"] parts))
        (handle-update-root service exchange)

        ;; GET /root
        (and (= "GET" method) (= ["root"] parts))
        (handle-get-root service exchange)

        :else
        (send-response exchange 404 {:error "not found"}))
      (catch Exception e
        (send-response exchange 500 {:error (.getMessage e)})))))

;; =============================================================================
;; Server lifecycle
;; =============================================================================

(defn start!
  "Start the HTTP server. Returns the HttpServer instance.
   Stop with (.stop server 0)."
  ([service] (start! service 8080))
  ([service port]
   (let [server (HttpServer/create (InetSocketAddress. port) 0)]
     (.createContext server "/"
                     (reify HttpHandler
                       (handle [_ exchange]
                         (route service exchange))))
     (.setExecutor server nil)
     (.start server)
     (println (str "Dacite server running on http://localhost:" port))
     server)))

(defn stop! [server]
  (.stop server 0)
  (println "Dacite server stopped."))
