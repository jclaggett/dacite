(ns dacite.store.reveal
  "HTTP/3 client for one `POST /reveal` stream.

   Java 21's `HttpClient` has no `HTTP_3`. This namespace is loaded only
   when a remote store opts in, so a pack GET never touches these classes.
   One client keeps one connection per destination, which is one reveal
   session on the server. A second request while the first response is
   still open is another reveal on that session. Root CAS and
   `GET /events` stay on the JDK client."
  (:require [clojure.java.io :as io]
            [dacite.service.reveal :as rv]
            [dacite.store :as store]
            [dacite.wire :as wire]
            [dacite.wire.binary :as bin])
  (:import [java.util Arrays]
           [java.util.concurrent TimeUnit]
           [org.eclipse.jetty.client HttpClient InputStreamResponseListener
            StringRequestContent]
           [org.eclipse.jetty.http HttpVersion]
           [org.eclipse.jetty.http3.client HTTP3Client HTTP3ClientQuicConfiguration]
           [org.eclipse.jetty.http3.client.transport HttpClientTransportOverHTTP3]
           [org.eclipse.jetty.io ClientConnector]
           [org.eclipse.jetty.quic.quiche.client QuicheClientQuicConfiguration
            QuicheTransport]
           [org.eclipse.jetty.util.ssl SslContextFactory$Client]))

(def ^:private item-header-bytes
  "enc byte, 32-byte hash, and the u32 payload length."
  37)

(defn- delete-tree [^java.io.File f]
  (doseq [c (reverse (file-seq f))]
    (.delete c)))

(defn open-client
  "A started Jetty HTTP/3 client and a way to stop it.

   `:trust-all` accepts the self-signed dev certificate. One connection
   is kept per destination. `:close!` stops the client and deletes its
   QUIC directory."
  [{:keys [trust-all]}]
  (let [pem (io/file (System/getProperty "java.io.tmpdir")
                     (str "dacite-h3-remote-" (System/nanoTime)))
        _ (.mkdirs pem)
        ssl (doto (SslContextFactory$Client.)
              (.setTrustAll (boolean trust-all))
              (.setEndpointIdentificationAlgorithm
               (when-not trust-all "HTTPS")))
        connector (doto (ClientConnector.)
                    (.setSslContextFactory ssl))
        quic (HTTP3ClientQuicConfiguration/configure
              (QuicheClientQuicConfiguration. (.toPath pem)))
        http3 (HTTP3Client. quic connector)
        transport (HttpClientTransportOverHTTP3.
                   http3
                   (QuicheTransport. quic))
        client (doto (HttpClient. transport)
                 (.setMaxConnectionsPerDestination 1)
                 (.start))]
    {:client client
     :close! (fn []
               (try (.stop client) (catch Exception _ nil))
               (delete-tree pem))}))

(defn- u32-be
  "Big-endian u32 at `off`."
  [^bytes bs off]
  (long (bit-or (bit-shift-left (bit-and 0xff (aget bs off)) 24)
                (bit-shift-left (bit-and 0xff (aget bs (inc off))) 16)
                (bit-shift-left (bit-and 0xff (aget bs (+ off 2))) 8)
                (bit-and 0xff (aget bs (+ off 3))))))

(defn- split-items
  "Complete items in `bs`, and the trailing bytes of a partial item."
  [^bytes bs]
  (let [n (alength bs)]
    (loop [offset 0 items []]
      (let [avail (- n offset)]
        (if (< avail item-header-bytes)
          [items (Arrays/copyOfRange bs (int offset) n)]
          (let [total (+ item-header-bytes (u32-be bs (+ offset 33)))]
            (if (< avail total)
              [items (Arrays/copyOfRange bs (int offset) n)]
              (let [slice (Arrays/copyOfRange bs (int offset) (int (+ offset total)))]
                (recur (int (+ offset total))
                       (conj items (first (bin/decode-item-stream slice))))))))))))

(defn read-items!
  "Call `f` with each complete wire-v1 item as `in` arrives.

   Returns when the stream ends. A trailing partial item throws
   \"truncated wire message\"."
  [in f]
  (let [buf (byte-array 8192)
        acc (java.io.ByteArrayOutputStream.)]
    (loop []
      (let [n (.read ^java.io.InputStream in buf)]
        (if (neg? n)
          (when (pos? (.size acc))
            (throw (ex-info "truncated wire message" {})))
          (do
            (.write acc buf 0 n)
            (let [[items leftover] (split-items (.toByteArray acc))]
              (.reset acc)
              (when (pos? (alength ^bytes leftover))
                (.write acc leftover))
              (doseq [item items]
                (f item))
              (recur))))))))

(defn- install-stream!
  "Read items as their bytes arrive and store each one once it checks.

   `waiting` is an atom of items whose children are not stored yet. A
   clean end leaves them there, so a later stream on this store can
   verify them. A truncated item still throws."
  [in store waiting]
  (read-items! in (fn [item]
                    (swap! waiting
                           (fn [pending]
                             (rv/accept-ready! store (conj pending item)))))))

(defn- wait-for-sibling
  "Poll until `h` is stored, or until this call is the only one left.

   `sibling-wait`, when set, is delivered as the wait starts. A page the
   server already offered on another stream of this connection arrives
   there. Twenty seconds without it returns nil."
  [store h inflight sibling-wait]
  (when sibling-wait
    (deliver sibling-wait true))
  (let [deadline (+ (System/nanoTime) 20000000000)]
    (loop []
      (let [node (store/await (store/s-get store h))]
        (cond
          (some? node) node
          (or (<= (long @inflight) 1)
              (> (System/nanoTime) deadline))
          nil
          :else
          (do (Thread/sleep 10)
              (recur)))))))

(defn- apply-headers!
  [^org.eclipse.jetty.client.Request req headers]
  (.headers req
            (reify java.util.function.Consumer
              (accept [_ fields]
                (doseq [[k v] headers]
                  (.put fields (name k) (str v)))))))

(defn fetch!
  "POST `/reveal` for `h` and install the stream into `store`.

   `session` is `{:url :client :headers :waiting :inflight}`. `:waiting`
   is an atom of items a previous stream could not verify yet.
   `:inflight` counts reveals still running on this connection. When
   this stream ends without the node and another is still running, the
   call waits for that stream to store it. `:sibling-wait`, when set,
   is delivered as that wait starts. Returns the stored node, or nil
   when the server answers 404 or the node is not stored yet. There is
   no total timeout: the request fails when the connection is idle for
   20 seconds. Any other status rejects as `:unavailable`."
  [{:keys [url client headers waiting inflight sibling-wait]} h store]
  (let [waiting (or waiting (atom []))
        inflight (or inflight (atom 0))
        body (wire/write-edn {:hash (store/hash->hex h)})
        listener (InputStreamResponseListener.)
        req (-> (.newRequest client (str url "/reveal"))
                (.method "POST")
                (.version HttpVersion/HTTP_3)
                (.body (StringRequestContent. "application/edn" body))
                (.idleTimeout 20 TimeUnit/SECONDS))]
    (swap! inflight inc)
    (apply-headers! req headers)
    (.send req listener)
    (try
      (let [resp (.get listener 20 TimeUnit/SECONDS)
            status (.getStatus resp)]
        (cond
          (= 404 status) nil
          (= 200 status)
          (with-open [in (.getInputStream listener)]
            (install-stream! in store waiting)
            (or (store/await (store/s-get store h))
                (when (> (long @inflight) 1)
                  (wait-for-sibling store h inflight sibling-wait))))
          :else
          (throw (ex-info "store unavailable"
                          {:reason :unavailable
                           :hash h
                           :status status}))))
      (finally
        (swap! inflight dec)
        (try (.close listener) (catch Exception _ nil))))))
