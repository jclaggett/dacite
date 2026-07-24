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
   - [\"ft/single\" {:value-hash h :measure m}]  ; legacy leaf adapter
   - [\"ft/digit\"  {:children [h...] :measure m}]
   - [\"ft/node\"   {:children [h...] :measure m}]
   - [\"ft/deep\"   {:left h :spine h :right h :measure m}]

   Leaf elision: digit/node children and 1-element roots are bare value
   hashes (any non-ft/* entry). Legacy ft/single is still dual-read.
   Structural cells remain ft/empty|digit|node|deep.
   See docs/design/ft-single-elision.md.

   Digits hold 1-32 children and nodes 2-32 — wider than the classic
   finger tree, trading the amortized O(1) proof for shallower trees."
  (:require [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.store :as store]
            [dacite.value.types :as types]
            [clojure.string :as str]))

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

(defn- ft-type?
  "True when type-name is a finger-tree structural (or legacy single) type."
  [type-name]
  (str/starts-with? (str type-name) "ft/"))

(defn- measure-of
  "Measure of an FT cell, legacy ft/single, or implicit leaf (non-ft/*).

   Leaf measure is synthesized: count 1, size-bytes from the entry,
   elements-fuse = the leaf hash itself."
  [store h]
  (let [entry (lookup store h)
        t (node-type entry)]
    (if (ft-type? t)
      (:measure (node-data entry))
      {:count 1
       :size-bytes (types/dacite-size entry)
       :elements-fuse h})))

(defn- as-leaf-hash
  "Resolve a leaf value hash: unwrap legacy ft/single, identity for non-ft
   leaves. Throws if h names a structural FT cell (empty/digit/node/deep)."
  [store h]
  (let [entry (lookup store h)
        t (node-type entry)]
    (case t
      "ft/single" (:value-hash (node-data entry))
      ("ft/empty" "ft/digit" "ft/node" "ft/deep")
      (throw (ex-info "expected leaf or ft/single"
                      {:type t :hash h}))
      ;; non-ft/* (and any future non-structural name): already a leaf
      h)))

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
        (make-digit! store nc (mapv #(measure-of store %) nc))))))

(defn- digit-butlast!
  "Drop the last child. Returns the new digit hash, or nil if it would
   become empty."
  [store dh]
  (let [children (get-children store dh)]
    (when (> (count children) 1)
      (let [nc (pop children)]
        (make-digit! store nc (mapv #(measure-of store %) nc))))))

(defn- digit-conj-left! [store dh elem]
  (let [nc (into [elem] (get-children store dh))]
    (make-digit! store nc (mapv #(measure-of store %) nc))))

(defn- digit-conj-right! [store dh elem]
  (let [nc (conj (get-children store dh) elem)]
    (make-digit! store nc (mapv #(measure-of store %) nc))))

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
                      (measure-of store new-left)
                      (measure-of store spine)
                      (measure-of store right))
          (if (empty-node? store spine)
            (to-tree-from-digit! store right)
            (let [spine-first (tree-first* store spine)
                  new-spine (tree-rest* store spine)
                  nch (get-children store spine-first)
                  new-left' (make-digit! store nch (mapv #(measure-of store %) nch))]
              (make-deep! store new-left' new-spine right
                          (measure-of store new-left')
                          (measure-of store new-spine)
                          (measure-of store right))))))
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
                      (measure-of store left)
                      (measure-of store spine)
                      (measure-of store new-right))
          (if (empty-node? store spine)
            (to-tree-from-digit! store left)
            (let [spine-last (tree-last* store spine)
                  new-spine (tree-butlast* store spine)
                  nch (get-children store spine-last)
                  new-right' (make-digit! store nch (mapv #(measure-of store %) nch))]
              (make-deep! store left new-spine new-right'
                          (measure-of store left)
                          (measure-of store new-spine)
                          (measure-of store new-right'))))))
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
                        (measure-of store new-left)
                        (measure-of store spine)
                        (measure-of store right)))
          (let [lc (get-children store left)
                new-left-children (into [elem] (subvec lc 0 7))
                new-left (make-digit! store new-left-children
                                      (mapv #(measure-of store %) new-left-children))
                node-children (subvec lc 7 32)
                new-node (make-node! store node-children
                                     (mapv #(measure-of store %) node-children))
                new-spine (tree-conj-left! store spine new-node)]
            (make-deep! store new-left new-spine right
                        (measure-of store new-left)
                        (measure-of store new-spine)
                        (measure-of store right)))))
      (let [left (make-digit! store [elem] [(measure-of store elem)])
            spine (make-empty! store)
            right (make-digit! store [root] [(measure-of store root)])]
        (make-deep! store left spine right
                    (measure-of store left)
                    (measure-of store spine)
                    (measure-of store right))))))

(defn- tree-conj-right! [store root elem]
  (let [node (lookup store root)]
    (case (node-type node)
      "ft/empty" elem
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)]
        (if (< (digit-count store right) 32)
          (let [new-right (digit-conj-right! store right elem)]
            (make-deep! store left spine new-right
                        (measure-of store left)
                        (measure-of store spine)
                        (measure-of store new-right)))
          (let [rc (get-children store right)
                node-children (subvec rc 0 24)
                new-node (make-node! store node-children
                                     (mapv #(measure-of store %) node-children))
                new-spine (tree-conj-right! store spine new-node)
                new-right-children (conj (subvec rc 24 32) elem)
                new-right (make-digit! store new-right-children
                                       (mapv #(measure-of store %) new-right-children))]
            (make-deep! store left new-spine new-right
                        (measure-of store left)
                        (measure-of store new-spine)
                        (measure-of store new-right)))))
      (let [left (make-digit! store [root] [(measure-of store root)])
            spine (make-empty! store)
            right (make-digit! store [elem] [(measure-of store elem)])]
        (make-deep! store left spine right
                    (measure-of store left)
                    (measure-of store spine)
                    (measure-of store right))))))

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
          c-count (:count (measure-of store c))]
      (if (< remaining c-count)
        (let [t (node-type (lookup store c))]
          (case t
            "ft/single" (get-value-hash store c)
            "ft/node" (recur (seq (get-children store c)) remaining)
            "ft/digit" (recur (seq (get-children store c)) remaining)
            ;; bare leaf (non-ft/*) or unexpected — treat as 1-elem leaf
            (as-leaf-hash store c)))
        (recur (next cs) (- remaining c-count))))))

(defn- tree-nth* [store root idx]
  (let [node (lookup store root)
        t (node-type node)]
    (case t
      "ft/single" (get-value-hash store root)
      "ft/node" (scan-children store (get-children store root) idx)
      "ft/digit" (scan-children store (get-children store root) idx)
      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            left-count (:count (measure-of store left))]
        (if (< idx left-count)
          (scan-children store (get-children store left) idx)
          (let [spine-count (:count (measure-of store spine))
                spine-idx (- idx left-count)]
            (if (< spine-idx spine-count)
              (tree-nth* store spine spine-idx)
              (scan-children store (get-children store right)
                             (- spine-idx spine-count))))))
      ;; bare leaf as 1-element tree root
      (if (zero? idx)
        (as-leaf-hash store root)
        (throw (ex-info "Index out of range for leaf root"
                        {:index idx :type t}))))))

;; =============================================================================
;; Remove at index (structural)
;; =============================================================================

(declare tree-remove-nth*)

(defn- remove-at-children!
  "Remove the leaf at local index idx from a vector of child node hashes.
   Returns a vector of remaining/replaced child hashes (may be empty)."
  [store children idx]
  (loop [i 0 remaining idx]
    (when (>= i (count children))
      (throw (ex-info "Index out of range while removing from children"
                      {:index idx :child-count (count children)})))
    (let [c (nth children i)
          c-count (:count (measure-of store c))]
      (if (< remaining c-count)
        (let [c' (tree-remove-nth* store c remaining)
              left (subvec children 0 i)
              right (subvec children (inc i))]
          (if c'
            (into (conj left c') right)
            (into left right)))
        (recur (inc i) (- remaining c-count))))))

(defn- tree-remove-nth*
  "Remove the leaf at idx under root. Returns the new root hash, or nil when
   the subtree becomes empty (caller promotes or rebalances)."
  [store root idx]
  (let [node (lookup store root)
        t (node-type node)]
    (case t
      "ft/empty"
      (throw (ex-info "Cannot remove from empty tree" {:index idx}))

      "ft/single"
      (if (zero? idx)
        nil
        (throw (ex-info "Index out of range for single"
                        {:index idx})))

      "ft/digit"
      (let [nc (remove-at-children! store (get-children store root) idx)]
        (when (seq nc)
          (make-digit! store nc (mapv #(measure-of store %) nc))))

      "ft/node"
      (let [nc (remove-at-children! store (get-children store root) idx)]
        (case (count nc)
          0 nil
          ;; Nodes require 2–32 children; promote a lone survivor.
          1 (first nc)
          (make-node! store nc (mapv #(measure-of store %) nc))))

      "ft/deep"
      (let [{:keys [left spine right]} (node-data node)
            left-m (measure-of store left)
            spine-m (measure-of store spine)
            right-m (measure-of store right)
            left-count (:count left-m)
            spine-count (:count spine-m)]
        (cond
          (< idx left-count)
          (let [nc (remove-at-children! store (get-children store left) idx)]
            (if (seq nc)
              (let [new-left (make-digit! store nc (mapv #(measure-of store %) nc))]
                (make-deep! store new-left spine right
                            (measure-of store new-left) spine-m right-m))
              (if (empty-node? store spine)
                (to-tree-from-digit! store right)
                (let [spine-first (tree-first* store spine)
                      new-spine (tree-rest* store spine)
                      nch (get-children store spine-first)
                      new-left' (make-digit! store nch
                                             (mapv #(measure-of store %) nch))]
                  (make-deep! store new-left' new-spine right
                              (measure-of store new-left')
                              (measure-of store new-spine)
                              right-m)))))

          (< idx (+ left-count spine-count))
          (let [spine-idx (- idx left-count)
                new-spine (tree-remove-nth* store spine spine-idx)]
            (if new-spine
              (make-deep! store left new-spine right
                          left-m (measure-of store new-spine) right-m)
              (make-deep! store left (make-empty! store) right
                          left-m measure-identity right-m)))

          :else
          (let [right-idx (- idx left-count spine-count)
                nc (remove-at-children! store (get-children store right) right-idx)]
            (if (seq nc)
              (let [new-right (make-digit! store nc (mapv #(measure-of store %) nc))]
                (make-deep! store left spine new-right
                            left-m spine-m (measure-of store new-right)))
              (if (empty-node? store spine)
                (to-tree-from-digit! store left)
                (let [spine-last (tree-last* store spine)
                      new-spine (tree-butlast* store spine)
                      nch (get-children store spine-last)
                      new-right' (make-digit! store nch
                                              (mapv #(measure-of store %) nch))]
                  (make-deep! store left new-spine new-right'
                              left-m
                              (measure-of store new-spine)
                              (measure-of store new-right'))))))))

      ;; bare leaf as 1-element tree root
      (if (zero? idx)
        nil
        (throw (ex-info "Index out of range for leaf root"
                        {:index idx :type t}))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn ft-empty
  "Create an empty finger tree in the store. Returns its root hash."
  [store]
  (make-empty! store))

(defn ft-conj-right
  "Append a value (by its hash, already in the store) to the right end.
   Returns the new root hash. Stores the leaf hash directly (no ft/single)."
  [store root value-hash]
  (tree-conj-right! store root value-hash))

(defn ft-conj-left
  "Prepend a value (by its hash, already in the store) to the left end.
   Returns the new root hash. Stores the leaf hash directly (no ft/single)."
  [store root value-hash]
  (tree-conj-left! store root value-hash))

(defn ft-first
  "Hash of the first element, or nil if empty."
  [store root]
  (when-let [s (tree-first* store root)]
    (as-leaf-hash store s)))

(defn ft-last
  "Hash of the last element, or nil if empty."
  [store root]
  (when-let [s (tree-last* store root)]
    (as-leaf-hash store s)))

(defn ft-rest
  "Remove the first element. Returns the new root hash."
  [store root]
  (tree-rest* store root))

(defn ft-butlast
  "Remove the last element. Returns the new root hash."
  [store root]
  (tree-butlast* store root))

(defn ft-empty? [store root]
  (let [entry (lookup store root)]
    (and entry (= "ft/empty" (node-type entry)))))

(defn ft-measure [store root]
  (measure-of store root))

(defn ft-count
  "Number of elements, O(1) via cached measure (or synthesized for bare leaf roots)."
  [store root]
  (:count (measure-of store root)))

(defn ft-size-bytes
  "Total leaf byte size, O(1) via cached measure (or synthesized for bare leaf roots)."
  [store root]
  (:size-bytes (measure-of store root)))

(defn ft-elements-fuse
  "The seq's data hash: the running fuse of all element hashes, O(1)."
  [store root]
  (:elements-fuse (measure-of store root)))

(defn ft-nth
  "Hash of the element at idx (0-indexed), O(log n). Throws if out of range."
  [store root idx]
  (let [cnt (:count (measure-of store root))]
    (when (or (neg? idx) (>= idx cnt))
      (throw (ex-info (str "Index " idx " out of bounds for count " cnt)
                      {:index idx :count cnt})))
    (tree-nth* store root idx)))

(defn ft-remove-nth
  "Remove the element at idx (0-indexed). Returns the new root hash.
   Structural O(log n) update. Throws if out of range."
  [store root idx]
  (let [cnt (:count (measure-of store root))]
    (when (or (neg? idx) (>= idx cnt))
      (throw (ex-info (str "Index " idx " out of bounds for count " cnt)
                      {:index idx :count cnt})))
    (or (tree-remove-nth* store root idx)
        (make-empty! store))))

(defn ft-seq
  "Lazy sequence of element value hashes under a tree root
   (empty / single / bare leaf / deep)."
  [store root]
  (map #(as-leaf-hash store %) (tree-to-seq* store root)))

(defn ft-leaves
  "Ordered leaf value hashes under any FT node type (including bare digit/node)
   or a bare leaf hash (implicit single).

   Unlike ft-seq (tree roots only), this walks digit and node cells so pack
   intermediate literals can realize their full leaf payload."
  [store h]
  (let [node (lookup store h)
        t (node-type node)]
    (case t
      "ft/empty" []
      "ft/single" [(:value-hash (node-data node))]
      "ft/digit" (mapcat #(ft-leaves store %) (:children (node-data node)))
      "ft/node" (mapcat #(ft-leaves store %) (:children (node-data node)))
      "ft/deep" (ft-seq store h)
      ;; non-ft/*: already a leaf value
      [h])))

(defn ft-from-value-hashes
  "Build a finger-tree root by conj-right of the given leaf value hashes
   (already in store). Same construction path as sequence collections.

   Used for intermediate ft/deep (and similar) packing: the resulting root
   hash is fuse(type, elements_fuse) and matches a sender node when types
   and leaf multiset agree."
  [store value-hashes]
  (reduce (fn [root vh] (ft-conj-right store root vh))
          (ft-empty store)
          value-hashes))

(defn ft-digit-from-value-hashes
  "Build an ft/digit whose children are bare leaf value hashes.

   Matches intermediate digit nodes under the leaf-elision encoding.
   Dual-read still accepts legacy digits of ft/singles."
  [store value-hashes]
  (let [vhs (vec value-hashes)]
    (make-digit! store vhs (mapv #(measure-of store %) vhs))))

(defn ft-single-from-value-hash
  "Build a legacy ft/single wrapping one leaf value hash.

   Retained for dual-read tests and materializing old pack payloads.
   New trees do not write singles."
  [store value-hash]
  (make-single! store value-hash (types/dacite-size (lookup store value-hash))))

(defn ft-concat
  "Concatenate two trees in the same store. Returns the new root hash."
  [store root-a root-b]
  (reduce (fn [h elem]
            (ft-conj-right store h (as-leaf-hash store elem)))
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
