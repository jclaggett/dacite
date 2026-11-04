(ns gen-fuse-fixtures
  "Regenerate fixtures/fuse-sl2/vectors.edn: known answers for the SL(2, F_p)
   fuse that the JVM test (hash_test) and the nbb test (peer-cljs-test) both
   check, so the two hosts agree. Run from impl/clojure:

       clojure -M:dev -m gen-fuse-fixtures"
  (:require [dacite.hash :as hash]
            [dacite.host :as host]
            [dacite.value.types :as types]
            [dacite.value :as v]
            [dacite.value.scalar]
            [dacite.store :as store]
            [dacite.string-vectors :as sv]
            [clojure.pprint :as pp]))

(def inputs ["" "a" "b" "ab" "ba" "abba" "baab" "hello" "world" "dacite" "1221" "2112"
             "héllo" "0000" "9999" "xabybaz" "xbayabz"])

(def refs ;; arbitrary fixed 32-byte refs
  ["0000000000000000000000000000000000000000000000000000000000000000"
   "0000000000000000000000000000000000000000000000000000000000000001"
   "ff00000000000000000000000000000000000000000000000000000000000000"
   "d370fe8efc450e7b7229c819c1e1e840566f6526fc21359133ba349b92aec118"
   "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d"
   "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"])

(def scalars [["null" nil] ["bool" true] ["bool" false] ["i64" 0] ["i64" 1] ["i64" 256]
              ["i64" -1] ["i32" 42] ["u8" 255] ["f64" 3.14] ["char" \a] ["char" \é]
              ;; one code point each, outside the BMP too (issue #16)
              ["char" "\uD83D\uDE00"] ["char" "\uD834\uDD1E"] ["char" "\uD83C\uDDE8"]
              ["char" \€] ["char" \uFEFF] ["char" \uFFFD]])

;; Text (issue #16): a string is a sequence of code points. Each case is
;; `:parts`, [text n] pairs (text repeated n times), so a long string is short
;; in the file.
(def string-cases
  [{:name "empty" :parts []}
   {:name "ascii" :parts [["hello" 1]]}
   {:name "bmp" :parts [["héllo, wörld € 日本語" 1]]}
   {:name "emoji" :parts [["\uD83D\uDE00" 1]]}
   {:name "emoji-between-ascii" :parts [["a" 1] ["\uD83D\uDE00" 1] ["b" 1]]}
   {:name "mixed-bmp-and-non-bmp"
    :parts [["héllo " 1] ["\uD83D\uDE00" 1] [" wörld " 1] ["\uD834\uDD1E" 1] ["日本語" 1]
            ["\uD83C\uDDE8\uD83C\uDDE6" 1] ["€" 1]]}
   {:name "bom-and-replacement-char" :parts [["\uFEFF" 1] ["a" 1] ["\uFFFD" 1]]}
   {:name "highest-code-point" :parts [["\uDBFF\uDFFF" 1] ["\uD800\uDC00" 1]]}
   ;; A page holds 4,086 bytes of text (a 4 KiB page less the 6-byte run header
   ;; and 4-byte length): 1 + 4 * 1021 = 4,085 bytes, so the first page of
   ;; "x"+emoji leaves 1 byte unused (2 and 3 for "xy" and "xyz"): it ends inside
   ;; the run of 4-byte characters, one short of the next.
   {:name "page-boundary-in-emoji" :parts [["x" 1] ["\uD83D\uDE00" 1100]]}
   {:name "page-boundary-in-emoji-2" :parts [["xy" 1] ["\uD83D\uDE00" 1100]]}
   {:name "page-boundary-in-emoji-3" :parts [["xyz" 1] ["\uD83D\uDE00" 1100]]}
   {:name "page-boundary-in-mixed" :parts [["日\uD83D\uDE00" 1500]]}
   {:name "pages-of-emoji" :parts [["\uD83D\uDE00" 5000]]}])

(def utf8-cases
  [{:text "a"} {:text "é"} {:text "€"} {:text "\uD83D\uDE00"} {:text "\uD834\uDD1E"}
   {:text "\uDBFF\uDFFF"} {:text "\uFEFFa"} {:text "\uFFFD"} {:text "a\uD83D\uDE00b"}])

(def invalid-utf8
  ["ff" "80" "c0af" "c328" "e282" "f09f98" "eda080" "edbfbf" "f4908080" "f8888080" "e0809f"
   "c080" "61c3"])

(def rejected-strings
  [{:name "lone-high-surrogate" :units [0xD83D]}
   {:name "lone-low-surrogate" :units [0xDE00]}
   {:name "high-surrogate-then-letter" :units [0xD83D 0x61]}
   {:name "letter-then-low-surrogate" :units [0x61 0xDE00]}
   {:name "reversed-pair" :units [0xDE00 0xD83D]}
   {:name "two-high-surrogates" :units [0xD83D 0xD83D]}
   {:name "pair-then-lone-high" :units [0xD83D 0xDE00 0xD83D]}
   {:name "lone-high-at-the-end" :units [0x61 0x62 0xD800]}
   {:name "lowest-and-highest-lone" :units [0xDFFF]}])

;; --- The kernel boundary (dacite.hash/kernel): hex in, hex out ----------------

(defn- hex-of-bytes [bs] (apply str (map host/byte->hex bs)))

(defn kernel-vectors []
  (let [k hash/kernel
        sh hash/state->hex
        p (host/sl2-p)
        rnd (java.util.Random. 20261103)
        rbytes (fn [n] (vec (repeatedly n #(.nextInt rnd 256))))
        ;; deterministic "random" states: the product of random byte strings
        rstate (fn [] (hash/fuse-bytes (rbytes (+ 3 (.nextInt rnd 40)))))
        states (vec (concat [hash/identity-state]
                            (map #(nth hash/byte->state %) [0 1 127 255])
                            (repeatedly 8 rstate)))
        ;; valid states at the edge of the range: p - 1 in cells (determinant 1)
        pm1 (.subtract ^java.math.BigInteger p java.math.BigInteger/ONE)
        edge [[pm1 (biginteger 0) (biginteger 0) pm1]                       ; -I
              [(biginteger 1) pm1 (biginteger 0) (biginteger 1)]
              [(biginteger 0) pm1 (biginteger 1) (biginteger 0)]
              [pm1 pm1 (biginteger 0) pm1]]
        _ (assert (every? hash/state-valid? edge))
        all (into states edge)
        pairs (concat (for [a (take 4 all) b (take 4 all)] [a b])
                      (map vector (drop 4 all) (reverse (drop 4 all)))
                      (map (fn [s] [s (hash/fuse-inverse s)]) (take 6 all)))
        bad-hexes (let [a (sh hash/identity-state)
                        cell (fn [n] (format "%064x" n))]
                    {:a-equals-p (str (cell p) (cell 0) (cell 0) (cell 1))
                     :determinant-2 (str (cell 1) (cell 0) (cell 0) (cell 2))
                     :all-zero (apply str (repeat 256 "0"))
                     :cell-above-p (str (cell (.add p java.math.BigInteger/ONE)) (cell 0) (cell 0) (cell 1))
                     :all-ff (apply str (repeat 256 "f"))
                     :short (subs a 0 254)
                     :not-hex (str "zz" (subs a 2))
                     :uppercase nil})
        ident (sh hash/identity-state)]
    {:state-mul (vec (for [[a b] pairs]
                       {:a (sh a) :b (sh b) :product (sh (hash/fuse a b))}))
     :bytes->state (vec (for [bs [[] [0] [255] [0 1 255] (vec (range 256)) (vec (reverse (range 256)))
                                  (vec (repeat 100 7)) (rbytes 1000)]]
                          {:bytes (hex-of-bytes bs) :state (sh (hash/fuse-bytes bs))}))
     :ref->state (vec (for [r refs]
                        {:ref r :state (sh (hash/ref->state (hash/hex->hash r)))}))
     :state->ref (vec (for [s all]
                        {:state (sh s) :ref (hash/hash->hex (hash/state->ref s))}))
     :state-valid? (into (vec (for [s all] {:state (sh s) :valid true}))
                         (for [[k h] bad-hexes :when (not= k :uppercase)]
                           {:state h :valid false}))
     :refused (vec (concat
                    (for [k [:a-equals-p :determinant-2 :all-zero :cell-above-p :all-ff :short :not-hex]]
                      {:fn :state-mul :args [(bad-hexes k) ident]})
                    [{:fn :state->ref :args [(bad-hexes :cell-above-p)]}
                     {:fn :ref->state :args [(subs ident 0 62)]}
                     {:fn :bytes->state :args ["abc"]}
                     {:fn :bytes->state :args ["zz"]}]))}))

(defn- escape-invisible
  "Write every character outside the BMP (as its two surrogate escapes), U+FEFF
   and U+FFFD as `\\uXXXX` in the printed EDN, so the file shows what it holds and
   no editor, BOM filter or line-ending filter can change it silently."
  [^String s]
  (apply str (map (fn [cp]
                    (cond
                      (>= cp 0x10000) (let [cs (Character/toChars (int cp))]
                                        (format "\\u%04X\\u%04X" (int (aget cs 0)) (int (aget cs 1))))
                      (#{0xFEFF 0xFFFD} cp) (format "\\u%04X" (int cp))
                      :else (String. (Character/toChars (int cp)))))
                  (.toArray (.codePoints s)))))

(defn -main [& _]
  (let [out (array-map :note "Known answers for the SL(2, F_p) fuse (docs/design/fuse-sl2.md). Generated by impl/clojure/dev/gen_fuse_fixtures.clj; the JVM and nbb tests must both match."
                       :p (str (host/sl2-p))
                       :fuse-str (vec (for [s inputs]
                                        (let [st (hash/fuse-str s)]
                                          {:input s :state (hash/state->hex st)
                                           :ref (hash/hash->hex (hash/state->ref st))})))
                       :ref->state (vec (for [r refs]
                                          {:ref r :state (hash/state->hex (hash/ref->state (hash/hex->hash r)))}))
                       :scalars (vec (for [[t v] scalars]
                                       {:type t :value (if (or (char? v) (and (= "char" t) (string? v))) (str v) v)
                                        :ref (hash/hash->hex (types/typed-value-hash [t v]))}))
                       :strings (vec (for [{:keys [name parts]} string-cases]
                                       (let [text (sv/text-of-parts parts)
                                             n (host/cp-count text)]
                                         (cond-> {:name name :parts parts :count n}
                                           (<= n 8) (assoc :code-points
                                                           (mapv #(host/code-point-at (str %) 0) (host/chars-of text)))
                                           true (assoc :ref (sv/string-hash-hex text))))))
                       :utf8 (vec (for [{:keys [text]} utf8-cases]
                                    {:text text :bytes (sv/hex-of-bytes (host/utf8-bytes text))}))
                       :invalid-utf8 (mapv (fn [b] {:bytes b}) invalid-utf8)
                       :rejected-strings (mapv #(assoc % :reason :lone-surrogate) rejected-strings)
                       :kernel (kernel-vectors)
                       :table-sample (vec (for [i [0 1 2 65 128 254 255]]
                                            [i (hash/state->hex (nth hash/byte->state i))])))]
    (.mkdirs (java.io.File. "../../fixtures/fuse-sl2"))
    (spit "../../fixtures/fuse-sl2/vectors.edn" (escape-invisible (with-out-str (pp/pprint out))))
    (println "wrote fixtures/fuse-sl2/vectors.edn")))
