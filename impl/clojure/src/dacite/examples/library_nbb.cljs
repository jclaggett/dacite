(ns dacite.examples.library-nbb
  "nbb peer that holds the seed library (M5.3).

   From the repo root, with the slice-3 relay already listening:

     nbb -m dacite.examples.library-nbb --ws ws://127.0.0.1:8787 --room m53

   This process is the WebRTC answerer. The library page is the offerer
   and creates `dacite-peer` in-band. The room token is the manual step.
   There is no origin discovery and no mDNS.

   The catalog is `dacite.examples.library/seed-library`, the same value
   the JVM origin publishes, so a reader that times out can load that
   root from the origin. Prints the root, the reachable entry count, how
   long building the catalog took (hashing included), and the default
   interest `tau`. While it serves, it prints the `recv-max` of the one
   `get` and how long the answer took.

   `--page` is the library page without a query (default
   http://127.0.0.1:8080/app/library/)."
  (:require [clojure.string :as str]
            [dacite.examples.library :as lib]
            [dacite.peer.link :as link]
            [dacite.peer.rates :as rates]
            [dacite.peer.responder :as responder]
            [dacite.peer.transport.webrtc :as webrtc]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.value :as v]))

(def peer-id "browser")

(defn- opt [args flag]
  (loop [a args]
    (when-let [x (first a)]
      (if (= x flag) (second a) (recur (next a))))))

(defn- mono-us
  "Microseconds from a monotonic clock. `process.hrtime` is [s, ns]."
  []
  (let [h (js/process.hrtime)]
    (+ (* (aget h 0) 1000000) (quot (aget h 1) 1000))))

(defn- page-url [base ws room]
  (str (str/replace base #"/+$" "")
       "/?chapter=0"
       "&signal=" (js/encodeURIComponent ws)
       "&room=" room))

(defn -main
  [& args]
  (let [ws (opt args "--ws")
        room (opt args "--room")
        page (or (opt args "--page") "http://127.0.0.1:8080/app/library/")]
    (when-not (and (string? ws) (string? room) (pos? (count room)))
      (println "usage: nbb -m dacite.examples.library-nbb --ws ws://127.0.0.1:8787 --room TOKEN")
      (js/process.exit 1))
    (let [t0 (mono-us)
          st (store/mem-store)
          catalog (lib/seed-library st)
          root (v/hash catalog)
          build-us (- (mono-us) t0)
          root-hex (store/hash->hex root)
          entries (count (gc/mark-reachable st root))
          book (v/nth (lib/title-index catalog) 0)
          plate (lib/book-image book)
          links (link/links)
          base (link/responder-opts links)
          saw-item (atom false)
          printed (atom false)
          served-at (atom nil)
          opts (assoc base
                      :send! (fn [peer bs]
                               (when (and (pos? (.-length bs))
                                          (= 3 (aget bs 0))
                                          (not @saw-item))
                                 (reset! saw-item true)
                                 (reset! served-at (mono-us))
                                 (println "served"))
                               ((:send! base) peer bs)))
          r (responder/responder (merge {:store st :now #(.now js/Date)} opts))
          peer (webrtc/create-peer :answerer {:ws {:url ws :room room}})]
      (when-not (and (> entries 1) (some? plate))
        (println "the held library must include a plate and more than one entry")
        (js/process.exit 1))
      (println "root" root-hex)
      (println "entries" entries)
      (println "build-us" build-us)
      (println "tau-ms" (get rates/default-interest "tau-ms"))
      (println "page" (page-url page ws room))
      (link/serve! links r peer-id (:conn peer))
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
           (println "recv-max" (get-in @(:state r) [:sessions peer-id :budget]))
           (println "serve-us" (- (mono-us) @served-at))
           (println "streams-open" 0)))
       25)
      nil)))
