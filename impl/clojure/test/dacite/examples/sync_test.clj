(ns dacite.examples.sync-test
  "Directory sync: list without bodies; one-file fetch < siblings; second copy is cheap."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [dacite.examples.sync :as fs]
            [dacite.service :as svc]
            [dacite.store :as store]
            [dacite.store.remote :as remote]
            [dacite.store.stats :as stats]
            [dacite.value :as v]))

(def sample-root-hex
  "fe0532ab564af43a7fb7c94541eaf63d63fa70d24e1290e6899747a571f4f196")

(deftest sample-tree-shares-identical-blobs
  (let [r (v/root-ref (fs/open-mem))
        [t _] (fs/load-or-seed! r)
        m (fs/measure-sharing t)]
    (is (= sample-root-hex (store/hash->hex (v/dacite-hash t))))
    (is (= #{"readme.txt" "copy.txt" "data.bin" "sub"} (set (:names m))))
    (is (true? (:shared-blob? m)))
    (is (= 13 (:readme-bytes m)))
    (is (= 256 (:data-bytes m)))
    (is (fs/dir? (fs/lookup t ["sub"])))
    (is (pos? (fs/entry-size (fs/lookup t ["sub" "note.txt"]))))))

(deftest ls-does-not-need-as-bytes
  (let [r (v/root-ref (fs/open-mem))
        [t _] (fs/load-or-seed! r)
        rows (fs/list-entries t)]
    (is (some #(= "readme.txt" (:name %)) rows))
    (is (every? #(or (= "dir" (:kind %)) (pos? (:size %))) rows))))

(deftest cat-round-trip
  (let [r (v/root-ref (fs/open-mem))
        [t _] (fs/load-or-seed! r)
        bs (fs/cat-file t ["readme.txt"])]
    (is (= "hello dacite\n" (String. ^bytes bs "UTF-8")))))

(deftest second-local-sync-copies-nothing
  (let [src (fs/open-mem)
        dest (store/mem-store)]
    (fs/load-or-seed! (v/root-ref src))
    (let [h (store/root src)
          first (store/sync-reachable! src dest h)
          second (store/sync-reachable! src dest h)]
      (is (pos? (:copied first)))
      (is (zero? (:copied second)))
      (is (pos? (:skipped second)))
      (is (= :nodes (:via first))))))

(deftest missing-blob-is-catchable
  (let [empty (store/mem-store)
        ghost (v/blob-via empty (byte-array [1]))]
    (store/s-delete empty (v/dacite-hash ghost))
    (try
      (v/as-bytes ghost)
      (is false "expected missing-blob throw")
      (catch clojure.lang.ExceptionInfo e
        (is (true? (:dacite/missing (ex-data e))))))))

(deftest remote-one-blob-cheaper-than-all-blobs
  (let [rooted (svc/make-demo-rooted)
        {:keys [base-url stop!]} (svc/start-server! {:port 0 :rooted rooted})]
    (try
      (let [writer (v/root-ref (store/remote-rooted-store base-url))]
        (fs/load-or-seed! writer)
        (let [tree (v/ref-deref writer)
              h-readme (v/dacite-hash (fs/entry-blob (fs/lookup tree ["readme.txt"])))
              h-note (v/dacite-hash (fs/entry-blob (fs/lookup tree ["sub" "note.txt"])))
              h-data (v/dacite-hash (fs/entry-blob (fs/lookup tree ["data.bin"])))]
          (stats/reset-stats!)
          (let [one (:delta
                     (stats/measure
                      #(store/s-get (remote/remote-store base-url) h-readme)))
                _ (stats/reset-stats!)
                all (:delta
                     (stats/measure
                      (fn []
                        (let [r (remote/remote-store base-url)]
                          (store/s-get r h-readme)
                          (store/s-get r h-note)
                          (store/s-get r h-data)))))]
            (is (pos? (:bytes-recv one)))
            (is (< (:bytes-recv one) (:bytes-recv all))
                "fetching one file must not pull sibling blobs"))))
      (finally
        (stop!)))))

(deftest file-reopen-keeps-tree
  (let [dir (io/file (str "target/dacite-sync-test-" (System/nanoTime)))]
    (try
      (let [r1 (v/root-ref (fs/open-file (.getPath dir)))]
        (fs/load-or-seed! r1)
        (let [h1 (v/dacite-hash (v/ref-deref r1))
              r2 (v/root-ref (fs/open-file (.getPath dir)))
              loaded (v/ref-deref r2)]
          (is (= h1 (v/dacite-hash loaded)))
          (is (= "hello dacite\n"
                 (String. ^bytes (fs/cat-file loaded ["readme.txt"]) "UTF-8")))))
      (finally
        (fs/reset-store-dir! (.getPath dir))))))

(deftest ingest-export-roundtrip
  (let [src (io/file (str "target/dacite-sync-src-" (System/nanoTime)))
        dest (io/file (str "target/dacite-sync-out-" (System/nanoTime)))]
    (try
      (.mkdirs (io/file src "d"))
      (spit (io/file src "a.txt") "aaa")
      (spit (io/file src "b.txt") "aaa")
      (spit (io/file src "d/c.txt") "ccc")
      (let [r (v/root-ref (fs/open-mem))
            t (fs/ingest-tree r (.getPath src))]
        (is (= #{"a.txt" "b.txt" "d"} (set (map :name (fs/list-entries t)))))
        (is (= (fs/blob-hash (fs/lookup t ["a.txt"]))
               (fs/blob-hash (fs/lookup t ["b.txt"])))
            "identical host files share a blob")
        (fs/emit-tree t (.getPath dest))
        (is (= "aaa" (slurp (io/file dest "a.txt"))))
        (is (= "ccc" (slurp (io/file dest "d/c.txt")))))
      (finally
        (doseq [d [dest src]]
          (when (.exists d)
            (doseq [f (reverse (file-seq d))]
              (.delete f))))))))
