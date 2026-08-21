(ns dacite.service.main
  "Runnable Dacite HTTP service for the browser todo demo.

   From impl/clojure:
     clojure -M:service
     clojure -M:service --port 8080 --store mem
     clojure -M:service --port 8080 --store file
     clojure -M:service --port 8080 --store file:target/dacite-service
     clojure -M:service --port 8080 --store lmdb
     clojure -M:service --port 8080 --store lmdb:target/my-lmdb
     clojure -M:service --throttle off
     clojure -M:service --rate 20 --burst 40 --inflight 4

   --store selects the content + root backend:
     mem | file | file:<path> | lmdb | lmdb:<path>
   Omitting --store is the same as --store file (default path target/dacite-service).

   Serves API at /node/* and /root* and static UI under /app/ (and /)."
  (:require [clojure.java.io :as io]
            [dacite.service :as svc]
            [dacite.service.throttle :as throttle])
  (:gen-class))

(def ^:private default-static
  ;; repo-relative from impl/clojure working dir
  "../../examples/web")

(defn- parse-args [args]
  (loop [args args
         acc {:port 8080
              :store nil
              :static default-static
              :throttle true
              :throttle-opts {}}]
    (if-let [a (first args)]
      (case a
        ("--port" "-p") (recur (nnext args) (assoc acc :port (Integer/parseInt (second args))))
        ("--store" "-s") (recur (nnext args) (assoc acc :store (second args)))
        ("--static") (recur (nnext args) (assoc acc :static (second args)))
        ;; Legacy alias: prefer --store mem
        ("--mem") (recur (next args) (assoc acc :store "mem"))
        ("--throttle") (recur (nnext args)
                              (assoc acc :throttle
                                     (let [v (some-> (second args) str .toLowerCase)]
                                       (if (#{"off" "false" "0"} v) false true))))
        ("--rate") (recur (nnext args)
                          (assoc-in acc [:throttle-opts :client-rate]
                                    (Double/parseDouble (second args))))
        ("--burst") (recur (nnext args)
                           (assoc-in acc [:throttle-opts :client-burst]
                                     (Long/parseLong (second args))))
        ("--inflight") (recur (nnext args)
                              (assoc-in acc [:throttle-opts :client-inflight]
                                        (Integer/parseInt (second args))))
        ("--max-body") (recur (nnext args)
                              (assoc-in acc [:throttle-opts :max-body-bytes]
                                        (Long/parseLong (second args))))
        ("--threads") (recur (nnext args)
                             (assoc-in acc [:throttle-opts :max-threads]
                                       (Integer/parseInt (second args))))
        (recur (next args) acc))
      acc)))

(defn- throttle-opt
  [{:keys [throttle throttle-opts]}]
  (cond
    (false? throttle) false
    (seq throttle-opts) throttle-opts
    :else true))

(defn- print-throttle [th-opt]
  (let [o (or (throttle/normalize-opts th-opt) {:off true})]
    (if (:off o)
      (println "  throttle: off")
      (println (str "  throttle: rate=" (:client-rate o)
                    "/s burst=" (:client-burst o)
                    " inflight=" (:client-inflight o)
                    " body=" (:max-body-bytes o)
                    " threads=" (:max-threads o))))))

(defn -main [& args]
  (let [parsed (parse-args args)
        {:keys [port store static]} parsed
        th-opt (throttle-opt parsed)
        {:keys [rooted close! backend path]}
        (svc/make-service-rooted {:store store})
        static-dir (io/file static)
        {:keys [base-url stop!]}
        (svc/start-server! {:port port
                            :rooted rooted
                            :static-dir (when (.exists static-dir) static-dir)
                            :throttle th-opt})
        stop-all!
        (fn []
          (stop!)
          (when close! (close!)))]
    (println "Dacite service listening at" base-url)
    (println "  API:  GET/PUT /node/{hex}  GET /root  POST /root/cas")
    (when (.exists static-dir)
      (println "  UI:   " (str base-url "/app/"))
      (println "  expl: " (str base-url "/app/explorer/")))
    (println "  store:"
             (case backend
               :mem "mem"
               :lmdb (str "lmdb:" path)
               :file (str "file:" path)
               (str backend (when path (str ":" path)))))
    (print-throttle th-opt)
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. ^Runnable stop-all!))
    ;; park main thread
    @(promise)))
