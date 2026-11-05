(ns dacite.peer.transport-webrtc-checks
  "M3.2 WebRTC over node-datachannel.
   Run: `npm run webrtc:test`.
   In-process checks use host ICE and `connect-peers`.
   `two-peers-connect-by-room-token` spawns `scripts/webrtc-signal-server.mjs`
   and joins one room (slice 3). The ~5 MB transfer is
   `node scripts/webrtc-transfer.mjs` (file `--dir`, or `--ws` and `--room`)."
  (:require             [dacite.peer.client :as client]
            [dacite.peer.client-checks :as cc]
            [dacite.peer.frame :as frame]
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
                      (bad "channel negotiated a maxMessageSize" true (> max-sz frame/webrtc-max-frame))
                      (bad "the session hello is within the webrtc clamp" true
                           (<= (get-in @(:state c) [:sessions "server" :hello "limits" "max-frame"])
                               frame/webrtc-max-frame))
                      (bad "clamp-hello still respects maxMessageSize"
                           (min 16384 max-sz)
                           (get-in (msg/clamp-hello sample-hello max-sz)
                                   ["limits" "max-frame"])))))))))))))

(defn webrtc-hello-max-frame-is-at-most-4096
  "The hellos exchanged over WebRTC advertise max-frame ≤ 4096, and the
   client side opens through `open-client-channel!`, which has to wait on
   the promise `open-channel!` returns."
  []
  (-> (webrtc/connect-pair {})
      (.then
       (fn [[a b]]
         (let [now (fn [] 0)
               rl (link/links)
               cl (link/links)
               r (responder/responder (merge {:store (store/mem-store) :now now}
                                             (link/responder-opts rl)))
               c (client/client (merge {:store (store/mem-store) :now now}
                                       (link/client-opts cl)))
               _ (link/serve! rl r "client" b)
               opened (link/open-client-channel! cl c "server" a)]
           (-> (js/Promise.resolve opened)
               (.then (fn [_]
                        (wait-until!
                         #(and (get-in @(:state c) [:sessions "server" :hello])
                               (get-in @(:state r) [:sessions "client" :peer-hello]))
                         400)))
               (.then
                (fn [_]
                  (let [from-responder (get-in @(:state c) [:sessions "server" :hello "limits" "max-frame"])
                        from-client (get-in @(:state r) [:sessions "client" :peer-hello "limits" "max-frame"])
                        ch (get @cl "server")]
                    (vec
                     (concat
                      (bad "open-client-channel! returned a promise" true (instance? js/Promise opened))
                      (bad "the channel's maxMessageSize is above the clamp" true
                           (> (t/max-message-size ch) frame/webrtc-max-frame))
                      (bad "responder hello max-frame ≤ 4096" true (<= from-responder frame/webrtc-max-frame))
                      (bad "client hello max-frame ≤ 4096" true (<= from-client frame/webrtc-max-frame))
                      (bad "negotiated frame is the clamp" frame/webrtc-max-frame
                           (get-in @(:state r) [:sessions "client" :max-frame])))))))))))))

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

(defn- with-timeout [p ms label]
  (js/Promise.
   (fn [resolve reject]
     (let [timer (js/setTimeout #(reject (ex-info label {:reason :timeout})) ms)]
       (-> (js/Promise.resolve p)
           (.then (fn [v] (js/clearTimeout timer) (resolve v)))
           (.catch (fn [e] (js/clearTimeout timer) (reject e))))))))

(defn- start-signal-server!
  "Spawn the slice-3 relay on an ephemeral port. Resolves with
   `{:url :kill!}`."
  []
  (js/Promise.
   (fn [resolve reject]
     (let [fs (js/require "fs")
           cp (js/require "child_process")
           script "scripts/webrtc-signal-server.mjs"]
       (if-not (.existsSync fs script)
         (reject (ex-info "signal server script missing"
                          {:reason :missing :path script :cwd (.cwd js/process)}))
         (let [child (.spawn cp "node" #js [script "--port" "0"]
                             #js {:stdio #js ["ignore" "pipe" "pipe"]})
               out (atom "")
               err (atom "")
               done? (atom false)
               timer (js/setTimeout
                      #(when-not @done?
                         (reset! done? true)
                         (try (.kill child "SIGTERM") (catch :default _))
                         (reject (ex-info "signal server did not listen"
                                          {:reason :timeout :out @out :err @err})))
                      5000)]
           (.on (.-stdout child) "data"
                (fn [d]
                  (swap! out str (.toString d))
                  (when-not @done?
                    (when-let [m (re-find #"listening (ws://\S+)" @out)]
                      (reset! done? true)
                      (js/clearTimeout timer)
                      (resolve {:url (nth m 1)
                                :kill! (fn []
                                         (try (.kill child "SIGTERM")
                                              (catch :default _)))})))))
           (.on (.-stderr child) "data" (fn [d] (swap! err str (.toString d))))
           (.on child "error"
                (fn [e]
                  (when-not @done?
                    (reset! done? true)
                    (js/clearTimeout timer)
                    (reject e))))
           (.on child "exit"
                (fn [code]
                  (when-not @done?
                    (reset! done? true)
                    (js/clearTimeout timer)
                    (reject (ex-info "signal server exited"
                                     {:reason :signal-server
                                      :code code
                                      :out @out
                                      :err @err})))))))))))

(defn two-peers-connect-by-room-token
  "Slice 3: two `create-peer` ends exchange SDP and ICE through a room token
   and open `dacite-peer`. No signal directory."
  []
  (-> (start-signal-server!)
      (.then
       (fn [{:keys [url kill!]}]
         (let [room (str "room-" (.now js/Date))
               a (webrtc/create-peer :offerer {:ws {:url url :room room}})
               b (webrtc/create-peer :answerer {:ws {:url url :room room}})
               cleanup! (fn []
                          (try (webrtc/close-signaling! a) (catch :default _))
                          (try (webrtc/close-signaling! b) (catch :default _))
                          (try (t/close-connection! (:conn a)) (catch :default _))
                          (try (t/close-connection! (:conn b)) (catch :default _))
                          (kill!))]
           (-> (js/Promise.all
                #js [(:signaling a)
                     (:signaling b)
                     (with-timeout (webrtc/open-peer-channel! (:conn a) (:conn b))
                                   7000
                                   "room data channel")])
               (.then
                (fn [result]
                  (let [pair (aget result 2)
                        ch-a (first pair)
                        ch-b (second pair)
                        payload (js/Uint8Array. #js [9 8 7 6 5])]
                    (js/Promise.
                     (fn [resolve reject]
                       (let [timer (js/setTimeout
                                    #(reject (ex-info "room payload was not delivered"
                                                      {:reason :timeout}))
                                    5000)]
                         (t/on-message!
                          ch-b
                          (fn [bs]
                            (js/clearTimeout timer)
                            (resolve
                             (vec
                              (concat
                               (bad "bytes arrived over the room" "0908070605"
                                    (.toString (js/Buffer.from bs) "hex"))
                               (bad "frame clamp stays 4096" frame/webrtc-max-frame
                                    (t/max-frame-limit ch-a))
                               (bad "the channel is dacite-peer" t/peer-channel
                                    (t/channel-name ch-a)))))))
                         (t/send! ch-a payload)))))))
               (.then (fn [problems] (cleanup!) problems))
               (.catch (fn [e] (cleanup!) (throw e)))))))))

(def checks
  (array-map
   "two-peers-over-webrtc-verify-a-value" two-peers-over-webrtc-verify-a-value
   "webrtc-hello-max-frame-is-at-most-4096" webrtc-hello-max-frame-is-at-most-4096
   "channel-close-ends-the-peer-session" channel-close-ends-the-peer-session
   "buffered-amount-back-pressure-over-webrtc" buffered-amount-back-pressure-over-webrtc
   "two-peers-connect-by-room-token" two-peers-connect-by-room-token))

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
