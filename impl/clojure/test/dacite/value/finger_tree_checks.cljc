(ns dacite.value.finger-tree-checks
  "Store-backed finger trees with implicit leaf singles (bare value hashes
   as 1-elem roots and digit children), on the JVM and on nbb.

   These were the bodies of `dacite.value.finger-tree-test`. The JVM runs
   them from that namespace, one deftest each under its old name. nbb runs
   them from `dacite.value-cljs-test` (`npm run value:test`).

   `is` and `testing` are `dacite.store.check`. A set of hashes is keyed by
   `store/mkey`: a hash on nbb holds BigInts, which SCI cannot hash."
  (:require [clojure.string :as str]
            [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.check :refer [is testing throws?]]
            [dacite.value :as v]
            [dacite.value.finger-tree :as ft]
            [dacite.value.lit :as lit]
            [dacite.value.types :as types]))

(defn- host-bytes
  "Host bytes for the ints `xs` (0..255)."
  [xs]
  #?(:clj (byte-array (map unchecked-byte xs)) :cljs (js/Uint8Array.from (clj->js xs))))

(defn- host-bytes?
  [x]
  #?(:clj (bytes? x) :cljs (instance? js/Uint8Array x)))

(defn- byte-count
  [bs]
  #?(:clj (alength ^bytes bs) :cljs (.-length bs)))

(defn- byte-at
  "Unsigned byte `i` of `bs`."
  [bs i]
  #?(:clj (bit-and 0xff (aget ^bytes bs i)) :cljs (aget bs i)))

(defn- byte-set!
  [bs i b]
  #?(:clj (aset ^bytes bs i (unchecked-byte b)) :cljs (aset bs i b)))

(defn- random-bytes
  "`n` pseudo-random bytes, the same each run. The JVM uses
   java.util.Random seed 3, as before. nbb uses a small LCG seeded with 3."
  [n]
  #?(:clj (let [b (byte-array n)] (.nextBytes (java.util.Random. 3) b) b)
     :cljs (let [b (js/Uint8Array. n)]
             (loop [i 0 x 3]
               (when (< i n)
                 (let [x' (js/Math.imul x 1103515245)
                       x' (bit-and (+ x' 12345) 0x7fffffff)]
                   (aset b i (bit-and (bit-shift-right x' 16) 0xff))
                   (recur (inc i) x'))))
             b)))

(defn- nodes-under
  "Pairs of [hash node] reachable from `roots`. One `s-get` per live hash."
  [st & roots]
  (mapv (fn [hk]
          (let [h (gc/->hash hk)]
            [h (store/await (store/s-get st h))]))
        (reduce (fn [s root]
                  (into s (gc/mark-reachable st root)))
                #{}
                roots)))

(defn- put-i64
  "Store an i64 and return its value hash."
  [st n]
  (v/hash (v/i64 st n)))

(defn- measure-combine [m1 m2]
  {:count (+ (:count m1) (:count m2))
   :size-bytes (+ (:size-bytes m1) (:size-bytes m2))
   :elements-fuse (hash/fuse (:elements-fuse m1) (:elements-fuse m2))})

(defn- leaf-measure [h size-bytes]
  {:count 1 :size-bytes size-bytes :elements-fuse (hash/ref->state h)})

(defn- put-digit-of-leaves!
  "Plant an ft/digit whose children are bare value hashes."
  [st leaf-hs]
  (let [vhs (vec leaf-hs)
        ms (mapv (fn [h]
                   (leaf-measure h (types/dacite-size (store/await (store/s-get st h)))))
                 vhs)
        m (reduce measure-combine
                  {:count 0 :size-bytes 0 :elements-fuse hash/identity-state}
                  ms)
        dh (types/node-hash "ft/digit" (:elements-fuse m))]
    (store/await (store/s-put st dh ["ft/digit" {:children vhs :measure m}]))
    dh))

(defn empty-tree
  [_]
  (let [st (store/mem-store)
        root (ft/ft-empty st)]
    (is (ft/ft-empty? st root))
    (is (zero? (ft/ft-count st root)))
    (is (nil? (ft/ft-first st root)))
    (is (empty? (ft/ft-seq st root)))))

(defn conj-bare-leaves-no-singles
  [_]
  (let [st (store/mem-store)
        a (put-i64 st 1)
        b (put-i64 st 2)
        r0 (ft/ft-empty st)
        r1 (ft/ft-conj-right st r0 a)
        r2 (ft/ft-conj-right st r1 b)
        snap (nodes-under st r0 r1 r2)
        singles (filter (fn [[_ e]] (= "ft/single" (types/entry-type e))) snap)]
    (is (= 1 (ft/ft-count st r1)))
    (is (= r1 a) "1-elem root is the bare value hash")
    (is (= 2 (ft/ft-count st r2)))
    (is (= [a] (vec (ft/ft-seq st r1))))
    (is (= [a b] (vec (ft/ft-seq st r2))))
    (is (= a (ft/ft-first st r2)))
    (is (= b (ft/ft-last st r2)))
    (is (= a (ft/ft-nth st r2 0)))
    (is (= b (ft/ft-nth st r2 1)))
    (is (empty? singles) "no ft/single entries")))

(defn vector-value-hash-stable-without-singles
  [_]
  ;; Collection value hash depends only on leaf elements_fuse, not spine adapters.
  (let [st (store/mem-store)
        vec-v (v/vector st 1 2 3)
        h (v/hash vec-v)
        leaves (mapv #(put-i64 st %) [1 2 3])
        root (ft/ft-from-value-hashes st leaves)
        h2 (types/value-hash "vector" (ft/ft-elements-fuse st root))]
    (is (= h h2))
    (is (empty? (filter (fn [[_ e]] (= "ft/single" (types/entry-type e)))
                        (nodes-under st h root))))))

(defn bare-leaf-root-ops
  [_]
  (testing "1-element tree root is a bare value hash"
    (let [st (store/mem-store)
          vh (put-i64 st 42)]
      (is (= 1 (ft/ft-count st vh)))
      (is (= 8 (ft/ft-size-bytes st vh)))
      (is (= (hash/ref->state vh) (ft/ft-elements-fuse st vh))
          "a bare leaf's elements-fuse is its ref as a fuse element")
      (is (= vh (ft/ft-first st vh)))
      (is (= vh (ft/ft-last st vh)))
      (is (= vh (ft/ft-nth st vh 0)))
      (is (= [vh] (vec (ft/ft-seq st vh))))
      (is (= [vh] (vec (ft/ft-leaves st vh))))
      (is (not (ft/ft-empty? st vh)))
      (let [empty (ft/ft-remove-nth st vh 0)]
        (is (ft/ft-empty? st empty)))
      (let [b (put-i64 st 99)
            deep (ft/ft-conj-right st vh b)]
        (is (= 2 (ft/ft-count st deep)))
        (is (= [vh b] (vec (ft/ft-seq st deep))))))))

(defn digit-of-bare-leaves
  [_]
  (let [st (store/mem-store)
        a (put-i64 st 10)
        b (put-i64 st 20)
        c (put-i64 st 30)
        dh (put-digit-of-leaves! st [a b c])]
    (is (= [a b c] (vec (ft/ft-leaves st dh))))
    (let [root (ft/ft-from-value-hashes st [a b c])]
      (is (= [a b c] (vec (ft/ft-seq st root))))
      (is (= a (ft/ft-nth st root 0)))
      (is (= c (ft/ft-nth st root 2))))))

(defn reject-structural-ft-as-leaf
  [_]
  (let [st (store/mem-store)
        empty (ft/ft-empty st)
        a (put-i64 st 1)]
    (is (throws? #"leaf value hash" #(ft/ft-conj-right st empty empty)))
    (is (throws? #"leaf value hash" #(ft/ft-conj-left st empty empty)))
    ;; nesting a public vector is fine
    (let [inner (v/hash (v/vector st 9))
          root (ft/ft-conj-right st empty inner)]
      (is (= 1 (ft/ft-count st root)))
      (is (= inner (ft/ft-first st root))))
    (is (= a a))))

(defn large-conj-and-nth
  [_]
  (let [st (store/mem-store)
        n 100
        vhs (mapv #(put-i64 st %) (range n))
        root (reduce (fn [r h] (ft/ft-conj-right st r h))
                     (ft/ft-empty st)
                     vhs)]
    (is (= n (ft/ft-count st root)))
    (is (= (first vhs) (ft/ft-first st root)))
    (is (= (peek vhs) (ft/ft-last st root)))
    (doseq [i (range 0 n 7)]
      (is (= (nth vhs i) (ft/ft-nth st root i))))
    (is (= vhs (vec (ft/ft-seq st root))))
    (is (empty? (filter (fn [[_ e]] (= "ft/single" (types/entry-type e)))
                        (nodes-under st root))))))

(defn long-string-seq-matches-count
  [_]
  ;; Overflowed digits mix leaves and nodes; seq must flatten to every char.
  (let [st (store/mem-store)
        s (v/string st (apply str (repeat 100 \x)))]
    (is (= 100 (v/count s)))
    (is (= 100 (count (v/seq s))))
    (is (= 100 (count (v/realize s))))
    (is (= (apply str (repeat 100 \x)) (v/native s)))))

(defn concat-and-rest
  [_]
  (let [st (store/mem-store)
        as (mapv #(put-i64 st %) [1 2 3])
        bs (mapv #(put-i64 st %) [4 5])
        ra (ft/ft-from-value-hashes st as)
        rb (ft/ft-from-value-hashes st bs)
        rc (ft/ft-concat st ra rb)]
    (is (= (into as bs) (vec (ft/ft-seq st rc))))
    (is (= (rest as) (vec (ft/ft-seq st (ft/ft-rest st ra)))))
    (is (= (pop (vec as)) (vec (ft/ft-seq st (ft/ft-butlast st ra)))))))

(defn remove-nth-middle
  [_]
  (let [st (store/mem-store)
        vhs (mapv #(put-i64 st %) (range 10))
        root (ft/ft-from-value-hashes st vhs)
        mid (ft/ft-remove-nth st root 4)]
    (is (= (concat (range 4) (range 5 10))
           (map (fn [h] (second (store/await (store/s-get st h)))) (ft/ft-seq st mid))))))

(defn entry-density-no-single-per-element
  [_]
  ;; n leaf elements must not produce n ft/single nodes.
  (let [st (store/mem-store)
        n 50
        vhs (mapv #(put-i64 st %) (range n))
        root (ft/ft-from-value-hashes st vhs)
        snap (nodes-under st root)
        by-type (frequencies (map (fn [[_ e]] (types/entry-type e)) snap))
        leaves (mapv (fn [h] (store/await (store/s-get st h))) vhs)]
    (is (nil? (get by-type "ft/single")))
    (is (= n (count leaves)))
    (is (every? #(= "i64" (types/entry-type %)) leaves))
    (is (pos? (get by-type "ft/deep" 0)))))

(defn- put-char [st ch]
  (v/hash (v/char st ch)))

(defn- put-digit-of-char-run!
  "Plant an ft/digit whose body is a char run/repeat (literal page)."
  [st s]
  (let [chs (vec (seq s))
        vhs (mapv #(put-char st %) chs)
        ms (mapv (fn [h]
                   (leaf-measure h (types/dacite-size (store/await (store/s-get st h)))))
                 vhs)
        m (reduce measure-combine
                  {:count 0 :size-bytes 0 :elements-fuse hash/identity-state}
                  ms)
        body (lit/rle-lits (mapv (fn [ch] {:type "char" :body ch}) chs))
        dh (types/node-hash "ft/digit" (:elements-fuse m))]
    (store/await (store/s-put st dh ["ft/digit" {:body body :measure m}]))
    dh))

(defn packed-char-run-digit-dual-read
  [_]
  (let [st (store/mem-store)
        s "abc"
        dh (put-digit-of-char-run! st s)
        vhs (mapv #(put-char st %) (seq s))]
    (is (= vhs (vec (ft/ft-leaves st dh))))
    (is (= (nth vhs 0) (ft/ft-nth st dh 0)))
    (is (= (nth vhs 2) (ft/ft-nth st dh 2)))
    (is (= 3 (:count (ft/ft-measure st dh))))
    (is (empty? (types/child-hashes (store/await (store/s-get st dh)))))
    (let [pointer (put-digit-of-leaves! st vhs)]
      (is (= pointer dh) "same elements_fuse ⇒ same digit hash"))))

(defn packed-char-repeat-digit-dual-read
  [_]
  (let [st (store/mem-store)
        s "xxxx"
        dh (put-digit-of-char-run! st s)
        entry (store/await (store/s-get st dh))]
    (is (= "repeat" (:type (first (:body (types/entry-data entry))))))
    (is (= 4 (count (ft/ft-leaves st dh))))
    (is (= (put-char st \x) (ft/ft-nth st dh 1)))))

(defn- live-ft-entries
  "Store entries reachable from a collection's tree root (not historical conj debris)."
  [st coll]
  (let [root (:root (types/entry-data (store/await (store/s-get st (v/hash coll)))))]
    (loop [hs [root] seen #{} acc []]
      (if (empty? hs)
        acc
        (let [h (first hs)]
          (if (or (nil? h) (contains? seen (store/mkey h)))
            (recur (rest hs) seen acc)
            (let [e (store/await (store/s-get st h))
                  t (when e (types/entry-type e))]
              (if (and e (str/starts-with? (str t) "ft/"))
                (recur (into (rest hs) (types/child-hashes e))
                       (conj seen (store/mkey h))
                       (conj acc [h e]))
                (recur (rest hs) (conj seen (store/mkey h)) acc)))))))))

(defn chunk-run-fills-forward-without-overshoot
  [_]
  (let [border (str (apply str (repeat 4095 \a)) \u03bb "bc")
        long (str (apply str (repeat 9000 \x)) \u03bb)
        chunks-b (lit/chunk-run "char" border lit/page-budget)
        chunks-l (lit/chunk-run "char" long lit/page-budget)
        text-of (fn [item]
                  (let [b (:body item)]
                    (case (:type item)
                      "char" (str b)
                      "run" (str (:values b)))))]
    (is (= ["aaa" "\u03bbb" "c"]
           (mapv text-of (lit/chunk-run "char" (str "aaa" \u03bb "bc") (+ 3 10))))
        "a 2-byte character that would cross the budget starts the next chunk (the budget is 3 bytes of text and 10 of char-run header)")
    (is (= border (apply str (map text-of chunks-b))))
    (is (= long (apply str (map text-of chunks-l))))
    (is (every? #(<= (lit/item-payload-bytes %) lit/page-budget)
                (concat chunks-b chunks-l)))
    (is (= 3 (count chunks-l)))))

(defn- u8-run-buffers
  "Byte buffers stored as u8 run payloads under live ft pages."
  [st coll]
  (into []
        (comp (map (fn [[_ e]] (:body (types/entry-data e) [])))
              (mapcat identity)
              (filter #(and (= "run" (:type %))
                            (= "u8" (get-in % [:body :of]))))
              (map #(get-in % [:body :values])))
        (live-ft-entries st coll)))

(defn packed-blob-keeps-copied-bytes
  [_]
  (let [st (store/mem-store)
        n 5000
        nums (mapv #(bit-and 0xff %) (range n))
        src (host-bytes nums)
        blob (v/blob st src)
        hs (map #(types/scalar-value-hash ["u8" (int %)]) nums)
        ef (reduce (fn [a h] (hash/fuse a (hash/ref->state h))) hash/identity-state hs)
        bufs (u8-run-buffers st blob)]
    (byte-set! src 0 9)
    (is (= n (v/count blob)))
    (is (seq bufs))
    (is (every? host-bytes? bufs))
    (is (every? #(<= (byte-count %) lit/page-budget) bufs))
    (is (= 0 (v/realize (v/nth blob 0))) "the stored page is a copy")
    (is (= 255 (v/realize (v/nth blob 255))))
    (is (= 128 (v/realize (v/nth blob 128))))
    (is (= (bit-and 0xff (dec n)) (v/realize (v/nth blob (dec n)))))
    (is (= (types/value-hash "blob" ef) (v/hash blob)))
    (let [root (:root (types/entry-data (store/await (store/s-get st (v/hash blob)))))
          out (ft/ft-export-u8 st root n)]
      (is (host-bytes? out))
      (is (= nums (mapv #(byte-at out %) (range n))))
      (is (= nums (mapv #(byte-at (v/as-bytes blob) %) (range n)))))))

(defn packed-string-hash-stable-and-dense
  [_]
  (let [st (store/mem-store)
        n 2000
        s (apply str (repeat n \x))
        dv (v/string st s)
        ch (put-char st \x)
        ef (reduce (fn [a _] (hash/fuse a (hash/ref->state ch)))
                   hash/identity-state
                   (range n))
        live (live-ft-entries st dv)
        digits (filter (fn [[_ e]] (= "ft/digit" (types/entry-type e))) live)
        pointer-slots (reduce + 0 (map (fn [[_ e]]
                                         (count (:children (types/entry-data e) [])))
                                       digits))]
    (is (= n (v/count dv)))
    (is (= s (v/native dv)))
    (is (= (nth s 0) (v/realize (v/nth dv 0))))
    (is (= (nth s (dec n)) (v/realize (v/nth dv (dec n)))))
    (is (= (types/value-hash "string" ef) (v/hash dv)))
    (is (< (count live) 8) "a 2k ASCII string is a handful of 1k pages")
    (is (zero? pointer-slots) "writers emit :body, not :children")))

(defn packed-i64-vector-fills-payload
  [_]
  (let [st (store/mem-store)
        n 200
        dv (apply v/vector st (range n))
        snap (nodes-under st (v/hash dv))
        digits (filter (fn [[_ e]] (= "ft/digit" (types/entry-type e))) snap)
        bodies (map (fn [[_ e]] (:body (types/entry-data e))) digits)]
    (is (= n (v/count dv)))
    (is (= 0 (v/realize (v/nth dv 0))))
    (is (= (dec n) (v/realize (v/nth dv (dec n)))))
    (is (some (fn [body]
                (some (fn [item]
                        (and (= "run" (:type item))
                             (= "i64" (get-in item [:body :of]))))
                      body))
              bodies)
        "i64s inline as a run, not 32 refs")))

(defn packed-ref-run-digit-dual-read
  [_]
  (let [st (store/mem-store)
        a (put-i64 st 1)
        b (put-i64 st 2)
        c (put-i64 st 3)
        ms (mapv (fn [h]
                   (leaf-measure h (types/dacite-size (store/await (store/s-get st h)))))
                 [a b c])
        m (reduce measure-combine
                  {:count 0 :size-bytes 0 :elements-fuse hash/identity-state}
                  ms)
        body [{:type "run" :body {:of "ref" :values [a b c]}}]
        dh (types/node-hash "ft/digit" (:elements-fuse m))]
    (store/await (store/s-put st dh ["ft/digit" {:body body :measure m}]))
    (is (= [a b c] (vec (ft/ft-leaves st dh))))
    (is (= [a b c] (types/child-hashes (store/await (store/s-get st dh)))))
    (is (= b (ft/ft-nth st dh 1)))))

(defn- count-fuses
  "Run `f`, counting the matrix products made through `hash/fuse` and
   `host/sl2-mul` (the two ways the code multiplies states)."
  [f]
  (let [n (atom 0)
        fuse hash/fuse
        mul host/sl2-mul]
    (with-redefs [hash/fuse (fn [a b] (swap! n inc) (fuse a b))
                  host/sl2-mul (fn [a b] (swap! n inc) (mul a b))]
      (f))
    @n))

(defn a-blob-costs-about-one-product-per-byte
  [_]
  ;; Re-wrapping a page (a digit as an ft/node) used to measure its bytes a
  ;; second time: 2.03 products per byte, twice the time. The measure the node
  ;; or digit already holds is passed through now, in `as-node!` and `as-digit!`
  ;; (review of PR #15). Each byte needs one product (the floor of a homomorphic
  ;; hash). Measured: 1.10 per byte at 40,000 bytes, 1.01 at 500,000: about
  ;; three pages at the ends of the tree are still measured twice (a fixed cost,
  ;; so a larger share of a small blob), hence the bound of 1.15. The warm-up
  ;; builds the 256 u8 states (one-time). About 0.05 s.
  (let [n 40000
        bs (random-bytes n)
        st (store/mem-store)
        _ (v/hash (v/blob (store/mem-store) (host-bytes (repeat 10 0))))
        fuses (count-fuses #(v/hash (v/blob st bs)))
        per-byte (/ fuses (* 1.0 n))]
    (is (<= n fuses) "one product per byte at least")
    (is (<= per-byte 1.15) (str "products per byte: " per-byte))))

(def checks
  {"empty-tree" empty-tree
   "conj-bare-leaves-no-singles" conj-bare-leaves-no-singles
   "vector-value-hash-stable-without-singles" vector-value-hash-stable-without-singles
   "bare-leaf-root-ops" bare-leaf-root-ops
   "digit-of-bare-leaves" digit-of-bare-leaves
   "reject-structural-ft-as-leaf" reject-structural-ft-as-leaf
   "large-conj-and-nth" large-conj-and-nth
   "long-string-seq-matches-count" long-string-seq-matches-count
   "concat-and-rest" concat-and-rest
   "remove-nth-middle" remove-nth-middle
   "entry-density-no-single-per-element" entry-density-no-single-per-element
   "packed-char-run-digit-dual-read" packed-char-run-digit-dual-read
   "packed-char-repeat-digit-dual-read" packed-char-repeat-digit-dual-read
   "chunk-run-fills-forward-without-overshoot" chunk-run-fills-forward-without-overshoot
   "packed-blob-keeps-copied-bytes" packed-blob-keeps-copied-bytes
   "packed-string-hash-stable-and-dense" packed-string-hash-stable-and-dense
   "packed-i64-vector-fills-payload" packed-i64-vector-fills-payload
   "packed-ref-run-digit-dual-read" packed-ref-run-digit-dual-read
   "a-blob-costs-about-one-product-per-byte" a-blob-costs-about-one-product-per-byte})
