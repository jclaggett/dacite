(ns dacite.value2
  "Dacite values as store-local, content-addressed objects.

   A DaciteValue is a lightweight wrapper around a store + hash pair.
   It caches the type string and data payload to avoid repeated store
   fetches for metadata.

   Core design:
   - store  : the IStore where the value's data lives
   - hash   : content-addressed pointer to a typed value [type-hash data-hash]
   - type   : cached type string (e.g. i64, vector)
   - data   : cached type-specific payload (decoded from raw bytes)

   All values are immutable. Operations return new DaciteValues.

   Constructors are pure: they take a store and return [store' hash].
   A convenience layer (with-store / current-store) sits on top."
  (:require [dacite.store :as store]
            [dacite.value2.primitive :as prim]
            [dacite.value2.scalar :as scalar]))

;; =============================================================================
;; DaciteValue record
;; =============================================================================

(defrecord DaciteValue [store hash type data]
  ;; store : IStore implementation
  ;; hash  : 4-long vector (content-addressed hash = fuse(type-hash, data-hash))
  ;; type  : string (e.g. "i64", "vector", "map")
  ;; data  : decoded payload (e.g. 42, "hello", {:root h, :count n})
  )

;; =============================================================================
;; Protocols
;; =============================================================================

(defprotocol IDaciteValue
  "Protocol for Dacite value operations."
  (value-store [this] "Return the store associated with this value.")
  (value-hash [this] "Return the hash of this value.")
  (value-type [this] "Return the type string of this value.")
  (value-data [this] "Return the cached data payload of this value."))

(extend-protocol IDaciteValue
  DaciteValue
  (value-store [this] (:store this))
  (value-hash [this] (:hash this))
  (value-type [this] (:type this))
  (value-data [this] (:data this)))

;; =============================================================================
;; Construction
;; =============================================================================

(defn- fetch-by-hash
  "Fetch a typed value [type-hash data-hash] from a store by hash.
   Returns [type-hash data-hash] or nil if not found."
  [store h]
  (when-let [entry (store/s-get store h)]
    (when (and (vector? entry) (= 2 (count entry)))
      entry)))

(defn- decode-scalar-data
  "Decode raw bytes to a Clojure value based on type string.
   Returns the decoded value or nil for unsupported types."
  [type-str raw-bytes]
  (case type-str
    "null" nil
    "bool" (= 1 (aget raw-bytes 0))
    "i8" (aget raw-bytes 0)
    "i16" (let [buf (java.nio.ByteBuffer/wrap raw-bytes)]
            (.getShort buf))
    "i32" (let [buf (java.nio.ByteBuffer/wrap raw-bytes)]
            (.getInt buf))
    "i64" (let [buf (java.nio.ByteBuffer/wrap raw-bytes)]
            (.getLong buf))
    "u8" (bit-and (aget raw-bytes 0) 0xFF)
    "u16" (let [buf (java.nio.ByteBuffer/wrap raw-bytes)]
            (bit-and (.getShort buf) 0xFFFF))
    "u32" (let [buf (java.nio.ByteBuffer/wrap raw-bytes)]
            (bit-and (.getInt buf) 0xFFFFFFFF))
    "u64" (let [buf (java.nio.ByteBuffer/wrap raw-bytes)
                val (.getLong buf)]
            (if (neg? val)
              (+ val 18446744073709551616N)
              val))
    "u256" raw-bytes
    "f32" (let [buf (java.nio.ByteBuffer/wrap raw-bytes)]
            (.getFloat buf))
    "f64" (let [buf (java.nio.ByteBuffer/wrap raw-bytes)]
            (.getDouble buf))
    "char" (let [s (String. raw-bytes "UTF-8")]
             (if (= 1 (count s))
               (first s)
               s))
    "negative" nil
    ;; Default: return raw bytes for unknown types
    raw-bytes))

(defn make-value
  "Create a DaciteValue from a store and hash.
   Fetches and decodes the type and data from the store.
   Returns nil if hash not found or data incomplete."
  [store h]
  (when-let [[type-hash data-hash] (fetch-by-hash store h)]
    (when-let [type-bytes (prim/fetch-raw store type-hash)]
      (when-let [data-bytes (prim/fetch-raw store data-hash)]
        (let [type-str (String. ^bytes type-bytes "UTF-8")
              decoded-data (decode-scalar-data type-str data-bytes)]
          (->DaciteValue store h type-str decoded-data))))))

;; =============================================================================
;; Value predicates
;; =============================================================================

(defn scalar?
  "Check if a DaciteValue is a scalar type."
  [v]
  (and (instance? DaciteValue v)
       (contains? #{"null" "bool" "i8" "i16" "i32" "i64"
                    "u8" "u16" "u32" "u64" "u256"
                    "f32" "f64" "char" "negative"}
                  (value-type v))))

(defn collection?
  "Check if a DaciteValue is a collection type."
  [v]
  (and (instance? DaciteValue v)
       (contains? #{"vector" "string" "blob" "map" "set"}
                  (value-type v))))

;; =============================================================================
;; Convenience layer (store binding)
;; =============================================================================

(def ^:dynamic *current-store*
  "Dynamic var holding the current store for convenience constructors.
   Defaults to a fresh mem-store."
  (store/mem-store))

(defmacro with-store
  "Execute body with *current-store* bound to the given store.
   Use this for isolated store contexts."
  [store & body]
  `(binding [*current-store* ~store]
     ~@body))

;; =============================================================================
;; Convenience constructors (use *current-store*)
;; =============================================================================

(defn c-null [] (make-value *current-store* (second (scalar/null *current-store*))))
(defn c-bool [b] (make-value *current-store* (second (scalar/bool *current-store* b))))
(defn c-i8 [n] (make-value *current-store* (second (scalar/i8 *current-store* n))))
(defn c-i16 [n] (make-value *current-store* (second (scalar/i16 *current-store* n))))
(defn c-i32 [n] (make-value *current-store* (second (scalar/i32 *current-store* n))))
(defn c-i64 [n] (make-value *current-store* (second (scalar/i64 *current-store* n))))
(defn c-u8 [n] (make-value *current-store* (second (scalar/u8 *current-store* n))))
(defn c-u16 [n] (make-value *current-store* (second (scalar/u16 *current-store* n))))
(defn c-u32 [n] (make-value *current-store* (second (scalar/u32 *current-store* n))))
(defn c-u64 [n] (make-value *current-store* (second (scalar/u64 *current-store* n))))
(defn c-u256 [data] (make-value *current-store* (second (scalar/u256 *current-store* data))))
(defn c-f32 [n] (make-value *current-store* (second (scalar/f32 *current-store* n))))
(defn c-f64 [n] (make-value *current-store* (second (scalar/f64 *current-store* n))))
(defn c-char [c] (make-value *current-store* (second (scalar/dacite-char *current-store* c))))
(defn c-neg [] (make-value *current-store* (second (scalar/neg *current-store*))))

;; =============================================================================
;; REPL examples
;; =============================================================================

(comment
  ;; Pure constructor
  (let [store (store/mem-store)
        [store' h] (scalar/i64 store 42)
        v (make-value store' h)]
    (value-type v)   ;; => "i64"
    (value-data v)) ;; => 42

  ;; Convenience constructor
  (with-store (store/mem-store)
    (let [v (c-i64 42)]
      (value-type v))) ;; => "i64"

  ;; Check if scalar
  (with-store (store/mem-store)
    (let [v (c-i64 42)]
      (scalar? v))) ;; => true
  )
