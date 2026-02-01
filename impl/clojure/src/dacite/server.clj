(ns dacite.server
  "Simple HTTP server for Dacite content-addressed data.
   
   Endpoints:
   - GET /root           - Get current root hash
   - GET /node/:hash     - Get node by hash (with inline_under support)
   - POST /root          - Update root hash (for demo)"
  (:require [dacite.store :as store]
            [dacite.hash :as hash]
            [clojure.string :as str]
            [clojure.edn :as edn])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.io InputStream OutputStream]))

;; =============================================================================
;; Server state
;; =============================================================================

(defonce ^:private server-state 
  (atom {:store nil
         :root-hash nil}))

(defn set-store! [s]
  (swap! server-state assoc :store s))

(defn set-root! [h]
  (swap! server-state assoc :root-hash h))

(defn get-store []
  (:store @server-state))

(defn get-root []
  (:root-hash @server-state))

;; =============================================================================
;; HTTP utilities
;; =============================================================================

(defn- parse-query-params [query]
  (when query
    (into {}
          (for [pair (str/split query #"&")
                :let [[k v] (str/split pair #"=" 2)]]
            [(keyword k) (or v "true")]))))

(defn- send-response [^HttpExchange exchange status body]
  (let [bytes (.getBytes (str body) "UTF-8")
        headers (.getResponseHeaders exchange)]
    (.add headers "Content-Type" "application/edn")
    (.add headers "Access-Control-Allow-Origin" "*")
    (.sendResponseHeaders exchange status (count bytes))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- send-json-response [^HttpExchange exchange status data]
  (send-response exchange status (pr-str data)))

(defn- get-path [^HttpExchange exchange]
  (.getPath (.getRequestURI exchange)))

(defn- get-query [^HttpExchange exchange]
  (parse-query-params (.getQuery (.getRequestURI exchange))))

(defn- read-body [^HttpExchange exchange]
  (let [is (.getRequestBody exchange)
        bytes (.readAllBytes is)]
    (String. bytes "UTF-8")))

;; =============================================================================
;; Inline threshold logic
;; =============================================================================

(def ^:const DEFAULT_INLINE_THRESHOLD 1024)

(defn- should-inline?
  "Check if node should be returned inline (with expanded children)."
  [node threshold]
  (let [size (get-in node [:measure :size-bytes] 0)]
    (<= size threshold)))

(defn- expand-for-inline
  "Recursively expand a node for inline response."
  [store node threshold]
  (if (or (nil? (:children node))
          (not (should-inline? node threshold)))
    node
    (let [expanded (mapv (fn [child-ref]
                           (if (and (vector? child-ref) (= 4 (count child-ref)))
                             ;; It's a hash reference - fetch and maybe expand
                             (when-let [child (store/get-value store child-ref)]
                               (expand-for-inline store child threshold))
                             ;; Already expanded
                             child-ref))
                         (:children node))]
      (assoc node :children expanded))))

;; =============================================================================
;; Request handlers
;; =============================================================================

(defn handle-get-root [^HttpExchange exchange]
  (if-let [root (get-root)]
    (send-json-response exchange 200 
                        {:root (store/hash->hex root)})
    (send-json-response exchange 404 
                        {:error "No root configured"})))

(defn handle-get-node [^HttpExchange exchange hash-hex]
  (let [store (get-store)
        query (get-query exchange)
        threshold (if-let [t (:inline_under query)]
                    (Integer/parseInt t)
                    DEFAULT_INLINE_THRESHOLD)]
    (if (nil? store)
      (send-json-response exchange 500 {:error "Store not configured"})
      (let [hash-longs (store/hex->hash hash-hex)
            node (store/get-value store hash-longs)]
        (if node
          (let [response-node (if (should-inline? node threshold)
                                (expand-for-inline store node threshold)
                                node)
                response {:kind (if (= response-node node) "structure" "inline")
                          :hash hash-hex
                          :node response-node}]
            (send-json-response exchange 200 response))
          (send-json-response exchange 404 {:error "Node not found"}))))))

(defn handle-post-root [^HttpExchange exchange]
  (let [body (read-body exchange)
        data (edn/read-string body)]
    (if-let [root-hex (:root data)]
      (do
        (set-root! (store/hex->hash root-hex))
        (send-json-response exchange 200 {:status "ok" :root root-hex}))
      (send-json-response exchange 400 {:error "Missing :root in body"}))))

;; =============================================================================
;; Router
;; =============================================================================

(defn- router [^HttpExchange exchange]
  (let [method (.getRequestMethod exchange)
        path (get-path exchange)]
    (try
      (cond
        (and (= method "GET") (= path "/root"))
        (handle-get-root exchange)
        
        (and (= method "GET") (str/starts-with? path "/node/"))
        (let [hash-hex (subs path 6)]  ;; Remove "/node/"
          (handle-get-node exchange hash-hex))
        
        (and (= method "POST") (= path "/root"))
        (handle-post-root exchange)
        
        (= method "OPTIONS")
        (do
          (let [headers (.getResponseHeaders exchange)]
            (.add headers "Access-Control-Allow-Origin" "*")
            (.add headers "Access-Control-Allow-Methods" "GET, POST, OPTIONS")
            (.add headers "Access-Control-Allow-Headers" "Content-Type"))
          (.sendResponseHeaders exchange 204 -1))
        
        :else
        (send-json-response exchange 404 {:error "Not found"}))
      
      (catch Exception e
        (send-json-response exchange 500 
                            {:error (.getMessage e)})))))

;; =============================================================================
;; Server lifecycle
;; =============================================================================

(defonce ^:private server-instance (atom nil))

(defn start-server
  "Start the HTTP server on the given port."
  [port store initial-root]
  (when @server-instance
    (throw (ex-info "Server already running" {})))
  
  (set-store! store)
  (when initial-root
    (set-root! initial-root))
  
  (let [server (HttpServer/create (InetSocketAddress. port) 0)
        handler (reify HttpHandler
                  (handle [_ exchange]
                    (router exchange)))]
    (.createContext server "/" handler)
    (.setExecutor server nil)
    (.start server)
    (reset! server-instance server)
    (println (str "Dacite server started on port " port))
    server))

(defn stop-server
  "Stop the running server."
  []
  (when-let [server @server-instance]
    (.stop server 0)
    (reset! server-instance nil)
    (println "Dacite server stopped")))

;; =============================================================================
;; Demo setup
;; =============================================================================

(defn demo-config
  "Create a demo configuration structure."
  []
  {:type "dacite.core/map"
   :measure {:count 4 :size-bytes 100}
   :data {:database {:host "localhost"
                     :port 5432
                     :pool-size 10}
          :cache {:enabled true
                  :ttl-seconds 3600}
          :logging {:level "info"
                    :format "json"}
          :features {:new-ui true
                     :beta-api false}}})

(defn setup-demo
  "Set up demo server with sample config."
  [port]
  (let [store (store/file-store "/tmp/dacite-demo")
        config (demo-config)
        root (store/put-value store config)]
    (start-server port store root)
    {:store store :root root}))

(comment
  ;; Start demo server
  (def demo (setup-demo 8080))
  
  ;; Test endpoints
  ;; curl http://localhost:8080/root
  ;; curl http://localhost:8080/node/<hash>
  ;; curl http://localhost:8080/node/<hash>?inline_under=2048
  
  ;; Stop server
  (stop-server)
  )
