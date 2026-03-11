(ns dacite.main
  "Dacite service entry point.

   Starts an HTTP server backed by a FileStore, auto-registers the
   unix user, and runs until interrupted.

   Usage: clojure -M -m dacite.main [--port PORT] [--store-dir DIR]

   Defaults:
     port:      8484
     store-dir: ~/.dacite/store"
  (:require [dacite.service :as svc]
            [dacite.store :as store]
            [example.server :as server])
  (:gen-class))

(defn- parse-args [args]
  (loop [args (seq args)
         opts {:port 8484
               :store-dir (str (System/getProperty "user.home") "/.dacite/store")}]
    (if (nil? args)
      opts
      (case (first args)
        "--port" (recur (nnext args) (assoc opts :port (Integer/parseInt (second args))))
        "--store-dir" (recur (nnext args) (assoc opts :store-dir (second args)))
        (recur (next args) opts)))))

(defn -main [& args]
  (let [{:keys [port store-dir]} (parse-args args)
        main-store (store/file-store store-dir)
        service (svc/create-service main-store)
        user (System/getProperty "user.name")]
    ;; Auto-register the unix user (no password for local service)
    (svc/register-user service user "")
    (println (str "Dacite service starting..."))
    (println (str "  Store: " store-dir))
    (println (str "  User:  " user))
    (let [srv (server/start! service port)]
      ;; Block until interrupted
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (println "\nShutting down...")
                                   (server/stop! srv))))
      ;; Keep main thread alive
      @(promise))))
