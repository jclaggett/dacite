(ns dacite.hash-checks
  "Dacite hashing invariants, on the JVM and on nbb. The JVM runs these
   checks from dacite.hash-test, one deftest each under its old name, with
   test.check for the properties (which were defspecs, 100 trials each).
   nbb runs them from `dacite.hash-cljs-test` (`npm run hash:test`);
   test.check is not on nbb, so there the properties run the same number
   of cases from a fixed seed (`dacite.value.seeded-gen`, no shrinking,
   smaller numbers).

   `is` and `testing` are `dacite.store.check`."
  (:require #?(:clj [clojure.test.check :as tc])
            #?(:clj [clojure.test.check.generators :as gen]
               :cljs [dacite.value.seeded-gen :as gen])
            #?(:clj [clojure.test.check.properties :as prop])
            [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.kernel-vectors :as kernel-vectors]
            [dacite.store :as store]
            [dacite.store.check :refer [is testing]]
            [dacite.value :as v]
            [dacite.value.types :as types]
            [dacite.value.scalar]))

(defn- property
  "The property `f` holds for `n` draws from `gens`: test.check on the JVM,
   a fixed seed on nbb. One assertion, as a defspec reports one."
  [n gens f]
  (let [r #?(:clj (tc/quick-check n (prop/for-all* gens f))
             :cljs (gen/quick-check n (apply gen/tuple gens) #(apply f %) 14))]
    (is (:pass? r) (str "seed " (:seed r) " failed on " (pr-str (:shrunk r))))))

;; =============================================================================
;; Host pieces
;; =============================================================================

(defn host-bytes
  "Host bytes for the ints `xs` (0..255, or signed bytes)."
  [xs]
  #?(:clj (byte-array (map unchecked-byte xs))
     :cljs (js/Uint8Array.from (clj->js (map #(bit-and 0xff %) xs)))))

(defn- distinct-states
  "The distinct states of `states` (SCI cannot hash a BigInt, so nbb keys
   them by hex)."
  [states]
  #?(:clj (set states) :cljs (set (map hash/state->hex states))))

(defn- four-digits
  "`i` (0..9999) as four digits, as (format \"%04d\" i)."
  [i]
  (let [s (str i)] (str (subs "0000" (count s)) s)))

(defn- seeded-bytes
  "A function of no arguments returning bytes 0..255 from `seed`:
   java.util.Random on the JVM, as before; `dacite.value.seeded-gen` on nbb."
  [seed]
  #?(:clj (let [rnd (java.util.Random. (long seed))] #(.nextInt rnd 256))
     :cljs (let [g (gen/choose 0 255) rng (volatile! seed)] #(g rng))))

(defn- u64-word
  "A long as a hash word: itself on the JVM, an unsigned BigInt on nbb."
  [n]
  #?(:clj n :cljs (js/BigInt.asUintN 64 (js/BigInt n))))

(defn- read-i64
  "The big-endian i64 of 8 bytes (ints)."
  [bs]
  #?(:clj (.getLong (java.nio.ByteBuffer/wrap (host-bytes bs)))
     :cljs (js/Number (.getBigInt64 (js/DataView. (.-buffer (host-bytes bs))) 0))))

(defn- read-f64
  "The big-endian f64 of 8 bytes (ints)."
  [bs]
  #?(:clj (.getDouble (java.nio.ByteBuffer/wrap (host-bytes bs)))
     :cljs (.getFloat64 (js/DataView. (.-buffer (host-bytes bs))) 0)))

(defn- read-utf8
  [bs]
  #?(:clj (String. ^bytes (host-bytes bs) "UTF-8")
     :cljs (.decode (js/TextDecoder.) (host-bytes bs))))

;; Bignums: BigInteger on the JVM, BigInt on nbb (cells of a state are both).

(defn- big [x] #?(:clj (biginteger x) :cljs (js/BigInt x)))
(defn- b+ [a b] #?(:clj (.add (biginteger a) (biginteger b)) :cljs (+ a b)))
(defn- b- [a b] #?(:clj (.subtract (biginteger a) (biginteger b)) :cljs (- a b)))
(defn- bmod [a m] #?(:clj (.mod (biginteger a) (biginteger m)) :cljs (mod a m)))
(defn- shl [a n] #?(:clj (.shiftLeft (biginteger a) (int n)) :cljs (bit-shift-left a (js/BigInt n))))
(defn- shr [a n] #?(:clj (.shiftRight (biginteger a) (int n)) :cljs (bit-shift-right a (js/BigInt n))))
(defn- band [a b] #?(:clj (.and (biginteger a) (biginteger b)) :cljs (bit-and a b)))
(defn- bor [a b] #?(:clj (.or (biginteger a) (biginteger b)) :cljs (bit-or a b)))
(defn- bxor [a b] #?(:clj (.xor (biginteger a) (biginteger b)) :cljs (bit-xor a b)))

(defn- low-word
  "The low 64 bits of `a` as a hash word (BigInteger.longValue on the JVM)."
  [a]
  #?(:clj (.longValue (biginteger a)) :cljs (js/BigInt.asUintN 64 a)))

(defn- parse-hex-byte [h] #?(:clj (Integer/parseInt h 16) :cljs (js/parseInt h 16)))

(defn- b* [a b] #?(:clj (.multiply (biginteger a) (biginteger b)) :cljs (* a b)))
(defn- bdiv [a b] #?(:clj (.divide (biginteger a) (biginteger b)) :cljs (/ a b)))
(defn- bneg [a] #?(:clj (.negate (biginteger a)) :cljs (- a)))
(defn- b>= [a b] #?(:clj (>= (.compareTo (biginteger a) (biginteger b)) 0) :cljs (>= a b)))
(defn- bzero? [a] #?(:clj (zero? (.signum (biginteger a))) :cljs (= a (js/BigInt 0))))

(defn- bit-length
  [a]
  #?(:clj (.bitLength (biginteger a))
     :cljs (if (= a (js/BigInt 0)) 0 (count (.toString (if (< a (js/BigInt 0)) (- (- a) (js/BigInt 1)) a) 2)))))

(defn- set-bits
  "Set bits of a nonnegative `a`."
  [a]
  #?(:clj (.bitCount (biginteger a))
     :cljs (count (filter #{"1"} (.toString a 2)))))

(defn- test-bit? [a i] #?(:clj (.testBit (biginteger a) (int i)) :cljs (= (js/BigInt 1) (bit-and (bit-shift-right a (js/BigInt i)) (js/BigInt 1)))))

(defn- ->hex [a] #?(:clj (.toString (biginteger a) 16) :cljs (.toString a 16)))

(defn- mod-pow
  [b e m]
  #?(:clj (.modPow (biginteger b) (biginteger e) (biginteger m))
     :cljs (loop [r (js/BigInt 1) b (mod b m) e e]
             (if (= e (js/BigInt 0))
               r
               (recur (if (= (js/BigInt 1) (bit-and e (js/BigInt 1))) (mod (* r b) m) r)
                      (mod (* b b) m)
                      (bit-shift-right e (js/BigInt 1)))))))

(defn- mod-inverse
  [a m]
  #?(:clj (.modInverse (biginteger a) (biginteger m))
     :cljs (loop [r0 m r1 (mod a m) t0 (js/BigInt 0) t1 (js/BigInt 1)]
             (if (= r1 (js/BigInt 0))
               (mod t0 m)
               (let [q (/ r0 r1)]
                 (recur r1 (- r0 (* q r1)) t1 (- t0 (* q t1))))))))

(defn- probable-prime?
  "BigInteger.isProbablePrime on the JVM. On nbb, Miller-Rabin to the first
   `certainty` / 2 prime bases: error at most 4^-(certainty / 2) =
   2^-certainty, the JVM's bound."
  [n certainty]
  #?(:clj (.isProbablePrime (biginteger n) (int certainty))
     :cljs (let [zero (js/BigInt 0) one (js/BigInt 1) n-1 (- n one)
                 [d s] (loop [d n-1 s 0]
                         (if (= zero (bit-and d one)) (recur (bit-shift-right d one) (inc s)) [d s]))
                 bases (map js/BigInt (take (max 1 (quot certainty 2))
                                            (filter (fn [k] (every? #(pos? (mod k %)) (range 2 k)))
                                                    (iterate inc 2))))
                 witness? (fn [a]
                            (let [x (mod-pow a d n)]
                              (and (not= x one) (not= x n-1)
                                   (loop [x x i 1]
                                     (cond (= i s) true
                                           :else (let [x (mod (* x x) n)]
                                                   (if (= x n-1) false (recur x (inc i)))))))))]
             (and (> n one)
                  (every? (fn [a] (if (>= a n) true (and (not= zero (mod n a)) (not (witness? a)))))
                          bases)))))

(defn- pow-by-squaring
  "a^n (n a nonnegative bignum) by repeated squaring."
  [a n]
  (loop [r hash/identity-state b a i 0]
    (if (>= i (bit-length n))
      r
      (recur (if (test-bit? n i) (hash/fuse r b) r) (hash/fuse b b) (inc i)))))

(defn- pow-by-fusing
  "a^n by n fuses (n small)."
  [a n]
  (reduce (fn [r _] (hash/fuse r a)) hash/identity-state (range n)))

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
  (gen/fmap #?(:clj bigint :cljs identity) (gen/large-integer* {:min 0})))

(def ^:private float-max #?(:clj Float/MAX_VALUE :cljs 3.4028234663852886e38))

(def gen-f32
  "Generator for 32-bit floats (within float range)."
  (gen/fmap float (gen/double* {:min (- float-max)
                                :max float-max
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

;; =============================================================================
;; Group laws (SL(2, F_p))
;; =============================================================================

(defn fuse-determinism
  [_]
  (property 100 [gen-state gen-state]
            (fn [a b] (= (hash/fuse a b) (hash/fuse a b)))))

(defn fuse-stays-in-the-group
  [_]
  (property 100 [gen-state gen-state]
            (fn [a b] (hash/state-valid? (hash/fuse a b)))))

(defn fuse-associative
  [_]
  (property 100 [gen-state gen-state gen-state]
            (fn [a b c] (= (hash/fuse a (hash/fuse b c))
                           (hash/fuse (hash/fuse a b) c)))))

(defn fuse-identity
  [_]
  (property 100 [gen-state]
            (fn [a] (and (= a (hash/fuse a hash/identity-state))
                         (= a (hash/fuse hash/identity-state a))))))

(defn fuse-non-commutative
  [_]
  (property 100 [gen-state gen-state]
            (fn [a b] (or (= a b)
                          (not= (hash/fuse a b) (hash/fuse b a))))))

(defn fuse-inverse-both-sides
  [_]
  (property 100 [gen-state]
            (fn [a] (and (= hash/identity-state (hash/fuse (hash/fuse-inverse a) a))
                         (= hash/identity-state (hash/fuse a (hash/fuse-inverse a)))))))

(defn unfuse-recovers
  [_]
  (property 100 [gen-state gen-state]
            (fn [a b] (let [fused (hash/fuse a b)]
                        (and (= a (hash/unfuse fused b))
                             (= b (hash/fuse (hash/fuse-inverse a) fused)))))))

(defn fuse-bytes-composes-over-concatenation
  [_]
  (property 100 [gen-bytes gen-bytes]
            (fn [x y] (= (hash/fuse-bytes (into x y))
                         (hash/fuse (hash/fuse-bytes x) (hash/fuse-bytes y))))))

(defn typed-value-hash-determinism
  [_]
  (property 100 [gen-tagged-scalar]
            (fn [[type-kw value]] (= (types/typed-value-hash [type-kw value])
                                     (types/typed-value-hash [type-kw value])))))

(defn different-types-different-hashes
  [_]
  (property 100 [(gen/elements ["i32" "i64" "u32" "f32" "f64"])
                 (gen/elements ["i32" "i64" "u32" "f32" "f64"])
                 gen/small-integer]
            ;; Same numeric value with different types should hash differently
            (fn [type1 type2 value]
              (or (= type1 type2)
                  (not= (types/typed-value-hash [type1 value])
                        (types/typed-value-hash [type2 value]))))))

(def group-law-checks
  {"fuse-determinism" fuse-determinism
   "fuse-stays-in-the-group" fuse-stays-in-the-group
   "fuse-associative" fuse-associative
   "fuse-identity" fuse-identity
   "fuse-non-commutative" fuse-non-commutative
   "fuse-inverse-both-sides" fuse-inverse-both-sides
   "unfuse-recovers" unfuse-recovers
   "fuse-bytes-composes-over-concatenation" fuse-bytes-composes-over-concatenation
   "typed-value-hash-determinism" typed-value-hash-determinism
   "different-types-different-hashes" different-types-different-hashes})

;; =============================================================================
;; Issue #14: the collision family of the old nilpotent fuse
;; =============================================================================

(defn- fs [s] (hash/fuse-str s))

(defn issue-14-named-collisions-are-gone
  [_]
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

(defn issue-14-through-values
  [_]
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

(defn every-short-word-has-its-own-state
  [_]
  (testing "all 3,905 strings of length 0..5 over abcde, and 5,460 over abcd up to length 6"
    (doseq [[alphabet maxlen] [["abcde" 5] ["abcd" 6]]]
      (let [words (loop [acc [""] frontier [""] n 0]
                    (if (= n maxlen)
                      acc
                      (let [next (for [w frontier c alphabet] (str w c))]
                        (recur (into acc next) next (inc n)))))
            states (map fs words)]
        (is (= (count words) (count (distinct-states states))) alphabet)))))

(defn digits-0000-to-9999-are-all-distinct
  [_]
  (is (= 10000 (count (distinct-states (for [i (range 10000)] (fs (four-digits i))))))))

;; =============================================================================
;; Refs: digest and expansion
;; =============================================================================

(defn a-ref-is-a-32-byte-digest-of-a-state
  [_]
  (let [s (fs "hello")
        r (hash/state->ref s)]
    (is (= 4 (count r)))
    (is (= 32 (count (hash/longs->bytes r))))
    (is (= r (hash/state->ref s)) "deterministic")
    (is (not= r (hash/state->ref (fs "hellp"))))
    (is (= (hash/fuse-bytes (hash/longs->bytes r)) (hash/ref->state r))
        "ref->state is fuse_bytes of the ref's bytes")
    (is (hash/state-valid? (hash/ref->state r)))))

(defn digest-of-identity-and-of-the-hand-computed-fold
  [_]
  (let [s (fs "hello")
        mask (b- (shl (big 1) 256) (big 1))
        rotl (fn [n r]
               (band (bor (shl n r) (shr n (- 256 r))) mask))
        d (reduce (fn [acc i] (bxor acc (rotl (nth s i) (* 64 i))))
                  (big 0) (range 4))
        words (mapv #(low-word (shr d %)) [192 128 64 0])]
    (is (= words (hash/state->ref s)))
    (is (= (mapv host/word [1 0 0 1]) (hash/state->ref hash/identity-state))
        "the identity: a = 1, d = 1 rotated left by 192 bits: 1 xor 2^192, words [1 0 0 1]")
    (testing "cells are not interchangeable: a transposed state has another digest"
      (let [[a b c d] s]
        (is (not= (hash/state->ref s) (hash/state->ref [a c b d])))
        (is (not= (hash/state->ref s) (hash/state->ref [d b c a])))))))

(defn digest-bits-are-uniform
  [_]
  (let [next-byte (seeded-bytes 5)
        n 3000
        refs (repeatedly n #(hash/state->ref (hash/fuse-bytes (vec (repeatedly 6 (fn [] (next-byte)))))))
        bits (fn [r] (let [bs (hash/longs->bytes r)]
                       (for [i (range 256)] (bit-and 1 (bit-shift-right (nth bs (quot i 8)) (- 7 (mod i 8)))))))
        counts (reduce (fn [acc r] (mapv + acc (bits r))) (vec (repeat 256 0)) refs)]
    (testing "every one of the 256 digest bits is set 44%..56% of the time over 3,000 states"
      (is (every? #(< (* 0.44 n) % (* 0.56 n)) counts) (pr-str (map-indexed vector counts))))))

;; =============================================================================
;; State bytes and hex
;; =============================================================================

(defn state-bytes-and-hex-round-trip
  [_]
  (doseq [s (concat [hash/identity-state] (map fs ["a" "hello" "abba"]))]
    (let [bs (hash/state->bytes s)]
      (is (= 128 (count bs)))
      (is (every? #(<= 0 % 255) bs))
      (is (= s (hash/bytes->state bs)))
      (is (= s (hash/hex->state (hash/state->hex s))))
      (is (= 256 (count (hash/state->hex s))))))
  (is (= (concat (repeat 31 0) [1] (repeat 64 0) (repeat 31 0) [1])
         (hash/state->bytes hash/identity-state))))

(defn invalid-states-are-refused
  [_]
  (let [s (fs "hello")]
    (is (hash/state-valid? s))
    (testing "a cell at or above p"
      (is (not (hash/state-valid? (assoc s 0 (host/sl2-p)))))
      (is (not (hash/state-valid? (assoc s 1 (b+ (host/sl2-p) (big 5)))))))
    (testing "determinant not 1"
      (is (not (hash/state-valid? (assoc s 3 (bmod (b+ (nth s 3) (big 1)) (host/sl2-p))))))
      (is (not (hash/state-valid? [(big 0) (big 0) (big 0) (big 0)]))))
    (testing "wrong shape"
      (is (not (hash/state-valid? [1 2 3])))
      (is (not (hash/state-valid? nil))))))

;; =============================================================================
;; Cross-platform fixtures (fixtures/fuse-sl2/vectors.edn)
;; =============================================================================

(defn the-kernel-boundary-agrees-with-the-known-answers
  [{:keys [read-vectors]}]
  ;; exactly the functions a WASM or native implementation would replace
  (let [vs (:kernel (read-vectors))]
    (is (= #{:state-mul :bytes->state :ref->state :state->ref :state-valid? :refused} (set (keys vs))))
    (is (every? seq (vals vs)))
    (is (= [] (kernel-vectors/problems hash/kernel vs)))
    (testing "the checker does fail on a wrong kernel"
      (is (seq (kernel-vectors/problems (assoc hash/kernel :state-mul (fn [a _] a)) vs)))
      (is (seq (kernel-vectors/problems (assoc hash/kernel :state-valid? (constantly true)) vs)))
      (is (seq (kernel-vectors/problems (assoc hash/kernel :state->ref (fn [s] (subs s 0 64))) vs))))
    (testing "a refused input throws bad-state"
      (is (= :bad-state (try ((:state-mul hash/kernel) (apply str (repeat 256 "0")) (apply str (repeat 256 "0")))
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e (:reason (ex-data e)))))))))

(defn fixture-vectors-match
  [{:keys [read-vectors]}]
  (let [{:keys [p fuse-str ref->state scalars table-sample]} (read-vectors)]
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

;; =============================================================================
;; encode-value, typed-value-hash, fuse-seq, fuse-bytes, node-hash, hex
;; =============================================================================

(defn test-encode-value
  [_]
  (testing "null encodes to 0 bytes"
    (is (= [] (vec (types/encode-value ["null" nil])))))
  (testing "bool encodes to 1 byte"
    (is (= [1] (vec (types/encode-value ["bool" true]))))
    (is (= [0] (vec (types/encode-value ["bool" false])))))
  (testing "i64 encodes to 8 big-endian bytes"
    (let [result (types/encode-value ["i64" 42])]
      (is (= 8 (count result)))
      (is (= 42 (read-i64 result)))))
  (testing "f64 encodes to 8 IEEE 754 bytes"
    (let [result (types/encode-value ["f64" 3.14])]
      (is (= 8 (count result)))
      (is (= 3.14 (read-f64 result)))))
  (testing "char encodes to UTF-8"
    (let [result (types/encode-value ["char" \a])]
      (is (= "a" (read-utf8 result)))))
  (testing "u8 encodes to 1 byte"
    (is (= [42] (vec (types/encode-value ["u8" 42])))))
  (testing "default falls back to pr-str for unknown types"
    (let [result (types/encode-value ["custom" {:x 1}])]
      (is (every? #(<= 0 % 255) result))
      (is (= "{:x 1}" (read-utf8 result))))))

(defn test-typed-value-hash-string-type
  [_]
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

(defn test-typed-value-hash-domain-separator
  [_]
  (testing "different type/data boundaries produce different hashes"
    ;; Without a domain separator, type="i64" data="2" and type="i6" data="42"
    ;; would collide because fuse(fuse-str(a), fuse-str(b)) = fuse-str(a++b)
    (let [h1 (types/typed-value-hash ["ab" "cd"])
          h2 (types/typed-value-hash ["abc" "d"])]
      (is (not= h1 h2) "domain separator prevents boundary collision"))))

(defn test-fuse-seq
  [_]
  (testing "fuse-seq of empty sequence is identity"
    (is (= hash/identity-state (hash/fuse-seq []))))
  (testing "fuse-seq of single state returns that state"
    (let [h (hash/fuse-str "hello")]
      (is (= h (hash/fuse-seq [h])))))
  (testing "fuse-seq is equivalent to chained fuse"
    (let [a (hash/fuse-str "a") b (hash/fuse-str "b") c (hash/fuse-str "c")]
      (is (= (hash/fuse (hash/fuse a b) c) (hash/fuse-seq [a b c]))))))

(defn test-byte-state-table
  [_]
  (testing "byte->state has 256 entries, each a vector of 4 cells"
    (is (= 256 (count hash/byte->state)))
    (is (every? #(and (vector? %) (= 4 (count %))) hash/byte->state))))

(defn test-fuse-bytes
  [_]
  (testing "fuse-bytes of empty array is identity"
    (is (= hash/identity-state (hash/fuse-bytes (host-bytes [])))))
  (testing "fuse-bytes of single byte matches byte->state"
    (is (= (nth hash/byte->state 65) (hash/fuse-bytes [65]))))
  (testing "signed host bytes are masked"
    (is (= (hash/fuse-bytes [255 128]) (hash/fuse-bytes (host-bytes [-1 -128])))))
  (testing "fuse-str equals fuse-bytes of UTF-8"
    (is (= (hash/fuse-str "héllo") (hash/fuse-bytes (host/utf8-bytes "héllo")))))
  (testing "fuse(fuse-str(a), fuse-str(b)) = fuse-str(a ++ b)"
    (is (= (hash/fuse (hash/fuse-str "hello") (hash/fuse-str "world")) (hash/fuse-str "helloworld")))))

(defn test-node-hash
  [_]
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

(defn test-hex-conversion
  [_]
  (testing "round-trip a ref through hex"
    (let [h (hash/state->ref (hash/fuse-str "test"))]
      (is (= h (hash/hex->hash (hash/hash->hex h)))))))

(defn test-bytes-longs-conversion
  [_]
  (testing "bytes->longs converts 32 bytes to 4 longs"
    (let [longs (hash/bytes->longs (host-bytes (repeat 32 0)))]
      (is (vector? longs))
      (is (= 4 (count longs)))))
  (testing "longs->bytes converts 4 longs to 32 portable bytes"
    (let [bytes (hash/longs->bytes [1 2 3 4])]
      (is (vector? bytes))
      (is (= 32 (count bytes)))
      (is (every? #(<= 0 % 255) bytes))))
  (testing "round-trip preserves data"
    (let [original [12345 -67890 111111 -222222]]
      (is (= (mapv u64-word original) (hash/bytes->longs (hash/longs->bytes original)))))))

(def state-and-ref-checks
  {"issue-14-named-collisions-are-gone" issue-14-named-collisions-are-gone
   "issue-14-through-values" issue-14-through-values
   "every-short-word-has-its-own-state" every-short-word-has-its-own-state
   "digits-0000-to-9999-are-all-distinct" digits-0000-to-9999-are-all-distinct
   "a-ref-is-a-32-byte-digest-of-a-state" a-ref-is-a-32-byte-digest-of-a-state
   "digest-of-identity-and-of-the-hand-computed-fold" digest-of-identity-and-of-the-hand-computed-fold
   "digest-bits-are-uniform" digest-bits-are-uniform
   "state-bytes-and-hex-round-trip" state-bytes-and-hex-round-trip
   "invalid-states-are-refused" invalid-states-are-refused
   "the-kernel-boundary-agrees-with-the-known-answers" the-kernel-boundary-agrees-with-the-known-answers
   "fixture-vectors-match" fixture-vectors-match
   "test-encode-value" test-encode-value
   "test-typed-value-hash-string-type" test-typed-value-hash-string-type
   "test-typed-value-hash-domain-separator" test-typed-value-hash-domain-separator
   "test-fuse-seq" test-fuse-seq
   "test-byte-state-table" test-byte-state-table
   "test-fuse-bytes" test-fuse-bytes
   "test-node-hash" test-node-hash
   "test-hex-conversion" test-hex-conversion
   "test-bytes-longs-conversion" test-bytes-longs-conversion})

;; =============================================================================
;; The prime and the generator table
;; =============================================================================

(def ^:private p (big (host/sl2-p)))
(def ^:private q1 (big "12584965754885075420060417285770960830067539916786805238668377903311008971511"))
(def ^:private q2 (big "18877448632327613130090625928656441245101309875180207858002566854966513457267"))

(defn the-prime-and-its-factorizations
  [_]
  (testing "p is the documented 256-bit prime, of no special form"
    (is (= (big "75509794529310452520362503714625764980405239500720831432010267419866053829067") p))
    (is (= 256 (bit-length p)))
    (is (= "a6f107770d78545e22069b32e798d267aa12b38be8c28a84036bd6b4e0ed61cb" (->hex p)))
    (is (probable-prime? p 100))
    (is (> (set-bits (b+ p (big 1))) 1) "p + 1 is not a power of two")
    (is (> (set-bits (b- (shl (big 1) 256) p)) 20)
        "2^256 - p is not small: p is not pseudo-Mersenne (no cheap special reduction)"))
  (testing "p - 1 = 2 * 3 * q1, with q1 a 253-bit prime"
    (is (= (b- p (big 1)) (b* (big 6) q1)))
    (is (= 253 (bit-length q1)))
    (is (probable-prime? q1 100)))
  (testing "p + 1 = 2^2 * q2, with q2 a 254-bit prime"
    (is (= (b+ p (big 1)) (b* (big 4) q2)))
    (is (= 254 (bit-length q2)))
    (is (probable-prime? q2 100))))

(def ^:private I hash/identity-state)

(defn- large-order?
  "Exact order test from the factorizations: order divides p-1 (split) or p+1
   (non-split); it is a multiple of q1 or q2 when g^6 or g^4 is not 1."
  [g]
  (let [t (bmod (b+ (nth g 0) (nth g 3)) p)
        disc (bmod (b- (b* t t) (big 4)) p)]
    (cond
      (bzero? disc) false
      (= (big 1) (mod-pow disc (shr (b- p (big 1)) 1) p))
      (not= I (pow-by-squaring g (big 6)))
      :else (not= I (pow-by-squaring g (big 4))))))

(defn the-byte-generator-table
  [_]
  (let [gens hash/byte->state]
    (testing "256 distinct, canonical, determinant-1 matrices"
      (is (= 256 (count gens)))
      (is (= 256 (count (distinct-states gens))))
      (is (every? hash/state-valid? gens)))
    (testing "every generator has order >= 2^252 (a multiple of q1 or q2)"
      (is (every? large-order? gens)))
    (testing "no two generators commute (no abelian shortcuts), all 32,640 pairs"
      (is (every? (fn [[i j]]
                    (not= (hash/fuse (nth gens i) (nth gens j))
                          (hash/fuse (nth gens j) (nth gens i))))
                  (for [i (range 256) j (range (inc i) 256)] [i j]))))))

(defn montgomery-reference-agrees-with-the-kernel
  [_]
  ;; The portable kernel reduces with the host's modulo. A port without
  ;; bignums reduces with Montgomery (REDC) or Barrett; this is the reference
  ;; for those constants (docs/design/fuse-sl2.md). R = 2^264, not 2^256: p is
  ;; above 2^255, and a sum of two products is below 2 p^2, so with R = 2^256
  ;; REDC could need two subtractions; R = 2^264 > 2^8 * 2p keeps it to one.
  (let [r-bits 264
        R (shl (big 1) r-bits)
        mask (b- R (big 1))
        p' (bmod (bneg (mod-inverse p R)) R)           ; -p^-1 mod R
        redc (fn [t]
               (let [m (band (b* (band t mask) p') mask)
                     u (shr (b+ t (b* m p)) r-bits)]
                 (if (b>= u p) (b- u p) u)))
        to-mont (fn [x] (bmod (shl x r-bits) p))
        from-mont redc
        mont-mat (fn [[a b c d] [e f g h]]
                   (let [ad (fn [x y z w] (redc (b+ (b* x z) (b* y w))))]
                     [(ad a b e g) (ad a b f h) (ad c d e g) (ad c d f h)]))]
    (testing "constants"
      (is (= (bmod (b* p p') R) (b- R (big 1))) "p * p' = -1 mod R")
      (is (= "a6f107770d78545e22069b32e798d267aa12b38be8c28a84036bd6b4e0ed61cb" (->hex p))))
    (testing "Montgomery products equal the kernel's on 200 random pairs"
      (let [next-byte (seeded-bytes 14)
            gen (fn [] (hash/fuse-bytes (vec (repeatedly 6 next-byte))))]
        (dotimes [_ 200]
          (let [a (gen) b (gen)
                mc (mapv (fn [x] (from-mont (to-mont x))) a)
                prod (mont-mat (mapv to-mont a) (mapv to-mont b))]
            (is (= a mc))
            (is (= (hash/fuse a b) (mapv from-mont prod)))))))))

;; =============================================================================
;; Repeated values: A^n returns to the identity only at a multiple of A's order
;; =============================================================================

(defn long-runs-of-one-element
  [_]
  (doseq [a [(fs "a") (fs "dacite") (hash/ref->state (hash/state->ref (fs "x")))
             (nth hash/byte->state 0)]]
    (testing "A^(2^k) is never the identity for k up to 40, and all differ"
      (let [powers (take 41 (iterate #(hash/fuse % %) a))]    ; A^(2^0) .. A^(2^40)
        (is (not-any? #(= I %) powers))
        (is (= 41 (count (distinct-states powers))))))
    (testing "A^(2^40) by squaring is not the identity, not any shorter run"
      (let [big' (pow-by-squaring a (shl (big 1) 40))]
        (is (not= I big'))
        (is (hash/state-valid? big'))
        (is (= (hash/fuse big' big') (pow-by-squaring a (shl (big 1) 41))) "A^(2^40) squared is A^(2^41)")
        (is (not-any? #(= big' %) (map #(pow-by-fusing a %) (range 1 200))))))
    (testing "the first 300 powers of A are distinct and none is the identity but A^0"
      (let [ps (take 300 (iterate #(hash/fuse % a) a))]
        (is (= 300 (count (distinct-states ps))))
        (is (not-any? #(= I %) ps))
        (is (= (pow-by-squaring a (big 299)) (nth ps 298)))))
    (testing "A^(order) is the identity: the order divides p-1, p+1 or p"
      (let [ord-candidates [(b* (big 6) q1) (b* (big 4) q2)
                            (b* (big 2) p) p]]
        (is (some #(= I (pow-by-squaring a %)) ord-candidates))))))

(defn a-run-wraps-only-after-the-order
  [_]
  (let [a (fs "a")
        t (bmod (b+ (nth a 0) (nth a 3)) p)
        disc (bmod (b- (b* t t) (big 4)) p)
        split? (= (big 1) (mod-pow disc (shr (b- p (big 1)) 1) p))
        n (if split? (b* (big 6) q1) (b* (big 4) q2))]
    (testing "A^n = identity at n = p-1 (split) or p+1 (non-split), >= 2^252"
      (is (>= (bit-length n) 253))
      (is (= I (pow-by-squaring a n)))
      (is (= a (pow-by-squaring a (b+ n (big 1))))))
    (testing "and not at n/q for each prime factor q (the order is the full n up to the small factors)"
      (is (not= I (pow-by-squaring a (bdiv n (if split? q1 q2))))))))

;; =============================================================================
;; Why the prime is 256 bits: a collision in ~sqrt(p) at a small prime
;; =============================================================================

(defn small-prime-collision
  [{:keys [read-small-prime-collision]}]
  ;; The same hash (a product of 256 random generators of SL(2, F_p) along a
  ;; byte string) over a 36-bit prime has two different byte strings with one
  ;; product, found by the Borel-subgroup attack (about 1.8e7 multiplications,
  ;; 92 * sqrt(p); fixtures/fuse-sl2/attack/). The search is Python and took
  ;; 15 s; this replays the result in milliseconds. At the shipped 256-bit p
  ;; the same attack is about 2^134 multiplications.
  (let [{:keys [p generators s1 s2]} (read-small-prime-collision)
        p (big p)
        generators (mapv #(mapv big %) generators)
        hex->bytes (fn [h] (mapv #(parse-hex-byte (subs h (* 2 %) (+ 2 (* 2 %))))
                                 (range (quot (count h) 2))))
        mul (fn [[a b c d] [e f g h]]
              [(bmod (b+ (b* a e) (b* b g)) p) (bmod (b+ (b* a f) (b* b h)) p)
               (bmod (b+ (b* c e) (b* d g)) p) (bmod (b+ (b* c f) (b* d h)) p)])
        prod (fn [bs] (reduce (fn [m i] (mul m (nth generators i))) (mapv big [1 0 0 1]) bs))
        b1 (hex->bytes s1)
        b2 (hex->bytes s2)]
    (is (= 36 (bit-length p)) "a 36-bit prime")
    (is (probable-prime? p 50))
    (is (= 256 (count generators)))
    (is (every? (fn [[a b c d]] (= (big 1) (bmod (b- (b* a d) (b* b c)) p))) generators) "all in SL(2, F_p)")
    (is (not= b1 b2) "two different byte strings")
    (is (= (prod b1) (prod b2)) "with the same product")
    (is (not= (mapv big [1 0 0 1]) (prod b1)))
    (is (< 300 (count b1) 500))))

(def number-theory-checks
  {"the-prime-and-its-factorizations" the-prime-and-its-factorizations
   "the-byte-generator-table" the-byte-generator-table
   "montgomery-reference-agrees-with-the-kernel" montgomery-reference-agrees-with-the-kernel
   "long-runs-of-one-element" long-runs-of-one-element
   "a-run-wraps-only-after-the-order" a-run-wraps-only-after-the-order
   "small-prime-collision" small-prime-collision})

(def checks
  (merge group-law-checks state-and-ref-checks number-theory-checks))

(def slow-ms
  "nbb ceilings above `check/max-ms`, each with a JUSTIFICATION on its check."
  {})
