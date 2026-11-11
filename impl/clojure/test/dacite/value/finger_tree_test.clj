(ns dacite.value.finger-tree-test
  "Store-backed finger trees with implicit leaf singles, on the JVM.

   The bodies are `dacite.value.finger-tree-checks`. nbb runs the same
   checks (`npm run value:test`). One deftest each, so the JVM suite still
   names the test it ran before."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store.check :as check]
            [dacite.value.finger-tree-checks :as checks]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks {} name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest empty-tree
  (run-check "empty-tree"))

(deftest conj-bare-leaves-no-singles
  (run-check "conj-bare-leaves-no-singles"))

(deftest vector-value-hash-stable-without-singles
  (run-check "vector-value-hash-stable-without-singles"))

(deftest bare-leaf-root-ops
  (run-check "bare-leaf-root-ops"))

(deftest digit-of-bare-leaves
  (run-check "digit-of-bare-leaves"))

(deftest reject-structural-ft-as-leaf
  (run-check "reject-structural-ft-as-leaf"))

(deftest large-conj-and-nth
  (run-check "large-conj-and-nth"))

(deftest long-string-seq-matches-count
  (run-check "long-string-seq-matches-count"))

(deftest concat-and-rest
  (run-check "concat-and-rest"))

(deftest remove-nth-middle
  (run-check "remove-nth-middle"))

(deftest entry-density-no-single-per-element
  (run-check "entry-density-no-single-per-element"))

(deftest packed-char-run-digit-dual-read
  (run-check "packed-char-run-digit-dual-read"))

(deftest packed-char-repeat-digit-dual-read
  (run-check "packed-char-repeat-digit-dual-read"))

(deftest chunk-run-fills-forward-without-overshoot
  (run-check "chunk-run-fills-forward-without-overshoot"))

(deftest packed-blob-keeps-copied-bytes
  (run-check "packed-blob-keeps-copied-bytes"))

(deftest packed-string-hash-stable-and-dense
  (run-check "packed-string-hash-stable-and-dense"))

(deftest packed-i64-vector-fills-payload
  (run-check "packed-i64-vector-fills-payload"))

(deftest packed-ref-run-digit-dual-read
  (run-check "packed-ref-run-digit-dual-read"))

(deftest a-blob-costs-about-one-product-per-byte
  (run-check "a-blob-costs-about-one-product-per-byte"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.value.finger-tree-test)))))
               "every-check-has-a-deftest"))
      "value/finger_tree_checks.cljc and this file list the same tests"))
