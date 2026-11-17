(ns dacite.peer.peer-items-test
  "Peer protocol v1, items, on the JVM.

   The bodies are `dacite.peer.items-checks`. nbb runs the same checks
   (`npm run items:test`). One deftest each, so a failure names the check.
   `node-items-over-a-payload-are-reported-by-default-and-throw-on-request`
   moved here from `literal_limits_test`: it needs only `peer.items`."
  (:require [clojure.test :refer [deftest is]]
            [dacite.peer.items-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [t0 (System/currentTimeMillis)
        problems (vec ((get checks/checks name)))
        ms (- (System/currentTimeMillis) t0)]
    (is (empty? problems) (pr-str problems))
    ;; The JVM keeps `max-ms` for every check, including the fixture check
    ;; whose nbb ceiling is `slow-ms` (the fuse there is the slow part).
    (is (< ms checks/max-ms) (str name " took " ms " ms"))))

(deftest every-item-stream-fixture-verifies
  (run-check "every-item-stream-fixture-verifies"))

(deftest small-values-are-one-literal
  (run-check "small-values-are-one-literal"))

(deftest the-shorter-form-wins-and-is-never-worse
  (run-check "the-shorter-form-wins-and-is-never-worse"))

(deftest a-literal-never-exceeds-one-frame-payload
  (run-check "a-literal-never-exceeds-one-frame-payload"))

(deftest the-cap-applies-at-its-boundary
  (run-check "the-cap-applies-at-its-boundary"))

(deftest the-cap-follows-the-negotiated-frame-size
  (run-check "the-cap-follows-the-negotiated-frame-size"))

(deftest frames-keep-items-whole-when-they-can
  (run-check "frames-keep-items-whole-when-they-can"))

(deftest items-verify-in-the-order-the-sender-writes-them
  (run-check "items-verify-in-the-order-the-sender-writes-them"))

(deftest a-header-is-accepted-first-and-checked-when-its-root-arrives
  (run-check "a-header-is-accepted-first-and-checked-when-its-root-arrives"))

(deftest a-forged-header-count-or-size-is-refused-when-the-root-arrives
  (run-check "a-forged-header-count-or-size-is-refused-when-the-root-arrives"))

(deftest a-header-whose-hash-does-not-fit-its-root-is-refused
  (run-check "a-header-whose-hash-does-not-fit-its-root-is-refused"))

(deftest only-the-failing-headers-waiting-on-a-root-are-removed
  (run-check "only-the-failing-headers-waiting-on-a-root-are-removed"))

(deftest a-header-over-a-bare-leaf-root-is-checked-when-the-leaf-arrives
  (run-check "a-header-over-a-bare-leaf-root-is-checked-when-the-leaf-arrives"))

(deftest a-stream-that-ends-early-leaves-no-unchecked-header
  (run-check "a-stream-that-ends-early-leaves-no-unchecked-header"))

(deftest pages-verify-in-any-order
  (run-check "pages-verify-in-any-order"))

(deftest a-run-of-refs-is-a-ref
  (run-check "a-run-of-refs-is-a-ref"))

(deftest a-page-with-a-run-of-refs-is-a-spine-page
  (run-check "a-page-with-a-run-of-refs-is-a-spine-page"))

(deftest a-missing-entry-is-a-protocol-reason
  (run-check "a-missing-entry-is-a-protocol-reason"))

(deftest tampering-is-refused
  (run-check "tampering-is-refused"))

(deftest the-reveal-service-and-the-peer-layer-accept-the-same-bytes
  (run-check "the-reveal-service-and-the-peer-layer-accept-the-same-bytes"))

(deftest node-items-over-a-payload-are-reported-by-default-and-throw-on-request
  (run-check "node-items-over-a-payload-are-reported-by-default-and-throw-on-request"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.peer.peer-items-test)))))
               "every-check-has-a-deftest"))
      "items_checks.cljc and this file list the same tests"))
