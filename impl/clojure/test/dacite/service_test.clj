(ns dacite.service-test
  (:require [clojure.test :refer [deftest testing is]]
            [dacite.service :as svc]
            [dacite.core :as d]
            [dacite.store :as store]
            [dacite.types :as types]
            [dacite.auth :as auth]
            [dacite.hash :as hash]))

;; =============================================================================
;; User management
;; =============================================================================

(deftest register-and-login
  (let [service (svc/create-service)]
    (svc/register-user service "alice" "secret123")

    (testing "login with correct credentials"
      (let [result (svc/login service "alice" "secret123")]
        (is (some? (:token result)))
        (is (nil? (:root-hash result)))))

    (testing "login with wrong password"
      (is (nil? (svc/login service "alice" "wrongpass"))))

    (testing "login with unknown user"
      (is (nil? (svc/login service "bob" "secret123"))))))

(deftest register-with-root-hash
  (d/reset-store!)
  (let [main-store store/*store*
        data (d/hash-map "name" "Alice")
        root-h (types/dacite-hash data)
        service (svc/create-service main-store)]
    (svc/register-user service "alice" "pass" root-h)

    (testing "login returns root hash"
      (let [result (svc/login service "alice" "pass")]
        (is (= root-h (:root-hash result)))))))

;; =============================================================================
;; Read with proof chains
;; =============================================================================

(deftest session-get-with-valid-chain
  (d/reset-store!)
  (let [main-store store/*store*
        data (d/hash-map "x" 42)
        root-h (types/dacite-hash data)
        target (d/i64 42)
        target-h (types/dacite-hash target)
        service (svc/create-service main-store)]
    (svc/register-user service "alice" "pass" root-h)
    (let [{:keys [token]} (svc/login service "alice" "pass")
          chain (auth/build-proof-chain main-store root-h target-h)
          result (svc/session-get service token target-h chain)]
      (is (nil? (:error result)))
      (is (= ["i64" 42] (:value result))))))

(deftest session-get-with-invalid-chain
  (d/reset-store!)
  (let [main-store store/*store*
        data (d/hash-map "x" 42)
        root-h (types/dacite-hash data)
        target (d/i64 42)
        target-h (types/dacite-hash target)
        fake-hash (hash/sha256 (.getBytes "fake"))
        service (svc/create-service main-store)]
    (svc/register-user service "alice" "pass" root-h)
    (let [{:keys [token]} (svc/login service "alice" "pass")]

      (testing "tampered chain"
        (let [result (svc/session-get service token target-h
                                      [root-h fake-hash target-h])]
          (is (= :invalid-proof-chain (:error result)))))

      (testing "chain root mismatch"
        (let [result (svc/session-get service token target-h
                                      [fake-hash target-h])]
          (is (= :chain-root-mismatch (:error result)))))

      (testing "chain target mismatch"
        (let [result (svc/session-get service token target-h
                                      [root-h fake-hash])]
          (is (= :chain-target-mismatch (:error result))))))))

(deftest session-get-invalid-token
  (let [service (svc/create-service)
        result (svc/session-get service "bad-token" nil nil)]
    (is (= :invalid-session (:error result)))))

;; =============================================================================
;; Session store (proxy)
;; =============================================================================

(deftest session-put-and-get
  (let [service (svc/create-service)
        _ (svc/register-user service "alice" "pass")
        {:keys [token]} (svc/login service "alice" "pass")
        fake-hash (hash/sha256 (.getBytes "test"))
        node ["i64" 99]]

    (testing "put to session store"
      (let [result (svc/session-put service token fake-hash node)]
        (is (:ok result))))

    (testing "get from session store"
      (let [result (svc/session-get-node service token fake-hash)]
        (is (= node (:value result)))))))

(deftest session-put-invalid-token
  (let [service (svc/create-service)
        result (svc/session-put service "bad" nil nil)]
    (is (= :invalid-session (:error result)))))

;; =============================================================================
;; Root replacement with walk-and-pull
;; =============================================================================

(deftest update-root-pulls-new-nodes
  (let [main-store (store/mem-store)
        service (svc/create-service main-store)]
    (svc/register-user service "alice" "pass")
    (let [{:keys [token]} (svc/login service "alice" "pass")
          ;; Build a value in a local store (simulating the client)
          local-store (store/mem-store)
          value (binding [store/*store* local-store]
                  (d/hash-map "greeting" "hello"))
          root-h (types/dacite-hash value)
          local-nodes (store/s-snapshot local-store)]

      (testing "main store doesn't have the nodes yet"
        (is (not (store/s-has? main-store root-h))))

      ;; Push nodes to session store (proxy)
      (doseq [[h v] local-nodes]
        (svc/session-put service token h v))

      (testing "update root pulls nodes into main store"
        (let [result (svc/update-root service token root-h)]
          (is (:ok result))
          (is (pos? (:nodes-pulled result)))
          (is (= root-h (:root-hash result)))))

      (testing "main store now has the nodes"
        (is (store/s-has? main-store root-h)))

      (testing "user's root is updated"
        (is (= root-h (svc/get-root-hash service "alice")))))))

(deftest update-root-missing-node-fails
  (let [service (svc/create-service)]
    (svc/register-user service "alice" "pass")
    (let [{:keys [token]} (svc/login service "alice" "pass")
          fake-root (hash/sha256 (.getBytes "nonexistent"))
          result (svc/update-root service token fake-root)]
      (is (= :missing-node (:error result))))))

(deftest update-root-incremental
  (testing "Second write only pulls new/changed nodes"
    (let [main-store (store/mem-store)
          service (svc/create-service main-store)]
      (svc/register-user service "alice" "pass")
      (let [{:keys [token]} (svc/login service "alice" "pass")

            ;; First write
            local1 (store/mem-store)
            v1 (binding [store/*store* local1]
                 (d/hash-map "x" 1))
            h1 (types/dacite-hash v1)
            _ (doseq [[h v] (store/s-snapshot local1)]
                (svc/session-put service token h v))
            r1 (svc/update-root service token h1)

            ;; Second write: add a key (shares structural nodes)
            local2 (store/mem-store)
            v2 (binding [store/*store* local2]
                 (d/hash-map "x" 1 "y" 2))
            h2 (types/dacite-hash v2)
            _ (doseq [[h v] (store/s-snapshot local2)]
                (svc/session-put service token h v))
            r2 (svc/update-root service token h2)]

        (is (:ok r1))
        (is (:ok r2))
        ;; Second write should pull fewer nodes than the total in local2,
        ;; because shared nodes (like "x", i64 1) are already in main
        (let [total-local2 (count (store/s-snapshot local2))]
          (is (< (:nodes-pulled r2) total-local2)))))))

;; =============================================================================
;; Logout
;; =============================================================================

(deftest logout-invalidates-session
  (let [service (svc/create-service)]
    (svc/register-user service "alice" "pass")
    (let [{:keys [token]} (svc/login service "alice" "pass")]
      (svc/logout service token)
      (is (= :invalid-session
             (:error (svc/session-get service token nil nil)))))))

;; =============================================================================
;; End-to-end: two users, isolation
;; =============================================================================

(deftest e2e-two-users-isolated
  (let [main-store (store/mem-store)
        service (svc/create-service main-store)]
    (svc/register-user service "alice" "pass-a")
    (svc/register-user service "bob" "pass-b")
    (let [{token-a :token} (svc/login service "alice" "pass-a")
          {token-b :token} (svc/login service "bob" "pass-b")

          ;; Alice writes her data
          local-a (store/mem-store)
          va (binding [store/*store* local-a]
               (d/hash-map "secret" "alice-only"))
          ha (types/dacite-hash va)
          _ (doseq [[h v] (store/s-snapshot local-a)]
              (svc/session-put service token-a h v))
          _ (svc/update-root service token-a ha)

          ;; Bob writes his data
          local-b (store/mem-store)
          vb (binding [store/*store* local-b]
               (d/hash-map "secret" "bob-only"))
          hb (types/dacite-hash vb)
          _ (doseq [[h v] (store/s-snapshot local-b)]
              (svc/session-put service token-b h v))
          _ (svc/update-root service token-b hb)

          ;; Alice's target
          target-a (binding [store/*store* local-a]
                     (d/str "alice-only"))
          target-a-h (types/dacite-hash target-a)

          ;; Bob tries to read Alice's data
          chain-from-bob (auth/build-proof-chain main-store hb target-a-h)]

      (testing "Alice can read her own data"
        (let [chain (auth/build-proof-chain main-store ha target-a-h)
              result (svc/session-get service token-a target-a-h chain)]
          (is (nil? (:error result)))))

      (testing "Bob cannot build a chain to Alice's data from his root"
        ;; The chain would not start from Bob's root
        ;; or would not exist at all
        (when chain-from-bob
          ;; Even if a chain exists, it won't start from Bob's root
          (let [result (svc/session-get service token-b target-a-h
                                        chain-from-bob)]
            ;; Bob's session has a different root, so chain root won't match
            (is (some? (:error result)))))))))
