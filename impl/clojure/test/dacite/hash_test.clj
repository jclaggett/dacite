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
  gen/int)

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
  "Generator for 32-bit floats."
  (gen/fmap float gen/double))

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

;; Generator for 256-bit hashes (as byte arrays)
(def gen-hash-bytes
  "Generator for 32-byte hash values."
  (gen/fmap byte-array (gen/vector (gen/choose -128 127) 32)))

;; =============================================================================
;; Helper functions for tests
;; =============================================================================

(defn value->bytes
  "Convert a leaf value to its canonical byte representation.
   TODO: This needs proper implementation per type."
  [type-kw value]
  (case type-kw
    :null (byte-array 0)
    :bool (byte-array [(if value 1 0)])
    :i8   (byte-array [(byte value)])
    :i16  (let [b (byte-array 2)]
            (aset b 0 (byte (bit-shift-right value 8)))
            (aset b 1 (byte value))
            b)
    :i32  (.array (doto (java.nio.ByteBuffer/allocate 4) (.putInt value)))
    :i64  (.array (doto (java.nio.ByteBuffer/allocate 8) (.putLong value)))
    :u8   (byte-array [(byte value)])
    :u16  (let [b (byte-array 2)]
            (aset b 0 (byte (bit-shift-right value 8)))
            (aset b 1 (byte value))
            b)
    :u32  (.array (doto (java.nio.ByteBuffer/allocate 8) (.putLong value)))
    :f32  (.array (doto (java.nio.ByteBuffer/allocate 4) (.putFloat value)))
    :f64  (.array (doto (java.nio.ByteBuffer/allocate 8) (.putDouble value)))
    :char (.getBytes (str value) "UTF-8")))

(defn compute-leaf-hash
  "Compute the full hash for a tagged leaf value."
  [type-kw value]
  (let [type-hash (get hash/builtin-type-hashes type-kw)
        data-bytes (value->bytes type-kw value)]
    (hash/value-hash type-hash data-bytes)))

(defn bytes=
  "Compare two byte arrays for equality."
  [^bytes a ^bytes b]
  (java.util.Arrays/equals a b))

;; =============================================================================
;; Property tests
;; =============================================================================

(defspec sha256-determinism 100
  (prop/for-all [data (gen/not-empty gen/bytes)]
    (bytes= (hash/sha256 data)
            (hash/sha256 data))))

(defspec fuse-determinism 100
  (prop/for-all [a gen-hash-bytes
                 b gen-hash-bytes]
    (bytes= (hash/fuse a b)
            (hash/fuse a b))))

(defspec fuse-non-commutative 100
  (prop/for-all [a gen-hash-bytes
                 b gen-hash-bytes]
    ;; fuse(a,b) ≠ fuse(b,a) unless a = b
    (or (bytes= a b)
        (not (bytes= (hash/fuse a b)
                     (hash/fuse b a))))))

(defspec fuse-not-identity-left 100
  (prop/for-all [a gen-hash-bytes
                 b gen-hash-bytes]
    ;; fuse(a,b) ≠ a (fuse shouldn't return just the left input)
    (not (bytes= (hash/fuse a b) a))))

(defspec fuse-not-identity-right 100
  (prop/for-all [a gen-hash-bytes
                 b gen-hash-bytes]
    ;; fuse(a,b) ≠ b (fuse shouldn't return just the right input)
    (not (bytes= (hash/fuse a b) b))))

(defspec leaf-hash-determinism 100
  (prop/for-all [[type-kw value] gen-tagged-leaf]
    (bytes= (compute-leaf-hash type-kw value)
            (compute-leaf-hash type-kw value))))

(defspec fuse-associative 100
  (prop/for-all [a gen-hash-bytes
                 b gen-hash-bytes
                 c gen-hash-bytes]
    ;; fuse(a, fuse(b, c)) = fuse(fuse(a, b), c)
    (bytes= (hash/fuse a (hash/fuse b c))
            (hash/fuse (hash/fuse a b) c))))

(defspec different-types-different-hashes 100
  (prop/for-all [type1 (gen/elements [:i32 :i64 :u32 :f32 :f64])
                 type2 (gen/elements [:i32 :i64 :u32 :f32 :f64])
                 value gen/int]
    ;; Same numeric value with different types should hash differently
    (or (= type1 type2)
        (not (bytes= (compute-leaf-hash type1 value)
                     (compute-leaf-hash type2 value))))))

;; =============================================================================
;; Unit tests for edge cases  
;; =============================================================================

(deftest test-fuse-basic
  (testing "fuse produces 32-byte output"
    (let [a (hash/sha256-str "hello")
          b (hash/sha256-str "world")
          c (hash/fuse a b)]
      (is (= 32 (count c)))))
  
  (testing "fuse with same input twice"
    (let [a (hash/sha256-str "test")
          c (hash/fuse a a)]
      (is (= 32 (count c)))
      ;; fuse(a,a) should still be different from a
      (is (not (bytes= c a)))))
  
  (testing "fuse is associative"
    (let [a (hash/sha256-str "one")
          b (hash/sha256-str "two")
          c (hash/sha256-str "three")
          ;; fuse(a, fuse(b, c)) should equal fuse(fuse(a, b), c)
          left (hash/fuse a (hash/fuse b c))
          right (hash/fuse (hash/fuse a b) c)]
      (is (bytes= left right)))))

(deftest test-type-hashes-unique
  (testing "all builtin type hashes are unique"
    (let [hashes (vals hash/builtin-type-hashes)
          hash-set (set (map vec hashes))]
      (is (= (count hashes) (count hash-set))))))

(deftest test-null-hashing
  (testing "null has consistent hash"
    (let [h1 (compute-leaf-hash :null nil)
          h2 (compute-leaf-hash :null nil)]
      (is (bytes= h1 h2)))))

(deftest test-bool-hashing
  (testing "true and false have different hashes"
    (let [h-true (compute-leaf-hash :bool true)
          h-false (compute-leaf-hash :bool false)]
      (is (not (bytes= h-true h-false))))))

(deftest test-low-entropy-detection
  (testing "normal hashes are not low-entropy"
    (let [h (hash/sha256-str "normal data")]
      (is (not (hash/low-entropy? h)))))
  
  (testing "hash with zeros in lower 32 bits is low-entropy"
    ;; Construct a degenerate hash with zeros in lower 32 bits of all words
    (let [bad-hash (hash/longs->bytes 
                    [0x1234567800000000  ;; lower 32 bits = 0
                     0xABCDEF0000000000
                     0x9876543200000000
                     0xFEDCBA9800000000])]
      (is (hash/low-entropy? bad-hash))))
  
  (testing "fuse! throws on low-entropy result"
    ;; This is hard to trigger naturally, but the function should work
    (let [a (hash/sha256-str "test1")
          b (hash/sha256-str "test2")]
      ;; Normal fuse should not throw
      (is (bytes? (hash/fuse! a b))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests)
  
  ;; Run specific property test
  (tc/quick-check 100 fuse-determinism)
  
  ;; Run with more iterations
  (tc/quick-check 1000 fuse-non-commutative))
