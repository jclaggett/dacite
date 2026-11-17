(ns dacite.store.pack-test
  "Pack Layer-1 tests on the JVM.

   The bodies are `dacite.store.pack-checks`. nbb runs the same checks
   (`npm run store-units:test`). One deftest each, so the JVM suite still
   names the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.store.pack-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest pack-items-soft-budget
  (run-check "pack-items-soft-budget"))

(deftest apply-chunk-puts-nodes
  (run-check "apply-chunk-puts-nodes"))

(deftest literal-of-and-materialize-scalars
  (run-check "literal-of-and-materialize-scalars"))

(deftest literal-of-and-materialize-string
  (run-check "literal-of-and-materialize-string"))

(deftest literal-of-medium-string
  (run-check "literal-of-medium-string"))

(deftest literal-of-vector-is-recursive-typed
  (run-check "literal-of-vector-is-recursive-typed"))

(deftest literal-of-nested-vector-of-strings
  (run-check "literal-of-nested-vector-of-strings"))

(deftest rle-lits-n1-stays-unwrapped
  (run-check "rle-lits-n1-stays-unwrapped"))

(deftest rle-lits-mixed-types-split-runs
  (run-check "rle-lits-mixed-types-split-runs"))

(deftest rle-char-run-on-string-spine
  (run-check "rle-char-run-on-string-spine"))

(deftest rle-all-equal-chars-are-repeat
  (run-check "rle-all-equal-chars-are-repeat"))

(deftest rle-mixed-chars-are-run-not-split-repeats
  (run-check "rle-mixed-chars-are-run-not-split-repeats"))

(deftest rle-bool-vector-all-false-is-repeat
  (run-check "rle-bool-vector-all-false-is-repeat"))

(deftest rle-char-run-wire-round-trip
  (run-check "rle-char-run-wire-round-trip"))

(deftest literal-of-map-and-set
  (run-check "literal-of-map-and-set"))

(deftest literal-of-empty-collections
  (run-check "literal-of-empty-collections"))

(deftest literal-of-spine-is-intermediate-form
  (run-check "literal-of-spine-is-intermediate-form"))

(deftest encode-and-apply-literal-scalars
  (run-check "encode-and-apply-literal-scalars"))

(deftest encode-and-apply-literal-string
  (run-check "encode-and-apply-literal-string"))

(deftest encode-and-apply-medium-string-literal
  (run-check "encode-and-apply-medium-string-literal"))

(deftest encode-and-apply-literal-vector-map-set
  (run-check "encode-and-apply-literal-vector-map-set"))

(deftest materialize-accepts-2b-flat-host-bodies
  (run-check "materialize-accepts-2b-flat-host-bodies"))

(deftest literal-hash-mismatch-throws
  (run-check "literal-hash-mismatch-throws"))

(deftest encode-reachable-prefers-literal-and-skips-children
  (run-check "encode-reachable-prefers-literal-and-skips-children"))

(deftest ft-internal-nodes-may-be-intermediate-literals
  (run-check "ft-internal-nodes-may-be-intermediate-literals"))

(deftest todo-seed-round-trips-as-literals
  (run-check "todo-seed-round-trips-as-literals"))

(deftest multi-page-blob-round-trips-as-byte-runs
  (run-check "multi-page-blob-round-trips-as-byte-runs"))

(deftest large-string-refuses-literal-at-default-budget
  (run-check "large-string-refuses-literal-at-default-budget"))

(deftest large-string-literal-when-budget-allows
  (run-check "large-string-literal-when-budget-allows"))

(deftest large-vector-mixed-node-and-child-literals
  (run-check "large-vector-mixed-node-and-child-literals"))

(deftest size-bytes-at-budget-is-literal-one-over-is-node
  (run-check "size-bytes-at-budget-is-literal-one-over-is-node"))

(deftest type-run-of-short-strings-fits-tight-budget
  (run-check "type-run-of-short-strings-fits-tight-budget"))

(deftest encode-summary-reports-mixed-stats
  (run-check "encode-summary-reports-mixed-stats"))

(deftest intermediate-ft-digit-round-trip
  (run-check "intermediate-ft-digit-round-trip"))

(deftest intermediate-ft-deep-round-trip
  (run-check "intermediate-ft-deep-round-trip"))

(deftest intermediate-literal-bottom-out-reduces-items
  (run-check "intermediate-literal-bottom-out-reduces-items"))

(deftest intermediate-hamt-entry-round-trip
  (run-check "intermediate-hamt-entry-round-trip"))

(deftest pack-under-bfs-single-chunk
  (run-check "pack-under-bfs-single-chunk"))

(deftest long-string-pack-covers-explorer-preview-prefix
  (run-check "long-string-pack-covers-explorer-preview-prefix"))

(deftest pack-under-budget-zero-is-single-item
  (run-check "pack-under-budget-zero-is-single-item"))

(deftest find-chunk-transport-outermost-wins
  (run-check "find-chunk-transport-outermost-wins"))

(deftest intermediate-hamt-bitmap-round-trip-when-assured
  (run-check "intermediate-hamt-bitmap-round-trip-when-assured"))

(deftest host-string-is-not-truncated
  (run-check "host-string-is-not-truncated"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.store.pack-test)))))
               "every-check-has-a-deftest"))
      "pack_checks.cljc and this file list the same tests"))
