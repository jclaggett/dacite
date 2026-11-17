(ns dacite.bench.todo-bw-test
  "Tests for the store stats counters and the pack budget sweep."
  (:require [clojure.test :refer [deftest is]]
            [dacite.bench.todo-bw :as bench]
            [dacite.store.stats :as stats]))

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

(deftest encode-sweep-covers-budget-ladder
  (let [sw (bench/encode-sweep [256 1024])
        rows (:rows sw)
        by-fx (group-by :fixture rows)]
    (is (= :encode-sweep (:kind sw)))
    (is (= [256 1024] (:budgets sw)))
    (is (contains? by-fx :todo-seed))
    (is (contains? by-fx :string-12000))
    ;; todo seed collapses to one literal by 1024
    (let [todo-1k (first (filter #(and (= :todo-seed (:fixture %))
                                       (= 1024 (:budget %)))
                                 rows))]
      (is (= 1 (:chunks todo-1k)))
      (is (= 1 (:literals todo-1k)))
      (is (zero? (:nodes todo-1k))))
    ;; 256 still fragments a 12000-char string (todo seed is one run-literal)
    (let [s-256 (first (filter #(and (= :string-12000 (:fixture %))
                                     (= 256 (:budget %)))
                               rows))]
      (is (> (:chunks s-256) 1)))))
