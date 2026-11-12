(ns dacite.peer.literal-limits-checks
  "Page and literal size limits on the JVM and on nbb: payload sizing of
   strings and blobs, small blobs in a vector (Fixes #8), the literal cap
   and its cache, the hello frame limits, the smallest frame, the size
   model, the node bound (store.bound), the first get of a long string,
   the literal cache counts, the bound walk, inner-node fallbacks and the
   encode times of a long run of u8s and of a 64 KB blob.
   Spec:
   docs/spec/peer-v1.md 3.2. The JVM runs them from
   `dacite.peer.literal-limits-test`, one deftest each under its old name;
   nbb runs them from `dacite.peer.items-cljs-test` (`npm run items:test`).

   `is` and `testing` are `dacite.store.check`. The checks take no host
   pieces (`env` is unused)."
  (:require [dacite.host :as host]
            [dacite.peer.examples :as ex]
            [dacite.peer.frame :as frame]
            [dacite.peer.items :as items]
            [dacite.peer.msg :as msg]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.bound :as bound]
            [dacite.store.check :refer [is testing]]
            [dacite.store.pack :as pack]
            [dacite.value :as v]
            [dacite.value.finger-tree :as ft]
            [dacite.value.lit :as lit]
            #?(:clj [clojure.test.check :as tc])
            #?(:clj [clojure.test.check.generators :as gen]
               :cljs [dacite.value.seeded-gen :as gen])
            #?(:clj [clojure.test.check.properties :as prop])
            [dacite.value.types :as types]
            [dacite.wire.binary :as bin]))

(defn- zero-bytes
  "`n` zero bytes on this host."
  [n]
  #?(:clj (byte-array n) :cljs (js/Uint8Array. n)))

(defn- host-bytes
  "Host bytes for the ints `xs` (the low 8 bits of each)."
  [xs]
  #?(:clj (byte-array (map unchecked-byte xs))
     :cljs (js/Uint8Array.from (clj->js (map #(bit-and 0xff %) xs)))))

(defn- host-bytes?
  "True for this host's bytes."
  [x]
  #?(:clj (bytes? x) :cljs (instance? js/Uint8Array x)))

(defn bytes-of
  "`n` bytes: 0, 7, 14, ... (the low 8 bits)."
  [n]
  (host-bytes (map #(* 7 %) (range n))))

(defn text
  "`n` letters a..z from `seed`: java.util.Random on the JVM, as before;
   `dacite.value.seeded-gen` on nbb."
  [n seed]
  #?(:clj (let [r (java.util.Random. seed)]
            (apply str (repeatedly n #(char (+ 97 (.nextInt r 26))))))
     :cljs (let [g (gen/choose 0 25) r (volatile! seed)]
             (apply str (repeatedly n #(.fromCharCode js/String (+ 97 (g r))))))))

(defn literal-round-trips?
  "The literal of `h` rebuilds `h` in a fresh store."
  [st h]
  (let [form (pack/literal-of st h)
        st2 (store/mem-store)
        got (pack/materialize-literal! st2 (:type form) (:body form))]
    (= (vec h) (vec got))))

(defn- reason-of [f]
  (try (f) :no-throw
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e (:reason (ex-data e)))))

(defn string-and-blob-payload-counts-their-length-plus-framing
  [env]
  (let [five lit/string-wire-overhead
        hdr lit/run-header-bytes]
    (is (= 5 five))
    (is (= 6 hdr))
    (is (= (+ five 3) (lit/item-payload-bytes {:type "string" :body "abc"})))
    (is (= (+ five 4) (lit/item-payload-bytes {:type "blob" :body (bytes-of 4)})))
    (testing "a run counts every value (it counted 0), and its header"
      (is (= (+ hdr five 1 five 2 five 3)
             (lit/item-payload-bytes
              {:type "run" :body {:of "string" :values ["a" "bb" "ccc"]}})))
      (is (= (+ hdr five 2 five 5)
             (lit/item-payload-bytes
              {:type "run" :body {:of "blob" :values [(bytes-of 2) (bytes-of 5)]}}))))
    (testing "a repeat counts n copies (it counted 0), and its header"
      (is (= (+ hdr (* 10 (+ five 4)))
             (lit/item-payload-bytes
              {:type "repeat" :body {:of "string" :n 10 :value "abcd"}})))
      (is (= (+ hdr (* 3 (+ five 6)))
             (lit/item-payload-bytes
              {:type "repeat" :body {:of "blob" :n 3 :value (bytes-of 6)}}))))
    (testing "an element is never free, so a page of nulls ends"
      (is (pos? (lit/item-payload-bytes {:type "null" :body nil})))
      (is (pos? (lit/item-payload-bytes {:type "negative" :body nil})))
      (is (= (+ hdr 100) (lit/item-payload-bytes
                          {:type "repeat" :body {:of "null" :n 100 :value nil}}))))))

(defn runs-of-strings-and-blobs-chunk-within-the-budget
  [env]
  (let [vs (vec (repeat 300 "0123456789"))     ; 15 bytes each
        chunks (lit/chunk-run "string" vs 1000)]
    (is (< 1 (count chunks)))
    (is (= vs (vec (mapcat #(let [b (:body %)]
                              (if (= "run" (:type %)) (:values b) [(:body %)]))
                           chunks))))
    (is (every? #(<= (lit/item-payload-bytes %) 1000) chunks)
        "no chunk is over the budget (a single value over it is alone)"))
  (let [big (apply str (repeat 5000 \x))
        chunks (lit/chunk-run "string" [big "a"] 1000)]
    (is (= 2 (count chunks)) "a value over the budget has a chunk to itself")))

(defn a-small-blob-in-a-vector-has-a-literal-that-round-trips
  ;; Fixes #8. Split from `a-blob-in-a-vector-has-a-literal-that-round-trips`,
  ;; which keeps its long run of blobs on the JVM. The last case puts raw
  ;; host bytes (a `byte[]`, a `Uint8Array`) in `v/vector` as a blob.
  [env]
  (let [st (store/mem-store)
        b1 (v/blob st (host-bytes [1 2 3]))
        b2 (v/blob st (bytes-of 9))]
    (doseq [[label vec-of] {"[blob nil]" (v/vector st b1 (v/null st))
                            "[nil blob]" (v/vector st (v/null st) b1)
                            "[blob blob]" (v/vector st b1 b2)
                            "[blob 1]" (v/vector st b1 (v/i64 st 1))
                            "[1 blob]" (v/vector st (v/i64 st 1) b2)
                            "[blob \"s\" blob]" (v/vector st b1 (v/string st "s") b1)}]
      (testing label
        (is (literal-round-trips? st (v/hash vec-of)))))
    (testing "the peer message codec encodes it (the original report)"
      (let [st (store/mem-store)
            h (v/hash (v/vector st (zero-bytes 1) nil))]
        (is (some? (items/literal-bytes st h frame/max-payload)))))))

(defn max-literal-caps-the-item-and-defaults-to-one-payload
  [env]
  (is (= frame/max-payload pack/default-max-literal))
  (let [st (store/mem-store)
        h (v/hash (v/string st (apply str (repeat 3000 \x))))
        entry (store/await (store/s-get st h))]
    (testing "room: a literal"
      (is (= :literal (:encoding (pack/encode-item st h entry {:max-literal 4000})))))
    (testing "no room: the node entry"
      (is (= :node (:encoding (pack/encode-item st h entry {:max-literal 2000})))))))

(defn hello-limits-max-frame-accepts-the-webrtc-clamp
  [env]
  ;; 8192 (`min-max-frame`) still holds a node item in one frame. The codec
  ;; also accepts 4096 (`webrtc-max-frame`): that is what the WebRTC transport
  ;; advertises, under the node-item minimum, until node-datachannel is fixed.
  (let [hello (fn [n] {:type :hello :stream 0
                       :value (assoc-in (:value (first ex/examples))
                                        ["limits" "max-frame"] n)})]
    (is (= :invalid-message (reason-of #(msg/encode-message (hello (dec frame/webrtc-max-frame)))))
        "one under the webrtc clamp")
    (is (host-bytes? (msg/encode-message (hello frame/webrtc-max-frame)))
        "4096 is legal so a webrtc hello can be sent")
    (is (host-bytes? (msg/encode-message (hello frame/min-max-frame))))
    (is (host-bytes? (msg/encode-message (hello frame/max-frame))))
    (is (= :invalid-message (reason-of #(msg/encode-message (hello (inc frame/max-frame))))))))

(defn the-smallest-frame-holds-the-largest-node-item
  [env]
  (is (= 8192 frame/min-max-frame))
  (is (frame/min-max-frame-fits-node?))
  (is (<= frame/max-node-item-bytes (frame/max-literal-item frame/min-max-frame))))

(defn the-size-model-is-never-below-the-wire-size
  [env]
  (let [wire #(bin/byte-len (bin/encode-lit-bytes %))
        h32 (vec (repeat 32 0))
        exact [{:type "u8" :body 7} {:type "bool" :body true} {:type "i64" :body 5}
               {:type "f64" :body 1.5} {:type "ref" :body h32}
               {:type "char" :body \a} {:type "char" :body \u03bb}
               {:type "run" :body {:of "char" :values "ab\u03bb"}}
               {:type "run" :body {:of "u8" :values (zero-bytes 5)}}
               {:type "run" :body {:of "i64" :values [1 2 3]}}
               {:type "run" :body {:of "bool" :values [true false]}}
               {:type "string" :body "abc"} {:type "blob" :body (zero-bytes 3)}]
        over [{:type "repeat" :body {:of "i64" :n 2 :value 4}}   ; counts n copies
              {:type "null" :body nil} {:type "negative" :body nil}
              {:type "run" :body {:of "null" :values [nil nil]}}
              {:type "repeat" :body {:of "null" :n 2 :value nil}}
              {:type "repeat" :body {:of "bool" :n 5 :value true}}
              {:type "run" :body {:of "string" :values ["a" "bc"]}}]]
    (doseq [it exact]
      (is (= (wire it) (lit/item-payload-bytes it))
          (str "exact for " (:type it) " " (pr-str (:body it)))))
    (doseq [it over]
      (is (<= (wire it) (lit/item-payload-bytes it))
          (str "never under for " (:type it))))
    (testing "the worst case is a short run: its header is counted"
      (is (<= (wire {:type "run" :body {:of "u8" :values (zero-bytes 2)}})
              (lit/item-payload-bytes {:type "run" :body {:of "u8" :values (zero-bytes 2)}}))))))

(defn the-node-item-bound-has-room-over-the-page-budget
  [env]
  (is (<= (+ lit/page-budget 400) frame/max-node-item-bytes)
      "a full page plus its measure and the page's own fields (the worst seen is about 4.2 KB)"))

(defn the-bound-counts-no-compression-and-never-less-than-a-run-costs
  [env]
  (let [st (store/mem-store)
        b (fn [x] (- (bound/literal-item-bound st (v/hash x) nil) bound/item-header-bytes))
        wire (fn [x] (let [f (pack/literal-of st (v/hash x))]
                       (bin/byte-len (bin/encode-lit-bytes {:type (:type f) :body (:body f)}))))]
    (testing "a blob or a string is its bytes plus five"
      (is (= 105 (b (v/blob st (bytes-of 100)))))
      (is (= 105 (b (v/string st (apply str (repeat 100 \a)))))))
    (testing "equal values are counted one by one: no repeat"
      (let [x (apply v/vector st (repeat 1000 (v/i64 st 7)))]
        (is (= (+ 5 6 (* 8 1000)) (b x)))
        (is (< (wire x) 100) "the repeat is far under it")))
    (testing "two u8 as a run (8 bytes) cost more than two singles (4): the bound counts the run"
      (let [x (v/vector st (v/u8 st 1) (v/u8 st 2))]
        (is (<= (wire x) (b x)))))
    (testing "a type change starts a new group and pays its header again"
      (let [x (v/vector st (v/u8 st 1) (v/bool st true) (v/u8 st 2))]
        (is (= (+ 5 (* 3 (+ 6 1))) (b x)))
        (is (<= (wire x) (b x)))))
    (testing "a value with no literal has no bound"
      (is (nil? (bound/literal-item-bound st (vec (repeat 32 0)) nil))))))

(defn the-bound-summaries-combine-as-a-monoid
  [env]
  (let [c bound/combine
        u8 (fn [n] {:f "u8" :l "u8" :v n :b (+ 6 n)})
        ch {:f "char" :l "char" :v 3 :b 13}]
    (is (= (u8 4) (c nil (u8 4))))
    (is (= (u8 4) (c (u8 4) nil)))
    (testing "equal types merge: the header once"
      (is (= {:f "u8" :l "u8" :v 5 :b 11} (c (u8 2) (u8 3)))))
    (testing "different types do not"
      (is (= {:f "u8" :l "char" :v 5 :b 21} (c (u8 2) ch))))
    (testing "associative"
      (let [xs [(u8 1) ch (u8 2) (u8 3) ch]]
        (doseq [i (range 1 4) j (range (inc i) 5)]
          (let [[a b d] [(subvec xs 0 i) (subvec xs i j) (subvec xs j)]
                red #(reduce c nil %)]
            (is (= (red xs) (c (c (red a) (red b)) (red d)) (c (red a) (c (red b) (red d)))))))))))

(defn utf8-len-adds-over-runs-cut-at-code-points
  [env]
  ;; The bound sums the bytes of runs. That is the bytes of the joined text
  ;; only when no run cuts a surrogate pair, which the splitters never do.
  (let [s "ab\u00e9\uD83D\uDE00x\uD83D\uDE01"]
    (doseq [k (range (inc (host/cp-count s)))]
      (is (= (lit/utf8-len s)
             (+ (lit/utf8-len (host/cp-subs s 0 k)) (lit/utf8-len (host/cp-subs s k (host/cp-count s)))))
          (str "cut at code point " k)))))

(defn the-literal-cache-is-per-hash-per-cap
  [env]
  (let [st (store/mem-store)
        h (v/hash (v/string st (apply str (repeat 3000 \x))))
        entry (store/await (store/s-get st h))
        cache (atom {})]
    (is (= :literal (:encoding (pack/encode-item st h entry {:max-literal 4000 :cache cache}))))
    (is (= :node (:encoding (pack/encode-item st h entry {:max-literal 2000 :cache cache})))
        "the answer for another cap is not the cached one")
    (is (= :literal (:encoding (pack/encode-item st h entry {:max-literal 4000 :cache cache})))
        "and the first is still cached")))

(defn the-default-chunk-budget-is-at-least-a-leaf-page
  [env]
  ;; Below a page, a full page is never a literal (it is over the gate), so
  ;; the first GET of a long string carries its header and spine and not its
  ;; first characters. Two constants, kept apart: one is a soft chunk target,
  ;; the other a hard cap on a page.
  (is (>= pack/default-budget lit/page-budget))
  (is (= 4096 pack/default-budget)))

(defn the-first-get-of-a-long-string-carries-its-first-characters
  [env]
  (let [st (store/mem-store)
        s (v/string st (text 20000 9))
        chunk (pack/pack-under st (v/hash s))
        st2 (store/mem-store)]
    (pack/apply-chunk! st2 chunk)
    (is (<= (count (:items chunk)) 6))
    (is (= (subs (text 20000 9) 0 64)
           (apply str (take 64 (v/realize s))))
        "(the sender's own copy; the receiver below)")
    (let [root (:root (types/entry-data (store/await (store/s-get st2 (v/hash s)))))]
      (is (= 64 (count (filter #(store/await (store/s-has? st2 %))
                               (take 64 (ft/ft-seq st2 root)))))
          "the first 64 characters are in the first chunk"))))

;; ---------------------------------------------------------------------------
;; Properties: test.check on the JVM, a fixed seed on nbb
;; ---------------------------------------------------------------------------

(defn- property
  "Run the property `f` on `n` draws from `gens`: test.check on the JVM
   (`for-all*`, shrinking), a fixed seed on nbb (`dacite.value.seeded-gen`,
   no shrinking). Returns the result map, :pass? :seed and, on a failure,
   :shrunk; the caller asserts :pass?, as the tests did before. nbb runs
   the JVM's counts: each property here takes under 0.4 s there."
  [n gens f]
  #?(:clj (tc/quick-check n (prop/for-all* gens f))
     :cljs (gen/quick-check n (apply gen/tuple gens) #(apply f %) 21)))

(defn append-item-matches-its-definition
  ;; The fast append rewrites only the last group; it must give what
  ;; expand + conj + rle-lits gives, for any run of elements.
  [env]
  (let [slow @#'lit/append-slow
        gen-el (gen/one-of
                [(gen/fmap (fn [c] {:type "char" :body c}) (gen/elements [\a \b \u03bb]))
                 (gen/fmap (fn [n] {:type "u8" :body n}) (gen/elements [0 1 255]))
                 (gen/fmap (fn [n] {:type "i64" :body n}) (gen/elements [1 2]))
                 (gen/fmap (fn [x] {:type "string" :body x}) (gen/elements ["x" "yy"]))
                 (gen/return {:type "null" :body nil})
                 (gen/fmap (fn [h] {:type "ref" :body h}) (gen/elements [[1 2 3 4] [5 6 7 8]]))])
        norm (fn [body]
               (mapv (fn [it]
                       (if (and (map? it) (= "run" (:type it)) (lit/u8-bytes? (:values (:body it))))
                         (update-in it [:body :values] vec)
                         it))
                     body))
        r (property 200
                    [(gen/vector gen-el 0 40)]
                    (fn [els]
                      (let [fast (reduce lit/append-item [] els)
                            ref (reduce slow [] els)]
                        (= (norm fast) (norm ref)))))]
    (is (:pass? r) (pr-str (:shrunk r)))))

(defn utf8-len-is-the-length-of-the-utf8-bytes
  [env]
  (let [host-len #(count (host/utf8-bytes %))
        hi "\uD83D" lo "\uDE00"]
    (doseq [s ["" "a" "abc" "\u00e9" "\u03bb\u03bb" "\u20ac" "x\u20acy" (str hi lo) (str "a" hi lo "b")]]
      (is (= (host-len s) (lit/utf8-len s)) (pr-str s)))
    (testing "a lone surrogate has no UTF-8 form: it throws, as the encoder does (#16)"
      (doseq [s [hi lo (str hi "a") (str "a" lo) (str lo hi) (str hi hi lo) (str "z" hi) (str lo lo)]]
        (is (= :lone-surrogate (:reason (ex-data (try (lit/utf8-len s)
                                                      (catch #?(:clj Exception :cljs :default) e e)))))
            (pr-str s))))
    (testing "a character (it is called with one)"
      (is (= 2 (lit/utf8-len \u03bb)))
      (is (= 1 (lit/utf8-len \a))))
    (let [gen-cp (gen/one-of [(gen/choose 0 0x7f) (gen/choose 0x80 0x7ff) (gen/choose 0x800 0xd7ff)
                              (gen/choose 0xe000 0xffff) (gen/choose 0x10000 0x10ffff)])
          cp-str #?(:clj #(String. (Character/toChars (int %)))
                    :cljs #(.fromCodePoint js/String %))
          r (property 500
                      [(gen/vector gen-cp 0 40)]
                      (fn [cps]
                        (let [s (apply str (map cp-str cps))]
                          (= (host-len s) (lit/utf8-len s)))))]
      (is (:pass? r) (pr-str (:shrunk r))))))

(defn payload-after-append-is-the-payload-of-the-appended-body
  [env]
  (let [gen-el (gen/one-of
                [(gen/fmap (fn [c] {:type "char" :body c}) (gen/elements [\a \b \u03bb]))
                 (gen/fmap (fn [n] {:type "u8" :body n}) (gen/elements [0 1 255]))
                 (gen/fmap (fn [n] {:type "i64" :body n}) (gen/elements [1 2]))
                 (gen/fmap (fn [x] {:type "string" :body x}) (gen/elements ["x" "yy"]))
                 (gen/return {:type "null" :body nil})
                 (gen/fmap (fn [h] {:type "ref" :body h}) (gen/elements [[1 2 3 4] [5 6 7 8]]))])
        r (property 300
                    [(gen/vector gen-el 0 60)]
                    (fn [els]
                      (loop [body [] pb 0 els els]
                        (if-let [el (first els)]
                          (let [b' (lit/append-item body el)
                                pb' (lit/payload-after-append body pb b')]
                            (and (= pb' (lit/payload-bytes b'))
                                 (recur b' pb' (rest els))))
                          true))))]
    (is (:pass? r) (pr-str (:shrunk r)))))

;; ---------------------------------------------------------------------------
;; The shape builder, the literal cache, the bound walk, encode times
;; ---------------------------------------------------------------------------

(defn- now-ms
  "A clock in ms on this host."
  []
  #?(:clj (/ (System/nanoTime) 1e6) :cljs (.now js/Date)))

(def nbb-cases
  "Smaller sizes on nbb, where each element of a vector or map takes about
   2.5 ms to build and a map literal about 13 ms to rebuild (#63). The JVM
   keeps the sizes it had.

   :inner-map  the large map shape of
               `an-inner-node-literal-that-does-not-round-trip-falls-back-to-its-node-item`
               (JVM [150 40]: on nbb its 350 inner nodes take about 6 s to
               rebuild; at [30 40] it is still a HAMT of several levels)"
  {:inner-map [30 40]})

(def one-byte-kinds
  "Kinds that are one byte (or two) of body: the ones whose page size was
   under-counted (M1.1a review S1)."
  [:u8 :char :bool :null :greek :neg])

(defn one-byte-el
  "Element `i` of the one-byte kind `kind`."
  [st kind i]
  (case kind
    :u8 (v/u8 st (mod i 256))
    :char (v/char st (char (+ 97 (mod i 26))))
    :greek (v/char st (char (+ 0x3b1 (mod i 20))))
    :bool (v/bool st (even? (quot i 2)))
    :null (v/null st)
    :neg (v/i64 st (- (inc i)))))

(defn build
  "Build the shape [kind x] in `st` and return its hash. The checks here
   name their shapes; the JVM properties draw them."
  [st [kind x]]
  (case kind
    :string (v/hash (v/string st x))
    :blob (v/hash (v/blob st (bytes-of x)))
    :strings (v/hash (apply v/vector st (map #(v/string st (apply str (repeat % \s))) x)))
    :ints (v/hash (apply v/vector st (map #(v/i64 st %) (range x))))
    :blobs (v/hash (apply v/vector st (map #(v/blob st (bytes-of %)) x)))
    :map (let [[n sz] x]
           (v/hash (apply v/map st (mapcat (fn [i] [(str "k" i) (v/string st (apply str (repeat sz \v)))])
                                           (range n)))))
    :mixed (v/hash (apply v/vector st
                          (map (fn [i] (case (mod i 4)
                                         0 (v/null st)
                                         1 (v/blob st (bytes-of (mod i 50)))
                                         2 (v/string st (str "e" i))
                                         3 (v/i64 st i)))
                               (range x))))
    :one-byte (let [[kinds len n] x]
                (v/hash (apply v/vector st
                               (map (fn [i] (one-byte-el st (nth kinds (mod (quot i len) (count kinds))) i))
                                    (range n)))))
    :nested (v/hash (apply v/vector st
                           (map (fn [i] (apply v/vector st (map #(v/i64 st %) (range (mod i 9)))))
                                (range x))))
    :astral (v/hash (v/string st (apply str (map #(if (even? %) "\uD83D\uDE00" "x") (range x)))))))

(defn reachable-hashes
  "Every node under `h` (a node once), breadth first."
  [st h]
  (loop [q [h] seen #{} out []]
    (if (empty? q)
      out
      (let [x (peek q) q (pop q) k (gc/hash-key x)]
        (if (contains? seen k)
          (recur q seen out)
          (let [e (store/await (store/s-get st x))]
            (recur (into q (or (types/child-hashes e) [])) (conj seen k) (conj out x))))))))

(defn a-literal-is-built-once-per-hash-per-cache
  [env]
  (let [st (store/mem-store)
        shared (v/string st "the quick brown fox")
        h (v/hash (apply v/map st (mapcat (fn [i] [(str "k" i) shared]) (range 30))))
        calls (atom 0)
        real pack/literal-of]
    (with-redefs [pack/literal-of (fn [& args] (swap! calls inc) (apply real args))]
      (let [shared-h (v/hash shared)
            entry (store/await (store/s-get st shared-h))
            cache (atom {})]
        (dotimes [_ 10]
          (pack/encode-item st shared-h entry {:max-literal 16378 :cache cache}))
        (is (= 1 @calls) "ten asks, one build")
        (reset! calls 0)
        (dotimes [_ 3]
          (pack/encode-item st shared-h entry {:max-literal 16378}))
        (is (= 3 @calls) "no cache, no saving")))
    (testing "encode-value of a value holding it 30 times builds it once"
      (let [shared-key (vec (v/hash shared))
            for-shared (atom 0)]
        (with-redefs [pack/literal-of (fn [s' h' & more]
                                        (when (= shared-key (vec h')) (swap! for-shared inc))
                                        (apply real s' h' more))]
          (items/encode-value st h)
          (is (= 1 @for-shared) "the shared string is built once"))))))

(defn pack-u8-values-is-linear
  ;; It indexed a lazy seq with `nth` (O(n) each): 100 000 bytes took 110 s.
  [env]
  (let [lits (map (fn [i] {:type "u8" :body (mod i 256)}) (range 100000))
        t0 (now-ms)
        packed (@#'lit/pack-u8-values lits)
        ms (- (now-ms) t0)]
    (is (= 100000 (alength ^bytes packed)))
    (is (= [0 1 255 0] (mapv #(bit-and 0xff %) [(aget ^bytes packed 0) (aget ^bytes packed 1)
                                                 (aget ^bytes packed 255) (aget ^bytes packed 256)])))
    (is (< ms 1000) (str "took " ms " ms"))))

(defn a-64-kb-blob-encodes-in-seconds-not-minutes
  ;; 35 s before the fix, about 1 s after: the test rule (a few seconds) holds
  ;; on a loaded machine only because the work is linear now.
  [env]
  (let [st (store/mem-store)
        h (v/hash (v/blob st (bytes-of 64000)))
        t0 (now-ms)
        {:keys [items oversized]} (items/encode-value st h)
        ms (- (now-ms) t0)
        rx (items/finish! (items/accept-items! nil (bin/decode-item-stream
                                                    (frame/concat-bytes items))))]
    (is (< ms 4000) (str "encode took " ms " ms"))
    (is (empty? oversized))
    (is (some? (store/await (store/s-get (:store rx) h))) "the receiver verified and holds it")))

(defn an-inner-node-literal-that-does-not-round-trip-falls-back-to-its-node-item
  ;; The bound lets a literal through on size. An inner HAMT or finger-tree
  ;; node may still have a literal that does not rebuild its own hash (it is
  ;; not the value its entries make). Then the sender sends the node item, as
  ;; before, and the stream still verifies. This is true of finger-tree nodes.
  ;; Every HAMT node is canonical for its keys (issue #12), so a HAMT node's
  ;; literal always rebuilds its hash: the map shapes have no such node.
  [env]
  (let [cap (frame/max-literal-item)
        inner-type? #(re-find #"^(hamt|ft)/" (str (first %)))
        round-trips? (fn [st x] (try (literal-round-trips? st x) (catch #?(:clj Throwable :cljs :default) _ false)))
        fallbacks (fn [shape]
                    (let [st (store/mem-store)
                          h (build st shape)
                          cache (atom {})]
                      {:st st :root h
                       :nodes (vec (for [x (reachable-hashes st h)
                                         :let [e (store/await (store/s-get st x))]
                                         :when (and (inner-type? e)
                                                    (not= (vec x) (vec h))
                                                    (some-> (bound/literal-item-bound st x cache)
                                                            (<= cap))
                                                    (not (round-trips? st x)))]
                                     x))}))]
    (doseq [shape [[:map [13 0]] [:nested 2] [:map #?(:clj [150 40] :cljs (:inner-map nbb-cases))]]
            :let [{:keys [st root nodes]} (fallbacks shape)]]
      (testing (pr-str shape)
        (if (= :map (first shape))
          (is (empty? nodes) "a HAMT node's literal always rebuilds its hash")
          (is (seq nodes) "the shape has an inner node whose literal does not round-trip"))
        (doseq [x nodes
                :let [item (pack/encode-item st x (store/await (store/s-get st x))
                                             {:max-literal cap :cache (atom {})})]]
          (is (= :node (:encoding item)) "the node item, not the literal"))
        (let [{:keys [items]} (items/encode-value st root)
              rx (items/finish! (items/accept-items! nil (bin/decode-item-stream
                                                          (frame/concat-bytes items))))]
          (is (some? (store/await (store/s-get (:store rx) root))) "and the receiver verifies the value"))))))

(defn a-node-whose-stored-size-is-over-the-cap-is-not-walked-for-a-bound
  [env]
  (let [st (store/mem-store)
        big (v/hash (v/string st (text 6000 7)))
        small (v/hash (v/string st (text 600 7)))
        entry #(store/await (store/s-get st %))
        walked (atom 0)
        real pack/literal-bound]
    (with-redefs [pack/literal-bound (fn [& args] (swap! walked inc) (apply real args))]
      (is (= :node (:encoding (pack/encode-item st big (entry big) {:max-literal 4000 :cache (atom {})}))))
      (is (zero? @walked) "the stored size-bytes is over the cap: no walk")
      (is (= :literal (:encoding (pack/encode-item st small (entry small) {:max-literal 4000 :cache (atom {})}))))
      (is (= 1 @walked) "under the cap the bound is walked")
      (testing "no cap: the size cue and the budget decide, no bound"
        (reset! walked 0)
        (pack/encode-item st small (entry small) {:budget 4096})
        (is (zero? @walked))))))

(defn a-string-over-the-cap-is-not-built-to-learn-it
  [env]
  (let [st (store/mem-store)
        big (v/string st (text 30000 3))
        small (v/string st "a")
        vec-of (v/vector st big small)
        big-key (vec (v/hash big))
        built (atom [])
        real pack/literal-of]
    (with-redefs [pack/literal-of (fn [s' h' & more]
                                    (swap! built conj (vec h'))
                                    (apply real s' h' more))]
      (let [r (items/encode-value st (v/hash vec-of))]
        (is (seq (:items r)))
        (is (not (some #{big-key} @built))
            "neither the string, nor (through the vector) a form of it")))))

(def checks
  {"string-and-blob-payload-counts-their-length-plus-framing" string-and-blob-payload-counts-their-length-plus-framing
   "runs-of-strings-and-blobs-chunk-within-the-budget" runs-of-strings-and-blobs-chunk-within-the-budget
   "a-small-blob-in-a-vector-has-a-literal-that-round-trips" a-small-blob-in-a-vector-has-a-literal-that-round-trips
   "max-literal-caps-the-item-and-defaults-to-one-payload" max-literal-caps-the-item-and-defaults-to-one-payload
   "hello-limits-max-frame-accepts-the-webrtc-clamp" hello-limits-max-frame-accepts-the-webrtc-clamp
   "the-smallest-frame-holds-the-largest-node-item" the-smallest-frame-holds-the-largest-node-item
   "the-size-model-is-never-below-the-wire-size" the-size-model-is-never-below-the-wire-size
   "the-node-item-bound-has-room-over-the-page-budget" the-node-item-bound-has-room-over-the-page-budget
   "the-bound-counts-no-compression-and-never-less-than-a-run-costs" the-bound-counts-no-compression-and-never-less-than-a-run-costs
   "the-bound-summaries-combine-as-a-monoid" the-bound-summaries-combine-as-a-monoid
   "utf8-len-adds-over-runs-cut-at-code-points" utf8-len-adds-over-runs-cut-at-code-points
   "the-literal-cache-is-per-hash-per-cap" the-literal-cache-is-per-hash-per-cap
   "the-default-chunk-budget-is-at-least-a-leaf-page" the-default-chunk-budget-is-at-least-a-leaf-page
   "the-first-get-of-a-long-string-carries-its-first-characters" the-first-get-of-a-long-string-carries-its-first-characters
   "append-item-matches-its-definition" append-item-matches-its-definition
   "utf8-len-is-the-length-of-the-utf8-bytes" utf8-len-is-the-length-of-the-utf8-bytes
   "payload-after-append-is-the-payload-of-the-appended-body" payload-after-append-is-the-payload-of-the-appended-body
   "a-literal-is-built-once-per-hash-per-cache" a-literal-is-built-once-per-hash-per-cache
   "pack-u8-values-is-linear" pack-u8-values-is-linear
   "a-64-kb-blob-encodes-in-seconds-not-minutes" a-64-kb-blob-encodes-in-seconds-not-minutes
   "an-inner-node-literal-that-does-not-round-trip-falls-back-to-its-node-item" an-inner-node-literal-that-does-not-round-trip-falls-back-to-its-node-item
   "a-node-whose-stored-size-is-over-the-cap-is-not-walked-for-a-bound" a-node-whose-stored-size-is-over-the-cap-is-not-walked-for-a-bound
   "a-string-over-the-cap-is-not-built-to-learn-it" a-string-over-the-cap-is-not-built-to-learn-it})
