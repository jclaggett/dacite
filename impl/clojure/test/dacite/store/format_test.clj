(ns dacite.store.format-test
  "The store format tag (`store/store-format`) of the persisted stores on the
   JVM: file and LMDB. A new store is tagged, a reopened one passes, and an
   untagged or wrong-version store is `:store-format-mismatch` (a clear error,
   not a node-hash failure). The IndexedDB store is tested in the idb suite."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dacite.store :as store]
            [dacite.store.file :as file]
            [dacite.store.jvm :as sj]
            [dacite.value :as v])
  (:import [java.nio ByteBuffer]
           [org.lmdbjava Dbi Env]))

(defn- temp-dir ^java.io.File [prefix]
  (let [d (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))]
    (.toFile d)))

(defn- rm-rf [^java.io.File d]
  (doseq [f (reverse (file-seq d))] (.delete f)))

(defn- mismatch
  "The ex-data of the :store-format-mismatch thrown by `f`, or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (when (= :store-format-mismatch (:reason (ex-data e)))
           (assoc (ex-data e) :message (ex-message e))))))

(defn- one-node
  "[hash entry] of a small value."
  []
  (let [mem (store/mem-store)
        h (v/hash (v/i64 mem 42))]
    [h (store/await (store/s-get mem h))]))

(deftest the-format-is-one-constant
  (is (integer? store/store-format))
  (is (= 2 store/store-format)))

;; ---------------------------------------------------------------------------
;; File store: {base}/FORMAT
;; ---------------------------------------------------------------------------

(deftest a-new-file-store-is-tagged-and-a-reopened-one-passes
  (let [dir (temp-dir "dacite-fmt-file")
        [h e] (one-node)]
    (try
      (let [st (file/file-store (str dir))]
        (is (= (str store/store-format) (.trim (slurp (io/file dir "FORMAT")))) "FORMAT holds the number")
        (store/await (store/s-put st h e)))
      (let [again (file/file-store (str dir))]
        (is (= e (store/await (store/s-get again h))) "reopened, data intact")
        (is (= 1 (count (store/await (store/s-keys again)))) "the tag is not a node"))
      (testing "s-reset keeps the tag"
        (let [st (file/file-store (str dir))]
          (store/await (store/s-reset st))
          (is (some? (file/file-store (str dir))))))
      (finally (rm-rf dir)))))

(deftest an-untagged-file-store-with-nodes-is-a-format-mismatch
  (let [dir (temp-dir "dacite-fmt-file")
        [h e] (one-node)]
    (try
      (store/await (store/s-put (file/file-store (str dir)) h e))
      (.delete (io/file dir "FORMAT"))     ; as a store written before tags
      (let [m (mismatch #(file/file-store (str dir)))]
        (is (some? m))
        (is (nil? (:found m)))
        (is (= store/store-format (:expected m)))
        (is (re-find #"untagged" (:message m)))
        (is (re-find #"store format" (:message m))))
      (testing "an untagged EMPTY directory is just a new store"
        (let [d2 (temp-dir "dacite-fmt-file2")]
          (try (is (some? (file/file-store (str d2))))
               (is (.exists (io/file d2 "FORMAT")))
               (finally (rm-rf d2)))))
      (finally (rm-rf dir)))))

(deftest a-file-store-of-another-version-is-a-format-mismatch
  (let [dir (temp-dir "dacite-fmt-file")]
    (try
      (file/file-store (str dir))
      (doseq [tag ["1" "3" "0" "banana"]] ; "1" is the store of the nilpotent fuse (before issue #14)
        (spit (io/file dir "FORMAT") (str tag "\n"))
        (let [m (mismatch #(file/file-store (str dir)))]
          (is (some? m) tag)
          (is (= (if (= "banana" tag) "banana" (Long/parseLong tag)) (:found m)))
          (is (re-find #"format" (:message m)))))
      (finally (rm-rf dir)))))

;; ---------------------------------------------------------------------------
;; LMDB: meta DB key "format"
;; ---------------------------------------------------------------------------

(defn- meta-key ^ByteBuffer [^String k]
  (let [bs (.getBytes k "UTF-8")]
    (doto (ByteBuffer/allocateDirect (alength bs)) (.put bs) (.flip))))

(defn- meta-format
  "The raw string under meta key \"format\", or nil."
  [st]
  (let [^Env env (:env st) ^Dbi mdb (:meta-db st)]
    (with-open [txn (.txnRead env)]
      (when-let [buf (.get mdb txn (meta-key "format"))]
        (let [bs (byte-array (.remaining buf))] (.get buf bs) (String. bs "UTF-8"))))))

(defn- set-meta-format!
  "Overwrite (string) or delete (nil) the format key."
  [st s]
  (let [^Env env (:env st) ^Dbi mdb (:meta-db st)]
    (with-open [txn (.txnWrite env)]
      (if s
        (let [bs (.getBytes ^String s "UTF-8")
              vb (doto (ByteBuffer/allocateDirect (alength bs)) (.put bs) (.flip))]
          (.put mdb txn (meta-key "format") vb (make-array org.lmdbjava.PutFlags 0)))
        (.delete mdb txn (meta-key "format")))
      (.commit txn))))

(deftest a-new-lmdb-store-is-tagged-and-a-reopened-one-passes
  (let [dir (temp-dir "dacite-fmt-lmdb")
        [h e] (one-node)]
    (try
      (let [st (sj/lmdb-store (str dir))]
        (try (is (= (str store/store-format) (meta-format st)) "meta key format holds the number")
             (store/await (store/s-put st h e))
             (finally (sj/lmdb-close st))))
      (let [again (sj/lmdb-store (str dir))]
        (try (is (= e (store/await (store/s-get again h))) "reopened, data intact")
             (finally (sj/lmdb-close again))))
      (finally (rm-rf dir)))))

(deftest an-untagged-or-wrong-version-lmdb-store-is-a-format-mismatch
  (let [dir (temp-dir "dacite-fmt-lmdb")
        [h e] (one-node)]
    (try
      (let [st (sj/lmdb-store (str dir))]
        (try (store/await (store/s-put st h e))
             (set-meta-format! st nil)       ; as a store written before tags
             (finally (sj/lmdb-close st))))
      (let [m (mismatch #(sj/lmdb-store (str dir)))]
        (is (some? m))
        (is (nil? (:found m)))
        (is (re-find #"untagged" (:message m))))
      ;; the failed open closed its env: the same path fails the same way, not
      ;; with an env-already-open error
      (is (nil? (:found (mismatch #(sj/lmdb-store (str dir))))))
      (testing "wrong version"
        (let [d2 (temp-dir "dacite-fmt-lmdb2")]
          (try
            (let [st (sj/lmdb-store (str d2))]
              (try (set-meta-format! st "7") (finally (sj/lmdb-close st))))
            (let [m (mismatch #(sj/lmdb-store (str d2)))]
              (is (= 7 (:found m)))
              (is (= store/store-format (:expected m))))
            (finally (rm-rf d2)))))
      (testing "an untagged EMPTY store is just a new store"
        (let [d3 (temp-dir "dacite-fmt-lmdb3")]
          (try
            (let [st (sj/lmdb-store (str d3))]
              (try (set-meta-format! st nil) (finally (sj/lmdb-close st))))
            (let [st (sj/lmdb-store (str d3))]
              (try (is (= (str store/store-format) (meta-format st)))
                   (finally (sj/lmdb-close st))))
            (finally (rm-rf d3)))))
      (finally (rm-rf dir)))))
