(ns dacite.value.unicode-checks
  "Issue #16: a Dacite string is a sequence of Unicode code points on every
   host. Length, indexing, paging and slicing count code points; a character
   outside the BMP is one element (not two lone surrogates, which the JVM
   encoded as `?` and a JS host as U+FFFD); a lone surrogate is refused with
   `:reason :lone-surrogate`; malformed UTF-8 on the wire is refused with
   `:reason :invalid-utf8`.

   These were the bodies of `dacite.value.unicode-test`. The JVM runs them
   from that namespace, one deftest each under its old name. nbb runs them
   from `dacite.value-cljs-test` (`npm run value:test`). `env` has
   `:read-vectors`, which reads fixtures/fuse-sl2/vectors.edn (the path
   depends on where the host runs from).

   The reference code points (`cps`, `cp-str`) come from the host's own
   string type, not from dacite.host: the JDK on the JVM, JS strings on nbb.
   A `thrown? AssertionError` there is `assert-fails?` here. One assertion
   of `a-character-outside-the-bmp-is-one-element` stays in the JVM deftest:
   `v/char` of a one-letter string fails because a BMP character is a char,
   not a String, and a JS host has no separate char type.

   JVM only, in unicode_test.clj: `charsequence-indexes-code-points`
   (java.lang.CharSequence), `cp-count-and-cp-offset-match-the-jdk` and
   `indexing-inside-a-page-is-not-a-hand-written-scan` (the JDK is the
   oracle and the timing baseline).

   `is` and `testing` are `dacite.store.check`."
  (:require [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.store :as store]
            [dacite.store.check :refer [is testing throws-info?]]
            [dacite.string-vectors :as sv]
            [dacite.value :as v]
            [dacite.value.finger-tree :as ft]
            [dacite.value.lit :as lit]
            [dacite.value.types :as types]
            [dacite.wire.binary :as bin]))

(def smile "\uD83D\uDE00")
(def clef "\uD834\uDD1E")

(defn reason [f]
  (try (f) :accepted (catch #?(:clj Throwable :cljs :default) e (:reason (ex-data e)))))

(defn cps
  "The code points of text, by the host string type, independent of
   dacite.host."
  [s]
  #?(:clj (vec (.toArray (.codePoints ^String s)))
     :cljs (mapv #(.codePointAt % 0) (js/Array.from s))))

(defn raw-bytes [bs]
  #?(:clj (mapv #(bit-and 0xff (long %)) bs)
     :cljs (mapv #(bit-and 0xff %) (js/Array.from bs))))

(defn cp-str [cp]
  #?(:clj (String. (Character/toChars (int cp)))
     :cljs (js/String.fromCodePoint cp)))

(defn- u+ [cp]
  #?(:clj (format "U+%04X" cp)
     :cljs (str "U+" (.toUpperCase (.toString cp 16)))))

(defn- assert-fails?
  "True when `(f)` fails an `assert`."
  [f]
  (try (f) false
       (catch #?(:clj AssertionError :cljs :default) e
         #?(:clj (some? e) :cljs (boolean (re-find #"Assert failed" (str (ex-message e))))))))

(defn the-string-vectors-agree
  [env]
  ;; emoji, mixed BMP and non-BMP, pages that end inside non-BMP text, lone
  ;; surrogates refused, invalid UTF-8 refused (nbb and reference.py check the
  ;; same file)
  (let [vs ((:read-vectors env))]
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

(defn wire-and-message-behavior-agrees-on-every-host
  [_]
  ;; the same checks run on nbb (peer:test): :bad-char, the char-run count,
  ;; a lone surrogate on encode, peer messages that report or repair one
  (is (= [] (sv/behavior-problems))))

(defn a-character-outside-the-bmp-is-one-element
  [_]
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
      (is (assert-fails? #(v/char st (str smile smile))))
      (is (assert-fails? #(v/char st ""))))
    (testing "a string of one emoji is not a string of two ?"
      (is (not= (v/hash (v/string st smile)) (v/hash (v/string st "??"))))
      (is (= 4 (count (host/utf8-bytes smile)))))))

(defn the-hash-is-the-hash-of-the-code-points
  [_]
  ;; computed from the pieces, not through the finger tree
  (let [st (store/mem-store)
        types types/scalar-value-hash
        value-hash types/value-hash
        hash-ref->state hash/ref->state
        fuse hash/fuse
        identity-state hash/identity-state
        by-hand (fn [text]
                  (store/hash->hex
                   (value-hash "string"
                               (reduce (fn [a cp] (fuse a (hash-ref->state (types ["char" (host/code-point->char cp)]))))
                                       identity-state
                                       (cps text)))))]
    (doseq [text ["" "abc" smile (str "a" smile "b" clef "é") (apply str (repeat 40 clef))]]
      (is (= (by-hand text) (store/hash->hex (v/hash (v/string st text)))) (pr-str text)))))

(defn paging-counts-code-points
  ;; JUSTIFICATION for the time (rule: a test over about 3 s is broken unless it
  ;; says why). Budgets 10 to 17 cut the 75-character text into one or two
  ;; characters a page, so each budget builds about 75 pages, once for `s`,
  ;; once for the second store, and again for each of the 9 slice texts. On
  ;; nbb a page costs several ms (the fuse), so the check takes about 8 s
  ;; there; the small budgets are the point of the check (pages that end next
  ;; to a pair), so they stay. nbb's ceiling is in `slow-ms`. The JVM does the
  ;; same work well under 3 s and keeps `max-ms`.
  [_]
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

(defn a-page-never-ends-inside-a-pair-at-any-offset
  [_]
  ;; a run of 4-byte characters cut at every offset of the budget
  (doseq [prefix (range 0 7)
          budget (range 11 30)]
    (let [text (str (apply str (repeat prefix "x")) (apply str (repeat 40 smile)))
          items (lit/chunk-run "char" text budget)
          pages (map (fn [it] (if (= "run" (:type it)) (:values (:body it)) (str (:body it)))) items)]
      (is (= text (apply str pages)) (str prefix " " budget))
      (is (every? #(nil? (host/lone-surrogate-index %)) pages) (str prefix " " budget)))))

(defn split-keeps-pairs-whole
  [_]
  (let [text (apply str (repeat 12 smile))
        item {:type "run" :body {:of "char" :values text}}]
    (doseq [target (range 1 49)]
      (let [[l r] (lit/split-body-at [item] target)
            body #(apply str (map (fn [it] (if (= "run" (:type it)) (:values (:body it)) (str (:body it)))) (lit/expand-rle-seq %)))]
        (is (= text (str (body l) (body r))))
        (is (nil? (host/lone-surrogate-index (body l))))
        (is (nil? (host/lone-surrogate-index (body r))))))))

(defn a-lone-surrogate-is-refused
  [_]
  (let [st (store/mem-store)
        hi "\uD83D" lo "\uDE00"]
    (doseq [s [hi lo (str hi "a") (str "a" lo) (str lo hi) (str hi hi) (str smile hi) (str "ab" hi)]]
      (testing (pr-str s)
        (is (= :lone-surrogate (reason #(v/string st s))))
        (is (= :lone-surrogate (reason #(host/utf8-bytes s))))
        (is (= :lone-surrogate (reason #(lit/utf8-len s))))
        (is (= :lone-surrogate (reason #(lit/chunk-run "char" s 100))))))
    (testing "the error says where"
      (let [e (try (v/string st (str "ab" hi)) (catch #?(:clj Exception :cljs :default) e e))]
        (is (= {:reason :lone-surrogate :index 2 :code-unit 0xD83D} (ex-data e)))
        (is (re-find #"U\+D83D" (ex-message e)))))
    (testing "as a character"
      (is (= :lone-surrogate (reason #(v/char st (char 0xD83D)))))
      (is (= :lone-surrogate (reason #(v/char st (char 0xDE00))))))
    (testing "nothing is stored by a refused string"
      (let [st2 (store/mem-store)]
        (reason #(v/string st2 (str (apply str (repeat 5000 "a")) hi)))
        (is (zero? (:entries (store/await (store/local-stats st2)))) "no page of it")))
    (testing "text inside a collection"
      (is (= :lone-surrogate (reason #(v/map st hi 1))))
      (is (= :lone-surrogate (reason #(v/vector st (str "a" lo))))))
    (testing "a well-formed pair is fine, and so is every BMP char around the surrogate range"
      (is (= 1 (v/count (v/string st smile))))
      (doseq [cp [0xD7FF 0xE000 0xFFFD 0xFFFF 0x10000 0x10FFFF]]
        (is (= 1 (v/count (v/string st (cp-str cp)))) (u+ cp))))))

(defn the-wire-counts-code-points-and-refuses-malformed-text
  [_]
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
        (is (throws-info? #(bin/decode-lit-bytes bad)))))
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

(defn host-helpers
  [_]
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

(defn the-explorer-style-walk-is-in-code-points
  [_]
  ;; ft-seq of the string tree has one leaf per code point
  (let [st (store/mem-store)
        s (v/string st (str "a" smile "b" smile))]
    (is (= 4 (count (ft/ft-seq st (:root (second (store/await (store/s-get st (v/hash s)))))))))))

(def slow-ms
  "nbb ceilings above `check/max-ms`. See the JUSTIFICATION on the check."
  {"paging-counts-code-points" 20000})

(def checks
  {"the-string-vectors-agree" the-string-vectors-agree
   "wire-and-message-behavior-agrees-on-every-host" wire-and-message-behavior-agrees-on-every-host
   "a-character-outside-the-bmp-is-one-element" a-character-outside-the-bmp-is-one-element
   "the-hash-is-the-hash-of-the-code-points" the-hash-is-the-hash-of-the-code-points
   "paging-counts-code-points" paging-counts-code-points
   "a-page-never-ends-inside-a-pair-at-any-offset" a-page-never-ends-inside-a-pair-at-any-offset
   "split-keeps-pairs-whole" split-keeps-pairs-whole
   "a-lone-surrogate-is-refused" a-lone-surrogate-is-refused
   "the-wire-counts-code-points-and-refuses-malformed-text" the-wire-counts-code-points-and-refuses-malformed-text
   "host-helpers" host-helpers
   "the-explorer-style-walk-is-in-code-points" the-explorer-style-walk-is-in-code-points})
