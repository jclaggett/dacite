(ns dacite.value2-test
  "Tests for scalars, accessors, and store-awareness in the value2 layer."
  (:require [clojure.test :refer [deftest is testing]]
            [dacite.store :as store]
            [dacite.value2 :as v2]))

;; =============================================================================
;; Scalar construction & accessors
;; =============================================================================

(deftest scalar-types-and-realize
  (let [s (store/mem-store)]
    (is (= "null" (v2/dacite-type (v2/null-with-store s))))
    (is (nil? (v2/->clj (v2/null-with-store s))))
    (is (= "bool" (v2/dacite-type (v2/bool-with-store s true))))
    (is (= true (v2/->clj (v2/bool-with-store s true))))
    (is (= (byte 42) (v2/->clj (v2/i8-with-store s 42))))
    (is (= (short 1000) (v2/->clj (v2/i16-with-store s 1000))))
    (is (= (int 123456) (v2/->clj (v2/i32-with-store s 123456))))
    (is (= 9999999999 (v2/->clj (v2/i64-with-store s 9999999999))))
    (is (= 255 (v2/->clj (v2/u8-with-store s 255))))
    (is (= 65535 (v2/->clj (v2/u16-with-store s 65535))))
    (is (= 4294967295 (v2/->clj (v2/u32-with-store s 4294967295))))
    (is (= 18446744073709551615 (v2/->clj (v2/u64-with-store s 18446744073709551615))))
    (is (float? (v2/->clj (v2/f32-with-store s 3.14))))
    (is (double? (v2/->clj (v2/f64-with-store s 3.14159))))
    (is (= \a (v2/->clj (v2/dacite-char-with-store s \a))))
    (is (= "negative" (v2/dacite-type (v2/negative-with-store s))))
    (is (= :dacite/negative (v2/->clj (v2/negative-with-store s))))
    (is (not= (v2/->clj (v2/negative-with-store s))
              (v2/->clj (v2/null-with-store s))))))

(deftest values-are-not-derefable
  (let [s (store/mem-store)]
    (is (not (instance? clojure.lang.IDeref (v2/i64-with-store s 1))))
    (is (not (instance? clojure.lang.IDeref (v2/vector-with-store s 1 2))))))

(deftest scalar-validation
  (let [s (store/mem-store)]
    (is (thrown? AssertionError (v2/u8-with-store s 256)))
    (is (thrown? AssertionError (v2/u8-with-store s -1)))
    (is (thrown? AssertionError (v2/u16-with-store s 65536)))
    (is (thrown? AssertionError (v2/u32-with-store s 4294967296)))
    (is (thrown? AssertionError (v2/u64-with-store s -1)))
    (is (thrown? AssertionError (v2/dacite-char-with-store s "a")))
    (is (thrown? AssertionError (v2/u256-with-store s (byte-array 31))))))

;; =============================================================================
;; Content addressing
;; =============================================================================

(deftest content-addressing
  (let [s (store/mem-store)]
    (testing "same value, same hash"
      (is (= (v2/dacite-hash (v2/i64-with-store s 42))
             (v2/dacite-hash (v2/i64-with-store s 42)))))
    (testing "different value, different hash"
      (is (not= (v2/dacite-hash (v2/i64-with-store s 42))
                (v2/dacite-hash (v2/i64-with-store s 43)))))
    (testing "type tag distinguishes equal data"
      ;; i64 0 and a different int width carry distinct type hashes
      (is (not= (v2/dacite-hash (v2/i32-with-store s 0))
                (v2/dacite-hash (v2/i64-with-store s 0)))))))

;; =============================================================================
;; Store awareness (§3.1)
;; =============================================================================

(deftest values-know-their-store
  (let [s (store/mem-store)
        v (v2/i64-with-store s 42)]
    (is (identical? s (v2/dacite-store v)))
    (testing "the value persists in its own store"
      (is (some? (store/s-get s (v2/dacite-hash v)))))))

(deftest get-value-round-trips
  (let [s (store/mem-store)
        v (v2/i64-with-store s 42)
        back (v2/get-value-with-store s (v2/dacite-hash v))]
    (is (= "i64" (v2/dacite-type back)))
    (is (= 42 (v2/->clj back)))
    (testing "missing hash returns nil"
      (is (nil? (v2/get-value-with-store s [0 0 0 0]))))))

;; =============================================================================
;; Cross-type equality (§3.3)
;; =============================================================================

(deftest cross-type-content-hash
  (let [s (store/mem-store)
        str-v (v2/string-with-store s "abc")
        chars (mapv #(v2/dacite-char-with-store s %) "abc")
        vec-v (apply v2/vector-with-store s chars)]
    (testing "different types => different value hashes"
      (is (not= (v2/dacite-hash str-v) (v2/dacite-hash vec-v))))
    (testing "same leaves => same content hash"
      (is (= (v2/content-hash str-v) (v2/content-hash vec-v))))))

(deftest content-hash-strips-type
  (let [s (store/mem-store)]
    ;; An empty string's data hash is the group identity.
    (is (= [0 0 0 0] (v2/content-hash (v2/string-with-store s ""))))))

;; =============================================================================
;; Implicit constructors (*store*)
;; =============================================================================

(deftest implicit-constructors-use-current-store
  (let [s (store/mem-store)]
    (store/bind-store s
                      (let [v (v2/vector 1 2 3)]
                        (is (= [1 2 3] (mapv v2/->clj (or (seq v) ()))))
                        (is (identical? s (v2/dacite-store v)))
                        (is (= 42 (v2/->clj (v2/i64 42))))))))
