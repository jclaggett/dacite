(ns dacite.examples.value-view-checks
  "M6.5 Value Browser rendering (`dacite.examples.value-view`): scalars and
   text as themselves, a map or a vector stepped into one hash at a time,
   and the raw/hex pane showing the entry the tree shows. On the JVM
   (`dacite.examples.value-view-test`) and on nbb (`npm run examples:test`).
   `is` is `dacite.store.check`."
  (:require [clojure.string :as str]
            [dacite.examples.value-view :as vv]
            [dacite.host :as host]
            [dacite.store :as store]
            [dacite.store.check :refer [is testing]]
            [dacite.value :as v]
            [dacite.value.types :as types]
            [dacite.wire :as wire]
            [dacite.wire.binary :as wb]))

(defn- u256-bytes [last-byte]
  #?(:clj (let [a (byte-array 32)] (aset-byte a 31 (byte last-byte)) a)
     :cljs (let [a (js/Uint8Array. 32)] (aset a 31 last-byte) a)))

(defn- fixture
  "A store holding the npx dacite welcome value's shape plus scalars and a
   long string: `{:st :root :lookup}`; `lookup` is hex -> entry or nil."
  []
  (let [st (store/mem-store)
        val (v/map st
                   "dacite" "local peer"
                   "says" (v/vector st "hello" "from" "npx dacite")
                   "n" 42
                   "neg" -7
                   "max" (v/u64 st host/max-u64)
                   "big" (v/u256 st (u256-bytes 5))
                   "ok" true
                   "none" nil
                   "half" 1.5
                   "c" (v/char st \x)
                   "long" (apply str (repeat 3000 "ab")))]
    {:st st
     :root (store/hash->hex (v/hash val))
     :lookup (fn [hex] (store/await (store/s-get st (store/hex->hash hex))))}))

(defn- walk
  "Every entry reachable from the root by following `vv/children` hashes,
   one hash at a time: hex -> entry."
  [{:keys [lookup root]}]
  (loop [q [root] seen {}]
    (if-let [hex (first q)]
      (if (contains? seen hex)
        (recur (rest q) seen)
        (let [e (lookup hex)]
          (recur (concat (rest q) (keep :hash (vv/children e))) (assoc seen hex e))))
      seen)))

(defn- value-of
  "The `value` child entry of the map entry whose key text is `k`."
  [fx k]
  (let [es (vals (walk fx))
        lookup (:lookup fx)]
    (some (fn [[t data :as e]]
            (when (= "hamt/entry" t)
              (let [{:keys [text]} (vv/text-of lookup (lookup (store/hash->hex (:key-ref data))))]
                (when (= k text)
                  (lookup (store/hash->hex (:val-ref data)))))))
          es)))

(defn scalars-render-as-themselves
  [_]
  (let [fx (fixture)
        s (fn [k] (vv/node-summary (value-of fx k)))]
    (is (= "42" (s "n")))
    (is (= "-7" (s "neg")))
    (is (= "18446744073709551615" (s "max")) "a u64 word above 2^63 in decimal")
    (is (= (str "0x" (apply str (repeat 62 "0")) "05") (s "big")))
    (is (= "true" (s "ok")))
    (is (= "nil" (s "none")))
    (is (= "1.5" (s "half")))
    (is (= "\"x\"" (s "c")))
    (is (= "i64" (:type (vv/view "h" (value-of fx "n")))))
    (is (= [] (vv/children (value-of fx "n"))) "a scalar has no children")))

(defn strings-render-as-text
  [_]
  (let [{:keys [lookup] :as fx} (fixture)
        says (value-of fx "dacite")]
    (is (= {:text "local peer"} (vv/text-of lookup says)))
    (is (= "\"local peer\"" (vv/node-summary says "local peer")))
    (is (= "10 characters" (vv/node-summary says)) "without its pages, the count")
    (testing "a long string is cut in the summary, whole in text-of"
      (let [long-e (value-of fx "long")
            {:keys [text]} (vv/text-of lookup long-e)]
        (is (= 6000 (count text)))
        (is (= (apply str (repeat 3000 "ab")) text))
        (is (= (+ 2 vv/max-text 1) (count (vv/node-summary long-e text))) "quoted, cut, and …")
        (is (str/ends-with? (vv/node-summary long-e text) "…\""))))
    (testing "missing pages are named, then read"
      (let [long-e (value-of fx "long")
            have (atom #{})
            partial (fn [hex] (when (@have hex) (lookup hex)))
            rounds (loop [n 0]
                     (let [r (vv/text-of partial long-e)]
                       (if-let [need (:need r)]
                         (do (swap! have into need) (if (< n 20) (recur (inc n)) n))
                         n)))]
        (is (pos? rounds) "the first ask names pages")
        (is (< rounds 20))
        (is (= 6000 (count (:text (vv/text-of partial long-e)))))))
    (is (nil? (vv/text-of lookup (value-of fx "n"))) "not a string")))

(defn map-and-vector-step-one-hash-at-a-time
  [_]
  (let [{:keys [lookup root] :as fx} (fixture)
        top (lookup root)
        [kid] (vv/children top)]
    (is (= "map" (first top)))
    (is (= "11 entries" (vv/node-summary top)))
    (is (= "root" (:label kid)))
    (is (= 64 (count (:hash kid))))
    (let [bitmap (lookup (:hash kid))
          kids (vv/children bitmap)]
      (is (= "hamt/bitmap" (first bitmap)))
      (is (every? :hash kids))
      (is (= (map #(str "child " %) (range (count kids))) (map :label kids))))
    (let [entry (some (fn [[_ e]] (when (= "hamt/entry" (first e)) e)) (walk fx))]
      (is (= ["key" "value"] (map :label (vv/children entry)))))
    (testing "a vector: root, then pages whose literals are leaves"
      (let [vec-e (value-of fx "says")
            _ (is (= "vector" (first vec-e)))
            _ (is (= "3 items" (vv/node-summary vec-e)))
            deep (lookup (:hash (first (vv/children vec-e))))
            _ (is (= "ft/deep" (first deep)))
            _ (is (= ["left" "spine" "right"] (map :label (vv/children deep))))
            pages (map #(lookup (:hash %)) (vv/children deep))
            leaves (mapcat #(filter :literal (vv/children %)) pages)]
        (is (= ["string \"hello\"" "run of string [\"from\" \"npx dacite\"]"]
               (map :literal leaves)))))))

(defn tree-reaches-every-entry
  [_]
  (let [{:keys [st root] :as fx} (fixture)
        by-tree (set (keys (walk fx)))
        by-gc (loop [q [(store/hex->hash root)] seen #{}]
                (if-let [h (first q)]
                  (let [hex (store/hash->hex h)]
                    (if (contains? seen hex)
                      (recur (rest q) seen)
                      (recur (concat (rest q) (remove nil? (types/child-hashes (store/require-node st h))))
                             (conj seen hex))))
                  seen))]
    (is (= by-gc by-tree) "every entry the peer counts can be opened from the tree")
    (is (= (count by-gc) (count by-tree)))
    (is (contains? by-tree root))
    (is (every? (comp string? :label) (mapcat vv/children (vals (walk fx)))) "every child is labelled")))

(defn raw-pane-shows-the-entry
  [_]
  (let [{:keys [lookup root] :as fx} (fixture)]
    (doseq [[hex e] (walk fx)]
      (let [bs (vv/entry-bytes e)
            w (vv/view hex e)]
        (let [back (wb/decode-node-bytes (wb/as-wire-bytes bs))]
          (is (= (first e) (first back)) (str "bytes decode to the entry's type " (:type w)))
          (is (= bs (vv/entry-bytes back)) (str "bytes decode to the entry " (:type w))))
        (is (str/starts-with? (:edn w) (str "[" (pr-str (first e)) " ")) "the edn names the type")
        (doseq [x (get-in (second e) [:measure :elements-fuse])]
          (is (str/includes? (:edn w) (str x)) "a field element is printed whole"))
        (doseq [x (:root (second e))]
          (is (str/includes? (:edn w) (wire/write-edn x)) "a hash word as on the wire"))
        (is (= (count bs) (:size w)))
        (is (= (int (Math/ceil (/ (count bs) 16))) (count (str/split-lines (:hex w)))))
        (is (= hex (:hash w)))))
    (let [top (lookup root)
          lines (str/split-lines (vv/hex-dump (vv/entry-bytes top)))]
      (is (str/starts-with? (first lines) "00000000  "))
      (is (str/starts-with? (second lines) "00000010  "))
      (is (re-matches #"[0-9a-f]{8}  ([0-9a-f]{2} ){7}[0-9a-f]{2}  ([0-9a-f]{2} ){7}[0-9a-f]{2}  \|.{16}\|" (first lines))))
    (testing "the same bytes on every host"
      (let [st (store/mem-store)
            h (v/hash (v/i64 st 42))
            welcome (v/hash (v/map st "dacite" "local peer" "says" (v/vector st "hello" "from" "npx dacite")))]
        (is (= "00000000  00 03 08 00 00 00 00 00  00 00 2a                 |..........*|"
               (vv/hex-dump (vv/entry-bytes (store/require-node st h)))))
        (is (= "763fed7661a60f1a6f3ea075a12dcc41537fa3c5cdf9cba07b8bbc155e6196b0" (store/hash->hex welcome))
            "the npx dacite welcome value")
        (is (= (str "00000000  03 03 bd eb 93 c1 7e 2d  5d ee ff 0d 4a e9 87 19  |......~-]...J...|\n"
                    "00000010  48 e3 e3 e0 df e9 4e bb  41 8e 06 de 9e 4c 4c b1  |H.....N.A....LL.|\n"
                    "00000020  16 57 00 00 00 00 00 00  00 02 00 00 00 00 00 00  |.W..............|\n"
                    "00000030  00 27                                             |.'|")
               (vv/hex-dump (vv/entry-bytes (store/require-node st welcome)))))))
    (is (= "00000000  00 41 7f 80 ff                                    |.A...|"
           (vv/hex-dump [0 65 127 128 255])))
    (is (= "" (vv/hex-dump [])))))

(def ^:private emoji
  "One non-BMP character: two UTF-16 units, one code point."
  "\uD83D\uDE00")

(defn cut-counts-code-points
  [_]
  (let [a199 (apply str (repeat 199 "a"))]
    (is (= 1 (vv/code-point-count emoji)))
    (is (= 2 (count emoji)) "two UTF-16 units")
    (is (= (str a199 emoji "…") (vv/cut (str a199 emoji "b") 200))
        "the emoji at code point 200 is kept whole")
    (is (= (str a199 emoji) (vv/cut (str a199 emoji) 200)) "200 code points are not cut")
    (is (= (str (apply str (repeat 119 "a")) emoji "…")
           (vv/cut (str (apply str (repeat 119 "a")) emoji emoji) vv/max-literal))
        "the literal limit counts code points too")
    (testing "a string summary"
      (let [text (str a199 emoji "tail")
            s (vv/node-summary ["string" {:count 204}] text)]
        (is (= (pr-str (str a199 emoji "…")) s))
        (is (str/includes? s emoji) "no lone surrogate")))))

(defn raw-edn-u64-is-unsigned
  [_]
  (let [st (store/mem-store)
        max-h (v/hash (v/u64 st host/max-u64))
        top (store/require-node st max-h)
        tag "#dacite/u64 \"18446744073709551615\""]
    (is (= (str "[\"u64\" " tag "]") (vv/raw-edn top)) "the same text on every host")
    (is (= (str "[\"u64\" #dacite/u64 \"7\"]") (vv/raw-edn (store/require-node st (v/hash (v/u64 st 7))))))
    (testing "a u64 inlined in a page"
      (let [fx-v (v/vector st (v/u64 st host/max-u64) (v/u64 st 1))
            vec-e (store/require-node st (v/hash fx-v))
            deep (store/require-node st (store/hex->hash (:hash (first (vv/children vec-e)))))
            pages (map #(store/require-node st (store/hex->hash (:hash %))) (vv/children deep))
            edns (map vv/raw-edn (cons deep pages))]
        (is (some #(str/includes? % tag) edns) "the inlined u64 is tagged unsigned")
        (is (not-any? #(str/includes? % ":body -1") edns) "never signed")))))

(def checks
  {"cut-counts-code-points" cut-counts-code-points
   "raw-edn-u64-is-unsigned" raw-edn-u64-is-unsigned
   "scalars-render-as-themselves" scalars-render-as-themselves
   "strings-render-as-text" strings-render-as-text
   "map-and-vector-step-one-hash-at-a-time" map-and-vector-step-one-hash-at-a-time
   "tree-reaches-every-entry" tree-reaches-every-entry
   "raw-pane-shows-the-entry" raw-pane-shows-the-entry})
