(ns dacite.peer.transport-test
  "M3.1 slice 1 on the JVM: the portable checks in transport_checks.cljc, one
   deftest each. nbb runs the same checks in `npm run peer:test`."
  (:require [clojure.test :refer [deftest is]]
            [dacite.peer.transport-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [t0 (System/currentTimeMillis)
        problems (vec ((get checks/checks name)))
        ms (- (System/currentTimeMillis) t0)]
    (is (empty? problems) (pr-str problems))
    (is (< ms checks/max-ms) (str name " took " ms " ms"))))

(deftest two-peers-over-in-memory-transport-verify-a-value
  (run-check "two-peers-over-in-memory-transport-verify-a-value"))

(deftest max-frame-is-clamped-to-the-channel
  (run-check "max-frame-is-clamped-to-the-channel"))

(deftest peer-never-sees-roots-messages
  (run-check "peer-never-sees-roots-messages"))

(deftest closing-a-channel-ends-only-that-layers-session
  (run-check "closing-a-channel-ends-only-that-layers-session"))

(deftest closing-the-connection-ends-the-peer-session
  (run-check "closing-the-connection-ends-the-peer-session"))

(deftest a-corrupted-entry-ends-the-session-over-the-transport
  (run-check "a-corrupted-entry-ends-the-session-over-the-transport"))

(deftest back-pressure-reads-the-channels-buffered-amount
  (run-check "back-pressure-reads-the-channels-buffered-amount"))

(deftest in-memory-channel-keeps-order-and-counts-buffered
  (run-check "in-memory-channel-keeps-order-and-counts-buffered"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.peer.transport-test)))))
               "every-check-has-a-deftest"))
      "transport_checks.cljc and this file list the same tests"))
