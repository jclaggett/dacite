(ns dacite.peer.async-checks
  "M1.3, portable checks (JVM: async_test.clj, nbb: peer_cljs_test). The peer code
   (client, responder, verify) chains on store promises, so a store that settles
   late works. Plan: docs/plan/peering.md, M1.3. No protocol change.

   Same shape as client_checks.cljc: a check returns a vector of problem strings,
   `checks` maps a name to it. Nothing sleeps: a late store here is a
   `harness/LateStore`, whose calls are pending until the check settles them, so a
   check can look at the state between two store calls.

   - `late-checks`: every M1.2 client and flow check again, over a late store.
   - `checks`: the M1.3 checks (rejected put, two items back to back, synchronous
     stores stay synchronous).
   - `host-promise-check`: a store whose promises are the host's own (a
     `CompletableFuture` that another thread completes, a `js/Promise` that a timer
     resolves), the one check that waits for real."
  (:require [dacite.peer.client :as client]
            [dacite.peer.client-checks :as cc]
            [dacite.peer.flow-checks :as fc]
            [dacite.peer.harness :as h]
            [dacite.peer.msg :as msg]
            [dacite.peer.responder :as responder]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.value :as v]
            [dacite.wire.binary :as bin])
  #?(:clj (:import (java.util.concurrent CompletableFuture TimeUnit))))

(def ^:private bad cc/bad)

(def max-ms
  "No check may take longer than this (milliseconds)."
  3000)

(def ^:private peer-hello
  (msg/encode-message {:type :hello :stream 0 :value responder/default-hello}))

(defn- node
  "A client over `st` with one peer, \"p\", that has said hello. `sent` logs what
   the client sends: [peer message]. Returns {:client :sent :closed}."
  [st]
  (let [sent (atom []) closed (atom [])
        c (client/client {:store st
                          :send! (fn [p m] (swap! sent conj [p m]))
                          :close! (fn [p] (swap! closed conj p))})]
    (client/open-session! c "p")
    (client/on-frame! c "p" peer-hello)
    {:client c :sent sent :closed closed}))

(defn- gets
  "[stream hash-hex] of each `get` sent."
  [{:keys [sent]}]
  (vec (keep (fn [[_ m]] (when (= :get (:type m))
                           [(:stream m) (msg/hash-ref-hex (get-in m [:value "hash"]))]))
             @sent)))

(defn- fatal-codes [{:keys [sent]}]
  (vec (keep (fn [[_ m]] (when (and (= :error (:type m)) (get-in m [:value "fatal"]))
                           (get-in m [:value "code"])))
             @sent)))

(defn- answer!
  "Deliver an honest answer of `src` for `hash` on stream `sid`."
  [{:keys [client]} src sid hash]
  (doseq [f ((cc/honest src) sid hash)] (h/on-frame! client "p" f)))

(defn- held? [st h] (some? (store/await (store/s-get st h))))

(defn- index-of [log entry] (first (keep-indexed (fn [i e] (when (= e entry) i)) log)))

;; =============================================================================
;; The checks
;; =============================================================================

(defn synchronous-store-stays-synchronous
  "A store that settles at once (the in-memory store) changes nothing for a caller:
   `request!` is its keyword, `have?` a boolean, `on-frame!` nil, and none is a
   pending `Deferred`."
  []
  (let [src (store/mem-store)
        root (v/hash (cc/vector-value src 12))
        n (node (store/mem-store))
        c (:client n)
        r1 (client/request! c root)
        [[sid _]] (gets n)
        frames ((cc/honest src) sid root)
        results (mapv #(client/on-frame! c "p" %) frames)
        had (client/have? c root)]
    (concat
     (bad "request! is :requested" :requested r1)
     (bad "on-frame! is nil each time" [nil] (vec (distinct results)))
     (bad "have? is true, not a promise" true had)
     (bad "request! of a had hash is :done" :done (client/request! c root))
     (bad "the hash is done" true (client/done? c root)))))

(defn two-items-back-to-back-the-second-waits-for-the-first
  "Two frames arrive one after the other while the first item's put is still
   pending. The second waits: its verify and put are not started until the first put
   has settled, so the items reach the store in the order they arrived. And an item
   that does not verify is never put: with a bad second item the first is stored
   and the second is not, the session ends with `bad-entry`, and the end is not
   sent until the first put has settled."
  []
  (let [src (store/mem-store)
        root (v/hash (cc/vector-value src 40))
        items (cc/items-of src root)
        hx (fn [i] (cc/hex (:hash (nth items i))))
        run (fn [bad-second?]
              (let [q (atom [])
                    st (h/late-store q)
                    n (node st)
                    c (:client n)
                    step0 (client/request! c root)
                    _ (h/settle! q)
                    [[sid _]] (gets n)
                    frame-of (fn [item] (cc/item-frame sid [(bin/encode-item item)]))
                    item2 (if bad-second?
                            (assoc (nth items 2) :hash (:hash (first items)))
                            (nth items 2))
                    s1 (client/on-frame! c "p" (frame-of (nth items 1)))
                    after1 {:pending? (chain/pending? s1)
                            :put-1-called? (some? (index-of @(:log st) [:call :put (hx 1)]))
                            :held-1? (held? (:inner st) (:hash (nth items 1)))}
                    s2 (client/on-frame! c "p" (frame-of item2))
                    waiting {:pending? (chain/pending? s2)
                             :verify-or-put-2-started? (some? (index-of @(:log st) [:call :put (hx 2)]))
                             :errors-sent (fatal-codes n)}]
                (h/settle! q)
                {:step0-pending? (chain/pending? step0)
                 :after1 after1 :waiting waiting
                 :log @(:log st)
                 :done? [(chain/pending? s1) (chain/pending? s2)]
                 :held-1? (held? (:inner st) (:hash (nth items 1)))
                 :held-2? (held? (:inner st) (:hash (nth items 2)))
                 :held-bad? (held? (:inner st) (:hash (first items)))
                 :status (client/session-status c "p")
                 :errors (fatal-codes n)}))
        good (run false)
        evil (run true)
        log (:log good)]
    (concat
     (bad "good: the first put is pending, not held" {:pending? true :put-1-called? true :held-1? false}
          (:after1 good))
     (bad "good: the second frame waits (nothing of it started, no end)"
          {:pending? true :verify-or-put-2-started? false :errors-sent []}
          (:waiting good))
     (bad "good: both frames are done in the end" [false false] (:done? good))
     (bad "good: both items are held" [true true] [(:held-1? good) (:held-2? good)])
     (bad "good: put 2 is started only after put 1 settled" true
          (< (index-of log [:settle :put (hx 1)]) (index-of log [:call :put (hx 2)])))
     (bad "good: no error, the session is open" [:open []] [(:status good) (:errors good)])
     (bad "bad second: the first item is held" true (:held-1? evil))
     (bad "bad second: the unverified item is never put (nor stored)"
          [nil false] [(index-of (:log evil) [:call :put (hx 0)]) (:held-bad? evil)])
     (bad "bad second: the second frame waited" {:pending? true :verify-or-put-2-started? false :errors-sent []}
          (:waiting evil))
     (bad "bad second: the session ends with bad-entry" [:ended ["bad-entry"]]
          [(:status evil) (:errors evil)]))))

(defn rejected-put-fails-the-hash-and-the-session-lives
  "A store that refuses a put (quota) is not the peer's fault. The hash whose item
   was refused fails locally: it is in `failed` with `:store-failed`, its stream is
   closed here (the rest of its frames are ignored), its unchecked header is
   dropped, and it is not asked of another peer. The session stays open, no error is
   sent, and another hash from the same peer completes. Asking for the failed hash
   again, when the store works, gets it."
  []
  (let [src (store/mem-store)
        x (v/hash (cc/vector-value src 12))
        y (v/hash (cc/map-of src 4))
        x-items (cc/items-of src x)
        refused (into #{} (map #(cc/hex (:hash %))) (rest x-items))   ; not the header
        q (atom [])]
    (binding [h/*late* q]
      (let [n (node (h/late-store q))
            c (:client n)
            st (:store c)
            _ (reset! (:fail-put st) (fn [hash] (contains? refused (cc/hex hash))))
            _ (h/request! c x)
            _ (h/request! c y)
            [[sx _] [sy _]] (gets n)
            _ (answer! n src sx x)
            _ (answer! n src sy y)
            failed (client/failed c)
            after (client/outstanding c "p")
            inner (:inner st)
            problems
            (concat
             (bad "two gets, and none to re-ask x" 2 (count (gets n)))
             (bad "the session is open" :open (client/session-status c "p"))
             (bad "no error was sent, the connection is open" [[] []] [(fatal-codes n) @(:closed n)])
             (bad "x failed locally, as the store failing" [[x] [:store-failed]]
                  [(mapv :hash failed) (mapv (comp :reason ex-data :error) failed)])
             (bad "x is not done and not had" [false false] [(client/done? c x) (h/have? c x)])
             (bad "x's unchecked header is dropped" false (held? inner x))
             (bad "no stream is left open" [] after)
             (bad "y, from the same peer, is done and had" [true true] [(client/done? c y) (h/have? c y)])
             (bad "nothing is waiting for a peer" [] (client/unserved c)))
            ;; the store works again: ask for x again
            _ (reset! (:fail-put st) nil)
            again (h/request! c x)
            [_ _ [sx2 _]] (gets n)
            _ (answer! n src sx2 x)]
        (vec (concat problems
                     (bad "asking again sends a new get" :requested again)
                     (bad "x is done in the end, and is no longer failed" [true []]
                          [(client/done? c x) (client/failed c)])
                     (bad "the session is still open" :open (client/session-status c "p"))))))))

(defn store-failure-while-dropping-headers-stays-local
  "A peer's `error` on a stream ends that stream and its unchecked header is dropped.
   If the store fails on that delete, it is not the peer's doing and not the
   session's: the session stays open, nothing fatal is sent, the stream is over and
   its hash is asked of another peer (here none is left, so it waits)."
  []
  (let [src (store/mem-store)
        root (v/hash (cc/vector-value src 12))
        items (cc/items-of src root)
        q (atom [])]
    (binding [h/*late* q]
      (let [n (node (h/late-store q))
            c (:client n)
            st (:store c)
            _ (h/request! c root)
            [[sid _]] (gets n)
            ;; the header alone: stored, and unchecked until its root arrives
            _ (h/on-frame! c "p" (cc/item-frame sid [(bin/encode-item (first items))]))
            _ (reset! (:fail-delete st) (constantly true))
            _ (h/on-frame! c "p" (cc/error-frame sid "unknown-hash" false))]
        (vec (concat
              (bad "the session is open, nothing fatal, connection open" [:open [] []]
                   [(client/session-status c "p") (fatal-codes n) @(:closed n)])
              (bad "the stream is over" [] (client/outstanding c "p"))
              (bad "the hash waits for another peer" [root] (client/unserved c))
              (bad "the header the store would not delete is not had" false (h/have? c root))))))))

(def checks
  "Name -> check, the M1.3 checks that need no waiting."
  (array-map
   "synchronous-store-stays-synchronous" synchronous-store-stays-synchronous
   "two-items-back-to-back-the-second-waits-for-the-first" two-items-back-to-back-the-second-waits-for-the-first
   "rejected-put-fails-the-hash-and-the-session-lives" rejected-put-fails-the-hash-and-the-session-lives
   "store-failure-while-dropping-headers-stays-local" store-failure-while-dropping-headers-stays-local))

;; =============================================================================
;; The M1.2 checks over a late store
;; =============================================================================

(defn- now-ms [] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))

(defn- timed [f]
  (let [t0 (now-ms)
        problems (try (vec (f))
                      (catch #?(:clj Throwable :cljs :default) e
                        [(str "threw " (ex-message e) " " (pr-str (ex-data e)))]))
        ms (- (now-ms) t0)]
    {:ms ms :problems (cond-> problems (> ms max-ms) (conj (str "took " ms " ms, over " max-ms)))}))

(defn late-checks
  "Every client check and every flow check (M1.2 slices 1 and 2) again, with the
   store of each client and responder a late one (`harness/*late*`): [{:name :ms
   :problems}]. A client check has no time limit of its own here beyond `max-ms`."
  []
  (binding [h/*late* (atom [])]
    (mapv (fn [[name f]] (assoc (timed f) :name (str "on a late store: " name)))
          (concat cc/checks fc/checks))))

(defn run-all
  "Run the checks and the late runs: [{:name :ms :problems}]."
  []
  (into (mapv (fn [[name f]] (assoc (timed f) :name name)) checks)
        (late-checks)))

;; =============================================================================
;; A store whose promises are the host's own
;; =============================================================================

(defn- host-later
  "A host promise for `(f)`, settled by another thread (JVM) or a timer (JS)."
  [f]
  #?(:clj (let [cf (CompletableFuture.)]
            (future (try (Thread/sleep 1) (.complete cf (f))
                         (catch Throwable e (.completeExceptionally cf e))))
            cf)
     :cljs (js/Promise. (fn [ok err]
                          (js/setTimeout (fn [] (try (ok (f)) (catch :default e (err e)))) 0)))))

(defrecord HostStore [inner]
  store/IStore
  (s-get [_ hash] (host-later #(store/await (store/s-get inner hash))))
  (s-put [this hash v] (host-later #(do (store/await (store/s-put inner hash v)) this)))
  (s-has? [_ hash] (host-later #(store/await (store/s-has? inner hash))))
  (s-delete [this hash] (host-later #(do (store/await (store/s-delete inner hash)) this)))
  (s-keys [_] (host-later #(store/await (store/s-keys inner))))
  (s-merge [this m] (host-later #(do (store/await (store/s-merge inner m)) this)))
  (s-reset [this] (host-later #(do (store/await (store/s-reset inner)) this))))

(defn- when-done
  "Call `(k)` when step `x` is done, or `(fail e)` when it failed. On the JVM this
   waits (at most 10 s); on JS it returns at once and calls `k` later."
  [x k fail]
  #?(:clj (try (.get ^CompletableFuture (chain/native x) 10 TimeUnit/SECONDS)
               (k)
               (catch Throwable e (fail e)))
     :cljs (-> (chain/native x) (.then (fn [_] (k))) (.catch fail))))

(defn host-promise-check
  "A client over a store whose promises settle later, by the host (`HostStore`): the
   whole value is received, frame after frame without waiting, and ends up verified
   and stored. Calls `(done problems)` when finished (on the JVM before it returns;
   on nbb from a timer)."
  [done]
  (let [src (store/mem-store)
        root (v/hash (cc/vector-value src 40))
        inner (store/mem-store)
        n (node (->HostStore inner))
        c (:client n)
        fail (fn [e] (done [(str "threw " (ex-message e) " " (pr-str (ex-data e)))]))
        snap (fn [st] (set (map store/hash->hex (store/await (store/s-keys st)))))
        step0 (client/request! c root)
        step0-waited? (chain/pending? step0)
        wanted (set (map (comp cc/hex :hash) (cc/items-of src root)))
        report (fn [frames-waited?]
                 (done (vec (concat
                             (bad "the request and the frames waited for the store" true
                                  (boolean (and step0-waited? frames-waited?)))
                             (bad "the hash is done" true (client/done? c root))
                             (bad "the session is open" :open (client/session-status c "p"))
                             (bad "the store holds exactly the value's nodes" wanted (snap inner))))))
        deliver (fn []
                  (let [[[sid _]] (gets n)
                        ;; every frame at once, as a transport delivers them
                        steps (mapv #(client/on-frame! c "p" %) ((cc/honest src) sid root))
                        frames-waited? (boolean (some chain/pending? steps))]
                    (when-done (last steps) #(report frames-waited?) fail)))]
    (when-done step0 deliver fail)))
