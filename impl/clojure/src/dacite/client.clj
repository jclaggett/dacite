(ns dacite.client
  "Simple HTTP client for fetching Dacite config from a server.
   
   Demonstrates:
   - Fetching root hash
   - Lazy fetching of nodes
   - Local caching
   - Inline threshold optimization"
  (:require [dacite.store :as store]
            [clojure.edn :as edn])
  (:import [java.net URL HttpURLConnection]
           [java.io BufferedReader InputStreamReader]))

;; =============================================================================
;; HTTP client utilities
;; =============================================================================

(defn- http-get
  "Simple HTTP GET request, returns parsed EDN or nil."
  [url-str]
  (try
    (let [url (URL. url-str)
          conn ^HttpURLConnection (.openConnection url)]
      (.setRequestMethod conn "GET")
      (.setConnectTimeout conn 5000)
      (.setReadTimeout conn 5000)

      (let [status (.getResponseCode conn)]
        (if (= 200 status)
          (with-open [reader (BufferedReader.
                              (InputStreamReader. (.getInputStream conn)))]
            (edn/read-string (slurp reader)))
          (do
            (println "HTTP error:" status)
            nil))))
    (catch Exception e
      (println "Request failed:" (.getMessage e))
      nil)))

;; =============================================================================
;; Client state
;; =============================================================================

(defrecord DaciteClient [server-url cache inline-threshold])

(defn make-client
  "Create a Dacite client.
   
   Options:
   - :cache - local store for caching (default: mem-store)
   - :inline-threshold - bytes threshold for inline responses (default: 1024)"
  [server-url & {:keys [cache inline-threshold]
                 :or {cache (store/mem-store)
                      inline-threshold 1024}}]
  (->DaciteClient server-url cache inline-threshold))

;; =============================================================================
;; Core operations
;; =============================================================================

(defn fetch-root
  "Fetch the current root hash from the server."
  [client]
  (let [url (str (:server-url client) "/root")]
    (when-let [response (http-get url)]
      (when-let [root-hex (:root response)]
        (store/hex->hash root-hex)))))

(defn fetch-node
  "Fetch a node by hash. Uses local cache if available."
  [client hash-longs]
  (let [cache (:cache client)]
    ;; Check cache first
    (if-let [cached (store/store-get cache hash-longs)]
      cached
      ;; Fetch from server
      (let [hash-hex (store/hash->hex hash-longs)
            threshold (:inline-threshold client)
            url (str (:server-url client) "/node/" hash-hex
                     "?inline_under=" threshold)]
        (when-let [response (http-get url)]
          (let [node (:node response)]
            ;; Cache the result
            (store/store-put cache hash-longs node)
            node))))))

(defn get-config
  "Fetch the full config from the server.
   Returns the root node with all data expanded."
  [client]
  (when-let [root (fetch-root client)]
    (fetch-node client root)))

(defn get-config-path
  "Fetch a specific path in the config.
   Path is a vector of keys, e.g., [:database :host]"
  [client path]
  (when-let [config (get-config client)]
    (get-in (:data config) path)))

;; =============================================================================
;; Incremental sync
;; =============================================================================

(defn sync-config
  "Sync config from server, fetching only what changed.
   Returns {:root new-root :changed? boolean}"
  [client old-root]
  (let [new-root (fetch-root client)]
    (if (= old-root new-root)
      {:root old-root :changed? false}
      (do
        ;; Fetch new root (will cache it)
        (fetch-node client new-root)
        {:root new-root :changed? true}))))

;; =============================================================================
;; Watch for changes (simple polling)
;; =============================================================================

(defn watch-config
  "Watch for config changes, calling callback when root changes.
   Returns a function to stop watching.
   
   Options:
   - :interval-ms - polling interval (default: 5000)"
  [client callback & {:keys [interval-ms] :or {interval-ms 5000}}]
  (let [running (atom true)
        current-root (atom nil)]
    (future
      (while @running
        (try
          (let [new-root (fetch-root client)]
            (when (and new-root (not= new-root @current-root))
              (let [old @current-root]
                (reset! current-root new-root)
                (callback {:old-root old
                           :new-root new-root
                           :config (fetch-node client new-root)}))))
          (catch Exception e
            (println "Watch error:" (.getMessage e))))
        (Thread/sleep interval-ms)))
    ;; Return stop function
    #(reset! running false)))

;; =============================================================================
;; Example usage
;; =============================================================================

(comment
  ;; Create client
  (def client (make-client "http://localhost:8080"))

  ;; Fetch root hash
  (fetch-root client)

  ;; Fetch full config
  (get-config client)

  ;; Fetch specific path
  (get-config-path client [:database :host])
  ;; => "localhost"

  ;; Watch for changes
  (def stop-watch
    (watch-config client
                  (fn [{:keys [old-root new-root config]}]
                    (println "Config changed!")
                    (println "Old root:" old-root)
                    (println "New root:" new-root)
                    (println "New config:" config))))

  ;; Stop watching
  (stop-watch))
