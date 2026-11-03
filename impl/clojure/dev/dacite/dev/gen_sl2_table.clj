(ns dacite.dev.gen-sl2-table
  "JVM-only regeneration tool for the byte -> SL(2, F_p) generator table.

   Fuse hashes a byte string as the product of one matrix per byte. The 256
   matrices are shipped as data in `dacite.sl2-table` (64 hex bytes each), so
   the portable core needs no host crypto. SHA-256 is used here only as the
   seed function, expanding the old SHA-256 byte table (`dacite.byte-table`)
   into matrix cells.

   Derivation, for each byte value i, counter k = 0, 1, ...:

       stream = SHA-256(\"dacite/fuse-sl2/v1\" ++ k_be4 ++ table[i] ++ j_u8)  for j = 0, 1
       a = first 16 bytes of block 0, b = last 16 of block 0, c = first 16 of block 1
           (each masked to 127 bits)
       reject unless a, b, c < p and a != 0
       d = (1 + b c) / a mod p                (so ad - bc = 1)

   A candidate is also rejected unless its order is huge. With t = a + d:
     - t^2 - 4 = 0 (a unipotent or -unipotent element) is rejected;
     - t^2 - 4 a nonzero square mod p (split torus, order divides p - 1 =
       2 * 3 * q1): keep it only if g^6 != 1, which forces q1 (124 bits) | order;
     - otherwise (non-split torus, order divides p + 1 = 2^2 * 19 * q2): keep it
       only if g^76 != 1, which forces q2 (120 bits) | order.
   So every accepted generator has order at least 2^119. After the table is
   built, no two generators may commute and all 256 must differ.

   Run:  clojure -X:dev dacite.dev.gen-sl2-table/generate!"
  (:require [clojure.java.io :as io]
            [dacite.byte-table :as byte-table])
  (:import [java.math BigInteger]
           [java.security MessageDigest]))

(def ^BigInteger p (BigInteger. "87078471178590763933909596057503143987"))
(def ^BigInteger q1 (BigInteger. "14513078529765127322318266009583857331"))
(def ^BigInteger q2 (BigInteger. "1145769357613036367551442053388199263"))
(def ^BigInteger mask127 (.subtract (.shiftLeft BigInteger/ONE 127) BigInteger/ONE))

(defn- mmul [[^BigInteger a ^BigInteger b ^BigInteger c ^BigInteger d]
             [^BigInteger e ^BigInteger f ^BigInteger g ^BigInteger h]]
  [(.mod (.add (.multiply a e) (.multiply b g)) p)
   (.mod (.add (.multiply a f) (.multiply b h)) p)
   (.mod (.add (.multiply c e) (.multiply d g)) p)
   (.mod (.add (.multiply c f) (.multiply d h)) p)])

(def ident [BigInteger/ONE BigInteger/ZERO BigInteger/ZERO BigInteger/ONE])

(defn mpow [m ^long n]
  (loop [r ident b m n n]
    (cond (zero? n) r
          (odd? n) (recur (mmul r b) (mmul b b) (quot n 2))
          :else (recur r (mmul b b) (quot n 2)))))

(defn mpow-big [m ^BigInteger n]
  (loop [r ident b m i 0]
    (if (>= i (.bitLength n))
      r
      (recur (if (.testBit n i) (mmul r b) r) (mmul b b) (inc i)))))

(defn- sha256 [^bytes bs] (.digest (MessageDigest/getInstance "SHA-256") bs))

(defn- hex->bytes [^String hex]
  (byte-array (map #(unchecked-byte (Integer/parseInt (subs hex (* 2 %) (+ 2 (* 2 %))) 16))
                   (range (/ (count hex) 2)))))

(defn- block [^bytes seed k j]
  (let [label (.getBytes "dacite/fuse-sl2/v1" "UTF-8")
        bb (java.io.ByteArrayOutputStream.)]
    (.write bb label)
    (.write bb (byte-array [(unchecked-byte (bit-shift-right k 24)) (unchecked-byte (bit-shift-right k 16))
                            (unchecked-byte (bit-shift-right k 8)) (unchecked-byte k)]))
    (.write bb seed)
    (.write bb (int j))
    (sha256 (.toByteArray bb))))

(defn- cell [^bytes bs off]
  (.and (BigInteger. 1 (java.util.Arrays/copyOfRange bs (int off) (int (+ off 16)))) mask127))

(defn large-order?
  "The order-size rule above."
  [[^BigInteger a _ _ ^BigInteger d :as g]]
  (let [t (.mod (.add a d) p)
        disc (.mod (.subtract (.multiply t t) (BigInteger/valueOf 4)) p)]
    (cond
      (.equals disc BigInteger/ZERO) false
      (.equals (.modPow disc (.shiftRight (.subtract p BigInteger/ONE) 1) p) BigInteger/ONE)
      (not= ident (mpow g 6))
      :else (not= ident (mpow g 76)))))

(defn candidate [seed k]
  (let [b0 (block seed k 0) b1 (block seed k 1)
        a (cell b0 0) b (cell b0 16) c (cell b1 0)]
    (when (and (neg? (.compareTo a p)) (neg? (.compareTo b p)) (neg? (.compareTo c p))
               (pos? (.signum a)))
      (let [d (.mod (.multiply (.add BigInteger/ONE (.multiply b c)) (.modInverse a p)) p)
            g [a b c d]]
        (when (and (not= g ident) (large-order? g)) g)))))

(defn generator-for-byte [i]
  (let [seed (hex->bytes (nth byte-table/hex-table i))]
    (loop [k 0] (or (candidate seed k) (recur (inc k))))))

(defn- cell-hex [^BigInteger v] (format "%032x" v))
(defn- state-hex [g] (apply str (map cell-hex g)))

(def ^:private target "src/dacite/sl2_table.cljc")

(defn check-table
  "Throws unless all 256 matrices have det 1 and a huge order, differ, and pairwise do not commute."
  [gens]
  (assert (= 256 (count (set gens))))
  (doseq [[a b c d :as g] gens]
    (assert (.equals BigInteger/ONE (.mod (.subtract (.multiply a d) (.multiply b c)) p)))
    (assert (large-order? g)))
  (doseq [i (range 256) j (range (inc i) 256)]
    (assert (not= (mmul (nth gens i) (nth gens j)) (mmul (nth gens j) (nth gens i)))
            (str "generators " i " and " j " commute"))))

(defn generate!
  "Write the generator table to src/dacite/sl2_table.cljc."
  [& _]
  (let [gens (mapv generator-for-byte (range 256))
        sb (StringBuilder.)]
    (check-table gens)
    (.append sb ";; GENERATED FILE — do not edit by hand.\n")
    (.append sb ";; Regenerate with: clojure -X:dev dacite.dev.gen-sl2-table/generate!\n")
    (.append sb "(ns dacite.sl2-table\n")
    (.append sb "  \"Byte -> SL(2, F_p) generator table for the fuse (docs/design/fuse-sl2.md),\n")
    (.append sb "   shipped as portable data. Indexed by unsigned byte value 0..255; each entry\n")
    (.append sb "   is 128 hex chars: the cells a, b, c, d as 16 big-endian bytes each, with\n")
    (.append sb "   p = 87078471178590763933909596057503143987.\")\n\n")
    (.append sb "(def hex-table\n  [")
    (doseq [[i g] (map-indexed vector gens)]
      (when (pos? i) (.append sb "\n   "))
      (.append sb (str "\"" (state-hex g) "\"")))
    (.append sb "])\n")
    (io/make-parents target)
    (spit target (.toString sb))
    (println "Wrote" (count gens) "entries to" target)))
