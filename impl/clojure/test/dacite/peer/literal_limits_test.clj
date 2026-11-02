(ns dacite.peer.literal-limits-test
  "M1.1a: page and literal size limits, payload sizing of strings and blobs,
   blob literals in vectors (Fixes #8), in-page reads, the literal cache and
   the node-item cap. Spec: docs/spec/peer-v1.md 3.2."
  (:require [clojure.test :refer [deftest is testing]]
            [dacite.host :as host]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dacite.peer.examples]
            [dacite.peer.frame :as frame]
            [dacite.peer.items :as items]
            [dacite.peer.msg :as msg]
            [dacite.reveal :as reveal]
            [dacite.service.reveal :as sr]
            [dacite.store :as store]
            [dacite.rooted.gc :as gc]
            [dacite.store.bound :as bound]
            [dacite.store.pack :as pack]
            [dacite.value :as v]
            [dacite.value.lit :as lit]
            [dacite.value.types :as types]
            [dacite.wire.binary :as bin]))

(defn- bytes-of [n] (byte-array (map #(unchecked-byte (* 7 %)) (range n))))

;; ---------------------------------------------------------------------------
;; Payload sizes: strings and blobs in runs and repeats
;; ---------------------------------------------------------------------------

(deftest string-and-blob-payload-counts-their-length-plus-framing
  (let [five lit/string-wire-overhead
        hdr lit/run-header-bytes]
    (is (= 5 five))
    (is (= 6 hdr))
    (is (= (+ five 3) (lit/item-payload-bytes {:type "string" :body "abc"})))
    (is (= (+ five 4) (lit/item-payload-bytes {:type "blob" :body (bytes-of 4)})))
    (testing "a run counts every value (it counted 0), and its header"
      (is (= (+ hdr (+ five 1) (+ five 2) (+ five 3))
             (lit/item-payload-bytes
              {:type "run" :body {:of "string" :values ["a" "bb" "ccc"]}})))
      (is (= (+ hdr (+ five 2) (+ five 5))
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

(deftest runs-of-strings-and-blobs-chunk-within-the-budget
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

(deftest the-leaf-page-budget-is-4-kib-and-bounds-every-page
  (is (= 4096 lit/page-budget))
  (testing "a string of 20 000 characters: every node item near the budget"
    (let [st (store/mem-store)
          h (v/hash (v/string st (apply str (repeat 20000 \a))))
          order (reveal/page-order st h)
          sizes (map #(bin/byte-len (sr/encode-page st {:hash %})) order)]
      (is (< 3 (count order)))
      (is (<= (apply max sizes) frame/max-node-item-bytes))
      (is (< 4096 (apply max sizes)) "and a full page is about 4 KiB, not 1")))
  (testing "a vector of 3000 short strings"
    (let [st (store/mem-store)
          h (v/hash (apply v/vector st (map #(v/string st (str "s" %)) (range 3000))))
          sizes (map #(bin/byte-len (sr/encode-page st {:hash %})) (reveal/page-order st h))]
      (is (<= (apply max sizes) frame/max-node-item-bytes)))))

;; ---------------------------------------------------------------------------
;; Fixes #8: a blob inside a literal page body
;; ---------------------------------------------------------------------------

(defn- literal-round-trips?
  [st h]
  (let [form (pack/literal-of st h)
        st2 (store/mem-store)
        got (pack/materialize-literal! st2 (:type form) (:body form))]
    (= (vec h) (vec got))))

(deftest a-blob-in-a-vector-has-a-literal-that-round-trips
  (let [st (store/mem-store)
        b1 (v/blob st (byte-array [1 2 3]))
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
            h (v/hash (v/vector st (byte-array 1) nil))]
        (is (some? (items/literal-bytes st h frame/max-payload)))))
    (testing "a long run of blobs reveals and verifies"
      (let [st (store/mem-store)
            h (v/hash (apply v/vector st (map #(v/blob st (bytes-of (+ 1 (mod % 40)))) (range 700))))
            {:keys [items]} (items/encode-value st h)
            rx (items/finish! (items/accept-items! nil (bin/decode-item-stream
                                                        (frame/concat-bytes items))))]
        (is (some? (store/await (store/s-get (:store rx) h))))))))

;; ---------------------------------------------------------------------------
;; ft-nth reads inside the page
;; ---------------------------------------------------------------------------

(deftest nth-reads-inside-the-page-without-interning-its-elements
  (let [st (store/mem-store)
        text (apply str (map #(char (+ 97 (mod % 26))) (range 9000)))
        s (v/string st text)
        vec-of (apply v/vector st (map #(v/i64 st %) (range 3000)))
        blob (v/blob st (bytes-of 9000))
        strs (apply v/vector st (map #(v/string st (str "item " %)) (range 800)))]
    (testing "same elements as the sequence, at the page edges and between"
      (doseq [i [0 1 4095 4096 4097 8999]]
        (is (= (nth text i) (v/realize (v/nth s i))) (str "string " i)))
      (doseq [i [0 1 511 512 513 1500 2999]]
        (is (= i (v/realize (v/nth vec-of i))) (str "vector " i)))
      (doseq [i [0 4095 4096 8999]]
        (is (= (aget ^bytes (bytes-of 9000) i) (unchecked-byte (v/realize (v/nth blob i))))
            (str "blob " i)))
      (doseq [i [0 17 400 799]]
        (is (= (str "item " i) (apply str (v/realize (v/nth strs i)))) (str "strings " i))))
    (testing "no element of the page is interned to answer"
      (let [interned (atom 0)
            real lit/intern-lit-item!]
        (with-redefs [lit/intern-lit-item! (fn [& args] (swap! interned inc) (apply real args))]
          (v/realize (v/nth s 5000))
          (v/realize (v/nth vec-of 2000))
          (is (<= @interned 2) "one element a call, not the page"))))))

;; ---------------------------------------------------------------------------
;; The literal cap and the literal cache
;; ---------------------------------------------------------------------------

(deftest max-literal-caps-the-item-and-defaults-to-one-payload
  (is (= frame/max-payload pack/default-max-literal))
  (let [st (store/mem-store)
        h (v/hash (v/string st (apply str (repeat 3000 \x))))
        entry (store/await (store/s-get st h))]
    (testing "room: a literal"
      (is (= :literal (:encoding (pack/encode-item st h entry {:max-literal 4000})))))
    (testing "no room: the node entry"
      (is (= :node (:encoding (pack/encode-item st h entry {:max-literal 2000})))))))

(deftest a-literal-is-built-once-per-hash-per-cache
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

(deftest node-items-over-a-payload-are-reported-by-default-and-throw-on-request
  (let [st (store/mem-store)
        h (v/hash (v/string st (apply str (repeat 3000 \x))))]
    (testing "a normal frame: no node item is over the cap"
      (is (empty? (:oversized (items/encode-value st h)))))
    (testing "a tiny frame: the node items are over it; reported, still sent"
      (let [r (items/encode-value st h {:max-frame-bytes 120})]
        (is (seq (:oversized r)))
        (is (= (count (:items r)) (count (:hashes r))))
        (is (every? #(> (:len %) (frame/max-literal-item 120)) (:oversized r)))))
    (testing "with the option it throws"
      (is (= :node-too-large
             (try (items/encode-value st h {:max-frame-bytes 120 :strict-node-cap? true})
                  :no-throw
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))))

(deftest hello-limits-max-frame-starts-at-8192
  (let [hello (fn [n] {:type :hello :stream 0
                       :value (assoc-in (:value (first dacite.peer.examples/examples))
                                        ["limits" "max-frame"] n)})]
    (is (= :invalid-message (reason-of #(msg/encode-message (hello 8191)))) "one under the smallest")
    (is (bytes? (msg/encode-message (hello 8192))))
    (is (bytes? (msg/encode-message (hello 16384))))
    (is (= :invalid-message (reason-of #(msg/encode-message (hello 16385)))))))

(deftest the-smallest-frame-holds-the-largest-node-item
  (is (= 8192 frame/min-max-frame))
  (is (frame/min-max-frame-fits-node?))
  (is (<= frame/max-node-item-bytes (frame/max-literal-item frame/min-max-frame))))

;; ---------------------------------------------------------------------------
;; The leaf budget does not change a value hash
;; ---------------------------------------------------------------------------

(defn- text [n seed]
  (let [r (java.util.Random. seed)]
    (apply str (repeatedly n #(char (+ 97 (.nextInt r 26)))))))

(deftest value-hashes-are-the-same-as-before-the-4-kib-leaf-budget
  ;; Expected hashes were computed on main at the 1 KiB budget, before M1.1a.
  ;; A page budget changes how elements are grouped into pages, not the
  ;; elements-fuse a value hashes, so these must not move.
  (let [st (store/mem-store)
        h (fn [x] (vec (v/hash x)))]
    (is (= [556921443261991940 321845223768230048 -6227015001349326196 7230859705705658041] (h (v/string st (text 100000 1)))))
    (is (= [-7746543829445278298 -7686170452263083441 -946355418787483763 7271117540972285520]
           (h (v/blob st (let [b (byte-array 300000)] (.nextBytes (java.util.Random. 2) b) b)))))
    (is (= [-8042333575886514603 7864943929036002206 -7580366598134099796 4983339238124604683] (h (apply v/vector st (map #(v/i64 st %) (range 4000))))))
    (is (= [-149883838514163401 -8000609268654991310 2446280707169379829 1595599979461944117]
           (h (apply v/vector st (map #(v/string st (text 12 (+ 10 %))) (range 2000))))))
    (is (= [2179089288235816445 522586264184947832 -4172388328751776686 8793168437925131185]
           (h (apply v/vector st (map #(v/i64 st (* 1000003 %)) (range 8000))))))
    (is (= [9205250687594850018 -2089264336835397224 -3659497600113136698 -4381344283639506741]
           (h (apply v/vector st (map (fn [i] (case (mod i 3) 0 (v/null st) 1 (v/string st (str i)) 2 (v/i64 st i)))
                                      (range 3000))))))
    (is (= [-3202565244896424341 -853792474632279232 2629703230003370940 945274105269131032]
           (h (apply v/map st (mapcat (fn [i] [(v/i64 st i) (v/i64 st (* 7 i))]) (range 1000))))))))

;; ---------------------------------------------------------------------------
;; Property: every page and literal is within its cap and round-trips
;; ---------------------------------------------------------------------------

(deftest append-item-matches-its-definition
  ;; The fast append rewrites only the last group; it must give what
  ;; expand + conj + rle-lits gives, for any run of elements.
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
        r (tc/quick-check
           200
           (prop/for-all [els (gen/vector gen-el 0 40)]
             (let [fast (reduce lit/append-item [] els)
                   ref (reduce slow [] els)]
               (= (norm fast) (norm ref)))))]
    (is (:pass? r) (pr-str (:shrunk r)))))

(def ^:private gen-text
  (gen/fmap (fn [[ch n]] (apply str (repeat n ch)))
            (gen/tuple (gen/elements [\a \z \u00e9 \u03bb \u20ac]) (gen/choose 0 9000))))

(def ^:private one-byte-kinds
  "Kinds that are one byte (or two) of body: the ones whose page size was
   under-counted (M1.1a review S1)."
  [:u8 :char :bool :null :greek :neg])

(defn- one-byte-el [st kind i]
  (case kind
    :u8 (v/u8 st (mod i 256))
    :char (v/char st (char (+ 97 (mod i 26))))
    :greek (v/char st (char (+ 0x3b1 (mod i 20))))
    :bool (v/bool st (even? (quot i 2)))
    :null (v/null st)
    :neg (v/i64 st (- (inc i)))))

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

(defn- build [st [kind x]]
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
  "Samples per frame size (it was 12): about 2.5 s of the suite for both."
  30)

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
  (let [wire #(bin/byte-len (bin/encode-lit-bytes %))
        h32 (vec (repeat 32 0))
        exact [{:type "u8" :body 7} {:type "bool" :body true} {:type "i64" :body 5}
               {:type "f64" :body 1.5} {:type "ref" :body h32}
               {:type "char" :body \a} {:type "char" :body \u03bb}
               {:type "run" :body {:of "char" :values "ab\u03bb"}}
               {:type "run" :body {:of "u8" :values (byte-array 5)}}
               {:type "run" :body {:of "i64" :values [1 2 3]}}
               {:type "run" :body {:of "bool" :values [true false]}}
               {:type "string" :body "abc"} {:type "blob" :body (byte-array 3)}]
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
      (is (<= (wire {:type "run" :body {:of "u8" :values (byte-array 2)}})
              (lit/item-payload-bytes {:type "run" :body {:of "u8" :values (byte-array 2)}}))))))

(deftest alternating-one-byte-kinds-stay-in-a-minimum-frame
  ;; The review's cases: 6 000 elements of two kinds, one at a time. A node
  ;; item was 8 261 bytes for u8/char, over the minimum frame's cap of 8 149.
  (doseq [[label kinds len] [["u8/char" [:u8 :char] 1] ["char/bool" [:char :bool] 1]
                             ["u8/greek" [:u8 :greek] 1] ["bool/greek" [:bool :greek] 1]
                             ["char/null" [:char :null] 1] ["u8/bool x2" [:u8 :bool] 2]]]
    (let [st (store/mem-store)
          h (build st [:one-byte [kinds len 3000]])
          sizes (map #(bin/byte-len (sr/encode-page st {:hash %})) (reveal/page-order st h))]
      (is (<= (apply max sizes) frame/max-node-item-bytes) label)
      (is (empty? (:oversized (items/encode-value
                               st h {:max-frame-bytes frame/min-max-frame})))
          label))))

(deftest the-node-item-bound-has-room-over-the-page-budget
  (is (<= (+ lit/page-budget 400) frame/max-node-item-bytes)
      "a full page plus its measure and the page's own fields (the worst seen is about 4.2 KB)"))

;; ---------------------------------------------------------------------------
;; M1: a blob over 16 KB is not quadratic to encode
;; ---------------------------------------------------------------------------

(deftest pack-u8-values-is-linear
  ;; It indexed a lazy seq with `nth` (O(n) each): 100 000 bytes took 110 s.
  (let [lits (map (fn [i] {:type "u8" :body (mod i 256)}) (range 100000))
        t0 (System/nanoTime)
        packed (@#'lit/pack-u8-values lits)
        ms (/ (- (System/nanoTime) t0) 1e6)]
    (is (= 100000 (alength ^bytes packed)))
    (is (= [0 1 255 0] (mapv #(bit-and 0xff %) [(aget ^bytes packed 0) (aget ^bytes packed 1)
                                                 (aget ^bytes packed 255) (aget ^bytes packed 256)])))
    (is (< ms 1000) (str "took " ms " ms"))))

(deftest a-64-kb-blob-encodes-in-seconds-not-minutes
  ;; 35 s before the fix, about 1 s after: the test rule (a few seconds) holds
  ;; on a loaded machine only because the work is linear now.
  (let [st (store/mem-store)
        h (v/hash (v/blob st (bytes-of 64000)))
        t0 (System/nanoTime)
        {:keys [items oversized]} (items/encode-value st h)
        ms (/ (- (System/nanoTime) t0) 1e6)
        rx (items/finish! (items/accept-items! nil (bin/decode-item-stream
                                                    (frame/concat-bytes items))))]
    (is (< ms 4000) (str "encode took " ms " ms"))
    (is (empty? oversized))
    (is (some? (store/await (store/s-get (:store rx) h))) "the receiver verified and holds it")))

;; ---------------------------------------------------------------------------
;; S2: utf8-len, and building a page one element at a time
;; ---------------------------------------------------------------------------

(deftest utf8-len-is-the-length-of-the-utf8-bytes
  (let [host-len #(count (host/utf8-bytes %))
        hi "\uD83D" lo "\uDE00"]
    (doseq [s ["" "a" "abc" "\u00e9" "\u03bb\u03bb" "\u20ac" "x\u20acy" (str hi lo) (str "a" hi lo "b")
               ;; lone surrogates: the host encoder's rule, not ours
               hi lo (str hi "a") (str "a" lo) (str lo hi) (str hi hi lo) (str "z" hi) (str lo lo)]]
      (is (= (host-len s) (lit/utf8-len s)) (pr-str s)))
    (testing "a character (it is called with one)"
      (is (= 2 (lit/utf8-len \u03bb)))
      (is (= 1 (lit/utf8-len \a))))
    (let [gen-unit (gen/one-of [(gen/choose 0 0x7f) (gen/choose 0x80 0x7ff) (gen/choose 0x800 0xffff)
                                (gen/choose 0xd800 0xdfff)])
          r (tc/quick-check
             500
             (prop/for-all [units (gen/vector gen-unit 0 40)]
               (let [s (apply str (map char units))]
                 (= (host-len s) (lit/utf8-len s)))))]
      (is (:pass? r) (pr-str (:shrunk r))))))

(deftest payload-after-append-is-the-payload-of-the-appended-body
  (let [gen-el (gen/one-of
                [(gen/fmap (fn [c] {:type "char" :body c}) (gen/elements [\a \b \u03bb]))
                 (gen/fmap (fn [n] {:type "u8" :body n}) (gen/elements [0 1 255]))
                 (gen/fmap (fn [n] {:type "i64" :body n}) (gen/elements [1 2]))
                 (gen/fmap (fn [x] {:type "string" :body x}) (gen/elements ["x" "yy"]))
                 (gen/return {:type "null" :body nil})
                 (gen/fmap (fn [h] {:type "ref" :body h}) (gen/elements [[1 2 3 4] [5 6 7 8]]))])
        r (tc/quick-check
           300
           (prop/for-all [els (gen/vector gen-el 0 60)]
             (loop [body [] pb 0 els els]
               (if-let [el (first els)]
                 (let [b' (lit/append-item body el)
                       pb' (lit/payload-after-append body pb b')]
                   (and (= pb' (lit/payload-bytes b'))
                        (recur b' pb' (rest els))))
                 true))))]
    (is (:pass? r) (pr-str (:shrunk r)))))

(deftest a-page-is-not-recounted-for-every-element
  (let [real lit/payload-bytes
        calls (atom 0)
        st (store/mem-store)]
    (with-redefs [lit/payload-bytes (fn [b] (swap! calls inc) (real b))]
      (let [h (v/hash (reduce (fn [c i] (v/conj c (v/char st (char (+ 97 (mod i 26))))))
                              (v/vector st) (range 3000)))]
        (is (some? h))
        (is (< @calls 100) (str @calls " recounts for 3000 appends"))))))

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

(defn- reachable-hashes
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
  ;; The bound lets a literal through on size. An inner HAMT or finger-tree
  ;; node may still have a literal that does not rebuild its own hash (it is
  ;; not the value its entries make). Then the sender sends the node item, as
  ;; before, and the stream still verifies.
  (let [cap (frame/max-literal-item)
        inner-type? #(re-find #"^(hamt|ft)/" (str (first %)))
        round-trips? (fn [st x] (try (literal-round-trips? st x) (catch Throwable _ false)))
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
    (doseq [shape [[:map [13 0]] [:nested 2] [:map [150 40]]]
            :let [{:keys [st root nodes]} (fallbacks shape)]]
      (testing (pr-str shape)
        (is (seq nodes) "the shape has an inner node whose literal does not round-trip")
        (doseq [x nodes
                :let [item (pack/encode-item st x (store/await (store/s-get st x))
                                             {:max-literal cap :cache (atom {})})]]
          (is (= :node (:encoding item)) "the node item, not the literal"))
        (let [{:keys [items]} (items/encode-value st root)
              rx (items/finish! (items/accept-items! nil (bin/decode-item-stream
                                                          (frame/concat-bytes items))))]
          (is (some? (store/await (store/s-get (:store rx) root))) "and the receiver verifies the value"))))))

(deftest the-bound-counts-no-compression-and-never-less-than-a-run-costs
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

(deftest the-bound-summaries-combine-as-a-monoid
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

(deftest utf8-len-upper-is-never-below-the-text-it-joins
  (is (= 0 (lit/utf8-len-upper "")))
  (is (= 4 (lit/utf8-len-upper "ab\u00e9")))
  (is (= 4 (lit/utf8-len-upper "\uD83D\uDE00")))
  (testing "a lone surrogate counts 2: two halves of a pair, in two runs, are 4 together"
    (let [hi "\uD83D" lo "\uDE00"]
      (is (>= (+ (lit/utf8-len-upper hi) (lit/utf8-len-upper lo))
              (lit/utf8-len (str hi lo)))))))

(deftest a-string-over-the-cap-is-not-built-to-learn-it
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

(deftest the-literal-cache-is-per-hash-per-cap
  (let [st (store/mem-store)
        h (v/hash (v/string st (apply str (repeat 3000 \x))))
        entry (store/await (store/s-get st h))
        cache (atom {})]
    (is (= :literal (:encoding (pack/encode-item st h entry {:max-literal 4000 :cache cache}))))
    (is (= :node (:encoding (pack/encode-item st h entry {:max-literal 2000 :cache cache})))
        "the answer for another cap is not the cached one")
    (is (= :literal (:encoding (pack/encode-item st h entry {:max-literal 4000 :cache cache})))
        "and the first is still cached")))
