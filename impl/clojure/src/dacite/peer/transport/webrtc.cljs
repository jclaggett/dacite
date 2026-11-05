(ns dacite.peer.transport.webrtc
  "WebRTC `ITransport` over `node-datachannel` (M3.2 slice 2). nbb / Node only.

   One `RTCPeerConnection` per connection; named data channels (reliable,
   ordered) are `IChannel`s. Peering uses `\"dacite-peer\"`. The channel's
   `maxMessageSize` is what `msg/clamp-hello` clamps `max-frame` to, and
   `max-frame-limit` then cuts it to `frame/webrtc-max-frame` (4096). That
   second cut is a known limit of node-datachannel 0.33.6: 16 KiB frames plus
   a full SCTP buffer caused silent payload corruption (hash mismatch) in our
   tests; 4 KiB frames with 4/1 `bufferedAmount` marks were fine. The
   negotiated `maxMessageSize` is often far above 16384, so the hello would
   otherwise stay at 16384. Raise `frame/webrtc-max-frame` when upstream is
   fixed. The in-memory transport does not set this limit. `bufferedAmount`
   is what the responder's 4/1 marks read (in frames of the clamped size).
   A channel or connection close ends the peer session (see `dacite.peer.link`).

   Two ways to get a connection:

   - `(connect-pair opts)` — two ends in one process, signaling in memory.
     For tests. Default ICE is host-only (`:ice-servers []`); pass
     `:stun? true` (or `:ice-servers [...]`) to use a public STUN server.
   - `(create-peer role opts)` — one end for **manual signaling** (two
     processes). Exchange the signal map (offer/answer + ICE candidates)
     via a file or paste; see `scripts/webrtc-transfer.mjs` and
     `docs/plan/peering.md` M3.2 slice 2.

   Library: `node-datachannel` (libdatachannel N-API). If its prebuilds fail
   on a platform, fall back to `werift` (pure TypeScript) — not wired here yet."
  (:require [dacite.peer.frame :as frame]
            [dacite.peer.transport :as t]
            [dacite.wire.binary :as bin]))

(def default-stun "stun:stun.l.google.com:19302")

(def ^:private ndc (js/require "node-datachannel"))

(defn- ice-servers [{:keys [ice-servers stun?]}]
  (cond
    (some? ice-servers) (clj->js ice-servers)
    stun? (clj->js [default-stun])
    :else (clj->js [])))

(defn- fail! [reason text data]
  (throw (ex-info text (assoc data :reason reason))))

(defn- as-buffer [bs]
  (cond
    (instance? js/Buffer bs) bs
    (instance? js/Uint8Array bs) (js/Buffer.from bs)
    :else (js/Buffer.from (bin/as-wire-bytes bs))))

(defn- from-msg [msg]
  (if (string? msg)
    (js/Uint8Array.from (js/Buffer.from msg "utf8"))
    (js/Uint8Array.from msg)))

;; =============================================================================
;; Channel
;; =============================================================================

(defn- attach-dc!
  "Wire a node-datachannel DataChannel into handlers. Returns a promise of the
   channel record once it is open (so `maxMessageSize` is known)."
  [state name dc]
  (js/Promise.
   (fn [resolve reject]
     (let [handler (atom nil)
           inbox (atom [])
           on-close (atom [])
           closed? (atom false)
           fire-close! (fn []
                         (when-not @closed?
                           (reset! closed? true)
                           (doseq [f @on-close] (f))
                           (reset! on-close [])))
           ch (reify t/IChannel
                (channel-name [_] name)
                (send! [_ bs]
                  (when @closed?
                    (fail! :channel-closed "send on a closed channel" {:channel name}))
                  (let [buf (as-buffer bs)
                        max (.maxMessageSize dc)]
                    (when (and (pos? max) (> (.-length buf) max))
                      (fail! :message-too-large "message over the channel's max-message-size"
                             {:channel name :size (.-length buf) :max max}))
                    ;; On node-datachannel 0.33.6 a false return still queues the
                    ;; message once the SCTP buffer is high. Failing or sending
                    ;; again drops or duplicates a frame that was accepted.
                    ;; Back-pressure is the buffered amount, not this boolean.
                    (.sendMessageBinary dc buf)
                    nil))
                (on-message! [_ f]
                  (reset! handler f)
                  (doseq [m @inbox] (f m))
                  (reset! inbox []))
                (on-close! [_ f]
                  (if @closed? (f) (swap! on-close conj f)))
                (buffered-amount [_] (.bufferedAmount dc))
                (max-message-size [_] (let [m (.maxMessageSize dc)] (if (pos? m) m 65536)))
                (max-frame-limit [_] frame/webrtc-max-frame)
                (channel-open? [_] (and (not @closed?) (.isOpen dc)))
                (close! [_]
                  (when-not @closed?
                    (try (.close dc) (catch :default _)))
                  (fire-close!)
                  nil))]
       (.onMessage dc (fn [msg]
                        (let [bs (from-msg msg)]
                          (if-let [f @handler]
                            (f bs)
                            (swap! inbox conj bs)))))
       (.onClosed dc fire-close!)
       (.onError dc (fn [err] (js/console.error "datachannel error" name err)))
       (swap! state assoc-in [:chans name] {:dc dc :ch ch :closed? closed?})
       (if (.isOpen dc)
         (resolve ch)
         (do (.onOpen dc (fn [] (resolve ch)))
             ;; a channel that never opens: the caller can time out
             ))))))

;; =============================================================================
;; Connection
;; =============================================================================

(defn- make-conn
  "An ITransport over a PeerConnection. `:role` is `:offerer` or `:answerer`."
  [pc role]
  (let [state (atom {:pc pc :role role :chans {} :on-channel {} :unclaimed {}
                     :on-conn-close [] :open? true})
        fire-conn-close! (fn []
                           (let [[old _] (swap-vals! state
                                                     (fn [s] (assoc s :open? false :on-conn-close [])))]
                             (when (:open? old)
                               (doseq [[_ {:keys [dc]}] (:chans old)]
                                 (try (.close dc) (catch :default _)))
                               (doseq [f (:on-conn-close old)] (f)))))]
    (.onStateChange pc (fn [st]
                         (when (#{"closed" "disconnected" "failed"} st)
                           (fire-conn-close!))))
    (.onDataChannel pc
                    (fn [dc]
                      (let [name (.getLabel dc)]
                        (-> (attach-dc! state name dc)
                            (.then (fn [ch]
                                     (if-let [f (get-in @state [:on-channel name])]
                                       (f ch)
                                       (swap! state assoc-in [:unclaimed name] ch))))))))
    (reify t/ITransport
      (open-channel! [_ name]
        ;; Always a Promise: a data channel is not usable until it is open
        ;; (that is when maxMessageSize is known).
        (js/Promise.
         (fn [resolve reject]
           (try
             (when-not (:open? @state)
               (fail! :connection-closed "open-channel! on a closed connection" {:channel name}))
             (when (get-in @state [:chans name])
               (fail! :channel-exists "this side already opened the channel" {:channel name}))
             (if-let [ch (get-in @state [:unclaimed name])]
               (do (swap! state update :unclaimed dissoc name) (resolve ch))
               (if (= role :answerer)
                 (let [prev (get-in @state [:on-channel name])]
                   (swap! state assoc-in [:on-channel name]
                          (fn [ch]
                            (when prev (prev ch))
                            (resolve ch))))
                 (let [dc (.createDataChannel pc name #js {:ordered true})]
                   (-> (attach-dc! state name dc)
                       (.then resolve)
                       (.catch reject)))))
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
          (f)))
      (connection-open? [_] (boolean (:open? @state)))
      (close-connection! [_]
        (fire-conn-close!)
        (try (.close pc) (catch :default _))
        nil))))

;; =============================================================================
;; In-process pair (tests)
;; =============================================================================

(defn connect-pair
  "Two connected ends `[a b]` of one WebRTC connection in this process.
   `a` is the offerer, `b` the answerer. Returns a promise of `[a b]`.

   Options: `:ice-servers` (vector of STUN/TURN URLs), `:stun?` (add the
   default public STUN server). Host-only ICE is the default (fast in one
   process). ICE finishes when the first data channel is created."
  ([] (connect-pair nil))
  ([opts]
   (let [ice (ice-servers opts)
         p1 (new (.-PeerConnection ndc) "a" #js {:iceServers ice})
         p2 (new (.-PeerConnection ndc) "b" #js {:iceServers ice})
         a (make-conn p1 :offerer)
         b (make-conn p2 :answerer)]
     (.onLocalDescription p1 (fn [sdp type] (.setRemoteDescription p2 sdp type)))
     (.onLocalCandidate p1 (fn [c mid] (.addRemoteCandidate p2 c mid)))
     (.onLocalDescription p2 (fn [sdp type] (.setRemoteDescription p1 sdp type)))
     (.onLocalCandidate p2 (fn [c mid] (.addRemoteCandidate p1 c mid)))
     (js/Promise.resolve [a b]))))

(defn open-peer-channel!
  "Open `dacite-peer` on both ends of a `connect-pair`. The answerer waits
   first, then the offerer creates the channel. Returns a promise of
   `[ch-a ch-b]` once both are open."
  [a b]
  (let [name t/peer-channel
        waiting (t/open-channel! b name)]
    (-> (t/open-channel! a name)
        (.then (fn [ch-a]
                 (.then waiting (fn [ch-b] [ch-a ch-b])))))))

;; =============================================================================
;; Manual signaling (two processes)
;; =============================================================================

(defn create-peer
  "One end of a WebRTC connection for manual signaling.

   `role` is `:offerer` or `:answerer`. Returns
   `{:conn ITransport :pc PeerConnection :signal atom :done! (fn [])}`.

   The atom under `:signal` holds `{:description {:type :sdp} :candidates [{:candidate :mid}]
   :complete?}`. Poll or watch it; write it to a file for the other process.
   Call `(set-remote! peer signal-map)` with the other side's signal once it has
   a description (and again as candidates arrive, or with the full map when
   gathering is complete).

   Options: same ICE options as `connect-pair`."
  [role opts]
  (let [ice (ice-servers opts)
        pc (new (.-PeerConnection ndc) (name role) #js {:iceServers ice})
        signal (atom {:description nil :candidates [] :complete? false})
        conn (make-conn pc role)]
    (.onLocalDescription pc (fn [sdp type]
                              (swap! signal assoc :description {:type type :sdp sdp})))
    (.onLocalCandidate pc (fn [c mid]
                            (swap! signal update :candidates conj {:candidate c :mid mid})))
    (.onGatheringStateChange pc (fn [st]
                                  (when (= st "complete")
                                    (swap! signal assoc :complete? true))))
    {:conn conn
     :pc pc
     :role role
     :signal signal
     :set-remote!
     (fn [other]
       (when-let [{:keys [type sdp]} (:description other)]
         (when (and type sdp)
           (.setRemoteDescription pc sdp type)))
       (doseq [{:keys [candidate mid]} (:candidates other)]
         (.addRemoteCandidate pc candidate mid)))}))

(defn wait-signal
  "Promise of `@signal` once it has a description and (if `need-complete?`)
   gathering is complete, or after `timeout-ms`."
  ([signal] (wait-signal signal {:need-complete? true :timeout-ms 15000}))
  ([signal {:keys [need-complete? timeout-ms] :or {need-complete? true timeout-ms 15000}}]
   (js/Promise.
    (fn [resolve reject]
      (let [done? (atom false)
            finish (fn [v] (when-not @done? (reset! done? true) (resolve v)))
            check (fn []
                    (let [s @signal]
                      (when (and (:description s)
                                 (or (not need-complete?) (:complete? s)))
                        (finish s))))]
        (check)
        (add-watch signal ::wait (fn [_ _ _ _] (check)))
        (js/setTimeout
         #(when-not @done?
            (remove-watch signal ::wait)
            (reject (ex-info "wait-signal timed out" {:reason :timeout :signal @signal})))
         timeout-ms))))))

(defn connect-peers
  "Promise of `{:a :b :ch-a :ch-b}`: two ends with `dacite-peer` open on both.
   The usual entry point for in-process tests."
  ([] (connect-peers nil))
  ([opts]
   (-> (connect-pair opts)
       (.then (fn [[a b]]
                (-> (open-peer-channel! a b)
                    (.then (fn [[ch-a ch-b]]
                             {:a a :b b :ch-a ch-a :ch-b ch-b}))))))))
