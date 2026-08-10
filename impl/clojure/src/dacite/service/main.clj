(ns dacite.service.main
  "Runnable Dacite HTTP service for the browser todo demo.

   From impl/clojure:
     clojure -M:service
     clojure -M:service --port 8080 --store mem
     clojure -M:service --port 8080 --store file
     clojure -M:service --port 8080 --store file:target/dacite-service
     clojure -M:service --port 8080 --store lmdb
     clojure -M:service --port 8080 --store lmdb:target/my-lmdb

   --store selects the content + root backend:
     mem | file | file:<path> | lmdb | lmdb:<path>
   Omitting --store is the same as --store file (default path target/dacite-service).

   Serves API at /node/* and /root* and static UI under /app/ (and /)."
  (:require [clojure.java.io :as io]
            [dacite.service :as svc])
  (:gen-class))

(def ^:private default-static
  ;; repo-relative from impl/clojure working dir
  "../../examples/web")

(defn- parse-args [args]
  (loop [args args
         acc {:port 8080
              :store nil
              :static default-static}]
    (if-let [a (first args)]
      (case a
        ("--port" "-p") (recur (nnext args) (assoc acc :port (Integer/parseInt (second args))))
        ("--store" "-s") (recur (nnext args) (assoc acc :store (second args)))
        ("--static") (recur (nnext args) (assoc acc :static (second args)))
        ;; Legacy alias: prefer --store mem
        ("--mem") (recur (next args) (assoc acc :store "mem"))
        (recur (next args) acc))
      acc)))

(defn -main [& args]
  (let [{:keys [port store static]} (parse-args args)
        {:keys [rooted close! backend path]}
        (svc/make-service-rooted {:store store})
        static-dir (io/file static)
        {:keys [base-url stop!]}
        (svc/start-server! {:port port
                            :rooted rooted
                            :static-dir (when (.exists static-dir) static-dir)})
        stop-all!
        (fn []
          (stop!)
          (when close! (close!)))]
    (println "Dacite service listening at" base-url)
    (println "  API:  GET/PUT /node/{hex}  GET /root  POST /root/cas")
    (when (.exists static-dir)
      (println "  UI:   " (str base-url "/app/")))
    (println "  store:"
             (case backend
               :mem "mem"
               :lmdb (str "lmdb:" path)
               :file (str "file:" path)
               (str backend (when path (str ":" path)))))
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. ^Runnable stop-all!))
    ;; park main thread
    @(promise)))
