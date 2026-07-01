(ns examples.cli
  "Example command-line tool for a running Dacite service.

   Push and fetch directory trees via the HTTP API. Not part of the core
   library — demonstrates how to build a client on top of dacite.

   Run from impl/clojure:
     clojure -M:cli status
     clojure -M:cli push <dir>
     clojure -M:cli fetch <dir>
     clojure -M:cli ls [path...]

   Files are stored as blobs. Directories are maps.
   Auth uses the unix username."
  (:require [clojure.java.io :as io]
            [dacite.convert :as convert]
            [dacite.core :as d]
            [dacite.store :as store]
            [dacite.value.types :as types]
            [examples.client :as client]))

(def ^:private default-url "http://localhost:8421")

;; =============================================================================
;; Directory → Dacite value
;; =============================================================================

(defn- dir->dacite
  "Recursively convert a directory to a Dacite map.
   Files become blobs, subdirectories become nested maps."
  [^java.io.File dir]
  (let [children (.listFiles dir)]
    (when children
      (let [entries (mapcat
                     (fn [^java.io.File f]
                       (let [name (.getName f)]
                         (cond
                           (.isDirectory f)
                           [name (dir->dacite f)]

                           (.isFile f)
                           [name (d/blob (.readAllBytes (io/input-stream f)))]

                           :else nil)))
                     (sort-by #(.getName ^java.io.File %) children))]
        (apply d/hash-map entries)))))

;; =============================================================================
;; Dacite value → Directory
;; =============================================================================

(defn- materialize
  "Recursively materialize a Dacite value as a directory tree.
   Maps become directories, blobs/strings become files."
  [^java.io.File target root-hash local-store]
  (let [node (store/s-get local-store root-hash)]
    (when node
      (case (first node)
        "map"
        (do
          (.mkdirs target)
          (store/bind-store local-store
                            (let [dac-map (d/wrap-hash root-hash)]
                              (doseq [[k v] (seq dac-map)]
                                (let [key-str (convert/dac->clj k)
                                      val-hash (types/dacite-hash v)
                                      child-file (io/file target key-str)]
                                  (materialize child-file val-hash local-store))))))

        "blob"
        (store/bind-store local-store
                          (let [bytes (convert/dac->clj (d/wrap-hash root-hash))]
                            (io/copy bytes target)))

        "string"
        (store/bind-store local-store
                          (let [text (convert/dac->clj (d/wrap-hash root-hash))]
                            (spit target text)))

        ;; Scalar — write as string
        (store/bind-store local-store
                          (let [val (convert/dac->clj (d/wrap-hash root-hash))]
                            (spit target (pr-str val))))))))

;; =============================================================================
;; List contents
;; =============================================================================

(defn- list-entries
  "List entries at a path within the fetched Dacite value."
  [local-store root-hash path-parts]
  (store/bind-store local-store
                    (let [target-hash (reduce
                                       (fn [h k]
                                         (when h
                                           (let [node (store/s-get local-store h)]
                                             (when (= "map" (first node))
                                               (let [m (d/wrap-hash h)
                                                     v (get m k)]
                                                 (when v (types/dacite-hash v)))))))
                                       root-hash
                                       path-parts)]
                      (when target-hash
                        (let [node (store/s-get local-store target-hash)]
                          (case (first node)
                            "map"
                            (let [m (d/wrap-hash target-hash)]
                              (doseq [[k v] (seq m)]
                                (let [val-hash (types/dacite-hash v)
                                      val-node (store/s-get local-store val-hash)
                                      type-str (first val-node)
                                      indicator (case type-str
                                                  "map" "dir/"
                                                  "blob" "blob"
                                                  "string" "str "
                                                  (str type-str))]
                                  (println (format "  %-6s %s" indicator (convert/dac->clj k))))))

            ;; Not a map — show the value
                            (println (convert/dac->clj (d/wrap-hash target-hash)))))))))

;; =============================================================================
;; Commands
;; =============================================================================

(defn- connect [url]
  (let [c (client/client url)
        user (System/getProperty "user.name")
        result (client/login! c user "")]
    (when (:token result)
      c)))

(defn- cmd-status [url]
  (try
    (let [c (client/client url)
          user (System/getProperty "user.name")
          result (client/login! c user "")]
      (if (:token result)
        (do
          (println "Dacite service: running")
          (println (str "  URL:  " url))
          (println (str "  User: " user))
          (if-let [root (:root-hash result)]
            (println (str "  Root: " (subs root 0 16) "..."))
            (println "  Root: (empty)"))
          (client/logout! c))
        (println "Dacite service: auth failed")))
    (catch Exception e
      (println (str "Dacite service: not reachable (" (.getMessage e) ")")))))

(defn- cmd-push [url dir-path]
  (let [dir (io/file dir-path)]
    (when-not (.isDirectory dir)
      (println (str "Error: " dir-path " is not a directory"))
      (System/exit 1))
    (if-let [c (connect url)]
      (do
        (println (str "Pushing " dir-path " ..."))
        (let [result (client/push-value! c
                                         (fn [s]
                                           (store/bind-store s
                                                             (dir->dacite dir))))]
          (if (:ok result)
            (do
              (println (str "  Root:  " (:root-hash result)))
              (println (str "  Nodes: " (:nodes-pulled result))))
            (println (str "  Error: " result)))
          (client/logout! c)))
      (println "Could not connect to Dacite service."))))

(defn- cmd-fetch [url dir-path]
  (if-let [c (connect url)]
    (let [root-hex (client/get-root c)]
      (if (nil? root-hex)
        (println "No data stored yet. Push a directory first.")
        (do
          (println (str "Fetching to " dir-path " ..."))
          (println (str "  Root: " (subs root-hex 0 16) "..."))
          (let [[local-store root-h] (client/fetch-all! c)
                target-dir (io/file dir-path)]
            (materialize target-dir root-h local-store)
            (println "  Done."))))
      (client/logout! c))
    (println "Could not connect to Dacite service.")))

(defn- cmd-ls [url path-parts]
  (if-let [c (connect url)]
    (let [root-hex (client/get-root c)]
      (if (nil? root-hex)
        (println "No data stored yet.")
        (do
          (println (str "Root: " (subs root-hex 0 16) "..."))
          (let [[local-store root-h] (client/fetch-all! c)]
            (list-entries local-store root-h path-parts))))
      (client/logout! c))
    (println "Could not connect to Dacite service.")))

;; =============================================================================
;; Main
;; =============================================================================

(defn -main [& args]
  (let [[cmd & rest-args] args
        url default-url]
    (case cmd
      "status" (cmd-status url)
      "push" (cmd-push url (first rest-args))
      "fetch" (cmd-fetch url (first rest-args))
      "ls" (cmd-ls url rest-args)
      (do
        (println "Usage: cli <command> [args]")
        (println)
        (println "Commands:")
        (println "  status          Check if the service is running")
        (println "  push <dir>      Push a directory tree to the store")
        (println "  fetch <dir>     Fetch the stored tree to a directory")
        (println "  ls [path...]    List contents at a path")))))
