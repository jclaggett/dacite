(ns dacite.store.lens-test
  "Lens tests on the JVM.

   The bodies are `dacite.store.lens-checks`. nbb runs the same checks
   (`npm run store-units:test`). One deftest each, so the JVM suite still
   names the test it ran before. `concurrent-disjoint-cas` stays JVM only:
   it races two threads, and nbb has one."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store :as s]
            [dacite.store-test :as store-test]
            [dacite.store.check :as check]
            [dacite.store.lens-checks :as checks]
            [dacite.value :as v]))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks store-test/env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest empty-path-is-identity
  (run-check "empty-path-is-identity"))

(deftest nested-lens-concatenates-path
  (run-check "nested-lens-concatenates-path"))

(deftest missing-path-seeds-via-cas-from-nil
  (run-check "missing-path-seeds-via-cas-from-nil"))

(deftest disjoint-path-cas-both-succeed
  (run-check "disjoint-path-cas-both-succeed"))

(deftest same-path-cas-one-fails
  (run-check "same-path-cas-one-fails"))

(deftest swap-on-lens-does-not-clobber-sibling
  (run-check "swap-on-lens-does-not-clobber-sibling"))

(deftest watches-skip-sibling-edits
  (run-check "watches-skip-sibling-edits"))

(deftest s-get-via-lens-equals-parent
  (run-check "s-get-via-lens-equals-parent"))

(deftest vector-index-path
  (run-check "vector-index-path"))

(deftest dacite-vector-path-matches-host-seq
  (run-check "dacite-vector-path-matches-host-seq"))

(deftest dacite-value-as-map-key
  (run-check "dacite-value-as-map-key"))

(deftest file-backed-disjoint-lenses
  (run-check "file-backed-disjoint-lenses"))

;; JVM only. See the namespace docstring.

(deftest concurrent-disjoint-cas
  (let [rs (checks/seed-ab)
        la (s/lens rs ["a"])
        lb (s/lens rs ["b"])
        ha (s/root la)
        hb (s/root lb)
        a2 (v/i64 rs 10)
        b2 (v/i64 rs 20)
        start (promise)
        fa (future (deref start) (s/cas-root! la ha (v/hash a2)))
        fb (future (deref start) (s/cas-root! lb hb (v/hash b2)))]
    (deliver start true)
    (is (true? @fa))
    (is (true? @fb))
    (let [doc @(v/root rs)]
      (is (= 10 (v/realize (v/get doc "a"))))
      (is (= 20 (v/realize (v/get doc "b")))))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.store.lens-test)))))
               "every-check-has-a-deftest"
               "concurrent-disjoint-cas"))
      "lens_checks.cljc and this file list the same tests"))
