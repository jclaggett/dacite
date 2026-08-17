(ns dacite.examples.notes-test
  "Versioned notes: restore reuses a snapshot hash; title-only edits share the body."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [dacite.examples.notes :as notes]
            [dacite.value :as v]))

(deftest seed-and-edit
  (let [r (v/root-ref (notes/open-mem))
        [nb seeded?] (notes/load-or-seed! r)]
    (is (true? seeded?))
    (is (= "Welcome" (notes/title (notes/current-doc nb))))
    (is (= 1 (notes/version-count nb)))
    (let [nb' (v/ref-swap! r notes/set-title "Hello")]
      (is (= "Hello" (notes/title (notes/current-doc nb'))))
      (is (= 2 (notes/version-count nb')))
      (is (= "Welcome" (notes/title (notes/doc-at nb' 1)))))))

(deftest restore-reuses-snapshot-hash
  (let [r (v/root-ref (notes/open-mem))
        [nb _] (notes/load-or-seed! r)
        h0 (v/dacite-hash (notes/current-doc nb))]
    (v/ref-swap! r notes/set-title "Once")
    (v/ref-swap! r notes/set-title "Twice")
    (let [nb' (v/ref-swap! r notes/restore 2)]
      (is (= h0 (v/dacite-hash (notes/current-doc nb')))
          "restore installs the historical value, not a rewrite")
      (is (= "Welcome" (notes/title (notes/current-doc nb'))))
      (is (= 4 (notes/version-count nb'))))))

(deftest title-only-shares-body
  (let [r (v/root-ref (notes/open-mem))
        [nb _] (notes/load-or-seed! r)
        body-h (notes/field-hash (notes/current-doc nb) "body")
        nb' (notes/set-title nb "New title")
        m (notes/measure-sharing nb)]
    (is (= body-h (notes/field-hash (notes/current-doc nb') "body")))
    (is (true? (:body-shared? m)))
    (is (pos? (:title-only m)))
    (is (pos? (:body-rewrite m)))
    (is (< (:title-only m) (:body-rewrite m))
        "rewriting the body should add more store nodes than a title change")))

(deftest diff-sees-title-not-body
  (let [r (v/root-ref (notes/open-mem))
        [nb _] (notes/load-or-seed! r)
        nb' (notes/set-title nb "Renamed")]
    (is (= ["title" "edited-at"] (notes/diff-keys (notes/current-doc nb')
                                                  (notes/doc-at nb' 1))))))

(deftest file-reopen-keeps-history
  (let [dir (io/file (str "target/dacite-notes-test-" (System/nanoTime)))]
    (try
      (let [r1 (v/root-ref (notes/open-file (.getPath dir)))]
        (notes/load-or-seed! r1)
        (v/ref-swap! r1 notes/set-title "Persisted")
        (let [h1 (v/dacite-hash (v/ref-deref r1))
              r2 (v/root-ref (notes/open-file (.getPath dir)))
              loaded (v/ref-deref r2)]
          (is (= h1 (v/dacite-hash loaded)))
          (is (= "Persisted" (notes/title (notes/current-doc loaded))))
          (is (= 2 (notes/version-count loaded)))))
      (finally
        (notes/reset-store-dir! (.getPath dir))))))
