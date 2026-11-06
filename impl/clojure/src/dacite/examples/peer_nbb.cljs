(ns dacite.examples.peer-nbb
  "nbb peer that holds a value the origin does not (M5.2).

   From the repo root, with the slice-3 relay already listening:

     nbb -m dacite.examples.peer-nbb --ws ws://127.0.0.1:8787 --room m52-manual

   This process is the WebRTC answerer. The browser is the offerer (odd
   stream ids) and creates `dacite-peer` in-band. The room token is the
   manual signaling step. There is no origin discovery.

   Prints the root hash and how many nodes are reachable from it. The
   browser is given that hash out of band and must read the bytes from
   this peer. On `dacite-roots` each side sends `probe-frames` messages
   of `probe-bytes` and this process prints the largest `bufferedAmount`
   it saw while sending them. The responder also counts how many times
   it read `bufferedAmount` while serving the value (the 4/1 marks).

   `--page` is the browser page without a query (default
   http://127.0.0.1:8080/app/peer/). The process prints a URL that opens
   it against this peer."
  (:require [clojure.string :as str]
            [dacite.peer.frame :as frame]
            [dacite.peer.link :as link]
            [dacite.peer.responder :as responder]
            [dacite.peer.transport :as t]
            [dacite.peer.transport.webrtc :as webrtc]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.value :as v]))

(def marker
  "A string the canonical parity value does not contain, so this root
   cannot be the origin's root."
  "m5.2-only-on-the-nbb-peer")

(def peer-id "browser")

(defn held-value
  "The value this peer holds and the origin does not."
  [st]
  (v/vector st marker
            (v/map st "via" "nbb" "milestone" "M5.2")
            (v/set st 1 2 3)))

(defn- opt [args flag]
  (loop [a args]
    (when-let [x (first a)]
      (if (= x flag) (second a) (recur (next a))))))

(defn- payload []
  (let [b (js/Uint8Array. t/probe-bytes)]
    (dotimes [i t/probe-bytes]
      (aset b i (bit-and (* i 17) 255)))
    b))

(defn- when-drained [ch f]
  (letfn [(step [left]
            (let [n (t/buffered-amount ch)]
              (if (or (zero? n) (not (pos? left)))
                (f n)
                (js/setTimeout #(step (dec left)) 20))))]
    (step 100)))

(defn- watch-roots!
  "When the browser has sent `probe-frames` messages, send the same
   number back and print the buffered-amount sample."
  [conn]
  (let [got (atom 0)
        sent? (atom false)]
    (t/on-channel!
     conn t/roots-channel
     (fn [ch]
       (t/on-message!
        ch
        (fn [_bs]
          (swap! got inc)
          (when (and (= @got t/probe-frames) (not @sent?))
            (reset! sent? true)
            (let [buf (payload)
                  max-b (atom 0)]
              (dotimes [_ t/probe-frames]
                (t/send! ch buf)
                (swap! max-b max (t/buffered-amount ch)))
              (println "flow-sent" t/probe-frames)
              (println "flow-max" @max-b)
              (println "flow-high"
                       (if (>= @max-b (* responder/high-frames frame/webrtc-max-frame))
                         1
                         0))
              (when-drained ch (fn [n] (println "flow-drained" n)))))))))))

(defn- page-url [base root-hex ws room]
  (str (str/replace base #"/+$" "")
       "/?root=" root-hex
       "&signal=" (js/encodeURIComponent ws)
       "&room=" room))

(defn -main
  [& args]
  (let [ws (opt args "--ws")
        room (opt args "--room")
        page (or (opt args "--page") "http://127.0.0.1:8080/app/peer/")]
    (when-not (and (string? ws) (string? room) (pos? (count room)))
      (println "usage: nbb -m dacite.examples.peer-nbb --ws ws://127.0.0.1:8787 --room TOKEN")
      (js/process.exit 1))
    (let [st (store/mem-store)
          value (held-value st)
          root (v/hash value)
          root-hex (store/hash->hex root)
          entries (count (gc/mark-reachable st root))
          links (link/links)
          base (link/responder-opts links)
          saw-item (atom false)
          printed (atom false)
          max-b (atom 0)
          reads (atom 0)
          opts (assoc base
                      :send! (fn [peer bs]
                               (when (and (pos? (.-length bs))
                                          (= 3 (aget bs 0))
                                          (not @saw-item))
                                 (reset! saw-item true)
                                 (println "served"))
                               ((:send! base) peer bs))
                      :buffered (fn [peer sid]
                                  (let [n ((:buffered base) peer sid)]
                                    (swap! reads inc)
                                    (swap! max-b max n)
                                    n)))
          r (responder/responder (merge {:store st :now #(.now js/Date)} opts))
          peer (webrtc/create-peer :answerer {:ws {:url ws :room room}})]
      (when-not (> entries 1)
        (println "the held value must be more than one entry")
        (js/process.exit 1))
      (println "root" root-hex)
      (println "entries" entries)
      (println "page" (page-url page root-hex ws room))
      (link/serve! links r peer-id (:conn peer))
      (watch-roots! (:conn peer))
      (-> (:signaling peer)
          (.then (fn [_] (println "signaling-up")))
          (.catch (fn [e]
                    (js/console.error "signaling-failed" (or (ex-message e) (.-message e) e))
                    (js/process.exit 1))))
      (js/setInterval
       (fn []
         (responder/tick! r)
         (when (and @saw-item
                    (not @printed)
                    (empty? (responder/open-streams r peer-id)))
           (reset! printed true)
           (println "peer-buffered-max" @max-b)
           (println "peer-buffered-reads" @reads)))
       25)
      nil)))
