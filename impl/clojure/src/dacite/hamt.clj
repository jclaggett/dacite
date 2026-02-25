(ns dacite.hamt
  "Hash Array Mapped Trie (HAMT) implementation for Dacite.
   
   A persistent map data structure with:
   - O(log32 n) lookup, insert, delete
   - Structural sharing for efficient updates
   - Content-addressed nodes via [dacite-map, root-hash] tuples
   
   Dacite-specific features:
   - 32-way branching (5-bit chunks of 256-bit hash)
   - Uses MSB (most mixed bits from fuse) for navigation
   - Accumulated measure (count, size-bytes) per node
   - All nodes bounded in size using hashes as references
   
   Node types stored as [type, data]:
   - [\"hamt/empty\" {:measure m}]
   - [\"hamt/entry\" {:key-hash h :key-ref h :val-ref h :measure m}]
   - [\"hamt/bitmap\" {:bitmap n :children [h...] :measure m}]"
  (:require [dacite.hash :as hash]
            [dacite.types :as types]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const BITS 5)           ;; bits per level
(def ^:const MASK 0x1F)        ;; 5-bit mask (0b11111)

;; =============================================================================
;; Measure (Monoid)
;; =============================================================================

(def measure-identity
  "Identity element for measure monoid."
  {:count 0 :size-bytes 0 :elements-fuse [0 0 0 0]})

(defn- measure-combine [m1 m2]
  {:count (+ (:count m1) (:count m2))
   :size-bytes (+ (:size-bytes m1) (:size-bytes m2))
   :elements-fuse (hash/unchecked-fuse (:elements-fuse m1) (:elements-fuse m2))})

(defn- measure-seq
  "Combine a sequence of measures using the monoid."
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
   Level 51: bits 4-0 of c3 (last 5 bits)"
  [hash-longs level]
  (let [bit-offset (* level BITS)
        long-idx (quot bit-offset 64)
        bit-in-long (mod bit-offset 64)
        long-val (nth hash-longs long-idx 0)
        shift (- 59 bit-in-long)]
    (if (>= shift 0)
      (bit-and MASK (unsigned-bit-shift-right long-val shift))
      ;; Span two longs
      (let [bits-from-current (+ shift 5)           ;; bits we can get from current long
            bits-from-next (- 5 bits-from-current)   ;; bits we need from next long
            current-part (bit-shift-left
                          (bit-and long-val (dec (bit-shift-left 1 bits-from-current)))
                          bits-from-next)
            next-long (nth hash-longs (inc long-idx) 0)
            next-part (unsigned-bit-shift-right next-long (- 64 bits-from-next))]
        (bit-and MASK (bit-or current-part next-part))))))

;; =============================================================================
;; Node helpers
;; =============================================================================

(defn- add-node
  "Add an internal tree node to the dacite-map, return [updated-map, hash].
   Hash is computed from node type and semantic content.
   
   For bitmap nodes, the bitmap value is included in the hash because
   it determines routing structure — two bitmaps with the same elements
   but different bitmaps are NOT interchangeable."
  [dacite-map node]
  (let [type-kw (first node)
        data (second node)
        ef (:elements-fuse (:measure data))
        h (if (= "hamt/bitmap" type-kw)
            ;; Include bitmap in hash: different routing = different identity
            (let [bitmap-bytes (.array (doto (java.nio.ByteBuffer/allocate 8)
                                         (.putLong (long (:bitmap data)))))
                  bitmap-hash (hash/fuse-bytes bitmap-bytes)]
              (hash/unchecked-fuse (hash/node-hash type-kw ef) bitmap-hash))
            (hash/node-hash type-kw ef))]
    [(assoc dacite-map h node) h]))

(defn- lookup-node
  "Look up a node by hash in the dacite-map."
  [dacite-map h]
  (get dacite-map h))

(defn- node-type [node]
  (first node))

(defn- node-data [node]
  (second node))

(defn- get-measure [dacite-map h]
  (:measure (node-data (lookup-node dacite-map h))))

;; =============================================================================
;; Node constructors
;; =============================================================================

(defn- make-empty [dacite-map]
  (add-node dacite-map ["hamt/empty" {:measure measure-identity}]))

(defn- make-entry [dacite-map key-hash key-ref val-ref measure]
  (add-node dacite-map ["hamt/entry" {:key-hash key-hash
                                      :key-ref key-ref
                                      :val-ref val-ref
                                      :measure measure}]))

(defn- make-bitmap [dacite-map bitmap children measure]
  (add-node dacite-map ["hamt/bitmap" {:bitmap bitmap
                                       :children (vec children)
                                       :measure measure}]))

;; =============================================================================
;; Internal HAMT operations
;; =============================================================================

(declare hamt-assoc* hamt-dissoc*)

(defn- hamt-lookup*
  "Look up value-ref by key-hash. Returns val-ref or nil."
  [dacite-map node-hash key-hash level]
  (let [node (lookup-node dacite-map node-hash)]
    (case (node-type node)
      "hamt/empty" nil

      "hamt/entry"
      (let [{entry-key-hash :key-hash entry-val-ref :val-ref} (node-data node)]
        (when (= entry-key-hash key-hash)
          entry-val-ref))

      "hamt/bitmap"
      (let [{:keys [bitmap children]} (node-data node)
            chunk (hash-chunk key-hash level)
            bit (bit-set 0 chunk)]
        (when (not= 0 (bit-and bitmap bit))
          (let [idx (Long/bitCount (bit-and bitmap (dec bit)))
                child-hash (nth children idx)]
            (hamt-lookup* dacite-map child-hash key-hash (inc level))))))))

(defn- hamt-assoc*
  "Associate key with value. Returns [new-map, new-node-hash]."
  [dacite-map node-hash key-hash key-ref val-ref measure level]
  (let [node (lookup-node dacite-map node-hash)]
    (case (node-type node)
      "hamt/empty"
      (make-entry dacite-map key-hash key-ref val-ref measure)

      "hamt/entry"
      (let [existing-key-hash (:key-hash (node-data node))]
        (if (= existing-key-hash key-hash)
          ;; Same key - replace
          (make-entry dacite-map key-hash key-ref val-ref measure)
          ;; Different key - split into bitmap node
          (let [my-chunk (hash-chunk existing-key-hash level)
                new-chunk (hash-chunk key-hash level)]
            (if (= my-chunk new-chunk)
              ;; Same chunk - recurse deeper with a single-child bitmap
              (let [[m1 deeper] (hamt-assoc* dacite-map node-hash key-hash
                                             key-ref val-ref measure (inc level))
                    deeper-measure (get-measure m1 deeper)]
                (make-bitmap m1 (bit-set 0 my-chunk) [deeper] deeper-measure))
              ;; Different chunks - two-child bitmap
              (let [[m1 new-entry-h] (make-entry dacite-map key-hash key-ref val-ref measure)
                    bitmap (bit-or (bit-set 0 my-chunk) (bit-set 0 new-chunk))
                    children (if (< my-chunk new-chunk)
                               [node-hash new-entry-h]
                               [new-entry-h node-hash])
                    combined-measure (measure-seq (mapv #(get-measure m1 %) children))]
                (make-bitmap m1 bitmap children combined-measure))))))

      "hamt/bitmap"
      (let [{:keys [bitmap children]} (node-data node)
            chunk (hash-chunk key-hash level)
            bit (bit-set 0 chunk)
            idx (Long/bitCount (bit-and bitmap (dec bit)))]
        (if (not= 0 (bit-and bitmap bit))
          ;; Child exists - recurse
          (let [child-hash (nth children idx)
                [m1 new-child] (hamt-assoc* dacite-map child-hash key-hash
                                            key-ref val-ref measure (inc level))
                new-children (assoc children idx new-child)
                new-measure (measure-seq (mapv #(get-measure m1 %) new-children))]
            (make-bitmap m1 bitmap new-children new-measure))
          ;; No child - insert new entry
          (let [[m1 new-entry-h] (make-entry dacite-map key-hash key-ref val-ref measure)
                new-children (vec (concat (subvec children 0 idx)
                                          [new-entry-h]
                                          (subvec children idx)))
                new-bitmap (bit-or bitmap bit)
                new-measure (measure-seq (mapv #(get-measure m1 %) new-children))]
            (make-bitmap m1 new-bitmap new-children new-measure)))))))

(defn- hamt-dissoc*
  "Remove key. Returns [new-map, new-node-hash] or [map, nil] if node becomes empty."
  [dacite-map node-hash key-hash level]
  (let [node (lookup-node dacite-map node-hash)]
    (case (node-type node)
      "hamt/empty" [dacite-map nil]

      "hamt/entry"
      (if (= key-hash (:key-hash (node-data node)))
        [dacite-map nil]
        [dacite-map node-hash])

      "hamt/bitmap"
      (let [{:keys [bitmap children]} (node-data node)
            chunk (hash-chunk key-hash level)
            bit (bit-set 0 chunk)]
        (if (= 0 (bit-and bitmap bit))
          ;; Not present
          [dacite-map node-hash]
          (let [idx (Long/bitCount (bit-and bitmap (dec bit)))
                child-hash (nth children idx)
                [m1 new-child] (hamt-dissoc* dacite-map child-hash key-hash (inc level))]
            (if (nil? new-child)
              ;; Child removed entirely
              (let [new-bitmap (bit-and-not bitmap bit)
                    new-children (vec (concat (subvec children 0 idx)
                                              (subvec children (inc idx))))]
                (cond
                  ;; Node now empty
                  (= 0 new-bitmap)
                  [m1 nil]

                  ;; Single entry child remaining - collapse
                  (and (= 1 (Long/bitCount new-bitmap))
                       (= "hamt/entry" (node-type (lookup-node m1 (first new-children)))))
                  [m1 (first new-children)]

                  ;; Multiple children remain
                  :else
                  (let [new-measure (measure-seq (mapv #(get-measure m1 %) new-children))]
                    (make-bitmap m1 new-bitmap new-children new-measure))))
              ;; Child still exists but changed
              (let [new-children (assoc children idx new-child)
                    new-measure (measure-seq (mapv #(get-measure m1 %) new-children))]
                (make-bitmap m1 bitmap new-children new-measure)))))))))

(defn- hamt-entries*
  "Collect all [key-ref val-ref] pairs from a node."
  [dacite-map node-hash]
  (let [node (lookup-node dacite-map node-hash)]
    (case (node-type node)
      "hamt/empty" []
      "hamt/entry" (let [{:keys [key-ref val-ref]} (node-data node)]
                     [[key-ref val-ref]])
      "hamt/bitmap" (let [{:keys [children]} (node-data node)]
                      (mapcat #(hamt-entries* dacite-map %) children)))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn hamt
  "Create an empty HAMT. Returns [dacite-map, root-hash]."
  []
  (make-empty {}))

(defn add-value
  "Add a typed value to the dacite-map. Returns [updated-map, value-hash].
   Value is a [type-name, data] tuple (e.g., [\"string\" \"name\"]).
   Hash is computed as fuse(type-name-hash, scalar-hash) per spec."
  [dacite-map value]
  (let [h (hash/typed-value-hash value)]
    [(assoc dacite-map h value) h]))

(defn assoc-val
  "Associate a key-ref with a val-ref in the HAMT.
   Both key and value should already be in the dacite-map via add-value.
   key-hash is the hash used for HAMT navigation (typically the key's content hash).
   Returns [new-map, new-root-hash]."
  [[dacite-map root-hash] key-hash key-ref val-ref]
  (let [key-value (lookup-node dacite-map key-ref)
        val-value (lookup-node dacite-map val-ref)
        key-size (types/dacite-size key-value)
        val-size (types/dacite-size val-value)
        entry-fuse (hash/fuse key-ref val-ref)
        measure {:count 1
                 :size-bytes (+ key-size val-size)
                 :elements-fuse entry-fuse}]
    (hamt-assoc* dacite-map root-hash key-hash key-ref val-ref measure 0)))

(defn get-val
  "Look up value-ref by key-hash. Returns the val-ref hash, or nil if not found."
  [[dacite-map root-hash] key-hash]
  (let [node (lookup-node dacite-map root-hash)]
    (case (node-type node)
      "hamt/empty" nil

      "hamt/entry"
      (let [{entry-key-hash :key-hash entry-val-ref :val-ref} (node-data node)]
        (when (= entry-key-hash key-hash)
          entry-val-ref))

      "hamt/bitmap"
      (let [{:keys [bitmap children]} (node-data node)
            chunk (hash-chunk key-hash 0)
            bit (bit-set 0 chunk)]
        (when (not= 0 (bit-and bitmap bit))
          (let [idx (Long/bitCount (bit-and bitmap (dec bit)))
                child-hash (nth children idx)]
            (hamt-lookup* dacite-map child-hash key-hash 1)))))))

(defn dissoc-val
  "Remove key by key-hash. Returns [new-map, new-root-hash]."
  [[dacite-map root-hash] key-hash]
  (let [[m new-root] (hamt-dissoc* dacite-map root-hash key-hash 0)]
    (if (nil? new-root)
      (make-empty m)
      [m new-root])))

(defn entries
  "Return all [key-ref val-ref] pairs as a sequence."
  [[dacite-map root-hash]]
  (hamt-entries* dacite-map root-hash))

(defn hamt-count
  "Return the number of entries (O(1) via measure)."
  [[dacite-map root-hash]]
  (:count (get-measure dacite-map root-hash)))

(defn hamt-size-bytes
  "Return total size in bytes of keys + values (O(1) via measure)."
  [[dacite-map root-hash]]
  (:size-bytes (get-measure dacite-map root-hash)))

(defn hamt-elements-fuse
  "Get the fused hash of all entries (O(1) via cached measure).
   Each entry contributes fuse(key-ref, val-ref), combined left-to-right
   in HAMT traversal order (ascending key-hash, i.e., sorted by hash).
   Use with a collection type hash to compute the semantic hash:
   (fuse collection-type-hash (hamt-elements-fuse h))"
  [[dacite-map root-hash]]
  (:elements-fuse (get-measure dacite-map root-hash)))

;; =============================================================================
;; REPL examples
;; =============================================================================

(comment
  ;; Create empty HAMT
  (def h (hamt))

  ;; Add values to map first
  (let [[m0 root] (hamt)
        [m1 k-ref] (add-value m0 ["string" "name"])
        [m2 v-ref] (add-value m1 ["string" "Alice"])
        key-hash (hash/fuse-str "name")
        h1 (assoc-val [m2 root] key-hash k-ref v-ref)]
    (get-val h1 key-hash)      ;; => v-ref
    (hamt-count h1)             ;; => 1
    (entries h1)))
