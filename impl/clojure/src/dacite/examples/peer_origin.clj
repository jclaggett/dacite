(ns dacite.examples.peer-origin
  "Origin for the M5.1 browser peer page.

   Seeds the canonical parity value, then serves the HTTP content store
   and the static page.

   From impl/clojure, after `npm run peer:build` at the repo root:

     clojure -M:peer-origin
     clojure -M:peer-origin --port 8080

   Open http://127.0.0.1:8080/app/peer/ . The page prints the same root
   hash and entry count this process prints."
  (:require [clojure.java.io :as io]
            [dacite.examples.parity :as parity]
            [dacite.rooted :as rs]
            [dacite.rooted.gc :as gc]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.value :as v])
  (:gen-class))

(defn- find-static
  "examples/web, walking up from the working directory."
  []
  (loop [dir (io/file (System/getProperty "user.dir"))]
    (when dir
      (let [web (io/file dir "examples" "web")]
        (if (.isFile (io/file web "peer" "index.html"))
          web
          (recur (.getParentFile dir)))))))

(defn seed-origin!
  "In-memory rooted store whose root is the canonical parity value.
   Returns `{:rooted :root :entries}`."
  []
  (let [content (store/mem-store)
        rooted (rs/rooted-store content)
        value (parity/canonical-value content)
        root (v/hash value)]
    (when-not (rs/cas-root! rooted nil root)
      (throw (ex-info "failed to publish the origin root" {:root root})))
    {:rooted rooted
     :root root
     :entries (count (gc/mark-reachable content root))}))

(defn- parse-port [args]
  (loop [args args]
    (when-let [a (first args)]
      (if (#{"--port" "-p"} a)
        (Integer/parseInt (second args))
        (recur (next args))))))

(defn -main
  [& args]
  (let [port (or (parse-port args) 8080)
        static (or (find-static)
                   (throw (ex-info "examples/web not found from the working directory"
                                   {:cwd (System/getProperty "user.dir")})))
        {:keys [rooted root entries]} (seed-origin!)
        {:keys [base-url stop!]}
        (svc/start-server! {:port port
                            :rooted rooted
                            :static-dir static
                            :throttle true})]
    (println "Dacite browser peer")
    (println "  origin: " base-url)
    (println "  page:   " (str base-url "/app/peer/"))
    (println "  root:   " (store/hash->hex root))
    (println "  entries:" entries)
    (println "  build:  npm run peer:build")
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop!))
    @(promise)))
