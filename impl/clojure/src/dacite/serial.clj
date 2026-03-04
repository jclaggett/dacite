(ns dacite.serial
  "Binary serialization for Dacite nodes.

   Serializes and deserializes individual store entries to/from the
   canonical binary format defined in the Dacite spec.

   Binary format overview:
   - Scalar:     0x00 + u8(len) + raw-bytes
   - Seq node:   0x01 + u8(subtype) + measure(48B) + u8(n) + hash[n]
   - Map node:   0x02 + u8(subtype) + measure(48B) + type-specific fields
   - Collection: 0x03 + u8(subtype) + hash(root,32B) + u64(count) + u64(size-bytes)

   Measures are fixed 48 bytes: u64(count) + u64(size-bytes) + hash(32B).
   Hashes are 32 bytes (4 × i64, big-endian). All integers big-endian."
  (:require [dacite.types :as types])
  (:import [java.nio ByteBuffer]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const kind-scalar     0x00)
(def ^:const kind-seq        0x01)
(def ^:const kind-map        0x02)
(def ^:const kind-collection 0x03)

;; Seq node subtypes
(def ^:const seq-empty    0x00)
(def ^:const seq-single   0x01)
(def ^:const seq-digit    0x02)
(def ^:const seq-node     0x03)
(def ^:const seq-deep     0x04)

;; Map node subtypes
(def ^:const map-empty    0x00)
(def ^:const map-entry    0x01)
(def ^:const map-bitmap   0x02)

;; Collection subtypes
(def ^:const coll-vector  0x00)
(def ^:const coll-string  0x01)
(def ^:const coll-blob    0x02)
(def ^:const coll-map     0x03)

;; =============================================================================
;; Write helpers
;; =============================================================================

(defn- write-u8 [^ByteBuffer buf n]
  (.put buf (unchecked-byte n)))

(defn- write-u32 [^ByteBuffer buf n]
  (.putInt buf (unchecked-int n)))

(defn- write-u64 [^ByteBuffer buf n]
  (.putLong buf (long n)))

(defn- write-hash [^ByteBuffer buf [a b c d]]
  (.putLong buf (long a))
  (.putLong buf (long b))
  (.putLong buf (long c))
  (.putLong buf (long d)))

(defn- write-measure [^ByteBuffer buf {:keys [count size-bytes elements-fuse]}]
  (write-u64 buf count)
  (write-u64 buf size-bytes)
  (write-hash buf elements-fuse))

;; =============================================================================
;; Read helpers
;; =============================================================================

(defn- read-u8 [^ByteBuffer buf]
  (Byte/toUnsignedInt (.get buf)))

(defn- read-u32 [^ByteBuffer buf]
  (Integer/toUnsignedLong (.getInt buf)))

(defn- read-u64 [^ByteBuffer buf]
  (.getLong buf))

(defn- read-hash [^ByteBuffer buf]
  [(.getLong buf) (.getLong buf) (.getLong buf) (.getLong buf)])

(defn- read-measure [^ByteBuffer buf]
  {:count (read-u64 buf)
   :size-bytes (read-u64 buf)
   :elements-fuse (read-hash buf)})

(defn- read-hashes [^ByteBuffer buf n]
  (mapv (fn [_] (read-hash buf)) (range n)))

;; =============================================================================
;; Serialize: store entry → bytes
;; =============================================================================

(defn- scalar-type?
  "Is this type name a scalar (not a collection or internal node)?"
  [type-name]
  (not (or (#{"string" "vector" "map" "blob"} type-name)
           (.startsWith ^String type-name "ft/")
           (.startsWith ^String type-name "hamt/"))))

(defn- serialize-scalar
  "Serialize a scalar typed value to bytes.
   Format: 0x00 + u8(len) + canonical-bytes"
  [typed-value]
  (let [data-bytes (types/encode-value typed-value)
        len (alength data-bytes)
        buf (ByteBuffer/allocate (+ 2 len))]
    (write-u8 buf kind-scalar)
    (write-u8 buf len)
    (.put buf ^bytes data-bytes)
    (.array buf)))

(defn- seq-subtype
  "Map a seq node type name to its binary subtype tag."
  [type-name]
  (case type-name
    "ft/empty"  seq-empty
    "ft/single" seq-single
    "ft/digit"  seq-digit
    "ft/node"   seq-node
    "ft/deep"   seq-deep))

(defn- serialize-seq-node
  "Serialize a finger tree node to bytes.
   Format: 0x01 + u8(subtype) + measure(48B) + u8(n) + hash[n]"
  [type-name data]
  (let [subtype (seq-subtype type-name)
        measure (:measure data)
        children (case (int subtype)
                   0 []                              ;; empty
                   1 [(:value-hash data)]            ;; single
                   2 (:children data)                ;; digit
                   3 (:children data)                ;; node
                   4 [(:left data) (:spine data) (:right data)]) ;; deep
        n (count children)
        buf (ByteBuffer/allocate (+ 2 48 1 (* 32 n)))]
    (write-u8 buf kind-seq)
    (write-u8 buf subtype)
    (write-measure buf measure)
    (write-u8 buf n)
    (doseq [h children]
      (write-hash buf h))
    (.array buf)))

(defn- map-subtype
  "Map a HAMT node type name to its binary subtype tag."
  [type-name]
  (case type-name
    "hamt/empty"  map-empty
    "hamt/entry"  map-entry
    "hamt/bitmap" map-bitmap))

(defn- serialize-map-node
  "Serialize a HAMT node to bytes.
   Format: 0x02 + u8(subtype) + measure(48B) + type-specific fields"
  [type-name data]
  (let [subtype (map-subtype type-name)
        measure (:measure data)]
    (case (int subtype)
      ;; empty: just header + measure
      0 (let [buf (ByteBuffer/allocate (+ 2 48))]
          (write-u8 buf kind-map)
          (write-u8 buf subtype)
          (write-measure buf measure)
          (.array buf))

      ;; entry: measure + key-hash + key-ref + val-ref
      1 (let [buf (ByteBuffer/allocate (+ 2 48 (* 3 32)))]
          (write-u8 buf kind-map)
          (write-u8 buf subtype)
          (write-measure buf measure)
          (write-hash buf (:key-hash data))
          (write-hash buf (:key-ref data))
          (write-hash buf (:val-ref data))
          (.array buf))

      ;; bitmap: measure + u32(bitmap) + u8(n) + hash[n]
      2 (let [children (:children data)
              n (count children)
              buf (ByteBuffer/allocate (+ 2 48 4 1 (* 32 n)))]
          (write-u8 buf kind-map)
          (write-u8 buf subtype)
          (write-measure buf measure)
          (write-u32 buf (:bitmap data))
          (write-u8 buf n)
          (doseq [h children]
            (write-hash buf h))
          (.array buf)))))

(defn- collection-type?
  "Is this type name a top-level collection (string, vector, map, blob)?"
  [type-name]
  (#{"string" "vector" "map" "blob"} type-name))

(defn- collection-subtype
  "Map a collection type name to its binary subtype tag."
  [type-name]
  (case type-name
    "vector" coll-vector
    "string" coll-string
    "blob"   coll-blob
    "map"    coll-map))

(defn- serialize-collection
  "Serialize a collection header to bytes.
   Format: 0x03 + u8(subtype) + hash(root) + u64(count) + u64(size-bytes)"
  [type-name data]
  (let [subtype (collection-subtype type-name)
        buf (ByteBuffer/allocate 50)]
    (write-u8 buf kind-collection)
    (write-u8 buf subtype)
    (write-hash buf (:root data))
    (write-u64 buf (:count data))
    (write-u64 buf (:size-bytes data))
    (.array buf)))

(defn serialize
  "Serialize a store entry [type-name, data] to canonical binary bytes.

   Scalars encode as: 0x00 + u8(len) + canonical-data-bytes
   Seq nodes encode as: 0x01 + u8(subtype) + measure + children
   Map nodes encode as: 0x02 + u8(subtype) + measure + type-specific
   Collections encode as: 0x03 + u8(subtype) + hash(root) + u64(size-bytes)"
  [[type-name data :as entry]]
  (cond
    (scalar-type? type-name)
    (serialize-scalar entry)

    (.startsWith ^String type-name "ft/")
    (serialize-seq-node type-name data)

    (.startsWith ^String type-name "hamt/")
    (serialize-map-node type-name data)

    (collection-type? type-name)
    (serialize-collection type-name data)

    :else
    (throw (ex-info (str "Unknown type for serialization: " type-name)
                    {:type type-name}))))

;; =============================================================================
;; Deserialize: bytes → store entry
;; =============================================================================

(defn- subtype->seq-type
  "Map binary subtype tag to seq node type name."
  [subtype]
  (case (int subtype)
    0 "ft/empty"
    1 "ft/single"
    2 "ft/digit"
    3 "ft/node"
    4 "ft/deep"))

(defn- subtype->map-type
  "Map binary subtype tag to map node type name."
  [subtype]
  (case (int subtype)
    0 "hamt/empty"
    1 "hamt/entry"
    2 "hamt/bitmap"))

(defn- deserialize-scalar
  "Deserialize a scalar from buffer (kind tag already consumed).
   Returns raw bytes. The caller must determine the typed value
   from context (the hash maps to a known [type-name data] in store)."
  [^ByteBuffer buf]
  (let [len (read-u8 buf)
        bs (byte-array len)]
    (.get buf bs)
    bs))

(defn- deserialize-seq-node
  "Deserialize a seq node from buffer (kind tag already consumed)."
  [^ByteBuffer buf]
  (let [subtype (read-u8 buf)
        type-name (subtype->seq-type subtype)
        measure (read-measure buf)
        n (read-u8 buf)
        children (read-hashes buf n)]
    (case (int subtype)
      0 [type-name {:measure measure}]
      1 [type-name {:value-hash (first children)
                    :measure measure}]
      2 [type-name {:children children
                    :measure measure}]
      3 [type-name {:children children
                    :measure measure}]
      4 [type-name {:left (nth children 0)
                    :spine (nth children 1)
                    :right (nth children 2)
                    :measure measure}])))

(defn- deserialize-map-node
  "Deserialize a map node from buffer (kind tag already consumed)."
  [^ByteBuffer buf]
  (let [subtype (read-u8 buf)
        type-name (subtype->map-type subtype)
        measure (read-measure buf)]
    (case (int subtype)
      0 [type-name {:measure measure}]

      1 (let [key-hash (read-hash buf)
              key-ref (read-hash buf)
              val-ref (read-hash buf)]
          [type-name {:key-hash key-hash
                      :key-ref key-ref
                      :val-ref val-ref
                      :measure measure}])

      2 (let [bitmap (read-u32 buf)
              n (read-u8 buf)
              children (read-hashes buf n)]
          [type-name {:bitmap bitmap
                      :children children
                      :measure measure}]))))

(defn- subtype->collection-type
  "Map binary subtype tag to collection type name."
  [subtype]
  (case (int subtype)
    0 "vector"
    1 "string"
    2 "blob"
    3 "map"))

(defn- deserialize-collection
  "Deserialize a collection header from buffer (kind tag already consumed)."
  [^ByteBuffer buf]
  (let [subtype (read-u8 buf)
        type-name (subtype->collection-type subtype)
        root (read-hash buf)
        cnt (read-u64 buf)
        size-bytes (read-u64 buf)]
    [type-name {:root root
                :count cnt
                :size-bytes size-bytes}]))

(defn deserialize
  "Deserialize binary bytes to a store entry.

   Returns:
   - For scalars: raw byte array (type info not encoded in binary format;
     the store maps hash → [type-name data])
   - For seq nodes: [type-name data] with measure and child hashes
   - For map nodes: [type-name data] with measure and type-specific fields
   - For collections: [type-name {:root hash :size-bytes n}]

   Note: Scalar deserialization returns raw bytes because the binary
   format only encodes the canonical data bytes, not the type name.
   The receiver must know the type (from the hash → entry mapping)
   to reconstruct the full typed value."
  [^bytes bs]
  (let [buf (ByteBuffer/wrap bs)
        kind (read-u8 buf)]
    (case (int kind)
      0 (deserialize-scalar buf)
      1 (deserialize-seq-node buf)
      2 (deserialize-map-node buf)
      3 (deserialize-collection buf)
      (throw (ex-info (str "Unknown node kind: " kind) {:kind kind})))))
