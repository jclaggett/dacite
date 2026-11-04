(ns dacite.peer.client-test
  "M1.2 slice 1 on the JVM: the portable checks in client_checks.cljc, one
   deftest each, named as in docs/spec/peer-v1.md section 13. nbb runs the same
   checks in `npm run peer:test`."
  (:require [clojure.test :refer [deftest is]]
            [dacite.peer.client-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [t0 (System/currentTimeMillis)
        problems (vec ((get checks/checks name)))
        ms (- (System/currentTimeMillis) t0)]
    (is (empty? problems) (pr-str problems))
    (is (< ms checks/max-ms) (str name " took " ms " ms"))))

(deftest bad-entry-ends-the-session
  (run-check "bad-entry-ends-the-session"))

(deftest bad-state-is-refused-as-bad-entry
  (run-check "bad-state-is-refused-as-bad-entry"))

(deftest nothing-unverified-reaches-the-store
  (run-check "nothing-unverified-reaches-the-store"))

(deftest every-page-verifies-in-any-order
  (run-check "every-page-verifies-in-any-order"))

(deftest literal-hash-mismatch-is-refused
  (run-check "literal-hash-mismatch-is-refused"))

(deftest hamt-bitmap-shape-refusals
  (run-check "hamt-bitmap-shape-refusals"))

(deftest a-session-whose-stream-ends-early-leaves-no-unchecked-header
  (run-check "a-session-whose-stream-ends-early-leaves-no-unchecked-header"))

(deftest one-session-ending-leaves-others-untouched
  (run-check "one-session-ending-leaves-others-untouched"))

(deftest bad-peer-streams-are-reasked-from-others
  (run-check "bad-peer-streams-are-reasked-from-others"))

(deftest lying-peer-and-honest-peer
  (run-check "lying-peer-and-honest-peer"))

(deftest client-stream-ids-are-its-own-counter
  (run-check "client-stream-ids-are-its-own-counter"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.peer.client-test)))))
               "every-check-has-a-deftest"))
      "client_checks.cljc and this file list the same tests"))
