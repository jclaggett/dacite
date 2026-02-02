(ns dacite.finger-tree
  "Finger Tree implementation for Dacite.
   
   A persistent sequence data structure with:
   - O(1) access to both ends
   - O(log n) random access and split
   - O(log n) concatenation
   
   Adapted for Dacite with:
   - 32-way branching (shallow trees for network efficiency)
   - 8-32 element fingers
   - Accumulated measure (count, size_bytes) per node"
  (:require [dacite.hash :as hash]))

;; =============================================================================
;; Measure (Monoid)
;; =============================================================================

(def measure-identity
  "Identity element for measure monoid."
  {:count 0 :size-bytes 0})

(defn measure-combine
  "Combine two measures (monoid operation)."
  [m1 m2]
  {:count (+ (:count m1) (:count m2))
   :size-bytes (+ (:size-bytes m1) (:size-bytes m2))})

(defn measure-concat
  "Combine multiple measures."
  [measures]
  (reduce measure-combine measure-identity measures))

;; =============================================================================
;; Protocols
;; =============================================================================

(defprotocol Measured
  "Protocol for types that have a cached measure."
  (measure [this] "Return the measure of this value."))

(defprotocol FingerTree
  "Protocol for finger tree operations."
  (ft-empty? [this] "Is the tree empty?")
  (ft-first [this] "Get the first element.")
  (ft-last [this] "Get the last element.")
  (ft-rest [this] "Remove the first element.")
  (ft-butlast [this] "Remove the last element.")
  (ft-conj-left [this v] "Add element to the left.")
  (ft-conj-right [this v] "Add element to the right.")
  (ft-concat [this other] "Concatenate two trees.")
  (ft-to-vec [this] "Convert to a vector (for debugging)."))

;; =============================================================================
;; Leaf wrapper (measured element)
;; =============================================================================

(defrecord Leaf [value size-bytes hash-longs]
  Measured
  (measure [_] {:count 1 :size-bytes size-bytes}))

(defn make-leaf
  "Create a leaf with its measure and hash."
  [value size-bytes hash-longs]
  (->Leaf value size-bytes hash-longs))

;; =============================================================================
;; Node (internal node with 2-32 children)
;; =============================================================================

(declare empty-tree make-deep make-digit)

(defrecord Node [children cached-measure]
  Measured
  (measure [_] cached-measure)

  ;; Node can act as a single-element tree (for spine)
  FingerTree
  (ft-empty? [_] false)
  (ft-first [this] this)
  (ft-last [this] this)
  (ft-rest [_] empty-tree)
  (ft-butlast [_] empty-tree)
  (ft-conj-left [this v]
    (make-deep (make-digit [v])
               empty-tree
               (make-digit [this])))
  (ft-conj-right [this v]
    (make-deep (make-digit [this])
               empty-tree
               (make-digit [v])))
  (ft-concat [this other]
    (ft-conj-left other this))
  (ft-to-vec [this] [this]))

(defn make-node
  "Create an internal node with 2-32 children."
  [children]
  {:pre [(<= 2 (count children) 32)]}
  (->Node (vec children)
          (measure-concat (map measure children))))

(defn node-children [^Node node]
  (:children node))

;; =============================================================================
;; Digit (finger with 8-32 elements, or fewer for small trees)
;; =============================================================================

(defrecord Digit [elements cached-measure]
  Measured
  (measure [_] cached-measure))

(defn make-digit
  "Create a digit (finger) with 1-32 elements."
  [elements]
  {:pre [(<= 1 (count elements) 32)]}
  (->Digit (vec elements)
           (measure-concat (map measure elements))))

(defn digit-elements [^Digit digit]
  (:elements digit))

(defn digit-first [^Digit digit]
  (first (:elements digit)))

(defn digit-last [^Digit digit]
  (peek (:elements digit)))

(defn digit-rest [^Digit digit]
  (let [elems (subvec (:elements digit) 1)]
    (when (seq elems)
      (make-digit elems))))

(defn digit-butlast [^Digit digit]
  (let [elems (pop (:elements digit))]
    (when (seq elems)
      (make-digit elems))))

(defn digit-conj-left [^Digit digit elem]
  (make-digit (into [elem] (:elements digit))))

(defn digit-conj-right [^Digit digit elem]
  (make-digit (conj (:elements digit) elem)))

(defn digit-count [^Digit digit]
  (count (:elements digit)))

;; =============================================================================
;; Deep Tree (left finger, spine, right finger)
;; MUST be defined before EmptyTree and Leaf extensions that reference it
;; =============================================================================

(declare deep-conj-left deep-conj-right)

(defrecord Deep [left spine right cached-measure]
  Measured
  (measure [_] cached-measure))

(defn make-deep
  "Create a deep tree with proper measure calculation."
  [left spine right]
  (->Deep left spine right
          (measure-combine
           (measure-combine (measure left) (measure spine))
           (measure right))))

;; =============================================================================
;; Empty Tree
;; =============================================================================

(defrecord EmptyTree []
  Measured
  (measure [_] measure-identity)

  FingerTree
  (ft-empty? [_] true)
  (ft-first [_] nil)
  (ft-last [_] nil)
  (ft-rest [_] empty-tree)
  (ft-butlast [_] empty-tree)
  (ft-conj-left [_ v] v)  ;; Return the leaf directly
  (ft-conj-right [_ v] v) ;; Return the leaf directly
  (ft-concat [_ other] other)
  (ft-to-vec [_] []))

(def empty-tree (->EmptyTree))

;; =============================================================================
;; Single Element Tree (Leaf as FingerTree)
;; =============================================================================

(extend-type Leaf
  FingerTree
  (ft-empty? [_] false)
  (ft-first [this] this)
  (ft-last [this] this)
  (ft-rest [_] empty-tree)
  (ft-butlast [_] empty-tree)
  (ft-conj-left [this v]
    (make-deep (make-digit [v])
               empty-tree
               (make-digit [this])))
  (ft-conj-right [this v]
    (make-deep (make-digit [this])
               empty-tree
               (make-digit [v])))
  (ft-concat [this other]
    (ft-conj-left other this))
  (ft-to-vec [this] [this]))

;; =============================================================================
;; Deep Tree FingerTree implementation
;; =============================================================================

(extend-type Deep
  FingerTree
  (ft-empty? [_] false)

  (ft-first [this]
    (digit-first (:left this)))

  (ft-last [this]
    (digit-last (:right this)))

  (ft-rest [this]
    (let [left (:left this)
          spine (:spine this)
          right (:right this)]
      (if-let [new-left (digit-rest left)]
        (make-deep new-left spine right)
        ;; Left digit exhausted, pull from spine
        (if (ft-empty? spine)
          ;; Spine empty, convert right to tree
          (reduce ft-conj-right empty-tree (digit-elements right))
          ;; Pull node from spine
          (let [node (ft-first spine)
                new-spine (ft-rest spine)
                new-left (make-digit (node-children node))]
            (make-deep new-left new-spine right))))))

  (ft-butlast [this]
    (let [left (:left this)
          spine (:spine this)
          right (:right this)]
      (if-let [new-right (digit-butlast right)]
        (make-deep left spine new-right)
        ;; Right digit exhausted, pull from spine
        (if (ft-empty? spine)
          ;; Spine empty, convert left to tree
          (reduce ft-conj-right empty-tree (digit-elements left))
          ;; Pull node from spine
          (let [node (ft-last spine)
                new-spine (ft-butlast spine)
                new-right (make-digit (node-children node))]
            (make-deep left new-spine new-right))))))

  (ft-conj-left [this v]
    (deep-conj-left this v))

  (ft-conj-right [this v]
    (deep-conj-right this v))

  (ft-concat [this other]
    ;; Simplified concat - can be optimized
    (reduce ft-conj-right this (ft-to-vec other)))

  (ft-to-vec [this]
    ;; Return all elements in order (Leaves or Nodes depending on level)
    (vec (concat (digit-elements (:left this))
                 (mapcat node-children (ft-to-vec (:spine this)))
                 (digit-elements (:right this))))))

;; =============================================================================
;; Deep tree helpers
;; =============================================================================

(defn deep-conj-left
  "Add element to left of deep tree, handling overflow."
  [^Deep tree v]
  (let [left (:left tree)
        spine (:spine tree)
        right (:right tree)]
    (if (< (digit-count left) 32)
      ;; Room in left digit
      (make-deep (digit-conj-left left v) spine right)
      ;; Left digit full (32 elements), push node to spine
      (let [elems (digit-elements left)
            ;; Keep first 8 elements in new left, push rest as node
            new-left (make-digit (into [v] (subvec elems 0 7)))
            node-elems (subvec elems 7 32)
            new-node (make-node node-elems)
            new-spine (ft-conj-left spine new-node)]
        (make-deep new-left new-spine right)))))

(defn deep-conj-right
  "Add element to right of deep tree, handling overflow."
  [^Deep tree v]
  (let [left (:left tree)
        spine (:spine tree)
        right (:right tree)]
    (if (< (digit-count right) 32)
      ;; Room in right digit
      (make-deep left spine (digit-conj-right right v))
      ;; Right digit full (32 elements), push node to spine
      (let [elems (digit-elements right)
            ;; Push first 24 elements as node, keep last 8 + new element
            node-elems (subvec elems 0 24)
            new-node (make-node node-elems)
            new-spine (ft-conj-right spine new-node)
            new-right (make-digit (conj (subvec elems 24 32) v))]
        (make-deep left new-spine new-right)))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn finger-tree
  "Create an empty finger tree."
  []
  empty-tree)

(defn conj-left
  "Add element to the left of the tree."
  [tree elem]
  (ft-conj-left tree elem))

(defn conj-right
  "Add element to the right of the tree."
  [tree elem]
  (ft-conj-right tree elem))

(defn tree-first
  "Get the first element."
  [tree]
  (ft-first tree))

(defn tree-last
  "Get the last element."
  [tree]
  (ft-last tree))

(defn tree-rest
  "Remove the first element."
  [tree]
  (ft-rest tree))

(defn tree-butlast
  "Remove the last element."
  [tree]
  (ft-butlast tree))

(defn tree-concat
  "Concatenate two trees."
  [t1 t2]
  (ft-concat t1 t2))

(defn tree-count
  "Get the count of elements (O(1) via measure)."
  [tree]
  (:count (measure tree)))

(defn tree-size-bytes
  "Get the total size in bytes (O(1) via measure)."
  [tree]
  (:size-bytes (measure tree)))

(defn from-seq
  "Build a finger tree from a sequence of leaves."
  [leaves]
  (reduce conj-right (finger-tree) leaves))

(defn- extract-values
  "Recursively extract all leaf values from an element (Leaf or nested Nodes)."
  [elem]
  (if (instance? Leaf elem)
    [(:value elem)]
    (mapcat extract-values (:children elem))))

(defn to-vec
  "Convert tree to vector of values."
  [tree]
  (mapcat extract-values (ft-to-vec tree)))

;; =============================================================================
;; Hashing support
;; =============================================================================

(defn- collect-leaves
  "Collect all leaves from the tree in order."
  [tree]
  (cond
    (ft-empty? tree) []
    (instance? Leaf tree) [tree]
    (instance? Deep tree)
    (concat (digit-elements (:left tree))
            (mapcat node-children (collect-leaves (:spine tree)))
            (digit-elements (:right tree)))
    (instance? Node tree)
    [tree]  ;; Nodes in spine
    :else []))

(defn tree-hash-longs
  "Compute the fused hash of the tree as longs.
   Uses unchecked-fuse-longs to avoid byte conversion overhead."
  [tree]
  (let [leaves (collect-leaves tree)]
    (if (empty? leaves)
      ;; Empty collection hash - identity for fuse
      [0 0 0 0]
      (reduce hash/fuse-longs
              (map :hash-longs leaves)))))

(comment
  ;; Example usage
  (def leaves (map #(make-leaf % 8 (hash/bytes->longs (hash/sha256-str (str %))))
                   (range 100)))

  (def tree (from-seq leaves))

  (tree-count tree)    ;; => 100
  (tree-size-bytes tree) ;; => 800

  (-> tree tree-first :value) ;; => 0
  (-> tree tree-last :value)  ;; => 99

  (to-vec tree) ;; => [0 1 2 ... 99]
  )
