(ns dacite.peer.literal-limits-test
  "M1.1a: page and literal size limits, payload sizing of strings and blobs,
   blob literals in vectors (Fixes #8), in-page reads, the literal cache and
   the node-item cap. Spec: docs/spec/peer-v1.md 3.2."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dacite.peer.frame :as frame]
            [dacite.peer.items :as items]
            [dacite.peer.msg :as msg]
            [dacite.reveal :as reveal]
            [dacite.service.reveal :as sr]
            [dacite.store :as store]
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
  (let [five lit/string-wire-overhead]
    (is (= 5 five))
    (is (= (+ five 3) (lit/item-payload-bytes {:type "string" :body "abc"})))
    (is (= (+ five 4) (lit/item-payload-bytes {:type "blob" :body (bytes-of 4)})))
    (testing "a run counts every value (it counted 0)"
      (is (= (+ (+ five 1) (+ five 2) (+ five 3))
             (lit/item-payload-bytes
              {:type "run" :body {:of "string" :values ["a" "bb" "ccc"]}})))
      (is (= (+ (+ five 2) (+ five 5))
             (lit/item-payload-bytes
              {:type "run" :body {:of "blob" :values [(bytes-of 2) (bytes-of 5)]}}))))
    (testing "a repeat counts n copies (it counted 0)"
      (is (= (* 10 (+ five 4))
             (lit/item-payload-bytes
              {:type "repeat" :body {:of "string" :n 10 :value "abcd"}})))
      (is (= (* 3 (+ five 6))
             (lit/item-payload-bytes
              {:type "repeat" :body {:of "blob" :n 3 :value (bytes-of 6)}}))))
    (testing "an element is never free, so a page of nulls ends"
      (is (pos? (lit/item-payload-bytes {:type "null" :body nil})))
      (is (pos? (lit/item-payload-bytes {:type "negative" :body nil})))
      (is (= 100 (lit/item-payload-bytes
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

(def ^:private gen-shape
  (gen/one-of
   [(gen/fmap (fn [t] [:string t]) gen-text)
    (gen/fmap (fn [n] [:blob n]) (gen/choose 0 9000))
    (gen/fmap (fn [ns] [:strings ns]) (gen/vector (gen/choose 0 300) 0 120))
    (gen/fmap (fn [n] [:ints n]) (gen/choose 0 3000))
    (gen/fmap (fn [ns] [:blobs ns]) (gen/vector (gen/choose 0 200) 1 40))
    (gen/fmap (fn [[n sz]] [:map [n sz]]) (gen/tuple (gen/choose 1 150) (gen/choose 0 600)))
    (gen/fmap (fn [n] [:mixed n]) (gen/choose 1 400))]))

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
                               (range x))))))

(defn- check-shape
  "Nil when `shape` is fine, else a map saying what failed."
  [shape mf]
  (let [st (store/mem-store)
        h (build st shape)
        cap (frame/max-literal-item mf)
        {:keys [items encs oversized]} (items/encode-value st h {:max-frame-bytes mf})
        sizes (map #(bin/byte-len %) items)
        rx (try (items/finish! (items/accept-items! nil (bin/decode-item-stream
                                                          (frame/concat-bytes items))))
                (catch Throwable e e))]
    (cond
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

(deftest property-pages-and-literals-stay-in-their-caps-and-round-trip
  (doseq [mf [frame/min-max-frame frame/max-frame]]
    (let [r (tc/quick-check
             12
             (prop/for-all [shape gen-shape]
               (nil? (check-shape shape mf))))]
      (is (:pass? r) (str "max-frame " mf " failed on " (pr-str (:shrunk r)))))))
