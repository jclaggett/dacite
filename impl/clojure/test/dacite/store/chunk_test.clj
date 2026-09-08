(ns dacite.store.chunk-test
  "Pack-literal durable layout — default for s/file and s/lmdb."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dacite.examples.library :as lib]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.chunk :as chunk]
            [dacite.store.file :as file]
            [dacite.store.jvm :as jvm]
            [dacite.store.pack :as pack]
            [dacite.value :as v]))

(defn- edn-files [^java.io.File dir]
  (->> (file-seq dir)
       (filter #(.isFile ^java.io.File %))
       (filter #(.endsWith (.getName ^java.io.File %) ".edn"))))

(defn- dir-bytes [^java.io.File dir]
  (reduce + 0 (map #(.length ^java.io.File %) (edn-files dir))))

(defn- copy-live
  "Exploded store of only hashes reachable from root-h."
  [src root-h dest]
  (doseq [hk (gc/mark-reachable src root-h)]
    (let [h (gc/->hash hk)]
      (store/s-put dest h (store/s-get src h))))
  dest)

(deftest seed-catalog-flushes-to-pack-items
  (let [cs (chunk/chunked (store/mem-store))
        catalog (lib/seed-library cs)
        h (v/hash catalog)
        debris (:entries (chunk/overlay-stats cs))
        live (chunk/live-count cs h)
        packed (pack/encode-reachable (chunk/overlay cs) h #{} (chunk/budget cs))
        flushed (chunk/flush! cs h)
        inner (chunk/inner-stats cs)]
    (println "chunk layout (seed catalog)"
             {:overlay-debris debris
              :live-exploded live
              :chunked-entries (:entries inner)
              :chunked-edn-bytes (:edn-bytes inner)
              :flush flushed})
    (is (= (count (:items packed)) (:items flushed) (:entries inner)))
    (is (= 48 (:entries inner))
        "encode-reachable at 1024 for the seed catalog")
    (is (< (:entries inner) live))
    (is (< live debris))))

(deftest seed-catalog-round-trip-hash-and-page
  (let [cs (chunk/chunked (store/mem-store))
        catalog (lib/seed-library cs)
        h (v/hash catalog)
        page0 (lib/chapter-page (v/nth (lib/title-index catalog) 0) 0 0 80)]
    (chunk/flush! cs h)
    (let [cs2 (chunk/chunked (chunk/inner cs))
          loaded (v/get-value cs2 h)
          book (v/nth (lib/title-index loaded) 0)]
      (is (= h (v/hash loaded)))
      (is (true? (lib/library-root? loaded)))
      (is (= lib/seed-title (lib/book-title book)))
      (is (= page0 (lib/chapter-page book 0 0 80))))))

(deftest title-native-does-not-hydrate-the-book-body
  (let [cs (chunk/chunked (store/mem-store))
        catalog (lib/seed-library cs)
        h (v/hash catalog)]
    (chunk/flush! cs h)
    (let [cs2 (chunk/chunked (chunk/inner cs))
          loaded (v/get-value cs2 h)
          book (v/nth (lib/title-index loaded) 0)
          title (lib/book-title book)
          overlay-n (:entries (chunk/overlay-stats cs2))]
      (is (= lib/seed-title title))
      (is (< overlay-n (v/count (lib/book-text book)))
          "title read must not explode the 1716-char string"))))

(deftest absorbed-leaf-is-not-an-inner-key
  (let [cs (chunk/chunked (store/mem-store))
        catalog (lib/seed-library cs)
        h (v/hash catalog)
        ch-h (v/hash (v/nth (lib/book-text (v/nth (lib/title-index catalog) 0)) 0))]
    (chunk/flush! cs h)
    (is (false? (store/s-has? (chunk/inner cs) ch-h))
        "a character leaf is covered by an ft/digit literal")
    (let [cs2 (chunk/chunked (chunk/inner cs))
          loaded (v/get-value cs2 h)
          book (v/nth (lib/title-index loaded) 0)
          page (lib/chapter-page book 0 0 40)]
      (is (seq page))
      (is (true? (store/s-has? (chunk/overlay cs2) ch-h))))))

(deftest chunked-edn-smaller-than-live-exploded
  (let [plain (store/mem-store)
        catalog (lib/seed-library plain)
        h (v/hash catalog)
        live (store/mem-store)
        _ (copy-live plain h live)
        live-bytes (reduce + 0 (map #(count (pr-str %))
                                    (vals (store/s-snapshot live))))
        cs (chunk/chunked (store/mem-store))
        c2 (lib/seed-library cs)
        _ (chunk/flush! cs (v/hash c2))
        inner (chunk/inner-stats cs)]
    (println "edn bytes live-exploded" live-bytes
             "chunked" (:edn-bytes inner)
             "ratio" (format "%.2f" (double (/ live-bytes (max 1 (:edn-bytes inner))))))
    (is (< (:edn-bytes inner) live-bytes))
    (is (< (:entries inner) (count (store/s-snapshot live))))))

(deftest chunked-file-smaller-than-exploded-file
  (let [dir-e (io/file (str "target/dacite-chunk-exploded-" (System/nanoTime)))
        dir-c (io/file (str "target/dacite-chunk-inner-" (System/nanoTime)))]
    (try
      (.mkdirs dir-e)
      (.mkdirs dir-c)
      (let [exploded (file/file-store (.getPath dir-e))
            catalog (lib/seed-library exploded)
            e-files (count (edn-files dir-e))
            e-bytes (dir-bytes dir-e)
            cs (chunk/chunked (file/file-store (.getPath dir-c)))
            catalog2 (lib/seed-library cs)
            _ (chunk/flush! cs (v/hash catalog2))
            c-files (count (edn-files dir-c))
            c-bytes (dir-bytes dir-c)]
        (println "file store seed catalog"
                 {:exploded-files e-files :exploded-bytes e-bytes
                  :chunked-files c-files :chunked-bytes c-bytes})
        (is (= 48 c-files))
        (is (< c-files e-files))
        (is (< c-bytes e-bytes)))
      (finally
        (doseq [d [dir-e dir-c]
                f (reverse (file-seq d))]
          (.delete ^java.io.File f))))))

(deftest app-file-store-flushes-pack-items-on-cas
  (let [dir (io/file (str "target/dacite-chunk-app-file-" (System/nanoTime)))]
    (try
      (let [r (v/root (store/file (.getPath dir)))]
        (lib/load-or-seed! r)
        (is (= 48 (count (edn-files dir)))
            "s/file persists pack items, not construction debris")
        (let [r2 (v/root (store/file (.getPath dir)))
              loaded (v/deref r2)
              book (v/nth (lib/title-index loaded) 0)]
          (is (true? (lib/library-root? loaded)))
          (is (= lib/seed-title (lib/book-title book)))
          (is (seq (lib/chapter-page book 0 0 40)))))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete ^java.io.File f))))))

(deftest app-lmdb-store-flushes-pack-items-on-cas
  (let [dir (io/file (str "target/dacite-chunk-app-lmdb-" (System/nanoTime)))
        path (.getPath dir)]
    (.mkdirs dir)
    (try
      (let [rs (store/lmdb path)
            inner (chunk/inner (:content rs))]
        (try
          (lib/load-or-seed! (v/root rs))
          (is (= 48 (:entries (jvm/lmdb-db-stat inner))))
          (finally
            (store/lmdb-close inner))))
      (let [rs2 (store/lmdb path)
            inner2 (chunk/inner (:content rs2))]
        (try
          (let [loaded (v/deref (v/root rs2))
                book (v/nth (lib/title-index loaded) 0)]
            (is (true? (lib/library-root? loaded)))
            (is (= lib/seed-title (lib/book-title book)))
            (is (seq (lib/chapter-page book 0 0 40))))
          (finally
            (store/lmdb-close inner2))))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete ^java.io.File f))))))
