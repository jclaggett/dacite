(ns dacite.demo
  "Demo script showing Dacite config management end-to-end.
   
   Run with: clj -M -m dacite.demo"
  (:require [dacite.server :as server]
            [dacite.client :as client]
            [dacite.store :as store]))

(defn demo-server-client
  "Demonstrate server-client config flow."
  []
  (println "=" (apply str (repeat 60 "=")))
  (println "Dacite Configuration Management Demo")
  (println "=" (apply str (repeat 60 "=")))
  (println)
  
  ;; 1. Set up server with initial config
  (println "1. Starting server with initial config...")
  (let [{:keys [store root]} (server/setup-demo 8080)]
    (println "   Server running on http://localhost:8080")
    (println "   Initial root:" (store/hash->hex root))
    (println)
    
    ;; 2. Create client
    (println "2. Creating client...")
    (let [c (client/make-client "http://localhost:8080")]
      (println "   Client connected")
      (println)
      
      ;; 3. Fetch config
      (println "3. Fetching config from server...")
      (let [config (client/get-config c)]
        (println "   Config type:" (:type config))
        (println "   Config data:" (:data config))
        (println))
      
      ;; 4. Fetch specific paths
      (println "4. Fetching specific config paths...")
      (println "   [:database :host] =" 
               (client/get-config-path c [:database :host]))
      (println "   [:cache :ttl-seconds] =" 
               (client/get-config-path c [:cache :ttl-seconds]))
      (println "   [:features :new-ui] =" 
               (client/get-config-path c [:features :new-ui]))
      (println)
      
      ;; 5. Update config on server
      (println "5. Updating config on server...")
      (let [old-root root
            new-config {:type "dacite.core/map"
                        :measure {:count 4 :size-bytes 120}
                        :data {:database {:host "db.example.com"  ;; Changed!
                                          :port 5432
                                          :pool-size 20}          ;; Changed!
                               :cache {:enabled true
                                       :ttl-seconds 7200}         ;; Changed!
                               :logging {:level "debug"           ;; Changed!
                                         :format "json"}
                               :features {:new-ui true
                                          :beta-api true}}}       ;; Changed!
            new-root (store/put-value store new-config)]
        (server/set-root! new-root)
        (println "   New root:" (store/hash->hex new-root))
        (println)
        
        ;; 6. Sync and see changes
        (println "6. Client syncing with server...")
        (let [sync-result (client/sync-config c old-root)]
          (println "   Changed?" (:changed? sync-result))
          (when (:changed? sync-result)
            (println "   New config:"
                     (client/get-config c))))
        (println)))
    
    ;; 7. Clean up
    (println "7. Stopping server...")
    (server/stop-server)
    (println "   Done!")
    (println)
    (println "Demo complete! Key points demonstrated:")
    (println "- Content-addressed storage (same data = same hash)")
    (println "- Server announces root hash")
    (println "- Client fetches only what it needs")
    (println "- Changes detected by comparing root hashes")))

(defn -main [& _args]
  (demo-server-client)
  (System/exit 0))

(comment
  ;; Run interactively
  (demo-server-client)
  )
