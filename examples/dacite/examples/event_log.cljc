(ns dacite.examples.event-log
  "Append-only event log + derived view.

   **Values** — a ledger:
     {\"log\"  [event …]
      \"view\" {\"size\" n \"credits\" n \"debits\" n \"balance\" n}}

   Events are {\"type\" \"credit\"|\"debit\" \"amount\" n \"note\" s}.
   Append updates the view incrementally. Replay rebuilds the view from
   a prefix via nth/subvec — it does not seq the whole log.

   **Store** — file-rooted or HTTP remote-rooted.

   Run:
     clojure -M:log -- --reset show
     clojure -M:log -- page 0
     clojure -M:log -- append credit 5 coffee
     clojure -M:log -- replay 100
     clojure -M:log -- bench
     bb log --reset show
     npx nbb -m dacite.examples.event-log -- --reset show"
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.value :as v]
            #?@(:org.babashka/nbb [[dacite.store.nbb :as host-store]]
                :cljs []
                :default [])))

;; =============================================================================
;; Values
;; =============================================================================

(def default-seed-n
  "Default number of seed events — large enough that seq-the-whole-log hurts."
  2000)

(def default-page-size 20)

(defn empty-view
  [peer]
  (v/hash-map-via peer
                  "size" 0
                  "credits" 0
                  "debits" 0
                  "balance" 0))

(defn empty-ledger
  [peer]
  (v/hash-map-via peer
                  "log" (v/vector-via peer)
                  "view" (empty-view peer)))

(defn event
  "A credit or debit relative to `peer`."
  ([peer type amount] (event peer type amount ""))
  ([peer type amount note]
   (v/hash-map-via peer
                   "type" (name type)
                   "amount" amount
                   "note" (str note))))

(defn seed-event
  "Deterministic event i: even → credit, odd → debit, amount 1..9."
  [peer i]
  (event peer
         (if (even? i) "credit" "debit")
         (inc (mod i 9))
         (str "e-" i)))

(defn event-type [ev] (v/as-str (v/get ev "type")))
(defn event-amount [ev] (or (v/native (v/get ev "amount")) 0))
(defn event-note [ev] (or (v/as-str (v/get ev "note")) ""))

(defn view-size [view] (or (v/native (v/get view "size")) 0))
(defn view-credits [view] (or (v/native (v/get view "credits")) 0))
(defn view-debits [view] (or (v/native (v/get view "debits")) 0))
(defn view-balance [view] (or (v/native (v/get view "balance")) 0))

(defn apply-event
  "Fold one event into the view. Returns a new view."
  [view ev]
  (let [amt (event-amount ev)
        size (inc (view-size view))
        credits (view-credits view)
        debits (view-debits view)
        balance (view-balance view)]
    (case (event-type ev)
      "credit" (v/hash-map-via view
                               "size" size
                               "credits" (+ credits amt)
                               "debits" debits
                               "balance" (+ balance amt))
      "debit" (v/hash-map-via view
                              "size" size
                              "credits" credits
                              "debits" (+ debits amt)
                              "balance" (- balance amt))
      (throw (ex-info "unknown event type" {:type (event-type ev)})))))

(defn append
  "Conj event and update the view incrementally. O(log n) on the log."
  [ledger ev]
  (let [log (v/conj (v/get ledger "log") ev)]
    (-> ledger
        (v/assoc "log" log)
        (v/assoc "view" (apply-event (v/get ledger "view") ev)))))

(defn build
  "Ledger of n seed events relative to `peer`."
  [peer n]
  (reduce (fn [led i] (append led (seed-event peer i)))
          (empty-ledger peer)
          (range n)))

(defn load-or-seed!
  "Load ledger from a root-ref, or CAS-seed `n` events (default default-seed-n)."
  ([led-ref] (load-or-seed! led-ref default-seed-n))
  ([led-ref n]
   (if-let [prior (v/ref-deref led-ref)]
     [prior false]
     (let [led (build led-ref n)]
       (if (v/ref-cas! led-ref nil led)
         [led true]
         [(v/ref-deref led-ref) false])))))

(defn log-of [ledger] (v/get ledger "log"))
(defn view-of [ledger] (v/get ledger "view"))
(defn log-count [ledger] (v/count (log-of ledger)))

(defn page
  "Page `page-n` (0-based) of the log as a Dacite vector (shared leaves).
   Uses subvec — does not seq the whole log."
  ([ledger] (page ledger 0 default-page-size))
  ([ledger page-n] (page ledger page-n default-page-size))
  ([ledger page-n page-size]
   (let [log (log-of ledger)
         n (v/count log)
         start (* (long page-n) (long page-size))
         end (min n (+ start (long page-size)))]
     (if (>= start n)
       (v/vector-via ledger)
       (v/subvec log start end)))))

(defn replay
  "Rebuild the view from log[0, end). Uses nth, not seq of the whole log."
  ([ledger] (replay ledger (log-count ledger)))
  ([ledger end]
   (let [log (log-of ledger)
         n (min (long end) (v/count log))
         view (reduce (fn [vw i] (apply-event vw (v/nth log i)))
                      (empty-view ledger)
                      (range n))]
     (v/assoc ledger "view" view))))

(defn prefix
  "First `end` events as a Dacite vector (same hash as a freshly built prefix)."
  [ledger end]
  (v/subvec (log-of ledger) 0 end))

(defn node-count
  [v]
  (count (store/s-snapshot (v/dacite-store v))))

(defn measure-append
  "Node-delta of one append at each size in `sizes`. Builds a fresh ledger
   up to (apply max sizes). Deltas should stay small (log n), not grow with n."
  [peer sizes]
  (let [target (apply max sizes)
        want (set sizes)]
    (loop [i 0
           led (empty-ledger peer)
           acc []]
      (if (= i target)
        acc
        (let [before (node-count led)
              led' (append led (seed-event peer i))
              after (node-count led')
              n (inc i)
              acc (if (want n)
                    (conj acc {:n n :delta (- after before)})
                    acc)]
          (recur n led' acc))))))

(defn short-hex [h]
  (when h
    (subs (store/hash->hex h) 0 12)))

(defn render-view [view]
  (str "size:     " (view-size view) "\n"
       "credits:  " (view-credits view) "\n"
       "debits:   " (view-debits view) "\n"
       "balance:  " (view-balance view) "\n"))

(defn render-event [i ev]
  (str "  " i ". " (event-type ev)
       " " (event-amount ev)
       (let [note (event-note ev)]
         (if (str/blank? note) "" (str "  " note)))
       "\n"))

(defn render
  [ledger]
  (str (render-view (view-of ledger))
       "log:      " (log-count ledger) " events\n"
       "root:     " (store/hash->hex (v/dacite-hash ledger)) "\n"))

(defn render-page
  [ledger page-n page-size]
  (let [log (log-of ledger)
        n (v/count log)
        start (* page-n page-size)
        evs (page ledger page-n page-size)
        end (+ start (v/count evs))]
    (str "page " page-n " [" start "," end ") of " n "\n"
         (apply str
                (map-indexed (fn [j ev]
                               (render-event (+ start j) ev))
                             (or (v/seq evs) ())))
         "page-hash: " (short-hex (v/dacite-hash evs)) "\n")))

(defn render-bench
  [samples]
  (str "append bench (nodes added by the last conj at each size)\n"
       (apply str
              (map (fn [{:keys [n delta]}]
                     (str "  n=" n "  +" delta " nodes\n"))
                   samples))))

;; =============================================================================
;; Store
;; =============================================================================

(def default-path
  "target/dacite-log")

#?(:org.babashka/nbb
   (defn open-file
     [path]
     (store/rooted-store (host-store/file-store path)
                         (store/file-root-cell path)))
   :cljs
   (defn open-file
     [_path]
     (throw (js/Error. "event-log/open-file is for JVM/nbb file backends only")))
   :default
   (defn open-file
     [path]
     (store/rooted-store (store/file-store path)
                         (store/file-root-cell path))))

#?(:clj
   (defn open-remote
     [url]
     (store/remote-rooted-store url))
   :default
   (defn open-remote
     [_url]
     (throw (ex-info "remote event-log store is JVM-only (java.net.http)" {}))))

(defn open-mem
  []
  (store/rooted-store (store/mem-store)))

#?(:org.babashka/nbb
   (defn reset-store-dir!
     [path]
     (let [content (host-store/file-store path)]
       (store/s-reset content)
       (store/rc-put! (store/file-root-cell path) nil)
       nil))
   :cljs
   (defn reset-store-dir! [_path] nil)
   :default
   (defn reset-store-dir!
     [path]
     (let [content (store/file-store path)]
       (store/s-reset content)
       (store/rc-put! (store/file-root-cell path) nil)
       nil)))

(defn parse-int
  [s]
  #?(:clj (Long/parseLong (str s))
     :cljs (js/parseInt (str s) 10)))

(defn parse-args
  "CLI: [--path DIR | --url URL] [--reset|-r] [--n N]
        [show|page [P] [SIZE]|append credit|debit AMT [NOTE…]|replay [END]|bench]"
  [args]
  (let [args (->> args (map str) (remove #{"--"}))]
    (loop [args args
           acc {:reset? false
                :path default-path
                :url nil
                :n default-seed-n
                :cmd "show"
                :cmd-args []}]
      (if-not (seq args)
        acc
        (let [a (first args)
              more (rest args)]
          (cond
            (or (= a "--reset") (= a "-r"))
            (recur more (assoc acc :reset? true))

            (= a "--path")
            (recur (rest more) (assoc acc :path (first more)))

            (= a "--url")
            (recur (rest more) (assoc acc :url (first more)))

            (= a "--n")
            (recur (rest more) (assoc acc :n (parse-int (first more))))

            (#{"show" "page" "append" "replay" "bench"} a)
            (assoc acc :cmd a :cmd-args (vec more))

            :else
            (assoc acc :cmd "show" :cmd-args (vec args))))))))

(defn open-store
  [{:keys [url path]}]
  (if url
    (open-remote url)
    (open-file path)))

;; =============================================================================
;; Main
;; =============================================================================

(defn- print! [s]
  (print s)
  (flush))

(defn -main [& args]
  (let [{:keys [reset? path url n cmd cmd-args] :as opts} (parse-args args)]
    (when (and reset? url)
      (throw (ex-info "--reset is for the local file store only" {:url url})))
    (when reset?
      (reset-store-dir! path)
      (println "reset store at" path))
    (if (= cmd "bench")
      (print! (render-bench (measure-append (store/mem-store) [100 500 1000 2000])))
      (let [rs (open-store opts)
            led-ref (v/root-ref rs)
            [_ seeded?] (load-or-seed! led-ref n)]
        (when seeded?
          (println (if url
                     (str "seeded remote at " url " (" n " events)")
                     (str "seeded new store at " path " (" n " events)"))))
        (case cmd
          "show"
          (print! (render (v/ref-deref led-ref)))

          "page"
          (let [p (if (seq cmd-args) (parse-int (first cmd-args)) 0)
                sz (if (next cmd-args) (parse-int (second cmd-args)) default-page-size)]
            (print! (render-page (v/ref-deref led-ref) p sz)))

          "append"
          (let [typ (first cmd-args)
                amt (parse-int (or (second cmd-args)
                                   (throw (ex-info "append requires TYPE AMOUNT" {}))))
                note (str/join " " (drop 2 cmd-args))]
            (when-not (#{"credit" "debit"} typ)
              (throw (ex-info "type must be credit or debit" {:type typ})))
            (print! (render
                     (v/ref-swap! led-ref append
                                  (event led-ref typ amt note)))))

          "replay"
          (let [end (if (seq cmd-args)
                      (parse-int (first cmd-args))
                      (log-count (v/ref-deref led-ref)))]
            (print! (render (v/ref-swap! led-ref replay end)))))))))
