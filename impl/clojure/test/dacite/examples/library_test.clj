(ns dacite.examples.library-test
  "Library: sets are tables, title index is a vector, a page is slice."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dacite.examples.library :as lib]
            [dacite.examples.library-origin :as origin]
            [dacite.service :as svc]
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

(deftest contents-list-is-not-chapters
  (let [toc (apply str (map #(str "CHAPTER " % ". Title.\n") (range 1 6)))
        pad (apply str (repeat 250 \x))
        body (str toc "\n" pad "\n"
                  "CHAPTER 1. Loomings.\n\n" pad "\n"
                  "CHAPTER 2. The Carpet-Bag.\n\n" pad "\n")
        heads (lib/parse-chapters body "Front")]
    (is (= ["Front" "CHAPTER 1. Loomings." "CHAPTER 2. The Carpet-Bag."]
           (mapv :title heads)))))

(deftest library-root-predicate
  (let [st (store/mem)
        catalog (lib/empty-library st)]
    (is (true? (lib/library-root? catalog)))
    (is (false? (lib/library-root? (v/vector st 1 2))))
    (is (false? (lib/library-root? nil)))))

(deftest pages-in-chapter-covers-text
  (let [st (store/mem)
        catalog (lib/ingest (lib/empty-library st) mini-a)
        book (lib/book-of catalog "Apple Tales")
        n (lib/pages-in-chapter book 0 8)]
    (is (pos? n))
    (is (seq (lib/chapter-page book 0 0 8)))
    (is (string? (lib/chapter-title book 0)))))

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

(deftest seed-plate-is-a-png-the-reader-can-show
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

(deftest origin-publishes-the-seed-library
  (let [st (store/mem)
        catalog (lib/seed-library st)
        {:keys [rooted root entries]} (origin/seed-origin!)
        resp (svc/handle-request rooted "GET" "/root" nil nil)]
    (is (= 200 (:status resp)))
    (is (= (store/hash->hex (v/hash catalog))
           (store/hash->hex root)
           (:root (wire/read-edn (:body resp)))))
    (is (> entries 1))))

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

(defn- http-no-follow
  [url]
  (let [conn ^java.net.HttpURLConnection (.openConnection (java.net.URL. url))]
    (.setInstanceFollowRedirects conn false)
    (.setRequestMethod conn "GET")
    (let [code (.getResponseCode conn)
          loc (.getHeaderField conn "Location")]
      (.disconnect conn)
      {:status code :location loc})))

(deftest static-library-index
  (let [rooted (svc/make-demo-rooted)
        static (io/file "../../examples/web")
        {:keys [base-url stop!]} (svc/start-server! {:port 0
                                                     :rooted rooted
                                                     :static-dir static
                                                     :throttle false})]
    (try
      (let [slash (slurp (str base-url "/app/library/"))
            noslash (http-no-follow (str base-url "/app/library"))]
        (is (re-find #"Dacite Library" slash))
        (is (re-find #"/app/library/js/main.js" slash)
            "script URL must be root-absolute")
        (is (= 301 (:status noslash)))
        (is (re-find #"/app/library/$" (:location noslash))))
      (finally
        (stop!)))))
