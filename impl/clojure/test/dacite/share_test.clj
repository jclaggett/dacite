(ns dacite.share-test
  (:require [clojure.test :refer [deftest is testing]]
            [dacite.share :as share]
            [dacite.hash :as hash]))

;; Use real hashes for target placeholders
(def fake-hash-a (hash/sha256 (.getBytes "target-a")))
(def fake-hash-b (hash/sha256 (.getBytes "target-b")))
(def fake-hash-c (hash/sha256 (.getBytes "target-c")))

;; =============================================================================
;; resolve-set
;; =============================================================================

(deftest resolve-set-test
  (testing "direct set passes through"
    (is (= #{:alice :bob} (share/resolve-set #{:alice :bob} {}))))

  (testing "string resolves via groups"
    (is (= #{:alice :bob} (share/resolve-set "team" {"team" #{:alice :bob}}))))

  (testing "missing group returns nil"
    (is (nil? (share/resolve-set "missing" {}))))

  (testing "nil passes through"
    (is (nil? (share/resolve-set nil {})))))

;; =============================================================================
;; authorized?
;; =============================================================================

(deftest authorized?-direct-set-test
  (let [root {:shares {"photos" {:target fake-hash-a
                                 :authorized #{:alice :bob}}}
              :groups {}}]
    (testing "member is authorized"
      (is (share/authorized? root "photos" :alice))
      (is (share/authorized? root "photos" :bob)))

    (testing "non-member is not authorized"
      (is (not (share/authorized? root "photos" :eve))))))

(deftest authorized?-named-group-test
  (let [root {:shares {"docs" {:target fake-hash-a
                               :authorized "team"}}
              :groups {"team" #{:alice :bob :carol}}}]
    (testing "group member is authorized"
      (is (share/authorized? root "docs" :carol)))

    (testing "non-group-member is not authorized"
      (is (not (share/authorized? root "docs" :dave))))))

(deftest authorized?-public-test
  (let [root {:shares {"public-data" {:target fake-hash-a
                                      :authorized #{share/public}}}
              :groups {}}]
    (testing "any identity is authorized for public share"
      (is (share/authorized? root "public-data" :anyone))
      (is (share/authorized? root "public-data" :literally-anyone))
      (is (share/authorized? root "public-data" "string-id")))))

(deftest authorized?-missing-share-test
  (let [root {:shares {"photos" {:target fake-hash-a
                                 :authorized #{:alice}}}
              :groups {}}]
    (testing "missing share name returns falsy"
      (is (not (share/authorized? root "nonexistent" :alice))))))

(deftest authorized?-no-shares-test
  (testing "root with no shares map"
    (is (not (share/authorized? {} "anything" :alice))))

  (testing "root with empty shares"
    (is (not (share/authorized? {:shares {}} "anything" :alice)))))

;; =============================================================================
;; shares / groups accessors
;; =============================================================================

(deftest shares-test
  (testing "extracts shares map"
    (is (= {"a" {:target fake-hash-a :authorized #{:x}}}
           (share/shares {:shares {"a" {:target fake-hash-a
                                        :authorized #{:x}}}}))))

  (testing "returns empty map when absent"
    (is (= {} (share/shares {})))))

(deftest groups-test
  (testing "extracts groups map"
    (is (= {"team" #{:a :b}}
           (share/groups {:groups {"team" #{:a :b}}}))))

  (testing "returns empty map when absent"
    (is (= {} (share/groups {})))))

;; =============================================================================
;; Grant helpers
;; =============================================================================

(deftest make-grant-test
  (is (= {:hash fake-hash-a :authorized #{:alice}}
         (share/make-grant fake-hash-a #{:alice}))))

(deftest own-root-grant-test
  (let [g (share/own-root-grant fake-hash-a :alice)]
    (is (= fake-hash-a (:hash g)))
    (is (= #{:alice} (:authorized g)))))

(deftest grant-authorizes?-test
  (testing "direct member"
    (is (share/grant-authorizes?
         (share/make-grant fake-hash-a #{:alice :bob}) :alice)))

  (testing "non-member"
    (is (not (share/grant-authorizes?
              (share/make-grant fake-hash-a #{:alice}) :eve))))

  (testing "public grant authorizes anyone"
    (is (share/grant-authorizes?
         (share/make-grant fake-hash-a #{share/public}) :stranger))))

(deftest find-authorized-grant-test
  (let [g1 (share/make-grant fake-hash-a #{:alice})
        g2 (share/make-grant fake-hash-b #{:bob})
        g3 (share/make-grant fake-hash-c #{share/public})
        grants [g1 g2 g3]]

    (testing "finds matching grant for identity"
      (is (= g1 (share/find-authorized-grant grants :alice fake-hash-a))))

    (testing "finds public grant"
      (is (= g3 (share/find-authorized-grant grants :anyone fake-hash-c))))

    (testing "returns nil when hash doesn't match"
      (is (nil? (share/find-authorized-grant grants :alice fake-hash-b))))

    (testing "returns nil when not authorized"
      (is (nil? (share/find-authorized-grant grants :eve fake-hash-a))))))

;; =============================================================================
;; claim
;; =============================================================================

(deftest claim-direct-set-test
  (let [root {:shares {"photos" {:target fake-hash-a
                                 :authorized #{:alice :bob}}}
              :groups {}}]
    (testing "authorized claim returns grant"
      (let [grant (share/claim root "photos" :bob)]
        (is (some? grant))
        (is (= fake-hash-a (:hash grant)))
        (is (= #{:alice :bob} (:authorized grant)))))

    (testing "unauthorized claim returns nil"
      (is (nil? (share/claim root "photos" :eve))))))

(deftest claim-named-group-test
  (let [root {:shares {"docs" {:target fake-hash-b
                               :authorized "editors"}}
              :groups {"editors" #{:alice :bob}}}]
    (testing "group member can claim"
      (let [grant (share/claim root "docs" :alice)]
        (is (some? grant))
        (is (= fake-hash-b (:hash grant)))
        ;; authorized is the resolved group set
        (is (= #{:alice :bob} (:authorized grant)))))

    (testing "non-member cannot claim"
      (is (nil? (share/claim root "docs" :carol))))))

(deftest claim-public-test
  (let [root {:shares {"open" {:target fake-hash-c
                               :authorized #{share/public}}}
              :groups {}}]
    (testing "anyone can claim public share"
      (let [grant (share/claim root "open" :stranger)]
        (is (some? grant))
        (is (= fake-hash-c (:hash grant)))))))

(deftest claim-missing-share-test
  (testing "claim on nonexistent share returns nil"
    (is (nil? (share/claim {:shares {} :groups {}} "nope" :alice)))))

(deftest claim-missing-group-test
  (let [root {:shares {"broken" {:target fake-hash-a
                                 :authorized "no-such-group"}}
              :groups {}}]
    (testing "claim with unresolvable group returns nil"
      (is (nil? (share/claim root "broken" :alice))))))
