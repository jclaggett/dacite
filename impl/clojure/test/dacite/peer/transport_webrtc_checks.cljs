(ns dacite.peer.transport-webrtc-checks
  "M3.2 slice 2: WebRTC over node-datachannel, in one process (host ICE).
   Run: `npm run webrtc:test`. The ~5 MB two-process transfer with manual
   signaling is `node scripts/webrtc-transfer.mjs` (see peering.md)."
  (:require [dacite.peer.client :as client]
            [dacite.peer.client-checks :as cc]
            [dacite.peer.link :as link]
            [dacite.peer.msg :as msg]
            [dacite.peer.responder :as responder]
            [dacite.peer.transport :as t]
            [dacite.peer.transport.webrtc :as webrtc]
            [dacite.reveal :as reveal]
            [dacite.store :as store]
            [dacite.value :as v]))

(def ^:private bad cc/bad)

(defn- blob-source [n]
  (let [st (store/mem-store)
        bs (let [b (js/Uint8Array. n)]
             (dotimes [i n] (aset b i (mod (* i 7919) 251)))
             b)]
    [st (v/hash (v/blob st bs))]))

(defn- have-all? [src dst root]
  (every? #(store/await (store/s-has? dst %)) (reveal/page-order src root)))

(defn- tick-until! [r c advance! done? limit]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(step [i]
               (cond
                 (done?) (resolve i)
                 (>= i limit) (reject (ex-info "transfer did not finish"
                                               {:reason :timeout :steps i}))
                 :else (do (advance! 50)
                           (responder/tick! r)
                           (client/tick! c)
                           (js/setTimeout #(step (inc i)) 5))))]
       (step 0)))))

(defn- wait-until! [pred limit]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(step [i]
               (cond
                 (pred) (resolve i)
                 (>= i limit) (reject (ex-info "wait-until timed out" {:reason :timeout :steps i}))
                 :else (js/setTimeout #(step (inc i)) 10)))]
       (step 0)))))

(defn- peer-over-webrtc [src]
  (-> (webrtc/connect-peers {})
      (.then
       (fn [{:keys [ch-a ch-b]}]
         (let [clock (atom 0)
               now (fn [] @clock)
               rl (link/links)
               cl (link/links)
               dst (store/mem-store)
               r (responder/responder (merge {:store src :now now} (link/responder-opts rl)))
               c (client/client (merge {:store dst :now now} (link/client-opts cl)))]
           (link/attach-responder! rl r "client" ch-b)
           (link/attach-client! cl c "server" ch-a)
           {:r r :c c :src src :dst dst :ch-a ch-a :ch-b ch-b
            :advance! (fn [ms] (swap! clock + ms))})))))

(def ^:private sample-hello
  {"versions" #{1}
   "kind" "node"
   "caps" #{"reveal"}
   "limits" {"max-frame" 16384 "max-item" 1048576 "max-streams" 32}
   "rates" {"send-max" 1 "recv-max" 1 "req-rate" 1 "req-burst" 1}})

(defn two-peers-over-webrtc-verify-a-value []
  (let [[src root] (blob-source 30000)]
    (-> (peer-over-webrtc src)
        (.then
         (fn [net]
           (let [{:keys [c r dst ch-a advance!]} net
                 max-sz (t/max-message-size ch-a)]
             (-> (wait-until! #(some? (get-in @(:state c) [:sessions "server" :hello])) 200)
                 (.then (fn [_] (client/request! c root) (advance! 0)
                          (tick-until! r c advance! #(client/done? c root) 800)))
                 (.then
                  (fn [_]
                    (vec
                     (concat
                      (bad "the client has the value" true (client/done? c root))
                      (bad "every entry is stored" true (have-all? src dst root))
                      (bad "sessions open" [:open :open]
                           [(client/session-status c "server")
                            (responder/session-status r "client")])
                      (bad "channel negotiated a maxMessageSize" true (pos? max-sz))
                      (bad "clamp-hello respects it"
                           (min 16384 max-sz)
                           (get-in (msg/clamp-hello sample-hello max-sz)
                                   ["limits" "max-frame"])))))))))))))

(defn max-frame-clamped-to-webrtc-max-message-size []
  (-> (webrtc/connect-peers {})
      (.then
       (fn [{:keys [ch-a]}]
         (let [max-sz (t/max-message-size ch-a)
               hello (msg/clamp-hello sample-hello max-sz)]
           (vec
            (concat
             (bad "maxMessageSize is at least the minimum frame" true (>= max-sz 8192))
             (bad "clamp cuts 16384 to the channel" (min 16384 max-sz)
                  (get-in hello ["limits" "max-frame"])))))))))

(defn channel-close-ends-the-peer-session []
  (let [[src root] (blob-source 8000)]
    (-> (peer-over-webrtc src)
        (.then
         (fn [{:keys [c r ch-a]}]
           (client/request! c root)
           (t/close! ch-a)
           (js/Promise.
            (fn [resolve]
              (js/setTimeout
               (fn []
                 (resolve
                  (vec
                   (concat
                    (bad "client session ended" :ended (client/session-status c "server"))
                    (bad "responder session ended" :ended (responder/session-status r "client"))
                    (bad "value not done" false (client/done? c root))))))
               50))))))))

(defn buffered-amount-back-pressure-over-webrtc []
  (-> (webrtc/connect-peers {})
      (.then
       (fn [{:keys [ch-a ch-b]}]
         (let [got (atom 0)
               frame 16000
               n 8
               payload (js/Uint8Array. frame)]
           (t/on-message! ch-b (fn [_] (swap! got inc)))
           (dotimes [_ n] (t/send! ch-a payload))
           (let [held (t/buffered-amount ch-a)]
             (js/Promise.
              (fn [resolve]
                (js/setTimeout
                 (fn []
                   (resolve
                    (vec
                     (concat
                      (bad "some bytes were buffered right after send"
                           true (or (pos? held) (= @got n)))
                      (bad "all messages arrived" n @got)
                      (bad "buffer drained" 0 (t/buffered-amount ch-a))))))
                 100)))))))))

(def checks
  (array-map
   "two-peers-over-webrtc-verify-a-value" two-peers-over-webrtc-verify-a-value
   "max-frame-clamped-to-webrtc-max-message-size" max-frame-clamped-to-webrtc-max-message-size
   "channel-close-ends-the-peer-session" channel-close-ends-the-peer-session
   "buffered-amount-back-pressure-over-webrtc" buffered-amount-back-pressure-over-webrtc))

(def max-ms 8000)

(defn run-all
  "Run every check. Each may return a promise of problems. Returns a promise of
   [{:name :ms :problems}]."
  []
  (reduce
   (fn [p [name f]]
     (.then p
            (fn [acc]
              (let [t0 (.now js/Date)]
                (-> (js/Promise.resolve (f))
                    (.then (fn [problems]
                             (let [ms (- (.now js/Date) t0)
                                   problems (cond-> (vec problems)
                                              (> ms max-ms)
                                              (conj (str "took " ms " ms, over " max-ms)))]
                               (conj acc {:name name :ms ms :problems problems}))))
                    (.catch (fn [e]
                              (conj acc {:name name
                                         :ms (- (.now js/Date) t0)
                                         :problems [(str "threw " (ex-message e)
                                                         " " (pr-str (ex-data e)))]}))))))))
   (js/Promise.resolve [])
   checks))
