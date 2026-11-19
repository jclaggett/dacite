(ns dacite.cli.peer
  "The nbb peer `npx dacite` runs (docs/design/packaging.md, slice 1).

   `bin/dacite.mjs` starts the one-port server (static app code and the
   `/signal` room relay), then calls `start!` with that relay's URL, a
   room it made for this launch, and the launch's `/signal` token, which
   this peer sends as the subprotocol `dacite.token.<token>` like the page
   (docs/design/packaging.md §P1). This peer is the WebRTC answerer
   in that room. The page is the offerer.

   The store is in memory and holds one welcome value, so a fresh launch
   has something to fetch. A persistent store (`--store`, the platform
   data dir) is later packaging work.

   One browser session at a time: the room has one offerer slot. When a
   session's connection closes, the page leaves the room, or signaling
   fails, this peer leaves the room and joins it again for the next page."
  (:require [dacite.peer.link :as link]
            [dacite.peer.responder :as responder]
            [dacite.peer.transport :as t]
            [dacite.peer.transport.webrtc :as webrtc]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.value :as v]))

(defn welcome-value
  "The value a fresh peer holds."
  [st]
  (v/map st
         "dacite" "local peer"
         "says" (v/vector st "hello" "from" "npx dacite")))

(def rejoin-ms
  "Wait before joining the room again after a session ends."
  250)

(defn- join!
  "One session: join `room` as the answerer, serve the page's channel,
   and join again when the session ends."
  [{:keys [ws room token links r log] :as ctx} n]
  (let [peer (webrtc/create-peer :answerer {:ws {:url ws :room room :token token :timeout-ms nil}})
        conn (:conn peer)
        ended? (atom false)
        again! (fn [why]
                 (when-not @ended?
                   (reset! ended? true)
                   (log (str "session " n " ended: " why))
                   (webrtc/close-signaling! peer)
                   (try (t/close-connection! conn) (catch :default _ nil))
                   (js/setTimeout #(join! ctx (inc n)) rejoin-ms)))]
    (t/on-channel! conn t/peer-channel
                   (fn [ch] (link/attach-responder! links r (str "page-" n) ch)))
    (t/on-connection-close! conn #(again! "closed"))
    ;; After signaling settles, `signal-over-ws!` stops reporting
    ;; `peer-left`. A page that reloads leaves the room long before ICE
    ;; notices the old connection is gone, so watch the socket too.
    (when-let [sock (some-> peer :ws-ref deref)]
      (.on sock "message"
           (fn [data]
             (when (= "peer-left" (try (.-type (js/JSON.parse (.toString data)))
                                       (catch :default _ nil)))
               (again! "peer-left")))))
    (-> (:signaling peer)
        (.then (fn [_] (log (str "session " n " connected"))))
        (.catch (fn [e]
                  (again! (or (some-> (ex-data e) :reason name)
                              (ex-message e)
                              (str e))))))
    nil))

(defn start!
  "Hold the welcome value and answer in `room` on the relay at `ws`.
   Takes and returns JS objects (it is called from `bin/dacite.mjs`):
   `{ws, room, token}` in, `{value, entries}` out (the welcome value's hex hash
   and reachable entry count)."
  [opts]
  (let [{:keys [ws room token]} (js->clj opts :keywordize-keys true)
        st (store/mem-store)
        value (welcome-value st)
        h (v/hash value)
        links (link/links)
        r (responder/responder (merge {:store st :now #(.now js/Date)}
                                      (link/responder-opts links)))
        log (fn [s] (js/console.error s))]
    (js/setInterval #(responder/tick! r) 25)
    (join! {:ws ws :room room :token token :links links :r r :log log} 1)
    (clj->js {:value (store/hash->hex h)
              :entries (count (gc/mark-reachable st h))})))
