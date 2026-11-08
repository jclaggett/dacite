(ns dacite.examples.library-origin
  "Origin for the M5.3 library reader.

   Seeds the catalog (the seed work and its plate), publishes that root,
   and serves the HTTP content store plus the static page.

   From impl/clojure, after the library bundle is compiled:

     clojure -M:library-origin
     clojure -M:library-origin --port 0

   The process prints the root hash, the reachable entry count, and the
   base URL. `--port 0` asks the OS for a port. The nbb peer
   (`dacite.examples.library-nbb`) holds the same catalog."
  (:require [clojure.java.io :as io]
            [dacite.examples.library :as lib]
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
        (if (.isFile (io/file web "library" "index.html"))
          web
          (recur (.getParentFile dir)))))))

(defn seed-origin!
  "In-memory rooted store whose root is the seed library.
   Returns `{:rooted :root :entries}`."
  []
  (let [content (store/mem-store)
        rooted (rs/rooted-store content)
        catalog (lib/seed-library content)
        root (v/hash catalog)]
    (when-not (rs/cas-root! rooted nil root)
      (throw (ex-info "failed to publish the library root" {:root root})))
    {:rooted rooted
     :root root
     :entries (count (gc/mark-reachable content root))}))

(defn- parse-port [args]
  (loop [args args]
    (when-let [a (first args)]
      (if (#{"--port" "-p"} a)
        (Integer/parseInt (second args))
        (recur (next args))))))

(defn- out! [& parts]
  (apply println parts)
  (flush))

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
    (out! "root" (store/hash->hex root))
    (out! "entries" entries)
    (out! "origin" base-url)
    (out! "page" (str base-url "/app/library/"))
    (out! "ready")
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop!))
    @(promise)))
