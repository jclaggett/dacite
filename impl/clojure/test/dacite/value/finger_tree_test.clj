(ns dacite.value.finger-tree-test
  "Dual-read and baseline tests for store-backed finger trees.

   PR1 (ft-single elision): ops accept bare value hashes as 1-elem roots and
   as digit children, while writers still emit ft/single."
  (:require [clojure.test :refer [deftest is testing]]
            [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.value.finger-tree :as ft]
            [dacite.value.types :as types]))

(defn- put-i64
  "Store an i64 and return its value hash."
  [st n]
  (v/dacite-hash (v/i64-with-store st n)))

(defn- measure-combine [m1 m2]
  {:count (+ (:count m1) (:count m2))
   :size-bytes (+ (:size-bytes m1) (:size-bytes m2))
   :elements-fuse (hash/unchecked-fuse (:elements-fuse m1) (:elements-fuse m2))})

(defn- leaf-measure [h size-bytes]
  {:count 1 :size-bytes size-bytes :elements-fuse h})

(defn- put-digit-of-leaves!
  "Plant an ft/digit whose children are bare value hashes (no ft/single)."
  [st leaf-hs]
  (let [vhs (vec leaf-hs)
        ms (mapv (fn [h]
                   (leaf-measure h (types/dacite-size (store/s-get st h))))
                 vhs)
        m (reduce measure-combine
                  {:count 0 :size-bytes 0 :elements-fuse host/zero-hash}
                  ms)
        dh (types/node-hash "ft/digit" (:elements-fuse m))]
    (store/s-put st dh ["ft/digit" {:children vhs :measure m}])
    dh))

(deftest empty-tree
  (let [st (store/mem-store)
        root (ft/ft-empty st)]
    (is (ft/ft-empty? st root))
    (is (zero? (ft/ft-count st root)))
    (is (nil? (ft/ft-first st root)))
    (is (empty? (ft/ft-seq st root)))))

(deftest conj-does-not-write-singles
  (let [st (store/mem-store)
        a (put-i64 st 1)
        b (put-i64 st 2)
        r0 (ft/ft-empty st)
        r1 (ft/ft-conj-right st r0 a)
        r2 (ft/ft-conj-right st r1 b)
        snap (store/s-snapshot st)
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
    (is (empty? singles) "PR2: fresh trees write no ft/single")))

(deftest vector-value-hash-stable-without-singles
  ;; Collection value hash depends only on leaf elements_fuse, not spine adapters.
  (let [st (store/mem-store)
        vec-v (v/vector-with-store st 1 2 3)
        h (v/dacite-hash vec-v)
        ;; Rebuild via FT of the same leaf hashes — same collection hash
        leaves (mapv #(put-i64 st %) [1 2 3])
        root (ft/ft-from-value-hashes st leaves)
        h2 (types/value-hash "vector" (ft/ft-elements-fuse st root))]
    (is (= h h2))
    (is (empty? (filter (fn [[_ e]] (= "ft/single" (types/entry-type e)))
                        (store/s-snapshot st))))))

(deftest dual-read-bare-leaf-root
  (testing "1-element tree root is a bare value hash"
    (let [st (store/mem-store)
          vh (put-i64 st 42)]
      (is (= 1 (ft/ft-count st vh)))
      (is (= 8 (ft/ft-size-bytes st vh)))
      (is (= vh (ft/ft-elements-fuse st vh)))
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

(deftest dual-read-digit-of-bare-leaves
  (testing "ft/digit with bare value children (no singles)"
    (let [st (store/mem-store)
          a (put-i64 st 10)
          b (put-i64 st 20)
          c (put-i64 st 30)
          dh (put-digit-of-leaves! st [a b c])]
      (is (= [a b c] (vec (ft/ft-leaves st dh))))
      ;; Build a deep tree whose left digit is bare-leaf digit by hand is heavy;
      ;; exercise leaves + measure via digit alone and via from-value-hashes path.
      (let [root (ft/ft-from-value-hashes st [a b c])]
        (is (= [a b c] (vec (ft/ft-seq st root))))
        (is (= a (ft/ft-nth st root 0)))
        (is (= c (ft/ft-nth st root 2)))))))

(deftest dual-read-legacy-single-still-works
  (let [st (store/mem-store)
        vh (put-i64 st 7)
        single (ft/ft-single-from-value-hash st vh)]
    (is (= 1 (ft/ft-count st single)))
    (is (= vh (ft/ft-first st single)))
    (is (= vh (ft/ft-nth st single 0)))
    (is (= [vh] (vec (ft/ft-seq st single))))
    (is (= [vh] (vec (ft/ft-leaves st single))))
    (is (ft/ft-empty? st (ft/ft-remove-nth st single 0)))))

(deftest large-conj-and-nth
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
    (is (= vhs (vec (ft/ft-seq st root))))))

(deftest concat-and-rest
  (let [st (store/mem-store)
        as (mapv #(put-i64 st %) [1 2 3])
        bs (mapv #(put-i64 st %) [4 5])
        ra (ft/ft-from-value-hashes st as)
        rb (ft/ft-from-value-hashes st bs)
        rc (ft/ft-concat st ra rb)]
    (is (= (into as bs) (vec (ft/ft-seq st rc))))
    (is (= (rest as) (vec (ft/ft-seq st (ft/ft-rest st ra)))))
    (is (= (pop (vec as)) (vec (ft/ft-seq st (ft/ft-butlast st ra)))))))

(deftest remove-nth-middle
  (let [st (store/mem-store)
        vhs (mapv #(put-i64 st %) (range 10))
        root (ft/ft-from-value-hashes st vhs)
        mid (ft/ft-remove-nth st root 4)]
    (is (= (concat (range 4) (range 5 10))
           (map (fn [h] (second (store/s-get st h))) (ft/ft-seq st mid))))))
