(ns dacite.peer.origin-test
  "M5.1: verify a value from the origin into a store.

   The fetch is the service's `GET /node` with the binary Accept header
   the browser page sends. No Jetty listener: `handle-request` is the
   same handler."
  (:require [clojure.test :refer [deftest is]]
            [dacite.examples.parity :as parity]
            [dacite.examples.peer-origin :as origin-demo]
            [dacite.peer.origin :as origin]
            [dacite.rooted :as rs]
            [dacite.rooted.gc :as gc]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.value :as v]
            [dacite.wire.binary :as bin]))

(defn- http-fetch
  "Chunk bytes `GET /node/{hex}` would return, or nil on 404."
  [rooted]
  (fn [h]
    (let [resp (svc/handle-request rooted "GET"
                                   (str "/node/" (store/hash->hex h))
                                   nil
                                   {:accept bin/content-type-chunk-v1})]
      (when (= 200 (:status resp))
        (:body resp)))))

(defn- varied-text
  "A string that does not collapse to one run, longer than one pack chunk."
  [n]
  (apply str (map (fn [i] (char (+ 32 (mod i 95)))) (range n))))

(defn- pulled [rooted root]
  (let [dst (store/mem-store)]
    (chain/result (origin/verify-from-origin! dst root (http-fetch rooted)))))

(deftest canonical-value-verifies-from-the-origin
  (let [{:keys [rooted root entries]} (origin-demo/seed-origin!)
        result (pulled rooted root)]
    (is (= (parity/canonical-hex) (store/hash->hex root)))
    (is (= root (:root result)))
    (is (= entries (:entries result)))
    (is (pos? entries))))

(deftest a-value-larger-than-one-chunk-is-verified
  (let [content (store/mem-store)
        rooted (rs/rooted-store content)
        root (v/hash (v/string content (varied-text 6000)))
        fetches (atom 0)
        fetch (fn [h]
                (swap! fetches inc)
                ((http-fetch rooted) h))
        result (chain/result (origin/verify-from-origin! (store/mem-store) root fetch))]
    (is (> @fetches 1) "one GET /node is not the whole value")
    (is (= root (:root result)))
    (is (= (count (gc/mark-reachable content root)) (:entries result)))))

(deftest a-bad-hash-is-not-stored
  (let [{:keys [rooted root]} (origin-demo/seed-origin!)
        good ((http-fetch rooted) root)
        chunk (bin/decode-chunk good)
        item (first (:items chunk))
        bad-hash (let [h (:hash item)
                       w (first h)]
                   (assoc h 0 (if (zero? w) 1 0)))
        bad (bin/encode-chunk (assoc chunk :items [(assoc item :hash bad-hash)]))
        dst (store/mem-store)
        e (try
            (chain/result (origin/verify-from-origin! dst root (fn [_] bad)))
            nil
            (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (contains? #{:bad-hash :bad-entry :bad-item :bad-state}
                   (:reason (ex-data e))))
    (is (nil? (store/await (store/s-get dst root))))))
