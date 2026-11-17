(ns dacite.examples.sync-checks
  "Directory sync: list without bodies; a second copy is cheap; a host
   tree round-trips. File calls are host pieces in `env`, on the JVM and on nbb.

   These were the bodies of `dacite.examples.sync-test`. The JVM runs them
   from that namespace, one deftest each under its old name; nbb runs them
   from `dacite.examples.examples-cljs-test` (`npm run examples:test`).
   `is` is `dacite.store.check`."
  (:require [dacite.store.check :refer [is]]
            [dacite.examples.sync :as fs]
            [dacite.host :as host]
            [dacite.store :as store]
            [dacite.value :as v]))

(def sample-root-hex
  "f0d28f419e2e147d26054c5b442e85b309f0985c9a1c5125e1439d1187a725ae")

(defn sample-tree-shares-identical-blobs
  [_]
  (let [r (v/root (fs/open-mem))
        [t _] (fs/load-or-seed! r)
        m (fs/measure-sharing t)]
    (is (= sample-root-hex (store/hash->hex (v/hash t))))
    (is (= #{"readme.txt" "copy.txt" "data.bin" "sub"} (set (:names m))))
    (is (true? (:shared-blob? m)))
    (is (= 13 (:readme-bytes m)))
    (is (= 256 (:data-bytes m)))
    (is (fs/dir? (fs/lookup t ["sub"])))
    (is (pos? (fs/entry-size (fs/lookup t ["sub" "note.txt"]))))))

(defn ls-does-not-need-as-bytes
  [_]
  (let [r (v/root (fs/open-mem))
        [t _] (fs/load-or-seed! r)
        rows (fs/list-entries t)]
    (is (some #(= "readme.txt" (:name %)) rows))
    (is (every? #(or (= "dir" (:kind %)) (pos? (:size %))) rows))))

(defn cat-round-trip
  [_]
  (let [r (v/root (fs/open-mem))
        [t _] (fs/load-or-seed! r)
        bs (fs/cat-file t ["readme.txt"])]
    (is (= "hello dacite\n" (host/utf8-decode bs)))))

(defn second-local-sync-copies-nothing
  [_]
  (let [src (fs/open-mem)
        dest (store/mem-store)]
    (fs/load-or-seed! (v/root src))
    (let [h (store/root src)
          first (store/sync-reachable! src dest h)
          second (store/sync-reachable! src dest h)]
      (is (pos? (:copied first)))
      (is (zero? (:copied second)))
      (is (pos? (:skipped second)))
      (is (= :nodes (:via first))))))

(defn missing-blob-is-catchable
  [_]
  (let [empty (store/mem-store)
        ghost (v/blob empty #?(:clj (byte-array [1]) :cljs (js/Uint8Array.from #js [1])))]
    (store/await (store/s-delete empty (v/hash ghost)))
    (try
      (v/as-bytes ghost)
      (is false "expected missing-blob throw")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
        (is (true? (:dacite/missing (ex-data e))))))))

(defn file-reopen-keeps-tree
  [_]
  (let [dir (str "target/dacite-sync-test-" #?(:clj (System/nanoTime) :cljs (.now js/Date)))]
    (try
      (let [r1 (v/root (fs/open-file dir))]
        (fs/load-or-seed! r1)
        (let [h1 (v/hash (v/deref r1))
              r2 (v/root (fs/open-file dir))
              loaded (v/deref r2)]
          (is (= h1 (v/hash loaded)))
          (is (= "hello dacite\n"
                 (host/utf8-decode (fs/cat-file loaded ["readme.txt"]))))))
      (finally
        (fs/reset-store-dir! dir)))))

(defn ingest-export-roundtrip
  "`env` has the host file calls: `:mkdirs`, `:spit`, `:slurp` and `:rm-rf`
   (paths are strings joined with /)."
  [{:keys [mkdirs spit slurp rm-rf]}]
  (let [src (str "target/dacite-sync-src-" #?(:clj (System/nanoTime) :cljs (.now js/Date)))
        dest (str "target/dacite-sync-out-" #?(:clj (System/nanoTime) :cljs (.now js/Date)))]
    (try
      (mkdirs (str src "/d"))
      (spit (str src "/a.txt") "aaa")
      (spit (str src "/b.txt") "aaa")
      (spit (str src "/d/c.txt") "ccc")
      (let [r (v/root (fs/open-mem))
            t (fs/ingest-tree r src)]
        (is (= #{"a.txt" "b.txt" "d"} (set (map :name (fs/list-entries t)))))
        (is (= (fs/blob-hash (fs/lookup t ["a.txt"]))
               (fs/blob-hash (fs/lookup t ["b.txt"])))
            "identical host files share a blob")
        (fs/emit-tree t dest)
        (is (= "aaa" (slurp (str dest "/a.txt"))))
        (is (= "ccc" (slurp (str dest "/d/c.txt")))))
      (finally
        (rm-rf dest)
        (rm-rf src)))))

(def checks
  "Check name to check fn. The JVM deftest of each has the same name."
  {"sample-tree-shares-identical-blobs" sample-tree-shares-identical-blobs
   "ls-does-not-need-as-bytes" ls-does-not-need-as-bytes
   "cat-round-trip" cat-round-trip
   "second-local-sync-copies-nothing" second-local-sync-copies-nothing
   "missing-blob-is-catchable" missing-blob-is-catchable
   "file-reopen-keeps-tree" file-reopen-keeps-tree
   "ingest-export-roundtrip" ingest-export-roundtrip})
