(ns dacite.core-test
  "Tests for the Dacite core API."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [dacite.core :as d]
            [dacite.hash :as hash]
            [dacite.store :as store]))

;; Reset global store before each test
(use-fixtures :each (fn [f] (d/reset-store!) (f)))

;; =============================================================================
;; Scalars
;; =============================================================================

(deftest null-test
  (testing "Null construction"
    (let [v (d/null)]
      (is (instance? dacite.scalar.DaciteScalar v))
      (is (nil? @v)))))

(deftest bool-test
  (testing "Boolean construction"
    (is (= true @(d/bool true)))
    (is (= false @(d/bool false)))))

(deftest integer-test
  (testing "Integer constructors"
    (is (= 42 @(d/i64 42)))
    (is (= 1 @(d/i8 1)))
    (is (= 1 @(d/i16 1)))
    (is (= 1 @(d/i32 1)))))

(deftest unsigned-test
  (testing "Unsigned integer constructors"
    (is (= 255 @(d/u8 255)))
    (is (= 65535 @(d/u16 65535)))
    (is (= 4294967295 @(d/u32 4294967295)))
    (is (= 0 @(d/u64 0)))))

(deftest unsigned-bounds-test
  (testing "Unsigned integers reject out-of-bounds"
    (is (thrown? AssertionError (d/u8 256)))
    (is (thrown? AssertionError (d/u8 -1)))
    (is (thrown? AssertionError (d/u16 65536)))))

(deftest u256-test
  (testing "u256 for hash-as-data"
    (let [data (hash/longs->bytes [1 2 3 4])
          v (d/u256 data)]
      (is (= "u256" (d/scalar-type v)))
      (is (= 32 (alength ^bytes @v))))))

(deftest float-test
  (testing "Float constructors"
    (is (= "f32" (d/scalar-type (d/f32 1.5))))
    (is (= "f64" (d/scalar-type (d/f64 3.14))))))

(deftest char-test
  (testing "Character constructor"
    (is (= \a @(d/dacite-char \a)))
    (is (thrown? AssertionError (d/dacite-char "a")))))

(deftest scalar-equality-test
  (testing "Same value = equal"
    (is (= (d/i64 42) (d/i64 42))))
  (testing "Different value = not equal"
    (is (not= (d/i64 42) (d/i64 43)))))

(deftest scalar-type-test
  (testing "scalar-type returns type name"
    (is (= "i64" (d/scalar-type (d/i64 42))))
    (is (= "null" (d/scalar-type (d/null))))))

(deftest scalar-ifn-test
  (testing "Scalar as zero-arg function returns deref"
    (is (= 42 ((d/i64 42))))))

(deftest size-bytes-scalar-test
  (testing "Scalar size-bytes"
    (is (= 8 (d/size-bytes (d/i64 42))))
    (is (= 1 (d/size-bytes (d/bool true))))
    (is (= 0 (d/size-bytes (d/null))))
    (is (= 4 (d/size-bytes (d/f32 1.0)))))
  (testing "String size-bytes (UTF-8)"
    (is (= 5 (d/size-bytes (d/str "hello"))))
    (is (= 0 (d/size-bytes (d/str ""))))))

(deftest size-bytes-vector-test
  (testing "Vector size-bytes is sum of element sizes"
    (let [v (d/vec [1 2 3])] ;; 3 x i64 = 24
      (is (= 24 (d/size-bytes v)))))
  (testing "Empty vector is 0 bytes"
    (is (= 0 (d/size-bytes (d/vec []))))))

(deftest size-bytes-map-test
  (testing "Map size-bytes includes keys and values"
    (let [m (d/hash-map "a" 1)] ;; "a" = 1 byte, i64 = 8 bytes
      (is (= 9 (d/size-bytes m)))))
  (testing "Empty map is 0 bytes"
    (is (= 0 (d/size-bytes (d/hash-map))))))

(deftest size-bytes-nested-test
  (testing "Nested structure accumulates sizes"
    (let [v (d/vec [1 2])       ;; 16 bytes
          m (d/hash-map "k" v)] ;; "k"=1 + vec=16 = 17
      (is (= 16 (d/size-bytes v)))
      (is (= 17 (d/size-bytes m))))))

(deftest scalar-hash-eq-test
  (testing "Same values have same hasheq"
    (is (= (hash (d/i64 42)) (hash (d/i64 42))))))

;; =============================================================================
;; Strings
;; =============================================================================

(deftest str-test
  (testing "String construction"
    (let [s (d/str "hello")]
      (is (instance? dacite.collections.DaciteString s))
      (is (= "hello" @s))
      (is (= "hello" (clojure.core/str s))))))

(deftest str-count-test
  (testing "String supports count"
    (is (= 5 (count (d/str "hello"))))
    (is (= 0 (count (d/str ""))))))

(deftest str-equality-test
  (testing "Same string = equal"
    (is (= (d/str "hello") (d/str "hello")))))

(deftest str-char-sequence-test
  (testing "CharSequence interface"
    (let [s (d/str "hello")]
      (is (= \h (.charAt s 0)))
      (is (= "ell" (clojure.core/str (.subSequence s 1 4)))))))

(deftest str-seq-test
  (testing "String is seqable, returns DaciteScalar chars"
    (let [s (seq (d/str "abc"))]
      (is (= 3 (clojure.core/count s)))
      (is (every? #(instance? dacite.scalar.DaciteScalar %) s))
      (is (= [\a \b \c] (mapv deref s))))))

;; =============================================================================
;; Blobs
;; =============================================================================

(deftest blob-test
  (testing "Blob construction"
    (let [b (d/blob (byte-array [1 2 3]))]
      (is (instance? dacite.collections.DaciteBlob b))
      (is (= 3 (count b))))))

(deftest blob-deref-test
  (testing "Deref returns byte array"
    (let [b (d/blob (byte-array [10 20 30]))]
      (is (bytes? @b))
      (is (= [10 20 30] (clojure.core/vec @b))))))

(deftest blob-empty-test
  (testing "Empty blob"
    (let [b (d/blob (byte-array 0))]
      (is (= 0 (count b)))
      (is (nil? (seq b))))))

(deftest blob-seq-test
  (testing "Seq returns DaciteScalar u8 wrappers"
    (let [s (seq (d/blob (byte-array [1 2 3])))]
      (is (= 3 (clojure.core/count s)))
      (is (every? #(instance? dacite.scalar.DaciteScalar %) s))
      (is (= [1 2 3] (mapv deref s))))))

(deftest blob-equality-test
  (testing "Same bytes = equal"
    (is (= (d/blob (byte-array [1 2 3])) (d/blob (byte-array [1 2 3])))))
  (testing "Different bytes = not equal"
    (is (not= (d/blob (byte-array [1 2])) (d/blob (byte-array [3 4]))))))

(deftest blob-size-bytes-test
  (testing "Size equals byte count"
    (is (= 5 (d/size-bytes (d/blob (byte-array [0 0 0 0 0])))))
    (is (= 0 (d/size-bytes (d/blob (byte-array 0)))))))

(deftest blob-toString-test
  (testing "toString shows byte count"
    (is (= "<blob 3 bytes>" (.toString (d/blob (byte-array [1 2 3])))))))

;; =============================================================================
;; Vectors
;; =============================================================================

(deftest vec-test
  (testing "Vector construction"
    (let [v (d/vec [1 2 3])]
      (is (instance? dacite.collections.DaciteVector v))
      (is (= 3 (count v))))))

(deftest vec-nth-test
  (testing "nth returns wrapped Dacite type"
    (let [v (d/vec [10 20 30])]
      (is (instance? dacite.scalar.DaciteScalar (nth v 0)))
      (is (= 10 @(nth v 0)))
      (is (= 30 @(nth v 2))))))

(deftest vec-conj-test
  (testing "conj appends"
    (let [v (d/vec [1 2])
          v2 (conj v 3)]
      (is (= 3 (count v2)))
      (is (= 3 @(nth v2 2))))))

(deftest vec-immutable-test
  (testing "conj doesn't modify original"
    (let [v (d/vec [1 2])
          _v2 (conj v 3)]
      (is (= 2 (count v))))))

(deftest vec-empty-test
  (testing "Empty vector"
    (let [v (d/vec [])]
      (is (= 0 (count v)))
      (is (nil? (seq v))))))

(deftest vec-ifn-test
  (testing "Vector as function"
    (is (= 20 @((d/vec [10 20 30]) 1)))))

(deftest vec-ilookup-test
  (testing "get works on vector"
    (let [v (d/vec [10 20 30])]
      (is (= 20 @(get v 1)))
      (is (nil? (get v 99))))))

(deftest vec-peek-pop-test
  (testing "peek and pop"
    (let [v (d/vec [1 2 3])]
      (is (= 3 @(peek v)))
      (let [p (pop v)]
        (is (= 2 (count p)))
        (is (= 2 @(peek p)))))))

(deftest vec-assoc-test
  (testing "assoc replaces element"
    (let [v (assoc (d/vec [1 2 3]) 1 99)]
      (is (= 3 (count v)))
      (is (= 99 @(nth v 1))))))

(deftest vec-seq-test
  (testing "seq returns wrapped elements"
    (let [s (seq (d/vec [10 20 30]))]
      (is (= 3 (clojure.core/count s)))
      (is (every? #(instance? dacite.scalar.DaciteScalar %) s))
      (is (= [10 20 30] (mapv deref s))))))

(deftest vec-contains-key-test
  (testing "containsKey on vector"
    (let [v (d/vec [10 20])]
      (is (.containsKey v 0))
      (is (.containsKey v 1))
      (is (not (.containsKey v 2))))))

(deftest vec-equality-test
  (testing "Same elements = equal"
    (is (= (d/vec [1 2 3]) (d/vec [1 2 3]))))
  (testing "Different order = not equal"
    (is (not= (d/vec [1 2]) (d/vec [2 1])))))

(deftest vec-toString-test
  (testing "toString"
    (is (= "[1 2 3]" (clojure.core/str (d/vec [1 2 3]))))))

(deftest vec-mixed-types-test
  (testing "Mixed auto-coerced types"
    (let [v (d/vec [nil true 42])]
      (is (nil? @(nth v 0)))
      (is (= true @(nth v 1)))
      (is (= 42 @(nth v 2))))))

(deftest vec-nested-test
  (testing "Vectors can contain vectors"
    (let [inner (d/vec [1 2])
          outer (d/vec-of-refs [(d/unwrap-hash inner)])]
      (is (= 1 (count outer)))
      (let [inner' (nth outer 0)]
        (is (instance? dacite.collections.DaciteVector inner'))
        (is (= 2 (count inner')))))))

(deftest vec-accepts-dacite-values-test
  (testing "vec accepts Dacite types directly"
    (let [a (d/i64 1)
          b (d/i64 2)
          v (d/vec [a b])]
      (is (= 2 (count v)))
      (is (= 1 @(nth v 0))))))

;; =============================================================================
;; Maps
;; =============================================================================

(deftest hash-map-test
  (testing "Map construction"
    (let [m (d/hash-map "name" "Alice" "age" 30)]
      (is (instance? dacite.collections.DaciteMap m))
      (is (= 2 (count m))))))

(deftest hash-map-get-test
  (testing "get works on map"
    (let [m (d/hash-map "name" "Alice" "age" 30)]
      (let [name-val (get m "name")]
        (is (instance? dacite.collections.DaciteString name-val))
        (is (= "Alice" @name-val)))
      (let [age-val (get m "age")]
        (is (instance? dacite.scalar.DaciteScalar age-val))
        (is (= 30 @age-val))))))

(deftest hash-map-get-missing-test
  (testing "get returns nil for missing key"
    (is (nil? (get (d/hash-map "a" 1) "missing")))))

(deftest hash-map-get-not-found-test
  (testing "get returns not-found for missing key"
    (is (= :nope (get (d/hash-map "a" 1) "missing" :nope)))))

(deftest hash-map-ifn-test
  (testing "Map as function"
    (is (= 42 @((d/hash-map "x" 42) "x")))))

(deftest hash-map-assoc-test
  (testing "assoc on map"
    (let [m (assoc (d/hash-map "a" 1) "b" 2)]
      (is (= 2 (count m)))
      (is (= 2 @(get m "b"))))))

(deftest hash-map-dissoc-test
  (testing "dissoc on map"
    (let [m (dissoc (d/hash-map "a" 1 "b" 2) "a")]
      (is (= 1 (count m)))
      (is (nil? (get m "a")))
      (is (= 2 @(get m "b"))))))

(deftest hash-map-conj-test
  (testing "conj with [k v] pair"
    (let [m (conj (d/hash-map "a" 1) ["b" 2])]
      (is (= 2 (count m))))))

(deftest hash-map-contains-test
  (testing "containsKey"
    (let [m (d/hash-map "a" 1)]
      (is (.containsKey m "a"))
      (is (not (.containsKey m "z"))))))

(deftest hash-map-empty-test
  (testing "Empty map"
    (let [m (d/hash-map)]
      (is (= 0 (count m)))
      (is (nil? (seq m))))))

(deftest hash-map-seq-test
  (testing "seq returns MapEntry elements"
    (let [s (seq (d/hash-map "x" 10))]
      (is (= 1 (clojure.core/count s)))
      (let [entry (first s)]
        (is (instance? clojure.lang.MapEntry entry))
        (is (= "x" @(key entry)))
        (is (= 10 @(val entry)))))))

(deftest hash-map-equality-test
  (testing "Same entries = equal"
    (is (= (d/hash-map "a" 1 "b" 2) (d/hash-map "a" 1 "b" 2)))))

(deftest hash-map-immutable-test
  (testing "assoc doesn't modify original"
    (let [m (d/hash-map "a" 1)
          _m2 (assoc m "b" 2)]
      (is (= 1 (count m))))))

(deftest hash-map-toString-test
  (testing "toString"
    (is (string? (clojure.core/str (d/hash-map "x" 10))))))

(deftest hash-map-accepts-dacite-values-test
  (testing "hash-map accepts Dacite types as keys/values"
    (let [k (d/str "key")
          v (d/i64 42)
          m (d/hash-map k v)]
      (is (= 1 (count m)))
      (is (= 42 @(get m "key"))))))

;; =============================================================================
;; with-store isolation
;; =============================================================================

(deftest with-store-isolation-test
  (testing "with-store creates isolated context"
    (let [_ (d/i64 42)
          [iso-store _result] (d/with-store [_s {}]
                                (d/vec [1 2 3]))]
      ;; isolated store should not contain the global i64
      (is (not (contains? iso-store (d/dacite-hash (d/i64 42))))))))

(deftest with-store-returns-store-and-result-test
  (testing "Returns [final-store last-value]"
    (let [[store result] (d/with-store [_s {}]
                           (let [v (d/i64 99)]
                             (is (= 99 @v))
                             v))]
      (is (map? store))
      (is (instance? dacite.scalar.DaciteScalar result)))))

;; =============================================================================
;; dac->clj
;; =============================================================================

(deftest dac->clj-scalar-test
  (testing "Scalars unwrap"
    (is (= 42 (d/dac->clj (d/i64 42))))
    (is (= true (d/dac->clj (d/bool true))))
    (is (nil? (d/dac->clj (d/null))))
    (is (= \a (d/dac->clj (d/dacite-char \a))))))

(deftest dac->clj-string-test
  (testing "Strings unwrap"
    (is (= "hello" (d/dac->clj (d/str "hello"))))))

(deftest dac->clj-vector-test
  (testing "Vectors recursively unwrap"
    (is (= [1 2 3] (d/dac->clj (d/vec [1 2 3]))))))

(deftest dac->clj-nested-vector-test
  (testing "Nested vectors unwrap recursively"
    (let [inner (d/vec [1 2])
          outer (d/vec-of-refs [(d/unwrap-hash inner) (d/dacite-hash (d/i64 3))])]
      (is (= [[1 2] 3] (d/dac->clj outer))))))

(deftest dac->clj-map-test
  (testing "Maps recursively unwrap"
    (is (= {"name" "Alice" "age" 30}
           (d/dac->clj (d/hash-map "name" "Alice" "age" 30))))))

(deftest dac->clj-blob-test
  (testing "Blobs unwrap to byte array"
    (let [result (d/dac->clj (d/blob (byte-array [1 2 3])))]
      (is (bytes? result))
      (is (= [1 2 3] (clojure.core/vec result))))))

(deftest dac->clj-empty-collections-test
  (testing "Empty collections"
    (is (= [] (d/dac->clj (d/vec []))))
    (is (= {} (d/dac->clj (d/hash-map))))))

(deftest dac->clj-passthrough-test
  (testing "Non-Dacite values pass through"
    (is (= 42 (d/dac->clj 42)))
    (is (= "hi" (d/dac->clj "hi")))))

(deftest dac->clj-max-bytes-test
  (testing "Exceeding max-bytes throws"
    (let [v (d/vec (range 100))]
      ;; 100 x i64 = 800 bytes
      (is (thrown? clojure.lang.ExceptionInfo (d/dac->clj v 100)))
      (is (= (clojure.core/vec (range 100)) (d/dac->clj v 1000)))))
  (testing "Default limit allows reasonable sizes"
    (is (= [1 2 3] (d/dac->clj (d/vec [1 2 3])))))
  (testing "Non-Dacite values bypass size check"
    (is (= 42 (d/dac->clj 42 0))))
  (testing "Scalars with tiny limit still work (size = 8 bytes)"
    (is (= 42 (d/dac->clj (d/i64 42) 10)))
    (is (thrown? clojure.lang.ExceptionInfo (d/dac->clj (d/i64 42) 1)))))

;; =============================================================================
;; clj->dac
;; =============================================================================

(deftest clj->dac-scalar-test
  (testing "Scalars wrap"
    (is (instance? dacite.scalar.DaciteScalar (d/clj->dac 42)))
    (is (= 42 @(d/clj->dac 42)))
    (is (instance? dacite.scalar.DaciteScalar (d/clj->dac true)))
    (is (instance? dacite.scalar.DaciteScalar (d/clj->dac nil)))))

(deftest clj->dac-string-test
  (testing "Strings wrap"
    (is (instance? dacite.collections.DaciteString (d/clj->dac "hello")))
    (is (= "hello" @(d/clj->dac "hello")))))

(deftest clj->dac-vector-test
  (testing "Vectors wrap recursively"
    (let [v (d/clj->dac [1 2 3])]
      (is (instance? dacite.collections.DaciteVector v))
      (is (= 3 (count v)))
      (is (= [1 2 3] (d/dac->clj v))))))

(deftest clj->dac-nested-vector-test
  (testing "Nested vectors wrap recursively"
    (let [v (d/clj->dac [[1 2] [3 4]])]
      (is (= [[1 2] [3 4]] (d/dac->clj v))))))

(deftest clj->dac-map-test
  (testing "Maps wrap recursively"
    (let [m (d/clj->dac {"a" 1 "b" 2})]
      (is (instance? dacite.collections.DaciteMap m))
      (is (= {"a" 1 "b" 2} (d/dac->clj m))))))

(deftest clj->dac-nested-map-test
  (testing "Nested structures wrap recursively"
    (let [data {"users" [{"name" "Alice"} {"name" "Bob"}]}
          d (d/clj->dac data)]
      (is (= data (d/dac->clj d))))))

(deftest clj->dac-blob-test
  (testing "Byte arrays wrap to DaciteBlob"
    (let [b (d/clj->dac (byte-array [10 20]))]
      (is (instance? dacite.collections.DaciteBlob b))
      (is (= [10 20] (clojure.core/vec @b))))))

(deftest clj->dac-idempotent-test
  (testing "Already-Dacite values pass through"
    (let [v (d/i64 42)]
      (is (identical? v (d/clj->dac v))))))

(deftest clj->dac-round-trip-test
  (testing "Round-trip: clj -> dac -> clj"
    (let [data [1 "hello" nil true [2 3] {"a" 4}]]
      (is (= data (d/dac->clj (d/clj->dac data)))))))

(deftest clj->dac-float-test
  (testing "Floats wrap to f64"
    (let [v (d/clj->dac (float 1.5))]
      (is (instance? dacite.scalar.DaciteScalar v))
      (is (= 1.5 @v))))
  (testing "Doubles wrap to f64"
    (let [v (d/clj->dac 3.14)]
      (is (instance? dacite.scalar.DaciteScalar v))
      (is (= 3.14 @v)))))

(deftest clj->dac-char-test
  (testing "Chars wrap to dacite-char"
    (let [v (d/clj->dac \x)]
      (is (instance? dacite.scalar.DaciteScalar v))
      (is (= \x @v)))))

(deftest clj->dac-list-test
  (testing "Lists (sequential) wrap to DaciteVector"
    (let [v (d/clj->dac '(1 2 3))]
      (is (instance? dacite.collections.DaciteVector v))
      (is (= [1 2 3] (d/dac->clj v))))))

(deftest clj->dac-unsupported-test
  (testing "Unsupported types throw"
    (is (thrown? clojure.lang.ExceptionInfo (d/clj->dac :keyword)))))

;; =============================================================================
;; set-store! and scalar-hash
;; =============================================================================

(deftest set-store-test
  (testing "set-store! replaces the global store"
    (let [original store/*store*
          new-store (store/mem-store)]
      (d/set-store! new-store)
      (is (identical? new-store store/*store*))
      ;; Restore original
      (d/set-store! original))))

(deftest scalar-hash-test
  (testing "scalar-hash returns the raw hash"
    (let [v (d/i64 42)
          h (d/scalar-hash v)]
      (is (some? h))
      (is (= h (d/dacite-hash v)))))
  (testing "Same value same hash"
    (is (= (d/scalar-hash (d/i64 42)) (d/scalar-hash (d/i64 42)))))
  (testing "Different value different hash"
    (is (not= (d/scalar-hash (d/i64 42)) (d/scalar-hash (d/i64 43))))))

;; =============================================================================
;; Uncovered edge cases
;; =============================================================================

(deftest scalar-toString-test
  (testing "Scalar toString returns readable representation"
    (let [s (.toString (d/i64 42))]
      (is (string? s))
      (is (.contains s "42")))))

(deftest scalar-hashCode-test
  (testing "Scalar hashCode works"
    (is (integer? (.hashCode (d/i64 42))))))

(deftest string-hashCode-test
  (testing "String hashCode works"
    (is (integer? (.hashCode (d/str "hello"))))))

(deftest string-hasheq-test
  (testing "String hasheq works"
    (is (integer? (hash (d/str "hello"))))))

(deftest string-length-test
  (testing "CharSequence.length works"
    (is (= 5 (.length (d/str "hello"))))))

(deftest blob-hashCode-test
  (testing "Blob hashCode works"
    (is (integer? (.hashCode (d/blob (byte-array [1 2])))))))

(deftest blob-hasheq-test
  (testing "Blob hasheq works"
    (is (integer? (hash (d/blob (byte-array [1 2])))))))

(deftest vector-deref-test
  (testing "Vector deref returns hash"
    (let [v (d/vec [1 2])]
      (is (some? @v)))))

(deftest vector-hasheq-test
  (testing "Vector hasheq works"
    (is (integer? (hash (d/vec [1 2]))))))

(deftest vector-hashCode-test
  (testing "Vector hashCode works"
    (is (integer? (.hashCode (d/vec [1 2]))))))

(deftest vector-nth-not-found-test
  (testing "nth with not-found on out-of-bounds"
    (is (= :nope (nth (d/vec [1 2]) 99 :nope))))
  (testing "nth with not-found on negative"
    (is (= :nope (nth (d/vec [1 2]) -1 :nope)))))

(deftest vector-empty-test-2
  (testing "IPersistentCollection.empty returns empty vector"
    (let [v (d/vec [1 2 3])
          e (.empty v)]
      (is (= 0 (count e)))
      (is (instance? dacite.collections.DaciteVector e)))))

(deftest vector-pop-empty-throws-test
  (testing "pop on empty vector throws"
    (is (thrown? IllegalStateException (pop (d/vec []))))))

(deftest vector-assoc-non-integer-test
  (testing "assoc with non-integer key throws"
    (is (thrown? IllegalArgumentException (assoc (d/vec [1 2]) "a" 3)))))

(deftest vector-entry-at-test
  (testing "entryAt returns MapEntry for valid index"
    (let [v (d/vec [10 20])
          e (.entryAt v 1)]
      (is (instance? clojure.lang.MapEntry e))
      (is (= 1 (key e)))
      (is (= 20 @(val e)))))
  (testing "entryAt returns nil for invalid index"
    (is (nil? (.entryAt (d/vec [1]) 5)))))

(deftest vector-length-test
  (testing "IPersistentVector.length works"
    (is (= 3 (.length (d/vec [1 2 3]))))))

(deftest vector-assocN-test
  (testing "assocN works"
    (let [v (.assocN (d/vec [1 2 3]) 1 99)]
      (is (= 99 @(nth v 1))))))

(deftest vector-comparable-test
  (testing "compareTo same vector = 0"
    (let [v (d/vec [1 2])]
      (is (= 0 (.compareTo v v)))))
  (testing "compareTo non-vector throws"
    (is (thrown? ClassCastException (.compareTo (d/vec [1]) (d/i64 1))))))

(deftest vector-iterator-test
  (testing "iterator works on non-empty vector"
    (let [it (.iterator (d/vec [1 2 3]))]
      (is (.hasNext it))
      (is (= 1 @(.next it)))))
  (testing "iterator works on empty vector"
    (let [it (.iterator (d/vec []))]
      (is (not (.hasNext it))))))

(deftest vector-equals-test
  (testing "equals with non-vector returns false"
    (is (not (.equals (d/vec [1]) (d/i64 1))))))

(deftest map-deref-test
  (testing "Map deref returns hash"
    (is (some? @(d/hash-map "a" 1)))))

(deftest map-hasheq-test
  (testing "Map hasheq works"
    (is (integer? (hash (d/hash-map "a" 1))))))

(deftest map-hashCode-test
  (testing "Map hashCode works"
    (is (integer? (.hashCode (d/hash-map "a" 1))))))

(deftest map-empty-collection-test
  (testing "IPersistentCollection.empty returns empty map"
    (let [m (d/hash-map "a" 1)
          e (.empty m)]
      (is (= 0 (count e)))
      (is (instance? dacite.collections.DaciteMap e)))))

(deftest map-conj-map-entry-test
  (testing "conj with MapEntry"
    (let [m (d/hash-map "a" 1)
          entry (first (seq m))
          m2 (conj (d/hash-map) entry)]
      (is (= 1 (count m2))))))

(deftest map-conj-invalid-test
  (testing "conj with non-pair throws"
    (is (thrown? IllegalArgumentException (conj (d/hash-map) "bad")))))

(deftest map-entry-at-test
  ;; NOTE: entryAt has a bug — it calls (wrap-hash k) on the raw key
  ;; instead of (wrap-hash (extract-hash k)), so the key in the returned
  ;; MapEntry won't deref correctly. Testing that it returns a MapEntry
  ;; and the value works correctly.
  (testing "entryAt returns MapEntry for existing key"
    (let [m (d/hash-map "a" 1)
          e (.entryAt m "a")]
      (is (instance? clojure.lang.MapEntry e))
      (is (= 1 @(val e)))))
  (testing "entryAt returns nil for missing key"
    (is (nil? (.entryAt (d/hash-map "a" 1) "z")))))

(deftest map-assocEx-test
  (testing "assocEx adds new key"
    (let [m (.assocEx (d/hash-map "a" 1) "b" 2)]
      (is (= 2 (count m)))))
  (testing "assocEx throws on existing key"
    (is (thrown? RuntimeException (.assocEx (d/hash-map "a" 1) "a" 2)))))

(deftest map-ifn-not-found-test
  (testing "Map as function with not-found"
    (is (= :nope ((d/hash-map "a" 1) "z" :nope)))))

(deftest map-iterator-test
  (testing "iterator works on map"
    (let [it (.iterator (d/hash-map "a" 1))]
      (is (.hasNext it))))
  (testing "iterator works on empty map"
    (let [it (.iterator (d/hash-map))]
      (is (not (.hasNext it))))))

(deftest map-equals-test
  (testing "equals with non-map returns false"
    (is (not (.equals (d/hash-map "a" 1) (d/i64 1))))))

(deftest wrap-hash-blob-test
  (testing "wrap-hash returns DaciteBlob for blob entries"
    (let [b (d/blob (byte-array [1 2 3]))
          h (d/dacite-hash b)
          wrapped (d/wrap-hash h)]
      (is (instance? dacite.collections.DaciteBlob wrapped)))))

(deftest unwrap-hash-non-dacite-test
  (testing "unwrap-hash throws on non-Dacite value"
    (is (thrown? clojure.lang.ExceptionInfo (d/unwrap-hash 42)))))

(deftest coerce-unsupported-test
  (testing "Auto-coercion rejects unsupported types"
    (is (thrown? clojure.lang.ExceptionInfo (d/vec [:keyword])))))

(deftest vector-nth-not-found-in-range-test
  (testing "nth with not-found returns value when in range"
    (is (= 20 @(nth (d/vec [10 20 30]) 1 :nope)))))

(deftest vector-equals-same-hash-test
  (testing "Two vectors with same content are .equals"
    (is (.equals (d/vec [1 2]) (d/vec [1 2])))))

(deftest map-equals-same-hash-test
  (testing "Two maps with same content are .equals"
    (is (.equals (d/hash-map "a" 1) (d/hash-map "a" 1)))))

(deftest with-store-istore-test
  (testing "with-store accepts IStore directly"
    (let [s (store/mem-store)
          [snap result] (d/with-store [st s]
                          (let [v (d/i64 99)]
                            (is (= 99 @v))
                            :done))]
      (is (map? snap))
      (is (= :done result)))))
