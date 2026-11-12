(ns dacite.examples.hello-checks
  "The hello example, on the JVM and on nbb.

   These were the bodies of `dacite.examples.hello-test`. The JVM runs them
   from that namespace, one deftest each under its old name; nbb runs them
   from `dacite.examples.examples-cljs-test` (`npm run examples:test`).
   `is` is `dacite.store.check`."
  (:require [dacite.store.check :refer [is]]
            [dacite.examples.hello :as hello]
            [dacite.store :as store]
            [dacite.value :as v]))

(defn hello-vector-and-map
  [_]
  (let [{:keys [vec map]} (hello/hello (store/mem))]
    (is (= 3 (v/count vec)))
    (is (= 2 (v/count map)))
    (is (= 42 (v/realize (v/get map "hello"))))
    (is (= (v/hash vec) (v/hash (v/get map "vec"))))))

(def checks
  "Check name to check fn. The JVM deftest of each has the same name."
  {"hello-vector-and-map" hello-vector-and-map})
