(ns dacite.value2.hamt
  "Store-aware Hash Array Mapped Trie (HAMT) for the value2 layer.

   Like the value2 finger tree, every operation reads and writes nodes
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
   elements but different routing are NOT interchangeable."
  (:require [dacite.hash :as hash]
            [dacite.store :as store]
            [dacite.value2.types :as types])
  (:import [java.nio ByteBuffer]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const BITS 5)
(def ^:const MASK 0x1F)

;; =============================================================================
;; Measure (monoid)
;; =============================================================================

(def measure-identity
  {:count 0 :size-bytes 0 :elements-fuse [0 0 0 0]})

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
   significant). Levels span the 256-bit hash from c0 down to c3."
  [hash-longs level]
  (let [bit-offset (* level BITS)
        long-idx (quot bit-offset 64)
        bit-in-long (mod bit-offset 64)
        long-val (nth hash-longs long-idx 0)
        shift (- 59 bit-in-long)]
    (if (>= shift 0)
      (bit-and MASK (unsigned-bit-shift-right long-val shift))
      (let [bits-from-current (+ shift 5)
            bits-from-next (- 5 bits-from-current)
            current-part (bit-shift-left
                          (bit-and long-val (dec (bit-shift-left 1 bits-from-current)))
                          bits-from-next)
            next-long (nth hash-longs (inc long-idx) 0)
            next-part (unsigned-bit-shift-right next-long (- 64 bits-from-next))]
        (bit-and MASK (bit-or current-part next-part))))))

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
            (let [bitmap-bytes (.array (doto (ByteBuffer/allocate 8)
                                         (.putLong (long (:bitmap data)))))]
              (hash/unchecked-fuse (types/node-hash type-name ef)
                                   (hash/fuse-bytes bitmap-bytes)))
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
            bit (bit-set 0 chunk)]
        (when (not= 0 (bit-and bitmap bit))
          (let [idx (Long/bitCount (bit-and bitmap (dec bit)))]
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
                (make-bitmap! store (bit-set 0 my-chunk) [deeper]
                              (get-measure store deeper)))
              (let [new-entry (make-entry! store key-hash key-ref val-ref measure)
                    bitmap (bit-or (bit-set 0 my-chunk) (bit-set 0 new-chunk))
                    children (if (< my-chunk new-chunk)
                               [node-hash new-entry]
                               [new-entry node-hash])]
                (make-bitmap! store bitmap children
                              (measure-seq (mapv #(get-measure store %) children))))))))

      "hamt/bitmap"
      (let [{:keys [bitmap children]} (node-data node)
            chunk (hash-chunk key-hash level)
            bit (bit-set 0 chunk)
            idx (Long/bitCount (bit-and bitmap (dec bit)))]
        (if (not= 0 (bit-and bitmap bit))
          (let [new-child (hamt-assoc* store (nth children idx) key-hash
                                       key-ref val-ref measure (inc level))
                new-children (assoc children idx new-child)]
            (make-bitmap! store bitmap new-children
                          (measure-seq (mapv #(get-measure store %) new-children))))
          (let [new-entry (make-entry! store key-hash key-ref val-ref measure)
                new-children (vec (concat (subvec children 0 idx)
                                          [new-entry]
                                          (subvec children idx)))]
            (make-bitmap! store (bit-or bitmap bit) new-children
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
            bit (bit-set 0 chunk)]
        (if (= 0 (bit-and bitmap bit))
          node-hash
          (let [idx (Long/bitCount (bit-and bitmap (dec bit)))
                new-child (hamt-dissoc* store (nth children idx) key-hash (inc level))]
            (if (nil? new-child)
              (let [new-bitmap (bit-and-not bitmap bit)
                    new-children (vec (concat (subvec children 0 idx)
                                              (subvec children (inc idx))))]
                (cond
                  (= 0 new-bitmap) nil
                  (and (= 1 (Long/bitCount new-bitmap))
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
