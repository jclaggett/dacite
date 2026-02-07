(ns dacite.cache
  "Cache manager for Dacite values.
   
   Core concepts:
   - Dacite values are [type, data] tuples
   - Each value has a content-addressable hash (256-bit)
   - The hash is an address into a vast sparse virtual space
   - The cache manager maintains the mapping from hash → value
   
   The cache manager guarantees:
   - Every committed value is cached at least in memory
   - Values can be retrieved by their hash
   
   This is similar to Git's object model:
   - commit! ≈ git add + git commit (creates object, returns SHA)
   - lookup ≈ git cat-file (retrieves object by SHA)
   
   Future extensions will add:
   - Multiple storage backends (disk, remote KV, etc.)
   - Policy-driven persistence (write-through, lazy sync, etc.)
   - forget operation for cache eviction"
  (:require [dacite.hash :as hash]))

;; =============================================================================
;; Re-export hash utilities for convenience
;; =============================================================================

(def hash->hex
  "Convert hash (4 longs or 32 bytes) to 64-char hex string."
  hash/hash->hex)

(def hex->hash
  "Convert 64-char hex string to hash (vector of 4 longs)."
  hash/hex->hash)

;; =============================================================================
;; Value encoding
;; =============================================================================

(defn encode-value
  "Encode a Clojure value to bytes for hashing.
   Currently uses pr-str + UTF-8, but could be made more efficient."
  ^bytes [value]
  (.getBytes (pr-str value) "UTF-8"))

(defn compute-hash
  "Compute the hash for a Dacite value [type, data].
   
   The hash is: fuse(sha256(type-name), sha256(data-bytes))
   
   Returns a vector of 4 longs (256 bits)."
  [[type-kw data]]
  (let [type-name (if (keyword? type-kw)
                    (str "dacite.core/" (name type-kw))
                    (str type-kw))
        type-hash (hash/sha256-str type-name)
        data-bytes (encode-value data)
        data-hash (hash/sha256 data-bytes)
        value-hash (hash/fuse type-hash data-hash)]
    (hash/bytes->longs value-hash)))

;; =============================================================================
;; Cache Manager Protocol
;; =============================================================================

(defprotocol CacheManager
  "Protocol for managing cached Dacite values."

  (commit! [this value]
    "Commit a value to the cache.
     
     Arguments:
       value - a Dacite value as [type, data]
               type is a keyword (e.g., :i64, :string)
               data is the value's data
     
     Returns:
       The value's hash (vector of 4 longs).
     
     Guarantees:
       - The value will be cached at least in memory.
       - The same value always produces the same hash.
       - After commit!, (lookup hash) will return the value.")

  (lookup [this hash]
    "Look up a value by its hash.
     
     Arguments:
       hash - vector of 4 longs (256 bits)
     
     Returns:
       The value [type, data] if found, nil otherwise."))

;; =============================================================================
;; In-Memory Cache Manager
;; =============================================================================

(defrecord MemoryCacheManager [cache]
  ;; cache is an atom containing {hash-vec -> [type, data]}

  CacheManager
  (commit! [_ value]
    (let [h (compute-hash value)]
      (swap! cache assoc h value)
      h))

  (lookup [_ hash]
    (get @cache hash)))

(defn memory-cache-manager
  "Create an in-memory cache manager.
   
   All values committed will be stored in memory.
   Values persist for the lifetime of the JVM (or until explicitly forgotten)."
  []
  (->MemoryCacheManager (atom {})))

;; =============================================================================
;; Convenience functions
;; =============================================================================

(defn stats
  "Get statistics about a cache manager."
  [manager]
  (when (instance? MemoryCacheManager manager)
    (let [cache @(:cache manager)]
      {:count (count cache)
       :hashes (keys cache)})))

(defn clear!
  "Clear all values from a cache manager (use with caution).
   Uses empty to preserve any metadata on the underlying collection."
  [manager]
  (when (instance? MemoryCacheManager manager)
    (swap! (:cache manager) empty)))

;; =============================================================================
;; Value size computation
;; =============================================================================

(defn value-size
  "Get the size in bytes of a committed value.
   
   For collections with :measure, returns (:size-bytes measure).
   For primitives, computes based on type.
   
   This is a simple case-based implementation; will evolve to multimethod
   for open type system."
  [cache hash]
  (when-let [[type-kw data] (lookup cache hash)]
    (cond
      ;; Collections with measure (finger tree nodes, etc.)
      (:measure data)
      (:size-bytes (:measure data))

      ;; Primitive types
      :else
      (case type-kw
        ;; Fixed-size numerics
        :i8 1
        :u8 1
        :i16 2
        :u16 2
        :i32 4
        :u32 4
        :i64 8
        :u64 8
        :f32 4
        :f64 8

        ;; Boolean
        :bool 1

        ;; Variable-size types
        :string (count (.getBytes ^String data "UTF-8"))
        :bytes (count data)

        ;; Default: serialize and measure
        (count (encode-value data))))))

;; =============================================================================
;; Global cache (optional convenience)
;; =============================================================================

(defonce ^:private global-cache (atom nil))

(defn init-global-cache!
  "Initialize the global cache manager.
   Call this once at application startup."
  []
  (reset! global-cache (memory-cache-manager)))

(defn global-commit!
  "Commit a value to the global cache."
  [value]
  (when-let [mgr @global-cache]
    (commit! mgr value)))

(defn global-lookup
  "Look up a value in the global cache."
  [hash]
  (when-let [mgr @global-cache]
    (lookup mgr hash)))

;; =============================================================================
;; REPL examples
;; =============================================================================

(comment
  ;; Create a cache manager
  (def mgr (memory-cache-manager))

  ;; Commit some leaf values
  (def h1 (commit! mgr [:i64 42]))
  (def h2 (commit! mgr [:string "hello"]))
  (def h3 (commit! mgr [:bool true]))

  ;; Look up by hash
  (lookup mgr h1)  ;; => [:i64 42]
  (lookup mgr h2)  ;; => [:string "hello"]

  ;; Same value = same hash (content-addressed)
  (= h1 (commit! mgr [:i64 42]))  ;; => true

  ;; Different value = different hash
  (= h1 (commit! mgr [:i64 43]))  ;; => false

  ;; Hash is printable
  (hash->hex h1)  ;; => "abcd1234..."

  ;; Stats
  (stats mgr)  ;; => {:count 3, :hashes [...]}

  ;; Using global cache
  (init-global-cache!)
  (def g1 (global-commit! [:f64 3.14]))
  (global-lookup g1)  ;; => [:f64 3.14]

  ;; Nested structures work too (for now - will be refined)
  (def h-nested (commit! mgr [:vector [1 2 3]]))
  (lookup mgr h-nested)  ;; => [:vector [1 2 3]]
  )
