(ns dacite.store.format-test
  "The store format tag of the persisted stores on the JVM: file and LMDB.

   The bodies are `dacite.store.format-checks`, with the JVM file and LMDB
   stores. nbb runs the same checks on its file and LMDB stores
   (`npm run storage:test`). One deftest each, so the JVM suite still names
   the test it ran before. The IndexedDB store is tested in the idb suite."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dacite.store-test :as store-test]
            [dacite.store.format-checks :as checks])
  (:import [java.nio ByteBuffer]
           [org.lmdbjava Dbi Env PutFlags]))

(defn- meta-key ^ByteBuffer [^String k]
  (let [bs (.getBytes k "UTF-8")]
    (doto (ByteBuffer/allocateDirect (alength bs)) (.put bs) (.flip))))

(defn- meta-format
  "The raw string under meta key \"format\", or nil."
  [st]
  (let [^Env env (:env st) ^Dbi mdb (:meta-db st)]
    (with-open [txn (.txnRead env)]
      (when-let [^ByteBuffer buf (.get mdb txn (meta-key "format"))]
        (let [bs (byte-array (.remaining buf))] (.get buf bs) (String. bs "UTF-8"))))))

(defn- set-meta-format!
  "Overwrite (string) or delete (nil) the format key."
  [st s]
  (let [^Env env (:env st) ^Dbi mdb (:meta-db st)]
    (with-open [txn (.txnWrite env)]
      (if s
        (let [bs (.getBytes ^String s "UTF-8")
              vb (doto (ByteBuffer/allocateDirect (alength bs)) (.put bs) (.flip))]
          (.put mdb txn (meta-key "format") vb (make-array PutFlags 0)))
        (.delete mdb txn (meta-key "format")))
      (.commit txn))))

(def env
  "The JVM stores and file calls the checks use."
  (assoc store-test/env
         :slurp slurp
         :spit spit
         :delete (fn [p] (.delete (io/file p)))
         :lmdb-meta-format meta-format
         :set-lmdb-meta-format! set-meta-format!))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (checks/run-one env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms checks/max-ms) (str name " took " ms " ms"))))

(deftest the-format-is-one-constant
  (run-check "the-format-is-one-constant"))

(deftest a-new-file-store-is-tagged-and-a-reopened-one-passes
  (run-check "a-new-file-store-is-tagged-and-a-reopened-one-passes"))

(deftest an-untagged-file-store-with-nodes-is-a-format-mismatch
  (run-check "an-untagged-file-store-with-nodes-is-a-format-mismatch"))

(deftest a-file-store-of-another-version-is-a-format-mismatch
  (run-check "a-file-store-of-another-version-is-a-format-mismatch"))

(deftest a-new-lmdb-store-is-tagged-and-a-reopened-one-passes
  (run-check "a-new-lmdb-store-is-tagged-and-a-reopened-one-passes"))

(deftest an-untagged-or-wrong-version-lmdb-store-is-a-format-mismatch
  (run-check "an-untagged-or-wrong-version-lmdb-store-is-a-format-mismatch"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.store.format-test)))))
               "every-check-has-a-deftest"))
      "format_checks.cljc and this file list the same tests"))
