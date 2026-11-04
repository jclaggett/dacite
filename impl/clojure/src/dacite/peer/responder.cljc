(ns dacite.peer.responder
  "Peer protocol v1, the answering side: pace, protect, and never write.
   Portable (.cljc), sans I/O, and with no clock of its own: the caller says what
   time it is (`:now`, `tick!`), hands in each frame it reads (`on-frame!`) and
   gets each frame to write through `:send!`.

   Spec: docs/spec/peer-v1.md §12.2 (C03), §12.4 (C16), §12.5 (C17, C18), §12.9
   (C34, C36), §12.11 (C40). M1.2 slice 2.

   - **Pacing (C03).** A session's rate is `min(send-max, budget)`, where the
     budget is the `recv-max` of the latest `get` (before any, the `recv-max` in
     the peer's `hello`). It is split among the session's open streams by the C01
     formula with this connection's `peak` of 1000. Whole frames are written when
     a stream's credit covers them.
   - **Flow control (C17, C18).** No frame is written to a stream whose channel
     holds 4 frames or more (`:buffered`), and writing resumes when it is down to 1
     frame. A blocked stream takes no share (the others share its rate) and banks
     at most one frame of credit.
   - **Token bucket (C40).** One per session, `req-rate` and `req-burst` from this
     side's `hello`. Each `get` costs a token, answered or refused; with none left
     the answer is `error rate-limited` with `retry-ms`, no stream starts, and the
     session lives.
   - **Read-only (C34, C36).** Nothing a peer sends changes the store. A `get` is
     answered from this store only; a hash it does not hold is `error
     unknown-hash`. There is no code here that calls `s-put`.
   - **Protocol violations (C16)** end the session: `error` `malformed`, fatal, on
     stream 0, then `:close!`. A request that cannot be served ends that stream
     only.

   Not here (M4): interest decay ending a stream at the floor (C05), `cancel`,
   the sent-hash record, `ack` and `forget`. The clamps of C09 and the stream cap
   of C11/C12 are M3.1. Items are sent as node items; the literal choice of
   `dacite.peer.items/encode-value` is JVM-only.

   Store calls are synchronous: this works with the in-memory store only."
  (:require [dacite.hash :as hash]
            [dacite.peer.bucket :as bucket]
            [dacite.peer.frame :as frame]
            [dacite.peer.msg :as msg]
            [dacite.peer.rates :as rates]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.wire.binary :as bin]))

(def default-hello
  "This side's `hello`: what it advertises and enforces."
  {"versions" #{1}
   "kind" "node"
   "caps" #{"reveal"}
   "limits" {"max-frame" 16384 "max-item" 1048576 "max-streams" 32}
   "rates" {"send-max" 10000000 "recv-max" 10000000 "req-rate" 20 "req-burst" 40}})

(def high-frames "C17: stop writing at this many frames buffered." 4)
(def low-frames "C17: resume writing at this many frames buffered." 1)

(def default-interest rates/default-interest)

(defn responder
  "A responder over `:store`. Options:

     :send!     (fn [peer-id frame-bytes]) write a frame (control or item-stream)
     :close!    (fn [peer-id]) close the connection
     :now       (fn [] milliseconds): the clock
     :buffered  (fn [peer-id stream-id] bytes): what the stream's channel holds
     :hello     this side's hello (default `default-hello`)"
  [{:keys [store send! close! now buffered hello]
    :or {send! (fn [_ _]) close! (fn [_]) buffered (fn [_ _] 0)
         now (fn [] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
         hello default-hello}}]
  {:store store :send! send! :close! close! :now now :buffered buffered :hello hello
   :state (atom {:sessions {}})})

(defn- own-rates [r] (get (:hello r) "rates"))

(defn- encode [m] (msg/encode-message m))

(defn- send-message! [r peer m] ((:send! r) peer (encode m)))

(defn- error-message [stream code text fatal? & [retry-ms]]
  {:type :error :stream stream
   :value (cond-> {"code" code "message" text "fatal" fatal?}
            retry-ms (assoc "retry-ms" retry-ms))})

(defn session-status [r peer] (get-in @(:state r) [:sessions peer :status]))

(defn open-session!
  "A connection from `peer` is up: send this side's `hello` and expect theirs.
   The token bucket starts full."
  [r peer]
  (swap! (:state r) assoc-in [:sessions peer]
         {:status :open :peer-hello nil :budget nil :last-tick nil :max-frame nil
          :table nil :streams {}
          :bucket (bucket/bucket (get (own-rates r) "req-rate") (get (own-rates r) "req-burst")
                                 ((:now r)))})
  (send-message! r peer {:type :hello :stream 0 :value (:hello r)})
  r)

(defn- end-session!
  "C16: a protocol violation. `error malformed`, fatal, on stream 0; then close."
  [r peer text]
  (when (= :open (session-status r peer))
    (swap! (:state r) update-in [:sessions peer] assoc :status :ended :streams {})
    (send-message! r peer (error-message 0 "malformed" text true))
    ((:close! r) peer)))

(defn- hash-hex->hash [h] (hash/hex->hash (msg/hash-ref-hex h)))

(defn- item-frames
  "Frames answering `h` from `st`: a full reveal as node items, header first."
  [st h sid max-frame]
  (frame/chunk-items sid
                     (mapv (fn [x] (bin/encode-item {:enc :node :hash x
                                                     :entry (store/await (store/s-get st x))}))
                           (reveal/page-order st h))
                     {:max-frame-bytes max-frame}))

(defn- start-stream! [r peer sid value]
  (let [{:keys [store now]} r
        h (hash-hex->hash (get value "hash"))
        {:keys [max-frame]} (get-in @(:state r) [:sessions peer])
        interest (merge default-interest (get value "interest"))]
    (swap! (:state r) update-in [:sessions peer]
           (fn [s]
             (-> s
                 ;; C03: the latest get's recv-max is the session budget
                 (assoc :budget (get value "recv-max"))
                 (assoc-in [:streams sid]
                           {:frames (item-frames store h sid max-frame)
                            :next 0 :t0 (now) :credit 0 :blocked? false
                            :peak (get interest "peak") :tau (get interest "tau-ms")}))))))

(defn- close-stream! [r peer sid]
  (swap! (:state r) update-in [:sessions peer]
         (fn [s] (-> s (update :table frame/close-stream sid) (update :streams dissoc sid)))))

(defn- on-get! [r peer {:keys [stream value] :as m}]
  (let [{:keys [bucket]} (get-in @(:state r) [:sessions peer])
        [bucket' ok? retry-ms] (bucket/take-token bucket ((:now r)))
        reply! (fn [code text & [retry]]
                 (send-message! r peer (error-message stream code text false retry)))]
    ;; C40: a get costs a token whether it is answered or refused
    (swap! (:state r) assoc-in [:sessions peer :bucket] bucket')
    (cond
      (not ok?) (reply! "rate-limited" "too many requests" retry-ms)

      (msg/answer-error m) (reply! (get-in (msg/answer-error m) [:value "code"]) "cannot serve")

      :else
      (let [table (get-in @(:state r) [:sessions peer :table])
            [opened failure] (try [(frame/open-remote table stream) nil]
                                  (catch #?(:clj Throwable :cljs :default) e [nil e]))]
        (cond
          ;; wrong parity or a used id: `malformed` on that stream (spec 4, C39)
          failure (if (= :too-many-streams (:reason (ex-data failure)))
                    (reply! "overloaded" "too many streams")
                    (reply! "malformed" (ex-message failure)))

          :else
          (do (swap! (:state r) assoc-in [:sessions peer :table] opened)
              ;; C36: from this store only
              (if (nil? (store/await (store/s-get (:store r) (hash-hex->hash (get value "hash")))))
                (do (close-stream! r peer stream)
                    (reply! "unknown-hash" "not held here"))
                (start-stream! r peer stream value))))))))

(defn on-frame!
  "The frame (bytes) that arrived from `peer`. Returns nil.

   - `hello` (once, first): it sets the budget (its `recv-max`) and the frame size
   - `get`: see the namespace doc
   - `error` fatal or on stream 0: the peer ended the session
   - `cancel`, `ack`, `forget`: ignored (M4)
   - an unknown type: `error unsupported` on its stream
   - anything else, a second `hello`, or any frame before the `hello`: a protocol
     violation, which ends the session (C16)"
  [r peer frame-bytes]
  (when (= :open (session-status r peer))
    (try
      (let [fr (frame/decode-frame frame-bytes)
            hello? (some? (get-in @(:state r) [:sessions peer :peer-hello]))
            type (:type fr)]
        (cond
          (= :hello type)
          (let [{:keys [value]} (msg/decode-message frame-bytes)]
            (when hello? (throw (ex-info "a second hello" {:reason :second-hello})))
            (swap! (:state r) update-in [:sessions peer]
                   assoc :peer-hello value
                   :budget (get-in value ["rates" "recv-max"])
                   :max-frame (get-in (msg/negotiate (:hello r) value) [:ok :max-frame])
                   :table (frame/stream-table :answerer (get-in (:hello r) ["limits" "max-streams"]))
                   :last-tick ((:now r))))

          (= :error type)
          (let [{:keys [stream value]} (msg/decode-message frame-bytes)]
            (when (or (zero? stream) (get value "fatal"))
              (swap! (:state r) update-in [:sessions peer] assoc :status :ended :streams {})
              ((:close! r) peer)))

          (not hello?)
          (throw (ex-info "a frame before the peer's hello" {:reason :before-hello}))

          (= :get type) (on-get! r peer (msg/decode-message frame-bytes))

          (number? type) (send-message! r peer (error-message (:stream fr) "unsupported"
                                                              "unknown message type" false))

          (#{:cancel :ack :forget} type) nil

          :else (throw (ex-info "a frame this side never accepts" {:reason :unexpected :type type}))))
      (catch #?(:clj Throwable :cljs :default) e
        (end-session! r peer (str (name (or (:reason (ex-data e)) :error)) ": " (ex-message e))))))
  nil)

;; =============================================================================
;; Pacing
;; =============================================================================

(defn- blocked?
  "C17 with its two marks: blocked from `high` frames buffered, until `low`."
  [was-blocked? buffered max-frame]
  (if was-blocked?
    (> buffered (* low-frames max-frame))
    (>= buffered (* high-frames max-frame))))

(defn- tick-session! [r peer now]
  (let [{:keys [state buffered]} r
        {:keys [budget last-tick max-frame streams] :as s} (get-in @state [:sessions peer])
        dt (/ (max 0 (- now last-tick)) 1000)
        send-max (get (own-rates r) "send-max")
        session-rate (rates/session-rate send-max budget)
        ;; the blocked state of each stream now, from its channel
        flags (into {} (map (fn [[sid st]]
                              [sid (blocked? (:blocked? st) (buffered peer sid) max-frame)]))
                    streams)
        interest (fn [st] (rates/interest (:peak st) (:tau st) (- now (:t0 st))))
        ;; C18: a blocked stream takes no share
        live (remove (fn [[sid _]] (flags sid)) streams)
        total (reduce + 0 (map (comp interest val) live))
        rate-of (fn [st] (/ (* session-rate (interest st)) (max rates/peak total)))]
    (swap! state assoc-in [:sessions peer :last-tick] now)
    (doseq [[sid st] (sort-by key streams)]
      (let [blocked (get flags sid)
            ;; C18: no share while blocked, and at most one frame banked
            credit (if blocked
                     (min (:credit st) max-frame)
                     (+ (:credit st) (* dt (rate-of st))))]
        (loop [credit credit next (:next st) blocked blocked]
          (let [frames (:frames st)
                f (get frames next)
                size (when f (bin/byte-len f))]
            (cond
              (nil? f) (close-stream! r peer sid)

              (and (not blocked) (>= credit size))
              (do ((:send! r) peer f)
                  (recur (- credit size) (inc next)
                         (blocked? false (buffered peer sid) max-frame)))

              :else
              (swap! state update-in [:sessions peer :streams sid] assoc
                     :credit (if blocked (min credit max-frame) credit)
                     :next next :blocked? blocked))))))))

(defn tick!
  "Time has moved to `(:now r)`: write what each session's rate allows."
  [r]
  (let [now ((:now r))]
    (doseq [peer (keys (:sessions @(:state r)))
            :when (and (= :open (session-status r peer))
                       (get-in @(:state r) [:sessions peer :peer-hello]))]
      (tick-session! r peer now))))

(defn open-streams
  "Stream ids `peer` has open now."
  [r peer]
  (set (keys (get-in @(:state r) [:sessions peer :streams]))))
