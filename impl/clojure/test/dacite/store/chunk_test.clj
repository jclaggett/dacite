(ns dacite.store.chunk-test
  "Pack-literal durable layout (the default for s/file and s/lmdb) on the JVM.

   The bodies are `dacite.store.chunk-checks`, with the JVM file and LMDB
   stores. nbb runs the same checks on its own (`npm run store-units:test`).
   One deftest each, so the JVM suite still names the test it ran before."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dacite.store-test :as store-test]
            [dacite.store.check :as check]
            [dacite.store.chunk-checks :as checks]
            [dacite.store.jvm :as jvm]))

(defn- edn-file-sizes
  "The byte size of each .edn file under `dir`."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile ^java.io.File %))
       (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
       (mapv #(.length ^java.io.File %))))

(def env
  "The JVM stores and file calls the checks use."
  (assoc store-test/env
         :edn-file-sizes edn-file-sizes
         :lmdb-entries (fn [st] (:entries (jvm/lmdb-db-stat st)))))

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest seed-catalog-flushes-to-pack-items
  (run-check "seed-catalog-flushes-to-pack-items"))

(deftest seed-catalog-round-trip-hash-and-page
  (run-check "seed-catalog-round-trip-hash-and-page"))

(deftest title-native-does-not-hydrate-the-book-body
  (run-check "title-native-does-not-hydrate-the-book-body"))

(deftest absorbed-leaf-is-not-an-inner-key
  (run-check "absorbed-leaf-is-not-an-inner-key"))

(deftest chunked-edn-smaller-than-live-exploded
  (run-check "chunked-edn-smaller-than-live-exploded"))

(deftest chunked-file-smaller-than-exploded-file
  (run-check "chunked-file-smaller-than-exploded-file"))

(deftest app-file-store-flushes-pack-items-on-cas
  (run-check "app-file-store-flushes-pack-items-on-cas"))

(deftest app-lmdb-store-flushes-pack-items-on-cas
  (run-check "app-lmdb-store-flushes-pack-items-on-cas"))

(deftest durable-stores-have-their-own-budget
  (run-check "durable-stores-have-their-own-budget"))

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.store.chunk-test)))))
               "every-check-has-a-deftest"))
      "chunk_checks.cljc and this file list the same tests"))
