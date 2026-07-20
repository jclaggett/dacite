(ns dacite.host
  "Platform abstraction for Dacite's portable core.

   Everything the pure core needs from the host language lives behind this
   namespace: 64-bit modular arithmetic, the handful of 64-bit bit ops used
   for hash navigation, popcount, byte<->long<->hex conversion, and the
   canonical big-endian encodings for scalar values. The rest of the core
   (dacite.hash, dacite.value.*) is plain portable Clojure built on top.

   Representation of a 64-bit word:
   - JVM / babashka: a native (signed) long. Modular 2^64 arithmetic is
     exactly Clojure's unchecked-* ops.
   - ClojureScript / nbb: a BigInt normalized to the unsigned 64-bit range
     via BigInt.asUintN. Bit patterns match the JVM's two's-complement
     longs, so serialized bytes and hex are identical across hosts.

   Bytes are represented portably as small integers in the range 0..255
   (never host byte arrays), so the core never touches java.nio or JS
   typed arrays directly.

   This is also the porting contract: a Python or C/C++ port implements the
   equivalents of these operations over its native 64-bit integer type.

   NOTE: each operation is a single top-level defn with the host branch in
   its body. We deliberately avoid wrapping several defns in a top-level
   `(do ...)`, because SCI (babashka/nbb) does not promote defns nested in
   a top-level do to namespace vars.")

;; =============================================================================
;; ClojureScript BigInt helpers
;; =============================================================================

#?(:cljs (def ^:private ZERO (js/BigInt 0)))
#?(:cljs (def ^:private ONE (js/BigInt 1)))
#?(:cljs (def ^:private BYTE-MASK (js/BigInt 0xFF)))
#?(:cljs (defn ^:private as-bi
           "Coerce Number or BigInt to BigInt (avoids mixed-arithmetic throws)."
           [x]
           (if (= (type x) js/BigInt) x (js/BigInt x))))
#?(:cljs (defn ^:private u64 [x] (.asUintN js/BigInt 64 (as-bi x))))

;; =============================================================================
;; Typed zero (a 64-bit word / a four-word hash)
;; =============================================================================

(def zero-word
  "The additive identity 64-bit word (native 0 on JVM, 0n on JS)."
  #?(:clj 0 :cljs (js/BigInt 0)))

(def zero-hash
  "The identity hash [0 0 0 0], with host-typed words."
  [zero-word zero-word zero-word zero-word])

(defn word
  "Coerce a small nonneg integer to a 64-bit word."
  [n]
  #?(:clj (long n) :cljs (js/BigInt n)))

(defn word->int
  "Coerce a small 64-bit word back to a native integer (for indexing)."
  [w]
  #?(:clj (int w) :cljs (js/Number w)))

;; =============================================================================
;; 64-bit modular arithmetic
;; =============================================================================

(defn add64 [a b]
  #?(:clj (unchecked-add (long a) (long b)) :cljs (u64 (+ (as-bi a) (as-bi b)))))

(defn sub64 [a b]
  #?(:clj (unchecked-subtract (long a) (long b)) :cljs (u64 (- (as-bi a) (as-bi b)))))

(defn mul64 [a b]
  #?(:clj (unchecked-multiply (long a) (long b)) :cljs (u64 (* (as-bi a) (as-bi b)))))

(defn neg64 [a]
  #?(:clj (unchecked-negate (long a)) :cljs (u64 (- (as-bi a)))))

;; =============================================================================
;; 64-bit bit operations (used by hash navigation)
;; =============================================================================

(defn band64 [a b]
  #?(:clj (bit-and (long a) (long b)) :cljs (u64 (bit-and (as-bi a) (as-bi b)))))

(defn bor64 [a b]
  #?(:clj (bit-or (long a) (long b)) :cljs (u64 (bit-or (as-bi a) (as-bi b)))))

(defn bxor64 [a b]
  #?(:clj (bit-xor (long a) (long b)) :cljs (u64 (bit-xor (as-bi a) (as-bi b)))))

(defn bnot64 [a]
  #?(:clj (bit-not (long a)) :cljs (u64 (bit-not (as-bi a)))))

(defn shl64 [a n]
  #?(:clj (bit-shift-left (long a) (int n))
     :cljs (u64 (bit-shift-left (as-bi a) (js/BigInt n)))))

(defn ushr64 [a n]
  #?(:clj (unsigned-bit-shift-right (long a) (int n))
     ;; the value is u64-normalized (nonneg), so arithmetic >> is logical >>>
     :cljs (u64 (bit-shift-right (u64 a) (js/BigInt n)))))

(defn popcount [a]
  #?(:clj (Long/bitCount (long a))
     :cljs (loop [x (u64 (js/BigInt a)) c 0]
             (if (= x ZERO)
               c
               (recur (u64 (bit-shift-right x ONE))
                      (+ c (js/Number (bit-and x ONE))))))))

(defn ->int32
  "Fold a 64-bit word to a signed 32-bit int (for hashCode/hasheq)."
  [a]
  #?(:clj (unchecked-int (long a)) :cljs (js/Number (.asIntN js/BigInt 32 a))))

(defn low32
  "The low 32 bits of a word, as a word."
  [a]
  #?(:clj (bit-and (long a) 0xFFFFFFFF) :cljs (u64 (bit-and a (js/BigInt 0xFFFFFFFF)))))

(defn word-zero?
  "True if the word is zero."
  [a]
  #?(:clj (zero? (long a)) :cljs (= a ZERO)))

;; =============================================================================
;; Byte <-> long conversions (a hash is [w0 w1 w2 w3] of four 64-bit words)
;; =============================================================================

(def ^:private long-shifts [56 48 40 32 24 16 8 0])

(defn word->bytes
  "One 64-bit word to eight big-endian bytes (ints 0..255)."
  [x]
  #?(:clj
     (mapv (fn [s] (bit-and 0xFF (unsigned-bit-shift-right (long x) (int s))))
           long-shifts)
     :cljs
     (mapv (fn [s]
             (js/Number (bit-and (bit-shift-right (u64 x) (js/BigInt s)) BYTE-MASK)))
           long-shifts)))

(defn bytes->word
  "Eight big-endian bytes (ints 0..255) to one 64-bit word."
  [bs]
  #?(:clj
     (reduce (fn [acc b] (bit-or (bit-shift-left (long acc) 8) (bit-and (long b) 0xFF)))
             0
             bs)
     :cljs
     (reduce (fn [acc b]
               (u64 (bit-or (bit-shift-left acc (js/BigInt 8))
                            (js/BigInt (bit-and b 0xFF)))))
             ZERO
             bs)))

(defn longs->bytes
  "A hash ([4 words]) to 32 big-endian bytes (vector of ints 0..255)."
  [words]
  (into [] (mapcat word->bytes) words))

(defn bytes->longs
  "32 bytes (seq of ints 0..255) to a hash ([4 words])."
  [bs]
  (mapv bytes->word (partition 8 bs)))

;; =============================================================================
;; Hex conversions
;; =============================================================================

(defn byte->hex [b]
  #?(:clj
     (let [s (Integer/toHexString (bit-and (int b) 0xFF))]
       (if (= 1 (count s)) (str "0" s) s))
     :cljs
     (let [s (.toString (bit-and b 0xFF) 16)]
       (if (= 1 (.-length s)) (str "0" s) s))))

(defn ^:private parse-hex-byte [pair]
  #?(:clj (Integer/parseInt (apply str pair) 16)
     :cljs (js/parseInt (apply str pair) 16)))

(defn longs->hex
  "A hash ([4 words]) to a 64-char lowercase hex string."
  [words]
  (apply str (map byte->hex (longs->bytes words))))

(defn hex->longs
  "A 64-char hex string to a hash ([4 words])."
  [hex]
  (bytes->longs (mapv parse-hex-byte (partition 2 hex))))

;; =============================================================================
;; Canonical scalar encodings (portable bytes: vectors of ints 0..255)
;; =============================================================================

(defn int->bytes-be
  "Two's-complement big-endian bytes of an integer, `width` bytes wide.
   Used for i8..i64 / u8..u64 scalar encodings."
  [n width]
  #?(:clj
     (mapv (fn [i] (bit-and 0xFF (unsigned-bit-shift-right (unchecked-long n)
                                                           (int (* 8 (- width 1 i))))))
           (range width))
     :cljs
     (let [b (u64 (js/BigInt n))]
       (mapv (fn [i]
               (js/Number (bit-and (bit-shift-right b (js/BigInt (* 8 (- width 1 i))))
                                   BYTE-MASK)))
             (range width)))))

(defn f32->bytes
  "IEEE-754 single-precision big-endian bytes (4)."
  [x]
  #?(:clj
     (let [bits (Integer/toUnsignedLong (Float/floatToIntBits (float x)))]
       (mapv (fn [s] (bit-and 0xFF (unsigned-bit-shift-right bits (int s)))) [24 16 8 0]))
     :cljs
     (let [dv (js/DataView. (js/ArrayBuffer. 4))]
       (.setFloat32 dv 0 x false)
       (mapv (fn [i] (.getUint8 dv i)) (range 4)))))

(defn f64->bytes
  "IEEE-754 double-precision big-endian bytes (8)."
  [x]
  #?(:clj
     (let [bits (Double/doubleToLongBits (double x))]
       (mapv (fn [s] (bit-and 0xFF (unsigned-bit-shift-right bits (int s))))
             [56 48 40 32 24 16 8 0]))
     :cljs
     (let [dv (js/DataView. (js/ArrayBuffer. 8))]
       (.setFloat64 dv 0 x false)
       (mapv (fn [i] (.getUint8 dv i)) (range 8)))))

(defn utf8-bytes
  "UTF-8 bytes of a string as a vector of ints 0..255."
  [s]
  #?(:clj
     (mapv (fn [b] (bit-and 0xFF (int b))) (.getBytes ^String s "UTF-8"))
     :cljs
     (vec (.encode (js/TextEncoder.) s))))

(defn utf8-decode
  "Decode a seq of UTF-8 bytes (ints 0..255) back to a string."
  [bs]
  #?(:clj
     (String. (byte-array (map unchecked-byte bs)) "UTF-8")
     :cljs
     (.decode (js/TextDecoder.) (js/Uint8Array. (clj->js (vec bs))))))
