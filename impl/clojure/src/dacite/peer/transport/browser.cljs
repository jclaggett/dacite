(ns dacite.peer.transport.browser
  "WebRTC `ITransport` over the browser's `RTCPeerConnection` (M5.1).

   Named data channels are reliable and ordered. Peering uses
   `\"dacite-peer\"`; the roots layer uses `\"dacite-roots\"`. Both sides
   create a channel with `negotiated: true` and the id from `channel-id`,
   so the label is the whole agreement and the transport never parses a
   frame. `bufferedAmount` is what the responder's 4-frame high and
   1-frame low marks read.

   `max-frame-limit` is nil. The 4096 cut (`frame/webrtc-max-frame`) is a
   node-datachannel 0.33.6 limit and stays in `dacite.peer.transport.webrtc`.
   The hello is clamped only to this channel's `maxMessageSize`, and a
   channel under 8192 still cannot carry a session (`msg/clamp-hello`).

   `(transport pc role)` wraps a connection that already exists.
   `(connect-pair)` builds two connections in this page, creates the two
   reserved channels, and exchanges offer, answer, and ICE in memory.
   Signaling to an nbb peer is M5.2; this namespace does not open a
   WebSocket or write a signal file."
  (:require [dacite.peer.transport :as t]
            [dacite.wire.binary :as bin]))

(def transport-name
  "The host object this adapter wraps."
  "RTCPeerConnection")

(def default-stun "stun:stun.l.google.com:19302")

(def channel-ids
  "SCTP ids for the reserved labels. Both sides use this map."
  {t/peer-channel 0
   t/roots-channel 1})

(def reserved-channels
  "Labels `connect-pair` creates before the offer, so one handshake
   carries both."
  [t/peer-channel t/roots-channel])

(defn channel-id
  "The negotiated id of `name`, or throws `:unknown-channel`."
  [name]
  (or (get channel-ids name)
      (throw (ex-info "unknown channel"
                      {:reason :unknown-channel :channel name}))))

(defn- fail! [reason text data]
  (throw (ex-info text (assoc data :reason reason))))

(defn- js-present?
  "False for nil, undefined, and JavaScript null. `js/null` is truthy in
   ClojureScript, and ICE signals the end of candidates with null."
  [x]
  (and (some? x)
       (not (undefined? x))
       (not (identical? x js/null))))

(defn- as-u8 [bs]
  (cond
    (instance? js/Uint8Array bs) bs
    (instance? js/ArrayBuffer bs) (js/Uint8Array. bs)
    :else (js/Uint8Array. (bin/as-wire-bytes bs))))

(defn- effective-max
  "Bytes `send` will accept. A channel that has not published a size yet
   uses 65536, the size browsers commonly guarantee."
  [dc]
  (let [m (.-maxMessageSize dc)]
    (if (and (number? m) (pos? m)) m 65536)))

(defn peer-connection
  "A new `RTCPeerConnection` for `config`. Tests replace this with a fake."
  [config]
  (new js/RTCPeerConnection config))

(defn- ice-config [{:keys [ice-servers stun?]}]
  (cond
    (some? ice-servers) #js {:iceServers (clj->js ice-servers)}
    stun? #js {:iceServers #js [#js {:urls default-stun}]}
    :else #js {:iceServers #js []}))

;; =============================================================================
;; Channel
;; =============================================================================

(defn- make-channel
  "An IChannel over an open RTCDataChannel. Messages that arrive before
   `on-message!` are kept and delivered when it is set."
  [name dc]
  (let [handler (atom nil)
        inbox (atom [])
        on-close (atom [])
        closed? (atom false)
        fire-close! (fn []
                      (when-not @closed?
                        (reset! closed? true)
                        (doseq [f @on-close] (f))
                        (reset! on-close [])))]
    (set! (.-binaryType dc) "arraybuffer")
    (set! (.-onmessage dc)
          (fn [ev]
            (let [data (.-data ev)
                  bs (if (instance? js/Uint8Array data)
                       data
                       (js/Uint8Array. data))]
              (if-let [f @handler]
                (f bs)
                (swap! inbox conj bs)))))
    (set! (.-onclose dc) fire-close!)
    (reify t/IChannel
      (channel-name [_] name)
      (send! [_ bs]
        (when (or @closed? (not= "open" (.-readyState dc)))
          (fail! :channel-closed "send on a closed channel" {:channel name}))
        (let [buf (as-u8 bs)
              max-n (effective-max dc)]
          (when (> (.-length buf) max-n)
            (fail! :message-too-large "message over the channel's max-message-size"
                   {:channel name :size (.-length buf) :max max-n}))
          (.send dc buf)
          nil))
      (on-message! [_ f]
        (reset! handler f)
        (doseq [m @inbox] (f m))
        (reset! inbox [])
        nil)
      (on-close! [_ f]
        (if @closed? (f) (swap! on-close conj f))
        nil)
      (buffered-amount [_] (or (.-bufferedAmount dc) 0))
      (max-message-size [_] (effective-max dc))
      (max-frame-limit [_] nil)
      (channel-open? [_] (and (not @closed?) (= "open" (.-readyState dc))))
      (close! [_]
        (when-not @closed?
          (try (.close dc) (catch :default _)))
        (fire-close!)
        nil))))

(defn- deliver-channel! [state name ch]
  (if-let [f (get-in @state [:on-channel name])]
    (f ch)
    (swap! state assoc-in [:unclaimed name] ch)))

(defn- ensure-dc!
  "Create the negotiated data channel `name` once. When it opens, hand the
   IChannel to `on-channel!` or keep it for `open-channel!`."
  [state pc name]
  (or (get-in @state [:dcs name])
      (let [delivered? (atom false)
            dc (.createDataChannel pc name
                                   #js {:ordered true
                                        :negotiated true
                                        :id (channel-id name)})
            opened! (fn []
                      (when-not @delivered?
                        (reset! delivered? true)
                        (deliver-channel! state name (make-channel name dc))))]
        (set! (.-binaryType dc) "arraybuffer")
        (set! (.-onopen dc) opened!)
        (swap! state assoc-in [:dcs name] dc)
        (when (= "open" (.-readyState dc))
          (opened!))
        dc)))

(defn- note-remote-channel! [state dc]
  (let [name (.-label dc)]
    (swap! state assoc-in [:dcs name] dc)
    (when (= "open" (.-readyState dc))
      (deliver-channel! state name (make-channel name dc)))))

;; =============================================================================
;; Connection
;; =============================================================================

(defn- make-transport
  "Build the ITransport. Returns `{:conn :prepare!}`. `prepare!` creates
   negotiated channels before the offer so they are in the handshake."
  [pc role]
  (let [state (atom {:role role :dcs {} :on-channel {} :unclaimed {}
                     :taken #{} :on-conn-close [] :open? true})
        fire-conn-close! (fn []
                           (let [[old _] (swap-vals! state
                                                     (fn [s]
                                                       (assoc s :open? false
                                                              :on-conn-close [])))]
                             (when (:open? old)
                               (doseq [[_ dc] (:dcs old)]
                                 (try (.close dc) (catch :default _)))
                               (doseq [f (:on-conn-close old)] (f)))))
        conn (reify t/ITransport
               (open-channel! [_ name]
                 (js/Promise.
                  (fn [resolve reject]
                    (try
                      (when-not (:open? @state)
                        (fail! :connection-closed
                               "open-channel! on a closed connection"
                               {:channel name}))
                      (when (contains? (:taken @state) name)
                        (fail! :channel-exists
                               "this side already opened the channel"
                               {:channel name}))
                      (swap! state update :taken conj name)
                      (ensure-dc! state pc name)
                      (if-let [ch (get-in @state [:unclaimed name])]
                        (do (swap! state update :unclaimed dissoc name)
                            (resolve ch))
                        (let [prev (get-in @state [:on-channel name])]
                          (swap! state assoc-in [:on-channel name]
                                 (fn [ch]
                                   (when prev (prev ch))
                                   (resolve ch)))))
                      (catch :default e (reject e))))))
               (on-channel! [_ name f]
                 (swap! state assoc-in [:on-channel name] f)
                 (when-let [ch (get-in @state [:unclaimed name])]
                   (swap! state update :unclaimed dissoc name)
                   (f ch))
                 nil)
               (on-connection-close! [_ f]
                 (if (:open? @state)
                   (swap! state update :on-conn-close conj f)
                   (f))
                 nil)
               (connection-open? [_] (boolean (:open? @state)))
               (close-connection! [_]
                 (fire-conn-close!)
                 (try (.close pc) (catch :default _))
                 nil))]
    (set! (.-onconnectionstatechange pc)
          (fn []
            (when (#{"closed" "failed"} (.-connectionState pc))
              (fire-conn-close!))))
    (set! (.-ondatachannel pc) (fn [ev] (note-remote-channel! state (.-channel ev))))
    {:conn conn
     :prepare! (fn [names]
                 (doseq [name names]
                   (ensure-dc! state pc name)))}))

(defn transport
  "An ITransport over an existing `RTCPeerConnection`.

   `role` is `:offerer` or `:answerer`. Both roles create a named channel
   the same way (`negotiated: true`). A side takes the channel with
   `open-channel!`, or receives it from `on-channel!` when it opens, not
   both: the second wait is not signaled again. `role` is recorded for
   the caller; the channel id does not depend on it."
  [pc role]
  (:conn (make-transport pc role)))

(defn- wire-ice! [from to]
  (set! (.-onicecandidate from)
        (fn [ev]
          (let [c (.-candidate ev)]
            (when (js-present? c)
              (.addIceCandidate to c))))))

(defn- negotiate! [offerer answerer]
  (-> (.createOffer offerer)
      (.then (fn [offer]
               (-> (.setLocalDescription offerer offer)
                   (.then (fn [] (.setRemoteDescription answerer offer))))))
      (.then (fn [] (.createAnswer answerer)))
      (.then (fn [answer]
               (-> (.setLocalDescription answerer answer)
                   (.then (fn [] (.setRemoteDescription offerer answer))))))))

(defn connect-pair
  "Two ends `[offerer answerer]` of one connection in this page.

   The reserved channels are created on both sides before the offer.
   Returns a promise of the two transports once the descriptions are
   set. A channel opens after ICE connects; `open-channel!` waits for
   that. Host candidates only, unless `:stun?` or `:ice-servers` is set.

   `:ice-servers` is a vector of RTCIceServer maps (`{:urls \"stun:...\"}`)."
  ([] (connect-pair nil))
  ([opts]
   (let [cfg (ice-config opts)
         pc1 (peer-connection cfg)
         pc2 (peer-connection cfg)
         a (make-transport pc1 :offerer)
         b (make-transport pc2 :answerer)]
     (wire-ice! pc1 pc2)
     (wire-ice! pc2 pc1)
     ((:prepare! a) reserved-channels)
     ((:prepare! b) reserved-channels)
     (-> (negotiate! pc1 pc2)
         (.then (fn [] [(:conn a) (:conn b)]))))))
