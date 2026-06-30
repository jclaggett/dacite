(ns dacite.value.types
  "Pure type and hashing rules for the value layer (Chapter 3).

   Every value — user-facing or internal — is hashed by one rule:

       value_hash = fuse(type_hash, data_hash)

   where the type hash is the fuse of the type name followed by a
   terminating 0x00 byte:

       type_hash = fuse_bytes(type_name ++ 0x00)

   Type names never contain a null byte, so the trailing 0x00 cleanly
   separates the type from the data that follows (fuse composes over
   concatenation, so a boundary marker is required).

   This namespace is store-free: it knows nothing about live store I/O.
   Here we define the hashing algebra, the value protocol, per-type
   encoding/size multimethods, and the wrap-entry / coerce-and-store!
   dispatch tables (extended in scalar and collections). Store-aware
   entry points live in dacite.value."
  (:require [dacite.hash :as hash]))

;; =============================================================================
;; Value protocol
;; =============================================================================

(defprotocol IDaciteValue
  "A store-aware, content-addressed Dacite value.

   Dacite values are immutable values, not references, so they do not
   implement IDeref. Converting to host-language values is an explicit
   call (`realize`)."
  (dacite-hash [this] "The value's 4-long content hash.")
  (dacite-store [this] "The store that created and persists this value.")
  (dacite-type [this] "The value's type name string (e.g. \"i64\", \"vector\").")
  (realize [this]
    "Expose this value's content in the host language. A scalar yields its
     native value (the `\"negative\"` sentinel yields `:dacite/negative`,
     distinct from `null`). A collection yields a lazy iterable of realized
     elements (each element is itself realized, so sub-collections become
     nested lazy iterables); a map yields a lazy iterable of [k v] pairs with
     both key and value realized. Empty collections yield nil (no elements).
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

;; Backward-compatible alias for callers that name the scalar value hash
;; `typed-value-hash` (e.g. hashing tests).
(def typed-value-hash scalar-value-hash)

;; =============================================================================
;; Child hash extraction (multimethod)
;; =============================================================================

(defmulti child-hashes
  "Ordered child hash references a stored node directly points to.

   Dispatches on the entry's type name. Both sides of a protocol can
   compute the same sequence independently, so it drives proof-chain and
   transition walks (Chapter 4). Returns nil for nil input and [] for
   types with no children.

   Scalar types have no children (the :default). Collection types
   (vector/string/blob/map/set) point at their tree :root. Internal
   finger-tree and HAMT node types register their own methods in
   dacite.value.finger-tree and dacite.value.hamt."
  (fn [node]
    (when (and (vector? node) (= 2 (count node)))
      (first node))))

(defmethod child-hashes nil [_] nil)

(defmethod child-hashes :default [_] [])

(doseq [t ["vector" "string" "blob" "map" "set"]]
  (defmethod child-hashes t [[_ data]]
    [(:root data)]))

;; =============================================================================
;; Wrapping & coercion (dispatch tables)
;; =============================================================================

(defmulti wrap-entry
  "Wrap a raw hash as the appropriate Dacite type, dispatching on the
   stored entry's type name. Scalar types use the :default method
   (registered in dacite.value.scalar); collection types register in
   dacite.value.collections. Callers pass type-name after reading the
   store entry; dacite.value/wrap-hash performs that lookup."
  (fn [type-name _store _h] type-name))

(defmulti coerce-and-store!
  "Coerce a plain Clojure value into the store, returning its hash.
   Scalar coercions register in dacite.value.scalar; collections in
   dacite.value.collections."
  (fn [_store x]
    (cond
      (nil? x) :null
      (instance? Boolean x) :bool
      (char? x) :char
      (integer? x) :i64
      (float? x) :f64
      (double? x) :double
      (string? x) :string
      (vector? x) :vector
      (set? x) :set
      (map? x) :map
      (bytes? x) :blob
      (sequential? x) :sequential
      :else :unsupported)))

(defmethod coerce-and-store! :unsupported [_ x]
  (throw (ex-info "Cannot coerce to dacite value" {:value x :type (type x)})))

(defn extract-hash
  "Hash of a Dacite value, or coerce-and-store a plain Clojure value."
  [store x]
  (if (satisfies? IDaciteValue x)
    (dacite-hash x)
    (coerce-and-store! store x)))
