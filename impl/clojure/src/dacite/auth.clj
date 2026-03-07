(ns dacite.auth
  "Authorization for Dacite stores.

   Implements proof chain construction and verification for the Dacite
   authorization model. A proof chain is a sequence of hashes
   [root, h1, h2, ..., target] proving that the target is reachable
   from the root through the DAG structure.

   Key functions:
   - child-hashes: extract all child hash references from a stored node
   - build-proof-chain: find a path from root to target, return as hash vector
   - verify-proof-chain: verify that each link in a chain is valid
   - dedicated-store: create a store containing only the nodes for a proof chain"
  (:require [dacite.store :as store]))

;; =============================================================================
;; Node child extraction
;; =============================================================================

(defn child-hashes
  "Given a stored node value (as retrieved by s-get), return all child hashes
   directly referenced by this node. Returns a set of hashes.

   Node types and their children:
   - Scalars (i64, char, etc.): no children
   - Collections (vector, string, blob, map): :root hash
   - ft/single: :value-hash
   - ft/digit: :children vector
   - ft/deep: :left, :spine, :right
   - hamt/empty: no children
   - hamt/entry: :key-ref, :val-ref
   - hamt/bitmap: :children vector"
  [node]
  (when (and (vector? node) (= 2 (count node)))
    (let [[type-tag data] node]
      (case type-tag
        ;; Collection roots -> internal tree root
        ("vector" "string" "blob" "map")
        #{(:root data)}

        ;; Finger tree nodes
        "ft/single"
        #{(:value-hash data)}

        "ft/digit"
        (set (:children data))

        "ft/deep"
        #{(:left data) (:spine data) (:right data)}

        ;; HAMT nodes
        "hamt/empty"
        #{}

        "hamt/entry"
        #{(:key-ref data) (:val-ref data)}

        "hamt/bitmap"
        (set (:children data))

        ;; Scalars and unknown types have no children
        #{}))))

;; =============================================================================
;; Proof chain construction
;; =============================================================================

(defn build-proof-chain
  "Given a store, root hash, and target hash, find a path from root to target.
   Returns a vector of hashes [root, h1, h2, ..., target] or nil if no path exists.
   Uses breadth-first search."
  [store root-hash target-hash]
  (if (= root-hash target-hash)
    [root-hash]
    (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [root-hash [root-hash]])
           visited #{root-hash}]
      (when (seq queue)
        (let [[current path] (peek queue)
              queue' (pop queue)
              node (store/s-get store current)
              children (child-hashes node)]
          (if (contains? children target-hash)
            (conj path target-hash)
            (let [unvisited (remove visited children)
                  new-visited (into visited unvisited)
                  new-entries (mapv (fn [h] [h (conj path h)]) unvisited)]
              (recur (into queue' new-entries) new-visited))))))))

;; =============================================================================
;; Proof chain verification
;; =============================================================================

(defn verify-proof-chain
  "Given a store and a proof chain (vector of hashes), verify that each hash
   in the chain is a direct child of the previous hash's stored node.
   Returns true if the chain is valid, false otherwise.

   The chain must have at least one element (the root). A single-element
   chain is trivially valid."
  [store chain]
  (cond
    (empty? chain) false
    (= 1 (count chain)) (some? (store/s-get store (first chain)))
    :else
    (loop [remaining (seq chain)]
      (if (nil? (next remaining))
        true
        (let [parent-hash (first remaining)
              child-hash (second remaining)
              node (store/s-get store parent-hash)]
          (if (and node (contains? (child-hashes node) child-hash))
            (recur (next remaining))
            false))))))

;; =============================================================================
;; Dedicated store
;; =============================================================================

(defn dedicated-store
  "Create a new mem-store containing only the nodes along a proof chain.
   The source store is used to look up each hash in the chain.
   Returns the new store."
  [source-store chain]
  (let [ds (store/mem-store)]
    (doseq [h chain]
      (when-let [v (store/s-get source-store h)]
        (store/s-put ds h v)))
    ds))

(defn dedicated-store-for-subtree
  "Create a new mem-store containing all nodes reachable from root-hash
   in the source store. Used for write operations where the client needs
   to expose a new subtree to the server.
   Returns the new store."
  [source-store root-hash]
  (let [ds (store/mem-store)]
    (loop [queue [root-hash]
           visited #{}]
      (if (empty? queue)
        ds
        (let [h (first queue)
              rest-queue (rest queue)]
          (if (visited h)
            (recur rest-queue visited)
            (if-let [v (store/s-get source-store h)]
              (do
                (store/s-put ds h v)
                (let [children (child-hashes v)]
                  (recur (into (vec rest-queue) (remove visited children))
                         (conj visited h))))
              (recur rest-queue (conj visited h)))))))))
