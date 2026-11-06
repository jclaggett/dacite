(ns dacite.peer.browser-test
  "Compiled-CLJS checks for the M5.1 browser peer.

   Run from the repo root: `npm run browser:test`.

   The transport runs against a fake `RTCPeerConnection` in this process.
   The acceptance check verifies a value into IndexedDB (`fake-indexeddb`)
   and reads it back from a reopened database."
  (:require [dacite.examples.peer-web :as page]
            [dacite.peer.client :as client]
            [dacite.peer.frame :as frame]
            [dacite.peer.link :as link]
            [dacite.peer.msg :as msg]
            [dacite.peer.origin :as origin]
            [dacite.peer.responder :as responder]
            [dacite.peer.transport :as t]
            [dacite.peer.transport.browser :as rtc]
            [dacite.reveal :as reveal]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.store.idb :as idb]
            [dacite.store.pack :as pack]
            [dacite.value :as v]
            [dacite.wire.binary :as bin]))

(defn- fail! [msg data]
  (throw (ex-info msg data)))

(defn- eq! [actual expected msg]
  (when-not (= actual expected)
    (fail! msg {:actual actual :expected expected})))

(defn- promise? [x]
  (instance? js/Promise x))

;; =============================================================================
;; Fake RTCPeerConnection
;; =============================================================================

(defn- install-fake-rtc! []
  (let [waiting (atom nil)]
    (letfn [(keys-of [obj]
              (let [ks (js/Object.keys obj)]
                (map (fn [i] (aget ks i)) (range (.-length ks)))))
            (close-dc! [^js dc]
              (when (not= "closed" (.-readyState dc))
                (set! (.-readyState dc) "closed")
                (when-let [f (.-onclose dc)] (f))
                (when-let [other (.-peer dc)]
                  (set! (.-peer dc) nil)
                  (set! (.-peer other) nil)
                  (close-dc! other))))
            (make-dc [label id]
              (let [dc #js {:label label
                            :readyState "connecting"
                            :bufferedAmount 0
                            :maxMessageSize 65536
                            :binaryType "arraybuffer"
                            :onopen nil
                            :onmessage nil
                            :onclose nil
                            :peer nil}]
                (set! (.-close dc) (fn [] (close-dc! dc)))
                (set! (.-send dc)
                      (fn [bytes]
                        (when (not= "open" (.-readyState dc))
                          (throw (js/Error. "send on a channel that is not open")))
                        (let [n (.-length bytes)
                              copy (.slice bytes)]
                          (set! (.-bufferedAmount dc) (+ (.-bufferedAmount dc) n))
                          (js/queueMicrotask
                           (fn []
                             (set! (.-bufferedAmount dc)
                                   (max 0 (- (.-bufferedAmount dc) n)))
                             (when-let [other (.-peer dc)]
                               (when-let [f (.-onmessage other)]
                                 (f #js {:data (.-buffer copy)}))))))))
                dc))
            (link-channels! [^js pc]
              (when (and (.-other pc) (.-remote pc) (.-remote (.-other pc)))
                (doseq [id (keys-of (.-channels pc))]
                  (let [^js dc (aget (.-channels pc) id)
                        ^js other (aget (.-channels (.-other pc)) id)]
                    (when (and other
                               (= "connecting" (.-readyState dc))
                               (= "connecting" (.-readyState other)))
                      (set! (.-peer dc) other)
                      (set! (.-peer other) dc)
                      (set! (.-readyState dc) "open")
                      (set! (.-readyState other) "open")
                      (when-let [f (.-onopen dc)] (f))
                      (when-let [f (.-onopen other)] (f)))))))
            (make-pc []
              (let [^js pc #js {:connectionState "new"
                            :channels #js {}
                            :other nil
                            :remote nil
                            :onicecandidate nil
                            :onconnectionstatechange nil
                            :ondatachannel nil}]
                (if-let [^js w @waiting]
                  (do (reset! waiting nil)
                      (set! (.-other pc) w)
                      (set! (.-other w) pc))
                  (reset! waiting pc))
                (set! (.-createDataChannel pc)
                      (fn [label opts]
                        (let [dc (make-dc label (.-id opts))]
                          (aset (.-channels pc) (str (.-id opts)) dc)
                          dc)))
                (set! (.-createOffer pc)
                      (fn [] (js/Promise.resolve #js {:type "offer" :sdp "offer"})))
                (set! (.-createAnswer pc)
                      (fn [] (js/Promise.resolve #js {:type "answer" :sdp "answer"})))
                (set! (.-setLocalDescription pc)
                      (fn [desc]
                        (set! (.-local pc) desc)
                        (js/Promise.resolve nil)))
                (set! (.-setRemoteDescription pc)
                      (fn [desc]
                        (set! (.-remote pc) desc)
                        (set! (.-connectionState pc) "connected")
                        (link-channels! pc)
                        (when-let [other (.-other pc)] (link-channels! other))
                        (js/Promise.resolve nil)))
                (set! (.-addIceCandidate pc)
                      (fn [_c] (js/Promise.resolve nil)))
                (set! (.-close pc)
                      (fn []
                        (when (not= "closed" (.-connectionState pc))
                          (set! (.-connectionState pc) "closed")
                          (doseq [id (keys-of (.-channels pc))]
                            (.close (aget (.-channels pc) id)))
                          (when-let [f (.-onconnectionstatechange pc)] (f))
                          (when-let [other (.-other pc)]
                            (set! (.-other pc) nil)
                            (set! (.-other other) nil)
                            (.close other)))))
                pc))]
      (set! rtc/peer-connection (fn [_config] (make-pc))))))

(defn- channels []
  (-> (rtc/connect-pair)
      (.then (fn [[a b]]
               (-> (js/Promise.all
                    #js [(t/open-channel! a t/peer-channel)
                         (t/open-channel! b t/peer-channel)
                         (t/open-channel! a t/roots-channel)
                         (t/open-channel! b t/roots-channel)])
                   (.then (fn [chs]
                            {:a a :b b
                             :peer-a (aget chs 0) :peer-b (aget chs 1)
                             :roots-a (aget chs 2) :roots-b (aget chs 3)})))))))

(defn- u8 [xs]
  (let [a (js/Uint8Array. (count xs))]
    (dotimes [i (count xs)] (aset a i (nth xs i)))
    a))

(defn- u8-vec [a]
  (mapv (fn [i] (aget a i)) (range (.-length a))))

(defn- next-turn []
  (js/Promise. (fn [ok _] (js/queueMicrotask ok))))

(defn transport-contract []
  (eq! (rtc/channel-id t/peer-channel) 0 "dacite-peer is id 0")
  (eq! (rtc/channel-id t/roots-channel) 1 "dacite-roots is id 1")
  (eq! (contains? rtc/channel-ids t/peer-channel) true "ids map has the peer channel")
  (let [unknown (try (rtc/channel-id "other") nil
                     (catch :default e e))]
    (eq! (:reason (ex-data unknown)) :unknown-channel "an unnamed channel is refused"))
  (-> (channels)
      (.then (fn [{:keys [a b peer-a peer-b roots-a roots-b]}]
               (let [again (t/open-channel! a t/peer-channel)]
                 (eq! (promise? again) true "open-channel! is a promise")
                 (-> again
                     (.then (fn [_] (fail! "a second open fulfilled" {}))
                            (fn [e]
                              (eq! (:reason (ex-data e)) :channel-exists
                                   "this side opens a channel once")))
                     (.then (fn []
                              (eq! (nil? (t/max-frame-limit peer-a)) true
                                   "the browser adapter does not apply the node-datachannel clamp")
                              (eq! (> (t/max-message-size peer-a) frame/min-max-frame) true
                                   "the channel can carry a node item")
                              (let [hello (msg/hello-for-channel
                                           (client/default-hello client/default-r-max)
                                           {:max-message-size (t/max-message-size peer-a)
                                            :max-frame-limit (t/max-frame-limit peer-a)})]
                                (eq! (get-in hello ["limits" "max-frame"]) frame/max-frame
                                     "hello keeps 16384 when the browser channel is large enough"))
                              (let [got (atom [])
                                    roots-got (atom 0)]
                                (t/on-message! peer-b (fn [bs] (swap! got conj bs)))
                                (t/on-message! roots-b (fn [_] (swap! roots-got inc)))
                                (t/send! peer-a (u8 [1]))
                                (t/send! peer-a (u8 [2 3]))
                                (t/send! roots-a (u8 [9]))
                                (let [held (t/buffered-amount peer-a)]
                                  (-> (next-turn)
                                      (.then (fn []
                                               (eq! (mapv u8-vec @got) [[1] [2 3]]
                                                    "peer messages arrive in order")
                                               (eq! @roots-got 1 "the roots channel has its own message")
                                               (eq! (count @got) 2 "a roots message is not a peer message")
                                               (eq! (pos? held) true "send reports buffered bytes immediately")
                                               (eq! (t/buffered-amount peer-a) 0 "the buffer drains after delivery")
                                               (let [closed (atom 0)]
                                                 (t/on-close! peer-b #(swap! closed inc))
                                                 (t/close! peer-a)
                                                 (eq! @closed 1 "closing one side closes the other")
                                                 (eq! (t/channel-open? peer-a) false "the channel is closed")
                                                 (let [sent (try (t/send! peer-a (u8 [1])) nil
                                                                 (catch :default e e))]
                                                   (eq! (:reason (ex-data sent)) :channel-closed
                                                        "send on a closed channel throws"))
                                                 (let [too-big (try (t/send! roots-a (js/Uint8Array. 70000)) nil
                                                                    (catch :default e e))]
                                                   (eq! (:reason (ex-data too-big)) :message-too-large
                                                        "a message over maxMessageSize is refused"))
                                                 (let [ends (atom 0)]
                                                   (t/on-connection-close! b #(swap! ends inc))
                                                   (t/close-connection! a)
                                                   (eq! @ends 1 "closing the connection ends the other side")
                                                   (eq! (t/connection-open? a) false "the offerer connection is closed")
                                                   (eq! (t/connection-open? b) false "the answerer connection is closed"))))))))))))))))

(defn- wait-until! [pred limit]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(step [i]
               (cond
                 (pred) (resolve i)
                 (>= i limit) (reject (ex-info "timed out" {:reason :timeout :steps i}))
                 :else (js/setTimeout #(step (inc i)) 5)))]
       (step 0)))))

(defn- tick-until! [r c advance! done? limit]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(step [i]
               (cond
                 (done?) (resolve i)
                 (>= i limit) (reject (ex-info "transfer did not finish" {:reason :timeout :steps i}))
                 :else (do (advance! 50)
                           (responder/tick! r)
                           (client/tick! c)
                           (js/setTimeout #(step (inc i)) 5))))]
       (step 0)))))

(defn peer-namespaces-over-the-browser-transport []
  (let [src (store/mem-store)
        root (v/hash (v/string src "m5.1"))
        dst (store/mem-store)]
    (-> (rtc/connect-pair)
        (.then (fn [[a b]]
                 (let [clock (atom 0)
                       now (fn [] @clock)
                       rl (link/links)
                       cl (link/links)
                       r (responder/responder (merge {:store src :now now}
                                                     (link/responder-opts rl)))
                       c (client/client (merge {:store dst :now now}
                                               (link/client-opts cl)))]
                   (link/serve! rl r "client" b)
                   (-> (js/Promise.resolve (link/open-client-channel! cl c "server" a))
                       (.then (fn [_]
                                (wait-until!
                                 #(some? (get-in @(:state c) [:sessions "server" :hello]))
                                 100)))
                       (.then (fn [_]
                                (client/request! c root)
                                (tick-until! r c #(swap! clock + %) #(client/done? c root) 80)))
                       (.then (fn [_]
                                (eq! (client/done? c root) true "the client verified the value")
                                (eq! (every? #(store/await (store/s-has? dst %))
                                             (reveal/page-order src root))
                                     true "every page is in the client store")
                                (eq! (client/session-status c "server") :open "the session stays open"))))))))))

;; =============================================================================
;; IndexedDB acceptance
;; =============================================================================

(defn- pack-fetch [src]
  (fn [h]
    (when-let [chunk (pack/pack-under src h)]
      (bin/encode-pack-edn chunk))))

(defn- snap-entries [st root]
  (-> (store/s-snapshot st)
      (.then (fn [snap] (origin/entry-count snap root)))))

(defn verifies-a-value-into-indexeddb []
  (let [src (store/mem-store)
        root (v/hash (v/vector src 1 "peer" (v/map src "k" 2)))
        live (count (gc/mark-reachable src root))
        name (str "dacite-m51-" (random-uuid))
        st (idb/idb-store name)]
    (-> (store/s-reset st)
        (.then (fn [_]
                 (chain/native (origin/verify-from-origin! st root (pack-fetch src)))))
        (.then (fn [result]
                 (eq! (:root result) root "the result names the root")
                 (eq! (:entries result) live "the entry count is the reachable nodes")
                 (eq! (store/hash->hex (:root result)) (store/hash->hex root) "root hex round-trips")
                 (idb/close st)
                 (let [again (idb/idb-store name)]
                   (-> (snap-entries again root)
                       (.then (fn [n]
                                (eq! n live "reopening IndexedDB still has every reachable entry")
                                (store/s-get again root)))
                       (.then (fn [node]
                                (eq! (some? node) true "the root entry is in IndexedDB")
                                (idb/close again))))))))))

(defn page-entry-is-a-function []
  (eq! (fn? page/init) true "the static page has an init")
  (eq! (:transport page/peer-build) rtc/transport-name "the page names the browser transport")
  (js/Promise.resolve nil))

(defn main [& _]
  (js/require "fake-indexeddb/auto")
  (install-fake-rtc!)
  (-> (transport-contract)
      (.then peer-namespaces-over-the-browser-transport)
      (.then verifies-a-value-into-indexeddb)
      (.then page-entry-is-a-function)
      (.then (fn [_] (println "browser peer tests passed")))
      (.catch (fn [e]
                (js/console.error e)
                (js/process.exit 1)))))
