(ns examples.main
  "Example Dacite service entry point.

   Starts an HTTP server backed by a FileStore, auto-registers the
   unix user, and runs until interrupted. Not part of the core library —
   a placeholder until a dedicated service project exists.

   Run from impl/clojure:
     clojure -M:server [--port 8421] [--store-dir ~/.local/dacite/store]"
  (:require [dacite.hash :as hash]
            [dacite.service :as svc]
            [dacite.store :as store]
            [example.server :as server]))

(defn- parse-args [args]
  (loop [args (seq args)
         opts {:port 8421
               :store-dir (str (System/getProperty "user.home") "/.local/dacite/store")}]
    (if (nil? args)
      opts
      (case (first args)
        "--port" (recur (nnext args) (assoc opts :port (Integer/parseInt (second args))))
        "--store-dir" (recur (nnext args) (assoc opts :store-dir (second args)))
        (recur (next args) opts)))))

(defn -main [& args]
  (let [{:keys [port store-dir]} (parse-args args)
        lmdb (store/lmdb-store store-dir)
        main-store (store/layered-store (store/mem-store) lmdb)
        service (svc/create-service main-store lmdb)
        user (System/getProperty "user.name")
        root-hash (svc/get-root-hash service)]
    ;; Auto-register the unix user (no password for local service)
    (svc/register-user service user "")
    (println (str "Dacite service starting..."))
    (println (str "  Store: " store-dir " (LMDB + mem cache)"))
    (println (str "  User:  " user))
    (when root-hash
      (println (str "  Root:  " (hash/hash->hex root-hash))))
    (let [srv (server/start! service port)]
      ;; Block until interrupted
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (println "\nShutting down...")
                                   (server/stop! srv)
                                   (store/lmdb-close lmdb))))
      ;; Keep main thread alive
      @(promise))))
