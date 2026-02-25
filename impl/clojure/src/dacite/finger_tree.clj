(ns dacite.finger-tree
  "Pure Finger Tree implementation for Dacite.
   
   A persistent sequence data structure with:
   - O(1) access to both ends
   - O(log n) concatenation
   - O(1) count/size via cached measures
   
   Trees are represented as [dacite-map, root-hash] tuples:
   - dacite-map: {hash -> value} containing all nodes
   - root-hash: hash of the root node
   
   Operations are pure functions that return new [map, hash] tuples.
   The map only grows (no GC). Persist by iterating over the map.
   
   Node types stored as [type, data]:
   - [\"ft/empty\" {:measure m}]
   - [\"ft/single\" {:value-hash h :measure m}]  ; single element wrapper
   - [\"ft/digit\" {:children [h...] :measure m}]
   - [\"ft/node\" {:children [h...] :measure m}]
   - [\"ft/deep\" {:left h :spine h :right h :measure m}]"
  (:require [dacite.hash :as hash]
            [dacite.types :as types]))

;; =============================================================================
;; Measure (Monoid)
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
;; Node helpers
;; =============================================================================

(defn- add-node
  "Add an internal tree node to the dacite-map, return [updated-map, hash].
   Hash is computed from node type and elements-fuse (semantic)."
  [dacite-map node]
  (let [type-kw (first node)
        ef (:elements-fuse (:measure (second node)))
        h (hash/node-hash type-kw ef)]
    [(assoc dacite-map h node) h]))

(defn- lookup-node
  "Look up a node by hash in the dacite-map."
  [dacite-map hash]
  (get dacite-map hash))

(defn- node-type [node]
  (first node))

(defn- node-data [node]
  (second node))

(defn- get-measure [dacite-map hash]
  (:measure (node-data (lookup-node dacite-map hash))))

(defn- get-children [dacite-map hash]
  (:children (node-data (lookup-node dacite-map hash))))

(defn- get-value-hash [dacite-map hash]
  (:value-hash (node-data (lookup-node dacite-map hash))))

;; =============================================================================
;; Node constructors (add to map, return [map, hash])
;; =============================================================================

(defn- make-empty [dacite-map]
  (add-node dacite-map ["ft/empty" {:measure measure-identity}]))

(defn- make-single [dacite-map value-hash size-bytes]
  (add-node dacite-map ["ft/single" {:value-hash value-hash
                                     :measure {:count 1
                                               :size-bytes size-bytes
                                               :elements-fuse value-hash}}]))

(defn- make-digit [dacite-map child-hashes child-measures]
  (add-node dacite-map ["ft/digit" {:children (vec child-hashes)
                                    :measure (measure-seq child-measures)}]))

(defn- make-node [dacite-map child-hashes child-measures]
  {:pre [(<= 2 (count child-hashes) 32)]}
  (add-node dacite-map ["ft/node" {:children (vec child-hashes)
                                   :measure (measure-seq child-measures)}]))

(defn- make-deep [dacite-map left-h spine-h right-h left-m spine-m right-m]
  (add-node dacite-map ["ft/deep" {:left left-h
                                   :spine spine-h
                                   :right right-h
                                   :measure (measure-combine
                                             (measure-combine left-m spine-m)
                                             right-m)}]))

;; =============================================================================
;; Type predicates
;; =============================================================================

(defn- empty-node? [dacite-map hash]
  (= "ft/empty" (node-type (lookup-node dacite-map hash))))

;; =============================================================================
;; Digit operations
;; =============================================================================

(defn- digit-first [dacite-map digit-hash]
  (first (get-children dacite-map digit-hash)))

(defn- digit-last [dacite-map digit-hash]
  (peek (get-children dacite-map digit-hash)))

(defn- digit-count [dacite-map digit-hash]
  (count (get-children dacite-map digit-hash)))

(defn- digit-rest
  "Remove first element from digit. Returns [map, new-digit-hash] or [map, nil] if empty."
  [dacite-map digit-hash]
  (let [children (get-children dacite-map digit-hash)]
    (if (> (count children) 1)
      (let [new-children (subvec children 1)
            new-measures (mapv #(get-measure dacite-map %) new-children)]
        (make-digit dacite-map new-children new-measures))
      [dacite-map nil])))

(defn- digit-butlast
  "Remove last element from digit. Returns [map, new-digit-hash] or [map, nil] if empty."
  [dacite-map digit-hash]
  (let [children (get-children dacite-map digit-hash)]
    (if (> (count children) 1)
      (let [new-children (pop children)
            new-measures (mapv #(get-measure dacite-map %) new-children)]
        (make-digit dacite-map new-children new-measures))
      [dacite-map nil])))

(defn- digit-conj-left [dacite-map digit-hash elem-hash]
  (let [children (get-children dacite-map digit-hash)
        new-children (into [elem-hash] children)
        new-measures (mapv #(get-measure dacite-map %) new-children)]
    (make-digit dacite-map new-children new-measures)))

(defn- digit-conj-right [dacite-map digit-hash elem-hash]
  (let [children (get-children dacite-map digit-hash)
        new-children (conj children elem-hash)
        new-measures (mapv #(get-measure dacite-map %) new-children)]
    (make-digit dacite-map new-children new-measures)))

;; =============================================================================
;; Tree operations (internal, work on [map, hash])
;; =============================================================================

(declare tree-conj-left tree-conj-right)

(defn- tree-first*
  "Get first element hash from tree. Returns hash or nil."
  [dacite-map root-hash]
  (let [node (lookup-node dacite-map root-hash)]
    (case (node-type node)
      "ft/empty" nil
      "ft/deep" (let [left-hash (:left (node-data node))]
                  (digit-first dacite-map left-hash))
      root-hash)))

(defn- tree-last*
  "Get last element hash from tree. Returns hash or nil."
  [dacite-map root-hash]
  (let [node (lookup-node dacite-map root-hash)]
    (case (node-type node)
      "ft/empty" nil
      "ft/deep" (let [right-hash (:right (node-data node))]
                  (digit-last dacite-map right-hash))
      root-hash)))

(defn- to-tree-from-digit
  "Convert a digit to a tree. Returns [map, root-hash]."
  [dacite-map digit-hash]
  (let [children (get-children dacite-map digit-hash)
        [m empty-h] (make-empty dacite-map)]
    (reduce (fn [[m h] child-h]
              (tree-conj-right m h child-h))
            [m empty-h]
            children)))

(defn- tree-rest*
  "Remove first element, return [map, new-root-hash]."
  [dacite-map root-hash]
  (let [node (lookup-node dacite-map root-hash)]
    (case (node-type node)
      "ft/empty" [dacite-map root-hash]
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            [m1 new-left] (digit-rest dacite-map left)]
        (if new-left
          ;; Left digit still has elements
          (make-deep m1 new-left spine right
                     (get-measure m1 new-left)
                     (get-measure m1 spine)
                     (get-measure m1 right))
          ;; Left digit exhausted
          (if (empty-node? m1 spine)
            ;; Spine empty, convert right to tree
            (to-tree-from-digit m1 right)
            ;; Pull node from spine
            (let [spine-first (tree-first* m1 spine)
                  [m2 new-spine] (tree-rest* m1 spine)
                  node-children (get-children m2 spine-first)
                  node-measures (mapv #(get-measure m2 %) node-children)
                  [m3 new-left'] (make-digit m2 node-children node-measures)]
              (make-deep m3 new-left' new-spine right
                         (get-measure m3 new-left')
                         (get-measure m3 new-spine)
                         (get-measure m3 right))))))
      (make-empty dacite-map))))

(defn- tree-butlast*
  "Remove last element, return [map, new-root-hash]."
  [dacite-map root-hash]
  (let [node (lookup-node dacite-map root-hash)]
    (case (node-type node)
      "ft/empty" [dacite-map root-hash]
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            [m1 new-right] (digit-butlast dacite-map right)]
        (if new-right
          ;; Right digit still has elements
          (make-deep m1 left spine new-right
                     (get-measure m1 left)
                     (get-measure m1 spine)
                     (get-measure m1 new-right))
          ;; Right digit exhausted
          (if (empty-node? m1 spine)
            ;; Spine empty, convert left to tree
            (to-tree-from-digit m1 left)
            ;; Pull node from spine
            (let [spine-last (tree-last* m1 spine)
                  [m2 new-spine] (tree-butlast* m1 spine)
                  node-children (get-children m2 spine-last)
                  node-measures (mapv #(get-measure m2 %) node-children)
                  [m3 new-right'] (make-digit m2 node-children node-measures)]
              (make-deep m3 left new-spine new-right'
                         (get-measure m3 left)
                         (get-measure m3 new-spine)
                         (get-measure m3 new-right'))))))
      (make-empty dacite-map))))

(defn- tree-conj-left
  "Add element to left of tree, return [map, new-root-hash]."
  [dacite-map root-hash elem-hash]
  (let [node (lookup-node dacite-map root-hash)]
    (case (node-type node)
      "ft/empty" [dacite-map elem-hash]
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            left-count (digit-count dacite-map left)]
        (if (< left-count 32)
          ;; Room in left digit
          (let [[m1 new-left] (digit-conj-left dacite-map left elem-hash)]
            (make-deep m1 new-left spine right
                       (get-measure m1 new-left)
                       (get-measure m1 spine)
                       (get-measure m1 right)))
          ;; Left digit full, push to spine
          (let [left-children (get-children dacite-map left)
                new-left-children (into [elem-hash] (subvec left-children 0 7))
                new-left-measures (mapv #(get-measure dacite-map %) new-left-children)
                [m1 new-left] (make-digit dacite-map new-left-children new-left-measures)
                node-children (subvec left-children 7 32)
                node-measures (mapv #(get-measure m1 %) node-children)
                [m2 new-node] (make-node m1 node-children node-measures)
                [m3 new-spine] (tree-conj-left m2 spine new-node)]
            (make-deep m3 new-left new-spine right
                       (get-measure m3 new-left)
                       (get-measure m3 new-spine)
                       (get-measure m3 right)))))

      (let [[m1 left] (make-digit dacite-map [elem-hash] [(get-measure dacite-map elem-hash)])
            [m2 spine] (make-empty m1)
            [m3 right] (make-digit m2 [root-hash] [(get-measure m2 root-hash)])]
        (make-deep m3 left spine right
                   (get-measure m3 left)
                   (get-measure m3 spine)
                   (get-measure m3 right))))))

(defn- tree-conj-right
  "Add element to right of tree, return [map, new-root-hash]."
  [dacite-map root-hash elem-hash]
  (let [node (lookup-node dacite-map root-hash)]
    (case (node-type node)
      "ft/empty" [dacite-map elem-hash]
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            right-count (digit-count dacite-map right)]
        (if (< right-count 32)
          ;; Room in right digit
          (let [[m1 new-right] (digit-conj-right dacite-map right elem-hash)]
            (make-deep m1 left spine new-right
                       (get-measure m1 left)
                       (get-measure m1 spine)
                       (get-measure m1 new-right)))
          ;; Right digit full, push to spine
          (let [right-children (get-children dacite-map right)
                node-children (subvec right-children 0 24)
                node-measures (mapv #(get-measure dacite-map %) node-children)
                [m1 new-node] (make-node dacite-map node-children node-measures)
                [m2 new-spine] (tree-conj-right m1 spine new-node)
                new-right-children (conj (subvec right-children 24 32) elem-hash)
                new-right-measures (mapv #(get-measure m2 %) new-right-children)
                [m3 new-right] (make-digit m2 new-right-children new-right-measures)]
            (make-deep m3 left new-spine new-right
                       (get-measure m3 left)
                       (get-measure m3 new-spine)
                       (get-measure m3 new-right)))))

      (let [[m1 left] (make-digit dacite-map [root-hash] [(get-measure dacite-map root-hash)])
            [m2 spine] (make-empty m1)
            [m3 right] (make-digit m2 [elem-hash] [(get-measure m2 elem-hash)])]
        (make-deep m3 left spine right
                   (get-measure m3 left)
                   (get-measure m3 spine)
                   (get-measure m3 right))))))

(defn- tree-to-seq*
  "Convert tree to seq of single-node hashes."
  [dacite-map root-hash]
  (let [node (lookup-node dacite-map root-hash)]
    (case (node-type node)
      "ft/empty" []
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)]
        (concat
         (get-children dacite-map left)
         (mapcat #(get-children dacite-map %) (tree-to-seq* dacite-map spine))
         (get-children dacite-map right)))
      [root-hash])))

;; =============================================================================
;; Public API
;; =============================================================================

(defn finger-tree
  "Create an empty finger tree. Returns [dacite-map, root-hash]."
  []
  (make-empty {}))

(defn add-value
  "Add a typed value to the dacite-map. Returns [updated-map, hash].
   Value is a [type-name, data] tuple (e.g., [\"i64\" 42]).
   Hash is computed as fuse(type-name-hash, scalar-hash) per spec."
  [dacite-map value]
  (let [h (hash/typed-value-hash value)]
    [(assoc dacite-map h value) h]))

(defn conj-left
  "Add element to the left of the tree.
   Takes [dacite-map, root-hash] and a value-hash.
   The value should already be in the dacite-map.
   Returns [new-map, new-root-hash]."
  [[dacite-map root-hash] value-hash]
  (let [size-bytes (types/dacite-size (lookup-node dacite-map value-hash))
        [m1 single-hash] (make-single dacite-map value-hash size-bytes)]
    (tree-conj-left m1 root-hash single-hash)))

(defn conj-right
  "Add element to the right of the tree.
   Takes [dacite-map, root-hash] and a value-hash.
   The value should already be in the dacite-map.
   Returns [new-map, new-root-hash]."
  [[dacite-map root-hash] value-hash]
  (let [size-bytes (types/dacite-size (lookup-node dacite-map value-hash))
        [m1 single-hash] (make-single dacite-map value-hash size-bytes)]
    (tree-conj-right m1 root-hash single-hash)))

(defn tree-first
  "Get the first element's value-hash, or nil if empty."
  [[dacite-map root-hash]]
  (when-let [single-hash (tree-first* dacite-map root-hash)]
    (get-value-hash dacite-map single-hash)))

(defn tree-last
  "Get the last element's value-hash, or nil if empty."
  [[dacite-map root-hash]]
  (when-let [single-hash (tree-last* dacite-map root-hash)]
    (get-value-hash dacite-map single-hash)))

(defn tree-rest
  "Remove the first element from the tree.
   Returns [new-map, new-root-hash]."
  [[dacite-map root-hash]]
  (tree-rest* dacite-map root-hash))

(defn tree-butlast
  "Remove the last element from the tree.
   Returns [new-map, new-root-hash]."
  [[dacite-map root-hash]]
  (tree-butlast* dacite-map root-hash))

(defn tree-empty?
  "Is the tree empty?"
  [[dacite-map root-hash]]
  (empty-node? dacite-map root-hash))

(defn tree-count
  "Get the count of elements (O(1) via cached measure)."
  [[dacite-map root-hash]]
  (:count (get-measure dacite-map root-hash)))

(defn tree-size-bytes
  "Get the total size in bytes (O(1) via cached measure)."
  [[dacite-map root-hash]]
  (:size-bytes (get-measure dacite-map root-hash)))

(defn tree-elements-fuse
  "Get the fused hash of all elements (O(1) via cached measure).
   This is the running fuse of all element value hashes in order.
   Use with a collection type hash to compute the semantic hash:
   (fuse collection-type-hash (tree-elements-fuse tree))"
  [[dacite-map root-hash]]
  (:elements-fuse (get-measure dacite-map root-hash)))

(defn tree-concat
  "Concatenate two trees. Returns [merged-map, new-root-hash]."
  [[m1 h1] [m2 h2]]
  (let [;; Merge the maps
        merged (merge m1 m2)
        ;; Add all elements from tree2 to tree1
        single-hashes (tree-to-seq* merged h2)]
    (reduce (fn [[m h] single-hash]
              (let [{:keys [value-hash]} (node-data (lookup-node m single-hash))]
                (conj-right [m h] value-hash)))
            [merged h1]
            single-hashes)))

(defn from-seq
  "Build a finger tree from a sequence of values.
   Returns [dacite-map, root-hash]."
  [values]
  (reduce (fn [[m h] value]
            (let [[m' vh] (add-value m value)]
              (conj-right [m' h] vh)))
          (finger-tree)
          values))

(defn to-vec
  "Convert tree to vector of value-hashes."
  [[dacite-map root-hash]]
  (mapv #(get-value-hash dacite-map %) (tree-to-seq* dacite-map root-hash)))

;; =============================================================================
;; REPL examples
;; =============================================================================

(comment
  ;; Create an empty tree
  (def ft0 (finger-tree))
  ;; => [{hash ["ft/empty" ...]} hash]

  (tree-empty? ft0)  ;; => true
  (tree-count ft0)   ;; => 0

  ;; Add values - first add to map, then conj
  (let [[m0 h0] (finger-tree)
        [m1 v1] (add-value m0 ["i64" 42])
        [m2 v2] (add-value m1 ["i64" 43])
        ft1 (conj-right [m2 h0] v1)  ;; use m2 which has both values
        ft2 (conj-right ft1 v2)]
    (tree-count ft2)     ;; => 2
    (to-vec ft2))        ;; => [v1 v2]

  ;; Simpler: use from-seq
  (def ft (from-seq [["i64" 1] ["i64" 2] ["i64" 3]]))
  (tree-count ft)        ;; => 3
  (tree-first ft))        ;; => hash of [:i64 1]
