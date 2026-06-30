(ns dacite.core-test
  "Tests for the Dacite core API (value-backed facade)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [dacite.core :as d]
            [dacite.hash :as hash]
            [dacite.store :as store]
            [dacite.value.types :as types]))

;; Each test runs against a fresh in-memory store.
(use-fixtures :each (fn [f]
                      (binding [store/*store* (store/mem-store)]
                        (f))))

;; Test helpers for type/size inspection
(defn- value-type [x]
  (d/dacite-type x))

(defn- value-size [x]
  (types/dacite-size (store/s-get (d/dacite-store x) (d/dacite-hash x))))

;; =============================================================================
;; Scalars
;; =============================================================================

(deftest null-test
  (testing "Null construction"
    (let [v (d/null)]
      (is (= "null" (value-type v)))
      (is (nil? (d/realize v))))))

(deftest bool-test
  (testing "Boolean construction"
    (is (= true (d/realize (d/bool true))))
    (is (= false (d/realize (d/bool false))))))

(deftest integer-test
  (testing "Integer constructors"
    (is (= 42 (d/realize (d/i64 42))))
    (is (= (byte 1) (d/realize (d/i8 1))))
    (is (= (short 1) (d/realize (d/i16 1))))
    (is (= (int 1) (d/realize (d/i32 1))))))

(deftest unsigned-test
  (testing "Unsigned integer constructors"
    (is (= 255 (d/realize (d/u8 255))))
    (is (= 65535 (d/realize (d/u16 65535))))
    (is (= 4294967295 (d/realize (d/u32 4294967295))))
    (is (= 0 (d/realize (d/u64 0))))))

(deftest unsigned-bounds-test
  (testing "Unsigned integers reject out-of-bounds"
    (is (thrown? AssertionError (d/u8 256)))
    (is (thrown? AssertionError (d/u8 -1)))
    (is (thrown? AssertionError (d/u16 65536)))))

(deftest u256-test
  (testing "u256 for hash-as-data"
    (let [data (hash/longs->bytes [1 2 3 4])
          v (d/u256 data)]
      (is (= "u256" (value-type v)))
      (is (= 32 (alength ^bytes (d/realize v)))))))

(deftest float-test
  (testing "Float constructors"
    (is (= "f32" (value-type (d/f32 1.5))))
    (is (= "f64" (value-type (d/f64 3.14))))))

(deftest char-test
  (testing "Character constructor"
    (is (= \a (d/realize (d/dacite-char \a))))
    (is (thrown? AssertionError (d/dacite-char "a")))))

(deftest scalar-equality-test
  (testing "Same value = equal"
    (is (= (d/i64 42) (d/i64 42))))
  (testing "Different value = not equal"
    (is (not= (d/i64 42) (d/i64 43)))))

(deftest scalar-type-test
  (testing "scalar-type returns type name"
    (is (= "i64" (value-type (d/i64 42))))
    (is (= "null" (value-type (d/null))))))

(deftest size-bytes-scalar-test
  (testing "Scalar size-bytes"
    (is (= 8 (value-size (d/i64 42))))
    (is (= 1 (value-size (d/bool true))))
    (is (= 0 (value-size (d/null))))
    (is (= 4 (value-size (d/f32 1.0)))))
  (testing "String size-bytes (UTF-8)"
    (is (= 5 (value-size (d/str "hello"))))
    (is (= 0 (value-size (d/str ""))))))

(deftest size-bytes-vector-test
  (testing "Vector size-bytes is sum of element sizes"
    (let [v (d/vec [1 2 3])] ;; 3 x i64 = 24
      (is (= 24 (value-size v)))))
  (testing "Empty vector is 0 bytes"
    (is (= 0 (value-size (d/vec []))))))

(deftest size-bytes-map-test
  (testing "Map size-bytes includes keys and values"
    (let [m (d/hash-map "a" 1)] ;; "a" = 1 byte, i64 = 8 bytes
      (is (= 9 (value-size m)))))
  (testing "Empty map is 0 bytes"
    (is (= 0 (value-size (d/hash-map))))))

(deftest size-bytes-nested-test
  (testing "Nested structure accumulates sizes"
    (let [v (d/vec [1 2])       ;; 16 bytes
          m (d/hash-map "k" v)] ;; "k"=1 + vec=16 = 17
      (is (= 16 (value-size v)))
      (is (= 17 (value-size m))))))

(deftest scalar-hash-eq-test
  (testing "Same values have same hasheq"
    (is (= (hash (d/i64 42)) (hash (d/i64 42))))))

;; =============================================================================
;; Strings
;; =============================================================================

(deftest str-test
  (testing "String construction"
    (let [s (d/str "hello")]
      (is (= "string" (value-type s)))
      (is (= "hello" (d/dac->clj s))))))

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
  (testing "String is seqable, returns char scalars"
    (let [s (seq (d/str "abc"))]
      (is (= 3 (clojure.core/count s)))
      (is (every? #(= "char" (d/dacite-type %)) s))
      (is (= [\a \b \c] (mapv d/realize s))))))

;; =============================================================================
;; Blobs
;; =============================================================================

(deftest blob-test
  (testing "Blob construction"
    (let [b (d/blob (byte-array [1 2 3]))]
      (is (= "blob" (value-type b)))
      (is (= 3 (count b))))))

(deftest blob-content-test
  (testing "dac->clj returns byte array"
    (let [b (d/blob (byte-array [10 20 30]))]
      (is (bytes? (d/dac->clj b)))
      (is (= [10 20 30] (clojure.core/vec (d/dac->clj b)))))))

(deftest blob-empty-test
  (testing "Empty blob"
    (let [b (d/blob (byte-array 0))]
      (is (= 0 (count b)))
      (is (nil? (seq b))))))

(deftest blob-seq-test
  (testing "Seq returns u8 scalar wrappers"
    (let [s (seq (d/blob (byte-array [1 2 3])))]
      (is (= 3 (clojure.core/count s)))
      (is (every? #(= "u8" (d/dacite-type %)) s))
      (is (= [1 2 3] (mapv d/realize s))))))

(deftest blob-equality-test
  (testing "Same bytes = equal"
    (is (= (d/blob (byte-array [1 2 3])) (d/blob (byte-array [1 2 3])))))
  (testing "Different bytes = not equal"
    (is (not= (d/blob (byte-array [1 2])) (d/blob (byte-array [3 4]))))))

(deftest blob-size-bytes-test
  (testing "Size equals byte count"
    (is (= 5 (value-size (d/blob (byte-array [0 0 0 0 0])))))
    (is (= 0 (value-size (d/blob (byte-array 0)))))))

(deftest blob-toString-test
  (testing "toString is a non-empty string"
    (is (string? (.toString (d/blob (byte-array [1 2 3])))))))

;; =============================================================================
;; Vectors
;; =============================================================================

(deftest vec-test
  (testing "Vector construction"
    (let [v (d/vec [1 2 3])]
      (is (= "vector" (value-type v)))
      (is (= 3 (count v))))))

(deftest vec-nth-test
  (testing "nth returns wrapped Dacite value"
    (let [v (d/vec [10 20 30])]
      (is (= "i64" (d/dacite-type (nth v 0))))
      (is (= 10 (d/realize (nth v 0))))
      (is (= 30 (d/realize (nth v 2)))))))

(deftest vec-conj-test
  (testing "conj appends"
    (let [v (d/vec [1 2])
          v2 (conj v 3)]
      (is (= 3 (count v2)))
      (is (= 3 (d/realize (nth v2 2)))))))

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
    (is (= 20 (d/realize ((d/vec [10 20 30]) 1))))))

(deftest vec-ilookup-test
  (testing "get works on vector"
    (let [v (d/vec [10 20 30])]
      (is (= 20 (d/realize (get v 1))))
      (is (nil? (get v 99))))))

(deftest vec-peek-pop-test
  (testing "peek and pop"
    (let [v (d/vec [1 2 3])]
      (is (= 3 (d/realize (peek v))))
      (let [p (pop v)]
        (is (= 2 (count p)))
        (is (= 2 (d/realize (peek p))))))))

(deftest vec-assoc-test
  (testing "assoc replaces element"
    (let [v (assoc (d/vec [1 2 3]) 1 99)]
      (is (= 3 (count v)))
      (is (= 99 (d/realize (nth v 1)))))))

(deftest vec-seq-test
  (testing "seq returns wrapped elements"
    (let [s (seq (d/vec [10 20 30]))]
      (is (= 3 (clojure.core/count s)))
      (is (every? #(= "i64" (d/dacite-type %)) s))
      (is (= [10 20 30] (mapv d/realize s))))))

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
  (testing "toString is a non-empty string"
    (is (string? (clojure.core/str (d/vec [1 2 3]))))))

(deftest vec-mixed-types-test
  (testing "Mixed auto-coerced types"
    (let [v (d/vec [nil true 42])]
      (is (nil? (d/realize (nth v 0))))
      (is (= true (d/realize (nth v 1))))
      (is (= 42 (d/realize (nth v 2)))))))

(deftest vec-nested-test
  (testing "Vectors can contain vectors"
    (let [inner (d/vec [1 2])
          outer (d/vec-of-refs [(d/unwrap-hash inner)])]
      (is (= 1 (count outer)))
      (let [inner' (nth outer 0)]
        (is (= "vector" (d/dacite-type inner')))
        (is (= 2 (count inner')))))))

(deftest vec-accepts-dacite-values-test
  (testing "vec accepts Dacite values directly"
    (let [a (d/i64 1)
          b (d/i64 2)
          v (d/vec [a b])]
      (is (= 2 (count v)))
      (is (= 1 (d/realize (nth v 0)))))))

;; =============================================================================
;; Maps
;; =============================================================================

(deftest hash-map-test
  (testing "Map construction"
    (let [m (d/hash-map "name" "Alice" "age" 30)]
      (is (= "map" (value-type m)))
      (is (= 2 (count m))))))

(deftest hash-map-get-test
  (testing "get works on map"
    (let [m (d/hash-map "name" "Alice" "age" 30)]
      (let [name-val (get m "name")]
        (is (= "string" (d/dacite-type name-val)))
        (is (= "Alice" (d/dac->clj name-val))))
      (let [age-val (get m "age")]
        (is (= "i64" (d/dacite-type age-val)))
        (is (= 30 (d/realize age-val)))))))

(deftest hash-map-get-missing-test
  (testing "get returns nil for missing key"
    (is (nil? (get (d/hash-map "a" 1) "missing")))))

(deftest hash-map-get-not-found-test
  (testing "get returns not-found for missing key"
    (is (= :nope (get (d/hash-map "a" 1) "missing" :nope)))))

(deftest hash-map-ifn-test
  (testing "Map as function"
    (is (= 42 (d/realize ((d/hash-map "x" 42) "x"))))))

(deftest hash-map-assoc-test
  (testing "assoc on map"
    (let [m (assoc (d/hash-map "a" 1) "b" 2)]
      (is (= 2 (count m)))
      (is (= 2 (d/realize (get m "b")))))))

(deftest hash-map-dissoc-test
  (testing "dissoc on map"
    (let [m (dissoc (d/hash-map "a" 1 "b" 2) "a")]
      (is (= 1 (count m)))
      (is (nil? (get m "a")))
      (is (= 2 (d/realize (get m "b")))))))

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
        (is (= "x" (d/dac->clj (key entry))))
        (is (= 10 (d/realize (val entry))))))))

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
  (testing "hash-map accepts Dacite values as keys/values"
    (let [k (d/str "key")
          v (d/i64 42)
          m (d/hash-map k v)]
      (is (= 1 (count m)))
      (is (= 42 (d/realize (get m "key")))))))

;; =============================================================================
;; Sets
;; =============================================================================

(deftest dacite-set-test
  (testing "Set construction"
    (let [s (d/dacite-set 1 2 3)]
      (is (= "set" (value-type s)))
      (is (= 3 (count s))))))

(deftest dacite-set-empty-test
  (testing "Empty set"
    (let [s (d/dacite-set)]
      (is (= 0 (count s)))
      (is (nil? (seq s))))))

(deftest dacite-set-lookup-test
  (testing "Lookup returns element for member"
    (let [s (d/dacite-set 10 20 30)]
      (is (some? (get s 10)))
      (is (some? (get s 20)))))
  (testing "Lookup returns nil for non-member"
    (is (nil? (get (d/dacite-set 1 2) 99)))))

(deftest dacite-set-lookup-not-found-test
  (testing "Lookup with not-found"
    (is (= :nope (get (d/dacite-set 1 2) 99 :nope)))))

(deftest dacite-set-ifn-test
  (testing "Set as function"
    (let [s (d/dacite-set 10 20)]
      (is (some? (s 10)))
      (is (nil? (s 99))))))

(deftest dacite-set-seq-test
  (testing "Seq returns wrapped elements"
    (let [s (d/dacite-set 10 20 30)
          elems (seq s)]
      (is (= 3 (clojure.core/count elems)))
      (is (= #{10 20 30} (into #{} (map d/realize) elems))))))

(deftest dacite-set-content-test
  (testing "dac->clj returns Clojure set"
    (is (= #{1 2 3} (d/dac->clj (d/dacite-set 1 2 3))))))

(deftest dacite-set-equality-test
  (testing "Same elements = equal"
    (is (= (d/dacite-set 1 2 3) (d/dacite-set 1 2 3))))
  (testing "Different elements = not equal"
    (is (not= (d/dacite-set 1 2) (d/dacite-set 3 4)))))

(deftest dacite-set-conj-test
  (testing "conj adds element"
    (let [s (conj (d/dacite-set 1 2) 3)]
      (is (= 3 (count s)))
      (is (some? (get s 3))))))

(deftest dacite-set-conj-duplicate-test
  (testing "conj with existing element is idempotent"
    (let [s (conj (d/dacite-set 1 2) 1)]
      (is (= 2 (count s))))))

(deftest dacite-set-empty-collection-test
  (testing "empty returns empty set"
    (let [s (d/dacite-set 1 2 3)
          e (.empty s)]
      (is (= 0 (count e)))
      (is (= "set" (d/dacite-type e))))))

(deftest dacite-set-hashCode-test
  (testing "hashCode works"
    (is (integer? (.hashCode (d/dacite-set 1 2))))))

(deftest dacite-set-hasheq-test
  (testing "hasheq works"
    (is (integer? (hash (d/dacite-set 1 2))))))

(deftest dacite-set-toString-test
  (testing "toString returns readable representation"
    (is (string? (.toString (d/dacite-set 1 2))))))

(deftest dacite-set-iterator-test
  (testing "Iterator works on non-empty set"
    (let [it (.iterator (d/dacite-set 1 2 3))]
      (is (.hasNext it))))
  (testing "Iterator works on empty set"
    (let [it (.iterator (d/dacite-set))]
      (is (not (.hasNext it))))))

(deftest dacite-set-size-bytes-test
  (testing "Set size-bytes reflects element sizes (self-map: key+value)"
    (let [s (d/dacite-set 1 2 3)] ;; 3 x i64, self-map counts k+v = 48
      (is (= 48 (value-size s))))))

(deftest dacite-set-with-strings-test
  (testing "Set with string elements"
    (let [s (d/dacite-set "a" "b" "c")]
      (is (= 3 (count s)))
      (is (= #{"a" "b" "c"} (d/dac->clj s))))))

(deftest neg-test
  (testing "Neg sentinel construction"
    (let [n (d/neg)]
      (is (= "negative" (value-type n)))
      (is (= :dacite/negative (d/realize n)))))
  (testing "Neg is deterministic"
    (is (= (d/neg) (d/neg)))))

;; =============================================================================
;; dac->clj / clj->dac for sets
;; =============================================================================

(deftest dac->clj-set-test
  (testing "Sets unwrap to Clojure sets"
    (is (= #{1 2 3} (d/dac->clj (d/dacite-set 1 2 3))))))

(deftest dac->clj-empty-set-test
  (testing "Empty set unwraps"
    (is (= #{} (d/dac->clj (d/dacite-set))))))

(deftest clj->dac-set-test
  (testing "Clojure sets wrap to a Dacite set"
    (let [s (d/clj->dac #{1 2 3})]
      (is (= "set" (d/dacite-type s)))
      (is (= #{1 2 3} (d/dac->clj s))))))

(deftest clj->dac-set-round-trip-test
  (testing "Round-trip: clj set -> dac -> clj"
    (is (= #{"a" "b"} (d/dac->clj (d/clj->dac #{"a" "b"}))))))

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
                             (is (= 99 (d/realize v)))
                             v))]
      (is (map? store))
      (is (= "i64" (d/dacite-type result))))))

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
    (is (= "i64" (d/dacite-type (d/clj->dac 42))))
    (is (= 42 (d/realize (d/clj->dac 42))))
    (is (= "bool" (d/dacite-type (d/clj->dac true))))
    (is (= "null" (d/dacite-type (d/clj->dac nil))))))

(deftest clj->dac-string-test
  (testing "Strings wrap"
    (is (= "string" (d/dacite-type (d/clj->dac "hello"))))
    (is (= "hello" (d/dac->clj (d/clj->dac "hello"))))))

(deftest clj->dac-vector-test
  (testing "Vectors wrap recursively"
    (let [v (d/clj->dac [1 2 3])]
      (is (= "vector" (d/dacite-type v)))
      (is (= 3 (count v)))
      (is (= [1 2 3] (d/dac->clj v))))))

(deftest clj->dac-nested-vector-test
  (testing "Nested vectors wrap recursively"
    (let [v (d/clj->dac [[1 2] [3 4]])]
      (is (= [[1 2] [3 4]] (d/dac->clj v))))))

(deftest clj->dac-map-test
  (testing "Maps wrap recursively"
    (let [m (d/clj->dac {"a" 1 "b" 2})]
      (is (= "map" (d/dacite-type m)))
      (is (= {"a" 1 "b" 2} (d/dac->clj m))))))

(deftest clj->dac-nested-map-test
  (testing "Nested structures wrap recursively"
    (let [data {"users" [{"name" "Alice"} {"name" "Bob"}]}
          dac (d/clj->dac data)]
      (is (= data (d/dac->clj dac))))))

(deftest clj->dac-blob-test
  (testing "Byte arrays wrap to a Dacite blob"
    (let [b (d/clj->dac (byte-array [10 20]))]
      (is (= "blob" (d/dacite-type b)))
      (is (= [10 20] (clojure.core/vec (d/dac->clj b)))))))

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
      (is (= "f64" (d/dacite-type v)))
      (is (= 1.5 (d/realize v)))))
  (testing "Doubles wrap to f64"
    (let [v (d/clj->dac 3.14)]
      (is (= "f64" (d/dacite-type v)))
      (is (= 3.14 (d/realize v))))))

(deftest clj->dac-char-test
  (testing "Chars wrap to dacite-char"
    (let [v (d/clj->dac \x)]
      (is (= "char" (d/dacite-type v)))
      (is (= \x (d/realize v))))))

(deftest clj->dac-list-test
  (testing "Lists (sequential) wrap to a Dacite vector"
    (let [v (d/clj->dac '(1 2 3))]
      (is (= "vector" (d/dacite-type v)))
      (is (= [1 2 3] (d/dac->clj v))))))

(deftest clj->dac-unsupported-test
  (testing "Unsupported types throw"
    (is (thrown? clojure.lang.ExceptionInfo (d/clj->dac :keyword)))))

;; =============================================================================
;; set-store! and dacite-hash
;; =============================================================================

(deftest set-store-test
  (testing "set-store! replaces the current store at the root binding"
    ;; set-store! uses alter-var-root, so observe the root directly rather
    ;; than the fixture's thread-local *store* binding.
    (let [original (.getRawRoot #'store/*store*)
          new-store (store/mem-store)]
      (d/set-store! new-store)
      (is (identical? new-store (.getRawRoot #'store/*store*)))
      ;; Restore original
      (d/set-store! original))))

(deftest dacite-hash-test
  (testing "dacite-hash returns the raw hash"
    (let [v (d/i64 42)
          h (d/dacite-hash v)]
      (is (some? h))
      (is (= h (d/dacite-hash v)))))
  (testing "Same value same hash"
    (is (= (d/dacite-hash (d/i64 42)) (d/dacite-hash (d/i64 42)))))
  (testing "Different value different hash"
    (is (not= (d/dacite-hash (d/i64 42)) (d/dacite-hash (d/i64 43))))))

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

(deftest vector-hash-test
  (testing "Vector exposes a stable content hash"
    (let [v (d/vec [1 2])]
      (is (some? (d/dacite-hash v))))))

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
      (is (= "vector" (d/dacite-type e))))))

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
      (is (= 20 (d/realize (val e))))))
  (testing "entryAt returns nil for invalid index"
    (is (nil? (.entryAt (d/vec [1]) 5)))))

(deftest vector-length-test
  (testing "IPersistentVector.length works"
    (is (= 3 (.length (d/vec [1 2 3]))))))

(deftest vector-assocN-test
  (testing "assocN works"
    (let [v (.assocN (d/vec [1 2 3]) 1 99)]
      (is (= 99 (d/realize (nth v 1)))))))

(deftest vector-iterator-test
  (testing "iterator works on non-empty vector"
    (let [it (.iterator (d/vec [1 2 3]))]
      (is (.hasNext it))
      (is (= 1 (d/realize (.next it))))))
  (testing "iterator works on empty vector"
    (let [it (.iterator (d/vec []))]
      (is (not (.hasNext it))))))

(deftest vector-equals-test
  (testing "equals with non-vector returns false"
    (is (not (.equals (d/vec [1]) (d/i64 1))))))

(deftest map-hash-test
  (testing "Map exposes a stable content hash"
    (is (some? (d/dacite-hash (d/hash-map "a" 1))))))

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
      (is (= "map" (d/dacite-type e))))))

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
  (testing "entryAt returns MapEntry for existing key"
    (let [m (d/hash-map "a" 1)
          e (.entryAt m "a")]
      (is (instance? clojure.lang.MapEntry e))
      (is (= 1 (d/realize (val e))))))
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
  (testing "wrap-hash returns a Dacite blob for blob entries"
    (let [b (d/blob (byte-array [1 2 3]))
          h (d/dacite-hash b)
          wrapped (d/wrap-hash h)]
      (is (= "blob" (d/dacite-type wrapped))))))

(deftest unwrap-hash-non-dacite-test
  (testing "unwrap-hash throws on non-Dacite value"
    (is (thrown? clojure.lang.ExceptionInfo (d/unwrap-hash 42)))))

(deftest coerce-unsupported-test
  (testing "Auto-coercion rejects unsupported types"
    (is (thrown? clojure.lang.ExceptionInfo (d/vec [:keyword])))))

(deftest vector-nth-not-found-in-range-test
  (testing "nth with not-found returns value when in range"
    (is (= 20 (d/realize (nth (d/vec [10 20 30]) 1 :nope))))))

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
                            (is (= 99 (d/realize v)))
                            :done))]
      (is (map? snap))
      (is (= :done result)))))
