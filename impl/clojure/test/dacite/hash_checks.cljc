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
            [dacite.store.check :refer [is]]
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

(def checks
  (merge group-law-checks))

(def slow-ms
  "nbb ceilings above `check/max-ms`, each with a JUSTIFICATION on its check."
  {})
