(ns dacite.hash-test
  "Dacite hashing invariants.

   The portable tests are checks in `dacite.hash-checks`, shared with nbb
   (`npm run hash:test`); each runs here as a deftest under its old name.
   The properties were defspecs; their checks run test.check here."
  (:require [clojure.edn]
            [clojure.test :refer [deftest is testing]]
            [dacite.hash :as hash]
            [dacite.hash-checks :as checks]
            [dacite.host :as host]
            [dacite.store.check :as check]
            [dacite.value.scalar]))

(defn fixture-vectors []
  (clojure.edn/read-string (slurp "../../fixtures/fuse-sl2/vectors.edn")))

(def ^:private env
  "Host pieces the checks take (see dacite.hash-checks). Paths are from
   impl/clojure."
  {:read-vectors fixture-vectors})

(defn- run-check
  "Run the check called `name`: no problems, and quick."
  [name]
  (let [{:keys [ms problems]} (check/run-one checks/checks env name)]
    (is (empty? problems) (pr-str problems))
    (is (<= ms check/max-ms) (str name " took " ms " ms"))))

(defn- pow-by-squaring
  "a^n (n a nonnegative biginteger) by repeated squaring."
  [a ^java.math.BigInteger n]
  (loop [r hash/identity-state b a i 0]
    (if (>= i (.bitLength n))
      r
      (recur (if (.testBit n i) (hash/fuse r b) r) (hash/fuse b b) (inc i)))))

(defn- pow-by-fusing
  "a^n by n fuses (n small)."
  [a n]
  (reduce (fn [r _] (hash/fuse r a)) hash/identity-state (range n)))

;; =============================================================================
;; Group laws (SL(2, F_p))
;; =============================================================================

(deftest fuse-determinism
  (run-check "fuse-determinism"))

(deftest fuse-stays-in-the-group
  (run-check "fuse-stays-in-the-group"))

(deftest fuse-associative
  (run-check "fuse-associative"))

(deftest fuse-identity
  (run-check "fuse-identity"))

(deftest fuse-non-commutative
  (run-check "fuse-non-commutative"))

(deftest fuse-inverse-both-sides
  (run-check "fuse-inverse-both-sides"))

(deftest unfuse-recovers
  (run-check "unfuse-recovers"))

(deftest fuse-bytes-composes-over-concatenation
  (run-check "fuse-bytes-composes-over-concatenation"))

(deftest typed-value-hash-determinism
  (run-check "typed-value-hash-determinism"))

(deftest different-types-different-hashes
  (run-check "different-types-different-hashes"))

;; =============================================================================
;; The prime and the generator table
;; =============================================================================

(def ^:private p (biginteger (host/sl2-p)))
(def ^:private q1 (biginteger "12584965754885075420060417285770960830067539916786805238668377903311008971511"))
(def ^:private q2 (biginteger "18877448632327613130090625928656441245101309875180207858002566854966513457267"))

(deftest the-prime-and-its-factorizations
  (testing "p is the documented 256-bit prime, of no special form"
    (is (= (biginteger "75509794529310452520362503714625764980405239500720831432010267419866053829067") p))
    (is (= 256 (.bitLength p)))
    (is (= "a6f107770d78545e22069b32e798d267aa12b38be8c28a84036bd6b4e0ed61cb" (.toString p 16)))
    (is (.isProbablePrime p 100))
    (is (> (.bitCount (.add p java.math.BigInteger/ONE)) 1) "p + 1 is not a power of two")
    (is (> (.bitCount (.subtract (.shiftLeft java.math.BigInteger/ONE 256) p)) 20)
        "2^256 - p is not small: p is not pseudo-Mersenne (no cheap special reduction)"))
  (testing "p - 1 = 2 * 3 * q1, with q1 a 253-bit prime"
    (is (= (.subtract p java.math.BigInteger/ONE) (.multiply (biginteger 6) q1)))
    (is (= 253 (.bitLength q1)))
    (is (.isProbablePrime q1 100)))
  (testing "p + 1 = 2^2 * q2, with q2 a 254-bit prime"
    (is (= (.add p java.math.BigInteger/ONE) (.multiply (biginteger 4) q2)))
    (is (= 254 (.bitLength q2)))
    (is (.isProbablePrime q2 100))))

(def ^:private I hash/identity-state)

(defn- large-order?
  "Exact order test from the factorizations: order divides p-1 (split) or p+1
   (non-split); it is a multiple of q1 or q2 when g^6 or g^4 is not 1."
  [g]
  (let [t (.mod (.add ^java.math.BigInteger (nth g 0) ^java.math.BigInteger (nth g 3)) p)
        disc (.mod (.subtract (.multiply t t) (biginteger 4)) p)]
    (cond
      (zero? (.signum disc)) false
      (= java.math.BigInteger/ONE (.modPow disc (.shiftRight (.subtract p java.math.BigInteger/ONE) 1) p))
      (not= I (pow-by-squaring g (biginteger 6)))
      :else (not= I (pow-by-squaring g (biginteger 4))))))

(deftest the-byte-generator-table
  (let [gens hash/byte->state]
    (testing "256 distinct, canonical, determinant-1 matrices"
      (is (= 256 (count gens)))
      (is (= 256 (count (set gens))))
      (is (every? hash/state-valid? gens)))
    (testing "every generator has order >= 2^252 (a multiple of q1 or q2)"
      (is (every? large-order? gens)))
    (testing "no two generators commute (no abelian shortcuts), all 32,640 pairs"
      (is (every? (fn [[i j]]
                    (not= (hash/fuse (nth gens i) (nth gens j))
                          (hash/fuse (nth gens j) (nth gens i))))
                  (for [i (range 256) j (range (inc i) 256)] [i j]))))))

(deftest montgomery-reference-agrees-with-the-kernel
  ;; The portable kernel reduces with the host's modulo. A port without
  ;; bignums reduces with Montgomery (REDC) or Barrett; this is the reference
  ;; for those constants (docs/design/fuse-sl2.md). R = 2^264, not 2^256: p is
  ;; above 2^255, and a sum of two products is below 2 p^2, so with R = 2^256
  ;; REDC could need two subtractions; R = 2^264 > 2^8 * 2p keeps it to one.
  (let [r-bits 264
        R (.shiftLeft java.math.BigInteger/ONE r-bits)
        mask (.subtract R java.math.BigInteger/ONE)
        p' (.mod (.negate (.modInverse p R)) R)           ; -p^-1 mod R
        redc (fn [^java.math.BigInteger t]
               (let [m (.and (.multiply (.and t mask) p') mask)
                     u (.shiftRight (.add t (.multiply m p)) r-bits)]
                 (if (>= (.compareTo u p) 0) (.subtract u p) u)))
        to-mont (fn [^java.math.BigInteger x] (.mod (.shiftLeft x r-bits) p))
        from-mont redc
        mont-mat (fn [[a b c d] [e f g h]]
                   (let [ad (fn [x y z w] (redc (.add (.multiply x z) (.multiply y w))))]
                     [(ad a b e g) (ad a b f h) (ad c d e g) (ad c d f h)]))]
    (testing "constants"
      (is (= (.mod (.multiply p p') R) (.subtract R java.math.BigInteger/ONE)) "p * p' = -1 mod R")
      (is (= "a6f107770d78545e22069b32e798d267aa12b38be8c28a84036bd6b4e0ed61cb" (.toString p 16))))
    (testing "Montgomery products equal the kernel's on 200 random pairs"
      (let [rnd (java.util.Random. 14)
            gen (fn [] (hash/fuse-bytes (vec (repeatedly 6 #(.nextInt rnd 256)))))]
        (dotimes [_ 200]
          (let [a (gen) b (gen)
                mc (mapv (fn [x] (from-mont (to-mont x))) a)
                prod (mont-mat (mapv to-mont a) (mapv to-mont b))]
            (is (= a mc))
            (is (= (hash/fuse a b) (mapv from-mont prod)))))))))

;; =============================================================================
;; Issue #14: the collision family of the old nilpotent fuse
;; =============================================================================

(defn- fs [^String s] (hash/fuse-str s))

(deftest issue-14-named-collisions-are-gone
  (run-check "issue-14-named-collisions-are-gone"))

(deftest issue-14-through-values
  (run-check "issue-14-through-values"))

(deftest every-short-word-has-its-own-state
  (run-check "every-short-word-has-its-own-state"))

(deftest digits-0000-to-9999-are-all-distinct
  (run-check "digits-0000-to-9999-are-all-distinct"))

;; =============================================================================
;; Repeated values: A^n returns to the identity only at a multiple of A's order
;; =============================================================================

(deftest long-runs-of-one-element
  (doseq [a [(fs "a") (fs "dacite") (hash/ref->state (hash/state->ref (fs "x")))
             (nth hash/byte->state 0)]]
    (testing "A^(2^k) is never the identity for k up to 40, and all differ"
      (let [powers (take 41 (iterate #(hash/fuse % %) a))]    ; A^(2^0) .. A^(2^40)
        (is (not-any? #(= I %) powers))
        (is (= 41 (count (set powers))))))
    (testing "A^(2^40) by squaring is not the identity, not any shorter run"
      (let [big (pow-by-squaring a (.shiftLeft java.math.BigInteger/ONE 40))]
        (is (not= I big))
        (is (hash/state-valid? big))
        (is (= (hash/fuse big big) (pow-by-squaring a (.shiftLeft java.math.BigInteger/ONE 41))) "A^(2^40) squared is A^(2^41)")
        (is (not-any? #(= big %) (map #(pow-by-fusing a %) (range 1 200))))))
    (testing "the first 300 powers of A are distinct and none is the identity but A^0"
      (let [ps (take 300 (iterate #(hash/fuse % a) a))]
        (is (= 300 (count (set ps))))
        (is (not-any? #(= I %) ps))
        (is (= (pow-by-squaring a (biginteger 299)) (nth ps 298)))))
    (testing "A^(order) is the identity: the order divides p-1, p+1 or p"
      (let [ord-candidates [(.multiply (biginteger 6) q1) (.multiply (biginteger 4) q2)
                            (.multiply (biginteger 2) p) p]]
        (is (some #(= I (pow-by-squaring a %)) ord-candidates))))))

(deftest a-run-wraps-only-after-the-order
  (let [a (fs "a")
        t (.mod (.add ^java.math.BigInteger (nth a 0) ^java.math.BigInteger (nth a 3)) p)
        disc (.mod (.subtract (.multiply t t) (biginteger 4)) p)
        split? (= java.math.BigInteger/ONE (.modPow disc (.shiftRight (.subtract p java.math.BigInteger/ONE) 1) p))
        n (if split? (.multiply (biginteger 6) q1) (.multiply (biginteger 4) q2))]
    (testing "A^n = identity at n = p-1 (split) or p+1 (non-split), >= 2^252"
      (is (>= (.bitLength n) 253))
      (is (= I (pow-by-squaring a n)))
      (is (= a (pow-by-squaring a (.add n java.math.BigInteger/ONE)))))
    (testing "and not at n/q for each prime factor q (the order is the full n up to the small factors)"
      (is (not= I (pow-by-squaring a (.divide n (if split? q1 q2))))))))

;; =============================================================================
;; Why the prime is 256 bits: a collision in ~sqrt(p) at a small prime
;; =============================================================================

(deftest small-prime-collision
  ;; The same hash (a product of 256 random generators of SL(2, F_p) along a
  ;; byte string) over a 36-bit prime has two different byte strings with one
  ;; product, found by the Borel-subgroup attack (about 1.8e7 multiplications,
  ;; 92 * sqrt(p); fixtures/fuse-sl2/attack/). The search is Python and took
  ;; 15 s; this replays the result in milliseconds. At the shipped 256-bit p
  ;; the same attack is about 2^134 multiplications.
  (let [{:keys [p generators s1 s2]} (clojure.edn/read-string
                                      (slurp "../../fixtures/fuse-sl2/small-prime-collision.edn"))
        p (bigint p)
        generators (mapv #(mapv bigint %) generators)
        hex->bytes (fn [h] (mapv #(Integer/parseInt (subs h (* 2 %) (+ 2 (* 2 %))) 16)
                                 (range (quot (count h) 2))))
        mul (fn [[a b c d] [e f g h]]
              [(mod (+ (* a e) (* b g)) p) (mod (+ (* a f) (* b h)) p)
               (mod (+ (* c e) (* d g)) p) (mod (+ (* c f) (* d h)) p)])
        prod (fn [bs] (reduce (fn [m i] (mul m (nth generators i))) [1N 0N 0N 1N] bs))
        b1 (hex->bytes s1)
        b2 (hex->bytes s2)]
    (is (= 36 (.bitLength (biginteger p))) "a 36-bit prime")
    (is (.isProbablePrime (biginteger p) 50))
    (is (= 256 (count generators)))
    (is (every? (fn [[a b c d]] (= 1 (mod (- (* a d) (* b c)) p))) generators) "all in SL(2, F_p)")
    (is (not= b1 b2) "two different byte strings")
    (is (= (prod b1) (prod b2)) "with the same product")
    (is (not= [1 0 0 1] (prod b1)))
    (is (< 300 (count b1) 500))))

;; =============================================================================
;; Refs: digest and expansion
;; =============================================================================

(deftest a-ref-is-a-32-byte-digest-of-a-state
  (run-check "a-ref-is-a-32-byte-digest-of-a-state"))

(deftest digest-of-identity-and-of-the-hand-computed-fold
  (run-check "digest-of-identity-and-of-the-hand-computed-fold"))

(deftest digest-bits-are-uniform
  (run-check "digest-bits-are-uniform"))

;; =============================================================================
;; State bytes and hex
;; =============================================================================

(deftest state-bytes-and-hex-round-trip
  (run-check "state-bytes-and-hex-round-trip"))

(deftest invalid-states-are-refused
  (run-check "invalid-states-are-refused"))

;; =============================================================================
;; Cross-platform fixtures (nbb reads the same file)
;; =============================================================================

(deftest the-kernel-boundary-agrees-with-the-known-answers
  (run-check "the-kernel-boundary-agrees-with-the-known-answers"))

(deftest fixture-vectors-match
  (run-check "fixture-vectors-match"))


(deftest test-encode-value
  (run-check "test-encode-value"))

(deftest test-typed-value-hash-string-type
  (run-check "test-typed-value-hash-string-type"))

(deftest test-typed-value-hash-domain-separator
  (run-check "test-typed-value-hash-domain-separator"))

(deftest test-fuse-seq
  (run-check "test-fuse-seq"))

(deftest test-byte-state-table
  (run-check "test-byte-state-table"))

(deftest test-fuse-bytes
  (run-check "test-fuse-bytes"))

(deftest test-node-hash
  (run-check "test-node-hash"))

(deftest test-hex-conversion
  (run-check "test-hex-conversion"))

(deftest test-bytes-longs-conversion
  (run-check "test-bytes-longs-conversion"))

;; Until all of hash_test is shared, this guard checks that every shared
;; check runs here, not that the two lists are the same.
(deftest every-check-has-a-deftest
  (is (every? (set (map #(-> % meta :name str)
                        (filter #(-> % meta :test) (vals (ns-interns 'dacite.hash-test)))))
              (keys checks/checks))
      "every check in hash_checks.cljc has a deftest here"))
