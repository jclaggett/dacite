(ns dacite.store-test
  "Content-addressed storage tests on the JVM.

   The bodies are `dacite.store-checks`, with the JVM file and LMDB stores.
   nbb runs the same checks on its file and LMDB stores
   (`npm run storage:test`). One deftest each, so the JVM suite still names
   the test it ran before. `await-future-test` is JVM only: a JVM future is
   the only host promise `store/await` reads besides a settled one."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dacite.store :as store]
            [dacite.store-checks :as checks]
            [dacite.store.jvm :as sj])
  (:import [java.nio ByteBuffer]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.lmdbjava Dbi Env]))

(defn- lmdb-raw
  "The bytes under hash `h` in the content DB of LMDB store `st`, or nil."
  [st h]
  (let [^Env env (:env st)
        ^Dbi db (:db st)]
    (with-open [txn (.txnRead env)]
      (let [k (doto (ByteBuffer/allocateDirect 32)
                (.putLong (long (nth h 0)))
                (.putLong (long (nth h 1)))
                (.putLong (long (nth h 2)))
                (.putLong (long (nth h 3)))
                (.flip))]
        (when-let [^ByteBuffer buf (.get db txn k)]
          (let [bs (byte-array (.remaining buf))]
            (.get buf bs)
            bs))))))

(def env
  "The JVM stores and file calls the checks use."
  {:file-store sj/file-store
   :lmdb-store sj/lmdb-store
   :lmdb-close sj/lmdb-close
   :write-batch sj/write-batch
   :lmdb-root-cell sj/lmdb-root-cell
   :lmdb-raw lmdb-raw
   :to-ubytes (fn [^bytes bs] (mapv #(bit-and 0xff %) bs))
   :join (fn [base & parts] (str (apply io/file base parts)))
   :exists? (fn [p] (.exists (io/file p)))
   :dir? (fn [p] (.isDirectory (io/file p)))
   :tmp-dir (fn [prefix]
              (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))
   :rm-rf (fn [p]
            (doseq [^java.io.File f (reverse (file-seq (io/file p)))]
              (.delete f)))})

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (checks/run-one env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms checks/max-ms) (str name " took " ms " ms"))))

(deftest hash-hex-roundtrip-test
  (run-check "hash-hex-roundtrip-test"))

(deftest mem-store-basic-test
  (run-check "mem-store-basic-test"))

(deftest mem-store-entries-test
  (run-check "mem-store-entries-test"))

(deftest mem-store-reset-test
  (run-check "mem-store-reset-test"))

(deftest file-store-basic-test
  (run-check "file-store-basic-test"))

(deftest file-store-persistence-test
  (run-check "file-store-persistence-test"))

(deftest file-store-sharding-test
  (run-check "file-store-sharding-test"))

(deftest file-store-entries-test
  (run-check "file-store-entries-test"))

(deftest file-store-reset-test
  (run-check "file-store-reset-test"))

(deftest file-store-creates-dir-test
  (run-check "file-store-creates-dir-test"))

(deftest layered-store-write-all-test
  (run-check "layered-store-write-all-test"))

(deftest layered-store-with-file-test
  (run-check "layered-store-with-file-test"))

(deftest layered-store-reset-test
  (run-check "layered-store-reset-test"))

(deftest lmdb-store-basic-test
  (run-check "lmdb-store-basic-test"))

(deftest lmdb-store-persistence-test
  (run-check "lmdb-store-persistence-test"))

(deftest lmdb-store-entries-test
  (run-check "lmdb-store-entries-test"))

(deftest lmdb-store-batch-test
  (run-check "lmdb-store-batch-test"))

(deftest lmdb-store-reset-test
  (run-check "lmdb-store-reset-test"))

(deftest lmdb-store-complex-values-test
  (run-check "lmdb-store-complex-values-test"))

(deftest lmdb-store-with-layered-test
  (run-check "lmdb-store-with-layered-test"))

(deftest lmdb-value-layer-scalar-and-collection-persist
  (run-check "lmdb-value-layer-scalar-and-collection-persist"))

(deftest lmdb-rooted-value-layer-persist
  (run-check "lmdb-rooted-value-layer-persist"))

(deftest lmdb-content-is-wire-v1-node-payload
  (run-check "lmdb-content-is-wire-v1-node-payload"))

(deftest content-addressing-test
  (run-check "content-addressing-test"))

(deftest missing-node-fails-walk-test
  (run-check "missing-node-fails-walk-test"))

(deftest await-rejected-is-unavailable-test
  (run-check "await-rejected-is-unavailable-test"))

;; JVM only. A future is a JVM host promise; nbb has none.
(deftest await-future-test
  (testing "await reads a JVM future, including nil"
    (is (= 42 (store/await (future 42))))
    (is (nil? (store/await (future nil)))))
  (testing "await unwraps a future that failed as :unavailable"
    (let [h [1 2 3 4]
          e (try
              (store/await
               (future
                 (throw (ex-info "store unavailable"
                                 {:reason :unavailable :hash h :cause :down}))))
              nil
              (catch clojure.lang.ExceptionInfo ex
                ex))]
      (is (= :unavailable (:reason (ex-data e))))
      (is (= h (:hash (ex-data e)))))))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.store-test)))))
               "every-check-has-a-deftest"
               "await-future-test"))
      "store_checks.cljc and this file list the same tests"))
