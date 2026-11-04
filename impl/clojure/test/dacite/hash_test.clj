(ns dacite.hash-test
  "Generative tests for Dacite hashing invariants."
  (:require [clojure.edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.kernel-vectors :as kernel-vectors]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.value.types :as types]
            [dacite.value.scalar]))

;; =============================================================================
;; Generators for scalar types
;; =============================================================================

(def gen-null
  "Generator for null values."
  (gen/return nil))

(def gen-bool
  "Generator for boolean values."
  gen/boolean)

(def gen-i8
  "Generator for signed 8-bit integers."
  (gen/fmap byte (gen/choose -128 127)))

(def gen-i16
  "Generator for signed 16-bit integers."
  (gen/fmap short (gen/choose -32768 32767)))

(def gen-i32
  "Generator for signed 32-bit integers."
  gen/small-integer)

(def gen-i64
  "Generator for signed 64-bit integers."
  gen/large-integer)

(def gen-u8
  "Generator for unsigned 8-bit integers (stored as int)."
  (gen/choose 0 255))

(def gen-u16
  "Generator for unsigned 16-bit integers (stored as int)."
  (gen/choose 0 65535))

(def gen-u32
  "Generator for unsigned 32-bit integers (stored as long)."
  (gen/choose 0 4294967295))

(def gen-u64
  "Generator for unsigned 64-bit integers (stored as BigInteger)."
  (gen/fmap bigint (gen/large-integer* {:min 0})))

(def gen-f32
  "Generator for 32-bit floats (within float range)."
  (gen/fmap float (gen/double* {:min (- Float/MAX_VALUE)
                                :max Float/MAX_VALUE
                                :infinite? false
                                :NaN? false})))

(def gen-f64
  "Generator for 64-bit floats."
  gen/double)

(def gen-char
  "Generator for UTF-8 characters."
  gen/char)

;; Composite generator for any scalar value with its type tag
(def gen-tagged-scalar
  "Generator for [type-name value] pairs."
  (gen/one-of
   [(gen/tuple (gen/return "null") gen-null)
    (gen/tuple (gen/return "bool") gen-bool)
    (gen/tuple (gen/return "i8") gen-i8)
    (gen/tuple (gen/return "i16") gen-i16)
    (gen/tuple (gen/return "i32") gen-i32)
    (gen/tuple (gen/return "i64") gen-i64)
    (gen/tuple (gen/return "u8") gen-u8)
    (gen/tuple (gen/return "u16") gen-u16)
    (gen/tuple (gen/return "u32") gen-u32)
    (gen/tuple (gen/return "f32") gen-f32)
    (gen/tuple (gen/return "f64") gen-f64)
    (gen/tuple (gen/return "char") gen-char)]))

;; =============================================================================
;; Generators of fuse states and refs
;; =============================================================================

(def gen-bytes
  "1 to 8 random bytes."
  (gen/vector (gen/choose 0 255) 1 8))

(def gen-state
  "A fuse state: the fused generators of some random bytes."
  (gen/fmap hash/fuse-bytes gen-bytes))

(def gen-ref
  "A ref (32-byte digest): the digest of a random state."
  (gen/fmap hash/state->ref gen-state))

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

(defspec fuse-determinism 100
  (prop/for-all [a gen-state b gen-state]
                (= (hash/fuse a b) (hash/fuse a b))))

(defspec fuse-stays-in-the-group 100
  (prop/for-all [a gen-state b gen-state]
                (hash/state-valid? (hash/fuse a b))))

(defspec fuse-associative 100
  (prop/for-all [a gen-state b gen-state c gen-state]
                (= (hash/fuse a (hash/fuse b c))
                   (hash/fuse (hash/fuse a b) c))))

(defspec fuse-identity 100
  (prop/for-all [a gen-state]
                (and (= a (hash/fuse a hash/identity-state))
                     (= a (hash/fuse hash/identity-state a)))))

(defspec fuse-non-commutative 100
  (prop/for-all [a gen-state b gen-state]
                (or (= a b)
                    (not= (hash/fuse a b) (hash/fuse b a)))))

(defspec fuse-inverse-both-sides 100
  (prop/for-all [a gen-state]
                (and (= hash/identity-state (hash/fuse (hash/fuse-inverse a) a))
                     (= hash/identity-state (hash/fuse a (hash/fuse-inverse a))))))

(defspec unfuse-recovers 100
  (prop/for-all [a gen-state b gen-state]
                (let [fused (hash/fuse a b)]
                  (and (= a (hash/unfuse fused b))
                       (= b (hash/fuse (hash/fuse-inverse a) fused))))))

(defspec fuse-bytes-composes-over-concatenation 100
  (prop/for-all [x gen-bytes y gen-bytes]
                (= (hash/fuse-bytes (into x y))
                   (hash/fuse (hash/fuse-bytes x) (hash/fuse-bytes y)))))

(defspec typed-value-hash-determinism 100
  (prop/for-all [[type-kw value] gen-tagged-scalar]
                (= (types/typed-value-hash [type-kw value])
                   (types/typed-value-hash [type-kw value]))))

(defspec different-types-different-hashes 100
  (prop/for-all [type1 (gen/elements ["i32" "i64" "u32" "f32" "f64"])
                 type2 (gen/elements ["i32" "i64" "u32" "f32" "f64"])
                 value gen/small-integer]
    ;; Same numeric value with different types should hash differently
                (or (= type1 type2)
                    (not= (types/typed-value-hash [type1 value])
                          (types/typed-value-hash [type2 value])))))

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
  (testing "abba and baab (the shortest collision of the old fuse)"
    (is (not= (fs "abba") (fs "baab")))
    (is (not= (hash/state->ref (fs "abba")) (hash/state->ref (fs "baab")))))
  (testing "1221 and 2112"
    (is (not= (fs "1221") (fs "2112"))))
  (testing "x ab y ba z and x ba y ab z"
    (doseq [[x y z] [["" "" ""] ["x" "y" "z"] ["xx" "" "zz"] ["q" "rst" "u"]]]
      (is (not= (fs (str x "ab" y "ba" z)) (fs (str x "ba" y "ab" z)))
          (pr-str [x y z]))))
  (testing "other word pairs of the family x u y v z = x v y u z"
    (doseq [[a b] [["ab" "ba"] ["ab" "ac"] ["xy" "yx"] ["abc" "cba"]]
            [x y z] [["" "m" ""] ["k" "" "j"]]]
      (is (not= (fs (str x a y b z)) (fs (str x b y a z)))))))

(deftest issue-14-through-values
  (let [st (store/mem-store)
        h (fn [x] (v/hash x))]
    (testing "[1 256] vs [256 1] (i64 elements whose words 1-3 are equal)"
      (is (not= (h (v/vector st 1 256)) (h (v/vector st 256 1)))))
    (testing "strings abba/baab and 1221/2112"
      (is (not= (h (v/string st "abba")) (h (v/string st "baab"))))
      (is (not= (h (v/string st "1221")) (h (v/string st "2112")))))
    (testing "[\"ab\" \"ba\"] vs [\"ba\" \"ab\"]"
      (is (not= (h (v/vector st "ab" "ba")) (h (v/vector st "ba" "ab")))))
    (testing "[1 2 2 1] vs [2 1 1 2], nested [[1 2] [2 1]] vs [[2 1] [1 2]]"
      (is (not= (h (v/vector st 1 2 2 1)) (h (v/vector st 2 1 1 2))))
      (is (not= (h (v/vector st (v/vector st 1 2) (v/vector st 2 1)))
                (h (v/vector st (v/vector st 2 1) (v/vector st 1 2))))))
    (testing "[\"ab\" 7 \"ba\"] vs [\"ba\" 7 \"ab\"]"
      (is (not= (h (v/vector st "ab" 7 "ba")) (h (v/vector st "ba" 7 "ab")))))
    (testing "maps with swapped anagram values"
      (is (not= (h (v/map st "k1" "ab" "k2" "ba")) (h (v/map st "k1" "ba" "k2" "ab")))))
    (testing "x [1 256] y [256 1] z over many contexts"
      (is (not= (h (v/vector st 9 1 256 8 256 1 7))
                (h (v/vector st 9 256 1 8 1 256 7)))))))

(deftest every-short-word-has-its-own-state
  (testing "all 3,905 strings of length 0..5 over abcde, and 5,460 over abcd up to length 6"
    (doseq [[alphabet maxlen] [["abcde" 5] ["abcd" 6]]]
      (let [words (loop [acc [""] frontier [""] n 0]
                    (if (= n maxlen)
                      acc
                      (let [next (for [w frontier c alphabet] (str w c))]
                        (recur (into acc next) next (inc n)))))
            states (map fs words)]
        (is (= (count words) (count (set states))) alphabet)))))

(deftest digits-0000-to-9999-are-all-distinct
  (is (= 10000 (count (set (for [i (range 10000)] (fs (format "%04d" i))))))))

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
  (let [s (fs "hello")
        r (hash/state->ref s)]
    (is (= 4 (count r)))
    (is (= 32 (count (hash/longs->bytes r))))
    (is (= r (hash/state->ref s)) "deterministic")
    (is (not= r (hash/state->ref (fs "hellp"))))
    (is (= (hash/fuse-bytes (hash/longs->bytes r)) (hash/ref->state r))
        "ref->state is fuse_bytes of the ref's bytes")
    (is (hash/state-valid? (hash/ref->state r)))))

(deftest digest-of-identity-and-of-the-hand-computed-fold
  (let [s (fs "hello")
        mask (.subtract (.shiftLeft java.math.BigInteger/ONE 256) java.math.BigInteger/ONE)
        rotl (fn [^java.math.BigInteger n r]
               (.and (.or (.shiftLeft n r) (.shiftRight n (- 256 r))) mask))
        d (reduce (fn [^java.math.BigInteger acc i] (.xor acc (rotl (nth s i) (* 64 i))))
                  java.math.BigInteger/ZERO (range 4))
        words (mapv #(.longValue (.shiftRight d %)) [192 128 64 0])]
    (is (= words (hash/state->ref s)))
    (is (= [1 0 0 1] (hash/state->ref hash/identity-state))
        "the identity: a = 1, d = 1 rotated left by 192 bits: 1 xor 2^192, words [1 0 0 1]")
    (testing "cells are not interchangeable: a transposed state has another digest"
      (let [[a b c d] s]
        (is (not= (hash/state->ref s) (hash/state->ref [a c b d])))
        (is (not= (hash/state->ref s) (hash/state->ref [d b c a])))))))

(deftest digest-bits-are-uniform
  (let [rnd (java.util.Random. 5)
        n 3000
        refs (repeatedly n #(hash/state->ref (hash/fuse-bytes (vec (repeatedly 6 (fn [] (.nextInt rnd 256)))))))
        bits (fn [r] (let [bs (hash/longs->bytes r)]
                       (for [i (range 256)] (bit-and 1 (bit-shift-right (nth bs (quot i 8)) (- 7 (mod i 8)))))))
        counts (reduce (fn [acc r] (mapv + acc (bits r))) (vec (repeat 256 0)) refs)]
    (testing "every one of the 256 digest bits is set 44%..56% of the time over 3,000 states"
      (is (every? #(< (* 0.44 n) % (* 0.56 n)) counts) (pr-str (map-indexed vector counts))))))

;; =============================================================================
;; State bytes and hex
;; =============================================================================

(deftest state-bytes-and-hex-round-trip
  (doseq [s (concat [hash/identity-state] (map fs ["a" "hello" "abba"]))]
    (let [bs (hash/state->bytes s)]
      (is (= 128 (count bs)))
      (is (every? #(<= 0 % 255) bs))
      (is (= s (hash/bytes->state bs)))
      (is (= s (hash/hex->state (hash/state->hex s))))
      (is (= 256 (count (hash/state->hex s))))))
  (is (= (concat (repeat 31 0) [1] (repeat 64 0) (repeat 31 0) [1])
         (hash/state->bytes hash/identity-state))))

(deftest invalid-states-are-refused
  (let [s (fs "hello")]
    (is (hash/state-valid? s))
    (testing "a cell at or above p"
      (is (not (hash/state-valid? (assoc s 0 (host/sl2-p)))))
      (is (not (hash/state-valid? (assoc s 1 (.add ^java.math.BigInteger (host/sl2-p) (biginteger 5)))))))
    (testing "determinant not 1"
      (is (not (hash/state-valid? (assoc s 3 (.mod (.add ^java.math.BigInteger (nth s 3) java.math.BigInteger/ONE) ^java.math.BigInteger (host/sl2-p))))))
      (is (not (hash/state-valid? [(biginteger 0) (biginteger 0) (biginteger 0) (biginteger 0)]))))
    (testing "wrong shape"
      (is (not (hash/state-valid? [1 2 3])))
      (is (not (hash/state-valid? nil))))))

;; =============================================================================
;; Cross-platform fixtures (nbb reads the same file)
;; =============================================================================

(defn fixture-vectors []
  (clojure.edn/read-string (slurp "../../fixtures/fuse-sl2/vectors.edn")))

(deftest the-kernel-boundary-agrees-with-the-known-answers
  ;; exactly the functions a WASM or native implementation would replace
  (let [vs (:kernel (fixture-vectors))]
    (is (= #{:state-mul :bytes->state :ref->state :state->ref :state-valid? :refused} (set (keys vs))))
    (is (every? seq (vals vs)))
    (is (= [] (kernel-vectors/problems hash/kernel vs)))
    (testing "the checker does fail on a wrong kernel"
      (is (seq (kernel-vectors/problems (assoc hash/kernel :state-mul (fn [a _] a)) vs)))
      (is (seq (kernel-vectors/problems (assoc hash/kernel :state-valid? (constantly true)) vs)))
      (is (seq (kernel-vectors/problems (assoc hash/kernel :state->ref (fn [s] (subs s 0 64))) vs))))
    (testing "a refused input throws bad-state"
      (is (= :bad-state (try ((:state-mul hash/kernel) (apply str (repeat 256 "0")) (apply str (repeat 256 "0")))
                             (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))))

(deftest fixture-vectors-match
  (let [{:keys [p fuse-str ref->state scalars table-sample]} (fixture-vectors)]
    (is (= (str (host/sl2-p)) p) "the vectors are for this prime")
    (testing "fuse-str: state hex and ref hex"
      (doseq [{:keys [input state ref]} fuse-str]
        (is (= state (hash/state->hex (hash/fuse-str input))) input)
        (is (= ref (hash/hash->hex (hash/state->ref (hash/fuse-str input)))) input)))
    (testing "ref->state of fixed refs"
      (doseq [{:keys [ref state]} ref->state]
        (is (= state (hash/state->hex (hash/ref->state (hash/hex->hash ref)))) ref)))
    (testing "scalar value hashes"
      (doseq [{:keys [type value ref]} scalars]
        (is (= ref (hash/hash->hex (types/typed-value-hash [type (if (= "char" type) (host/char-at value 0) value)])))
            (pr-str [type value]))))
    (testing "a sample of the generator table"
      (doseq [[i hex] table-sample]
        (is (= hex (hash/state->hex (nth hash/byte->state i))))))))

(defn- ->bytes
  "Bridge portable encode-value bytes (vector of ints 0..255, or a host
   byte array for u256) to a Java byte array for JVM assertions."
  [bs]
  (byte-array (map unchecked-byte bs)))

(deftest test-encode-value
  (testing "null encodes to 0 bytes"
    (is (= [] (vec (types/encode-value ["null" nil])))))
  (testing "bool encodes to 1 byte"
    (is (= [1] (vec (types/encode-value ["bool" true]))))
    (is (= [0] (vec (types/encode-value ["bool" false])))))
  (testing "i64 encodes to 8 big-endian bytes"
    (let [result (types/encode-value ["i64" 42])]
      (is (= 8 (count result)))
      (is (= 42 (.getLong (java.nio.ByteBuffer/wrap (->bytes result)))))))
  (testing "f64 encodes to 8 IEEE 754 bytes"
    (let [result (types/encode-value ["f64" 3.14])]
      (is (= 8 (count result)))
      (is (= 3.14 (.getDouble (java.nio.ByteBuffer/wrap (->bytes result)))))))
  (testing "char encodes to UTF-8"
    (let [result (types/encode-value ["char" \a])]
      (is (= "a" (String. (->bytes result) "UTF-8")))))
  (testing "u8 encodes to 1 byte"
    (is (= [42] (vec (types/encode-value ["u8" 42])))))
  (testing "default falls back to pr-str for unknown types"
    (let [result (types/encode-value ["custom" {:x 1}])]
      (is (every? #(<= 0 % 255) result))
      (is (= "{:x 1}" (String. (->bytes result) "UTF-8"))))))

(deftest test-typed-value-hash-string-type
  (testing "typed-value-hash handles string type name"
    (let [h1 (types/typed-value-hash ["my.custom/type" 42])
          h2 (types/typed-value-hash ["my.custom/type" 42])]
      (is (= h1 h2))
      (is (vector? h1))
      (is (= 4 (count h1)))))
  (testing "same type name and data produce same hash"
    (let [h1 (types/typed-value-hash ["i64" 42])
          h2 (types/typed-value-hash ["i64" 42])]
      (is (= h1 h2)))))

(deftest test-typed-value-hash-domain-separator
  (testing "different type/data boundaries produce different hashes"
    ;; Without a domain separator, type="i64" data="2" and type="i6" data="42"
    ;; would collide because fuse(fuse-str(a), fuse-str(b)) = fuse-str(a++b)
    (let [h1 (types/typed-value-hash ["ab" "cd"])
          h2 (types/typed-value-hash ["abc" "d"])]
      (is (not= h1 h2) "domain separator prevents boundary collision"))))

(deftest test-fuse-seq
  (testing "fuse-seq of empty sequence is identity"
    (is (= hash/identity-state (hash/fuse-seq []))))
  (testing "fuse-seq of single state returns that state"
    (let [h (hash/fuse-str "hello")]
      (is (= h (hash/fuse-seq [h])))))
  (testing "fuse-seq is equivalent to chained fuse"
    (let [a (hash/fuse-str "a") b (hash/fuse-str "b") c (hash/fuse-str "c")]
      (is (= (hash/fuse (hash/fuse a b) c) (hash/fuse-seq [a b c]))))))

(deftest test-byte-state-table
  (testing "byte->state has 256 entries, each a vector of 4 cells"
    (is (= 256 (count hash/byte->state)))
    (is (every? #(and (vector? %) (= 4 (count %))) hash/byte->state))))

(deftest test-fuse-bytes
  (testing "fuse-bytes of empty array is identity"
    (is (= hash/identity-state (hash/fuse-bytes (byte-array 0)))))
  (testing "fuse-bytes of single byte matches byte->state"
    (is (= (nth hash/byte->state 65) (hash/fuse-bytes [65]))))
  (testing "signed host bytes are masked"
    (is (= (hash/fuse-bytes [255 128]) (hash/fuse-bytes (byte-array [-1 -128])))))
  (testing "fuse-str equals fuse-bytes of UTF-8"
    (is (= (hash/fuse-str "héllo") (hash/fuse-bytes (.getBytes "héllo" "UTF-8")))))
  (testing "fuse(fuse-str(a), fuse-str(b)) = fuse-str(a ++ b)"
    (is (= (hash/fuse (hash/fuse-str "hello") (hash/fuse-str "world")) (hash/fuse-str "helloworld")))))

(deftest test-node-hash
  (testing "node-hash with the identity elements-fuse is a ref"
    (let [h (types/node-hash "ft/empty" hash/identity-state)]
      (is (vector? h))
      (is (= 4 (count h)))))
  (testing "different node types produce different hashes"
    (let [ef (hash/fuse-str "some-content")]
      (is (not= (types/node-hash "ft/deep" ef) (types/node-hash "ft/digit" ef)))))
  (testing "same type + same elements-fuse = same hash"
    (let [ef (hash/fuse-str "content")]
      (is (= (types/node-hash "ft/deep" ef) (types/node-hash "ft/deep" ef))))))

(deftest test-hex-conversion
  (testing "round-trip a ref through hex"
    (let [h (hash/state->ref (hash/fuse-str "test"))]
      (is (= h (hash/hex->hash (hash/hash->hex h)))))))

(deftest test-bytes-longs-conversion
  (testing "bytes->longs converts 32 bytes to 4 longs"
    (let [longs (hash/bytes->longs (byte-array 32))]
      (is (vector? longs))
      (is (= 4 (count longs)))))
  (testing "longs->bytes converts 4 longs to 32 portable bytes"
    (let [bytes (hash/longs->bytes [1 2 3 4])]
      (is (vector? bytes))
      (is (= 32 (count bytes)))
      (is (every? #(<= 0 % 255) bytes))))
  (testing "round-trip preserves data"
    (let [original [12345 -67890 111111 -222222]]
      (is (= original (hash/bytes->longs (hash/longs->bytes original)))))))
