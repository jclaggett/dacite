(ns dacite.examples.library-checks
  "Library: sets are tables, the title index is a vector, a page is a
   slice. On a mem store and a file store, on the JVM and on nbb.

   These were the bodies of `dacite.examples.library-test`. The JVM runs them
   from that namespace, one deftest each under its old name; nbb runs them
   from `dacite.examples.examples-cljs-test` (`npm run examples:test`).
   `is` is `dacite.store.check`."
  (:require [clojure.string :as str]
            [dacite.store.check :refer [is]]
            [dacite.examples.library :as lib]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.wire :as wire]))

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

(defn contents-list-is-not-chapters
  [_]
  (let [toc (apply str (map #(str "CHAPTER " % ". Title.\n") (range 1 6)))
        pad (apply str (repeat 250 \x))
        body (str toc "\n" pad "\n"
                  "CHAPTER 1. Loomings.\n\n" pad "\n"
                  "CHAPTER 2. The Carpet-Bag.\n\n" pad "\n")
        heads (lib/parse-chapters body "Front")]
    (is (= ["Front" "CHAPTER 1. Loomings." "CHAPTER 2. The Carpet-Bag."]
           (mapv :title heads)))))

(defn library-root-predicate
  [_]
  (let [st (store/mem)
        catalog (lib/empty-library st)]
    (is (true? (lib/library-root? catalog)))
    (is (false? (lib/library-root? (v/vector st 1 2))))
    (is (false? (lib/library-root? nil)))))

(defn pages-in-chapter-covers-text
  [_]
  (let [st (store/mem)
        catalog (lib/ingest (lib/empty-library st) mini-a)
        book (lib/book-of catalog "Apple Tales")
        n (lib/pages-in-chapter book 0 8)]
    (is (pos? n))
    (is (seq (lib/chapter-page book 0 0 8)))
    (is (string? (lib/chapter-title book 0)))))

(defn empty-shape
  [_]
  (let [st (store/mem)
        empty (lib/empty-library st)]
    (is (= "map" (v/type empty)))
    (is (= "set" (v/type (lib/epubs-of empty))))
    (is (= "set" (v/type (lib/books-of empty))))
    (is (zero? (v/count (lib/epubs-of empty))))
    (is (zero? (v/count (lib/title-index empty))))))

(defn ingest-shares-epub-blob
  [_]
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

(defn duplicate-ingest-is-identity
  [_]
  (let [st (store/mem)
        a (lib/ingest (lib/empty-library st) mini-a)
        b (lib/ingest a mini-a)]
    (is (= (v/hash a) (v/hash b)))
    (is (= 1 (v/count (lib/epubs-of b))))
    (is (= 1 (v/count (lib/books-of b))))))

(defn title-index-is-sorted
  [_]
  (let [st (store/mem)
        catalog (-> (lib/empty-library st)
                    (lib/ingest mini-z)
                    (lib/ingest mini-a))
        idx (lib/title-index catalog)]
    (is (= 2 (v/count idx)))
    (is (= "Apple Tales" (lib/book-title (v/nth idx 0))))
    (is (= "Zebra Notes" (lib/book-title (v/nth idx 1))))
    (is (= "Apple Tales" (lib/book-title (lib/book-of catalog "Apple Tales"))))))

(defn page-is-slice-of-text
  [_]
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

(defn add-epub-without-catalog
  [_]
  (let [st (store/mem)
        full (lib/ingest (lib/empty-library st) mini-a)
        blob (lib/book-epub (v/nth (lib/title-index full) 0))
        only (lib/add-epub (lib/empty-library st) blob)]
    (is (= 1 (v/count (lib/epubs-of only))))
    (is (zero? (v/count (lib/books-of only))))
    (is (= (v/hash only) (v/hash (lib/add-epub only blob))))))

(defn seed-plate-is-a-png-the-reader-can-show
  [_]
  (let [st (store/mem)
        catalog (lib/seed-library st)
        book (v/nth (lib/title-index catalog) 0)
        img (lib/book-image book)
        bs (wire/bytes->vec (v/as-bytes img))
        plain (lib/ingest (lib/empty-library st) mini-a)]
    (is (= [137 80 78 71 13 10 26 10] (subvec lib/seed-plate 0 8)))
    (is (= (vec lib/seed-plate) bs))
    (is (= "blob" (v/type img)))
    (is (nil? (lib/book-image (lib/book-of plain "Apple Tales"))))
    (is (str/includes? (lib/chapter-page book 0 0 400) "CHAPTER I. The Shelf"))
    (is (str/includes? (lib/chapter-page book 0 0 80) "The catalog is a set of books"))))

(defn seed-has-three-chapters
  [_]
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

(defn file-reopen
  [_]
  (let [dir (str "target/dacite-library-test-" #?(:clj (System/nanoTime) :cljs (.now js/Date)))]
    (try
      (let [r1 (v/root (lib/open-file dir))]
        (lib/load-or-seed! r1)
        (v/swap! r1 lib/ingest mini-z)
        (let [h1 (v/hash (v/deref r1))
              r2 (v/root (lib/open-file dir))
              loaded (v/deref r2)]
          (is (= h1 (v/hash loaded)))
          (is (= 2 (v/count (lib/title-index loaded))))
          (is (some? (lib/book-of loaded "Zebra Notes")))))
      (finally
        (lib/reset-store-dir! dir)))))

(def checks
  "Check name to check fn. The JVM deftest of each has the same name."
  {"contents-list-is-not-chapters" contents-list-is-not-chapters
   "library-root-predicate" library-root-predicate
   "pages-in-chapter-covers-text" pages-in-chapter-covers-text
   "empty-shape" empty-shape
   "ingest-shares-epub-blob" ingest-shares-epub-blob
   "duplicate-ingest-is-identity" duplicate-ingest-is-identity
   "title-index-is-sorted" title-index-is-sorted
   "page-is-slice-of-text" page-is-slice-of-text
   "add-epub-without-catalog" add-epub-without-catalog
   "seed-plate-is-a-png-the-reader-can-show" seed-plate-is-a-png-the-reader-can-show
   "seed-has-three-chapters" seed-has-three-chapters
   "file-reopen" file-reopen})
