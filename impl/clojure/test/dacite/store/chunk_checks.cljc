(ns dacite.store.chunk-checks
  "Pack-literal durable layout, the default for `store/file` and
   `store/lmdb`, on the JVM and on nbb.

   These were the bodies of `dacite.store.chunk-test`. The JVM runs them from
   that namespace, one deftest each under its old name, with the JVM file and
   LMDB stores. nbb runs them from `dacite.store.store-units-cljs-test`
   (`npm run store-units:test`) with its own. The host `env` is the one of
   `dacite.store-checks` plus:

     :edn-file-sizes  (fn [dir]) the byte size of each .edn file under `dir`
     :lmdb-entries    (fn [st]) the entry count of an LMDB content store

   `is` and `testing` are `dacite.store.check`."
  (:require [dacite.examples.library :as lib]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.check :refer [is]]
            [dacite.store.chunk :as chunk]
            [dacite.store.pack :as pack]
            [dacite.value :as v]))

(defn- two-places
  "`x` with two decimal places, for a printed ratio."
  [x]
  #?(:clj (format "%.2f" (double x))
     :cljs (.toFixed (js/Number x) 2)))

(defn seed-catalog-flushes-to-pack-items
  [_]
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
    (is (= 33 (:entries inner))
        "encode-reachable at the durable 1024 for the seed catalog, plate included")
    (is (< (:entries inner) live))
    (is (< live debris))))

(defn seed-catalog-round-trip-hash-and-page
  [_]
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

(defn title-native-does-not-hydrate-the-book-body
  [_]
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

(defn absorbed-leaf-is-not-an-inner-key
  [_]
  (let [cs (chunk/chunked (store/mem-store))
        catalog (lib/seed-library cs)
        h (v/hash catalog)
        ch-h (v/hash (v/nth (lib/book-text (v/nth (lib/title-index catalog) 0)) 0))]
    (chunk/flush! cs h)
    (is (false? (store/await (store/s-has? (chunk/inner cs) ch-h)))
        "a character leaf is covered by an ft/digit literal")
    (let [cs2 (chunk/chunked (chunk/inner cs))
          loaded (v/get-value cs2 h)
          book (v/nth (lib/title-index loaded) 0)
          page (lib/chapter-page book 0 0 40)]
      (is (seq page))
      (is (true? (store/await (store/s-has? (chunk/overlay cs2) ch-h)))))))

(defn chunked-edn-smaller-than-live-exploded
  [_]
  (let [plain (store/mem-store)
        catalog (lib/seed-library plain)
        h (v/hash catalog)
        live-hexes (gc/mark-reachable plain h)
        live-bytes (reduce (fn [n hk]
                             (+ n (count (pr-str (store/await (store/s-get plain (gc/->hash hk)))))))
                           0
                           live-hexes)
        cs (chunk/chunked (store/mem-store))
        c2 (lib/seed-library cs)
        _ (chunk/flush! cs (v/hash c2))
        inner (chunk/inner-stats cs)]
    (println "edn bytes live-exploded" live-bytes
             "chunked" (:edn-bytes inner)
             "ratio" (two-places (/ live-bytes (max 1 (:edn-bytes inner)))))
    (is (< (:edn-bytes inner) live-bytes))
    (is (< (:entries inner) (count live-hexes)))))

(defn chunked-file-smaller-than-exploded-file
  [env]
  (let [dir-e ((:tmp-dir env) "dacite-chunk-exploded-")
        dir-c ((:tmp-dir env) "dacite-chunk-inner-")
        sizes (:edn-file-sizes env)]
    (try
      (let [exploded ((:file-store env) dir-e)
            _catalog (lib/seed-library exploded)
            e-files (count (sizes dir-e))
            e-bytes (reduce + 0 (sizes dir-e))
            cs (chunk/chunked ((:file-store env) dir-c))
            catalog2 (lib/seed-library cs)
            _ (chunk/flush! cs (v/hash catalog2))
            c-files (count (sizes dir-c))
            c-bytes (reduce + 0 (sizes dir-c))]
        (println "file store seed catalog"
                 {:exploded-files e-files :exploded-bytes e-bytes
                  :chunked-files c-files :chunked-bytes c-bytes})
        (is (= 33 c-files))
        (is (< c-files e-files))
        (is (< c-bytes e-bytes)))
      (finally
        ((:rm-rf env) dir-e)
        ((:rm-rf env) dir-c)))))

(defn app-file-store-flushes-pack-items-on-cas
  [env]
  (let [dir ((:tmp-dir env) "dacite-chunk-app-file-")]
    (try
      (let [r (v/root (store/file dir))]
        (lib/load-or-seed! r)
        (is (= 33 (count ((:edn-file-sizes env) dir)))
            "s/file persists pack items, not construction debris")
        (let [r2 (v/root (store/file dir))
              loaded (v/deref r2)
              book (v/nth (lib/title-index loaded) 0)]
          (is (true? (lib/library-root? loaded)))
          (is (= lib/seed-title (lib/book-title book)))
          (is (seq (lib/chapter-page book 0 0 40)))))
      (finally
        ((:rm-rf env) dir)))))

(defn app-lmdb-store-flushes-pack-items-on-cas
  [env]
  ;; LMDB mmaps data.mdb. Keep the env off a 9p checkout, which returns EINVAL:
  ;; `:tmp-dir` is under the host temp directory.
  (let [path ((:tmp-dir env) "dacite-chunk-app-lmdb-")
        close (:lmdb-close env)]
    (try
      (let [rs (store/lmdb path)
            inner (chunk/inner (:content rs))]
        (try
          (lib/load-or-seed! (v/root rs))
          (is (= 33 ((:lmdb-entries env) inner)))
          (finally
            (close inner))))
      (let [rs2 (store/lmdb path)
            inner2 (chunk/inner (:content rs2))]
        (try
          (let [loaded (v/deref (v/root rs2))
                book (v/nth (lib/title-index loaded) 0)]
            (is (true? (lib/library-root? loaded)))
            (is (= lib/seed-title (lib/book-title book)))
            (is (seq (lib/chapter-page book 0 0 40))))
          (finally
            (close inner2))))
      (finally
        ((:rm-rf env) path)))))

(defn durable-stores-have-their-own-budget
  [_]
  (is (= 1024 chunk/durable-budget))
  (is (= 4096 pack/default-budget) "HTTP and peers keep the leaf page size")
  (is (= chunk/durable-budget (chunk/budget (chunk/chunked (store/mem-store)))))
  (is (= 77 (chunk/budget (chunk/chunked (store/mem-store) {:budget 77})))
      "an explicit budget still wins"))

(def checks
  {"seed-catalog-flushes-to-pack-items" seed-catalog-flushes-to-pack-items
   "seed-catalog-round-trip-hash-and-page" seed-catalog-round-trip-hash-and-page
   "title-native-does-not-hydrate-the-book-body" title-native-does-not-hydrate-the-book-body
   "absorbed-leaf-is-not-an-inner-key" absorbed-leaf-is-not-an-inner-key
   "chunked-edn-smaller-than-live-exploded" chunked-edn-smaller-than-live-exploded
   "chunked-file-smaller-than-exploded-file" chunked-file-smaller-than-exploded-file
   "app-file-store-flushes-pack-items-on-cas" app-file-store-flushes-pack-items-on-cas
   "app-lmdb-store-flushes-pack-items-on-cas" app-lmdb-store-flushes-pack-items-on-cas
   "durable-stores-have-their-own-budget" durable-stores-have-their-own-budget})
