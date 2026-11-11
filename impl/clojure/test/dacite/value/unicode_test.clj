(ns dacite.value.unicode-test
  "Issue #16 (a Dacite string is a sequence of Unicode code points on every
   host), on the JVM.

   The portable bodies are `dacite.value.unicode-checks`. nbb runs the same
   checks (`npm run value:test`). One deftest each, so the JVM suite still
   names the test it ran before. Three deftests stay here whole (see
   below), and `a-character-outside-the-bmp-is-one-element` keeps one
   assertion: `v/char` of a one-letter String fails, since a BMP character
   is a char. A JS host has no separate char type."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dacite.host :as host]
            [dacite.store :as store]
            [dacite.store.check :as check]
            [dacite.value :as v]
            [dacite.value.unicode-checks :as checks]))

(def env
  "Host pieces the checks take. Paths are from impl/clojure."
  {:read-vectors #(edn/read-string (slurp "../../fixtures/fuse-sl2/vectors.edn"))})

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(deftest the-string-vectors-agree
  (run-check "the-string-vectors-agree"))

(deftest wire-and-message-behavior-agrees-on-every-host
  (run-check "wire-and-message-behavior-agrees-on-every-host"))

(deftest a-character-outside-the-bmp-is-one-element
  (run-check "a-character-outside-the-bmp-is-one-element")
  ;; JVM only: a BMP character is a char, not a String.
  (is (thrown? AssertionError (v/char (store/mem-store) "a")) "a BMP character is a char, not a String"))

(deftest the-hash-is-the-hash-of-the-code-points
  (run-check "the-hash-is-the-hash-of-the-code-points"))

(deftest paging-counts-code-points
  (run-check "paging-counts-code-points"))

(deftest a-page-never-ends-inside-a-pair-at-any-offset
  (run-check "a-page-never-ends-inside-a-pair-at-any-offset"))

(deftest split-keeps-pairs-whole
  (run-check "split-keeps-pairs-whole"))

(deftest a-lone-surrogate-is-refused
  (run-check "a-lone-surrogate-is-refused"))

(deftest the-wire-counts-code-points-and-refuses-malformed-text
  (run-check "the-wire-counts-code-points-and-refuses-malformed-text"))

(deftest host-helpers
  (run-check "host-helpers"))

(deftest the-explorer-style-walk-is-in-code-points
  (run-check "the-explorer-style-walk-is-in-code-points"))

;; JVM only: java.lang.CharSequence, and the JDK as the oracle and the
;; timing baseline for dacite.host's code point offsets.

(def ^:private smile checks/smile)
(def ^:private clef checks/clef)
(def ^:private cps checks/cps)
(def ^:private reason checks/reason)

(deftest charsequence-indexes-code-points
  (let [st (store/mem-store)
        s (v/string st (str "a" smile "b"))]
    (is (= 3 (.length ^CharSequence s)))
    (is (= \a (.charAt ^CharSequence s 0)))
    (is (= \b (.charAt ^CharSequence s 2)))
    (is (= :non-bmp-char (reason #(.charAt ^CharSequence s 1))))
    (is (= (str smile "b") (str (.subSequence ^CharSequence s 1 3))))))

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

(deftest every-check-has-a-deftest
  (is (= (set (keys checks/checks))
         (disj (set (map #(-> % meta :name str)
                         (filter #(-> % meta :test) (vals (ns-interns 'dacite.value.unicode-test)))))
               "every-check-has-a-deftest"
               "charsequence-indexes-code-points"
               "cp-count-and-cp-offset-match-the-jdk"
               "indexing-inside-a-page-is-not-a-hand-written-scan"))
      "value/unicode_checks.cljc and this file list the same tests"))
