(ns dacite.hash-test
  "Dacite hashing invariants.

   The tests are checks in `dacite.hash-checks`, shared with nbb
   (`npm run hash:test`); each runs here as a deftest under its old name.
   The properties were defspecs; their checks run test.check here."
  (:require [clojure.edn]
            [clojure.test :refer [deftest is]]
            [dacite.hash-checks :as checks]
            [dacite.store.check :as check]))

(defn fixture-vectors []
  (clojure.edn/read-string (slurp "../../fixtures/fuse-sl2/vectors.edn")))

(def ^:private env
  "Host pieces the checks take (see dacite.hash-checks). Paths are from
   impl/clojure."
  {:read-vectors fixture-vectors
   :read-small-prime-collision #(clojure.edn/read-string
                                 (slurp "../../fixtures/fuse-sl2/small-prime-collision.edn"))})

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

;; =============================================================================
;; Group laws (SL(2, F_p))
;; =============================================================================

(deftest fuse-determinism
  (run-check "fuse-determinism"))

(deftest fuse-stays-in-the-group
  (run-check "fuse-stays-in-the-group"))

(deftest fuse-associative
  (run-check "fuse-associative"))

(deftest fuse-identity
  (run-check "fuse-identity"))

(deftest fuse-non-commutative
  (run-check "fuse-non-commutative"))

(deftest fuse-inverse-both-sides
  (run-check "fuse-inverse-both-sides"))

(deftest unfuse-recovers
  (run-check "unfuse-recovers"))

(deftest fuse-bytes-composes-over-concatenation
  (run-check "fuse-bytes-composes-over-concatenation"))

(deftest typed-value-hash-determinism
  (run-check "typed-value-hash-determinism"))

(deftest different-types-different-hashes
  (run-check "different-types-different-hashes"))

;; =============================================================================
;; The prime and the generator table
;; =============================================================================

(deftest the-prime-and-its-factorizations
  (run-check "the-prime-and-its-factorizations"))

(deftest the-byte-generator-table
  (run-check "the-byte-generator-table"))

(deftest montgomery-reference-agrees-with-the-kernel
  (run-check "montgomery-reference-agrees-with-the-kernel"))

;; =============================================================================
;; Issue #14: the collision family of the old nilpotent fuse
;; =============================================================================

(deftest issue-14-named-collisions-are-gone
  (run-check "issue-14-named-collisions-are-gone"))

(deftest issue-14-through-values
  (run-check "issue-14-through-values"))

(deftest every-short-word-has-its-own-state
  (run-check "every-short-word-has-its-own-state"))

(deftest digits-0000-to-9999-are-all-distinct
  (run-check "digits-0000-to-9999-are-all-distinct"))

;; =============================================================================
;; Repeated values: A^n returns to the identity only at a multiple of A's order
;; =============================================================================

(deftest long-runs-of-one-element
  (run-check "long-runs-of-one-element"))

(deftest a-run-wraps-only-after-the-order
  (run-check "a-run-wraps-only-after-the-order"))

;; =============================================================================
;; Why the prime is 256 bits: a collision in ~sqrt(p) at a small prime
;; =============================================================================

(deftest small-prime-collision
  (run-check "small-prime-collision"))

;; =============================================================================
;; Refs: digest and expansion
;; =============================================================================

(deftest a-ref-is-a-32-byte-digest-of-a-state
  (run-check "a-ref-is-a-32-byte-digest-of-a-state"))

(deftest digest-of-identity-and-of-the-hand-computed-fold
  (run-check "digest-of-identity-and-of-the-hand-computed-fold"))

(deftest digest-bits-are-uniform
  (run-check "digest-bits-are-uniform"))

;; =============================================================================
;; State bytes and hex
;; =============================================================================

(deftest state-bytes-and-hex-round-trip
  (run-check "state-bytes-and-hex-round-trip"))

(deftest invalid-states-are-refused
  (run-check "invalid-states-are-refused"))

;; =============================================================================
;; Cross-platform fixtures (nbb reads the same file)
;; =============================================================================

(deftest the-kernel-boundary-agrees-with-the-known-answers
  (run-check "the-kernel-boundary-agrees-with-the-known-answers"))

(deftest fixture-vectors-match
  (run-check "fixture-vectors-match"))

(deftest test-encode-value
  (run-check "test-encode-value"))

(deftest test-typed-value-hash-string-type
  (run-check "test-typed-value-hash-string-type"))

(deftest test-typed-value-hash-domain-separator
  (run-check "test-typed-value-hash-domain-separator"))

(deftest test-fuse-seq
  (run-check "test-fuse-seq"))

(deftest test-byte-state-table
  (run-check "test-byte-state-table"))

(deftest test-fuse-bytes
  (run-check "test-fuse-bytes"))

(deftest test-node-hash
  (run-check "test-node-hash"))

(deftest test-hex-conversion
  (run-check "test-hex-conversion"))

(deftest test-bytes-longs-conversion
  (run-check "test-bytes-longs-conversion"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.hash-test)))))
               "every-check-has-a-deftest"))
      "hash_checks.cljc and this file list the same tests"))
