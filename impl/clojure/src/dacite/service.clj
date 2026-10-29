(ns dacite.service
  "HTTP service for Dacite content stores + root CAS (docs/design/service.md).

   Endpoints:
     GET  /node/{hex}   — pack-filled get (one chunk)
     PUT  /node/{hex}   — s-put (EDN body) → 200 novelty
     HEAD /node/{hex}   — s-has?
     DELETE /node/{hex} — s-delete (optional)
     POST /nodes        — apply one pack chunk (leaf-chunking write)
     POST /nodes/get    — pack-fetch: encode reachable → chunks (read)
     GET  /root         — {:root hex-or-nil}
     POST /root/cas     — {:expected hex-or-nil :new hex} → 200/409
     GET  /events       — SSE root announcements (text/event-stream)
     POST /reveal       — one reveal stream (wire-v1 items, paced)

   Cleartext HTTP/1.1 is the `base-url` (existing clients, browser demos).
   The same handler also speaks HTTP/3 on UDP, advertised with Alt-Svc.
   Also serves static files under /app/* from a configured directory (demo UI)."
  (:require [clojure.java.io :as io]
            [dacite.store :as store]
            [dacite.store.file :as file]
            [dacite.store.jvm :as jvm]
            [dacite.store.chunk :as chunk]
            [dacite.store.pack :as pack]
            [dacite.rooted :as rs]
            [dacite.wire :as wire]
            [dacite.wire.binary :as bin]
            [dacite.service.throttle :as throttle]
            [dacite.service.reveal :as rv])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [org.eclipse.jetty.http3.server HTTP3ServerConnectionFactory
            HTTP3ServerQuicConfiguration]
           [org.eclipse.jetty.io Content$Sink Content$Source]
           [org.eclipse.jetty.quic.common StreamEndPoint]
           [org.eclipse.jetty.quic.quiche.server QuicheServerConnector
            QuicheServerQuicConfiguration]
           [org.eclipse.jetty.server ConnectionFactory Handler$Abstract
            HttpConfiguration HttpConnectionFactory NetworkConnector
            NetworkConnector$Listener Request Response Server ServerConnector
            SecureRequestCustomizer]
           [org.eclipse.jetty.util Callback]
           [org.eclipse.jetty.util.ssl SslContextFactory$Server]
           [org.eclipse.jetty.util.thread QueuedThreadPool]))

(def ^:private ct-edn "application/edn; charset=utf-8")
(def ^:private ct-chunk bin/content-type-chunk-v1)

(defn- utf8-bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- header-val
  "Header value (case-insensitive name), or nil."
  [^Request request name]
  (when request
    (.get (.getHeaders request) ^String name)))

(defn- reveal-conn-key
  "The connection this request belongs to.

   On HTTP/3, `ConnectionMetaData.getConnection` is the stream, a new
   object for every request. The QUIC session on that stream's endpoint
   is the connection, and later reveals on it share one session. On
   HTTP/1.1 the metadata connection is already the TCP connection."
  [^Request request]
  (let [conn (.getConnection (.getConnectionMetaData request))
        ep (.getEndPoint conn)]
    (if (instance? StreamEndPoint ep)
      (.getSession (.getProtocolSession ^StreamEndPoint ep))
      conn)))

(defn- remote-host [^Request request]
  (when-let [addr (.getRemoteSocketAddress (.getConnectionMetaData request))]
    (when (instance? java.net.InetSocketAddress addr)
      (when-let [a (.getAddress ^java.net.InetSocketAddress addr)]
        (.getHostAddress a)))))

(defn- alt-svc
  "Advertise HTTP/3 on the UDP port once that connector is open."
  [^QuicheServerConnector h3]
  (let [p (.getLocalPort h3)]
    (when (pos? p)
      (str "h3=\":" p "\""))))

(defn- apply-common-headers!
  [^Response response ^QuicheServerConnector h3 extra-headers]
  (let [headers (.getHeaders response)]
    ;; CORS for browser demos (the page may be same-origin if served here).
    (.put headers "Access-Control-Allow-Origin" "*")
    (.put headers "Access-Control-Allow-Methods" "GET,PUT,POST,HEAD,DELETE,OPTIONS")
    (.put headers "Access-Control-Allow-Headers" "Content-Type,Authorization,Accept")
    (when-let [alt (alt-svc h3)]
      (.put headers "Alt-Svc" alt))
    (doseq [[k v] extra-headers]
      (.put headers (str k) (str v)))))

(defn- write-bytes!
  "Buffer `body` and send it with Content-Length. A nil or empty body
   completes with no content. Streaming a pack is a later milestone."
  [^Response response status content-type body extra-headers h3]
  (.setStatus response (int status))
  (apply-common-headers! response h3 extra-headers)
  (when content-type
    (.put (.getHeaders response) "Content-Type" (str content-type)))
  (when (and (bytes? body) (pos? (alength ^bytes body)))
    (let [^bytes bs body]
      (.put (.getHeaders response) "Content-Length" (str (alength bs)))
      (Content$Sink/write response true (ByteBuffer/wrap bs)))))

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
  "Hold the response open and stream root events until the client drops."
  [^Response response hub rooted h3]
  (let [{:keys [subs send! frame]} hub
        headers (.getHeaders response)]
    (.setStatus response 200)
    (.put headers "Content-Type" ct-sse)
    (.put headers "Cache-Control" "no-cache")
    (.put headers "Connection" "keep-alive")
    (apply-common-headers! response h3 nil)
    (let [os (Content$Sink/asOutputStream response)]
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

(defn handle-request
  "Request handler against a rooted store.
   Returns {:status n :body string-or-bytes :content-type s?} for testing.

   body — String (EDN) or byte[] (binary chunk or UTF-8 EDN).
   opts — {:content-type s :accept s}

   GET /node/{hex} returns a pack chunk (realized literals under the hash,
   BFS until the soft budget). Chunk GET/POST support wire-v1 binary when
   Content-Type / Accept indicate application/vnd.dacite.chunk.v1
   (see docs/spec/wire-v1.md)."
  ([rooted method path body]
   (handle-request rooted method path body nil))
  ([rooted method path body {:keys [content-type accept
                                    pack-get-max-budget pack-get-max-starts]}]
   (try
     (let [[path-only _] (split-path-query path)
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
               h (store/hex->hash hex)]
           (case method
             "GET"
             (if-let [chunk (pack/pack-under rooted h #{} pack/default-budget
                                             {:size-fn (if (bin/wants-binary? accept)
                                                         pack/wire-chunk-size
                                                         pack/chunk-size)})]
               (let [fmt (format-chunk-response chunk accept)]
                 {:status 200
                  :content-type (:content-type fmt)
                  :body (:body fmt)})
               {:status 404})

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
             (if (store/await (store/s-has? rooted h))
               {:status 200}
               {:status 404})

             "DELETE"
             (do (store/await (store/s-delete rooted h))
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

(defn- static-rel
  "Path relative to the static dir, or nil if this request is not static."
  [path]
  (cond
    (= path "/") "index.html"
    (or (= path "/app") (= path "/app/")) "app-index"
    (.startsWith path "/app/") (subs path 5)
    :else nil))

(defn- handle-static [^java.io.File static-dir path]
  (when static-dir
    (let [rel (static-rel path)]
      (cond
        (nil? rel) nil

        ;; `/app` without a slash must redirect so relative `js/main.js`
        ;; does not resolve as `/js/main.js`.
        (and (= rel "app-index") (not (.endsWith path "/")))
        {:status 301 :headers {"Location" "/app/"}}

        (= rel "app-index")
        (when-let [f (safe-child static-dir "index.html")]
          (when (.isFile f)
            {:status 200
             :content-type (content-type-for "index.html")
             :body-file f}))

        :else
        (when-let [f (safe-child static-dir rel)]
          (cond
            (.isFile f)
            {:status 200
             :content-type (content-type-for (.getName f))
             :body-file f}

            (.isDirectory f)
            (if (.endsWith path "/")
              (let [idx (io/file f "index.html")]
                (when (.isFile idx)
                  {:status 200
                   :content-type (content-type-for "index.html")
                   :body-file idx}))
              ;; `/app/explorer` otherwise serves HTML at a URL whose
              ;; relative `js/main.js` is the *todo* bundle.
              {:status 301 :headers {"Location" (str path "/")}})))))))

(defn- write-response!
  [^Response response resp h3]
  (if-let [f (:body-file resp)]
    (write-bytes! response (:status resp) (:content-type resp)
                  (.readAllBytes (io/input-stream f)) (:headers resp) h3)
    (let [body (:body resp)
          bs (cond
               (nil? body) nil
               (bytes? body) body
               (string? body) (utf8-bytes body)
               :else (utf8-bytes (pr-str body)))]
      (write-bytes! response (:status resp) (:content-type resp) bs
                    (:headers resp) h3))))

(defn- deny-response
  "Throttle deny map → HTTP response map."
  [{:keys [status error retry-after-s]}]
  (cond-> {:status status
           :content-type ct-edn
           :body (wire/write-edn (cond-> {:ok false :error error}
                                   retry-after-s (assoc :retry-after-s retry-after-s)))}
    retry-after-s (assoc :headers {"Retry-After" (str retry-after-s)})))

(defn- drain-body! [^Request request]
  (try
    (.close (Content$Source/asInputStream request))
    (catch Exception _ nil)))

(defn- request-body
  "Full request body, or nil when this method has none."
  [^Request request method]
  (when (#{"PUT" "POST"} method)
    (.readAllBytes (Content$Source/asInputStream request))))

(defn- handle-plain-request!
  "Read body (unbounded) and dispatch handle-request. No admission."
  [^Request request ^Response response rooted method path+q h3]
  (let [body (request-body request method)
        ct (header-val request "Content-Type")
        accept (header-val request "Accept")
        resp (handle-request rooted method path+q body
                             {:content-type ct :accept accept})]
    (write-response! response resp h3)))

(defn- handle-throttled-request!
  "Admit, bounded-read, dispatch. Caller has already acquired API slot."
  [^Request request ^Response response th rooted method path+q h3]
  (let [max-body (get-in th [:opts :max-body-bytes])
        cl (header-val request "Content-Length")]
    (if (throttle/content-length-too-large? cl max-body)
      (do (drain-body! request)
          (write-response! response (deny-response (throttle/body-too-large)) h3))
      (let [raw (when (#{"PUT" "POST"} method)
                  (throttle/read-body-limited
                   (Content$Source/asInputStream request) max-body))]
        (if (= :too-large raw)
          (do (drain-body! request)
              (write-response! response (deny-response (throttle/body-too-large)) h3))
          (let [ct (header-val request "Content-Type")
                accept (header-val request "Accept")
                resp (handle-request
                      rooted method path+q raw
                      {:content-type ct
                       :accept accept
                       :pack-get-max-budget (get-in th [:opts :pack-get-max-budget])
                       :pack-get-max-starts (get-in th [:opts :pack-get-max-starts])})]
            (write-response! response resp h3)))))))

(defn- serve-reveal!
  "Stream one reveal of the hash named by the EDN body.

   The body is `{:hash \"<hex>\"}`. The response has no Content-Length.
   Its bytes are wire-v1 items, written as the session clock covers them."
  [^Request request ^Response response rooted table params h3]
  (let [raw (try
              (.readAllBytes (Content$Source/asInputStream request))
              (catch Exception _ ::bad))
        h (when-not (= raw ::bad)
            (try
              (let [data (wire/read-edn (String. ^bytes raw StandardCharsets/UTF_8))
                    hex (:hash data)]
                (when-not (and (string? hex)
                               (= 64 (count hex))
                               (re-matches #"[0-9a-f]+" hex))
                  (throw (ex-info "reveal hash required" {})))
                (store/hex->hash hex))
              (catch Exception _ ::bad)))]
    (cond
      (or (= raw ::bad) (= h ::bad))
      (write-response! response
                       {:status 400
                        :content-type ct-edn
                        :body (wire/write-edn {:error "malformed reveal"})}
                       h3)

      (not (store/await (store/s-has? rooted h)))
      (write-response! response {:status 404} h3)

      :else
      (let [conn (rv/ensure-conn table
                                 (reveal-conn-key request)
                                 rooted
                                 params)
            headers (.getHeaders response)]
        (.setStatus response 200)
        (apply-common-headers! response h3 nil)
        (.put headers "Content-Type" rv/content-type)
        (.put headers "Cache-Control" "no-store")
        (let [out (Content$Sink/asOutputStream response)]
          (try
            (rv/begin-reveal! conn h out)
            (finally
              (try (.close out) (catch Exception _ nil)))))))))

(defn- handle-reveal!
  [^Request request ^Response response th rooted table params h3]
  (let [ck (when th
             (throttle/client-key (header-val request "Authorization")
                                  (remote-host request)))]
    (if (nil? th)
      (serve-reveal! request response rooted table params h3)
      (let [adm (throttle/acquire-api! th ck)]
        (if-not (:ok adm)
          (do (drain-body! request)
              (write-response! response (deny-response adm) h3))
          (try
            (let [max-body (get-in th [:opts :max-body-bytes])
                  cl (header-val request "Content-Length")]
              (if (throttle/content-length-too-large? cl max-body)
                (do (drain-body! request)
                    (write-response! response
                                     (deny-response (throttle/body-too-large))
                                     h3))
                (serve-reveal! request response rooted table params h3)))
            (finally
              (throttle/release-api! th ck))))))))

(defn- handle-request!
  [^Request request ^Response response th rooted static hub h3 table params]
  (let [method (.getMethod request)
        uri (.getHttpURI request)
        path (.getPath uri)
        q (.getQuery uri)
        path+q (if q (str path "?" q) path)
        ck (when th
             (throttle/client-key (header-val request "Authorization")
                                  (remote-host request)))]
    (cond
      (and (= "POST" method) (= path "/reveal"))
      (handle-reveal! request response th rooted table params h3)

      (and (= "GET" method) (= path "/events"))
      (if (nil? th)
        (handle-sse! response hub rooted h3)
        (let [adm (throttle/acquire-sse! th ck)]
          (if-not (:ok adm)
            (do (drain-body! request)
                (write-response! response (deny-response adm) h3))
            (try
              (handle-sse! response hub rooted h3)
              (finally
                (throttle/release-sse! th ck))))))

      (and (#{"GET" "HEAD"} method) (handle-static static path))
      (write-response! response (handle-static static path) h3)

      (= "OPTIONS" method)
      (write-response! response {:status 204} h3)

      (nil? th)
      (handle-plain-request! request response rooted method path+q h3)

      :else
      (let [adm (throttle/acquire-api! th ck)]
        (if-not (:ok adm)
          (do (drain-body! request)
              (write-response! response (deny-response adm) h3))
          (try
            (handle-throttled-request! request response th rooted method path+q h3)
            (finally
              (throttle/release-api! th ck))))))))

(defn- make-handler [th rooted static hub h3 table params]
  (proxy [Handler$Abstract] []
    (handle [^Request request ^Response response ^Callback callback]
      (try
        (handle-request! request response th rooted static hub h3 table params)
        (.succeeded callback)
        (catch Exception e
          (try
            (when-not (.isCommitted response)
              (write-bytes! response 500 ct-edn
                            (utf8-bytes (wire/write-edn {:error (.getMessage e)}))
                            nil h3))
            (catch Exception _ nil))
          (if (.isCommitted response)
            (.succeeded callback)
            (.failed callback e))))
      true)))

(def ^:private dev-keystore-password "dacite-dev")
(def ^:private dev-keystore-lock (Object.))

(defn- delete-tree [^java.io.File f]
  (when (and f (.exists f))
    (doseq [c (vec (.listFiles f))]
      (delete-tree c))
    (.delete f)))

(defn- dev-keystore-file
  "Self-signed PKCS12 for the local HTTP/3 connector.

   HTTP/3 is always TLS. The cert names localhost and 127.0.0.1. It lives
   under java.io.tmpdir so a 9p checkout is not mmapped."
  []
  (locking dev-keystore-lock
    (let [dir (io/file (System/getProperty "java.io.tmpdir") "dacite-http3")
          ks (io/file dir "dev.p12")]
      (when-not (.isFile ks)
        (.mkdirs dir)
        (let [keytool (.getAbsolutePath
                       (io/file (System/getProperty "java.home") "bin" "keytool"))
              pb (doto (ProcessBuilder.
                        [keytool
                         "-genkeypair" "-alias" "dacite"
                         "-keyalg" "EC" "-groupname" "secp256r1"
                         "-storetype" "PKCS12"
                         "-keystore" (.getAbsolutePath ks)
                         "-storepass" dev-keystore-password
                         "-keypass" dev-keystore-password
                         "-dname" "CN=localhost"
                         "-ext" "san=dns:localhost,ip:127.0.0.1"
                         "-ext" "eku=serverAuth"
                         "-validity" "3650"
                         "-noprompt"])
                   (.redirectErrorStream true))
              proc (.start pb)
              out (slurp (.getInputStream proc))
              code (.waitFor proc)]
          (when-not (zero? code)
            (.delete ks)
            (throw (ex-info "could not generate the HTTP/3 dev certificate"
                            {:exit code :out out})))))
      ks)))

(defn- make-thread-pool [th]
  ;; Sized for API threads + SSE slots so a watcher cannot starve CAS.
  ;; Throttle off uses Jetty's usual ceiling.
  (let [n (if th
            (+ (long (get-in th [:opts :max-threads]))
               (long (get-in th [:opts :max-sse])))
            200)]
    (doto (QueuedThreadPool. (int (max 1 n)) 1)
      (.setName "dacite-http"))))

(defn- make-http3-connector
  [^Server server ^SslContextFactory$Server ssl pem-dir]
  (let [secure (doto (HttpConfiguration.)
                 (.setSendServerVersion false)
                 (.setSendDateHeader false)
                 (.addCustomizer (SecureRequestCustomizer.)))
        factory (HTTP3ServerConnectionFactory. secure)
        quic (HTTP3ServerQuicConfiguration/configure
              (QuicheServerQuicConfiguration. (.toPath (io/file pem-dir))))]
    (QuicheServerConnector. server ssl quic
                            (into-array ConnectionFactory [factory]))))

(defn start-server!
  "Start the Dacite HTTP service.

   Cleartext HTTP/1.1 listens on `:port` (0 = ephemeral). HTTP/3 listens
   on UDP. When `:port` is 0 the UDP socket takes the TCP port the OS
   assigned, because one is TCP and the other is UDP. Responses carry
   `Alt-Svc: h3=\":<udp-port>\"`.

   opts:
     :port (default 0)
     :rooted — required RootedStore
     :static-dir — optional File/string for /app/* and /
     :throttle — true/nil (defaults on), false (off), or map merged
                 over dacite.service.throttle/defaults

   Returns {:server :port :base-url :h3-port :h3-url :keystore
            :keystore-password :rooted :hub :throttle :stop!}.
   `:base-url` stays http:// so existing clients do not change.
   `:h3-url` is https:// on the UDP port. The keystore is the self-signed
   dev certificate a test client can trust."
  [opts]
  (System/setProperty "org.eclipse.jetty.LEVEL" "WARN")
  (let [{:keys [port rooted static-dir] :or {port 0}} opts
        th (throttle/create (:throttle opts true))
        reveals (atom {})
        reveal-params (merge rv/default-params (:reveal opts))]
    (when-not rooted
      (throw (ex-info "rooted store required" {})))
    (let [static (when static-dir (io/file static-dir))
          hub (make-sse-hub rooted)
          ks (dev-keystore-file)
          pem-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "dacite-http3-pem-" (System/nanoTime)))
          _ (.mkdirs pem-dir)
          ssl (doto (SslContextFactory$Server.)
                (.setKeyStorePath (.getAbsolutePath ks))
                (.setKeyStoreType "PKCS12")
                (.setKeyStorePassword dev-keystore-password)
                (.setKeyManagerPassword dev-keystore-password))
          pool (make-thread-pool th)
          server (Server. pool)
          plain-config (doto (HttpConfiguration.)
                         (.setSendServerVersion false)
                         (.setSendDateHeader false))
          http (doto (ServerConnector.
                      server
                      (into-array ConnectionFactory
                                  [(HttpConnectionFactory. plain-config)]))
                 (.setPort (int port)))
          h3 (doto (make-http3-connector server ssl pem-dir)
               (.setPort (int port)))]
      (.addConnector server http)
      (.addConnector server h3)
      ;; Plain opens first. If the caller asked for port 0, copy the
      ;; assigned TCP port onto the UDP connector before it binds.
      (.addEventListener
       http
       (reify NetworkConnector$Listener
         (onOpen [_ connector]
           (when (zero? (.getPort h3))
             (.setPort h3 (.getLocalPort ^NetworkConnector connector))))))
      (.setHandler server (make-handler th rooted static hub h3
                                        reveals reveal-params))
      (try
        (.start server)
        (catch Exception e
          (try (.stop server) (catch Exception _ nil))
          (delete-tree pem-dir)
          (throw e)))
      (let [bound (.getLocalPort http)
            h3-port (.getLocalPort h3)]
        {:server server
         :port bound
         :base-url (str "http://127.0.0.1:" bound)
         :h3-port h3-port
         :h3-url (str "https://127.0.0.1:" h3-port)
         :keystore (.getAbsolutePath ks)
         :keystore-password dev-keystore-password
         :rooted rooted
         :hub hub
         :throttle th
         :stop! (fn []
                  (try
                    (rv/stop-all! reveals)
                    ((:close! hub))
                    (.stop server)
                    (finally
                      (delete-tree pem-dir))))}))))

(defn stop-server! [{:keys [stop!]}]
  (when stop! (stop!)))

(defn make-demo-rooted
  "In-memory rooted store for demos/tests."
  []
  (rs/rooted-store (store/mem-store) (rs/mem-root-cell)))

(defn make-file-rooted
  "File-backed content + file root cell under dir.

   Inner layout is pack literals; constructors write an overlay and root
   commit flushes."
  [dir]
  (let [path (str dir)]
    (rs/rooted-store (chunk/chunked (file/file-store path))
                     (rs/file-root-cell path))))

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
    {:rooted (rs/rooted-store (chunk/chunked st) (jvm/lmdb-root-cell st))
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