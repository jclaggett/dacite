(ns dacite.wire
  "EDN wire encoding for cross-host store nodes (JVM longs ↔ JS BigInt).

   64-bit words are tagged #dacite/u64 \"decimal\" so browser clients do not
   lose precision on large unsigned values."
  (:require [clojure.walk :as walk]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

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
  "Unsigned decimal string for a 64-bit word (stable across JVM/JS)."
  [w]
  #?(:clj  (Long/toUnsignedString ^long (long w))
     :cljs (str (.asUintN js/BigInt 64
                          (if (= (type w) js/BigInt) w (js/BigInt w))))))

(defn- ->word [s]
  ;; Always unsigned decimal 0..2^64-1
  #?(:clj  (Long/parseUnsignedLong (str s))
     :cljs (.asUintN js/BigInt 64 (js/BigInt (str s)))))

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
       (byte-buffer? v) (tagged-literal 'dacite/bytes (bytes->vec v))
       :else v))
   x))

(def readers
  {'dacite/u64 (fn [s] (->word s))
   'dacite/bytes bytes-from-edn})

(defn read-edn
  "Parse EDN store body with dacite/u64 readers."
  [s]
  #?(:clj  (edn/read-string {:readers readers} s)
     :cljs (reader/read-string {:readers readers} s)))

(defn write-edn
  "pr-str with BigInt/long words as tags."
  [x]
  (pr-str (encode x)))
