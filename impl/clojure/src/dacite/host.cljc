(ns dacite.host
  "Platform abstraction for Dacite's portable core.

   Everything the pure core needs from the host language lives behind this
   namespace: 64-bit modular arithmetic, the handful of 64-bit bit ops used
   for hash navigation, popcount, byte<->long<->hex conversion, the
   canonical big-endian encodings for scalar values, and the real-number
   operations the reveal clock uses (`exp`, `log`, `next-up`, `floor`).
   The rest of the core (dacite.hash, dacite.value.*, dacite.reveal.engine)
   is plain portable Clojure built on top.

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
   a top-level do to namespace vars."
  (:refer-clojure :exclude [infinite?]))

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
;; SL(2, F_p) kernel (the fuse group)
;; =============================================================================
;;
;; A fuse *state* is a 2x2 matrix [a b c d] over F_p with ad - bc = 1, p the
;; 256-bit prime below. Cells are host bignums in 0..p-1 (java.math.BigInteger
;; on the JVM, BigInt on ClojureScript/nbb), held in a persistent vector.
;; Reduction is the host's own modulo: p has no special form (it is chosen
;; for the factors of p-1 and p+1, see docs/design/fuse-sl2.md), so a port
;; without bignums reduces with Montgomery (R = 2^264) or Barrett; both give
;; the same canonical residues, and the test suite carries a Montgomery
;; reference that is checked against this kernel.

(def sl2-p-decimal
  "p = 75509794529310452520362503714625764980405239500720831432010267419866053829067
     = 0xa6f107770d78545e22069b32e798d267aa12b38be8c28a84036bd6b4e0ed61cb (256 bits).
   p - 1 = 2 * 3 * q1, q1 = 12584965754885075420060417285770960830067539916786805238668377903311008971511
   (253-bit prime); p + 1 = 2^2 * q2, q2 = 18877448632327613130090625928656441245101309875180207858002566854966513457267
   (254-bit prime)."
  "75509794529310452520362503714625764980405239500720831432010267419866053829067")

(def sl2-cell-bytes
  "A cell on the wire: 32 big-endian bytes."
  32)

(def sl2-state-bytes
  "A state on the wire: four cells, 128 bytes."
  (* 4 sl2-cell-bytes))

(def ^:private SL2-P
  #?(:clj (java.math.BigInteger. ^String sl2-p-decimal)
     :cljs (js/BigInt sl2-p-decimal)))

(defn sl2-p
  "The field modulus as a host bignum."
  []
  SL2-P)

(defn big
  "Coerce an integer (or a decimal string) to a host bignum."
  [n]
  #?(:clj (if (string? n) (java.math.BigInteger. ^String n) (biginteger n))
     :cljs (js/BigInt n)))

(def sl2-identity
  "The identity matrix [1 0 0 1]."
  [(big 1) (big 0) (big 0) (big 1)])

#?(:clj
   (defn- bi
     "A cell as a BigInteger. A state read back from EDN (the file store) holds
      Long or clojure.lang.BigInt cells; the kernel takes them all."
     ^java.math.BigInteger [x]
     (if (instance? java.math.BigInteger x) x (biginteger x))))

(defn sl2-mul
  "Matrix product of two states (cells mod p). The group operation of fuse."
  [x y]
  #?(:clj
     (let [^java.math.BigInteger a (bi (nth x 0)) ^java.math.BigInteger b (bi (nth x 1))
           ^java.math.BigInteger c (bi (nth x 2)) ^java.math.BigInteger d (bi (nth x 3))
           ^java.math.BigInteger e (bi (nth y 0)) ^java.math.BigInteger f (bi (nth y 1))
           ^java.math.BigInteger g (bi (nth y 2)) ^java.math.BigInteger h (bi (nth y 3))
           ^java.math.BigInteger p SL2-P]
       [(.mod (.add (.multiply a e) (.multiply b g)) p)
        (.mod (.add (.multiply a f) (.multiply b h)) p)
        (.mod (.add (.multiply c e) (.multiply d g)) p)
        (.mod (.add (.multiply c f) (.multiply d h)) p)])
     :cljs
     (let [a (nth x 0) b (nth x 1) c (nth x 2) d (nth x 3)
           e (nth y 0) f (nth y 1) g (nth y 2) h (nth y 3)]
       [(mod (+ (* a e) (* b g)) SL2-P)
        (mod (+ (* a f) (* b h)) SL2-P)
        (mod (+ (* c e) (* d g)) SL2-P)
        (mod (+ (* c f) (* d h)) SL2-P)])))

(defn sl2-inverse
  "Inverse of a determinant-1 matrix: the adjugate [d -b -c a] (no products)."
  [x]
  #?(:clj
     (let [^java.math.BigInteger p SL2-P
           neg (fn [v] (let [^java.math.BigInteger v (bi v)]
                         (if (zero? (.signum v)) v (.subtract p v))))]
       [(bi (nth x 3)) (neg (nth x 1)) (neg (nth x 2)) (bi (nth x 0))])
     :cljs
     (let [neg (fn [v] (if (= v ZERO) v (- SL2-P v)))]
       [(nth x 3) (neg (nth x 1)) (neg (nth x 2)) (nth x 0)])))

(defn sl2-valid?
  "True when x is four canonical cells (0 <= cell < p) with ad - bc = 1 mod p."
  [x]
  (and (vector? x)
       (= 4 (count x))
       #?(:clj
          (let [^java.math.BigInteger p SL2-P]
            (and (every? (fn [v]
                           (and (integer? v)
                                (let [^java.math.BigInteger v (bi v)]
                                  (and (>= (.signum v) 0) (neg? (.compareTo v p))))))
                         x)
                 (let [^java.math.BigInteger a (bi (nth x 0)) ^java.math.BigInteger b (bi (nth x 1))
                       ^java.math.BigInteger c (bi (nth x 2)) ^java.math.BigInteger d (bi (nth x 3))]
                   (= java.math.BigInteger/ONE
                      (.mod (.subtract (.multiply a d) (.multiply b c)) p)))))
          :cljs
          (and (every? (fn [v] (and (>= v ZERO) (< v SL2-P))) x)
               (= ONE (mod (- (* (nth x 0) (nth x 3)) (* (nth x 1) (nth x 2))) SL2-P))))))

(defn sl2->bytes
  "A state to 128 big-endian bytes: cells a, b, c, d, 32 bytes each. A cell
   must be below 2^256 (a valid state's is below p)."
  [x]
  #?(:clj
     (into []
           (mapcat (fn [v]
                     (let [^bytes bs (.toByteArray (bi v))
                           n (alength bs)
                           ;; toByteArray is signed and as short as it can be
                           lo (max 0 (- n sl2-cell-bytes))]
                       (when (some #(not (zero? (aget bs %))) (range lo))
                         (throw (ex-info "fuse cell does not fit 32 bytes" {:reason :bad-state})))
                       (concat (repeat (max 0 (- sl2-cell-bytes n)) 0)
                               (map #(bit-and 0xFF (int (aget bs %))) (range lo n))))))
           x)
     :cljs
     (into []
           (mapcat (fn [v]
                     (map (fn [i]
                            (js/Number (bit-and (bit-shift-right v (js/BigInt (* 8 (- (dec sl2-cell-bytes) i))))
                                                BYTE-MASK)))
                          (range sl2-cell-bytes))))
           x)))

(defn bytes->sl2
  "128 big-endian bytes to four cells. Not validated: see `sl2-valid?`."
  [bs]
  (mapv (fn [cell]
          #?(:clj (java.math.BigInteger. 1 (byte-array (map unchecked-byte cell)))
             :cljs (reduce (fn [acc b] (+ (* acc (js/BigInt 256)) (js/BigInt b))) ZERO cell)))
        (partition sl2-cell-bytes bs)))

(defn sl2-digest-words
  "The 32-byte digest of a state as four 64-bit words (the form of a ref).

   digest = a xor rotl64(b) xor rotl128(c) xor rotl192(d), where each cell is
   read as a 256-bit integer and rotl-k rotates it left by k bits (a whole
   number of 64-bit words). Every digest word takes exactly one word from each
   cell, each from a different position, so a cell is not interchangeable with
   another, and each digest bit has at least two input bits that are uniform
   over F_p (only the top word of a cell is skewed, by p being below 2^256)."
  [x]
  #?(:clj
     (let [mask (.subtract (.shiftLeft java.math.BigInteger/ONE 256) java.math.BigInteger/ONE)
           rotl (fn [^java.math.BigInteger n r]
                  (.and (.or (.shiftLeft n (int r)) (.shiftRight n (int (- 256 r)))) mask))
           ^java.math.BigInteger dg
           (reduce (fn [^java.math.BigInteger acc i]
                     (.xor acc (rotl (bi (nth x i)) (* 64 i))))
                   java.math.BigInteger/ZERO (range 4))]
       (mapv (fn [s] (.longValue (.shiftRight dg (int s)))) [192 128 64 0]))
     :cljs
     (let [mask (- (bit-shift-left ONE (js/BigInt 256)) ONE)
           rotl (fn [n r]
                  (bit-and (bit-or (bit-shift-left n (js/BigInt r))
                                   (bit-shift-right n (js/BigInt (- 256 r))))
                           mask))
           dg (reduce (fn [acc i] (bit-xor acc (rotl (nth x i) (* 64 i)))) ZERO (range 4))]
       (mapv (fn [s] (u64 (bit-shift-right dg (js/BigInt s)))) [192 128 64 0]))))

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

(defn- code-unit
  "The UTF-16 code unit of `s` at index `i`, as a long."
  [s i]
  #?(:clj (long (int (.charAt ^String s (int i))))
     :cljs (long (.charCodeAt s i))))

(defn lone-surrogate-index
  "The UTF-16 index of the first lone surrogate (a high surrogate with no low
   one after it, or a low one with no high one before it) in `s`, or nil.
   Such a string is not a sequence of Unicode scalar values: it has no UTF-8
   form, so Dacite does not accept it (see `check-text!`)."
  [s]
  (let [s (str s)
        n (long (count s))]
    (loop [i 0]
      (when (< i n)
        (let [c (code-unit s i)]
          (cond
            (< c 0xD800) (recur (inc i))
            (< c 0xDC00) (if (and (< (inc i) n)
                                  (let [d (code-unit s (inc i))]
                                    (and (>= d 0xDC00) (< d 0xE000))))
                           (recur (+ i 2))
                           i)
            (< c 0xE000) i
            :else (recur (inc i))))))))

(defn- lone!
  "Throw the lone-surrogate error for the surrogate at UTF-16 index `i`."
  [s i]
  (let [u (code-unit s i)]
    (throw (ex-info (str "a lone surrogate (U+"
                         #?(:clj (format "%04X" u)
                            :cljs (.padStart (.toUpperCase (.toString u 16)) 4 "0"))
                         " at UTF-16 index " i ") is not a Unicode scalar value;"
                         " Dacite text is a sequence of code points and has no"
                         " encoding for it")
                    {:reason :lone-surrogate
                     :index i
                     :code-unit u}))))

(defn check-text!
  "Return `s`, or throw `ex-info` with `:reason :lone-surrogate` when it holds
   a lone surrogate. The same check, with the same data, on every host: the
   JVM would encode a lone surrogate as `?` and a JS host as U+FFFD, so the
   same text would be two different values."
  [s]
  (when-let [i (lone-surrogate-index s)]
    (lone! (str s) i))
  s)

(defn utf8-bytes
  "UTF-8 bytes of a string as a vector of ints 0..255. A string with a lone
   surrogate has no UTF-8 form: it throws (`check-text!`)."
  [s]
  (check-text! s)
  #?(:clj
     (mapv (fn [b] (bit-and 0xFF (int b))) (.getBytes ^String s "UTF-8"))
     :cljs
     (vec (.encode (js/TextEncoder.) s))))

(defn utf8-decode
  "Decode a seq of UTF-8 bytes (ints 0..255) back to a string. Malformed
   UTF-8 (a bad lead or continuation byte, an overlong form, a surrogate, a
   value above U+10FFFF, a truncated sequence) throws `ex-info` with
   `:reason :invalid-utf8` instead of becoming U+FFFD. A leading U+FEFF stays."
  [bs]
  #?(:clj
     (let [dec (doto (.newDecoder java.nio.charset.StandardCharsets/UTF_8)
                 (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT)
                 (.onUnmappableCharacter java.nio.charset.CodingErrorAction/REPORT))]
       (try
         (str (.decode dec (java.nio.ByteBuffer/wrap (byte-array (map unchecked-byte bs)))))
         (catch java.nio.charset.CharacterCodingException e
           (throw (ex-info "malformed UTF-8" {:reason :invalid-utf8} e)))))
     :cljs
     (try
       (.decode (js/TextDecoder. "utf-8" #js {:fatal true :ignoreBOM true})
                (js/Uint8Array. (clj->js (vec bs))))
       (catch :default e
         (throw (ex-info "malformed UTF-8" {:reason :invalid-utf8} e))))))

;; -----------------------------------------------------------------------------
;; Characters: one code point (a Unicode scalar value), on every host
;;
;; A Dacite string is a sequence of code points. Host strings are UTF-16, so
;; these helpers are the only places that map between the two. A character is
;; `\a` (a java.lang.Character) on the JVM when it is in the BMP, and a one-
;; code-point String when it is not (a Character is 16 bits); in ClojureScript
;; it is always a one-code-point String.
;; -----------------------------------------------------------------------------

(defn- pair-at?
  "True when a surrogate pair starts at UTF-16 index `i` of `s` (length `n`)."
  [s i n]
  (let [c (code-unit s i)]
    (and (>= c 0xD800) (< c 0xDC00) (< (inc i) n)
         (let [d (code-unit s (inc i))]
           (and (>= d 0xDC00) (< d 0xE000))))))

(defn code-point-at
  "The code point at UTF-16 index `i` of `s`, as a long. A lone surrogate
   throws (`check-text!`)."
  [s i]
  (let [s (str s)
        n (long (count s))
        c (code-unit s i)]
    (cond
      (or (< c 0xD800) (>= c 0xE000)) c
      (pair-at? s i n) (+ 0x10000
                          (bit-shift-left (- c 0xD800) 10)
                          (- (code-unit s (inc i)) 0xDC00))
      :else (lone! s i))))

#?(:cljs
   (def ^:private surrogate-re
     "Any UTF-16 surrogate unit. A page of text without one (the common case)
      has one code point per unit, so counting and indexing it need no scan."
     (js/RegExp. "[\\uD800-\\uDFFF]")))

#?(:cljs
   (defn- no-surrogate?
     "True when host string `s` has no surrogate unit at all (a native regex
      scan, far cheaper than a loop in ClojureScript). JVM: not used."
     [s]
     (not (.test surrogate-re s))))

(defn cp-count
  "Number of code points of a host string (its UTF-16 length minus one for
   each surrogate pair). JVM: `String.codePointCount` (constant time for a
   Latin-1 string); JS: the length when there is no surrogate, else a scan."
  [s]
  (let [s (str s)]
    #?(:clj (.codePointCount ^String s 0 (.length ^String s))
       :cljs (if (no-surrogate? s)
               (.-length s)
               (let [n (long (count s))]
                 (loop [i 0 acc 0]
                   (if (>= i n)
                     acc
                     (recur (if (pair-at? s i n) (+ i 2) (inc i)) (inc acc)))))))))

(defn cp-offset
  "The UTF-16 index that is `k` code points after UTF-16 index `from` in `s`
   (`k` may reach the end of `s`, not pass it; else `ex-info`). JVM:
   `String.offsetByCodePoints`; JS: `from + k` when there is no surrogate."
  [s from k]
  (let [s (str s)
        n (long (count s))
        from (long from)
        k (long k)]
    (when (or (neg? k) (neg? from) (> from n) (> k (- n from)))
      (throw (ex-info "code point offset out of range" {:from from :k k})))
    #?(:clj (try (.offsetByCodePoints ^String s (int from) (int k))
                 (catch IndexOutOfBoundsException _
                   (throw (ex-info "code point offset out of range" {:from from :k k}))))
       :cljs (if (no-surrogate? s)
               (if (> (+ from k) n)
                 (throw (ex-info "code point offset out of range" {:from from :k k}))
                 (+ from k))
               (loop [i from k k]
                 (if (zero? k)
                   i
                   (do (when (>= i n)
                         (throw (ex-info "code point offset out of range" {:from from :k k})))
                       (recur (if (pair-at? s i n) (+ i 2) (inc i)) (dec k)))))))))

(defn cp-subs
  "The substring of `s` of code points `[start, end)` (or to the end)."
  ([s start]
   (let [s (str s)] (subs s (cp-offset s 0 start))))
  ([s start end]
   (let [s (str s)
         a (cp-offset s 0 start)
         b (cp-offset s a (- end start))]
     (subs s a b))))

(defn code-point->char
  "The character of code point `cp`."
  [cp]
  #?(:clj (if (< cp 0x10000)
            (char cp)
            (String. (Character/toChars (int cp))))
     :cljs (.fromCodePoint js/String cp)))

(defn chars-of
  "The characters of host string `s` as a seq, one per code point (nil when
   empty). A supplementary character is one element, not two."
  [s]
  (let [s (str s)
        n (long (count s))]
    (loop [i 0 out (transient [])]
      (if (>= i n)
        (seq (persistent! out))
        (if (pair-at? s i n)
          (recur (+ i 2) (conj! out (subs s i (+ i 2))))
          (recur (inc i) (conj! out #?(:clj (.charAt ^String s (int i))
                                       :cljs (subs s i (inc i))))))))))

(defn char-at
  "The character at code point index `i` of host string `s`."
  [s i]
  (let [s (str s)
        a (cp-offset s 0 i)
        n (long (count s))]
    (if (pair-at? s a n)
      (subs s a (+ a 2))
      #?(:clj (.charAt ^String s (int a))
         :cljs (subs s a (inc a))))))

(defn one-char?
  "True when `x` is a character: on the JVM a `char`, or a String of one
   supplementary code point (a surrogate pair; a BMP character is always a
   `char`, so a character has one form); in ClojureScript a String of one
   code point."
  [x]
  #?(:clj (or (char? x) (and (string? x) (= 2 (count x)) (= 1 (cp-count x))))
     :cljs (and (string? x) (= 1 (cp-count x)))))

;; =============================================================================
;; Real numbers and hash identity for the reveal clock
;; =============================================================================
;; These do not affect root hashes. The reveal engine calls them so a core
;; namespace never names Math, Double, or js/Math itself.

(defn exp
  "e to the power `x`."
  [x]
  #?(:clj (Math/exp (double x))
     :cljs (js/Math.exp x)))

(defn log
  "Natural logarithm of `x`."
  [x]
  #?(:clj (Math/log (double x))
     :cljs (js/Math.log x)))

(defn finite?
  "True when `x` is a finite double."
  [x]
  #?(:clj (Double/isFinite (double x))
     :cljs (js/isFinite x)))

(defn infinite?
  "True when `x` is positive or negative infinity."
  [x]
  #?(:clj (Double/isInfinite (double x))
     :cljs (and (number? x) (not (js/isFinite x)) (not (js/isNaN x)))))

(defn floor
  "Greatest integer less than or equal to `x`. A long on the JVM, a number
   on JavaScript."
  [x]
  #?(:clj (long (Math/floor (double x)))
     :cljs (js/Math.floor x)))

(defn next-up
  "The next double after `x`, matching Java's Math/nextUp.

   Adding half an ulp rounds back to `x` at powers of two, so on
   JavaScript the IEEE-754 bit pattern itself is stepped. Positive zero
   and negative zero both step to the smallest positive value."
  [x]
  #?(:clj (Math/nextUp (double x))
     :cljs
     (let [x (double x)]
       (cond
         (js/isNaN x) x
         (= x js/Number.POSITIVE_INFINITY) x
         (= x js/Number.NEGATIVE_INFINITY) (- js/Number.MAX_VALUE)
         :else
         (let [x (if (zero? x) 0.0 x)
               buf (js/ArrayBuffer. 8)
               view (js/DataView. buf)]
           (.setFloat64 view 0 x false)
           (let [hi (.getUint32 view 0 false)
                 lo (.getUint32 view 4 false)
                 [hi lo] (if (neg? x)
                           (if (zero? lo)
                             [(dec hi) 4294967295]
                             [hi (dec lo)])
                           (if (= lo 4294967295)
                             [(inc hi) 0]
                             [hi (inc lo)]))]
             (.setUint32 view 0 hi false)
             (.setUint32 view 4 lo false)
             (.getFloat64 view 0 false)))))))

(defn hash-key
  "What a set remembers a content hash by.

   On the JVM the four-word vector is a fine set element. On JavaScript a
   hash is four BigInts, and hashing a BigInt throws once a set grows past
   eight, so the hex string stands for it."
  [h]
  #?(:clj h
     :cljs (longs->hex h)))
