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
            [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.peer.frame :as frame]
            [dacite.peer.items :as items]
            [dacite.store :as store]
            [dacite.service.reveal :as sr]
            [dacite.store.pack :as pack]
            [dacite.value :as v]
            [dacite.value.collections :as coll]
            [dacite.value.hamt :as hamt]
            [dacite.value.types :as types]
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
   chunks 0..2, so chunk values repeat in a chain; the rest is random. One
   draw in five shares 40..51 levels: a chain down to the last level (level 51
   reads the last bit only, so there such keys differ in that one bit)."
  (gen/bind
   (gen/tuple (gen/choose 2 7)
              (gen/frequency [[4 (gen/choose 1 6)] [1 (gen/choose 40 51)]]))
   (fn [[n shared]]
     (gen/fmap (fn [rows]
                 (vec (distinct (map (fn [[head tail]] (chunks->hash (concat head tail))) rows))))
               (gen/vector (gen/tuple (gen/vector (gen/choose 0 2) shared)
                                      (gen/vector (gen/choose 0 31) 8))
                           n)))))

(defn- check-tree
  "Problems found in the tree under `root`, [] when sound: a node stored under
   other than its `expected-hash` (the property that broke), a cycle, a bitmap
   whose popcount is not its child count, a measure that is not the sum of its
   children's."
  [st root]
  (let [problems (atom [])]
    (letfn [(walk [h path]
              (if (contains? path (vec h))
                (swap! problems conj [:cycle (vec h)])
                (let [e (store/await (store/s-get st h))]
                  (when-not (= (vec h) (some-> (sr/expected-hash st e) vec))
                    (swap! problems conj [:stored-under-another-hash (first e)]))
                  (when (= "hamt/bitmap" (first e))
                    (let [{:keys [bitmap children measure]} (second e)
                          ms (map #(:measure (second (store/await (store/s-get st %)))) children)]
                      (when-not (= (Long/bitCount (long bitmap)) (count children))
                        (swap! problems conj [:popcount]))
                      (when-not (and (= (:count measure) (reduce + (map :count ms)))
                                     (= (:elements-fuse measure)
                                        (reduce hash/unchecked-fuse host/zero-hash (map :elements-fuse ms))))
                        (swap! problems conj [:measure]))
                      (doseq [c children] (walk c (conj path (vec h)))))))))]
      (walk root #{}))
    @problems))

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
                (empty? (check-tree st root))
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
                           (empty? (check-tree st r'))
                           ;; the history does not show: the same entries built by assoc
                           (= (vec r') (vec (build st rest')))
                           (= (count rest') (hamt/hamt-count st r'))
                           (= (set (map (comp pair first) rest')) (entries r'))
                           (every? (fn [[j kj]] (some? (hamt/hamt-get st r' kj))) rest')
                           (recur r' rest')))))))))]
    (is (:pass? r) (str "seed " (:seed r) " failed on " (pr-str (:shrunk r))))))

(def ^:private colliding-i64s
  "Four groups of i64 keys whose hashes share their first three chunks, with
   a repeated chunk value among them (found by search over 0..200000)."
  [[9422 19429 22349 25338 51124]       ; chunks 4 11 4
   [11940 28589 44915 71283 93707]      ; 3 8 8
   [5537 7255 37583 39722 44040]        ; 27 26 27
   [14337 34890 48621 76560 84576]])    ; 12 12 13

(deftest the-deepest-chain-is-built-read-and-checked
  ;; Two keys that differ only in the last bit (level 51): 51 single-child
  ;; nodes over one two-child node. Chunk 0 on every level repeats one bitmap.
  (doseq [c [0 7]]
    (let [st (store/mem-store)
          a (chunks->hash (concat (repeat 51 c) [0]))
          b (chunks->hash (concat (repeat 51 c) [16]))
          [ra rb] [[1 a] [2 b]]
          root (build st [ra rb])
          nodes (loop [h root n 0]
                  (let [e (store/await (store/s-get st h))]
                    (if (= "hamt/bitmap" (first e))
                      (recur (first (:children (second e))) (inc n))
                      n)))]
      (is (= 52 nodes) "51 single-child levels and the last, two-child node")
      (is (empty? (check-tree st root)))
      (is (= 2 (count (hamt/hamt-entries st root))))
      (is (every? some? [(hamt/hamt-get st root a) (hamt/hamt-get st root b)]))
      (testing "dissoc leaves the single entry a fresh one-key map has"
        (is (= (vec (hamt/hamt-dissoc st root a)) (vec (build st [rb]))))
        (is (= (vec (hamt/hamt-dissoc st root b)) (vec (build st [ra]))))))))

(deftest property-fuzz-assoc-and-dissoc-histories-give-one-tree
  ;; A random history of assoc and dissoc over a pool of forged key hashes:
  ;; at every step the tree is the one assoc builds from the entries now held
  ;; (same root hash), it is sound, and it answers get and count.
  (let [r (tc/quick-check
           samples
           (prop/for-all [khs gen-chunk-sets
                          ops (gen/vector (gen/tuple (gen/choose 0 9) (gen/choose 0 6)) 4 24)]
             (let [st (store/mem-store)
                   pool (vec (map-indexed vector khs))]
               (loop [root (hamt/hamt-empty st), held {}, ops ops]
                 (if (empty? ops)
                   true
                   (let [[mode j] (first ops)
                         [i kh] (pool (mod j (count pool)))
                         assoc? (< mode 6)
                         root' (if assoc?
                                 (first (let [r (hamt/hamt-assoc st root kh (v/hash (v/i64 st i))
                                                                 (v/hash (v/i64 st (+ 1000 i))))]
                                          [r]))
                                 (hamt/hamt-dissoc st root kh))
                         held' (if assoc? (assoc held i kh) (dissoc held i))]
                     (and (empty? (check-tree st root'))
                          (= (vec root') (vec (build st (sort-by first held'))))
                          (= (count held') (hamt/hamt-count st root'))
                          (every? (fn [[i kh]] (some? (hamt/hamt-get st root' kh))) held')
                          (recur root' held' (rest ops))))))))) ]
    (is (:pass? r) (str "seed " (:seed r) " failed on " (pr-str (:shrunk r))))))

;; ---------------------------------------------------------------------------
;; The hash rule, and reveal's check of it
;; ---------------------------------------------------------------------------

(defn- bitmap-nodes
  "[hash entry] of every hamt/bitmap node under `root`."
  [st root]
  (loop [q [root] out []]
    (if (empty? q)
      out
      (let [e (store/await (store/s-get st (peek q)))]
        (recur (into (pop q) (when (= "hamt/bitmap" (first e)) (:children (second e))))
               (if (= "hamt/bitmap" (first e)) (conj out [(peek q) e]) out))))))

(defn- before-12-hash
  "The rule before the fix: the bitmap alone, whatever the children."
  [e]
  (let [{:keys [measure bitmap]} (second e)]
    (hash/unchecked-fuse (types/node-hash "hamt/bitmap" (:elements-fuse measure))
                         (hash/fuse-bytes (host/int->bytes-be bitmap 8)))))

(deftest reveal-checks-the-single-child-rule-and-rejects-a-swapped-child
  (let [st (store/mem-store)
        m (apply v/map st (mapcat (fn [k] [(v/i64 st k) k]) (first colliding-i64s)))
        root (:root (second (store/await (store/s-get st (v/hash m)))))
        nodes (bitmap-nodes st root)
        single (filter (fn [[_ e]] (= 1 (count (:children (second e))))) nodes)
        multi (filter (fn [[_ e]] (< 1 (count (:children (second e))))) nodes)]
    (is (= 3 (count single)) "chunks 4 11 4: three single-child levels, then the split")
    (is (seq multi))
    (doseq [[h e] single]
      (is (= (vec h) (vec (sr/expected-hash st e))) "reveal expects the stored hash")
      (is (= (vec h) (vec (hamt/bitmap-node-hash (:elements-fuse (:measure (second e)))
                                                 (:bitmap (second e)) (:children (second e))))))
      (is (not= (vec h) (vec (before-12-hash e))) "and it is not the old rule's"))
    (testing "a node with two or more children keeps the old rule"
      (doseq [[h e] multi]
        (is (= (vec h) (vec (before-12-hash e))))
        (is (= (vec h) (vec (sr/expected-hash st e))))))
    (testing "the old rule gave the first and third single-child nodes one hash (the bug)"
      (let [olds (map (fn [[_ e]] (vec (before-12-hash e))) single)]
        (is (< (count (distinct olds)) (count olds)))))
    (testing "tamper: the same node with another child is a bad hash"
      (let [[h e] (first single)
            other (first (remove #(= (vec %) (vec (first (:children (second e)))))
                                 (map first nodes)))
            swapped [(first e) (assoc (second e) :children [other])]]
        (is (not= (vec h) (vec (sr/expected-hash st swapped))))
        (is (some? (items/verify-item! st {:enc :node :hash h :entry e})))
        (is (= :bad-hash
               (try (items/verify-item! st {:enc :node :hash h :entry swapped})
                    (catch clojure.lang.ExceptionInfo ex (:reason (ex-data ex))))))))))

;; ---------------------------------------------------------------------------
;; Real keys that collide: through the value API, literal-of and encode-value
;; ---------------------------------------------------------------------------

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
                (empty? (check-tree st (:root (second (store/await (store/s-get st (v/hash m)))))))
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

(deftest colliding-keys-in-a-map-big-enough-for-node-items-round-trip
  ;; 700 entries do not fit one literal at the smallest frame, so the stream
  ;; carries node items (the single-child nodes among them), and so does the
  ;; map after a dissoc of one colliding key.
  (let [st (store/mem-store)
        group (first colliding-i64s)
        ks (concat group (range 300000 300700))
        m (apply v/map st (mapcat (fn [k] [(v/i64 st k) k]) ks))
        m2 (v/dissoc m (v/i64 st (first group)))]
    (doseq [[mv n] [[m 705] [m2 704]]
            :let [h (v/hash mv)
                  {:keys [items encs]} (items/encode-value st h {:max-frame-bytes frame/min-max-frame})
                  rx (items/finish! (items/accept-items! nil (bin/decode-item-stream
                                                              (frame/concat-bytes items))))
                  rst (:store rx)
                  rroot (:root (second (store/await (store/s-get rst h))))]]
      (is (some #{:node} encs) "node items on the wire")
      (is (= n (coll/coll-count rst h)))
      (is (= n (count (hamt/hamt-entries rst rroot))))
      (is (empty? (check-tree rst rroot)) "every node the receiver holds is under its hash")
      (is (every? #(some? (hamt/hamt-get rst rroot (v/hash (v/i64 st %)))) (remove #{(first group)} ks))))))
