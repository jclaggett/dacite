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

(deftest new-peer-starts-at-its-fair-share
  (run-check "new-peer-starts-at-its-fair-share"))

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

(deftest same-hash-twice-is-two-independent-streams
  (run-check "same-hash-twice-is-two-independent-streams"))

(deftest responder-never-fetches-on-behalf
  (run-check "responder-never-fetches-on-behalf"))

(deftest no-message-changes-the-store-except-verified-items
  (run-check "no-message-changes-the-store-except-verified-items"))

(deftest record-is-capped-oldest-first
  (run-check "record-is-capped-oldest-first"))

(deftest failed-stream-retry-does-not-resend-confirmed-pages
  (run-check "failed-stream-retry-does-not-resend-confirmed-pages"))

(deftest root-of-a-get-is-sent-even-if-recorded
  (run-check "root-of-a-get-is-sent-even-if-recorded"))

(deftest empty-and-wrong-record-still-complete-every-request
  (run-check "empty-and-wrong-record-still-complete-every-request"))

(deftest a-get-of-a-recorded-hash-is-sent-and-verified
  (run-check "a-get-of-a-recorded-hash-is-sent-and-verified"))

(deftest ack-is-optional-and-unknown-hashes-are-ignored
  (run-check "ack-is-optional-and-unknown-hashes-are-ignored"))

(deftest renewal-is-correct-with-no-record-and-may-reveal-more
  (run-check "renewal-is-correct-with-no-record-and-may-reveal-more"))

(deftest stream-state-is-forgotten-by-its-lifetime-bound
  (run-check "stream-state-is-forgotten-by-its-lifetime-bound"))

(deftest stream-ends-partial-when-interest-falls-below-floor
  (run-check "stream-ends-partial-when-interest-falls-below-floor"))

(deftest floor-sends-fin-partial-when-blocked-or-credit-runs-out
  (run-check "floor-sends-fin-partial-when-blocked-or-credit-runs-out"))

(deftest cancel-stops-and-frees-share-and-is-never-required
  (run-check "cancel-stops-and-frees-share-and-is-never-required"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.peer.flow-test)))))
               "every-check-has-a-deftest"))
      "flow_checks.cljc and this file list the same tests"))
