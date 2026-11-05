(ns dacite.value.hamt-collision-test
  "Issue #12 and the telescoping HAMT that fixes it. Keys whose hashes share
   their first chunks used to make a chain of one-child bitmap nodes; two nodes
   of a chain had one hash, so a node was its own child (`hamt-entries`
   overflowed the stack, `get` found nothing). Now a bitmap node has two or more
   children and names the chunk it splits on (`:split`), so the tree is the
   canonical HAMT of its keys: the shape is a function of the key set, and the
   same entries (keys with values) give byte-identical nodes, whatever the
   history and whichever map holds them.

   Two kinds of key are forced: forged key hashes with chosen chunks (every
   shape, down to the last chunk, at the hamt level), and real i64 keys whose
   hashes share three chunks with a repeated one (through `v/map`, literal-of
   and encode-value)."
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

(declare check-tree build chunks->hash shape canon)

(deftest the-two-key-map-of-issue-12
  (let [st (store/mem-store)
        k1 (v/u16 st 17736)
        k2 (v/u16 st 47677)
        m (v/map st k1 1 k2 2)
        root (:root (second (store/await (store/s-get st (v/hash m)))))
        [_ rd] (store/await (store/s-get st root))]
    (is (= 2 (v/count m)))
    (is (= 2 (count (hamt/hamt-entries st root))))
    (is (= 1 (v/realize (v/get m k1))))
    (is (= 2 (v/realize (v/get m k2))))
    (is (= #{1 2} (set (map v/realize (v/vals m)))))
    (testing "the chunks of the two hashes are 31 31 10 7 and 31 31 10 28: a chunk
              value repeats (31 31, as in the report of issue 12, where it made
              two chain nodes hash alike), the three first chunks are shared, and
              the node branches at chunk 3, directly, with two entries under it"
      (is (= [31 31 10 7] (mapv #(hamt/hash-chunk (v/hash k1) %) (range 4))))
      (is (= [31 31 10 28] (mapv #(hamt/hash-chunk (v/hash k2) %) (range 4))))
      (is (= 3 (:split rd)))
      (is (= 2 (count (:children rd))))
      (is (every? #(= "hamt/entry" (first (store/await (store/s-get st %)))) (:children rd))))
    (is (empty? (check-tree st root)))))

(deftest forged-chunk-pairs-of-issue-12
  ;; Chunk prefixes of two key hashes (the rest zero). The first chunks repeat
  ;; ([1 1 ...], [1 2 1 ...]), as in the bug: each pair branches at the first
  ;; chunk where it differs (index 2, index 3) and nowhere else.
  (doseq [[a b split] [[[1 1 2] [1 1 3] 2]
                       [[1 2 1 4] [1 2 1 5] 3]
                       [[1 1 1 1 2] [1 1 1 1 3] 4]
                       [[0 0 0 0] [0 0 0 16] 3]]
          :let [st (store/mem-store)
                ha (chunks->hash a)
                hb (chunks->hash b)
                root (build st [[1 ha] [2 hb]])
                [_ rd] (store/await (store/s-get st root))]]
    (testing (pr-str [a b])
      (is (= split (:split rd)) "one node, at the first chunk where the hashes differ")
      (is (every? #(= "hamt/entry" (first (store/await (store/s-get st %)))) (:children rd)))
      (is (= 2 (hamt/hamt-count st root)))
      (is (= 2 (count (hamt/hamt-entries st root))))
      (is (every? some? [(hamt/hamt-get st root ha) (hamt/hamt-get st root hb)]))
      (is (empty? (check-tree st root)))
      (is (= (vec root) (vec (build st [[2 hb] [1 ha]]))) "either order, one root")
      (let [r1 (hamt/hamt-dissoc st root ha)]
        (is (= (vec r1) (vec (build st [[2 hb]]))) "a single entry is the root again")
        (is (nil? (hamt/hamt-get st r1 ha)))))))

;; ---------------------------------------------------------------------------
;; Forged key hashes: the chain shapes, at the hamt level
;; ---------------------------------------------------------------------------

(defn chunks->hash
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

;; The canonical shape, computed without the HAMT code: one key is an entry;
;; more split at the first chunk where they differ, one child per chunk value.
(defn canon
  "Shape of the canonical HAMT of the distinct key hashes `khs`."
  [khs]
  (if (= 1 (count khs))
    [:entry (vec (first khs))]
    (let [split (first (filter (fn [c] (apply not= (map #(hamt/hash-chunk % c) khs)))
                               (range 52)))
          groups (group-by #(hamt/hash-chunk % split) khs)]
      [:node split (mapv (fn [c] (canon (groups c))) (sort (keys groups)))])))

(defn shape
  "The same shape, read from the stored tree under `h`."
  [st h]
  (let [[t d] (store/await (store/s-get st h))]
    (case t
      "hamt/empty" [:empty]
      "hamt/entry" [:entry (vec (:key-hash d))]
      "hamt/bitmap" [:node (:split d) (mapv #(shape st %) (:children d))])))

(defn- key-hashes [st h]
  (let [[t d] (store/await (store/s-get st h))]
    (case t
      "hamt/empty" []
      "hamt/entry" [(:key-hash d)]
      "hamt/bitmap" (mapcat #(key-hashes st %) (:children d)))))

(defn check-tree
  "Problems found in the tree under `root`, [] when sound: a node stored under
   other than its `expected-hash`, a cycle, a bitmap with fewer than two
   children, a split out of 0..51 or not above its parent's, a bitmap that is
   not one bit per child chunk, a measure that is not the sum of its
   children's, keys that do not agree before the split, or a split at which
   they all agree (the shape is not canonical)."
  [st root]
  (let [problems (atom [])]
    (letfn [(walk [h path above]
              (if (contains? path (vec h))
                (swap! problems conj [:cycle (vec h)])
                (let [e (store/await (store/s-get st h))]
                  (when-not (= (vec h) (some-> (sr/expected-hash st e) vec))
                    (swap! problems conj [:stored-under-another-hash (first e)]))
                  (when (= "hamt/bitmap" (first e))
                    (let [{:keys [bitmap split children measure]} (second e)
                          ms (map #(:measure (second (store/await (store/s-get st %)))) children)
                          groups (map #(key-hashes st %) children)
                          chunks (map #(hamt/hash-chunk (first %) split) groups)]
                      (when-not (<= 2 (count children))
                        (swap! problems conj [:fewer-than-two-children (count children)]))
                      (when-not (and (<= 0 split 51) (> split above))
                        (swap! problems conj [:split-out-of-order split above]))
                      (when-not (= (Long/bitCount (long bitmap)) (count children))
                        (swap! problems conj [:popcount]))
                      (when-not (and (apply distinct? chunks) (= chunks (sort chunks))
                                     (= (long bitmap) (reduce bit-or 0 (map #(bit-shift-left 1 %) chunks))))
                        (swap! problems conj [:bitmap-is-not-the-chunks]))
                      (when-not (every? (fn [g] (apply = (map #(hamt/hash-chunk % split) g))) groups)
                        (swap! problems conj [:child-mixes-chunks]))
                      (when-not (every? (fn [c] (apply = (map #(hamt/hash-chunk % c) (apply concat groups))))
                                        (range split))
                        (swap! problems conj [:keys-differ-before-split]))
                      (when-not (and (= (:count measure) (reduce + (map :count ms)))
                                     (= (:elements-fuse measure)
                                        (reduce hash/fuse hash/identity-state (map :elements-fuse ms))))
                        (swap! problems conj [:measure]))
                      (doseq [c children] (walk c (conj path (vec h)) split)))))))]
      (walk root #{} -1))
    @problems))

(defn build
  "Root of a HAMT with the [i key-hash] pairs: key hash i under the i64 key i
   and the value 1000 + i."
  [st indexed]
  (reduce (fn [root [i kh]]
            (hamt/hamt-assoc st root kh (v/hash (v/i64 st i)) (v/hash (v/i64 st (+ 1000 i)))))
          (hamt/hamt-empty st)
          indexed))

(defn- reachable
  "{hash-vec entry} of every node under `root`."
  [st root]
  (loop [q [root] out {}]
    (if (empty? q)
      out
      (let [h (peek q)
            e (store/await (store/s-get st h))]
        (recur (into (pop q) (when (= "hamt/bitmap" (first e)) (:children (second e))))
               (assoc out (vec h) e))))))

(defn- node-bytes [h e]
  (vec (bin/encode-item {:enc :node :hash h :entry e})))

(defn- same-nodes?
  "Both roots reach the same set of nodes, and each node has the same wire bytes."
  [st1 r1 st2 r2]
  (let [a (reachable st1 r1)
        b (reachable st2 r2)]
    (and (= (set (keys a)) (set (keys b)))
         (every? (fn [h] (= (node-bytes h (a h)) (node-bytes h (b h)))) (keys a)))))

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
                ;; count, entries, get; the shape is the canonical one
                (= n (hamt/hamt-count st root))
                (empty? (check-tree st root))
                (= (canon khs) (shape st root))
                (= (set (map pair (range n))) (entries root))
                (every? (fn [[i kh]] (= (second (pair i)) (some-> (hamt/hamt-get st root kh) vec)))
                        (map-indexed vector khs))
                ;; the same entries in the other order are the same tree
                (= (vec root) (vec (build st (reverse (map-indexed vector khs)))))
                ;; assoc of an existing key replaces its value and keeps the rest
                (let [kh (first khs)
                      r2 (hamt/hamt-assoc st root kh (v/hash (v/i64 st 0)) (v/hash (v/i64 st 77)))]
                  (and (= n (hamt/hamt-count st r2))
                       (= (canon khs) (shape st r2))
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
                           (or (empty? rest') (= (canon (map second rest')) (shape st r')))
                           (= (count rest') (hamt/hamt-count st r'))
                           (= (set (map (comp pair first) rest')) (entries r'))
                           (every? (fn [[j kj]] (some? (hamt/hamt-get st r' kj))) rest')
                           (recur r' rest')))))))))]
    (is (:pass? r) (str "seed " (:seed r) " failed on " (pr-str (:shrunk r))))))

(def ^:private colliding-i64s
  "Four groups of i64 keys whose hashes share their first three chunks, with
   a repeated chunk value among them, and a different fourth chunk each (found
   by search over 0..120000 with the SL(2, F_p) hash, issue #14)."
  [[24948 25845 28248 40945 71283]      ; chunks 2 0 2
   [31622 33512 87412 88804 113243]     ; 3 0 3
   [4295 64535 72858 74890 82793]       ; 5 0 5
   [41289 49026 49810 54189 95030]])    ; 6 0 6

(deftest the-last-chunk-is-a-split-like-any-other
  ;; Keys that agree on chunks 0..50 and differ in the last bit (chunk 51,
  ;; which reads one bit as 0 or 16): one node, split 51, two entries. Not 51
  ;; one-child nodes over it. Chunk 0 on every level is the same on purpose.
  (doseq [c [0 7]]
    (let [st (store/mem-store)
          a (chunks->hash (concat (repeat 51 c) [0]))
          b (chunks->hash (concat (repeat 51 c) [16]))
          other (chunks->hash (concat (repeat 49 c) [31 0]))
          root (build st [[1 a] [2 b]])
          [_ rd] (store/await (store/s-get st root))]
      (is (= 51 (:split rd)))
      (is (= 3 (count (reachable st root))) "the node and its two entries")
      (is (empty? (check-tree st root)))
      (is (= 2 (count (hamt/hamt-entries st root))))
      (is (every? some? [(hamt/hamt-get st root a) (hamt/hamt-get st root b)]))
      (testing "a third key that leaves them at chunk 49: that node splits at 49, the last-chunk node hangs under it"
        (let [r3 (build st [[1 a] [2 b] [3 other]])
              [_ d3] (store/await (store/s-get st r3))]
          (is (= 49 (:split d3)))
          (is (= [:node 49 [[:node 51 [[:entry (vec a)] [:entry (vec b)]]] [:entry (vec other)]]]
                 (shape st r3)))
          (is (= (canon [a b other]) (shape st r3)))
          (is (empty? (check-tree st r3)))
          (is (= (vec root) (vec (hamt/hamt-dissoc st r3 other))) "dissoc gives back the two-key node")))
      (testing "dissoc leaves the single entry a fresh one-key map has"
        (is (= (vec (hamt/hamt-dissoc st root a)) (vec (build st [[2 b]]))))
        (is (= (vec (hamt/hamt-dissoc st root b)) (vec (build st [[1 a]]))))))))

(deftest equal-key-hashes-are-one-key
  ;; There is no collision bucket: two keys with one 256-bit hash are treated
  ;; as one key and the later assoc replaces the earlier.
  (let [st (store/mem-store)
        kh (chunks->hash [5 5 5])
        r1 (build st [[1 kh]])
        r2 (hamt/hamt-assoc st r1 kh (v/hash (v/i64 st 1)) (v/hash (v/i64 st 2)))]
    (is (= 1 (hamt/hamt-count st r2)))
    (is (= (vec (v/hash (v/i64 st 2))) (vec (hamt/hamt-get st r2 kh))))))

(def ^:private gen-history
  (gen/tuple gen-chunk-sets
             (gen/vector (gen/tuple (gen/choose 0 9) (gen/choose 0 6)) 4 24)))

(deftest property-fuzz-assoc-and-dissoc-histories-give-one-tree
  ;; A random history of assoc and dissoc over a pool of forged key hashes.
  ;; At every step the tree is sound, canonical, and the root of the same
  ;; entries built fresh in another store, in another order: the same root
  ;; node bytes and the same set of nodes. And no bitmap node ever stored in
  ;; the history, garbage included, has fewer than two children.
  (let [r (tc/quick-check
           samples
           (prop/for-all [[khs ops] gen-history
                          seed (gen/choose 0 1000)]
             (let [st (store/mem-store)
                   pool (vec (map-indexed vector khs))]
               (and
                (loop [root (hamt/hamt-empty st), held {}, ops ops]
                  (if (empty? ops)
                    (let [st2 (store/mem-store)
                          order (shuffle (sort-by first held))
                          fresh (build st2 order)]
                      (and (same-nodes? st root st2 fresh)
                           (= (node-bytes root (store/await (store/s-get st root)))
                              (node-bytes fresh (store/await (store/s-get st2 fresh))))))
                    (let [[mode j] (first ops)
                          [i kh] (pool (mod j (count pool)))
                          assoc? (< mode 6)
                          root' (if assoc?
                                  (hamt/hamt-assoc st root kh (v/hash (v/i64 st i))
                                                   (v/hash (v/i64 st (+ 1000 i))))
                                  (hamt/hamt-dissoc st root kh))
                          held' (if assoc? (assoc held i kh) (dissoc held i))]
                      (and (empty? (check-tree st root'))
                           (or (empty? held')
                               (= (canon (vals held')) (shape st root')))
                           (= (vec root') (vec (build st (sort-by first held'))))
                           (= (count held') (hamt/hamt-count st root'))
                           (every? (fn [[i kh]] (some? (hamt/hamt-get st root' kh))) held')
                           (recur root' held' (rest ops))))))
                ;; every node the history stored
                (every? (fn [[_ e]]
                          (or (not= "hamt/bitmap" (first e))
                              (<= 2 (count (:children (second e))))))
                        (store/await (store/s-snapshot st)))))))]
    (is (:pass? r) (str "seed " (:seed r) " failed on " (pr-str (:shrunk r))))))

;; ---------------------------------------------------------------------------
;; The hash rule, the wire bytes, and the receiver's checks
;; ---------------------------------------------------------------------------

(defn- bitmap-nodes
  "[hash entry] of every hamt/bitmap node under `root`."
  [st root]
  (for [[h e] (reachable st root) :when (= "hamt/bitmap" (first e))] [h e]))

(deftest the-hash-covers-the-bitmap-and-the-split-not-the-children
  (let [st (store/mem-store)
        m (apply v/map st (mapcat (fn [k] [(v/i64 st k) k]) (first colliding-i64s)))
        root (:root (second (store/await (store/s-get st (v/hash m)))))
        [_ rd :as e] (store/await (store/s-get st root))
        ef (:elements-fuse (:measure rd))
        hand (hash/state->ref
              (hash/fuse (types/value-state "hamt/bitmap" ef)
                         (hash/fuse-bytes (conj (vec (host/int->bytes-be (:bitmap rd) 8))
                                                (:split rd)))))]
    (is (= 3 (:split rd)) "chunks 2 0 2 are shared: the split is chunk 3")
    (is (= (vec root) (vec hand)) "digest(fuse(fuse(type_state, ef), bytes(bitmap_be8 ++ [split])))")
    (is (= (vec root) (vec (sr/expected-hash st e))) "reveal expects the stored hash")
    (testing "the split is in the hash: another split, another hash"
      (is (not= (vec root)
                (vec (hamt/bitmap-node-hash ef (:bitmap rd) (inc (:split rd)))))))
    (testing "the children are not in the hash (as before): a node is checked from its own bytes"
      (is (= (vec root)
             (vec (sr/expected-hash st [(first e) (assoc rd :children (vec (reverse (:children rd))))])))))))

(deftest equal-entries-give-the-same-node-in-any-map
  ;; The node for a set of entries (keys with values) does not depend on which
  ;; map holds it. The five colliding keys are the subtree under chunk 0 = 2; two
  ;; maps with different other keys hold the same node there, in different
  ;; stores. The same keys with another value are the same shape, other bytes.
  (let [group (first colliding-i64s)
        chunk0 (fn [st k] (hamt/hash-chunk (v/hash (v/i64 st k)) 0))
        others (fn [st from] (take 6 (filter #(not= 2 (chunk0 st %)) (iterate inc from))))
        under-4 (fn [st others val-of]
                  (let [m (apply v/map st (mapcat (fn [k] [(v/i64 st k) (val-of k)])
                                                  (concat group others)))
                        root (:root (second (store/await (store/s-get st (v/hash m)))))
                        [_ rd] (store/await (store/s-get st root))]
                    (is (= 0 (:split rd)))
                    {:root root
                     :node (nth (:children rd)
                                (Long/bitCount (bit-and (long (:bitmap rd)) (dec (bit-shift-left 1 2)))))}))
        st1 (store/mem-store)
        st2 (store/mem-store)
        st3 (store/mem-store)
        a (under-4 st1 (others st1 300000) identity)
        b (under-4 st2 (others st2 400000) identity)
        c (under-4 st3 (others st3 300000) inc)]
    (is (not= (vec (:root a)) (vec (:root b))))
    (is (= (vec (:node a)) (vec (:node b))))
    (is (same-nodes? st1 (:node a) st2 (:node b)) "byte-identical, node for node")
    (testing "another value for each key: the same shape, another node"
      (is (not= (vec (:node a)) (vec (:node c))))
      (is (= (shape st1 (:node a)) (shape st3 (:node c)))))))

(deftest the-wire-layout-of-a-bitmap-node
  ;; item header, then: kind 0x02, subtype 2, measure (144), bitmap u32, split u8,
  ;; count u8, children (32 each). One byte longer than before the split.
  (let [st (store/mem-store)
        root (build st [[1 (chunks->hash [1 1 2])] [2 (chunks->hash [1 1 3])]
                        [3 (chunks->hash [1 4])]])
        e (store/await (store/s-get st root))
        d (second e)
        bs (node-bytes root e)
        i (- (count bs) (* 32 (count (:children d))) 6)] ; bitmap(4) split(1) count(1)
    (is (= 2 (count (:children d))))
    (is (= (+ 37 2 144 4 1 1 (* 32 2)) (count bs)))
    (is (= [0x02 2] (subvec bs 37 39)))
    (is (= (:split d) (nth bs (+ i 4))))
    (is (= (count (:children d)) (nth bs (+ i 5))))
    (is (= (:bitmap d) (reduce (fn [a b] (+ (* a 256) (bit-and 0xFF b))) 0 (subvec bs i (+ i 4)))))
    (testing "the bytes decode to the node"
      (let [[item] (bin/decode-item-stream (byte-array (map unchecked-byte bs)))]
        (is (= e (:entry item)))))))

(defn- verify-reason [st h e]
  (try (items/verify-item! st {:enc :node :hash h :entry e}) :ok
       (catch clojure.lang.ExceptionInfo ex (:reason (ex-data ex)))))

(deftest tampered-bitmap-nodes-are-rejected
  (let [st (store/mem-store)
        root (build st [[1 (chunks->hash [1 1 2])] [2 (chunks->hash [1 1 3])]
                        [3 (chunks->hash [1 4])]])
        [t d :as e] (store/await (store/s-get st root))
        expected-ef (:elements-fuse (:measure d))
        node-with (fn [data] (let [e' [t data]]
                               [(hamt/bitmap-node-hash (:elements-fuse (:measure data))
                                                       (:bitmap data) (:split data))
                                e']))]
    (is (= :ok (verify-reason st root e)))
    (testing "another split index: a bad hash (in the entry, and in the wire bytes)"
      (doseq [split [0 (inc (:split d)) 51]
              :when (not= split (:split d))]
        (is (= :bad-hash (verify-reason st root [t (assoc d :split split)])) (str "split " split)))
      (let [good (node-bytes root e)
            moved (node-bytes root [t (assoc d :split (inc (:split d)))])
            diffs (keep-indexed (fn [i [x y]] (when (not= x y) i)) (map vector good moved))]
        (is (= 1 (count diffs)) "the split is one byte")
        (let [flipped (byte-array (map unchecked-byte moved))
              [item] (bin/decode-item-stream flipped)]
          (is (= :bad-hash (verify-reason st (:hash item) (:entry item)))
              "the claimed hash is the old one, the bytes say another split"))))
    (testing "another bitmap: a bad hash"
      (is (= :bad-hash (verify-reason st root [t (assoc d :bitmap (bit-xor (long (:bitmap d)) 0x80000000))]))))
    (testing "a node that hashes right to its own bytes but is not shaped right: bad entry"
      (let [one-child (assoc d :bitmap (bit-and (long (:bitmap d)) (- (long (:bitmap d))))
                             :children [(first (:children d))])
            [h1 e1] (node-with one-child)
            [h2 e2] (node-with (assoc d :split 52))
            [h3 e3] (node-with (assoc d :bitmap 7))]
        (is (= :bad-entry (verify-reason st h1 e1)) "one child")
        (is (= :bad-entry (verify-reason st h2 e2)) "split past the last chunk")
        (is (= :bad-entry (verify-reason st h3 e3)) "bits and children disagree")))))

(deftest the-receiver-checks-split-51-bits-and-an-entrys-key-hash
  (let [st (store/mem-store)
        a (chunks->hash (concat (repeat 51 3) [0]))
        b (chunks->hash (concat (repeat 51 3) [16]))
        root (build st [[1 a] [2 b]])
        [t d :as e] (store/await (store/s-get st root))
        forged (fn [data] [(hamt/bitmap-node-hash (:elements-fuse (:measure data))
                                                  (:bitmap data) (:split data))
                           [t data]])]
    (is (= 51 (:split d)))
    (is (= 0x10001 (:bitmap d)) "chunk 51 reads one bit as 0 or 16")
    (is (= :ok (verify-reason st root e)))
    (testing "S2: split 51 with another bitmap, hash right, is a bad entry"
      (let [[h1 e1] (forged (assoc d :bitmap 0x6))
            [h2 e2] (forged (assoc d :bitmap 0x30000))]
        (is (= :bad-entry (verify-reason st h1 e1)) "bitmap 0b110")
        (is (= :bad-entry (verify-reason st h2 e2)) "bits 16 and 17")))
    (testing "S2: other splits take any bits"
      (let [st2 (store/mem-store)
            r2 (build st2 [[1 (chunks->hash [1 1 2])] [2 (chunks->hash [1 1 3])]])
            [_ d2] (store/await (store/s-get st2 r2))]
        (is (= :ok (verify-reason st2 r2 (store/await (store/s-get st2 r2)))))
        (is (not= 0x10001 (:bitmap d2)))))
    (testing "S3: an entry whose key-hash is not its key-ref, hash right, is a bad entry"
      (let [kh (v/hash (v/i64 st 1))
            ent (hamt/hamt-entry-node st kh kh (v/hash (v/i64 st 2)))
            [et ed :as ee] (store/await (store/s-get st ent))
            ee2 [et (assoc ed :key-hash (chunks->hash [9 9 9]))]]
        (is (= :ok (verify-reason st ent ee)))
        (is (= :bad-entry (verify-reason st ent ee2))
            "key-hash is in no hash, so only this check catches it")))))

(deftest assoc-onto-a-misplaced-entry-is-bad-hamt-node-not-an-npe
  ;; An entry stored where its own hash does not route it. The root splits at
  ;; chunk 0; its slot-1 child is an entry whose hash has chunk 0 = 2 and
  ;; otherwise [5 0 0 ...]. Assoc of the key [1 5 ...] reaches that entry at
  ;; chunk 1, where the two agree on every chunk: no branch exists. Before the
  ;; check this was a NullPointerException.
  (let [st (store/mem-store)
        root (build st [[1 (chunks->hash [1 6])] [2 (chunks->hash [2 7])]])
        [t d] (store/await (store/s-get st root))
        mis (hamt/hamt-assoc st (hamt/hamt-empty st) (chunks->hash [2 5])
                             (v/hash (v/i64 st 7)) (v/hash (v/i64 st 8)))
        data (assoc d :children [mis (second (:children d))])
        forged (hamt/bitmap-node-hash (:elements-fuse (:measure data)) (:bitmap data) (:split data))]
    (store/await (store/s-put st forged [t data]))
    (is (= :bad-hamt-node
           (try (hamt/hamt-assoc st forged (chunks->hash [1 5]) (v/hash (v/i64 st 9)) (v/hash (v/i64 st 10)))
                :no-throw
                (catch clojure.lang.ExceptionInfo ex (:reason (ex-data ex)))
                (catch NullPointerException _ :npe))))))

(deftest a-forged-store-with-splits-out-of-order-cannot-loop
  ;; Both nodes hash right to their own bytes; the child's split is not above
  ;; its parent's. A walk throws; it does not recurse.
  (let [st (store/mem-store)
        a (chunks->hash [1 1 2]) b (chunks->hash [1 1 3])
        root (build st [[1 a] [2 b]])
        [t d] (store/await (store/s-get st root))
        kids (:children d)
        put! (fn [data] (let [h (hamt/bitmap-node-hash (:elements-fuse (:measure data))
                                                       (:bitmap data) (:split data))]
                          (store/await (store/s-put st h [t data]))
                          h))
        loop-root (put! (assoc d :split 2))
        loopy (put! (assoc d :split 2 :children [loop-root (second kids)]))]
    (doseq [f [#(hamt/hamt-get st loopy a) #(hamt/hamt-entries st loopy)
               #(hamt/hamt-dissoc st loopy a) #(hamt/hamt-assoc st loopy a (v/hash (v/i64 st 1)) (v/hash (v/i64 st 2)))]]
      (is (= :bad-hamt-node (try (doall (f)) nil
                                 (catch clojure.lang.ExceptionInfo ex (:reason (ex-data ex)))))))))

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
  ;; carries node items (with the telescoped branch of the colliding keys among
  ;; them), and so does the map after a dissoc of one colliding key.
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

(deftest every-inner-hamt-node-literal-round-trips
  ;; A node is the canonical HAMT of its keys, so a literal of its entries
  ;; rebuilds the very node (before telescoping some inner nodes did not).
  (let [st (store/mem-store)
        ks (concat (first colliding-i64s) (range 300000 300150))
        m (apply v/map st (mapcat (fn [k] [(v/i64 st k) k]) ks))
        root (:root (second (store/await (store/s-get st (v/hash m)))))
        nodes (bitmap-nodes st root)]
    (is (< 3 (count nodes)))
    (doseq [[h _] nodes
            :let [form (pack/literal-of st h)]]
      (is (= (vec h) (vec (pack/materialize-literal! (store/mem-store) (:type form) (:body form))))))))

(deftest value-hashes-of-correct-maps-are-pinned
  ;; The value hash is the fuse of the entries, not of the tree: telescoping
  ;; (d1a0455) moved no value hash, only the bitmap nodes under them. These pins
  ;; are hash version 2 (the SL(2, F_p) fuse, issue #14). The 4-key set is four
  ;; colliding keys that share their first three chunks (the shape the bug
  ;; corrupted: main gave a hash per insertion order on such keys), and here both
  ;; orders give one.
  (let [st (store/mem-store)
        hx #(store/hash->hex (v/hash %))
        i64s (fn [ks] (apply v/map st (mapcat (fn [k] [(v/i64 st k) k]) ks)))]
    (is (= "dc5ea1d78239c6765a9dbee9de21dcf77b6cc9932ab3b88d0e7ce098d49ceb25" (hx (i64s (range 500)))))
    (is (= "5654c09b96e587190d6dda589c0a1aa51c48703f6de63e27af3dec5f743d0584"
           (hx (i64s (range 89891 (+ 89891 1142))))))
    (is (= "a028fc68920707cc8f6e8e86f703b1801d08f7e0d5070a1fa6ccc7adb6b1c698"
           (hx (apply v/map st (mapcat (fn [i] [(v/string st (str "key-" i)) i]) (range 100))))))
    (is (= "211317cc354ef6666f56520f94bcf6ad89dee6f5a50e6ec4d2ca07f1a5080d6f"
           (hx (apply v/set st (map #(v/i64 st %) [24948 25845 28248 40945])))))
    (is (= "211317cc354ef6666f56520f94bcf6ad89dee6f5a50e6ec4d2ca07f1a5080d6f"
           (hx (apply v/set st (map #(v/i64 st %) [40945 28248 25845 24948])))))))
