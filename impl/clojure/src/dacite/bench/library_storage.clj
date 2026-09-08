(ns dacite.bench.library-storage
  "File + LMDB size of the library seed catalog.

   Layouts:
     :snapshot      — seed as-is (construction debris still present)
     :gc-live       — collect-garbage! from the catalog hash, in place
     :gc-live-fresh — reachable exploded nodes copied into a new store
                      (LMDB compact equivalent; file GC already deletes)
     :chunked       — pack-literal flush at budget 1024 into the inner store

   File data-file-bytes = sum of .edn file lengths.
   LMDB used-bytes = content DBI page-size × (branch + leaf + overflow).
   data.mdb does not shrink on delete; gc-live-fresh is the fair LMDB size.

   Chunked LMDB is not the default lmdb-store (node payloads only). This
   bench writes pack items as wire-v1 encode-item bytes so used-pages are
   real, then closes the env. Do not default file/LMDB to chunked from
   these numbers alone.

     cd impl/clojure
     clojure -M:library-storage
     clojure -M:library-storage -- --out target/library-storage.edn"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [dacite.examples.library :as lib]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.chunk :as chunk]
            [dacite.store.file :as file]
            [dacite.store.jvm :as jvm]
            [dacite.store.pack :as pack]
            [dacite.value :as v]
            [dacite.wire.binary :as bin])
  (:import [java.nio ByteBuffer]
           [org.lmdbjava PutFlags])
  (:gen-class))

(def budget
  pack/default-budget)

(def lmdb-map-size
  "Small enough that data.mdb is not the 1GB default."
  (* 8 1024 1024))

(defn- delete-tree
  [dir]
  (when (and dir (.exists (io/file dir)))
    (doseq [f (reverse (file-seq (io/file dir)))]
      (.delete ^java.io.File f))))

(defn- edn-files
  [^java.io.File dir]
  (->> (file-seq dir)
       (filter #(.isFile ^java.io.File %))
       (filter #(.endsWith (.getName ^java.io.File %) ".edn"))))

(defn- file-data-bytes
  [dir]
  (reduce + 0 (map #(.length ^java.io.File %) (edn-files (io/file dir)))))

(defn- copy-live
  [src root-h dest]
  (doseq [hk (gc/mark-reachable src root-h)]
    (let [h (gc/->hash hk)]
      (store/s-put dest h (store/s-get src h))))
  dest)

(defn- value-wire-bytes
  [v]
  (cond
    (chunk/pack-item? v)
    (bin/pack-item-wire-bytes v)

    (and (vector? v) (= 2 (count v)) (string? (first v)))
    (bin/byte-len (bin/encode-node-bytes v))

    :else
    (count (pr-str v))))

(defn- store-wire-bytes
  [st]
  (reduce + 0 (map value-wire-bytes (vals (store/s-snapshot st)))))

(defn- file-measure
  [dir st extras]
  (merge {:backend :file
          :entries (count (store/s-snapshot st))
          :data-file-bytes (file-data-bytes dir)
          :file-count (count (edn-files (io/file dir)))
          :wire-bytes (store-wire-bytes st)}
         extras))

(defn- lmdb-measure
  [path st extras]
  (let [stat (jvm/lmdb-db-stat st)
        mdb (.length (io/file path "data.mdb"))]
    (merge {:backend :lmdb
            :entries (:entries stat)
            :used-bytes (:used-bytes stat)
            :data-file-bytes mdb
            :page-size (:page-size stat)
            :map-size (:map-size stat)
            :wire-bytes (try (store-wire-bytes st)
                             (catch Exception _ nil))}
           extras)))

(defn- with-file-dir
  [tag f]
  (let [dir (io/file (str "target/dacite-lib-storage-file-" tag "-"
                          (System/nanoTime)))]
    (try
      (.mkdirs dir)
      (f (.getPath dir))
      (finally
        (delete-tree dir)))))

(defn- with-lmdb-dir
  [tag f]
  (let [dir (io/file (str "target/dacite-lib-storage-lmdb-" tag "-"
                          (System/nanoTime)))]
    (try
      (.mkdirs dir)
      (f (.getPath dir))
      (finally
        (delete-tree dir)))))

(defn- hash->direct-bb
  ^ByteBuffer [h]
  (let [buf (ByteBuffer/allocateDirect 32)]
    (.putLong buf (long (nth h 0)))
    (.putLong buf (long (nth h 1)))
    (.putLong buf (long (nth h 2)))
    (.putLong buf (long (nth h 3)))
    (.flip buf)))

(defn- bytes->direct-bb
  ^ByteBuffer [^bytes bs]
  (let [buf (ByteBuffer/allocateDirect (alength bs))]
    (.put buf bs)
    (.flip buf)))

(defn- put-pack-items-lmdb
  "Write pack items as wire-v1 encode-item bytes into a fresh LMDB env.
   Not the default node-payload codec."
  [path items]
  (let [st (jvm/lmdb-store path {:max-size lmdb-map-size})
        env (:env st)
        db (:db st)]
    (with-open [txn (.txnWrite env)]
      (doseq [item items]
        (let [h (store/hex->hash (:hash item))
              k (hash->direct-bb h)
              v (bytes->direct-bb
                 (bin/encode-item (bin/pack-item->wire-item item)))]
          (.put db txn k v (make-array PutFlags 0))))
      (.commit txn))
    st))

;; -----------------------------------------------------------------------------
;; File layouts
;; -----------------------------------------------------------------------------

(defn- file-snapshot
  [dir]
  (let [st (file/file-store dir)
        catalog (lib/seed-library st)]
    (file-measure dir st {:layout :snapshot :root-hash (v/hash catalog)})))

(defn- file-gc-live
  [dir]
  (let [st (file/file-store dir)
        catalog (lib/seed-library st)
        h (v/hash catalog)
        gc-res (gc/collect-garbage! st h)]
    (file-measure dir st {:layout :gc-live :root-hash h :gc gc-res})))

(defn- file-chunked
  [dir]
  (let [inner (file/file-store dir)
        cs (chunk/chunked inner {:budget budget})
        catalog (lib/seed-library cs)
        h (v/hash catalog)
        flushed (chunk/flush! cs h)]
    (file-measure dir inner {:layout :chunked :root-hash h :flush flushed})))

;; -----------------------------------------------------------------------------
;; LMDB layouts
;; -----------------------------------------------------------------------------

(defn- lmdb-snapshot
  [dir]
  (let [st (jvm/lmdb-store dir {:max-size lmdb-map-size})]
    (try
      (let [catalog (lib/seed-library st)]
        (lmdb-measure dir st {:layout :snapshot :root-hash (v/hash catalog)}))
      (finally
        (jvm/lmdb-close st)))))

(defn- lmdb-gc-live
  [dir]
  (let [st (jvm/lmdb-store dir {:max-size lmdb-map-size})]
    (try
      (let [catalog (lib/seed-library st)
            h (v/hash catalog)
            gc-res (gc/collect-garbage! st h)]
        (lmdb-measure dir st {:layout :gc-live :root-hash h :gc gc-res}))
      (finally
        (jvm/lmdb-close st)))))

(defn- lmdb-gc-live-fresh
  [dir]
  (let [src (store/mem-store)
        catalog (lib/seed-library src)
        h (v/hash catalog)
        st (jvm/lmdb-store dir {:max-size lmdb-map-size})]
    (try
      (copy-live src h st)
      (lmdb-measure dir st {:layout :gc-live-fresh :root-hash h})
      (finally
        (jvm/lmdb-close st)))))

(defn- lmdb-chunked
  [dir]
  (let [src (store/mem-store)
        catalog (lib/seed-library src)
        h (v/hash catalog)
        {:keys [items]} (pack/encode-reachable src h #{} budget)
        sum (pack/summarize-items items)
        wire (reduce + 0 (map bin/pack-item-wire-bytes items))
        st (put-pack-items-lmdb dir items)]
    (try
      (lmdb-measure dir st {:layout :chunked
                            :root-hash h
                            :flush {:items (count items)
                                    :literals (:literals sum)
                                    :nodes (:nodes sum)}
                            :wire-bytes wire})
      (finally
        (jvm/lmdb-close st)))))

;; -----------------------------------------------------------------------------
;; Suite
;; -----------------------------------------------------------------------------

(defn run-suite
  "Measure snapshot / gc-live / chunked on file and LMDB for the seed catalog."
  []
  (let [file-s (with-file-dir "snap" file-snapshot)
        file-g (with-file-dir "gc" file-gc-live)
        file-c (with-file-dir "chunk" file-chunked)
        lmdb-s (with-lmdb-dir "snap" lmdb-snapshot)
        lmdb-g (with-lmdb-dir "gc" lmdb-gc-live)
        lmdb-f (with-lmdb-dir "fresh" lmdb-gc-live-fresh)
        lmdb-c (with-lmdb-dir "chunk" lmdb-chunked)
        ratio (fn [a b]
                (when (and a b (pos? b))
                  (/ (double a) (double b))))]
    {:budget budget
     :file {:snapshot file-s
            :gc-live file-g
            :chunked file-c}
     :lmdb {:snapshot lmdb-s
            :gc-live lmdb-g
            :gc-live-fresh lmdb-f
            :chunked lmdb-c}
     :ratios {:file-data-snapshot-vs-chunked
              (ratio (:data-file-bytes file-s) (:data-file-bytes file-c))
              :file-data-live-vs-chunked
              (ratio (:data-file-bytes file-g) (:data-file-bytes file-c))
              :lmdb-used-snapshot-vs-chunked
              (ratio (:used-bytes lmdb-s) (:used-bytes lmdb-c))
              :lmdb-used-live-fresh-vs-chunked
              (ratio (:used-bytes lmdb-f) (:used-bytes lmdb-c))
              :lmdb-used-snapshot-vs-live-fresh
              (ratio (:used-bytes lmdb-s) (:used-bytes lmdb-f))
              :wire-live-vs-chunked
              (ratio (:wire-bytes file-g) (:wire-bytes file-c))}}))

(defn- fmt-int
  [n]
  (if (nil? n)
    "—"
    (format "%,d" (long n))))

(defn- fmt-ratio
  [r]
  (if (nil? r)
    "—"
    (format "%.2f×" (double r))))

(defn- row
  [layout m]
  (format "  %-14s %-6s %10s %12s %14s %12s"
          (name layout)
          (name (:backend m))
          (fmt-int (:entries m))
          (fmt-int (:wire-bytes m))
          (fmt-int (:data-file-bytes m))
          (fmt-int (:used-bytes m))))

(defn render
  [suite]
  (let [f (:file suite)
        l (:lmdb suite)
        r (:ratios suite)]
    (str "library storage (seed catalog, budget " (:budget suite) ")\n"
         "  layout         store    entries   wire-bytes   data-file-bytes   used-pages\n"
         (row :snapshot (:snapshot f)) "\n"
         (row :gc-live (:gc-live f)) "\n"
         (row :chunked (:chunked f)) "\n"
         (row :snapshot (:snapshot l)) "\n"
         (row :gc-live (:gc-live l)) "\n"
         (row :gc-live-fresh (:gc-live-fresh l)) "\n"
         (row :chunked (:chunked l)) "\n"
         "\n"
         "  ratios (larger / smaller)\n"
         "    file data-file  snapshot/chunked  " (fmt-ratio (:file-data-snapshot-vs-chunked r)) "\n"
         "    file data-file  gc-live/chunked   " (fmt-ratio (:file-data-live-vs-chunked r)) "\n"
         "    lmdb used-pages snapshot/chunked  " (fmt-ratio (:lmdb-used-snapshot-vs-chunked r)) "\n"
         "    lmdb used-pages live-fresh/chunked " (fmt-ratio (:lmdb-used-live-fresh-vs-chunked r)) "\n"
         "    lmdb used-pages snapshot/live-fresh " (fmt-ratio (:lmdb-used-snapshot-vs-live-fresh r)) "\n"
         "    wire bytes      gc-live/chunked   " (fmt-ratio (:wire-live-vs-chunked r)) "\n"
         "\n"
         "  notes\n"
         "    file snapshot includes construction debris and 64-hex filenames.\n"
         "    LMDB gc-live is in-place delete: used-pages drop, data.mdb does not.\n"
         "    LMDB gc-live-fresh is a new env of reachable exploded nodes.\n"
         "    chunked LMDB writes pack encode-item bytes (not default lmdb-store).\n"
         "    Do not default file/LMDB to chunked from the seed catalog alone.\n")))

(defn- write-edn!
  [path suite]
  (io/make-parents path)
  (spit path (with-out-str (pp/pprint suite)))
  path)

(defn -main
  [& args]
  (let [args (->> args (map str) (remove #{"--"}))
        out (loop [a args]
              (cond
                (empty? a) nil
                (= (first a) "--out") (second a)
                :else (recur (rest a))))
        suite (run-suite)]
    (print (render suite))
    (flush)
    (when out
      (println "wrote" (write-edn! out suite)))))
