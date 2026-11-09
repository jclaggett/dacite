(ns dacite.examples.browser-web
  "Value Browser page (M6.4).

   Compile from the repo root:

     npm run browser:build

   The example server mounts `examples/web` at `/app/`, so the local page
   is http://127.0.0.1:8080/app/browser/ . The intended public path is
   https://dacite.io/browser . This page does not deploy that host.

   The field takes `dacite://<host>#urn:dacite:<hex>`, or `dacite://<host>`
   together with `urn:dacite:<hex>`. The host is the operator's name for
   the peer. The fragment is the content name. It is not requested as an
   HTTP path.

   Introduce is a separate step: the slice-3 room (`signal` and `room`)
   or a pasted answer. Fetch then does one peer-v1 `get` of that hash.
   A later paste of another hash for the same name reuses the channel
   when it is still up. Entries are verified before they are stored.
   The page shows the hash, the entry count, and the verification status."
  (:require [clojure.string :as str]
            [dacite.examples.browser :as uri]
            [dacite.peer.client :as client]
            [dacite.peer.link :as link]
            [dacite.peer.msg :as msg]
            [dacite.peer.transport :as t]
            [dacite.peer.transport.browser :as rtc]
            [dacite.peer.verify :as verify]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.store.idb :as idb]
            [dacite.value.types :as types]))

(def db-name
  "IndexedDB database for entries this page has verified."
  "dacite-value-browser")

(def ^:private error-text
  {:empty "paste a dacite:// URI"
   :not-dacite "the peer is dacite://<host>"
   :has-path "the value is the fragment, urn:dacite:<hex>"
   :has-port "the host is a name"
   :has-user "the host is a name"
   :has-query "the host is a name"
   :bad-host "the host is a name"
   :bad-urn "the value is urn:dacite:<hex>"
   :algorithm "the URN carries no algorithm name"
   :mismatch "the fragment and the URN differ"})

(defonce !ui
  (atom {:store nil
         :client nil
         :links nil
         :rtc nil
         :timer nil
         :errors nil
         :host nil
         :connects 0
         :gets 0
         :phase :idle
         :busy? false}))

(defn- by-id [id]
  (.getElementById js/document id))

(defn- set-text! [id s]
  (when-let [el (by-id id)]
    (set! (.-textContent el) (str s))))

(defn- set-attr! [id k v]
  (when-let [el (by-id id)]
    (.setAttribute el k (str v))))

(defn- field [id]
  (let [el (by-id id)]
    (if el (str/trim (or (.-value el) "")) "")))

(defn- err-text [e]
  (let [reason (:reason (ex-data e))
        msg (or (ex-message e) (.-message e) (str e))]
    (if reason (str msg " (" (name reason) ")") msg)))

(defn- reason-text [reason]
  (or (error-text reason) (name reason)))

(defn- render-counts! []
  (let [{:keys [connects gets]} @!ui]
    (set-attr! "status" "data-connects" connects)
    (set-attr! "status" "data-gets" gets)))

(defn- show-name! [{:keys [host fragment from] hex :hash}]
  (set-text! "peer" (or host "—"))
  (set-attr! "peer" "data-name" (or host ""))
  (set-text! "hash" (or hex "—"))
  (set-attr! "hash" "data-hash" (or hex ""))
  (set-attr! "hash" "data-from" (if from (name from) ""))
  (set-text! "fragment" (or fragment "—")))

(defn- clear-result! []
  (set-text! "entries" "—")
  (set-text! "verified" "—")
  (set-attr! "entries" "data-count" "")
  (set-attr! "verified" "data-count" ""))

(defn- query-param
  "A non-empty query value, or nil. Used for the room and the relay
   only. The value hash is never taken from the page URL."
  [k]
  (let [v (.get (js/URLSearchParams. (.-search js/location)) k)]
    (when (and (string? v) (pos? (count (str/trim v))))
      (str/trim v))))

(defn- prefill! []
  (doseq [id ["signal" "room"]]
    (when-let [v (query-param id)]
      (when-let [el (by-id id)]
        (set! (.-value el) v)))))

(defn- poll! [pred ms limit]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(step [i]
               (try
                 (cond
                   (pred) (resolve i)
                   (>= i limit)
                   (reject (ex-info "timed out" {:reason :timeout :steps i}))
                   :else (js/setTimeout #(step (inc i)) ms))
                 (catch :default e (reject e))))]
       (step 0)))))

(defn- tap-channel
  "Deliver each frame to `f`, then to the peer client."
  [ch f]
  (reify t/IChannel
    (channel-name [_] (t/channel-name ch))
    (send! [_ bs] (t/send! ch bs))
    (on-message! [_ g]
      (t/on-message! ch (fn [bs]
                          (f bs)
                          (g bs))))
    (on-close! [_ g] (t/on-close! ch g))
    (buffered-amount [_] (t/buffered-amount ch))
    (max-message-size [_] (t/max-message-size ch))
    (max-frame-limit [_] (t/max-frame-limit ch))
    (channel-open? [_] (t/channel-open? ch))
    (close! [_] (t/close! ch))))

(defn- note-incoming!
  "Record a non-fatal `error` code for the hash on that stream. The
   code is the one peer-v1 already sends (`unknown-hash` and the rest).
   Item frames are ignored."
  [c peer-id errors bs]
  (when (and bs (pos? (.-length bs)) (= 7 (aget bs 0)))
    (try
      (let [{:keys [type stream value]} (msg/decode-message bs)]
        (when (and (= type :error)
                   (number? stream)
                   (pos? stream)
                   (not (get value "fatal")))
          (when-let [h (get-in @(:state c) [:sessions peer-id :streams stream :hash])]
            (swap! errors assoc (gc/hash-key h) (get value "code")))))
      (catch :default _ nil))))

(defn- check-reachable!
  "How many nodes are reachable from `root`. Each one must already have
   passed verification. The walk is `s-get` and `child-hashes`, the same
   set the nbb peer counts with `gc/mark-reachable`."
  [st root]
  (letfn [(step [queue live n]
            (if (pos? (count queue))
              (let [h (nth queue 0)
                    queue (subvec queue 1)
                    hk (gc/hash-key h)]
                (if (or (nil? h) (contains? live hk))
                  (step queue live n)
                  (chain/then
                   (verify/checked? st h)
                   (fn [ok]
                     (when-not ok
                       (throw (ex-info "an entry was stored without passing verification"
                                       {:reason :bad-entry :hash hk})))
                     (chain/then
                      (store/s-get st h)
                      (fn [node]
                        (when (nil? node)
                          (throw (ex-info "a reachable entry is missing"
                                          {:reason :missing :hash hk})))
                        (step (into queue
                                    (remove nil?)
                                    (or (types/child-hashes node) []))
                              (conj live hk)
                              (inc n))))))))
              n))]
    (step [root] #{} 0)))

(defn- hello? [c host]
  (some? (get-in @(:state c) [:sessions host :hello])))

(defn- session-up? [host]
  (let [{:keys [client phase]} @!ui]
    (and (= phase :open)
         (= host (:host @!ui))
         (some? client)
         (= :open (client/session-status client host))
         (hello? client host))))

(defn- drop-channel! []
  (let [{:keys [timer rtc client host]} @!ui]
    (when timer (js/clearInterval timer))
    (when (and client host)
      (try (client/close-session! client host) (catch :default _ nil)))
    (when (and rtc (:conn rtc))
      (try
        (when (t/connection-open? (:conn rtc))
          (t/close-connection! (:conn rtc)))
        (catch :default _ nil))))
  (swap! !ui assoc :timer nil :rtc nil :client nil :links nil :errors nil
         :host nil :phase :idle)
  (render-counts!))

(defn- fail! [e]
  (when (= :connecting (:phase @!ui))
    (drop-channel!))
  (let [data (ex-data e)
        code (:code data)
        reason (:reason data)
        msg (ex-message e)
        shown (cond
                code code
                (and (string? msg) (pos? (count msg))) msg
                reason (name reason)
                :else (err-text e))]
    (set-attr! "status" "data-code" (or code (if reason (name reason) "failed")))
    (set-text! "status" (str "failed: " shown))
    (js/console.log "status" (str "failed: " shown))))

(defn- attach! [host]
  (let [{:keys [links client rtc errors]} @!ui
        opened (t/open-channel! (:conn rtc) t/peer-channel)]
    (-> (js/Promise.resolve opened)
        (.then (fn [ch]
                 (link/attach-client!
                  links client host
                  (tap-channel ch #(note-incoming! client host errors %))
                  :offerer)
                 (poll! #(hello? client host) 25 800)))
        (.then (fn [_]
                 (swap! !ui assoc :phase :open :host host)
                 (render-counts!)
                 :open)))))

(defn- open-room! [host signal room]
  (let [st (:store @!ui)
        links (link/links)
        errors (atom {})
        c (client/client (merge {:store st :now #(.now js/Date)}
                                (link/client-opts links)))
        timer (js/setInterval #(client/tick! c) 25)
        rtc-peer (rtc/create-peer :offerer {:ice-servers []
                                            :ws {:url signal :room room}})]
    (swap! !ui assoc
           :links links
           :client c
           :errors errors
           :timer timer
           :rtc rtc-peer
           :host host
           :phase :connecting
           :connects (inc (:connects @!ui)))
    (render-counts!)
    (set-text! "status" "connecting…")
    (-> (:signaling rtc-peer)
        (.then (fn [_] (attach! host))))))

(defn- read-signal [text]
  (let [v (js->clj (js/JSON.parse text) :keywordize-keys true)]
    (if (map? v)
      v
      (throw (ex-info "the pasted answer is not a signal map"
                      {:reason :bad-signaling})))))

(defn- open-pasted! [host answer-text]
  (let [rtc-peer (:rtc @!ui)
        remote (read-signal answer-text)
        st (:store @!ui)
        links (link/links)
        errors (atom {})
        c (client/client (merge {:store st :now #(.now js/Date)}
                                (link/client-opts links)))
        timer (js/setInterval #(client/tick! c) 25)]
    (swap! !ui assoc
           :links links
           :client c
           :errors errors
           :timer timer
           :host host
           :phase :connecting)
    (render-counts!)
    (set-text! "status" "connecting…")
    (-> ((:set-remote! rtc-peer) remote)
        (.then (fn [_] (attach! host))))))

(defn- room-filled? []
  (let [signal (field "signal")
        room (field "room")]
    (when (and (pos? (count signal)) (pos? (count room)))
      [signal room])))

(defn- ensure-channel! [host]
  (cond
    (session-up? host)
    (js/Promise.resolve :reused)

    (and (= :open (:phase @!ui))
         (:host @!ui)
         (not= host (:host @!ui)))
    (js/Promise.reject
     (ex-info "the open channel names a different peer" {:reason :other-peer}))

    (= :connecting (:phase @!ui))
    (js/Promise.reject (ex-info "the channel is still opening" {:reason :busy}))

    (= :offering (:phase @!ui))
    (js/Promise.reject (ex-info "the offer is not ready" {:reason :busy}))

    (= :have-offer (:phase @!ui))
    (let [answer (field "answer")]
      (if (pos? (count answer))
        (open-pasted! host answer)
        (js/Promise.reject
         (ex-info "paste the answer" {:reason :introduce}))))

    (room-filled?)
    (let [[signal room] (room-filled?)]
      (open-room! host signal room))

    :else
    (js/Promise.reject
     (ex-info "introduce the peer with a room token, or paste the answer"
              {:reason :introduce}))))

(defn- fetch-hash! [host hex]
  (let [c (:client @!ui)
        h (store/hex->hash hex)
        errors (:errors @!ui)]
    (when errors (swap! errors dissoc hex))
    (-> (chain/native (client/request! c h))
        (.then (fn [outcome]
                 (when-not (#{:requested :done} outcome)
                   (throw (ex-info "the get was not sent" {:reason :unserved})))
                 (when (= :requested outcome)
                   (swap! !ui update :gets inc)
                   (render-counts!))
                 (poll! (fn []
                          (when (= :ended (client/session-status c host))
                            (throw (ex-info "the session ended"
                                            {:reason :session-ended})))
                          (when-let [code (when errors (get @errors hex))]
                            (throw (ex-info code {:reason :peer-error :code code})))
                          (client/done? c h))
                        25 800)))
        (.then (fn [_]
                 (chain/native (check-reachable! (:store @!ui) h))))
        (.then (fn [n]
                 (set-text! "entries" n)
                 (set-text! "verified" n)
                 (set-attr! "entries" "data-count" n)
                 (set-attr! "verified" "data-count" n)
                 (set-attr! "status" "data-code" "verified")
                 (set-text! "status" "verified"))))))

(defn- parse-field! []
  (let [parsed (uri/parse-value-uri (field "uri") (field "urn"))]
    (if-let [err (:error parsed)]
      (throw (ex-info (reason-text err) {:reason err}))
      parsed)))

(defn- go-open! []
  (let [parsed (parse-field!)]
    (when (str/blank? (:host parsed))
      (throw (ex-info "the peer is dacite://<host>" {:reason :bad-host})))
    (show-name! (if (:hash parsed) parsed (assoc parsed :hash nil)))
    (-> (ensure-channel! (:host parsed))
        (.then (fn [_]
                 (set-attr! "status" "data-code" "open")
                 (set-text! "status" "channel open"))))))

(defn- go-fetch! []
  (let [parsed (parse-field!)]
    (when-not (:hash parsed)
      (throw (ex-info "the URI needs a value URN" {:reason :missing-urn})))
    (show-name! parsed)
    (clear-result!)
    (set-attr! "status" "data-code" "")
    (set-text! "status" "reading…")
    (-> (ensure-channel! (:host parsed))
        (.then (fn [_] (fetch-hash! (:host parsed) (:hash parsed)))))))

(defn- run-ui! [f]
  (when-not (:busy? @!ui)
    (swap! !ui assoc :busy? true)
    (-> (js/Promise.resolve)
        (.then f)
        (.catch fail!)
        (.then (fn [] (swap! !ui assoc :busy? false))))))

(defn- signal-json [peer]
  (js/JSON.stringify (clj->js @(:signal peer)) nil 2))

(defn- prepare-offer! []
  (cond
    (:rtc @!ui)
    (fail! (ex-info "a channel is already started" {:reason :busy}))

    :else
    (let [rtc-peer (rtc/create-peer :offerer {:ice-servers []})]
      (swap! !ui assoc :rtc rtc-peer :phase :offering
             :connects (inc (:connects @!ui)))
      (render-counts!)
      (set-text! "status" "gathering the offer…")
      (-> (poll! (fn []
                   (let [s @(:signal rtc-peer)]
                     (and (:description s) (:complete? s))))
                 25 800)
          (.then (fn [_]
                   (when-let [el (by-id "offer")]
                     (set! (.-value el) (signal-json rtc-peer)))
                   (swap! !ui assoc :phase :have-offer)
                   (set-attr! "status" "data-code" "offer")
                   (set-text! "status" "paste the answer")))
          (.catch (fn [e]
                    (drop-channel!)
                    (fail! e)))))))

(defn start! []
  (set-text! "status" "loading…")
  (render-counts!)
  (prefill!)
  (let [st (idb/idb-store db-name)]
    (swap! !ui assoc :store st)
    (-> (store/s-reset st)
        (.then (fn [_]
                 (set-attr! "status" "data-code" "")
                 (set-text! "status" "paste a peer")))
        (.catch fail!))))

(defonce started? (atom false))

(defn init
  "Page entry. shadow-cljs calls this when the script loads."
  []
  (when (compare-and-set! started? false true)
    (when-let [form (by-id "ask")]
      (.addEventListener form "submit"
                         (fn [e]
                           (.preventDefault e)
                           (run-ui! go-fetch!))))
    (when-let [open (by-id "open")]
      (.addEventListener open "click" (fn [e]
                                        (.preventDefault e)
                                        (run-ui! go-open!))))
    (when-let [prepare (by-id "prepare")]
      (.addEventListener prepare "click" (fn [e]
                                           (.preventDefault e)
                                           (prepare-offer!))))
    (start!)))
