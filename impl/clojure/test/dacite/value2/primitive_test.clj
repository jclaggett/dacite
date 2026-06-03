(ns dacite.value2.primitive-test
  "Tests for the primitive namespace — raw byte storage and numeric helpers.

   Goal: 100% coverage of primitive.clj via public API tests.
   Tests serve double duty as usage examples."
  (:require [clojure.test :refer [deftest is]]
            [dacite.store :as store]
            [dacite.value2.primitive :as prim]))

;; =============================================================================
;; raw-bytes
;; =============================================================================

(deftest test-raw-bytes-stores-and-retrieves
  (let [store (store/mem-store)
        data (.getBytes "hello" "UTF-8")
        [store' h] (prim/raw-bytes store data)]
    ;; MemStore is mutable (returns same instance), so check content instead
    (is (some? (store/s-get store' h)) "Data is retrievable from store")
    (is (vector? h) "Hash is a vector of 4 longs")
    (is (= 4 (count h)) "Hash has 4 elements")
    (is (= java.lang.Long (type (first h))) "Hash elements are longs")
    ;; Round-trip
    (is (java.util.Arrays/equals ^bytes data ^bytes (store/s-get store' h))
        "Retrieved bytes match stored bytes")))

(deftest test-raw-bytes-content-addressed
  (let [store (store/mem-store)
        data (.getBytes "same" "UTF-8")
        [store' h1] (prim/raw-bytes store data)
        [store'' h2] (prim/raw-bytes store' data)]
    (is (= h1 h2) "Same data = same hash")
    (is (identical? store' store'') "Store unchanged on second store (already present)")))

(deftest test-raw-bytes-different-data-different-hashes
  (let [store (store/mem-store)
        [store' h1] (prim/raw-bytes store (.getBytes "a" "UTF-8"))
        [store'' h2] (prim/raw-bytes store' (.getBytes "b" "UTF-8"))]
    (is (not= h1 h2) "Different data = different hash")))

(deftest test-raw-bytes-empty-array
  (let [store (store/mem-store)
        [store' h] (prim/raw-bytes store (byte-array 0))]
    (is (vector? h) "Empty array still produces a hash")
    ;; The hash is fuse-bytes of empty array = identity [0 0 0 0]
    (is (= [0 0 0 0] h) "Empty array hashes to identity")))

;; =============================================================================
;; Numeric helpers: i8, i16, i32, i64
;; =============================================================================

(deftest test-i8->bytes-roundtrip
  (let [buf (prim/i8->bytes -128)]
    (is (= 1 (alength ^bytes buf)) "i8 is 1 byte")
    (is (= -128 (aget ^bytes buf 0)) "Value round-trips"))
  (let [buf (prim/i8->bytes 127)]
    (is (= 127 (aget ^bytes buf 0)) "Max i8 value")))

(deftest test-i16->bytes-roundtrip
  (let [buf (prim/i16->bytes -32768)
        wrapped (java.nio.ByteBuffer/wrap buf)]
    (is (= 2 (alength ^bytes buf)) "i16 is 2 bytes")
    (is (= -32768 (.getShort wrapped)) "Value round-trips")))

(deftest test-i32->bytes-roundtrip
  (let [buf (prim/i32->bytes -2147483648)
        wrapped (java.nio.ByteBuffer/wrap buf)]
    (is (= 4 (alength ^bytes buf)) "i32 is 4 bytes")
    (is (= -2147483648 (.getInt wrapped)) "Value round-trips")))

(deftest test-i64->bytes-roundtrip
  (let [buf (prim/i64->bytes -9223372036854775808)
        wrapped (java.nio.ByteBuffer/wrap buf)]
    (is (= 8 (alength ^bytes buf)) "i64 is 8 bytes")
    (is (= -9223372036854775808 (.getLong wrapped)) "Value round-trips")))

;; =============================================================================
;; Numeric helpers: u8, u16, u32, u64
;; =============================================================================

(deftest test-u8->bytes-roundtrip
  (let [buf (prim/u8->bytes 255)]
    (is (= 1 (alength ^bytes buf)) "u8 is 1 byte")
    (is (= -1 (aget ^bytes buf 0)) "255 as signed byte = -1")))

(deftest test-u16->bytes-roundtrip
  (let [buf (prim/u16->bytes 65535)
        wrapped (java.nio.ByteBuffer/wrap buf)]
    (is (= 2 (alength ^bytes buf)) "u16 is 2 bytes")
    ;; unchecked-short 65535 = -1, which gets stored as 0xFFFF
    (is (= -1 (.getShort wrapped)) "65535 as signed short = -1")))

(deftest test-u32->bytes-roundtrip
  (let [buf (prim/u32->bytes 4294967295)
        wrapped (java.nio.ByteBuffer/wrap buf)]
    (is (= 4 (alength ^bytes buf)) "u32 is 4 bytes")
    (is (= -1 (.getInt wrapped)) "Max u32 as signed int = -1")))

(deftest test-u64->bytes-roundtrip
  (let [buf (prim/u64->bytes 18446744073709551615)
        wrapped (java.nio.ByteBuffer/wrap buf)]
    (is (= 8 (alength ^bytes buf)) "u64 is 8 bytes")
    (is (= -1 (.getLong wrapped)) "Max u64 as signed long = -1")))

;; =============================================================================
;; Float helpers
;; =============================================================================

(deftest test-f32->bytes-roundtrip
  (let [buf (prim/f32->bytes 3.14)
        wrapped (java.nio.ByteBuffer/wrap buf)]
    (is (= 4 (alength ^bytes buf)) "f32 is 4 bytes")
    (is (> 0.001 (Math/abs (- 3.14 (.getFloat wrapped)))) "Value round-trips approximately")))

(deftest test-f64->bytes-roundtrip
  (let [buf (prim/f64->bytes 3.14159265358979)
        wrapped (java.nio.ByteBuffer/wrap buf)]
    (is (= 8 (alength ^bytes buf)) "f64 is 8 bytes")
    (is (> 0.0001 (Math/abs (- 3.14159265358979 (.getDouble wrapped)))) "Value round-trips approximately")))

;; =============================================================================
;; Char helper
;; =============================================================================

(deftest test-char->bytes-roundtrip
  (let [buf (prim/char->bytes \a)]
    (is (= "a" (String. buf "UTF-8")) "ASCII char round-trips"))
  (let [buf (prim/char->bytes \u00e9)] ; é
    (is (= "\u00e9" (String. buf "UTF-8")) "Unicode char round-trips"))
  (let [buf (prim/char->bytes \u4e2d)] ; 中
    (is (= 3 (alength ^bytes buf)) "CJK char is 3 bytes in UTF-8")
    (is (= "\u4e2d" (String. buf "UTF-8")) "CJK char round-trips")))

;; =============================================================================
;; Convenience wrappers: raw-i8 through raw-i64
;; =============================================================================

(deftest test-raw-i8
  (let [store (store/mem-store)
        [store' h] (prim/raw-i8 store -128)]
    (is (= -128 (aget ^bytes (store/s-get store' h) 0)))))

(deftest test-raw-i16
  (let [store (store/mem-store)
        [store' h] (prim/raw-i16 store -32768)]
    (is (= -32768 (.getShort (java.nio.ByteBuffer/wrap (store/s-get store' h)))))))

(deftest test-raw-i32
  (let [store (store/mem-store)
        [store' h] (prim/raw-i32 store -2147483648)]
    (is (= -2147483648 (.getInt (java.nio.ByteBuffer/wrap (store/s-get store' h)))))))

(deftest test-raw-i64
  (let [store (store/mem-store)
        [store' h] (prim/raw-i64 store -9223372036854775808)]
    (is (= -9223372036854775808 (.getLong (java.nio.ByteBuffer/wrap (store/s-get store' h)))))))

;; =============================================================================
;; Convenience wrappers: raw-u8 through raw-u64
;; =============================================================================

(deftest test-raw-u8
  (let [store (store/mem-store)
        [store' h] (prim/raw-u8 store 255)]
    (is (= -1 (aget ^bytes (store/s-get store' h) 0)) "255 stored as -1 (same bits)")))

(deftest test-raw-u16
  (let [store (store/mem-store)
        [store' h] (prim/raw-u16 store 65535)]
    (is (= -1 (.getShort (java.nio.ByteBuffer/wrap (store/s-get store' h)))))))

(deftest test-raw-u32
  (let [store (store/mem-store)
        [store' h] (prim/raw-u32 store 4294967295)]
    (is (= -1 (.getInt (java.nio.ByteBuffer/wrap (store/s-get store' h)))))))

(deftest test-raw-u64
  (let [store (store/mem-store)
        [store' h] (prim/raw-u64 store 18446744073709551615)]
    (is (= -1 (.getLong (java.nio.ByteBuffer/wrap (store/s-get store' h)))))))

;; =============================================================================
;; Float convenience wrappers
;; =============================================================================

(deftest test-raw-f32
  (let [store (store/mem-store)
        [store' h] (prim/raw-f32 store 3.14)]
    (is (> 0.001 (Math/abs (- 3.14 (.getFloat (java.nio.ByteBuffer/wrap (store/s-get store' h)))))))))

(deftest test-raw-f64
  (let [store (store/mem-store)
        [store' h] (prim/raw-f64 store 3.14159265358979)]
    (is (> 0.0001 (Math/abs (- 3.14159265358979 (.getDouble (java.nio.ByteBuffer/wrap (store/s-get store' h)))))))))

;; =============================================================================
;; Char convenience wrapper
;; =============================================================================

(deftest test-raw-char
  (let [store (store/mem-store)
        [store' h] (prim/raw-char store \a)]
    (is (= "a" (String. (store/s-get store' h) "UTF-8")))))

;; =============================================================================
;; raw-u256
;; =============================================================================

(deftest test-raw-u256-valid
  (let [store (store/mem-store)
        data (byte-array 32 (byte 0x42))
        [store' h] (prim/raw-u256 store data)]
    (is (java.util.Arrays/equals ^bytes data ^bytes (store/s-get store' h))
        "32-byte data stored correctly")))

(deftest test-raw-u256-validation
  (let [store (store/mem-store)]
    (is (thrown? AssertionError (prim/raw-u256 store (byte-array 31)))
        "31 bytes rejected")
    (is (thrown? AssertionError (prim/raw-u256 store (byte-array 33)))
        "33 bytes rejected")))

;; =============================================================================
;; raw-null
;; =============================================================================

(deftest test-raw-null
  (let [store (store/mem-store)
        [store' h] (prim/raw-null store)]
    (is (= 0 (alength ^bytes (store/s-get store' h))) "Null is 0 bytes")
    (is (= [0 0 0 0] h) "Null hashes to identity")))

;; =============================================================================
;; raw-bool
;; =============================================================================

(deftest test-raw-bool-true
  (let [store (store/mem-store)
        [store' h] (prim/raw-bool store true)]
    (is (= 1 (aget ^bytes (store/s-get store' h) 0)) "True = 1")))

(deftest test-raw-bool-false
  (let [store (store/mem-store)
        [store' h] (prim/raw-bool store false)]
    (is (= 0 (aget ^bytes (store/s-get store' h) 0)) "False = 0")))

(deftest test-raw-bool-content-addressed
  (let [store (store/mem-store)
        [store' h-true-1] (prim/raw-bool store true)
        [store'' h-true-2] (prim/raw-bool store' true)
        [store''' h-false] (prim/raw-bool store'' false)]
    (is (= h-true-1 h-true-2) "Same bool = same hash")
    (is (not= h-true-1 h-false) "Different bool = different hash")))

;; =============================================================================
;; Edge cases and integration
;; =============================================================================

(deftest test-multiple-values-in-same-store
  (let [s0 (store/mem-store)
        [s1 h1] (prim/raw-i64 s0 42)
        [s2 h2] (prim/raw-bool s1 true)
        [s3 h3] (prim/raw-null s2)]
    (is (= 42 (.getLong (java.nio.ByteBuffer/wrap (store/s-get s3 h1)))))
    (is (= 1 (aget ^bytes (store/s-get s3 h2) 0)))
    (is (= 0 (alength ^bytes (store/s-get s3 h3))))))

(deftest test-hash-type-and-structure
  (let [store (store/mem-store)
        [store' h] (prim/raw-i64 store 42)]
    (is (vector? h))
    (is (= 4 (count h)))
    (is (every? #(= java.lang.Long (type %)) h))))

;; =============================================================================
;; Property-style: determinism and uniqueness
;; =============================================================================

(deftest test-determinism-small-integers
  (let [store (store/mem-store)
        [s1 h1] (prim/raw-i64 store 42)
        [s2 h2] (prim/raw-i64 s1 42)
        [s3 h3] (prim/raw-i64 s2 42)]
    (is (= h1 h2 h3) "Deterministic: same input = same hash")))

(deftest test-uniqueness-different-values
  (let [store (store/mem-store)
        [s1 h1] (prim/raw-i64 store 1)
        [s2 h2] (prim/raw-i64 s1 2)
        [s3 h3] (prim/raw-i64 s2 3)]
    (is (not= h1 h2))
    (is (not= h2 h3))
    (is (not= h1 h3))))

;; =============================================================================
;; Run all
;; =============================================================================

(comment
  (clojure.test/run-tests *ns*))
