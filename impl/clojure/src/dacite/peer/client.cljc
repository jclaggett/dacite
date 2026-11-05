(ns dacite.peer.client
  "Peer protocol v1, the requesting side: sessions, and what a failure does.
   Portable (.cljc): the same code runs on the JVM and on nbb. No I/O and no
   clock of its own: the caller says what time it is (`:now`, `tick!`), hands in
   each frame it reads (`on-frame!`) and gets each message to send through the
   `:send!` callback.

   Spec: docs/spec/peer-v1.md §12.2 (C01, C02, C45), §12.4 (session end, C16),
   §12.8 (verification), §12.9 (C35), §12.11 (C41). M1.2 slices 1 and 2. Not here
   yet (M4): interest ending a stream at its floor, `cancel`, `ack` and `forget`.

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
   - **Rates (C01, C02, C45).** Every `get` carries a `recv-max` (`next-recv-max`):
     the peer's C01/C02 share of the client's one `R_max`, counting the new stream
     at peak interest. The peer's `send-max` (from its `hello`) caps its share and
     the excess goes to the others. A new peer starts at its full share, as if the
     others were already rebalanced. The others keep their older, higher `recv-max`
     until their next `get` or until their interest decays, so the client can be
     over `R_max` for a while (the accepted gap of spec 12.2). A `get` starts at
     peak interest and decays by the clock.
   - **Request bucket (C41).** A bucket per peer from its `hello` (`req-rate`,
     `req-burst`). A `get` is not sent to a peer whose bucket is empty: it goes to
     another peer, or waits and is sent by `tick!` when a token is there.
   - **Protocol violations end the session (C16).** A frame other than `hello` and
     `error` before the peer's `hello`, a second `hello`, a malformed frame, and an
     `item-stream` for a stream id this side never opened: `error` `malformed`,
     fatal, on stream 0, then `:close!`. An item for a closed id is ignored (C35).
     A request that fails (`error` on a stream) ends that stream only.
   - **What the peer was serving is re-requested elsewhere (C14).** Each
     stream still open on the ended session becomes a new `get` on another
     open session that has not already failed to serve that hash. With no
     such session the hash waits in `unserved` and goes out when a session
     opens.

   **Store calls may be asynchronous (M1.3).** Every store call here chains on its
   promise (`dacite.store.chain`) instead of unwrapping it with `store/await`, so a
   store that settles late (a host promise that is not done yet) works as well as
   the in-memory store. The functions that touch the store (`request!`, `have?`,
   `on-frame!`, `close-session!`) return a *step*: the plain value (`on-frame!`:
   nil) when the store answered at once, which is always the case with
   `store/mem-store`, or a `chain/Deferred` when it did not (`chain/native` makes a
   `js/Promise` or `CompletableFuture` of it).
   Order is kept: the client does one thing at a time. A frame, a `request!` or a
   `close-session!` that arrives while an earlier one still waits for the store is
   queued and runs after it, in the order of arrival, across all sessions (the store
   is shared, so two sessions never read-modify-write it at once). Within a frame,
   an item is verified, then stored, before the next is read. `open-session!` and
   `tick!` touch no store and run at once.
   **A store that fails does not end the session (M1.3).** If a store call fails
   (a put that is refused for quota, a read that cannot be answered), the peer did
   nothing wrong: that hash fails locally. Its stream is closed on this side (its
   later items are ignored, C35), its unchecked headers are dropped, and the hash
   is not asked of another peer (another peer would hit the same store). The
   session stays open and its other streams go on. The hash is in `failed`; asking
   for it again with `request!` tries again.

   The store is shared by all sessions, so a collection header that one session
   stored and has not yet checked is not taken as the answer for another session:
   each session stores, tracks and checks its own copy (`verify/accept-item!`), a
   session that gives up removes only its own copy (`verify/discard-unchecked!`),
   and `have?` asks the store whether the header matches its root, not who sent it.
   A session ending counts only against its own peer for the hashes it was serving.
   A reconnecting peer is out of scope: a peer that failed to serve a hash is not
   asked for it again.

   A `get` for a hash a peer cannot serve (`error unknown-hash`, or a stream
   that ends without the hash) ends that stream only, and the hash is asked of
   another peer. A hash is never asked twice of the same peer."
  (:require [dacite.peer.bucket :as bucket]
            [dacite.peer.frame :as frame]
            [dacite.peer.msg :as msg]
            [dacite.peer.rates :as rates]
            [dacite.peer.verify :as verify]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.wire.binary :as bin]))

(def default-r-max
  "The client's one `R_max`, bytes a second, when none is given."
  10000000)

(def default-interest rates/default-interest)

(defn default-hello
  "This side's `hello`: a client that wants `r-max` bytes a second in all."
  [r-max]
  {"versions" #{1}
   "kind" "node"
   "caps" #{"reveal"}
   "limits" {"max-frame" 16384 "max-item" 1048576 "max-streams" 32}
   "rates" {"send-max" r-max "recv-max" r-max "req-rate" 20 "req-burst" 40}})

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

;; The client's per-session state:
;;   :status        :open | :ended
;;   :table         `frame/stream-table`: this side's ids
;;   :streams       open streams, id -> {:hash :reader :rx :t0 :peak :tau :served?}
;;   :hello         the peer's `hello`, nil until it arrives
;;   :bucket        request bucket (C41), from the peer's hello
;;   :verified      {hash-key entry}: what this session verified and the entry it
;;                  stored, a cache for `have?` (dropped when the session ends)
(defn client
  "A client. Options:

     :store     where verified items go (default a new mem-store)
     :send!     (fn [peer-id message]) with message {:type :get :stream n
                :value {...}}, {:type :hello ...} or {:type :error ...}; the
                caller encodes it with `msg/encode-message`
     :close!    (fn [peer-id]) the session is over; close the connection
     :r-max     the client's one `R_max`, bytes a second (`default-r-max`)
     :now       (fn [] milliseconds): the clock (default the system clock)
     :hello     this side's hello (default `default-hello` of `r-max`)

   The state is in an atom under `:state`; read it through the functions
   below, not directly."
  [{:keys [store send! close! r-max now hello]
    :or {send! (fn [_ _]) close! (fn [_]) r-max default-r-max
         now (fn [] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))}}]
  {:store (or store (store/mem-store))
   :send! send!
   :close! close!
   :r-max r-max
   :now now
   :hello (or hello (default-hello r-max))
   ;; :sessions peer-id -> the session state above
   ;; :order    peer ids, oldest first
   ;; :wanted   hash-key -> {:hash h :tried #{peer-id} :at [peer-id stream]}
   :state (atom {:sessions {} :order [] :wanted {} :done #{} :failed {}})
   ;; one thing at a time (`serially!`): whether a task runs, and the tasks that wait
   :queue (atom {:busy? false :tasks []})})

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
  "Hashes wanted that no `get` is out for: no open session can take one now (none
   has said `hello`, or all failed to serve it, or every request bucket is empty).
   They go out when a session opens or `tick!` finds a token."
  [c]
  (into [] (comp (filter (comp nil? :at)) (map :hash)) (vals (:wanted @(:state c)))))

(defn done?
  "True when the wanted hash `h` was received, verified, and stored."
  [c h]
  (contains? (:done @(:state c)) (gc/hash-key h)))

(defn have?
  "True when `h` is stored and verified (C27, C31): a node that checked when it
   was stored, or a collection header whose root is stored and matches it. A
   header that still waits for its root, or a forged one, is not had, whichever
   session stored it. Only a hash that is had may be read or offered to another
   peer. A step (see the namespace doc).

   An open session remembers what it verified, with the entry it stored
   (`:verified`). A hash is not checked again while the store still holds that
   same entry; if the entry changed or is gone the cache does not count, and the
   store is checked (`verify/checked?`). A session's memory goes when it ends.
   Any other hash is checked against the store."
  [c h]
  (let [hk (gc/hash-key h)]
    (chain/then
     (chain/store-op #(store/s-get (:store c) h) h)
     (fn [entry]
       (and (some? entry)
            (or (some #(let [e (get (:verified %) hk)] (and (some? e) (= e entry)))
                      (vals (:sessions @(:state c))))
                (do (swap! (:state c) update :rechecks (fnil inc 0))
                    (verify/checked? (:store c) h))))))))

(defn failed
  "The hashes that failed locally because the store failed (see the namespace
   doc): [{:hash :error}]."
  [c]
  (vec (vals (:failed @(:state c)))))

(defn recheck-count
  "How many times `have?` had to check a stored hash against the store because no
   session had verified it (see `have?`). A hash a session verified is not counted."
  [c]
  (get @(:state c) :rechecks 0))

;; =============================================================================
;; Rates (C01, C02, C45)
;; =============================================================================

(defn- sum-interest
  "Σ I of a session's open streams at `now`."
  [session now]
  (reduce + 0 (map (fn [{:keys [peak tau t0]}] (rates/interest peak tau (- now t0)))
                   (vals (:streams session)))))

(defn- next-recv-max
  "The `recv-max` for a new `get` to `peer` at `now` (C45): the peer's C01/C02
   share, counting the new stream at peak interest. The other peers' older
   `recv-max`s are not subtracted."
  [c sessions peer now]
  (let [ready (into {} (filter (fn [[_ s]] (and (= :open (:status s)) (:hello s)))) sessions)
        interest (fn [p] (+ (sum-interest (get ready p) now) (if (= p peer) rates/peak 0)))
        shares (rates/peer-shares (:r-max c)
                                  (into {} (map (fn [[p s]]
                                                  [p {:interest (interest p)
                                                      :cap (get-in s [:hello "rates" "send-max"])}]))
                                        ready))]
    (rates/recv-max (get shares peer))))

;; =============================================================================
;; Asking
;; =============================================================================

(defn- candidate-peers
  "Open sessions that have said `hello`, have not failed to serve `tried`'s hash,
   and (C41) have a token in their bucket, as [[load index] peer], fewest streams
   first, oldest on a tie."
  [{:keys [state now]} tried]
  (let [{:keys [sessions order]} @state
        t (now)]
    (sort-by first
             (keep-indexed
              (fn [i p]
                (let [s (get sessions p)]
                  (when (and (= :open (:status s)) (:hello s)
                             (not (contains? tried p))
                             (bucket/has-token? (:bucket s) t))
                    [[(count (:streams s)) i] p])))
              order))))

(defn- dispatch!
  "Send a `get` for wanted hash `hk` to the best candidate (`candidate-peers`).
   With none, the hash stays in `unserved`."
  [c hk]
  (let [{:keys [state store send!]} c
        {:keys [hash tried]} (get-in @state [:wanted hk])
        peer (second (first (candidate-peers c tried)))]
    (if-not peer
      (swap! state assoc-in [:wanted hk :at] nil)
      (let [now ((:now c))
            sessions (:sessions @state)
            recv-max (next-recv-max c sessions peer now)
            [table sid] (frame/open-local (get-in sessions [peer :table]))
            [bucket' _ _] (bucket/take-token (get-in sessions [peer :bucket]) now)]
        (swap! state (fn [s]
                       (-> s
                           (assoc-in [:sessions peer :table] table)
                           (assoc-in [:sessions peer :bucket] bucket')
                           (assoc-in [:sessions peer :streams sid]
                                     {:hash hash
                                      :reader (frame/reader sid)
                                      :rx (verify/receiver store)
                                      :t0 now
                                      :peak (get default-interest "peak")
                                      :tau (get default-interest "tau-ms")})
                           (assoc-in [:wanted hk :at] [peer sid]))))
        (send! peer {:type :get :stream sid
                     :value {"hash" (msg/hash-ref (store/hash->hex hash))
                             "policy" "default"
                             "recv-max" recv-max
                             "interest" default-interest}})))))

(defn- dispatch-waiting! [c]
  (doseq [hk (keys (filter (comp nil? :at val) (:wanted @(:state c))))]
    (dispatch! c hk)))

(defn tick!
  "Time has moved: send the `get`s that waited for a token (C41)."
  [c]
  (dispatch-waiting! c)
  nil)

(defn- drain!
  "Run the tasks that waited, one at a time: the next starts when the one before
   it is done. Each task's outcome goes to its `Deferred`. Taking a task, or
   finding none and clearing `:busy?`, is one atomic update, so a task queued from
   another thread meanwhile is never stranded."
  [c]
  (let [q (:queue c)]
    (loop []
      (let [[old _] (swap-vals! q (fn [s] (if (seq (:tasks s))
                                            (update s :tasks subvec 1)
                                            (assoc s :busy? false))))]
        (when-let [[f d] (first (:tasks old))]
          (let [[tag v] (try [:ok (f)] (catch #?(:clj Throwable :cljs :default) e [:err e]))]
            (if (= :err tag) (chain/reject! d v) (chain/pipe! d v))
            (if (chain/pending? d)
              (chain/on-done d #(drain! c))
              (recur))))))))

(defn- serially!
  "Do task `f` (a function of no arguments that returns a step) when nothing else
   is waiting for the store, else after the tasks before it. Returns `f`'s step
   (what `f` throws is thrown), or a `Deferred` of it when it had to wait.
   Whether to run or to queue is decided by one atomic update (`swap-vals!`), so
   callbacks on several JVM threads cannot both start a task."
  [c f]
  (let [q (:queue c) d (chain/deferred)
        [old _] (swap-vals! q (fn [s] (if (:busy? s)
                                        (update s :tasks conj [f d])
                                        (assoc s :busy? true))))]
    (if (:busy? old)
      d
      (do (let [[tag v] (try [:ok (f)] (catch #?(:clj Throwable :cljs :default) e [:err e]))]
            (if (= :err tag) (chain/reject! d v) (chain/pipe! d v)))
          (if (chain/pending? d)
            (do (chain/on-done d #(drain! c))
                d)
            (do (drain! c)
                (chain/result d)))))))

(defn request!
  "Want `h`. Returns `:done` when it is already stored and verified, else
   `:requested` (the `get` went out) or `:unserved` (no session can take it now).
   A step: with a store that settles late, a `Deferred` of one of those; with other
   work still waiting for the store, it runs after it."
  [c h]
  (serially! c
        (fn []
          (let [hk (gc/hash-key h) state (:state c)]
            (chain/then
             (have? c h)
             (fn [had]
               (cond
                 had (do (swap! state update :done conj hk) :done)
                 (contains? (:wanted @state) hk)
                 (if (get-in @state [:wanted hk :at]) :requested :unserved)
                 :else (do (swap! state (fn [s] (-> s
                                                    (update :failed dissoc hk)
                                                    (assoc-in [:wanted hk] {:hash h :tried #{} :at nil}))))
                           (dispatch! c hk)
                           (if (get-in @state [:wanted hk :at]) :requested :unserved)))))))))

(defn open-session!
  "A connection to `peer-id` is up. This side's `hello` is sent at once; nothing
   else is until the peer's arrives (`on-frame!`). `role` is `:offerer` (this
   side sent the WebRTC offer, odd stream ids; the default) or `:answerer`.
   `opts` may hold `:max-message-size`, the largest message the connection's
   channel carries: the `hello` sent is clamped to it (`msg/clamp-hello`)."
  ([c peer-id] (open-session! c peer-id :offerer))
  ([c peer-id role] (open-session! c peer-id role nil))
  ([c peer-id role {:keys [max-message-size]}]
   (swap! (:state c)
          (fn [s]
            (-> s
                (assoc-in [:sessions peer-id]
                          {:status :open :table (frame/stream-table role 64) :streams {}
                           :hello nil :bucket nil :verified {}})
                (update :order #(conj (vec (remove #{peer-id} %)) peer-id)))))
   ((:send! c) peer-id {:type :hello :stream 0
                        :value (cond-> (:hello c)
                                 max-message-size (msg/clamp-hello max-message-size))})
   c))

(defn- on-hello!
  "The peer's `hello` (validated by `msg/decode-message`). Its rates give the
   request bucket (C41) and the cap on its share (C02); hashes that waited for a
   session go out."
  [c peer-id value]
  (swap! (:state c) update-in [:sessions peer-id] assoc
         :hello value
         :bucket (bucket/bucket (get-in value ["rates" "req-rate"])
                                (get-in value ["rates" "req-burst"])
                                ((:now c))))
  (dispatch-waiting! c))

;; =============================================================================
;; Ending
;; =============================================================================

(defn- reask!
  "Streams on `peer-id` stopped serving `hks`: it is never asked for them
   again, and each goes to another session if there is one. Only `peer-id`
   is marked: what happens on one session never counts against another."
  [c peer-id hks]
  (swap! (:state c)
         (fn [s]
           (reduce (fn [s hk] (-> s (update-in [:wanted hk :tried] conj peer-id)
                                  (assoc-in [:wanted hk :at] nil)))
                   s hks)))
  (doseq [hk hks] (dispatch! c hk)))

(defn- retry!
  "The hash was served and verified on a stream that has since ended, but it is
   no longer stored (another session's failure removed a copy they shared). The
   peer did nothing wrong: it is not marked, and the hash is asked again."
  [c hk]
  (swap! (:state c) assoc-in [:wanted hk :at] nil)
  (dispatch! c hk))

(defn- end-session!
  "End `peer-id`'s session (C13, C15). `code`, when given, is sent as a fatal
   `error` on stream 0 first. The session is over at once: the error goes out and
   the connection closes. Then its unchecked headers are dropped (verified items
   stay; a store that fails here leaves them), and each stream still open is asked
   of another session (C14). No other session is read or changed. A step."
  [c peer-id code text]
  (when (open? c peer-id)
    (let [{:keys [state send! close!]} c
          {:keys [streams]} (get-in @state [:sessions peer-id])
          hks (mapv (comp gc/hash-key :hash) (vals streams))]
      (swap! state update-in [:sessions peer-id]
             assoc :status :ended :streams {} :verified {})
      (when code
        (send! peer-id {:type :error :stream 0
                        :value {"code" code "message" text "fatal" true}}))
      (close! peer-id)
      (chain/then (chain/guard #(chain/each (vals streams)
                                            (fn [{:keys [rx]}] (verify/discard-unchecked! rx)))
                               (fn [_] nil))
                  (fn [_] (reask! c peer-id hks) nil)))))

(defn- fail!
  "Exception `e` ends `peer-id`'s session with `error` and fatal true."
  [c peer-id e]
  (end-session! c peer-id (error-code e)
                (str (name (or (:reason (ex-data e)) :error)) ": " (ex-message e))))

(defn close-session!
  "The connection to `peer-id` is gone. Like a fatal error from the peer:
   nothing is sent, and what it was serving is asked of other sessions. A step."
  [c peer-id]
  (serially! c #(end-session! c peer-id nil nil)))

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
   verified in the store; otherwise this peer did not serve it. A step."
  [c peer-id sid partial?]
  (let [{:keys [state]} c
        {:keys [hash rx served?]} (get-in @state [:sessions peer-id :streams sid])
        hk (gc/hash-key hash)]
    (chain/then
     (if partial? (verify/discard-unchecked! rx) (verify/finish! rx))
     (fn [_]
       (chain/then
        (have? c hash)
        (fn [had]
          (swap! state (fn [s]
                         (-> s
                             (update-in [:sessions peer-id :table] frame/close-stream sid)
                             (update-in [:sessions peer-id :streams] dissoc sid))))
          (cond
            had (swap! state (fn [s] (-> s (update :done conj hk) (update :wanted dissoc hk))))
            served? (retry! c hk)
            :else (reask! c peer-id [hk]))))))))

(defn- fail-locally!
  "The store failed (`e` is `:store-failed`) on a stream of `peer-id`'s session.
   The peer did nothing wrong, so the session lives: this stream is closed on this
   side, its unchecked headers are dropped, and the hash is recorded in `failed`
   and not asked again. A step."
  [c peer-id sid e]
  (let [{:keys [state]} c
        {:keys [hash rx]} (get-in @state [:sessions peer-id :streams sid])]
    (when rx
      (let [hk (gc/hash-key hash)]
        (swap! state (fn [s]
                       (-> s
                           (update-in [:sessions peer-id :table] frame/close-stream sid)
                           (update-in [:sessions peer-id :streams] dissoc sid)
                           (update :wanted dissoc hk)
                           (assoc-in [:failed hk] {:hash hash :error e}))))
        (chain/guard #(verify/discard-unchecked! rx) (fn [_] nil))))))

(defn- on-item!
  "One item: decoded, verified, stored, and noted in the session's verified cache
   (what `have?` need not check again). A step."
  [c peer-id sid bs]
  (let [{:keys [state]} c
        {:keys [rx]} (get-in @state [:sessions peer-id :streams sid])
        item (decode-item bs)
        before-hashes (vec (verify/unchecked rx))]
    (chain/then
     (verify/accept-item! rx item)
     (fn [checks]
       (let [after (set (map gc/hash-key (verify/unchecked rx)))
             ;; what this session has verified: the item (unless a header that
             ;; waits) and the headers that its arrival checked
             verified (concat (when-not (contains? checks :pending) [(:hash item)])
                              (remove #(contains? after (gc/hash-key %)) before-hashes))]
         (chain/then
          (chain/each verified
                      (fn [h]
                        (chain/then (chain/store-op #(store/s-get (:store c) h) h)
                                    (fn [e]
                                      (when (some? e)
                                        (swap! state assoc-in [:sessions peer-id :verified (gc/hash-key h)] e))))))
          (fn [_]
            (when (= (gc/hash-key (:hash item))
                     (gc/hash-key (get-in @state [:sessions peer-id :streams sid :hash])))
              (swap! state assoc-in [:sessions peer-id :streams sid :served?] true)))))))))

(defn- on-items!
  "The items of one frame, in order: each is verified, then stored, before the
   next is read (C27). A failed store call is local to the stream (`fail-locally!`);
   any other failure goes up and ends the session. A step."
  [c peer-id fr]
  (let [{:keys [state]} c
        sid (:stream fr)
        {:keys [reader]} (get-in @state [:sessions peer-id :streams sid])
        [reader' item-bytes] (frame/feed reader fr)]
    (swap! state assoc-in [:sessions peer-id :streams sid :reader] reader')
    (chain/guard
     (fn []
       (chain/then (chain/each item-bytes #(on-item! c peer-id sid %))
                   (fn [_]
                     (when (:fin? fr)
                       (finish-stream! c peer-id sid (:partial? fr))))))
     (fn [e]
       (if (chain/store-failed? e)
         (fail-locally! c peer-id sid e)
         (throw e))))))

(defn- on-error!
  "An `error` the peer sent. Fatal on stream 0: the session is over. On a
   stream: that stream is over, and the peer did not serve its hash. A step."
  [c peer-id bs]
  (let [{:keys [type stream value]} (msg/decode-message bs)]
    (when (= :error type)
      (if (or (zero? stream) (get value "fatal"))
        (end-session! c peer-id nil nil)
        (when-let [{:keys [hash rx]} (get-in @(:state c) [:sessions peer-id :streams stream])]
          ;; dropping the headers is best effort, as in `end-session!`: a store that
          ;; fails here leaves them, and the failure stays local
          (chain/then
           (chain/guard #(verify/discard-unchecked! rx) (fn [_] nil))
           (fn [_]
             (swap! (:state c) (fn [s]
                                 (-> s
                                     (update-in [:sessions peer-id :table] frame/close-stream stream)
                                     (update-in [:sessions peer-id :streams] dissoc stream))))
             (reask! c peer-id [(gc/hash-key hash)]))))))))

(defn- handle-frame!
  "What one frame does (see `on-frame!`). A step; throws a protocol violation."
  [c peer-id frame-bytes]
  (let [fr (frame/decode-frame frame-bytes)
        session (get-in @(:state c) [:sessions peer-id])
        type (:type fr)]
    (cond
      (= :hello type)
      (let [{:keys [value]} (msg/decode-message frame-bytes)]
        (when (:hello session)
          (throw (ex-info "a second hello" {:reason :second-hello})))
        (on-hello! c peer-id value))

      (= :error type) (on-error! c peer-id frame-bytes)

      (nil? (:hello session))
      (throw (ex-info "a frame before the peer's hello" {:reason :before-hello}))

      (= :item-stream type)
      (let [sid (:stream fr)]
        (cond
          (contains? (:streams session) sid) (on-items! c peer-id fr)
          (contains? (:used (:table session)) sid) nil
          :else (throw (ex-info "item-stream for a stream this side never opened"
                                {:reason :never-opened :stream sid}))))

      :else nil)))

(defn on-frame!
  "The frame (bytes) that arrived on `peer-id`'s session.

   - `hello` (once, first): see `on-hello!`
   - `item-stream` on an open stream: each item is verified and then stored.
     A verification failure ends the whole session (C13). A frame for an id that
     was opened and is closed is ignored and stores nothing (C35). A store that
     fails ends only that stream (see the namespace doc).
   - `error` from the peer: see `on-error!`
   - anything else is ignored

   A protocol violation ends the session with `malformed` (C16): a frame the
   codec refuses, any frame but `hello` and `error` before the peer's `hello`, a
   second `hello`, an `item-stream` for an id this side never opened.

   Returns nil, or with a store that settles late a `Deferred` of nil that is done
   when the frame has been handled. Frames run in the order of arrival: one that
   comes while an earlier one waits for the store is queued."
  [c peer-id frame-bytes]
  (serially! c
        (fn []
          (when (open? c peer-id)
            (chain/then
             (chain/guard (fn [] (handle-frame! c peer-id frame-bytes))
                          (fn [e] (fail! c peer-id e)))
             (fn [_] nil))))))
