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

   **Sent-hash record, `ack` and `forget` (M4.2, C19 to C23, C26).** One record
   per session, at most 16 384 hashes, oldest dropped first. A hash is recorded
   when the frame that finishes its item is written. A later `get` skips
   recorded hashes and spends its stream on others, except the root, which is
   always sent (C20). `ack` is a hint: known hashes are kept the longest, unknown
   hashes are ignored, and nothing waits for an ack. `forget` removes hashes so
   the next `get` sends them again. An empty or a wrong record still answers
   (C19). `cancel` is still ignored.

   **Stream lifetime (C10).** After the C09 clamps a stream is dropped at
   `tau · ln(peak / floor)` from its request, at most about 276 s. A renewal is
   a new `get`.

   Not here: interest decay ending a stream at the floor (C05). The clamps are
   applied to that lifetime; they do not change the send rate. The stream cap
   of C11/C12 is M3.1. Items are sent as node items; the literal choice of
   `dacite.peer.items/encode-value` is JVM-only.

   **Store reads may be asynchronous (M1.3).** A `get` reads the store through
   `dacite.store.chain/with-reads`, so a store that settles late works as well as
   the in-memory store: the stream starts when the read is done, and a `get` that
   arrives meanwhile is handled on its own (nothing here writes, so there is no
   order to keep between them). With a store that answers at once, which is always
   the case with `store/mem-store`, `on-frame!` is as synchronous as before and
   returns nil; otherwise it returns a `chain/Deferred` of nil. A store that fails
   while it is read ends that stream only, with `error internal`; the session
   lives. The reveal walk (`dacite.reveal/page-order`) is still synchronous code:
   it runs on a read view that fetches the subtree under each node it misses and
   starts again, so over a store that is not settled it costs a couple of passes,
   not one per page."
  (:require [dacite.hash :as hash]
            [dacite.peer.bucket :as bucket]
            [dacite.peer.frame :as frame]
            [dacite.peer.msg :as msg]
            [dacite.peer.rates :as rates]
            [dacite.host :as host]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.value.types :as types]
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

(def sent-cap
  "Hashes in one session's sent-record (C21)."
  16384)

(defn record-empty
  "An empty sent-hash record."
  []
  {:set #{} :order [] :cap sent-cap})

(defn- drop-oldest [rec]
  (-> rec
      (assoc :order (into [] (rest (:order rec))))
      (update :set disj (first (:order rec)))))

(defn record-sent
  "Record hex keys `hks` as sent. Past the cap, the oldest is dropped.
   A key already recorded stays where it is."
  [rec hks]
  (reduce (fn [rec hk]
            (if (or (nil? hk) (contains? (:set rec) hk))
              rec
              (let [rec (-> rec (update :set conj hk) (update :order conj hk))]
                (if (<= (count (:order rec)) (:cap rec)) rec (drop-oldest rec)))))
          rec hks))

(defn record-ack
  "Move recorded keys in `hks` to the newest end, so they are dropped last.
   A key the record does not hold is ignored (C23, N10)."
  [rec hks]
  (reduce (fn [rec hk]
            (if-not (contains? (:set rec) hk)
              rec
              (let [order (into [] (remove #(= hk %) (:order rec)))]
                (assoc rec :order (conj order hk)))))
          rec hks))

(defn record-forget
  "Remove `hks` from the record. A key that is not there is ignored (N10)."
  [rec hks]
  (reduce (fn [rec hk]
            (if-not (contains? (:set rec) hk)
              rec
              (let [order (into [] (remove #(= hk %) (:order rec)))]
                (-> rec
                    (update :set disj hk)
                    (assoc :order order)))))
          rec hks))

(defn- lifetime-ms
  "C10, after the C09 clamps: at most 60 s · ln(1000/10) ≈ 276 s."
  [peak tau floor]
  (let [peak (double (min 1000 (max 1 (or peak 1))))
        tau (double (min 60000 (max 1 (or tau 1))))
        floor (double (max 10 (or floor 10)))]
    (* tau (host/log (/ peak floor)))))

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
   The token bucket starts full. `opts` may hold `:max-message-size`, the largest
   message the connection's channel carries, and `:max-frame-limit`, a tighter
   cap (WebRTC passes 4096). The `hello` sent is `msg/hello-for-channel` of
   those, and that same map is what `negotiate` later reads (`:advertised`),
   so a library clamp is the frame size this side writes, not only the bytes
   on the wire."
  ([r peer] (open-session! r peer nil))
  ([r peer opts]
   (let [hello (msg/hello-for-channel (:hello r) opts)]
     (swap! (:state r) assoc-in [:sessions peer]
            {:status :open :peer-hello nil :budget nil :last-tick nil :max-frame nil
             :advertised hello
             :record (record-empty)
             :table nil :streams {}
             :bucket (bucket/bucket (get (own-rates r) "req-rate") (get (own-rates r) "req-burst")
                                    ((:now r)))})
     (send-message! r peer {:type :hello :stream 0 :value hello})
     r)))

(defn close-session!
  "The connection to `peer` is gone (a transport close, not a protocol violation
   by the peer): the session is over, nothing is sent and `:close!` is not called.
   A no-op for a session that already ended."
  [r peer]
  (when (= :open (session-status r peer))
    (swap! (:state r) update-in [:sessions peer] assoc :status :ended :streams {}))
  nil)

(defn- end-session!
  "C16: a protocol violation. `error malformed`, fatal, on stream 0; then close."
  [r peer text]
  (when (= :open (session-status r peer))
    (swap! (:state r) update-in [:sessions peer] assoc :status :ended :streams {})
    (send-message! r peer (error-message 0 "malformed" text true))
    ((:close! r) peer)))

(defn- hash-hex->hash [h] (hash/hex->hash (msg/hash-ref-hex h)))

(defn- item-end-indexes
  "Frame index where each item's last byte lands. The cuts match
   `frame/chunk-items` for a payload of `room` bytes."
  [sizes room]
  (loop [sizes (seq sizes) idx 0 used 0 ends []]
    (if (nil? sizes)
      ends
      (let [n (first sizes)]
        (cond
          (and (<= n room) (<= (+ used n) room))
          (recur (next sizes) idx (+ used n) (conj ends idx))

          (<= n room)
          (recur (next sizes) (inc idx) n (conj ends (inc idx)))

          :else
          (let [idx (if (pos? used) (inc idx) idx)
                cuts (quot (+ n (dec room)) room)
                last-idx (+ idx (dec cuts))
                tail (let [m (rem n room)] (if (zero? m) room m))]
            (recur (next sizes) last-idx tail (conj ends last-idx))))))))

(defn- plan-items
  "Item frames for `hashes`, and per frame the hex keys whose item ends there.
   The root is the caller's concern: every hash here is sent."
  [st hashes sid max-frame]
  (let [room (- max-frame frame/header-bytes)
        encoded (mapv (fn [h]
                        {:hk (store/hash->hex h)
                         :bytes (bin/encode-item {:enc :node :hash h
                                                  :entry (store/await (store/s-get st h))})})
                      hashes)
        frames (frame/chunk-items sid (mapv :bytes encoded) {:max-frame-bytes max-frame})
        ends (item-end-indexes (mapv #(bin/byte-len (:bytes %)) encoded) room)]
    {:frames frames
     :ends (reduce (fn [v [idx hk]] (update v idx (fnil conj #{}) hk))
                   (vec (repeat (count frames) #{}))
                   (map vector ends (map :hk encoded)))}))

(defn- pages-to-send
  "Reveal order of `h`, without hashes the session has already sent, except
   `h` itself: the root of a get is always sent (C20)."
  [st h record]
  (let [skip (:set record)
        root (store/hash->hex h)]
    (into []
          (remove (fn [x]
                    (let [k (store/hash->hex x)]
                      (and (not= k root) (contains? skip k)))))
          (reveal/page-order st h))))

(defn- start-stream! [r peer sid value plan]
  (let [{:keys [now]} r
        interest (merge default-interest (get value "interest"))
        t0 (now)]
    (swap! (:state r) update-in [:sessions peer]
           (fn [s]
             (-> s
                 ;; C03: the latest get's recv-max is the session budget
                 (assoc :budget (get value "recv-max"))
                 (assoc-in [:streams sid]
                           {:frames (:frames plan)
                            :ends (:ends plan)
                            :next 0 :t0 t0 :credit 0 :blocked? false
                            :deadline (+ t0 (lifetime-ms (get interest "peak")
                                                        (get interest "tau-ms")
                                                        (get interest "floor")))
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
              (chain/then
               (chain/guard
                (fn []
                  (chain/with-reads (:store r)
                    (fn [view]
                      (let [h (hash-hex->hash (get value "hash"))
                            session (get-in @(:state r) [:sessions peer])
                            {:keys [max-frame record]} session]
                        (when (some? (store/await (store/s-get view h)))
                          (plan-items view (pages-to-send view h record) stream max-frame))))
                    ;; the walk reads the whole value: fetch it a subtree at a time
                    {:expand types/child-hashes}))
                ;; the store failed: this stream only, and the session lives
                (fn [_] ::unreadable))
               (fn [frames]
                 ;; the session may have ended while the store was read
                 (when (= :open (session-status r peer))
                   (cond
                     (= ::unreadable frames)
                     (do (close-stream! r peer stream)
                         (reply! "internal" "the store could not be read"))

                     (nil? frames)
                     (do (close-stream! r peer stream)
                         (reply! "unknown-hash" "not held here"))

                     :else (start-stream! r peer stream value frames)))))))))))

(defn on-frame!
  "The frame (bytes) that arrived from `peer`. Returns nil, or with a store that
   settles late a `chain/Deferred` of nil, done when the `get` it carries has its
   stream (see the namespace doc).

   - `hello` (once, first): it sets the budget (its `recv-max`) and the frame size
   - `get`: see the namespace doc
   - `error` fatal or on stream 0: the peer ended the session
   - `ack` and `forget`: update the sent-hash record (unknown hashes ignored)
   - `cancel`: ignored
   - an unknown type: `error unsupported` on its stream
   - anything else, a second `hello`, or any frame before the `hello`: a protocol
     violation, which ends the session (C16)"
  [r peer frame-bytes]
  (when (= :open (session-status r peer))
    (chain/then
     (chain/guard
      (fn []
       (let [fr (frame/decode-frame frame-bytes)
            hello? (some? (get-in @(:state r) [:sessions peer :peer-hello]))
            type (:type fr)]
        (cond
          (= :hello type)
          (let [{:keys [value]} (msg/decode-message frame-bytes)
                mine (or (get-in @(:state r) [:sessions peer :advertised]) (:hello r))]
            (when hello? (throw (ex-info "a second hello" {:reason :second-hello})))
            (swap! (:state r) update-in [:sessions peer]
                   assoc :peer-hello value
                   :budget (get-in value ["rates" "recv-max"])
                   :max-frame (get-in (msg/negotiate mine value) [:ok :max-frame])
                   :table (frame/stream-table :answerer (get-in mine ["limits" "max-streams"]))
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

          (#{:ack :forget} type)
          (let [{:keys [value]} (msg/decode-message frame-bytes)
                hks (mapv msg/hash-ref-hex (get value "hashes"))]
            (swap! (:state r) update-in [:sessions peer :record]
                   #((if (= :ack type) record-ack record-forget) (or % (record-empty)) hks)))

          (= :cancel type) nil

          :else (throw (ex-info "a frame this side never accepts" {:reason :unexpected :type type})))))
      (fn [e]
        (end-session! r peer (str (name (or (:reason (ex-data e)) :error)) ": " (ex-message e)))))
     (fn [_] nil))))

;; =============================================================================
;; Pacing
;; =============================================================================

(defn- blocked?
  "C17 with its two marks: blocked from `high` frames buffered, until `low`."
  [was-blocked? buffered max-frame]
  (if was-blocked?
    (> buffered (* low-frames max-frame))
    (>= buffered (* high-frames max-frame))))

(defn- drop-expired!
  "C10: a stream's state is gone by its lifetime, even if frames remain."
  [r peer now]
  (doseq [[sid st] (get-in @(:state r) [:sessions peer :streams])]
    (when (and (:deadline st) (>= (double now) (double (:deadline st))))
      (close-stream! r peer sid))))

(defn- note-frame-sent! [r peer st idx]
  (when-let [hks (get (:ends st) idx)]
    (when (seq hks)
      (swap! (:state r) update-in [:sessions peer :record]
             #(record-sent (or % (record-empty)) hks)))))

(defn- tick-session! [r peer now]
  (drop-expired! r peer now)
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
                  (note-frame-sent! r peer st next)
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
