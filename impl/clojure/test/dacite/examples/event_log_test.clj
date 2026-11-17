(ns dacite.examples.event-log-test
  "Event log on the JVM.

   The bodies are `dacite.examples.event-log-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before.

   Four stay JVM only: `two-writers-keep-every-append` (two JVM threads on
   one root; nbb has one thread, so the writers cannot interleave) and
   `remote-page-cheaper-than-full-seq`, `two-remote-writers-keep-every-append`
   and `sse-watch-sees-new-root` (the HTTP service, `store/remote` and SSE,
   legacy HTTP)."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.event-log-checks :as checks]
            [dacite.examples.event-log :as elog]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.remote :as remote]
            [dacite.store.stats :as stats]
            [dacite.value :as v]))

(defn- run-check
  "Run the check called `name`: no problems, and within its ceiling
   (`checks/slow-ms`, else `check/max-ms`)."
  [name]
  (let [limit (get checks/slow-ms name check/max-ms)
        {:keys [ms problems]} (check/run-one checks/checks {} name limit)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms limit) (str name " took " ms " ms"))))

(deftest incremental-view-matches-replay
  (run-check "incremental-view-matches-replay"))

(deftest prefix-slice-matches-fresh-build
  (run-check "prefix-slice-matches-fresh-build"))

(deftest page-is-a-window
  (run-check "page-is-a-window"))

(deftest append-delta-stays-small
  (run-check "append-delta-stays-small"))

(deftest file-reopen
  (run-check "file-reopen"))

(deftest remote-page-cheaper-than-full-seq
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [writer (v/root (store/remote-rooted-store base-url))]
        (elog/load-or-seed! writer 160)
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
          (elog/load-or-seed! writer 100)
          (v/swap! writer elog/append (elog/event writer "credit" 1 "sse"))
          (is (not= :timeout (deref later 8000 :timeout))
              "SSE client should see a root after CAS")
          (is (>= (count @seen) 2))
          (finally
            ((:stop! w)))))
      (finally
        (stop!)))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.event-log-test)))))
               "every-check-has-a-deftest"
               "remote-page-cheaper-than-full-seq"
               "two-writers-keep-every-append"
               "two-remote-writers-keep-every-append"
               "sse-watch-sees-new-root"))
      "examples/event_log_checks.cljc and this file list the same tests"))
