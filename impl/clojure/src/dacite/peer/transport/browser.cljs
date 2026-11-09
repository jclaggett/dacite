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
   reserved channels with negotiated ids, and exchanges offer, answer,
   and ICE in memory.

   `(create-peer)` is one end of a session with another process (M5.2).
   The browser is the offerer: it creates `dacite-peer` and `dacite-roots`
   in-band (no negotiated id) before the offer, which is what an nbb
   answerer receives on `onDataChannel`. Signaling is the slice-3 room
   (`signal-over-ws!`): a shared token, not origin discovery. A pasted
   answer uses `apply-remote!` on the same signal map (M6.4). The 4096
   frame clamp stays on the nbb peer. `bufferedAmount` is still what a
   responder's 4/1 marks read; this side's channel reports it too."
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
  "False for nil and undefined. A JavaScript null from ICE or
   `localDescription` is nil here. Do not mention `js/null`: shadow-cljs
   compiles that name to a var that is not defined."
  [x]
  (and (some? x)
       (not (undefined? x))))

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
  "Create data channel `name` once. When it opens, hand the IChannel to
   `on-channel!` or keep it for `open-channel!`.

   In-band (a remote nbb peer) the offerer calls `createDataChannel` with
   no id, so the channel is in the offer and the answerer receives it.
   Otherwise both sides use the negotiated id from `channel-id`."
  [state pc name]
  (or (get-in @state [:dcs name])
      (let [delivered? (atom false)
            dc (if (and (:in-band? @state) (= (:role @state) :offerer))
                 (.createDataChannel pc name #js {:ordered true})
                 (.createDataChannel pc name
                                     #js {:ordered true
                                          :negotiated true
                                          :id (channel-id name)}))
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
  (let [name (.-label dc)
        deliver! (fn []
                   (when-not (get-in @state [:made name])
                     (swap! state assoc-in [:made name] true)
                     (deliver-channel! state name (make-channel name dc))))]
    (swap! state assoc-in [:dcs name] dc)
    (cond
      (= "open" (.-readyState dc)) (deliver!)
      (:in-band? @state) (set! (.-onopen dc) deliver!))))

;; =============================================================================
;; Connection
;; =============================================================================

(defn- make-transport
  "Build the ITransport. Returns `{:conn :prepare!}`. `prepare!` creates
   channels before the offer so they are in the handshake.

   `:in-band?` (default false) is the remote-peer path: the offerer creates
   channels without a negotiated id, and the answerer waits for
   `ondatachannel`."
  ([pc role] (make-transport pc role nil))
  ([pc role {:keys [in-band?]}]
   (let [state (atom {:role role :in-band? (boolean in-band?)
                      :dcs {} :on-channel {} :unclaimed {} :made {}
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
                      (when-not (and (:in-band? @state) (= role :answerer))
                        (ensure-dc! state pc name))
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
                   (ensure-dc! state pc name)))})))

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

;; =============================================================================
;; One end, for an nbb peer (M5.2)
;; =============================================================================

(defn- publish-local! [signal pc]
  (let [d (.-localDescription pc)]
    (when (js-present? d)
      (swap! signal assoc :description {:type (.-type d) :sdp (.-sdp d)}))))

(defn- start-offer! [pc signal]
  (-> (.createOffer pc)
      (.then (fn [offer] (.setLocalDescription pc offer)))
      (.then (fn [] (publish-local! signal pc)))))

(defn- add-cand! [pc {:keys [candidate mid]}]
  (when (and (string? candidate) (pos? (count candidate)))
    (-> (.addIceCandidate pc (clj->js {:candidate candidate
                                       :sdpMid (or mid "")
                                       :sdpMLineIndex 0}))
        (.catch (fn [e] (js/console.error "ice candidate" e))))))

(defn- apply-description! [peer remote opened! failed!]
  (let [pc (:pc peer)
        d (:description remote)
        answer? (= (:role peer) :answerer)
        desc (clj->js {:type (:type d) :sdp (:sdp d)})
        applied (if answer?
                  (-> (.setRemoteDescription pc desc)
                      (.then (fn [] (.createAnswer pc)))
                      (.then (fn [answer] (.setLocalDescription pc answer)))
                      (.then (fn [] (publish-local! (:signal peer) pc))))
                  (.setRemoteDescription pc desc))]
    (-> applied
        (.then (fn [] (opened!)))
        (.catch (fn [e] (failed! e))))))

(defn signal-over-ws!
  "Relay `peer`'s offer or answer and ICE candidates through the slice-3
   server. `peer` is a `create-peer` result. `url` is `ws://host:port`.
   `room` is the shared token. `role` defaults to the peer's role.

   Returns a promise that resolves once the remote description has been
   applied. Later candidates still flow. Rejects on a room error, a
   timeout, or the other peer leaving before that description arrives.
   The JSON frames match `scripts/webrtc-signal-server.mjs`."
  [peer {:keys [url room role timeout-ms] :or {timeout-ms 20000}}]
  (js/Promise.
   (fn [resolve reject]
     (if-not (and (string? url) (string? room) (pos? (count room)))
       (reject (ex-info "signal-over-ws! needs :url and :room"
                        {:reason :bad-signaling}))
       (let [role (or role (:role peer))
             ws (js/WebSocket. (t/signal-room-url url room role))
             signal (:signal peer)
             opened? (atom false)
             pending (atom [])
             stopped? (atom false)
             settled? (atom false)
             remote (atom {:desc? false :queue []})
             timer (js/setTimeout
                    #(when-not @settled?
                       (reset! settled? true)
                       (reset! stopped? true)
                       (remove-watch signal ::ws)
                       (reject (ex-info "signaling timed out"
                                        {:reason :timeout :room room})))
                    timeout-ms)
             settle-err! (fn [e]
                           (when-not @settled?
                             (reset! settled? true)
                             (reset! stopped? true)
                             (js/clearTimeout timer)
                             (remove-watch signal ::ws)
                             (reject e)))
             settle-ok! (fn [v]
                          (when-not @settled?
                            (reset! settled? true)
                            (js/clearTimeout timer)
                            (resolve v)))
             send! (fn [msg]
                     (let [text (js/JSON.stringify (clj->js msg))]
                       (if (and @opened? (= (.-readyState ws) 1))
                         (.send ws text)
                         (swap! pending conj text))))
             flush! (fn []
                      (let [batch @pending]
                        (reset! pending [])
                        (doseq [text batch] (.send ws text))))]
         (add-watch signal ::ws
                    (fn [_ _ old new]
                      (when-not @stopped?
                        (t/emit-new-signals send! old new))))
         (t/emit-new-signals send! {:description nil :candidates [] :complete? false}
                             @signal)
         (set! (.-onopen ws)
               (fn []
                 (reset! opened? true)
                 (flush!)))
         (set! (.-onerror ws)
               (fn []
                 (settle-err! (ex-info "signaling socket error"
                                       {:reason :signaling-error :room room}))))
         (set! (.-onclose ws)
               (fn []
                 (reset! stopped? true)
                 (remove-watch signal ::ws)
                 (js/setTimeout
                  #(settle-err! (ex-info "signaling socket closed"
                                         {:reason :closed :room room}))
                  0)))
         (set! (.-onmessage ws)
               (fn [ev]
                 (try
                   (let [msg (js->clj (js/JSON.parse (.-data ev)) :keywordize-keys true)]
                     (cond
                       (= (:type msg) "error")
                       (do (settle-err! (ex-info (or (:message msg) "signaling error")
                                                {:reason (keyword (or (:reason msg) "error"))
                                                 :room room}))
                           (try (.close ws) (catch :default _)))

                       (and (= (:type msg) "joined") role
                            (not= (name role) (:role msg)))
                       (settle-err! (ex-info "signaling role mismatch"
                                             {:reason :role
                                              :expected (name role)
                                              :got (:role msg)}))

                       (= (:type msg) "peer-left")
                       (settle-err! (ex-info "the other peer left"
                                             {:reason :peer-left :room room}))

                       (= (:type msg) "signal")
                       (do
                         (when-let [d (:description msg)]
                           (when (and (not (:desc? @remote)) (:sdp d) (:type d))
                             (apply-description!
                              peer (assoc msg :description d)
                              (fn []
                                (swap! remote assoc :desc? true)
                                (doseq [c (:queue @remote)] (add-cand! (:pc peer) c))
                                (swap! remote assoc :queue [])
                                (settle-ok! true))
                              settle-err!)))
                         (when-let [c (:candidate msg)]
                           (if (:desc? @remote)
                             (add-cand! (:pc peer) c)
                             (swap! remote update :queue conj c)))
                         (doseq [c (:candidates msg)]
                           (if (:desc? @remote)
                             (add-cand! (:pc peer) c)
                             (swap! remote update :queue conj c))))))
                   (catch :default e
                     (settle-err! e))))))))))

(defn- watch-ice! [pc signal]
  (set! (.-onicecandidate pc)
        (fn [ev]
          (let [c (.-candidate ev)]
            (if (js-present? c)
              (let [line (.-candidate c)]
                (when (and (string? line) (pos? (count line)))
                  (swap! signal update :candidates conj
                         {:candidate line :mid (or (.-sdpMid c) "")})))
              (swap! signal assoc :complete? true)))))
  (set! (.-onicegatheringstatechange pc)
        (fn []
          (when (= "complete" (.-iceGatheringState pc))
            (swap! signal assoc :complete? true)))))

(defn apply-remote!
  "Apply a pasted signal map to `pc`.

   `other` is `{:description {:type :sdp} :candidates [{:candidate :mid}]}`,
   the same shape as `@(:signal peer)`. Returns a promise that resolves
   once the description is set. A candidate the description already
   carries is ignored. This is the manual paste of an offer or an answer.
   It does not read a `dacite://` host."
  [pc other]
  (let [d (:description other)]
    (if-not (and (map? d) (string? (:type d)) (string? (:sdp d))
                 (pos? (count (:sdp d))))
      (js/Promise.reject (ex-info "a pasted answer needs type and sdp"
                                  {:reason :bad-signaling}))
      (-> (.setRemoteDescription pc (clj->js {:type (:type d) :sdp (:sdp d)}))
          (.then (fn []
                   (js/Promise.all
                    (into-array
                     (for [{:keys [candidate mid]} (:candidates other)
                           :when (and (string? candidate) (pos? (count candidate)))]
                       (-> (.addIceCandidate
                            pc (clj->js {:candidate candidate
                                         :sdpMid (or mid "")
                                         :sdpMLineIndex 0}))
                           (.catch (fn [_] nil))))))))))))

(defn create-peer
  "One end of a WebRTC connection to a peer in another process.

   `role` is `:offerer` or `:answerer`. Returns
   `{:conn :pc :role :signal :set-remote!}`. With `:ws {:url :room}` also
   `:signaling`, a promise from `signal-over-ws!` that resolves once the
   remote description has been applied. `:set-remote!` is `apply-remote!`
   for a pasted offer or answer (M6.4). The room token and the paste are
   manual introduce. Neither one reads a `dacite://` host.

   The offerer creates `dacite-peer` and `dacite-roots` in-band before the
   offer. The answerer waits for those channels. `connect-pair` is unchanged
   and still uses negotiated ids inside one page.

   ICE options match `connect-pair` (`:ice-servers`, `:stun?`). Host
   candidates are the default. The room token is the manual signaling step."
  [role opts]
  (let [pc (peer-connection (ice-config opts))
        built (make-transport pc role {:in-band? true})
        signal (atom {:description nil :candidates [] :complete? false})
        peer {:conn (:conn built)
              :pc pc
              :role role
              :signal signal
              :set-remote! (fn [other] (apply-remote! pc other))}]
    (when (= role :offerer)
      ((:prepare! built) reserved-channels))
    (watch-ice! pc signal)
    (when (= role :offerer)
      (start-offer! pc signal))
    (if-let [ws-opts (:ws opts)]
      (assoc peer :signaling (signal-over-ws! peer (assoc ws-opts :role role)))
      peer)))
