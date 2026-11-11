(ns dacite.peer.literal-limits-checks
  "Page and literal size limits on the JVM and on nbb: payload sizing of
   strings and blobs, the literal cap and its cache, the hello frame
   limits, the smallest frame, the size model, the node bound
   (store.bound) and the first get of a long string. Spec:
   docs/spec/peer-v1.md 3.2. The JVM runs them from
   `dacite.peer.literal-limits-test`, one deftest each under its old name;
   nbb runs them from `dacite.peer.items-cljs-test` (`npm run items:test`).

   `is` and `testing` are `dacite.store.check`. The checks take no host
   pieces (`env` is unused)."
  (:require [dacite.host :as host]
            [dacite.peer.examples :as ex]
            [dacite.peer.frame :as frame]
            [dacite.peer.msg :as msg]
            [dacite.store :as store]
            [dacite.store.bound :as bound]
            [dacite.store.check :refer [is testing]]
            [dacite.store.pack :as pack]
            [dacite.value :as v]
            [dacite.value.finger-tree :as ft]
            [dacite.value.lit :as lit]
            #?(:cljs [dacite.value.seeded-gen :as gen])
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

(def checks
  {"string-and-blob-payload-counts-their-length-plus-framing" string-and-blob-payload-counts-their-length-plus-framing
   "runs-of-strings-and-blobs-chunk-within-the-budget" runs-of-strings-and-blobs-chunk-within-the-budget
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
   "the-first-get-of-a-long-string-carries-its-first-characters" the-first-get-of-a-long-string-carries-its-first-characters})
