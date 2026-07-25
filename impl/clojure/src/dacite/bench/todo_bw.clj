(ns dacite.bench.todo-bw
  "Benchmark suite for store-protocol bandwidth of todo seed / add / reload.

   Scenarios (fixed):
     :seed-cold   — empty server root; build seed list; CAS root; realize entries
     :add-warm    — same client after seed; add one todo; CAS; realize new title
     :reload-cold — new client (empty cache); GET root + materialize todos

   Primary metrics: :requests :bytes-sent :bytes-recv (dacite.store.stats).

   Leaf-chunking 2d — budget sweep:
     clojure -M:dev -m dacite.bench.todo-bw --budget-sweep
     clojure -M:dev -m dacite.bench.todo-bw --budget-sweep --out target/budget-sweep.edn

   Other runs:
     cd impl/clojure && clojure -M:dev -m dacite.bench.todo-bw --policy none
     clojure -M:dev -m dacite.bench.todo-bw --policy smart-put --out results.edn"
  (:require [clojure.pprint :as pp]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.store.stats :as stats]
            [dacite.store.remote :as remote]
            [dacite.store.client-cache :as client-cache]
            [dacite.examples.todo :as todo]
            [dacite.value :as v]
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

    ;; --- reload-cold (fresh client; pack-filled s-get via GET /node) ---
    (stats/reset-stats!)
    (let [cold (make-client base-url policy)
          root @!root
          delta
          (:delta
           (stats/measure
            (fn []
              (let [h (or (remote/remote-get-root cold)
                          (throw (ex-info "missing root" {})))
                    ;; Normal materialize: each miss pack-fills a neighborhood
                    todos (d/get-value cold h)]
                (when-not todos
                  (throw (ex-info "root missing after get" {:hash h})))
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

;; =============================================================================
;; 2d — soft-budget sweep (encode-side + live write-back)
;; =============================================================================

(def budget-ladder
  "Budgets to measure for leaf-chunking 2d (bytes, soft Layer-2 threshold)."
  [256 512 1024 2048 4096])

(defn- fixture-todo-seed
  "Local mem store + root hash of seed todo list."
  []
  (let [st (store/mem-store)
        seeded (todo/build st (todo/seed-items))
        h (types/dacite-hash seeded)]
    {:name :todo-seed
     :store st
     :root h
     :note "5-item sample list (browser demo seed)"}))

(defn- fixture-large-string
  []
  (let [st (store/mem-store)
        s (v/string-with-store st (apply str (repeat 3000 \x)))
        h (types/dacite-hash s)]
    {:name :string-3000
     :store st
     :root h
     :note "3000-char string (size cue >> small budgets)"}))

(defn- fixture-vector-40-strings
  []
  (let [st (store/mem-store)
        xs (mapv #(str "item-" %) (range 40))
        vec-v (apply v/vector-with-store st xs)
        h (types/dacite-hash vec-v)]
    {:name :vector-40-strings
     :store st
     :root h
     :note "40 short strings under one vector"}))

(defn- fixture-vector-200-i64
  []
  (let [st (store/mem-store)
        vec-v (apply v/vector-with-store st (range 200))
        h (types/dacite-hash vec-v)]
    {:name :vector-200-i64
     :store st
     :root h
     :note "200 i64 elements (post ft/single-elision spine)"}))

(defn encode-fixture-at-budget
  "encode-summary for one fixture root at budget."
  [{:keys [store root]} budget]
  (pack/encode-summary store root budget))

(defn encode-sweep
  "Local encode-side matrix: fixture × budget → encode-summary stats.

   Does not hit the network. Good for literal/node mix and chunk counts."
  ([] (encode-sweep budget-ladder))
  ([budgets]
   (let [fixtures [(fixture-todo-seed)
                   (fixture-large-string)
                   (fixture-vector-40-strings)
                   (fixture-vector-200-i64)]]
     {:kind :encode-sweep
      :budgets (vec budgets)
      :rows
      (vec
       (for [fx fixtures
             b budgets]
         (let [sum (encode-fixture-at-budget fx b)]
           (merge {:fixture (:name fx)
                   :note (:note fx)}
                  (select-keys sum [:budget :items :literals :nodes
                                    :chunks :approx-bytes :covered])))))})))

(defn live-write-back-at-budget
  "Run write-back suite with pack/default-budget rebound to b."
  [b]
  (with-redefs [pack/default-budget (long b)]
    (let [r (run-with-server :write-back)]
      {:budget (long b)
       :policy :write-back
       :scenarios (:scenarios r)
       :totals (:totals r)})))

(defn live-sweep
  "Live write-back bandwidth matrix across budgets (ephemeral HTTP service)."
  ([] (live-sweep budget-ladder))
  ([budgets]
   {:kind :live-write-back-sweep
    :budgets (vec budgets)
    :rows (mapv live-write-back-at-budget budgets)}))

(defn budget-sweep
  "Full 2d report: encode matrix + live write-back suite per budget."
  ([] (budget-sweep budget-ladder))
  ([budgets]
   {:kind :budget-sweep-2d
    :default-budget pack/default-budget
    :encode (encode-sweep budgets)
    :live (live-sweep budgets)}))

(defn- parse-args [args]
  (loop [args args
         acc {:policy :none
              :out nil
              :label "run"
              :budget-sweep? false}]
    (if-let [a (first args)]
      (case a
        "--policy" (recur (nnext args) (assoc acc :policy (keyword (second args))))
        "--out" (recur (nnext args) (assoc acc :out (second args)))
        "--label" (recur (nnext args) (assoc acc :label (second args)))
        "--budget-sweep" (recur (next args) (assoc acc :budget-sweep? true))
        (recur (next args) acc))
      acc)))

(defn -main [& args]
  (let [{:keys [policy out label budget-sweep?]} (parse-args args)
        result (if budget-sweep?
                 (budget-sweep)
                 (assoc (run-with-server policy) :label label))
        edn-str (with-out-str (pp/pprint result))]
    (print edn-str)
    (flush)
    (when out
      (spit out edn-str)
      (println "Wrote" out))
    (System/exit 0)))
