(ns dacite.examples.event-log
  "Append-only event log + derived view.

   **Values** — a ledger:
     {\"log\"  [event …]
      \"view\" {\"size\" n \"credits\" n \"debits\" n \"balance\" n}}

   Events are {\"type\" \"credit\"|\"debit\" \"amount\" n \"note\" s}.
   Append updates the view incrementally. Replay rebuilds the view from
   a prefix via nth/slice — it does not seq the whole log.

   **Store** — file-rooted or HTTP remote-rooted.

   Run:
     clojure -M:log -- --reset show
     clojure -M:log -- page 0
     clojure -M:log -- append credit 5 coffee
     clojure -M:log -- replay 100
     clojure -M:log -- bench
     clojure -M:log -- --url http://127.0.0.1:8080 watch
     clojure -M:log -- --url http://127.0.0.1:8080 contend 10
     bb log --reset show
     npx nbb -m dacite.examples.event-log -- --reset show
     npx nbb -m dacite.examples.event-log -- --lmdb --n 50 show"
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.value :as v]
            #?(:clj [dacite.store.remote :as remote])))

;; =============================================================================
;; Values
;; =============================================================================

(def default-seed-n
  "Default number of seed events — large enough that seq-the-whole-log hurts."
  2000)

(def default-page-size 20)

(defn empty-view
  [peer]
  (v/map peer
         "size" 0
         "credits" 0
         "debits" 0
         "balance" 0))

(defn empty-ledger
  [peer]
  (v/map peer
         "log" (v/vector peer)
         "view" (empty-view peer)))

(defn event
  "A credit or debit relative to `peer`."
  ([peer type amount] (event peer type amount ""))
  ([peer type amount note]
   (v/map peer
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

(defn event-type [ev] (v/native (v/get ev "type")))
(defn event-amount [ev] (or (v/native (v/get ev "amount")) 0))
(defn event-note [ev] (or (v/native (v/get ev "note")) ""))

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
      "credit" (v/map view
                      "size" size
                      "credits" (+ credits amt)
                      "debits" debits
                      "balance" (+ balance amt))
      "debit" (v/map view
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
  "Load ledger from a root, or CAS-seed `n` events (default default-seed-n)."
  ([led-ref] (load-or-seed! led-ref default-seed-n))
  ([led-ref n]
   (if-let [prior (v/deref led-ref)]
     [prior false]
     (let [led (build led-ref n)]
       (if (v/cas! led-ref nil led)
         [led true]
         [(v/deref led-ref) false])))))

(defn log-of [ledger] (v/get ledger "log"))
(defn view-of [ledger] (v/get ledger "view"))
(defn log-count [ledger] (v/count (log-of ledger)))

(defn page
  "Page `page-n` (0-based) of the log as a Dacite vector (shared leaves).
   Uses slice — does not seq the whole log."
  ([ledger] (page ledger 0 default-page-size))
  ([ledger page-n] (page ledger page-n default-page-size))
  ([ledger page-n page-size]
   (let [log (log-of ledger)
         n (v/count log)
         start (* (long page-n) (long page-size))
         end (min n (+ start (long page-size)))]
     (if (>= start n)
       (v/vector ledger)
       (v/slice log start end)))))

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
  (v/slice (log-of ledger) 0 end))

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
       "root:     " (store/hash->hex (v/hash ledger)) "\n"))

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
         "page-hash: " (short-hex (v/hash evs)) "\n")))

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

(defn open-mem [] (store/mem))
(defn open-file [path] (store/file path))
(defn open-remote [url] (store/remote url))
(defn open-lmdb [path] (store/lmdb path))

(defn reset-store-dir!
  [path]
  (store/file path {:reset true})
  nil)

(defn reset-lmdb-dir!
  [path]
  (store/lmdb path {:reset true})
  nil)

(defn local-path
  [{:keys [lmdb? path]}]
  (if (and lmdb? (= path default-path))
    (str default-path "-lmdb")
    path))

(defn parse-int
  [s]
  #?(:clj (Long/parseLong (str s))
     :cljs (js/parseInt (str s) 10)))

(defn parse-args
  "CLI: [--path DIR | --url URL] [--lmdb] [--reset|-r] [--n N]
        [show|page|append|replay|bench|watch|contend]"
  [args]
  (let [args (->> args (map str) (remove #{"--"}))]
    (loop [args args
           acc {:reset? false
                :lmdb? false
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

            (= a "--lmdb")
            (recur more (assoc acc :lmdb? true))

            (= a "--path")
            (recur (rest more) (assoc acc :path (first more)))

            (= a "--url")
            (recur (rest more) (assoc acc :url (first more)))

            (= a "--n")
            (recur (rest more) (assoc acc :n (parse-int (first more))))

            (#{"show" "page" "append" "replay" "bench" "watch" "contend"} a)
            (assoc acc :cmd a :cmd-args (vec more))

            :else
            (assoc acc :cmd "show" :cmd-args (vec args))))))))

(defn open-store
  [{:keys [url lmdb?] :as opts}]
  (cond
    url (open-remote url)
    lmdb? (open-lmdb (local-path opts))
    :else (open-file (:path opts))))

;; =============================================================================
;; Main
;; =============================================================================

(defn- print! [s]
  (print s)
  (flush))

(defn watch-sse!
  "Print the ledger whenever GET /events announces a new root."
  [rs]
  #?(:clj
     (do
       (println "watching GET /events (Ctrl-C to stop)…")
       (let [r (v/root rs)]
         (when-let [led (v/deref r)]
           (print! (render led)))
         (let [w (remote/watch-root
                  rs
                  (fn [_h]
                    (if-let [led (v/deref r)]
                      (print! (str "updated:\n" (render led)))
                      (println "root cleared"))))]
           (try
             (loop []
               (Thread/sleep 3600000)
               (recur))
             (finally
               ((:stop! w)))))))
     :default
     (throw (ex-info "SSE watch is JVM-only" {}))))

(defn contend!
  "Two remote clients each append `n` events. Prints retries and final count."
  [url n]
  #?(:clj
     (let [a (v/root (store/remote url))
           b (v/root (store/remote url))]
       (load-or-seed! a 0)
       (let [n0 (log-count (v/deref a))
             retries (atom 0)
             fa (future
                  (dotimes [i n]
                    (let [info (v/swap-info!
                                a append (event a "credit" 1 (str "a-" i)))]
                      (swap! retries + (:retries info)))))
             fb (future
                  (dotimes [i n]
                    (let [info (v/swap-info!
                                b append (event b "debit" 1 (str "b-" i)))]
                      (swap! retries + (:retries info)))))]
         @fa
         @fb
         (let [final (v/deref (v/root (store/remote url)))]
           (println "started:" n0
                    "appended:" (* 2 n)
                    "final:" (log-count final)
                    "cas-retries:" @retries)
           (print! (render final)))))
     :default
     (throw (ex-info "contend is JVM-only" {}))))

(defn- run-cmd!
  [rs led-ref url cmd cmd-args]
  (case cmd
    "show" (print! (render (v/deref led-ref)))
    "page" (let [p (if (seq cmd-args) (parse-int (first cmd-args)) 0)
                 sz (if (next cmd-args) (parse-int (second cmd-args)) default-page-size)]
             (print! (render-page (v/deref led-ref) p sz)))
    "append" (let [typ (first cmd-args)
                   amt (parse-int (or (second cmd-args)
                                      (throw (ex-info "append requires TYPE AMOUNT" {}))))
                   note (str/join " " (drop 2 cmd-args))]
               (when-not (#{"credit" "debit"} typ)
                 (throw (ex-info "type must be credit or debit" {:type typ})))
               (let [info (v/swap-info! led-ref append
                                        (event led-ref typ amt note))]
                 (when (pos? (:retries info))
                   (println "cas retried" (:retries info) "time(s)"))
                 (print! (render (:value info)))))
    "replay" (let [end (if (seq cmd-args)
                         (parse-int (first cmd-args))
                         (log-count (v/deref led-ref)))]
               (print! (render (v/swap! led-ref replay end))))
    "watch" (watch-sse! rs)
    "contend" (do
                (when-not url
                  (throw (ex-info "contend requires --url (two remote clients)" {})))
                (contend! url (if (seq cmd-args) (parse-int (first cmd-args)) 10)))))

(defn -main [& args]
  (let [{:keys [reset? path url lmdb? n cmd cmd-args] :as opts} (parse-args args)
        local (local-path opts)]
    (when (and reset? url)
      (throw (ex-info "--reset is for the local store only" {:url url})))
    (when reset?
      (if lmdb?
        (reset-lmdb-dir! local)
        (reset-store-dir! path))
      (println "reset store at" local))
    (if (= cmd "bench")
      (print! (render-bench (measure-append (store/mem) [100 500 1000 2000])))
      (let [rs (open-store opts)
            led-ref (v/root rs)
            [_ seeded?] (load-or-seed! led-ref n)]
        (when lmdb?
          (println "lmdb" local))
        (when seeded?
          (println (if url
                     (str "seeded remote at " url " (" n " events)")
                     (str "seeded new store at " local " (" n " events)"))))
        (run-cmd! rs led-ref url cmd cmd-args)))))
