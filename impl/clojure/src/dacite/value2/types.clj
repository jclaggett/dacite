(ns dacite.value2.types
  "Pure type and hashing rules for the value2 layer (Chapter 3).

   Every value — user-facing or internal — is hashed by one rule:

       value_hash = fuse(type_hash, data_hash)

   where the type hash is the fuse of the type name followed by a
   terminating 0x00 byte:

       type_hash = fuse_bytes(type_name ++ 0x00)

   Type names never contain a null byte, so the trailing 0x00 cleanly
   separates the type from the data that follows (fuse composes over
   concatenation, so a boundary marker is required).

   This namespace is pure: it knows nothing about stores. Stores enter
   at the finger-tree / hamt / scalar / collection layers, where values
   actually persist. Here we only define the hashing algebra, the value
   protocol, and the per-type encoding/size multimethods."
  (:require [dacite.hash :as hash]))

;; =============================================================================
;; Value protocol
;; =============================================================================

(defprotocol IDaciteValue
  "A store-aware, content-addressed Dacite value.

   Dacite values are immutable values, not references, so they do not
   implement IDeref. Converting to a plain Clojure value is an explicit
   call (->clj)."
  (dacite-hash [this] "The value's 4-long content hash.")
  (dacite-store [this] "The store that created and persists this value.")
  (dacite-type [this] "The value's type name string (e.g. \"i64\", \"vector\").")
  (->clj [this]
    "Realize this value as a plain Clojure value. A scalar yields its
     language value (the `\"negative\"` sentinel yields `:dacite/negative`,
     distinct from `null`). A collection yields a lazy seq of its realized
     elements (each element is itself ->clj'd, so sub-collections become
     nested lazy seqs); a map yields a lazy seq of [k v] pairs with both
     key and value realized. Empty collections yield nil (matching `seq`).
     Laziness means only the consumed portion is fetched from the store,
     preserving partial availability for large values."))

;; =============================================================================
;; Stored entry accessors
;; =============================================================================
;; A value persists in the store as a [type-name data] tuple. The first
;; element names the type (self-describing); the second is type-specific.

(defn entry-type
  "Type name from a stored [type-name data] entry."
  [[type-name _]]
  type-name)

(defn entry-data
  "Data payload from a stored [type-name data] entry."
  [[_ data]]
  data)

;; =============================================================================
;; Type / value / node / content hashing
;; =============================================================================

(def ^:private ^bytes null-separator
  "Domain separator (0x00) terminating a type name."
  (byte-array [0]))

(def ^:private null-separator-hash
  (hash/fuse-bytes null-separator))

(def ^:private type-hash-cache (atom {}))

(defn type-hash
  "The type hash for a type name: fuse_bytes(type_name ++ 0x00).

   Computed as fuse(fuse_bytes(name), fuse_bytes(0x00)) — equivalent to
   hashing the concatenation, since fuse composes over concatenation.
   Cached per name. Uses unchecked-fuse: never throws on the separator."
  [type-name]
  (or (@type-hash-cache type-name)
      (let [h (hash/unchecked-fuse
               (hash/fuse-bytes (.getBytes ^String type-name "UTF-8"))
               null-separator-hash)]
        (swap! type-hash-cache assoc type-name h)
        h)))

(defn value-hash
  "value_hash = fuse(type_hash, data_hash).

   Uses unchecked-fuse so that empty/zero data hashes (e.g. null, the
   empty collection) do not trip the low-entropy check."
  [type-name data-hash]
  (hash/unchecked-fuse (type-hash type-name) data-hash))

(def node-hash
  "Internal tree nodes hash by the same rule as every other value:
   fuse(type_hash, elements_fuse)."
  value-hash)

(defn content-hash
  "Strip the type tag, recovering the data hash:

       content_hash = fuse(inv(type_hash), value_hash) = data_hash

   Because value_hash = fuse(type_hash, data_hash) and fuse forms a
   group, left-multiplying by the type hash's inverse cancels it. Two
   values of different types but identical data share a content hash."
  [type-name value-hash-v]
  (hash/unchecked-fuse (hash/fuse-inverse (type-hash type-name)) value-hash-v))

;; =============================================================================
;; Canonical scalar encoding (multimethod)
;; =============================================================================

(defmulti encode-value
  "Canonical bytes for a scalar [type data]. Each scalar type has a
   fixed-width, big-endian, language-agnostic encoding. Collections are
   hashed via their elements_fuse measure, never through encode-value."
  entry-type)

(defmethod encode-value :default [[_ data]]
  (.getBytes (pr-str data) "UTF-8"))

(defn scalar-data-hash
  "data_hash for a scalar = fuse_bytes(canonical_bytes)."
  [typed-value]
  (hash/fuse-bytes (encode-value typed-value)))

(defn scalar-value-hash
  "value_hash for a scalar [type data]."
  [typed-value]
  (value-hash (entry-type typed-value) (scalar-data-hash typed-value)))

;; =============================================================================
;; Size (multimethod)
;; =============================================================================

(defmulti dacite-size
  "Byte size of a stored [type data] value. Scalars define explicit
   methods; collections carry :size-bytes in their data."
  entry-type)

(defmethod dacite-size :default [[_ data]]
  (if-let [m (:measure data)]
    (:size-bytes m)
    (if-let [sb (:size-bytes data)]
      sb
      (count (.getBytes (pr-str data) "UTF-8")))))
