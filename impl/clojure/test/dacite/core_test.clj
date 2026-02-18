(ns dacite.core-test
  "Tests for the Dacite core value construction API."
  (:require [clojure.test :refer [deftest testing is]]
            [dacite.core :as d]
            [dacite.hash :as hash]))

;; =============================================================================
;; Scalar constructors (pure)
;; =============================================================================

(deftest null-value
  (testing "Null construction returns [store hash]"
    (let [[s h] (d/null {})]
      (is (map? s))
      (is (vector? h))
      (is (= 4 (count h)))
      (is (= :null (d/value-type s h)))
      (is (nil? (d/value-data s h))))))

(deftest bool-value
  (testing "Boolean construction"
    (let [[s h] (d/bool {} true)]
      (is (= :bool (d/value-type s h)))
      (is (= true (d/value-data s h))))
    (let [[s h] (d/bool {} false)]
      (is (= false (d/value-data s h))))))

(deftest integer-values
  (testing "Signed integer constructors"
    (let [[s h] (d/i8 {} 1)] (is (= :i8 (d/value-type s h))))
    (let [[s h] (d/i16 {} 1)] (is (= :i16 (d/value-type s h))))
    (let [[s h] (d/i32 {} 1)] (is (= :i32 (d/value-type s h))))
    (let [[s h] (d/i64 {} 42)]
      (is (= :i64 (d/value-type s h)))
      (is (= 42 (d/value-data s h))))))

(deftest unsigned-integer-values
  (testing "Unsigned integer constructors"
    (let [[s h] (d/u8 {} 255)] (is (= :u8 (d/value-type s h))))
    (let [[s h] (d/u16 {} 65535)] (is (= :u16 (d/value-type s h))))
    (let [[s h] (d/u32 {} 4294967295)] (is (= :u32 (d/value-type s h))))
    (let [[s h] (d/u64 {} 0)] (is (= :u64 (d/value-type s h))))))

(deftest unsigned-integer-bounds
  (testing "Unsigned integers reject out-of-bounds"
    (is (thrown? AssertionError (d/u8 {} 256)))
    (is (thrown? AssertionError (d/u8 {} -1)))
    (is (thrown? AssertionError (d/u16 {} 65536)))))

(deftest u256-value
  (testing "u256 stores 32-byte array (hash as data)"
    (let [data (hash/longs->bytes [1 2 3 4])
          [s h] (d/u256 {} data)]
      (is (= :u256 (d/value-type s h)))
      (is (= 32 (alength ^bytes (d/value-data s h)))))))

(deftest float-values
  (testing "Float constructors"
    (let [[s h] (d/f32 {} 1.5)] (is (= :f32 (d/value-type s h))))
    (let [[s h] (d/f64 {} 3.14)] (is (= :f64 (d/value-type s h))))))

(deftest char-value
  (testing "Character constructor"
    (let [[s h] (d/dacite-char {} \a)]
      (is (= :char (d/value-type s h)))
      (is (= \a (d/value-data s h))))
    (is (thrown? AssertionError (d/dacite-char {} "a")))))

(deftest scalar-generic
  (testing "Generic scalar constructor"
    (let [[s h] (d/scalar {} :u8 42)]
      (is (= :u8 (d/value-type s h)))
      (is (= 42 (d/value-data s h))))))

;; =============================================================================
;; Store threading
;; =============================================================================

(deftest store-threading
  (testing "Store accumulates values across calls"
    (let [[s h1] (d/i64 {} 1)
          [s h2] (d/i64 s 2)
          [s h3] (d/i64 s 3)]
      (is (= 1 (d/value-data s h1)))
      (is (= 2 (d/value-data s h2)))
      (is (= 3 (d/value-data s h3))))))

;; =============================================================================
;; Value accessors
;; =============================================================================

(deftest lookup-value
  (testing "Lookup returns the full [type data] pair"
    (let [[s h] (d/i64 {} 42)]
      (is (= [:i64 42] (d/lookup s h))))))

(deftest lookup-missing
  (testing "Lookup of missing hash returns nil"
    (is (nil? (d/lookup {} [0 0 0 1])))))

;; =============================================================================
;; Hashing
;; =============================================================================

(deftest hash-determinism
  (testing "Same value always produces same hash"
    (let [[_ h1] (d/i64 {} 42)
          [_ h2] (d/i64 {} 42)]
      (is (= h1 h2)))))

(deftest hash-different-values
  (testing "Different values produce different hashes"
    (let [[_ h1] (d/i64 {} 42)
          [_ h2] (d/i64 {} 43)]
      (is (not= h1 h2)))))

(deftest hash-different-types
  (testing "Same data, different types produce different hashes"
    (let [[_ h1] (d/i64 {} 1)
          [_ h2] (d/u64 {} 1)]
      (is (not= h1 h2)))))

(deftest hash-hex-format
  (testing "Hash hex is 64 characters"
    (let [[_ h] (d/i64 {} 42)]
      (is (= 64 (count (d/hash-hex h)))))))

(deftest hash-as-value-round-trip
  (testing "Store a hash as a u256 value and retrieve the bytes"
    (let [[s h1] (d/i64 {} 42)
          [s h2] (d/hash-as-value s h1)
          stored-bytes (d/value-data s h2)]
      (is (= :u256 (d/value-type s h2)))
      (is (= h1 (hash/bytes->longs stored-bytes))))))

;; =============================================================================
;; String construction
;; =============================================================================

(deftest string-construction
  (testing "String creation and access"
    (let [[s h] (d/string {} "hello")]
      (is (= "hello" (d/string-value s h))))))

(deftest string-empty
  (testing "Empty string"
    (let [[s h] (d/string {} "")]
      (is (= "" (d/string-value s h))))))

(deftest string-deterministic
  (testing "Same string produces same hash"
    (let [[_ h1] (d/string {} "hello")
          [_ h2] (d/string {} "hello")]
      (is (= h1 h2)))))

;; =============================================================================
;; Vector construction
;; =============================================================================

(deftest vector-from-refs
  (testing "Vector from refs already in store"
    (let [[s h1] (d/i64 {} 1)
          [s h2] (d/i64 s 2)
          [s h3] (d/i64 s 3)
          [s vh] (d/vector s [h1 h2 h3])]
      (is (= 3 (d/vector-count s vh)))
      (is (= h1 (d/vector-nth s vh 0)))
      (is (= h3 (d/vector-nth s vh 2))))))

(deftest vector-of-auto-coerce
  (testing "vector-of auto-coerces plain values"
    (let [[s vh] (d/vector-of {} [1 2 3])]
      (is (= 3 (d/vector-count s vh)))
      (let [first-ref (d/vector-nth s vh 0)]
        (is (= :i64 (d/value-type s first-ref)))
        (is (= 1 (d/value-data s first-ref)))))))

(deftest vector-empty
  (testing "Empty vector"
    (let [[s vh] (d/vector {} [])]
      (is (= 0 (d/vector-count s vh)))
      (is (= [] (d/vector-refs s vh))))))

(deftest vector-conj-appends
  (testing "Conj appends to vector"
    (let [[s h1] (d/i64 {} 1)
          [s h2] (d/i64 s 2)
          [s vh] (d/vector s [h1])
          [s vh2] (d/vector-conj s vh h2)]
      (is (= 1 (d/vector-count s vh)))
      (is (= 2 (d/vector-count s vh2)))
      (is (= h2 (d/vector-nth s vh2 1))))))

(deftest vector-conj-immutable
  (testing "Conj doesn't modify original"
    (let [[s h1] (d/i64 {} 1)
          [s h2] (d/i64 s 2)
          [s vh] (d/vector s [h1])
          [s _] (d/vector-conj s vh h2)]
      (is (= 1 (d/vector-count s vh))))))

(deftest vector-deterministic
  (testing "Same elements produce same hash"
    (let [[s1 h1] (d/i64 {} 1)
          [s1 h2] (d/i64 s1 2)
          [_ vh1] (d/vector s1 [h1 h2])
          [s2 h3] (d/i64 {} 1)
          [s2 h4] (d/i64 s2 2)
          [_ vh2] (d/vector s2 [h3 h4])]
      (is (= vh1 vh2)))))

(deftest vector-order-matters
  (testing "Different order produces different hash"
    (let [[s h1] (d/i64 {} 1)
          [s h2] (d/i64 s 2)
          [_ vh1] (d/vector s [h1 h2])
          [_ vh2] (d/vector s [h2 h1])]
      (is (not= vh1 vh2)))))

(deftest vector-of-mixed-types
  (testing "vector-of with mixed auto-coerced types"
    (let [[s vh] (d/vector-of {} [nil true 42 3.14])]
      (is (= 4 (d/vector-count s vh)))
      (is (= :null (d/value-type s (d/vector-nth s vh 0))))
      (is (= :bool (d/value-type s (d/vector-nth s vh 1))))
      (is (= :i64 (d/value-type s (d/vector-nth s vh 2))))
      (is (= :f64 (d/value-type s (d/vector-nth s vh 3)))))))

;; =============================================================================
;; Map construction
;; =============================================================================

(deftest map-from-refs
  (testing "Map from ref pairs"
    (let [[s kh] (d/string {} "name")
          [s vh] (d/string s "Alice")
          [s mh] (d/dacite-map s [[kh vh]])]
      (is (= 1 (d/map-count s mh)))
      (is (= vh (d/map-get s mh "name"))))))

(deftest map-of-auto-coerce
  (testing "map-of auto-coerces keys and values"
    (let [[s mh] (d/map-of {} {"name" "Alice" "age" 30})]
      (is (= 2 (d/map-count s mh)))
      (let [name-ref (d/map-get s mh "name")]
        (is (= :string (d/value-type s name-ref)))
        (is (= "Alice" (d/value-data s name-ref))))
      (let [age-ref (d/map-get s mh "age")]
        (is (= :i64 (d/value-type s age-ref)))
        (is (= 30 (d/value-data s age-ref)))))))

(deftest map-empty
  (testing "Empty map"
    (let [[s mh] (d/dacite-map {} [])]
      (is (= 0 (d/map-count s mh)))
      (is (nil? (d/map-get s mh "anything"))))))

(deftest map-assoc-adds
  (testing "Assoc adds a key-value pair"
    (let [[s kh1] (d/string {} "a")
          [s vh1] (d/i64 s 1)
          [s mh] (d/dacite-map s [[kh1 vh1]])
          [s kh2] (d/string s "b")
          [s vh2] (d/i64 s 2)
          [s mh2] (d/map-assoc s mh kh2 vh2)]
      (is (= 1 (d/map-count s mh)))
      (is (= 2 (d/map-count s mh2)))
      (is (= vh2 (d/map-get s mh2 "b"))))))

(deftest map-dissoc-removes
  (testing "Dissoc removes a key"
    (let [[s mh] (d/map-of {} {"a" 1 "b" 2})
          [s mh2] (d/map-dissoc s mh "a")]
      (is (= 1 (d/map-count s mh2)))
      (is (nil? (d/map-get s mh2 "a")))
      (is (some? (d/map-get s mh2 "b"))))))

(deftest map-dissoc-missing
  (testing "Dissoc of missing key is a no-op"
    (let [[s mh] (d/map-of {} {"a" 1})
          [s mh2] (d/map-dissoc s mh "z")]
      (is (= 1 (d/map-count s mh2))))))

(deftest map-immutable
  (testing "Map operations don't modify original"
    (let [[s kh1] (d/string {} "a")
          [s vh1] (d/i64 s 1)
          [s mh] (d/dacite-map s [[kh1 vh1]])
          [s kh2] (d/string s "b")
          [s vh2] (d/i64 s 2)
          [s _] (d/map-assoc s mh kh2 vh2)]
      (is (= 1 (d/map-count s mh))))))

(deftest map-entries-returns-pairs
  (testing "Entries returns key-ref val-ref pairs"
    (let [[s kh] (d/string {} "x")
          [s vh] (d/i64 s 10)
          [s mh] (d/dacite-map s [[kh vh]])
          entries (d/map-entries s mh)]
      (is (= 1 (count entries)))
      (let [[k v] (first entries)]
        (is (= kh k))
        (is (= vh v))))))

(deftest map-deterministic
  (testing "Same entries produce same hash"
    (let [[_s1 mh1] (d/map-of {} {"a" 1 "b" 2})
          [_s2 mh2] (d/map-of {} {"a" 1 "b" 2})]
      (is (= mh1 mh2)))))

(deftest map-insertion-order-independent
  (testing "Different insertion order produces same hash"
    (let [[_ mh1] (d/map-of {} {"a" 1 "b" 2})
          [_ mh2] (d/map-of {} {"b" 2 "a" 1})]
      (is (= mh1 mh2)))))

;; =============================================================================
;; Content equality
;; =============================================================================

(deftest dacite-equality
  (testing "Same hash = equal"
    (let [[_ h1] (d/i64 {} 42)
          [_ h2] (d/i64 {} 42)]
      (is (d/dacite= h1 h2))))
  (testing "Different hash = not equal"
    (let [[_ h1] (d/i64 {} 42)
          [_ h2] (d/i64 {} 43)]
      (is (not (d/dacite= h1 h2))))))

;; =============================================================================
;; with-store macro
;; =============================================================================

(deftest with-store-basic
  (testing "with-store manages store and returns [store last-value]"
    (let [[store result] (d/with-store [_s {}]
                           (d/i64! 42))]
      (is (map? store))
      (is (vector? result))
      (is (= :i64 (d/value-type store result))))))

(deftest with-store-multiple-values
  (testing "Multiple bang constructors accumulate in store"
    (let [[store v-hash] (d/with-store [_s {}]
                           (let [a (d/i64! 1)
                                 b (d/i64! 2)
                                 c (d/i64! 3)]
                             (d/vector! [a b c])))]
      (is (= 3 (d/vector-count store v-hash))))))

(deftest with-store-vector-of!
  (testing "vector-of! auto-coerces in store context"
    (let [[store vh] (d/with-store [_s {}]
                       (d/vector-of! [1 2 3]))]
      (is (= 3 (d/vector-count store vh)))
      (is (= :i64 (d/value-type store (d/vector-nth store vh 0)))))))

(deftest with-store-map-of!
  (testing "map-of! auto-coerces in store context"
    (let [[store mh] (d/with-store [_s {}]
                       (d/map-of! {"name" "Alice" "age" 30}))]
      (is (= 2 (d/map-count store mh)))
      (let [name-ref (d/map-get store mh "name")]
        (is (= "Alice" (d/value-data store name-ref)))))))

(deftest with-store-string!
  (testing "string! works in store context"
    (let [[store sh] (d/with-store [_s {}]
                       (d/string! "hello"))]
      (is (= "hello" (d/string-value store sh))))))

(deftest with-store-all-scalar-bangs
  (testing "All scalar bang constructors work"
    (let [[store results]
          (d/with-store [_s {}]
            {:null (d/null!)
             :bool (d/bool! true)
             :i8 (d/i8! 1) :i16 (d/i16! 1) :i32 (d/i32! 1) :i64 (d/i64! 42)
             :u8 (d/u8! 255) :u16 (d/u16! 100) :u32 (d/u32! 100) :u64 (d/u64! 100)
             :f32 (d/f32! 1.5) :f64 (d/f64! 3.14)
             :char (d/dacite-char! \a)
             :scalar (d/scalar! :i64 99)
             :u256 (d/u256! (hash/longs->bytes [1 2 3 4]))})]
      (is (= :null (d/value-type store (:null results))))
      (is (= :bool (d/value-type store (:bool results))))
      (is (= :i64 (d/value-type store (:i64 results))))
      (is (= :u8 (d/value-type store (:u8 results))))
      (is (= :f64 (d/value-type store (:f64 results))))
      (is (= :char (d/value-type store (:char results))))
      (is (= :u256 (d/value-type store (:u256 results)))))))
