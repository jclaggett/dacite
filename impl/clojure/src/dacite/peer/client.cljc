(ns dacite.peer.client
  "Peer protocol v1, the requesting side: sessions, and what a failure does.
   Portable (.cljc): the same code runs on the JVM and on nbb. No I/O and no
   clock: the caller hands in each frame it reads (`on-frame!`) and gets each
   message to send through the `:send!` callback.

   Spec: docs/spec/peer-v1.md §12.4 (session end), §12.8 (verification).
   M1.2 slice 1. Not here yet (slice 2 and later): flow control, token buckets,
   the read-only rule, interest decay and `cancel`, `ack` and `forget`.

   One client has one store and any number of sessions, one per peer. What it
   keeps, per session: a stream table (`frame/stream-table`), and per open
   stream the hash it asked for, a frame reader and a receiver
   (`dacite.peer.verify`). A stream id is the table's counter, never the hash.

   Rules this namespace carries out:

   - **Verify before store (C27 to C33).** An item reaches the store only
     through `verify/accept-item!`, which checks it first. `have?` is the
     question \"may I read or offer this hash onward\": stored, and not an
     unchecked collection header.
   - **A verification failure ends the whole session (C13).** The client
     sends `error` `bad-entry` with `fatal: true` on stream 0, closes the
     session (`:close!`) and drops that session's unchecked headers. Items
     that were verified stay.
   - **Other sessions are untouched (C15).** Every piece of state above is
     per session. Ending one only reads and rewrites that session's entry,
     and the hashes it was serving.
   - **What the peer was serving is re-requested elsewhere (C14).** Each
     stream still open on the ended session becomes a new `get` on another
     open session that has not already failed to serve that hash. With no
     such session the hash waits in `unserved` and goes out when a session
     opens.

   A `get` for a hash a peer cannot serve (`error unknown-hash`, or a stream
   that ends without the hash) ends that stream only, and the hash is asked of
   another peer. A hash is never asked twice of the same peer."
  (:require [dacite.peer.frame :as frame]
            [dacite.peer.msg :as msg]
            [dacite.peer.verify :as verify]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.wire.binary :as bin]))

(def default-recv-max
  "`recv-max` in every `get`, bytes a second. A placeholder: slice 2 sets it
   to the client's C01/C02 share for the peer."
  1000000)

(def ^:private verification-reasons
  "Why a check can fail (verify, the wire decoder). All are `bad-entry` (C13)."
  #{:bad-hash :bad-entry :bad-item :bad-state})

(defn- error-code
  "The `error` code that ends the session for failure `e`: `bad-entry` for a
   verification failure, `malformed` for a frame the codec refuses."
  [e]
  (if (contains? verification-reasons (:reason (ex-data e))) "bad-entry" "malformed"))

;; =============================================================================
;; The client
;; =============================================================================

(defn client
  "A client. Options:

     :store     where verified items go (default a new mem-store)
     :send!     (fn [peer-id message]) with message {:type :get :stream n
                :value {...}} or {:type :error :stream 0 :value {...}}; the
                caller encodes it with `msg/encode-message`
     :close!    (fn [peer-id]) the session is over; close the connection
     :recv-max  see `default-recv-max`

   The state is in an atom under `:state`; read it through the functions
   below, not directly."
  [{:keys [store send! close! recv-max]
    :or {send! (fn [_ _]) close! (fn [_])
         recv-max default-recv-max}}]
  {:store (or store (store/mem-store))
   :send! send!
   :close! close!
   :recv-max recv-max
   ;; :sessions peer-id -> {:status :open|:ended :table t :streams {id {...}}}
   ;; :order    peer ids, oldest first
   ;; :wanted   hash-key -> {:hash h :tried #{peer-id} :at [peer-id stream]}
   :state (atom {:sessions {} :order [] :wanted {} :done #{}})})

(defn- open? [c peer-id]
  (= :open (get-in @(:state c) [:sessions peer-id :status])))

(defn session-status
  "`:open`, `:ended`, or nil for a peer with no session."
  [c peer-id]
  (get-in @(:state c) [:sessions peer-id :status]))

(defn outstanding
  "The hashes `peer-id`'s session is serving now: one per open stream."
  [c peer-id]
  (mapv :hash (vals (get-in @(:state c) [:sessions peer-id :streams]))))

(defn unserved
  "Hashes wanted that no open session is asked for: they wait for a session."
  [c]
  (into [] (comp (filter (comp nil? :at)) (map :hash)) (vals (:wanted @(:state c)))))

(defn done?
  "True when the wanted hash `h` was received, verified, and stored."
  [c h]
  (contains? (:done @(:state c)) (gc/hash-key h)))

(defn have?
  "True when `h` is stored and verified: it is in the store and is not a
   collection header still waiting for its root (C27, C31). Only such a hash
   may be read or offered to another peer."
  [c h]
  (and (some? (store/await (store/s-get (:store c) h)))
       (not-any? (fn [{:keys [streams]}]
                   (some (fn [{:keys [rx]}]
                           (some #(= (gc/hash-key h) (gc/hash-key %)) (verify/unchecked rx)))
                         (vals streams)))
                 (vals (:sessions @(:state c))))))

;; =============================================================================
;; Asking
;; =============================================================================

(defn- dispatch!
  "Send a `get` for wanted hash `hk` to the open session with the fewest open
   streams that has not failed to serve it. With none, the hash stays in
   `unserved`."
  [c hk]
  (let [{:keys [state store send! recv-max]} c
        {:keys [wanted sessions order]} @state
        {:keys [hash tried]} (get wanted hk)
        ;; the session with the fewest open streams; the oldest on a tie
        peer (second (first (sort-by first
                                     (keep-indexed
                                      (fn [i p]
                                        (when (and (= :open (get-in sessions [p :status]))
                                                   (not (contains? tried p)))
                                          [[(count (get-in sessions [p :streams])) i] p]))
                                      order))))]
    (if-not peer
      (swap! state assoc-in [:wanted hk :at] nil)
      (let [[table sid] (frame/open-local (get-in sessions [peer :table]))]
        (swap! state (fn [s]
                       (-> s
                           (assoc-in [:sessions peer :table] table)
                           (assoc-in [:sessions peer :streams sid]
                                     {:hash hash
                                      :reader (frame/reader sid)
                                      :rx (verify/receiver store)})
                           (assoc-in [:wanted hk :at] [peer sid]))))
        (send! peer {:type :get :stream sid
                     :value {"hash" (msg/hash-ref (store/hash->hex hash)) "policy" "default" "recv-max" recv-max}})))))

(defn request!
  "Want `h`. Returns `:done` when it is already stored and verified, else
   `:requested` (the `get` went out) or `:unserved` (no open session)."
  [c h]
  (let [hk (gc/hash-key h) state (:state c)]
    (cond
      (have? c h) (do (swap! state update :done conj hk) :done)
      (contains? (:wanted @state) hk)
      (if (get-in @state [:wanted hk :at]) :requested :unserved)
      :else (do (swap! state assoc-in [:wanted hk] {:hash h :tried #{} :at nil})
                (dispatch! c hk)
                (if (get-in @state [:wanted hk :at]) :requested :unserved)))))

(defn open-session!
  "A session with `peer-id` is up. `role` is `:offerer` (this side sent the
   WebRTC offer, odd stream ids; the default) or `:answerer`. Hashes that
   were waiting for a session are asked of it now."
  ([c peer-id] (open-session! c peer-id :offerer))
  ([c peer-id role]
   (swap! (:state c)
          (fn [s]
            (-> s
                (assoc-in [:sessions peer-id]
                          {:status :open :table (frame/stream-table role 64) :streams {}})
                (update :order #(conj (vec (remove #{peer-id} %)) peer-id)))))
   (doseq [hk (keys (filter (comp nil? :at val) (:wanted @(:state c))))]
     (dispatch! c hk))
   c))

;; =============================================================================
;; Ending
;; =============================================================================

(defn- reask!
  "Streams on `peer-id` stopped serving `hks`: it is never asked for them
   again, and each goes to another session if there is one."
  [c peer-id hks]
  (swap! (:state c)
         (fn [s]
           (reduce (fn [s hk] (-> s (update-in [:wanted hk :tried] conj peer-id)
                                  (assoc-in [:wanted hk :at] nil)))
                   s hks)))
  (doseq [hk hks] (dispatch! c hk)))

(defn- end-session!
  "End `peer-id`'s session (C13, C15). `code`, when given, is sent as a fatal
   `error` on stream 0 first. The session's unchecked headers are dropped
   (verified items stay), the connection is closed, and each stream still open
   is asked of another session (C14). No other session is read or changed."
  [c peer-id code text]
  (when (open? c peer-id)
    (let [{:keys [state send! close!]} c
          {:keys [streams]} (get-in @state [:sessions peer-id])
          hks (mapv (comp gc/hash-key :hash) (vals streams))]
      (doseq [{:keys [rx]} (vals streams)] (verify/discard-unchecked! rx))
      (swap! state update-in [:sessions peer-id]
             assoc :status :ended :streams {})
      (when code
        (send! peer-id {:type :error :stream 0
                        :value {"code" code "message" text "fatal" true}}))
      (close! peer-id)
      (reask! c peer-id hks))))

(defn- fail!
  "Exception `e` ends `peer-id`'s session with `error` and fatal true."
  [c peer-id e]
  (end-session! c peer-id (error-code e)
                (str (name (or (:reason (ex-data e)) :error)) ": " (ex-message e))))

(defn close-session!
  "The connection to `peer-id` is gone. Like a fatal error from the peer:
   nothing is sent, and what it was serving is asked of other sessions."
  [c peer-id]
  (end-session! c peer-id nil nil))

;; =============================================================================
;; Receiving
;; =============================================================================

(defn- decode-item
  "One wire-v1 item from its bytes. A failure to decode is a bad item."
  [bs]
  (try (first (bin/decode-item-stream bs))
       (catch #?(:clj Throwable :cljs :default) e
         (throw (ex-info (str "item does not decode: " (ex-message e))
                         {:reason (if (= :bad-state (:reason (ex-data e)))
                                    :bad-state
                                    :bad-item)})))))

(defn- finish-stream!
  "The stream ended (FIN). Its headers are checked (`finish!`) or, when the
   stream was PARTIAL, dropped. The hash that was asked for is done when it is
   verified in the store; otherwise this peer did not serve it."
  [c peer-id sid partial?]
  (let [{:keys [state]} c
        {:keys [hash rx]} (get-in @state [:sessions peer-id :streams sid])
        hk (gc/hash-key hash)]
    (if partial? (verify/discard-unchecked! rx) (verify/finish! rx))
    (swap! state (fn [s]
                   (-> s
                       (update-in [:sessions peer-id :table] frame/close-stream sid)
                       (update-in [:sessions peer-id :streams] dissoc sid))))
    (if (have? c hash)
      (swap! state (fn [s] (-> s (update :done conj hk) (update :wanted dissoc hk))))
      (reask! c peer-id [hk]))))

(defn- on-items!
  [c peer-id fr]
  (let [{:keys [state]} c
        sid (:stream fr)
        {:keys [reader rx]} (get-in @state [:sessions peer-id :streams sid])
        [reader' item-bytes] (frame/feed reader fr)]
    (swap! state assoc-in [:sessions peer-id :streams sid :reader] reader')
    ;; each item is verified, then stored, before the next is read (C27)
    (doseq [bs item-bytes]
      (verify/accept-item! rx (decode-item bs)))
    (when (:fin? fr)
      (finish-stream! c peer-id sid (:partial? fr)))))

(defn- on-error!
  "An `error` the peer sent. Fatal on stream 0: the session is over. On a
   stream: that stream is over, and the peer did not serve its hash."
  [c peer-id bs]
  (let [{:keys [type stream value]} (msg/decode-message bs)]
    (when (= :error type)
      (if (or (zero? stream) (get value "fatal"))
        (end-session! c peer-id nil nil)
        (when-let [{:keys [hash rx]} (get-in @(:state c) [:sessions peer-id :streams stream])]
          (verify/discard-unchecked! rx)
          (swap! (:state c) (fn [s]
                              (-> s
                                  (update-in [:sessions peer-id :table] frame/close-stream stream)
                                  (update-in [:sessions peer-id :streams] dissoc stream))))
          (reask! c peer-id [(gc/hash-key hash)]))))))

(defn on-frame!
  "The frame (bytes) that arrived on `peer-id`'s session.

   - `item-stream` on an open stream: each item is verified and then stored.
     Any failure ends the whole session (C13). A frame for an id that is not
     open is ignored and stores nothing.
   - `error` from the peer: see `on-error!`.
   - anything else is ignored in this slice.

   A frame the codec refuses ends the session with `malformed`. Returns nil."
  [c peer-id frame-bytes]
  (when (open? c peer-id)
    (try
      (let [fr (frame/decode-frame frame-bytes)]
        (case (:type fr)
          :item-stream
          (when (get-in @(:state c) [:sessions peer-id :streams (:stream fr)])
            (on-items! c peer-id fr))
          :error (on-error! c peer-id frame-bytes)
          nil))
      (catch #?(:clj Throwable :cljs :default) e
        (fail! c peer-id e))))
  nil)
