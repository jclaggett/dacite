(ns dacite.examples.notes-checks
  "Versioned notes: restore reuses a snapshot hash; title-only edits
   share the body.
   On the JVM and on nbb.

   These were the bodies of `dacite.examples.notes-test`. The JVM runs them
   from that namespace, one deftest each under its old name; nbb runs them
   from `dacite.examples.examples-cljs-test` (`npm run examples:test`).
   `is` is `dacite.store.check`."
  (:require [dacite.store.check :refer [is]]
            [dacite.examples.notes :as notes]
            [dacite.value :as v]))

(defn seed-and-edit
  [_]
  (let [r (v/root (notes/open-mem))
        [nb seeded?] (notes/load-or-seed! r)]
    (is (true? seeded?))
    (is (= "Welcome" (notes/title (notes/current-doc nb))))
    (is (= 1 (notes/version-count nb)))
    (let [nb' (v/swap! r notes/set-title "Hello")]
      (is (= "Hello" (notes/title (notes/current-doc nb'))))
      (is (= 2 (notes/version-count nb')))
      (is (= "Welcome" (notes/title (notes/doc-at nb' 1)))))))

(defn restore-reuses-snapshot-hash
  [_]
  (let [r (v/root (notes/open-mem))
        [nb _] (notes/load-or-seed! r)
        h0 (v/hash (notes/current-doc nb))]
    (v/swap! r notes/set-title "Once")
    (v/swap! r notes/set-title "Twice")
    (let [nb' (v/swap! r notes/restore 2)]
      (is (= h0 (v/hash (notes/current-doc nb')))
          "restore installs the historical value, not a rewrite")
      (is (= "Welcome" (notes/title (notes/current-doc nb'))))
      (is (= 4 (notes/version-count nb'))))))

(defn title-only-shares-body
  [_]
  (let [r (v/root (notes/open-mem))
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

(defn diff-sees-title-not-body
  [_]
  (let [r (v/root (notes/open-mem))
        [nb _] (notes/load-or-seed! r)
        nb' (notes/set-title nb "Renamed")]
    (is (= ["title" "edited-at"] (notes/diff-keys (notes/current-doc nb')
                                                  (notes/doc-at nb' 1))))))

(defn file-reopen-keeps-history
  [_]
  (let [dir (str "target/dacite-notes-test-" #?(:clj (System/nanoTime) :cljs (.now js/Date)))]
    (try
      (let [r1 (v/root (notes/open-file dir))]
        (notes/load-or-seed! r1)
        (v/swap! r1 notes/set-title "Persisted")
        (let [h1 (v/hash (v/deref r1))
              r2 (v/root (notes/open-file dir))
              loaded (v/deref r2)]
          (is (= h1 (v/hash loaded)))
          (is (= "Persisted" (notes/title (notes/current-doc loaded))))
          (is (= 2 (notes/version-count loaded)))))
      (finally
        (notes/reset-store-dir! dir)))))

(def checks
  "Check name to check fn. The JVM deftest of each has the same name."
  {"seed-and-edit" seed-and-edit
   "restore-reuses-snapshot-hash" restore-reuses-snapshot-hash
   "title-only-shares-body" title-only-shares-body
   "diff-sees-title-not-body" diff-sees-title-not-body
   "file-reopen-keeps-history" file-reopen-keeps-history})
