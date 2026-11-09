(ns dacite.examples.browser-nbb
  "nbb peer the Value Browser reads (M6.4).

   From the repo root, with the slice-3 relay already listening:

     nbb -m dacite.examples.browser-nbb --ws ws://127.0.0.1:8787 --room m64

   This process is the WebRTC answerer. The browser is the offerer and
   creates `dacite-peer` in-band. The room token is the manual step.
   The process does not learn the operator's `dacite://` name, and it
   does not publish a signaling URL.

   It holds two values and prints each root and reachable entry count,
   plus one hash it does not hold. Paste
   `dacite://<name>#urn:dacite:<hex>` into the page. A second hash is
   another `get` on the same channel. The absent hash is answered
   `unknown-hash`.

   `--page` is the browser page without a query (default
   http://127.0.0.1:8080/app/browser/). The printed URL carries the
   relay and the room, not a hash.

   `--sdp` reads a pasted offer (one JSON signal map on stdin) and
   prints the answer. No room relay in that mode."
  (:require [clojure.string :as str]
            [dacite.peer.link :as link]
            [dacite.peer.msg :as msg]
            [dacite.peer.responder :as responder]
            [dacite.peer.transport :as t]
            [dacite.peer.transport.webrtc :as webrtc]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.value :as v]))

(def peer-id
  "The responder's name for the browser connection. This is not the
   `dacite://` host. The host is the operator's name and stays on the page."
  "browser")

(def absent-hex
  "64 hex chars this process does not store. Checked before it is printed."
  (apply str (repeat 64 "a")))

(defn value-a
  "The first value. A vector, a map, and a set, so the entry count is
   more than the root."
  [st]
  (v/vector st "m6.4-value-a"
            (v/map st "via" "nbb" "milestone" "M6.4")
            (v/set st 1 2 3)))

(defn value-b
  "The second value. A different shape, so its entry count is not the
   first value's count."
  [st]
  (v/map st
         "second" "m6.4-value-b"
         "nested" (v/vector st "one" "two")))

(defn- opt [args flag]
  (loop [a args]
    (when-let [x (first a)]
      (if (= x flag) (second a) (recur (next a))))))

(defn- flag? [args flag]
  (boolean (some #(= % flag) args)))

(defn- page-url [base ws room]
  (str (str/replace base #"/+$" "")
       "/?signal=" (js/encodeURIComponent ws)
       "&room=" (js/encodeURIComponent room)))

(defn- tap-channel
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

(defn- note-get!
  "Print the hash of each `get`. The browser's second paste is another
   line. Item frames are not control messages."
  [bs]
  (when (and bs (pos? (.-length bs)) (= 2 (aget bs 0)))
    (try
      (let [{:keys [type value]} (msg/decode-message bs)
            h (get value "hash")]
        (when (= type :get)
          (println "asked" (cond
                             (string? h) h
                             (msg/hash-ref? h) (msg/hash-ref-hex h)
                             :else ""))))
      (catch :default e
        (js/console.error "asked" (or (ex-message e) (.-message e) e))))))

(defn- serve-browser! [links r conn]
  (t/on-channel!
   conn t/peer-channel
   (fn [ch]
     (link/attach-responder! links r peer-id (tap-channel ch note-get!)))))

(defn- hold []
  (let [st (store/mem-store)
        a (value-a st)
        b (value-b st)
        ha (v/hash a)
        hb (v/hash b)
        hex-a (store/hash->hex ha)
        hex-b (store/hash->hex hb)
        entries-a (count (gc/mark-reachable st ha))
        entries-b (count (gc/mark-reachable st hb))]
    (when (or (<= entries-a 1) (<= entries-b 1) (= entries-a entries-b))
      (println "the two values need distinct counts above one")
      (js/process.exit 1))
    (when (or (= absent-hex hex-a)
              (= absent-hex hex-b)
              (store/await (store/s-has? st (store/hex->hash absent-hex))))
      (println "absent hash is held")
      (js/process.exit 1))
    {:store st
     :hex-a hex-a
     :hex-b hex-b
     :entries-a entries-a
     :entries-b entries-b}))

(defn- print-held! [{:keys [hex-a hex-b entries-a entries-b]}]
  (println "value-a" hex-a)
  (println "entries-a" entries-a)
  (println "value-b" hex-b)
  (println "entries-b" entries-b)
  (println "absent" absent-hex))

(defn- start-responder [st conn]
  (let [links (link/links)
        r (responder/responder (merge {:store st :now #(.now js/Date)}
                                      (link/responder-opts links)))]
    (serve-browser! links r conn)
    (js/setInterval #(responder/tick! r) 25)
    nil))

(defn- read-stdin! []
  (js/Promise.
   (fn [resolve reject]
     (let [buf (atom "")]
       (.setEncoding js/process.stdin "utf8")
       (.on js/process.stdin "data" #(swap! buf str %))
       (.on js/process.stdin "end" #(resolve @buf))
       (.on js/process.stdin "error" reject)))))

(defn- apply-offer! [peer text]
  (let [other (js->clj (js/JSON.parse text) :keywordize-keys true)]
    ((:set-remote! peer) (assoc other :candidates []))
    (doseq [{:keys [candidate mid]} (:candidates other)]
      (when (and (string? candidate) (pos? (count candidate)))
        (try
          (.addRemoteCandidate (:pc peer) candidate (or mid ""))
          (catch :default _ nil))))
    (webrtc/wait-signal (:signal peer) {:need-complete? true :timeout-ms 20000})))

(defn- run-room! [held-value ws room page]
  (let [peer (webrtc/create-peer :answerer {:ws {:url ws :room room}})]
    (println "page" (page-url page ws room))
    (start-responder (:store held-value) (:conn peer))
    (-> (:signaling peer)
        (.then (fn [_] (println "signaling-up")))
        (.catch (fn [e]
                  (js/console.error "signaling-failed" (or (ex-message e) (.-message e) e))
                  (js/process.exit 1))))))

(defn- run-sdp! [held-value]
  (let [peer (webrtc/create-peer :answerer {})]
    (start-responder (:store held-value) (:conn peer))
    (-> (read-stdin!)
        (.then (fn [text]
                 (when-not (pos? (count (str/trim text)))
                   (println "stdin needs the offer JSON")
                   (js/process.exit 1))
                 (apply-offer! peer text)))
        (.then (fn [_]
                 (println "answer" (js/JSON.stringify (clj->js @(:signal peer))))))
        (.catch (fn [e]
                  (js/console.error "sdp-failed" (or (ex-message e) (.-message e) e))
                  (js/process.exit 1))))))

(defn -main
  [& args]
  (let [ws (opt args "--ws")
        room (opt args "--room")
        page (or (opt args "--page") "http://127.0.0.1:8080/app/browser/")
        sdp? (flag? args "--sdp")
        held-value (hold)]
    (print-held! held-value)
    (cond
      sdp? (run-sdp! held-value)
      (and (string? ws) (string? room) (pos? (count room)))
      (run-room! held-value ws room page)
      :else
      (do
        (println "usage: nbb -m dacite.examples.browser-nbb --ws ws://127.0.0.1:8787 --room TOKEN")
        (println "       nbb -m dacite.examples.browser-nbb --sdp")
        (js/process.exit 1)))
    nil))
