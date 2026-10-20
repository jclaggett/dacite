(ns dacite.service-test
  "Protocol tests for dacite.service handlers and the live HTTP server."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.remote :as remote]
            [dacite.store.client-cache :as client-cache]
            [dacite.store.pack :as pack]
            [dacite.value.collections :as coll]
            [dacite.value :as d]
            [dacite.value.types :as types]
            [dacite.wire :as wire]
            [dacite.wire.binary :as bin]
            [dacite.examples.todo :as todo])
  (:import [java.util.function Consumer]
           [org.eclipse.jetty.client ContentResponse HttpClient]
           [org.eclipse.jetty.http HttpVersion]
           [org.eclipse.jetty.http3.client HTTP3Client HTTP3ClientQuicConfiguration]
           [org.eclipse.jetty.http3.client.transport HttpClientTransportOverHTTP3]
           [org.eclipse.jetty.io ClientConnector]
           [org.eclipse.jetty.quic.quiche.client QuicheClientQuicConfiguration
            QuicheTransport]
           [org.eclipse.jetty.util.ssl SslContextFactory$Client]))

(deftest handle-request-node-and-root
  (let [rooted (svc/make-demo-rooted)
        h (store/hex->hash (apply str (repeat 64 "a")))
        node ["i64" 42]
        put (svc/handle-request rooted "PUT" (str "/node/" (store/hash->hex h))
                                (wire/write-edn node))]
    (is (= 200 (:status put)))
    (is (= :partial (:status (wire/read-edn (:body put)))))
    (let [put2 (svc/handle-request rooted "PUT" (str "/node/" (store/hash->hex h))
                                   (wire/write-edn node))]
      (is (= 200 (:status put2)))
      (is (= :complete (:status (wire/read-edn (:body put2))))
          "second PUT of same hash is complete (already exists)"))
    (let [got (svc/handle-request rooted "GET" (str "/node/" (store/hash->hex h)) nil)]
      (is (= 200 (:status got)))
      (is (true? (:dacite.wire/chunk-v1 (wire/read-edn (:body got))))
          "GET /node returns a chunk envelope"))
    (let [miss (svc/handle-request rooted "GET"
                                   (str "/node/" (apply str (repeat 64 "b"))) nil)]
      (is (= 404 (:status miss))))
    (let [r0 (svc/handle-request rooted "GET" "/root" nil)]
      (is (= 200 (:status r0)))
      (is (nil? (:root (wire/read-edn (:body r0))))))
    (let [hex (store/hash->hex h)
          cas-ok (svc/handle-request rooted "POST" "/root/cas"
                                     (wire/write-edn {:expected nil :new hex}))
          cas-bad (svc/handle-request rooted "POST" "/root/cas"
                                      (wire/write-edn {:expected nil :new hex}))]
      (is (= 200 (:status cas-ok)))
      (is (true? (:ok (wire/read-edn (:body cas-ok)))))
      (is (= 409 (:status cas-bad)))
      (is (false? (:ok (wire/read-edn (:body cas-bad)))))
      (let [r1 (svc/handle-request rooted "GET" "/root" nil)]
        (is (= hex (:root (wire/read-edn (:body r1)))))))))

(deftest handle-request-get-always-pack
  (let [rooted (svc/make-demo-rooted)
        vec (coll/vector-with-store rooted 1 2 3)
        vh (types/dacite-hash vec)
        hex (store/hash->hex vh)
        resp (svc/handle-request rooted "GET" (str "/node/" hex) nil)
        ignored (svc/handle-request rooted "GET"
                                    (str "/node/" hex "?raw=1&nodes=1")
                                    nil)
        chunk (wire/read-edn (:body resp))
        ignored-chunk (wire/read-edn (:body ignored))]
    (is (= 200 (:status resp)))
    (is (true? (:dacite.wire/chunk-v1 chunk)))
    (is (seq (:items chunk)))
    (is (= 200 (:status ignored)))
    (is (true? (:dacite.wire/chunk-v1 ignored-chunk))
        "query flags are ignored; GET stays pack-filled")))

(defn- http3-client
  "Jetty HTTP/3 client for the local self-signed connector.

   Java 21's java.net.http.HttpClient cannot speak HTTP/3. Quiche needs a
   PEM work directory even when peer verification is off. Returns
   {:client :pem}."
  []
  (let [pem (io/file (System/getProperty "java.io.tmpdir")
                     (str "dacite-h3-client-" (System/nanoTime)))
        _ (.mkdirs pem)
        ssl (doto (SslContextFactory$Client.)
              (.setTrustAll true)
              (.setEndpointIdentificationAlgorithm nil))
        connector (doto (ClientConnector.)
                    (.setSslContextFactory ssl))
        quic (HTTP3ClientQuicConfiguration/configure
              (QuicheClientQuicConfiguration. (.toPath pem)))
        http3 (HTTP3Client. quic connector)
        transport (HttpClientTransportOverHTTP3.
                   http3
                   (QuicheTransport. quic))]
    {:client (HttpClient. transport)
     :pem pem}))

(deftest live-server-http3-get-root
  (let [rooted (svc/make-demo-rooted)
        {:keys [h3-url stop!]} (svc/start-server! {:port 0 :rooted rooted})
        {:keys [client pem]} (http3-client)]
    (try
      (.start client)
      (let [^ContentResponse resp
            (-> (.newRequest client (str h3-url "/root"))
                (.method "GET")
                (.headers (reify Consumer
                            (accept [_ headers]
                              (.put headers "Accept" "application/edn"))))
                .send)
            alt (.get (.getHeaders resp) "Alt-Svc")]
        (is (= HttpVersion/HTTP_3 (.getVersion resp)))
        (is (= 200 (.getStatus resp)))
        (is (= {:root nil} (wire/read-edn (.getContentAsString resp))))
        (is (and alt (.startsWith alt "h3=")) alt)
        (is (nil? (.get (.getHeaders resp) "Strict-Transport-Security"))))
      (finally
        (try (.stop client) (catch Exception _ nil))
        (stop!)
        (doseq [f (reverse (file-seq pem))]
          (.delete f))))))

(deftest live-server-advertises-http3-on-cleartext
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url h3-port stop!]}
        (svc/start-server! {:port 0 :rooted rooted})
        tcp-port (Integer/parseInt (subs base-url (inc (.lastIndexOf base-url ":"))))]
    (try
      (is (= tcp-port h3-port) "HTTP/3 listens on the cleartext port number")
      (let [remote (remote/remote-store base-url)
            node ["bool" true]
            hex (apply str (repeat 64 "d"))
            h (store/hex->hash hex)]
        (store/s-put remote h node)
        (is (= node (store/s-get remote h)))
        (let [url (java.net.URI/create (str base-url "/root"))
              client (.build (java.net.http.HttpClient/newBuilder))
              req (-> (java.net.http.HttpRequest/newBuilder url) .GET .build)
              resp (.send client req
                          (java.net.http.HttpResponse$BodyHandlers/ofString))]
          (is (= 200 (.statusCode resp)))
          (is (= (str "h3=\":" h3-port "\"")
                 (.orElse (.firstValue (.headers resp) "alt-svc") nil)))))
      (finally
        (stop!)))))

(deftest live-server-remote-client-roundtrip
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [remote (remote/remote-store base-url)
            node ["bool" true]
            hex (apply str (repeat 64 "c"))
            h (store/hex->hash hex)]
        (store/s-put remote h node)
        (is (= node (store/s-get remote h)))
        (is (true? (store/s-has? remote h)))
        (is (nil? (remote/remote-get-root remote)))
        (is (true? (remote/remote-cas-root! remote nil h)))
        (is (= h (remote/remote-get-root remote)))
        (is (false? (remote/remote-cas-root! remote nil h))))
      (finally
        (stop!)))))

(deftest binary-chunk-post-and-get-accept
  (let [rooted (svc/make-demo-rooted)
        st (store/mem-store)
        v (coll/vector-with-store st 1 2 3)
        h (types/dacite-hash v)
        form (pack/literal-of st h)
        wire-chunk {:budget 1024
                    :items [{:enc :literal :hash h :literal form}]}
        body (bin/encode-chunk wire-chunk)
        post (svc/handle-request rooted "POST" "/nodes" body
                                 {:content-type bin/content-type-chunk-v1})]
    (is (= 200 (:status post)))
    (is (true? (:ok (wire/read-edn (:body post)))))
    (is (store/s-has? rooted h))
    (let [get-bin (svc/handle-request rooted "GET"
                                      (str "/node/" (store/hash->hex h))
                                      nil
                                      {:accept bin/content-type-chunk-v1})
          get-edn (svc/handle-request rooted "GET"
                                      (str "/node/" (store/hash->hex h))
                                      nil
                                      {:accept "application/edn"})]
      (is (= 200 (:status get-bin)))
      (is (= bin/content-type-chunk-v1 (:content-type get-bin)))
      (is (bytes? (:body get-bin)))
      (let [decoded (bin/decode-pack-edn (:body get-bin))]
        (is (true? (:dacite.wire/chunk-v1 decoded)))
        (is (seq (:items decoded))))
      (is (= 200 (:status get-edn)))
      (is (string? (:body get-edn)))
      (is (true? (:dacite.wire/chunk-v1 (wire/read-edn (:body get-edn))))))))

(deftest live-binary-wire-remote-write-back
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [raw (remote/remote-store base-url {:binary true})
            wb (client-cache/wrap raw :write-back)
            todos (todo/build wb (todo/seed-items))
            h (types/dacite-hash todos)]
        (is (true? (remote/remote-cas-root! wb nil h)))
        (is (store/s-has? raw h))
        (let [cold (remote/remote-store base-url {:binary true})
              node (store/s-get cold h)]
          (is (some? node))
          (is (= "vector" (types/entry-type node)))))
      (finally
        (stop!)))))

(deftest live-server-todo-domain-over-remote
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      ;; Build on remote store (write-through s-put), then CAS server root.
      (let [remote (remote/remote-store base-url)
            empty (coll/vector-with-store remote)
            t0 (todo/add-todo empty "browser demo item" false)
            t1 (todo/toggle-at t0 0)
            root-h (types/dacite-hash t1)
            cas (remote/remote-cas-root! remote nil root-h)]
        (is (true? cas))
        (is (= root-h (remote/remote-get-root remote)))
        (let [h (remote/remote-get-root remote)
              loaded (d/get-value remote h)]
          (is (= 1 (d/count loaded)))
          (is (= "browser demo item" (todo/title-str (d/nth loaded 0))))
          (is (true? (todo/done? (d/nth loaded 0))))))
      (finally
        (stop!)))))

(deftest parse-store-spec-tokens
  (is (= {:backend :mem} (svc/parse-store-spec "mem")))
  (is (= {:backend :file :path svc/default-file-path}
         (svc/parse-store-spec "file")))
  (is (= {:backend :file :path "target/my-file"}
         (svc/parse-store-spec "file:target/my-file")))
  (is (= {:backend :lmdb :path svc/default-lmdb-path}
         (svc/parse-store-spec "lmdb")))
  (is (= {:backend :lmdb :path "target/my-lmdb"}
         (svc/parse-store-spec "lmdb:target/my-lmdb")))
  (is (= {:backend :file :path svc/default-file-path}
         (svc/parse-store-spec nil))
      "omitted --store defaults to file")
  (is (thrown-with-msg? Exception #"unknown --store"
                        (svc/parse-store-spec "target/bare-path")))
  (is (thrown-with-msg? Exception #"empty path after file:"
                        (svc/parse-store-spec "file:")))
  (is (thrown-with-msg? Exception #"empty path after lmdb:"
                        (svc/parse-store-spec "lmdb:"))))

(deftest make-service-rooted-lmdb-backend
  (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "dacite-svc-lmdb-" (System/nanoTime))))
        {:keys [rooted close! backend path]}
        (svc/make-service-rooted {:store (str "lmdb:" dir)})]
    (try
      (is (= :lmdb backend))
      (is (= dir path))
      (is (some? rooted))
      (let [r (svc/handle-request rooted "GET" "/root" nil)]
        (is (= 200 (:status r)))
        (is (nil? (:root (wire/read-edn (:body r))))))
      (finally
        (when close! (close!))
        (doseq [f (reverse (file-seq (io/file dir)))]
          (.delete f))))))

(deftest lmdb-service-http-node-root-survives-reopen
  ;; Todo-demo protocol over LMDB: PUT/GET node, CAS root, reopen same path.
  (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "dacite-lmdb-http-" (System/nanoTime))))
        store-spec (str "lmdb:" dir)
        root-hex
        (let [{:keys [rooted close!]} (svc/make-service-rooted {:store store-spec})
              {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
          (try
            (let [remote (remote/remote-store base-url)
                  todos (todo/build remote (todo/seed-items))
                  h (types/dacite-hash todos)
                  hex (store/hash->hex h)]
              (is (true? (remote/remote-cas-root! remote nil h)))
              (is (= h (remote/remote-get-root remote)))
              (is (some? (store/s-get remote h)))
              hex)
            (finally
              (stop!)
              (when close! (close!)))))
        {:keys [rooted close!]} (svc/make-service-rooted {:store store-spec})
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    ;; reopen fresh LMDB env + server on same path
    (try
      (let [remote (remote/remote-store base-url)
            h (store/hex->hash root-hex)
            node (store/s-get remote h)
            loaded (d/get-value remote h)]
        (is (= h (remote/remote-get-root remote)))
        (is (store/s-has? remote h))
        (is (some? node))
        (is (= "vector" (types/entry-type node)))
        (is (pos? (d/count loaded))))
      (finally
        (stop!)
        (when close! (close!))
        (doseq [f (reverse (file-seq (io/file dir)))]
          (.delete f))))))
