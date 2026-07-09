(ns dacite.value.serial-test
  "Tests for Dacite binary serialization."
  (:require [clojure.test :refer [deftest is testing]]
            [dacite.value.serial :as serial]))

;; =============================================================================
;; Scalar round-trips
;; =============================================================================

(deftest serialize-null-test
  (testing "null serializes to 2 bytes (tag + zero length)"
    (let [bs (serial/serialize ["null" nil])]
      (is (= 2 (alength bs)))
      (is (= 0x00 (aget bs 0)))
      (is (= 0 (aget bs 1))))))

(deftest serialize-bool-test
  (testing "bool true"
    (let [bs (serial/serialize ["bool" true])]
      (is (= 3 (alength bs)))
      (is (= 1 (aget bs 2)))))
  (testing "bool false"
    (let [bs (serial/serialize ["bool" false])]
      (is (= 3 (alength bs)))
      (is (= 0 (aget bs 2))))))

(deftest serialize-i64-test
  (testing "i64 serializes to 10 bytes"
    (let [bs (serial/serialize ["i64" 42])]
      (is (= 10 (alength bs)))
      (is (= 0x00 (aget bs 0)))  ;; kind: scalar
      (is (= 8 (aget bs 1))))))  ;; length: 8 bytes

(deftest serialize-u8-test
  (testing "u8 serializes to 3 bytes"
    (let [bs (serial/serialize ["u8" 255])]
      (is (= 3 (alength bs))))))

(deftest serialize-f64-test
  (testing "f64 serializes to 10 bytes"
    (let [bs (serial/serialize ["f64" 3.14])]
      (is (= 10 (alength bs))))))

(deftest serialize-char-test
  (testing "ASCII char serializes to 3 bytes"
    (let [bs (serial/serialize ["char" \a])]
      (is (= 3 (alength bs)))))
  (testing "Multi-byte char serializes correctly"
    (let [bs (serial/serialize ["char" \λ])]
      ;; λ is 2 bytes in UTF-8
      (is (= 4 (alength bs))))))

(deftest scalar-deserialize-test
  (testing "Scalar round-trip returns raw bytes"
    (let [bs (serial/serialize ["i64" 42])
          result (serial/deserialize bs)]
      (is (bytes? result))
      (is (= 8 (alength result)))
      ;; 42 as big-endian i64
      (is (= 42 (.getLong (java.nio.ByteBuffer/wrap result)))))))

(deftest null-deserialize-test
  (testing "Null round-trip returns empty byte array"
    (let [result (serial/deserialize (serial/serialize ["null" nil]))]
      (is (bytes? result))
      (is (= 0 (alength result))))))

(deftest bool-deserialize-test
  (testing "Bool round-trip"
    (let [result (serial/deserialize (serial/serialize ["bool" true]))]
      (is (= [1] (vec result))))
    (let [result (serial/deserialize (serial/serialize ["bool" false]))]
      (is (= [0] (vec result))))))

;; =============================================================================
;; Seq node round-trips
;; =============================================================================

(def sample-measure
  {:count 3 :size-bytes 24 :elements-fuse [100 200 300 400]})

(def sample-hash-a [1 2 3 4])
(def sample-hash-b [5 6 7 8])
(def sample-hash-c [9 10 11 12])

(deftest serialize-ft-empty-test
  (testing "ft/empty serializes and deserializes"
    (let [entry ["ft/empty" {:measure sample-measure}]
          bs (serial/serialize entry)
          result (serial/deserialize bs)]
      (is (= 0x01 (aget bs 0)))  ;; kind: seq
      (is (= "ft/empty" (first result)))
      (is (= sample-measure (:measure (second result)))))))

(deftest serialize-ft-single-test
  (testing "ft/single round-trip"
    (let [entry ["ft/single" {:value-hash sample-hash-a
                              :measure sample-measure}]
          bs (serial/serialize entry)
          result (serial/deserialize bs)]
      (is (= "ft/single" (first result)))
      (is (= sample-hash-a (:value-hash (second result))))
      (is (= sample-measure (:measure (second result)))))))

(deftest serialize-ft-digit-test
  (testing "ft/digit round-trip with multiple children"
    (let [children [sample-hash-a sample-hash-b sample-hash-c]
          entry ["ft/digit" {:children children
                             :measure sample-measure}]
          bs (serial/serialize entry)
          result (serial/deserialize bs)]
      (is (= "ft/digit" (first result)))
      (is (= children (:children (second result))))
      (is (= sample-measure (:measure (second result)))))))

(deftest serialize-ft-node-test
  (testing "ft/node round-trip"
    (let [children [sample-hash-a sample-hash-b]
          entry ["ft/node" {:children children
                            :measure sample-measure}]
          bs (serial/serialize entry)
          result (serial/deserialize bs)]
      (is (= "ft/node" (first result)))
      (is (= children (:children (second result)))))))

(deftest serialize-ft-deep-test
  (testing "ft/deep round-trip"
    (let [entry ["ft/deep" {:left sample-hash-a
                            :spine sample-hash-b
                            :right sample-hash-c
                            :measure sample-measure}]
          bs (serial/serialize entry)
          result (serial/deserialize bs)]
      (is (= "ft/deep" (first result)))
      (is (= sample-hash-a (:left (second result))))
      (is (= sample-hash-b (:spine (second result))))
      (is (= sample-hash-c (:right (second result))))
      (is (= sample-measure (:measure (second result)))))))

;; =============================================================================
;; Map node round-trips
;; =============================================================================

(deftest serialize-hamt-empty-test
  (testing "hamt/empty round-trip"
    (let [entry ["hamt/empty" {:measure sample-measure}]
          bs (serial/serialize entry)
          result (serial/deserialize bs)]
      (is (= 0x02 (aget bs 0)))  ;; kind: map
      (is (= "hamt/empty" (first result)))
      (is (= sample-measure (:measure (second result)))))))

(deftest serialize-hamt-entry-test
  (testing "hamt/entry round-trip"
    (let [entry ["hamt/entry" {:key-hash sample-hash-a
                               :key-ref sample-hash-b
                               :val-ref sample-hash-c
                               :measure sample-measure}]
          bs (serial/serialize entry)
          result (serial/deserialize bs)]
      (is (= "hamt/entry" (first result)))
      (is (= sample-hash-a (:key-hash (second result))))
      (is (= sample-hash-b (:key-ref (second result))))
      (is (= sample-hash-c (:val-ref (second result))))
      (is (= sample-measure (:measure (second result)))))))

(deftest serialize-hamt-bitmap-test
  (testing "hamt/bitmap round-trip"
    (let [children [sample-hash-a sample-hash-b]
          entry ["hamt/bitmap" {:bitmap 0x60000000
                                :children children
                                :measure sample-measure}]
          bs (serial/serialize entry)
          result (serial/deserialize bs)]
      (is (= "hamt/bitmap" (first result)))
      (is (= 0x60000000 (:bitmap (second result))))
      (is (= children (:children (second result))))
      (is (= sample-measure (:measure (second result)))))))

;; =============================================================================
;; Collection round-trips
;; =============================================================================

(deftest serialize-vector-collection-test
  (testing "vector collection round-trip"
    (let [entry ["vector" {:root sample-hash-a :count 3 :size-bytes 24}]
          bs (serial/serialize entry)
          result (serial/deserialize bs)]
      (is (= 50 (alength bs)))
      (is (= 0x03 (aget bs 0)))  ;; kind: collection
      (is (= 0x00 (aget bs 1)))  ;; subtype: vector
      (is (= "vector" (first result)))
      (is (= sample-hash-a (:root (second result))))
      (is (= 3 (:count (second result))))
      (is (= 24 (:size-bytes (second result)))))))

(deftest serialize-string-collection-test
  (testing "string collection round-trip"
    (let [entry ["string" {:root sample-hash-b :count 5 :size-bytes 5}]
          bs (serial/serialize entry)
          result (serial/deserialize bs)]
      (is (= 50 (alength bs)))
      (is (= "string" (first result)))
      (is (= sample-hash-b (:root (second result))))
      (is (= 5 (:count (second result))))
      (is (= 5 (:size-bytes (second result)))))))

(deftest serialize-blob-collection-test
  (testing "blob collection round-trip"
    (let [entry ["blob" {:root sample-hash-c :count 1024 :size-bytes 1024}]
          bs (serial/serialize entry)
          result (serial/deserialize bs)]
      (is (= 50 (alength bs)))
      (is (= "blob" (first result)))
      (is (= sample-hash-c (:root (second result))))
      (is (= 1024 (:count (second result))))
      (is (= 1024 (:size-bytes (second result)))))))

(deftest serialize-map-collection-test
  (testing "map collection round-trip"
    (let [entry ["map" {:root sample-hash-a :count 5 :size-bytes 80}]
          bs (serial/serialize entry)
          result (serial/deserialize bs)]
      (is (= 50 (alength bs)))
      (is (= 0x03 (aget bs 1)))  ;; subtype: map
      (is (= "map" (first result)))
      (is (= sample-hash-a (:root (second result))))
      (is (= 5 (:count (second result))))
      (is (= 80 (:size-bytes (second result)))))))

(deftest collection-fixed-size-test
  (testing "all collection types serialize to exactly 50 bytes"
    (doseq [type-name ["vector" "string" "blob" "map"]]
      (let [bs (serial/serialize [type-name {:root [0 0 0 0] :count 0 :size-bytes 0}])]
        (is (= 50 (alength bs)) (str type-name " should be 50 bytes"))))))

;; =============================================================================
;; Error cases
;; =============================================================================

(deftest serialize-unknown-type-falls-through-test
  (testing "Unknown types use default encode-value (pr-str fallback)"
    ;; Unrecognized types hit the :default encode-value method
    ;; which produces pr-str bytes — no exception
    (let [bs (serial/serialize ["ft/bogus" {:measure sample-measure}])]
      (is (bytes? bs)))))

(deftest deserialize-unknown-kind-throws-test
  (testing "Unknown kind tag throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (serial/deserialize (byte-array [0x04]))))))

;; =============================================================================
;; Size validation
;; =============================================================================

(deftest measure-serialization-size-test
  (testing "Measure always occupies 48 bytes in seq/map nodes"
    ;; ft/empty: 1(kind) + 1(subtype) + 48(measure) + 1(n_children) = 51
    (let [bs (serial/serialize ["ft/empty" {:measure sample-measure}])]
      (is (= 51 (alength bs))))
    ;; hamt/empty: 1(kind) + 1(subtype) + 48(measure) = 50
    (let [bs (serial/serialize ["hamt/empty" {:measure sample-measure}])]
      (is (= 50 (alength bs))))))

(deftest entry-size-test
  (testing "hamt/entry is 146 bytes (2 + 48 + 3*32)"
    (let [bs (serial/serialize ["hamt/entry" {:key-hash [0 0 0 0]
                                              :key-ref [0 0 0 0]
                                              :val-ref [0 0 0 0]
                                              :measure sample-measure}])]
      (is (= 146 (alength bs))))))
