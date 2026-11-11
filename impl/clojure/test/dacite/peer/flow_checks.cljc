(ns dacite.peer.flow-checks
  "M1.2 slice 2, portable checks (JVM: flow_test.clj, nbb: peer_cljs_test).
   Spec: docs/spec/peer-v1.md §12.2 (C01, C02, C03, C45), §12.3 (C05, C06, C07,
   C08, C09, C10, C11, C12), §12.4 (C16), §12.5 (C17, C18), §12.7 (C24, C25), §12.8 (C32),
   §12.9 (C34, C35, C36), §12.11 (C40, C41, C42); test map in §13.

   Same shape as client_checks.cljc: each check returns a vector of problem strings,
   empty when it holds, and `checks` maps the §13 test name to it. Every clock here
   is simulated (an atom of milliseconds that the check advances), so a check that
   covers ten simulated seconds still takes milliseconds of real time.

   Two harnesses: `cnet` (a client, peers that say `hello` and then do what the check
   writes) and `rnet` (a responder, one peer, a fake channel whose buffer the check
   fills and drains)."
  (:require [dacite.host :as host]
            [dacite.peer.client :as client]
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
            [dacite.value.types :as types]
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

(def ^:private hello-bytes
  "A `hello` frame (bytes) for hello `h`. Encoded once per hello: every
   responder harness sends one, and the encode is about 40 ms on nbb."
  (memoize (fn [h] (msg/encode-message {:type :hello :stream 0 :value h}))))

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

(def ^:private mid
  "A store with a value of 50 entries, about 48 KB, and its root: more than
   any one stream of the pacing checks writes (C03, C18). A `get` reads its
   whole value, and on nbb that is most of a pacing check's time. Built once."
  (delay (let [st (store/mem-store)] [st (v/hash (cc/map-of st 50))])))

(defn- mid-value [] @mid)

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
                    (let [n (cnet {:peers [["a" {}]] :hello? (not= what "before hello")})]
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
        ;; a request error ends the stream only (no peer serves `root`: small)
        n (cnet {:peers [["a" {}] ["b" {}]]})
        root (v/hash (v/string (store/mem-store) "asked of a, then b"))
        _ (h/request! (:client n) root)
        _ (h/on-frame! (:client n) "a" (cc/error-frame 1 "unknown-hash" false))
        ;; responder side
        st (store/mem-store)
        rroot (v/hash (cc/map-of st 5))         ; small: a `get` reads it all
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
   streams by interest. On a simulated clock, over one second each time, with
   4096-byte frames and a `send-max` of 30000: a `get` with a 50000 budget
   writes about 30000 bytes (within one frame; the `send-max` is the smaller); a
   later `get` with 10000 lowers the session to that; and, on another session,
   two streams at interest 1000 and 500 on a 45000 budget share it two to one.
   (Half the bytes and frame size of the first version, the same precision
   in frames, on a 48 KB value: a `get` costs time on nbb.)"
  []
  (let [[st root] (mid-value)
        fb 4096
        slow {"peak" 1000 "tau-ms" 100000000 "floor" 50}
        pace (fn [n from]
               (dotimes [_ 20] (step! n 50))
               (item-bytes n from (+ from 1000)))
        total #(reduce + 0 (vals %))
        n1 (rnet {:st st :max-frame fb
                  :hello (assoc-in responder/default-hello ["rates" "send-max"] 30000)})
        _ (send-to! n1 (get-bytes 1 root 50000 slow))
        capped (pace n1 0)
        _ (send-to! n1 (get-bytes 3 root 10000 slow))
        lowered (pace n1 1000)
        n2 (rnet {:st st :max-frame fb})
        _ (send-to! n2 (get-bytes 1 root 45000 slow))
        _ (send-to! n2 (get-bytes 3 root 45000 (assoc slow "peak" 500)))
        split (pace n2 0)]
    (concat
     (bad "budget 50000, send-max 30000: within one frame of 30000 and not over" true
          (<= (- 30000 fb) (total capped) 30000))
     (bad "after a get with recv-max 10000, about 10000 (two frames of slack)" true
          (<= 1500 (total lowered) (+ 10000 (* 2 fb))))
     (bad "two streams: the 1000 one gets about two thirds of 45000" true
          (near? (get split 1 0) 30000 (+ 1 fb)))
     (bad "the 500 one gets about a third" true
          (near? (get split 3 0) 15000 (+ 1 fb))))))

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
  "C18. Two streams at the same interest and 40000 a second, in 4096-byte
   frames. (1) One is blocked (its channel holds 5 frames) for the whole second:
   it writes nothing, and the other writes at the whole rate, not half. (2) A
   stream blocked for 2 s, then freed as a second stream starts: it banked at
   most one frame (it writes no more than 2 frames in the first tick) and it
   kept decaying (tau 2 s): over the next second it gets about 27% of the rate,
   not the half it would at the same interest. (Two fifths of the bytes and
   half the frame size of the first version, on a 48 KB value: a `get` costs
   time on nbb.)"
  []
  (let [[st root] (mid-value)
        fb 4096
        slow {"peak" 1000 "tau-ms" 100000000 "floor" 50}
        n1 (rnet {:st st :channel? true :max-frame fb})
        _ (send-to! n1 (get-bytes 1 root 40000 slow))
        _ (send-to! n1 (get-bytes 3 root 40000 slow))
        _ (set-buffered! n1 1 (* 5 fb))
        _ (dotimes [_ 20] (set-buffered! n1 3 0) (step! n1 50))
        phase1 (item-bytes n1 0 1000)
        ;; (2)
        decay {"peak" 1000 "tau-ms" 2000 "floor" 50}
        n2 (rnet {:st st :channel? true :max-frame fb})
        _ (send-to! n2 (get-bytes 1 root 40000 decay))
        _ (set-buffered! n2 1 (* 5 fb))
        _ (dotimes [_ 20] (step! n2 100))          ; 2 s blocked
        blocked-bytes (get (item-bytes n2 0 2000) 1 0)
        _ (send-to! n2 (get-bytes 3 root 40000 decay))
        _ (set-buffered! n2 1 0)
        _ (step! n2 10)
        first-tick (get (item-bytes n2 2000 2010) 1 0)
        _ (dotimes [_ 10] (set-buffered! n2 1 0) (set-buffered! n2 3 0) (step! n2 100))
        phase2 (item-bytes n2 2010 3010)
        a (get phase2 1 0) b (get phase2 3 0)]
    (concat
     (bad "blocked stream writes nothing" 0 (get phase1 1 0))
     (bad "the other writes at the whole rate" true
          (<= (- 40000 fb) (get phase1 3 0) 40000))
     (bad "blocked for 2 s, nothing written" 0 blocked-bytes)
     (bad "freed: at most two frames in the first tick (one banked)" true
          (<= first-tick (* 2 fb)))
     (bad "it has decayed meanwhile: about 27% of the next second, not 50%" true
          (< 3200 a 16000))
     (bad "the new stream gets the rest" true (> b (* 1.5 a))))))

(defn get-over-the-bucket-is-rate-limited-and-session-lives
  "C40: the responder's bucket per session. Its hello says req-rate 20 and a
   burst of 2 (the default burst of 40 only makes the check slower: each `get`
   costs time on nbb). Of 4 `get`s at one moment (for a hash it does not hold:
   each costs a token, answered or refused) 2 are answered `unknown-hash` and
   2 `rate-limited` with a
   `retry-ms`; the session lives; after 50 ms one token is back; a long time
   later the bucket holds its burst, not more."
  []
  (let [st (store/mem-store)
        missing (v/hash (v/string (store/mem-store) "not here"))
        n (rnet {:st st :hello (assoc-in responder/default-hello ["rates" "req-burst"] 2)})
        ask! (fn [sid] (send-to! n (get-bytes sid missing 1000)))
        codes #(frequencies (map (fn [m] (get-in m [:value "code"])) (errors-of n)))
        _ (doseq [i (range 4)] (ask! (inc (* 2 i))))
        c1 (codes)
        limited (filterv #(= "rate-limited" (get-in % [:value "code"])) (errors-of n))
        retry (get-in (first limited) [:value "retry-ms"])
        _ (step! n 50)
        _ (ask! 91)                              ; the token that is back
        _ (ask! 93)                              ; not another
        after-50 (codes)
        _ (step! n 60000)
        _ (doseq [i (range 4)] (ask! (+ 101 (* 2 i))))
        after-long (codes)]
    (concat
     (bad "4 gets at once: 2 unknown-hash, 2 rate-limited" {"unknown-hash" 2 "rate-limited" 2} c1)
     (bad "rate-limited names a retry-ms of about 50" true (and retry (<= 1 retry 100)))
     (bad "the limited gets are on their own streams" [5 7] (mapv :stream limited))
     (bad "50 ms later one more is answered, one limited" {"unknown-hash" 3 "rate-limited" 3} after-50)
     (bad "a minute later the burst, 2 more, is there and no more" {"unknown-hash" 5 "rate-limited" 5} after-long)
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

(def ^:private floor-value
  "A store with a value of about 210 items and its root, for the C05 floor
   check. Built once: a responder only reads it. At a `recv-max` of 30000 a
   stream has sent about 27 of its pages at the floor and the other stream
   about 170, so neither reveal is finished. The 630-item `big` value at
   80000 has the same shape, but each `get` of it costs about 300 ms on nbb."
  (delay (let [st (store/mem-store)] [st (v/hash (cc/map-of st 40))])))

(def ^:private floor-recv-max 30000)

(defn stream-ends-partial-when-interest-falls-below-floor
  "C05. Clamped interest reaches the floor at `tau · ln(peak / floor)`, the
   same instant the lifetime already dropped the stream. The tick that reaches
   it ends the stream: FIN, and PARTIAL when pages remain. A reveal that
   finishes first is FIN without PARTIAL. A floor above the peak ends on the
   first tick, with no pages. The other stream of the session stays open."
  []
  (let [[st root] @floor-value
        order (vec (reveal/page-order st root))
        short {"peak" 1000 "tau-ms" 1000 "floor" 200}
        long {"peak" 1000 "tau-ms" 60000 "floor" 10}
        n (rnet {:st st})
        _ (send-to! n (get-bytes 1 root floor-recv-max short))
        _ (send-to! n (get-bytes 3 root floor-recv-max long))
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
        ;; small: a vector of two, three pages. It is enough for a stream
        ;; that ends before its first page, and for a reveal that finishes.
        small (store/mem-store)
        sroot (v/hash (v/vector small (v/i64 small 1) (v/string small "leaf")))
        paused (let [p (rnet {:st small})]
                 (send-to! p (get-bytes 1 sroot floor-recv-max
                                        {"peak" 100 "tau-ms" 1000 "floor" 1000}))
                 (let [open (responder/open-streams (:r p) "p")]
                   (step! p 1)
                   {:open-before open
                    :frames (item-frames p 1)
                    :items (read-items p 1)
                    :open (responder/open-streams (:r p) "p")
                    :status (responder/session-status (:r p) "p")}))
        ;; the reveal finishes while interest is still above the floor
        done (let [sorder (vec (reveal/page-order small sroot))
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
  "C05 and C10. The deadline tick writes FIN+PARTIAL and drops the stream,
   even when the channel is over the high mark: nothing waits for a drain.
   A leaf cut across frames is finished on that same tick when banked credit
   does not cover the rest, and that end is FIN+PARTIAL too.

   One 8000-character string. At 8192 it is two frames, so the channel can
   be blocked after the first. At 4096 a leaf is cut. The floor is about
   9 s out; both stops happen sooner."
  []
  (let [leaf (store/mem-store)
        root (v/hash (v/string leaf (apply str (repeat 8000 "z"))))
        order (vec (reveal/page-order leaf root))
        interest {"peak" 1000 "tau-ms" 3000 "floor" 50}
        ;; blocked after the first frame, still blocked on the deadline tick
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
     (bad "the deadline tick writes FIN PARTIAL with no drain" true
          (and (= (count blocked-after) (inc (count blocked-before)))
               (= [true true] (fin-partial? blocked-after))
               (empty? (no-fin (pop blocked-after)))
               (>= (get @(:bufs blocked) 1 0) (* 4 frame-bytes))))
     (bad "that frame adds no page" pages-before (read-items blocked 1))
     (bad "the stream state is gone on that tick, the session lives" [false :open]
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
   arrives while the `get` is still being read does not let that stream start.
   (A 50000 budget, 4096-byte frames and a 48 KB value: half the bytes and
   frame size of the first version, the same slack in frames; a `get` costs
   time on nbb.)"
  []
  (let [[st root] (mid-value)
        fb 4096
        steady {"peak" 1000 "tau-ms" 60000 "floor" 10}
        n (rnet {:st st :max-frame fb})
        _ (send-to! n (get-bytes 1 root 50000 steady))
        _ (send-to! n (get-bytes 3 root 50000 steady))
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
          (near? (get after 3 0) (* 2 (get before 3 0)) (* 2 fb)))
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

(defn- spec-life
  "C09 clamps, then the lifetime `tau · ln(peak / floor)`."
  [peak tau floor]
  (let [peak (double (min 1000 peak))
        tau (double (min 60000 tau))
        floor (double (max 10 floor))]
    (* tau (host/log (/ peak floor)))))

(defn- life-bracket
  "Whole milliseconds `[before at]` around `life`. `before` is strictly
   earlier. `at` is the first millisecond at or after it."
  [life]
  (let [life (double life)
        floored (host/floor life)
        at (if (< (double floored) life) (inc floored) floored)]
    [(max 0 (dec at)) at]))

(defn- jump!
  "Advance `n` so its clock reads `ms`, and let the responder write."
  [n ms]
  (step! n (- ms @(:clock n))))

(defn get-interest-is-clamped-by-the-responder
  "C09. The responder clamps a `get`'s interest before it uses it: `floor` to
   at least 10, `tau-ms` to at most 60 000, `peak` to at most 1000. A floor
   of 0 or 1, and a peak of 5000, end at the same instant as floor 10 and
   peak 1000. `tau-ms` 120 000 ends at the same instant as 60 000. A request
   inside the bounds ends at its own lifetime, and an absent interest uses
   the defaults, which sit inside the clamps. One session, simulated clock;
   the longest jump is about 276 s."
  []
  (let [st (store/mem-store)
        root (v/hash (v/string st (apply str (repeat 800 "c"))))
        defaults rates/default-interest
        floor-life (spec-life 1000 1000 10)
        own-life (spec-life 500 3000 50)
        default-life (spec-life (get defaults "peak")
                                (get defaults "tau-ms")
                                (get defaults "floor"))
        tau-life (spec-life 1000 60000 10)
        [floor-before floor-at] (life-bracket floor-life)
        [own-before own-at] (life-bracket own-life)
        [def-before def-at] (life-bracket default-life)
        [tau-before tau-at] (life-bracket tau-life)
        clamped #{1 3 5 7 9}
        n (rnet {:st st})
        _ (send-to! n (get-bytes 1 root 1 {"peak" 1000 "tau-ms" 1000 "floor" 0}))
        _ (send-to! n (get-bytes 3 root 1 {"peak" 1000 "tau-ms" 1000 "floor" 1}))
        _ (send-to! n (get-bytes 5 root 1 {"peak" 1000 "tau-ms" 1000 "floor" 10}))
        _ (send-to! n (get-bytes 7 root 1 {"peak" 5000 "tau-ms" 1000 "floor" 10}))
        _ (send-to! n (get-bytes 9 root 1 {"peak" 1000 "tau-ms" 1000 "floor" 10}))
        _ (send-to! n (get-bytes 11 root 1 {"peak" 500 "tau-ms" 3000 "floor" 50}))
        _ (send-to! n (get-bytes 13 root 1 nil))
        _ (send-to! n (get-bytes 15 root 1 {"peak" 1000 "tau-ms" 60000 "floor" 10}))
        _ (send-to! n (get-bytes 17 root 1 {"peak" 1000 "tau-ms" 120000 "floor" 10}))
        _ (jump! n floor-before)
        early (responder/open-streams (:r n) "p")
        _ (jump! n floor-at)
        after-floor (responder/open-streams (:r n) "p")
        _ (jump! n own-before)
        own-open (contains? (responder/open-streams (:r n) "p") 11)
        _ (jump! n own-at)
        after-own (responder/open-streams (:r n) "p")
        _ (jump! n def-before)
        def-open (contains? (responder/open-streams (:r n) "p") 13)
        _ (jump! n def-at)
        after-default (responder/open-streams (:r n) "p")
        _ (jump! n tau-before)
        tau-open (responder/open-streams (:r n) "p")
        _ (jump! n tau-at)]
    (concat
     (bad "the defaults sit inside the clamps" true
          (and (<= 1 (get defaults "peak") 1000)
               (<= 1 (get defaults "tau-ms") 60000)
               (<= 10 (get defaults "floor") (get defaults "peak"))))
     (bad "every stream is open just before the first clamp" #{1 3 5 7 9 11 13 15 17} early)
     (bad "floor 0, floor 1, floor 10, peak 5000 and peak 1000 end together" #{}
          (set (filter after-floor clamped)))
     (bad "that tick is FIN and PARTIAL" [true true] (fin-partial? (item-frames n 1)))
     (bad "a request inside the bounds is still open after that clamp" true own-open)
     (bad "and it ends at its own lifetime, later streams stay" [false true]
          [(contains? after-own 11) (contains? after-own 13)])
     (bad "an absent interest is still open at that earlier lifetime" true def-open)
     (bad "the absent interest has ended, the capped tau has not" [false true]
          [(contains? after-default 13) (contains? after-default 15)])
     (bad "tau 60 000 and tau 120 000 are both open before the cap" #{15 17} tau-open)
     (bad "both are gone at 60 s times ln(100)" #{}
          (responder/open-streams (:r n) "p"))
     (bad "the session stays open" :open (responder/session-status (:r n) "p")))))


(defn- hello-capping
  "This side's hello advertising `max-streams`, with a bucket large enough
   that the cap, not the token bucket, is what refuses a `get`."
  [max-streams]
  (-> responder/default-hello
      (assoc-in ["limits" "max-streams"] max-streams)
      (assoc-in ["rates" "req-burst"] 100)
      (assoc-in ["rates" "req-rate"] 1000)))

(defn- error-rows
  "[[code stream fatal] ...] of every error the responder has sent."
  [n]
  (mapv (fn [m] [(get-in m [:value "code"]) (:stream m) (get-in m [:value "fatal"])])
        (errors-of n)))

(defn- errors-on [n sid]
  (filterv #(= sid (:stream %)) (errors-of n)))

(defn- get-frames
  "Get frames for stream ids `ids`. The value is encoded once; each frame only
   changes the id in the envelope."
  [root recv-max ids interest]
  (let [payload (:payload (frame/decode-frame (get-bytes (first ids) root recv-max interest)))]
    (mapv (fn [sid]
            (frame/encode-frame {:type :get :stream sid :flags frame/flag-fin
                                 :payload payload}))
          ids)))

(defn- ask-range!
  "Open `k` gets of `root` on the odd ids 1, 3, ... ."
  [n root k recv-max]
  (doseq [bs (get-frames root recv-max (map #(inc (* 2 %)) (range k)) nil)]
    (send-to! n bs)))

(defn too-many-streams-is-refused-and-a-closed-slot-frees
  "C11. The responder enforces the `max-streams` it advertises. At 16 (the
   check advertises 16, not the most, 64: each `get` costs time on nbb), the
   17th `get` is `error overloaded` on that stream, the stream does not
   start, and the session stays open. Cancel frees a slot at once and the
   id is not reused. The floor does the same. FIN does too: one stream
   completes while the others are over the high mark, and a get that was
   refused is then accepted."
  []
  (let [st (store/mem-store)
        tiny (v/hash (v/i64 st 7))
        short {"peak" 1000 "tau-ms" 1000 "floor" 200}
        [before at] (life-bracket (spec-life 1000 1000 200))
        n (rnet {:st st :channel? true :hello (hello-capping 16)})
        _ (send-to! n (get-bytes 1 tiny 1 short))
        _ (doseq [bs (get-frames tiny 1 (map #(inc (* 2 %)) (range 1 16)) nil)]
            (send-to! n bs))
        open-16 (responder/open-streams (:r n) "p")
        _ (send-to! n (get-bytes 129 tiny 1))
        refused (error-rows n)
        _ (send-to! n (cancel-bytes 3 {}))
        after-cancel (responder/open-streams (:r n) "p")
        _ (send-to! n (get-bytes 129 tiny 1))
        _ (send-to! n (get-bytes 3 tiny 1))
        after-reuse (responder/open-streams (:r n) "p")
        _ (jump! n before)
        mid (responder/open-streams (:r n) "p")
        _ (send-to! n (get-bytes 131 tiny 1))
        _ (jump! n at)
        floor-frames (item-frames n 1)
        after-floor (responder/open-streams (:r n) "p")
        _ (send-to! n (get-bytes 131 tiny 1000000))
        _ (send-to! n (get-bytes 133 tiny 1))
        held (responder/open-streams (:r n) "p")
        _ (doseq [sid held]
            (when-not (= sid 5) (set-buffered! n sid (* 5 frame-bytes))))
        _ (step! n 100)
        fin-frames (item-frames n 5)
        after-fin (responder/open-streams (:r n) "p")
        _ (send-to! n (get-bytes 133 tiny 1))
        _ (send-to! n (get-bytes 5 tiny 1))
        _ (send-to! n (get-bytes 1 tiny 1))]
    (concat
     (bad "16 gets are open" 16 (count open-16))
     (bad "the 17th is overloaded, on its stream, and the session lives"
          [[["overloaded" 129 false]] :open []]
          [refused (responder/session-status (:r n) "p") @(:closed n)])
     (bad "that get did not start" false (contains? open-16 129))
     (bad "cancel frees the slot at once" 15 (count after-cancel))
     (bad "the cancelled id is no longer open" false (contains? after-cancel 3))
     (bad "the refused id starts once a slot is free" true (contains? after-reuse 129))
     (bad "a closed id is not reused" [["malformed" 3 false]]
          (mapv (fn [m] [(get-in m [:value "code"]) (:stream m) (get-in m [:value "fatal"])])
                (errors-on n 3)))
     (bad "stream 1 is still open just before its floor" true (contains? mid 1))
     (bad "a further get is still overloaded then" [["overloaded" 131 false]]
          (mapv (fn [m] [(get-in m [:value "code"]) (:stream m) (get-in m [:value "fatal"])])
                (errors-on n 131)))
     (bad "the floor ends that stream with FIN PARTIAL" [true true] (fin-partial? floor-frames))
     (bad "the floor frees the slot" false (contains? after-floor 1))
     (bad "the other streams stay open" true (contains? after-floor 5))
     (bad "at the cap again, the next get is overloaded" [true [["overloaded" 133 false]]]
          [(contains? held 131)
           (mapv (fn [m] [(get-in m [:value "code"]) (:stream m) (get-in m [:value "fatal"])])
                 (errors-on n 133))])
     (bad "FIN completes the one stream that could write" [true false] (fin-partial? fin-frames))
     (bad "that stream is gone and another stays" [false true]
          [(contains? after-fin 5) (contains? after-fin 129)])
     (bad "a refused id starts once FIN has freed the slots" true
          (contains? (responder/open-streams (:r n) "p") 133))
     (bad "a finished id is not reused" [["malformed" 5 false]]
          (mapv (fn [m] [(get-in m [:value "code"]) (:stream m) (get-in m [:value "fatal"])])
                (errors-on n 5)))
     (bad "the stream the floor ended is not reused either" [["malformed" 1 false]]
          (mapv (fn [m] [(get-in m [:value "code"]) (:stream m) (get-in m [:value "fatal"])])
                (errors-on n 1)))
     (bad "the session stays open" :open (responder/session-status (:r n) "p")))))


(defn- negotiated-caps [mine theirs]
  (let [ok (:ok (msg/negotiate mine theirs))]
    [(:may-open ok) (:will-accept ok)]))

(defn- hello-streams [n]
  (assoc-in responder/default-hello ["limits" "max-streams"] n))

(defn max-streams-above-64-is-read-as-64
  "C12. A receiver reads an advertised `max-streams` above 64 as 64, in
   negotiation and when the responder enforces its own hello. A smaller
   advertisement is left as it is: 32 and 8 stay put, and a responder that
   advertises 2 refuses the third get."
  []
  (let [st (store/mem-store)
        root (v/hash (v/i64 st 3))
        wide (rnet {:st st :hello (hello-capping 128)})
        _ (ask-range! wide root 64 1000)
        _ (send-to! wide (get-bytes 129 root 1000))
        two (rnet {:st st :hello (hello-capping 2)})
        _ (ask-range! two root 2 1000)
        _ (send-to! two (get-bytes 5 root 1000))]
    (concat
     (bad "128 in either hello is read as 64" [64 64]
          (negotiated-caps (hello-streams 128) (hello-streams 128)))
     (bad "64 stays 64" [64 64]
          (negotiated-caps (hello-streams 64) (hello-streams 64)))
     (bad "the other side's 32 is 32, and this side's 128 is 64" [32 64]
          (negotiated-caps (hello-streams 128) (hello-streams 32)))
     (bad "8 is left at 8" [8 8]
          (negotiated-caps (hello-streams 8) (hello-streams 8)))
     (bad "a responder that advertises 128 accepts 64" 64
          (count (responder/open-streams (:r wide) "p")))
     (bad "and refuses the 65th" [["overloaded" 129 false]] (error-rows wide))
     (bad "the refused stream did not start, and the session lives" [:open false]
          [(responder/session-status (:r wide) "p")
           (contains? (responder/open-streams (:r wide) "p") 129)])
     (bad "a responder that advertises 2 accepts 2 and refuses the third"
          [2 [["overloaded" 5 false]]]
          [(count (responder/open-streams (:r two) "p")) (error-rows two)]))))

(defn- u32-hex
  [n]
  (let [digits "0123456789abcdef"]
    (apply str (map (fn [shift]
                      (nth digits (bit-and 15 (unsigned-bit-shift-right n shift))))
                    [28 24 20 16 12 8 4 0]))))

(defn- claimed-item
  "A node item whose header claims `payload-len` bytes of payload and which
   carries only `extra` of them. The rest of the claim is not in the bytes."
  [payload-len extra]
  (let [st (store/mem-store)
        h (v/hash (v/i64 st 1))
        real (bin/encode-item {:enc :node :hash h
                               :entry (store/await (store/s-get st h))})
        hex (bin/bytes->hex real)]
    (bin/hex->bytes (str (subs hex 0 66)
                         (u32-hex payload-len)
                         (apply str (repeat (* 2 extra) "0"))))))

(defn- item-frame [sid payload]
  (frame/decode-frame
   (frame/encode-frame {:type :item-stream :stream sid :payload payload})))

(defn- feed-outcome
  [st fr]
  (try (let [[st' items] (frame/feed st fr)]
         [:ok (:buffered st') (:need st') (count items)])
       (catch #?(:clj Throwable :cljs :default) e
         [:err (:reason (ex-data e))])))

(defn item-over-max-item-is-refused-before-buffering
  "C32. An item header that claims more than the reader's `max-item` is
   `item-too-large` as soon as the header can be read. The claimed payload
   is not kept: the feed throws instead of returning a reader waiting on
   that length. A claim of exactly `max-item` is accepted, and the reader
   holds only the bytes that have arrived."
  []
  (let [max-item 1000
        over (claimed-item (- (inc max-item) frame/item-header-bytes) 200)
        at (claimed-item (- max-item frame/item-header-bytes) 0)
        split (claimed-item 5000 80)
        n (bin/byte-len split)
        [prefix _] (frame/feed (frame/reader 1 max-item)
                               (item-frame 1 (frame/slice split 0 20)))]
    (concat
     (bad "one byte over max-item is refused from the header" [:err :item-too-large]
          (feed-outcome (frame/reader 1 max-item) (item-frame 1 over)))
     (bad "exactly max-item keeps the header and waits for the payload" [:ok 37 max-item 0]
          (feed-outcome (frame/reader 1 max-item) (item-frame 1 at)))
     (bad "a short prefix is only the bytes in hand" [20 nil]
          [(:buffered prefix) (:need prefix)])
     (bad "finishing an oversized header refuses before the payload is kept"
          [:err :item-too-large]
          (feed-outcome prefix (item-frame 1 (frame/slice split 20 n)))))))

(defn- frames-to-finish
  "From a stream paused inside an item: how many planned frames finish it,
   and whether a planned frame remains after that."
  [st]
  (let [ends (:ends st)
        next (:next st)
        n (count (:frames st))
        finish (loop [i next]
                 (cond
                   (>= i n) nil
                   (seq (get ends i)) i
                   :else (recur (inc i))))]
    (when finish
      {:frames (inc (- finish next))
       :more? (< (inc finish) n)})))

(defn blocked-mid-item-ends-on-the-deadline-tick
  "C05. The channel sits over the high mark partway through a node item
   that spans frames. A tick before the floor writes nothing. The deadline
   tick writes the rest of that item and an empty FIN+PARTIAL frame, and
   the stream state is gone when the tick returns. The tail frame may also
   hold the next whole item that fits in the leftover room.

   `floor-sends-fin-partial-when-blocked-or-credit-runs-out` does not cover
   this. Its blocked channel stops after a frame that finished an item, so
   the deadline adds only the empty frame. The cut leaf in that check is
   credit running out, with the channel empty."
  []
  (let [leaf (store/mem-store)
        root (v/hash (v/string leaf (apply str (repeat 8000 "z"))))
        order (vec (reveal/page-order leaf root))
        mf 4096
        interest {"peak" 1000 "tau-ms" 3000 "floor" 50}
        n (rnet {:st leaf :channel? true :max-frame mf})
        _ (send-to! n (get-bytes 1 root 5000 interest))
        mid (loop [i 0]
              (let [s (responder-stream n 1)]
                (cond
                  (inside-an-item? s) s
                  (or (nil? s) (>= i 40)) nil
                  :else (do (set-buffered! n 1 0)
                            (step! n 50)
                            (recur (inc i))))))
        high (* responder/high-frames mf)
        _ (set-buffered! n 1 (* 5 mf))
        held (item-frames n 1)
        pages-held (read-items n 1)
        _ (step! n 50)
        still (item-frames n 1)
        life (* 3000.0 (host/log (/ 1000.0 50.0)))
        [_ at] (life-bracket life)
        _ (step! n (- at @(:clock n)))
        after (item-frames n 1)
        added (subvec after (count held))
        plan (frames-to-finish mid)
        pages (read-items n 1)
        fed (fed-stream n 1)]
    (concat
     (bad "the channel blocked inside a node item" true
          (and (some? mid) (some? plan) (:more? plan)
               (pos? (count held)) (empty? (no-fin held))))
     (bad "a tick before the floor writes nothing while the channel is high" (count held)
          (count still))
     (bad "the buffer is still over the high mark" true
          (>= (get @(:bufs n) 1 0) high))
     (bad "the deadline writes the rest of the item, then an empty FIN PARTIAL" true
          (and plan (seq added)
               (= (count added) (inc (:frames plan)))
               (every? (fn [fr]
                         (and (not (:fin? fr)) (pos? (bin/byte-len (:payload fr)))))
                       (pop added))
               (= [true true 0]
                  (let [fr (peek added)]
                    [(:fin? fr) (:partial? fr) (bin/byte-len (:payload fr))]))))
     (bad "only the last of those frames ends the stream" []
          (if (seq added) (no-fin (pop added)) [:none]))
     (bad "that tick finishes the cut item and leaves the reveal unfinished" true
          (and (= pages (vec (take (count pages) order)))
               (> (count pages) (count pages-held))
               (< (count pages) (count order))))
     (bad "the receiver can read the partial stream" [true true nil]
          [(:fin? fed) (:partial? fed) (:error fed)])
     (bad "the stream state is gone on that tick, and the session lives" [false :open]
          [(contains? (responder/open-streams (:r n) "p") 1)
           (responder/session-status (:r n) "p")])
     (bad "the channel was not drained to make room" true
          (>= (get @(:bufs n) 1 0) high)))))

;; =============================================================================
;; C06, C08: no idle timeout; renewal is a new stream
;; =============================================================================

(defn stalled-stream-decays-and-frees-its-share
  "C06: there is no idle timeout. (1) Responder: stream 1's channel stays full
   (its peer reads nothing) for 40 simulated seconds, far past any idle
   timeout. It is not ended, no error is sent, and stream 3 of the same
   session is not held: it finishes meanwhile. Read again, stream 1 writes
   again (at about half its peak interest). Only decay ends it: it is open
   just before `tau · ln(peak / floor)` (about 276 s, the longest the clamps
   allow) and ends on that instant with FIN and PARTIAL. (2)
   Client: gets that peers a and b never answer stay open a minute on, with
   no error and no cancel, while their decayed interest returns their share
   of `R_max`: the second get took half of it at once, and a new get a
   minute on takes nearly all of it."
  []
  (let [[st root] (mid-value)
        stall {"peak" 1000 "tau-ms" 60000 "floor" 10}
        [before at] (life-bracket (spec-life 1000 60000 10))
        n (rnet {:st st :channel? true})
        _ (send-to! n (get-bytes 1 root 40000 stall))
        _ (set-buffered! n 1 (* 5 frame-bytes))
        _ (send-to! n (get-bytes 3 root 40000 {"peak" 1000 "tau-ms" 60000 "floor" 10}))
        _ (dotimes [_ 40] (set-buffered! n 3 0) (step! n 1000))
        open-stalled (responder/open-streams (:r n) "p")
        stalled-frames (count (item-frames n 1))
        last3 (peek (item-frames n 3))
        _ (set-buffered! n 1 0)
        _ (step! n 500)
        resumed (count (item-frames n 1))
        _ (jump! n before)
        open-before (contains? (responder/open-streams (:r n) "p") 1)
        _ (jump! n at)
        last1 (peek (item-frames n 1))
        ;; (2)
        r-max 1000000
        hs (distinct-hashes 3)
        c (cnet {:r-max r-max :peers [["a" {}] ["b" {}]]})
        ask! (fn [h] (h/request! (:client c) h)
               (get-in (last (gets-sent c)) [2 :value "recv-max"]))
        _ (ask! (nth hs 0))
        second-rm (ask! (nth hs 1))
        _ (advance! c 60000)
        kept [(client/outstanding (:client c) "a") (client/outstanding (:client c) "b")]
        statuses [(client/session-status (:client c) "a") (client/session-status (:client c) "b")]
        late-rm (ask! (nth hs 2))
        kinds (set (map (fn [[_ _ m]] (:type m)) @(:log c)))]
    (concat
     (bad "40 s stalled, the stream is still open" true
          (contains? open-stalled 1))
     (bad "the stalled stream wrote nothing" 0 stalled-frames)
     (bad "the other stream was not held: it finished" [true false false]
          [(boolean (:fin? last3)) (boolean (:partial? last3))
           (contains? open-stalled 3)])
     (bad "no error was sent" [] (mapv :stream (errors-of n)))
     (bad "read again, the stalled stream writes" true (pos? resumed))
     (bad "just before its lifetime it is still open" true open-before)
     (bad "on that instant decay ends it, FIN and PARTIAL" [false true true]
          [(contains? (responder/open-streams (:r n) "p") 1)
           (boolean (:fin? last1)) (boolean (:partial? last1))])
     (bad "the session stays open" :open (responder/session-status (:r n) "p"))
     (bad "client: the second get took half of R_max at once" (/ r-max 2) second-rm)
     (bad "a minute on, the unanswered gets are still open" [[(nth hs 0)] [(nth hs 1)]] kept)
     (bad "and both sessions too" [:open :open] statuses)
     (bad "the client sent no error and no cancel" #{:get} (disj kinds :hello))
     (bad "their share came back: a new get takes nearly all of R_max" true
          (> late-rm (* 0.99 r-max))))))

(defn renewal-is-a-new-stream-at-peak
  "C08: renewal is a new get with a new stream id, and the responder treats it
   as independent of the stream it renews: a new clock at peak. Stream 1 asks
   with tau 10 s (it lives about 46 s) and its channel stays full; 30 s on a
   get on stream 3 renews the same hash. Both are open. Read again, the
   renewal, at peak, takes nearly all of the session's rate over the next
   second, and stream 1, decayed to about 5% of the peak, not a frame. With
   both channels full again, stream 1 ends at its own deadline and stream 3
   lives on to 30 s plus its own lifetime."
  []
  (let [[st root] (mid-value)
        fb 4096
        interest {"peak" 1000 "tau-ms" 10000 "floor" 10}
        life (spec-life 1000 10000 10)
        n (rnet {:st st :channel? true :max-frame fb})
        _ (send-to! n (get-bytes 1 root 40000 interest))
        _ (set-buffered! n 1 (* 5 fb))
        _ (dotimes [_ 30] (step! n 1000))
        _ (send-to! n (get-bytes 3 root 40000 interest))
        both (responder/open-streams (:r n) "p")
        _ (set-buffered! n 1 0)
        _ (step! n 10)                  ; stream 1's one banked frame (C18)
        t1 @(:clock n)
        _ (dotimes [_ 10] (set-buffered! n 1 0) (set-buffered! n 3 0) (step! n 100))
        window (item-bytes n t1 (+ t1 1000))
        _ (set-buffered! n 1 (* 5 fb))
        _ (set-buffered! n 3 (* 5 fb))
        [before1 at1] (life-bracket life)
        _ (jump! n before1)
        open-before1 (responder/open-streams (:r n) "p")
        _ (jump! n at1)
        open-at1 (responder/open-streams (:r n) "p")
        [before3 at3] (life-bracket (+ 30000 life))
        _ (jump! n before3)
        open-before3 (responder/open-streams (:r n) "p")
        _ (jump! n at3)
        last3 (peek (item-frames n 3))]
    (concat
     (bad "the renewal is a new stream; the renewed one stays open" #{1 3} both)
     (bad "at peak the renewal takes nearly all of 40000 a second" true
          (>= (get window 3 0) 30000))
     (bad "the decayed stream 1 takes not a frame of it" true (< (get window 1 0) fb))
     (bad "just before stream 1's deadline both are open" #{1 3} open-before1)
     (bad "stream 1 ends on its own deadline; the renewal does not" #{3} open-at1)
     (bad "the renewal is open until 30 s plus its own lifetime" #{3} open-before3)
     (bad "then it ends too, FIN and PARTIAL" [#{} true true]
          [(responder/open-streams (:r n) "p") (boolean (:fin? last3)) (boolean (:partial? last3))])
     (bad "no error was sent" [] (mapv :stream (errors-of n))))))

;; =============================================================================
;; C42: no state is shared between sessions
;; =============================================================================

(defn sessions-share-no-state-except-rmax
  "C42: a bucket, budget, record or clock belongs to one session; only the
   client's own `R_max` is shared. (1) One responder, peers p and q, with a
   bucket of 6 a session (a smaller burst than the default 40: each get and
   each error is a message to encode, about 10 ms on nbb). p is served a
   small value and acks it (its record), asks the 48 KB value at `recv-max`
   2000 (its budget), and floods 8 gets for hashes not held, past its bucket
   (rate-limited). q then gets its whole bucket: 2 gets and 4 more, none
   rate-limited. q's get of the small value sends every page,
   not only the root p's record would leave. Over the next second q's stream
   of the 48 KB value writes at its own 40000 a second while p's writes at
   about 2000. p then breaks the protocol and its session ends; q's session
   and stream go on, and its stream finishes. (2) One client, peers a and b,
   each with a bucket of 2. Five requests with only a open: a takes 2, 3
   wait. b opens and takes its own 2, as many as a bucket of 2 holds, and its
   first `get` asks a third of `R_max`: the one thing shared is the split of
   `R_max` by interest (C01), a's two streams at peak against b's one."
  []
  (let [[st root] (mid-value)
        small (v/hash (v/vector st (v/i64 st 1) (v/i64 st 2) (v/i64 st 3)))
        small-order (mapv store/hash->hex (reveal/page-order st small))
        burst 6
        missing (distinct-hashes 12)
        clock (atom 0) sent (atom [])
        r (h/responder {:store st :now (fn [] @clock)
                        :hello (assoc-in responder/default-hello ["rates" "req-burst"] burst)
                        :send! (fn [p bs] (swap! sent conj [@clock p bs]))})
        to! (fn [p bs] (h/responder-on-frame! r p bs))
        step! (fn [ms] (swap! clock + ms) (responder/tick! r))
        long-life {"peak" 1000 "tau-ms" 60000 "floor" 10}
        frames-of (fn [p sid]
                    (into [] (keep (fn [[_ p' bs]]
                                     (let [fr (frame/decode-frame bs)]
                                       (when (and (= p p') (= :item-stream (:type fr)) (= sid (:stream fr)))
                                         fr))))
                          @sent))
        hashes-of (fn [p sid]
                    (loop [frs (frames-of p sid) rd (frame/reader sid) out []]
                      (if (empty? frs)
                        out
                        (let [[rd' items] (frame/feed rd (first frs))]
                          (recur (rest frs) rd'
                                 (into out (mapcat (fn [bs] (map (comp store/hash->hex :hash)
                                                                 (bin/decode-item-stream bs)))
                                                   items)))))))
        bytes-of (fn [p sid from to]
                   (reduce (fn [n [t p' bs]]
                             (let [fr (frame/decode-frame bs)]
                               (if (and (= p p') (> t from) (<= t to)
                                        (= :item-stream (:type fr)) (= sid (:stream fr)))
                                 (+ n (bin/byte-len bs))
                                 n)))
                           0 @sent))
        codes-of (fn [p]
                   (frequencies
                    (keep (fn [[_ p' bs]]
                            (when (and (= p p') (= :error (kind bs)))
                              (get-in (msg/decode-message bs) [:value "code"])))
                          @sent)))
        _ (doseq [p ["p" "q"]]
            (responder/open-session! r p)
            (to! p (hello-bytes (hello-with {}))))
        ;; p: its record, its budget, its bucket
        _ (to! "p" (get-bytes 1 small 10000000))
        _ (step! 100)
        _ (to! "p" (msg/encode-message
                    {:type :ack :stream 0
                     :value {"hashes" (mapv #(msg/hash-ref %) (hashes-of "p" 1))}}))
        _ (to! "p" (get-bytes 3 root 2000 long-life))
        _ (doseq [[i h] (map-indexed vector (take 8 missing))]
            (to! "p" (get-bytes (+ 5 (* 2 i)) h 2000)))
        p-codes (codes-of "p")
        ;; q: the same requests, its own state
        _ (to! "q" (get-bytes 1 small 10000000))
        _ (to! "q" (get-bytes 3 root 40000 long-life))
        _ (doseq [[i h] (map-indexed vector (take (- burst 2) (drop 8 missing)))]
            (to! "q" (get-bytes (+ 5 (* 2 i)) h 40000)))
        q-codes (codes-of "q")
        t1 @clock
        _ (dotimes [_ 10] (step! 100))
        p-rate (bytes-of "p" 3 t1 (+ t1 1000))
        q-rate (bytes-of "q" 3 t1 (+ t1 1000))
        q-small (hashes-of "q" 1)
        _ (to! "p" (garbage))
        after-p [(responder/session-status r "p") (responder/session-status r "q")
                 (contains? (responder/open-streams r "q") 3)]
        _ (dotimes [_ 20] (step! 100))
        q-last (peek (frames-of "q" 3))
        ;; (2) the client
        r-max 1000000
        hs (distinct-hashes 5)
        small-bucket {"req-rate" 1 "req-burst" 2}
        n (cnet {:r-max r-max :peers [["a" small-bucket]]})
        _ (doseq [h hs] (h/request! (:client n) h))
        waiting (count (client/unserved (:client n)))
        _ (client/open-session! (:client n) "b")
        _ (h/on-frame! (:client n) "b" (hello-bytes (hello-with small-bucket)))
        gets (gets-sent n)
        b-first (some (fn [[_ p m]] (when (= "b" p) (get-in m [:value "recv-max"]))) gets)]
    (concat
     (bad "p: its flood of 8 is partly rate-limited" true (pos? (get p-codes "rate-limited" 0)))
     (bad "q: none of its 6 gets is rate-limited, 4 are unknown-hash" {"unknown-hash" (- burst 2)} q-codes)
     (bad "q: its get of the small value sends every page, not p's record" small-order q-small)
     (bad "q's stream writes at its own budget, 40000 a second" true (<= 30000 q-rate 40000))
     (bad "p's stream at its budget, about 2000 a second" true (< p-rate (+ 2000 frame-bytes)))
     (bad "p's session ends; q's session and stream go on" [:ended :open true] after-p)
     (bad "q's stream finishes" [true false] [(boolean (:fin? q-last)) (boolean (:partial? q-last))])
     (bad "client: with only a open, a takes its 2 and 3 wait" 3 waiting)
     (bad "b opens and takes its own 2" {"a" 2 "b" 2} (frequencies (map second gets)))
     (bad "b's first get asks a third of R_max (the shared split)" true
          (and b-first (near? b-first (/ r-max 3) 2))))))

;; =============================================================================
;; C24, C25: the reveal order
;; =============================================================================

(defn- order-value
  "A store and a vector whose elements are each more than one node: a long
   string (its own pages), a vector of three strings, that long string again,
   and a map. Built the same way each call. {:st :root :elems}"
  []
  (let [st (store/mem-store)
        s1 (v/string st (apply str (repeat 9000 "a")))
        v2 (apply v/vector st (map #(v/string st (str "e" % (apply str (repeat 300 "z")))) (range 3)))
        m3 (v/map st "k" (v/string st (apply str (repeat 500 "m"))) "j" (v/i64 st 4))
        elems [s1 v2 s1 m3]]
    {:st st :root (v/hash (apply v/vector st elems)) :elems (mapv v/hash elems)}))

(defn- served-items
  "The decoded items a responder over `st` sends on one get of `root`, in
   order, once the stream has ended."
  [st root]
  (let [n (rnet {:st st})]
    (send-to! n (get-bytes 1 root 10000000))
    (loop [i 0]
      (step! n 200)
      (when (and (seq (responder/open-streams (:r n) "p")) (< i 50))
        (recur (inc i))))
    (loop [frames (item-frames n 1) rd (frame/reader 1) out []]
      (if (empty? frames)
        out
        (let [[rd' items] (frame/feed rd (first frames))]
          (recur (rest frames) rd' (into out (mapcat bin/decode-item-stream items))))))))

(defn- served-order
  "Hex hashes of `served-items`."
  [st root]
  (mapv (comp store/hash->hex :hash) (served-items st root)))

(defn- own-pages
  "The structural pages of the collection `h` (finger-tree or HAMT nodes),
   each page and then its structural children in stored order. Elements
   are not entered. An independent statement of the C24 walk, from
   `types/child-hashes`."
  [st h]
  (letfn [(entry [x] (store/await (store/s-get st x)))
          (walk [x] (if (re-find #"^(ft|hamt)/" (str (first (entry x))))
                      (cons x (mapcat walk (types/child-hashes (entry x))))
                      []))]
    (vec (mapcat walk (types/child-hashes (entry h))))))

(defn reveal-walks-each-subtree-depth-first
  "C24: a responder sends the asked value depth-first. The asked node comes
   first, then the collection's own structural pages, then each element's
   subtree, whole, in element order. A hash already sent is not sent again,
   so the repeated string adds nothing. Each element asked alone starts the
   same way, and the whole order is `dacite.reveal/page-order`."
  []
  (let [{:keys [st root elems]} (order-value)
        hex #(mapv store/hash->hex %)
        sent (served-order st root)
        own (hex (own-pages st root))
        subtrees (mapv #(served-order st %) elems)
        expected (vec (distinct (concat [(store/hash->hex root)] own (apply concat subtrees))))]
    (concat
     (bad "the asked node is first" (store/hash->hex root) (first sent))
     (bad "the vector has its own pages" true (< 1 (count own)))
     (bad "then the vector's own pages, each before its children"
          own (subvec sent 1 (min (count sent) (inc (count own)))))
     (bad "then each element's subtree, whole, in element order, no hash twice"
          expected sent)
     (bad "the repeated element is sent once" (count (distinct sent)) (count sent))
     (vec (mapcat (fn [e sub]
                    (let [eown (hex (own-pages st e))]
                      (bad "an element asked alone: it, then its own pages"
                           (vec (cons (store/hash->hex e) eown))
                           (subvec sub 0 (min (count sub) (inc (count eown)))))))
                  (distinct elems) (distinct subtrees)))
     (bad "the order is the engine's page-order" (hex (reveal/page-order st root)) sent))))

(defn- permute
  "`xs` in an order fixed by `seed` (the same on both hosts)."
  [xs seed]
  (let [keys (vec (take (count xs) (rest (iterate lcg seed))))]
    (mapv second (sort-by first (map vector keys xs)))))

(defn reveal-order-is-fixed-and-receiver-accepts-any
  "C25: the order is fixed, with no shuffling: the same get on the same store
   sends the same order, and so does the same value built in another store.
   A receiver does not rely on it: the items of the nested value verify and
   store all of it forward, reversed and in three fixed shuffles."
  []
  (let [{:keys [st root]} (order-value)
        items (served-items st root)
        order (mapv (comp store/hash->hex :hash) items)
        other (order-value)
        hex-set #(set (map (comp store/hash->hex :hash) %))]
    (concat
     (bad "the same get on the same store, the same order" order (served-order st root))
     (bad "the same value in another store, the same order" order
          (served-order (:st other) (:root other)))
     (bad "the stream sends every page" (count (reveal/page-order st root)) (count items))
     (vec (mapcat (fn [[label items']]
                    (let [rx (verify/receiver (store/mem-store))
                          outcome (try (verify/finish! (verify/accept-items! rx items')) :ok
                                       (catch #?(:clj Throwable :cljs :default) e
                                         (or (:reason (ex-data e)) (ex-message e))))]
                      (concat
                       (bad (str label ": accepted") :ok outcome)
                       (bad (str label ": all stored") (hex-set items)
                            (hex-set (filter #(store/await (store/s-get (:store rx) (:hash %))) items))))))
                  [["forward" items]
                   ["reversed" (vec (reverse items))]
                   ["shuffle 1" (permute items 1)]
                   ["shuffle 2" (permute items 2)]
                   ["shuffle 3" (permute items 3)]])))))

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
   "blocked-mid-item-ends-on-the-deadline-tick" blocked-mid-item-ends-on-the-deadline-tick
   "cancel-stops-and-frees-share-and-is-never-required" cancel-stops-and-frees-share-and-is-never-required
   "get-interest-is-clamped-by-the-responder" get-interest-is-clamped-by-the-responder
   "too-many-streams-is-refused-and-a-closed-slot-frees" too-many-streams-is-refused-and-a-closed-slot-frees
   "max-streams-above-64-is-read-as-64" max-streams-above-64-is-read-as-64
   "item-over-max-item-is-refused-before-buffering" item-over-max-item-is-refused-before-buffering
   "stalled-stream-decays-and-frees-its-share" stalled-stream-decays-and-frees-its-share
   "renewal-is-a-new-stream-at-peak" renewal-is-a-new-stream-at-peak
   "sessions-share-no-state-except-rmax" sessions-share-no-state-except-rmax
   "reveal-walks-each-subtree-depth-first" reveal-walks-each-subtree-depth-first
   "reveal-order-is-fixed-and-receiver-accepts-any" reveal-order-is-fixed-and-receiver-accepts-any))

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
