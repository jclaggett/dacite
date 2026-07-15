(ns dacite.value.hamt
  "Store-aware Hash Array Mapped Trie (HAMT) for the value layer.

   Like the value finger tree, every operation reads and writes nodes
   directly through the value's IStore via s-get / s-put rather than
   threading a pure map. The store is mutated in place, so operations
   return only the new root hash.

   32-way branching consumes the key's hash 5 bits at a time, from the
   most-mixed word (c0) down. Each node caches a measure for O(1) count,
   size, and data hash at the root.

   Node types (stored as [type-name data]):
   - [\"hamt/empty\"  {:measure m}]
   - [\"hamt/entry\"  {:key-hash h :key-ref h :val-ref h :measure m}]
   - [\"hamt/bitmap\" {:bitmap n :children [h...] :measure m}]

   Bitmap nodes are the one exception to shape-independence (§3.7): their
   bitmap value is folded into the hash, because two bitmaps with the same
   elements but different routing are NOT interchangeable.

   The bitmap and 64-bit hash-word navigation go through dacite.host so the
   arithmetic is identical on JVM (native long) and JS (BigInt)."
  (:require [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.store :as store]
            [dacite.value.types :as types]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const BITS 5)
(def ^:private MASK-WORD (host/word 0x1F))
(def ^:private ONE-WORD (host/word 1))

;; =============================================================================
;; Measure (monoid)
;; =============================================================================

(def measure-identity
  {:count 0 :size-bytes 0 :elements-fuse host/zero-hash})

(defn- measure-combine [m1 m2]
  {:count (+ (:count m1) (:count m2))
   :size-bytes (+ (:size-bytes m1) (:size-bytes m2))
   :elements-fuse (hash/unchecked-fuse (:elements-fuse m1) (:elements-fuse m2))})

(defn- measure-seq [measures]
  (reduce measure-combine measure-identity measures))

;; =============================================================================
;; Hash navigation
;; =============================================================================

(defn hash-chunk
  "Extract the 5-bit chunk of a hash at the given level (0 = most
   significant), as a native int 0..31. Levels span the 256-bit hash from
   c0 down to c3."
  [hash-longs level]
  (let [bit-offset (* level BITS)
        long-idx (quot bit-offset 64)
        bit-in-long (mod bit-offset 64)
        long-val (nth hash-longs long-idx host/zero-word)
        shift (- 59 bit-in-long)]
    (if (>= shift 0)
      (host/word->int (host/band64 MASK-WORD (host/ushr64 long-val shift)))
      (let [bits-from-current (+ shift 5)
            bits-from-next (- 5 bits-from-current)
            current-part (host/shl64
                          (host/band64 long-val
                                       (host/sub64 (host/shl64 ONE-WORD bits-from-current)
                                                   ONE-WORD))
                          bits-from-next)
            next-long (nth hash-longs (inc long-idx) host/zero-word)
            next-part (host/ushr64 next-long (- 64 bits-from-next))]
        (host/word->int (host/band64 MASK-WORD (host/bor64 current-part next-part)))))))

(defn- chunk-bit
  "The single-bit word for a chunk index (0..31)."
  [chunk]
  (host/shl64 ONE-WORD chunk))

(defn- index-of
  "Sparse index of a chunk in a bitmap: popcount of bits below `bit`."
  [bitmap bit]
  (host/popcount (host/band64 bitmap (host/sub64 bit ONE-WORD))))

;; =============================================================================
;; Node helpers (store-backed)
;; =============================================================================

(defn- add-node!
  "Persist a node, returning its content hash. Bitmap nodes fold their
   bitmap into the hash (the §3.7 routing exception); all other nodes use
   the plain fuse(type_hash, elements_fuse) rule."
  [store node]
  (let [type-name (first node)
        data (second node)
        ef (:elements-fuse (:measure data))
        h (if (= "hamt/bitmap" type-name)
            (hash/unchecked-fuse (types/node-hash type-name ef)
                                 (hash/fuse-bytes (host/int->bytes-be (:bitmap data) 8)))
            (types/node-hash type-name ef))]
    (store/s-put store h node)
    h))

(defn- lookup [store h] (store/s-get store h))
(defn- node-type [node] (first node))
(defn- node-data [node] (second node))
(defn- get-measure [store h] (:measure (node-data (lookup store h))))

;; =============================================================================
;; Node constructors
;; =============================================================================

(defn- make-empty! [store]
  (add-node! store ["hamt/empty" {:measure measure-identity}]))

(defn- make-entry! [store key-hash key-ref val-ref measure]
  (add-node! store ["hamt/entry" {:key-hash key-hash
                                  :key-ref key-ref
                                  :val-ref val-ref
                                  :measure measure}]))

(defn- make-bitmap! [store bitmap children measure]
  (add-node! store ["hamt/bitmap" {:bitmap bitmap
                                   :children (vec children)
                                   :measure measure}]))

;; =============================================================================
;; Internal operations
;; =============================================================================

(declare hamt-assoc* hamt-dissoc*)

(defn- hamt-lookup* [store node-hash key-hash level]
  (let [node (lookup store node-hash)]
    (case (node-type node)
      "hamt/empty" nil
      "hamt/entry"
      (let [{ekh :key-hash evr :val-ref} (node-data node)]
        (when (= ekh key-hash) evr))
      "hamt/bitmap"
      (let [{:keys [bitmap children]} (node-data node)
            chunk (hash-chunk key-hash level)
            bit (chunk-bit chunk)]
        (when-not (host/word-zero? (host/band64 bitmap bit))
          (let [idx (index-of bitmap bit)]
            (hamt-lookup* store (nth children idx) key-hash (inc level))))))))

(defn- hamt-assoc* [store node-hash key-hash key-ref val-ref measure level]
  (let [node (lookup store node-hash)]
    (case (node-type node)
      "hamt/empty"
      (make-entry! store key-hash key-ref val-ref measure)

      "hamt/entry"
      (let [existing-key-hash (:key-hash (node-data node))]
        (if (= existing-key-hash key-hash)
          (make-entry! store key-hash key-ref val-ref measure)
          (let [my-chunk (hash-chunk existing-key-hash level)
                new-chunk (hash-chunk key-hash level)]
            (if (= my-chunk new-chunk)
              (let [deeper (hamt-assoc* store node-hash key-hash
                                        key-ref val-ref measure (inc level))]
                (make-bitmap! store (chunk-bit my-chunk) [deeper]
                              (get-measure store deeper)))
              (let [new-entry (make-entry! store key-hash key-ref val-ref measure)
                    bitmap (host/bor64 (chunk-bit my-chunk) (chunk-bit new-chunk))
                    children (if (< my-chunk new-chunk)
                               [node-hash new-entry]
                               [new-entry node-hash])]
                (make-bitmap! store bitmap children
                              (measure-seq (mapv #(get-measure store %) children))))))))

      "hamt/bitmap"
      (let [{:keys [bitmap children]} (node-data node)
            chunk (hash-chunk key-hash level)
            bit (chunk-bit chunk)
            idx (index-of bitmap bit)]
        (if-not (host/word-zero? (host/band64 bitmap bit))
          (let [new-child (hamt-assoc* store (nth children idx) key-hash
                                       key-ref val-ref measure (inc level))
                new-children (assoc children idx new-child)]
            (make-bitmap! store bitmap new-children
                          (measure-seq (mapv #(get-measure store %) new-children))))
          (let [new-entry (make-entry! store key-hash key-ref val-ref measure)
                new-children (vec (concat (subvec children 0 idx)
                                          [new-entry]
                                          (subvec children idx)))]
            (make-bitmap! store (host/bor64 bitmap bit) new-children
                          (measure-seq (mapv #(get-measure store %) new-children)))))))))

(defn- hamt-dissoc*
  "Remove a key. Returns the new node hash, or nil if the node becomes
   empty."
  [store node-hash key-hash level]
  (let [node (lookup store node-hash)]
    (case (node-type node)
      "hamt/empty" nil
      "hamt/entry"
      (if (= key-hash (:key-hash (node-data node))) nil node-hash)
      "hamt/bitmap"
      (let [{:keys [bitmap children]} (node-data node)
            chunk (hash-chunk key-hash level)
            bit (chunk-bit chunk)]
        (if (host/word-zero? (host/band64 bitmap bit))
          node-hash
          (let [idx (index-of bitmap bit)
                new-child (hamt-dissoc* store (nth children idx) key-hash (inc level))]
            (if (nil? new-child)
              (let [new-bitmap (host/band64 bitmap (host/bnot64 bit))
                    new-children (vec (concat (subvec children 0 idx)
                                              (subvec children (inc idx))))]
                (cond
                  (host/word-zero? new-bitmap) nil
                  (and (= 1 (host/popcount new-bitmap))
                       (= "hamt/entry" (node-type (lookup store (first new-children)))))
                  (first new-children)
                  :else
                  (make-bitmap! store new-bitmap new-children
                                (measure-seq (mapv #(get-measure store %) new-children)))))
              (let [new-children (assoc children idx new-child)]
                (make-bitmap! store bitmap new-children
                              (measure-seq (mapv #(get-measure store %) new-children)))))))))))

(defn- hamt-entries* [store node-hash]
  (let [node (lookup store node-hash)]
    (case (node-type node)
      "hamt/empty" []
      "hamt/entry" (let [{:keys [key-ref val-ref]} (node-data node)]
                     [[key-ref val-ref]])
      "hamt/bitmap" (mapcat #(hamt-entries* store %)
                            (:children (node-data node))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn hamt-empty
  "Create an empty HAMT in the store. Returns its root hash."
  [store]
  (make-empty! store))

(defn hamt-assoc
  "Associate key-ref/val-ref under key-hash (the key's content hash, used
   for navigation). Both refs must already be in the store. Returns the
   new root hash."
  [store root key-hash key-ref val-ref]
  (let [key-size (types/dacite-size (lookup store key-ref))
        val-size (types/dacite-size (lookup store val-ref))
        measure {:count 1
                 :size-bytes (+ key-size val-size)
                 :elements-fuse (hash/unchecked-fuse key-ref val-ref)}]
    (hamt-assoc* store root key-hash key-ref val-ref measure 0)))

(defn hamt-get
  "Look up a value ref by key hash. Returns the val-ref or nil."
  [store root key-hash]
  (hamt-lookup* store root key-hash 0))

(defn hamt-dissoc
  "Remove a key by key hash. Returns the new root hash (an empty node if
   the map becomes empty)."
  [store root key-hash]
  (let [new-root (hamt-dissoc* store root key-hash 0)]
    (if (nil? new-root)
      (make-empty! store)
      new-root)))

(defn hamt-entries
  "All [key-ref val-ref] pairs, in ascending key-hash traversal order."
  [store root]
  (hamt-entries* store root))

(defn hamt-measure [store root]
  (get-measure store root))

(defn hamt-count
  "Entry count, O(1) via cached measure."
  [store root]
  (:count (get-measure store root)))

(defn hamt-size-bytes
  "Total key+value byte size, O(1) via cached measure."
  [store root]
  (:size-bytes (get-measure store root)))

(defn hamt-elements-fuse
  "The map's data hash: the fuse of all entry fuses, O(1)."
  [store root]
  (:elements-fuse (get-measure store root)))

;; =============================================================================
;; child-hashes implementations for HAMT node types
;; =============================================================================

(defmethod types/child-hashes "hamt/empty" [_] [])

(defmethod types/child-hashes "hamt/entry" [[_ data]]
  [(:key-ref data) (:val-ref data)])

(defmethod types/child-hashes "hamt/bitmap" [[_ data]]
  (:children data))
