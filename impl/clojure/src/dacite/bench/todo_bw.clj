(ns dacite.bench.todo-bw
  "Benchmark suite for store-protocol bandwidth of todo seed / add / reload.

   Scenarios (fixed):
     :seed-cold   — empty server root; build seed list; CAS root; realize entries
     :add-warm    — same client after seed; add one todo; CAS; realize new title
     :reload-cold — new client (empty cache); GET root + materialize todos

   Primary metrics: :requests :bytes-sent :bytes-recv (dacite.store.stats).

   Run:
     cd impl/clojure && clojure -M:dev -m dacite.bench.todo-bw --policy none
     clojure -M:dev -m dacite.bench.todo-bw --policy smart-put --out results.edn"
  (:require [clojure.pprint :as pp]
            [dacite.service :as svc]
            [dacite.store.stats :as stats]
            [dacite.store.remote :as remote]
            [dacite.store.client-cache :as client-cache]
            [dacite.examples.todo :as todo]
            [dacite.value.api :as d]
            [dacite.value.types :as types])
  (:gen-class))

(def scenario-names
  [:seed-cold :add-warm :reload-cold])

(defn- primary [delta]
  (select-keys delta [:requests :bytes-sent :bytes-recv]))

(defn- suite-totals [scenario-map]
  (reduce (fn [acc [_sc m]]
            (-> acc
                (update :requests + (:requests m 0))
                (update :bytes-sent + (:bytes-sent m 0))
                (update :bytes-recv + (:bytes-recv m 0))))
          {:requests 0 :bytes-sent 0 :bytes-recv 0}
          scenario-map))

(defn make-client
  "Remote store (+ optional client-cache policy) against base-url."
  [base-url policy]
  (client-cache/wrap (remote/remote-store base-url) policy))

(defn- realize-todos!
  "Touch fields like the UI does when rendering."
  [todos]
  (dotimes [i (d/count todos)]
    (let [t (d/nth todos i)]
      (todo/title-str t)
      (todo/done? t)))
  todos)

(defn run-scenarios
  "Run the fixed suite against a live service at base-url with policy.
   Returns {:scenarios {name metrics} :totals metrics :policy p}."
  [base-url policy]
  (let [results (atom {})
        client (make-client base-url policy)
        !root (atom nil)]

    ;; --- seed-cold ---
    (stats/reset-stats!)
    (let [delta
          (:delta
           (stats/measure
            (fn []
              (let [seeded (todo/build client (todo/seed-items))
                    h (types/dacite-hash seeded)
                    ok (remote/remote-cas-root! client nil h)]
                (when-not ok
                  (throw (ex-info "seed CAS failed" {})))
                (reset! !root h)
                (realize-todos! seeded)
                seeded))))]
      (swap! results assoc :seed-cold (primary delta)))

    ;; --- add-warm (same client / cache) ---
    (stats/reset-stats!)
    (let [delta
          (:delta
           (stats/measure
            (fn []
              (let [root @!root
                    todos (d/get-value client root)
                    todos' (todo/add-todo todos "bw bench item" false)
                    h (types/dacite-hash todos')
                    ok (remote/remote-cas-root! client root h)]
                (when-not ok
                  (throw (ex-info "add CAS failed" {})))
                (reset! !root h)
                (todo/title-str (d/nth todos' (dec (d/count todos'))))
                todos'))))]
      (swap! results assoc :add-warm (primary delta)))

    ;; --- reload-cold (fresh client) ---
    (stats/reset-stats!)
    (let [cold (make-client base-url policy)
          root @!root
          delta
          (:delta
           (stats/measure
            (fn []
              (let [h (or (remote/remote-get-root cold)
                          (throw (ex-info "missing root" {})))
                    todos (d/get-value cold h)]
                (realize-todos! todos)
                todos))))]
      (swap! results assoc :reload-cold (primary delta)))

    {:policy policy
     :scenarios @results
     :totals (suite-totals @results)}))

(defn improvement
  "Fraction improved: (prev-next)/prev. Nil if prev is 0."
  [prev next]
  (when (and prev (pos? prev))
    (double (/ (- prev next) prev))))

(defn compare-rounds
  "Map of scenario -> metric -> improvement fraction (prev vs next)."
  [prev-round next-round]
  (into {}
        (for [sc scenario-names]
          [sc (into {}
                    (for [m [:requests :bytes-sent :bytes-recv]]
                      [m (improvement (get-in prev-round [:scenarios sc m])
                                      (get-in next-round [:scenarios sc m]))]))])))

(defn all-improvements-below?
  "True if every scenario×metric improvement is strictly < 0.5 (or nil)."
  [cmp]
  (every? (fn [[_sc metrics]]
            (every? (fn [[_m imp]]
                      (or (nil? imp) (< imp 0.5)))
                    metrics))
          cmp))

(defn no-regression?
  "Suite totals for next are all ≤ prev on primary metrics."
  [prev next]
  (let [p (:totals prev)
        n (:totals next)]
    (and (<= (:requests n) (:requests p))
         (<= (:bytes-sent n) (:bytes-sent p))
         (<= (:bytes-recv n) (:bytes-recv p)))))

(defn run-with-server
  "Start ephemeral mem service, run suite for policy, stop. Returns result map."
  ([policy] (run-with-server policy nil))
  ([policy _opts]
   (let [rooted (svc/make-demo-rooted)
         {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
     (try
       (run-scenarios base-url policy)
       (finally
         (stop!))))))
(defn- parse-args [args]
  (loop [args args
         acc {:policy :none
              :out nil
              :label "run"}]
    (if-let [a (first args)]
      (case a
        "--policy" (recur (nnext args) (assoc acc :policy (keyword (second args))))
        "--out" (recur (nnext args) (assoc acc :out (second args)))
        "--label" (recur (nnext args) (assoc acc :label (second args)))
        (recur (next args) acc))
      acc)))

(defn -main [& args]
  (let [{:keys [policy out label]} (parse-args args)
        result (assoc (run-with-server policy) :label label)
        edn-str (with-out-str (pp/pprint result))]
    (print edn-str)
    (flush)
    (when out
      (spit out edn-str)
      (println "Wrote" out))
    (System/exit 0)))
