(ns dacite.core-checks
  "The portable part of `dacite.core-test`, on the JVM and on nbb:
   scalar construction and sizes.

   core_test is mostly the JVM collection interfaces of Dacite values
   (count, nth, get, conj, assoc, seq, IFn, equals, hashCode, toString,
   CharSequence, Iterator, MapEntry) and `dacite.convert` (.clj), which a
   SCI type and nbb do not have; those deftests stay in core_test.clj. The
   JVM runs these checks from core_test.clj, one deftest each under its old
   name. nbb runs them from `dacite.value-cljs-test` (`npm run value:test`).

   core_test runs each test in a fresh `store/*store*` (a fixture); each
   check here does the same (`checks` binds it). Host pieces: bytes are a
   byte-array or a Uint8Array, and a failed `assert` is an AssertionError or
   a js/Error (`assert-fails?`).

   `is` and `testing` are `dacite.store.check`."
  (:require [dacite.hash :as hash]
            [dacite.store :as store]
            [dacite.store.check :refer [is testing]]
            [dacite.value :as v]
            [dacite.value.types :as types]))

(defn- ctx [] store/*store*)

(defn- value-type [x]
  (v/type x))

(defn- value-size [x]
  (types/dacite-size (store/await (store/s-get (v/dacite-store x) (v/hash x)))))

(defn assert-fails?
  "True when `(f)` fails an `assert`."
  [f]
  (try (f) false
       (catch #?(:clj AssertionError :cljs :default) e
         #?(:clj (some? e) :cljs (boolean (re-find #"Assert failed" (str (ex-message e))))))))

(defn host-bytes
  "Host bytes for the ints `xs`."
  [xs]
  #?(:clj (byte-array (map unchecked-byte xs)) :cljs (js/Uint8Array.from (clj->js xs))))

(defn- byte-count
  [bs]
  #?(:clj (alength ^bytes bs) :cljs (.-length bs)))

;; =============================================================================
;; Scalars, sizes, and construction
;; =============================================================================

(defn null-test
  [_]
  (testing "Null construction"
    (let [v (v/null (ctx))]
      (is (= "null" (value-type v)))
      (is (nil? (v/realize v))))))

(defn bool-test
  [_]
  (testing "Boolean construction"
    (is (= true (v/realize (v/bool (ctx) true))))
    (is (= false (v/realize (v/bool (ctx) false))))))

(defn integer-test
  [_]
  (testing "Integer constructors"
    (is (= 42 (v/realize (v/i64 (ctx) 42))))
    (is (= (byte 1) (v/realize (v/i8 (ctx) 1))))
    (is (= (short 1) (v/realize (v/i16 (ctx) 1))))
    (is (= (int 1) (v/realize (v/i32 (ctx) 1))))))

(defn unsigned-test
  [_]
  (testing "Unsigned integer constructors"
    (is (= 255 (v/realize (v/u8 (ctx) 255))))
    (is (= 65535 (v/realize (v/u16 (ctx) 65535))))
    (is (= 4294967295 (v/realize (v/u32 (ctx) 4294967295))))
    (is (= 0 (v/realize (v/u64 (ctx) 0))))))

(defn unsigned-bounds-test
  [_]
  (testing "Unsigned integers reject out-of-bounds"
    (is (assert-fails? #(v/u8 (ctx) 256)))
    (is (assert-fails? #(v/u8 (ctx) -1)))
    (is (assert-fails? #(v/u16 (ctx) 65536)))))

(defn u256-test
  [_]
  (testing "u256 for hash-as-data"
    (let [data #?(:clj (byte-array (map unchecked-byte (hash/longs->bytes [1 2 3 4])))
                  :cljs (host-bytes (hash/longs->bytes [1 2 3 4])))
          v (v/u256 (ctx) data)]
      (is (= "u256" (value-type v)))
      (is (= 32 (byte-count (v/realize v)))))))

(defn float-test
  [_]
  (testing "Float constructors"
    (is (= "f32" (value-type (v/f32 (ctx) 1.5))))
    (is (= "f64" (value-type (v/f64 (ctx) 3.14))))))

(defn char-test
  [_]
  (testing "Character constructor"
    (is (= \a (v/realize (v/char (ctx) \a))))))

(defn scalar-type-test
  [_]
  (testing "scalar-type returns type name"
    (is (= "i64" (value-type (v/i64 (ctx) 42))))
    (is (= "null" (value-type (v/null (ctx)))))))

(defn size-bytes-scalar-test
  [_]
  (testing "Scalar size-bytes"
    (is (= 8 (value-size (v/i64 (ctx) 42))))
    (is (= 1 (value-size (v/bool (ctx) true))))
    (is (= 0 (value-size (v/null (ctx)))))
    (is (= 4 (value-size (v/f32 (ctx) 1.0)))))
  (testing "String size-bytes (UTF-8)"
    (is (= 5 (value-size (v/string (ctx) "hello"))))
    (is (= 0 (value-size (v/string (ctx) ""))))))

(defn size-bytes-vector-test
  [_]
  (testing "Vector size-bytes is sum of element sizes"
    (let [v (apply v/vector (ctx) [1 2 3])] ;; 3 x i64 = 24
      (is (= 24 (value-size v)))))
  (testing "Empty vector is 0 bytes"
    (is (= 0 (value-size (v/vector (ctx)))))))

(defn size-bytes-map-test
  [_]
  (testing "Map size-bytes includes keys and values"
    (let [m (v/map (ctx) "a" 1)] ;; "a" = 1 byte, i64 = 8 bytes
      (is (= 9 (value-size m)))))
  (testing "Empty map is 0 bytes"
    (is (= 0 (value-size (v/map (ctx)))))))

(defn size-bytes-nested-test
  [_]
  (testing "Nested structure accumulates sizes"
    (let [v (apply v/vector (ctx) [1 2])       ;; 16 bytes
          m (v/map (ctx) "k" v)] ;; "k"=1 + vec=16 = 17
      (is (= 16 (value-size v)))
      (is (= 17 (value-size m))))))

(defn blob-size-bytes-test
  [_]
  (testing "Size equals byte count"
    (is (= 5 (value-size (v/blob (ctx) (host-bytes [0 0 0 0 0])))))
    (is (= 0 (value-size (v/blob (ctx) (host-bytes [])))))))

(defn dacite-set-size-bytes-test
  [_]
  (testing "Set size-bytes reflects element sizes (self-map: key+value)"
    (let [s (v/set (ctx) 1 2 3)] ;; 3 x i64, self-map counts k+v = 48
      (is (= 48 (value-size s))))))

(defn neg-test
  [_]
  (testing "Neg sentinel construction"
    (let [n (v/negative (ctx))]
      (is (= "negative" (value-type n)))
      (is (= :dacite/negative (v/realize n))))))

;; Construction of strings and collections: their type. The rest of these
;; deftests is native interfaces or dacite.convert, in core_test.clj.

(defn str-test
  [_]
  (testing "String construction"
    (let [s (v/string (ctx) "hello")]
      (is (= "string" (value-type s))))))

(defn blob-test
  [_]
  (testing "Blob construction"
    (let [b (v/blob (ctx) (host-bytes [1 2 3]))]
      (is (= "blob" (value-type b))))))

(defn vec-test
  [_]
  (testing "Vector construction"
    (let [v (apply v/vector (ctx) [1 2 3])]
      (is (= "vector" (value-type v))))))

(defn hash-map-test
  [_]
  (testing "Map construction"
    (let [m (v/map (ctx) "name" "Alice" "age" 30)]
      (is (= "map" (value-type m))))))

(defn dacite-set-test
  [_]
  (testing "Set construction"
    (let [s (v/set (ctx) 1 2 3)]
      (is (= "set" (value-type s))))))

(def scalar-checks
  {"null-test" null-test
   "bool-test" bool-test
   "integer-test" integer-test
   "unsigned-test" unsigned-test
   "unsigned-bounds-test" unsigned-bounds-test
   "u256-test" u256-test
   "float-test" float-test
   "char-test" char-test
   "scalar-type-test" scalar-type-test
   "size-bytes-scalar-test" size-bytes-scalar-test
   "size-bytes-vector-test" size-bytes-vector-test
   "size-bytes-map-test" size-bytes-map-test
   "size-bytes-nested-test" size-bytes-nested-test
   "blob-size-bytes-test" blob-size-bytes-test
   "dacite-set-size-bytes-test" dacite-set-size-bytes-test
   "neg-test" neg-test
   "str-test" str-test
   "blob-test" blob-test
   "vec-test" vec-test
   "hash-map-test" hash-map-test
   "dacite-set-test" dacite-set-test})


(defn- in-fresh-store
  "`f` with `store/*store*` bound to a fresh mem store, as core_test's
   fixture does."
  [f]
  (fn [env]
    (binding [store/*store* (store/mem-store)]
      (f env))))

(def checks
  (into {}
        (map (fn [[k f]] [k (in-fresh-store f)]))
        scalar-checks))
