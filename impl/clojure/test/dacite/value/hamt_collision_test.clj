(ns dacite.value.hamt-collision-test
  "Issue #12: a HAMT whose keys share their first hash chunks. Two single-child
   bitmap nodes of one chain with the same bit and the same entries had one
   hash (it covered the bitmap and the entries, not the level), so a node was
   its own child: `hamt-entries` overflowed the stack and `get` found nothing.

   Two kinds of collision are forced: forged key hashes with chosen chunks
   (every chain shape, at the hamt level), and real i64 keys whose hashes share
   three chunks with a repeated one (through `v/map`, literal-of and
   encode-value)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dacite.peer.frame :as frame]
            [dacite.peer.items :as items]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.value :as v]
            [dacite.value.collections :as coll]
            [dacite.value.hamt :as hamt]
            [dacite.wire.binary :as bin]))

(def ^:private samples
  "Per property. Each sample builds and takes apart a small map."
  40)

;; ---------------------------------------------------------------------------
;; The report of the issue
;; ---------------------------------------------------------------------------

(deftest the-two-key-map-of-issue-12
  (let [st (store/mem-store)
        k1 (v/u16 st 46801)
        k2 (v/f64 st 9.0)
        m (v/map st k1 1 k2 2)
        root (:root (second (store/await (store/s-get st (v/hash m)))))]
    (is (= 2 (v/count m)))
    (is (= 2 (count (hamt/hamt-entries st root))))
    (is (= 1 (v/realize (v/get m k1))))
    (is (= 2 (v/realize (v/get m k2))))
    (is (= #{1 2} (set (map v/realize (v/vals m)))))
    (testing "no node is its own descendant: every node of the tree is distinct"
      (let [hs (loop [q [root] seen []]
                 (if (empty? q)
                   seen
                   (let [e (store/await (store/s-get st (peek q)))]
                     (recur (into (pop q) (when (= "hamt/bitmap" (first e)) (:children (second e))))
                            (conj seen (peek q))))))]
        (is (= (count hs) (count (distinct (map vec hs)))))))))

;; ---------------------------------------------------------------------------
;; Forged key hashes: the chain shapes, at the hamt level
;; ---------------------------------------------------------------------------

(defn- chunks->hash
  "A 4-long key hash from the 5-bit chunks of levels 0.. (level 51 reads one bit, as 0 or 16)."
  [chunks]
  (let [chunks (take 52 (concat chunks (repeat 0)))
        n (reduce (fn [^java.math.BigInteger acc c] (.or (.shiftLeft acc 5) (java.math.BigInteger/valueOf c)))
                  java.math.BigInteger/ZERO chunks)
        n (.shiftRight ^java.math.BigInteger n 4)]
    (vec (for [i (range 4)]
           (.longValue (.shiftRight ^java.math.BigInteger n (* 64 (- 3 i))))))))

(deftest chunks->hash-is-the-inverse-of-hash-chunk
  (let [cs (vec (take 52 (cycle [3 31 0 17 9])))
        cs (conj (subvec cs 0 51) (rand-nth [0 16]))
        h (chunks->hash cs)]
    (is (= cs (mapv #(hamt/hash-chunk h %) (range 52))))))

(def ^:private gen-chunk-sets
  "Distinct key hashes from 2..7 keys. The first `shared` levels use only
   chunks 0..2, so chunk values repeat in a chain; the rest is random."
  (gen/bind
   (gen/tuple (gen/choose 2 7) (gen/choose 1 6))
   (fn [[n shared]]
     (gen/fmap (fn [rows]
                 (vec (distinct (map (fn [[head tail]] (chunks->hash (concat head tail))) rows))))
               (gen/vector (gen/tuple (gen/vector (gen/choose 0 2) shared)
                                      (gen/vector (gen/choose 0 31) 8))
                           n)))))

(defn- build
  "Root of a HAMT with the [i key-hash] pairs: key hash i under the i64 key i
   and the value 1000 + i."
  [st indexed]
  (reduce (fn [root [i kh]]
            (hamt/hamt-assoc st root kh (v/hash (v/i64 st i)) (v/hash (v/i64 st (+ 1000 i)))))
          (hamt/hamt-empty st)
          indexed))

(deftest property-forced-chains-assoc-get-count-entries-dissoc
  (let [r (tc/quick-check
           samples
           (prop/for-all [khs gen-chunk-sets]
             (let [st (store/mem-store)
                   root (build st (map-indexed vector khs))
                   n (count khs)
                   pair (fn [i] [(vec (v/hash (v/i64 st i))) (vec (v/hash (v/i64 st (+ 1000 i))))])
                   entries (fn [r] (set (map (fn [[k x]] [(vec k) (vec x)]) (hamt/hamt-entries st r))))]
               (and
                ;; count, entries, get
                (= n (hamt/hamt-count st root))
                (= (set (map pair (range n))) (entries root))
                (every? (fn [[i kh]] (= (second (pair i)) (some-> (hamt/hamt-get st root kh) vec)))
                        (map-indexed vector khs))
                ;; the same entries in the other order are the same tree
                (= (vec root) (vec (build st (reverse (map-indexed vector khs)))))
                ;; assoc of an existing key replaces its value and keeps the rest
                (let [kh (first khs)
                      r2 (hamt/hamt-assoc st root kh (v/hash (v/i64 st 0)) (v/hash (v/i64 st 77)))]
                  (and (= n (hamt/hamt-count st r2))
                       (= (vec (v/hash (v/i64 st 77))) (vec (hamt/hamt-get st r2 kh)))))
                ;; dissoc one at a time: the rest stay, the removed is gone, down to empty
                (loop [r root, left (vec (map-indexed vector khs))]
                  (if (empty? left)
                    (= (vec r) (vec (hamt/hamt-empty st)))
                    (let [[i kh] (first left)
                          r' (hamt/hamt-dissoc st r kh)
                          rest' (subvec left 1)]
                      (and (nil? (hamt/hamt-get st r' kh))
                           (= (count rest') (hamt/hamt-count st r'))
                           (= (set (map (comp pair first) rest')) (entries r'))
                           (every? (fn [[j kj]] (some? (hamt/hamt-get st r' kj))) rest')
                           (recur r' rest')))))))))]
    (is (:pass? r) (str "seed " (:seed r) " failed on " (pr-str (:shrunk r))))))

;; ---------------------------------------------------------------------------
;; Real keys that collide: through the value API, literal-of and encode-value
;; ---------------------------------------------------------------------------

(def ^:private colliding-i64s
  "Four groups of i64 keys whose hashes share their first three chunks, with
   a repeated chunk value among them (found by search over 0..200000)."
  [[9422 19429 22349 25338 51124]       ; chunks 4 11 4
   [11940 28589 44915 71283 93707]      ; 3 8 8
   [5537 7255 37583 39722 44040]        ; 27 26 27
   [14337 34890 48621 76560 84576]])    ; 12 12 13

(deftest the-collision-pool-still-collides
  (let [st (store/mem-store)]
    (doseq [g colliding-i64s
            :let [cs (map (fn [i] (mapv #(hamt/hash-chunk (v/hash (v/i64 st i)) %) (range 3))) g)]]
      (is (= 1 (count (distinct cs))) "one group shares its first three chunks")
      (let [[a b c] (first cs)]
        (is (or (= a b) (= b c) (= a c)) "and one of the three chunk values repeats")))))

(def ^:private gen-colliding
  (gen/bind (gen/choose 0 3)
            (fn [g]
              (gen/tuple (gen/return g)
                         (gen/vector-distinct (gen/elements (nth colliding-i64s g)) {:min-elements 2 :max-elements 5})
                         (gen/vector (gen/choose 0 40) 0 4)))))

(defn- value-map [st ks others]
  (apply v/map st (mapcat (fn [k] [(v/i64 st k) k]) (concat ks (map #(+ 200000 %) (distinct others))))))

(deftest property-colliding-real-keys-through-the-value-api
  (let [r (tc/quick-check
           samples
           (prop/for-all [[_ ks others] gen-colliding]
             (let [st (store/mem-store)
                   others (distinct others)
                   all (concat ks (map #(+ 200000 %) others))
                   m (value-map st ks others)
                   expect (into {} (map (fn [k] [k k]) all))]
               (and
                (= (count all) (v/count m))
                (= expect (into {} (map (fn [[k x]] [(v/realize k) (v/realize x)]) (v/seq m))))
                (every? (fn [k] (= k (v/realize (v/get m (v/i64 st k))))) all)
                (nil? (v/get m (v/i64 st 999999)))
                ;; the value hash is that of the same entries built in another order
                (= (vec (v/hash m)) (vec (v/hash (value-map st (reverse ks) (reverse others)))))
                ;; assoc a new value for one key; dissoc another
                (let [k (first ks)
                      m2 (v/assoc m (v/i64 st k) -1)
                      m3 (v/dissoc m2 (v/i64 st (second ks)))]
                  (and (= -1 (v/realize (v/get m2 (v/i64 st k))))
                       (= (count all) (v/count m2))
                       (= (dec (count all)) (v/count m3))
                       (nil? (v/get m3 (v/i64 st (second ks))))
                       (= -1 (v/realize (v/get m3 (v/i64 st k))))
                       (every? (fn [x] (= x (v/realize (v/get m3 (v/i64 st x)))))
                               (remove #{k (second ks)} all))))))))]
    (is (:pass? r) (str "seed " (:seed r) " failed on " (pr-str (:shrunk r))))))

(deftest property-colliding-maps-round-trip-through-literals-and-streams
  (let [r (tc/quick-check
           20
           (prop/for-all [[_ ks others] gen-colliding]
             (let [st (store/mem-store)
                   m (value-map st ks others)
                   h (v/hash m)
                   form (pack/literal-of st h)
                   st2 (store/mem-store)
                   got (pack/materialize-literal! st2 (:type form) (:body form))
                   mf frame/max-frame
                   {:keys [items]} (items/encode-value st h {:max-frame-bytes mf})
                   rx (items/finish! (items/accept-items! nil (bin/decode-item-stream
                                                                (frame/concat-bytes items))))]
               (and (= (vec h) (vec got))
                    (some? (store/await (store/s-get (:store rx) h)))
                    (= (v/count m) (coll/coll-count (:store rx) h))))))]
    (is (:pass? r) (str "seed " (:seed r) " failed on " (pr-str (:shrunk r))))))

(deftest a-set-with-colliding-members-keeps-them-all
  (let [st (store/mem-store)
        s (apply v/set st (map #(v/i64 st %) (first colliding-i64s)))]
    (is (= 5 (v/count s)))
    (is (every? #(v/contains? s (v/i64 st %)) (first colliding-i64s)))))
