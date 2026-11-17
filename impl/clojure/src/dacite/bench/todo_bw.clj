(ns dacite.bench.todo-bw
  "Leaf-chunking 2d: how the pack encoder splits fixed values at each soft
   budget (items, literals, nodes, chunks, approximate bytes). Local only.

     clojure -M:dev -m dacite.bench.todo-bw --budget-sweep
     clojure -M:dev -m dacite.bench.todo-bw --budget-sweep --out target/budget-sweep.edn

   The live store-protocol bandwidth scenarios ran against the HTTP
   service and were removed with it (HTTP removal slice 2)."
  (:require [clojure.pprint :as pp]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.examples.todo :as todo]
            [dacite.value :as v]
            [dacite.value.types :as types])
  (:gen-class))

;; =============================================================================
;; 2d — soft-budget sweep (encode side)
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
        s (v/string st (apply str (repeat 12000 \x)))
        h (types/dacite-hash s)]
    {:name :string-12000
     :store st
     :root h
     :note "12000-char string, three 4 KiB leaf pages (size cue >> small budgets)"}))

(defn- fixture-vector-40-strings
  []
  (let [st (store/mem-store)
        xs (mapv #(str "item-" %) (range 40))
        vec-v (apply v/vector st xs)
        h (types/dacite-hash vec-v)]
    {:name :vector-40-strings
     :store st
     :root h
     :note "40 short strings under one vector"}))

(defn- fixture-vector-200-i64
  []
  (let [st (store/mem-store)
        vec-v (apply v/vector st (range 200))
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

(defn budget-sweep
  "Full 2d report: the encode matrix per budget."
  ([] (budget-sweep budget-ladder))
  ([budgets]
   {:kind :budget-sweep-2d
    :default-budget pack/default-budget
    :encode (encode-sweep budgets)}))

(defn- parse-args [args]
  (loop [args args
         acc {:out nil}]
    (if-let [a (first args)]
      (case a
        "--out" (recur (nnext args) (assoc acc :out (second args)))
        (recur (next args) acc))
      acc)))

(defn -main [& args]
  (let [{:keys [out]} (parse-args args)
        edn-str (with-out-str (pp/pprint (budget-sweep)))]
    (print edn-str)
    (flush)
    (when out
      (spit out edn-str)
      (println "Wrote" out))
    (System/exit 0)))
