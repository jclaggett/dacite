(ns dacite.peer.examples
  "The control-message examples behind fixtures/peer-v1/cases.

   Each example is {:id :title :type :stream :value}. The fixture
   generator (dev/gen_peer_fixtures.clj) encodes them with the real
   encoder; the spec quotes them. The hashes are real hashes of small
   Dacite values, so the examples read like traffic."
  (:require [dacite.peer.msg :as msg]))

(def h1 "00000000000000010000000000000002000000000000000300000000000000aa")
(def h2 "1111111111111111222222222222222233333333333333334444444444444444")
(def h3 "ffffffffffffffff0000000000000000ffffffffffffffff0000000000000000")

(defn- bytes-of
  [& ns]
  (dacite.wire.binary/as-wire-bytes (vec ns)))

(def examples
  [{:id "hello-node"
    :title "A Node peer introduces itself"
    :type :hello
    :stream 0
    :value {"versions" #{1}
            "agent" "dacite-nbb/0.2.0-alpha"
            "kind" "node"
            "caps" #{"have" "reveal"}
            "limits" {"max-frame" 16384 "max-item" 1048576
                      "max-streams" 32 "max-have" 256}
            "rates" {"send-max" 10000000 "recv-max" 10000000
                     "req-rate" 20 "req-burst" 40}}}

   {:id "hello-browser"
    :title "A browser peer: smaller limits, no agent string"
    :type :hello
    :stream 0
    :value {"versions" #{1}
            "kind" "browser"
            "caps" #{"have" "reveal"}
            "limits" {"max-frame" 16384 "max-item" 262144
                      "max-streams" 8 "max-have" 128}
            "rates" {"send-max" 1000000 "recv-max" 5000000
                     "req-rate" 10 "req-burst" 20}}}

   {:id "have-query"
    :title "Does the peer hold these three entries?"
    :type :have?
    :stream 1
    :value {"hashes" [(msg/hash-ref h1) (msg/hash-ref h2) (msg/hash-ref h3)]}}

   {:id "have-reply"
    :title "It holds the first and third (bits 1 0 1, MSB first)"
    :type :have
    :stream 1
    :value {"count" 3 "bits" (bytes-of 0xa0)}}

   {:id "have-reply-9"
    :title "Nine hashes need two bytes; spare bits are zero"
    :type :have
    :stream 3
    :value {"count" 9 "bits" (bytes-of 0xff 0x80)}}

   {:id "get-default"
    :title "Reveal a value with the default policy and the peer's interest"
    :type :get
    :stream 5
    :value {"hash" (msg/hash-ref h1) "policy" "default"}}

   {:id "get-interest"
    :title "Reveal with explicit interest: peak 1.0, tau 2 s, floor 0.05"
    :type :get
    :stream 7
    :value {"hash" (msg/hash-ref h2)
            "policy" "default"
            "interest" {"peak" 1000 "tau-ms" 2000 "floor" 50}}}

   {:id "get-repeat"
    :title "A second ask for the same hash is its own stream (N1)"
    :type :get
    :stream 9
    :value {"hash" (msg/hash-ref h1) "policy" "default"}}

   {:id "ack"
    :title "The client stored these pages"
    :type :ack
    :stream 0
    :value {"hashes" [(msg/hash-ref h1) (msg/hash-ref h2)]}}

   {:id "release"
    :title "The client dropped a page and wants it resent if asked again"
    :type :release
    :stream 0
    :value {"hashes" [(msg/hash-ref h3)] "reason" "evicted"}}

   {:id "cancel"
    :title "The client no longer wants stream 5"
    :type :cancel
    :stream 5
    :value {"reason" "navigated away"}}

   {:id "cancel-bare"
    :title "Cancel with no reason: an empty map"
    :type :cancel
    :stream 7
    :value {}}

   {:id "error-unknown-hash"
    :title "The peer does not hold the hash; the stream ends, the session lives"
    :type :error
    :stream 5
    :value {"code" "unknown-hash" "fatal" false}}

   {:id "error-rate-limited"
    :title "Too many requests: retry later"
    :type :error
    :stream 11
    :value {"code" "rate-limited" "message" "request bucket empty"
            "fatal" false "retry-ms" 250}}

   {:id "error-bad-entry"
    :title "An entry failed its hash check: the session ends"
    :type :error
    :stream 0
    :value {"code" "bad-entry" "message" "hash mismatch on stream 5"
            "fatal" true}}])
