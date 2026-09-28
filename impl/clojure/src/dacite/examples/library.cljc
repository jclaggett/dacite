(ns dacite.examples.library
  "Public-domain library — sets are tables, vectors are indexes.

   **Values** — a library map:
     {\"epubs\"   #{blob …}
      \"books\"   #{record …}
      \"indexes\" {\"title\" [record …]}}   ; sorted by title, then author

   A record is {title, author, epub, text, source, license, chapters}.
   `epub` is the same blob as the set member (shared hash). `text` is the
   linearized reading string. The shelf is `v/slice` of the title index;
   a display page is `nth` of characters (slice would hash a new spine).

   **Store** — file-rooted or HTTP remote-rooted.

   Run:
     clojure -M:library -- --reset shelf
     clojure -M:library -- toc
     clojure -M:library -- read --chapter 1 --page 0
     clojure -M:library -- bench
     clojure -M:library-storage
     clojure -M:library -- ingest --file book.txt --title T --author A
     bb library --reset shelf
     npx nbb -m dacite.examples.library -- --reset shelf
     clojure -M:cljs-library
     clojure -M:service   ; then /app/library/"
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.value :as v]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [dacite.store.chunk :as chunk])))

;; =============================================================================
;; Values
;; =============================================================================

(def default-page-size
  "Viewer window in characters. Not stored on the book."
  800)

(def seed-title "A Walk Through the Catalog")
(def seed-author "A Public Domain Clerk")
(def seed-source "dacite.examples.library")
(def seed-license "public-domain")

(def seed-text
  "CHAPTER I. The Shelf

The catalog is a set of books, not a pile of files. You do not open the
whole library to read a title. You walk a shelf: an ordered vector of the
same records that live in the set, sharing their hashes.

A second copy of the same volume is not a second object. Its bytes hash
to the member you already hold, and conj on the set is a no-op.

CHAPTER II. The Index

Maps and sets are not sorted. That is not a missing feature; it is why
the title index exists. A Dacite vector is the index: a finger tree of
rows, paged with slice, the way a B-tree is paged in a SQL engine.

Tables are sets. Indexes are vectors. Lookup is either a walk of the
title index or an arbitrary traverse of the books set.

CHAPTER III. A Page of Text

The reading value is one string. Chapters are start offsets into that
string. The viewer chooses how many characters make a page; the book
does not.

You never native the whole novel to show chapter two. You slice the
window you need, then native that page.

The rest of this chapter exists so a single page is not the whole book.
The clerk copies the same sentence until the string is long enough that
slice and a full native are different amounts of work. The clerk copies
the same sentence until the string is long enough that slice and a full
native are different amounts of work. The clerk copies the same sentence
until the string is long enough that slice and a full native are
different amounts of work. The clerk copies the same sentence until the
string is long enough that slice and a full native are different amounts
of work. The clerk copies the same sentence until the string is long
enough that slice and a full native are different amounts of work.
")

(defn empty-library
  "Empty corpus, empty catalog, empty title index."
  [peer]
  (v/map peer
         "epubs" (v/set peer)
         "books" (v/set peer)
         "indexes" (v/map peer "title" (v/vector peer))))

(defn epubs-of [lib] (v/get lib "epubs"))
(defn books-of [lib] (v/get lib "books"))
(defn title-index [lib] (v/get-in lib ["indexes" "title"]))

(defn library-root?
  "True if x is a library catalog map (epubs + books sets, indexes map)."
  [x]
  (and (v/dacite-value? x)
       (= "map" (v/type x))
       (let [ep (v/get x "epubs")
             bk (v/get x "books")
             ix (v/get x "indexes")]
         (boolean
          (and ep bk ix
               (= "set" (v/type ep))
               (= "set" (v/type bk))
               (= "map" (v/type ix)))))))

(defn book-title [book] (or (v/native (v/get book "title")) ""))
(defn book-author [book] (or (v/native (v/get book "author")) ""))
(defn book-source [book] (or (v/native (v/get book "source")) ""))
(defn book-license [book] (or (v/native (v/get book "license")) ""))
(defn book-text [book] (v/get book "text"))
(defn book-epub [book] (v/get book "epub"))
(defn book-chapters [book] (v/get book "chapters"))

(defn- utf8-bytes
  "UTF-8 bytes of `s`. JVM and babashka return a byte array so blob
   construction can slice it. ClojureScript returns 0–255 ints."
  [s]
  #?(:cljs
     (let [u8 (if (exists? js/Buffer)
                (.from js/Buffer s "utf8")
                (.encode (js/TextEncoder.) s))]
       (mapv #(aget u8 %) (range (.-length u8))))
     :default
     (.getBytes ^String (str s) "UTF-8")))

(defn- utf8-string
  "Host string from `v/as-bytes` (byte array or 0–255 ints)."
  [bs]
  #?(:clj
     (let [arr (if (bytes? bs)
                 bs
                 (byte-array (map unchecked-byte bs)))]
       (String. ^bytes arr "UTF-8"))
     :cljs
     (let [nums (vec bs)
           arr (into-array nums)]
       (if (exists? js/Buffer)
         (.toString (js/Buffer.from arr) "utf8")
         (.decode (js/TextDecoder.) (js/Uint8Array.from arr))))))

(defn- tight-run-length
  "How many headings, starting at `heads`, are separated by less than
   `min-body` characters. A contents list is one of these runs."
  [heads min-body]
  (loop [xs (seq heads) n 1]
    (let [b (second xs)]
      (if (and b (< (- (:start b) (:start (first xs))) min-body))
        (recur (next xs) (inc n))
        n))))

(defn- without-contents-lists
  "Drop runs of three or more headings with almost no text between them.
   Gutenberg files repeat every chapter title in a contents list before
   the novel. A real chapter has a body before the next heading."
  [heads]
  (loop [hs (seq heads) out []]
    (if-not (seq hs)
      out
      (let [n (tight-run-length hs 200)]
        (if (>= n 3)
          (recur (seq (drop n hs)) out)
          (recur (next hs) (conj out (first hs))))))))

(defn parse-chapters
  "Heading lines `CHAPTER …` / `Chapter …` → [{:title :start} …].
   Character offsets into `text`. A preamble before the first heading
   becomes Preface at 0. No headings → one chapter at 0 named `fallback`.
   A contents list of bare headings is not a sequence of chapters."
  ([text] (parse-chapters text "Preface"))
  ([text fallback]
   (let [heads
         (without-contents-lists
          (loop [from 0 acc []]
            (let [s (subs text from)
                  m (re-find #"(?m)^(?:CHAPTER|Chapter) .+$" s)]
              (if-not m
                acc
                (let [rel (str/index-of s m)
                      start (+ from rel)]
                  (recur (+ start (count m))
                         (conj acc {:title (str/trim m) :start start})))))))]
     (cond
       (empty? heads)
       [{:title fallback :start 0}]

       (pos? (:start (first heads)))
       (into [{:title fallback :start 0}] heads)

       :else heads))))

(defn- chapters-value
  [peer heads]
  (reduce (fn [ch {:keys [title start]}]
            (v/conj ch (v/map ch "title" title "start" start)))
          (v/vector peer)
          heads))

(defn book-record
  "Catalog row relative to `peer`. `epub` is a Dacite blob; `text` a host string."
  [peer {:keys [title author year source license text epub]}]
  (let [title (str title)
        rec (v/map peer
                   "title" title
                   "author" (str (or author ""))
                   "epub" epub
                   "text" (v/string peer (str text))
                   "source" (str (or source ""))
                   "license" (str (or license "public-domain"))
                   "chapters" (chapters-value peer (parse-chapters (str text) title)))]
    (if (nil? year)
      rec
      (v/assoc rec "year" year))))

(defn add-epub
  "Conj `epub` (a blob) onto the corpus. No-op if already a member."
  [lib epub]
  (v/update lib "epubs" v/conj epub))

(defn rebuild-title-index
  "Sorted vector of the same records as `books` (shared hashes)."
  [books]
  (let [rows (vec (or (v/seq books) ()))
        sorted (sort-by (fn [b]
                          [(str/lower-case (book-title b))
                           (str/lower-case (book-author b))])
                        rows)]
    (reduce v/conj (v/vector books) sorted)))

(defn catalog-epub
  "Insert a book record for `epub` and rebuild the title index.

   `opts` is {:title :author :year :source :license :text}. Missing `text`
   is decoded from the blob as UTF-8."
  [lib epub opts]
  (let [text (or (:text opts) (utf8-string (v/as-bytes epub)))
        rec (book-record lib (assoc opts :epub epub :text text))
        books (v/conj (books-of lib) rec)]
    (-> lib
        (v/assoc "books" books)
        (v/assoc "indexes" (v/assoc (v/get lib "indexes")
                                    "title" (rebuild-title-index books))))))

(defn ingest
  "Add a UTF-8 document to the corpus and catalog it as one book."
  [lib {:keys [title author year source license text] :as opts}]
  (when (str/blank? (str title))
    (throw (ex-info "ingest requires a title" opts)))
  (when (str/blank? (str text))
    (throw (ex-info "ingest requires text" {:title title})))
  (let [blob (v/blob lib (utf8-bytes text))]
    (-> lib
        (add-epub blob)
        (catalog-epub blob opts))))

(defn shelf
  "A page of the title index (records, not host maps)."
  ([lib] (shelf lib 0 (v/count (title-index lib))))
  ([lib start] (shelf lib start (v/count (title-index lib))))
  ([lib start end]
   (let [idx (title-index lib)
         n (v/count idx)
         start (max 0 (long start))
         end (min n (long end))]
     (if (>= start end)
       (v/vector lib)
       (v/slice idx start end)))))

(defn book-of
  "First record on the title index matching `title`, and `author` when given.
   Falls back to a traverse of the books set."
  ([lib title] (book-of lib title nil))
  ([lib title author]
   (let [want-title (str title)
         want-author (when author (str author))
         match (fn [b]
                 (and (= want-title (book-title b))
                      (or (nil? want-author)
                          (= want-author (book-author b)))))]
     (or (some #(when (match %) %) (or (v/seq (title-index lib)) ()))
         (some #(when (match %) %) (or (v/seq (books-of lib)) ()))))))

(defn chapter-start
  "Character offset of chapter `i` (0-based)."
  [book i]
  (let [ch (book-chapters book)
        n (v/count ch)]
    (when (or (neg? i) (>= i n))
      (throw (ex-info "chapter out of range" {:i i :chapters n})))
    (or (v/native (v/get (v/nth ch i) "start")) 0)))

(defn chapter-end
  "Exclusive end offset of chapter `i`."
  [book i]
  (let [ch (book-chapters book)
        n (v/count ch)
        text-n (v/count (book-text book))]
    (if (< (inc i) n)
      (chapter-start book (inc i))
      text-n)))

(defn- chars-window
  "Host string of `text[start, end)` via nth. Does not build a new Dacite string."
  [text start end]
  (if (>= start end)
    ""
    #?(:clj
       (let [sb (StringBuilder. (int (- end start)))]
         (loop [i start]
           (if (< i end)
             (do (.append sb (str (v/native (v/nth text i))))
                 (recur (inc i)))
             (.toString sb))))
       :cljs
       (let [out #js []]
         (loop [i start]
           (if (< i end)
             (do (.push out (str (v/native (v/nth text i))))
                 (recur (inc i)))
             (.join out "")))))))

(defn page
  "Host string for `text[start, start+n)`. Empty if start is past the end.

   Uses nth of each character — not slice (that would hash a new spine)."
  ([book start] (page book start default-page-size))
  ([book start n]
   (let [text (book-text book)
         c (v/count text)
         start (max 0 (long start))
         end (min c (+ start (long n)))]
     (if (>= start c)
       ""
       (chars-window text start end)))))

(defn chapter-title
  [book i]
  (or (v/native (v/get (v/nth (book-chapters book) i) "title")) ""))

(defn pages-in-chapter
  "How many viewer pages fit in chapter `i` at `page-size`."
  ([book i] (pages-in-chapter book i default-page-size))
  ([book i page-size]
   (let [len (max 0 (- (chapter-end book i) (chapter-start book i)))
         sz (max 1 (long page-size))]
     (if (zero? len)
       1
       (long (Math/ceil (/ (double len) sz)))))))

(defn chapter-page
  "Page `page-n` (0-based) of chapter `chapter-i`."
  ([book chapter-i] (chapter-page book chapter-i 0 default-page-size))
  ([book chapter-i page-n] (chapter-page book chapter-i page-n default-page-size))
  ([book chapter-i page-n page-size]
   (let [c0 (chapter-start book chapter-i)
         c1 (chapter-end book chapter-i)
         start (+ c0 (* (long page-n) (long page-size)))
         n (min (long page-size) (max 0 (- c1 start)))]
     (if (>= start c1)
       ""
       (page book start n)))))

(defn seed-library
  "Library containing the in-repo public-domain seed work."
  [peer]
  (ingest (empty-library peer)
          {:title seed-title
           :author seed-author
           :source seed-source
           :license seed-license
           :text seed-text}))

(defn load-or-seed!
  "Load library from a root, or CAS-seed the sample work."
  [lib-ref]
  (if-let [prior (v/deref lib-ref)]
    [prior false]
    (let [lib (seed-library lib-ref)]
      (if (v/cas! lib-ref nil lib)
        [lib true]
        [(v/deref lib-ref) false]))))

(defn node-count
  [x]
  (count (store/s-snapshot (v/dacite-store x))))

#?(:clj
   (defn- chunked-seed-stats
     "Flush the seed catalog through a 1k chunked store (experiment)."
     []
     (let [cs (chunk/chunked (store/mem-store))
           catalog (seed-library cs)
           h (v/hash catalog)
           debris (:entries (chunk/overlay-stats cs))
           live (chunk/live-count cs h)
           flushed (chunk/flush! cs h)
           inner (chunk/inner-stats cs)]
       {:overlay-debris debris
        :live-exploded live
        :chunked-entries (:entries inner)
        :chunked-edn-bytes (:edn-bytes inner)
        :chunked-literals (:literals flushed)
        :chunked-nodes (:nodes flushed)})))

(defn measure
  "Shelf vs one page vs whole text; second add-epub is identity."
  [lib]
  (let [book (v/nth (title-index lib) 0)
        text (book-text book)
        n (v/count text)
        pg (page book 0 default-page-size)
        lib2 (add-epub lib (book-epub book))]
    (merge {:text-chars n
            :page-chars (count pg)
            :epubs (v/count (epubs-of lib))
            :books (v/count (books-of lib))
            :shelf (v/count (title-index lib))
            :same-epub-noop? (= (v/hash lib) (v/hash lib2))
            :nodes (node-count lib)}
           #?(:clj (chunked-seed-stats)
              :default {}))))

(defn short-hex [h]
  (when h
    (subs (store/hash->hex h) 0 12)))

(defn render-shelf
  [lib]
  (let [idx (title-index lib)
        n (v/count idx)]
    (str "shelf (" n ")\n"
         (apply str
                (map-indexed
                 (fn [i b]
                   (str "  " i ". " (book-title b)
                        "  — " (book-author b) "\n"))
                 (or (v/seq idx) ())))
         "epubs: " (v/count (epubs-of lib))
         "  books: " (v/count (books-of lib)) "\n"
         "root:  " (store/hash->hex (v/hash lib)) "\n")))

(defn render-toc
  [book]
  (let [ch (book-chapters book)
        n (v/count ch)]
    (str (book-title book) "  — " (book-author book) "\n"
         "chars: " (v/count (book-text book))
         "  chapters: " n "\n"
         (apply str
                (map (fn [i]
                       (let [c (v/nth ch i)]
                         (str "  " i ". " (v/native (v/get c "title"))
                              "  @" (v/native (v/get c "start")) "\n")))
                     (range n)))
         "book: " (short-hex (v/hash book)) "\n")))

(defn render-page
  [book chapter-i page-n page-size]
  (let [body (chapter-page book chapter-i page-n page-size)
        c0 (chapter-start book chapter-i)
        c1 (chapter-end book chapter-i)
        start (+ c0 (* page-n page-size))]
    (str (book-title book) "  ch " chapter-i
         "  page " page-n
         "  [" start "," (min c1 (+ start page-size)) ") of chapter ["
         c0 "," c1 ")\n\n"
         body
         (when (and (seq body) (not (str/ends-with? body "\n"))) "\n")
         "\npage-chars: " (count body) "\n")))

(defn render-bench
  [m]
  (str "library bench\n"
       "  epubs/books/shelf: " (:epubs m) "/" (:books m) "/" (:shelf m) "\n"
       "  text chars:        " (:text-chars m) "\n"
       "  page chars:        " (:page-chars m)
       "  (window " default-page-size ")\n"
       "  store nodes:       " (:nodes m) "\n"
       "  same epub add-epub is identity: " (:same-epub-noop? m) "\n"
       (when (:chunked-entries m)
         (str "  chunked (1k):      " (:chunked-entries m) " entries ("
              (:chunked-literals m) " literals / " (:chunked-nodes m) " nodes, "
              (:chunked-edn-bytes m) " EDN bytes)\n"
              "  live exploded:     " (:live-exploded m) "\n"
              "  overlay debris:    " (:overlay-debris m) "\n"))))

;; =============================================================================
;; Store
;; =============================================================================

(def default-path
  "target/dacite-library")

(defn open-mem [] (store/mem))
(defn open-file [path] (store/file path))
(defn open-remote [url] (store/remote url))
(defn open-lmdb [path] (store/lmdb path))

(defn reset-store-dir!
  [path]
  (store/file path {:reset true})
  nil)

(defn reset-lmdb-dir!
  [path]
  (store/lmdb path {:reset true})
  nil)

(defn local-path
  [{:keys [lmdb? path]}]
  (if (and lmdb? (= path default-path))
    (str default-path "-lmdb")
    path))

(defn parse-int
  [s]
  #?(:clj (Long/parseLong (str s))
     :cljs (js/parseInt (str s) 10)))

(defn- ingest-kv
  "Pull --title/--author/--source/--license/--file from ingest args."
  [args]
  (loop [args args
         acc {:title nil :author "" :source "" :license seed-license :file nil}]
    (if-not (seq args)
      acc
      (let [a (first args)
            more (rest args)]
        (cond
          (= a "--title") (recur (rest more) (assoc acc :title (first more)))
          (= a "--author") (recur (rest more) (assoc acc :author (first more)))
          (= a "--source") (recur (rest more) (assoc acc :source (first more)))
          (= a "--license") (recur (rest more) (assoc acc :license (first more)))
          (= a "--file") (recur (rest more) (assoc acc :file (first more)))
          :else (throw (ex-info "unknown ingest flag" {:flag a})))))))

(defn parse-args
  "CLI: [--path DIR | --url URL] [--lmdb] [--reset|-r]
        [shelf|toc|read|ingest|bench|show]"
  [args]
  (let [args (->> args (map str) (remove #{"--"}))]
    (loop [args args
           acc {:reset? false
                :lmdb? false
                :path default-path
                :url nil
                :cmd "shelf"
                :cmd-args []}]
      (if-not (seq args)
        acc
        (let [a (first args)
              more (rest args)]
          (cond
            (or (= a "--reset") (= a "-r"))
            (recur more (assoc acc :reset? true))

            (= a "--lmdb")
            (recur more (assoc acc :lmdb? true))

            (= a "--path")
            (recur (rest more) (assoc acc :path (first more)))

            (= a "--url")
            (recur (rest more) (assoc acc :url (first more)))

            (#{"shelf" "show" "toc" "read" "ingest" "bench"} a)
            (assoc acc :cmd a :cmd-args (vec more))

            :else
            (assoc acc :cmd "shelf" :cmd-args (vec args))))))))

(defn open-store
  [{:keys [url lmdb?] :as opts}]
  (cond
    url (open-remote url)
    lmdb? (open-lmdb (local-path opts))
    :else (open-file (:path opts))))

;; =============================================================================
;; Main
;; =============================================================================

(defn- print! [s]
  (print s)
  (flush))

(defn- first-book [lib]
  (let [idx (title-index lib)]
    (when (pos? (v/count idx))
      (v/nth idx 0))))

(defn- require-book [lib title author]
  (or (if title
        (book-of lib title author)
        (first-book lib))
      (throw (ex-info "no book on the shelf" {:title title}))))

(defn- read-flags [args]
  (loop [args args
         acc {:chapter 0 :page 0 :size default-page-size :title nil :author nil}]
    (if-not (seq args)
      acc
      (let [a (first args)
            more (rest args)]
        (cond
          (= a "--chapter") (recur (rest more) (assoc acc :chapter (parse-int (first more))))
          (= a "--page") (recur (rest more) (assoc acc :page (parse-int (first more))))
          (= a "--size") (recur (rest more) (assoc acc :size (parse-int (first more))))
          (= a "--title") (recur (rest more) (assoc acc :title (first more)))
          (= a "--author") (recur (rest more) (assoc acc :author (first more)))
          :else (throw (ex-info "unknown read flag" {:flag a})))))))

(defn- slurp-utf8 [path]
  #?(:clj (slurp (io/file path) :encoding "UTF-8")
     :default (throw (ex-info "ingest --file is JVM/bb" {:path path}))))

(defn- run-cmd!
  [lib-ref cmd cmd-args]
  (case cmd
    ("shelf" "show")
    (print! (render-shelf (v/deref lib-ref)))

    "toc"
    (let [{:keys [title author]} (read-flags cmd-args)
          book (require-book (v/deref lib-ref) title author)]
      (print! (render-toc book)))

    "read"
    (let [{:keys [chapter page size title author]} (read-flags cmd-args)
          book (require-book (v/deref lib-ref) title author)]
      (print! (render-page book chapter page size)))

    "ingest"
    (let [{:keys [title author source license file]} (ingest-kv cmd-args)]
      (when (str/blank? file)
        (throw (ex-info "ingest requires --file PATH" {})))
      (when (str/blank? title)
        (throw (ex-info "ingest requires --title TITLE" {})))
      (let [text (slurp-utf8 file)
            lib' (v/swap! lib-ref ingest {:title title
                                          :author author
                                          :source source
                                          :license license
                                          :text text})]
        (print! (render-shelf lib'))))

    "bench"
    (print! (render-bench (measure (v/deref lib-ref))))))

(defn -main [& args]
  (let [{:keys [reset? path url lmdb? cmd cmd-args] :as opts} (parse-args args)
        local (local-path opts)]
    (when (and reset? url)
      (throw (ex-info "--reset is for the local store only" {:url url})))
    (when reset?
      (if lmdb?
        (reset-lmdb-dir! local)
        (reset-store-dir! path))
      (println "reset store at" local))
    (let [rs (open-store opts)
          lib-ref (v/root rs)
          [_ seeded?] (load-or-seed! lib-ref)]
      (when lmdb?
        (println "lmdb" local))
      (when seeded?
        (println (if url
                   (str "seeded remote at " url)
                   (str "seeded new store at " local))))
      (run-cmd! lib-ref cmd cmd-args))))
