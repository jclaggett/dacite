(ns dacite.store.stats
  "Store-protocol bandwidth counters shared by browser XHR and JVM remote.

   Counts request/response body sizes (approx UTF-8 via string length / byte
   array length). Does not count static assets."
  (:require [clojure.string :as str]))

(defn empty-stats
  []
  {:requests 0
   :bytes-sent 0
   :bytes-recv 0
   :by-kind {}})

(defonce ^:private !stats (atom (empty-stats)))

(defn get-stats
  "Snapshot of cumulative store-protocol bandwidth stats."
  []
  @!stats)

(defn reset-stats!
  "Clear cumulative stats."
  []
  (reset! !stats (empty-stats)))

(defn record!
  "Record one store-protocol exchange.
   kind — keyword e.g. :node-get :node-put :root-get :root-cas
   sent / recv — body sizes in bytes (long)."
  [kind sent recv]
  (let [s (long (or sent 0))
        r (long (or recv 0))
        k (or kind :other)]
    (swap! !stats
           (fn [st]
             (-> st
                 (update :requests inc)
                 (update :bytes-sent + s)
                 (update :bytes-recv + r)
                 (update-in [:by-kind k] (fnil + 0) 1))))
    nil))

(defn- kind-diff
  [before-kinds after-kinds]
  (let [keys* (into (set (keys after-kinds)) (keys before-kinds))]
    (reduce (fn [m k]
              (let [d (- (get after-kinds k 0) (get before-kinds k 0))]
                (if (pos? d) (assoc m k d) m)))
            {}
            keys*)))

(defn stats-diff
  "Delta between two stats snapshots (after − before)."
  [before after]
  {:requests (- (:requests after 0) (:requests before 0))
   :bytes-sent (- (:bytes-sent after 0) (:bytes-sent before 0))
   :bytes-recv (- (:bytes-recv after 0) (:bytes-recv before 0))
   :by-kind (kind-diff (:by-kind before {}) (:by-kind after {}))})

(defn measure
  "Run f, return {:result … :delta stats-diff :totals snapshot}."
  [f]
  (let [before (get-stats)
        result (f)
        after (get-stats)]
    {:result result
     :delta (stats-diff before after)
     :totals after}))

(defn format-bytes
  [n]
  (let [n (long (or n 0))]
    (if (< n 1024)
      (str n " B")
      #?(:clj  (format "%.1f KB" (/ n 1024.0))
         :cljs (str (.toFixed (/ n 1024.0) 1) " KB")))))

(defn format-stats
  ([stats] (format-stats stats nil))
  ([stats label]
   (let [up (:bytes-sent stats 0)
         down (:bytes-recv stats 0)
         total (+ up down)
         base (str (:requests stats 0) " req · ↑ " (format-bytes up)
                   " · ↓ " (format-bytes down)
                   " · Σ " (format-bytes total))]
     (if label
       (str base " · last " (format-bytes total) " (" label ")")
       base))))

(defn format-delta
  [delta label]
  (let [up (:bytes-sent delta 0)
        down (:bytes-recv delta 0)
        total (+ up down)]
    (str (format-bytes total)
         " (" (:requests delta 0) " req ↑" (format-bytes up)
         " ↓" (format-bytes down)
         (when label (str " · " label))
         ")")))

(defn classify-url
  "Keyword bucket from HTTP method + URL."
  [method url]
  (let [path (or (second (re-find #"(?:https?://[^/]+)?(/[^?]*)" (str url)))
                 (str url))
        m (str/upper-case (str method))]
    (cond
      (str/includes? path "/root/cas") :root-cas
      (str/includes? path "/root") :root-get
      (and (= "POST" m) (or (= path "/nodes") (.endsWith path "/nodes"))) :nodes-put
      (str/includes? path "/node/")
      (case m
        "GET" :node-get
        "PUT" :node-put
        "HEAD" :node-head
        "DELETE" :node-delete
        :node-other)
      :else :other)))
