(ns dacite.peer.transport.mem
  "The in-memory `ITransport` (M3.1): two ends of one connection in one process,
   no WebRTC. Portable (.cljc). It exists so the peer protocol is tested through
   the same interface WebRTC will implement, and keeps every rule of
   `dacite.peer.transport`: whole messages, in order, nothing split, back-pressure
   per channel, close ending that channel (or all of them).

   `(connect opts)` returns `[a b]`, the two ends (each an `ITransport`).

   Delivery goes through one queue per connection, delivered by `pump!`, so a
   handler that sends never recurses into the other side's handler: the message
   waits in the queue, and the pump that is already running delivers it. Two modes:

   - **auto** (default): `send!`, `close!` and the rest pump at once. When the
     caller is not inside a handler the message has been handled when `send!`
     returns (`buffered-amount` is back to 0).
   - **manual** (`:manual? true`): nothing is delivered until the check calls
     `pump!` (everything) or `deliver-next!` (one event). A channel's
     `buffered-amount` then holds what was sent and not delivered, so a check can
     fill a channel and drain it, as it would a data channel's `bufferedAmount`.

   Options: `:max-message-size` (default 65536, what libdatachannel offers; a
   test sets 8192 to make the clamp matter) and `:manual?`.

   One thread at a time delivers (an atomic claim), so handlers never run
   concurrently for one connection."
  (:require [dacite.peer.transport :as t]
            [dacite.wire.binary :as bin]))

(def default-max-message-size 65536)

(defn- other [side] (case side :a :b :b :a))

(defn- fresh-end [] {:handler nil :inbox [] :on-close [] :claimed? false :buffered 0})

(defn- fresh-side [] {:on-channel {} :unclaimed #{} :on-conn-close []})

(defn- fail! [reason text data]
  (throw (ex-info text (assoc data :reason reason))))

;; The state of a connection (one atom, `pair`):
;;   :queue    events not delivered yet, oldest first
;;   :busy?    a pump is running
;;   :open?    the connection is open
;;   :sides    {:a {...} :b {...}} per side: :on-channel {name f}, :unclaimed #{name},
;;             :on-conn-close [f]
;;   :chans    {name {:open? :close-fired? :a end :b end}}, an end being
;;             {:handler :inbox :on-close :claimed? :buffered}
;; Events: [:msg from name bs] [:channel to name] [:chan-close name] [:conn-close]

(defn- enqueue! [pair ev] (swap! pair update :queue conj ev))

(defn- take-next!
  "Atomically take the oldest event, or find none and clear `:busy?`."
  [pair]
  (let [[old _] (swap-vals! pair (fn [s] (if (seq (:queue s))
                                           (update s :queue subvec 1)
                                           (assoc s :busy? false))))]
    (first (:queue old))))

(defn- fire-all! [fs] (doseq [f fs] (f)))

(declare ->MemChannel)

(defn- handle!
  [pair [kind a b c]]
  (case kind
    :msg                                ; a = from, b = name, c = bytes
    (let [to (other a)
          size (bin/byte-len c)
          [old _] (swap-vals! pair (fn [s]
                                     (-> s
                                         (update-in [:chans b a :buffered] - size)
                                         ;; keep it in the inbox until a handler is set
                                         (update-in [:chans b to :inbox]
                                                    (fn [in] (if (get-in s [:chans b to :handler]) in (conj in c)))))))
          f (get-in old [:chans b to :handler])]
      (when f (f c)))

    :channel                            ; a = to, b = name
    (let [s @pair]
      (when-not (get-in s [:chans b a :claimed?])
        (if-let [f (get-in s [:sides a :on-channel b])]
          (do (swap! pair assoc-in [:chans b a :claimed?] true)
              (f (->MemChannel pair b a)))
          (swap! pair update-in [:sides a :unclaimed] conj b))))

    :chan-close                         ; a = name
    (let [[old _] (swap-vals! pair (fn [s]
                                     (if (get-in s [:chans a :close-fired?])
                                       s
                                       (-> s
                                           (assoc-in [:chans a :close-fired?] true)
                                           (assoc-in [:chans a :a :on-close] [])
                                           (assoc-in [:chans a :b :on-close] [])))))]
      (when-not (get-in old [:chans a :close-fired?])
        (fire-all! (get-in old [:chans a :a :on-close]))
        (fire-all! (get-in old [:chans a :b :on-close]))))

    :conn-close
    (let [[old _] (swap-vals! pair (fn [s] (-> s
                                               (assoc-in [:sides :a :on-conn-close] [])
                                               (assoc-in [:sides :b :on-conn-close] []))))]
      (fire-all! (get-in old [:sides :a :on-conn-close]))
      (fire-all! (get-in old [:sides :b :on-conn-close])))))

(defn- pump-n!
  "Deliver up to `n` events, one at a time. Does nothing when another pump runs
   (it will deliver what was queued). Returns the number delivered."
  [pair n]
  (let [[old _] (swap-vals! pair (fn [s] (if (:busy? s) s (assoc s :busy? true))))]
    (if (:busy? old)
      0
      (loop [k 0]
        (if (>= k n)
          (do (swap! pair assoc :busy? false) k)
          (if-let [ev (try (take-next! pair)
                           (catch #?(:clj Throwable :cljs :default) e
                             (swap! pair assoc :busy? false) (throw e)))]
            (do (try (handle! pair ev)
                     (catch #?(:clj Throwable :cljs :default) e
                       (swap! pair assoc :busy? false) (throw e)))
                (recur (inc k)))
            k))))))

(defn- auto! [pair] (when-not (:manual? @pair) (pump-n! pair ##Inf)))

(defrecord MemChannel [pair name side]
  t/IChannel
  (channel-name [_] name)
  (send! [_ bs]
    (let [n (bin/byte-len bs) {:keys [max-message-size chans]} @pair]
      (when-not (get-in chans [name :open?])
        (fail! :channel-closed "send on a closed channel" {:channel name}))
      (when (> n max-message-size)
        (fail! :message-too-large "message over the channel's max-message-size"
               {:channel name :size n :max max-message-size}))
      (swap! pair (fn [s] (-> s
                              (update-in [:chans name side :buffered] + n)
                              (update-in [:stats :messages] inc)
                              (update-in [:stats :bytes] + n)
                              (update-in [:stats :largest] max n))))
      (enqueue! pair [:msg side name bs])
      (auto! pair)
      nil))
  (on-message! [_ f]
    (let [[old _] (swap-vals! pair (fn [s] (-> s
                                               (assoc-in [:chans name side :handler] f)
                                               (assoc-in [:chans name side :inbox] []))))]
      (doseq [bs (get-in old [:chans name side :inbox])] (f bs))))
  (on-close! [_ f]
    (let [s @pair]
      (if (get-in s [:chans name :close-fired?])
        (f)
        (swap! pair update-in [:chans name side :on-close] conj f))))
  (buffered-amount [_] (get-in @pair [:chans name side :buffered] 0))
  (max-message-size [_] (:max-message-size @pair))
  (channel-open? [_] (boolean (get-in @pair [:chans name :open?])))
  (close! [_]
    (when (get-in @pair [:chans name :open?])
      (swap! pair assoc-in [:chans name :open?] false)
      (enqueue! pair [:chan-close name])
      (auto! pair))
    nil))

(defrecord MemTransport [pair side]
  t/ITransport
  (open-channel! [_ name]
    (let [s @pair]
      (when-not (:open? s)
        (fail! :connection-closed "open-channel! on a closed connection" {:channel name}))
      (cond
        (get-in s [:chans name side :claimed?])
        (fail! :channel-exists "this side already opened the channel" {:channel name})

        ;; the other side opened it first: this side takes it (negotiated up front)
        (get-in s [:chans name])
        (swap! pair (fn [s] (-> s
                                (assoc-in [:chans name side :claimed?] true)
                                (update-in [:sides side :unclaimed] disj name))))

        :else
        (do (swap! pair assoc-in [:chans name]
                   {:open? true :close-fired? false
                    side (assoc (fresh-end) :claimed? true)
                    (other side) (fresh-end)})
            (enqueue! pair [:channel (other side) name])
            (auto! pair)))
      (->MemChannel pair name side)))
  (on-channel! [_ name f]
    (swap! pair assoc-in [:sides side :on-channel name] f)
    (when (contains? (get-in @pair [:sides side :unclaimed]) name)
      (swap! pair (fn [s] (-> s
                              (assoc-in [:chans name side :claimed?] true)
                              (update-in [:sides side :unclaimed] disj name))))
      (f (->MemChannel pair name side)))
    nil)
  (on-connection-close! [_ f]
    (if (:open? @pair)
      (swap! pair update-in [:sides side :on-conn-close] conj f)
      (f)))
  (connection-open? [_] (boolean (:open? @pair)))
  (close-connection! [_]
    (when (:open? @pair)
      (let [open-names (keep (fn [[n c]] (when (:open? c) n)) (:chans @pair))]
        (swap! pair (fn [s] (reduce #(assoc-in %1 [:chans %2 :open?] false)
                                    (assoc s :open? false) open-names)))
        (doseq [n open-names] (enqueue! pair [:chan-close n]))
        (enqueue! pair [:conn-close])
        (auto! pair)))
    nil))

(defn connect
  "Two connected ends `[a b]` of one in-memory connection (see the namespace doc
   for the options)."
  ([] (connect nil))
  ([{:keys [max-message-size manual?] :or {max-message-size default-max-message-size}}]
   (let [pair (atom {:queue [] :busy? false :open? true :manual? (boolean manual?)
                     :max-message-size max-message-size
                     :sides {:a (fresh-side) :b (fresh-side)}
                     :stats {:messages 0 :bytes 0 :largest 0}
                     :chans {}})]
     [(->MemTransport pair :a) (->MemTransport pair :b)])))

(defn pump!
  "Deliver every queued event on `conn`'s connection (manual mode, or after a
   handler left something behind). Returns the number delivered."
  [conn]
  (pump-n! (:pair conn) ##Inf))

(defn deliver-next!
  "Deliver the oldest queued event only. True when there was one."
  [conn]
  (pos? (pump-n! (:pair conn) 1)))

(defn pending
  "The number of events queued and not yet delivered."
  [conn]
  (count (:queue @(:pair conn))))

(defn stats
  "What has been sent on the connection, both directions: {:messages :bytes
   :largest} (the largest message in bytes)."
  [conn]
  (:stats @(:pair conn)))
