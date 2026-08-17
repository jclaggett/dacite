(ns dacite.examples.event-log-test
  "Event log: append stays cheap; page/replay do not seq the whole log."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [dacite.examples.event-log :as elog]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.stats :as stats]
            [dacite.value :as v]))

(deftest incremental-view-matches-replay
  (let [led (elog/build (elog/open-mem) 80)
        wiped (v/assoc led "view" (elog/empty-view led))
        replayed (elog/replay wiped)]
    (is (= 80 (elog/log-count led)))
    (is (= (v/dacite-hash (elog/view-of led))
           (v/dacite-hash (elog/view-of replayed))))))

(deftest prefix-subvec-matches-fresh-build
  (let [st (store/mem-store)
        full (elog/build st 50)
        pre (elog/prefix full 20)
        fresh (elog/log-of (elog/build st 20))]
    (is (= 20 (v/count pre)))
    (is (= (v/dacite-hash fresh) (v/dacite-hash pre))
        "first 20 events have the same hash whether sliced or built")))

(deftest page-is-a-window
  (let [led (elog/build (elog/open-mem) 100)
        p (elog/page led 2 20)]
    (is (= 20 (v/count p)))
    (is (= (v/dacite-hash (v/nth (elog/log-of led) 40))
           (v/dacite-hash (v/nth p 0))))
    (is (= (v/dacite-hash (v/nth (elog/log-of led) 59))
           (v/dacite-hash (v/nth p 19))))))

(deftest append-delta-stays-small
  (let [samples (elog/measure-append (store/mem-store) [100 500 2000])
        deltas (mapv :delta samples)]
    (is (= [100 500 2000] (mapv :n samples)))
    (is (every? pos? deltas))
    (is (every? #(< % 120) deltas)
        "one append must not rewrite the log")
    (is (< (apply max deltas) (* 4 (apply min deltas)))
        "append cost must not grow linearly with log length")))

(deftest file-reopen
  (let [dir (io/file (str "target/dacite-log-test-" (System/nanoTime)))]
    (try
      (let [r1 (v/root-ref (elog/open-file (.getPath dir)))]
        (elog/load-or-seed! r1 30)
        (v/ref-swap! r1 elog/append (elog/event r1 "credit" 4 "tip"))
        (let [h1 (v/dacite-hash (v/ref-deref r1))
              r2 (v/root-ref (elog/open-file (.getPath dir)))
              loaded (v/ref-deref r2)]
          (is (= h1 (v/dacite-hash loaded)))
          (is (= 31 (elog/log-count loaded)))))
      (finally
        (elog/reset-store-dir! (.getPath dir))))))

(deftest remote-page-cheaper-than-full-seq
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [writer (v/root-ref (store/remote-rooted-store base-url))]
        (elog/load-or-seed! writer 200)
        (stats/reset-stats!)
        (let [cold-page (v/root-ref (store/remote-rooted-store base-url
                                                               {:policy :none}))
              page-delta (:delta
                          (stats/measure
                           (fn []
                             (let [led (v/ref-deref cold-page)]
                               (v/count (elog/page led 0 20))))))
              _ (stats/reset-stats!)
              cold-all (v/root-ref (store/remote-rooted-store base-url
                                                              {:policy :none}))
              all-delta (:delta
                         (stats/measure
                          (fn []
                            (let [log (elog/log-of (v/ref-deref cold-all))]
                              (count (or (v/seq log) ()))))))]
          (is (pos? (:bytes-recv page-delta)))
          (is (< (:bytes-recv page-delta) (:bytes-recv all-delta))
              "page 0 must not pull as much as seq of the whole log")
          (is (< (:requests page-delta) (:requests all-delta)))))
      (finally
        (stop!)))))
