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

   `problems` returns a vector of strings, empty when this host agrees.
   `behavior-problems` checks, on any host, the wire decode rules for text
   (`:bad-char`, the char-run count, a lone surrogate on encode) and that peer
   messages report or repair a lone surrogate instead of throwing. The JVM
   (`unicode-test`), nbb (`npm run peer:test`) and
   `fixtures/fuse-sl2/reference.py` all check them."
  (:require [dacite.host :as host]
            [dacite.store :as store]
            [dacite.value :as v]
            [dacite.peer.msg :as msg]
            [dacite.value.lit :as lit]
            [dacite.value.scalar]
            [dacite.wire.binary :as bin]))

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

(defn- wire [bs] (bin/as-wire-bytes bs))

(defn- u32 [n] [(bit-and 0xff (bit-shift-right n 24)) (bit-and 0xff (bit-shift-right n 16))
                (bit-and 0xff (bit-shift-right n 8)) (bit-and 0xff n)])

(defn- char-run-bytes
  "A `run` of `char` on the wire (type 0x50, inner 0x02) with count `n`."
  [n text]
  (let [bs (host/utf8-bytes text)]
    (wire (vec (concat [0x50 0x02] (u32 n) (u32 (count bs)) bs)))))

(defn behavior-problems
  []
  (let [out (atom [])
        bad! (fn [& xs] (swap! out conj (apply str xs)))
        want (fn [what expected f]
               (let [r (reason-of f)]
                 (when-not (= expected r) (bad! what ": " (pr-str r) ", expected " (pr-str expected)))))
        hi (text-of-units [0xD83D])
        lo (text-of-units [0xDE00])
        text (str "a" (host/code-point->char 0x1F600) "\u00e9" (host/code-point->char 0x1D11E))
        decode #(bin/decode-lit-bytes (wire %))]
    ;; wire decode
    (let [run (bin/decode-lit-bytes (char-run-bytes 4 text))]
      (when-not (= text (:values (:body run))) (bad! "wire: a char run of 4 code points did not decode to its text")))
    (when-not (= 4 (host/cp-count text)) (bad! "wire: the test text is not 4 code points"))
    (want "wire: a char run count in UTF-16 units (6)" :other #(bin/decode-lit-bytes (char-run-bytes 6 text)))
    (want "wire: a char run count that is too small (3)" :other #(bin/decode-lit-bytes (char-run-bytes 3 text)))
    (want "wire: a char of two code points" :bad-char #(decode [0x02 2 0x61 0x62]))
    (want "wire: a char of no bytes" :bad-char #(decode [0x02 0]))
    (let [ch (decode (vec (concat [0x02 4] (host/utf8-bytes (host/code-point->char 0x1F600)))))]
      (when-not (= 0x1F600 (host/code-point-at (str (:body ch)) 0)) (bad! "wire: a 4-byte char did not decode")))
    (want "wire: malformed UTF-8 in a string" :invalid-utf8 #(decode [0x10 0 0 0 1 0xFF]))
    (want "wire: an encoded surrogate in a string" :invalid-utf8 #(decode [0x10 0 0 0 3 0xED 0xA0 0x80]))
    (want "wire: malformed UTF-8 in a char run" :invalid-utf8
          #(bin/decode-lit-bytes (wire (vec (concat [0x50 0x02] (u32 1) (u32 1) [0xFF])))))
    ;; wire encode
    (want "wire encode: a char run with a lone surrogate" :lone-surrogate
          #(bin/encode-lit-bytes {:type "run" :body {:of "char" :values (str "a" hi)}}))
    (want "wire encode: a char that is a lone surrogate" :lone-surrogate
          #(bin/encode-lit-bytes {:type "char" :body (host/char-at lo 0)}))
    (want "wire encode: a string with a lone surrogate" :lone-surrogate
          #(bin/encode-lit-bytes {:type "string" :body (str lo "a")}))
    (want "wire encode: a well-formed run" :accepted
          #(bin/encode-lit-bytes {:type "run" :body {:of "char" :values text}}))
    ;; peer messages: report or repair, never throw
    (doseq [t [(str "a" hi) lo (str hi hi) (str "x" lo "y")]]
      (let [r (try (msg/problems :cancel {"reason" t}) (catch #?(:clj Throwable :cljs :default) e [(str "threw " (ex-message e))]))]
        (when-not (and (seq r) (re-find #"not valid Unicode" (str (first r))))
          (bad! "msg/problems of " (pr-str (host/lone-surrogate-index t)) ": " (pr-str r)))))
    (when-not (= [] (msg/problems :cancel {"reason" text})) (bad! "msg/problems of well-formed text"))
    (doseq [[t expected] [[(str "a" hi) "a\uFFFD"] [lo "\uFFFD"] [(str hi hi) "\uFFFD\uFFFD"]
                          [(str hi (host/code-point->char 0x1F600)) (str "\uFFFD" (host/code-point->char 0x1F600))]
                          [(str "x" lo "y") "x\uFFFDy"] [text text]]]
      (let [r (try (msg/cut-text t) (catch #?(:clj Throwable :cljs :default) e (str "threw " (ex-message e))))]
        (when-not (= expected r) (bad! "msg/cut-text of " (pr-str expected) ": " (pr-str r)))))
    (let [long (str hi (apply str (repeat 300 "x")))
          r (try (msg/cut-text long) (catch #?(:clj Throwable :cljs :default) e (str "threw " (ex-message e))))]
      (when-not (= (str "\uFFFD" (apply str (repeat 253 "x"))) r) (bad! "msg/cut-text of a long text with a lone surrogate")))
    (let [r (try (let [bs (msg/encode-message {:type :cancel :stream 5 :value {"reason" (str "a" hi)}})]
                   (get-in (msg/decode-message bs) [:value "reason"]))
                 (catch #?(:clj Throwable :cljs :default) e (str "threw " (ex-message e))))]
      (when-not (= "a\uFFFD" r) (bad! "a message whose free text has a lone surrogate: " (pr-str r))))
    @out))
