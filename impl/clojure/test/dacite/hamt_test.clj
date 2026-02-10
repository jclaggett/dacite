(ns dacite.hamt-test
  "Tests for Dacite HAMT implementation - 100% coverage via public API."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dacite.hamt :as hamt]
            [dacite.hash :as hash]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn insert-kv
  "Helper: add a key-value pair to a HAMT.
   key and value are dacite values like [:string \"name\"].
   key-hash is used for HAMT navigation."
  [h key-value val-value key-hash]
  (let [[m root] h
        [m1 k-ref] (hamt/add-value m key-value)
        [m2 v-ref] (hamt/add-value m1 val-value)]
    (hamt/assoc-val [m2 root] key-hash k-ref v-ref)))

(defn insert-string-kv
  "Helper: insert a string key -> integer value pair.
   Uses sha256 of the key string for HAMT navigation."
  [h key-str value]
  (let [key-hash (hash/sha256-str key-str)]
    (insert-kv h [:string key-str] [:i64 value] key-hash)))

(defn get-string-val
  "Helper: look up by string key, return the actual value from dacite-map."
  [h key-str]
  (let [[dacite-map _] h
        key-hash (hash/sha256-str key-str)
        val-ref (hamt/get-val h key-hash)]
    (when val-ref
      (get dacite-map val-ref))))

(defn dissoc-string
  "Helper: remove by string key."
  [h key-str]
  (hamt/dissoc-val h (hash/sha256-str key-str)))

;; =============================================================================
;; Basic operations
;; =============================================================================

(deftest test-empty-hamt
  (testing "empty HAMT"
    (let [h (hamt/hamt)]
      (is (= 0 (hamt/hamt-count h)))
      (is (= 0 (hamt/hamt-size-bytes h)))
      (is (nil? (hamt/get-val h (hash/sha256-str "anything"))))
      (is (= [] (hamt/entries h))))))

(deftest test-single-entry
  (testing "single entry"
    (let [h (insert-string-kv (hamt/hamt) "name" 42)]
      (is (= 1 (hamt/hamt-count h)))
      (is (pos? (hamt/hamt-size-bytes h)))
      (is (= [:i64 42] (get-string-val h "name")))
      (is (nil? (get-string-val h "other"))))))

(deftest test-multiple-entries
  (testing "multiple entries"
    (let [h (-> (hamt/hamt)
                (insert-string-kv "a" 1)
                (insert-string-kv "b" 2)
                (insert-string-kv "c" 3))]
      (is (= 3 (hamt/hamt-count h)))
      (is (= [:i64 1] (get-string-val h "a")))
      (is (= [:i64 2] (get-string-val h "b")))
      (is (= [:i64 3] (get-string-val h "c"))))))

(deftest test-overwrite
  (testing "overwriting existing key"
    (let [h1 (insert-string-kv (hamt/hamt) "key" 1)
          h2 (insert-string-kv h1 "key" 2)]
      (is (= [:i64 1] (get-string-val h1 "key")))
      (is (= [:i64 2] (get-string-val h2 "key")))
      (is (= 1 (hamt/hamt-count h1)))
      (is (= 1 (hamt/hamt-count h2))))))

(deftest test-delete
  (testing "delete entry"
    (let [h1 (-> (hamt/hamt)
                 (insert-string-kv "a" 1)
                 (insert-string-kv "b" 2)
                 (insert-string-kv "c" 3))
          h2 (dissoc-string h1 "b")]
      (is (= 3 (hamt/hamt-count h1)))
      (is (= 2 (hamt/hamt-count h2)))
      (is (= [:i64 1] (get-string-val h2 "a")))
      (is (nil? (get-string-val h2 "b")))
      (is (= [:i64 3] (get-string-val h2 "c"))))))

(deftest test-delete-nonexistent
  (testing "delete non-existent key"
    (let [h1 (insert-string-kv (hamt/hamt) "a" 1)
          h2 (dissoc-string h1 "b")]
      (is (= 1 (hamt/hamt-count h2)))
      (is (= [:i64 1] (get-string-val h2 "a"))))))

(deftest test-delete-to-empty
  (testing "delete last entry"
    (let [h1 (insert-string-kv (hamt/hamt) "only" 1)
          h2 (dissoc-string h1 "only")]
      (is (= 0 (hamt/hamt-count h2)))
      (is (nil? (get-string-val h2 "only"))))))

(deftest test-delete-from-empty
  (testing "delete from empty HAMT"
    (let [h (dissoc-string (hamt/hamt) "nothing")]
      (is (= 0 (hamt/hamt-count h))))))

;; =============================================================================
;; Structural sharing (persistence)
;; =============================================================================

(deftest test-persistence
  (testing "structural sharing"
    (let [h1 (-> (hamt/hamt)
                 (insert-string-kv "a" 1)
                 (insert-string-kv "b" 2))
          h2 (insert-string-kv h1 "c" 3)]
      (is (= 2 (hamt/hamt-count h1)))
      (is (= 3 (hamt/hamt-count h2)))
      (is (nil? (get-string-val h1 "c")))
      (is (= [:i64 3] (get-string-val h2 "c")))
      (is (= [:i64 1] (get-string-val h1 "a")))
      (is (= [:i64 1] (get-string-val h2 "a"))))))

;; =============================================================================
;; Entries
;; =============================================================================

(deftest test-entries
  (testing "entries returns all ref pairs"
    (let [h (-> (hamt/hamt)
                (insert-string-kv "x" 1)
                (insert-string-kv "y" 2)
                (insert-string-kv "z" 3))
          [dacite-map _] h
          es (hamt/entries h)
          ;; Resolve refs to actual values
          resolved (set (map (fn [[k-ref v-ref]]
                               [(get dacite-map k-ref) (get dacite-map v-ref)])
                             es))]
      (is (= 3 (count es)))
      (is (contains? resolved [[:string "x"] [:i64 1]]))
      (is (contains? resolved [[:string "y"] [:i64 2]]))
      (is (contains? resolved [[:string "z"] [:i64 3]])))))

;; =============================================================================
;; Large maps (exercise tree depth)
;; =============================================================================

(deftest test-large-map
  (testing "map with 1000 entries"
    (let [h (reduce (fn [h i]
                      (insert-string-kv h (str "key" i) i))
                    (hamt/hamt)
                    (range 1000))]
      (is (= 1000 (hamt/hamt-count h)))
      (is (= [:i64 0] (get-string-val h "key0")))
      (is (= [:i64 500] (get-string-val h "key500")))
      (is (= [:i64 999] (get-string-val h "key999")))
      (is (nil? (get-string-val h "key1000"))))))

;; =============================================================================
;; Hash chunk extraction
;; =============================================================================

(deftest test-hash-chunk
  (testing "hash chunk extraction - same long"
    (let [h [0x1234567890ABCDEF 0xFEDCBA0987654321 0 0]]
      ;; Level 0: bits 63-59 of first long
      ;; 0x12 = 0001 0010, bits 63-59: 00010 = 2
      (is (= 2 (hamt/hash-chunk h 0)))
      ;; Level 1: bits 58-54
      ;; 0x1234 = 0001 0010 0011 0100, bits 58-54: 01000 = 8
      (is (= 8 (hamt/hash-chunk h 1)))))

  (testing "hash chunk extraction - cross-long boundary"
    ;; Level 12: bit-offset = 60, which spans longs 0 and 1
    ;; bits 3-0 of long[0] + bit 63 of long[1]
    (let [h [0x000000000000000F (unchecked-long 0x8000000000000000) 0 0]]
      ;; long[0] low 4 bits = 1111, long[1] high bit = 1
      ;; chunk = (1111 << 1) | 1 = 11111 = 31
      (is (= 31 (hamt/hash-chunk h 12)))))

  (testing "hash chunk at various levels"
    ;; Ensure all levels 0-51 return values in [0, 31]
    (let [h (hash/sha256-str "test hash chunks")]
      (doseq [level (range 52)]
        (let [chunk (hamt/hash-chunk h level)]
          (is (<= 0 chunk 31) (str "Level " level " chunk out of range: " chunk)))))))

;; =============================================================================
;; Measure accumulation
;; =============================================================================

(deftest test-measure-accumulation
  (testing "size-bytes reflects key + value sizes"
    (let [h (-> (hamt/hamt)
                (insert-string-kv "a" 1)
                (insert-string-kv "b" 2))]
      ;; Each entry has a string key + i64 value
      ;; size should be positive and reflect both keys and values
      (is (pos? (hamt/hamt-size-bytes h)))
      (is (= 2 (hamt/hamt-count h))))))

;; =============================================================================
;; Pure data structure
;; =============================================================================

(deftest test-pure-structure
  (testing "HAMT is [dacite-map, root-hash] tuple"
    (let [h (hamt/hamt)]
      (is (vector? h))
      (is (= 2 (count h)))
      (is (map? (first h)))
      (is (vector? (second h)))
      (is (= 4 (count (second h))))))

  (testing "add-value returns [map, hash]"
    (let [[m0 _] (hamt/hamt)
          [m1 ref] (hamt/add-value m0 [:i64 42])]
      (is (map? m1))
      (is (vector? ref))
      (is (= 4 (count ref)))
      (is (= [:i64 42] (get m1 ref)))))

  (testing "identical inputs produce identical hashes"
    (let [[_ h1] (hamt/hamt)
          [_ h2] (hamt/hamt)]
      (is (= h1 h2)))))

;; =============================================================================
;; Delete with bitmap collapse
;; =============================================================================

(deftest test-delete-collapse
  (testing "deleting from 2-entry bitmap collapses to entry"
    (let [h1 (-> (hamt/hamt)
                 (insert-string-kv "a" 1)
                 (insert-string-kv "b" 2))
          h2 (dissoc-string h1 "a")]
      (is (= 1 (hamt/hamt-count h2)))
      (is (= [:i64 2] (get-string-val h2 "b")))
      (is (nil? (get-string-val h2 "a"))))))

(deftest test-delete-deep
  (testing "delete from a deeper tree structure"
    ;; Insert many entries to ensure multi-level tree, then delete from interior
    (let [h (reduce (fn [h i] (insert-string-kv h (str "k" i) i))
                    (hamt/hamt)
                    (range 100))
          ;; Delete from the middle
          h2 (dissoc-string h "k50")
          ;; Delete several more
          h3 (reduce (fn [h i] (dissoc-string h (str "k" i)))
                     h2
                     (range 10 20))]
      (is (= 99 (hamt/hamt-count h2)))
      (is (nil? (get-string-val h2 "k50")))
      (is (= [:i64 49] (get-string-val h2 "k49")))
      (is (= [:i64 51] (get-string-val h2 "k51")))
      (is (= 89 (hamt/hamt-count h3)))
      ;; Verify remaining entries still accessible
      (is (= [:i64 0] (get-string-val h3 "k0")))
      (is (= [:i64 99] (get-string-val h3 "k99"))))))

;; =============================================================================
;; Property tests
;; =============================================================================

(def gen-key-str (gen/such-that #(< (count %) 50) gen/string-alphanumeric))
(def gen-val gen/small-integer)

(defspec lookup-after-insert 100
  (prop/for-all [k gen-key-str
                 v gen-val]
                (let [h (insert-string-kv (hamt/hamt) k v)]
                  (= [:i64 v] (get-string-val h k)))))

(defspec count-increases-on-new-key 100
  (prop/for-all [k1 gen-key-str
                 k2 gen-key-str
                 v1 gen-val
                 v2 gen-val]
                (if (= k1 k2)
                  true
                  (let [h1 (insert-string-kv (hamt/hamt) k1 v1)
                        h2 (insert-string-kv h1 k2 v2)]
                    (= (inc (hamt/hamt-count h1)) (hamt/hamt-count h2))))))

(defspec count-unchanged-on-same-key 100
  (prop/for-all [k gen-key-str
                 v1 gen-val
                 v2 gen-val]
                (let [h1 (insert-string-kv (hamt/hamt) k v1)
                      h2 (insert-string-kv h1 k v2)]
                  (= (hamt/hamt-count h1) (hamt/hamt-count h2)))))

(defspec delete-removes-key 100
  (prop/for-all [k gen-key-str
                 v gen-val]
                (let [h1 (insert-string-kv (hamt/hamt) k v)
                      h2 (dissoc-string h1 k)]
                  (nil? (get-string-val h2 k)))))

(defspec insert-multiple-all-present 50
  (prop/for-all [kvs (gen/vector (gen/tuple gen-key-str gen-val) 1 50)]
                (let [h (reduce (fn [h [k v]] (insert-string-kv h k v))
                                (hamt/hamt)
                                kvs)
                      expected (into {} kvs)]
                  (every? (fn [[k v]] (= [:i64 v] (get-string-val h k))) expected))))

(comment
  (clojure.test/run-tests))
