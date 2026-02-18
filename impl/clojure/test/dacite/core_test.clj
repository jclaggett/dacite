(ns dacite.core-test
  "Tests for the Dacite core value construction API."
  (:require [clojure.test :refer [deftest testing is]]
            [dacite.core :as d]
            [dacite.hash :as hash]))

;; Helper: run assertions inside with-store so *store* is bound
(defmacro in-store
  "Execute body inside a with-store. Returns nil (for side-effecting test assertions)."
  [& body]
  `(d/with-store [~'_s {}] ~@body nil))

;; =============================================================================
;; Scalar constructors (pure)
;; =============================================================================

(deftest null-value-pure
  (testing "Null construction returns [store hash]"
    (let [[s h] (d/null {})]
      (is (map? s))
      (is (vector? h))
      (is (= :null (d/value-type s h)))
      (is (nil? (d/value-data s h))))))

(deftest bool-value-pure
  (testing "Boolean construction"
    (let [[s h] (d/bool {} true)]
      (is (= :bool (d/value-type s h)))
      (is (= true (d/value-data s h))))))

(deftest integer-values-pure
  (testing "Signed integer constructors"
    (let [[s h] (d/i64 {} 42)]
      (is (= :i64 (d/value-type s h)))
      (is (= 42 (d/value-data s h))))))

(deftest unsigned-bounds-pure
  (testing "Unsigned integers reject out-of-bounds"
    (is (thrown? AssertionError (d/u8 {} 256)))
    (is (thrown? AssertionError (d/u8 {} -1)))))

(deftest store-threading-pure
  (testing "Store accumulates values across calls"
    (let [[s h1] (d/i64 {} 1)
          [s h2] (d/i64 s 2)]
      (is (= 1 (d/value-data s h1)))
      (is (= 2 (d/value-data s h2))))))

;; =============================================================================
;; Scalar constructors (bang) — return DaciteScalar
;; =============================================================================

(deftest scalar-bang-returns-dacite-scalar
  (testing "Bang constructors return DaciteScalar"
    (in-store
     (let [result (d/i64! 42)]
       (is (instance? dacite.core.DaciteScalar result))))))

(deftest scalar-deref
  (testing "Dereffing a DaciteScalar yields the raw value"
    (in-store
     (let [result (d/i64! 42)]
       (is (= 42 @result))))))

(deftest scalar-type-accessor
  (testing "scalar-type returns type keyword"
    (in-store
     (let [result (d/i64! 42)]
       (is (= :i64 (d/scalar-type result)))))))

(deftest scalar-equality
  (testing "Same value produces equal scalars"
    (in-store
     (let [a (d/i64! 42)
           b (d/i64! 42)]
       (is (= a b)))))
  (testing "Different values produce unequal scalars"
    (in-store
     (let [a (d/i64! 42)
           b (d/i64! 43)]
       (is (not= a b))))))

(deftest scalar-null-deref
  (testing "Null deref returns nil"
    (in-store
     (let [result (d/null!)]
       (is (nil? @result))))))

(deftest scalar-all-types
  (testing "All scalar types work via bang constructors"
    (in-store
     (let [results {:null (d/null!)
                    :bool (d/bool! true)
                    :i8 (d/i8! 1) :i16 (d/i16! 1) :i32 (d/i32! 1) :i64 (d/i64! 42)
                    :u8 (d/u8! 255) :u16 (d/u16! 100) :u32 (d/u32! 100) :u64 (d/u64! 100)
                    :f32 (d/f32! 1.5) :f64 (d/f64! 3.14)
                    :char (d/dacite-char! \a)
                    :u256 (d/u256! (hash/longs->bytes [1 2 3 4]))}]
       (is (nil? @(:null results)))
       (is (= true @(:bool results)))
       (is (= 42 @(:i64 results)))
       (is (= 255 @(:u8 results)))
       (is (= \a @(:char results)))
       (is (= :u256 (d/scalar-type (:u256 results))))))))

;; =============================================================================
;; String construction
;; =============================================================================

(deftest string-bang
  (testing "string! returns DaciteString"
    (in-store
     (let [result (d/string! "hello")]
       (is (instance? dacite.core.DaciteString result))
       (is (= "hello" @result))
       (is (= "hello" (str result)))))))

(deftest string-count
  (testing "DaciteString supports count"
    (in-store
     (let [result (d/string! "hello")]
       (is (= 5 (count result)))))))

(deftest string-empty-bang
  (testing "Empty string"
    (in-store
     (let [result (d/string! "")]
       (is (= "" @result))
       (is (= 0 (count result)))))))

(deftest string-equality
  (testing "Same string = equal"
    (in-store
     (let [a (d/string! "hello")
           b (d/string! "hello")]
       (is (= a b))))))

(deftest string-char-sequence
  (testing "DaciteString implements CharSequence"
    (in-store
     (let [result (d/string! "hello")]
       (is (= \h (.charAt result 0)))
       (is (= "ell" (str (.subSequence result 1 4))))))))

(deftest string-seq
  (testing "DaciteString is seqable"
    (in-store
     (let [result (d/string! "abc")]
       (is (= [\a \b \c] (seq result)))))))

;; =============================================================================
;; Vector construction (bang)
;; =============================================================================

(deftest vector-of-bang
  (testing "vector-of! returns DaciteVector with correct count"
    (in-store
     (let [v (d/vector-of! [1 2 3])]
       (is (instance? dacite.core.DaciteVector v))
       (is (= 3 (count v)))))))

(deftest vector-nth-returns-scalar
  (testing "nth on DaciteVector returns DaciteScalar"
    (in-store
     (let [v (d/vector-of! [10 20 30])]
       (let [elem (nth v 0)]
         (is (instance? dacite.core.DaciteScalar elem))
         (is (= 10 @elem)))
       (is (= 30 @(nth v 2)))))))

(deftest vector-conj-via-interface
  (testing "conj on DaciteVector returns new DaciteVector"
    (in-store
     (let [v (d/vector-of! [1 2])
           v2 (conj v 3)]
       (is (instance? dacite.core.DaciteVector v2))
       (is (= 3 (count v2)))
       (is (= 3 @(nth v2 2)))))))

(deftest vector-conj-immutable
  (testing "conj doesn't modify original"
    (in-store
     (let [v (d/vector-of! [1 2])
           _v2 (conj v 3)]
       (is (= 2 (count v)))))))

(deftest vector-empty-bang
  (testing "Empty vector"
    (in-store
     (let [v (d/vector-of! [])]
       (is (= 0 (count v)))
       (is (nil? (seq v)))))))

(deftest vector-ifn
  (testing "DaciteVector as function (index lookup)"
    (in-store
     (let [v (d/vector-of! [10 20 30])]
       (is (= 20 @(v 1)))))))

(deftest vector-ilookup
  (testing "get works on DaciteVector"
    (in-store
     (let [v (d/vector-of! [10 20 30])]
       (is (= 20 @(get v 1)))
       (is (nil? (get v 99)))))))

(deftest vector-peek-pop
  (testing "peek and pop on DaciteVector"
    (in-store
     (let [v (d/vector-of! [1 2 3])]
       (is (= 3 @(peek v)))
       (let [popped (pop v)]
         (is (= 2 (count popped)))
         (is (= 2 @(peek popped))))))))

(deftest vector-assoc-interface
  (testing "assoc on DaciteVector replaces element"
    (in-store
     (let [v (d/vector-of! [1 2 3])
           v2 (assoc v 1 99)]
       (is (= 3 (count v2)))
       (is (= 99 @(nth v2 1)))))))

(deftest vector-seq-returns-scalars
  (testing "seq on DaciteVector returns DaciteScalar elements"
    (in-store
     (let [v (d/vector-of! [10 20 30])
           s (seq v)]
       (is (= 3 (clojure.core/count s)))
       (is (every? #(instance? dacite.core.DaciteScalar %) s))
       (is (= [10 20 30] (mapv deref s)))))))

(deftest vector-contains-key
  (testing "contains? works with integer keys"
    (in-store
     (let [v (d/vector-of! [10 20])]
       (is (.containsKey v 0))
       (is (.containsKey v 1))
       (is (not (.containsKey v 2)))))))

(deftest vector-equality
  (testing "Same elements produce equal vectors"
    (in-store
     (let [a (d/vector-of! [1 2 3])
           b (d/vector-of! [1 2 3])]
       (is (= a b)))))
  (testing "Different elements are not equal"
    (in-store
     (let [a (d/vector-of! [1 2])
           b (d/vector-of! [2 1])]
       (is (not= a b))))))

(deftest vector-toString
  (testing "toString shows readable representation"
    (in-store
     (let [v (d/vector-of! [1 2 3])]
       (is (= "[1 2 3]" (str v)))))))

(deftest vector-mixed-types
  (testing "Vector with mixed auto-coerced types"
    (in-store
     (let [v (d/vector-of! [nil true 42])]
       (is (= 3 (count v)))
       (is (nil? @(nth v 0)))
       (is (= true @(nth v 1)))
       (is (= 42 @(nth v 2)))))))

(deftest vector-nested
  (testing "Vectors can contain vectors"
    (in-store
     (let [inner (d/vector-of! [1 2])
           outer (d/vector! [(d/unwrap-hash inner)])]
       (is (= 1 (count outer)))
       (let [inner' (nth outer 0)]
         (is (instance? dacite.core.DaciteVector inner'))
         (is (= 2 (count inner'))))))))

;; =============================================================================
;; Map construction (bang)
;; =============================================================================

(deftest map-of-bang
  (testing "map-of! returns DaciteMap"
    (in-store
     (let [m (d/map-of! {"name" "Alice" "age" 30})]
       (is (instance? dacite.core.DaciteMap m))
       (is (= 2 (count m)))))))

(deftest map-get-via-interface
  (testing "get/valAt works on DaciteMap"
    (in-store
     (let [m (d/map-of! {"name" "Alice" "age" 30})]
       (let [name-val (get m "name")]
         (is (instance? dacite.core.DaciteString name-val))
         (is (= "Alice" @name-val)))
       (let [age-val (get m "age")]
         (is (instance? dacite.core.DaciteScalar age-val))
         (is (= 30 @age-val)))))))

(deftest map-get-missing
  (testing "get returns nil for missing key"
    (in-store
     (let [m (d/map-of! {"a" 1})]
       (is (nil? (get m "missing")))))))

(deftest map-get-not-found
  (testing "get returns not-found for missing key"
    (in-store
     (let [m (d/map-of! {"a" 1})]
       (is (= :nope (get m "missing" :nope)))))))

(deftest map-ifn
  (testing "DaciteMap as function"
    (in-store
     (let [m (d/map-of! {"x" 42})]
       (is (= 42 @(m "x")))))))

(deftest map-assoc-via-interface
  (testing "assoc on DaciteMap"
    (in-store
     (let [m (d/map-of! {"a" 1})
           m2 (assoc m "b" 2)]
       (is (= 2 (count m2)))
       (is (= 2 @(get m2 "b")))))))

(deftest map-dissoc-via-interface
  (testing "dissoc on DaciteMap"
    (in-store
     (let [m (d/map-of! {"a" 1 "b" 2})
           m2 (dissoc m "a")]
       (is (= 1 (count m2)))
       (is (nil? (get m2 "a")))
       (is (= 2 @(get m2 "b")))))))

(deftest map-conj-entry
  (testing "conj on DaciteMap with [k v] pair"
    (in-store
     (let [m (d/map-of! {"a" 1})
           m2 (conj m ["b" 2])]
       (is (= 2 (count m2)))))))

(deftest map-contains
  (testing "contains? via containsKey"
    (in-store
     (let [m (d/map-of! {"a" 1})]
       (is (.containsKey m "a"))
       (is (not (.containsKey m "z")))))))

(deftest map-empty-bang
  (testing "Empty map"
    (in-store
     (let [m (d/map-of! {})]
       (is (= 0 (count m)))
       (is (nil? (seq m)))))))

(deftest map-seq-returns-entries
  (testing "seq on DaciteMap returns MapEntry elements"
    (in-store
     (let [m (d/map-of! {"x" 10})
           s (seq m)]
       (is (= 1 (clojure.core/count s)))
       (let [entry (first s)]
         (is (instance? clojure.lang.MapEntry entry))
         (is (instance? dacite.core.DaciteString (key entry)))
         (is (= "x" @(key entry)))
         (is (= 10 @(val entry))))))))

(deftest map-equality
  (testing "Same entries produce equal maps"
    (in-store
     (let [a (d/map-of! {"a" 1 "b" 2})
           b (d/map-of! {"a" 1 "b" 2})]
       (is (= a b))))))

(deftest map-immutable
  (testing "assoc doesn't modify original"
    (in-store
     (let [m (d/map-of! {"a" 1})
           _m2 (assoc m "b" 2)]
       (is (= 1 (count m)))))))

(deftest map-toString
  (testing "toString shows readable representation"
    (in-store
     (let [m (d/map-of! {"x" 10})]
       (is (string? (str m)))))))

;; =============================================================================
;; Hashing utilities
;; =============================================================================

(deftest hash-hex-format
  (testing "Hash hex is 64 characters"
    (let [[_ h] (d/i64 {} 42)]
      (is (= 64 (count (d/hash-hex h)))))))

(deftest hash-determinism
  (testing "Same value always same hash"
    (let [[_ h1] (d/i64 {} 42)
          [_ h2] (d/i64 {} 42)]
      (is (= h1 h2)))))

(deftest hash-different-types
  (testing "Same data, different types = different hashes"
    (let [[_ h1] (d/i64 {} 1)
          [_ h2] (d/u64 {} 1)]
      (is (not= h1 h2)))))

(deftest hash-as-value-round-trip
  (testing "Store a hash as u256 and retrieve"
    (let [[s h1] (d/i64 {} 42)
          [s h2] (d/hash-as-value s h1)]
      (is (= :u256 (d/value-type s h2)))
      (is (= h1 (hash/bytes->longs (d/value-data s h2)))))))

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
;; Pure vector/map API (backward compat)
;; =============================================================================

(deftest vector-pure-api
  (testing "Pure vector API still works"
    (let [[s h1] (d/i64 {} 1)
          [s h2] (d/i64 s 2)
          [s vh] (d/vector s [h1 h2])]
      (is (= 2 (d/vector-count s vh)))
      (is (= h1 (d/vector-nth s vh 0))))))

(deftest map-pure-api
  (testing "Pure map API still works"
    (let [[s mh] (d/map-of {} {"a" 1})]
      (is (= 1 (d/map-count s mh)))
      (is (some? (d/map-get s mh "a"))))))
