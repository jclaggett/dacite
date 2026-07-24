(ns dacite.service
  "HTTP service for Dacite content stores + root CAS (docs/design/service.md).

   Endpoints:
     GET  /node/{hex}   — s-get
     PUT  /node/{hex}   — s-put (EDN body), 204
     HEAD /node/{hex}   — s-has?
     DELETE /node/{hex} — s-delete (optional)
     POST /nodes        — apply one pack chunk (leaf-chunking write)
     POST /nodes/get    — pack-fetch: encode reachable → chunks (read)
     GET  /root         — {:root hex-or-nil}
     POST /root/cas     — {:expected hex-or-nil :new hex} → 200/409

   Also serves static files under /app/* from a configured directory (demo UI)."
  (:require [clojure.java.io :as io]
            [dacite.store :as store]
            [dacite.store.file :as file]
            [dacite.store.pack :as pack]
            [dacite.rooted :as rs]
            [dacite.wire :as wire])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange Headers]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(defn- utf8-bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- read-body-str [^HttpExchange ex]
  (String. (.readAllBytes (.getRequestBody ex)) StandardCharsets/UTF_8))

(defn- send-bytes!
  [^HttpExchange ex status ^String content-type ^bytes body]
  (let [^Headers headers (.getResponseHeaders ex)]
    (when content-type
      (.set headers "Content-Type" content-type))
    ;; CORS for browser demos (page may be same-origin if served from here)
    (.set headers "Access-Control-Allow-Origin" "*")
    (.set headers "Access-Control-Allow-Methods" "GET,PUT,POST,HEAD,DELETE,OPTIONS")
    (.set headers "Access-Control-Allow-Headers" "Content-Type,Authorization")
    (if (and body (pos? (alength body)))
      (do (.sendResponseHeaders ex status (alength body))
          (with-open [os (.getResponseBody ex)]
            (.write os body)))
      (.sendResponseHeaders ex status -1))))

(defn- send-edn! [^HttpExchange ex status body]
  (send-bytes! ex status "application/edn; charset=utf-8"
               (utf8-bytes (wire/write-edn body))))

(defn- send-empty! [^HttpExchange ex status]
  (send-bytes! ex status nil nil))

(defn- parse-node-hex [path]
  (when (and path (.startsWith path "/node/") (> (count path) 6))
    (subs path 6)))

(defn handle-request
  "Pure-ish request handler against a rooted store.
   Returns {:status n :headers m? :body string-or-bytes-or-nil :content-type s?}
   for testing without HttpExchange."
  [rooted method path body-str]
  (try
    (cond
      (= "OPTIONS" method)
      {:status 204}

      (and (= "GET" method) (= path "/root"))
      (let [r (rs/root rooted)]
        {:status 200
         :content-type "application/edn; charset=utf-8"
         :body (wire/write-edn {:root (when r (store/hash->hex r))})})

      (and (= "POST" method) (= path "/root/cas"))
      (let [data (wire/read-edn body-str)
            expected (when-let [e (:expected data)]
                       (when-not (nil? e) (store/hex->hash e)))
            new-h (store/hex->hash (:new data))]
        (if (rs/cas-root! rooted expected new-h)
          {:status 200
           :content-type "application/edn; charset=utf-8"
           :body (wire/write-edn {:ok true})}
          {:status 409
           :content-type "application/edn; charset=utf-8"
           :body (wire/write-edn {:ok false})}))

      (and (= "POST" method) (= path "/nodes/get"))
      (try
        (let [req (wire/read-edn body-str)
              result (pack/pack-get rooted req)]
          {:status 200
           :content-type "application/edn; charset=utf-8"
           :body (wire/write-edn (assoc result :ok true))})
        (catch Exception e
          {:status 400
           :content-type "application/edn; charset=utf-8"
           :body (wire/write-edn {:ok false
                                  :error "malformed pack-get"
                                  :detail (.getMessage e)})}))

      (and (= "POST" method) (= path "/nodes"))
      (try
        (let [chunk (wire/read-edn body-str)
              result (pack/apply-chunk! rooted chunk)]
          {:status 200
           :content-type "application/edn; charset=utf-8"
           :body (wire/write-edn (assoc result :ok true))})
        (catch Exception e
          {:status 400
           :content-type "application/edn; charset=utf-8"
           :body (wire/write-edn {:ok false
                                  :error "malformed chunk"
                                  :detail (.getMessage e)})}))

      (and (#{"GET" "PUT" "HEAD" "DELETE"} method) (parse-node-hex path))
      (let [hex (parse-node-hex path)
            h (store/hex->hash hex)]
        (case method
          "GET"
          (if-let [node (store/s-get rooted h)]
            {:status 200
             :content-type "application/edn; charset=utf-8"
             :body (wire/write-edn node)}
            {:status 404})

          "PUT"
          (try
            (let [node (wire/read-edn body-str)]
              (store/s-put rooted h node)
              {:status 204})
            (catch Exception e
              {:status 400
               :content-type "application/edn; charset=utf-8"
               :body (wire/write-edn {:error "malformed body"
                                      :detail (.getMessage e)})}))

          "HEAD"
          (if (store/s-has? rooted h)
            {:status 200}
            {:status 404})

          "DELETE"
          (do (store/s-delete rooted h)
              {:status 204})))

      :else
      {:status 404
       :content-type "application/edn; charset=utf-8"
       :body (wire/write-edn {:error "not found" :path path})})
    (catch Exception e
      {:status 500
       :content-type "application/edn; charset=utf-8"
       :body (wire/write-edn {:error (.getMessage e)})})))

(defn- content-type-for [^String path]
  (cond
    (.endsWith path ".html") "text/html; charset=utf-8"
    (.endsWith path ".js") "application/javascript; charset=utf-8"
    (.endsWith path ".css") "text/css; charset=utf-8"
    (.endsWith path ".edn") "application/edn; charset=utf-8"
    (.endsWith path ".map") "application/json; charset=utf-8"
    (.endsWith path ".json") "application/json; charset=utf-8"
    (.endsWith path ".svg") "image/svg+xml"
    :else "application/octet-stream"))

(defn- safe-child
  "Resolve path under base; return File or nil if escapes base."
  [^java.io.File base ^String rel]
  (let [f (io/file base rel)
        base-path (.getCanonicalPath base)
        file-path (.getCanonicalPath f)]
    (when (.startsWith file-path base-path)
      f)))

(defn- handle-static [^java.io.File static-dir path]
  (when static-dir
    (let [rel (cond
                (or (= path "/") (= path "/app") (= path "/app/")) "index.html"
                (.startsWith path "/app/") (subs path 5)
                :else nil)]
      (when rel
        (when-let [f (safe-child static-dir rel)]
          (when (.isFile f)
            {:status 200
             :content-type (content-type-for (.getName f))
             :body-file f}))))))

(defn- write-response! [^HttpExchange ex resp]
  (if-let [f (:body-file resp)]
    (let [bytes (.readAllBytes (io/input-stream f))]
      (send-bytes! ex (:status resp) (:content-type resp) bytes))
    (let [body (:body resp)
          bs (cond
               (nil? body) nil
               (bytes? body) body
               (string? body) (utf8-bytes body)
               :else (utf8-bytes (pr-str body)))]
      (if bs
        (send-bytes! ex (:status resp) (:content-type resp) bs)
        (send-empty! ex (:status resp))))))

(defn start-server!
  "Start HttpServer.

   opts:
     :port (default 0 = ephemeral)
     :rooted — required RootedStore
     :static-dir — optional File/string for /app/* and /
   Returns {:server HttpServer :port int :base-url string :rooted ...}"
  [{:keys [port rooted static-dir]
    :or {port 0}}]
  (when-not rooted
    (throw (ex-info "rooted store required" {})))
  (let [static (when static-dir (io/file static-dir))
        server (HttpServer/create (InetSocketAddress. port) 0)
        handler
        (reify HttpHandler
          (handle [_ exchange]
            (try
              (let [method (.getRequestMethod exchange)
                    path (.getPath (.getRequestURI exchange))
                    body (when (#{"PUT" "POST"} method) (read-body-str exchange))
                    resp (or (when (#{"GET" "HEAD"} method)
                               (handle-static static path))
                             (handle-request rooted method path body))]
                (write-response! exchange resp))
              (catch Exception e
                (try
                  (send-edn! exchange 500 {:error (.getMessage e)})
                  (catch Exception _ nil))))))]
    (.createContext server "/" handler)
    (.setExecutor server nil)
    (.start server)
    (let [bound (.. server getAddress getPort)
          base (str "http://127.0.0.1:" bound)]
      {:server server
       :port bound
       :base-url base
       :rooted rooted
       :stop! (fn [] (.stop server 0))})))

(defn stop-server! [{:keys [stop!]}]
  (when stop! (stop!)))

(defn make-demo-rooted
  "In-memory rooted store for demos/tests."
  []
  (rs/rooted-store (store/mem-store) (rs/mem-root-cell)))

(defn make-file-rooted
  "File-backed content + file root cell under dir."
  [dir]
  (let [path (str dir)]
    (rs/rooted-store (file/file-store path) (rs/file-root-cell path))))