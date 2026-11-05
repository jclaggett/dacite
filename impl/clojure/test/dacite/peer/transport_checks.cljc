(ns dacite.peer.transport-checks
  "M3.1 slice 1, portable checks (JVM: transport_test.clj, nbb: peer_cljs_test).
   Spec: docs/plan/peering.md M3.1, docs/spec/peer-v1.md section 5.

   Two peers talk through `dacite.peer.transport`, over the in-memory connection
   (`dacite.peer.transport.mem`) and `dacite.peer.link`: a client on one end, a
   responder on the other, frames on the `dacite-peer` channel. Same shape as
   client_checks.cljc: each check returns a vector of problem strings, empty when
   it holds. The clock is simulated (an atom the check advances), so a transfer of
   seconds takes milliseconds."
  (:require [dacite.peer.client :as client]
            [dacite.peer.client-checks :as cc]
            [dacite.peer.link :as link]
            [dacite.peer.msg :as msg]
            [dacite.peer.responder :as responder]
            [dacite.peer.transport :as t]
            [dacite.peer.transport.mem :as mem]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.wire.binary :as bin]))

(def ^:private bad cc/bad)

;; =============================================================================
;; Two peers on a connection
;; =============================================================================

(defn- peer-net
  "A responder over `src` on end `a` and a client with an empty store on end `b`,
   one connection, `dacite-peer` channel attached. `:connect` is the options of
   `mem/connect`. Nothing is delivered yet in manual mode."
  [{:keys [src connect] :or {connect {}}}]
  (let [clock (atom 0)
        [ca cb] (mem/connect connect)
        rl (link/links) cl (link/links)
        dst (store/mem-store)
        r (responder/responder (merge {:store src :now (fn [] @clock)} (link/responder-opts rl)))
        c (client/client (merge {:store dst :now (fn [] @clock)} (link/client-opts cl)))]
    (link/serve! rl r "client" ca)
    (link/open-client-channel! cl c "server" cb)
    {:clock clock :ca ca :cb cb :r r :c c :src src :dst dst :rl rl :cl cl}))

(defn- advance!
  "Move the clock on `ms` and let both sides act."
  [{:keys [clock r c ca]} ms]
  (swap! clock + ms)
  (responder/tick! r)
  (client/tick! c)
  (mem/pump! ca))

(defn- run-until!
  "Advance in 50 ms steps until `(done?)`, at most `limit` steps."
  [net done? limit]
  (loop [i 0]
    (when (and (< i limit) (not (done?)))
      (advance! net 50)
      (recur (inc i)))))

(defn- state-of [{:keys [c r]}]
  [(client/session-status c "server") (responder/session-status r "client")])

(defn- have-all?
  "True when every hash of a full reveal of `root` in `src` is in `dst`."
  [src dst root]
  (every? #(store/await (store/s-has? dst %)) (reveal/page-order src root)))

(defn- source
  "A store holding a vector of `n` lines; [store root]. (A vector, not a map: on
   nbb a `hamt/bitmap` page does not verify, because the wire decoder reads its
   u32 bitmap as a signed number. Known, not part of M3.1.)"
  [n]
  (let [st (store/mem-store)] [st (v/hash (cc/vector-value st n))]))

(defn- transfer!
  "Ask for `root` on the client of `net` and run until it is done. Returns `net`."
  [{:keys [c] :as net} root]
  (client/request! c root)
  (mem/pump! (:ca net))
  (run-until! net #(client/done? c root) 400)
  net)

(defn- blob-source
  "A store holding a blob of `n` bytes (cheap to build and about `n` bytes of
   pages, more frames than the high mark); [store root]."
  [n]
  (let [st (store/mem-store)
        bs #?(:clj (let [b (byte-array n)] (dotimes [i n] (aset b i (unchecked-byte (mod (* i 7919) 251)))) b)
              :cljs (let [b (js/Uint8Array. n)] (dotimes [i n] (aset b i (mod (* i 7919) 251))) b))]
    [st (v/hash (v/blob st bs))]))

;; =============================================================================
;; The checks
;; =============================================================================

(defn two-peers-over-in-memory-transport-verify-a-value
  "M3.1: a client reads a value that only the responder holds, through the
   transport. Every entry is verified (the client stores nothing it did not
   check), the value's hash matches, and both sessions are open."
  []
  (let [[src root] (source 60)
        net (transfer! (peer-net {:src src}) root)
        {:keys [c dst ca]} net]
    (concat
     (bad "the client has the value" true (client/done? c root))
     (bad "every entry of the reveal is in the client's store" true (have-all? src dst root))
     (bad "both sessions are open" [:open :open] (state-of net))
     (bad "the connection carried frames" true (pos? (:messages (mem/stats ca)))))))

(defn max-frame-is-clamped-to-the-channel
  "peer-v1 section 5: the `max-frame` of a `hello` is cut to the channel's
   `max-message-size`, so a frame never needs splitting at the transport. With a
   channel of 8192 no message is larger, and the value still arrives. A channel
   under the smallest frame cannot carry a session. `send!` refuses a message over
   the channel's size."
  []
  (let [[src root] (blob-source 40000)
        net (transfer! (peer-net {:src src :connect {:max-message-size 8192}}) root)
        wide (transfer! (peer-net {:src src}) root)
        small (try (peer-net {:src src :connect {:max-message-size 4096}}) :accepted
                   (catch #?(:clj Throwable :cljs :default) e (:reason (ex-data e))))
        [ca _] (mem/connect {:max-message-size 100})
        ch (t/open-channel! ca "x")
        refused (try (t/send! ch (bin/hex->bytes (apply str (repeat 101 "00")))) :sent
                     (catch #?(:clj Throwable :cljs :default) e (:reason (ex-data e))))
        hello (msg/clamp-hello (:hello (client/client {})) 9000)]
    (concat
     (bad "the value arrived over 8192-byte messages" true (have-all? src (:dst net) root))
     (bad "no message was over 8192" true (<= (:largest (mem/stats (:ca net))) 8192))
     (bad "without the clamp frames are larger (so the clamp is what held them)" true
          (> (:largest (mem/stats (:ca wide))) 8192))
     (bad "and the value also arrived over the wide channel" true (have-all? src (:dst wide) root))
     (bad "a channel under the smallest frame is refused" :channel-too-small small)
     (bad "send! over the channel's size is refused" :message-too-large refused)
     (bad "clamp-hello cuts max-frame" 9000 (get-in hello ["limits" "max-frame"]))
     (bad "and keeps a smaller one" 8192
          (get-in (msg/clamp-hello (assoc-in hello ["limits" "max-frame"] 8192) 16384) ["limits" "max-frame"])))))

(defn peer-never-sees-roots-messages
  "Named channels: `dacite-roots` shares the connection with `dacite-peer`.
   Messages on it, even garbage, never reach the peer code (which would end its
   session on them), and the value still transfers."
  []
  (let [[src root] (source 40)
        net (peer-net {:src src})
        {:keys [ca cb]} net
        got-a (atom []) got-b (atom [])
        ;; the roots layer: a handler on each end
        _ (t/on-channel! ca t/roots-channel
                         (fn [ch] (t/on-message! ch #(swap! got-a conj %))
                           (t/send! ch (bin/hex->bytes "52"))))
        rb (t/open-channel! cb t/roots-channel)
        _ (t/on-message! rb #(swap! got-b conj %))
        garbage (bin/hex->bytes "00010203ff")
        _ (t/send! rb garbage)
        _ (t/send! rb (bin/hex->bytes "52"))
        _ (transfer! net root)]
    (concat
     (bad "roots messages reached roots" 2 (count @got-a))
     (bad "the answering roots end sent back one" 1 (count @got-b))
     (bad "the peer session stayed open" [:open :open] (state-of net))
     (bad "the value arrived beside it" true (have-all? src (:dst net) root))
     (bad "no error went to the peer" 0 (count (client/failed (:c net)))))))

(defn closing-a-channel-ends-only-that-layers-session
  "One channel close ends only that layer: closing `dacite-roots` leaves the peer
   session open and working. Closing `dacite-peer` ends both peer sessions and
   leaves roots open."
  []
  (let [[src root] (source 40)
        net (peer-net {:src src})
        {:keys [ca cb]} net
        roots-closed (atom 0)
        _ (t/on-channel! ca t/roots-channel (fn [ch] (t/on-close! ch #(swap! roots-closed inc))))
        rb (t/open-channel! cb t/roots-channel)
        _ (t/on-close! rb #(swap! roots-closed inc))
        _ (t/close! rb)
        after-roots-close (state-of net)
        _ (transfer! net root)
        arrived (have-all? src (:dst net) root)
        ;; a second roots channel, to close the peer channel instead
        rb2 (t/open-channel! cb "dacite-roots-2")
        peer-end (get @(:cl net) "server")
        _ (t/close! peer-end)
        after-peer-close (state-of net)]
    (concat
     (bad "both ends of roots saw it close" 2 @roots-closed)
     (bad "the peer session was open after the roots channel closed" [:open :open] after-roots-close)
     (bad "and the value transferred" true arrived)
     (bad "closing the peer channel ends both sessions" [:ended :ended] after-peer-close)
     (bad "another channel is not touched" true (t/channel-open? rb2))
     (bad "a send on the closed peer channel is refused" :channel-closed
          (try (t/send! peer-end (bin/hex->bytes "00")) :sent
               (catch #?(:clj Throwable :cljs :default) e (:reason (ex-data e))))))))

(defn closing-the-connection-ends-the-peer-session
  "A connection close ends the peer session like a fatal error: both sides'
   sessions end, every channel closes, and what the client was waiting for goes
   unserved (no other peer) rather than being lost."
  []
  (let [[src root] (source 60)
        net (peer-net {:src src :connect {:manual? true}})
        {:keys [c ca cb]} net
        _ (mem/pump! ca)                ; the hellos
        _ (client/request! c root)
        _ (mem/pump! ca)                ; the get
        asked (client/outstanding c "server")
        rb (t/open-channel! cb t/roots-channel)
        conn-closed (atom 0)
        _ (t/on-connection-close! ca #(swap! conn-closed inc))
        _ (t/on-connection-close! cb #(swap! conn-closed inc))
        _ (t/close-connection! cb)
        _ (mem/pump! ca)]
    (concat
     (bad "the get was out" 1 (count asked))
     (bad "both sessions ended" [:ended :ended] (state-of net))
     (bad "every channel closed" false (t/channel-open? rb))
     (bad "both ends heard the connection close" 2 @conn-closed)
     (bad "the connection is closed" false (t/connection-open? cb))
     (bad "the hash is waiting for another peer" [(store/hash->hex root)]
          (mapv store/hash->hex (client/unserved c)))
     (bad "nothing unverified was stored" 0 (count (cc/snapshot (:dst net)))))))

(defn a-corrupted-entry-ends-the-session-over-the-transport
  "M3.1: a responder serves an entry that does not match its hash. The client
   refuses it (C13), sends a fatal `bad-entry`, closes the channel, and stores
   nothing unverified; both sessions end and the responder's other channels live."
  []
  (let [[src root] (source 60)
        pages (reveal/page-order src root)
        victim (last pages)
        ;; put another page's entry under the victim's hash
        _ (store/await (store/s-put src victim (store/await (store/s-get src (nth pages 2)))))
        net (peer-net {:src src})
        {:keys [cb c dst]} net
        roots (t/open-channel! cb t/roots-channel)
        _ (transfer! net root)]
    (concat
     (bad "both sessions ended" [:ended :ended] (state-of net))
     (bad "the corrupt entry is not stored" false (store/await (store/s-has? dst victim)))
     (bad "the value is not done" false (client/done? c root))
     (bad "the roots channel lived" true (t/channel-open? roots)))))

(defn back-pressure-reads-the-channels-buffered-amount
  "C17 over a real channel: with nothing delivered, the responder writes until the
   channel holds 4 frames and then stops (at most 5 frames ever buffered). When the
   channel is delivered the writing resumes, and the value arrives."
  []
  (let [[src root] (blob-source 100000)
        frame 8192
        net (peer-net {:src src :connect {:manual? true :max-message-size frame}})
        {:keys [c ca]} net
        _ (mem/pump! ca)
        _ (client/request! c root)
        _ (mem/pump! ca)                ; the get reaches the responder
        _ (dotimes [_ 40] (swap! (:clock net) + 100) (responder/tick! (:r net)))
        server-end (get @(:rl net) "client")
        held (t/buffered-amount server-end)
        sent-before (:messages (mem/stats ca))
        _ (dotimes [_ 3] (swap! (:clock net) + 100) (responder/tick! (:r net)))
        still-held (t/buffered-amount server-end)
        sent-blocked (:messages (mem/stats ca))
        _ (mem/pump! ca)                ; the client takes what is buffered
        _ (run-until! net #(client/done? c root) 400)]
    (concat
     (bad "the responder wrote until 4 frames were held" true (>= held (* 4 frame)))
     (bad "and never more than 5" true (<= held (* 5 frame)))
     (bad "a blocked channel gets nothing more, however long it waits" [held sent-before]
          [still-held sent-blocked])
     (bad "writing resumed once the channel was delivered, and the value arrived" true
          (have-all? src (:dst net) root))
     (bad "nothing is left buffered" 0 (t/buffered-amount server-end)))))

(defn in-memory-channel-keeps-order-and-counts-buffered
  "The in-memory transport itself: messages arrive in order, `buffered-amount`
   counts what is sent and not delivered, a message sent before the handler is
   set waits for it, a message sent before a close is delivered before the close,
   and a channel opened before the other side listens is handed over when it does."
  []
  (let [[a b] (mem/connect {:manual? true})
        ch-a (t/open-channel! a "x")
        msgs (mapv #(bin/hex->bytes %) ["01" "0202" "030303"])
        _ (doseq [m msgs] (t/send! ch-a m))
        buffered-before (t/buffered-amount ch-a)
        pending-before (mem/pending a)
        seen (atom [])
        chan-b (atom nil)
        ;; the other side listens only now
        _ (t/on-channel! b "x" #(reset! chan-b %))
        _ (mem/deliver-next! a)          ; the channel event
        _ (t/on-message! @chan-b #(swap! seen conj (bin/bytes->hex %)))
        _ (mem/deliver-next! a)
        after-one (t/buffered-amount ch-a)
        _ (t/close! ch-a)
        closed (atom 0)
        _ (t/on-close! @chan-b #(swap! closed inc))
        _ (mem/pump! a)]
    (concat
     (bad "buffered counts the bytes sent" 6 buffered-before)
     (bad "four events wait (the channel and three messages), plus none else" 4 pending-before)
     (bad "one delivery frees its bytes" 5 after-one)
     (bad "messages arrive in order, before the close" ["01" "0202" "030303"] @seen)
     (bad "the close was seen once" 1 @closed)
     (bad "nothing is buffered after" 0 (t/buffered-amount ch-a)))))

(def checks
  "Test name -> check. Run order is this order."
  (array-map
   "two-peers-over-in-memory-transport-verify-a-value" two-peers-over-in-memory-transport-verify-a-value
   "max-frame-is-clamped-to-the-channel" max-frame-is-clamped-to-the-channel
   "peer-never-sees-roots-messages" peer-never-sees-roots-messages
   "closing-a-channel-ends-only-that-layers-session" closing-a-channel-ends-only-that-layers-session
   "closing-the-connection-ends-the-peer-session" closing-the-connection-ends-the-peer-session
   "a-corrupted-entry-ends-the-session-over-the-transport" a-corrupted-entry-ends-the-session-over-the-transport
   "back-pressure-reads-the-channels-buffered-amount" back-pressure-reads-the-channels-buffered-amount
   "in-memory-channel-keeps-order-and-counts-buffered" in-memory-channel-keeps-order-and-counts-buffered))

(def max-ms
  "No check may take longer than this (milliseconds)."
  3000)

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
