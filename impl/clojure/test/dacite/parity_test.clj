(ns dacite.parity-test
  "JVM side of the reveal/pack parity suite.

   The cases live in dacite.parity.checks and fixtures/parity/decisions.edn.
   nbb runs the same namespace (`npm run parity:test`). `bin/parity.sh`
   runs both and fails if the digests differ."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dacite.parity.checks :as checks]))

(defn- ok
  [ids]
  (let [ps (checks/problems ids)]
    (is (empty? ps) (str/join "\n" ps))))

(deftest reveal-page-order-matches-the-golden
  (ok #{"scalar" "empty-vector" "inlined-scalars" "bare-leaf"
        "nested-vectors" "long-string" "map-order" "set-order"
        "omit-sent" "interest" "shares"}))

(deftest reveal-clock-matches-the-golden
  (ok #{"one-page" "equal-shares" "below-floor" "cancel" "already-sent"
        "clock-rejects" "release" "ack-evict" "floor-instant"
        "duplicate-request" "paused-ends" "page-over-budget"
        "unknown-ack"}))

(deftest reveal-multipage-clock-matches-the-golden
  (ok #{"reveal-order" "budget" "reask-stall" "child-focus" "newer" "resume"
        "child-receives"}))

(deftest pack-decisions-match-the-golden
  (ok #{"i64" "string-hello" "vector-123" "vector-strings" "map-ab" "set-123"
        "empty-string" "empty-vector-pack" "empty-map" "empty-set"
        "string-100" "bool-repeat" "mixed-vector" "char-repeat"
        "pack-budget" "large-string" "vector-tight" "canonical"}))
