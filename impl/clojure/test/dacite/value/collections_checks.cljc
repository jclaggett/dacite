(ns dacite.value.collections-checks
  "Vectors, strings, blobs, maps, and sets in the value layer, on the JVM
   and on nbb.

   These were the bodies of `dacite.value.collections-test`. The JVM runs
   them from that namespace, one deftest each under its old name. nbb runs
   them from `dacite.value-cljs-test` (`npm run value:test`).

   Assertions on the JVM's native collection interfaces (`count`, `nth`,
   `get`, invoking, `conj`, `assoc`, `dissoc`, `peek`, `pop`, `empty`,
   `contains?`, `CharSequence`) and on the bounded `toString` and printing
   (`dacite.value.render`, .clj) stay in the JVM deftests: a SCI type has
   neither. The helpers that list a collection's elements use native `seq`
   on the JVM, as before, and `v2/seq` on nbb.

   `is` and `testing` are `dacite.store.check`."
  (:require [dacite.store :as store]
            [dacite.store.check :refer [is testing throws?]]
            [dacite.value :as v2]
            [dacite.value.collections :as coll]))

(def ^:private seq*
  #?(:clj seq :cljs v2/seq))

(def ^:private count*
  #?(:clj count :cljs v2/count))

(defn realize-each
  "Realize every element of a seqable Dacite collection to Clojure values."
  [coll]
  (mapv v2/realize (or (seq* coll) ())))

(defn set-vals
  [s]
  (into #{} (map v2/realize) (or (seq* s) ())))

(defn host-bytes
  "Host bytes for the ints `xs`."
  [xs]
  #?(:clj (byte-array xs) :cljs (js/Uint8Array.from (clj->js xs))))

;; =============================================================================
;; Vectors
;; =============================================================================

(defn vector-basics
  [_]
  (let [s (store/mem-store)
        v (v2/vector s 1 2 3)]
    (is (= "vector" (v2/type v)))
    (is (= [1 2 3] (realize-each v)))))

(defn vector-remove-nth
  [_]
  (let [s (store/mem-store)
        v (v2/vector s 0 1 2 3 4 5 6 7 8 9)
        remove-nth (fn [vv i]
                     (coll/vec-remove-nth s (v2/hash vv) i))]
    (testing "remove middle / ends"
      (let [v' (remove-nth v 4)]
        (is (= [0 1 2 3 5 6 7 8 9] (realize-each v')))
        (is (identical? s (v2/dacite-store v'))))
      (is (= [1 2 3 4 5 6 7 8 9] (realize-each (remove-nth v 0))))
      (is (= [0 1 2 3 4 5 6 7 8] (realize-each (remove-nth v 9)))))
    (testing "original unchanged"
      (is (= (range 10) (realize-each v))))
    (testing "out of bounds"
      (is (throws? #(remove-nth v 10)))
      (is (throws? #(remove-nth v -1))))
    (testing "remove until empty matches successive drop from left"
      (loop [cur v expected (vec (range 10))]
        (if (zero? (count* cur))
          (is (empty? expected))
          (let [cur' (remove-nth cur 0)]
            (is (= (subvec expected 1) (realize-each cur')))
            (recur cur' (subvec expected 1))))))
    (testing "large vector structural removes"
      ;; 100 elements, not 200: the same tree (an ft/deep with two digits
      ;; and an empty middle), and 0.6 s on nbb instead of 2.4 s.
      (let [n 100
            big (apply v2/vector s (range n))
            ;; remove odd indices from the end so remaining indices stay valid
            after (reduce (fn [vv i] (remove-nth vv i))
                          big
                          (range (dec n) -1 -2))]
        (is (= (vec (range 0 n 2)) (realize-each after)))))))

(defn vector-empty-and-wrap
  [_]
  (let [s (store/mem-store)
        v (v2/vector s 1 2 3)
        w (v2/wrap-hash s (v2/hash v))]
    (is (= "vector" (v2/type w)))
    (is (= [1 2 3] (realize-each w)))))

;; =============================================================================
;; Strings & blobs
;; =============================================================================

(defn string-basics
  [_]
  (let [s (store/mem-store)
        str-v (v2/string s "hello")]
    (is (= "string" (v2/type str-v)))
    (testing "realize yields a lazy seq of chars; (apply str ...) rebuilds the String"
      (is (= [\h \e \l \l \o] (v2/realize str-v)))
      (is (= "hello" (apply str (v2/realize str-v)))))
    (is (= [\h \e \l \l \o] (realize-each str-v)))))

(defn blob-basics
  [_]
  (let [s (store/mem-store)
        b (v2/blob s (host-bytes [1 2 3 4]))]
    (is (= "blob" (v2/type b)))
    (is (= [1 2 3 4] (vec (v2/realize b))))))

;; =============================================================================
;; Maps
;; =============================================================================

(defn map-basics
  [_]
  (let [s (store/mem-store)
        m (v2/map s "a" 1 "b" 2)]
    (is (= "map" (v2/type m)))))

(defn map-insertion-order-independent
  [_]
  (let [s (store/mem-store)]
    (is (= (v2/hash (v2/map s "a" 1 "b" 2 "c" 3))
           (v2/hash (v2/map s "c" 3 "a" 1 "b" 2))))))

;; =============================================================================
;; Sets
;; =============================================================================

(defn set-basics
  [_]
  (let [s (store/mem-store)
        st (v2/set s 1 2 3)]
    (is (= "set" (v2/type st)))
    (is (= #{1 2 3} (set-vals st)))
    (is (v2/set-member? st 1))
    (is (not (v2/set-member? st 9)))))

(defn set-order-independent
  [_]
  (let [s (store/mem-store)]
    (is (= (v2/hash (v2/set s 1 2 3))
           (v2/hash (v2/set s 3 1 2))))))

(defn set-operations
  [_]
  (let [s (store/mem-store)
        a (v2/set s 1 2 3)
        b (v2/set s 3 4 5)]
    (is (= #{1 2 3 4 5} (set-vals (v2/set-union a b))))
    (is (= #{3} (set-vals (v2/set-intersect a b))))
    (is (= #{1 2} (set-vals (v2/set-difference a b))))))

(defn negative-sets
  [_]
  (let [s (store/mem-store)
        a (v2/set s 1 2 3)
        comp (v2/set-complement a)]
    (testing "complement inverts membership"
      (is (not (v2/set-member? comp 1)))
      (is (v2/set-member? comp 99)))
    (testing "union(complement(A), A) is universal"
      (let [u (v2/set-union comp a)]
        (is (v2/set-member? u 1))
        (is (v2/set-member? u 999))))
    (testing "intersect(A, complement(A)) is empty"
      (let [e (v2/set-intersect a comp)]
        (is (not (v2/set-member? e 1)))
        (is (not (v2/set-member? e 999)))))
    (testing "double complement restores the set"
      (is (= (v2/hash a)
             (v2/hash (v2/set-complement comp)))))
    (testing "realize exposes sentinel as :dacite/negative, not nil"
      (is (= #{:dacite/negative 1 2 3} (set (v2/realize comp)))))))

;; =============================================================================
;; realize realizes collections as lazy seqs (deep, partial-availability-friendly)
;; =============================================================================

(defn realize-collections-are-lazy-seqs
  [_]
  (let [s (store/mem-store)]
    (testing "vector -> lazy seq of values"
      (let [r (v2/realize (v2/vector s 1 2 3))]
        (is (seq? r))
        (is (= [1 2 3] r))))
    (testing "set -> lazy seq of values"
      (is (= #{1 2 3} (set (v2/realize (v2/set s 1 2 3))))))
    (testing "blob -> lazy seq of byte values"
      (is (= [1 2 3 4] (vec (v2/realize (v2/blob s (host-bytes [1 2 3 4])))))))))

(defn realize-map-yields-realized-pairs
  [_]
  (let [s (store/mem-store)
        m (v2/map s 1 10 2 20)
        r (v2/realize m)]
    (is (seq? r))
    (testing "each entry is a [k v] pair with key and value realized"
      (is (= #{[1 10] [2 20]} (set r))))))

(defn realize-is-deep-with-nested-collections
  [_]
  (let [s (store/mem-store)
        nested (v2/vector s (v2/vector s 1 2) 3)
        r (v2/realize nested)]
    (testing "sub-collections become nested seqs"
      (is (= [[1 2] 3] r))
      (is (seq? (first r))))))

(defn realize-empty-collections-are-nil
  [_]
  (let [s (store/mem-store)]
    (is (nil? (v2/realize (v2/vector s))))
    (is (nil? (v2/realize (v2/set s))))
    (is (nil? (v2/realize (v2/string s ""))))
    (is (nil? (v2/realize (v2/map s))))))

;; =============================================================================
;; Bounded toString: only the element listing is portable
;; =============================================================================

(defn bounded-toString-small-collections
  [_]
  (let [s (store/mem-store)]
    (is (= #{1 2 3} (set-vals (v2/set s 1 2 3))))))

(def checks
  {"vector-basics" vector-basics
   "vector-remove-nth" vector-remove-nth
   "vector-empty-and-wrap" vector-empty-and-wrap
   "string-basics" string-basics
   "blob-basics" blob-basics
   "map-basics" map-basics
   "map-insertion-order-independent" map-insertion-order-independent
   "set-basics" set-basics
   "set-order-independent" set-order-independent
   "set-operations" set-operations
   "negative-sets" negative-sets
   "realize-collections-are-lazy-seqs" realize-collections-are-lazy-seqs
   "realize-map-yields-realized-pairs" realize-map-yields-realized-pairs
   "realize-is-deep-with-nested-collections" realize-is-deep-with-nested-collections
   "realize-empty-collections-are-nil" realize-empty-collections-are-nil
   "bounded-toString-small-collections" bounded-toString-small-collections})
