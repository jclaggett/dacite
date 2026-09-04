(ns dacite.examples.library-test
  "Library: sets are tables, title index is a vector, a page is slice."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [dacite.examples.library :as lib]
            [dacite.store :as store]
            [dacite.value :as v]))

(def mini-a
  {:title "Apple Tales"
   :author "A. Author"
   :source "test"
   :license "public-domain"
   :text "CHAPTER I. Core\n\nHello from apple.\n\nCHAPTER II. Slice\n\nA page of apple text.\n"})

(def mini-z
  {:title "Zebra Notes"
   :author "Z. Author"
   :source "test"
   :license "public-domain"
   :text "CHAPTER I. Stripes\n\nHello from zebra.\n"})

(deftest empty-shape
  (let [st (store/mem)
        empty (lib/empty-library st)]
    (is (= "map" (v/type empty)))
    (is (= "set" (v/type (lib/epubs-of empty))))
    (is (= "set" (v/type (lib/books-of empty))))
    (is (zero? (v/count (lib/epubs-of empty))))
    (is (zero? (v/count (lib/title-index empty))))))

(deftest ingest-shares-epub-blob
  (let [st (store/mem)
        catalog (lib/ingest (lib/empty-library st) mini-a)
        book (v/nth (lib/title-index catalog) 0)]
    (is (= 1 (v/count (lib/epubs-of catalog))))
    (is (= 1 (v/count (lib/books-of catalog))))
    (is (= 1 (v/count (lib/title-index catalog))))
    (is (= "Apple Tales" (lib/book-title book)))
    (is (= (v/hash (lib/book-epub book))
           (v/hash (first (v/seq (lib/epubs-of catalog)))))
        "record.epub is the set member")
    (is (v/set-member? (lib/epubs-of catalog) (lib/book-epub book)))
    (is (v/set-member? (lib/books-of catalog) book)
        "index row is the books-set member")))

(deftest duplicate-ingest-is-identity
  (let [st (store/mem)
        a (lib/ingest (lib/empty-library st) mini-a)
        b (lib/ingest a mini-a)]
    (is (= (v/hash a) (v/hash b)))
    (is (= 1 (v/count (lib/epubs-of b))))
    (is (= 1 (v/count (lib/books-of b))))))

(deftest title-index-is-sorted
  (let [st (store/mem)
        catalog (-> (lib/empty-library st)
                    (lib/ingest mini-z)
                    (lib/ingest mini-a))
        idx (lib/title-index catalog)]
    (is (= 2 (v/count idx)))
    (is (= "Apple Tales" (lib/book-title (v/nth idx 0))))
    (is (= "Zebra Notes" (lib/book-title (v/nth idx 1))))
    (is (= "Apple Tales" (lib/book-title (lib/book-of catalog "Apple Tales"))))))

(deftest page-is-slice-of-text
  (let [st (store/mem)
        catalog (lib/ingest (lib/empty-library st) mini-a)
        book (lib/book-of catalog "Apple Tales")
        text (lib/book-text book)
        n 12
        sliced (v/slice text 0 n)
        shown (lib/page book 0 n)]
    (is (= "string" (v/type sliced)))
    (is (= shown (v/native sliced)))
    (is (= (v/hash (v/string book shown)) (v/hash sliced)))
    (is (= 2 (v/count (lib/book-chapters book))))
    (is (zero? (lib/chapter-start book 0)))
    (is (pos? (lib/chapter-start book 1)))
    (is (seq (lib/chapter-page book 1 0 40)))))

(deftest add-epub-without-catalog
  (let [st (store/mem)
        full (lib/ingest (lib/empty-library st) mini-a)
        blob (lib/book-epub (v/nth (lib/title-index full) 0))
        only (lib/add-epub (lib/empty-library st) blob)]
    (is (= 1 (v/count (lib/epubs-of only))))
    (is (zero? (v/count (lib/books-of only))))
    (is (= (v/hash only) (v/hash (lib/add-epub only blob))))))

(deftest seed-has-three-chapters
  (let [r (v/root (lib/open-mem))
        [catalog seeded?] (lib/load-or-seed! r)
        book (v/nth (lib/title-index catalog) 0)
        m (lib/measure catalog)]
    (is (true? seeded?))
    (is (= lib/seed-title (lib/book-title book)))
    (is (= 3 (v/count (lib/book-chapters book))))
    (is (< (:page-chars m) (:text-chars m)))
    (is (true? (:same-epub-noop? m)))
    (is (pos? (count (lib/chapter-page book 0 0 80))))))

(deftest file-reopen
  (let [dir (io/file (str "target/dacite-library-test-" (System/nanoTime)))]
    (try
      (let [r1 (v/root (lib/open-file (.getPath dir)))]
        (lib/load-or-seed! r1)
        (v/swap! r1 lib/ingest mini-z)
        (let [h1 (v/hash (v/deref r1))
              r2 (v/root (lib/open-file (.getPath dir)))
              loaded (v/deref r2)]
          (is (= h1 (v/hash loaded)))
          (is (= 2 (v/count (lib/title-index loaded))))
          (is (some? (lib/book-of loaded "Zebra Notes")))))
      (finally
        (lib/reset-store-dir! (.getPath dir))))))
