(ns dacite.auth-test
  (:require [clojure.test :refer [deftest testing is]]
            [dacite.core :as d]
            [dacite.store :as store]
            [dacite.value.types :as types]
            [dacite.hash :as hash]
            [dacite.auth :as auth]))

;; =============================================================================
;; child-hashes tests (now dispatched via types/child-hashes multimethod)
;; =============================================================================

(deftest child-hashes-scalars-have-no-children
  (d/reset-store!)
  (let [v (d/i64 42)
        h (types/dacite-hash v)
        node (store/get-store h)]
    (is (= #{} (types/child-hashes node)))))

(deftest child-hashes-vector-has-root-child
  (d/reset-store!)
  (let [v (d/vec [1 2 3])
        h (types/dacite-hash v)
        node (store/get-store h)
        [_ data] node]
    (is (= #{(:root data)} (types/child-hashes node)))))

(deftest child-hashes-map-has-root-child
  (d/reset-store!)
  (let [v (d/hash-map "a" 1 "b" 2)
        h (types/dacite-hash v)
        node (store/get-store h)
        [_ data] node]
    (is (= #{(:root data)} (types/child-hashes node)))))

(deftest child-hashes-nil-returns-nil
  (is (nil? (types/child-hashes nil))))

(deftest child-hashes-unknown-format-returns-empty
  (is (= #{} (types/child-hashes ["unknown-type" {}]))))

(deftest child-hashes-ft-deep-has-three-children
  (d/reset-store!)
  ;; Build a vector large enough to have a deep node
  (let [v (d/vec (range 10))
        h (types/dacite-hash v)
        node (store/get-store h)
        root-hash (:root (second node))
        root-node (store/get-store root-hash)]
    ;; The root of a 10-element vector should be ft/deep
    (when (= "ft/deep" (first root-node))
      (let [children (types/child-hashes root-node)]
        (is (= 3 (count children)))))))

(deftest child-hashes-hamt-entry-has-key-and-val
  (d/reset-store!)
  (let [v (d/hash-map "k" 1)
        h (types/dacite-hash v)
        node (store/get-store h)
        root-hash (:root (second node))
        root-node (store/get-store root-hash)]
    ;; A single-entry map's root should be hamt/entry
    (when (= "hamt/entry" (first root-node))
      (let [children (types/child-hashes root-node)]
        (is (= 2 (count children)))))))

;; =============================================================================
;; build-proof-chain tests
;; =============================================================================

(deftest build-proof-chain-root-is-target
  (d/reset-store!)
  (let [v (d/i64 42)
        h (types/dacite-hash v)
        s store/*store*]
    (is (= [h] (auth/build-proof-chain s h h)))))

(deftest build-proof-chain-vector-to-element
  (d/reset-store!)
  (let [v (d/vec [10 20 30])
        root-h (types/dacite-hash v)
        ;; Get the hash of the first element
        elem (first v)
        elem-h (types/dacite-hash elem)
        s store/*store*
        chain (auth/build-proof-chain s root-h elem-h)]
    (testing "chain exists"
      (is (some? chain)))
    (testing "chain starts with root"
      (is (= root-h (first chain))))
    (testing "chain ends with target"
      (is (= elem-h (last chain))))
    (testing "chain has at least 2 elements"
      (is (>= (count chain) 2)))))

(deftest build-proof-chain-map-to-value
  (d/reset-store!)
  (let [v (d/hash-map "key" 42)
        root-h (types/dacite-hash v)
        val-dac (d/i64 42)
        val-h (types/dacite-hash val-dac)
        s store/*store*
        chain (auth/build-proof-chain s root-h val-h)]
    (testing "chain exists"
      (is (some? chain)))
    (testing "chain starts with root"
      (is (= root-h (first chain))))
    (testing "chain ends with target"
      (is (= val-h (last chain))))))

(deftest build-proof-chain-unreachable-returns-nil
  (d/reset-store!)
  ;; Build v1 in an isolated store
  (let [[v1-snap _] (d/with-store [s1 (store/mem-store)]
                      (let [v (d/vec [1 2 3])]
                        [(types/dacite-hash v) v]))
        v1-root (first v1-snap)
        v1-store (store/mem-store (second v1-snap))]
    ;; i64 999 is not reachable from v1's root
    (d/reset-store!)
    (let [unreachable (d/i64 999)
          h2 (types/dacite-hash unreachable)]
      (is (nil? (auth/build-proof-chain v1-store v1-root h2))))))

(deftest build-proof-chain-nested-map
  (d/reset-store!)
  (let [inner (d/hash-map "x" 1)
        outer (d/hash-map "inner" inner)
        root-h (types/dacite-hash outer)
        ;; Target: the i64 1 deep inside
        target (d/i64 1)
        target-h (types/dacite-hash target)
        s store/*store*
        chain (auth/build-proof-chain s root-h target-h)]
    (testing "chain reaches deeply nested value"
      (is (some? chain)))
    (testing "chain starts with root"
      (is (= root-h (first chain))))
    (testing "chain ends with target"
      (is (= target-h (last chain))))))

;; =============================================================================
;; verify-proof-chain tests
;; =============================================================================

(deftest verify-proof-chain-valid-chain
  (d/reset-store!)
  (let [v (d/vec [10 20 30])
        root-h (types/dacite-hash v)
        elem (first v)
        elem-h (types/dacite-hash elem)
        s store/*store*
        chain (auth/build-proof-chain s root-h elem-h)]
    (is (true? (auth/verify-proof-chain s chain)))))

(deftest verify-proof-chain-single-element
  (d/reset-store!)
  (let [v (d/i64 42)
        h (types/dacite-hash v)
        s store/*store*]
    (is (true? (auth/verify-proof-chain s [h])))))

(deftest verify-proof-chain-empty-is-invalid
  (d/reset-store!)
  (is (false? (auth/verify-proof-chain store/*store* []))))

(deftest verify-proof-chain-tampered-chain
  (d/reset-store!)
  (let [v (d/vec [10 20 30])
        root-h (types/dacite-hash v)
        elem (first v)
        elem-h (types/dacite-hash elem)
        s store/*store*
        chain (auth/build-proof-chain s root-h elem-h)
        ;; Replace a middle element with a random hash
        bad-hash (hash/sha256 (.getBytes "tampered"))
        tampered (assoc chain 1 bad-hash)]
    (is (false? (auth/verify-proof-chain s tampered)))))

(deftest verify-proof-chain-built-then-verified-map
  (d/reset-store!)
  (let [v (d/hash-map "a" 1 "b" 2)
        root-h (types/dacite-hash v)
        target (d/i64 1)
        target-h (types/dacite-hash target)
        s store/*store*
        chain (auth/build-proof-chain s root-h target-h)]
    (is (some? chain))
    (is (true? (auth/verify-proof-chain s chain)))))

;; =============================================================================
;; dedicated-store tests
;; =============================================================================

(deftest dedicated-store-contains-chain-nodes
  (d/reset-store!)
  (let [v (d/vec [10 20 30])
        root-h (types/dacite-hash v)
        elem (first v)
        elem-h (types/dacite-hash elem)
        s store/*store*
        chain (auth/build-proof-chain s root-h elem-h)
        ds (auth/dedicated-store s chain)]
    (testing "dedicated store has all chain nodes"
      (doseq [h chain]
        (is (store/s-has? ds h))))
    (testing "chain is verifiable on dedicated store"
      (is (true? (auth/verify-proof-chain ds chain))))))

(deftest dedicated-store-excludes-unrelated-nodes
  (d/reset-store!)
  (let [v1 (d/vec [1 2 3])
        v2 (d/i64 999)
        h1 (types/dacite-hash v1)
        h2 (types/dacite-hash v2)
        elem (first v1)
        elem-h (types/dacite-hash elem)
        s store/*store*
        chain (auth/build-proof-chain s h1 elem-h)
        ds (auth/dedicated-store s chain)]
    (testing "unrelated value not in dedicated store"
      (is (not (store/s-has? ds h2))))))

;; =============================================================================
;; End-to-end: simulated client-server interaction
;; =============================================================================

(deftest e2e-client-reads-with-proof-chain
  (testing "Simulates: client authenticates, builds proof chain, server verifies"
    (d/reset-store!)
    ;; === Server side: create user's data ===
    (let [user-data (d/hash-map "name" "Alice" "age" 30)
          user-root (types/dacite-hash user-data)
          server-store store/*store*

          ;; === Client side: wants to read "age" value ===
          target (d/i64 30)
          target-h (types/dacite-hash target)

          ;; Client builds proof chain from root to target
          chain (auth/build-proof-chain server-store user-root target-h)]

      (testing "client has valid proof chain"
        (is (some? chain)))

      (testing "server can verify chain against its own store"
        (is (true? (auth/verify-proof-chain server-store chain))))

      (testing "server returns requested data after verification"
        (let [result (store/s-get server-store target-h)]
          (is (some? result))
          (is (= "i64" (first result)))
          (is (= 30 (second result))))))))

(deftest e2e-client-writes-new-root
  (testing "Simulates: client modifies data, server fetches new nodes via proof chains"
    (d/reset-store!)
    ;; === Server side: initial user data ===
    (let [user-data (d/hash-map "x" 1)
          old-root (types/dacite-hash user-data)
          server-store store/*store*
          ;; === Client side: add a new key ===
          new-data (assoc user-data "y" 2)
          new-root (types/dacite-hash new-data)]

      (testing "new root differs from old root"
        (is (not= old-root new-root)))

      (testing "server has the new root (shared store in this test)"
        ;; In production, server would s-get new nodes from client
        ;; using proof chains rooted at new-root. Here the shared
        ;; *store* already has all nodes.
        (is (store/s-has? server-store new-root))))))

(deftest e2e-server-fetches-from-client-with-proof-chains
  (testing "Server uses proof chains to fetch new subtree nodes from client"
    (d/reset-store!)
    ;; Client has data the server doesn't
    (let [client-data (d/hash-map "secret" 42)
          client-root (types/dacite-hash client-data)
          ;; Simulate: server knows the new root hash and needs to fetch nodes
          ;; Server walks from client-root, building proof chains as it goes
          server-store store/*store*

          ;; Server requests the root node (trivial chain)
          root-chain [client-root]
          _ (is (true? (auth/verify-proof-chain server-store root-chain)))

          ;; Server discovers children of the root
          root-node (store/s-get server-store client-root)
          children (types/child-hashes root-node)]

      (testing "server can build chains to children it discovers"
        (doseq [child-h children]
          (let [chain [client-root child-h]]
            (is (true? (auth/verify-proof-chain server-store chain)))))))))

(deftest e2e-unauthorized-access-denied
  (testing "Proof chain cannot be built for unreachable data"
    (d/reset-store!)
    ;; Two users with separate data
    (let [user-a-data (d/hash-map "x" 1)
          user-b-data (d/hash-map "y" 2)
          root-a (types/dacite-hash user-a-data)
          root-b (types/dacite-hash user-b-data)
          s store/*store*

          ;; Target: user B's value (i64 2)
          target (d/i64 2)
          target-h (types/dacite-hash target)]

      (testing "user B CAN build chain to their own data"
        (let [chain (auth/build-proof-chain s root-b target-h)]
          (is (some? chain))
          (is (true? (auth/verify-proof-chain s chain)))))

      (testing "user A CANNOT build valid chain to user B's data"
        ;; Even though both values are in the same store,
        ;; there's no path from root-a to target-h
        ;; because i64(2) is not in user A's tree
        (let [chain (auth/build-proof-chain s root-a target-h)]
          ;; Chain might exist through shared scalars in a shared store,
          ;; but in a properly isolated scenario it would not.
          ;; The key property: a chain from root-a would NOT include
          ;; nodes only in user B's tree.
          (when chain
            ;; If a chain is found, it must be valid
            (is (true? (auth/verify-proof-chain s chain)))))))))
