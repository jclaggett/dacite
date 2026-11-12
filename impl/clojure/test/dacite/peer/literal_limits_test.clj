(ns dacite.peer.literal-limits-test
  "M1.1a: page and literal size limits, payload sizing of strings and blobs,
   blob literals in vectors (Fixes #8), in-page reads, the literal cache and
   the node-item cap. Spec: docs/spec/peer-v1.md 3.2.

   Most tests are checks in `dacite.peer.literal-limits-checks`, shared with
   nbb (`npm run items:test`); each runs here as a deftest under its old
   name. Six stay here, too slow for nbb or JVM-bound:
   `a-control-value-over-one-payload-fails-at-encode-value` (`msg/encode-value`
   of 9 to 16 KB, its sizes fixed by the frame sizes: about 6 s on nbb, #63),
   `value-hashes-do-not-depend-on-the-leaf-budget` (pinned hashes of values of
   up to 40 000 elements, two passes),
   `property-pages-and-literals-stay-in-their-caps-and-round-trip` and
   `the-bound-is-never-below-the-literal-and-a-bound-that-fits-makes-one`
   (test.check over shapes of up to 3000 elements),
   `the-bound-fits-means-the-stream-never-needed-a-second-try` (eight shapes at
   two frame sizes: about 9 s on nbb even at a quarter of the sizes) and
   `concurrent-page-builds-keep-the-incremental-page-size` (JVM threads)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dacite.peer.frame :as frame]
            [dacite.peer.items :as items]
            [dacite.peer.msg :as msg]
            [dacite.peer.literal-limits-checks :as checks
             :refer [build literal-round-trips? one-byte-kinds reachable-hashes
                     text]]
            [dacite.store :as store]
            [dacite.store.check :as check]
            [dacite.store.bound :as bound]
            [dacite.store.pack :as pack]
            [dacite.value :as v]
            [dacite.value.lit :as lit]
            [dacite.value.types :as types]
            [dacite.wire.binary :as bin]))

(def ^:private env
  "The checks take no host pieces."
  {})

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

;; ---------------------------------------------------------------------------
;; Payload sizes: strings and blobs in runs and repeats
;; ---------------------------------------------------------------------------

(deftest string-and-blob-payload-counts-their-length-plus-framing
  (run-check "string-and-blob-payload-counts-their-length-plus-framing"))

(deftest runs-of-strings-and-blobs-chunk-within-the-budget
  (run-check "runs-of-strings-and-blobs-chunk-within-the-budget"))

(deftest the-leaf-page-budget-is-4-kib-and-bounds-every-page
  (run-check "the-leaf-page-budget-is-4-kib-and-bounds-every-page"))

;; ---------------------------------------------------------------------------
;; Fixes #8: a blob inside a literal page body
;; ---------------------------------------------------------------------------

(deftest a-small-blob-in-a-vector-has-a-literal-that-round-trips
  (run-check "a-small-blob-in-a-vector-has-a-literal-that-round-trips"))

(deftest a-blob-in-a-vector-has-a-literal-that-round-trips
  (run-check "a-blob-in-a-vector-has-a-literal-that-round-trips"))

;; ---------------------------------------------------------------------------
;; ft-nth reads inside the page
;; ---------------------------------------------------------------------------

(deftest nth-reads-inside-the-page-without-interning-its-elements
  (run-check "nth-reads-inside-the-page-without-interning-its-elements"))

;; ---------------------------------------------------------------------------
;; The literal cap and the literal cache
;; ---------------------------------------------------------------------------

(deftest max-literal-caps-the-item-and-defaults-to-one-payload
  (run-check "max-literal-caps-the-item-and-defaults-to-one-payload"))

(deftest a-literal-is-built-once-per-hash-per-cache
  (run-check "a-literal-is-built-once-per-hash-per-cache"))

;; ---------------------------------------------------------------------------
;; A control message is capped where it is encoded
;; ---------------------------------------------------------------------------

(defn- reason-of [f]
  (try (f) :no-throw (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest a-control-value-over-one-payload-fails-at-encode-value
  (is (bytes? (msg/encode-value ["a" "b"])))
  (is (= :too-large (reason-of #(msg/encode-value {"x" "y"} {:max-bytes 3}))))
  (testing "the default cap is one frame payload"
    (is (= :too-large (reason-of #(msg/encode-value
                                    {"k" (apply str (repeat (+ frame/max-payload 100) \a))})))))
  (testing "a message cuts it at its own frame size"
    (let [v {"k" (apply str (repeat 9000 \a))}]
      (is (some? (msg/encode-value v {:max-bytes (- 16384 6)})))
      (is (= :too-large (reason-of #(msg/encode-value v {:max-bytes (- 8192 6)})))))))

;; ---------------------------------------------------------------------------
;; The node-item cap and the smallest frame
;; ---------------------------------------------------------------------------

;; `node-items-over-a-payload-are-reported-by-default-and-throw-on-request`
;; needs only `peer.items`. It runs from `items_checks.cljc`.

(deftest hello-limits-max-frame-accepts-the-webrtc-clamp
  (run-check "hello-limits-max-frame-accepts-the-webrtc-clamp"))

(deftest the-smallest-frame-holds-the-largest-node-item
  (run-check "the-smallest-frame-holds-the-largest-node-item"))

;; ---------------------------------------------------------------------------
;; The leaf budget does not change a value hash
;; ---------------------------------------------------------------------------

(defn- leaf-budget-cases
  "Seven values over up to 40,000 elements: long strings and blobs, vectors
   of i64, strings and mixed, a map."
  [st]
  [(v/string st (text 20000 1))
   (v/blob st (let [b (byte-array 40000)] (.nextBytes (java.util.Random. 2) b) b))
   (apply v/vector st (map #(v/i64 st %) (range 4000)))
   (apply v/vector st (map #(v/string st (text 12 (+ 10 %))) (range 2000)))
   (apply v/vector st (map #(v/i64 st (* 1000003 %)) (range 8000)))
   (apply v/vector st (map (fn [i] (case (mod i 3) 0 (v/null st) 1 (v/string st (str i)) 2 (v/i64 st i)))
                           (range 3000)))
   (apply v/map st (mapcat (fn [i] [(v/i64 st i) (v/i64 st (* 7 i))]) (range 1000)))])

(def ^:private pinned-hashes
  "Hashes under hash version 2 (the SL(2, F_p) fuse, issue #14). They were first
   pinned on main at the 1 KiB budget before M1.1a with the old fuse; the fuse
   changed every one of them, the budget changes none."
  [[1078299981186003861 -155926856322451879 693347967224154184 -8866242100286208368]
   [-8210046138250363483 -5402556966473044580 -881565849737082841 7077770126167544400]
   [-1432747629841586475 119801778901863359 -7973651944650703613 6418691978646905553]
   [5221086643464979049 -4243622791092783356 7974419522164833095 -7481285042368311675]
   [-2688787341703232119 2250345235901175991 -6772763393576013057 3430969428547567943]
   [-2454016610027912395 -2168651837364051152 -674395543878613742 -8405510871974739575]
   [-4620344927445740154 -7991643559882597587 89251510965221873 -7415641222677739697]])

(deftest value-hashes-do-not-depend-on-the-leaf-budget
  ;; A page budget changes how elements are grouped into pages, not the
  ;; elements-fuse a value hashes, so these must not move: the pinned hashes
  ;; are the same at the 4 KiB budget and at the 1 KiB one.
  ;;
  ;; JUSTIFICATION for the time (rule: a test over about 3 s is broken unless it
  ;; says why). The pinned hashes cost one pass at each budget over values of
  ;; ~80,000 fuse steps, about 2.3 s a pass. The test compares the two budgets
  ;; against the same pins; the 4 KiB pass is the whole value of the pin and
  ;; the 1 KiB pass is the whole value of the test, so neither can be cut
  ;; without cutting the values themselves.
  (let [hashes (fn [] (mapv #(vec (v/hash %)) (leaf-budget-cases (store/mem-store))))]
    (is (= pinned-hashes (hashes)) "4 KiB pages")
    (is (= pinned-hashes (binding [lit/page-budget 1024] (hashes))) "1 KiB pages")))

;; ---------------------------------------------------------------------------
;; Property: every page and literal is within its cap and round-trips
;; ---------------------------------------------------------------------------

(deftest append-item-matches-its-definition
  (run-check "append-item-matches-its-definition"))

(def ^:private gen-text
  (gen/fmap (fn [[ch n]] (apply str (repeat n ch)))
            (gen/tuple (gen/elements [\a \z \u00e9 \u03bb \u20ac]) (gen/choose 0 9000))))

(def ^:private gen-shape
  (gen/one-of
   [(gen/fmap (fn [t] [:string t]) gen-text)
    (gen/fmap (fn [n] [:blob n]) (gen/choose 0 9000))
    (gen/fmap (fn [ns] [:strings ns]) (gen/vector (gen/choose 0 300) 0 120))
    (gen/fmap (fn [n] [:ints n]) (gen/choose 0 3000))
    (gen/fmap (fn [ns] [:blobs ns]) (gen/vector (gen/choose 0 200) 1 40))
    (gen/fmap (fn [[n sz]] [:map [n sz]]) (gen/tuple (gen/choose 1 150) (gen/choose 0 600)))
    (gen/fmap (fn [[n sz]] [:map [n sz]]) (gen/tuple (gen/choose 1 5) (gen/choose 3000 9000)))
    (gen/fmap (fn [n] [:mixed n]) (gen/choose 1 400))
    ;; alternating one-byte kinds in groups of 1..3 (a page of singletons, of
    ;; runs of two, ...): every element costs a tag and every run a header
    (gen/fmap (fn [[kinds len n]] [:one-byte [kinds len n]])
              (gen/tuple (gen/vector (gen/elements one-byte-kinds) 2 3)
                         (gen/choose 1 3) (gen/choose 1 2600)))
    (gen/fmap (fn [n] [:nested n]) (gen/choose 1 120))
    ;; characters outside the BMP (a surrogate pair, four bytes)
    (gen/fmap (fn [n] [:astral n]) (gen/choose 0 4000))]))

(defn- check-shape
  "Nil when `shape` is fine, else a map saying what failed."
  [shape mf]
  (let [st (store/mem-store)
        h (build st shape)
        cap (frame/max-literal-item mf)
        misses (atom [])
        {:keys [items encs oversized]} (binding [pack/*on-bound-miss* #(swap! misses conj %)]
                                         (items/encode-value st h {:max-frame-bytes mf}))
        sizes (map #(bin/byte-len %) items)
        rx (try (items/finish! (items/accept-items! nil (bin/decode-item-stream
                                                          (frame/concat-bytes items))))
                (catch Throwable e e))]
    (cond
      (seq @misses) {:fail :bound-miss :shape shape :miss (first @misses)}
      (some #(> % cap) (map second (filter #(= :literal (first %)) (map vector encs sizes))))
      {:fail :literal-over-cap :shape shape}
      (and (>= mf frame/min-max-frame) (seq oversized))
      {:fail :node-over-cap :shape shape :oversized (mapv :len oversized)}
      (and (>= mf frame/min-max-frame)
           (some #(> % frame/max-node-item-bytes)
                 (map second (filter #(= :node (first %)) (map vector encs sizes)))))
      {:fail :node-over-max-node-item-bytes :shape shape}
      (instance? Throwable rx) {:fail :verify :shape shape :why (ex-message rx)}
      (nil? (store/await (store/s-get (:store rx) h))) {:fail :root-missing :shape shape}
      ;; the receiver holds the root. (It may hold other pages than the
      ;; sender's: a literal is rebuilt in the receiver's paging, and the
      ;; hash of a value does not depend on its paging.)
      )))

(def ^:private property-samples
  "Samples per frame size. Each is a value of up to a few thousand nodes and
   every node is a few dozen fuse steps. A fuse step costs about 1.7 us on the
   JVM since the SL(2, F_p) fuse with a 256-bit prime (issue #14); 10 samples
   per frame size keep each of the two tests that use this under about 3 s.
   Before the switch it was 30: the trim is a choice of the PR, not a finding,
   and the property is still checked on every run."
  10)

(deftest property-pages-and-literals-stay-in-their-caps-and-round-trip
  (doseq [mf [frame/min-max-frame frame/max-frame]]
    (let [r (tc/quick-check
             property-samples
             (prop/for-all [shape gen-shape]
               (nil? (check-shape shape mf))))]
      (is (:pass? r) (str "max-frame " mf " seed " (:seed r)
                          " failed on " (pr-str (:shrunk r)))))))

;; ---------------------------------------------------------------------------
;; The size model: what a page counts is what it costs on the wire (review S1)
;; ---------------------------------------------------------------------------

(deftest the-size-model-is-never-below-the-wire-size
  (run-check "the-size-model-is-never-below-the-wire-size"))

(deftest alternating-one-byte-kinds-stay-in-a-minimum-frame
  (run-check "alternating-one-byte-kinds-stay-in-a-minimum-frame"))

(deftest the-node-item-bound-has-room-over-the-page-budget
  (run-check "the-node-item-bound-has-room-over-the-page-budget"))

;; ---------------------------------------------------------------------------
;; M1: a blob over 16 KB is not quadratic to encode
;; ---------------------------------------------------------------------------

(deftest pack-u8-values-is-linear
  (run-check "pack-u8-values-is-linear"))

(deftest a-64-kb-blob-encodes-in-seconds-not-minutes
  (run-check "a-64-kb-blob-encodes-in-seconds-not-minutes"))

;; ---------------------------------------------------------------------------
;; S2: utf8-len, and building a page one element at a time
;; ---------------------------------------------------------------------------

(deftest utf8-len-is-the-length-of-the-utf8-bytes
  (run-check "utf8-len-is-the-length-of-the-utf8-bytes"))

(deftest payload-after-append-is-the-payload-of-the-appended-body
  (run-check "payload-after-append-is-the-payload-of-the-appended-body"))

(deftest a-page-is-not-recounted-for-every-element
  (run-check "a-page-is-not-recounted-for-every-element"))

(deftest concurrent-page-builds-keep-the-incremental-page-size
     ;; The memo of the last page's size is per thread. The four builds run
     ;; in lock step (a barrier at every append), so under one memo shared by
     ;; all of them each append would find another thread's page and recount.
     (let [real-payload lit/payload-bytes
           real-append lit/append-item
           calls (atom 0)
           threads 4
           appends 400
           barrier (java.util.concurrent.CyclicBarrier. threads)
           build (fn [seed]
                   (let [st (store/mem-store)]
                     (vec (v/hash (reduce (fn [c i] (v/conj c (v/char st (char (+ 97 (mod (+ i seed) 26))))))
                                          (v/vector st) (range appends))))))
           expected (mapv build (range threads))]
       (with-redefs [lit/payload-bytes (fn [b] (swap! calls inc) (real-payload b))
                     lit/append-item (fn [& args]
                                       (.await barrier 10 java.util.concurrent.TimeUnit/SECONDS)
                                       (apply real-append args))]
         (reset! calls 0)
         (let [t0 (System/nanoTime)
               fs (mapv #(future (build %)) (range threads))
               got (mapv #(deref % 20000 :timeout) fs)
               ms (/ (- (System/nanoTime) t0) 1e6)]
           (is (= expected got) "the same hashes as built one at a time")
           (is (< @calls (* 10 threads)) (str @calls " recounts for " (* threads appends) " appends"))
           (is (< ms 3000) (str ms " ms"))))))

;; ---------------------------------------------------------------------------
;; S3/S4: the literal attempt, its gate and its cache
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; The node bound (store.bound) replaces the literal gate (M1.1b)
;; ---------------------------------------------------------------------------

(defn- bound-failures
  "Over each node under `h` whose bound is at most `limit`, the faults: the
   bound under the size of the literal built, or (the bound at most one of
   `caps`) a literal that cannot be made, does not fit or does not round-trip."
  [st h caps limit]
  (let [cache (atom {})]
    (for [x (reachable-hashes st h)
          :let [b (bound/literal-item-bound st x cache)]
          :when (and b (<= (long b) (long limit)))
          :let [form (try (pack/literal-of st x) (catch Throwable _ nil))
                size (when form
                       (pack/wire-item-size (pack/literal-item x (:type form) (:body form))))
                fits (filter #(<= (long b) (long %)) caps)]
          fault [(when (and form (< (long b) (long size))) :bound-under-size)
                 (when (and (seq fits) (nil? form)) :fits-but-no-literal)
                 ;; the stored size-bytes never skips a node the bound lets through
                 (when (some #(and (<= (long b) (long %))
                                   (when-let [sb (some-> (store/await (store/s-get st x)) types/entry-data
                                                         (as-> d (when (map? d) (or (:size-bytes d) (get-in d [:measure :size-bytes])))))]
                                     (> (long sb) (long %))))
                             caps)
                   :measure-skips-a-fitting-node)
                 ;; (an inner node of a tree has a literal that may not round-trip:
                 ;; it is not the value its entries make; the sender then
                 ;; sends the node item, as before)
                 (when (and (seq fits) form
                            (not (re-find #"^(hamt|ft)/" (str (first (store/await (store/s-get st x))))))
                            (not (literal-round-trips? st x)))
                   :round-trip)]
          :when fault]
      {:fault fault :bound b :size size :type (:type form)})))

(deftest the-bound-is-never-below-the-literal-and-a-bound-that-fits-makes-one
  ;; Over every node of a generated value: bound >= the size of the literal
  ;; built (so a literal is never built to find that it is over), and a node
  ;; whose bound is under the cap does have a literal. Nodes whose bound is
  ;; over 40 KB are not built: it keeps the test to a second or two.
  (let [caps [(frame/max-literal-item frame/min-max-frame) (frame/max-literal-item)]
        r (tc/quick-check
           property-samples
           (prop/for-all [shape gen-shape]
             (let [st (store/mem-store)
                   h (build st shape)]
               (empty? (bound-failures st h caps 40000)))))]
    (is (:pass? r) (str "seed " (:seed r) " failed on " (pr-str (:shrunk r))))))

(deftest the-bound-fits-means-the-stream-never-needed-a-second-try
  ;; encode-value reports a literal that its bound let through and that then
  ;; did not fit or did not round-trip. None may.
  (let [misses (atom [])]
    (binding [pack/*on-bound-miss* #(swap! misses conj %)]
      (doseq [shape [[:string 30000] [:astral 4000] [:one-byte [[:u8 :char] 2 2600]]
                     [:strings (repeat 100 250)] [:map [100 600]] [:nested 120]
                     [:ints 3000] [:blobs [200 200 200 200 200 200]]]
              mf [frame/min-max-frame frame/max-frame]]
        (let [st (store/mem-store)
              h (build st (if (= :string (first shape)) [:string (text (second shape) 5)] shape))]
          (is (seq (:items (items/encode-value st h {:max-frame-bytes mf})))))))
    (is (empty? @misses) (pr-str (take 3 @misses)))))

(deftest an-inner-node-literal-that-does-not-round-trip-falls-back-to-its-node-item
  (run-check "an-inner-node-literal-that-does-not-round-trip-falls-back-to-its-node-item"))

(deftest a-node-whose-stored-size-is-over-the-cap-is-not-walked-for-a-bound
  (run-check "a-node-whose-stored-size-is-over-the-cap-is-not-walked-for-a-bound"))

(deftest the-bound-counts-no-compression-and-never-less-than-a-run-costs
  (run-check "the-bound-counts-no-compression-and-never-less-than-a-run-costs"))

(deftest the-bound-summaries-combine-as-a-monoid
  (run-check "the-bound-summaries-combine-as-a-monoid"))

(deftest utf8-len-adds-over-runs-cut-at-code-points
  (run-check "utf8-len-adds-over-runs-cut-at-code-points"))

(deftest a-string-over-the-cap-is-not-built-to-learn-it
  (run-check "a-string-over-the-cap-is-not-built-to-learn-it"))

(deftest the-literal-cache-is-per-hash-per-cap
  (run-check "the-literal-cache-is-per-hash-per-cap"))

;; ---------------------------------------------------------------------------
;; The chunk budget follows the leaf page size
;; ---------------------------------------------------------------------------

(deftest the-default-chunk-budget-is-at-least-a-leaf-page
  (run-check "the-default-chunk-budget-is-at-least-a-leaf-page"))

(deftest the-first-get-of-a-long-string-carries-its-first-characters
  (run-check "the-first-get-of-a-long-string-carries-its-first-characters"))

(deftest every-check-has-a-deftest
  (is (every? #(-> (ns-interns 'dacite.peer.literal-limits-test) (get (symbol %)) meta :test)
              (keys checks/checks))
      "each check in literal_limits_checks.cljc has a deftest here"))
