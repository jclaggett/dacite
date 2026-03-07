(ns dacite.auth-test
  (:require [clojure.test :refer [deftest testing is]]
            [dacite.core :as d]
            [dacite.store :as store]
            [dacite.types :as types]
            [dacite.hash :as hash]
            [dacite.auth :as auth]))

;; =============================================================================
;; child-hashes tests
;; =============================================================================

(deftest child-hashes-scalars-have-no-children
  (d/reset-store!)
  (let [v (d/i64 42)
        h (types/dacite-hash v)
        node (store/get-store h)]
    (is (= #{} (auth/child-hashes node)))))

(deftest child-hashes-vector-has-root-child
  (d/reset-store!)
  (let [v (d/vec [1 2 3])
        h (types/dacite-hash v)
        node (store/get-store h)
        [_ data] node]
    (is (= #{(:root data)} (auth/child-hashes node)))))

(deftest child-hashes-map-has-root-child
  (d/reset-store!)
  (let [v (d/hash-map "a" 1 "b" 2)
        h (types/dacite-hash v)
        node (store/get-store h)
        [_ data] node]
    (is (= #{(:root data)} (auth/child-hashes node)))))

(deftest child-hashes-nil-returns-nil
  (is (nil? (auth/child-hashes nil))))

(deftest child-hashes-unknown-format-returns-empty
  (is (= #{} (auth/child-hashes ["unknown-type" {}]))))

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
  (let [v1 (d/vec [1 2 3])
        h1 (types/dacite-hash v1)
        ;; Create a separate value not connected to v1
        _v2 (d/i64 999)
        h2 (types/dacite-hash _v2)
        ;; Use a fresh store with only v1's nodes
        v1-store (auth/dedicated-store-for-subtree store/*store* h1)]
    (is (nil? (auth/build-proof-chain v1-store h1 h2)))))

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
        bad-hash (dacite.hash/sha256 (.getBytes "tampered"))
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

(deftest dedicated-store-for-subtree-copies-reachable
  (d/reset-store!)
  (let [v (d/hash-map "a" 1 "b" 2)
        root-h (types/dacite-hash v)
        s store/*store*
        ds (auth/dedicated-store-for-subtree s root-h)]
    (testing "root is in dedicated store"
      (is (store/s-has? ds root-h)))
    (testing "all reachable nodes are in dedicated store"
      ;; Every hash reachable from root should be in ds
      (let [full-snap (store/s-snapshot ds)]
        (is (pos? (count full-snap)))))))

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
          chain (auth/build-proof-chain server-store user-root target-h)

          ;; Client creates dedicated store with chain nodes
          client-ds (auth/dedicated-store server-store chain)]

      (testing "client has valid proof chain"
        (is (some? chain)))

      (testing "server can verify chain using dedicated store"
        ;; Server fetches chain from client's dedicated store
        ;; and verifies it against its own store
        (is (true? (auth/verify-proof-chain server-store chain))))

      (testing "server returns requested data after verification"
        (let [result (store/s-get server-store target-h)]
          (is (some? result))
          (is (= "i64" (first result)))
          (is (= 30 (second result))))))))

(deftest e2e-client-writes-new-root
  (testing "Simulates: client modifies data, creates dedicated store, server ingests"
    (d/reset-store!)
    ;; === Server side: initial user data ===
    (let [user-data (d/hash-map "x" 1)
          old-root (types/dacite-hash user-data)
          server-store store/*store*
          ;; === Client side: add a new key ===
          new-data (assoc user-data "y" 2)
          new-root (types/dacite-hash new-data)
          ;; Client creates dedicated store with the new subtree
          client-ds (auth/dedicated-store-for-subtree server-store new-root)]

      (testing "new root differs from old root"
        (is (not= old-root new-root)))

      (testing "server can ingest new nodes from client's dedicated store"
        ;; Server merges nodes from client's dedicated store
        (let [client-snap (store/s-snapshot client-ds)]
          (store/s-merge server-store client-snap)
          ;; Server now has the new root
          (is (store/s-has? server-store new-root)))))))

(deftest e2e-unauthorized-access-denied
  (testing "Proof chain for unreachable hash fails verification"
    (d/reset-store!)
    ;; User A has a map with key "x" -> 1
    ;; User B has a map with key "y" -> 2
    ;; User A should not be able to reach user B's value (2) from their root
    (let [user-a-data (d/hash-map "x" 1)
          user-b-data (d/hash-map "y" 2)
          root-a (types/dacite-hash user-a-data)
          root-b (types/dacite-hash user-b-data)
          s store/*store*

          ;; Target: user B's value (i64 2), not in user A's tree
          target (d/i64 2)
          target-h (types/dacite-hash target)

          ;; Build a store scoped to only user A's reachable nodes
          a-store (auth/dedicated-store-for-subtree s root-a)]

      (testing "user A cannot build chain to user B's data"
        (is (nil? (auth/build-proof-chain a-store root-a target-h))))

      (testing "user B CAN build chain to their own data"
        (let [b-store (auth/dedicated-store-for-subtree s root-b)
              chain (auth/build-proof-chain b-store root-b target-h)]
          (is (some? chain))
          (is (true? (auth/verify-proof-chain b-store chain))))))))
