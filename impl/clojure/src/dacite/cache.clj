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
   - forget operation for cache eviction
   
   Type definitions live in dacite.types"
  (:require [dacite.hash :as hash]))

;; =============================================================================
;; Cache Manager Protocol
;; =============================================================================

(defprotocol CacheManager
  "Protocol for managing cached Dacite values."

  (commit! [this value]
    "Commit a typed value to the cache.
     
     Arguments:
       value - a typed Dacite value as [type-kw, data]
     
     Returns:
       The value's hash (vector of 4 longs).
     
     Computes the hash using typed-value-hash and stores the value.")

  (store! [this hash value]
    "Store a value at a specific hash.
     
     Arguments:
       hash  - vector of 4 longs (256 bits), pre-computed by caller
       value - the value to store
     
     Used by data structures that compute their own hashes
     (e.g., internal tree nodes using node-hash).")

  (lookup [this hash]
    "Look up a value by its hash.
     
     Arguments:
       hash - vector of 4 longs (256 bits)
     
     Returns:
       The value if found, nil otherwise."))

;; =============================================================================
;; In-Memory Cache Manager
;; =============================================================================

(defrecord MemoryCacheManager [cache]
  ;; cache is an atom containing {hash-vec -> [type, data]}

  CacheManager
  (commit! [_ value]
    (let [h (hash/typed-value-hash value)]
      (swap! cache assoc h value)
      h))

  (store! [_ hash value]
    (swap! cache assoc hash value)
    hash)

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

  ;; Commit some scalar values
  (def h1 (commit! mgr ["i64" 42]))
  (def h2 (commit! mgr ["string" "hello"]))
  (def h3 (commit! mgr ["bool" true]))

  ;; Look up by hash
  (lookup mgr h1)  ;; => ["i64" 42]
  (lookup mgr h2)  ;; => ["string" "hello"]

  ;; Same value = same hash (content-addressed)
  (= h1 (commit! mgr ["i64" 42]))  ;; => true

  ;; Different value = different hash
  (= h1 (commit! mgr ["i64" 43]))  ;; => false

  ;; Hash is printable
  (hash/hash->hex h1)  ;; => "abcd1234..."

  ;; Stats
  (stats mgr)  ;; => {:count 3, :hashes [...]}

  ;; Using global cache
  (init-global-cache!)
  (def g1 (global-commit! ["f64" 3.14]))
  (global-lookup g1)  ;; => ["f64" 3.14]

  ;; Nested structures work too (for now - will be refined)
  (def h-nested (commit! mgr ["vector" [1 2 3]]))
  (lookup mgr h-nested)  ;; => ["vector" [1 2 3]]
  )
