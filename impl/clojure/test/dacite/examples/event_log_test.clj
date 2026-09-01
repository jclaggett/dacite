(ns dacite.examples.event-log-test
  "Event log: append stays cheap; page/replay do not seq the whole log."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [dacite.examples.event-log :as elog]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.remote :as remote]
            [dacite.store.stats :as stats]
            [dacite.value :as v]))

(deftest incremental-view-matches-replay
  (let [led (elog/build (elog/open-mem) 80)
        wiped (v/assoc led "view" (elog/empty-view led))
        replayed (elog/replay wiped)]
    (is (= 80 (elog/log-count led)))
    (is (= (v/hash (elog/view-of led))
           (v/hash (elog/view-of replayed))))))

(deftest prefix-subvec-matches-fresh-build
  (let [st (store/mem-store)
        full (elog/build st 50)
        pre (elog/prefix full 20)
        fresh (elog/log-of (elog/build st 20))]
    (is (= 20 (v/count pre)))
    (is (= (v/hash fresh) (v/hash pre))
        "first 20 events have the same hash whether sliced or built")))

(deftest page-is-a-window
  (let [led (elog/build (elog/open-mem) 100)
        p (elog/page led 2 20)]
    (is (= 20 (v/count p)))
    (is (= (v/hash (v/nth (elog/log-of led) 40))
           (v/hash (v/nth p 0))))
    (is (= (v/hash (v/nth (elog/log-of led) 59))
           (v/hash (v/nth p 19))))))

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
      (let [r1 (v/root (elog/open-file (.getPath dir)))]
        (elog/load-or-seed! r1 30)
        (v/swap! r1 elog/append (elog/event r1 "credit" 4 "tip"))
        (let [h1 (v/hash (v/deref r1))
              r2 (v/root (elog/open-file (.getPath dir)))
              loaded (v/deref r2)]
          (is (= h1 (v/hash loaded)))
          (is (= 31 (elog/log-count loaded)))))
      (finally
        (elog/reset-store-dir! (.getPath dir))))))

(deftest remote-page-cheaper-than-full-seq
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [writer (v/root (store/remote-rooted-store base-url))]
        (elog/load-or-seed! writer 200)
        (stats/reset-stats!)
        (let [cold-page (v/root (store/remote-rooted-store base-url
                                                           {:policy :none}))
              page-delta (:delta
                          (stats/measure
                           (fn []
                             (let [led (v/deref cold-page)]
                               (v/count (elog/page led 0 20))))))
              _ (stats/reset-stats!)
              cold-all (v/root (store/remote-rooted-store base-url
                                                          {:policy :none}))
              all-delta (:delta
                         (stats/measure
                          (fn []
                            (let [log (elog/log-of (v/deref cold-all))]
                              (count (or (v/seq log) ()))))))]
          (is (pos? (:bytes-recv page-delta)))
          (is (< (:bytes-recv page-delta) (:bytes-recv all-delta))
              "page 0 must not pull as much as seq of the whole log")
          ;; A full seq walk now fills dense event runs per GET, so it can
          ;; use *fewer* requests than 20 nth-style page misses. Bytes stay
          ;; the cheapness claim.
          (is (pos? (:requests page-delta)))))
      (finally
        (stop!)))))

(deftest two-writers-keep-every-append
  (let [r (v/root (elog/open-mem))]
    (v/cas! r nil (elog/empty-ledger r))
    (let [retries (atom 0)
          fa (future
               (dotimes [i 20]
                 (swap! retries +
                        (:retries (v/swap-info!
                                   r elog/append
                                   (elog/event r "credit" 1 (str "a-" i)))))))
          fb (future
               (dotimes [i 20]
                 (swap! retries +
                        (:retries (v/swap-info!
                                   r elog/append
                                   (elog/event r "debit" 1 (str "b-" i)))))))]
      @fa
      @fb
      (is (= 40 (elog/log-count (v/deref r)))
          "CAS retry must not drop an append")
      (is (pos? @retries)
          "two writers on one root should collide at least once"))))

(deftest two-remote-writers-keep-every-append
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [a (v/root (store/remote-rooted-store base-url))
            b (v/root (store/remote-rooted-store base-url))]
        (elog/load-or-seed! a 0)
        (let [fa (future
                   (dotimes [i 8]
                     (v/swap! a elog/append
                              (elog/event a "credit" 1 (str "a-" i)))))
              fb (future
                   (dotimes [i 8]
                     (v/swap! b elog/append
                              (elog/event b "debit" 1 (str "b-" i)))))]
          @fa
          @fb
          (let [reader (v/root (store/remote-rooted-store base-url))]
            (is (= 16 (elog/log-count (v/deref reader)))
                "both remotes' appends must land"))))
      (finally
        (stop!)))))

(deftest sse-watch-sees-new-root
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [writer (v/root (store/remote-rooted-store base-url))
            seen (atom [])
            first-ev (promise)
            later (promise)
            w (remote/watch-root
               (store/remote-rooted-store base-url {:policy :none})
               (fn [h]
                 (swap! seen conj h)
                 (when (= 1 (count @seen))
                   (deliver first-ev h))
                 (when (> (count @seen) 1)
                   (deliver later h))))]
        (try
          (is (not= :timeout (deref first-ev 5000 :timeout))
              "SSE should emit the current root first")
          (elog/load-or-seed! writer 0)
          (v/swap! writer elog/append (elog/event writer "credit" 1 "sse"))
          (is (not= :timeout (deref later 8000 :timeout))
              "SSE client should see a root after CAS")
          (is (>= (count @seen) 2))
          (finally
            ((:stop! w)))))
      (finally
        (stop!)))))
