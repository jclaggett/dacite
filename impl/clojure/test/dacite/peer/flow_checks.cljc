(ns dacite.peer.flow-checks
  "M1.2 slice 2, portable checks (JVM: flow_test.clj, nbb: peer_cljs_test).
   Spec: docs/spec/peer-v1.md §12.2 (C01, C02, C03, C45), §12.3 (C05, C07, C10),
   §12.4 (C16), §12.5 (C17, C18), §12.9 (C34, C35, C36), §12.11 (C40, C41);
   test map in §13.

   Same shape as client_checks.cljc: each check returns a vector of problem strings,
   empty when it holds, and `checks` maps the §13 test name to it. Every clock here
   is simulated (an atom of milliseconds that the check advances), so a check that
   covers ten simulated seconds still takes milliseconds of real time.

   Two harnesses: `cnet` (a client, peers that say `hello` and then do what the check
   writes) and `rnet` (a responder, one peer, a fake channel whose buffer the check
   fills and drains)."
  (:require [dacite.peer.client :as client]
            [dacite.peer.client-checks :as cc]
            [dacite.peer.frame :as frame]
            [dacite.peer.harness :as h]
            [dacite.peer.msg :as msg]
            [dacite.peer.rates :as rates]
            [dacite.peer.responder :as responder]
            [dacite.peer.verify :as verify]
            [dacite.reveal :as reveal]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.value :as v]
            [dacite.wire.binary :as bin]))

(def ^:private bad cc/bad)

(defn- near?
  "True when `a` and `b` differ by less than `eps`."
  [a b eps]
  (let [d (- a b)] (< (if (neg? d) (- d) d) eps)))

;; =============================================================================
;; Hellos and messages
;; =============================================================================

(defn- hello-with
  "A peer's hello: the default, with `rates` merged over its rates and a
   `max-frame` of 8192."
  [rates]
  (-> responder/default-hello
      (update "rates" merge rates)
      (assoc-in ["limits" "max-frame"] 8192)))

(defn- hello-bytes [h] (msg/encode-message {:type :hello :stream 0 :value h}))

(defn- get-bytes
  "A `get` frame (bytes) for `root` on stream `sid`."
  ([sid root recv-max] (get-bytes sid root recv-max nil))
  ([sid root recv-max interest]
   (msg/encode-message
    {:type :get :stream sid
     :value (cond-> {"hash" (msg/hash-ref (store/hash->hex root))
                     "policy" "default" "recv-max" recv-max}
              interest (assoc "interest" interest))})))

(defn- kind [bs] (:type (frame/decode-frame bs)))

(defn- stray-frame
  "An item-stream frame on stream `sid` carrying a node item of some other value."
  [sid]
  (let [st (store/mem-store)
        root (v/hash (cc/map-of st 5))]
    (first (cc/frames-of sid [(bin/encode-item (second (cc/items-of st root)))]))))

(defn- garbage [] (bin/hex->bytes "000102"))

(defn- distinct-hashes
  "`k` distinct hashes (of strings), to ask for."
  [k]
  (let [st (store/mem-store)]
    (mapv #(v/hash (v/string st (str "wanted " %))) (range k))))

;; =============================================================================
;; The client harness
;; =============================================================================

(defn- cnet
  "A client with `:r-max` and peers `{peer-id rates}` (each says `hello` with its
   rates over the defaults, unless `:hello?` is false), on a clock the check moves."
  [{:keys [r-max peers hello?] :or {r-max 1000000 hello? true}}]
  (let [clock (atom 0) log (atom []) closed (atom [])
        c (h/client {:r-max r-max :now (fn [] @clock)
                          :send! (fn [p m] (swap! log conj [@clock p m]))
                          :close! (fn [p] (swap! closed conj p))})]
    (doseq [[p rs] peers]
      (client/open-session! c p)
      (when hello? (h/on-frame! c p (hello-bytes (hello-with rs)))))
    {:client c :clock clock :log log :closed closed}))

(defn- advance! [{:keys [clock client]} ms]
  (swap! clock + ms)
  (client/tick! client))

(defn- gets-sent
  "[time peer message] of every `get` the client sent."
  [{:keys [log]}]
  (filterv (fn [[_ _ m]] (= :get (:type m))) @log))

(defn- fatal-errors [{:keys [log]}]
  (filterv (fn [[_ _ m]] (and (= :error (:type m)) (get-in m [:value "fatal"]))) @log))

;; =============================================================================
;; The responder harness
;; =============================================================================

(defn- rnet
  "A responder over `st` with one peer, \"p\", whose `hello` has `peer-rates`.
   `:hello` is the responder's own. `:channel?`: the fake channel holds what is
   written until the check drains it (`set-buffered!`); otherwise nothing is
   ever buffered. `:on-write` (fn [sid buffered-before size]) sees each item
   frame as it is written. `:max-frame`, when set, is the peer's `max-frame`
   (the session uses the smaller of the two hellos)."
  [{:keys [st hello peer-rates channel? on-write hello? max-frame]
    :or {peer-rates {} hello? true on-write (fn [_ _ _])}}]
  (let [clock (atom 0) sent (atom []) closed (atom []) bufs (atom {})
        r (h/responder
           {:store st :now (fn [] @clock)
            :hello (or hello responder/default-hello)
            :send! (fn [p bs]
                     (swap! sent conj [@clock p bs])
                     (when (= :item-stream (kind bs))
                       (let [sid (:stream (frame/decode-frame bs))
                             before (get @bufs sid 0)
                             size (bin/byte-len bs)]
                         (on-write sid before size)
                         (swap! bufs update sid (fnil + 0) size))))
            :close! (fn [p] (swap! closed conj p))
            :buffered (fn [_ sid] (if channel? (get @bufs sid 0) 0))})]
    (responder/open-session! r "p")
    (when hello?
      (h/responder-on-frame! r "p"
                             (hello-bytes (cond-> (hello-with peer-rates)
                                            max-frame (assoc-in ["limits" "max-frame"] max-frame)))))
    {:r r :clock clock :sent sent :closed closed :bufs bufs}))

(defn- send-to! [{:keys [r]} bs] (h/responder-on-frame! r "p" bs))

(defn- step!
  "Move the clock `ms` on and let the responder write. In a channel, `drain!` is
   called with the buffers first (the peer reads)."
  [{:keys [r clock]} ms]
  (swap! clock + ms)
  (responder/tick! r))

(defn- drain! [{:keys [bufs]} sid bytes]
  (swap! bufs update sid (fn [b] (max 0 (- (or b 0) bytes)))))

(defn- set-buffered! [{:keys [bufs]} sid bytes] (swap! bufs assoc sid bytes))

(defn- item-frames
  "Decoded item-stream frames written on `sid`, in order."
  [{:keys [sent]} sid]
  (into []
        (keep (fn [[_ _ bs]]
                (let [fr (frame/decode-frame bs)]
                  (when (and (= :item-stream (:type fr)) (= sid (:stream fr)))
                    fr))))
        @sent))

(defn- item-bytes
  "Bytes of item-stream frames written to each stream between `from` (exclusive)
   and `to` (inclusive) ms: {sid bytes}."
  [{:keys [sent]} from to]
  (reduce (fn [m [t _ bs]]
            (if (and (> t from) (<= t to) (= :item-stream (kind bs)))
              (update m (:stream (frame/decode-frame bs)) (fnil + 0) (bin/byte-len bs))
              m))
          {} @sent))

(defn- control-messages
  "Decoded messages the responder sent that are not item frames, other than its
   hello."
  [{:keys [sent]}]
  (vec (keep (fn [[_ _ bs]]
               (when-not (#{:item-stream :hello} (kind bs)) (msg/decode-message bs)))
             @sent)))

(defn- errors-of [n] (filterv #(= :error (:type %)) (control-messages n)))

(def ^:private big
  "A store with a value of about 630 items, 115 KB, and its root. Built once:
   a responder only reads it."
  (delay (let [st (store/mem-store)] [st (v/hash (cc/map-of st 120))])))

(defn- big-value [] @big)

(def ^:private frame-bytes 8192)

;; =============================================================================
;; C01, C02: the split
;; =============================================================================

(defn shared-rmax-splits-by-interest-across-peers
  "C01: one `R_max`, split by interest: `R_max · I / max(peak, ΣI)`. At low
   interest part of it is unused. Across peers (`peer-shares`) with no caps,
   the same split by each peer's total interest."
  []
  (let [r 1000000
        [a b] (rates/split r [1000 500])
        [c d] (rates/split r [300 200])
        shares (rates/peer-shares r {"x" {:interest 1500} "y" {:interest 500}})]
    (concat
     (bad "two streams at 1000 and 500: first gets two thirds" true
          (near? (* r 2/3) a 1e-6))
     (bad "the second gets a third" true (near? (* r 1/3) b 1e-6))
     (bad "at ΣI = 500 the rate is half of R_max (the rest unused)" true
          (near? (+ c d) (/ r 2) 1e-6))
     (bad "each is its own interest of the peak" [300000 200000] [(long c) (long d)])
     (bad "peer x holds three quarters of the interest, so three quarters of R_max"
          [750000 250000] [(long (get shares "x")) (long (get shares "y"))])
     (bad "a lone peer at peak 1000 gets all of it" true
          (near? r (get (rates/peer-shares r {"x" {:interest 1000}}) "x") 1e-6)))))

(defn- lcg [x] (mod (+ (* x 48271) 11) 2147483647))

(defn capped-peer-share-is-redistributed
  "C02: a peer's rate is `min(share, its send-max)` and what it cannot use goes
   to the others by interest. A hand case, then a property over 300 generated
   sets of peers: the sum never exceeds `R_max`, no peer is over its cap, nobody
   gets less than the smaller of its cap and its plain C01 share, and when
   some peer is uncapped and the interest is at the peak, `R_max` is all used."
  []
  (let [r 1000000
        hand (rates/peer-shares r {"a" {:interest 500 :cap 100000}
                                   "b" {:interest 500}})
        problems (atom [])]
    (loop [seed 7 i 0]
      (when (< i 300)
        (let [seeds (take 9 (iterate lcg seed))
              n (+ 2 (mod (nth seeds 1) 4))
              peers (into {} (map (fn [k]
                                    (let [x (nth seeds (+ 2 k))]
                                      [k (cond-> {:interest (+ 1 (mod x 2000))}
                                           (even? (quot x 7)) (assoc :cap (+ 1 (mod (quot x 11) 1500000))))])))
                          (range n))
              out (rates/peer-shares r peers)
              plain (zipmap (keys peers) (rates/split r (map :interest (vals peers))))
              total (reduce + (vals out))
              uncapped? (some #(nil? (:cap %)) (vals peers))
              total-interest (reduce + (map :interest (vals peers)))]
          (when (> total (+ r 1e-3))
            (swap! problems conj (str "sum over R_max: " total " " (pr-str peers))))
          (doseq [[k {:keys [cap]}] peers]
            (when (and cap (> (get out k) (+ cap 1e-3)))
              (swap! problems conj (str "over its cap: " k " " (pr-str peers))))
            (when (< (get out k) (- (min (or cap 1e18) (get plain k)) 1e-3))
              (swap! problems conj (str "less than its plain share: " k " " (pr-str peers)))))
          (when (and uncapped? (>= total-interest 1000) (< total (- r 1)))
            (swap! problems conj (str "R_max not all used: " total " " (pr-str peers))))
          (recur (nth seeds 8) (inc i)))))
    (concat
     (bad "a: capped at 100000" true (near? 100000 (get hand "a") 1e-6))
     (bad "b: the rest, 900000" true (near? 900000 (get hand "b") 1e-6))
     (take 5 @problems))))

;; =============================================================================
;; C45: a new peer starts at its fair share
;; =============================================================================

(defn new-peer-starts-at-its-fair-share
  "C45 (relaxed): a `get`'s `recv-max` is the peer's current C01/C02 share of
   `R_max`, and nothing else: the other peers' earlier `recv-max`s are not
   subtracted. With equal interest the second peer's first `get` is half of
   `R_max`, not 1 byte a second; a peer capped by its `send-max` gets the cap. A
   minute on, when the first peer's interest has decayed, a new `get` to the
   second peer takes nearly all of `R_max`. The accepted cost is checked too: the
   `recv-max`s last told to the peers sum to more than `R_max` until the first
   peer's next `get` (spec 12.2, known gap)."
  []
  (let [r-max 1000000
        hs (distinct-hashes 6)
        n (cnet {:r-max r-max :peers [["a" {}] ["b" {}] ["c" {"send-max" 100000}]]})
        ask! (fn [h] (h/request! (:client n) h)
               (get-in (last (gets-sent n)) [2 :value "recv-max"]))
        first-rm (ask! (nth hs 0))               ; a alone
        second-rm (ask! (nth hs 1))              ; b at equal interest
        third-rm (ask! (nth hs 2))               ; c, capped by its send-max
        told-sum (+ first-rm second-rm third-rm)
        ;; a minute on: every interest has decayed to about 0.25%
        _ (advance! n 60000)
        late-rm (ask! (nth hs 3))]
    (concat
     (bad "the first get, alone, takes all of R_max" r-max first-rm)
     (bad "the second peer starts at its fair share, half of R_max (not 1 B/s)" 500000 second-rm)
     (bad "the third peer: its send-max, 100000" 100000 third-rm)
     (bad "the recv-maxes last told sum to more than R_max (the accepted gap)" true
          (> told-sum r-max))
     (bad "a minute on, a new get takes nearly all of R_max" true (> late-rm (* 0.9 r-max)))
     (bad "no get was held back for a token" 4 (count (gets-sent n))))))

;; =============================================================================
;; C41: the requester's bucket
;; =============================================================================

(defn requester-bucket-never-overspends
  "C41: the client never sends a `get` to a peer whose bucket is empty. One peer
   with burst 3 and rate 2 a second: of 6 requests at t = 0 three are sent, the
   others wait (`unserved`), and a tick sends one more each half second; at no
   moment have more than `burst + rate · t` been sent. With two peers the
   second takes what the first cannot."
  []
  (let [hs (distinct-hashes 6)
        one (cnet {:peers [["a" {"req-rate" 2 "req-burst" 3}]]})
        overspent (atom [])
        _ (doseq [h hs] (h/request! (:client one) h))
        at0 (count (gets-sent one))
        waiting0 (count (client/unserved (:client one)))
        _ (dotimes [_ 100]                      ; 10 s in 100 ms steps
            (advance! one 100)
            (let [t @(:clock one) sent (count (gets-sent one))]
              (when (> sent (+ 3 (* 2 (/ t 1000))))
                (swap! overspent conj [t sent]))))
        two (cnet {:peers [["a" {"req-rate" 1 "req-burst" 2}] ["b" {"req-rate" 1 "req-burst" 2}]]})
        _ (doseq [h hs] (h/request! (:client two) h))
        per-peer (frequencies (map second (gets-sent two)))]
    (concat
     (bad "at t = 0 only the burst is sent" 3 at0)
     (bad "three wait" 3 waiting0)
     (bad "never more than burst + rate · t" [] @overspent)
     (bad "all are sent in the end" 6 (count (gets-sent one)))
     (bad "nothing waits in the end" [] (client/unserved (:client one)))
     (bad "two peers: burst each, 4 sent at t = 0" {"a" 2 "b" 2} per-peer)
     (bad "two peers: the rest wait" 2 (count (client/unserved (:client two)))))))

;; =============================================================================
;; C35, C16, the have? cache (client side)
;; =============================================================================

(defn- served-node
  "A client, peer \"a\" (and optionally others) saying hello, and the source
   store `src` with a value of 158 items whose root is `root`."
  [peers]
  (let [src (store/mem-store)
        root (v/hash (cc/map-of src 30))]
    [(cnet {:peers peers}) src root]))

(defn- answer!
  "Deliver an honest answer of `src` for `root` on stream `sid` from peer `p`."
  [n src root p sid]
  (doseq [f ((cc/honest src) sid root)] (h/on-frame! (:client n) p f)))

(defn item-on-a-stream-never-opened-ends-the-session
  "C35: an `item-stream` for an id this side never opened ends the session
   (C16), `error malformed` fatal on stream 0 and the connection closed. The
   item is not stored. Other sessions are untouched."
  []
  (let [[n src root] (served-node [["a" {}] ["b" {}]])
        st (h/inner (:store (:client n)))
        items (cc/items-of src root)
        _ (h/request! (:client n) root)    ; stream 1 of a
        before (cc/store-count st)
        item (bin/encode-item (second items))
        _ (h/on-frame! (:client n) "a" (first (cc/frames-of 99 [item])))
        [[_ _ m] :as errs] (fatal-errors n)]
    (concat
     (bad "the session ended" :ended (client/session-status (:client n) "a"))
     (bad "one fatal error, to a" ["a"] (mapv second errs))
     (bad "malformed, on stream 0" ["malformed" 0] [(get-in m [:value "code"]) (:stream m)])
     (bad "closed" ["a"] @(:closed n))
     (bad "nothing was stored" before (cc/store-count st))
     (bad "b is untouched" :open (client/session-status (:client n) "b")))))

(defn item-on-a-closed-stream-is-ignored
  "C35: an `item-stream` for an id that was opened and has closed is ignored: the
   session stays, and nothing is stored."
  []
  (let [[n src root] (served-node [["a" {}]])
        st (h/inner (:store (:client n)))
        _ (h/request! (:client n) root)
        _ (answer! n src root "a" 1)             ; the stream ends: it is closed
        done? (client/done? (:client n) root)
        before (cc/store-count st)
        extra (bin/encode-item (second (cc/items-of src root)))
        _ (h/on-frame! (:client n) "a" (first (cc/frames-of 1 [extra])))]
    (concat
     (bad "the first answer was verified and stored" true done?)
     (bad "the session is open" :open (client/session-status (:client n) "a"))
     (bad "no error was sent" [] (fatal-errors n))
     (bad "nothing more was stored" before (cc/store-count st)))))

(defn protocol-violation-ends-session-request-error-ends-stream
  "C16 on both sides. Client: an item frame before the peer's `hello`, a second
   `hello`, and a malformed frame each end the session (`error malformed`, fatal,
   stream 0, closed); an `error unknown-hash` on a stream ends that stream only,
   the session lives and the hash is asked of the other peer. Responder: a `get`
   before the `hello`, a second `hello`, a malformed frame and an `item-stream`
   each end the session; an `unknown-hash` answer does not."
  []
  (let [violation (fn [what setup]
                    (let [[n _ _] (served-node [])
                          n (cnet {:peers [["a" {}]] :hello? (not= what "before hello")})]
                      (setup n)
                      [what n]))
        item-frame (stray-frame 1)
        client-cases
        [(violation "before hello" #(h/on-frame! (:client %) "a" item-frame))
         (violation "second hello" #(h/on-frame! (:client %) "a" (hello-bytes (hello-with {}))))
         (violation "malformed" #(h/on-frame! (:client %) "a" (garbage)))]
        client-problems
        (mapcat (fn [[what n]]
                  (concat
                   (bad (str "client, " what ": session ended") :ended (client/session-status (:client n) "a"))
                   (bad (str "client, " what ": malformed, fatal, stream 0")
                        [["malformed" true 0]]
                        (mapv (fn [[_ _ m]] [(get-in m [:value "code"]) (get-in m [:value "fatal"]) (:stream m)])
                              (fatal-errors n)))
                   (bad (str "client, " what ": closed") ["a"] @(:closed n))))
                client-cases)
        ;; a request error ends the stream only
        [n _ root] (served-node [["a" {}] ["b" {}]])
        _ (h/request! (:client n) root)
        _ (h/on-frame! (:client n) "a" (cc/error-frame 1 "unknown-hash" false))
        ;; responder side
        st (store/mem-store)
        rroot (v/hash (cc/map-of st 30))
        resp-violation (fn [what f]
                         (let [rn (rnet {:st st :hello? (not= what "get before hello")})]
                           (f rn)
                           [what rn]))
        resp-cases
        [(resp-violation "get before hello" #(send-to! % (get-bytes 1 rroot 1000)))
         (resp-violation "second hello" #(send-to! % (hello-bytes (hello-with {}))))
         (resp-violation "malformed" #(send-to! % (garbage)))
         (resp-violation "item-stream" #(send-to! % (stray-frame 1)))]
        resp-problems
        (mapcat (fn [[what rn]]
                  (let [errs (errors-of rn)]
                    (concat
                     (bad (str "responder, " what ": session ended") :ended (responder/session-status (:r rn) "p"))
                     (bad (str "responder, " what ": malformed, fatal, stream 0")
                          [["malformed" true 0]]
                          (mapv (fn [m] [(get-in m [:value "code"]) (get-in m [:value "fatal"]) (:stream m)]) errs))
                     (bad (str "responder, " what ": closed") ["p"] @(:closed rn)))))
                resp-cases)
        rn (rnet {:st st})
        other (store/mem-store)
        missing (v/hash (v/string other "not here"))
        _ (send-to! rn (get-bytes 1 missing 1000))
        _ (send-to! rn (get-bytes 3 rroot 1000000))
        errs (errors-of rn)]
    (concat
     client-problems
     (bad "client: a request error does not end the session" :open (client/session-status (:client n) "a"))
     (bad "client: no fatal error" [] (fatal-errors n))
     (bad "client: that stream is over" [] (client/outstanding (:client n) "a"))
     (bad "client: the hash is asked of the other peer" ["b"] (mapv second (rest (gets-sent n))))
     resp-problems
     (bad "responder: request errors end the stream only"
          [["unknown-hash" 1 false]]
          (mapv (fn [m] [(get-in m [:value "code"]) (:stream m) (get-in m [:value "fatal"])]) errs))
     (bad "responder: the session lives and serves the next get" #{3}
          (responder/open-streams (:r rn) "p"))
     (bad "responder: not closed" [] @(:closed rn)))))

(defn have-does-not-recompute-what-a-session-verified
  "A session remembers what it verified and the entry it stored, so `have?` on
   those hashes (the root, a header, every item of a value of 158 items) does not
   check the store again. A hash that is stored but that no session verified is
   checked, once per call. The memory is tied to the entry: if the store's entry
   for a verified header is replaced by a forged one `have?` says no (and yes
   again when the real one is back); and it is dropped when the session ends
   (`have?` is still true, but checks the store again)."
  []
  (let [[n src root] (served-node [["a" {}]])
        c (:client n)
        st (h/inner (:store c))
        items (cc/items-of src root)
        _ (h/request! c root)
        _ (answer! n src root "a" 1)
        after-answer (client/recheck-count c)
        all-had? (every? #(h/have? c (:hash %)) items)
        _ (dotimes [_ 5] (h/have? c root))
        after (client/recheck-count c)
        ;; a hash put in the store by something other than a session
        stranger (v/hash (v/string st "a stranger"))
        _ (h/have? c stranger)
        _ (h/have? c stranger)
        rechecks-stranger (- (client/recheck-count c) after)
        ;; the header's stored entry is replaced by a forged one, then restored
        entry (store/await (store/s-get st root))
        _ (store/await (store/s-put st root (update-in entry [1 :count] inc)))
        forged-had? (h/have? c root)
        _ (store/await (store/s-put st root entry))
        restored-had? (h/have? c root)
        before-end (client/recheck-count c)
        _ (h/close-session! c "a")
        had-after-end? (every? #(h/have? c (:hash %)) items)]
    (concat
     (bad "the answer was verified" true (client/done? c root))
     (bad "the answer needed no re-check" 0 after-answer)
     (bad "every item is had" true all-had?)
     (bad "have? on verified hashes re-checks nothing" 0 after)
     (bad "a stranger hash (stored outside a session) is checked each time" 2 rechecks-stranger)
     (bad "a forged header in the store's place is not had, whatever the cache says" false forged-had?)
     (bad "the real entry back: had" true restored-had?)
     (bad "after the session ends everything is still had" true had-after-end?)
     (bad "and the session's memory is gone: the store is checked again" true
          (> (client/recheck-count c) before-end)))))

;; =============================================================================
;; The responder: C03, C17, C18, C34, C36, C40
;; =============================================================================

(defn responder-paces-to-session-budget
  "C03: the responder writes at `min(send-max, budget)`, where the budget is the
   `recv-max` of the latest `get` (the `hello`'s before any), split among the
   streams by interest. On a simulated clock, over one second each time, with a
   `send-max` of 60000: a `get` with a 100000 budget writes about 60000 bytes
   (within one frame; the `send-max` is the smaller); a later `get` with 20000
   lowers the session to that; and, on another session, two streams at interest
   1000 and 500 on a 90000 budget share it two to one."
  []
  (let [[st root] (big-value)
        slow {"peak" 1000 "tau-ms" 100000000 "floor" 50}
        pace (fn [n from]
               (dotimes [_ 20] (step! n 50))
               (item-bytes n from (+ from 1000)))
        total #(reduce + 0 (vals %))
        n1 (rnet {:st st :hello (assoc-in responder/default-hello ["rates" "send-max"] 60000)})
        _ (send-to! n1 (get-bytes 1 root 100000 slow))
        capped (pace n1 0)
        _ (send-to! n1 (get-bytes 3 root 20000 slow))
        lowered (pace n1 1000)
        n2 (rnet {:st st})
        _ (send-to! n2 (get-bytes 1 root 90000 slow))
        _ (send-to! n2 (get-bytes 3 root 90000 (assoc slow "peak" 500)))
        split (pace n2 0)]
    (concat
     (bad "budget 100000, send-max 60000: within one frame of 60000 and not over" true
          (<= (- 60000 frame-bytes) (total capped) 60000))
     (bad "after a get with recv-max 20000, about 20000 (two frames of slack)" true
          (<= 3000 (total lowered) (+ 20000 (* 2 frame-bytes))))
     (bad "two streams: the 1000 one gets about two thirds of 90000" true
          (near? (get split 1 0) 60000 (+ 1 frame-bytes)))
     (bad "the 500 one gets about a third" true
          (near? (get split 3 0) 30000 (+ 1 frame-bytes))))))

(defn channel-never-buffers-over-high-mark-and-resumes-at-low
  "C17, on a fake channel that the peer drains at 20000 bytes a second while the
   responder could write far faster. No frame is written at 4 frames or more
   buffered; after the high mark is reached nothing is written until the buffer is
   down to 1 frame (hysteresis); the high mark is reached, and the whole value
   still arrives."
  []
  (let [[st root] (big-value)
        high (* 4 frame-bytes) low frame-bytes
        blocked? (atom false) violations (atom []) hit-high (atom false)
        n (rnet {:st st :channel? true
                 :on-write (fn [sid before size]
                             (when (>= before high)
                               (swap! violations conj [:written-at-high before]))
                             (when (and @blocked? (> before low))
                               (swap! violations conj [:resumed-above-low before]))
                             (when (<= before low) (reset! blocked? false))
                             (when (>= (+ before size) high)
                               (reset! blocked? true) (reset! hit-high true)))})
        _ (send-to! n (get-bytes 1 root 10000000))
        _ (dotimes [_ 150]
            (drain! n 1 2000)                    ; 20000 a second, 100 ms
            (step! n 100))
        all (reduce + 0 (vals (item-bytes n -1 100000000)))
        expected (reduce + 0 (map bin/byte-len
                                  ((cc/honest st) 1 root)))]
    (concat
     (bad "no frame written at the high mark, none resumed above the low mark" [] (take 3 @violations))
     (bad "the high mark was reached" true @hit-high)
     (bad "the channel carries the whole value in the end" true (> all 100000))
     (bad "the stream is over" #{} (responder/open-streams (:r n) "p")))))

(defn blocked-stream-takes-no-share-and-banks-one-frame
  "C18. Two streams at the same interest and 100000 a second. (1) One is blocked
   (its channel holds 5 frames) for the whole second: it writes nothing, and the
   other writes at the whole rate, not half. (2) A stream blocked for 2 s, then
   freed as a second stream starts: it banked at most one frame (it writes no more
   than 2 frames in the first tick) and it kept decaying (tau 2 s): over the next
   second it gets about 27% of the rate, not the half it would at the same interest."
  []
  (let [[st root] (big-value)
        slow {"peak" 1000 "tau-ms" 100000000 "floor" 50}
        n1 (rnet {:st st :channel? true})
        _ (send-to! n1 (get-bytes 1 root 100000 slow))
        _ (send-to! n1 (get-bytes 3 root 100000 slow))
        _ (set-buffered! n1 1 (* 5 frame-bytes))
        _ (dotimes [_ 20] (set-buffered! n1 3 0) (step! n1 50))
        phase1 (item-bytes n1 0 1000)
        ;; (2)
        decay {"peak" 1000 "tau-ms" 2000 "floor" 50}
        n2 (rnet {:st st :channel? true})
        _ (send-to! n2 (get-bytes 1 root 100000 decay))
        _ (set-buffered! n2 1 (* 5 frame-bytes))
        _ (dotimes [_ 20] (step! n2 100))          ; 2 s blocked
        blocked-bytes (get (item-bytes n2 0 2000) 1 0)
        _ (send-to! n2 (get-bytes 3 root 100000 decay))
        _ (set-buffered! n2 1 0)
        _ (step! n2 10)
        first-tick (get (item-bytes n2 2000 2010) 1 0)
        _ (dotimes [_ 10] (set-buffered! n2 1 0) (set-buffered! n2 3 0) (step! n2 100))
        phase2 (item-bytes n2 2010 3010)
        a (get phase2 1 0) b (get phase2 3 0)]
    (concat
     (bad "blocked stream writes nothing" 0 (get phase1 1 0))
     (bad "the other writes at the whole rate" true
          (<= (- 100000 frame-bytes) (get phase1 3 0) 100000))
     (bad "blocked for 2 s, nothing written" 0 blocked-bytes)
     (bad "freed: at most two frames in the first tick (one banked)" true
          (<= first-tick (* 2 frame-bytes)))
     (bad "it has decayed meanwhile: about 27% of the next second, not 50%" true
          (< 8000 a 40000))
     (bad "the new stream gets the rest" true (> b (* 1.5 a))))))

(defn get-over-the-bucket-is-rate-limited-and-session-lives
  "C40: the responder's bucket per session. Its hello says req-rate 20 and a
   burst of 5 (the default burst of 40 only makes the check slower). Of 7 `get`s
   at one moment (for a hash it does not hold: each costs a token, answered or
   refused) 5 are answered `unknown-hash` and 2 `rate-limited` with a
   `retry-ms`; the session lives; after 50 ms one token is back; a long time
   later the bucket holds its burst, not more."
  []
  (let [st (store/mem-store)
        missing (v/hash (v/string (store/mem-store) "not here"))
        n (rnet {:st st :hello (assoc-in responder/default-hello ["rates" "req-burst"] 5)})
        ask! (fn [sid] (send-to! n (get-bytes sid missing 1000)))
        codes #(frequencies (map (fn [m] (get-in m [:value "code"])) (errors-of n)))
        _ (doseq [i (range 7)] (ask! (inc (* 2 i))))
        c1 (codes)
        limited (filterv #(= "rate-limited" (get-in % [:value "code"])) (errors-of n))
        retry (get-in (first limited) [:value "retry-ms"])
        _ (step! n 50)
        _ (ask! 91)                              ; the token that is back
        _ (ask! 93)                              ; not another
        after-50 (codes)
        _ (step! n 60000)
        _ (doseq [i (range 7)] (ask! (+ 101 (* 2 i))))
        after-long (codes)]
    (concat
     (bad "7 gets at once: 5 unknown-hash, 2 rate-limited" {"unknown-hash" 5 "rate-limited" 2} c1)
     (bad "rate-limited names a retry-ms of about 50" true (and retry (<= 1 retry 100)))
     (bad "the limited gets are on their own streams" [11 13] (mapv :stream limited))
     (bad "50 ms later one more is answered, one limited" {"unknown-hash" 6 "rate-limited" 3} after-50)
     (bad "a minute later the burst, 5 more, is there and no more" {"unknown-hash" 11 "rate-limited" 5} after-long)
     (bad "the session is open, never closed" [:open []]
          [(responder/session-status (:r n) "p") @(:closed n)]))))

(defn responder-never-fetches-on-behalf
  "C36: a `get` for a hash this store does not hold is `error unknown-hash` on
   its stream and nothing else: no item is written. (The responder has no
   other store to ask: this check is about its answer.)"
  []
  (let [[st _] (big-value)
        other (store/mem-store)
        elsewhere (v/hash (cc/map-of other 5))
        n (rnet {:st st})
        _ (send-to! n (get-bytes 1 elsewhere 1000000))
        _ (dotimes [_ 10] (step! n 100))]
    (concat
     (bad "unknown-hash on stream 1, not fatal" [["unknown-hash" 1 false]]
          (mapv (fn [m] [(get-in m [:value "code"]) (:stream m) (get-in m [:value "fatal"])])
                (errors-of n)))
     (bad "no item frame was written" {} (item-bytes n -1 100000))
     (bad "the stream is not open" #{} (responder/open-streams (:r n) "p"))
     (bad "the store holds exactly what it did" false
          (boolean (store/await (store/s-get st elsewhere)))))))

(defn- stream-hashes
  "Hex hashes of the node items each stream has delivered, in order."
  [{:keys [sent]}]
  (let [readers (reduce (fn [readers [_ _ bs]]
                          (let [fr (frame/decode-frame bs)]
                            (if-not (= :item-stream (:type fr))
                              readers
                              (let [sid (:stream fr)
                                    [r items] (frame/feed (get readers sid (frame/reader sid)) fr)
                                    hashes (mapv (fn [item]
                                                   (store/hash->hex
                                                    (:hash (first (bin/decode-item-stream item)))))
                                                 items)]
                                (assoc readers sid
                                       (assoc r :hashes (into (:hashes r []) hashes)))))))
                        {}
                        @sent)]
    (into {} (map (fn [[sid r]] [sid (:hashes r)])) readers)))

(defn same-hash-twice-is-two-independent-streams
  "N1, C38. Two `get`s for one hash are two streams, keyed by stream id.
   Opening the second does not cancel the first, and neither stream is an
   error. The first stream, planned with an empty record, delivers every
   page. The second sends the root and the pages not yet recorded (C19, C20)."
  []
  (let [st (store/mem-store)
        root (v/hash (v/string st (apply str (repeat 20000 "q"))))
        order (mapv store/hash->hex (reveal/page-order st root))
        slow {"peak" 1000 "tau-ms" 100000000 "floor" 0}
        n (rnet {:st st})
        _ (send-to! n (get-bytes 1 root 4000 slow))
        ;; The first frame is a full max-frame, so a few tenths of a
        ;; second of credit are not enough to write it. Four simulated
        ;; seconds covers that frame and leaves the rest of the string.
        started (loop [i 0]
                  (step! n 100)
                  (let [open (responder/open-streams (:r n) "p")
                        nsent (count (get (stream-hashes n) 1))]
                    (cond
                      (and (contains? open 1) (pos? nsent)) true
                      (not (contains? open 1)) false
                      (< i 40) (recur (inc i))
                      :else false)))
        _ (send-to! n (get-bytes 3 root 4000 slow))
        both (responder/open-streams (:r n) "p")
        _ (loop [i 0]
            (when (and (seq (responder/open-streams (:r n) "p")) (< i 120))
              (step! n 200)
              (recur (inc i))))
        done (stream-hashes n)
        errs (errors-of n)]
    (concat
     (bad "the first stream is sending when the second get arrives" true started)
     (bad "both streams are open together" #{1 3} both)
     (bad "stream 1 delivers every page" order (get done 1))
     ;; The sent-hash record skips pages stream 1 has already written (C19).
     ;; Stream 3 is still its own stream: it sends the root (C20) and whatever
     ;; stream 1 had not recorded when this get was planned.
     (bad "stream 3 sends the root" (first order) (first (get done 3)))
     (bad "together the two streams cover the value" (set order)
          (set (concat (get done 1) (get done 3))))
     (bad "neither stream is an error" [] (mapv :stream errs))
     (bad "both streams end" #{} (responder/open-streams (:r n) "p")))))

(defn no-message-changes-the-store-except-verified-items
  "C34: no message changes a responder's store, whatever it says. A `get` for
   a held hash and for a missing one, `ack`, `cancel`, an unknown
   type, a second `hello`, an `item-stream` and a garbage frame leave its keys as
   they were. (On the client side, only a verified item reaches the store: see
   `nothing-unverified-reaches-the-store`.)"
  []
  (let [[st root] (big-value)
        fingerprint #(vector (cc/store-count st) (gc/mark-reachable st root))
        before (fingerprint)
        n (rnet {:st st})
        h (msg/hash-ref (store/hash->hex root))
        item (stray-frame 1)
        _ (send-to! n (get-bytes 1 root 100000))
        _ (send-to! n (get-bytes 3 (v/hash (v/string (store/mem-store) "none")) 100000))
        _ (send-to! n (msg/encode-message {:type :ack :stream 0 :value {"hashes" [h]}}))
        _ (send-to! n (msg/encode-message {:type :cancel :stream 1 :value {}}))
        _ (dotimes [_ 5] (step! n 100))
        mid (fingerprint)
        _ (send-to! n item)                       ; a protocol violation: ends it
        after (fingerprint)]
    (concat
     (bad "unchanged after gets, ack, cancel and serving" before mid)
     (bad "unchanged after an item-stream (which ends the session)" before after)
     (bad "that ended the session" :ended (responder/session-status (:r n) "p")))))

(defn- hex-ref [h] (msg/hash-ref (store/hash->hex h)))

(defn- read-items
  "Hashes of the items `n` wrote on stream `sid`, in order."
  [n sid]
  (let [frames (into []
                     (keep (fn [[_ _ bs]]
                             (let [fr (frame/decode-frame bs)]
                               (when (and (= :item-stream (:type fr)) (= sid (:stream fr)))
                                 fr))))
                     @(:sent n))]
    (loop [frames frames st (frame/reader sid) out []]
      (if (empty? frames)
        out
        (let [[st' items] (frame/feed st (first frames))]
          (recur (rest frames) st'
                 (into out (mapcat (fn [bs] (map :hash (bin/decode-item-stream bs))) items))))))))

(defn- ack! [n hashes]
  (send-to! n (msg/encode-message
               {:type :ack :stream 0
                :value {"hashes" (mapv hex-ref hashes)}})))

(defn record-is-capped-oldest-first
  "C21: the sent-hash record holds 16 384 hashes and drops the oldest. There
   is no wire message that removes one. `ack` of a hash the record holds
   moves it to the newest end; an unknown hash is ignored (C23)."
  []
  (let [cap responder/sent-cap
        keys (mapv #(str "k" %) (range (+ cap 2)))
        full (responder/record-sent (responder/record-empty) (take cap keys))
        overflow (responder/record-sent full (drop cap keys))
        oldest (first keys)
        acked (responder/record-ack overflow [(nth keys 2) "not-recorded"])]
    (concat
     (bad "the cap is 16 384" 16384 cap)
     (bad "a full record has the cap" cap (count (:set full)))
     (bad "the oldest is still there at the cap" true (contains? (:set full) oldest))
     (bad "one past the cap drops the oldest" false (contains? (:set overflow) oldest))
     (bad "the newest past the cap is kept" true (contains? (:set overflow) (last keys)))
     (bad "still at the cap" cap (count (:set overflow)))
     (bad "ack of an unknown hash does not grow the record" cap (count (:set acked)))
     (bad "ack keeps a recorded hash" true (contains? (:set acked) (nth keys 2)))
     (bad "ack moves that hash to the newest end" (nth keys 2) (last (:order acked))))))

(defn failed-stream-retry-does-not-resend-confirmed-pages
  "A get that stops after a few pages, once those pages are acked, is retried
   as a new get. The retry sends the root again (C20) and does not send the
   pages the client already confirmed."
  []
  (let [st (store/mem-store)
        root (v/hash (v/string st (apply str (repeat 30000 "q"))))
        order (reveal/page-order st root)
        n (rnet {:st st})
        _ (send-to! n (get-bytes 1 root 40000))
        _ (step! n 300)
        sent1 (read-items n 1)
        confirmed (into [] (remove #(= % root)) sent1)
        _ (ack! n sent1)
        _ (send-to! n (get-bytes 3 root 10000000))
        _ (step! n 2000)
        sent2 (read-items n 3)]
    (concat
     (bad "the first stream confirmed a page that is not the root" true (boolean (seq confirmed)))
     (bad "the value has pages the first stream did not send" true
          (< (count sent1) (count order)))
     (bad "the retry sends the root" true (boolean (some #(= % root) sent2)))
     (bad "the retry does not resend a confirmed page" []
          (into [] (filter (set confirmed)) sent2))
     (bad "the retry sends a page the first stream had not reached" true
          (boolean (some (complement (set sent1)) sent2)))
     (bad "the session stays open" :open (responder/session-status (:r n) "p")))))

(defn root-of-a-get-is-sent-even-if-recorded
  "C20: after a value has been sent and acked, asking again still sends the
   root, and does not send the other pages again."
  []
  (let [st (store/mem-store)
        root (v/hash (v/vector st (v/i64 st 1) (v/i64 st 2) (v/i64 st 3)))
        order (reveal/page-order st root)
        n (rnet {:st st})
        _ (send-to! n (get-bytes 1 root 10000000))
        _ (step! n 200)
        sent1 (read-items n 1)
        _ (ack! n sent1)
        _ (send-to! n (get-bytes 3 root 10000000))
        _ (step! n 200)
        sent2 (read-items n 3)]
    (concat
     (bad "the first get sent every page" (count order) (count sent1))
     (bad "the second get sends the root" [root] (filterv #(= % root) sent2))
     (bad "and only the root" [root] sent2))))

(defn empty-and-wrong-record-still-complete-every-request
  "C19: an empty record sends the whole value. A record that names a child
   skips that child and still sends the root; asking for the child itself
   sends it, because that ask's root is the child."
  []
  (let [st (store/mem-store)
        root (v/hash (v/vector st (v/i64 st 1) (v/string st "child")))
        order (reveal/page-order st root)
        child (second order)
        whole (fn []
                (let [n (rnet {:st st})]
                  (send-to! n (get-bytes 1 root 10000000))
                  (step! n 200)
                  (read-items n 1)))
        wrong (let [n (rnet {:st st})
                    rec (responder/record-sent (responder/record-empty)
                                               [(store/hash->hex child) "not-a-page"])]
                (swap! (:state (:r n)) assoc-in [:sessions "p" :record] rec)
                (send-to! n (get-bytes 1 root 10000000))
                (step! n 200)
                (read-items n 1))
        asked (let [n (rnet {:st st})]
                (send-to! n (get-bytes 1 child 10000000))
                (step! n 200)
                (read-items n 1))]
    (concat
     (bad "an empty record sends every page" (set order) (set (whole)))
     (bad "a wrong record still sends the root" true (boolean (some #(= % root) wrong)))
     (bad "a wrong record skips the child it names" false (boolean (some #(= % child) wrong)))
     (bad "asking for that child sends it" true (boolean (some #(= % child) asked))))))

(defn a-get-of-a-recorded-hash-is-sent-and-verified
  "C20 and C22: a hash already in the sent record is still delivered when a
   later `get` names it. That is how a client recovers a page it evicted or
   never stored. The item verifies from its own bytes."
  []
  (let [src (store/mem-store)
        root (v/hash (v/vector src (v/i64 src 7) (v/i64 src 8)))
        order (reveal/page-order src root)
        child (last order)
        n (rnet {:st src})
        _ (send-to! n (get-bytes 1 root 10000000))
        _ (step! n 200)
        sent-first (read-items n 1)
        _ (send-to! n (get-bytes 3 child 10000000))
        _ (step! n 200)
        sent (read-items n 3)
        frames (into []
                     (keep (fn [[_ _ bs]]
                             (let [fr (frame/decode-frame bs)]
                               (when (and (= :item-stream (:type fr)) (= 3 (:stream fr)))
                                 fr))))
                     @(:sent n))
        items (loop [frames frames st (frame/reader 3) out []]
                (if (empty? frames)
                  out
                  (let [[st' bs] (frame/feed st (first frames))]
                    (recur (rest frames) st'
                           (into out (mapcat bin/decode-item-stream bs))))))
        child-item (first (filter #(= child (:hash %)) items))
        checked (when child-item (verify/verify-item! (store/mem-store) child-item))]
    (concat
     (bad "the first get recorded the child" true (boolean (some #(= % child) sent-first)))
     (bad "a get of that hash sends it again" true (boolean (some #(= % child) sent)))
     (bad "the resent page verifies from its own bytes" true (set? checked)))))

(defn ack-is-optional-and-unknown-hashes-are-ignored
  "C23: a stream finishes with no ack. An ack that names a hash the
   responder has never sent does not end the session and does not change
   what the next get sends."
  []
  (let [st (store/mem-store)
        root (v/hash (v/i64 st 4))
        other (v/hash (v/i64 (store/mem-store) 5))
        n (rnet {:st st})
        _ (ack! n [other])
        _ (send-to! n (get-bytes 1 root 10000000))
        _ (step! n 100)
        sent (read-items n 1)]
    (concat
     (bad "no ack was required: the page was sent" [root] sent)
     (bad "the stream is finished" #{} (responder/open-streams (:r n) "p"))
     (bad "an unknown ack did not end the session" :open
          (responder/session-status (:r n) "p"))
     (bad "no error was sent" [] (errors-of n)))))

(defn renewal-is-correct-with-no-record-and-may-reveal-more
  "C26: a second get is a new stream. With nothing recorded it sends the
   value again. After the first pages are recorded, the renewal sends pages
   the first stream had not reached."
  []
  (let [st (store/mem-store)
        root (v/hash (v/string st (apply str (repeat 30000 "r"))))
        order (reveal/page-order st root)
        fresh (rnet {:st st})
        _ (send-to! fresh (get-bytes 1 root 10000000))
        _ (step! fresh 500)
        all (read-items fresh 1)
        paced (rnet {:st st})
        _ (send-to! paced (get-bytes 1 root 40000))
        _ (step! paced 300)
        first (read-items paced 1)
        _ (send-to! paced (get-bytes 3 root 10000000))
        _ (step! paced 2000)
        more (read-items paced 3)]
    (concat
     (bad "no record: the get sends every page" (set order) (set all))
     (bad "the paced get did not finish the value" true (< (count first) (count order)))
     (bad "the renewal sends a page the first stream had not" true
          (boolean (some (complement (set first)) more)))
     (bad "the renewal still sends the root" true (boolean (some #(= % root) more))))))

(defn- cancel-bytes
  [sid value]
  (msg/encode-message {:type :cancel :stream sid :value value}))

(defn- fed-stream
  "Reader state after every item frame on `sid`, or `{:error ...}`."
  [n sid]
  (try
    (loop [frames (item-frames n sid) st (frame/reader sid)]
      (if (empty? frames)
        st
        (let [[st' _] (frame/feed st (first frames))]
          (recur (rest frames) st'))))
    (catch #?(:clj Throwable :cljs :default) e
      {:error (ex-message e)})))

(defn- cancel-while-the-read-is-pending
  "A `get` whose store read has not finished is an open stream. `cancel` then
   closes it, and settling the read does not start it."
  []
  (let [inner (store/mem-store)
        root (v/hash (v/string inner "held"))
        q (atom [])
        st (h/late-store inner q)
        clock (atom 0)
        sent (atom [])
        closed (atom [])
        r (responder/responder {:store st
                                :now (fn [] @clock)
                                :send! (fn [_ bs] (swap! sent conj bs))
                                :close! (fn [p] (swap! closed conj p))})
        hello (hello-bytes (hello-with {}))
        _ (responder/open-session! r "p")
        _ (responder/on-frame! r "p" hello)
        step (responder/on-frame! r "p" (get-bytes 1 root 100000))
        pending? (chain/pending? step)
        _ (responder/on-frame! r "p" (cancel-bytes 1 {}))
        _ (h/settle! q)
        _ (swap! clock + 50)
        _ (responder/tick! r)
        kinds (mapv (fn [bs] (:type (frame/decode-frame bs))) @sent)
        finished (try (chain/result step) :ok
                      (catch #?(:clj Throwable :cljs :default) _ :err))]
    (concat
     (bad "the read was still pending when cancel arrived" true pending?)
     (bad "settling it finishes the get" :ok finished)
     (bad "the stream never starts" #{} (responder/open-streams r "p"))
     (bad "nothing is written but the hello" [:hello] (vec (distinct kinds)))
     (bad "the session stays open and is not closed" [:open []]
          [(responder/session-status r "p") @closed]))))

(defn stream-ends-partial-when-interest-falls-below-floor
  "C05. Clamped interest reaches the floor at `tau · ln(peak / floor)`, the
   same instant the lifetime already dropped the stream. The tick that reaches
   it ends the stream: FIN, and PARTIAL when pages remain. A reveal that
   finishes first is FIN without PARTIAL. A floor above the peak ends on the
   first tick, with no pages. The other stream of the session stays open."
  []
  (let [[st root] (big-value)
        order (vec (reveal/page-order st root))
        short {"peak" 1000 "tau-ms" 1000 "floor" 200}
        long {"peak" 1000 "tau-ms" 60000 "floor" 10}
        n (rnet {:st st})
        _ (send-to! n (get-bytes 1 root 80000 short))
        _ (send-to! n (get-bytes 3 root 80000 long))
        ;; t = 1600 ms. The floor is tau · ln(1000/200) ≈ 1609 ms.
        _ (dotimes [_ 16] (step! n 100))
        open-mid (responder/open-streams (:r n) "p")
        mid-frames (item-frames n 1)
        mid-items (read-items n 1)
        _ (step! n 100)
        open-end (responder/open-streams (:r n) "p")
        end-frames (item-frames n 1)
        end-items (read-items n 1)
        fed (fed-stream n 1)
        _ (dotimes [_ 5] (step! n 100))
        later (item-frames n 1)
        ;; a floor above the peak pauses before any page
        paused (let [p (rnet {:st st})]
                 (send-to! p (get-bytes 1 root 80000
                                        {"peak" 100 "tau-ms" 1000 "floor" 1000}))
                 (let [open (responder/open-streams (:r p) "p")]
                   (step! p 1)
                   {:open-before open
                    :frames (item-frames p 1)
                    :items (read-items p 1)
                    :open (responder/open-streams (:r p) "p")
                    :status (responder/session-status (:r p) "p")}))
        ;; the reveal finishes while interest is still above the floor
        done (let [small (store/mem-store)
                   sroot (v/hash (v/vector small (v/i64 small 1) (v/string small "leaf")))
                   sorder (vec (reveal/page-order small sroot))
                   p (rnet {:st small})]
               (send-to! p (get-bytes 1 sroot 10000000))
               (step! p 200)
               {:order sorder
                :items (read-items p 1)
                :frames (item-frames p 1)
                :open (responder/open-streams (:r p) "p")})]
    (concat
     (bad "both streams are open while interest is still above the floor" #{1 3} open-mid)
     (bad "a page went out before the floor" true (pos? (count mid-items)))
     (bad "the reveal was not finished yet" true (< (count mid-items) (count order)))
     (bad "no frame had ended the stream yet" []
          (into [] (keep #(when (:fin? %) :fin) mid-frames)))
     (bad "the pages sent are a prefix of the reveal" (vec (take (count end-items) order))
          end-items)
     (bad "the floor sends no further pages" (count mid-items) (count end-items))
     (bad "the stream has ended" false (contains? open-end 1))
     (bad "the last frame is FIN and PARTIAL" [true true]
          (let [fr (peek end-frames)] [(:fin? fr) (:partial? fr)]))
     (bad "only that last frame ends the stream" []
          (into [] (keep-indexed (fn [i fr]
                                   (when (and (:fin? fr) (not= i (dec (count end-frames)))) i))
                                 end-frames)))
     (bad "the receiver can read the partial stream" [true true nil]
          [(:fin? fed) (:partial? fed) (:error fed)])
     (bad "nothing more is written for it" (count end-frames) (count later))
     (bad "the other stream stays open, and the session too" [true :open]
          [(contains? open-end 3)
           (responder/session-status (:r n) "p")])
     (bad "a floor above the peak had the stream open" #{1} (:open-before paused))
     (bad "and the first tick ends it with an empty FIN PARTIAL" [[] [true true] #{} :open]
          [(:items paused)
           (let [fr (peek (:frames paused))] [(:fin? fr) (:partial? fr)])
           (:open paused)
           (:status paused)])
     (bad "a reveal that finishes first is every page, FIN, not PARTIAL" [(:order done) [true false] #{}]
          [(:items done)
           (let [fr (peek (:frames done))] [(:fin? fr) (:partial? fr)])
           (:open done)]))))

(defn- responder-stream
  [n sid]
  (get-in @(:state (:r n)) [:sessions "p" :streams sid]))

(defn- inside-an-item?
  "The last frame sent did not finish an item, and a later frame does."
  [st]
  (let [n (:next st)]
    (and (some? st)
         (pos? n)
         (not (seq (get (:ends st) (dec n))))
         (some seq (drop n (:ends st))))))

(defn- no-fin [frames]
  (into [] (keep #(when (:fin? %) :fin) frames)))

(defn- fin-partial? [frames]
  (let [fr (peek frames)]
    [(:fin? fr) (:partial? fr)]))

(defn floor-sends-fin-partial-when-blocked-or-credit-runs-out
  "C05. The floor always ends with FIN+PARTIAL. A channel at the high mark
   writes that frame once it drains, and does not write while it is blocked.
   A leaf cut across frames is finished even when banked credit does not
   cover the rest, and that end is FIN+PARTIAL too.

   One 8000-character string. At 8192 it is two frames, so the channel can
   be blocked after the first. At 4096 a leaf is cut. The floor is about
   9 s out; both stops happen sooner."
  []
  (let [leaf (store/mem-store)
        root (v/hash (v/string leaf (apply str (repeat 8000 "z"))))
        order (vec (reveal/page-order leaf root))
        interest {"peak" 1000 "tau-ms" 3000 "floor" 50}
        ;; blocked after the first frame, held there across the floor
        blocked (rnet {:st leaf :channel? true})
        _ (send-to! blocked (get-bytes 1 root 15000 interest))
        _ (loop [i 0]
            (when (and (< i 40) (not= 1 (count (item-frames blocked 1))))
              (drain! blocked 1 1000000)
              (step! blocked 100)
              (recur (inc i))))
        blocked-before (item-frames blocked 1)
        pages-before (read-items blocked 1)
        _ (set-buffered! blocked 1 (* 5 frame-bytes))
        _ (step! blocked 12000)
        held (item-frames blocked 1)
        held-open? (contains? (responder/open-streams (:r blocked) "p") 1)
        _ (set-buffered! blocked 1 0)
        _ (step! blocked 100)
        blocked-after (item-frames blocked 1)
        ;; 4096 cuts a leaf. Stop while credit cannot cover the rest of it.
        short (rnet {:st leaf :max-frame 4096})
        _ (send-to! short (get-bytes 1 root 5000 interest))
        mid (loop [i 0]
              (if (>= i 80)
                nil
                (do (step! short 50)
                    (let [s (responder-stream short 1)]
                      (cond
                        (nil? s) :closed
                        (inside-an-item? s) s
                        :else (recur (inc i)))))))
        need (when (map? mid) (bin/byte-len (nth (:frames mid) (:next mid))))
        short-before (item-frames short 1)
        short-pages-before (read-items short 1)
        _ (step! short 12000)
        short-after (item-frames short 1)
        short-pages (read-items short 1)
        fed (fed-stream short 1)]
    (concat
     (bad "the blocked stream had sent a page and not ended" true
          (and (pos? (count blocked-before)) (empty? (no-fin blocked-before))))
     (bad "at the high mark the floor writes nothing yet" (count blocked-before) (count held))
     (bad "and the stream waits for the drain" true held-open?)
     (bad "once the channel drains, one FIN PARTIAL frame arrives" true
          (and (= (count blocked-after) (inc (count blocked-before)))
               (= [true true] (fin-partial? blocked-after))
               (empty? (no-fin (pop blocked-after)))))
     (bad "that frame adds no page" pages-before (read-items blocked 1))
     (bad "the blocked stream is then gone, the session lives" [false :open]
          [(contains? (responder/open-streams (:r blocked) "p") 1)
           (responder/session-status (:r blocked) "p")])
     (bad "credit ran out inside an item" true
          (and (map? mid) (some? need) (< (:credit mid) need)))
     (bad "that stream had sent a page and not ended" true
          (and (pos? (count short-before)) (empty? (no-fin short-before))))
     (bad "short credit still finishes with FIN PARTIAL" [true true]
          (fin-partial? short-after))
     (bad "only that last frame ends it" []
          (no-fin (pop short-after)))
     (bad "the cut leaf is in the prefix, and the reveal is not finished" true
          (and (= short-pages (vec (take (count short-pages) order)))
               (> (count short-pages) (count short-pages-before))
               (< (count short-pages) (count order))))
     (bad "the receiver can read that partial stream" [true true nil]
          [(:fin? fed) (:partial? fed) (:error fed)])
     (bad "the short stream is gone, the session lives" [false :open]
          [(contains? (responder/open-streams (:r short) "p") 1)
           (responder/session-status (:r short) "p")]))))

(defn cancel-stops-and-frees-share-and-is-never-required
  "C07. `cancel` stops the stream it names and frees its share: the other
   stream, which was splitting the rate, then writes about twice as much. A
   `cancel` for a closed stream, or for an id that was never opened, is
   ignored. A stream that is never cancelled still completes. A `cancel` that
   arrives while the `get` is still being read does not let that stream start."
  []
  (let [[st root] (big-value)
        steady {"peak" 1000 "tau-ms" 60000 "floor" 10}
        n (rnet {:st st})
        _ (send-to! n (get-bytes 1 root 100000 steady))
        _ (send-to! n (get-bytes 3 root 100000 steady))
        _ (dotimes [_ 10] (step! n 50))
        before (item-bytes n 0 500)
        frames-before (count (item-frames n 1))
        _ (send-to! n (cancel-bytes 1 {"reason" "navigated away"}))
        closed-at-once (responder/open-streams (:r n) "p")
        _ (dotimes [_ 10] (step! n 50))
        after (item-bytes n 500 1000)
        _ (send-to! n (cancel-bytes 1 {}))
        _ (send-to! n (cancel-bytes 99 {}))
        _ (send-to! n (cancel-bytes 2 {}))
        quiet (errors-of n)
        missing (v/hash (v/string (store/mem-store) "not held"))
        _ (send-to! n (get-bytes 5 missing 100000))
        ;; no cancel: a small value runs to FIN
        whole (let [small (store/mem-store)
                    sroot (v/hash (v/vector small (v/i64 small 1) (v/i64 small 2)))
                    sorder (vec (reveal/page-order small sroot))
                    p (rnet {:st small})]
                (send-to! p (get-bytes 1 sroot 10000000))
                (step! p 200)
                {:order sorder
                 :items (read-items p 1)
                 :frames (item-frames p 1)
                 :open (responder/open-streams (:r p) "p")
                 :status (responder/session-status (:r p) "p")})
        pending (cancel-while-the-read-is-pending)]
    (concat
     (bad "both streams were sending" true
          (and (pos? (get before 1 0)) (pos? (get before 3 0))))
     (bad "cancel closes that stream at once" #{3} closed-at-once)
     (bad "and writes nothing more for it" frames-before (count (item-frames n 1)))
     (bad "the other stream writes about twice what it wrote while sharing" true
          (near? (get after 3 0) (* 2 (get before 3 0)) (* 2 frame-bytes)))
     (bad "a cancel for a closed or unknown stream is ignored" [] quiet)
     (bad "the session still takes a new get" [["unknown-hash" 5 false]]
          (mapv (fn [m] [(get-in m [:value "code"]) (:stream m) (get-in m [:value "fatal"])])
                (errors-of n)))
     (bad "the session stays open" :open (responder/session-status (:r n) "p"))
     (bad "with no cancel, the stream still finishes" [(:order whole) [true false] #{} :open]
          [(:items whole)
           (let [fr (peek (:frames whole))] [(:fin? fr) (:partial? fr)])
           (:open whole)
           (:status whole)])
     pending)))

(defn stream-state-is-forgotten-by-its-lifetime-bound
  "C10: a stream that has not finished is dropped once `tau · ln(peak / floor)`
   has passed (the clamps make that at most about 276 s). The session lives,
   and a new get is a new stream."
  []
  (let [st (store/mem-store)
        root (v/hash (v/string st (apply str (repeat 80000 "z"))))
        n (rnet {:st st})
        _ (send-to! n (get-bytes 1 root 1000))
        _ (step! n 20)
        open-before (responder/open-streams (:r n) "p")
        _ (step! n 40000)
        _ (send-to! n (get-bytes 3 root 10000000))
        _ (step! n 50)]
    (concat
     (bad "the slow stream was open" #{1} open-before)
     (bad "past the lifetime that stream is gone" false
          (contains? (responder/open-streams (:r n) "p") 1))
     (bad "the session lives" :open (responder/session-status (:r n) "p"))
     (bad "a new get is accepted" true
          (boolean (or (contains? (responder/open-streams (:r n) "p") 3)
                       (seq (read-items n 3))))))))

(def checks
  "Test name (spec §13) -> check. Run order is this order."
  (array-map
   "shared-rmax-splits-by-interest-across-peers" shared-rmax-splits-by-interest-across-peers
   "capped-peer-share-is-redistributed" capped-peer-share-is-redistributed
   "new-peer-starts-at-its-fair-share" new-peer-starts-at-its-fair-share
   "requester-bucket-never-overspends" requester-bucket-never-overspends
   "item-on-a-stream-never-opened-ends-the-session" item-on-a-stream-never-opened-ends-the-session
   "item-on-a-closed-stream-is-ignored" item-on-a-closed-stream-is-ignored
   "protocol-violation-ends-session-request-error-ends-stream" protocol-violation-ends-session-request-error-ends-stream
   "have-does-not-recompute-what-a-session-verified" have-does-not-recompute-what-a-session-verified
   "responder-paces-to-session-budget" responder-paces-to-session-budget
   "channel-never-buffers-over-high-mark-and-resumes-at-low" channel-never-buffers-over-high-mark-and-resumes-at-low
   "blocked-stream-takes-no-share-and-banks-one-frame" blocked-stream-takes-no-share-and-banks-one-frame
   "get-over-the-bucket-is-rate-limited-and-session-lives" get-over-the-bucket-is-rate-limited-and-session-lives
   "same-hash-twice-is-two-independent-streams" same-hash-twice-is-two-independent-streams
   "responder-never-fetches-on-behalf" responder-never-fetches-on-behalf
   "no-message-changes-the-store-except-verified-items" no-message-changes-the-store-except-verified-items
   "record-is-capped-oldest-first" record-is-capped-oldest-first
   "failed-stream-retry-does-not-resend-confirmed-pages" failed-stream-retry-does-not-resend-confirmed-pages
   "root-of-a-get-is-sent-even-if-recorded" root-of-a-get-is-sent-even-if-recorded
   "empty-and-wrong-record-still-complete-every-request" empty-and-wrong-record-still-complete-every-request
   "a-get-of-a-recorded-hash-is-sent-and-verified" a-get-of-a-recorded-hash-is-sent-and-verified
   "ack-is-optional-and-unknown-hashes-are-ignored" ack-is-optional-and-unknown-hashes-are-ignored
   "renewal-is-correct-with-no-record-and-may-reveal-more" renewal-is-correct-with-no-record-and-may-reveal-more
   "stream-state-is-forgotten-by-its-lifetime-bound" stream-state-is-forgotten-by-its-lifetime-bound
   "stream-ends-partial-when-interest-falls-below-floor" stream-ends-partial-when-interest-falls-below-floor
   "floor-sends-fin-partial-when-blocked-or-credit-runs-out" floor-sends-fin-partial-when-blocked-or-credit-runs-out
   "cancel-stops-and-frees-share-and-is-never-required" cancel-stops-and-frees-share-and-is-never-required))

(def max-ms
  "No check may take longer than this (milliseconds)."
  5000)

(defn- now-ms [] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))

(defn run-all
  "Run every check. Returns [{:name :ms :problems}]. A check that throws is a
   problem, and one over `max-ms` is a problem."
  []
  (mapv (fn [[name f]]
          (let [t0 (now-ms)
                problems (try (vec (f))
                              (catch #?(:clj Throwable :cljs :default) e
                                [(str "threw " (ex-message e) " " (pr-str (ex-data e)))]))
                ms (- (now-ms) t0)]
            {:name name :ms ms
             :problems (cond-> problems
                         (> ms max-ms) (conj (str "took " ms " ms, over " max-ms)))}))
        checks))
