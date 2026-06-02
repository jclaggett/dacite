(ns dacite.value2-test
  "Tests for the value2 namespace."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store :as store]
            [dacite.value2 :as v2]
            [dacite.value2.scalar :as scalar]))

;; =============================================================================
;; Scalar constructors (pure)
;; =============================================================================

(deftest test-null-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/null store)
        v (v2/make-value store' h)]
    (is (= "null" (v2/value-type v)))
    (is (nil? (v2/value-data v)))
    (is (v2/scalar? v))
    (is (not (v2/collection? v)))))

(deftest test-bool-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/bool store true)
        v (v2/make-value store' h)]
    (is (= "bool" (v2/value-type v)))
    (is (= true (v2/value-data v)))
    (is (v2/scalar? v))
    (is (not (v2/collection? v)))))

(deftest test-i8-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/i8 store 42)
        v (v2/make-value store' h)]
    (is (= "i8" (v2/value-type v)))
    (is (= (byte 42) (v2/value-data v)))
    (is (v2/scalar? v))))

(deftest test-i16-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/i16 store 1000)
        v (v2/make-value store' h)]
    (is (= "i16" (v2/value-type v)))
    (is (= (short 1000) (v2/value-data v)))))

(deftest test-i32-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/i32 store 123456)
        v (v2/make-value store' h)]
    (is (= "i32" (v2/value-type v)))
    (is (= (int 123456) (v2/value-data v)))))

(deftest test-i64-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/i64 store 9999999999)
        v (v2/make-value store' h)]
    (is (= "i64" (v2/value-type v)))
    (is (= 9999999999 (v2/value-data v)))))

(deftest test-u8-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/u8 store 255)
        v (v2/make-value store' h)]
    (is (= "u8" (v2/value-type v)))
    (is (= 255 (v2/value-data v)))))

(deftest test-u8-validation
  (let [store (store/mem-store)]
    (is (thrown? AssertionError (scalar/u8 store 256)))
    (is (thrown? AssertionError (scalar/u8 store -1)))))

(deftest test-u16-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/u16 store 65535)
        v (v2/make-value store' h)]
    (is (= "u16" (v2/value-type v)))
    (is (= 65535 (v2/value-data v)))))

(deftest test-u16-validation
  (let [store (store/mem-store)]
    (is (thrown? AssertionError (scalar/u16 store 65536)))
    (is (thrown? AssertionError (scalar/u16 store -1)))))

(deftest test-u32-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/u32 store 4294967295)
        v (v2/make-value store' h)]
    (is (= "u32" (v2/value-type v)))
    (is (= 4294967295 (v2/value-data v)))))

(deftest test-u32-validation
  (let [store (store/mem-store)]
    (is (thrown? AssertionError (scalar/u32 store 4294967296)))
    (is (thrown? AssertionError (scalar/u32 store -1)))))

(deftest test-u64-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/u64 store 18446744073709551615)
        v (v2/make-value store' h)]
    (is (= "u64" (v2/value-type v)))
    (is (= 18446744073709551615 (v2/value-data v)))))

(deftest test-u64-validation
  (let [store (store/mem-store)]
    (is (thrown? AssertionError (scalar/u64 store -1)))))

(deftest test-u256-constructor
  (let [store (store/mem-store)
        data (byte-array 32 (byte 0x42))
        [store' h] (scalar/u256 store data)
        v (v2/make-value store' h)]
    (is (= "u256" (v2/value-type v)))
    (is (= 32 (alength ^bytes (v2/value-data v))))))

(deftest test-u256-validation
  (let [store (store/mem-store)]
    (is (thrown? AssertionError (scalar/u256 store (byte-array 31))))
    (is (thrown? AssertionError (scalar/u256 store (byte-array 33))))))

(deftest test-f32-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/f32 store 3.14)
        v (v2/make-value store' h)]
    (is (= "f32" (v2/value-type v)))
    (is (float? (v2/value-data v)))
    (is (> 0.001 (Math/abs (- 3.14 (v2/value-data v)))))))

(deftest test-f64-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/f64 store 3.14159265358979)
        v (v2/make-value store' h)]
    (is (= "f64" (v2/value-type v)))
    (is (double? (v2/value-data v)))
    (is (> 0.0001 (Math/abs (- 3.14159265358979 (v2/value-data v)))))))

(deftest test-char-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/dacite-char store \a)
        v (v2/make-value store' h)]
    (is (= "char" (v2/value-type v)))
    (is (= \a (v2/value-data v)))))

(deftest test-char-validation
  (let [store (store/mem-store)]
    (is (thrown? AssertionError (scalar/dacite-char store "a")))))

(deftest test-neg-constructor
  (let [store (store/mem-store)
        [store' h] (scalar/neg store)
        v (v2/make-value store' h)]
    (is (= "negative" (v2/value-type v)))
    (is (nil? (v2/value-data v)))))

;; =============================================================================
;; Content addressing
;; =============================================================================

(deftest test-content-addressing
  (let [store (store/mem-store)
        [store' h1] (scalar/i64 store 42)
        [store'' h2] (scalar/i64 store' 42)
        [store''' h3] (scalar/i64 store'' 43)]
    ;; Same value = same hash
    (is (= h1 h2))
    ;; Different value = different hash
    (is (not= h1 h3))))

;; =============================================================================
;; Convenience layer
;; =============================================================================

(deftest test-with-store
  (v2/with-store (store/mem-store)
    (let [v (v2/c-i64 42)]
      (is (= "i64" (v2/value-type v)))
      (is (= 42 (v2/value-data v))))))

(deftest test-convenience-constructors
  (v2/with-store (store/mem-store)
    (let [v-null (v2/c-null)
          v-bool (v2/c-bool false)
          v-i8 (v2/c-i8 1)
          v-i16 (v2/c-i16 2)
          v-i32 (v2/c-i32 3)
          v-i64 (v2/c-i64 4)
          v-u8 (v2/c-u8 5)
          v-u16 (v2/c-u16 6)
          v-u32 (v2/c-u32 7)
          v-u64 (v2/c-u64 8)
          v-f32 (v2/c-f32 9.0)
          v-f64 (v2/c-f64 10.0)
          v-char (v2/c-char \x)]
      (is (= "null" (v2/value-type v-null)))
      (is (= "bool" (v2/value-type v-bool)))
      (is (= "i8" (v2/value-type v-i8)))
      (is (= "i16" (v2/value-type v-i16)))
      (is (= "i32" (v2/value-type v-i32)))
      (is (= "i64" (v2/value-type v-i64)))
      (is (= "u8" (v2/value-type v-u8)))
      (is (= "u16" (v2/value-type v-u16)))
      (is (= "u32" (v2/value-type v-u32)))
      (is (= "u64" (v2/value-type v-u64)))
      (is (= "f32" (v2/value-type v-f32)))
      (is (= "f64" (v2/value-type v-f64)))
      (is (= "char" (v2/value-type v-char))))))

;; =============================================================================
;; Error cases
;; =============================================================================

(deftest test-make-value-not-found
  (let [store (store/mem-store)
        fake-hash [0 0 0 0]
        v (v2/make-value store fake-hash)]
    (is (nil? v))))

;; =============================================================================
;; Run all
;; =============================================================================

(comment
  (clojure.test/run-tests *ns*))
