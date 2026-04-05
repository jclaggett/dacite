(ns dacite.auth
  "Authorization for Dacite stores.

   Implements proof of possession and root transition verification for
   the Dacite authorization model.

   Proof chains are ordered sequences of hashes [root, h1, ..., target]
   proving structural reachability from root to target through the DAG.

   Root transitions use a DFS walk protocol: the client proves each hash
   in the new root's tree via either a proof chain (structural possession)
   or raw data (data possession). The server validates proofs as a stream.

   The child-hashes multimethod is defined in dacite.types and extended
   by dacite.finger-tree and dacite.hamt for their respective node types.

   Key functions:
   - build-proof-chain: find a path from root to target, return as hash vector
   - verify-proof-chain: verify that each link in a chain is valid
   - dedicated-store: create a store containing only specific nodes
   - validate-proof: verify a single proof (chain or data) for one hash
   - verify-transition: DFS walk of new root, validating proofs from a prover fn"
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
              children (types/child-hashes node)
              child-set (set children)]
          (if (contains? child-set target-hash)
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
          (if (and node (some #{child-hash} (types/child-hashes node)))
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

;; =============================================================================
;; Proof validation
;; =============================================================================

(defn validate-proof
  "Validate a single proof for a hash. Returns the stored node value on
   success, or nil on failure.

   proof is one of:
   - {:chain [root ... target]}  — structural possession; chain's last
     element must equal hash, root must be in valid-roots, and chain
     must verify against the store.
   - {:data value}               — data possession; the value must
     already be stored at hash (i.e., its serialized form hashes to
     the expected hash). The store is assumed to enforce this on put.

   valid-roots is a set of hashes accepted as proof chain roots.
   In Layer 4 this is #{user-root}; Layer 5 adds share roots."
  [store valid-roots hash proof]
  (case (:type proof)
    :chain
    (let [chain (:chain proof)]
      (when (and (seq chain)
                 (= hash (last chain))
                 (contains? valid-roots (first chain))
                 (verify-proof-chain store chain))
        (store/s-get store hash)))

    :data
    (let [value (:value proof)]
      ;; Store the value — the store is content-addressed so put is
      ;; only valid if the value hashes to the expected hash.
      ;; Caller (service layer) should verify hash matches before
      ;; calling, or the store should enforce it.
      (store/s-put store hash value)
      value)

    ;; Unknown proof type
    nil))

;; =============================================================================
;; Root transition verification
;; =============================================================================

(defn verify-transition
  "Verify a root transition from old-root to new-root using a prover function.

   Walks the new root's tree in DFS order. For each hash:
   - If already in the store, skip (server has it).
   - Otherwise, call (prover hash) to get a proof, then validate it.

   prover is (fn [hash] -> {:type :chain, :chain [...]} | {:type :data, :value ...})

   valid-roots is a set of hashes accepted as proof chain anchors.

   Returns {:valid? true,  :new-nodes {hash value, ...}} on success,
   or       {:valid? false, :failed-hash hash, :reason string} on failure."
  [store valid-roots new-root prover]
  (loop [stack [new-root]
         new-nodes {}]
    (if (empty? stack)
      {:valid? true :new-nodes new-nodes}
      (let [hash (peek stack)
            stack' (pop stack)]
        (if (or (store/s-has? store hash)
                (contains? new-nodes hash))
          ;; Already known — skip
          (recur stack' new-nodes)
          ;; Need proof
          (let [proof (prover hash)
                node (validate-proof store valid-roots hash proof)]
            (if (nil? node)
              {:valid? false :failed-hash hash :reason "invalid proof"}
              ;; Push children onto stack in reverse order so first child
              ;; is on top (DFS left-to-right)
              (let [children (types/child-hashes node)]
                (recur (into stack' (rseq (vec children)))
                       (assoc new-nodes hash node))))))))))

(defn apply-transition
  "Verify and apply a root transition. On success, merges new nodes into
   the store and returns {:valid? true, :new-nodes ...}.
   On failure, returns the error map without modifying the store.

   This is a convenience over verify-transition — the service layer
   should call this and then update its own root pointer."
  [store valid-roots new-root prover]
  (let [result (verify-transition store valid-roots new-root prover)]
    (when (:valid? result)
      (doseq [[h v] (:new-nodes result)]
        (store/s-put store h v)))
    result))
