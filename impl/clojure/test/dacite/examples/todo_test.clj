(ns dacite.examples.todo-test
  "Todo domain stays on the public value API; store is s/mem."
  (:require [clojure.test :refer [deftest is]]
            [dacite.examples.todo :as todo]
            [dacite.store :as store]
            [dacite.value :as v]))

(deftest seed-and-toggle
  (let [r (v/root (store/mem))
        [todos seeded?] (todo/load-or-seed! r)]
    (is (true? seeded?))
    (is (= 5 (v/count todos)))
    (is (= "write portable host layer" (todo/title-str (v/nth todos 0))))
    (is (true? (todo/done? (v/nth todos 0))))
    (let [todos' (v/swap! r todo/add-todo "milk")]
      (is (= 6 (v/count todos')))
      (is (= "milk" (todo/title-str (v/nth todos' 5))))
      (is (false? (todo/done? (v/nth todos' 5))))
      (is (= (v/hash todos') (v/hash (v/deref r)))))
    (let [todos' (v/swap! r todo/toggle-at 5)]
      (is (true? (todo/done? (v/nth todos' 5)))))))

(deftest file-reopen-same-hash
  (let [dir (str "target/dacite-todo-test-" (System/nanoTime))]
    (try
      (let [r1 (v/root (todo/open-store dir))]
        (todo/load-or-seed! r1)
        (v/swap! r1 todo/add-todo "persist me")
        (let [h1 (v/hash (v/deref r1))
              r2 (v/root (todo/open-store dir))]
          (is (= h1 (v/hash (v/deref r2))))
          (is (= "persist me" (todo/title-str (v/nth (v/deref r2) 5))))))
      (finally
        (todo/reset-store-dir! dir)))))
