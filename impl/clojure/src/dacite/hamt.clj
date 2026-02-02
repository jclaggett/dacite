(ns dacite.hamt
  "Hash Array Mapped Trie (HAMT) implementation for Dacite.
   
   A persistent map data structure with:
   - O(log32 n) lookup, insert, delete
   - Structural sharing for efficient updates
   - Content-addressed nodes
   
   Dacite-specific features:
   - 32-way branching (5-bit chunks of 256-bit hash)
   - Uses MSB (most mixed bits from fuse) for navigation
   - Accumulated measure (count, size_bytes) per node
   - Any Dacite value can be a key or value"
  (:require [dacite.hash :as hash]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const BITS 5)           ;; bits per level
(def ^:const WIDTH 32)         ;; 2^5 = 32 children per node
(def ^:const MASK 0x1F)        ;; 5-bit mask (0b11111)

;; =============================================================================
;; Measure (Monoid) - same as finger tree
;; =============================================================================

(def measure-identity
  "Identity element for measure monoid."
  {:count 0 :size-bytes 0})

(defn measure-combine
  "Combine two measures."
  [m1 m2]
  {:count (+ (:count m1) (:count m2))
   :size-bytes (+ (:size-bytes m1) (:size-bytes m2))})

(defn measure-concat
  "Combine multiple measures."
  [measures]
  (reduce measure-combine measure-identity measures))

;; =============================================================================
;; Hash navigation
;; =============================================================================

(defn hash-chunk
  "Extract 5-bit chunk from hash at given level (0 = MSB).
   Hash is a vector of 4 longs [c0, c1, c2, c3] where c0 has most entropy.
   
   Level 0: bits 63-59 of c0
   Level 1: bits 58-54 of c0
   ...
   Level 12: bits 3-0 of c0 + bit 63 of c1
   ...
   Level 51: bits 4-0 of c3 (last 5 bits)"
  [hash-longs level]
  (let [bit-offset (* level BITS)           ;; 0, 5, 10, 15, ...
        long-idx (quot bit-offset 64)       ;; which long (0-3)
        bit-in-long (mod bit-offset 64)     ;; bit position within that long
        long-val (nth hash-longs long-idx 0)
        shift (- 59 bit-in-long)]
    ;; Extract 5 bits starting from MSB
    ;; For bit-in-long=0, we want bits 63-59
    ;; Shift right by (64 - 5 - bit-in-long) = (59 - bit-in-long)
    (if (>= shift 0)
      (bit-and MASK (unsigned-bit-shift-right long-val shift))
      ;; Need to span two longs
      (let [bits-from-current (+ shift 5)  ;; bits we can get from current long
            bits-from-next (- bits-from-current) ;; bits we need from next long
            current-part (bit-and (bit-shift-left 
                                   (bit-and long-val (dec (bit-shift-left 1 bits-from-current)))
                                   bits-from-next)
                                  MASK)
            next-long (nth hash-longs (inc long-idx) 0)
            next-part (unsigned-bit-shift-right next-long (- 64 bits-from-next))]
        (bit-or current-part next-part)))))

;; =============================================================================
;; Protocols
;; =============================================================================

(defprotocol HAMTNode
  "Protocol for HAMT nodes."
  (hamt-lookup [this key-hash level] "Look up value by key hash.")
  (hamt-assoc [this key-hash key-val level] "Associate key with value.")
  (hamt-dissoc [this key-hash level] "Remove key.")
  (hamt-entries [this] "Return all [key value] entries.")
  (hamt-measure [this] "Return the node's measure."))

;; =============================================================================
;; Forward declarations
;; =============================================================================

(declare ->BitmapNode ->Entry)

;; =============================================================================
;; Entry (leaf node - single key/value pair)
;; =============================================================================

(defrecord Entry [key-hash key-val cached-measure]
  ;; key-hash: [4 longs] - the hash of the key
  ;; key-val: {:key _ :value _ :key-size _ :val-size _}
  ;; cached-measure: {:count 1 :size-bytes (+ key-size val-size)}
  
  HAMTNode
  (hamt-lookup [_this lookup-hash _level]
    (when (= key-hash lookup-hash)
      (:value key-val)))
  
  (hamt-assoc [this new-hash new-kv level]
    (if (= key-hash new-hash)
      ;; Same key - replace value
      (->Entry new-hash new-kv (:cached-measure new-kv))
      ;; Hash collision at this level - need to go deeper or create collision node
      (let [my-chunk (hash-chunk key-hash level)
            new-chunk (hash-chunk new-hash level)]
        (if (= my-chunk new-chunk)
          ;; Same chunk - recurse deeper
          (let [deeper (hamt-assoc this new-hash new-kv (inc level))]
            (->BitmapNode (bit-set 0 my-chunk)
                          [deeper]
                          (hamt-measure deeper)))
          ;; Different chunks - create bitmap node with both
          (let [bitmap (bit-or (bit-set 0 my-chunk) (bit-set 0 new-chunk))
                children (if (< my-chunk new-chunk)
                           [this (->Entry new-hash new-kv (:cached-measure new-kv))]
                           [(->Entry new-hash new-kv (:cached-measure new-kv)) this])]
            (->BitmapNode bitmap
                          (vec children)
                          (measure-combine cached-measure (:cached-measure new-kv))))))))
  
  (hamt-dissoc [this lookup-hash _level]
    (when-not (= key-hash lookup-hash)
      this))
  
  (hamt-entries [_this]
    [[(:key key-val) (:value key-val)]])
  
  (hamt-measure [_this]
    cached-measure))

(defn make-entry
  "Create an entry from key and value with their hashes and sizes."
  [key key-hash key-size value _value-hash value-size]
  (let [kv {:key key :value value :key-size key-size :val-size value-size}
        measure {:count 1 :size-bytes (+ key-size value-size)}]
    (->Entry key-hash kv measure)))

;; =============================================================================
;; BitmapNode (internal node with sparse children)
;; =============================================================================

(declare empty-hamt)

(defrecord BitmapNode [bitmap children cached-measure]
  ;; bitmap: 32-bit int, bit i set means child at index i exists
  ;; children: vector of child nodes (compressed - only present children)
  ;; cached-measure: accumulated measure of all children
  
  HAMTNode
  (hamt-lookup [_this key-hash level]
    (let [chunk (hash-chunk key-hash level)
          bit (bit-set 0 chunk)]
      (when (not= 0 (bit-and bitmap bit))
        (let [idx (Long/bitCount (bit-and bitmap (dec bit)))
              child (nth children idx)]
          (hamt-lookup child key-hash (inc level))))))
  
  (hamt-assoc [_this key-hash key-val level]
    (let [chunk (hash-chunk key-hash level)
          bit (bit-set 0 chunk)
          idx (Long/bitCount (bit-and bitmap (dec bit)))]
      (if (not= 0 (bit-and bitmap bit))
        ;; Child exists at this position - recurse
        (let [child (nth children idx)
              new-child (hamt-assoc child key-hash key-val (inc level))
              new-children (assoc children idx new-child)
              measure-diff (measure-combine 
                            (hamt-measure new-child)
                            {:count (- (:count (hamt-measure child)))
                             :size-bytes (- (:size-bytes (hamt-measure child)))})]
          (->BitmapNode bitmap
                        new-children
                        (measure-combine cached-measure measure-diff)))
        ;; No child at this position - insert new entry
        (let [new-entry (->Entry key-hash key-val (:cached-measure key-val))
              new-children (vec (concat (subvec children 0 idx)
                                        [new-entry]
                                        (subvec children idx)))
              new-bitmap (bit-or bitmap bit)]
          (->BitmapNode new-bitmap
                        new-children
                        (measure-combine cached-measure (hamt-measure new-entry)))))))
  
  (hamt-dissoc [this key-hash level]
    (let [chunk (hash-chunk key-hash level)
          bit (bit-set 0 chunk)]
      (if (= 0 (bit-and bitmap bit))
        ;; Not present
        this
        (let [idx (Long/bitCount (bit-and bitmap (dec bit)))
              child (nth children idx)
              new-child (hamt-dissoc child key-hash (inc level))]
          (if (nil? new-child)
            ;; Child removed entirely
            (let [new-bitmap (bit-and-not bitmap bit)
                  new-children (vec (concat (subvec children 0 idx)
                                            (subvec children (inc idx))))]
              (cond
                (= 0 new-bitmap) nil  ;; Node now empty
                (and (= 1 (Long/bitCount new-bitmap))
                     (instance? Entry (first new-children)))
                (first new-children)  ;; Collapse to single entry
                :else
                (->BitmapNode new-bitmap
                              new-children
                              (measure-combine cached-measure
                                               {:count (- (:count (hamt-measure child)))
                                                :size-bytes (- (:size-bytes (hamt-measure child)))}))))
            ;; Child still exists but may have changed
            (let [new-children (assoc children idx new-child)]
              (->BitmapNode bitmap
                            new-children
                            (measure-combine cached-measure
                                             (measure-combine 
                                              (hamt-measure new-child)
                                              {:count (- (:count (hamt-measure child)))
                                               :size-bytes (- (:size-bytes (hamt-measure child)))})))))))))
  
  (hamt-entries [_this]
    (mapcat hamt-entries children))
  
  (hamt-measure [_this]
    cached-measure))

;; =============================================================================
;; Empty HAMT
;; =============================================================================

(defrecord EmptyHAMT []
  HAMTNode
  (hamt-lookup [_ _ _] nil)
  
  (hamt-assoc [_ key-hash key-val _level]
    (->Entry key-hash key-val (:cached-measure key-val)))
  
  (hamt-dissoc [this _ _] this)
  
  (hamt-entries [_] [])
  
  (hamt-measure [_] measure-identity))

(def empty-hamt (->EmptyHAMT))

;; =============================================================================
;; Public API
;; =============================================================================

(defn hamt
  "Create an empty HAMT."
  []
  empty-hamt)

(defn lookup
  "Look up a value by key. Returns nil if not found."
  [m _key key-hash]
  (hamt-lookup m key-hash 0))

(defn insert
  "Insert a key-value pair. Returns new HAMT."
  [m key key-hash key-size value _value-hash value-size]
  (let [kv {:key key :value value :key-size key-size :val-size value-size
            :cached-measure {:count 1 :size-bytes (+ key-size value-size)}}]
    (hamt-assoc m key-hash kv 0)))

(defn delete
  "Remove a key. Returns new HAMT (or nil if empty)."
  [m key-hash]
  (or (hamt-dissoc m key-hash 0) empty-hamt))

(defn entries
  "Return all [key value] pairs."
  [m]
  (hamt-entries m))

(defn hamt-count
  "Return the number of entries (O(1) via measure)."
  [m]
  (:count (hamt-measure m)))

(defn hamt-size-bytes
  "Return total size in bytes (O(1) via measure)."
  [m]
  (:size-bytes (hamt-measure m)))

;; =============================================================================
;; Convenience functions with automatic hashing
;; =============================================================================

(defn- compute-hash
  "Compute hash for a value (placeholder - needs proper type dispatch)."
  [value]
  (let [bytes (cond
                (string? value) (.getBytes ^String value "UTF-8")
                (number? value) (.getBytes (str value) "UTF-8")
                :else (.getBytes (pr-str value) "UTF-8"))]
    (hash/bytes->longs (hash/sha256 bytes))))

(defn- compute-size
  "Compute size in bytes for a value."
  [value]
  (cond
    (string? value) (count (.getBytes ^String value "UTF-8"))
    (number? value) 8
    :else (count (.getBytes (pr-str value) "UTF-8"))))

(defn assoc-val
  "Associate key with value, computing hashes automatically."
  [m key value]
  (let [key-hash (compute-hash key)
        key-size (compute-size key)
        value-hash (compute-hash value)
        value-size (compute-size value)]
    (insert m key key-hash key-size value value-hash value-size)))

(defn get-val
  "Get value for key, computing hash automatically."
  [m key]
  (lookup m key (compute-hash key)))

(defn dissoc-val
  "Remove key, computing hash automatically."
  [m key]
  (delete m (compute-hash key)))

(comment
  ;; Example usage
  (def m (-> (hamt)
             (assoc-val "name" "Alice")
             (assoc-val "age" 30)
             (assoc-val "city" "Boston")))
  
  (get-val m "name")     ;; => "Alice"
  (get-val m "age")      ;; => 30
  (hamt-count m)         ;; => 3
  
  (entries m)
  ;; => [["name" "Alice"] ["age" 30] ["city" "Boston"]]
  
  (def m2 (dissoc-val m "age"))
  (hamt-count m2)        ;; => 2
  (get-val m2 "age")     ;; => nil
  )
