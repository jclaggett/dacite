(ns dacite.value.collections-test
  "Tests for vectors, strings, blobs, maps, and sets in the value layer."
  (:require [clojure.test :refer [deftest is testing]]
            [dacite.store :as store]
            [dacite.value :as v2]
            [dacite.value.collections :as coll]
            [dacite.value.render :as render]))
(defn- realize-each
  "Realize every element of a seqable Dacite collection to Clojure values."
  [coll]
  (mapv v2/realize (or (seq coll) ())))

(defn- set-vals
  [s]
  (into #{} (map v2/realize) (or (seq s) ())))

;; =============================================================================
;; Vectors
;; =============================================================================

(deftest vector-basics
  (let [s (store/mem-store)
        v (v2/vector s 1 2 3)]
    (is (= "vector" (v2/type v)))
    (is (= 3 (count v)))
    (is (= [1 2 3] (realize-each v)))
    (is (= 2 (v2/realize (nth v 1))))
    (is (= 2 (v2/realize (get v 1))))
    (is (= 2 (v2/realize (v 1))))
    (is (nil? (get v 99)))))

(deftest vector-conj-is-persistent
  (let [s (store/mem-store)
        v (v2/vector s 1 2 3)
        v' (conj v 4)]
    (is (= [1 2 3 4] (realize-each v')))
    (testing "original is unchanged (immutable)"
      (is (= [1 2 3] (realize-each v))))
    (testing "conj keeps the same store"
      (is (identical? (v2/dacite-store v) (v2/dacite-store v'))))))

(deftest vector-assoc-and-stack
  (let [s (store/mem-store)
        v (v2/vector s 1 2 3)]
    (is (= [1 9 3] (realize-each (assoc v 1 9))))
    (is (= 3 (v2/realize (peek v))))
    (is (= [1 2] (realize-each (pop v))))))

(deftest vector-remove-nth
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
      (is (thrown? Exception (remove-nth v 10)))
      (is (thrown? Exception (remove-nth v -1))))
    (testing "remove until empty matches successive drop from left"
      (loop [cur v expected (vec (range 10))]
        (if (zero? (count cur))
          (is (empty? expected))
          (let [cur' (remove-nth cur 0)]
            (is (= (subvec expected 1) (realize-each cur')))
            (recur cur' (subvec expected 1))))))
    (testing "large vector structural removes"
      (let [n 200
            big (apply v2/vector s (range n))
            ;; remove odd indices from the end so remaining indices stay valid
            after (reduce (fn [vv i] (remove-nth vv i))
                          big
                          (range (dec n) -1 -2))]
        (is (= (vec (range 0 n 2)) (realize-each after))))))) (deftest vector-empty-and-wrap
                                                                (let [s (store/mem-store)
                                                                      v (v2/vector s 1 2 3)
                                                                      w (v2/wrap-hash s (v2/hash v))]
                                                                  (is (= "vector" (v2/type w)))
                                                                  (is (= [1 2 3] (realize-each w)))
                                                                  (is (zero? (count (empty v))))))

;; =============================================================================
;; Strings & blobs
;; =============================================================================

(deftest string-basics
  (let [s (store/mem-store)
        str-v (v2/string s "hello")]
    (is (= "string" (v2/type str-v)))
    (is (= 5 (count str-v)))
    (testing "realize yields a lazy seq of chars; (apply str ...) rebuilds the String"
      (is (= [\h \e \l \l \o] (v2/realize str-v)))
      (is (= "hello" (apply str (v2/realize str-v)))))
    (is (= "hello" (str str-v)))
    (is (= \h (.charAt ^CharSequence str-v 0)))
    (is (= [\h \e \l \l \o] (realize-each str-v)))))

(deftest blob-basics
  (let [s (store/mem-store)
        b (v2/blob s (byte-array [1 2 3 4]))]
    (is (= "blob" (v2/type b)))
    (is (= 4 (count b)))
    (is (= [1 2 3 4] (vec (v2/realize b))))))

;; =============================================================================
;; Maps
;; =============================================================================

(deftest map-basics
  (let [s (store/mem-store)
        m (v2/map s "a" 1 "b" 2)]
    (is (= "map" (v2/type m)))
    (is (= 2 (count m)))
    (is (= 1 (v2/realize (get m "a"))))
    (is (= 2 (v2/realize (m "b"))))
    (is (nil? (get m "z")))
    (is (contains? m "a"))))

(deftest map-assoc-dissoc
  (let [s (store/mem-store)
        m (v2/map s "a" 1 "b" 2)
        m2 (assoc m "c" 3)
        m3 (dissoc m "a")]
    (is (= 3 (count m2)))
    (is (= 3 (v2/realize (get m2 "c"))))
    (testing "immutability"
      (is (= 2 (count m))))
    (is (= 1 (count m3)))
    (is (nil? (get m3 "a")))
    (is (= 2 (v2/realize (get m3 "b"))))))

(deftest map-insertion-order-independent
  (let [s (store/mem-store)]
    (is (= (v2/hash (v2/map s "a" 1 "b" 2 "c" 3))
           (v2/hash (v2/map s "c" 3 "a" 1 "b" 2))))))

;; =============================================================================
;; Sets
;; =============================================================================

(deftest set-basics
  (let [s (store/mem-store)
        st (v2/set s 1 2 3)]
    (is (= "set" (v2/type st)))
    (is (= 3 (count st)))
    (is (= #{1 2 3} (set-vals st)))
    (is (v2/set-member? st 1))
    (is (not (v2/set-member? st 9)))))

(deftest set-order-independent
  (let [s (store/mem-store)]
    (is (= (v2/hash (v2/set s 1 2 3))
           (v2/hash (v2/set s 3 1 2))))))

(deftest set-operations
  (let [s (store/mem-store)
        a (v2/set s 1 2 3)
        b (v2/set s 3 4 5)]
    (is (= #{1 2 3 4 5} (set-vals (v2/set-union a b))))
    (is (= #{3} (set-vals (v2/set-intersect a b))))
    (is (= #{1 2} (set-vals (v2/set-difference a b))))))

(deftest negative-sets
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
;; Shape independence (§3.3) — many small pushes equal one bulk build
;; =============================================================================

(deftest vector-shape-independence
  (let [s (store/mem-store)
        bulk (apply v2/vector s (range 50))
        incremental (reduce conj (v2/vector s) (range 50))]
    (is (= (v2/hash bulk) (v2/hash incremental)))
    (is (= 50 (count bulk)))))

;; =============================================================================
;; realize realizes collections as lazy seqs (deep, partial-availability-friendly)
;; =============================================================================

(deftest realize-collections-are-lazy-seqs
  (let [s (store/mem-store)]
    (testing "vector -> lazy seq of values"
      (let [r (v2/realize (v2/vector s 1 2 3))]
        (is (seq? r))
        (is (= [1 2 3] r))))
    (testing "set -> lazy seq of values"
      (is (= #{1 2 3} (set (v2/realize (v2/set s 1 2 3))))))
    (testing "blob -> lazy seq of byte values"
      (is (= [1 2 3 4] (vec (v2/realize (v2/blob s (byte-array [1 2 3 4])))))))))

(deftest realize-map-yields-realized-pairs
  (let [s (store/mem-store)
        m (v2/map s 1 10 2 20)
        r (v2/realize m)]
    (is (seq? r))
    (testing "each entry is a [k v] pair with key and value realized"
      (is (= #{[1 10] [2 20]} (set r))))))

(deftest realize-is-deep-with-nested-collections
  (let [s (store/mem-store)
        nested (v2/vector s (v2/vector s 1 2) 3)
        r (v2/realize nested)]
    (testing "sub-collections become nested seqs"
      (is (= [[1 2] 3] r))
      (is (seq? (first r))))))

(deftest realize-empty-collections-are-nil
  (let [s (store/mem-store)]
    (is (nil? (v2/realize (v2/vector s))))
    (is (nil? (v2/realize (v2/set s))))
    (is (nil? (v2/realize (v2/string s ""))))
    (is (nil? (v2/realize (v2/map s))))
    (is (nil? (v2/realize (empty (v2/vector s 1 2 3)))))))

;; =============================================================================
;; Bounded toString (partial-availability-safe debug rendering)
;; =============================================================================

(deftest bounded-toString-small-collections
  (let [s (store/mem-store)]
    (is (= "[1 2 3]" (str (v2/vector s 1 2 3))))
    (is (= #{1 2 3} (set-vals (v2/set s 1 2 3))))
    (is (re-find #"^#\{.+\}$" (str (v2/set s 1 2 3))))
    (is (= "<blob 4 bytes 0x01 02 03 04>" (str (v2/blob s (byte-array [1 2 3 4])))))))

(deftest bounded-toString-truncates-large-collections
  (let [s (store/mem-store)
        v (apply v2/vector s (range 50))]
    (is (re-find #"… \(50 total\)" (str v)))
    (binding [render/*to-string-element-limit* 5]
      (is (re-find #"\[0 1 2 3 4 … \(50 total\)" (str v))))))

(deftest bounded-toString-truncates-large-strings
  (let [s (store/mem-store)
        long-str (apply str (repeat 100 \x))
        str-v (v2/string s long-str)]
    (is (= "hello" (str (v2/string s "hello"))))
    (binding [render/*to-string-char-limit* 10]
      (let [rendered (str str-v)]
        (is (re-find #"^\"x{10}…\" \(100 chars\)$" rendered))))))

(deftest print-respects-print-length
  (let [s (store/mem-store)
        v (v2/vector s 1 2 3 4 5)]
    (is (= "[1 2 #]" (binding [*print-length* 2] (pr-str v))))))


