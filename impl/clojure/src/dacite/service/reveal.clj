(ns dacite.service.reveal
  "One reveal session per connection, one stream per focused hash.

   `POST /reveal` carries `{:hash \"<hex>\"}`. The response is a
   concatenation of wire-v1 items (`dacite.wire.binary/decode-item-stream`),
   written when the session clock covers each page. There is no chunk
   envelope and no byte-budget header.

   The connection keeps one `dacite.reveal` session, so later streams on
   it share the rate and skip hashes still offered or held. A second
   request for a hash that is already open waits on that reveal. It does
   not replace the stream that is writing it. A stream that finishes, or
   whose reveal has paused or exhausted its budget, acknowledges the pages
   it wrote and ends. The reveal stays, so a later request resumes. A
   stream that fails, and is the last stream on that reveal, cancels it
   and releases those offers. `GET /events` stays the root-notice stream.
   `GET /node/{hex}` stays the pack.

   `peak`, `tau`, `floor`, and `r-max` are server configuration. They are
   not agreed on the wire. The defaults are placeholders: floor 0 does not
   pause a reveal, and the rate is not derived from load or the pipe. A
   lone decay can send at most `r-max * tau` bytes before the response
   ends."
  (:require [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]
            [dacite.value.types :as types]
            [dacite.wire.binary :as bin])
  (:import [java.io OutputStream]))

(def content-type
  "Response body of a reveal stream: concatenated wire-v1 items."
  "application/vnd.dacite.item-stream.v1")

(def default-params
  "Placeholder share parameters. Not derived from load or the pipe.

   A lone reveal in the decay regime can send at most `r-max * tau`
   bytes, then the response ends and a later request resumes. The
   default `tau` makes that budget `r-max * 1e6` bytes, so a normal
   value finishes while the rate is still near the ceiling. A floor at
   or below 0 does not pause."
  {:peak 1.0 :tau 1.0e6 :floor 0.0 :r-max 1.0e7})

(def ^:private tick-ms 10)

(def ^:private idle-ms
  "How long a stream may go without a new page before it fails."
  20000)

(defn encode-page
  "Wire-v1 item for one page the session has offered."
  ^bytes [store {:keys [hash]}]
  (bin/encode-item {:enc :node
                    :hash hash
                    :entry (store/require-node store hash)}))

(defn- missing-node?
  [e]
  (and (instance? clojure.lang.ExceptionInfo e)
       (= :missing (:reason (ex-data e)))))

(defn- collection-hash
  "Value hash of a collection node, or nil when a child is not stored yet."
  [store type-name root]
  (try
    (let [ef (case type-name
               ("string" "blob" "vector") (ft/ft-elements-fuse store root)
               ("map" "set") (hamt/hamt-elements-fuse store root)
               nil)]
      (when ef
        (types/value-hash type-name ef)))
    (catch clojure.lang.ExceptionInfo e
      (if (missing-node? e)
        nil
        (throw e)))))

(defn expected-hash
  "Content hash of a stored node, or nil when a child it needs is absent.

   Structural nodes carry `elements-fuse` in their measure. A bitmap node
   also folds its bitmap, matching `hamt` construction. A collection root
   uses the same elements fuse as the value that wrote it. A scalar uses
   `scalar-value-hash`."
  [store entry]
  (let [type-name (first entry)
        data (second entry)]
    (cond
      (and (map? data) (contains? (:measure data) :elements-fuse))
      (let [ef (:elements-fuse (:measure data))
            base (types/node-hash type-name ef)]
        (if (= "hamt/bitmap" type-name)
          (hash/unchecked-fuse
           base
           (hash/fuse-bytes (host/int->bytes-be (:bitmap data) 8)))
          base))

      (#{"string" "blob" "vector" "map" "set"} type-name)
      (collection-hash store type-name (:root data))

      :else
      (types/scalar-value-hash entry))))

(defn- take-ready
  "Store items whose hash can be checked. Return those still waiting.

   A literal or a hash mismatch throws. The mismatched item is not stored."
  [store items]
  (reduce
   (fn [waiting {:keys [enc hash entry] :as item}]
     (when-not (= :node enc)
       (throw (ex-info "reveal page is a node" {:enc enc})))
     (if-let [got (expected-hash store entry)]
       (do
         (when-not (= got hash)
           (throw (ex-info "reveal page hash mismatch"
                           {:expected (store/hash->hex hash)
                            :got (store/hash->hex got)})))
         (store/await (store/s-put store hash entry))
         waiting)
       (conj waiting item)))
   []
   items))

(defn accept-ready!
  "Store every item that can be checked now.

   Returns the items still waiting on a child. A mismatch throws and is
   not stored. An item that waits is not a failure yet."
  [store items]
  (loop [pending (vec items)]
    (if (empty? pending)
      []
      (let [still (take-ready store pending)]
        (if (= (count still) (count pending))
          still
          (recur still))))))

(defn accept-items!
  "Verify each item against its hash, then put it.

   A collection root waits until the child it names is already stored.
   A mismatch throws and is not stored. Returns `store`."
  [store items]
  (let [store (or store (store/mem-store))
        still (accept-ready! store items)]
    (when (seq still)
      (throw (ex-info "reveal page could not be verified"
                      {:hashes (mapv #(store/hash->hex (:hash %)) still)})))
    store))

(defn- elapsed
  [origin]
  (/ (- (System/nanoTime) (long origin)) 1.0e9))

(defn- now-fn
  [origin]
  (fn [] (elapsed origin)))

(defn- clock
  "Monotonic session time. Never earlier than the clock already stored."
  [conn]
  (let [t (double ((:now conn)))
        at (:t @(:session conn))]
    (if (some? at)
      (max t (double at))
      t)))

(defn- reveal-done?
  [session id]
  (let [reveal (first (filter #(= id (:id %)) (:reveals session)))]
    (boolean (and reveal (:done? reveal)))))

(defn- offered-hashes
  "Hashes this reveal still has in the session offer set."
  [session id]
  (into []
        (keep (fn [[h meta]]
                (when (= id (:id meta)) h)))
        (:offered session)))

(declare fail-unlocked!)

(defn- writer-for
  "The stream that writes pages for reveal `reveal-id`."
  [streams reveal-id]
  (some (fn [[_ stream]]
          (when (and (:writer? stream) (= reveal-id (:reveal-id stream)))
            stream))
        streams))

(defn- write-batches!
  "Caller holds `(:lock conn)`."
  [conn batches]
  (doseq [{:keys [stream-id out bytes]} batches]
    (let [stream (get @(:streams conn) stream-id)]
      (when (and stream (not (realized? (:done stream))))
        (try
          (.write ^OutputStream out ^bytes bytes)
          (.flush ^OutputStream out)
          (catch Exception e
            (fail-unlocked! conn stream-id e)))))))

(defn- note-page!
  "Record `hash` on the writer and count progress on every stream of
   that reveal. Caller holds the lock."
  [conn writer-id reveal-id hash]
  (swap! (:streams conn)
         (fn [streams]
           (into {}
                 (map (fn [[sid stream]]
                        [sid (cond
                               (= sid writer-id)
                               (-> stream
                                   (update :offered conj hash)
                                   (update :progress (fnil inc 0)))

                               (= reveal-id (:reveal-id stream))
                               (update stream :progress (fnil inc 0))

                               :else stream)]))
                 streams))))

(defn- plan-writes
  "Remember each offered hash on its writer and return the bytes to write.
   Caller holds the lock."
  [conn pages]
  (reduce (fn [batches {:keys [id hash]}]
            (if-let [stream (writer-for @(:streams conn) id)]
              (let [bytes (encode-page (:store conn) {:hash hash})]
                (note-page! conn (:stream-id stream) id hash)
                (conj batches {:stream-id (:stream-id stream)
                               :out (:out stream)
                               :bytes bytes}))
              batches))
          []
          pages))

(defn- close-stream!
  "Ack the pages this stream wrote and wake its request. Caller holds
   the lock. A reveal that has only paused stays in the session."
  [conn sid]
  (let [stream (get @(:streams conn) sid)]
    (when (and stream (not (realized? (:done stream))))
      (let [hashes (vec (:offered stream))]
        (when (seq hashes)
          (reset! (:session conn)
                  (reveal/ack-pages @(:session conn) hashes)))
        (swap! (:streams conn) dissoc sid)
        (deliver (:done stream) :ok)))))

(defn- finish-done!
  "End streams whose reveal has finished, paused, or exhausted its budget.
   Caller holds the lock."
  [conn]
  (let [session @(:session conn)
        t (:t session)]
    (doseq [[sid stream] @(:streams conn)]
      (when-not (realized? (:done stream))
        (let [reveal-id (:reveal-id stream)]
          (when (or (reveal-done? session reveal-id)
                    (and (some? t)
                         (reveal/stalled? session reveal-id t (:params conn))))
            (close-stream! conn sid)))))))

(defn- pump-once!
  [conn]
  (let [lock (:lock conn)]
    (locking lock
      (let [taken (reveal/take-pages @(:session conn)
                                     (:store conn)
                                     (clock conn)
                                     (:params conn))]
        (reset! (:session conn) (:session taken))
        (write-batches! conn (plan-writes conn (:pages taken)))
        (finish-done! conn)))))

(defn- fail-unlocked!
  "Fail the stream `sid`. Caller holds the lock.

   A waiter stops without touching the reveal. The writer, when another
   stream is still attached, is replaced by that stream. The last stream
   cancels the reveal and releases the pages it had offered."
  [conn sid cause]
  (let [stream (get @(:streams conn) sid)]
    (when (and stream (not (realized? (:done stream))))
      (swap! (:streams conn) dissoc sid)
      (let [reveal-id (:reveal-id stream)
            others (filter #(= reveal-id (:reveal-id %))
                           (vals @(:streams conn)))]
        (cond
          (seq others)
          (when (:writer? stream)
            (let [promoted (first others)]
              (swap! (:streams conn) assoc (:stream-id promoted)
                     (assoc promoted :writer? true))))

          (:writer? stream)
          (let [store (:store conn)
                params (:params conn)
                t (clock conn)
                session @(:session conn)
                session (if (some #(= reveal-id (:id %)) (:reveals session))
                          (try
                            (:session (reveal/cancel-reveal session store
                                                            reveal-id t
                                                            params))
                            (catch Exception _ session))
                          session)
                hashes (offered-hashes session reveal-id)
                session (if (seq hashes)
                          (try
                            (reveal/release-pages session store hashes)
                            (catch Exception _ session))
                          session)]
            (reset! (:session conn) session))))
      (deliver (:done stream) cause))))

(defn- scheduler
  [conn]
  (try
    (while @(:alive conn)
      (let [busy? (pos? (count @(:streams conn)))]
        (if busy?
          (do
            (pump-once! conn)
            (Thread/sleep (long tick-ms)))
          (let [lock (:lock conn)]
            (locking lock
              (when (and @(:alive conn) (empty? @(:streams conn)))
                (.wait lock 1000)))))))
    (catch InterruptedException _ nil)))

(defn start-conn
  "A reveal session over `store`. One scheduler thread writes its streams."
  [store params]
  (let [conn {:lock (Object.)
              :session (atom (reveal/session))
              :streams (atom {})
              :stream-seq (atom 0)
              :alive (atom true)
              :thread (atom nil)
              :store store
              :params (merge default-params params)
              :now (now-fn (System/nanoTime))}]
    (let [thread (doto (Thread. #(scheduler conn) "dacite-reveal")
                   (.setDaemon true))]
      (reset! (:thread conn) thread)
      (.start thread))
    conn))

(defn stop-conn!
  [conn]
  (when conn
    (reset! (:alive conn) false)
    (let [lock (:lock conn)]
      (locking lock
        (doseq [id (keys @(:streams conn))]
          (fail-unlocked! conn id (ex-info "reveal connection closed" {})))
        (.notifyAll lock)))
    (when-let [thread @(:thread conn)]
      (.interrupt thread))))

(defn stop-all!
  "Stop every connection in the atom `table`."
  [table]
  (doseq [conn (vals @table)]
    (stop-conn! conn))
  (reset! table {}))

(defn ensure-conn
  "The reveal session for `conn-key`, creating it on first use.

   `conn-key` is the QUIC session on HTTP/3, or the TCP connection on
   HTTP/1.1. An HTTP/3 stream object is not that key: each request has
   its own."
  [table conn-key store params]
  (or (get @table conn-key)
      (locking table
        (or (get @table conn-key)
            (let [conn (start-conn store params)]
              (swap! table assoc conn-key conn)
              conn)))))

(defn begin-reveal!
  "Open a reveal of `h` and write its pages to `out`.

   Returns when the walk has been offered and acknowledged, or when the
   reveal pauses or exhausts its budget. A second call for a hash that is
   already open waits on that reveal and does not take its stream. Pages
   already held or still offered on this connection are not written again.
   Throws when this stream fails or goes `idle-ms` without a new page."
  [conn h ^OutputStream out]
  (let [done (promise)
        sid (volatile! nil)
        lock (:lock conn)]
    (locking lock
      (let [t (clock conn)
            opened (reveal/open-reveal @(:session conn) (:store conn) h t
                                       (:params conn))
            reveal-id (:id opened)
            stream-id (swap! (:stream-seq conn) inc)
            writer? (nil? (writer-for @(:streams conn) reveal-id))]
        (vreset! sid stream-id)
        (reset! (:session conn) (:session opened))
        (swap! (:streams conn) assoc stream-id
               {:stream-id stream-id
                :reveal-id reveal-id
                :out out
                :offered []
                :done done
                :writer? writer?
                :progress 0})
        (write-batches! conn (plan-writes conn (:pages opened)))
        (finish-done! conn)
        (.notifyAll lock)))
    (loop [mark 0]
      (let [result (deref done idle-ms :timeout)]
        (cond
          (= result :timeout)
          (let [[step mark'] (locking lock
                               (let [stream (get @(:streams conn) @sid)
                                     n (long (:progress stream 0))]
                                 (cond
                                   (and stream (> n mark)) [:again n]
                                   (nil? stream) [:done mark]
                                   :else
                                   (do (fail-unlocked! conn @sid
                                                       (ex-info "reveal timed out"
                                                                {:id @sid}))
                                       [:timeout mark]))))]
            (case step
              :again (recur mark')
              :done :ok
              :timeout (throw (ex-info "reveal timed out" {:id @sid}))))

          (instance? Throwable result)
          (throw result)

          :else :ok)))))
