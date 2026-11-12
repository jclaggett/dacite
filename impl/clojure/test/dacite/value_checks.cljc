(ns dacite.value-checks
  "Scalars, accessors, and store-awareness in the value layer, on the JVM
   and on nbb.

   These were the bodies of `dacite.value-test`. The JVM runs them from that
   namespace, one deftest each under its old name. nbb runs them from
   `dacite.value-cljs-test` (`npm run value:test`).

   Host pieces: a u64 past 2^53 is a BigInt on nbb (`big-u64`), a char is a
   one-letter string there, `IDeref` is a protocol there (`derefable?`), and
   a failed `assert` is a js/Error (`assert-fails?`). One assertion of
   `scalar-validation` stays in the JVM deftest: `v2/char` of a one-letter
   String fails, because a BMP character is a char, and a JS host has no
   separate char type.

   `is` and `testing` are `dacite.store.check`."
  (:require [dacite.hash :as hash]
            [dacite.store :as store]
            [dacite.store.check :refer [is testing]]
            [dacite.value :as v2]))

(def ^:private big-u64
  "2^64 - 1."
  #?(:clj 18446744073709551615 :cljs (js/BigInt "18446744073709551615")))

(defn- derefable?
  [x]
  #?(:clj (instance? clojure.lang.IDeref x) :cljs (satisfies? IDeref x)))

(defn- assert-fails?
  "True when `(f)` fails an `assert`."
  [f]
  (try (f) false
       (catch #?(:clj AssertionError :cljs :default) e
         #?(:clj (some? e) :cljs (boolean (re-find #"Assert failed" (str (ex-message e))))))))

(defn- host-bytes
  "`n` zero bytes."
  [n]
  #?(:clj (byte-array n) :cljs (js/Uint8Array. n)))

;; =============================================================================
;; Scalar construction & accessors
;; =============================================================================

(defn scalar-types-and-realize
  [_]
  (let [s (store/mem-store)]
    (is (= "null" (v2/type (v2/null s))))
    (is (nil? (v2/realize (v2/null s))))
    (is (= "bool" (v2/type (v2/bool s true))))
    (is (= true (v2/realize (v2/bool s true))))
    (is (= (byte 42) (v2/realize (v2/i8 s 42))))
    (is (= (short 1000) (v2/realize (v2/i16 s 1000))))
    (is (= (int 123456) (v2/realize (v2/i32 s 123456))))
    (is (= 9999999999 (v2/realize (v2/i64 s 9999999999))))
    (is (= 255 (v2/realize (v2/u8 s 255))))
    (is (= 65535 (v2/realize (v2/u16 s 65535))))
    (is (= 4294967295 (v2/realize (v2/u32 s 4294967295))))
    (is (= big-u64 (v2/realize (v2/u64 s big-u64))))
    (is (float? (v2/realize (v2/f32 s 3.14))))
    (is (double? (v2/realize (v2/f64 s 3.14159))))
    (is (= \a (v2/realize (v2/char s \a))))
    (is (= "negative" (v2/type (v2/negative s))))
    (is (= :dacite/negative (v2/realize (v2/negative s))))
    (is (not= (v2/realize (v2/negative s))
              (v2/realize (v2/null s))))))

(defn values-are-not-derefable
  [_]
  (let [s (store/mem-store)]
    (is (not (derefable? (v2/i64 s 1))))
    (is (not (derefable? (v2/vector s 1 2))))))

(defn scalar-validation
  [_]
  (let [s (store/mem-store)]
    (is (assert-fails? #(v2/u8 s 256)))
    (is (assert-fails? #(v2/u8 s -1)))
    (is (assert-fails? #(v2/u16 s 65536)))
    (is (assert-fails? #(v2/u32 s 4294967296)))
    (is (assert-fails? #(v2/u64 s -1)))
    (is (assert-fails? #(v2/u256 s (host-bytes 31))))))

;; =============================================================================
;; Content addressing
;; =============================================================================

(defn content-addressing
  [_]
  (let [s (store/mem-store)]
    (testing "same value, same hash"
      (is (= (v2/hash (v2/i64 s 42))
             (v2/hash (v2/i64 s 42)))))
    (testing "different value, different hash"
      (is (not= (v2/hash (v2/i64 s 42))
                (v2/hash (v2/i64 s 43)))))
    (testing "type tag distinguishes equal data"
      ;; i64 0 and a different int width carry distinct type hashes
      (is (not= (v2/hash (v2/i32 s 0))
                (v2/hash (v2/i64 s 0)))))))

;; =============================================================================
;; Store awareness (§3.1)
;; =============================================================================

(defn values-know-their-store
  [_]
  (let [s (store/mem-store)
        v (v2/i64 s 42)]
    (is (identical? s (v2/dacite-store v)))
    (testing "the value persists in its own store"
      (is (some? (store/await (store/s-get s (v2/hash v))))))))

(defn get-value-round-trips
  [_]
  (let [s (store/mem-store)
        v (v2/i64 s 42)
        back (v2/get-value-with-store s (v2/hash v))]
    (is (= "i64" (v2/type back)))
    (is (= 42 (v2/realize back)))
    (testing "missing hash returns nil"
      (is (nil? (v2/get-value-with-store s [0 0 0 0]))))))

;; =============================================================================
;; Cross-type equality (§3.3)
;; =============================================================================

(defn cross-type-content-hash
  [_]
  (let [s (store/mem-store)
        str-v (v2/string s "abc")
        chars (mapv #(v2/char s %) "abc")
        vec-v (apply v2/vector s chars)]
    (testing "different types => different value hashes"
      (is (not= (v2/hash str-v) (v2/hash vec-v))))
    (testing "same leaves => same content hash"
      (is (= (v2/content-hash str-v) (v2/content-hash vec-v))))))

(defn content-hash-strips-type
  [_]
  (let [s (store/mem-store)]
    ;; An empty string's data hash is the group identity.
    (is (= (hash/state->ref hash/identity-state) (v2/content-hash (v2/string s ""))))))

(def checks
  {"scalar-types-and-realize" scalar-types-and-realize
   "values-are-not-derefable" values-are-not-derefable
   "scalar-validation" scalar-validation
   "content-addressing" content-addressing
   "values-know-their-store" values-know-their-store
   "get-value-round-trips" get-value-round-trips
   "cross-type-content-hash" cross-type-content-hash
   "content-hash-strips-type" content-hash-strips-type})
