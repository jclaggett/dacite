(ns dacite.core-test
  "JVM collection-interface and convert tests for Dacite values."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [dacite.convert :as convert]
            [dacite.hash :as hash]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.value.types :as types]))

;; Each test runs against a fresh in-memory store.
(use-fixtures :each (fn [f]
                      (binding [store/*store* (store/mem-store)]
                        (f))))

(defn- ctx [] store/*store*)

(defn- value-type [x]
  (v/type x))

(defn- value-size [x]
  (types/dacite-size (store/s-get (v/dacite-store x) (v/hash x))))

;; =============================================================================
;; Scalars
;; =============================================================================

(deftest null-test
  (testing "Null construction"
    (let [v (v/null (ctx))]
      (is (= "null" (value-type v)))
      (is (nil? (v/realize v))))))

(deftest bool-test
  (testing "Boolean construction"
    (is (= true (v/realize (v/bool (ctx) true))))
    (is (= false (v/realize (v/bool (ctx) false))))))

(deftest integer-test
  (testing "Integer constructors"
    (is (= 42 (v/realize (v/i64 (ctx) 42))))
    (is (= (byte 1) (v/realize (v/i8 (ctx) 1))))
    (is (= (short 1) (v/realize (v/i16 (ctx) 1))))
    (is (= (int 1) (v/realize (v/i32 (ctx) 1))))))

(deftest unsigned-test
  (testing "Unsigned integer constructors"
    (is (= 255 (v/realize (v/u8 (ctx) 255))))
    (is (= 65535 (v/realize (v/u16 (ctx) 65535))))
    (is (= 4294967295 (v/realize (v/u32 (ctx) 4294967295))))
    (is (= 0 (v/realize (v/u64 (ctx) 0))))))

(deftest unsigned-bounds-test
  (testing "Unsigned integers reject out-of-bounds"
    (is (thrown? AssertionError (v/u8 (ctx) 256)))
    (is (thrown? AssertionError (v/u8 (ctx) -1)))
    (is (thrown? AssertionError (v/u16 (ctx) 65536)))))

(deftest u256-test
  (testing "u256 for hash-as-data"
    (let [data (byte-array (map unchecked-byte (hash/longs->bytes [1 2 3 4])))
          v (v/u256 (ctx) data)]
      (is (= "u256" (value-type v)))
      (is (= 32 (alength ^bytes (v/realize v)))))))

(deftest float-test
  (testing "Float constructors"
    (is (= "f32" (value-type (v/f32 (ctx) 1.5))))
    (is (= "f64" (value-type (v/f64 (ctx) 3.14))))))

(deftest char-test
  (testing "Character constructor"
    (is (= \a (v/realize (v/char (ctx) \a))))
    (is (thrown? AssertionError (v/char (ctx) "a")))))

(deftest scalar-equality-test
  (testing "Same value = equal"
    (is (= (v/i64 (ctx) 42) (v/i64 (ctx) 42))))
  (testing "Different value = not equal"
    (is (not= (v/i64 (ctx) 42) (v/i64 (ctx) 43)))))

(deftest scalar-type-test
  (testing "scalar-type returns type name"
    (is (= "i64" (value-type (v/i64 (ctx) 42))))
    (is (= "null" (value-type (v/null (ctx)))))))

(deftest size-bytes-scalar-test
  (testing "Scalar size-bytes"
    (is (= 8 (value-size (v/i64 (ctx) 42))))
    (is (= 1 (value-size (v/bool (ctx) true))))
    (is (= 0 (value-size (v/null (ctx)))))
    (is (= 4 (value-size (v/f32 (ctx) 1.0)))))
  (testing "String size-bytes (UTF-8)"
    (is (= 5 (value-size (v/string (ctx) "hello"))))
    (is (= 0 (value-size (v/string (ctx) ""))))))

(deftest size-bytes-vector-test
  (testing "Vector size-bytes is sum of element sizes"
    (let [v (apply v/vector (ctx) [1 2 3])] ;; 3 x i64 = 24
      (is (= 24 (value-size v)))))
  (testing "Empty vector is 0 bytes"
    (is (= 0 (value-size (v/vector (ctx)))))))

(deftest size-bytes-map-test
  (testing "Map size-bytes includes keys and values"
    (let [m (v/map (ctx) "a" 1)] ;; "a" = 1 byte, i64 = 8 bytes
      (is (= 9 (value-size m)))))
  (testing "Empty map is 0 bytes"
    (is (= 0 (value-size (v/map (ctx)))))))

(deftest size-bytes-nested-test
  (testing "Nested structure accumulates sizes"
    (let [v (apply v/vector (ctx) [1 2])       ;; 16 bytes
          m (v/map (ctx) "k" v)] ;; "k"=1 + vec=16 = 17
      (is (= 16 (value-size v)))
      (is (= 17 (value-size m))))))

(deftest scalar-hash-eq-test
  (testing "Same values have same hasheq"
    (is (= (hash (v/i64 (ctx) 42)) (hash (v/i64 (ctx) 42))))))

;; =============================================================================
;; Strings
;; =============================================================================

(deftest str-test
  (testing "String construction"
    (let [s (v/string (ctx) "hello")]
      (is (= "string" (value-type s)))
      (is (= "hello" (convert/dac->clj s))))))

(deftest str-count-test
  (testing "String supports count"
    (is (= 5 (count (v/string (ctx) "hello"))))
    (is (= 0 (count (v/string (ctx) ""))))))

(deftest str-equality-test
  (testing "Same string = equal"
    (is (= (v/string (ctx) "hello") (v/string (ctx) "hello")))))

(deftest str-char-sequence-test
  (testing "CharSequence interface"
    (let [s (v/string (ctx) "hello")]
      (is (= \h (.charAt s 0)))
      (is (= "ell" (clojure.core/str (.subSequence s 1 4)))))))

(deftest str-seq-test
  (testing "String is seqable, returns char scalars"
    (let [s (seq (v/string (ctx) "abc"))]
      (is (= 3 (clojure.core/count s)))
      (is (every? #(= "char" (v/type %)) s))
      (is (= [\a \b \c] (mapv v/realize s))))))

;; =============================================================================
;; Blobs
;; =============================================================================

(deftest blob-test
  (testing "Blob construction"
    (let [b (v/blob (ctx) (byte-array [1 2 3]))]
      (is (= "blob" (value-type b)))
      (is (= 3 (count b))))))

(deftest blob-content-test
  (testing "dac->clj returns byte array"
    (let [b (v/blob (ctx) (byte-array [10 20 30]))]
      (is (bytes? (convert/dac->clj b)))
      (is (= [10 20 30] (clojure.core/vec (convert/dac->clj b)))))))

(deftest blob-empty-test
  (testing "Empty blob"
    (let [b (v/blob (ctx) (byte-array 0))]
      (is (= 0 (count b)))
      (is (nil? (seq b))))))

(deftest blob-seq-test
  (testing "Seq returns u8 scalar wrappers"
    (let [s (seq (v/blob (ctx) (byte-array [1 2 3])))]
      (is (= 3 (clojure.core/count s)))
      (is (every? #(= "u8" (v/type %)) s))
      (is (= [1 2 3] (mapv v/realize s))))))

(deftest blob-equality-test
  (testing "Same bytes = equal"
    (is (= (v/blob (ctx) (byte-array [1 2 3])) (v/blob (ctx) (byte-array [1 2 3])))))
  (testing "Different bytes = not equal"
    (is (not= (v/blob (ctx) (byte-array [1 2])) (v/blob (ctx) (byte-array [3 4]))))))

(deftest blob-size-bytes-test
  (testing "Size equals byte count"
    (is (= 5 (value-size (v/blob (ctx) (byte-array [0 0 0 0 0])))))
    (is (= 0 (value-size (v/blob (ctx) (byte-array 0)))))))

(deftest blob-toString-test
  (testing "toString is a non-empty string"
    (is (string? (.toString (v/blob (ctx) (byte-array [1 2 3])))))))

;; =============================================================================
;; Vectors
;; =============================================================================

(deftest vec-test
  (testing "Vector construction"
    (let [v (apply v/vector (ctx) [1 2 3])]
      (is (= "vector" (value-type v)))
      (is (= 3 (count v))))))

(deftest vec-nth-test
  (testing "nth returns wrapped Dacite value"
    (let [v (apply v/vector (ctx) [10 20 30])]
      (is (= "i64" (v/type (nth v 0))))
      (is (= 10 (v/realize (nth v 0))))
      (is (= 30 (v/realize (nth v 2)))))))

(deftest vec-conj-test
  (testing "conj appends"
    (let [v (apply v/vector (ctx) [1 2])
          v2 (conj v 3)]
      (is (= 3 (count v2)))
      (is (= 3 (v/realize (nth v2 2)))))))

(deftest vec-immutable-test
  (testing "conj doesn't modify original"
    (let [v (apply v/vector (ctx) [1 2])
          _v2 (conj v 3)]
      (is (= 2 (count v))))))

(deftest vec-empty-test
  (testing "Empty vector"
    (let [v (v/vector (ctx))]
      (is (= 0 (count v)))
      (is (nil? (seq v))))))

(deftest vec-ifn-test
  (testing "Vector as function"
    (is (= 20 (v/realize ((apply v/vector (ctx) [10 20 30]) 1))))))

(deftest vec-ilookup-test
  (testing "get works on vector"
    (let [v (apply v/vector (ctx) [10 20 30])]
      (is (= 20 (v/realize (get v 1))))
      (is (nil? (get v 99))))))

(deftest vec-peek-pop-test
  (testing "peek and pop"
    (let [v (apply v/vector (ctx) [1 2 3])]
      (is (= 3 (v/realize (peek v))))
      (let [p (pop v)]
        (is (= 2 (count p)))
        (is (= 2 (v/realize (peek p))))))))

(deftest vec-assoc-test
  (testing "assoc replaces element"
    (let [v (assoc (apply v/vector (ctx) [1 2 3]) 1 99)]
      (is (= 3 (count v)))
      (is (= 99 (v/realize (nth v 1)))))))

(deftest vec-seq-test
  (testing "seq returns wrapped elements"
    (let [s (seq (apply v/vector (ctx) [10 20 30]))]
      (is (= 3 (clojure.core/count s)))
      (is (every? #(= "i64" (v/type %)) s))
      (is (= [10 20 30] (mapv v/realize s))))))

(deftest vec-contains-key-test
  (testing "containsKey on vector"
    (let [v (apply v/vector (ctx) [10 20])]
      (is (.containsKey v 0))
      (is (.containsKey v 1))
      (is (not (.containsKey v 2))))))

(deftest vec-equality-test
  (testing "Same elements = equal"
    (is (= (apply v/vector (ctx) [1 2 3]) (apply v/vector (ctx) [1 2 3]))))
  (testing "Different order = not equal"
    (is (not= (apply v/vector (ctx) [1 2]) (apply v/vector (ctx) [2 1])))))

(deftest vec-toString-test
  (testing "toString is a non-empty string"
    (is (string? (clojure.core/str (apply v/vector (ctx) [1 2 3]))))))

(deftest vec-mixed-types-test
  (testing "Mixed auto-coerced types"
    (let [v (apply v/vector (ctx) [nil true 42])]
      (is (nil? (v/realize (nth v 0))))
      (is (= true (v/realize (nth v 1))))
      (is (= 42 (v/realize (nth v 2)))))))

(deftest vec-nested-test
  (testing "Vectors can contain vectors"
    (let [inner (apply v/vector (ctx) [1 2])
          outer (v/vector (ctx) inner)]
      (is (= 1 (count outer)))
      (let [inner' (nth outer 0)]
        (is (= "vector" (v/type inner')))
        (is (= 2 (count inner')))))))

(deftest vec-accepts-dacite-values-test
  (testing "vec accepts Dacite values directly"
    (let [a (v/i64 (ctx) 1)
          b (v/i64 (ctx) 2)
          v (apply v/vector (ctx) [a b])]
      (is (= 2 (count v)))
      (is (= 1 (v/realize (nth v 0)))))))

;; =============================================================================
;; Maps
;; =============================================================================

(deftest hash-map-test
  (testing "Map construction"
    (let [m (v/map (ctx) "name" "Alice" "age" 30)]
      (is (= "map" (value-type m)))
      (is (= 2 (count m))))))

(deftest hash-map-get-test
  (testing "get works on map"
    (let [m (v/map (ctx) "name" "Alice" "age" 30)]
      (let [name-val (get m "name")]
        (is (= "string" (v/type name-val)))
        (is (= "Alice" (convert/dac->clj name-val))))
      (let [age-val (get m "age")]
        (is (= "i64" (v/type age-val)))
        (is (= 30 (v/realize age-val)))))))

(deftest hash-map-get-missing-test
  (testing "get returns nil for missing key"
    (is (nil? (get (v/map (ctx) "a" 1) "missing")))))

(deftest hash-map-get-not-found-test
  (testing "get returns not-found for missing key"
    (is (= :nope (get (v/map (ctx) "a" 1) "missing" :nope)))))

(deftest hash-map-ifn-test
  (testing "Map as function"
    (is (= 42 (v/realize ((v/map (ctx) "x" 42) "x"))))))

(deftest hash-map-assoc-test
  (testing "assoc on map"
    (let [m (assoc (v/map (ctx) "a" 1) "b" 2)]
      (is (= 2 (count m)))
      (is (= 2 (v/realize (get m "b")))))))

(deftest hash-map-dissoc-test
  (testing "dissoc on map"
    (let [m (dissoc (v/map (ctx) "a" 1 "b" 2) "a")]
      (is (= 1 (count m)))
      (is (nil? (get m "a")))
      (is (= 2 (v/realize (get m "b")))))))

(deftest hash-map-conj-test
  (testing "conj with [k v] pair"
    (let [m (conj (v/map (ctx) "a" 1) ["b" 2])]
      (is (= 2 (count m))))))

(deftest hash-map-contains-test
  (testing "containsKey"
    (let [m (v/map (ctx) "a" 1)]
      (is (.containsKey m "a"))
      (is (not (.containsKey m "z"))))))

(deftest hash-map-empty-test
  (testing "Empty map"
    (let [m (v/map (ctx))]
      (is (= 0 (count m)))
      (is (nil? (seq m))))))

(deftest hash-map-seq-test
  (testing "seq returns MapEntry elements"
    (let [s (seq (v/map (ctx) "x" 10))]
      (is (= 1 (clojure.core/count s)))
      (let [entry (first s)]
        (is (instance? clojure.lang.MapEntry entry))
        (is (= "x" (convert/dac->clj (key entry))))
        (is (= 10 (v/realize (val entry))))))))

(deftest hash-map-equality-test
  (testing "Same entries = equal"
    (is (= (v/map (ctx) "a" 1 "b" 2) (v/map (ctx) "a" 1 "b" 2)))))

(deftest hash-map-immutable-test
  (testing "assoc doesn't modify original"
    (let [m (v/map (ctx) "a" 1)
          _m2 (assoc m "b" 2)]
      (is (= 1 (count m))))))

(deftest hash-map-toString-test
  (testing "toString"
    (is (string? (clojure.core/str (v/map (ctx) "x" 10))))))

(deftest hash-map-accepts-dacite-values-test
  (testing "hash-map accepts Dacite values as keys/values"
    (let [k (v/string (ctx) "key")
          v (v/i64 (ctx) 42)
          m (v/map (ctx) k v)]
      (is (= 1 (count m)))
      (is (= 42 (v/realize (get m "key")))))))

;; =============================================================================
;; Sets
;; =============================================================================

(deftest dacite-set-test
  (testing "Set construction"
    (let [s (v/set (ctx) 1 2 3)]
      (is (= "set" (value-type s)))
      (is (= 3 (count s))))))

(deftest dacite-set-empty-test
  (testing "Empty set"
    (let [s (v/set (ctx))]
      (is (= 0 (count s)))
      (is (nil? (seq s))))))

(deftest dacite-set-lookup-test
  (testing "Lookup returns element for member"
    (let [s (v/set (ctx) 10 20 30)]
      (is (some? (get s 10)))
      (is (some? (get s 20)))))
  (testing "Lookup returns nil for non-member"
    (is (nil? (get (v/set (ctx) 1 2) 99)))))

(deftest dacite-set-lookup-not-found-test
  (testing "Lookup with not-found"
    (is (= :nope (get (v/set (ctx) 1 2) 99 :nope)))))

(deftest dacite-set-ifn-test
  (testing "Set as function"
    (let [s (v/set (ctx) 10 20)]
      (is (some? (s 10)))
      (is (nil? (s 99))))))

(deftest dacite-set-seq-test
  (testing "Seq returns wrapped elements"
    (let [s (v/set (ctx) 10 20 30)
          elems (seq s)]
      (is (= 3 (clojure.core/count elems)))
      (is (= #{10 20 30} (into #{} (map v/realize) elems))))))

(deftest dacite-set-content-test
  (testing "dac->clj returns Clojure set"
    (is (= #{1 2 3} (convert/dac->clj (v/set (ctx) 1 2 3))))))

(deftest dacite-set-equality-test
  (testing "Same elements = equal"
    (is (= (v/set (ctx) 1 2 3) (v/set (ctx) 1 2 3))))
  (testing "Different elements = not equal"
    (is (not= (v/set (ctx) 1 2) (v/set (ctx) 3 4)))))

(deftest dacite-set-conj-test
  (testing "conj adds element"
    (let [s (conj (v/set (ctx) 1 2) 3)]
      (is (= 3 (count s)))
      (is (some? (get s 3))))))

(deftest dacite-set-conj-duplicate-test
  (testing "conj with existing element is idempotent"
    (let [s (conj (v/set (ctx) 1 2) 1)]
      (is (= 2 (count s))))))

(deftest dacite-set-empty-collection-test
  (testing "empty returns empty set"
    (let [s (v/set (ctx) 1 2 3)
          e (.empty s)]
      (is (= 0 (count e)))
      (is (= "set" (v/type e))))))

(deftest dacite-set-hashCode-test
  (testing "hashCode works"
    (is (integer? (.hashCode (v/set (ctx) 1 2))))))

(deftest dacite-set-hasheq-test
  (testing "hasheq works"
    (is (integer? (hash (v/set (ctx) 1 2))))))

(deftest dacite-set-toString-test
  (testing "toString returns readable representation"
    (is (string? (.toString (v/set (ctx) 1 2))))))

(deftest dacite-set-iterator-test
  (testing "Iterator works on non-empty set"
    (let [it (.iterator (v/set (ctx) 1 2 3))]
      (is (.hasNext it))))
  (testing "Iterator works on empty set"
    (let [it (.iterator (v/set (ctx)))]
      (is (not (.hasNext it))))))

(deftest dacite-set-size-bytes-test
  (testing "Set size-bytes reflects element sizes (self-map: key+value)"
    (let [s (v/set (ctx) 1 2 3)] ;; 3 x i64, self-map counts k+v = 48
      (is (= 48 (value-size s))))))

(deftest dacite-set-with-strings-test
  (testing "Set with string elements"
    (let [s (v/set (ctx) "a" "b" "c")]
      (is (= 3 (count s)))
      (is (= #{"a" "b" "c"} (convert/dac->clj s))))))

(deftest neg-test
  (testing "Neg sentinel construction"
    (let [n (v/negative (ctx))]
      (is (= "negative" (value-type n)))
      (is (= :dacite/negative (v/realize n)))))
  (testing "Neg is deterministic"
    (is (= (v/negative (ctx)) (v/negative (ctx))))))

;; =============================================================================
;; dac->clj / clj->dac for sets
;; =============================================================================

(deftest dac->clj-set-test
  (testing "Sets unwrap to Clojure sets"
    (is (= #{1 2 3} (convert/dac->clj (v/set (ctx) 1 2 3))))))

(deftest dac->clj-empty-set-test
  (testing "Empty set unwraps"
    (is (= #{} (convert/dac->clj (v/set (ctx)))))))

(deftest clj->dac-set-test
  (testing "Clojure sets wrap to a Dacite set"
    (let [s (convert/clj->dac #{1 2 3})]
      (is (= "set" (v/type s)))
      (is (= #{1 2 3} (convert/dac->clj s))))))

(deftest clj->dac-set-round-trip-test
  (testing "Round-trip: clj set -> dac -> clj"
    (is (= #{"a" "b"} (convert/dac->clj (convert/clj->dac #{"a" "b"}))))))

;; =============================================================================
;; with-store isolation
;; =============================================================================

(deftest with-store-isolation-test
  (testing "with-store creates isolated context"
    (let [_ (v/i64 (ctx) 42)
          [iso-store _result] (store/with-store [_s {}]
                                (apply v/vector (ctx) [1 2 3]))]
      ;; isolated store should not contain the global i64
      (is (not (contains? iso-store (v/hash (v/i64 (ctx) 42))))))))

(deftest with-store-returns-store-and-result-test
  (testing "Returns [final-store last-value]"
    (let [[store result] (store/with-store [_s {}]
                           (let [v (v/i64 (ctx) 99)]
                             (is (= 99 (v/realize v)))
                             v))]
      (is (map? store))
      (is (= "i64" (v/type result))))))

;; =============================================================================
;; dac->clj
;; =============================================================================

(deftest dac->clj-scalar-test
  (testing "Scalars unwrap"
    (is (= 42 (convert/dac->clj (v/i64 (ctx) 42))))
    (is (= true (convert/dac->clj (v/bool (ctx) true))))
    (is (nil? (convert/dac->clj (v/null (ctx)))))
    (is (= \a (convert/dac->clj (v/char (ctx) \a))))))

(deftest dac->clj-string-test
  (testing "Strings unwrap"
    (is (= "hello" (convert/dac->clj (v/string (ctx) "hello"))))))

(deftest dac->clj-vector-test
  (testing "Vectors recursively unwrap"
    (is (= [1 2 3] (convert/dac->clj (apply v/vector (ctx) [1 2 3]))))))

(deftest dac->clj-nested-vector-test
  (testing "Nested vectors unwrap recursively"
    (let [inner (apply v/vector (ctx) [1 2])
          outer (v/vector (ctx) inner (v/i64 (ctx) 3))]
      (is (= [[1 2] 3] (convert/dac->clj outer))))))

(deftest dac->clj-map-test
  (testing "Maps recursively unwrap"
    (is (= {"name" "Alice" "age" 30}
           (convert/dac->clj (v/map (ctx) "name" "Alice" "age" 30))))))

(deftest dac->clj-blob-test
  (testing "Blobs unwrap to byte array"
    (let [result (convert/dac->clj (v/blob (ctx) (byte-array [1 2 3])))]
      (is (bytes? result))
      (is (= [1 2 3] (clojure.core/vec result))))))

(deftest dac->clj-empty-collections-test
  (testing "Empty collections"
    (is (= [] (convert/dac->clj (v/vector (ctx)))))
    (is (= {} (convert/dac->clj (v/map (ctx)))))))

(deftest dac->clj-passthrough-test
  (testing "Non-Dacite values pass through"
    (is (= 42 (convert/dac->clj 42)))
    (is (= "hi" (convert/dac->clj "hi")))))

(deftest dac->clj-max-bytes-test
  (testing "Exceeding max-bytes throws"
    (let [v (apply v/vector (ctx) (range 100))]
      ;; 100 x i64 = 800 bytes
      (is (thrown? clojure.lang.ExceptionInfo (convert/dac->clj v 100)))
      (is (= (clojure.core/vec (range 100)) (convert/dac->clj v 1000)))))
  (testing "Default limit allows reasonable sizes"
    (is (= [1 2 3] (convert/dac->clj (apply v/vector (ctx) [1 2 3])))))
  (testing "Non-Dacite values bypass size check"
    (is (= 42 (convert/dac->clj 42 0))))
  (testing "Scalars with tiny limit still work (size = 8 bytes)"
    (is (= 42 (convert/dac->clj (v/i64 (ctx) 42) 10)))
    (is (thrown? clojure.lang.ExceptionInfo (convert/dac->clj (v/i64 (ctx) 42) 1)))))

;; =============================================================================
;; clj->dac
;; =============================================================================

(deftest clj->dac-scalar-test
  (testing "Scalars wrap"
    (is (= "i64" (v/type (convert/clj->dac 42))))
    (is (= 42 (v/realize (convert/clj->dac 42))))
    (is (= "bool" (v/type (convert/clj->dac true))))
    (is (= "null" (v/type (convert/clj->dac nil))))))

(deftest clj->dac-string-test
  (testing "Strings wrap"
    (is (= "string" (v/type (convert/clj->dac "hello"))))
    (is (= "hello" (convert/dac->clj (convert/clj->dac "hello"))))))

(deftest clj->dac-vector-test
  (testing "Vectors wrap recursively"
    (let [v (convert/clj->dac [1 2 3])]
      (is (= "vector" (v/type v)))
      (is (= 3 (count v)))
      (is (= [1 2 3] (convert/dac->clj v))))))

(deftest clj->dac-nested-vector-test
  (testing "Nested vectors wrap recursively"
    (let [v (convert/clj->dac [[1 2] [3 4]])]
      (is (= [[1 2] [3 4]] (convert/dac->clj v))))))

(deftest clj->dac-map-test
  (testing "Maps wrap recursively"
    (let [m (convert/clj->dac {"a" 1 "b" 2})]
      (is (= "map" (v/type m)))
      (is (= {"a" 1 "b" 2} (convert/dac->clj m))))))

(deftest clj->dac-nested-map-test
  (testing "Nested structures wrap recursively"
    (let [data {"users" [{"name" "Alice"} {"name" "Bob"}]}
          dac (convert/clj->dac data)]
      (is (= data (convert/dac->clj dac))))))

(deftest clj->dac-blob-test
  (testing "Byte arrays wrap to a Dacite blob"
    (let [b (convert/clj->dac (byte-array [10 20]))]
      (is (= "blob" (v/type b)))
      (is (= [10 20] (clojure.core/vec (convert/dac->clj b)))))))

(deftest clj->dac-idempotent-test
  (testing "Already-Dacite values pass through"
    (let [v (v/i64 (ctx) 42)]
      (is (identical? v (convert/clj->dac v))))))

(deftest clj->dac-round-trip-test
  (testing "Round-trip: clj -> dac -> clj"
    (let [data [1 "hello" nil true [2 3] {"a" 4}]]
      (is (= data (convert/dac->clj (convert/clj->dac data)))))))

(deftest clj->dac-float-test
  (testing "Floats wrap to f64"
    (let [v (convert/clj->dac (float 1.5))]
      (is (= "f64" (v/type v)))
      (is (= 1.5 (v/realize v)))))
  (testing "Doubles wrap to f64"
    (let [v (convert/clj->dac 3.14)]
      (is (= "f64" (v/type v)))
      (is (= 3.14 (v/realize v))))))

(deftest clj->dac-char-test
  (testing "Chars wrap to dacite-char"
    (let [v (convert/clj->dac \x)]
      (is (= "char" (v/type v)))
      (is (= \x (v/realize v))))))

(deftest clj->dac-list-test
  (testing "Lists (sequential) wrap to a Dacite vector"
    (let [v (convert/clj->dac '(1 2 3))]
      (is (= "vector" (v/type v)))
      (is (= [1 2 3] (convert/dac->clj v))))))

(deftest clj->dac-unsupported-test
  (testing "Unsupported types throw"
    (is (thrown? clojure.lang.ExceptionInfo (convert/clj->dac :keyword)))))

;; =============================================================================
;; Identity
;; =============================================================================

(deftest dacite-hash-test
  (testing "dacite-hash returns the raw hash"
    (let [v (v/i64 (ctx) 42)
          h (v/hash v)]
      (is (some? h))
      (is (= h (v/hash v)))))
  (testing "Same value same hash"
    (is (= (v/hash (v/i64 (ctx) 42)) (v/hash (v/i64 (ctx) 42)))))
  (testing "Different value different hash"
    (is (not= (v/hash (v/i64 (ctx) 42)) (v/hash (v/i64 (ctx) 43))))))

;; =============================================================================
;; Uncovered edge cases
;; =============================================================================

(deftest scalar-toString-test
  (testing "Scalar toString returns readable representation"
    (let [s (.toString (v/i64 (ctx) 42))]
      (is (string? s))
      (is (.contains s "42")))))

(deftest scalar-hashCode-test
  (testing "Scalar hashCode works"
    (is (integer? (.hashCode (v/i64 (ctx) 42))))))

(deftest string-hashCode-test
  (testing "String hashCode works"
    (is (integer? (.hashCode (v/string (ctx) "hello"))))))

(deftest string-hasheq-test
  (testing "String hasheq works"
    (is (integer? (hash (v/string (ctx) "hello"))))))

(deftest string-length-test
  (testing "CharSequence.length works"
    (is (= 5 (.length (v/string (ctx) "hello"))))))

(deftest blob-hashCode-test
  (testing "Blob hashCode works"
    (is (integer? (.hashCode (v/blob (ctx) (byte-array [1 2])))))))

(deftest blob-hasheq-test
  (testing "Blob hasheq works"
    (is (integer? (hash (v/blob (ctx) (byte-array [1 2])))))))

(deftest vector-hash-test
  (testing "Vector exposes a stable content hash"
    (let [v (apply v/vector (ctx) [1 2])]
      (is (some? (v/hash v))))))

(deftest vector-hasheq-test
  (testing "Vector hasheq works"
    (is (integer? (hash (apply v/vector (ctx) [1 2]))))))

(deftest vector-hashCode-test
  (testing "Vector hashCode works"
    (is (integer? (.hashCode (apply v/vector (ctx) [1 2]))))))

(deftest vector-nth-not-found-test
  (testing "nth with not-found on out-of-bounds"
    (is (= :nope (nth (apply v/vector (ctx) [1 2]) 99 :nope))))
  (testing "nth with not-found on negative"
    (is (= :nope (nth (apply v/vector (ctx) [1 2]) -1 :nope)))))

(deftest vector-empty-test-2
  (testing "IPersistentCollection.empty returns empty vector"
    (let [v (apply v/vector (ctx) [1 2 3])
          e (.empty v)]
      (is (= 0 (count e)))
      (is (= "vector" (v/type e))))))

(deftest vector-pop-empty-throws-test
  (testing "pop on empty vector throws"
    (is (thrown? IllegalStateException (pop (v/vector (ctx)))))))

(deftest vector-assoc-non-integer-test
  (testing "assoc with non-integer key throws"
    (is (thrown? IllegalArgumentException (assoc (apply v/vector (ctx) [1 2]) "a" 3)))))

(deftest vector-entry-at-test
  (testing "entryAt returns MapEntry for valid index"
    (let [v (apply v/vector (ctx) [10 20])
          e (.entryAt v 1)]
      (is (instance? clojure.lang.MapEntry e))
      (is (= 1 (key e)))
      (is (= 20 (v/realize (val e))))))
  (testing "entryAt returns nil for invalid index"
    (is (nil? (.entryAt (apply v/vector (ctx) [1]) 5)))))

(deftest vector-length-test
  (testing "IPersistentVector.length works"
    (is (= 3 (.length (apply v/vector (ctx) [1 2 3]))))))

(deftest vector-assocN-test
  (testing "assocN works"
    (let [v (.assocN (apply v/vector (ctx) [1 2 3]) 1 99)]
      (is (= 99 (v/realize (nth v 1)))))))

(deftest vector-iterator-test
  (testing "iterator works on non-empty vector"
    (let [it (.iterator (apply v/vector (ctx) [1 2 3]))]
      (is (.hasNext it))
      (is (= 1 (v/realize (.next it))))))
  (testing "iterator works on empty vector"
    (let [it (.iterator (v/vector (ctx)))]
      (is (not (.hasNext it))))))

(deftest vector-equals-test
  (testing "equals with non-vector returns false"
    (is (not (.equals (apply v/vector (ctx) [1]) (v/i64 (ctx) 1))))))

(deftest map-hash-test
  (testing "Map exposes a stable content hash"
    (is (some? (v/hash (v/map (ctx) "a" 1))))))

(deftest map-hasheq-test
  (testing "Map hasheq works"
    (is (integer? (hash (v/map (ctx) "a" 1))))))

(deftest map-hashCode-test
  (testing "Map hashCode works"
    (is (integer? (.hashCode (v/map (ctx) "a" 1))))))

(deftest map-empty-collection-test
  (testing "IPersistentCollection.empty returns empty map"
    (let [m (v/map (ctx) "a" 1)
          e (.empty m)]
      (is (= 0 (count e)))
      (is (= "map" (v/type e))))))

(deftest map-conj-map-entry-test
  (testing "conj with MapEntry"
    (let [m (v/map (ctx) "a" 1)
          entry (first (seq m))
          m2 (conj (v/map (ctx)) entry)]
      (is (= 1 (count m2))))))

(deftest map-conj-invalid-test
  (testing "conj with non-pair throws"
    (is (thrown? IllegalArgumentException (conj (v/map (ctx)) "bad")))))

(deftest map-entry-at-test
  (testing "entryAt returns MapEntry for existing key"
    (let [m (v/map (ctx) "a" 1)
          e (.entryAt m "a")]
      (is (instance? clojure.lang.MapEntry e))
      (is (= 1 (v/realize (val e))))))
  (testing "entryAt returns nil for missing key"
    (is (nil? (.entryAt (v/map (ctx) "a" 1) "z")))))

(deftest map-assocEx-test
  (testing "assocEx adds new key"
    (let [m (.assocEx (v/map (ctx) "a" 1) "b" 2)]
      (is (= 2 (count m)))))
  (testing "assocEx throws on existing key"
    (is (thrown? RuntimeException (.assocEx (v/map (ctx) "a" 1) "a" 2)))))

(deftest map-ifn-not-found-test
  (testing "Map as function with not-found"
    (is (= :nope ((v/map (ctx) "a" 1) "z" :nope)))))

(deftest map-iterator-test
  (testing "iterator works on map"
    (let [it (.iterator (v/map (ctx) "a" 1))]
      (is (.hasNext it))))
  (testing "iterator works on empty map"
    (let [it (.iterator (v/map (ctx)))]
      (is (not (.hasNext it))))))

(deftest map-equals-test
  (testing "equals with non-map returns false"
    (is (not (.equals (v/map (ctx) "a" 1) (v/i64 (ctx) 1))))))

(deftest wrap-hash-blob-test
  (testing "wrap-hash returns a Dacite blob for blob entries"
    (let [b (v/blob (ctx) (byte-array [1 2 3]))
          h (v/hash b)
          wrapped (v/wrap-hash (ctx) h)]
      (is (= "blob" (v/type wrapped))))))

(deftest hash-non-dacite-test
  (testing "v/hash throws on a non-Dacite value"
    (is (thrown? Exception (v/hash 42)))))

(deftest coerce-unsupported-test
  (testing "Auto-coercion rejects unsupported types"
    (is (thrown? clojure.lang.ExceptionInfo (apply v/vector (ctx) [:keyword])))))

(deftest vector-nth-not-found-in-range-test
  (testing "nth with not-found returns value when in range"
    (is (= 20 (v/realize (nth (apply v/vector (ctx) [10 20 30]) 1 :nope))))))

(deftest vector-equals-same-hash-test
  (testing "Two vectors with same content are .equals"
    (is (.equals (apply v/vector (ctx) [1 2]) (apply v/vector (ctx) [1 2])))))

(deftest map-equals-same-hash-test
  (testing "Two maps with same content are .equals"
    (is (.equals (v/map (ctx) "a" 1) (v/map (ctx) "a" 1)))))

(deftest with-store-istore-test
  (testing "with-store accepts IStore directly"
    (let [s (store/mem-store)
          [snap result] (store/with-store [st s]
                          (let [v (v/i64 (ctx) 99)]
                            (is (= 99 (v/realize v)))
                            :done))]
      (is (map? snap))
      (is (= :done result)))))
