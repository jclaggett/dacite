(ns dacite.cli.peer
  "The nbb peer `npx dacite` runs (docs/design/packaging.md, slice 1).

   `bin/dacite.mjs` starts the one-port server (static app code and the
   `/signal` room relay), then calls `start!` with that relay's URL, a
   room it made for this launch, and the launch's `/signal` token, which
   this peer sends as the subprotocol `dacite.token.<token>` like the page
   (docs/design/packaging.md §P1). This peer is the WebRTC answerer
   in that room. The page is the offerer.

   The store is persistent (docs/design/packaging.md §P3, `dacite.cli.store`):
   the platform data dir's `store`, or `--store <dir>`, or in memory with
   `--memory`. The welcome value is put once, into an empty store, so a
   fresh launch has something to fetch. The printed value is the root
   when a root is set, else the welcome value.

   One browser session at a time: the room has one offerer slot. When a
   session's connection closes, the page leaves the room, or signaling
   fails, this peer leaves the room and joins it again for the next page."
  (:require [dacite.cli.store :as cli-store]
            [dacite.peer.link :as link]
            [dacite.peer.responder :as responder]
            [dacite.peer.transport :as t]
            [dacite.peer.transport.webrtc :as webrtc]
            [dacite.rooted :as rooted]
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

(defn hold!
  "Put the welcome value into `content` when `empty?`, and return
   {:value h :entries n}: the hash the peer prints (the root in `cell`
   when one is set, else the welcome value's) and the entries reachable
   from it in `content`. `cell` is a root cell or nil (in memory)."
  [content cell empty?]
  (let [welcome (v/hash (welcome-value (if empty? content (store/mem-store))))
        h (or (some-> cell rooted/rc-get) welcome)]
    {:value h
     :entries (count (gc/mark-reachable content h))}))

(defn open-store!
  "The store for the bin's `store` options (keywordized): `{:memory true}`,
   `{:dir d}`, or `{}` for the default store dir. Returns
   {:engine :dir :content :cell :empty? :close}; `:engine` is `:memory`
   and `:dir` nil in memory. Extra keys go to `dacite.cli.store/open!`."
  [{:keys [memory dir] :as opts}]
  (if memory
    {:engine :memory :dir nil :content (store/mem-store) :cell nil :empty? true
     :close (fn [] nil)}
    (cli-store/open! (or dir (cli-store/default-store-dir))
                     (dissoc opts :memory :dir))))

(defn start!
  "Open the store, hold its value, and answer in `room` on the relay at
   `ws`. Takes and returns JS objects (it is called from `bin/dacite.mjs`):
   `{ws, room, token, store}` in, where `store` is `{memory: true}`,
   `{dir}`, or `{}` (the default store dir); `{value, entries, engine, path,
   close}` out (the value's hex hash, its reachable entry count, the
   engine and store dir for the startup line, and a function that closes
   the store and removes its `LOCK`). When the store is refused (in use,
   or the wrong engine), nothing starts and it returns `{error, exitCode}`
   instead: an error thrown through nbb loses its ex-data."
  [opts]
  (let [{:keys [ws room token store]} (js->clj opts :keywordize-keys true)
        opened (try (open-store! (or store {}))
                    (catch :default e
                      (if-let [code (:dacite/exit (ex-data e))]
                        {:error (ex-message e) :exit-code code}
                        (throw e))))]
    (if (:error opened)
      #js {:error (:error opened) :exitCode (:exit-code opened)}
      (let [{:keys [engine dir content cell empty? close]} opened
            {:keys [value entries]} (try (hold! content cell empty?)
                                         (catch :default e (close) (throw e)))
            links (link/links)
            r (responder/responder (merge {:store content :now #(.now js/Date)}
                                          (link/responder-opts links)))
            log (fn [s] (js/console.error s))
            timer (js/setInterval #(responder/tick! r) 25)]
        (join! {:ws ws :room room :token token :links links :r r :log log} 1)
        (clj->js {:value (store/hash->hex value)
                  :entries entries
                  :engine (name engine)
                  :path dir
                  :close (fn []
                           (js/clearInterval timer)
                           (close))})))))
