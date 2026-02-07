(ns dacite.finger-tree
  "Cached Finger Tree implementation for Dacite.
   
   A persistent sequence data structure with:
   - O(1) access to both ends
   - O(log n) concatenation
   - O(1) count/size via cached measures
   
   Every node is stored in a CacheManager:
   - Content-addressed by hash
   - Eager commit on creation
   - Lookup on traversal
   
   Node types stored as [type, data]:
   - [:ft/empty {:measure m}]
   - [:ft/leaf {:value-hash h :measure m}]  ; points to separately committed value
   - [:ft/digit {:children [h...] :measure m}]
   - [:ft/node {:children [h...] :measure m}]
   - [:ft/deep {:left h :spine h :right h :measure m}]"
  (:require [dacite.cache :as cache]))

;; =============================================================================
;; Measure (Monoid)
;; =============================================================================

(def measure-identity
  {:count 0 :size-bytes 0})

(defn- measure-combine [m1 m2]
  {:count (+ (:count m1) (:count m2))
   :size-bytes (+ (:size-bytes m1) (:size-bytes m2))})

(defn- measure-seq [measures]
  (reduce measure-combine measure-identity measures))

;; =============================================================================
;; Node accessors (lookup from cache)
;; =============================================================================

(defn- lookup-node [cache hash]
  (cache/lookup cache hash))

(defn- node-type [node]
  (first node))

(defn- node-data [node]
  (second node))

(defn- get-measure [cache hash]
  (:measure (node-data (lookup-node cache hash))))

(defn- get-children [cache hash]
  (:children (node-data (lookup-node cache hash))))

(defn- get-value-hash [cache hash]
  (:value-hash (node-data (lookup-node cache hash))))

;; =============================================================================
;; Node constructors (commit to cache, return hash)
;; =============================================================================

(defn- commit-empty [cache]
  (cache/commit! cache [:ft/empty {:measure measure-identity}]))

(defn- commit-leaf [cache value-hash size-bytes]
  (cache/commit! cache [:ft/leaf {:value-hash value-hash
                                  :measure {:count 1 :size-bytes size-bytes}}]))

(defn- commit-digit [cache child-hashes child-measures]
  (cache/commit! cache [:ft/digit {:children (vec child-hashes)
                                   :measure (measure-seq child-measures)}]))

(defn- commit-node [cache child-hashes child-measures]
  {:pre [(<= 2 (count child-hashes) 32)]}
  (cache/commit! cache [:ft/node {:children (vec child-hashes)
                                  :measure (measure-seq child-measures)}]))

(defn- commit-deep [cache left-h spine-h right-h left-m spine-m right-m]
  (cache/commit! cache [:ft/deep {:left left-h
                                  :spine spine-h
                                  :right right-h
                                  :measure (measure-combine
                                            (measure-combine left-m spine-m)
                                            right-m)}]))

;; =============================================================================
;; Type predicates
;; =============================================================================

(defn- empty-node? [cache hash]
  (= :ft/empty (node-type (lookup-node cache hash))))

;; =============================================================================
;; Digit operations
;; =============================================================================

(defn- digit-first [cache digit-hash]
  (first (get-children cache digit-hash)))

(defn- digit-last [cache digit-hash]
  (peek (get-children cache digit-hash)))

(defn- digit-count [cache digit-hash]
  (count (get-children cache digit-hash)))

(defn- digit-rest
  "Remove first element from digit. Returns new digit hash or nil if empty."
  [cache digit-hash]
  (let [children (get-children cache digit-hash)]
    (when (> (count children) 1)
      (let [new-children (subvec children 1)
            new-measures (mapv #(get-measure cache %) new-children)]
        (commit-digit cache new-children new-measures)))))

(defn- digit-butlast
  "Remove last element from digit. Returns new digit hash or nil if empty."
  [cache digit-hash]
  (let [children (get-children cache digit-hash)]
    (when (> (count children) 1)
      (let [new-children (pop children)
            new-measures (mapv #(get-measure cache %) new-children)]
        (commit-digit cache new-children new-measures)))))

(defn- digit-conj-left [cache digit-hash elem-hash]
  (let [children (get-children cache digit-hash)
        new-children (into [elem-hash] children)
        new-measures (mapv #(get-measure cache %) new-children)]
    (commit-digit cache new-children new-measures)))

(defn- digit-conj-right [cache digit-hash elem-hash]
  (let [children (get-children cache digit-hash)
        new-children (conj children elem-hash)
        new-measures (mapv #(get-measure cache %) new-children)]
    (commit-digit cache new-children new-measures)))

;; =============================================================================
;; Tree operations (internal, work on hashes)
;; =============================================================================

(declare tree-conj-left tree-conj-right)

(defn- tree-first*
  "Get first element hash from tree."
  [cache root-hash]
  (let [node (lookup-node cache root-hash)]
    (case (node-type node)
      :ft/empty nil
      :ft/leaf root-hash
      :ft/deep (let [left-hash (:left (node-data node))]
                 (digit-first cache left-hash))
      ;; Node acts as single-element in spine
      :ft/node root-hash)))

(defn- tree-last*
  "Get last element hash from tree."
  [cache root-hash]
  (let [node (lookup-node cache root-hash)]
    (case (node-type node)
      :ft/empty nil
      :ft/leaf root-hash
      :ft/deep (let [right-hash (:right (node-data node))]
                 (digit-last cache right-hash))
      :ft/node root-hash)))

(defn- to-tree-from-digit
  "Convert a digit to a tree (for when spine is empty and we need to restructure)."
  [cache digit-hash]
  (let [children (get-children cache digit-hash)]
    (reduce #(tree-conj-right cache %1 %2)
            (commit-empty cache)
            children)))

(defn- tree-rest*
  "Remove first element, return new tree hash."
  [cache root-hash]
  (let [node (lookup-node cache root-hash)]
    (case (node-type node)
      :ft/empty root-hash
      :ft/leaf (commit-empty cache)
      :ft/node (commit-empty cache)
      :ft/deep
      (let [{:keys [left spine right]} (node-data node)]
        (if-let [new-left (digit-rest cache left)]
          ;; Left digit still has elements
          (commit-deep cache new-left spine right
                       (get-measure cache new-left)
                       (get-measure cache spine)
                       (get-measure cache right))
          ;; Left digit exhausted
          (if (empty-node? cache spine)
            ;; Spine empty, convert right to tree
            (to-tree-from-digit cache right)
            ;; Pull node from spine
            (let [spine-first (tree-first* cache spine)
                  new-spine (tree-rest* cache spine)
                  ;; spine-first is a :ft/node, its children become the new left digit
                  node-children (get-children cache spine-first)
                  node-measures (mapv #(get-measure cache %) node-children)
                  new-left (commit-digit cache node-children node-measures)]
              (commit-deep cache new-left new-spine right
                           (get-measure cache new-left)
                           (get-measure cache new-spine)
                           (get-measure cache right)))))))))

(defn- tree-butlast*
  "Remove last element, return new tree hash."
  [cache root-hash]
  (let [node (lookup-node cache root-hash)]
    (case (node-type node)
      :ft/empty root-hash
      :ft/leaf (commit-empty cache)
      :ft/node (commit-empty cache)
      :ft/deep
      (let [{:keys [left spine right]} (node-data node)]
        (if-let [new-right (digit-butlast cache right)]
          ;; Right digit still has elements
          (commit-deep cache left spine new-right
                       (get-measure cache left)
                       (get-measure cache spine)
                       (get-measure cache new-right))
          ;; Right digit exhausted
          (if (empty-node? cache spine)
            ;; Spine empty, convert left to tree
            (to-tree-from-digit cache left)
            ;; Pull node from spine
            (let [spine-last (tree-last* cache spine)
                  new-spine (tree-butlast* cache spine)
                  node-children (get-children cache spine-last)
                  node-measures (mapv #(get-measure cache %) node-children)
                  new-right (commit-digit cache node-children node-measures)]
              (commit-deep cache left new-spine new-right
                           (get-measure cache left)
                           (get-measure cache new-spine)
                           (get-measure cache new-right)))))))))

(defn- tree-conj-left
  "Add element to left of tree, return new tree hash."
  [cache root-hash elem-hash]
  (let [node (lookup-node cache root-hash)]
    (case (node-type node)
      :ft/empty elem-hash

      :ft/leaf
      ;; Single element -> Deep with one element on each side
      (let [left (commit-digit cache [elem-hash] [(get-measure cache elem-hash)])
            spine (commit-empty cache)
            right (commit-digit cache [root-hash] [(get-measure cache root-hash)])]
        (commit-deep cache left spine right
                     (get-measure cache left)
                     (get-measure cache spine)
                     (get-measure cache right)))

      :ft/node
      ;; Node acts like a leaf in spine context
      (let [left (commit-digit cache [elem-hash] [(get-measure cache elem-hash)])
            spine (commit-empty cache)
            right (commit-digit cache [root-hash] [(get-measure cache root-hash)])]
        (commit-deep cache left spine right
                     (get-measure cache left)
                     (get-measure cache spine)
                     (get-measure cache right)))

      :ft/deep
      (let [{:keys [left spine right]} (node-data node)
            left-count (digit-count cache left)]
        (if (< left-count 32)
          ;; Room in left digit
          (let [new-left (digit-conj-left cache left elem-hash)]
            (commit-deep cache new-left spine right
                         (get-measure cache new-left)
                         (get-measure cache spine)
                         (get-measure cache right)))
          ;; Left digit full, push to spine
          (let [left-children (get-children cache left)
                ;; Keep first 8 in new left (including new elem)
                new-left-children (into [elem-hash] (subvec left-children 0 7))
                new-left-measures (mapv #(get-measure cache %) new-left-children)
                new-left (commit-digit cache new-left-children new-left-measures)
                ;; Push remaining 25 as a node to spine
                node-children (subvec left-children 7 32)
                node-measures (mapv #(get-measure cache %) node-children)
                new-node (commit-node cache node-children node-measures)
                new-spine (tree-conj-left cache spine new-node)]
            (commit-deep cache new-left new-spine right
                         (get-measure cache new-left)
                         (get-measure cache new-spine)
                         (get-measure cache right))))))))

(defn- tree-conj-right
  "Add element to right of tree, return new tree hash."
  [cache root-hash elem-hash]
  (let [node (lookup-node cache root-hash)]
    (case (node-type node)
      :ft/empty elem-hash

      :ft/leaf
      (let [left (commit-digit cache [root-hash] [(get-measure cache root-hash)])
            spine (commit-empty cache)
            right (commit-digit cache [elem-hash] [(get-measure cache elem-hash)])]
        (commit-deep cache left spine right
                     (get-measure cache left)
                     (get-measure cache spine)
                     (get-measure cache right)))

      :ft/node
      (let [left (commit-digit cache [root-hash] [(get-measure cache root-hash)])
            spine (commit-empty cache)
            right (commit-digit cache [elem-hash] [(get-measure cache elem-hash)])]
        (commit-deep cache left spine right
                     (get-measure cache left)
                     (get-measure cache spine)
                     (get-measure cache right)))

      :ft/deep
      (let [{:keys [left spine right]} (node-data node)
            right-count (digit-count cache right)]
        (if (< right-count 32)
          ;; Room in right digit
          (let [new-right (digit-conj-right cache right elem-hash)]
            (commit-deep cache left spine new-right
                         (get-measure cache left)
                         (get-measure cache spine)
                         (get-measure cache new-right)))
          ;; Right digit full, push to spine
          (let [right-children (get-children cache right)
                ;; Push first 24 as a node to spine
                node-children (subvec right-children 0 24)
                node-measures (mapv #(get-measure cache %) node-children)
                new-node (commit-node cache node-children node-measures)
                new-spine (tree-conj-right cache spine new-node)
                ;; Keep last 8 + new elem in right
                new-right-children (conj (subvec right-children 24 32) elem-hash)
                new-right-measures (mapv #(get-measure cache %) new-right-children)
                new-right (commit-digit cache new-right-children new-right-measures)]
            (commit-deep cache left new-spine new-right
                         (get-measure cache left)
                         (get-measure cache new-spine)
                         (get-measure cache new-right))))))))

(defn- tree-to-seq*
  "Convert tree to lazy seq of element hashes."
  [cache root-hash]
  (let [node (lookup-node cache root-hash)]
    (case (node-type node)
      :ft/empty []
      :ft/leaf [root-hash]
      :ft/node [root-hash]
      :ft/deep
      (let [{:keys [left spine right]} (node-data node)]
        (concat
         ;; Left digit elements
         (get-children cache left)
         ;; Spine nodes (each node's children)
         (mapcat #(get-children cache %) (tree-to-seq* cache spine))
         ;; Right digit elements
         (get-children cache right))))))

;; =============================================================================
;; Public API - CachedFingerTree record
;; =============================================================================

(defrecord CachedFingerTree [cache root-hash])

(defn finger-tree
  "Create an empty finger tree backed by the given cache manager."
  [cache]
  (->CachedFingerTree cache (commit-empty cache)))

(defn conj-left
  "Add element to the left of the tree.
   value-hash is the hash of a previously committed value."
  [tree value-hash]
  (let [{:keys [cache root-hash]} tree
        size-bytes (cache/value-size cache value-hash)
        leaf-hash (commit-leaf cache value-hash size-bytes)]
    (->CachedFingerTree cache (tree-conj-left cache root-hash leaf-hash))))

(defn conj-right
  "Add element to the right of the tree.
   value-hash is the hash of a previously committed value."
  [tree value-hash]
  (let [{:keys [cache root-hash]} tree
        size-bytes (cache/value-size cache value-hash)
        leaf-hash (commit-leaf cache value-hash size-bytes)]
    (->CachedFingerTree cache (tree-conj-right cache root-hash leaf-hash))))

(defn tree-first
  "Get the first element's value-hash, or nil if empty.
   Use cache/lookup on the returned hash to get the actual value."
  [tree]
  (let [{:keys [cache root-hash]} tree]
    (when-let [leaf-hash (tree-first* cache root-hash)]
      (get-value-hash cache leaf-hash))))

(defn tree-last
  "Get the last element's value-hash, or nil if empty.
   Use cache/lookup on the returned hash to get the actual value."
  [tree]
  (let [{:keys [cache root-hash]} tree]
    (when-let [leaf-hash (tree-last* cache root-hash)]
      (get-value-hash cache leaf-hash))))

(defn tree-rest
  "Remove the first element from the tree."
  [tree]
  (let [{:keys [cache root-hash]} tree]
    (->CachedFingerTree cache (tree-rest* cache root-hash))))

(defn tree-butlast
  "Remove the last element from the tree."
  [tree]
  (let [{:keys [cache root-hash]} tree]
    (->CachedFingerTree cache (tree-butlast* cache root-hash))))

(defn tree-empty?
  "Is the tree empty?"
  [tree]
  (let [{:keys [cache root-hash]} tree]
    (empty-node? cache root-hash)))

(defn tree-count
  "Get the count of elements (O(1) via cached measure)."
  [tree]
  (let [{:keys [cache root-hash]} tree]
    (:count (get-measure cache root-hash))))

(defn tree-size-bytes
  "Get the total size in bytes (O(1) via cached measure)."
  [tree]
  (let [{:keys [cache root-hash]} tree]
    (:size-bytes (get-measure cache root-hash))))

(defn tree-concat
  "Concatenate two trees."
  [tree1 tree2]
  ;; Simple implementation: add all elements of tree2 to tree1
  (let [{:keys [cache]} tree1
        leaf-hashes (tree-to-seq* cache (:root-hash tree2))]
    (reduce (fn [t leaf-hash]
              (let [node (lookup-node cache leaf-hash)
                    {:keys [value-hash]} (node-data node)]
                (conj-right t value-hash)))
            tree1
            leaf-hashes)))

(defn from-seq
  "Build a finger tree from a sequence of value-hashes.
   Each value-hash should be a previously committed value."
  [cache value-hashes]
  (reduce conj-right
          (finger-tree cache)
          value-hashes))

(defn to-vec
  "Convert tree to vector of value-hashes.
   Use cache/lookup on each hash to get actual values."
  [tree]
  (let [{:keys [cache root-hash]} tree]
    (mapv #(get-value-hash cache %) (tree-to-seq* cache root-hash))))

;; =============================================================================
;; REPL examples
;; =============================================================================

(comment
  ;; Create a cache manager
  (def mgr (cache/memory-cache-manager))

  ;; Commit some values first
  (def h1 (cache/commit! mgr [:string "hello"]))
  (def h2 (cache/commit! mgr [:string "world"]))
  (def h3 (cache/commit! mgr [:string "start"]))

  ;; Create an empty tree
  (def t0 (finger-tree mgr))

  (tree-empty? t0)  ;; => true
  (tree-count t0)   ;; => 0

  ;; Add elements using their hashes (size computed automatically)
  (def t1 (conj-right t0 h1))
  (def t2 (conj-right t1 h2))
  (def t3 (conj-left t2 h3))

  (tree-count t3)      ;; => 3
  (tree-size-bytes t3) ;; => 15
  (tree-first t3)      ;; => h3 (hash of "start")
  (tree-last t3)       ;; => h2 (hash of "world")
  (to-vec t3)          ;; => [h3 h1 h2] (vector of hashes)

  ;; Lookup actual values
  (cache/lookup mgr (tree-first t3))  ;; => [:string "start"]

  ;; Remove elements
  (def t4 (tree-rest t3))
  (tree-count t4)      ;; => 2

  (def t5 (tree-butlast t3))
  (tree-count t5)      ;; => 2

  ;; Build from sequence of hashes
  (def hashes (mapv #(cache/commit! mgr [:i64 %]) (range 10)))
  (def t6 (from-seq mgr hashes))
  (tree-count t6)      ;; => 10

  ;; Check cache stats
  (cache/stats mgr)    ;; Shows all committed nodes
  )
