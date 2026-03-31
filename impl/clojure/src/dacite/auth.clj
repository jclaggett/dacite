(ns dacite.auth
  "Authorization for Dacite stores.

   Implements proof chain construction and verification for the Dacite
   authorization model. A proof chain is a sequence of hashes
   [root, h1, h2, ..., target] proving that the target is reachable
   from the root through the DAG structure.

   The child-hashes multimethod is defined in dacite.types and extended
   by dacite.finger-tree and dacite.hamt for their respective node types.

   Key functions:
   - build-proof-chain: find a path from root to target, return as hash vector
   - verify-proof-chain: verify that each link in a chain is valid
   - dedicated-store: create a store containing only specific nodes"
  (:require [dacite.store :as store]
            [dacite.value.types :as types]
            ;; Require these to register child-hashes implementations
            [dacite.value.finger-tree]
            [dacite.value.hamt]))

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
              children (types/child-hashes node)]
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
          (if (and node (contains? (types/child-hashes node) child-hash))
            (recur (next remaining))
            false))))))

;; =============================================================================
;; Dedicated store
;; =============================================================================

(defn dedicated-store
  "Create a new mem-store containing only the nodes along a proof chain.
   The source store is used to look up each hash in the chain.
   Returns the new store.

   Useful for creating scoped stores that expose a limited subset of
   data to a peer (e.g., proof chain nodes for a specific request)."
  [source-store chain]
  (let [ds (store/mem-store)]
    (doseq [h chain]
      (when-let [v (store/s-get source-store h)]
        (store/s-put ds h v)))
    ds))
