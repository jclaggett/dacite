(ns dacite.value.finger-tree
  "Store-aware finger tree for the value layer.

   This is the value refactor's core difference from the original value
   layer: instead of threading a pure {hash -> node} map and merging the
   result back into a global cache, every operation reads and writes nodes
   directly through the value's own IStore via s-get / s-put.

   Nodes are content-addressed: a node's hash is fuse(type_hash,
   elements_fuse), independent of tree shape (§3.6). Because the store is
   mutated in place, operations return only the new root hash.

   Node types (stored as [type-name data]):
   - [\"ft/empty\"  {:measure m}]
   - [\"ft/single\" {:value-hash h :measure m}]
   - [\"ft/digit\"  {:children [h...] :measure m}]
   - [\"ft/node\"   {:children [h...] :measure m}]
   - [\"ft/deep\"   {:left h :spine h :right h :measure m}]

   Digits hold 1-32 children and nodes 2-32 — wider than the classic
   finger tree, trading the amortized O(1) proof for shallower trees."
  (:require [dacite.hash :as hash]
            [dacite.store :as store]
            [dacite.value.types :as types]))

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
;; Node helpers (store-backed)
;; =============================================================================

(defn- add-node!
  "Persist an internal node, returning its content hash. The hash is
   derived from the node type and its elements_fuse, so equal logical
   nodes normalize to one entry regardless of tree shape."
  [store node]
  (let [type-name (first node)
        ef (:elements-fuse (:measure (second node)))
        h (types/node-hash type-name ef)]
    (store/s-put store h node)
    h))

(defn- lookup [store h] (store/s-get store h))
(defn- node-type [node] (first node))
(defn- node-data [node] (second node))
(defn- get-measure [store h] (:measure (node-data (lookup store h))))
(defn- get-children [store h] (:children (node-data (lookup store h))))
(defn- get-value-hash [store h] (:value-hash (node-data (lookup store h))))

;; =============================================================================
;; Node constructors (persist, return hash)
;; =============================================================================

(defn- make-empty! [store]
  (add-node! store ["ft/empty" {:measure measure-identity}]))

(defn- make-single! [store value-hash size-bytes]
  (add-node! store ["ft/single" {:value-hash value-hash
                                 :measure {:count 1
                                           :size-bytes size-bytes
                                           :elements-fuse value-hash}}]))

(defn- make-digit! [store child-hashes child-measures]
  (add-node! store ["ft/digit" {:children (vec child-hashes)
                                :measure (measure-seq child-measures)}]))

(defn- make-node! [store child-hashes child-measures]
  {:pre [(<= 2 (count child-hashes) 32)]}
  (add-node! store ["ft/node" {:children (vec child-hashes)
                               :measure (measure-seq child-measures)}]))

(defn- make-deep! [store left spine right left-m spine-m right-m]
  (add-node! store ["ft/deep" {:left left
                               :spine spine
                               :right right
                               :measure (measure-combine
                                         (measure-combine left-m spine-m)
                                         right-m)}]))

(defn- empty-node? [store h]
  (= "ft/empty" (node-type (lookup store h))))

;; =============================================================================
;; Digit operations
;; =============================================================================

(defn- digit-first [store dh] (first (get-children store dh)))
(defn- digit-last [store dh] (peek (get-children store dh)))
(defn- digit-count [store dh] (count (get-children store dh)))

(defn- digit-rest!
  "Drop the first child. Returns the new digit hash, or nil if it would
   become empty."
  [store dh]
  (let [children (get-children store dh)]
    (when (> (count children) 1)
      (let [nc (subvec children 1)]
        (make-digit! store nc (mapv #(get-measure store %) nc))))))

(defn- digit-butlast!
  "Drop the last child. Returns the new digit hash, or nil if it would
   become empty."
  [store dh]
  (let [children (get-children store dh)]
    (when (> (count children) 1)
      (let [nc (pop children)]
        (make-digit! store nc (mapv #(get-measure store %) nc))))))

(defn- digit-conj-left! [store dh elem]
  (let [nc (into [elem] (get-children store dh))]
    (make-digit! store nc (mapv #(get-measure store %) nc))))

(defn- digit-conj-right! [store dh elem]
  (let [nc (conj (get-children store dh) elem)]
    (make-digit! store nc (mapv #(get-measure store %) nc))))

;; =============================================================================
;; Tree operations (internal — operate on element/single hashes)
;; =============================================================================

(declare tree-conj-left! tree-conj-right!)

(defn- tree-first* [store root]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" nil
      "ft/deep" (digit-first store (:left (node-data node)))
      root)))

(defn- tree-last* [store root]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" nil
      "ft/deep" (digit-last store (:right (node-data node)))
      root)))

(defn- to-tree-from-digit!
  "Rebuild a tree from a digit's children."
  [store dh]
  (reduce (fn [h child] (tree-conj-right! store h child))
          (make-empty! store)
          (get-children store dh)))

(defn- tree-rest* [store root]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" root
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            new-left (digit-rest! store left)]
        (if new-left
          (make-deep! store new-left spine right
                      (get-measure store new-left)
                      (get-measure store spine)
                      (get-measure store right))
          (if (empty-node? store spine)
            (to-tree-from-digit! store right)
            (let [spine-first (tree-first* store spine)
                  new-spine (tree-rest* store spine)
                  nch (get-children store spine-first)
                  new-left' (make-digit! store nch (mapv #(get-measure store %) nch))]
              (make-deep! store new-left' new-spine right
                          (get-measure store new-left')
                          (get-measure store new-spine)
                          (get-measure store right))))))
      (make-empty! store))))

(defn- tree-butlast* [store root]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" root
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            new-right (digit-butlast! store right)]
        (if new-right
          (make-deep! store left spine new-right
                      (get-measure store left)
                      (get-measure store spine)
                      (get-measure store new-right))
          (if (empty-node? store spine)
            (to-tree-from-digit! store left)
            (let [spine-last (tree-last* store spine)
                  new-spine (tree-butlast* store spine)
                  nch (get-children store spine-last)
                  new-right' (make-digit! store nch (mapv #(get-measure store %) nch))]
              (make-deep! store left new-spine new-right'
                          (get-measure store left)
                          (get-measure store new-spine)
                          (get-measure store new-right'))))))
      (make-empty! store))))

(defn- tree-conj-left! [store root elem]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" elem
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)]
        (if (< (digit-count store left) 32)
          (let [new-left (digit-conj-left! store left elem)]
            (make-deep! store new-left spine right
                        (get-measure store new-left)
                        (get-measure store spine)
                        (get-measure store right)))
          (let [lc (get-children store left)
                new-left-children (into [elem] (subvec lc 0 7))
                new-left (make-digit! store new-left-children
                                      (mapv #(get-measure store %) new-left-children))
                node-children (subvec lc 7 32)
                new-node (make-node! store node-children
                                     (mapv #(get-measure store %) node-children))
                new-spine (tree-conj-left! store spine new-node)]
            (make-deep! store new-left new-spine right
                        (get-measure store new-left)
                        (get-measure store new-spine)
                        (get-measure store right)))))
      (let [left (make-digit! store [elem] [(get-measure store elem)])
            spine (make-empty! store)
            right (make-digit! store [root] [(get-measure store root)])]
        (make-deep! store left spine right
                    (get-measure store left)
                    (get-measure store spine)
                    (get-measure store right))))))

(defn- tree-conj-right! [store root elem]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" elem
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)]
        (if (< (digit-count store right) 32)
          (let [new-right (digit-conj-right! store right elem)]
            (make-deep! store left spine new-right
                        (get-measure store left)
                        (get-measure store spine)
                        (get-measure store new-right)))
          (let [rc (get-children store right)
                node-children (subvec rc 0 24)
                new-node (make-node! store node-children
                                     (mapv #(get-measure store %) node-children))
                new-spine (tree-conj-right! store spine new-node)
                new-right-children (conj (subvec rc 24 32) elem)
                new-right (make-digit! store new-right-children
                                       (mapv #(get-measure store %) new-right-children))]
            (make-deep! store left new-spine new-right
                        (get-measure store left)
                        (get-measure store new-spine)
                        (get-measure store new-right)))))
      (let [left (make-digit! store [root] [(get-measure store root)])
            spine (make-empty! store)
            right (make-digit! store [elem] [(get-measure store elem)])]
        (make-deep! store left spine right
                    (get-measure store left)
                    (get-measure store spine)
                    (get-measure store right))))))

(defn- tree-to-seq*
  "Sequence of element/single hashes in order."
  [store root]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" []
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)]
        (concat
         (get-children store left)
         (mapcat #(get-children store %) (tree-to-seq* store spine))
         (get-children store right)))
      [root])))

(defn- scan-children [store children idx]
  (loop [cs (seq children) remaining idx]
    (let [c (first cs)
          c-count (:count (get-measure store c))]
      (if (< remaining c-count)
        (case (node-type (lookup store c))
          "ft/single" (get-value-hash store c)
          "ft/node" (recur (seq (get-children store c)) remaining)
          "ft/digit" (recur (seq (get-children store c)) remaining))
        (recur (next cs) (- remaining c-count))))))

(defn- tree-nth* [store root idx]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/single" (get-value-hash store root)
      "ft/node" (scan-children store (get-children store root) idx)
      "ft/digit" (scan-children store (get-children store root) idx)
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            left-count (:count (get-measure store left))]
        (if (< idx left-count)
          (scan-children store (get-children store left) idx)
          (let [spine-count (:count (get-measure store spine))
                spine-idx (- idx left-count)]
            (if (< spine-idx spine-count)
              (tree-nth* store spine spine-idx)
              (scan-children store (get-children store right)
                             (- spine-idx spine-count)))))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn ft-empty
  "Create an empty finger tree in the store. Returns its root hash."
  [store]
  (make-empty! store))

(defn ft-conj-right
  "Append a value (by its hash, already in the store) to the right end.
   Returns the new root hash."
  [store root value-hash]
  (let [sb (types/dacite-size (lookup store value-hash))
        single (make-single! store value-hash sb)]
    (tree-conj-right! store root single)))

(defn ft-conj-left
  "Prepend a value (by its hash, already in the store) to the left end.
   Returns the new root hash."
  [store root value-hash]
  (let [sb (types/dacite-size (lookup store value-hash))
        single (make-single! store value-hash sb)]
    (tree-conj-left! store root single)))

(defn ft-first
  "Hash of the first element, or nil if empty."
  [store root]
  (when-let [s (tree-first* store root)]
    (get-value-hash store s)))

(defn ft-last
  "Hash of the last element, or nil if empty."
  [store root]
  (when-let [s (tree-last* store root)]
    (get-value-hash store s)))

(defn ft-rest
  "Remove the first element. Returns the new root hash."
  [store root]
  (tree-rest* store root))

(defn ft-butlast
  "Remove the last element. Returns the new root hash."
  [store root]
  (tree-butlast* store root))

(defn ft-empty? [store root]
  (empty-node? store root))

(defn ft-measure [store root]
  (get-measure store root))

(defn ft-count
  "Number of elements, O(1) via cached measure."
  [store root]
  (:count (get-measure store root)))

(defn ft-size-bytes
  "Total leaf byte size, O(1) via cached measure."
  [store root]
  (:size-bytes (get-measure store root)))

(defn ft-elements-fuse
  "The seq's data hash: the running fuse of all element hashes, O(1)."
  [store root]
  (:elements-fuse (get-measure store root)))

(defn ft-nth
  "Hash of the element at idx (0-indexed), O(log n). Throws if out of range."
  [store root idx]
  (let [cnt (:count (get-measure store root))]
    (when (or (neg? idx) (>= idx cnt))
      (throw (IndexOutOfBoundsException.
              (str "Index " idx " out of bounds for count " cnt))))
    (tree-nth* store root idx)))

(defn ft-seq
  "Lazy sequence of element value hashes."
  [store root]
  (map #(get-value-hash store %) (tree-to-seq* store root)))

(defn ft-concat
  "Concatenate two trees in the same store. Returns the new root hash."
  [store root-a root-b]
  (reduce (fn [h single]
            (let [vh (:value-hash (node-data (lookup store single)))]
              (ft-conj-right store h vh)))
          root-a
          (tree-to-seq* store root-b)))

;; =============================================================================
;; child-hashes implementations for finger tree node types
;; =============================================================================

(defmethod types/child-hashes "ft/empty" [_] [])

(defmethod types/child-hashes "ft/single" [[_ data]]
  [(:value-hash data)])

(defmethod types/child-hashes "ft/digit" [[_ data]]
  (:children data))

(defmethod types/child-hashes "ft/node" [[_ data]]
  (:children data))

(defmethod types/child-hashes "ft/deep" [[_ data]]
  [(:left data) (:spine data) (:right data)])
