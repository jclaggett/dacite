(ns dacite.examples.event-log-checks
  "Event log: an append stays cheap; page and replay do not seq the whole
   log, on the JVM and on nbb.

   These were the bodies of `dacite.examples.event-log-test`. The JVM runs them
   from that namespace, one deftest each under its old name; nbb runs them
   from `dacite.examples.examples-cljs-test` (`npm run examples:test`).
   `is` is `dacite.store.check`."
  (:require [dacite.store.check :refer [is]]
            [dacite.examples.event-log :as elog]
            [dacite.store :as store]
            [dacite.value :as v]))

;; JUSTIFICATION for the time (rule: a check over about 1.5 s needs one): about
;; 1.9 s on nbb. It builds an 80-event log (each append updates the view too)
;; and replays it, at the nbb cost of #63. Its nbb ceiling is in `slow-ms`.
(defn incremental-view-matches-replay
  [_]
  (let [led (elog/build (elog/open-mem) 80)
        wiped (v/assoc led "view" (elog/empty-view led))
        replayed (elog/replay wiped)]
    (is (= 80 (elog/log-count led)))
    (is (= (v/hash (elog/view-of led))
           (v/hash (elog/view-of replayed))))))

(defn prefix-slice-matches-fresh-build
  [_]
  (let [st (store/mem-store)
        full (elog/build st 50)
        pre (elog/prefix full 20)
        fresh (elog/log-of (elog/build st 20))]
    (is (= 20 (v/count pre)))
    (is (= (v/hash fresh) (v/hash pre))
        "first 20 events have the same hash whether sliced or built")))

;; JUSTIFICATION for the time (rule: a check over about 1.5 s needs one): about
;; 1.5 s on nbb, for building the 100-event log it pages (#63). Its nbb
;; ceiling is in `slow-ms`.
(defn page-is-a-window
  [_]
  (let [led (elog/build (elog/open-mem) 100)
        p (elog/page led 2 20)]
    (is (= 20 (v/count p)))
    (is (= (v/hash (v/nth (elog/log-of led) 40))
           (v/hash (v/nth p 0))))
    (is (= (v/hash (v/nth (elog/log-of led) 59))
           (v/hash (v/nth p 19))))))

(def nbb-cases
  "Log sizes on nbb for `append-delta-stays-small`. `elog/measure-append`
   counts every node of the whole log after each append, so its cost grows
   with the square of the largest size, and each append builds values at
   the nbb cost of #63: [100] alone takes about 6 s on nbb. The JVM runs
   [100 500]."
  {:append-sizes [20 50]})

;; JUSTIFICATION for the time (rule: a check over about 1.5 s needs one): about
;; 2.0 s on nbb at [20 50] and about 9 s on the JVM at [100 500].
;; `measure-append` counts the whole log after each append, so the cost grows
;; with the square of the log size (about 130 s on the JVM at 2000, which is
;; why the JVM stops at 500), and nbb adds #63. `measure-append` is in src and
;; stays as it is. Its ceilings are in `slow-ms`.
(defn append-delta-stays-small
  [_]
  (let [sizes #?(:clj [100 500] :cljs (:append-sizes nbb-cases))
        samples (elog/measure-append (store/mem-store) sizes)
        deltas (mapv :delta samples)]
    (is (= sizes (mapv :n samples)))
    (is (every? pos? deltas))
    (is (every? #(< % 120) deltas)
        "one append must not rewrite the log")
    (is (< (apply max deltas) (* 4 (apply min deltas)))
        "append cost must not grow linearly with log length")))

(defn file-reopen
  [_]
  (let [dir (str "target/dacite-log-test-" #?(:clj (System/nanoTime) :cljs (.now js/Date)))]
    (try
      (let [r1 (v/root (elog/open-file dir))]
        (elog/load-or-seed! r1 30)
        (v/swap! r1 elog/append (elog/event r1 "credit" 4 "tip"))
        (let [h1 (v/hash (v/deref r1))
              r2 (v/root (elog/open-file dir))
              loaded (v/deref r2)]
          (is (= h1 (v/hash loaded)))
          (is (= 31 (elog/log-count loaded)))))
      (finally
        (elog/reset-store-dir! dir)))))

(def checks
  "Check name to check fn. The JVM deftest of each has the same name."
  {"incremental-view-matches-replay" incremental-view-matches-replay
   "prefix-slice-matches-fresh-build" prefix-slice-matches-fresh-build
   "page-is-a-window" page-is-a-window
   "append-delta-stays-small" append-delta-stays-small
   "file-reopen" file-reopen})

(def slow-ms
  "Ceilings above `check/max-ms`, each with a JUSTIFICATION on its check."
  #?(:clj {"append-delta-stays-small" 18000}
     :cljs {"append-delta-stays-small" 4000
            "incremental-view-matches-replay" 4000
            "page-is-a-window" 3000}))
