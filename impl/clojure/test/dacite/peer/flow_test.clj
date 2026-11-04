(ns dacite.peer.flow-test
  "M1.2 slice 2 on the JVM: the portable checks in flow_checks.cljc, one deftest
   each, named as in docs/spec/peer-v1.md section 13. nbb runs the same checks in
   `npm run peer:test`."
  (:require [clojure.test :refer [deftest is]]
            [dacite.peer.flow-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [t0 (System/currentTimeMillis)
        problems (vec ((get checks/checks name)))
        ms (- (System/currentTimeMillis) t0)]
    (is (empty? problems) (pr-str problems))
    (is (< ms checks/max-ms) (str name " took " ms " ms"))))

(deftest shared-rmax-splits-by-interest-across-peers
  (run-check "shared-rmax-splits-by-interest-across-peers"))

(deftest capped-peer-share-is-redistributed
  (run-check "capped-peer-share-is-redistributed"))

(deftest committed-rate-never-exceeds-rmax
  (run-check "committed-rate-never-exceeds-rmax"))

(deftest requester-bucket-never-overspends
  (run-check "requester-bucket-never-overspends"))

(deftest item-on-a-stream-never-opened-ends-the-session
  (run-check "item-on-a-stream-never-opened-ends-the-session"))

(deftest item-on-a-closed-stream-is-ignored
  (run-check "item-on-a-closed-stream-is-ignored"))

(deftest protocol-violation-ends-session-request-error-ends-stream
  (run-check "protocol-violation-ends-session-request-error-ends-stream"))

(deftest have-does-not-recompute-what-a-session-verified
  (run-check "have-does-not-recompute-what-a-session-verified"))

(deftest responder-paces-to-session-budget
  (run-check "responder-paces-to-session-budget"))

(deftest channel-never-buffers-over-high-mark-and-resumes-at-low
  (run-check "channel-never-buffers-over-high-mark-and-resumes-at-low"))

(deftest blocked-stream-takes-no-share-and-banks-one-frame
  (run-check "blocked-stream-takes-no-share-and-banks-one-frame"))

(deftest get-over-the-bucket-is-rate-limited-and-session-lives
  (run-check "get-over-the-bucket-is-rate-limited-and-session-lives"))

(deftest responder-never-fetches-on-behalf
  (run-check "responder-never-fetches-on-behalf"))

(deftest no-message-changes-the-store-except-verified-items
  (run-check "no-message-changes-the-store-except-verified-items"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.peer.flow-test)))))
               "every-check-has-a-deftest"))
      "flow_checks.cljc and this file list the same tests"))
