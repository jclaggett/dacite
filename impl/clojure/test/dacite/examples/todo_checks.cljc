(ns dacite.examples.todo-checks
  "The todo domain, on the public value API with a mem store and a file
   store.
   On the JVM and on nbb.

   These were the bodies of `dacite.examples.todo-test`. The JVM runs them
   from that namespace, one deftest each under its old name; nbb runs them
   from `dacite.examples.examples-cljs-test` (`npm run examples:test`).
   `is` is `dacite.store.check`."
  (:require [dacite.store.check :refer [is]]
            [dacite.examples.todo :as todo]
            [dacite.store :as store]
            [dacite.value :as v]))

(defn seed-and-toggle
  [_]
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

(defn file-reopen-same-hash
  [_]
  (let [dir (str "target/dacite-todo-test-" #?(:clj (System/nanoTime) :cljs (.now js/Date)))]
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

(def checks
  "Check name to check fn. The JVM deftest of each has the same name."
  {"seed-and-toggle" seed-and-toggle
   "file-reopen-same-hash" file-reopen-same-hash})
