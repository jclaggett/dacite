(ns dacite.cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dacite.cache :as cache]
            [dacite.hash :as hash]))

;; =============================================================================
;; Unit tests
;; =============================================================================

(deftest basic-commit-and-lookup
  (testing "commit! returns a hash and lookup retrieves the value"
    (let [mgr (cache/memory-cache-manager)
          value ["i64" 42]
          hash (cache/commit! mgr value)]
      (is (vector? hash) "hash should be a vector")
      (is (= 4 (count hash)) "hash should have 4 elements")
      (is (every? number? hash) "hash elements should be numbers")
      (is (= value (cache/lookup mgr hash)) "lookup should return original value"))))

(deftest content-addressability
  (testing "same value produces same hash"
    (let [mgr (cache/memory-cache-manager)
          h1 (cache/commit! mgr ["string" "hello"])
          h2 (cache/commit! mgr ["string" "hello"])]
      (is (= h1 h2) "same value should produce same hash")))

  (testing "different values produce different hashes"
    (let [mgr (cache/memory-cache-manager)
          h1 (cache/commit! mgr ["string" "hello"])
          h2 (cache/commit! mgr ["string" "world"])]
      (is (not= h1 h2) "different values should produce different hashes")))

  (testing "different types produce different hashes"
    (let [mgr (cache/memory-cache-manager)
          h1 (cache/commit! mgr ["i64" 42])
          h2 (cache/commit! mgr ["f64" 42.0])]
      (is (not= h1 h2) "different types should produce different hashes"))))

(deftest lookup-missing
  (testing "lookup returns nil for unknown hash"
    (let [mgr (cache/memory-cache-manager)
          fake-hash [0 0 0 0]]
      (is (nil? (cache/lookup mgr fake-hash))))))

(deftest multiple-values
  (testing "cache can hold multiple values"
    (let [mgr (cache/memory-cache-manager)
          values [["i64" 1] ["i64" 2] ["string" "a"] ["bool" true]]
          hashes (mapv #(cache/commit! mgr %) values)]
      (is (= (count (set hashes)) (count hashes)) "all hashes should be unique")
      (doseq [[hash value] (map vector hashes values)]
        (is (= value (cache/lookup mgr hash)) "each value should be retrievable")))))

(deftest hash-hex-conversion
  (testing "hash->hex produces 64-char string"
    (let [mgr (cache/memory-cache-manager)
          hash (cache/commit! mgr ["i64" 42])
          hex (hash/hash->hex hash)]
      (is (string? hex))
      (is (= 64 (count hex)))
      (is (re-matches #"[0-9a-f]+" hex) "should be lowercase hex")))

  (testing "hex->hash round-trips"
    (let [mgr (cache/memory-cache-manager)
          hash (cache/commit! mgr ["string" "test"])
          hex (hash/hash->hex hash)
          hash2 (hash/hex->hash hex)]
      (is (= hash hash2) "round-trip should preserve hash"))))

(deftest stats-and-clear
  (testing "stats reports count"
    (let [mgr (cache/memory-cache-manager)]
      (is (= 0 (:count (cache/stats mgr))))
      (cache/commit! mgr ["i64" 1])
      (is (= 1 (:count (cache/stats mgr))))
      (cache/commit! mgr ["i64" 2])
      (is (= 2 (:count (cache/stats mgr))))))

  (testing "clear! removes all values"
    (let [mgr (cache/memory-cache-manager)
          h (cache/commit! mgr ["i64" 42])]
      (is (= ["i64" 42] (cache/lookup mgr h)))
      (cache/clear! mgr)
      (is (= 0 (:count (cache/stats mgr))))
      (is (nil? (cache/lookup mgr h))))))

(deftest global-cache
  (testing "global cache operations"
    (cache/init-global-cache!)
    (let [h (cache/global-commit! ["i64" 999])]
      (is (= ["i64" 999] (cache/global-lookup h))))))

;; =============================================================================
;; Property-based tests
;; =============================================================================

(def gen-type
  "Generator for Dacite type names."
  (gen/elements ["i64" "i32" "string" "bool" "f64" "f32"]))

(def gen-scalar-data
  "Generator for scalar value data."
  (gen/one-of [gen/small-integer
               gen/string-alphanumeric
               gen/boolean
               (gen/double* {:infinite? false :NaN? false})]))

(def gen-dacite-value
  "Generator for Dacite values [type, data]."
  (gen/tuple gen-type gen-scalar-data))

(defspec commit-then-lookup-returns-value 100
  (prop/for-all [value gen-dacite-value]
                (let [mgr (cache/memory-cache-manager)
                      hash (cache/commit! mgr value)]
                  (= value (cache/lookup mgr hash)))))

(defspec same-value-same-hash 100
  (prop/for-all [value gen-dacite-value]
                (let [mgr (cache/memory-cache-manager)
                      h1 (cache/commit! mgr value)
                      h2 (cache/commit! mgr value)]
                  (= h1 h2))))

(defspec hash-is-valid-256bit 100
  (prop/for-all [value gen-dacite-value]
                (let [mgr (cache/memory-cache-manager)
                      hash (cache/commit! mgr value)]
                  (and (vector? hash)
                       (= 4 (count hash))
                       (every? int? hash)))))

(defspec hex-round-trip 100
  (prop/for-all [value gen-dacite-value]
                (let [mgr (cache/memory-cache-manager)
                      hash (cache/commit! mgr value)
                      hex (hash/hash->hex hash)
                      hash2 (hash/hex->hash hex)]
                  (= hash hash2))))
