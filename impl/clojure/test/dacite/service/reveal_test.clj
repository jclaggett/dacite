(ns dacite.service.reveal-test
  "The reveal stream: item bytes, hash checks, and one HTTP/3 session."
  (:require [clojure.test :refer [deftest is]]
            [dacite.reveal :as reveal]
            [dacite.service :as svc]
            [dacite.service.reveal :as rv]
            [dacite.store :as store]
            [dacite.store.remote :as remote]
            [dacite.store.reveal :as reveal-client]
            [dacite.value :as v]
            [dacite.wire :as wire]
            [dacite.wire.binary :as bin])
  (:import [java.util.concurrent TimeUnit]
           [org.eclipse.jetty.client InputStreamResponseListener
            StringRequestContent]
           [org.eclipse.jetty.http HttpVersion]))

(defn- post-reveal
  "POST /reveal and return {:status :type :version :items}."
  [client h3-url h]
  (let [body (wire/write-edn {:hash (store/hash->hex h)})
        listener (InputStreamResponseListener.)
        req (-> (.newRequest client (str h3-url "/reveal"))
                (.method "POST")
                (.body (StringRequestContent. "application/edn" body))
                (.timeout 20 TimeUnit/SECONDS)
                (.idleTimeout 20 TimeUnit/SECONDS))]
    (.send req listener)
    (let [resp (.get listener 20 TimeUnit/SECONDS)
          bs (try
               (.readAllBytes (.getInputStream listener))
               (finally
                 (.close listener)))]
      {:status (.getStatus resp)
       :type (str (.get (.getHeaders resp) "Content-Type"))
       :version (.getVersion resp)
       :items (bin/decode-item-stream bs)})))

(deftest pages-round-trip-as-wire-items
  (let [source (store/mem-store)
        h (v/hash (v/vector source
                            (v/i64 source 1)
                            (v/vector source (v/i64 source 2))))
        order (reveal/page-order source h)
        bytes (let [out (java.io.ByteArrayOutputStream.)]
                (doseq [page order]
                  (.write out (rv/encode-page source {:hash page})))
                (.toByteArray out))
        items (bin/decode-item-stream bytes)
        dest (rv/accept-items! (store/mem-store) items)]
    (is (= order (mapv :hash items)))
    (doseq [page order]
      (is (= (store/require-node source page)
             (store/require-node dest page))))))

(deftest a-bad-page-hash-is-refused
  (let [source (store/mem-store)
        h (v/hash (v/i64 source 7))
        item (first (bin/decode-item-stream (rv/encode-page source {:hash h})))
        wrong (assoc item :hash (v/hash (v/i64 source 8)))
        err (try
              (rv/accept-items! (store/mem-store) [wrong])
              nil
              (catch clojure.lang.ExceptionInfo e e))]
    (is (some? err))
    (is (re-find #"hash mismatch" (.getMessage err)))))

(deftest http3-reveal-shares-one-connection
  (let [rooted (svc/make-demo-rooted)
        h (v/hash (v/map rooted
                         (v/string rooted "a") (v/i64 rooted 1)
                         (v/string rooted "b") (v/i64 rooted 2)))
        order (reveal/page-order rooted h)
        {:keys [h3-url stop!]} (svc/start-server! {:port 0 :rooted rooted})
        primary (reveal-client/open-client {:trust-all true})
        fresh (reveal-client/open-client {:trust-all true})]
    (try
      (let [first (post-reveal (:client primary) h3-url h)
            again (post-reveal (:client primary) h3-url h)
            anew (post-reveal (:client fresh) h3-url h)
            pack (svc/handle-request rooted "GET"
                                     (str "/node/" (store/hash->hex h))
                                     nil
                                     {:accept bin/content-type-chunk-v1})]
        (is (= HttpVersion/HTTP_3 (:version first)))
        (is (= 200 (:status first)))
        (is (= rv/content-type (:type first)))
        (is (< 1 (count order)))
        (is (= order (mapv :hash (:items first))))
        (rv/accept-items! (store/mem-store) (:items first))
        (is (= 200 (:status again)))
        (is (empty? (:items again))
            "a page offered on this connection is not sent again")
        (is (= order (mapv :hash (:items anew)))
            "a new connection has its own session")
        (is (= 200 (:status pack)))
        (is (= bin/content-type-chunk-v1 (:content-type pack))))
      (finally
        ((:close! primary))
        ((:close! fresh))
        (stop!)))))

(deftest remote-get-reads-the-reveal-stream
  (let [rooted (svc/make-demo-rooted)
        h (v/hash (v/map rooted
                         (v/string rooted "a") (v/i64 rooted 1)
                         (v/string rooted "b") (v/i64 rooted 2)))
        order (reveal/page-order rooted h)
        child (first (remove #(= h %) order))
        {:keys [base-url h3-url stop!]} (svc/start-server! {:port 0 :rooted rooted})
        primary (reveal-client/open-client {:trust-all true})
        fresh (reveal-client/open-client {:trust-all true})
        via-reveal (remote/remote-store base-url
                                        {:reveal {:url h3-url
                                                  :client (:client primary)}})
        again (remote/remote-store base-url
                                   {:reveal {:url h3-url
                                             :client (:client fresh)}})]
    (try
      (let [node (store/await (store/s-get via-reveal h))]
        (is (= (store/require-node rooted h) node))
        (is (= (set order)
               (set (keys (store/await (store/s-snapshot (:pack-local via-reveal)))))))
        ((:close! primary))
        (is (= (store/require-node rooted child)
               (store/await (store/s-get via-reveal child)))
            "a page this stream stored is a later local read")
        (is (= (store/require-node rooted h)
               (store/await (store/s-get again h))))
        (is (= (set order)
               (set (keys (store/await (store/s-snapshot (:pack-local again)))))))
        (let [packed (remote/remote-store base-url)
              pack (svc/handle-request rooted "GET"
                                       (str "/node/" (store/hash->hex h))
                                       nil
                                       {:accept bin/content-type-chunk-v1})]
          (is (= (store/require-node rooted h)
                 (store/await (store/s-get packed h))))
          (is (= 200 (:status pack)))
          (is (= bin/content-type-chunk-v1 (:content-type pack)))))
      (finally
        ((:close! primary))
        ((:close! fresh))
        (stop!)))))
