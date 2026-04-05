(ns dacite.value.types
  "Dacite type system.
   
   All Dacite values are [type, data] tuples where:
   - type is a string identifying the type (e.g. \"i64\", \"vector\")
   - data is the type-specific payload
   
   This namespace defines:
   - IDaciteHash protocol for Dacite wrapper types
   - Accessors for extracting type and data
   - The dacite-size multimethod for computing value sizes
   - The encode-value multimethod for canonical byte encoding
   - typed-value-hash and node-hash for content addressing
   - Built-in primitive type implementations
   
   To add a new type, extend dacite-size and encode-value:
   (defmethod dacite-size \"my-type\" [[_ data]] ...)
   (defmethod encode-value \"my-type\" [[_ data]] ...)"
  (:require [dacite.hash :as hash]))

;; =============================================================================
;; IDaciteHash protocol
;; =============================================================================

(defprotocol IDaciteHash
  (dacite-hash [this] "Return the internal hash of this Dacite value."))

;; =============================================================================
;; Value accessors
;; =============================================================================

(defn dacite-type
  "Get the type string from a Dacite value [type, data]."
  [[type-name _]]
  type-name)

(defn dacite-data
  "Get the data from a Dacite value [type, data]."
  [[_ data]]
  data)

;; =============================================================================
;; Size multimethod
;; =============================================================================

(defmulti dacite-size
  "Get the size in bytes of a Dacite value [type, data].
   
   Dispatches on type string. To add a new type:
   (defmethod dacite-size \"my-type\" [[_ data]] ...)
   
   Collections with :size-bytes in data return it directly.
   Primitives should define explicit methods."
  dacite-type)

;; Default: check for :measure (collections), otherwise estimate
(defmethod dacite-size :default [[_ data]]
  (if-let [measure (:measure data)]
    (:size-bytes measure)
    ;; Fallback: serialize and measure (not ideal, but safe)
    (count (.getBytes (pr-str data) "UTF-8"))))

;; =============================================================================
;; Canonical value encoding (multimethod)
;; =============================================================================

(defmulti encode-value
  "Encode a typed Dacite value to canonical bytes for hashing.
   
   Dispatches on type-name string. Each scalar type has a fixed-width,
   big-endian, language-agnostic encoding:
   
     null  → 0 bytes
     bool  → 1 byte (0x00 or 0x01)
     i8    → 1 byte signed
     i16   → 2 bytes big-endian signed
     i32   → 4 bytes big-endian signed
     i64   → 8 bytes big-endian signed
     u8    → 1 byte unsigned
     u16   → 2 bytes big-endian unsigned
     u32   → 4 bytes big-endian unsigned
     u64   → 8 bytes big-endian unsigned
     u256  → 32 bytes raw
     f32   → 4 bytes IEEE 754 big-endian
     f64   → 8 bytes IEEE 754 big-endian
     char  → 1-4 bytes UTF-8
   
   Collections (string, vector, map, blob) are hashed via their
   elements_fuse measure, not via encode-value."
  (fn [[type-name _data]] type-name))

;; Default: fall back to pr-str for types without a canonical encoding.
;; This covers collection types (string, vector, map, blob) when they
;; pass through typed-value-hash in the cache layer. Collection hashes
;; in core use node-hash + elements_fuse instead.
(defmethod encode-value :default [[_ data]]
  (.getBytes (pr-str data) "UTF-8"))

;; =============================================================================
;; Typed value and node hashing
;; =============================================================================

(def ^:private ^bytes null-separator
  "Domain separator byte (0x00) between type name and data.
   Prevents boundary collisions: since type names cannot contain
   null bytes, type ++ 0x00 ++ data is an unambiguous encoding."
  (byte-array [0]))

(def ^:private null-separator-hash
  (hash/fuse-bytes null-separator))

(defn typed-value-hash
  "Compute the hash of a typed value [type-name, data].
   
   A typed value is conceptually seq(type-name, data).
   The hash is: fuse-bytes(type-name-bytes ++ 0x00 ++ encode(data))
   
   type-name is a string (e.g. \"i64\", \"vector\").
   The null byte acts as a domain separator — since type names
   cannot contain null bytes, this prevents boundary collisions."
  [[type-name _data :as typed-value]]
  (let [type-bytes (.getBytes ^String type-name "UTF-8")
        data-bytes (encode-value typed-value)]
    (-> (hash/fuse-bytes type-bytes)
        (hash/unchecked-fuse null-separator-hash)
        (hash/unchecked-fuse (hash/fuse-bytes data-bytes)))))

(defn node-hash
  "Compute the hash of an internal tree node.
   
   node_hash = fuse(fuse-str(node-type-name ++ 0x00), elements-fuse)
   
   type-name is a string (e.g. \"ft/empty\", \"hamt/bitmap\").
   The null byte terminates the type name, preventing collisions
   with longer type names. Uses unchecked-fuse since elements-fuse
   may be [0,0,0,0] for empty nodes."
  [type-name elements-fuse]
  (let [type-bytes (.getBytes ^String type-name "UTF-8")]
    (-> (hash/fuse-bytes type-bytes)
        (hash/unchecked-fuse null-separator-hash)
        (hash/unchecked-fuse elements-fuse))))

;; =============================================================================
;; Child hash extraction (multimethod)
;; =============================================================================

(defmulti child-hashes
  "Extract child hash references from a stored node value.
   Returns an ordered vector of hashes that this node directly references.
   Order is deterministic and structural — both sides of a protocol
   can compute the same sequence independently.

   Dispatches on type string. Each type defines what constitutes
   its children:
   - Scalars: no children (empty vector)
   - Collections (vector, string, blob, map, set): {:root h} → [h]
   - Internal tree nodes: type-specific child references

   Returns nil for nil input, empty vector for unknown types."
  (fn [node]
    (when (and (vector? node) (= 2 (count node)))
      (first node))))

(defmethod child-hashes nil [_] nil)

(defmethod child-hashes :default [_] [])

;; Collection types have a :root child
(doseq [t ["vector" "string" "blob" "map" "set"]]
  (defmethod child-hashes t [[_ data]]
    [(:root data)]))

;; =============================================================================
;; REPL examples
;; =============================================================================

(comment
  ;; Get type and data
  (dacite-type ["i64" 42])   ;; => "i64"
  (dacite-data ["i64" 42])   ;; => 42

  ;; Get size
  (dacite-size ["i64" 42])   ;; => 8
  (dacite-size ["i8" 1])     ;; => 1
  (dacite-size ["bool" true]) ;; => 1

  ;; Typed value hash (with null separator)
  (typed-value-hash ["i64" 42])  ;; => fuse-bytes("i64" ++ 0x00 ++ encode(42))

  ;; Internal node hash
  (node-hash "ft/empty" [0 0 0 0]))
