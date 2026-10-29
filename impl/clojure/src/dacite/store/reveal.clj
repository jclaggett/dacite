(ns dacite.store.reveal
  "HTTP/3 client for one `POST /reveal` stream.

   Java 21's `HttpClient` has no `HTTP_3`. This namespace is loaded only
   when a remote store opts in, so a pack GET never touches these classes.
   One client keeps one connection per destination, which is one reveal
   session on the server. Root CAS and `GET /events` stay on the JDK
   client."
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

(defn- install-stream!
  "Read items as their bytes arrive and store each one once it checks."
  [in store]
  (let [buf (byte-array 8192)
        acc (java.io.ByteArrayOutputStream.)]
    (loop [pending []]
      (let [n (.read in buf)]
        (if (neg? n)
          (do
            (when (pos? (.size acc))
              (throw (ex-info "truncated wire message" {})))
            (when (seq pending)
              (rv/accept-items! store pending)))
          (do
            (.write acc buf 0 n)
            (let [[items leftover] (split-items (.toByteArray acc))
                  waiting (rv/accept-ready! store (into pending items))]
              (.reset acc)
              (when (pos? (alength ^bytes leftover))
                (.write acc leftover))
              (recur waiting))))))))

(defn- apply-headers!
  [^org.eclipse.jetty.client.Request req headers]
  (.headers req
            (reify java.util.function.Consumer
              (accept [_ fields]
                (doseq [[k v] headers]
                  (.put fields (name k) (str v)))))))

(defn fetch!
  "POST `/reveal` for `h` and install the stream into `store`.

   `session` is `{:url :client :headers}`. Returns the stored node, or
   nil when the server answers 404. The call returns after the stream
   ends, so a later read of a page this stream delivered is local.
   Any other status rejects as `:unavailable`."
  [{:keys [url client headers]} h store]
  (let [body (wire/write-edn {:hash (store/hash->hex h)})
        listener (InputStreamResponseListener.)
        req (-> (.newRequest client (str url "/reveal"))
                (.method "POST")
                (.version HttpVersion/HTTP_3)
                (.body (StringRequestContent. "application/edn" body))
                (.timeout 20 TimeUnit/SECONDS)
                (.idleTimeout 20 TimeUnit/SECONDS))]
    (apply-headers! req headers)
    (.send req listener)
    (try
      (let [resp (.get listener 20 TimeUnit/SECONDS)
            status (.getStatus resp)]
        (cond
          (= 404 status) nil
          (= 200 status)
          (with-open [in (.getInputStream listener)]
            (install-stream! in store)
            (store/await (store/s-get store h)))
          :else
          (throw (ex-info "store unavailable"
                          {:reason :unavailable
                           :hash h
                           :status status}))))
      (finally
        (try (.close listener) (catch Exception _ nil))))))
