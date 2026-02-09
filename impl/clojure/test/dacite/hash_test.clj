(ns dacite.hash-test
  "Generative tests for Dacite hashing invariants."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dacite.hash :as hash]))

;; =============================================================================
;; Generators for leaf types
;; =============================================================================

(def gen-null
  "Generator for null values."
  (gen/return nil))

(def gen-bool
  "Generator for boolean values."
  gen/boolean)

(def gen-i8
  "Generator for signed 8-bit integers."
  (gen/fmap byte (gen/choose -128 127)))

(def gen-i16
  "Generator for signed 16-bit integers."
  (gen/fmap short (gen/choose -32768 32767)))

(def gen-i32
  "Generator for signed 32-bit integers."
  gen/small-integer)

(def gen-i64
  "Generator for signed 64-bit integers."
  gen/large-integer)

(def gen-u8
  "Generator for unsigned 8-bit integers (stored as int)."
  (gen/choose 0 255))

(def gen-u16
  "Generator for unsigned 16-bit integers (stored as int)."
  (gen/choose 0 65535))

(def gen-u32
  "Generator for unsigned 32-bit integers (stored as long)."
  (gen/choose 0 4294967295))

(def gen-u64
  "Generator for unsigned 64-bit integers (stored as BigInteger)."
  (gen/fmap bigint (gen/large-integer* {:min 0})))

(def gen-f32
  "Generator for 32-bit floats (within float range)."
  (gen/fmap float (gen/double* {:min (- Float/MAX_VALUE)
                                :max Float/MAX_VALUE
                                :infinite? false
                                :NaN? false})))

(def gen-f64
  "Generator for 64-bit floats."
  gen/double)

(def gen-char
  "Generator for UTF-8 characters."
  gen/char)

;; Composite generator for any leaf value with its type tag
(def gen-tagged-leaf
  "Generator for [type-keyword value] pairs."
  (gen/one-of
   [(gen/tuple (gen/return :null) gen-null)
    (gen/tuple (gen/return :bool) gen-bool)
    (gen/tuple (gen/return :i8) gen-i8)
    (gen/tuple (gen/return :i16) gen-i16)
    (gen/tuple (gen/return :i32) gen-i32)
    (gen/tuple (gen/return :i64) gen-i64)
    (gen/tuple (gen/return :u8) gen-u8)
    (gen/tuple (gen/return :u16) gen-u16)
    (gen/tuple (gen/return :u32) gen-u32)
    (gen/tuple (gen/return :f32) gen-f32)
    (gen/tuple (gen/return :f64) gen-f64)
    (gen/tuple (gen/return :char) gen-char)]))

;; Generator for 256-bit hashes (as vectors of 4 longs) - the standard form
(def gen-hash
  "Generator for hash as vector of 4 longs."
  (gen/vector gen/large-integer 4))

;; =============================================================================
;; Property tests
;; =============================================================================

(defspec sha256-determinism 100
  (prop/for-all [data (gen/not-empty gen/bytes)]
                (= (hash/sha256 data)
                   (hash/sha256 data))))

(defspec fuse-determinism 100
  (prop/for-all [a gen-hash
                 b gen-hash]
                (= (hash/unchecked-fuse a b)
                   (hash/unchecked-fuse a b))))

(defspec fuse-non-commutative 100
  (prop/for-all [a gen-hash
                 b gen-hash]
    ;; fuse(a,b) ≠ fuse(b,a) unless a = b or degenerate cases
    ;; The formula c0 = a0 + a3*b2 + b0 can be commutative when:
    ;; - a = b, OR
    ;; - a3*b2 = b3*a2 (which happens when these products are equal)
                (let [result-ab (hash/unchecked-fuse a b)
                      result-ba (hash/unchecked-fuse b a)]
                  (or (= a b)
                      (not= result-ab result-ba)
          ;; Allow degenerate cases where a3*b2 = b3*a2
                      (= (unchecked-multiply (nth a 3) (nth b 2))
                         (unchecked-multiply (nth b 3) (nth a 2)))))))

;; Note: [0,0,0,0] is an identity element for fuse:
;;   fuse(a, [0,0,0,0]) = a  (right identity)
;;   fuse([0,0,0,0], b) = b  (left identity)
;; These properties don't apply to real SHA-256 hashes (vanishingly unlikely to be zero)

(defspec fuse-not-identity-left 100
  (prop/for-all [a gen-hash
                 b gen-hash]
    ;; fuse(a,b) ≠ a unless b is zero (right identity)
                (or (= b [0 0 0 0])
                    (not= (hash/unchecked-fuse a b) a))))

(defspec fuse-not-identity-right 100
  (prop/for-all [a gen-hash
                 b gen-hash]
    ;; fuse(a,b) ≠ b unless a is zero (left identity)
                (or (= a [0 0 0 0])
                    (not= (hash/unchecked-fuse a b) b))))

(defspec leaf-hash-determinism 100
  (prop/for-all [[type-kw value] gen-tagged-leaf]
                (= (hash/compute-hash [type-kw value])
                   (hash/compute-hash [type-kw value]))))

(defspec fuse-associative 100
  (prop/for-all [a gen-hash
                 b gen-hash
                 c gen-hash]
    ;; fuse(a, fuse(b, c)) = fuse(fuse(a, b), c)
                (= (hash/unchecked-fuse a (hash/unchecked-fuse b c))
                   (hash/unchecked-fuse (hash/unchecked-fuse a b) c))))

(defspec different-types-different-hashes 100
  (prop/for-all [type1 (gen/elements [:i32 :i64 :u32 :f32 :f64])
                 type2 (gen/elements [:i32 :i64 :u32 :f32 :f64])
                 value gen/small-integer]
    ;; Same numeric value with different types should hash differently
                (or (= type1 type2)
                    (not= (hash/compute-hash [type1 value])
                          (hash/compute-hash [type2 value])))))

;; =============================================================================
;; Unit tests for edge cases  
;; =============================================================================

(deftest test-fuse-basic
  (testing "fuse produces vector of 4 longs"
    (let [a (hash/sha256-str "hello")
          b (hash/sha256-str "world")
          c (hash/fuse a b)]
      (is (vector? c))
      (is (= 4 (count c)))))

  (testing "fuse with same input twice"
    (let [a (hash/sha256-str "test")
          c (hash/fuse a a)]
      (is (= 4 (count c)))
      ;; fuse(a,a) should still be different from a
      (is (not= c a))))

  (testing "fuse is associative"
    (let [a (hash/sha256-str "one")
          b (hash/sha256-str "two")
          c (hash/sha256-str "three")
          ;; fuse(a, fuse(b, c)) should equal fuse(fuse(a, b), c)
          left (hash/fuse a (hash/fuse b c))
          right (hash/fuse (hash/fuse a b) c)]
      (is (= left right)))))

(deftest test-type-hashes-unique
  (testing "different type names produce unique hashes"
    (let [type-names ["dacite.core/i64" "dacite.core/i32" "dacite.core/bool"
                      "dacite.core/null" "dacite.core/string"]
          hashes (map hash/sha256-str type-names)]
      (is (= (count hashes) (count (set hashes)))))))

(deftest test-null-hashing
  (testing "null has consistent hash"
    (let [h1 (hash/compute-hash [:null nil])
          h2 (hash/compute-hash [:null nil])]
      (is (= h1 h2)))))

(deftest test-bool-hashing
  (testing "true and false have different hashes"
    (let [h-true (hash/compute-hash [:bool true])
          h-false (hash/compute-hash [:bool false])]
      (is (not= h-true h-false)))))

(deftest test-low-entropy-detection
  (testing "normal hashes are not low-entropy"
    (let [h (hash/sha256-str "normal data")]
      (is (not (hash/low-entropy? h)))))

  (testing "hash with zeros in lower 32 bits is low-entropy"
    ;; Construct a degenerate hash with zeros in lower 32 bits of all words
    (let [bad-hash [(unchecked-long 0x1234567800000000)  ;; lower 32 bits = 0
                    (unchecked-long 0xABCDEF0000000000)
                    (unchecked-long 0x9876543200000000)
                    (unchecked-long 0xFEDCBA9800000000)]]
      (is (hash/low-entropy? bad-hash))))

  (testing "fuse throws on low-entropy result (via repeated self-fuse)"
    ;; Fusing a hash with itself ~65 times converges to low-entropy
    (let [start (hash/sha256-str "trigger low entropy")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Low-entropy hash detected"
           (reduce (fn [h _] (hash/fuse h h))
                   start
                   (range 65))))))

  (testing "unchecked-fuse allows low-entropy (no exception)"
    ;; Use unchecked version to verify low-entropy actually occurs
    (let [start (hash/sha256-str "any value")
          result (reduce (fn [h _] (hash/unchecked-fuse h h))
                         start
                         (range 65))]
      (is (hash/low-entropy? result)
          "65 iterations of self-fuse should produce low-entropy hash"))))

(deftest test-hex-conversion
  (testing "round-trip through hex"
    (let [h (hash/sha256-str "test")]
      (is (= h (hash/hex->hash (hash/hash->hex h)))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests)

  ;; Run specific property test
  (tc/quick-check 100 fuse-determinism)

  ;; Run with more iterations
  (tc/quick-check 1000 fuse-non-commutative))
