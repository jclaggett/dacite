(ns dacite.value.serial
  "LEGACY binary serialization for Dacite nodes.

   Prefer docs/spec/wire-v1.md and dacite.wire.binary for interop.
   This ns remains for older tests and directional layouts (ft/hamt/collection).

   Serializes and deserializes individual store entries to/from an early
   binary layout (no chunk envelope, no pack literals).

   Binary format overview:
   - Scalar:     0x00 + u8(len) + raw-bytes
   - Seq node:   0x01 + u8(subtype) + measure(48B) + u8(n) + hash[n]
   - Map node:   0x02 + u8(subtype) + measure(48B) + type-specific fields
   - Collection: 0x03 + u8(subtype) + hash(root,32B) + u64(count) + u64(size-bytes)

   Measures are fixed 48 bytes: u64(count) + u64(size-bytes) + hash(32B).
   Hashes are 32 bytes (4 × i64, big-endian). All integers big-endian."
  (:require [dacite.value.types :as types]
            [dacite.value.scalar])
  (:import [java.nio ByteBuffer]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const kind-scalar     0x00)
(def ^:const kind-seq        0x01)
(def ^:const kind-map        0x02)
(def ^:const kind-collection 0x03)

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
;; encode-value: finger tree nodes
;; =============================================================================

(defn- encode-ft-node [subtype {:keys [measure] :as data}]
  ;; Subtype tags: 0 empty, 1 reserved (was ft/single), 2 digit, 3 node, 4 deep
  (let [children (case (int subtype)
                   0 []
                   2 (:children data)
                   3 (:children data)
                   4 [(:left data) (:spine data) (:right data)])
        n (count children)
        buf (ByteBuffer/allocate (+ 2 48 1 (* 32 n)))]
    (write-u8 buf kind-seq)
    (write-u8 buf subtype)
    (write-measure buf measure)
    (write-u8 buf n)
    (doseq [h children]
      (write-hash buf h))
    (.array buf)))

(defmethod types/encode-value "ft/empty" [[_ data]]
  (encode-ft-node 0 data))

(defmethod types/encode-value "ft/digit" [[_ data]]
  (encode-ft-node 2 data))

(defmethod types/encode-value "ft/node" [[_ data]]
  (encode-ft-node 3 data))

(defmethod types/encode-value "ft/deep" [[_ data]]
  (encode-ft-node 4 data))

;; =============================================================================
;; encode-value: HAMT nodes
;; =============================================================================

(defmethod types/encode-value "hamt/empty" [[_ data]]
  (let [buf (ByteBuffer/allocate (+ 2 48))]
    (write-u8 buf kind-map)
    (write-u8 buf 0)
    (write-measure buf (:measure data))
    (.array buf)))

(defmethod types/encode-value "hamt/entry" [[_ data]]
  (let [buf (ByteBuffer/allocate (+ 2 48 (* 3 32)))]
    (write-u8 buf kind-map)
    (write-u8 buf 1)
    (write-measure buf (:measure data))
    (write-hash buf (:key-hash data))
    (write-hash buf (:key-ref data))
    (write-hash buf (:val-ref data))
    (.array buf)))

(defmethod types/encode-value "hamt/bitmap" [[_ data]]
  (let [children (:children data)
        n (count children)
        buf (ByteBuffer/allocate (+ 2 48 4 1 (* 32 n)))]
    (write-u8 buf kind-map)
    (write-u8 buf 2)
    (write-measure buf (:measure data))
    (write-u32 buf (:bitmap data))
    (write-u8 buf n)
    (doseq [h children]
      (write-hash buf h))
    (.array buf)))

;; =============================================================================
;; encode-value: collection headers
;; =============================================================================

(defn- encode-collection [subtype {:keys [root count size-bytes]}]
  (let [buf (ByteBuffer/allocate 50)]
    (write-u8 buf kind-collection)
    (write-u8 buf subtype)
    (write-hash buf root)
    (write-u64 buf count)
    (write-u64 buf size-bytes)
    (.array buf)))

(defmethod types/encode-value "vector" [[_ data]]
  (if (and (map? data) (:root data))
    (encode-collection 0 data)
    (.getBytes (pr-str data) "UTF-8")))

(defmethod types/encode-value "string" [[_ data]]
  (if (and (map? data) (:root data))
    (encode-collection 1 data)
    (.getBytes (pr-str data) "UTF-8")))

(defmethod types/encode-value "blob" [[_ data]]
  (if (and (map? data) (:root data))
    (encode-collection 2 data)
    (.getBytes (pr-str data) "UTF-8")))

(defmethod types/encode-value "map" [[_ data]]
  (if (and (map? data) (:root data))
    (encode-collection 3 data)
    (.getBytes (pr-str data) "UTF-8")))

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
   Format: 0x00 + u8(len) + canonical-bytes.

   encode-value returns portable bytes (a vector of ints 0..255, or a host
   byte array for u256); bridge to a Java byte array for the buffer."
  [typed-value]
  (let [^bytes data-bytes (byte-array (map unchecked-byte (types/encode-value typed-value)))
        len (alength data-bytes)
        buf (ByteBuffer/allocate (+ 2 len))]
    (write-u8 buf kind-scalar)
    (write-u8 buf len)
    (.put buf data-bytes)
    (.array buf)))

(defn serialize
  "Serialize a store entry [type-name, data] to canonical binary bytes.

   Scalars are wrapped with a 0x00 + u8(len) framing header.
   All other types (collections, finger tree nodes, HAMT nodes)
   delegate directly to their encode-value implementations which
   produce self-describing binary with kind tags."
  [[type-name _data :as entry]]
  (if (scalar-type? type-name)
    (serialize-scalar entry)
    (types/encode-value entry)))

;; =============================================================================
;; Deserialize: bytes → store entry
;; =============================================================================

(defn- subtype->seq-type
  "Map binary subtype tag to seq node type name.
   Tag 1 is reserved (former ft/single); no longer a valid type."
  [subtype]
  (case (int subtype)
    0 "ft/empty"
    2 "ft/digit"
    3 "ft/node"
    4 "ft/deep"
    (throw (ex-info "unsupported or removed ft subtype"
                    {:subtype (int subtype)}))))

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
      2 [type-name {:children children
                    :measure measure}]
      3 [type-name {:children children
                    :measure measure}]
      4 [type-name {:left (nth children 0)
                    :spine (nth children 1)
                    :right (nth children 2)
                    :measure measure}]
      (throw (ex-info "unsupported or removed ft subtype"
                      {:subtype (int subtype)})))))

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
