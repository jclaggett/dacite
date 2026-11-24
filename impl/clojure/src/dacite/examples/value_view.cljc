(ns dacite.examples.value-view
  "What the Value Browser shows for one stored entry (M6.5). Pure, on the
   JVM, nbb, and the browser; the page (`dacite.examples.browser-web`)
   does the reads and the DOM.

   A value is stored as entries `[type-name data]`, each under its hash.
   The page shows that graph as a tree of hashes (docs/plan/peering.md
   M6.5): one row per entry, with its type and a summary, and its
   children under it. A child is a hash (a row that opens another entry)
   or a literal inlined in a finger-tree page (a leaf). Choosing a hash
   reads that entry, so the tree is stepped into one hash at a time.

   - `node-summary`: a scalar as itself, text as itself when its pages
     are at hand (`text-of`), a collection by its count.
   - `children`: the labelled children of an entry, in order.
   - `text-of`: the characters of a `string` value from the entries a
     `lookup` already has, or the hashes it still needs.
   - `raw-edn` and `hex-dump`: the raw/hex pane, the entry's EDN form and
     the bytes it is stored and sent as (`dacite.wire.binary/encode-node-bytes`).

   No media types: bytes are shown as hex, never interpreted (M6.5 scope)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [dacite.host :as host]
            [dacite.store :as store]
            [dacite.value.lit :as lit]
            [dacite.wire :as wire]
            [dacite.wire.binary :as wb]))

(def scalar-types
  "Entry types that are one scalar: shown as themselves."
  lit/scalar-types)

(def collection-types
  "User-facing collection entries: their one child is the tree root."
  #{"map" "set" "vector" "string" "blob"})

(def max-text
  "Characters of a string shown in a summary; a longer one is cut."
  200)

(def max-literal
  "Characters of a literal's printed form shown in a leaf; longer is cut."
  120)

(defn hash-hex
  "Lowercase hex of a hash (four words), or nil."
  [h]
  (when h (store/hash->hex h)))

(defn code-point-count
  "Characters in `s` as code points: an emoji or any other non-BMP
   character (a UTF-16 surrogate pair) counts once."
  [s]
  #?(:clj (.codePointCount ^String s 0 (count s))
     :cljs (.-length (js/Array.from s))))

(defn cut
  "`s` cut to its first `n` code points, with `…` when it was longer. A
   surrogate pair is never split."
  [s n]
  (if (> (code-point-count s) n)
    (str #?(:clj (subs s 0 (.offsetByCodePoints ^String s 0 (int n)))
            :cljs (.join (.slice (js/Array.from s) 0 n) ""))
         "…")
    s))

(defn- word-str
  "A count or size word as decimal text on every host."
  [w]
  (str w))

(declare hex2)

(defn scalar-text
  "A scalar's data as text: `nil`, `true`, `42`, `1.5`, a char or string
   quoted, a u64 in decimal (a word at or above 2^63 too), a u256 as `0x`
   and 64 hex digits."
  [t data]
  (let [t (str t)]
    (cond
      (= t "null") "nil"
      (= t "negative") "negative"
      (= t "u64") #?(:clj (if (integer? data)
                            (if (instance? Long data) (Long/toUnsignedString (long data)) (str data))
                            (str data))
                     :cljs (str (.asUintN js/BigInt 64 (js/BigInt data))))
      (= t "u256") (str "0x" (apply str (map hex2 (wire/bytes->vec (wb/as-wire-bytes data)))))
      (= t "char") (pr-str (str data))
      (string? data) (pr-str data)
      :else (str data))))

(defn- literal-text
  "Printed form of one page literal, `{:type t :body b}`."
  [{:keys [type body]}]
  (let [t (str type)]
    (cond
      (and (= t "run") (= "char" (str (:of body))))
      (str "chars " (pr-str (str (:values body))))
      (= t "run")
      (str "run of " (:of body) " " (wire/write-edn (:values body)))
      (= t "repeat")
      (str (:n body) " × " (:of body) " " (wire/write-edn (:value body)))
      (contains? lit/scalar-types t) (str t " " (scalar-text t body))
      :else
      (str t " " (wire/write-edn body)))))

(defn- ref-items
  "The hashes a page body item names, when it names any: a `ref`, or a
   run or repeat of refs. Nil for an inlined literal."
  [{:keys [type body]}]
  (let [t (str type)]
    (cond
      (= t "ref") [body]
      (and (= t "run") (= "ref" (str (:of body)))) (vec (:values body))
      (and (= t "repeat") (= "ref" (str (:of body))))
      (vec (repeat (long (or (:n body) 0)) (:value body)))
      :else nil)))

(defn- hash-child [label h]
  {:label label :hash (hash-hex h)})

(defn- numbered [prefix hs]
  (into [] (map-indexed (fn [i h] (hash-child (str prefix " " i) h))) hs))

(defn- page-children
  "Children of a finger-tree digit or node page: its pointer list, or its
   body in order, refs as hashes and inlined literals as leaves."
  [data]
  (if-let [ch (:children data)]
    (numbered "child" ch)
    (let [items (or (:body data) [])]
      (:out
       (reduce (fn [{:keys [i out]} item]
                 (if-let [hs (ref-items item)]
                   {:i (+ i (count hs))
                    :out (into out (map-indexed (fn [j h] (hash-child (str "item " (+ i j)) h))) hs)}
                   {:i (inc i)
                    :out (conj out {:label (str "item " i)
                                    :literal (cut (literal-text item) max-literal)})}))
               {:i 0 :out []}
               items)))))

(defn children
  "Labelled children of a stored entry, in order. Each is
   `{:label s :hash hex}` (another entry) or `{:label s :literal text}`
   (a literal inlined in this entry)."
  [[t data]]
  (let [t (str t)]
    (cond
      (contains? collection-types t) (if (:root data) [(hash-child "root" (:root data))] [])
      (= t "hamt/bitmap") (numbered "child" (:children data))
      (= t "hamt/entry") [(hash-child "key" (:key-ref data))
                          (hash-child "value" (:val-ref data))]
      (= t "ft/deep") [(hash-child "left" (:left data))
                       (hash-child "spine" (:spine data))
                       (hash-child "right" (:right data))]
      (#{"ft/digit" "ft/node"} t) (page-children data)
      :else [])))

(defn- page-text
  "Characters under one finger-tree entry, in order: `{:chars [..]}`,
   `{:need #{hex}}` when an entry is not at hand, or `{:not-text t}`."
  [lookup h]
  (let [hex (hash-hex h)
        entry (lookup hex)]
    (if (nil? entry)
      {:need #{hex}}
      (let [[t data] entry
            t (str t)
            parts (cond
                    (= t "ft/empty") []
                    (= t "char") [{:chars [(str data)]}]
                    (= t "ft/deep") (mapv #(page-text lookup %)
                                          [(:left data) (:spine data) (:right data)])
                    (#{"ft/digit" "ft/node"} t)
                    (if-let [ch (:children data)]
                      (mapv #(page-text lookup %) ch)
                      (mapv (fn [{:keys [type body] :as item}]
                              (let [it (str type)]
                                (cond
                                  (ref-items item) {:refs (ref-items item)}
                                  (= it "char") {:chars [(str body)]}
                                  (and (= it "run") (= "char" (str (:of body))))
                                  {:chars (mapv str (host/chars-of (:values body)))}
                                  (and (= it "repeat") (= "char" (str (:of body))))
                                  {:chars (vec (repeat (long (or (:n body) 0)) (str (:value body))))}
                                  :else {:not-text it})))
                            (or (:body data) [])))
                    :else [{:not-text t}])
            parts (mapcat (fn [p]
                            (if-let [rs (:refs p)]
                              (map #(page-text lookup %) rs)
                              [p]))
                          parts)]
        (cond
          (some :not-text parts) (first (filter :not-text parts))
          (some :need parts) {:need (into #{} (mapcat :need) parts)}
          :else {:chars (into [] (mapcat :chars) parts)})))))

(defn text-of
  "The text of a `string` entry, from the entries `lookup` (hex -> entry or
   nil) already has: `{:text s}`, `{:need #{hex}}` (read these, then ask
   again), or nil when the entry is not a string or holds something other
   than characters."
  [lookup [t data]]
  (when (= "string" (str t))
    (if-let [root (:root data)]
      (let [r (page-text lookup root)]
        (cond
          (:need r) r
          (:chars r) {:text (lit/join-chars (:chars r))}
          :else nil))
      {:text ""})))

(defn- plural [n one many]
  (str (word-str n) " " (if (= "1" (word-str n)) one many)))

(defn node-summary
  "One line for an entry: a scalar as itself, a string as its text when
   `text` is given (cut at `max-text`), a collection or a tree page by
   its count."
  ([entry] (node-summary entry nil))
  ([[t data] text]
   (let [t (str t)
         n (or (:count data) (get-in data [:measure :count]))]
     (cond
       (contains? scalar-types t) (scalar-text t data)
       (= t "string") (if (some? text)
                        (pr-str (cut text max-text))
                        (plural n "character" "characters"))
       (= t "map") (plural n "entry" "entries")
       (= t "set") (plural n "element" "elements")
       (= t "vector") (plural n "item" "items")
       (= t "blob") (plural (:size-bytes data) "byte" "bytes")
       (= t "hamt/bitmap") (plural (count (:children data)) "child" "children")
       (= t "hamt/entry") "key and value"
       (str/starts-with? t "ft/") (if n (plural n "element" "elements") "")
       :else ""))))

(defn- integer-like?
  "A host integer: a long on the JVM (or a BigInt N), a number or BigInt on
   ClojureScript."
  [x]
  #?(:clj (integer? x)
     :cljs (or (number? x) (= (type x) js/BigInt))))

(defn- wide-int?
  "An integer outside 64 bits (a measure's field elements): `dacite.wire`
   tags words only, so these are printed whole instead (`<digits>N`)."
  [x]
  #?(:clj (and (or (instance? clojure.lang.BigInt x) (instance? java.math.BigInteger x))
               (> (.bitLength (biginteger x)) 64))
     :cljs (and (= (type x) js/BigInt)
                (or (> x (js/BigInt "18446744073709551615"))
                    (< x (js/BigInt "-9223372036854775808"))))))

(defn- u64-tag
  "A u64 as `#dacite/u64 \"<unsigned decimal>\"` on every host: on the JVM
   a word at or above 2^63 is a negative long, which must not print signed."
  [w]
  (tagged-literal 'dacite/u64 (scalar-text "u64" w)))

(defn- tag-u64s
  "Tag the u64 data in an entry: a `u64` entry's data, and `u64` literal
   bodies and runs inside a page."
  [entry]
  (let [[t data] entry
        entry (if (= "u64" (str t)) [t (u64-tag data)] entry)]
    (walk/prewalk
     (fn [x]
       (if (and (map? x) (contains? x :type) (contains? x :body))
         (let [t (str (:type x)) b (:body x)]
           (cond
             (and (= t "u64") (integer-like? b)) (assoc x :body (u64-tag b))
             (and (= "run" t) (= "u64" (str (:of b))) (sequential? (:values b)))
             (assoc-in x [:body :values] (mapv u64-tag (:values b)))
             (and (= t "repeat") (= "u64" (str (:of b))) (integer-like? (:value b)))
             (assoc-in x [:body :value] (u64-tag (:value b)))
             :else x))
         x))
     entry)))

(defn raw-edn
  "The entry as EDN: words tagged `#dacite/u64` as on the wire
   (`dacite.wire/write-edn`), a u64 value tagged unsigned on every host,
   wider integers (a measure's `elements-fuse`) printed whole as
   `<digits>N`. The text follows the host's numbers (a
   count is a BigInt on nbb, so it is tagged there); the hex dump is the
   same bytes on every host."
  [entry]
  (wire/write-edn (walk/prewalk #(if (wide-int? %) (symbol (str % "N")) %) (tag-u64s entry))))

(defn entry-bytes
  "The bytes the entry is stored and sent as: a vector of 0..255."
  [entry]
  (wire/bytes->vec (wb/as-wire-bytes (wb/encode-node-bytes entry))))

(defn- hex2 [b]
  (let [s (str/lower-case #?(:clj (Integer/toString (int b) 16) :cljs (.toString b 16)))]
    (if (< (count s) 2) (str "0" s) s)))

(defn- hex-offset [n]
  (let [s #?(:clj (Integer/toString (int n) 16) :cljs (.toString n 16))]
    (str (apply str (repeat (- 8 (count s)) "0")) s)))

(defn hex-dump
  "Hex dump of a byte vector (0..255): 16 bytes a line, the offset, the
   bytes, and their printable ASCII (`.` for the rest)."
  [bs]
  (->> (partition-all 16 bs)
       (map-indexed
        (fn [i row]
          (let [hexes (map hex2 row)
                [a b] (split-at 8 hexes)
                cells (str (str/join " " a) (when (seq b) (str "  " (str/join " " b))))
                ascii (apply str (map #(if (<= 32 % 126) (char %) ".") row))]
            (str (hex-offset (* 16 i)) "  "
                 cells (apply str (repeat (- 49 (count cells)) " "))
                 " |" ascii "|"))))
       (str/join "\n")))

(defn view
  "Everything the page shows for the entry stored under `hex`: type,
   summary, children, the raw EDN, and the hex dump."
  ([hex entry] (view hex entry nil))
  ([hex entry text]
   (let [bs (entry-bytes entry)]
     {:hash hex
      :type (str (first entry))
      :summary (node-summary entry text)
      :children (children entry)
      :edn (raw-edn entry)
      :size (count bs)
      :hex (hex-dump bs)})))
