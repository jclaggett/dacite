(ns dacite.service.throttle-test
  "Per-client inbound throttle: isolation, 413, pack-get clamp, 429 retry."
  (:require [clojure.test :refer [deftest is]]
            [dacite.service :as svc]
            [dacite.service.throttle :as throttle]
            [dacite.store :as store]
            [dacite.store.remote :as remote]
            [dacite.wire :as wire])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(deftest client-key-prefers-bearer
  (is (= "bearer:alice" (throttle/client-key "Bearer alice" "127.0.0.1")))
  (is (= "bearer:alice" (throttle/client-key "bearer alice" "10.0.0.1")))
  (is (= "ip:127.0.0.1" (throttle/client-key nil "127.0.0.1")))
  (is (= "ip:unknown" (throttle/client-key nil nil))))

(deftest take-tokens-isolated-per-client
  (let [th (throttle/create {:client-burst 2
                             :client-rate 0.001
                             :client-inflight 8
                             :max-threads 8})]
    (is (:ok (throttle/take-request-token! th "bearer:a")))
    (is (:ok (throttle/take-request-token! th "bearer:a")))
    (let [denied (throttle/take-request-token! th "bearer:a")]
      (is (false? (:ok denied)))
      (is (= 429 (:status denied))))
    (is (:ok (throttle/take-request-token! th "bearer:b"))
        "other client has its own burst")))

(deftest inflight-cap-is-per-client
  (let [th (throttle/create {:client-burst 100
                             :client-rate 50
                             :client-inflight 1
                             :max-threads 8})]
    (is (:ok (throttle/acquire-api! th "bearer:a")))
    (let [denied (throttle/acquire-api! th "bearer:a")]
      (is (false? (:ok denied)))
      (is (= 429 (:status denied))))
    (is (:ok (throttle/acquire-api! th "bearer:b"))
        "other client has its own inflight slot")
    (throttle/release-api! th "bearer:a")
    (throttle/release-api! th "bearer:b")
    (is (:ok (throttle/acquire-api! th "bearer:a"))
        "release frees the slot")
    (throttle/release-api! th "bearer:a")))

(deftest clamp-pack-get-budget-and-starts
  (let [clamped (throttle/clamp-pack-get-req
                 {:budget 999999
                  :roots (mapv str (range 10))
                  :hashes ["x" "y"]}
                 100
                 4)]
    (is (= 100 (:budget clamped)))
    (is (= 4 (count (:roots clamped))))
    (is (= [] (:hashes clamped)))))

(deftest read-body-limited-too-large
  (let [in (java.io.ByteArrayInputStream. (byte-array 20))]
    (is (= :too-large (throttle/read-body-limited in 8))))
  (let [in (java.io.ByteArrayInputStream. (byte-array (repeat 4 (byte 1))))
        bs (throttle/read-body-limited in 8)]
    (is (bytes? bs))
    (is (= 4 (alength bs)))))

(defn- raw-http
  "One HTTP call, no 429 retry. Returns {:status :body :retry-after}."
  ([url method]
   (raw-http url method nil nil))
  ([url method headers]
   (raw-http url method headers nil))
  ([url method headers ^bytes body]
   (let [client (.build (.. (HttpClient/newBuilder)
                            (connectTimeout (Duration/ofSeconds 5))))
         builder (.. (HttpRequest/newBuilder)
                     (uri (URI/create url))
                     (timeout (Duration/ofSeconds 5))
                     (method method
                             (if body
                               (HttpRequest$BodyPublishers/ofByteArray body)
                               (HttpRequest$BodyPublishers/noBody))))
         builder (reduce (fn [b [k v]]
                           (.header b (name k) (str v)))
                         builder
                         headers)
         ^HttpResponse resp (.send client (.build builder)
                                   (HttpResponse$BodyHandlers/ofByteArray))
         ra (.firstValue (.headers resp) "Retry-After")]
     {:status (.statusCode resp)
      :body (.body resp)
      :retry-after (when (.isPresent ra) (.get ra))})))

(deftest handle-request-clamps-pack-get-budget
  (let [rooted (svc/make-demo-rooted)
        resp (svc/handle-request rooted "POST" "/nodes/get"
                                 (wire/write-edn {:roots [] :budget 999999})
                                 {:pack-get-max-budget 128
                                  :pack-get-max-starts 8})]
    (is (= 200 (:status resp)))
    (is (= 128 (:budget (wire/read-edn (:body resp)))))))

(deftest live-per-client-isolation
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]}
        (svc/start-server! {:port 0
                            :rooted rooted
                            :throttle {:client-burst 2
                                       :client-rate 0.01
                                       :client-inflight 8
                                       :max-threads 8}})]
    (try
      (let [url (str base-url "/root")
            a {"Authorization" "Bearer a"}
            b {"Authorization" "Bearer b"}
            r1 (raw-http url "GET" a)
            r2 (raw-http url "GET" a)
            r3 (raw-http url "GET" a)
            rb (raw-http url "GET" b)
            r3b (raw-http url "GET" a)]
        (is (= 200 (:status r1)))
        (is (= 200 (:status r2)))
        (is (= 429 (:status r3)) "A burned its burst")
        (is (some? (:retry-after r3)))
        (is (= 200 (:status rb)) "B is a different bucket")
        (is (= 429 (:status r3b)) "shared key stays limited"))
      (finally
        (stop!)))))

(deftest live-other-client-served-during-flood
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]}
        (svc/start-server! {:port 0
                            :rooted rooted
                            :throttle {:client-burst 2
                                       :client-rate 2.0
                                       :client-inflight 1
                                       :max-threads 8}})]
    (try
      (let [url (str base-url "/root")
            a {"Authorization" "Bearer flood"}
            b {"Authorization" "Bearer polite"}
            floods (mapv (fn [_]
                           (future (raw-http url "GET" a)))
                         (range 16))
            t0 (System/currentTimeMillis)
            polite (raw-http url "GET" b)
            elapsed (- (System/currentTimeMillis) t0)
            flood-statuses (mapv (comp :status deref) floods)]
        (is (= 200 (:status polite)))
        (is (< elapsed 200) "polite client is not queued behind the flood")
        (is (some #{429} flood-statuses) "flooder is 429'd"))
      (finally
        (stop!)))))

(deftest live-413-body-too-large
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]}
        (svc/start-server! {:port 0
                            :rooted rooted
                            :throttle {:max-body-bytes 16
                                       :client-burst 20
                                       :client-rate 50}})]
    (try
      (let [hex (apply str (repeat 64 "a"))
            url (str base-url "/node/" hex)
            body (.getBytes (apply str (repeat 80 "x")) "UTF-8")
            resp (raw-http url "PUT"
                           {"Content-Type" "application/edn"
                            "Authorization" "Bearer fat"}
                           body)]
        (is (= 413 (:status resp)))
        (is (nil? (store/s-get rooted (store/hex->hash hex)))))
      (finally
        (stop!)))))

(deftest live-remote-retries-429
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]}
        (svc/start-server! {:port 0
                            :rooted rooted
                            :throttle {:client-burst 1
                                       :client-rate 20.0
                                       :client-inflight 8
                                       :max-threads 8}})]
    (try
      (let [remote (remote/remote-store base-url {:token "retry-me" :binary false})
            h1 (store/hex->hash (apply str (repeat 64 "1")))
            h2 (store/hex->hash (apply str (repeat 64 "2")))]
        (store/s-put remote h1 ["i64" 1])
        (store/s-put remote h2 ["i64" 2])
        (is (= ["i64" 1] (store/s-get remote h1)))
        (is (store/s-has? rooted h2)))
      (finally
        (stop!)))))

(deftest live-defaults-do-not-trip-existing-path
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [remote (remote/remote-store base-url)
            h (store/hex->hash (apply str (repeat 64 "c")))]
        (store/s-put remote h ["bool" true])
        (is (= ["bool" true] (store/s-get remote h)))
        (is (true? (remote/remote-cas-root! remote nil h))))
      (finally
        (stop!)))))
