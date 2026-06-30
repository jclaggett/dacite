(ns dacite.service-test
  (:require [clojure.test :refer [deftest testing is]]
            [dacite.service :as svc]
            [dacite.core :as d]
            [dacite.store :as store]
            [dacite.value2.types :as types]
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

;; =============================================================================
;; Read with proof chains
;; =============================================================================

(deftest session-get-with-valid-chain
  (let [main-store (store/mem-store)
        service (svc/create-service main-store)]
    (svc/register-user service "alice" "pass")
    (let [{:keys [token]} (svc/login service "alice" "pass")
          ;; Build user data and push it
          local-store (store/mem-store)
          data (store/bind-store local-store
                                 (d/hash-map "x" 42))
          root-h (types/dacite-hash data)
          _ (doseq [[h v] (store/s-snapshot local-store)]
              (svc/session-put service token h v))
          _ (svc/update-root service token root-h)
          ;; Now alice has a subtree root
          user-root (:root-hash (get-in @service [:sessions token]))
          target (store/bind-store local-store
                                   (d/i64 42))
          target-h (types/dacite-hash target)
          chain (auth/build-proof-chain main-store user-root target-h)
          result (svc/session-get service token target-h chain)]
      (is (nil? (:error result)))
      (is (= ["i64" 42] (:value result))))))

(deftest session-get-with-invalid-chain
  (let [main-store (store/mem-store)
        service (svc/create-service main-store)]
    (svc/register-user service "alice" "pass")
    (let [{:keys [token]} (svc/login service "alice" "pass")
          local-store (store/mem-store)
          data (store/bind-store local-store
                                 (d/hash-map "x" 42))
          root-h (types/dacite-hash data)
          _ (doseq [[h v] (store/s-snapshot local-store)]
              (svc/session-put service token h v))
          _ (svc/update-root service token root-h)
          user-root (:root-hash (get-in @service [:sessions token]))
          target (store/bind-store local-store
                                   (d/i64 42))
          target-h (types/dacite-hash target)
          fake-hash (hash/sha256 (.getBytes "fake"))]

      (testing "tampered chain"
        (let [result (svc/session-get service token target-h
                                      [user-root fake-hash target-h])]
          (is (= :invalid-proof-chain (:error result)))))

      (testing "chain root mismatch"
        (let [result (svc/session-get service token target-h
                                      [fake-hash target-h])]
          (is (= :chain-root-mismatch (:error result)))))

      (testing "chain target mismatch"
        (let [result (svc/session-get service token target-h
                                      [user-root fake-hash])]
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
          value (store/bind-store local-store
                                  (d/hash-map "greeting" "hello"))
          root-h (types/dacite-hash value)
          local-nodes (store/s-snapshot local-store)]

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

      (testing "service root is set"
        (is (some? (svc/get-root-hash service))))

      (testing "user's subtree is accessible via service root"
        (let [user-root (svc/get-user-root service "alice")]
          (is (= root-h user-root)))))))

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
            v1 (store/bind-store local1
                                 (d/hash-map "x" 1))
            h1 (types/dacite-hash v1)
            _ (doseq [[h v] (store/s-snapshot local1)]
                (svc/session-put service token h v))
            r1 (svc/update-root service token h1)

            ;; Second write: add a key (shares structural nodes)
            local2 (store/mem-store)
            v2 (store/bind-store local2
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
;; Single root map
;; =============================================================================

(deftest single-root-map-structure
  (testing "Service root is a map of username to user subtree"
    (let [main-store (store/mem-store)
          service (svc/create-service main-store)]
      (svc/register-user service "alice" "pass")
      (let [{:keys [token]} (svc/login service "alice" "pass")
            local-store (store/mem-store)
            value (store/bind-store local-store
                                    (d/hash-map "name" "Alice"))
            root-h (types/dacite-hash value)]
        (doseq [[h v] (store/s-snapshot local-store)]
          (svc/session-put service token h v))
        (svc/update-root service token root-h)

        (testing "service root is a map containing alice's key"
          (let [service-root (svc/get-root-hash service)]
            (is (some? service-root))
            (store/bind-store main-store
                              (let [root-map (d/wrap-hash service-root)]
                                (is (= 1 (count root-map)))
                                (is (some? (get root-map "alice")))))))))))

(deftest two-users-single-root
  (testing "Two users share a single service root map"
    (let [main-store (store/mem-store)
          service (svc/create-service main-store)]
      (svc/register-user service "alice" "pass-a")
      (svc/register-user service "bob" "pass-b")
      (let [{token-a :token} (svc/login service "alice" "pass-a")
            {token-b :token} (svc/login service "bob" "pass-b")

            ;; Alice writes
            local-a (store/mem-store)
            va (store/bind-store local-a
                                 (d/hash-map "secret" "alice-only"))
            ha (types/dacite-hash va)
            _ (doseq [[h v] (store/s-snapshot local-a)]
                (svc/session-put service token-a h v))
            _ (svc/update-root service token-a ha)

            ;; Bob writes
            local-b (store/mem-store)
            vb (store/bind-store local-b
                                 (d/hash-map "secret" "bob-only"))
            hb (types/dacite-hash vb)
            _ (doseq [[h v] (store/s-snapshot local-b)]
                (svc/session-put service token-b h v))
            _ (svc/update-root service token-b hb)]

        (testing "both users have subtrees under single root"
          (let [service-root (svc/get-root-hash service)]
            (store/bind-store main-store
                              (let [root-map (d/wrap-hash service-root)]
                                (is (= 2 (count root-map)))
                                (is (some? (get root-map "alice")))
                                (is (some? (get root-map "bob")))))))

        (testing "user subtrees are correct"
          (is (= ha (svc/get-user-root service "alice")))
          (is (= hb (svc/get-user-root service "bob"))))))))

;; =============================================================================
;; LMDB root persistence
;; =============================================================================

(deftest root-persisted-to-lmdb
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir")
                     "/dacite-test-" (System/nanoTime))
        lmdb (store/lmdb-store tmp-dir)]
    (try
      (let [main-store (store/layered-store (store/mem-store) lmdb)
            service (svc/create-service main-store lmdb)]
        (svc/register-user service "alice" "pass")
        (let [{:keys [token]} (svc/login service "alice" "pass")
              local-store (store/mem-store)
              value (store/bind-store local-store
                                      (d/hash-map "greeting" "hello"))
              root-h (types/dacite-hash value)]
          (doseq [[h v] (store/s-snapshot local-store)]
            (svc/session-put service token h v))
          (svc/update-root service token root-h)

          (testing "root persisted to LMDB meta db"
            (let [stored-root (store/lmdb-get-meta lmdb "root")]
              (is (some? stored-root))
              (is (= (svc/get-root-hash service) stored-root))))

          (testing "new service restores root from LMDB"
            (let [service2 (svc/create-service main-store lmdb)]
              (is (= (svc/get-root-hash service) (svc/get-root-hash service2)))))))
      (finally
        (store/lmdb-close lmdb)
        ;; Clean up temp files
        (doseq [f (reverse (file-seq (java.io.File. tmp-dir)))]
          (.delete f))))))

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
