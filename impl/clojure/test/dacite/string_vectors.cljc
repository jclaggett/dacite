(ns dacite.string-vectors
  "Known answers for text: a Dacite string is a sequence of Unicode code
   points on every host (issue #16), and a lone surrogate is refused.

   The vectors are in fixtures/fuse-sl2/vectors.edn:

     :strings          [{:name s :parts [[text n] ...] :count code-points
                         :code-points [cp ...] (short ones only) :ref hex}]
                       the string is each `text` repeated `n` times, in order;
                       :ref is the value hash of the Dacite string
     :utf8             [{:text s :bytes hex}]        encode and decode
     :invalid-utf8     [{:bytes hex}]                decode must throw :invalid-utf8
     :rejected-strings [{:name s :units [u16 ...] :reason kw}]
                       text with a lone surrogate; the string, its UTF-8
                       and (for one unit) the character are refused with :reason

   `problems` returns a vector of strings, empty when this host agrees. The JVM
   (`unicode-test`), nbb (`npm run peer:test`) and
   `fixtures/fuse-sl2/reference.py` all check them."
  (:require [dacite.host :as host]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.value.lit :as lit]
            [dacite.value.scalar]))

(defn text-of-parts [parts]
  (apply str (mapcat (fn [[t n]] (repeat n t)) parts)))

(defn text-of-units
  "A host string of these UTF-16 code units (it may hold a lone surrogate)."
  [units]
  (apply str (map #?(:clj char :cljs #(.fromCharCode js/String %)) units)))

(defn bytes-of-hex [hex]
  (mapv #(#?(:clj Integer/parseInt :cljs js/parseInt) (subs hex % (+ % 2)) 16)
        (range 0 (count hex) 2)))

(defn hex-of-bytes [bs] (apply str (map host/byte->hex bs)))

(defn- reason-of
  "`:accepted` when `f` returns, else the `:reason` of its ex-info (`:other`
   when it has none)."
  [f]
  (try (f) :accepted
       (catch #?(:clj Throwable :cljs :default) e (or (:reason (ex-data e)) :other))))

(defn string-hash-hex [text]
  (store/hash->hex (v/hash (v/string (store/mem-store) text))))

(defn problems
  [{:keys [strings utf8 invalid-utf8 rejected-strings]}]
  (let [out (atom [])
        bad! (fn [& xs] (swap! out conj (apply str xs)))]
    (doseq [{:keys [name parts count code-points ref]} strings]
      (let [text (text-of-parts parts)
            st (store/mem-store)
            s (v/string st text)
            got (store/hash->hex (v/hash s))]
        (when-not (= ref got) (bad! "string " name ": ref " got ", expected " ref))
        (when-not (= count (v/count s)) (bad! "string " name ": count " (v/count s) ", expected " count))
        (when-not (= count (host/cp-count text)) (bad! "string " name ": cp-count"))
        (when code-points
          (let [cps (mapv #(host/code-point-at (str %) 0) (v/realize s))]
            (when-not (= code-points cps) (bad! "string " name ": code points " cps))))
        ;; the hash does not depend on how the text is paged
        ;; (only for short texts: this host's pages are many, and slow)
        (when (<= (host/cp-count text) 300)
          (let [small (binding [lit/page-budget 64] (string-hash-hex text))]
            (when-not (= ref small) (bad! "string " name ": ref at a 64-byte page " small))))
        ;; indexing is in code points, at the ends
        (let [n (host/cp-count text)]
          (when (pos? n)
            (when-not (= (str (host/char-at text (dec n))) (str (v/native (v/nth s (dec n)))))
              (bad! "string " name ": last character"))))))
    (doseq [{:keys [text bytes]} utf8]
      (when-not (= bytes (hex-of-bytes (host/utf8-bytes text))) (bad! "utf8 encode " (pr-str text)))
      (when-not (= text (host/utf8-decode (bytes-of-hex bytes))) (bad! "utf8 decode " bytes)))
    (doseq [{:keys [bytes]} invalid-utf8]
      (let [r (reason-of #(host/utf8-decode (bytes-of-hex bytes)))]
        (when-not (= :invalid-utf8 r) (bad! "invalid utf8 " bytes ": " r))))
    (doseq [{:keys [name units reason]} rejected-strings]
      (let [text (text-of-units units)
            st (store/mem-store)]
        (doseq [[what f] [["v/string" #(v/string st text)]
                          ["utf8-bytes" #(host/utf8-bytes text)]
                          ["check-text!" #(host/check-text! text)]
                          ["as a vector element" #(v/vector st (v/string st text))]]]
          (let [r (reason-of f)]
            (when-not (= reason r) (bad! "rejected " name " by " what ": " r))))
        (when (= 1 (clojure.core/count units))
          (let [r (reason-of #(v/char st (host/char-at text 0)))]
            (when-not (= reason r) (bad! "rejected " name " by v/char: " r))))))
    @out))
