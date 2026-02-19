(ns dacite.core-test
  "Tests for the Dacite core value construction API."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [dacite.core :as d]
            [dacite.hash :as hash]))

;; Reset global store before each test
(use-fixtures :each (fn [f] (d/reset-store!) (f)))

;; =============================================================================
;; Integrated API — scalars
;; =============================================================================

(deftest null-integrated
  (testing "Null construction"
    (let [v (d/null)]
      (is (instance? dacite.core.DaciteScalar v))
      (is (nil? @v)))))

(deftest bool-integrated
  (testing "Boolean construction"
    (is (= true @(d/bool true)))
    (is (= false @(d/bool false)))))

(deftest integer-integrated
  (testing "Integer constructors"
    (is (= 42 @(d/i64 42)))
    (is (= 1 @(d/i8 1)))
    (is (= 1 @(d/i16 1)))
    (is (= 1 @(d/i32 1)))))

(deftest unsigned-integrated
  (testing "Unsigned integer constructors"
    (is (= 255 @(d/u8 255)))
    (is (= 65535 @(d/u16 65535)))
    (is (= 4294967295 @(d/u32 4294967295)))
    (is (= 0 @(d/u64 0)))))

(deftest unsigned-bounds
  (testing "Unsigned integers reject out-of-bounds"
    (is (thrown? AssertionError (d/u8 256)))
    (is (thrown? AssertionError (d/u8 -1)))
    (is (thrown? AssertionError (d/u16 65536)))))

(deftest u256-integrated
  (testing "u256 for hash-as-data"
    (let [data (hash/longs->bytes [1 2 3 4])
          v (d/u256 data)]
      (is (= :u256 (d/scalar-type v)))
      (is (= 32 (alength ^bytes @v))))))

(deftest float-integrated
  (testing "Float constructors"
    (is (= :f32 (d/scalar-type (d/f32 1.5))))
    (is (= :f64 (d/scalar-type (d/f64 3.14))))))

(deftest char-integrated
  (testing "Character constructor"
    (is (= \a @(d/dacite-char \a)))
    (is (thrown? AssertionError (d/dacite-char "a")))))

(deftest scalar-equality
  (testing "Same value = equal"
    (is (= (d/i64 42) (d/i64 42))))
  (testing "Different value = not equal"
    (is (not= (d/i64 42) (d/i64 43)))))

(deftest scalar-type-accessor
  (testing "scalar-type returns type keyword"
    (is (= :i64 (d/scalar-type (d/i64 42))))
    (is (= :null (d/scalar-type (d/null))))))

;; =============================================================================
;; Integrated API — strings
;; =============================================================================

(deftest str-integrated
  (testing "String construction"
    (let [s (d/str "hello")]
      (is (instance? dacite.core.DaciteString s))
      (is (= "hello" @s))
      (is (= "hello" (clojure.core/str s))))))

(deftest str-count
  (testing "String supports count"
    (is (= 5 (count (d/str "hello"))))
    (is (= 0 (count (d/str ""))))))

(deftest str-equality
  (testing "Same string = equal"
    (is (= (d/str "hello") (d/str "hello")))))

(deftest str-char-sequence
  (testing "CharSequence interface"
    (let [s (d/str "hello")]
      (is (= \h (.charAt s 0)))
      (is (= "ell" (clojure.core/str (.subSequence s 1 4)))))))

(deftest str-seq
  (testing "String is seqable"
    (is (= [\a \b \c] (seq (d/str "abc"))))))

;; =============================================================================
;; Integrated API — vectors
;; =============================================================================

(deftest vec-integrated
  (testing "Vector construction"
    (let [v (d/vec [1 2 3])]
      (is (instance? dacite.core.DaciteVector v))
      (is (= 3 (count v))))))

(deftest vec-nth
  (testing "nth returns DaciteScalar"
    (let [v (d/vec [10 20 30])]
      (is (instance? dacite.core.DaciteScalar (nth v 0)))
      (is (= 10 @(nth v 0)))
      (is (= 30 @(nth v 2))))))

(deftest vec-conj
  (testing "conj appends"
    (let [v (d/vec [1 2])
          v2 (conj v 3)]
      (is (= 3 (count v2)))
      (is (= 3 @(nth v2 2))))))

(deftest vec-immutable
  (testing "conj doesn't modify original"
    (let [v (d/vec [1 2])
          _v2 (conj v 3)]
      (is (= 2 (count v))))))

(deftest vec-empty
  (testing "Empty vector"
    (let [v (d/vec [])]
      (is (= 0 (count v)))
      (is (nil? (seq v))))))

(deftest vec-ifn
  (testing "Vector as function"
    (is (= 20 @((d/vec [10 20 30]) 1)))))

(deftest vec-ilookup
  (testing "get works on vector"
    (let [v (d/vec [10 20 30])]
      (is (= 20 @(get v 1)))
      (is (nil? (get v 99))))))

(deftest vec-peek-pop
  (testing "peek and pop"
    (let [v (d/vec [1 2 3])]
      (is (= 3 @(peek v)))
      (let [p (pop v)]
        (is (= 2 (count p)))
        (is (= 2 @(peek p)))))))

(deftest vec-assoc
  (testing "assoc replaces element"
    (let [v (assoc (d/vec [1 2 3]) 1 99)]
      (is (= 3 (count v)))
      (is (= 99 @(nth v 1))))))

(deftest vec-seq-returns-scalars
  (testing "seq returns DaciteScalar elements"
    (let [s (seq (d/vec [10 20 30]))]
      (is (= 3 (clojure.core/count s)))
      (is (every? #(instance? dacite.core.DaciteScalar %) s))
      (is (= [10 20 30] (mapv deref s))))))

(deftest vec-contains-key
  (testing "containsKey on vector"
    (let [v (d/vec [10 20])]
      (is (.containsKey v 0))
      (is (.containsKey v 1))
      (is (not (.containsKey v 2))))))

(deftest vec-equality
  (testing "Same elements = equal"
    (is (= (d/vec [1 2 3]) (d/vec [1 2 3]))))
  (testing "Different order = not equal"
    (is (not= (d/vec [1 2]) (d/vec [2 1])))))

(deftest vec-toString
  (testing "toString"
    (is (= "[1 2 3]" (clojure.core/str (d/vec [1 2 3]))))))

(deftest vec-mixed-types
  (testing "Mixed auto-coerced types"
    (let [v (d/vec [nil true 42])]
      (is (nil? @(nth v 0)))
      (is (= true @(nth v 1)))
      (is (= 42 @(nth v 2))))))

(deftest vec-nested
  (testing "Vectors can contain vectors"
    (let [inner (d/vec [1 2])
          outer (d/vec-of-refs [(d/unwrap-hash inner)])]
      (is (= 1 (count outer)))
      (let [inner' (nth outer 0)]
        (is (instance? dacite.core.DaciteVector inner'))
        (is (= 2 (count inner')))))))

;; =============================================================================
;; Integrated API — maps
;; =============================================================================

(deftest hash-map-integrated
  (testing "Map construction"
    (let [m (d/hash-map "name" "Alice" "age" 30)]
      (is (instance? dacite.core.DaciteMap m))
      (is (= 2 (count m))))))

(deftest hash-map-get
  (testing "get works on map"
    (let [m (d/hash-map "name" "Alice" "age" 30)]
      (let [name-val (get m "name")]
        (is (instance? dacite.core.DaciteString name-val))
        (is (= "Alice" @name-val)))
      (let [age-val (get m "age")]
        (is (instance? dacite.core.DaciteScalar age-val))
        (is (= 30 @age-val))))))

(deftest hash-map-get-missing
  (testing "get returns nil for missing key"
    (is (nil? (get (d/hash-map "a" 1) "missing")))))

(deftest hash-map-get-not-found
  (testing "get returns not-found for missing key"
    (is (= :nope (get (d/hash-map "a" 1) "missing" :nope)))))

(deftest hash-map-ifn
  (testing "Map as function"
    (is (= 42 @((d/hash-map "x" 42) "x")))))

(deftest hash-map-assoc
  (testing "assoc on map"
    (let [m (assoc (d/hash-map "a" 1) "b" 2)]
      (is (= 2 (count m)))
      (is (= 2 @(get m "b"))))))

(deftest hash-map-dissoc
  (testing "dissoc on map"
    (let [m (dissoc (d/hash-map "a" 1 "b" 2) "a")]
      (is (= 1 (count m)))
      (is (nil? (get m "a")))
      (is (= 2 @(get m "b"))))))

(deftest hash-map-conj
  (testing "conj with [k v] pair"
    (let [m (conj (d/hash-map "a" 1) ["b" 2])]
      (is (= 2 (count m))))))

(deftest hash-map-contains
  (testing "containsKey"
    (let [m (d/hash-map "a" 1)]
      (is (.containsKey m "a"))
      (is (not (.containsKey m "z"))))))

(deftest hash-map-empty
  (testing "Empty map"
    (let [m (d/hash-map)]
      (is (= 0 (count m)))
      (is (nil? (seq m))))))

(deftest hash-map-seq
  (testing "seq returns MapEntry elements"
    (let [s (seq (d/hash-map "x" 10))]
      (is (= 1 (clojure.core/count s)))
      (let [entry (first s)]
        (is (instance? clojure.lang.MapEntry entry))
        (is (= "x" @(key entry)))
        (is (= 10 @(val entry)))))))

(deftest hash-map-equality
  (testing "Same entries = equal"
    (is (= (d/hash-map "a" 1 "b" 2) (d/hash-map "a" 1 "b" 2)))))

(deftest hash-map-immutable
  (testing "assoc doesn't modify original"
    (let [m (d/hash-map "a" 1)
          _m2 (assoc m "b" 2)]
      (is (= 1 (count m))))))

(deftest hash-map-toString
  (testing "toString"
    (is (string? (clojure.core/str (d/hash-map "x" 10))))))

;; =============================================================================
;; with-store isolation
;; =============================================================================

(deftest with-store-isolation
  (testing "with-store creates isolated context"
    (let [_ (d/i64 42) ;; goes into global store
          [iso-store result] (d/with-store [_s {}]
                               (d/vec [1 2 3]))]
      ;; isolated store should not contain the global i64
      (is (not (contains? iso-store (d/dacite-hash (d/i64 42))))))))

(deftest with-store-returns-store-and-result
  (testing "Returns [final-store last-value]"
    (let [[store result] (d/with-store [_s {}]
                           (let [v (d/i64 99)]
                             (is (= 99 @v))
                             v))]
      (is (map? store))
      (is (instance? dacite.core.DaciteScalar result)))))

;; =============================================================================
;; Pure API — scalars
;; =============================================================================

(deftest scalar-pure
  (testing "Pure scalar construction"
    (let [[s h] (d/i64 {} 42)]
      (is (map? s))
      (is (vector? h))
      (is (= :i64 (d/value-type s h)))
      (is (= 42 (d/value-data s h))))))

(deftest scalar-pure-threading
  (testing "Store threads across calls"
    (let [[s h1] (d/i64 {} 1)
          [s h2] (d/i64 s 2)]
      (is (= 1 (d/value-data s h1)))
      (is (= 2 (d/value-data s h2))))))

(deftest scalar-pure-deterministic
  (testing "Same value = same hash"
    (let [[_ h1] (d/i64 {} 42)
          [_ h2] (d/i64 {} 42)]
      (is (= h1 h2)))))

(deftest scalar-pure-different-types
  (testing "Same data, different types = different hashes"
    (let [[_ h1] (d/i64 {} 1)
          [_ h2] (d/u64 {} 1)]
      (is (not= h1 h2)))))

;; =============================================================================
;; Pure API — strings
;; =============================================================================

(deftest str-pure
  (testing "Pure string construction"
    (let [[s h] (d/str {} "hello")]
      (is (= "hello" (d/string-value s h))))))

;; =============================================================================
;; Pure API — vectors
;; =============================================================================

(deftest vec-pure
  (testing "Pure vector construction"
    (let [[s h1] (d/i64 {} 1)
          [s h2] (d/i64 s 2)
          [s vh] (d/vec-of-refs s [h1 h2])]
      (is (= 2 (d/vector-count s vh)))
      (is (= h1 (d/vector-nth s vh 0))))))

(deftest vec-pure-auto-coerce
  (testing "Pure vector with auto-coercion"
    (let [[s vh] (d/vec {} [1 2 3])]
      (is (= 3 (d/vector-count s vh))))))

;; =============================================================================
;; Pure API — maps
;; =============================================================================

(deftest map-pure
  (testing "Pure map construction"
    (let [[s mh] (d/map-of {} {"a" 1})]
      (is (= 1 (d/map-count s mh)))
      (is (some? (d/map-get s mh "a"))))))

;; =============================================================================
;; Hashing utilities
;; =============================================================================

(deftest hash-hex-format
  (testing "Hash hex is 64 characters"
    (let [[_ h] (d/i64 {} 42)]
      (is (= 64 (count (d/hash-hex h)))))))

(deftest hash-as-value-round-trip
  (testing "Store hash as u256 and retrieve"
    (let [[s h1] (d/i64 {} 42)
          [s h2] (d/hash-as-value s h1)]
      (is (= :u256 (d/value-type s h2)))
      (is (= h1 (hash/bytes->longs (d/value-data s h2)))))))

;; =============================================================================
;; Content equality
;; =============================================================================

;; =============================================================================
;; dac->clj
;; =============================================================================

(deftest dac->clj-scalar
  (testing "Scalars unwrap"
    (is (= 42 (d/dac->clj (d/i64 42))))
    (is (= true (d/dac->clj (d/bool true))))
    (is (nil? (d/dac->clj (d/null))))
    (is (= \a (d/dac->clj (d/dacite-char \a))))))

(deftest dac->clj-string
  (testing "Strings unwrap"
    (is (= "hello" (d/dac->clj (d/str "hello"))))))

(deftest dac->clj-vector
  (testing "Vectors recursively unwrap"
    (is (= [1 2 3] (d/dac->clj (d/vec [1 2 3]))))))

(deftest dac->clj-nested-vector
  (testing "Nested vectors unwrap recursively"
    (let [inner (d/vec [1 2])
          outer (d/vec-of-refs [(d/unwrap-hash inner) (d/dacite-hash (d/i64 3))])]
      (is (= [[1 2] 3] (d/dac->clj outer))))))

(deftest dac->clj-map
  (testing "Maps recursively unwrap"
    (is (= {"name" "Alice" "age" 30}
           (d/dac->clj (d/hash-map "name" "Alice" "age" 30))))))

(deftest dac->clj-empty-collections
  (testing "Empty collections"
    (is (= [] (d/dac->clj (d/vec []))))
    (is (= {} (d/dac->clj (d/hash-map))))))

(deftest dac->clj-passthrough
  (testing "Non-Dacite values pass through"
    (is (= 42 (d/dac->clj 42)))
    (is (= "hi" (d/dac->clj "hi")))))

;; =============================================================================
;; clj->dac
;; =============================================================================

(deftest clj->dac-scalar
  (testing "Scalars wrap"
    (is (instance? dacite.core.DaciteScalar (d/clj->dac 42)))
    (is (= 42 @(d/clj->dac 42)))
    (is (instance? dacite.core.DaciteScalar (d/clj->dac true)))
    (is (instance? dacite.core.DaciteScalar (d/clj->dac nil)))))

(deftest clj->dac-string
  (testing "Strings wrap"
    (is (instance? dacite.core.DaciteString (d/clj->dac "hello")))
    (is (= "hello" @(d/clj->dac "hello")))))

(deftest clj->dac-vector
  (testing "Vectors wrap recursively"
    (let [v (d/clj->dac [1 2 3])]
      (is (instance? dacite.core.DaciteVector v))
      (is (= 3 (count v)))
      (is (= [1 2 3] (d/dac->clj v))))))

(deftest clj->dac-nested-vector
  (testing "Nested vectors wrap recursively"
    (let [v (d/clj->dac [[1 2] [3 4]])]
      (is (= [[1 2] [3 4]] (d/dac->clj v))))))

(deftest clj->dac-map
  (testing "Maps wrap recursively"
    (let [m (d/clj->dac {"a" 1 "b" 2})]
      (is (instance? dacite.core.DaciteMap m))
      (is (= {"a" 1 "b" 2} (d/dac->clj m))))))

(deftest clj->dac-nested-map
  (testing "Nested structures wrap recursively"
    (let [data {"users" [{"name" "Alice"} {"name" "Bob"}]}
          d (d/clj->dac data)]
      (is (= data (d/dac->clj d))))))

(deftest clj->dac-idempotent
  (testing "Already-Dacite values pass through"
    (let [v (d/i64 42)]
      (is (identical? v (d/clj->dac v))))))

(deftest clj->dac-round-trip
  (testing "Round-trip: clj -> dac -> clj"
    (let [data [1 "hello" nil true [2 3] {"a" 4}]]
      (is (= data (d/dac->clj (d/clj->dac data)))))))
