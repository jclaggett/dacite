(ns dacite.examples.event-log-test
  "Event log on the JVM.

   The bodies are `dacite.examples.event-log-checks`; nbb runs the same checks
   (`npm run examples:test`). One deftest each, so the JVM suite still
   names the test it ran before.

   `two-writers-keep-every-append` stays JVM only: two JVM threads on one
   root; nbb has one thread, so the writers cannot interleave."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.examples.event-log-checks :as checks]
            [dacite.examples.event-log :as elog]
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

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.examples.event-log-test)))))
               "every-check-has-a-deftest"
               "two-writers-keep-every-append"))
      "examples/event_log_checks.cljc and this file list the same tests"))
