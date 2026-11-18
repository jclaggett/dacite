(ns dacite.wire
  "EDN wire encoding for cross-host store nodes (JVM longs ↔ JS BigInt).

   64-bit words are tagged #dacite/u64 \"decimal\" so browser clients do not
   lose precision on large unsigned values."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            [dacite.host :as host]))

(def ^:const js-safe-max
  "Largest integer exactly representable as IEEE-754 double (JS Number)."
  9007199254740991)

(defn- host-word?
  "True for values that must be tagged for exact 64-bit transport.

   On the JVM every integer is a Long, including measure counts — only
   values outside the JS safe integer range are tagged (hash words).
   On CLJS, hash words are BigInt and are always tagged."
  [x]
  #?(:clj  (and (instance? Long x)
                (or (> ^long x js-safe-max)
                    (< ^long x (- js-safe-max))))
     :cljs (or (= (type x) js/BigInt)
               ;; compiled cljs: typeof via Function (js/typeof is not a thing)
               (try
                 (true? (.call (js/Function. "return typeof arguments[0]==='bigint'")
                               nil x))
                 (catch :default _ false)))))

(defn- word->wire-str
  "Decimal string for a 64-bit word (stable across JVM/JS): unsigned, except
   a negative BigInt on ClojureScript (an i64, #64), which keeps its sign."
  [w]
  #?(:clj  (Long/toUnsignedString ^long (long w))
     :cljs (if (and (= (type w) js/BigInt) (< w 0))
             (str w)
             (str (.asUintN js/BigInt 64
                            (if (= (type w) js/BigInt) w (js/BigInt w)))))))

(defn- ->word [s]
  ;; Unsigned decimal 0..2^64-1, read as its unsigned value (#64): on the
  ;; JVM a long below 2^63 and a BigInt N at or above it, as the writer takes
  ;; a u64 literal body; a BigInt on ClojureScript. A negative decimal is an
  ;; i64 from ClojureScript: a long on the JVM, the signed BigInt on JS.
  (let [s (str s)]
    #?(:clj  (if (str/starts-with? s "-")
               (Long/parseLong s)
               (let [w (Long/parseUnsignedLong s)]
                 (if (neg? w) (+ 18446744073709551616N w) w)))
       :cljs (if (str/starts-with? s "-") (js/BigInt s) (.asUintN js/BigInt 64 (js/BigInt s))))))

(defn- byte-buffer?
  [x]
  #?(:clj (bytes? x)
     :cljs (instance? js/Uint8Array x)))

(defn bytes->vec
  "Unsigned 0..255 vector of a host byte buffer, for EDN."
  [bs]
  #?(:clj (let [n (alength ^bytes bs)]
            (mapv #(Byte/toUnsignedInt (aget ^bytes bs %)) (range n)))
     :cljs (let [n (.-length bs)]
             (mapv #(aget bs %) (range n)))))

(defn bytes-from-edn
  "Host byte buffer from an EDN `#dacite/bytes` payload (vector of 0..255)."
  [xs]
  #?(:clj (byte-array (map #(unchecked-byte (bit-and 0xff (int %))) xs))
     :cljs (let [out (js/Uint8Array. (count xs))]
             (dotimes [i (count xs)]
               (aset out i (bit-and 0xff (nth xs i))))
             out)))

(defn encode
  "Walk a store value, tagging host words as #dacite/u64 and byte buffers
   as #dacite/bytes."
  [x]
  (walk/prewalk
   (fn [v]
     (cond
       (host-word? v) (tagged-literal 'dacite/u64 (word->wire-str v))
       ;; a u64 literal body >= 2^63 is a BigInt N on the JVM (#64): the same
       ;; tag and text as the word nbb writes
       #?(:clj (instance? clojure.lang.BigInt v) :cljs false)
       (tagged-literal 'dacite/u64 (str v))
       (byte-buffer? v) (tagged-literal 'dacite/bytes (bytes->vec v))
       :else v))
   x))

(def readers
  {'dacite/u64 (fn [s] (->word s))
   'dacite/bytes bytes-from-edn})

(defn- host-ints
  "`x` with every i64 and u64 entry (`[type data]`) and literal body in the
   host representation the builder uses (#64). An i64 is a long on the JVM
   and a signed BigInt on ClojureScript. A u64 entry holds the word (a long
   on the JVM), a u64 literal body the value (a BigInt N >= 2^63 on the
   JVM); on ClojureScript both are the unsigned BigInt. A JVM writes a small
   one as a plain integer, and a negative i64 as its unsigned word."
  [x]
  (let [i64 #?(:clj unchecked-long :cljs host/->i64)
        entry-conv {"i64" i64 "u64" host/->word}
        body-conv {"i64" i64
                   "u64" #?(:clj (comp host/word->unsigned host/->word) :cljs host/->word)}
        int-data? (fn [d] #?(:clj (integer? d)
                             :cljs (or (number? d) (= (type d) js/BigInt))))]
    (walk/postwalk
     (fn [v]
       (cond
         (and (vector? v) (= 2 (count v)) (entry-conv (first v)) (int-data? (second v)))
         [(first v) ((entry-conv (first v)) (second v))]

         (and (map? v) (body-conv (:type v)) (int-data? (:body v)))
         (update v :body (body-conv (:type v)))

         (and (map? v) (map? (:body v)) (body-conv (:of (:body v))))
         (let [f (body-conv (:of (:body v)))
               {:keys [values value]} (:body v)]
           (cond-> v
             (and (vector? values) (every? int-data? values))
             (assoc-in [:body :values] (mapv f values))
             (int-data? value) (assoc-in [:body :value] (f value))))

         :else v))
     x)))

(defn read-edn
  "Parse EDN store body with dacite/u64 readers. A `#dacite/u64` reads as
   its unsigned value: on the JVM a word >= 2^63 is a BigInt N, not the
   negative long (`host/->word` gives the word back). Then an i64 or u64
   entry or literal body comes back in its host representation, as built
   (#64, `host-ints`)."
  [s]
  (host-ints #?(:clj  (edn/read-string {:readers readers} s)
                :cljs (reader/read-string {:readers readers} s))))

(defn write-edn
  "pr-str with BigInt/long words as tags."
  [x]
  (pr-str (encode x)))
