(ns dacite.examples.explorer
  "Web value explorer — browse a rooted store as a typed tree.

   **Values** — type gallery, row summary, paged children. Domain ops
   take/return Dacite values (`dacite.value` only).

   **Store** — mem rooted store for tests. Browser HTTP wiring lives in
   `dacite.examples.explorer-web`.

   First pass: expand/collapse vector, map, and set. Strings and blobs
   are truncated leaves (count + prefix). A later 'read more' can
   lengthen that prefix — not a char/byte tree."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.value :as v]))

;; =============================================================================
;; Values
;; =============================================================================

(def page-size
  "Children shown per expand / 'show next' click."
  32)

(def string-preview-chars
  "First-pass string prefix length."
  64)

(def blob-preview-bytes
  "First-pass blob hex prefix length."
  16)

(def page-me-count
  "Gallery vector long enough that the first page is not the whole thing."
  128)

(def public-types
  "Every user-facing type name the gallery must contain."
  #{"null" "bool" "char"
    "i8" "i16" "i32" "i64"
    "u8" "u16" "u32" "u64" "u256"
    "f32" "f64" "negative"
    "string" "blob" "vector" "map" "set"})

(defn node-kind
  "Keyword kind for explorer rows: :scalar, :string, :blob, :vector, :map, :set."
  [x]
  (if-not (v/dacite-value? x)
    :host
    (case (v/type x)
      "vector" :vector
      "map"    :map
      "set"    :set
      "string" :string
      "blob"   :blob
      :scalar)))

(defn expandable?
  "True if the first-pass tree can open children under x."
  [x]
  (contains? #{:vector :map :set} (node-kind x)))

(defn- join-chars
  "Concatenate a seq of characters without `apply str` (chunked apply
   can stop at 20+32 = 52 args)."
  [cs]
  #?(:clj
     (let [sb (StringBuilder.)]
       (doseq [ch cs]
         (.append sb (clojure.core/str ch)))
       (.toString sb))
     :cljs
     (.join (to-array (map clojure.core/str cs)) "")))

(def ^:private hex-digits "0123456789abcdef")

(defn- byte->hex [b]
  (let [n (bit-and 255 (long b))]
    (str (.charAt hex-digits (quot n 16))
         (.charAt hex-digits (mod n 16)))))

(defn- byte-seq
  "Host bytes (array, Uint8Array, or seq of ints) as 0–255 longs."
  [bs]
  (cond
    (nil? bs) []
    #?(:clj (bytes? bs) :cljs false)
    #?(:clj (map #(bit-and 255 %) bs) :cljs nil)
    (sequential? bs) (map #(bit-and 255 (long %)) bs)
    :else
    #?(:cljs (map #(bit-and 255 %) (array-seq bs))
       :clj [])))

(defn- bytes-hex
  "Space-separated hex, at most `n` bytes."
  [bs n]
  (str/join " " (map byte->hex (take n (byte-seq bs)))))

(defn- u256-bytes
  "32 bytes for the gallery u256 scalar."
  []
  #?(:clj (byte-array (map unchecked-byte (range 32)))
     :cljs (js/Uint8Array. (into-array (range 32)))))

(defn- blob-bytes
  "40 bytes so the 16-byte hex preview truncates."
  []
  #?(:clj (byte-array (map unchecked-byte (range 40)))
     :cljs (mapv identity (range 40))))

(defn- long-string
  "Longer than string-preview-chars so the row shows an ellipsis."
  []
  (apply str (repeat 5 "The explorer shows type and value. ")))

(defn- scalar-native-str
  "Display string for a scalar's native host value."
  [v]
  (let [t (v/type v)
        n (v/native v)]
    (case t
      "null"     "nil"
      "negative" "negative"
      "u256"     (bytes-hex n 32)
      "char"     (pr-str n)
      "string"   (pr-str n)
      (str n))))

(defn- string-preview
  "Prefix of a Dacite string plus truncation flag. Uses lazy realize."
  [v]
  (let [n (v/count v)
        take-n (min n string-preview-chars)
        s (join-chars (take take-n (or (v/realize v) ())))]
    {:preview s
     :truncated? (> n take-n)}))

(defn- blob-preview
  "Hex prefix of a Dacite blob plus truncation flag. Uses lazy realize."
  [v]
  (let [n (v/count v)
        take-n (min n blob-preview-bytes)
        hex (bytes-hex (take take-n (or (v/realize v) ())) take-n)]
    {:preview hex
     :truncated? (> n take-n)}))

(defn row-summary
  "Structured row for the explorer. Never dumps a collection into RAM.

   Keys:
     :type :kind :hash
     :count (collections, string, blob)
     :native (scalars, as a display string)
     :preview :truncated? (string / blob)"
  [v]
  (when-not (v/dacite-value? v)
    (throw (ex-info "row-summary expects a Dacite value" {:value v})))
  (let [kind (node-kind v)
        t (v/type v)
        base {:type t
              :kind kind
              :hash (v/hash v)}]
    (case kind
      :scalar (assoc base :native (scalar-native-str v))
      :string (let [{:keys [preview truncated?]} (string-preview v)]
                (assoc base :count (v/count v)
                       :preview preview
                       :truncated? truncated?))
      :blob (let [{:keys [preview truncated?]} (blob-preview v)]
              (assoc base :count (v/count v)
                     :preview preview
                     :truncated? truncated?))
      (assoc base :count (v/count v)))))

(defn child-page
  "One page of children under a vector, map, or set.

   Returns {:offset :items :total :done?}
   Each item is {:label :value}. Vector label is the index; map label is
   the key value (a Dacite value); set label is nil."
  ([v] (child-page v 0 page-size))
  ([v offset] (child-page v offset page-size))
  ([v offset limit]
   (let [kind (node-kind v)]
     (if-not (expandable? v)
       {:offset 0 :items [] :total 0 :done? true}
       (let [total (v/count v)
             off (max 0 (min offset total))
             lim (max 0 limit)
             end (min total (+ off lim))
             items
             (case kind
               :vector
               (mapv (fn [i] {:label i :value (v/nth v i)})
                     (range off end))
               :map
               (mapv (fn [[k val]] {:label k :value val})
                     (take lim (drop off (or (v/seq v) ()))))
               :set
               (mapv (fn [x] {:label nil :value x})
                     (take lim (drop off (or (v/seq v) ()))))
               [])]
         {:offset off
          :items items
          :total total
          :done? (>= end total)})))))

(defn- walk-types
  [x acc]
  (when (v/dacite-value? x)
    (swap! acc conj (v/type x))
    (when (expandable? x)
      (doseq [{:keys [label value]} (:items (child-page x 0 (v/count x)))]
        (walk-types label acc)
        (walk-types value acc)))))

(defn collect-types
  "Set of type names reachable from v. Walks via child-page (full pages).
   For tests — not the UI."
  [v]
  (let [acc (atom #{})]
    (walk-types v acc)
    @acc))

(defn gallery-via
  "Type-coverage map relative to `peer` (store, root, or value)."
  [peer]
  (let [vk (v/vector peer "nested" "key")
        inner (v/map peer "k" "v")
        page (reduce (fn [acc m] (v/conj acc m))
                     (v/vector peer)
                     (map (fn [i] (v/map peer "n" i))
                          (range page-me-count)))]
    (v/map
     peer
     "scalars"
     (v/map
      peer
      "null" (v/null peer)
      "bool" (v/bool peer true)
      "char" (v/char peer #?(:clj \A :cljs "A"))
      "i8" (v/i8 peer -8)
      "i16" (v/i16 peer -16)
      "i32" (v/i32 peer -32)
      "i64" (v/i64 peer -64)
      "u8" (v/u8 peer 8)
      "u16" (v/u16 peer 16)
      "u32" (v/u32 peer 32)
      "u64" (v/u64 peer 64)
      "u256" (v/u256 peer (u256-bytes))
      "f32" (v/f32 peer 1.5)
      "f64" (v/f64 peer 2.5)
      "negative" (v/negative peer))
     "string" (v/string peer (long-string))
     "blob" (v/blob peer (blob-bytes))
     "vector" (v/vector peer 1 2 inner)
     "map" (v/map peer
                  vk (v/i64 peer 7)
                  "plain" (v/bool peer false))
     "set" (v/set peer 1 "two" inner)
     "page-me" page)))

(defn load-or-seed!
  "Load the current root, or CAS-seed the type gallery from nil.

   Seed with `cas!` so the same code works against a remote store.
   An existing root is never overwritten. Returns [value seeded?]."
  [root-ref]
  (if-let [prior (v/deref root-ref)]
    [prior false]
    (let [g (gallery-via root-ref)]
      (if (v/cas! root-ref nil g)
        [g true]
        [(v/deref root-ref) false]))))

;; =============================================================================
;; Store
;; =============================================================================

(defn open-mem
  "In-memory rooted store for tests and REPL. Browser uses explorer-web."
  []
  (store/mem))
