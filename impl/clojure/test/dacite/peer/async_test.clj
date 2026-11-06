(ns dacite.peer.async-test
  "M1.3 on the JVM: the portable checks in async_checks.cljc, one deftest each.
   nbb runs the same checks in `npm run peer:test`."
  (:require [clojure.test :refer [deftest is]]
            [dacite.peer.async-checks :as checks]
            [dacite.peer.client-checks :as client-checks]
            [dacite.peer.flow-checks :as flow-checks]))

(defn- run-check [name]
  (let [t0 (System/currentTimeMillis)
        problems (vec ((get checks/checks name)))
        ms (- (System/currentTimeMillis) t0)]
    (is (empty? problems) (pr-str problems))
    (is (< ms checks/max-ms) (str name " took " ms " ms"))))

(deftest synchronous-store-stays-synchronous
  (run-check "synchronous-store-stays-synchronous"))

(deftest two-items-back-to-back-the-second-waits-for-the-first
  (run-check "two-items-back-to-back-the-second-waits-for-the-first"))

(deftest rejected-put-fails-the-hash-and-the-session-lives
  (run-check "rejected-put-fails-the-hash-and-the-session-lives"))

(deftest store-failure-while-dropping-headers-stays-local
  (run-check "store-failure-while-dropping-headers-stays-local"))

(deftest m1-2-checks-run-on-a-late-store
  (let [results (checks/late-checks)]
    (is (= (+ (count client-checks/checks) (count flow-checks/checks))
           (count results))
        "every client and flow check ran")
    (doseq [{:keys [name problems]} results]
      (is (empty? problems) (str name ": " (pr-str problems))))))

(deftest a-store-with-host-promises-works
  (let [p (promise)]
    (checks/host-promise-check #(deliver p %))
    (let [problems (deref p 15000 ["timed out"])]
      (is (empty? problems) (pr-str problems)))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.peer.async-test)))))
               "every-check-has-a-deftest" "m1-2-checks-run-on-a-late-store"
               "a-store-with-host-promises-works"))
      "async_checks.cljc and this file list the same tests"))
