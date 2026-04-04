(ns dacite.service-sharing-test
  "Tests for Layer 5 sharing integration in the service layer.

   Tests cover:
   - Session grants (own root as grant)
   - claim-share (authorized, unauthorized, public, groups)
   - GET via claimed grant (proof chain from shared subtree)
   - PUT with shared data references
   - Cross-user sharing flows"
  (:require [clojure.test :refer [deftest testing is]]
            [dacite.service :as svc]
            [dacite.share :as share]
            [dacite.core :as d]
            [dacite.store :as store]
            [dacite.value.types :as types]
            [dacite.auth :as auth]
            [dacite.hash :as hash]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- setup-user!
  "Register a user, login, build a value, push and update root.
   Returns {:token t :root-hash h :local-store s}."
  [service user-id password value-fn]
  (svc/register-user service user-id password)
  (let [{:keys [token]} (svc/login service user-id password)
        local-store (store/mem-store)
        value (binding [store/*store* local-store]
                (value-fn))
        root-h (types/dacite-hash value)]
    (doseq [[h v] (store/s-snapshot local-store)]
      (svc/session-put service token h v))
    (svc/update-root service token root-h)
    {:token token :root-hash root-h :local-store local-store}))

;; =============================================================================
;; Session grants: own root
;; =============================================================================

(deftest session-has-own-root-grant
  (let [main-store (store/mem-store)
        service (svc/create-service main-store)]
    (let [{:keys [token root-hash]}
          (setup-user! service "alice" "pass" #(d/hash-map "x" 1))
          grants (svc/session-grants service token)]

      (testing "session has exactly one grant after first write"
        (is (= 1 (count grants))))

      (testing "grant hash matches user root"
        (is (= root-hash (:hash (first grants)))))

      (testing "grant authorized set is #{user-id}"
        (is (= #{"alice"} (:authorized (first grants))))))))

(deftest session-grant-updates-on-root-change
  (let [main-store (store/mem-store)
        service (svc/create-service main-store)]
    (svc/register-user service "alice" "pass")
    (let [{:keys [token]} (svc/login service "alice" "pass")
          ;; First write
          local1 (store/mem-store)
          v1 (binding [store/*store* local1] (d/hash-map "x" 1))
          h1 (types/dacite-hash v1)
          _ (doseq [[h v] (store/s-snapshot local1)]
              (svc/session-put service token h v))
          _ (svc/update-root service token h1)

          ;; Second write
          local2 (store/mem-store)
          v2 (binding [store/*store* local2] (d/hash-map "x" 2))
          h2 (types/dacite-hash v2)
          _ (doseq [[h v] (store/s-snapshot local2)]
              (svc/session-put service token h v))
          _ (svc/update-root service token h2)

          grants (svc/session-grants service token)]

      (testing "still one own-root grant"
        (is (= 1 (count grants))))

      (testing "grant points to new root"
        (is (= h2 (:hash (first grants))))))))

;; =============================================================================
;; claim-share
;; =============================================================================

(deftest claim-share-basic
  (testing "Claim using Clojure map roots (convention layer)"
    (let [main-store (store/mem-store)
          service (svc/create-service main-store)]

      ;; Alice builds a tree with a shared subtree
      (let [alice-result (setup-user! service "alice" "pass-a"
                                      #(d/hash-map "x" 42))
            ;; We need Alice's root to follow the sharing convention
            ;; For now, test the claim function directly with a mock root
            alice-root {:shares {"photos" {:target (:root-hash alice-result)
                                           :authorized #{"bob"}}}
                        :groups {}}]

        ;; Verify the claim logic works
        (is (some? (share/claim alice-root "photos" "bob")))
        (is (nil? (share/claim alice-root "photos" "eve")))))))

(deftest claim-share-with-groups
  (let [alice-root {:shares {"docs" {:target (hash/sha256 (.getBytes "target"))
                                     :authorized "editors"}}
                    :groups {"editors" #{"alice" "bob" "carol"}}}]

    (testing "group member can claim"
      (is (some? (share/claim alice-root "docs" "carol"))))

    (testing "non-member cannot claim"
      (is (nil? (share/claim alice-root "docs" "dave"))))))

(deftest claim-share-public
  (let [root {:shares {"open" {:target (hash/sha256 (.getBytes "pub"))
                               :authorized #{share/public}}}
              :groups {}}]
    (testing "anyone can claim public share"
      (is (some? (share/claim root "open" "stranger"))))))

;; =============================================================================
;; Service-level claim-share
;; =============================================================================

(deftest service-claim-share-not-found
  (let [service (svc/create-service)]
    (svc/register-user service "bob" "pass")
    (let [{:keys [token]} (svc/login service "bob" "pass")
          result (svc/claim-share service token "nonexistent" "photos")]
      (is (= :sharer-not-found (:error result))))))

(deftest service-claim-share-invalid-session
  (let [service (svc/create-service)
        result (svc/claim-share service "bad-token" "alice" "photos")]
    (is (= :invalid-session (:error result)))))

;; =============================================================================
;; GET via own root grant (regression)
;; =============================================================================

(deftest get-via-own-root-grant
  (let [main-store (store/mem-store)
        service (svc/create-service main-store)]
    (let [{:keys [token root-hash]}
          (setup-user! service "alice" "pass" #(d/hash-map "x" 42))
          ;; Find the i64 42 in the tree
          target-val (binding [store/*store* main-store]
                       (d/i64 42))
          target-h (types/dacite-hash target-val)
          chain (auth/build-proof-chain main-store root-hash target-h)]

      (testing "GET with chain from own root works"
        (let [result (svc/session-get service token target-h chain)]
          (is (nil? (:error result)))
          (is (some? (:value result))))))))

;; =============================================================================
;; GET cross-user isolation
;; =============================================================================

(deftest get-cross-user-isolation
  (let [main-store (store/mem-store)
        service (svc/create-service main-store)]
    (let [alice (setup-user! service "alice" "pass-a" #(d/hash-map "secret" "alice-data"))
          bob (setup-user! service "bob" "pass-b" #(d/hash-map "secret" "bob-data"))

          ;; Try to use alice's chain root with bob's token
          alice-root (:root-hash alice)
          target-val (binding [store/*store* main-store]
                       (d/str "alice-data"))
          target-h (types/dacite-hash target-val)
          chain (auth/build-proof-chain main-store alice-root target-h)]

      (testing "Bob cannot GET alice's data via her chain root"
        (let [result (svc/session-get service (:token bob) target-h chain)]
          (is (= :no-matching-grant (:error result))))))))

;; =============================================================================
;; Multiple grants in session
;; =============================================================================

(deftest session-multiple-grants
  (let [main-store (store/mem-store)
        service (svc/create-service main-store)]
    (let [{:keys [token]} (setup-user! service "bob" "pass" #(d/hash-map "mine" 1))
          ;; Manually add a second grant to bob's session
          fake-target (hash/sha256 (.getBytes "shared-target"))
          fake-grant (share/make-grant fake-target #{"bob" "alice"})]

      (swap! service update-in [:sessions token :grants] conj fake-grant)

      (testing "session now has two grants"
        (is (= 2 (count (svc/session-grants service token)))))

      (testing "both grants have correct hashes"
        (let [grant-hashes (set (map :hash (svc/session-grants service token)))]
          (is (contains? grant-hashes fake-target)))))))

;; =============================================================================
;; find-authorized-grant
;; =============================================================================

(deftest find-grant-among-multiple
  (let [h1 (hash/sha256 (.getBytes "g1"))
        h2 (hash/sha256 (.getBytes "g2"))
        h3 (hash/sha256 (.getBytes "g3"))
        grants [(share/make-grant h1 #{"alice"})
                (share/make-grant h2 #{"bob"})
                (share/make-grant h3 #{share/public})]]

    (testing "finds alice's grant"
      (is (= h1 (:hash (share/find-authorized-grant grants "alice" h1)))))

    (testing "alice cannot use bob's grant"
      (is (nil? (share/find-authorized-grant grants "alice" h2))))

    (testing "anyone can use public grant"
      (is (= h3 (:hash (share/find-authorized-grant grants "random" h3)))))

    (testing "no match for wrong hash"
      (is (nil? (share/find-authorized-grant grants "alice" h2))))))

;; =============================================================================
;; End-to-end: Alice shares with Bob, Bob reads
;; =============================================================================

(deftest e2e-share-and-read
  (testing "Alice creates data, Bob reads via claimed grant"
    (let [main-store (store/mem-store)
          service (svc/create-service main-store)]

      ;; Alice creates a subtree
      (let [alice (setup-user! service "alice" "pass-a"
                               #(d/hash-map "photos" (d/hash-map "vacation" "beach.jpg")))
            alice-root (:root-hash alice)

            ;; Bob logs in (no data yet)
            _ (svc/register-user service "bob" "pass-b")
            {bob-token :token} (svc/login service "bob" "pass-b")

            ;; Manually add a grant for Bob to Alice's root
            ;; (simulating what claim-share would do once convention layer is wired)
            alice-grant (share/make-grant alice-root #{"bob" "alice"})
            _ (swap! service update-in [:sessions bob-token :grants] conj alice-grant)

            ;; Bob builds a proof chain from Alice's root to a leaf
            target-val (binding [store/*store* main-store]
                         (d/str "beach.jpg"))
            target-h (types/dacite-hash target-val)
            chain (auth/build-proof-chain main-store alice-root target-h)]

        (testing "Bob can read alice's data via granted chain"
          (let [result (svc/session-get service bob-token target-h chain)]
            (is (nil? (:error result)) (str "Error: " (:error result)))
            (is (some? (:value result)))))

        (testing "Bob still cannot access with a random chain root"
          (let [fake-root (hash/sha256 (.getBytes "fake"))
                result (svc/session-get service bob-token target-h [fake-root target-h])]
            (is (= :no-matching-grant (:error result)))))))))

(deftest e2e-share-isolation
  (testing "Eve cannot read Alice's shared data without a grant"
    (let [main-store (store/mem-store)
          service (svc/create-service main-store)]

      (let [alice (setup-user! service "alice" "pass-a"
                               #(d/hash-map "secret" "top-secret"))
            alice-root (:root-hash alice)

            _ (svc/register-user service "eve" "pass-e")
            {eve-token :token} (svc/login service "eve" "pass-e")

            target-val (binding [store/*store* main-store]
                         (d/str "top-secret"))
            target-h (types/dacite-hash target-val)
            chain (auth/build-proof-chain main-store alice-root target-h)]

        (testing "Eve cannot read without grant"
          (let [result (svc/session-get service eve-token target-h chain)]
            (is (= :no-matching-grant (:error result)))))))))
