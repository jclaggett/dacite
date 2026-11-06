(ns dacite.examples.peer-web
  "Browser peer page (M5.1 and M5.2).

   Compile from the repo root:

     npm run peer:build

   Origin only (M5.1), from impl/clojure:

     clojure -M:peer-origin

   Open http://127.0.0.1:8080/app/peer/ . The page reads the origin's
   root, verifies every pack item into IndexedDB, and shows the root
   hash and the entry count.

   nbb peer (M5.2): the query names a root the origin does not hold,
   plus the slice-3 relay. This page is the WebRTC offerer. It refuses
   to continue unless `GET /node/{root}` is 404, then asks the nbb peer
   for that root. The client verifies every item before storing it, and
   the page walks every reachable entry with `verify/checked?`.

     http://127.0.0.1:8080/app/peer/?root=HEX&signal=ws://127.0.0.1:8787&room=TOKEN

   `dacite.examples.peer-nbb` prints that URL. `npm run peer:e2e` runs
   the headless Chromium check."
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
            [dacite.peer.verify :as verify]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.store.idb :as idb]
            [dacite.value.types :as types]
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
    (set! (.-textContent el) (str s))
    (when (= id "status")
      (js/console.log "status" (str s)))))

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

(defn- set-attr! [id k v]
  (when-let [el (by-id id)]
    (.setAttribute el k (str v))))

(defn- verify-root! [st base hex]
  (let [root (store/hex->hash hex)]
    (chain/native
     (origin/verify-from-origin! st root #(node-chunk base %)))))

(defn- query-param
  "A non-empty query value, or nil. `URLSearchParams.get` returns
   JavaScript null when the key is absent, and null is truthy here."
  [k]
  (let [v (.get (js/URLSearchParams. (.-search js/location)) k)]
    (when (and (string? v) (pos? (count (str/trim v))))
      (str/trim v))))

(defn peer-query
  "The M5.2 parameters, or nil when this load is the origin page."
  []
  (let [root (query-param "root")
        signal (query-param "signal")
        room (query-param "room")]
    (when (and root signal room)
      {:root root :signal signal :room room})))

(defn- origin-absent!
  "A promise of 404 when the origin does not hold `hex`. A 200 is an
   error: that value must be read from the nbb peer, not from here."
  [base hex]
  (let [url (str (trim-base base) "/node/" hex)]
    (-> (js/fetch url #js {:method "GET"
                           :headers #js {"Accept" bin/content-type-chunk-v1}
                           :cache "no-store"})
        (.then (fn [resp]
                 (-> (.text resp)
                     (.then (fn [_]
                              (let [status (.-status resp)]
                                (cond
                                  (= 404 status) status
                                  (= 200 status)
                                  (throw (ex-info "the origin holds this value"
                                                  {:reason :origin-holds
                                                   :status status
                                                   :url url}))
                                  :else
                                  (throw (ex-info (str "origin " status " " url)
                                                  {:reason :origin
                                                   :status status
                                                   :url url}))))))))))))

(defn- check-reachable!
  "A step of how many nodes are reachable from `root`. Each one must
   already have passed verification. There is no key list on the store;
   the walk is `s-get` and `child-hashes`, the same set the nbb peer
   counts with `gc/mark-reachable`."
  [st root]
  (letfn [(step [queue live n]
            (if (pos? (count queue))
              (let [h (nth queue 0)
                    queue (subvec queue 1)
                    hk (gc/hash-key h)]
                (if (or (nil? h) (contains? live hk))
                  (step queue live n)
                  (chain/then
                   (verify/checked? st h)
                   (fn [ok]
                     (when-not ok
                       (throw (ex-info "an entry was stored without passing verification"
                                       {:reason :bad-entry :hash hk})))
                     (chain/then
                      (store/s-get st h)
                      (fn [node]
                        (when (nil? node)
                          (throw (ex-info "a reachable entry is missing"
                                          {:reason :missing :hash hk})))
                        (step (into queue
                                    (remove nil?)
                                    (or (types/child-hashes node) []))
                              (conj live hk)
                              (inc n))))))))
              n))]
    (step [root] #{} 0)))

(defn- poll! [pred ms limit]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(step [i]
               (try
                 (cond
                   (pred) (resolve i)
                   (>= i limit)
                   (reject (ex-info "timed out" {:reason :timeout :steps i}))
                   :else (js/setTimeout #(step (inc i)) ms))
                 (catch :default e (reject e))))]
       (step 0)))))

(defn- burst!
  "Send the fixed probe on `ch` and return the largest `buffered-amount`
   seen during that burst. The nbb peer sends the same count back."
  [ch]
  (let [buf (js/Uint8Array. t/probe-bytes)
        max-b (atom 0)]
    (dotimes [i t/probe-bytes]
      (aset buf i (bit-and (* i 17) 255)))
    (dotimes [_ t/probe-frames]
      (t/send! ch buf)
      (swap! max-b max (t/buffered-amount ch)))
    @max-b))

(defn- read-from-peer! [st base {:keys [root signal room]}]
  (set-text! "lede"
             (str "Reads a value held only by the nbb peer. "
                  "The origin does not have it. "
                  "Every entry is verified into IndexedDB."))
  (set-text! "status" "checking the origin…")
  (let [root-hash (store/hex->hash root)
        links (link/links)
        c (client/client (merge {:store st :now #(.now js/Date)}
                                (link/client-opts links)))
        timer (js/setInterval #(client/tick! c) 25)
        stop! #(js/clearInterval timer)
        peer (rtc/create-peer :offerer {:ice-servers []
                                        :ws {:url signal :room room}})]
    (-> (js/Promise.all
         #js [(origin-absent! base root)
              (:signaling peer)])
        (.then (fn [ready]
                 (let [status (aget ready 0)]
                   (set-text! "origin" (str status))
                   (set-attr! "origin" "data-status" status)
                   (set-text! "status" "connecting…")
                   nil)))
        (.then (fn [_]
                 (js/Promise.all
                  #js [(js/Promise.resolve
                        (link/open-client-channel! links c "nbb" (:conn peer) :offerer))
                       (t/open-channel! (:conn peer) t/roots-channel)])))
        (.then (fn [opened]
                 (let [roots (aget opened 1)
                       got (atom 0)
                       max-b (do (t/on-message! roots (fn [_] (swap! got inc)))
                                 (burst! roots))]
                   (set-attr! "flow" "data-max" max-b)
                   (set-text! "status" "reading…")
                   (-> (poll! #(some? (get-in @(:state c) [:sessions "nbb" :hello]))
                              25 800)
                       (.then (fn [_] (chain/native (client/request! c root-hash))))
                       (.then (fn [_]
                                (poll! (fn []
                                         (when (= :ended (client/session-status c "nbb"))
                                           (throw (ex-info "the session ended"
                                                           {:reason :session-ended})))
                                         (and (client/done? c root-hash)
                                              (= @got t/probe-frames)))
                                       25 800)))
                       (.then (fn [_]
                                (set-attr! "flow" "data-got" @got)
                                (set-text! "flow"
                                           (str @got " frames, buffered " max-b))
                                (set-attr! "status" "data-frame"
                                           (get-in @(:state c)
                                                   [:sessions "nbb" :limits :max-frame]))
                                (chain/native (check-reachable! st root-hash))))
                       (.then (fn [n]
                                (set-text! "root" root)
                                (set-text! "entries" n)
                                (set-text! "verified" n)
                                (set-attr! "entries" "data-count" n)
                                (set-attr! "verified" "data-count" n)
                                (set-text! "status" "verified from peer")))))))
        (.catch (fn [e]
                  (set-text! "status" (str "failed: " (err-text e)))))
        (.then (fn [_] (stop!))))))

(defn- read-from-origin! [st base]
  (-> (fetch-root-hex base)
      (.then (fn [hex]
               (if (seq hex)
                 (verify-root! st base hex)
                 (js/Promise.resolve :empty))))
      (.then (fn [result]
               (if (= result :empty)
                 (set-text! "status" "origin has no root")
                 (do (set-text! "root" (store/hash->hex (:root result)))
                     (set-text! "entries" (:entries result))
                     (set-text! "verified" (:entries result))
                     (set-text! "origin" "held by origin")
                     (set-text! "status" "verified")))))))

(defn start!
  "Reset IndexedDB. With `root`, `signal`, and `room` in the query, read
   that root from the nbb peer. Otherwise verify the origin's root."
  []
  (show-build!)
  (set-text! "status" "loading…")
  (let [st (idb/idb-store db-name)
        base (or (.-DACITE_API_BASE js/window) "")
        q (peer-query)]
    (-> (store/s-reset st)
        (.then (fn [_]
                 (if q
                   (read-from-peer! st base q)
                   (read-from-origin! st base))))
        (.catch (fn [e]
                  (set-text! "status" (str "failed: " (err-text e))))))))

(defonce started? (atom false))

(defn init
  "Page entry. shadow-cljs calls this when the script loads."
  []
  (when (compare-and-set! started? false true)
    (start!)))
