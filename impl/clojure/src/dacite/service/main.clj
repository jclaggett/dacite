(ns dacite.service.main
  "Runnable Dacite HTTP service for the browser todo demo.

   From impl/clojure:
     clojure -M:service
     clojure -M:service --port 8080 --store target/dacite-service

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
              :static default-static
              :mem? false}]
    (if-let [a (first args)]
      (case a
        ("--port" "-p") (recur (nnext args) (assoc acc :port (Integer/parseInt (second args))))
        ("--store" "-s") (recur (nnext args) (assoc acc :store (second args)))
        ("--static") (recur (nnext args) (assoc acc :static (second args)))
        ("--mem") (recur (next args) (assoc acc :mem? true))
        (recur (next args) acc))
      acc)))

(defn -main [& args]
  (let [{:keys [port store static mem?]} (parse-args args)
        store-path (or store "target/dacite-service")
        rooted (if mem?
                 (svc/make-demo-rooted)
                 (do (.mkdirs (io/file store-path))
                     (svc/make-file-rooted store-path)))
        static-dir (io/file static)
        {:keys [base-url stop!]}
        (svc/start-server! {:port port
                            :rooted rooted
                            :static-dir (when (.exists static-dir) static-dir)})]
    (println "Dacite service listening at" base-url)
    (println "  API:  GET/PUT /node/{hex}  GET /root  POST /root/cas")
    (when (.exists static-dir)
      (println "  UI:   " (str base-url "/app/")))
    (println "  store:" (if mem? "(memory)" store-path))
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. ^Runnable (fn [] (stop!))))
    ;; park main thread
    @(promise)))
