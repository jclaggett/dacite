(ns dacite.examples.peer-web
  "Browser peer page (M5.1).

   Compile from the repo root:

     npm run peer:build

   Serve the origin and this page (from impl/clojure, after the build):

     clojure -M:peer-origin

   Open http://127.0.0.1:8080/app/peer/ . The page reads the origin's
   root, verifies every pack item into IndexedDB, and shows the root
   hash and the entry count.

   The peer namespaces, including `dacite.peer.transport.browser`, are
   on this build. A session with an nbb peer is M5.2."
  (:require [clojure.string :as str]
            [dacite.peer.bucket :as bucket]
            [dacite.peer.client :as client]
            [dacite.peer.frame :as frame]
            [dacite.peer.link :as link]
            [dacite.peer.msg :as msg]
            [dacite.peer.origin :as origin]
            [dacite.peer.rates :as rates]
            [dacite.peer.responder :as responder]
            [dacite.peer.transport :as t]
            [dacite.peer.transport.browser :as rtc]
            [dacite.peer.transport.mem :as mem]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.store.idb :as idb]
            [dacite.wire :as wire]
            [dacite.wire.binary :as bin]))

(def db-name
  "IndexedDB database for the verified local tier."
  "dacite-peer")

(def peer-build
  "Names this bundle compiled in. Reading them keeps the namespaces in
   the build; a missing one fails compilation."
  {:transport rtc/transport-name
   :channels rtc/reserved-channels
   :channel-ids rtc/channel-ids
   :stun rtc/default-stun
   :frame frame/max-frame
   :high-frames responder/high-frames
   :r-max client/default-r-max
   :interest rates/default-interest
   :kinds msg/kinds
   :bucket bucket/bucket
   :mem mem/connect
   :link link/links})

(defn- by-id [id]
  (.getElementById js/document id))

(defn- set-text! [id s]
  (when-let [el (by-id id)]
    (set! (.-textContent el) (str s))))

(defn- err-text [e]
  (let [reason (:reason (ex-data e))
        msg (or (ex-message e) (.-message e) (str e))]
    (if reason (str msg " (" (name reason) ")") msg)))

(defn- trim-base [base]
  (str/replace (or base "") #"/$" ""))

(defn fetch-root-hex
  "Hex root from `GET /root`, or nil when the origin has none."
  [base]
  (let [url (str (trim-base base) "/root")]
    (-> (js/fetch url #js {:method "GET" :cache "no-store"})
        (.then (fn [resp]
                 (if (= 200 (.-status resp))
                   (.text resp)
                   (throw (ex-info (str "origin " (.-status resp) " " url)
                                   {:reason :origin :status (.-status resp) :url url})))))
        (.then (fn [text] (:root (wire/read-edn text)))))))

(defn node-chunk
  "Wire-v1 bytes of `GET /node/{hex}`, or nil on 404.

   The same request the JVM test makes through `dacite.service/handle-request`
   with `Accept: application/vnd.dacite.chunk.v1`."
  [base h]
  (let [url (str (trim-base base) "/node/" (store/hash->hex h))]
    (-> (js/fetch url #js {:method "GET"
                           :headers #js {"Accept" bin/content-type-chunk-v1}
                           :cache "no-store"})
        (.then (fn [resp]
                 (cond
                   (= 200 (.-status resp)) (.arrayBuffer resp)
                   (= 404 (.-status resp)) nil
                   :else (throw (ex-info (str "origin " (.-status resp) " " url)
                                         {:reason :origin
                                          :status (.-status resp)
                                          :url url})))))
        (.then (fn [buf]
                 (when buf (js/Uint8Array. buf)))))))

(defn- show-build! []
  (set-text! "build"
             (str (:transport peer-build)
                  (if (exists? js/RTCPeerConnection) " present" " missing")
                  " · channel " t/peer-channel)))

(defn- verify-root! [st base hex]
  (let [root (store/hex->hash hex)]
    (chain/native
     (origin/verify-from-origin! st root #(node-chunk base %)))))

(defn start!
  "Reset IndexedDB, pull the origin's root, verify it, and show the hash
   and the entry count."
  []
  (show-build!)
  (set-text! "status" "loading…")
  (let [st (idb/idb-store db-name)
        base (or (.-DACITE_API_BASE js/window) "")]
    (-> (store/s-reset st)
        (.then (fn [_] (fetch-root-hex base)))
        (.then (fn [hex]
                 (if (seq hex)
                   (verify-root! st base hex)
                   (js/Promise.resolve :empty))))
        (.then (fn [result]
                 (if (= result :empty)
                   (set-text! "status" "origin has no root")
                   (do (set-text! "root" (store/hash->hex (:root result)))
                       (set-text! "entries" (:entries result))
                       (set-text! "status" "verified")))))
        (.catch (fn [e]
                  (set-text! "status" (str "failed: " (err-text e))))))))

(defonce started? (atom false))

(defn init
  "Page entry. shadow-cljs calls this when the script loads."
  []
  (when (compare-and-set! started? false true)
    (start!)))
