(ns dacite.bench.todo-bw-test
  "Integration tests for todo bandwidth benchmarks and client-cache policies."
  (:require [clojure.test :refer [deftest is]]
            [dacite.bench.todo-bw :as bench]
            [dacite.store.stats :as stats]
            [dacite.service :as svc]
            [dacite.store.remote :as remote]
            [dacite.examples.todo :as todo]
            [dacite.value.types :as types]))

(deftest stats-record-and-measure
  (stats/reset-stats!)
  (stats/record! :node-put 10 0)
  (stats/record! :node-get 0 20)
  (let [s (stats/get-stats)]
    (is (= 2 (:requests s)))
    (is (= 10 (:bytes-sent s)))
    (is (= 20 (:bytes-recv s))))
  (let [{:keys [delta]} (stats/measure (fn [] (stats/record! :root-get 0 5)))]
    (is (= 1 (:requests delta)))
    (is (= 5 (:bytes-recv delta)))))

(deftest live-seed-records-nonzero-stats
  (stats/reset-stats!)
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [remote (remote/remote-store base-url)
            before (stats/get-stats)
            _ (let [t (todo/build remote (todo/seed-items))
                    h (types/dacite-hash t)]
                (is (true? (remote/remote-cas-root! remote nil h))))
            after (stats/get-stats)
            d (stats/stats-diff before after)]
        (is (pos? (:requests d)) "seed must issue store-protocol requests")
        (is (pos? (:bytes-sent d)) "seed must send node bodies")
        (is (pos? (+ (:bytes-sent d) (:bytes-recv d)))))
      (finally
        (stop!)))))

(deftest smart-cache-reduces-add-warm-requests
  (let [none (bench/run-with-server :none)
        smart (bench/run-with-server :smart-put)
        none-add (get-in none [:scenarios :add-warm :requests])
        smart-add (get-in smart [:scenarios :add-warm :requests])]
    (is (pos? none-add))
    (is (< smart-add none-add)
        (str "smart-put add-warm should beat baseline: "
             smart-add " vs " none-add))
    (is (bench/no-regression? none smart)
        "smart-put suite totals must not regress vs none")))

(deftest write-back-beats-smart-put-on-suite-totals
  (let [smart (bench/run-with-server :smart-put)
        wb (bench/run-with-server :write-back)]
    ;; Chunked flush may recv small POST responses; still fewer requests and sent bytes.
    (is (< (get-in wb [:totals :requests])
           (get-in smart [:totals :requests])))
    (is (<= (get-in wb [:totals :bytes-sent])
            (get-in smart [:totals :bytes-sent])))))
(deftest suite-scenarios-present
  (let [r (bench/run-with-server :layered)]
    (doseq [sc bench/scenario-names]
      (is (contains? (:scenarios r) sc))
      (is (pos? (get-in r [:scenarios sc :requests]))))))

(deftest encode-sweep-covers-budget-ladder
  (let [sw (bench/encode-sweep [256 1024])
        rows (:rows sw)
        by-fx (group-by :fixture rows)]
    (is (= :encode-sweep (:kind sw)))
    (is (= [256 1024] (:budgets sw)))
    (is (contains? by-fx :todo-seed))
    (is (contains? by-fx :string-3000))
    ;; todo seed collapses to one literal by 1024
    (let [todo-1k (first (filter #(and (= :todo-seed (:fixture %))
                                       (= 1024 (:budget %)))
                                 rows))]
      (is (= 1 (:chunks todo-1k)))
      (is (= 1 (:literals todo-1k)))
      (is (zero? (:nodes todo-1k))))
    ;; 256 fragments the same seed
    (let [todo-256 (first (filter #(and (= :todo-seed (:fixture %))
                                        (= 256 (:budget %)))
                                  rows))]
      (is (> (:chunks todo-256) 1)))))
