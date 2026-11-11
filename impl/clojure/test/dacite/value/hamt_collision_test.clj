(ns dacite.value.hamt-collision-test
  "Issue #12 and the telescoping HAMT that fixes it, on the JVM.

   The bodies are `dacite.value.hamt-collision-checks`. nbb runs the same
   checks (`npm run value:test`). One deftest each, so the JVM suite still
   names the test it ran before. The properties use test.check here, as
   before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.value.hamt-collision-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest the-two-key-map-of-issue-12
  (run-check "the-two-key-map-of-issue-12"))

(deftest forged-chunk-pairs-of-issue-12
  (run-check "forged-chunk-pairs-of-issue-12"))

(deftest chunks->hash-is-the-inverse-of-hash-chunk
  (run-check "chunks->hash-is-the-inverse-of-hash-chunk"))

(deftest property-forced-chains-assoc-get-count-entries-dissoc
  (run-check "property-forced-chains-assoc-get-count-entries-dissoc"))

(deftest the-last-chunk-is-a-split-like-any-other
  (run-check "the-last-chunk-is-a-split-like-any-other"))

(deftest equal-key-hashes-are-one-key
  (run-check "equal-key-hashes-are-one-key"))

(deftest property-fuzz-assoc-and-dissoc-histories-give-one-tree
  (run-check "property-fuzz-assoc-and-dissoc-histories-give-one-tree"))

(deftest the-hash-covers-the-bitmap-and-the-split-not-the-children
  (run-check "the-hash-covers-the-bitmap-and-the-split-not-the-children"))

(deftest equal-entries-give-the-same-node-in-any-map
  (run-check "equal-entries-give-the-same-node-in-any-map"))

(deftest the-wire-layout-of-a-bitmap-node
  (run-check "the-wire-layout-of-a-bitmap-node"))

(deftest tampered-bitmap-nodes-are-rejected
  (run-check "tampered-bitmap-nodes-are-rejected"))

(deftest the-receiver-checks-split-51-bits-and-an-entrys-key-hash
  (run-check "the-receiver-checks-split-51-bits-and-an-entrys-key-hash"))

(deftest assoc-onto-a-misplaced-entry-is-bad-hamt-node-not-an-npe
  (run-check "assoc-onto-a-misplaced-entry-is-bad-hamt-node-not-an-npe"))

(deftest a-forged-store-with-splits-out-of-order-cannot-loop
  (run-check "a-forged-store-with-splits-out-of-order-cannot-loop"))

(deftest the-collision-pool-still-collides
  (run-check "the-collision-pool-still-collides"))

(deftest property-colliding-real-keys-through-the-value-api
  (run-check "property-colliding-real-keys-through-the-value-api"))

(deftest property-colliding-maps-round-trip-through-literals-and-streams
  (run-check "property-colliding-maps-round-trip-through-literals-and-streams"))

(deftest a-set-with-colliding-members-keeps-them-all
  (run-check "a-set-with-colliding-members-keeps-them-all"))

(deftest colliding-keys-in-a-map-big-enough-for-node-items-round-trip
  (run-check "colliding-keys-in-a-map-big-enough-for-node-items-round-trip"))

(deftest every-inner-hamt-node-literal-round-trips
  (run-check "every-inner-hamt-node-literal-round-trips"))

(deftest value-hashes-of-correct-maps-are-pinned
  (run-check "value-hashes-of-correct-maps-are-pinned"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.value.hamt-collision-test)))))
               "every-check-has-a-deftest"))
      "value/hamt_collision_checks.cljc and this file list the same tests"))
