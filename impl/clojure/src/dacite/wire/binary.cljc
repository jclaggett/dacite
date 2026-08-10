(ns dacite.wire.binary
  "Dacite wire format v1 — chunk-only binary codec (JVM + CLJS).

   Spec: docs/spec/wire-v1.md
   Fixtures: fixtures/wire-v1/ (JVM loaders)

   Encodes/decodes ChunkMessage with items as node or literal payloads.
   EDN transport remains in dacite.wire for debug/transition.

   On the JVM, wire bodies are Java byte[]. On CLJS/nbb they are Uint8Array.
   Encode/decode APIs accept either form where conversion is unambiguous."
  (:require [clojure.string :as str]
            [dacite.host :as host]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.value.types :as types]
            #?(:clj [clojure.java.io :as io])))

;; =============================================================================
;; Portable byte arrays (JVM byte[] / CLJS Uint8Array)
;; =============================================================================

(defn- make-bytes
  "Allocate n zeroed wire bytes."
  [n]
  #?(:clj (byte-array (int n))
     :cljs (js/Uint8Array. n)))

(defn byte-len
  "Length of a wire byte array."
  [bs]
  #?(:clj (alength ^bytes bs)
     :cljs (.-length bs)))

(defn- bget
  "Unsigned byte 0..255 at index."
  [bs i]
  #?(:clj (bit-and 0xff (aget ^bytes bs (int i)))
     :cljs (aget bs i)))

(defn- bset
  "Set unsigned byte 0..255 at index."
  [bs i v]
  #?(:clj (aset-byte ^bytes bs (int i) (unchecked-byte (bit-and 0xff v)))
     :cljs (aset bs i (bit-and 0xff v))))

(defn- wire-bytes?
  "True if x is a platform wire byte array."
  [x]
  #?(:clj (bytes? x)
     :cljs (or (instance? js/Uint8Array x)
               (instance? js/ArrayBuffer x))))

(defn as-wire-bytes
  "Normalize ArrayBuffer / seq of 0..255 / wire bytes → platform wire bytes."
  [x]
  (cond
    (nil? x) (make-bytes 0)
    #?(:clj (bytes? x) :cljs (instance? js/Uint8Array x)) x
    #?(:cljs (instance? js/ArrayBuffer x))
    #?(:cljs (js/Uint8Array. x))
    (or (vector? x) (seq? x) (list? x))
    (let [v (vec x)
          a (make-bytes (count v))]
      (dotimes [i (count v)]
        (bset a i (nth v i)))
      a)
    :else
    (throw (ex-info "cannot coerce to wire bytes" {:type (type x)}))))

(defn- bytes-eq?
  [a b]
  (let [a (as-wire-bytes a)
        b (as-wire-bytes b)
        n (byte-len a)]
    (and (= n (byte-len b))
         (loop [i 0]
           (cond
             (= i n) true
             (not= (bget a i) (bget b i)) false
             :else (recur (inc i)))))))

;; =============================================================================
;; Buffer helpers — cursor over a pre-sized wire byte array
;; =============================================================================

(defn- bb
  "Allocate a write buffer of size n. Finish with `finish`."
  [n]
  {:arr (make-bytes n) :pos (volatile! 0)})

(defn- wrap-bytes
  "Read cursor over existing wire bytes."
  [bs]
  {:arr (as-wire-bytes bs) :pos (volatile! 0)})

(defn- finish
  "Return the underlying array (sized exactly at allocate time)."
  [buf]
  (:arr buf))

(defn- remaining
  [buf]
  (- (byte-len (:arr buf)) @(:pos buf)))

(defn- ensure-remaining
  [buf n]
  (when (< (remaining buf) n)
    (throw (ex-info "truncated wire message" {:need n :have (remaining buf)}))))

(defn- put-u8
  [buf n]
  (let [p @(:pos buf)]
    (bset (:arr buf) p n)
    (vreset! (:pos buf) (inc p))))

(defn- put-u32
  [buf n]
  (let [n (bit-and 0xffffffff #?(:clj (long n) :cljs (js/Math.floor n)))]
    (put-u8 buf (bit-and 0xff (unsigned-bit-shift-right n 24)))
    (put-u8 buf (bit-and 0xff (unsigned-bit-shift-right n 16)))
    (put-u8 buf (bit-and 0xff (unsigned-bit-shift-right n 8)))
    (put-u8 buf (bit-and 0xff n))))

(defn- put-u64
  "Write unsigned/modular 64-bit word (host long / BigInt)."
  [buf n]
  (doseq [b (host/word->bytes n)]
    (put-u8 buf b)))

(defn- put-i64
  [buf n]
  (put-u64 buf #?(:clj (long n) :cljs (host/word n))))

(defn- put-f64
  [buf x]
  (doseq [b (host/f64->bytes x)]
    (put-u8 buf b)))

(defn- put-f32
  [buf x]
  (doseq [b (host/f32->bytes x)]
    (put-u8 buf b)))

(defn- put-int-be
  "Write signed/unsigned integer body as big-endian `width` bytes (value-layer layout)."
  [buf n width]
  (doseq [b (host/int->bytes-be n width)]
    (put-u8 buf b)))

(defn- signed-from-be
  "Interpret wire bytes as two's-complement big-endian signed integer."
  [bs]
  (let [bs (as-wire-bytes bs)
        n (byte-len bs)
        mag (loop [i 0 acc 0]
              (if (= i n)
                acc
                (recur (inc i) (+ (* acc 256) (bget bs i)))))
        bits (* 8 n)
        sign (bit-shift-left 1 (dec bits))]
    (if (>= mag sign)
      (- mag (bit-shift-left 1 bits))
      mag)))

(defn- unsigned-from-be
  "Interpret wire bytes as unsigned big-endian integer.
   8-byte values use host word (long/BigInt) bit patterns."
  [bs]
  (let [bs (as-wire-bytes bs)
        n (byte-len bs)]
    (if (= n 8)
      (host/bytes->word (mapv #(bget bs %) (range 8)))
      (loop [i 0 acc 0]
        (if (= i n)
          acc
          (recur (inc i) (+ (* acc 256) (bget bs i))))))))

(defn- get-f32-from-bytes
  [bs]
  (let [bs (as-wire-bytes bs)]
    (when-not (= 4 (byte-len bs))
      (throw (ex-info "f32 data must be 4 bytes" {:n (byte-len bs)})))
    #?(:clj
       ;; unchecked-int: high bit set (negative f32 / -0 / -Inf) yields long ≥ 2^31;
       ;; clojure.core/int throws ArithmeticException on those values.
       (let [bits (unchecked-int
                   (bit-or (bit-shift-left (long (bget bs 0)) 24)
                           (bit-shift-left (long (bget bs 1)) 16)
                           (bit-shift-left (long (bget bs 2)) 8)
                           (long (bget bs 3))))]
         (Float/intBitsToFloat bits))
       :cljs
       (let [dv (js/DataView. (js/ArrayBuffer. 4))]
         (dotimes [i 4]
           (.setUint8 dv i (bget bs i)))
         (.getFloat32 dv 0 false)))))

(defn- u256-body->wire
  "Normalize u256 body (byte-array or seq of 0..255) to 32 wire bytes."
  [body]
  (let [bs (as-wire-bytes body)]
    (when-not (= 32 (byte-len bs))
      (throw (ex-info "u256 must be 32 bytes" {:n (byte-len bs)})))
    bs))

(defn- u256-wire->body
  "Decode u256 data to portable vector of ints 0..255."
  [bs]
  (let [bs (as-wire-bytes bs)]
    (when-not (= 32 (byte-len bs))
      (throw (ex-info "u256 must be 32 bytes" {:n (byte-len bs)})))
    (mapv #(bget bs %) (range 32))))

(defn- put-bytes
  [buf bs]
  (let [bs (as-wire-bytes bs)
        n (byte-len bs)
        p @(:pos buf)
        arr (:arr buf)]
    (dotimes [i n]
      (bset arr (+ p i) (bget bs i)))
    (vreset! (:pos buf) (+ p n))))

(defn- get-u8
  [buf]
  (ensure-remaining buf 1)
  (let [p @(:pos buf)
        v (bget (:arr buf) p)]
    (vreset! (:pos buf) (inc p))
    v))

(defn- get-u32
  [buf]
  (ensure-remaining buf 4)
  (let [b0 (get-u8 buf)
        b1 (get-u8 buf)
        b2 (get-u8 buf)
        b3 (get-u8 buf)]
    #?(:clj (bit-or (bit-shift-left (long b0) 24)
                    (bit-shift-left (long b1) 16)
                    (bit-shift-left (long b2) 8)
                    (long b3))
       :cljs (bit-or (bit-shift-left b0 24)
                     (bit-shift-left b1 16)
                     (bit-shift-left b2 8)
                     b3))))

(defn- get-u64
  "Read 64-bit word as host hash word (long / BigInt)."
  [buf]
  (ensure-remaining buf 8)
  (host/bytes->word [(get-u8 buf) (get-u8 buf) (get-u8 buf) (get-u8 buf)
                     (get-u8 buf) (get-u8 buf) (get-u8 buf) (get-u8 buf)]))

(defn- get-i64
  "Read signed i64; returns host long on JVM, Number when safe on CLJS."
  [buf]
  (let [w (get-u64 buf)]
    #?(:clj (long w)
       :cljs (js/Number (.asIntN js/BigInt 64 w)))))

(defn- get-f64
  [buf]
  (ensure-remaining buf 8)
  (let [bs [(get-u8 buf) (get-u8 buf) (get-u8 buf) (get-u8 buf)
            (get-u8 buf) (get-u8 buf) (get-u8 buf) (get-u8 buf)]]
    #?(:clj
       (let [bits (reduce (fn [acc b]
                            (bit-or (bit-shift-left (long acc) 8)
                                    (bit-and (long b) 0xFF)))
                          0
                          bs)]
         (Double/longBitsToDouble bits))
       :cljs
       (let [dv (js/DataView. (js/ArrayBuffer. 8))]
         (dotimes [i 8]
           (.setUint8 dv i (nth bs i)))
         (.getFloat64 dv 0 false)))))

(defn- get-bytes
  [buf n]
  (ensure-remaining buf n)
  (let [a (make-bytes n)
        p @(:pos buf)
        src (:arr buf)]
    (dotimes [i n]
      (bset a i (bget src (+ p i))))
    (vreset! (:pos buf) (+ p n))
    a))

(defn- put-hash
  [buf [a b c d]]
  (put-u64 buf a)
  (put-u64 buf b)
  (put-u64 buf c)
  (put-u64 buf d))

(defn- get-hash
  [buf]
  [(get-u64 buf) (get-u64 buf) (get-u64 buf) (get-u64 buf)])

;; =============================================================================
;; Constants
;; =============================================================================

(defn- magic-bytes
  []
  (as-wire-bytes [0x44 0x41 0x43 0x31])) ; "DAC1"

(def version 1)
(def msg-type-chunk 0x01)

(def enc-node 0x00)
(def enc-literal 0x01)

(def kind-scalar 0x00)
(def kind-ft 0x01)
(def kind-hamt 0x02)
(def kind-collection 0x03)

;; Literal / scalar type ids (wire-v1.md + intermediate spine literals 2c′)
(def type-id->name
  {0x00 "null"
   0x01 "bool"
   0x02 "char"
   0x03 "i64"
   0x04 "f64"
   0x05 "i8"
   0x06 "i16"
   0x07 "i32"
   0x08 "u8"
   0x09 "u16"
   0x0A "u32"
   0x0B "u64"
   0x0C "f32"
   0x0D "u256"
   0x0E "negative"
   0x10 "string"
   0x11 "blob"
   0x20 "vector"
   0x21 "map"
   0x22 "set"
   ;; Intermediate pack literals: body = ordered leaf lits (or entry pair)
   0x30 "ft/empty"
   0x31 "ft/digit"
   0x32 "ft/node"
   0x33 "ft/deep"
   0x40 "hamt/empty"
   0x41 "hamt/entry"
   0x42 "hamt/bitmap"})

(def public-scalar-types
  "Public scalar type names supported on the wire (node + literal)."
  #{"null" "bool" "char"
    "i8" "i16" "i32" "i64"
    "u8" "u16" "u32" "u64" "u256"
    "f32" "f64"
    "negative"})

(def public-value-types
  "Public first-class value types (node + literal)."
  (into public-scalar-types #{"string" "blob" "vector" "map" "set"}))

(def name->type-id
  (into {} (map (fn [[k v]] [v k]) type-id->name)))

(def coll-id->name
  {0x00 "vector"
   0x01 "string"
   0x02 "blob"
   0x03 "map"
   0x04 "set"})

(def name->coll-id
  (into {} (map (fn [[k v]] [v k]) coll-id->name)))

(def ft-subtype->name
  {0x00 "ft/empty"
   0x02 "ft/digit"
   0x03 "ft/node"
   0x04 "ft/deep"})

(def name->ft-subtype
  (into {} (map (fn [[k v]] [v k]) ft-subtype->name)))

(def hamt-subtype->name
  {0x00 "hamt/empty"
   0x01 "hamt/entry"
   0x02 "hamt/bitmap"})

(def name->hamt-subtype
  (into {} (map (fn [[k v]] [v k]) hamt-subtype->name)))

;; =============================================================================
;; Hex helpers
;; =============================================================================

(defn bytes->hex
  "Lowercase hex of wire bytes (no whitespace)."
  [bs]
  (let [bs (as-wire-bytes bs)
        n (byte-len bs)]
    (apply str (map (fn [i] (host/byte->hex (bget bs i))) (range n)))))

(defn hex->bytes
  "Parse lowercase/uppercase hex string to wire bytes."
  [hex]
  (let [s (str/replace (str hex) #"\s" "")
        n (count s)]
    (when-not (even? n)
      (throw (ex-info "hex length must be even" {:n n})))
    (let [a (make-bytes (quot n 2))]
      (doseq [i (range 0 n 2)]
        (bset a (quot i 2)
              #?(:clj (Integer/parseInt (subs s i (+ i 2)) 16)
                 :cljs (js/parseInt (subs s i (+ i 2)) 16))))
      a)))

(defn hash->bytes
  "32-byte BE encoding of a hash vector."
  [h]
  (let [buf (bb 32)]
    (put-hash buf h)
    (finish buf)))

(defn bytes->hash
  [bs]
  (let [bs (as-wire-bytes bs)]
    (when-not (= 32 (byte-len bs))
      (throw (ex-info "hash must be 32 bytes" {:n (byte-len bs)})))
    (get-hash (wrap-bytes bs))))

(defn dac1-magic?
  "True if body starts with wire-v1 magic DAC1."
  [bs]
  (let [bs (as-wire-bytes bs)]
    (and (>= (byte-len bs) 4)
         (= 0x44 (bget bs 0))
         (= 0x41 (bget bs 1))
         (= 0x43 (bget bs 2))
         (= 0x31 (bget bs 3)))))

;; =============================================================================
;; Measure
;; =============================================================================

(defn- put-measure
  [buf {:keys [count size-bytes elements-fuse]}]
  (put-u64 buf (or count 0))
  (put-u64 buf (or size-bytes 0))
  (put-hash buf elements-fuse))

(defn- get-measure
  [buf]
  {:count (get-u64 buf)
   :size-bytes (get-u64 buf)
   :elements-fuse (get-hash buf)})

;; =============================================================================
;; Literal encode / decode
;; =============================================================================

(declare encode-lit-bytes decode-lit)

(defn- utf8-wire
  "UTF-8 of string as wire bytes."
  [s]
  (as-wire-bytes (host/utf8-bytes (str s))))

(defn- utf8-from-wire
  [bs]
  (host/utf8-decode (mapv #(bget bs %) (range (byte-len bs)))))

(defn encode-lit-bytes
  "Encode a recursive literal form {:type t :body b} to payload bytes.
   body for collections is a vector of nested forms (map: pairs as [k v])."
  [{:keys [type body] :as form}]
  (let [tid (or (name->type-id (str type))
                (throw (ex-info "unknown literal type" {:type type})))]
    (case (str type)
      "null"
      (as-wire-bytes [tid])

      "negative"
      (as-wire-bytes [tid])

      "bool"
      (as-wire-bytes [tid (if body 1 0)])

      "char"
      (let [bs (utf8-wire (str body))
            buf (bb (+ 2 (byte-len bs)))]
        (put-u8 buf tid)
        (put-u8 buf (byte-len bs))
        (put-bytes buf bs)
        (finish buf))

      "i8"
      (let [buf (bb 2)]
        (put-u8 buf tid)
        (put-int-be buf body 1)
        (finish buf))

      "i16"
      (let [buf (bb 3)]
        (put-u8 buf tid)
        (put-int-be buf body 2)
        (finish buf))

      "i32"
      (let [buf (bb 5)]
        (put-u8 buf tid)
        (put-int-be buf body 4)
        (finish buf))

      "i64"
      (let [buf (bb 9)]
        (put-u8 buf tid)
        (put-i64 buf body)
        (finish buf))

      "u8"
      (let [buf (bb 2)]
        (put-u8 buf tid)
        (put-int-be buf body 1)
        (finish buf))

      "u16"
      (let [buf (bb 3)]
        (put-u8 buf tid)
        (put-int-be buf body 2)
        (finish buf))

      "u32"
      (let [buf (bb 5)]
        (put-u8 buf tid)
        (put-int-be buf body 4)
        (finish buf))

      "u64"
      (let [buf (bb 9)]
        (put-u8 buf tid)
        (put-u64 buf body)
        (finish buf))

      "u256"
      (let [bs (u256-body->wire body)
            buf (bb 33)]
        (put-u8 buf tid)
        (put-bytes buf bs)
        (finish buf))

      "f32"
      (let [buf (bb 5)]
        (put-u8 buf tid)
        (put-f32 buf body)
        (finish buf))

      "f64"
      (let [buf (bb 9)]
        (put-u8 buf tid)
        (put-f64 buf body)
        (finish buf))

      "string"
      (let [bs (utf8-wire body)
            buf (bb (+ 5 (byte-len bs)))]
        (put-u8 buf tid)
        (put-u32 buf (byte-len bs))
        (put-bytes buf bs)
        (finish buf))

      "blob"
      (let [bs (if (wire-bytes? body)
                 (as-wire-bytes body)
                 (as-wire-bytes body))
            buf (bb (+ 5 (byte-len bs)))]
        (put-u8 buf tid)
        (put-u32 buf (byte-len bs))
        (put-bytes buf bs)
        (finish buf))

      ("vector" "set" "ft/empty" "ft/digit" "ft/node" "ft/deep"
                "hamt/empty")
      ;; Ordered sequence of nested lits (ft leaf payloads share this shape)
      (let [els (or body [])
            children (mapv encode-lit-bytes els)
            total (reduce + 5 (map byte-len children))
            buf (bb total)]
        (put-u8 buf tid)
        (put-u32 buf (count children))
        (doseq [c children] (put-bytes buf c))
        (finish buf))

      ("map" "hamt/bitmap")
      ;; map: pairs; hamt/bitmap: ordered entry pair lits [k v]
      (let [pairs (mapv (fn [pair]
                          (let [k (nth pair 0)
                                v (nth pair 1)
                                kb (encode-lit-bytes k)
                                vb (encode-lit-bytes v)]
                            [kb vb]))
                        (or body []))
            total (reduce + 5 (map (fn [[k v]] (+ (byte-len k) (byte-len v))) pairs))
            buf (bb total)]
        (put-u8 buf tid)
        (put-u32 buf (count pairs))
        (doseq [[k v] pairs]
          (put-bytes buf k)
          (put-bytes buf v))
        (finish buf))

      "hamt/entry"
      ;; Body is [key-lit val-lit]
      (let [kb (encode-lit-bytes (nth body 0))
            vb (encode-lit-bytes (nth body 1))
            buf (bb (+ 1 (byte-len kb) (byte-len vb)))]
        (put-u8 buf tid)
        (put-bytes buf kb)
        (put-bytes buf vb)
        (finish buf))

      (throw (ex-info "unsupported literal type" {:type type :form form})))))

(defn decode-lit
  "Decode one Lit from buffer; advances position. Returns {:type :body}."
  [buf]
  (ensure-remaining buf 1)
  (let [tid (get-u8 buf)
        tname (or (type-id->name tid)
                  (throw (ex-info "unknown literal type_id" {:id tid})))]
    (case tname
      "null" {:type "null" :body nil}

      "negative" {:type "negative" :body nil}

      "bool"
      (do (ensure-remaining buf 1)
          {:type "bool" :body (not (zero? (get-u8 buf)))})

      "char"
      (let [dlen (get-u8 buf)
            bs (get-bytes buf dlen)]
        {:type "char" :body (first (seq (utf8-from-wire bs)))})

      "i8"
      (do (ensure-remaining buf 1)
          {:type "i8" :body (signed-from-be (get-bytes buf 1))})

      "i16"
      (do (ensure-remaining buf 2)
          {:type "i16" :body (signed-from-be (get-bytes buf 2))})

      "i32"
      (do (ensure-remaining buf 4)
          {:type "i32" :body (signed-from-be (get-bytes buf 4))})

      "i64"
      (do (ensure-remaining buf 8)
          {:type "i64" :body (get-i64 buf)})

      "u8"
      (do (ensure-remaining buf 1)
          {:type "u8" :body (unsigned-from-be (get-bytes buf 1))})

      "u16"
      (do (ensure-remaining buf 2)
          {:type "u16" :body (unsigned-from-be (get-bytes buf 2))})

      "u32"
      (do (ensure-remaining buf 4)
          {:type "u32" :body (unsigned-from-be (get-bytes buf 4))})

      "u64"
      (do (ensure-remaining buf 8)
          {:type "u64" :body (get-u64 buf)})

      "u256"
      (do (ensure-remaining buf 32)
          {:type "u256" :body (u256-wire->body (get-bytes buf 32))})

      "f32"
      (do (ensure-remaining buf 4)
          {:type "f32" :body (get-f32-from-bytes (get-bytes buf 4))})

      "f64"
      (do (ensure-remaining buf 8)
          {:type "f64" :body (get-f64 buf)})

      "string"
      (let [n (get-u32 buf)
            bs (get-bytes buf n)]
        {:type "string" :body (utf8-from-wire bs)})

      "blob"
      (let [n (get-u32 buf)
            bs (get-bytes buf n)]
        {:type "blob" :body (mapv #(bget bs %) (range (byte-len bs)))})

      ("vector" "set" "ft/empty" "ft/digit" "ft/node" "ft/deep"
                "hamt/empty")
      (let [n (int (get-u32 buf))
            kids (mapv (fn [_] (decode-lit buf)) (range n))]
        {:type tname :body kids})

      ("map" "hamt/bitmap")
      (let [n (int (get-u32 buf))
            pairs (mapv (fn [_]
                          [(decode-lit buf) (decode-lit buf)])
                        (range n))]
        {:type tname :body pairs})

      "hamt/entry"
      {:type "hamt/entry"
       :body [(decode-lit buf) (decode-lit buf)]}

      (throw (ex-info "unsupported literal type" {:type tname})))))

(defn decode-lit-bytes
  "Decode a full literal payload byte array."
  [bs]
  (let [buf (wrap-bytes bs)
        form (decode-lit buf)]
    (when (pos? (remaining buf))
      (throw (ex-info "trailing garbage in literal payload"
                      {:remaining (remaining buf)})))
    form))

;; =============================================================================
;; Node encode / decode
;; =============================================================================

(defn- scalar-data-bytes
  "Canonical scalar data bytes (no type_id) from store entry."
  [entry]
  (as-wire-bytes (types/encode-value entry)))

(defn encode-node-bytes
  "Encode a store entry [type-name data] as node payload bytes."
  [[type-name data :as entry]]
  (let [t (str type-name)]
    (cond
      (contains? public-scalar-types t)
      (let [tid (or (name->type-id t)
                    (throw (ex-info "scalar type not in wire-v1 table yet" {:type t})))
            ;; u256 store data may be byte[]; encode-value returns it as-is
            data-bs (if (= "u256" t)
                      (u256-body->wire (second entry))
                      (scalar-data-bytes entry))
            ;; kind + type_id + dlen + data
            buf (bb (+ 3 (byte-len data-bs)))]
        (put-u8 buf kind-scalar)
        (put-u8 buf tid)
        (put-u8 buf (byte-len data-bs))
        (put-bytes buf data-bs)
        (finish buf))

      (str/starts-with? t "ft/")
      (let [sub (or (name->ft-subtype t)
                    (throw (ex-info "unknown ft type" {:type t})))
            m (:measure data)
            children (case t
                       "ft/empty" []
                       "ft/deep" [(:left data) (:spine data) (:right data)]
                       (:children data))
            n (count children)
            buf (bb (+ 2 48 1 (* 32 n)))]
        (put-u8 buf kind-ft)
        (put-u8 buf sub)
        (put-measure buf m)
        (put-u8 buf n)
        (doseq [h children] (put-hash buf h))
        (finish buf))

      (str/starts-with? t "hamt/")
      (case t
        "hamt/empty"
        (let [buf (bb (+ 2 48))]
          (put-u8 buf kind-hamt)
          (put-u8 buf 0)
          (put-measure buf (:measure data))
          (finish buf))

        "hamt/entry"
        (let [buf (bb (+ 2 48 (* 3 32)))]
          (put-u8 buf kind-hamt)
          (put-u8 buf 1)
          (put-measure buf (:measure data))
          (put-hash buf (:key-hash data))
          (put-hash buf (:key-ref data))
          (put-hash buf (:val-ref data))
          (finish buf))

        "hamt/bitmap"
        (let [ch (:children data)
              n (count ch)
              buf (bb (+ 2 48 4 1 (* 32 n)))]
          (put-u8 buf kind-hamt)
          (put-u8 buf 2)
          (put-measure buf (:measure data))
          (put-u32 buf (:bitmap data))
          (put-u8 buf n)
          (doseq [h ch] (put-hash buf h))
          (finish buf))

        (throw (ex-info "unknown hamt type" {:type t})))

      (#{"vector" "string" "blob" "map" "set"} t)
      (let [cid (name->coll-id t)
            buf (bb 50)]
        (put-u8 buf kind-collection)
        (put-u8 buf cid)
        (put-hash buf (:root data))
        (put-u64 buf (:count data 0))
        (put-u64 buf (:size-bytes data 0))
        (finish buf))

      :else
      (throw (ex-info "unsupported node type for wire-v1" {:type t})))))

(defn decode-node-bytes
  "Decode node payload to store entry [type-name data]."
  [bs]
  (let [buf (wrap-bytes bs)
        kind (get-u8 buf)]
    (case kind
      0x00 ; scalar
      (let [tid (get-u8 buf)
            tname (or (type-id->name tid)
                      (throw (ex-info "unknown scalar type_id" {:id tid})))
            dlen (get-u8 buf)
            data-bs (get-bytes buf dlen)]
        (when (pos? (remaining buf))
          (throw (ex-info "trailing garbage in scalar node" {})))
        ;; Reconstruct typed value from canonical bytes (value-layer layouts)
        (case tname
          "null" ["null" nil]
          "negative" ["negative" nil]
          "bool" ["bool" (not (zero? (bget data-bs 0)))]
          "char" ["char" (first (seq (utf8-from-wire data-bs)))]
          "i8" ["i8" (signed-from-be data-bs)]
          "i16" ["i16" (signed-from-be data-bs)]
          "i32" ["i32" (signed-from-be data-bs)]
          "i64" ["i64" (get-i64 (wrap-bytes data-bs))]
          "u8" ["u8" (unsigned-from-be data-bs)]
          "u16" ["u16" (unsigned-from-be data-bs)]
          "u32" ["u32" (unsigned-from-be data-bs)]
          "u64" ["u64" (get-u64 (wrap-bytes data-bs))]
          "u256" ["u256" #?(:clj (let [a (byte-array 32)]
                                   (dotimes [i 32]
                                     (aset-byte a i (unchecked-byte (bget data-bs i))))
                                   a)
                            :cljs (u256-wire->body data-bs))]
          "f32" ["f32" (get-f32-from-bytes data-bs)]
          "f64" ["f64" (get-f64 (wrap-bytes data-bs))]
          (throw (ex-info "scalar type decode not implemented" {:type tname}))))

      0x01 ; ft
      (let [sub (get-u8 buf)
            _ (when (= sub 1)
                (throw (ex-info "ft/single reserved" {:subtype sub})))
            tname (or (ft-subtype->name sub)
                      (throw (ex-info "unknown ft subtype" {:sub sub})))
            m (get-measure buf)
            n (get-u8 buf)
            children (mapv (fn [_] (get-hash buf)) (range n))]
        (when (pos? (remaining buf))
          (throw (ex-info "trailing garbage in ft node" {})))
        (case tname
          "ft/empty" [tname {:measure m}]
          "ft/deep" [tname {:left (nth children 0)
                            :spine (nth children 1)
                            :right (nth children 2)
                            :measure m}]
          [tname {:children children :measure m}]))

      0x02 ; hamt
      (let [sub (get-u8 buf)
            tname (or (hamt-subtype->name sub)
                      (throw (ex-info "unknown hamt subtype" {:sub sub})))
            m (get-measure buf)]
        (case tname
          "hamt/empty"
          (do (when (pos? (remaining buf))
                (throw (ex-info "trailing garbage" {})))
              [tname {:measure m}])

          "hamt/entry"
          (let [kh (get-hash buf) kr (get-hash buf) vr (get-hash buf)]
            (when (pos? (remaining buf))
              (throw (ex-info "trailing garbage" {})))
            [tname {:measure m :key-hash kh :key-ref kr :val-ref vr}])

          "hamt/bitmap"
          (let [bitmap (get-u32 buf)
                n (get-u8 buf)
                ch (mapv (fn [_] (get-hash buf)) (range n))]
            (when (pos? (remaining buf))
              (throw (ex-info "trailing garbage" {})))
            [tname {:measure m :bitmap bitmap :children ch}])))

      0x03 ; collection
      (let [cid (get-u8 buf)
            tname (or (coll-id->name cid)
                      (throw (ex-info "unknown coll_id" {:id cid})))
            root (get-hash buf)
            cnt (get-u64 buf)
            sz (get-u64 buf)]
        (when (pos? (remaining buf))
          (throw (ex-info "trailing garbage in collection node" {})))
        [tname {:root root :count cnt :size-bytes sz}])

      (throw (ex-info "unknown node kind" {:kind kind})))))

;; =============================================================================
;; Chunk message
;; =============================================================================

(defn encode-item
  "Encode one logical item map to bytes (without outer chunk header).

   Item map:
     {:enc :node|:literal
      :hash hash-vector
      :entry [type data]}           ; when :node
      :literal {:type :body}}       ; when :literal
   "
  [{:keys [enc hash entry literal]}]
  (let [payload (case enc
                  :node (encode-node-bytes entry)
                  :literal (encode-lit-bytes literal)
                  (throw (ex-info "enc must be :node or :literal" {:enc enc})))
        enc-b (case enc :node enc-node :literal enc-literal)
        buf (bb (+ 1 32 4 (byte-len payload)))]
    (put-u8 buf enc-b)
    (put-hash buf hash)
    (put-u32 buf (byte-len payload))
    (put-bytes buf payload)
    (finish buf)))

(defn encode-chunk
  "Encode a chunk message map to binary bytes.

   {:budget n
    :items [{:enc :node|:literal :hash h :entry e | :literal form} …]}"
  [{:keys [budget items] :or {budget 0}}]
  (let [item-bs (mapv encode-item items)
        body-len (reduce + 0 (map byte-len item-bs))
        ;; magic4 + ver1 + msg1 + flags1 + n4 + budget4 + items
        buf (bb (+ 4 1 1 1 4 4 body-len))]
    (put-bytes buf (magic-bytes))
    (put-u8 buf version)
    (put-u8 buf msg-type-chunk)
    (put-u8 buf 0)
    (put-u32 buf (count items))
    (put-u32 buf #?(:clj (long (or budget 0)) :cljs (or budget 0)))
    (doseq [ib item-bs] (put-bytes buf ib))
    (finish buf)))

(defn decode-item
  "Decode one Item from buffer."
  [buf]
  (ensure-remaining buf (+ 1 32 4))
  (let [enc-b (get-u8 buf)
        h (get-hash buf)
        plen (int (get-u32 buf))
        payload (get-bytes buf plen)
        enc (case enc-b
              0x00 :node
              0x01 :literal
              (throw (ex-info "unknown item enc" {:enc enc-b})))]
    (case enc
      :node {:enc :node :hash h :entry (decode-node-bytes payload)}
      :literal {:enc :literal :hash h :literal (decode-lit-bytes payload)})))

(defn decode-chunk
  "Decode full ChunkMessage bytes → map."
  [bs]
  (let [buf (wrap-bytes bs)]
    (ensure-remaining buf 12)
    (let [mag (get-bytes buf 4)]
      (when-not (bytes-eq? mag (magic-bytes))
        (throw (ex-info "bad magic" {:got (bytes->hex mag)}))))
    (let [ver (get-u8 buf)
          mtype (get-u8 buf)
          flags (get-u8 buf)
          n (int (get-u32 buf))
          budget (get-u32 buf)]
      (when-not (= ver version)
        (throw (ex-info "unsupported wire version" {:version ver})))
      (when-not (= mtype msg-type-chunk)
        (throw (ex-info "unsupported msg_type" {:msg-type mtype})))
      (when-not (zero? flags)
        (throw (ex-info "nonzero flags reserved in v1" {:flags flags})))
      (let [items (mapv (fn [_] (decode-item buf)) (range n))]
        (when (pos? (remaining buf))
          (throw (ex-info "trailing garbage after chunk" {:remaining (remaining buf)})))
        {:version ver
         :msg-type :chunk
         :flags flags
         :budget budget
         :items items}))))

(defn encode-chunk-hex
  "encode-chunk → lowercase hex string."
  [chunk-map]
  (bytes->hex (encode-chunk chunk-map)))

(defn decode-chunk-hex
  "hex string → decode-chunk."
  [hex]
  (decode-chunk (hex->bytes hex)))

;; =============================================================================
;; Apply to store
;; =============================================================================

(defn apply-chunk-message!
  "Apply a decoded chunk map to store. Verifies literal hashes when
   pack/*verify-literal-hash* is true.

   Returns {:applied n :created [hex…] :exists [hex…]}."
  [st {:keys [items]}]
  (let [created (atom [])
        exists (atom [])]
    (doseq [{:keys [enc hash entry literal]} items]
      (let [hex (store/hash->hex hash)
            had? (store/s-has? st hash)]
        (case enc
          :node
          (do (store/s-put st hash entry)
              (if had?
                (swap! exists conj hex)
                (swap! created conj hex)))

          :literal
          (if had?
            (swap! exists conj hex)
            (let [got (pack/materialize-literal! st (:type literal) (:body literal))]
              (when (and pack/*verify-literal-hash* (not= got hash))
                (throw (ex-info "literal hash mismatch"
                                {:expected hex
                                 :got (store/hash->hex got)})))
              (swap! created conj hex))))))
    {:applied (count items)
     :created @created
     :exists @exists}))

;; =============================================================================
;; Fixtures (JVM)
;; =============================================================================

#?(:clj
   (defn fixture-root
     "Absolute path to fixtures/wire-v1 (repo root), independent of cwd."
     []
     (let [cwd (io/file (System/getProperty "user.dir"))]
       (or (loop [dir cwd]
             (when dir
               (let [f (io/file dir "fixtures" "wire-v1" "cases")]
                 (if (.isDirectory f)
                   (.getCanonicalPath (io/file dir "fixtures" "wire-v1"))
                   (recur (.getParentFile dir))))))
           (throw (ex-info "fixtures/wire-v1 not found walking parents of cwd"
                           {:cwd (.getCanonicalPath cwd)}))))))

#?(:clj
   (defn load-fixture
     "Load a case dir: returns {:description :message-bytes :hash-hex}."
     [case-id]
     (let [root (io/file (fixture-root) "cases" case-id)
           msg-hex (str/trim (slurp (io/file root "message.hex")))
           hash-file (io/file root "hash.hex")
           desc-file (io/file root "description.json")]
       {:id case-id
        :description (when (.exists desc-file) (slurp desc-file))
        :message-hex msg-hex
        :message-bytes (hex->bytes msg-hex)
        :hash-hex (when (.exists hash-file) (str/trim (slurp hash-file)))})))

#?(:clj
   (defn list-fixture-ids
     "Case directory names under fixtures/wire-v1/cases."
     []
     (let [cases (io/file (fixture-root) "cases")]
       (->> (.listFiles cases)
            (filter #(.isDirectory %))
            (map #(.getName %))
            sort
            vec))))

;; =============================================================================
;; Pack EDN chunk bridge (leaf-chunking EDN maps ↔ wire-v1)
;; =============================================================================

(def content-type-chunk-v1
  "application/vnd.dacite.chunk.v1")

(defn binary-content-type?
  "True if Content-Type / Accept prefers wire-v1 binary chunks."
  [ct]
  (when ct
    (let [s (str/lower-case (str ct))]
      (or (str/includes? s "vnd.dacite.chunk")
          (str/includes? s "application/octet-stream")))))

(defn edn-content-type?
  [ct]
  (when ct
    (str/includes? (str/lower-case (str ct)) "edn")))

(defn pack-item->wire-item
  "Convert pack Layer-1 EDN item to wire encode-item map."
  [item]
  (let [h (store/hex->hash (:hash item))
        enc (keyword (:encoding item))]
    (case enc
      :node {:enc :node :hash h :entry (:body item)}
      :literal {:enc :literal
                :hash h
                :literal {:type (str (:type item)) :body (:body item)}}
      (throw (ex-info "unknown pack item encoding" {:encoding enc})))))

(defn wire-item->pack-item
  "Convert wire item map to pack Layer-1 EDN item."
  [{:keys [enc hash entry literal]}]
  (case enc
    :node {:encoding :node
           :hash (store/hash->hex hash)
           :body entry}
    :literal {:encoding :literal
              :hash (store/hash->hex hash)
              :type (:type literal)
              :body (:body literal)}
    (throw (ex-info "unknown wire enc" {:enc enc}))))

(defn pack-edn->wire-map
  "EDN pack chunk map → wire encode-chunk map."
  [chunk]
  {:budget #?(:clj (long (or (:budget chunk) 0))
              :cljs (or (:budget chunk) 0))
   :items (mapv pack-item->wire-item (:items chunk))})

(defn wire-map->pack-edn
  "Wire decode-chunk map → EDN pack chunk map (for pack/apply-chunk!)."
  [{:keys [budget items]}]
  {:dacite.wire/chunk-v1 true
   :budget #?(:clj (long (or budget 0))
              :cljs (or budget 0))
   :items (mapv wire-item->pack-item items)})

(defn encode-pack-edn
  "Serialize an EDN pack chunk to wire-v1 binary bytes."
  [chunk]
  (encode-chunk (pack-edn->wire-map chunk)))

(defn decode-pack-edn
  "Parse wire-v1 binary bytes to an EDN pack chunk map."
  [bs]
  (wire-map->pack-edn (decode-chunk bs)))

(defn wants-binary?
  "True if Accept header prefers binary chunks over EDN."
  [accept]
  (boolean
   (when accept
     (let [a (str/lower-case (str accept))]
       (and (str/includes? a "vnd.dacite.chunk")
            ;; if both listed, prefer the one that appears first
            (let [bi (or (str/index-of a "vnd.dacite.chunk") 9999)
                  ei (or (str/index-of a "application/edn") 9999)]
              (<= bi ei)))))))
