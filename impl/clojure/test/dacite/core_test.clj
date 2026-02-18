(ns dacite.core-test
  "Tests for the Dacite core value construction API."
  (:require [clojure.test :refer [deftest testing is]]
            [dacite.core :as d]))

;; =============================================================================
;; Scalar constructors
;; =============================================================================

(deftest null-value
  (testing "Null construction"
    (let [v (d/null)]
      (is (= :null (d/value-type v)))
      (is (nil? (d/value-data v))))))

(deftest bool-value
  (testing "Boolean construction"
    (is (= [:bool true] (d/bool true)))
    (is (= [:bool false] (d/bool false)))))

(deftest integer-values
  (testing "Signed integer constructors"
    (is (= :i8 (d/value-type (d/i8 1))))
    (is (= :i16 (d/value-type (d/i16 1))))
    (is (= :i32 (d/value-type (d/i32 1))))
    (is (= :i64 (d/value-type (d/i64 42))))
    (is (= 42 (d/value-data (d/i64 42))))))

(deftest unsigned-integer-values
  (testing "Unsigned integer constructors"
    (is (= :u8 (d/value-type (d/u8 255))))
    (is (= :u16 (d/value-type (d/u16 65535))))
    (is (= :u32 (d/value-type (d/u32 4294967295))))
    (is (= :u64 (d/value-type (d/u64 0))))))

(deftest unsigned-integer-bounds
  (testing "Unsigned integers reject out-of-bounds"
    (is (thrown? AssertionError (d/u8 256)))
    (is (thrown? AssertionError (d/u8 -1)))
    (is (thrown? AssertionError (d/u16 65536)))))

(deftest float-values
  (testing "Float constructors"
    (is (= :f32 (d/value-type (d/f32 1.5))))
    (is (= :f64 (d/value-type (d/f64 3.14))))))

(deftest char-value
  (testing "Character constructor"
    (is (= [:char \a] (d/dacite-char \a)))
    (is (thrown? AssertionError (d/dacite-char "a")))))

(deftest scalar-generic
  (testing "Generic scalar constructor"
    (is (= [:u8 42] (d/scalar :u8 42)))
    (is (= [:i64 99] (d/scalar :i64 99)))))

;; =============================================================================
;; Value accessors
;; =============================================================================

(deftest value-accessors
  (testing "Type and data extraction"
    (is (= :i64 (d/value-type (d/i64 42))))
    (is (= 42 (d/value-data (d/i64 42))))
    (is (= :bool (d/value-type (d/bool true))))
    (is (= true (d/value-data (d/bool true))))))

;; =============================================================================
;; Hashing
;; =============================================================================

(deftest value-hashing
  (testing "Hash of scalar values"
    (let [h (d/value-hash (d/i64 42))]
      (is (clojure.core/vector? h))
      (is (= 4 (count h)))
      (is (every? integer? h)))))

(deftest value-hash-hex-format
  (testing "Hex hash is 64 characters"
    (let [hex (d/value-hash-hex (d/i64 42))]
      (is (string? hex))
      (is (= 64 (count hex))))))

(deftest value-hash-determinism
  (testing "Same value always produces same hash"
    (is (= (d/value-hash (d/i64 42))
           (d/value-hash (d/i64 42))))
    (is (not= (d/value-hash (d/i64 42))
              (d/value-hash (d/i64 43))))))

(deftest different-types-different-hashes
  (testing "Same data but different types produce different hashes"
    (is (not= (d/value-hash (d/i64 1))
              (d/value-hash (d/u64 1))))))

;; =============================================================================
;; String construction
;; =============================================================================

(deftest string-construction
  (testing "String creation and access"
    (let [s (d/string "hello")]
      (is (= :string (:type s)))
      (is (= "hello" (d/string-value s)))
      (is (= 5 (d/string-count s))))))

(deftest string-empty
  (testing "Empty string"
    (let [s (d/string "")]
      (is (= "" (d/string-value s)))
      (is (= 0 (d/string-count s))))))

(deftest string-has-hash
  (testing "String has a hash"
    (let [s (d/string "hello")]
      (is (some? (:hash s)))
      (is (= 4 (count (:hash s)))))))

(deftest string-deterministic-hash
  (testing "Same string produces same hash"
    (is (= (:hash (d/string "hello"))
           (:hash (d/string "hello"))))))

;; =============================================================================
;; Vector construction
;; =============================================================================

(deftest vector-from-integers
  (testing "Vector from plain integers (auto-coerced)"
    (let [v (d/vector [1 2 3])]
      (is (= :vector (:type v)))
      (is (= 3 (d/vector-count v)))
      (is (= [:i64 1] (d/vector-nth v 0)))
      (is (= [:i64 3] (d/vector-nth v 2))))))

(deftest vector-from-typed-values
  (testing "Vector from explicit typed values"
    (let [v (d/vector [[:i64 1] [:string "two"] [:bool true]])]
      (is (= 3 (d/vector-count v)))
      (is (= [:i64 1] (d/vector-nth v 0)))
      (is (= [:string "two"] (d/vector-nth v 1)))
      (is (= [:bool true] (d/vector-nth v 2))))))

(deftest vector-empty
  (testing "Empty vector"
    (let [v (d/vector [])]
      (is (= 0 (d/vector-count v)))
      (is (= [] (d/vector-elements v))))))

(deftest vector-conj-appends
  (testing "Conj appends to vector"
    (let [v (d/vector [1 2])
          v2 (d/vector-conj v 3)]
      (is (= 2 (d/vector-count v)))
      (is (= 3 (d/vector-count v2)))
      (is (= [:i64 3] (d/vector-nth v2 2))))))

(deftest vector-conj-immutable
  (testing "Conj doesn't modify original"
    (let [v (d/vector [1])
          _ (d/vector-conj v 2)]
      (is (= 1 (d/vector-count v))))))

(deftest vector-auto-coercion
  (testing "Mixed auto-coercion"
    (let [v (d/vector [nil true 42 3.14])]
      (is (= [:null nil] (d/vector-nth v 0)))
      (is (= [:bool true] (d/vector-nth v 1)))
      (is (= [:i64 42] (d/vector-nth v 2)))
      (is (= :f64 (d/value-type (d/vector-nth v 3)))))))

(deftest vector-deterministic-hash
  (testing "Same elements produce same hash"
    (is (= (:hash (d/vector [1 2 3]))
           (:hash (d/vector [1 2 3]))))))

(deftest vector-order-matters
  (testing "Different order produces different hash"
    (is (not= (:hash (d/vector [1 2 3]))
              (:hash (d/vector [3 2 1]))))))

;; =============================================================================
;; Map construction
;; =============================================================================

(deftest map-from-string-keys
  (testing "Map with string keys"
    (let [m (d/dacite-map {"name" "Alice" "age" 30})]
      (is (= :map (:type m)))
      (is (= 2 (d/map-count m)))
      (is (= [:string "Alice"] (d/map-get m "name")))
      (is (= [:i64 30] (d/map-get m "age"))))))

(deftest map-empty
  (testing "Empty map"
    (let [m (d/dacite-map {})]
      (is (= 0 (d/map-count m)))
      (is (nil? (d/map-get m "anything"))))))

(deftest map-assoc-adds
  (testing "Assoc adds a key-value pair"
    (let [m (d/dacite-map {"a" 1})
          m2 (d/map-assoc m "b" 2)]
      (is (= 1 (d/map-count m)))
      (is (= 2 (d/map-count m2)))
      (is (= [:i64 2] (d/map-get m2 "b"))))))

(deftest map-assoc-replaces
  (testing "Assoc replaces existing key"
    (let [m (d/dacite-map {"x" 1})
          m2 (d/map-assoc m "x" 2)]
      (is (= 1 (d/map-count m2)))
      (is (= [:i64 2] (d/map-get m2 "x"))))))

(deftest map-dissoc-removes
  (testing "Dissoc removes a key"
    (let [m (d/dacite-map {"a" 1 "b" 2})
          m2 (d/map-dissoc m "a")]
      (is (= 1 (d/map-count m2)))
      (is (nil? (d/map-get m2 "a")))
      (is (= [:i64 2] (d/map-get m2 "b"))))))

(deftest map-dissoc-missing-key
  (testing "Dissoc of missing key is a no-op"
    (let [m (d/dacite-map {"a" 1})
          m2 (d/map-dissoc m "z")]
      (is (= 1 (d/map-count m2))))))

(deftest map-immutable
  (testing "Map operations don't modify original"
    (let [m (d/dacite-map {"a" 1})]
      (d/map-assoc m "b" 2)
      (is (= 1 (d/map-count m))))))

(deftest map-entries-returns-pairs
  (testing "Entries returns key-value pairs"
    (let [m (d/dacite-map {"x" 10})
          entries (d/map-entries m)]
      (is (= 1 (count entries)))
      (let [[k v] (first entries)]
        (is (= [:string "x"] k))
        (is (= [:i64 10] v))))))

(deftest map-deterministic-hash
  (testing "Same entries produce same hash"
    (is (= (:hash (d/dacite-map {"a" 1 "b" 2}))
           (:hash (d/dacite-map {"a" 1 "b" 2}))))))

(deftest map-insertion-order-independent
  (testing "Different insertion order produces same hash"
    (is (= (:hash (d/dacite-map {"a" 1 "b" 2}))
           (:hash (d/dacite-map {"b" 2 "a" 1}))))))

;; =============================================================================
;; Generic hashing
;; =============================================================================

(deftest dacite-hash-scalars
  (testing "dacite-hash works on scalar values"
    (let [h (d/dacite-hash (d/i64 42))]
      (is (= 4 (count h))))))

(deftest dacite-hash-collections
  (testing "dacite-hash works on collections"
    (is (some? (d/dacite-hash (d/string "hello"))))
    (is (some? (d/dacite-hash (d/vector [1 2]))))
    (is (some? (d/dacite-hash (d/dacite-map {"a" 1}))))))

(deftest dacite-hash-hex-format
  (testing "dacite-hash-hex returns 64-char hex"
    (is (= 64 (count (d/dacite-hash-hex (d/vector [1 2 3])))))))

;; =============================================================================
;; Content equality
;; =============================================================================

(deftest dacite-equality-scalars
  (testing "Scalar equality by content"
    (is (d/dacite= (d/i64 42) (d/i64 42)))
    (is (not (d/dacite= (d/i64 42) (d/i64 43))))))

(deftest dacite-equality-collections
  (testing "Collection equality by content"
    (is (d/dacite= (d/vector [1 2 3]) (d/vector [1 2 3])))
    (is (not (d/dacite= (d/vector [1 2]) (d/vector [2 1]))))))

(deftest dacite-equality-cross-type
  (testing "Different types are not equal"
    (is (not (d/dacite= (d/i64 1) (d/u64 1))))))
