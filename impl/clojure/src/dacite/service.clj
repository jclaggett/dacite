(ns dacite.service
  "HTTP service for Dacite content stores + root CAS (docs/design/service.md).

   Endpoints:
     GET  /node/{hex}   — s-get
     PUT  /node/{hex}   — s-put (EDN body) → 200 novelty
     HEAD /node/{hex}   — s-has?
     DELETE /node/{hex} — s-delete (optional)
     POST /nodes        — apply one pack chunk (leaf-chunking write)
     POST /nodes/get    — pack-fetch: encode reachable → chunks (read)
     GET  /root         — {:root hex-or-nil}
     POST /root/cas     — {:expected hex-or-nil :new hex} → 200/409
     GET  /events       — SSE root announcements (text/event-stream)

   Also serves static files under /app/* from a configured directory (demo UI)."
  (:require [clojure.java.io :as io]
            [dacite.store :as store]
            [dacite.store.file :as file]
            [dacite.store.jvm :as jvm]
            [dacite.store.pack :as pack]
            [dacite.rooted :as rs]
            [dacite.wire :as wire]
            [dacite.wire.binary :as bin]
            [dacite.service.throttle :as throttle])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange Headers]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors]))

(def ^:private ct-edn "application/edn; charset=utf-8")
(def ^:private ct-chunk bin/content-type-chunk-v1)

(defn- utf8-bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- read-body-bytes [^HttpExchange ex]
  (.readAllBytes (.getRequestBody ex)))

(defn- header-val
  "First value of a request header (case-insensitive name), or nil."
  [^HttpExchange ex name]
  (when-let [vals (.get (.getRequestHeaders ex) name)]
    (when (seq vals) (first vals))))

(defn- remote-host [^HttpExchange ex]
  (when-let [addr (.getRemoteAddress ex)]
    (when-let [a (.getAddress addr)]
      (.getHostAddress a))))

(defn- send-bytes!
  ([^HttpExchange ex status ^String content-type ^bytes body]
   (send-bytes! ex status content-type body nil))
  ([^HttpExchange ex status ^String content-type ^bytes body extra-headers]
   (let [^Headers headers (.getResponseHeaders ex)]
     (when content-type
       (.set headers "Content-Type" content-type))
     (doseq [[k v] extra-headers]
       (.set headers (str k) (str v)))
     ;; CORS for browser demos (page may be same-origin if served from here)
     (.set headers "Access-Control-Allow-Origin" "*")
     (.set headers "Access-Control-Allow-Methods" "GET,PUT,POST,HEAD,DELETE,OPTIONS")
     (.set headers "Access-Control-Allow-Headers" "Content-Type,Authorization,Accept")
     (if (and body (pos? (alength body)))
       (do (.sendResponseHeaders ex status (alength body))
           (with-open [os (.getResponseBody ex)]
             (.write os body)))
       (.sendResponseHeaders ex status -1)))))

(defn- send-edn! [^HttpExchange ex status body]
  (send-bytes! ex status ct-edn (utf8-bytes (wire/write-edn body))))

(defn- send-empty! [^HttpExchange ex status]
  (send-bytes! ex status nil nil))

(def ^:private ct-sse "text/event-stream; charset=utf-8")

(defn- sse-frame
  "One SSE event named `event` with an EDN `data` payload."
  [event data]
  (str "event: " event "\ndata: " (wire/write-edn data) "\n\n"))

(defn- make-sse-hub
  "Per-server subscriber set. Broadcasts on the rooted store's hash watch."
  [rooted]
  (let [subs (atom #{})
        send! (fn [^java.io.OutputStream os ^String s]
                (.write os (utf8-bytes s))
                (.flush os))
        frame (fn [h]
                (sse-frame "root" {:root (when h (store/hash->hex h))}))
        broadcast (fn [_k _rs _old new]
                    (doseq [os @subs]
                      (try
                        (send! os (frame new))
                        (catch Exception _
                          (swap! subs disj os)))))]
    (rs/add-root-watch rooted ::sse broadcast)
    {:subs subs
     :send! send!
     :frame frame
     :close! (fn []
               (rs/remove-root-watch rooted ::sse)
               (doseq [^java.io.OutputStream os @subs]
                 (try (.close os) (catch Exception _ nil)))
               (reset! subs #{}))}))

(defn- handle-sse!
  "Hold the exchange open and stream root events until the client drops."
  [^HttpExchange ex hub rooted]
  (let [^Headers headers (.getResponseHeaders ex)
        {:keys [subs send! frame]} hub]
    (.set headers "Content-Type" ct-sse)
    (.set headers "Cache-Control" "no-cache")
    (.set headers "Connection" "keep-alive")
    (.set headers "Access-Control-Allow-Origin" "*")
    (.sendResponseHeaders ex 200 0)
    (let [os (.getResponseBody ex)]
      (swap! subs conj os)
      (try
        (send! os (frame (rs/root rooted)))
        (loop []
          (Thread/sleep 15000)
          (send! os ": keepalive\n\n")
          (recur))
        (catch Exception _)
        (finally
          (swap! subs disj os)
          (try (.close os) (catch Exception _ nil)))))))

(defn- parse-edn-body
  "body is String or bytes → EDN data."
  [body]
  (cond
    (nil? body) nil
    (string? body) (when (pos? (count body)) (wire/read-edn body))
    (bytes? body) (when (pos? (alength ^bytes body))
                    (wire/read-edn (String. ^bytes body StandardCharsets/UTF_8)))
    :else (wire/read-edn (str body))))

(defn- parse-chunk-body
  "Parse POST /nodes body as binary chunk or EDN pack chunk."
  [body content-type]
  (cond
    (and (bytes? body) (or (bin/binary-content-type? content-type)
                           (and (pos? (alength ^bytes body))
                                (= (aget ^bytes body 0) (unchecked-byte 0x44))
                                (= (aget ^bytes body 1) (unchecked-byte 0x41)))))
    (bin/decode-pack-edn body)

    (bytes? body)
    (parse-edn-body body)

    :else
    (parse-edn-body body)))

(defn- format-chunk-response
  "EDN pack chunk map → response body + content-type based on Accept."
  [chunk accept]
  (if (bin/wants-binary? accept)
    {:content-type ct-chunk
     :body (bin/encode-pack-edn chunk)}
    {:content-type ct-edn
     :body (wire/write-edn chunk)}))

(defn- split-path-query
  "Return [path query-string-or-nil] from a path that may include ?query."
  [path]
  (let [i (when path (.indexOf ^String path (int \?)))]
    (if (and i (not (neg? i)))
      [(subs path 0 i) (subs path (inc i))]
      [path nil])))

(defn- parse-node-hex [path]
  (let [[p _] (split-path-query path)]
    (when (and p (.startsWith p "/node/") (> (count p) 6))
      (subs p 6))))

(defn- query-flag?
  "True if query string contains key=1/true/yes (e.g. raw=1)."
  [query key]
  (when query
    (boolean (re-find (re-pattern (str "(?i)(?:^|&)" key "=(?:1|true|yes)(?:&|$)"))
                      query))))

(defn handle-request
  "Request handler against a rooted store.
   Returns {:status n :body string-or-bytes :content-type s?} for testing.

   body — String (EDN) or byte[] (binary chunk or UTF-8 EDN).
   opts — {:content-type s :accept s}

   GET /node/{hex} returns a pack chunk by default. Pass ?raw=1 for bare node.
   Chunk GET/POST support wire-v1 binary when Content-Type / Accept indicate
   application/vnd.dacite.chunk.v1 (see docs/spec/wire-v1.md)."
  ([rooted method path body]
   (handle-request rooted method path body nil))
  ([rooted method path body {:keys [content-type accept
                                    pack-get-max-budget pack-get-max-starts]}]
   (try
     (let [[path-only query] (split-path-query path)
           accept (or accept "")]
       (cond
         (= "OPTIONS" method)
         {:status 204}

         (and (= "GET" method) (= path-only "/root"))
         (let [r (rs/root rooted)]
           {:status 200
            :content-type ct-edn
            :body (wire/write-edn {:root (when r (store/hash->hex r))})})

         (and (= "POST" method) (= path-only "/root/cas"))
         (let [data (parse-edn-body body)
               expected (when-let [e (:expected data)]
                          (when-not (nil? e) (store/hex->hash e)))
               new-h (store/hex->hash (:new data))]
           (if (rs/cas-root! rooted expected new-h)
             {:status 200 :content-type ct-edn :body (wire/write-edn {:ok true})}
             {:status 409 :content-type ct-edn :body (wire/write-edn {:ok false})}))

         ;; Bulk pack-get: demoted — admin/sync only
         (and (= "POST" method) (= path-only "/nodes/get"))
         (try
           (let [req (parse-edn-body body)
                 req (throttle/clamp-pack-get-req
                      req
                      (or pack-get-max-budget
                          (:pack-get-max-budget throttle/defaults))
                      (or pack-get-max-starts
                          (:pack-get-max-starts throttle/defaults)))
                 result (pack/pack-get rooted req)]
             {:status 200
              :content-type ct-edn
              :body (wire/write-edn (assoc result :ok true))})
           (catch Exception e
             {:status 400
              :content-type ct-edn
              :body (wire/write-edn {:ok false
                                     :error "malformed pack-get"
                                     :detail (.getMessage e)})}))

         (and (= "POST" method) (= path-only "/nodes"))
         (try
           (let [chunk (parse-chunk-body body content-type)
                 result (pack/apply-chunk! rooted chunk)]
             ;; Novelty stays EDN for now (small response)
             {:status 200
              :content-type ct-edn
              :body (wire/write-edn (assoc result :ok true))})
           (catch Exception e
             {:status 400
              :content-type ct-edn
              :body (wire/write-edn {:ok false
                                     :error "malformed chunk"
                                     :detail (.getMessage e)})}))

         (and (#{"GET" "PUT" "HEAD" "DELETE"} method) (parse-node-hex path-only))
         (let [hex (parse-node-hex path-only)
               h (store/hex->hash hex)
               raw? (query-flag? query "raw")]
           (case method
             "GET"
             (if raw?
               (if-let [node (store/s-get rooted h)]
                 {:status 200 :content-type ct-edn :body (wire/write-edn node)}
                 {:status 404})
               (if-let [chunk (pack/pack-under rooted h)]
                 (let [fmt (format-chunk-response chunk accept)]
                   {:status 200
                    :content-type (:content-type fmt)
                    :body (:body fmt)})
                 {:status 404}))

             "PUT"
             (try
               (let [node (parse-edn-body body)
                     nov (pack/put-node! rooted h node)]
                 {:status 200
                  :content-type ct-edn
                  :body (wire/write-edn (assoc nov :ok true))})
               (catch Exception e
                 {:status 400
                  :content-type ct-edn
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
          :content-type ct-edn
          :body (wire/write-edn {:error "not found" :path path})}))
     (catch Exception e
       {:status 500
        :content-type ct-edn
        :body (wire/write-edn {:error (.getMessage e)})}))))

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
      (send-bytes! ex (:status resp) (:content-type resp) bytes (:headers resp)))
    (let [body (:body resp)
          bs (cond
               (nil? body) nil
               (bytes? body) body
               (string? body) (utf8-bytes body)
               :else (utf8-bytes (pr-str body)))]
      (if bs
        (send-bytes! ex (:status resp) (:content-type resp) bs (:headers resp))
        (send-empty! ex (:status resp))))))

(defn- deny-response
  "Throttle deny map → HTTP response map."
  [{:keys [status error retry-after-s]}]
  (cond-> {:status status
           :content-type ct-edn
           :body (wire/write-edn (cond-> {:ok false :error error}
                                   retry-after-s (assoc :retry-after-s retry-after-s)))}
    retry-after-s (assoc :headers {"Retry-After" (str retry-after-s)})))

(defn- drain-body! [^HttpExchange ex]
  (try
    (.close (.getRequestBody ex))
    (catch Exception _ nil)))

(defn- make-executor [th]
  (if th
    (let [n (+ (long (get-in th [:opts :max-threads]))
               (long (get-in th [:opts :max-sse])))]
      (Executors/newFixedThreadPool (int (max 1 n))))
    (Executors/newCachedThreadPool)))

(defn- handle-plain-request!
  "Read body (unbounded) and dispatch handle-request. No admission."
  [^HttpExchange exchange rooted method path+q]
  (let [body (when (#{"PUT" "POST"} method) (read-body-bytes exchange))
        ct (header-val exchange "Content-type")
        accept (header-val exchange "Accept")
        resp (handle-request rooted method path+q body
                             {:content-type ct :accept accept})]
    (write-response! exchange resp)))

(defn- handle-throttled-request!
  "Admit, bounded-read, dispatch. Caller has already acquired API slot."
  [^HttpExchange exchange th rooted method path+q]
  (let [max-body (get-in th [:opts :max-body-bytes])
        cl (header-val exchange "Content-length")]
    (if (throttle/content-length-too-large? cl max-body)
      (do (drain-body! exchange)
          (write-response! exchange (deny-response (throttle/body-too-large))))
      (let [raw (when (#{"PUT" "POST"} method)
                  (throttle/read-body-limited (.getRequestBody exchange) max-body))]
        (if (= :too-large raw)
          (do (drain-body! exchange)
              (write-response! exchange (deny-response (throttle/body-too-large))))
          (let [ct (header-val exchange "Content-type")
                accept (header-val exchange "Accept")
                resp (handle-request
                      rooted method path+q raw
                      {:content-type ct
                       :accept accept
                       :pack-get-max-budget (get-in th [:opts :pack-get-max-budget])
                       :pack-get-max-starts (get-in th [:opts :pack-get-max-starts])})]
            (write-response! exchange resp)))))))

(defn- handle-exchange!
  [^HttpExchange exchange th rooted static hub]
  (let [method (.getRequestMethod exchange)
        uri (.getRequestURI exchange)
        path (.getPath uri)
        q (.getQuery uri)
        path+q (if q (str path "?" q) path)
        ck (when th
             (throttle/client-key (header-val exchange "Authorization")
                                  (remote-host exchange)))]
    (cond
      (and (= "GET" method) (= path "/events"))
      (if (nil? th)
        (handle-sse! exchange hub rooted)
        (let [adm (throttle/acquire-sse! th ck)]
          (if-not (:ok adm)
            (do (drain-body! exchange)
                (write-response! exchange (deny-response adm)))
            (try
              (handle-sse! exchange hub rooted)
              (finally
                (throttle/release-sse! th ck))))))

      (and (#{"GET" "HEAD"} method) (handle-static static path))
      (write-response! exchange (handle-static static path))

      (= "OPTIONS" method)
      (write-response! exchange {:status 204})

      (nil? th)
      (handle-plain-request! exchange rooted method path+q)

      :else
      (let [adm (throttle/acquire-api! th ck)]
        (if-not (:ok adm)
          (do (drain-body! exchange)
              (write-response! exchange (deny-response adm)))
          (try
            (handle-throttled-request! exchange th rooted method path+q)
            (finally
              (throttle/release-api! th ck))))))))

(defn start-server!
  "Start HttpServer.

   opts:
     :port (default 0 = ephemeral)
     :rooted — required RootedStore
     :static-dir — optional File/string for /app/* and /
     :throttle — true/nil (defaults on), false (off), or map merged
                 over dacite.service.throttle/defaults
   Returns {:server HttpServer :port int :base-url string :rooted ...}"
  [opts]
  (let [{:keys [port rooted static-dir] :or {port 0}} opts
        th (throttle/create (:throttle opts true))]
    (when-not rooted
      (throw (ex-info "rooted store required" {})))
    (let [static (when static-dir (io/file static-dir))
          hub (make-sse-hub rooted)
          executor (make-executor th)
          server (HttpServer/create (InetSocketAddress. port) 0)
          handler (reify HttpHandler
                    (handle [_ exchange]
                      (try
                        (handle-exchange! exchange th rooted static hub)
                        (catch Exception e
                          (try
                            (send-edn! exchange 500 {:error (.getMessage e)})
                            (catch Exception _ nil))))))]
      (.createContext server "/" handler)
      ;; Fixed pool sized for API threads + SSE slots so a watcher cannot
      ;; starve CAS / node I/O. Cached pool when throttle is off.
      (.setExecutor server executor)
      (.start server)
      (let [bound (.. server getAddress getPort)
            base (str "http://127.0.0.1:" bound)]
        {:server server
         :port bound
         :base-url base
         :rooted rooted
         :hub hub
         :throttle th
         :stop! (fn []
                  ((:close! hub))
                  (.stop server 0)
                  (.shutdownNow executor))}))))

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

(def default-file-path
  "Default directory when CLI uses --store file without a path."
  "target/dacite-service")

(def default-lmdb-path
  "Default LMDB env directory when CLI uses --store lmdb without a path."
  "target/dacite-service-lmdb")

(defn make-lmdb-rooted
  "LMDB content store + durable LMDB meta root cell under dir.

   Returns a map:
     :rooted  — IRootedStore for the HTTP handlers
     :close!  — fn to close the LMDB env (call on process/server stop)
     :backend — :lmdb
     :path    — env directory string"
  [dir]
  (let [path (str (or dir default-lmdb-path))
        _ (.mkdirs (io/file path))
        st (jvm/lmdb-store path)]
    {:rooted (rs/rooted-store st (jvm/lmdb-root-cell st))
     :close! (fn [] (jvm/lmdb-close st))
     :backend :lmdb
     :path path}))

(defn parse-store-spec
  "Parse --store argument into a backend description.

   Supported tokens:
     \"mem\"            → {:backend :mem}
     \"file\"           → {:backend :file :path default-file-path}
     \"file:<path>\"    → {:backend :file :path <path>}
     \"lmdb\"           → {:backend :lmdb :path default-lmdb-path}
     \"lmdb:<path>\"    → {:backend :lmdb :path <path>}
     nil / omitted      → same as \"file\" (default durable file store)

   Throws on unknown tokens (including bare paths without a type prefix)."
  [store-arg]
  (cond
    (nil? store-arg)
    {:backend :file :path default-file-path}

    (= "mem" store-arg)
    {:backend :mem}

    (= "file" store-arg)
    {:backend :file :path default-file-path}

    (and (string? store-arg) (.startsWith ^String store-arg "file:"))
    (let [p (subs store-arg 5)]
      (when (or (empty? p) (re-find #"^\s*$" p))
        (throw (ex-info "empty path after file:" {:store store-arg})))
      {:backend :file :path p})

    (= "lmdb" store-arg)
    {:backend :lmdb :path default-lmdb-path}

    (and (string? store-arg) (.startsWith ^String store-arg "lmdb:"))
    (let [p (subs store-arg 5)]
      (when (or (empty? p) (re-find #"^\s*$" p))
        (throw (ex-info "empty path after lmdb:" {:store store-arg})))
      {:backend :lmdb :path p})

    :else
    (throw (ex-info
            (str "unknown --store value \"" store-arg "\"; "
                 "use mem | file | file:<path> | lmdb | lmdb:<path>")
            {:store store-arg}))))

(defn make-service-rooted
  "Build rooted store for the HTTP service from a --store string.

   opts:
     :store — raw --store token (see parse-store-spec); nil defaults to file

   Returns {:rooted :close! :backend :path}
   where :close! may be nil (mem/file) and :path is nil for :mem."
  [{:keys [store]}]
  (let [spec (parse-store-spec store)]
    (case (:backend spec)
      :mem
      {:rooted (make-demo-rooted)
       :close! nil
       :backend :mem
       :path nil}

      :lmdb
      (make-lmdb-rooted (:path spec))

      :file
      (let [path (:path spec)]
        (.mkdirs (io/file path))
        {:rooted (make-file-rooted path)
         :close! nil
         :backend :file
         :path path}))))