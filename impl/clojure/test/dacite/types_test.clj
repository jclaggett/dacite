(ns dacite.types-test
  "Tests for dacite.types - 100% coverage via public API."
  (:require [clojure.test :refer [deftest is testing]]
            [dacite.types :as types]))

;; =============================================================================
;; Value accessors
;; =============================================================================

(deftest test-dacite-type
  (testing "extracts type name from value tuple"
    (is (= "i64" (types/dacite-type ["i64" 42])))
    (is (= "bool" (types/dacite-type ["bool" true])))
    (is (= "null" (types/dacite-type ["null" nil])))
    (is (= :custom (types/dacite-type [:custom {:foo "bar"}])))))

(deftest test-dacite-data
  (testing "extracts data from value tuple"
    (is (= 42 (types/dacite-data ["i64" 42])))
    (is (= true (types/dacite-data ["bool" true])))
    (is (= nil (types/dacite-data ["null" nil])))
    (is (= {:foo "bar"} (types/dacite-data [:custom {:foo "bar"}])))))

;; =============================================================================
;; Primitive sizes
;; =============================================================================

(deftest test-null-size
  (is (= 0 (types/dacite-size ["null" nil]))))

(deftest test-bool-size
  (is (= 1 (types/dacite-size ["bool" true])))
  (is (= 1 (types/dacite-size ["bool" false]))))

(deftest test-signed-integer-sizes
  (testing "i8 = 1 byte"
    (is (= 1 (types/dacite-size ["i8" 0])))
    (is (= 1 (types/dacite-size ["i8" 127])))
    (is (= 1 (types/dacite-size ["i8" -128]))))
  (testing "i16 = 2 bytes"
    (is (= 2 (types/dacite-size ["i16" 0])))
    (is (= 2 (types/dacite-size ["i16" 32767]))))
  (testing "i32 = 4 bytes"
    (is (= 4 (types/dacite-size ["i32" 0])))
    (is (= 4 (types/dacite-size ["i32" Integer/MAX_VALUE]))))
  (testing "i64 = 8 bytes"
    (is (= 8 (types/dacite-size ["i64" 0])))
    (is (= 8 (types/dacite-size ["i64" Long/MAX_VALUE]))))
  (testing "i128 = 16 bytes"
    (is (= 16 (types/dacite-size ["i128" 0]))))
  (testing "i256 = 32 bytes"
    (is (= 32 (types/dacite-size ["i256" 0])))))

(deftest test-unsigned-integer-sizes
  (testing "u8 = 1 byte"
    (is (= 1 (types/dacite-size ["u8" 0])))
    (is (= 1 (types/dacite-size ["u8" 255]))))
  (testing "u16 = 2 bytes"
    (is (= 2 (types/dacite-size ["u16" 0])))
    (is (= 2 (types/dacite-size ["u16" 65535]))))
  (testing "u32 = 4 bytes"
    (is (= 4 (types/dacite-size ["u32" 0]))))
  (testing "u64 = 8 bytes"
    (is (= 8 (types/dacite-size ["u64" 0]))))
  (testing "u128 = 16 bytes"
    (is (= 16 (types/dacite-size ["u128" 0]))))
  (testing "u256 = 32 bytes"
    (is (= 32 (types/dacite-size ["u256" 0])))))

(deftest test-float-sizes
  (testing "f32 = 4 bytes"
    (is (= 4 (types/dacite-size ["f32" 0.0])))
    (is (= 4 (types/dacite-size ["f32" 3.14]))))
  (testing "f64 = 8 bytes"
    (is (= 8 (types/dacite-size ["f64" 0.0])))
    (is (= 8 (types/dacite-size ["f64" Math/PI])))))

(deftest test-char-size
  (testing "ASCII = 1 byte"
    (is (= 1 (types/dacite-size ["char" \a])))
    (is (= 1 (types/dacite-size ["char" \Z])))
    (is (= 1 (types/dacite-size ["char" \space]))))
  (testing "Latin Extended = 2 bytes"
    (is (= 2 (types/dacite-size ["char" \é])))
    (is (= 2 (types/dacite-size ["char" \ñ]))))
  (testing "CJK = 3 bytes"
    (is (= 3 (types/dacite-size ["char" \中])))
    (is (= 3 (types/dacite-size ["char" \日])))))

;; =============================================================================
;; Default method (collections and fallback)
;; =============================================================================

(deftest test-default-with-measure
  (testing "collections with :measure use :size-bytes"
    (is (= 80 (types/dacite-size ["ft/deep" {:measure {:count 10 :size-bytes 80}}])))
    (is (= 0 (types/dacite-size ["ft/empty" {:measure {:count 0 :size-bytes 0}}])))
    (is (= 1024 (types/dacite-size [:custom {:measure {:size-bytes 1024}}])))))

(deftest test-default-fallback
  (testing "unknown types without :measure use serialization size"
    ;; Fallback serializes with pr-str and measures UTF-8 bytes
    (let [size (types/dacite-size [:unknown-type "hello"])]
      (is (pos? size))
      ;; pr-str of "hello" is "\"hello\"" = 7 chars = 7 bytes
      (is (= 7 size)))
    ;; Numbers serialize to their string form
    (let [size (types/dacite-size [:mystery 42])]
      (is (= 2 size)))  ;; "42" = 2 bytes
    ;; nil serializes to "nil"
    (let [size (types/dacite-size [:weird nil])]
      (is (= 3 size)))))  ;; "nil" = 3 bytes
