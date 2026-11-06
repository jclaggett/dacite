(ns dacite.value.unicode-test
  "Issue #16: a Dacite string is a sequence of Unicode code points on every
   host. Length, indexing, paging and slicing count code points; a character
   outside the BMP is one element (not two lone surrogates, which the JVM
   encoded as `?` and a JS host as U+FFFD); a lone surrogate is refused with
   `:reason :lone-surrogate`; malformed UTF-8 on the wire is refused with
   `:reason :invalid-utf8`."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dacite.host :as host]
            [dacite.store :as store]
            [dacite.string-vectors :as sv]
            [dacite.value :as v]
            [dacite.value.finger-tree :as ft]
            [dacite.value.lit :as lit]
            [dacite.wire.binary :as bin]))

(def smile "\uD83D\uDE00")
(def clef "\uD834\uDD1E")

(defn- reason [f]
  (try (f) :accepted (catch Throwable e (:reason (ex-data e)))))

(defn- cps
  "The code points of text, by the JDK, independent of dacite.host."
  [s]
  (vec (.toArray (.codePoints ^String s))))

(defn- raw-bytes [bs] (mapv #(bit-and 0xff (long %)) bs))

(defn- cp-str [cp] (String. (Character/toChars (int cp))))

(deftest the-string-vectors-agree
  ;; emoji, mixed BMP and non-BMP, pages that end inside non-BMP text, lone
  ;; surrogates refused, invalid UTF-8 refused (nbb and reference.py check the
  ;; same file)
  (let [vs (edn/read-string (slurp "../../fixtures/fuse-sl2/vectors.edn"))]
    (is (every? seq [(:strings vs) (:utf8 vs) (:invalid-utf8 vs) (:rejected-strings vs)]))
    (is (= [] (sv/problems vs)))
    (testing "the checker does fail on a wrong answer"
      (is (seq (sv/problems (update-in vs [:strings 3 :ref] #(str "00" (subs % 2))))))
      (is (seq (sv/problems (update-in vs [:strings 3 :count] inc))))
      (is (seq (sv/problems (update-in vs [:rejected-strings 0 :reason] (constantly :other)))))
      (is (seq (sv/problems (assoc vs :invalid-utf8 [{:bytes "41"}])))))
    (testing "a case with a page that ends inside a run of non-BMP characters"
      (let [s (sv/text-of-parts (:parts (first (filter #(= "page-boundary-in-emoji" (:name %)) (:strings vs)))))
            [first-page] (lit/chunk-run "char" s lit/page-budget)]
        (is (< (lit/item-count first-page) (host/cp-count s)) "more than one page")
        (is (= smile (host/char-at (:values (:body first-page)) (dec (lit/item-count first-page)))))))))

(deftest wire-and-message-behavior-agrees-on-every-host
  ;; the same checks run on nbb (peer:test): :bad-char, the char-run count,
  ;; a lone surrogate on encode, peer messages that report or repair one
  (is (= [] (sv/behavior-problems))))

(deftest a-character-outside-the-bmp-is-one-element
  (let [st (store/mem-store)
        s (v/string st (str "a" smile "b"))]
    (is (= 3 (v/count s)))
    (is (= [\a smile \b] (v/realize s)))
    (is (= smile (v/native (v/nth s 1))))
    (is (= \b (v/native (v/nth s 2))))
    (is (= (str "a" smile "b") (v/native s)))
    (is (= 1 (v/count (v/string st smile))))
    (testing "remove-nth counts code points"
      (is (= (v/hash s) (v/hash (v/remove-nth (v/string st (str "a" smile "X" "b")) 2))))
      (is (= (v/hash (v/string st "ab")) (v/hash (v/remove-nth s 1)))))
    (testing "a character is one code point"
      (is (= smile (v/native (v/char st smile))))
      (is (= \a (v/native (v/char st \a))))
      (is (thrown? AssertionError (v/char st (str smile smile))))
      (is (thrown? AssertionError (v/char st "a")) "a BMP character is a char, not a String")
      (is (thrown? AssertionError (v/char st ""))))
    (testing "a string of one emoji is not a string of two ?"
      (is (not= (v/hash (v/string st smile)) (v/hash (v/string st "??"))))
      (is (= 4 (count (host/utf8-bytes smile)))))))

(deftest the-hash-is-the-hash-of-the-code-points
  ;; computed from the pieces, not through the finger tree
  (let [st (store/mem-store)
        types (requiring-resolve 'dacite.value.types/scalar-value-hash)
        value-hash (requiring-resolve 'dacite.value.types/value-hash)
        hash-ref->state (requiring-resolve 'dacite.hash/ref->state)
        fuse (requiring-resolve 'dacite.hash/fuse)
        identity-state @(requiring-resolve 'dacite.hash/identity-state)
        by-hand (fn [text]
                  (store/hash->hex
                   (value-hash "string"
                               (reduce (fn [a cp] (fuse a (hash-ref->state (types ["char" (host/code-point->char cp)]))))
                                       identity-state
                                       (cps text)))))]
    (doseq [text ["" "abc" smile (str "a" smile "b" clef "é") (apply str (repeat 40 clef))]]
      (is (= (by-hand text) (store/hash->hex (v/hash (v/string st text)))) (pr-str text)))))

(deftest paging-counts-code-points
  (let [text (str "x" (apply str (repeat 40 smile)) "é" (apply str (repeat 30 "日")) clef "yz")
        n (host/cp-count text)
        ref-cps (cps text)]
    (is (= n (count ref-cps)))
    (is (< n (count text)) "UTF-16 length is longer than the code point count")
    (doseq [budget [10 11 12 13 17 64 4096]]
      (binding [lit/page-budget budget]
        (let [st (store/mem-store)
              s (v/string st text)
              items (lit/chunk-run "char" text budget)
              pages (map (fn [it] (if (= "run" (:type it)) (:values (:body it)) (str (:body it)))) items)]
          (testing (str "page budget " budget)
            (is (= n (v/count s)))
            (is (= text (apply str pages)) "the pages join to the text")
            (is (= n (reduce + (map lit/item-count items))) "the item counts are code points")
            (is (every? #(nil? (host/lone-surrogate-index %)) pages) "no page ends inside a pair")
            (is (every? #(<= (lit/utf8-len %) (max 1 (- budget 10))) (remove #(= 1 (host/cp-count %)) pages))
                "no page is over the budget (a single character apart)")
            (is (= (v/hash s) (v/hash (v/string (store/mem-store) text))) "one value at every budget")
            (testing "indexing"
              (doseq [i [0 1 2 3 20 39 40 41 42 60 71 72 (dec n)]]
                (is (= (cp-str (ref-cps i)) (str (v/native (v/nth s i)))) (str "nth " i))))
            (testing "slicing"
              (doseq [[a b] [[0 1] [0 2] [1 2] [1 3] [39 43] [40 72] [0 n] [n n] [(- n 3) n]]]
                (let [sl (v/slice s a b)]
                  (is (= (apply str (map cp-str (subvec ref-cps a b))) (v/native sl)) (str "slice " a " " b))
                  (is (= (- b a) (v/count sl)))
                  (is (= (v/hash sl) (v/hash (v/string (store/mem-store) (host/cp-subs text a b))))
                      "a slice is the value of its text"))))))))))

(deftest a-page-never-ends-inside-a-pair-at-any-offset
  ;; a run of 4-byte characters cut at every offset of the budget
  (doseq [prefix (range 0 7)
          budget (range 11 30)]
    (let [text (str (apply str (repeat prefix "x")) (apply str (repeat 40 smile)))
          items (lit/chunk-run "char" text budget)
          pages (map (fn [it] (if (= "run" (:type it)) (:values (:body it)) (str (:body it)))) items)]
      (is (= text (apply str pages)) (str prefix " " budget))
      (is (every? #(nil? (host/lone-surrogate-index %)) pages) (str prefix " " budget)))))

(deftest split-keeps-pairs-whole
  (let [text (apply str (repeat 12 smile))
        item {:type "run" :body {:of "char" :values text}}]
    (doseq [target (range 1 49)]
      (let [[l r] (lit/split-body-at [item] target)
            body #(apply str (map (fn [it] (if (= "run" (:type it)) (:values (:body it)) (str (:body it)))) (lit/expand-rle-seq %)))]
        (is (= text (str (body l) (body r))))
        (is (nil? (host/lone-surrogate-index (body l))))
        (is (nil? (host/lone-surrogate-index (body r))))))))

(deftest a-lone-surrogate-is-refused
  (let [st (store/mem-store)
        hi "\uD83D" lo "\uDE00"]
    (doseq [s [hi lo (str hi "a") (str "a" lo) (str lo hi) (str hi hi) (str smile hi) (str "ab" hi)]]
      (testing (pr-str s)
        (is (= :lone-surrogate (reason #(v/string st s))))
        (is (= :lone-surrogate (reason #(host/utf8-bytes s))))
        (is (= :lone-surrogate (reason #(lit/utf8-len s))))
        (is (= :lone-surrogate (reason #(lit/chunk-run "char" s 100))))))
    (testing "the error says where"
      (let [e (try (v/string st (str "ab" hi)) (catch Exception e e))]
        (is (= {:reason :lone-surrogate :index 2 :code-unit 0xD83D} (ex-data e)))
        (is (re-find #"U\+D83D" (ex-message e)))))
    (testing "as a character"
      (is (= :lone-surrogate (reason #(v/char st (char 0xD83D)))))
      (is (= :lone-surrogate (reason #(v/char st (char 0xDE00))))))
    (testing "nothing is stored by a refused string"
      (let [st2 (store/mem-store)]
        (reason #(v/string st2 (str (apply str (repeat 5000 "a")) hi)))
        (is (= 0 (count (store/await (store/s-keys st2)))) "no page of it")))
    (testing "text inside a collection"
      (is (= :lone-surrogate (reason #(v/map st hi 1))))
      (is (= :lone-surrogate (reason #(v/vector st (str "a" lo))))))
    (testing "a well-formed pair is fine, and so is every BMP char around the surrogate range"
      (is (= 1 (v/count (v/string st smile))))
      (doseq [cp [0xD7FF 0xE000 0xFFFD 0xFFFF 0x10000 0x10FFFF]]
        (is (= 1 (v/count (v/string st (cp-str cp)))) (format "U+%04X" cp))))))

(deftest the-wire-counts-code-points-and-refuses-malformed-text
  (let [text (str "a" smile "é" clef)
        run {:type "run" :body {:of "char" :values text}}
        bs (bin/encode-lit-bytes run)
        back (bin/decode-lit-bytes bs)]
    (is (= text (:values (:body back))))
    (testing "the run's count is code points: 5 characters, 9 UTF-16 units, 11 bytes"
      (is (= [0 0 0 4] (subvec (raw-bytes bs) 2 6)) "u32 n = 4, after the type and the inner type"))
    (testing "a count in UTF-16 units is a mismatch"
      (let [raw (raw-bytes bs)
            bad (bin/as-wire-bytes (concat (take 2 raw) [0 0 0 6] (drop 6 raw)))]
        (is (thrown? clojure.lang.ExceptionInfo (bin/decode-lit-bytes bad)))))
    (testing "a lone surrogate has no wire form"
      (is (= :lone-surrogate (reason #(bin/encode-lit-bytes {:type "run" :body {:of "char" :values "a\uD83D"}}))))
      (is (= :lone-surrogate (reason #(bin/encode-lit-bytes {:type "char" :body (char 0xDE00)})))))
    (testing "a char is exactly one code point"
      (let [one (bin/encode-lit-bytes {:type "char" :body smile})]
        (is (= smile (:body (bin/decode-lit-bytes one))))
        (is (= :bad-char (reason #(bin/decode-lit-bytes (bin/as-wire-bytes [0x02 2 0x61 0x62])))))
        (is (= :bad-char (reason #(bin/decode-lit-bytes (bin/as-wire-bytes [0x02 0])))))))
    (testing "malformed UTF-8 is refused, not repaired to U+FFFD"
      (is (= :invalid-utf8 (reason #(bin/decode-lit-bytes (bin/as-wire-bytes [0x10 0 0 0 1 0xFF])))))
      (is (= :invalid-utf8 (reason #(bin/decode-lit-bytes (bin/as-wire-bytes [0x10 0 0 0 3 0xED 0xA0 0x80]))))
          "an encoded surrogate (CESU-8)")
      (is (= :invalid-utf8 (reason #(host/utf8-decode [0xC0 0xAF]))) "overlong"))
    (testing "a BOM is text, not a marker"
      (is (= "\uFEFFa" (host/utf8-decode [0xEF 0xBB 0xBF 0x61]))))))

(deftest charsequence-indexes-code-points
  (let [st (store/mem-store)
        s (v/string st (str "a" smile "b"))]
    (is (= 3 (.length ^CharSequence s)))
    (is (= \a (.charAt ^CharSequence s 0)))
    (is (= \b (.charAt ^CharSequence s 2)))
    (is (= :non-bmp-char (reason #(.charAt ^CharSequence s 1))))
    (is (= (str smile "b") (str (.subSequence ^CharSequence s 1 3))))))

(deftest host-helpers
  (is (= 0 (host/cp-count "")))
  (is (= 3 (host/cp-count (str "a" smile "b"))))
  (is (= [\a smile \b] (host/chars-of (str "a" smile "b"))))
  (is (nil? (host/chars-of "")))
  (is (= "b" (host/cp-subs (str "a" smile "b") 2)))
  (is (= smile (host/cp-subs (str "a" smile "b") 1 2)))
  (is (= 0x1F600 (host/code-point-at smile 0)))
  (is (= smile (host/code-point->char 0x1F600)))
  (is (= \a (host/code-point->char 97)))
  (is (= 1 (host/lone-surrogate-index (str "a" "\uD83D"))))
  (is (nil? (host/lone-surrogate-index (str "a" smile "b"))))
  (is (= [4 4 1]  [(host/cp-offset "ab😀" 0 3) (host/cp-offset "ab😀" 2 1) (host/cp-offset "ab😀" 0 1)])
      "an offset is in UTF-16 units"))

(def ^:private pages
  "ASCII, mixed BMP (Latin-1, CJK, symbols) and astral (emoji, clefs, mixed with
   BMP) text, each a little over a 4 KiB page of UTF-16 units."
  {:ascii (apply str (take 4100 (cycle "abcdefghij klmnop")))
   :mixed-bmp (apply str (take 3000 (cycle "aé日本語€ x")))
   :astral (apply str (take 2000 (cycle (str smile clef "a" smile "日"))))})

(defn- unit-offsets
  "Vector `v` where `(v k)` is the UTF-16 index after the first `k` code points
   of `s`, from the JDK's code point stream and `Character/charCount` (not from
   dacite.host)."
  [s]
  (vec (reductions + 0 (map #(Character/charCount (int %)) (cps s)))))

(deftest cp-count-and-cp-offset-match-the-jdk
  (doseq [[kind page] pages
          :let [page ^String page
                offs (unit-offsets page)
                n (dec (count offs))]]
    (testing (str kind " page")
      (is (= n (host/cp-count page) (.codePointCount page 0 (.length page))))
      ;; every offset from the start, and a sample from other starts
      (is (= [] (vec (for [k (range (inc n))
                           :let [want (offs k) got (host/cp-offset page 0 k)]
                           :when (not= want got)]
                       [k want got]))))
      (is (= [] (vec (for [k (range 0 (inc n) 37)
                           :let [j (min 11 (- n k))
                                 want (offs (+ k j))
                                 got (host/cp-offset page (offs k) j)]
                           :when (not= want got)]
                       [k want got]))))
      (testing "counting a suffix"
        (is (= (- n 5) (host/cp-count (subs page (offs 5))))))
      (testing "past the end is refused, even for an absurd count"
        (is (= :error (try (host/cp-offset page 0 (inc n)) (catch Exception _ :error))))
        (is (= :error (try (host/cp-offset page 0 (+ n 4294967296)) (catch Exception _ :error))))
        (is (= :error (try (host/cp-offset page (+ (.length page) 1) 0) (catch Exception _ :error))))
        (is (= (.length page) (host/cp-offset page 0 n)))))))

(defn- best-ms
  "The fastest of 5 runs of (f), in milliseconds."
  [f]
  (apply min (repeatedly 5 (fn [] (let [t (System/nanoTime)]
                                    (f)
                                    (/ (- (System/nanoTime) t) 1e6))))))

(defn- iterations-for-20ms
  "A call count that makes `(run-n n)` take at least about 20 ms, so that noise
   (a GC, a JIT compile, a scheduler slice) does not dominate a measurement."
  [run-n]
  (loop [n 20000]
    (let [t (System/nanoTime)]
      (run-n n)
      (if (or (>= (/ (- (System/nanoTime) t) 1e6) 20.0) (>= n 100000000))
        n
        (recur (* n 4))))))

(deftest indexing-inside-a-page-is-not-a-hand-written-scan
  ;; Review of #18: a hand-written pair-at? loop made `nth` and `slice` on BMP
  ;; text 3-4 times slower. This is a deliberately generous guard: within 5x of
  ;; the JDK's own String.offsetByCodePoints, best of 5, each run >= ~20 ms (the
  ;; old loop was 20 to 700x slower). Precise timing is in dev/bench_cp.clj, not
  ;; here. Takes about 2 s (calibration plus 10 runs of >= 20 ms on each of
  ;; three pages).
  (doseq [[kind page] pages
          :let [page ^String page
                top (count (cps page))
                ;; sum the results, so the JIT cannot drop the calls
                jdk-n (fn [n] (loop [i 0 acc 0]
                                (if (< i n)
                                  (recur (inc i) (+ acc (.offsetByCodePoints page 0 (mod i top))))
                                  acc)))
                ours-n (fn [n] (loop [i 0 acc 0]
                                 (if (< i n)
                                   (recur (inc i) (+ acc (long (host/cp-offset page 0 (mod i top)))))
                                   acc)))]]
    (jdk-n 20000) (ours-n 20000)        ; warm up
    (let [n (iterations-for-20ms jdk-n)
          a (best-ms #(jdk-n n))
          b (best-ms #(ours-n n))]
      (is (= (jdk-n 1000) (ours-n 1000)))
      (is (< b (* 5 a)) (str kind ": cp-offset " b " ms vs the JDK " a " ms for " n " calls")))))

(deftest the-explorer-style-walk-is-in-code-points
  ;; ft-seq of the string tree has one leaf per code point
  (let [st (store/mem-store)
        s (v/string st (str "a" smile "b" smile))]
    (is (= 4 (count (ft/ft-seq st (:root (second (store/await (store/s-get st (v/hash s)))))))))))
