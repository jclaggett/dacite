(ns dacite.value.lit
  "Sequence-literal algebra for pack items and ft/digit|node page bodies.

   A page body is an ordered vector of nested lits. Contiguous same-type
   leaves collapse to run/repeat. A ref is a 32-byte hash of a child that
   is not inlined (a large value, or an ft/* spine node).

   See docs/design/dense-sequence-leaves.md and leaf-chunking.md 2f."
  (:require [dacite.host :as host]
            [dacite.store :as store]
            [dacite.value.types :as types]))

(def scalar-types
  "First-class scalar type names that intern via put-scalar!."
  #{"null" "bool" "char"
    "i8" "i16" "i32" "i64"
    "u8" "u16" "u32" "u64" "u256"
    "f32" "f64" "negative"})

(def rle-seq-types
  "Store types whose literal body is an ordered list of nested lits."
  #{"vector" "set"
    "ft/empty" "ft/digit" "ft/node" "ft/deep"
    "hamt/empty"})

(defn join-chars
  "Concatenate characters without `apply str` (CLJS apply of a long
   lazy seq can throw RangeError / silently stop around 52 args)."
  [cs]
  #?(:clj
     (let [sb (StringBuilder.)]
       (doseq [ch cs]
         (.append sb (str ch)))
       (.toString sb))
     :cljs
     (.join (to-array (map str cs)) "")))

(defn run-form?
  [x]
  (and (map? x)
       (let [t (str (:type x))]
         (or (= t "run") (= t "repeat")))))

(defn- pack-run-values
  "Compact :values for a type-run of lits that share `of`."
  [of lits]
  (if (= of "char")
    (join-chars (map :body lits))
    (mapv :body lits)))

(declare rle-form) ; public: pack uses it on nested pair lits

(defn- all-bodies-equal?
  [lits]
  (let [bs (mapv :body lits)]
    (and (seq bs) (apply = bs))))

(defn rle-lits
  "Collapse contiguous same-type nested lits into run (or repeat if all
   bodies are equal). Length-1 groups stay unwrapped. Recurses into
   collection / map bodies first. Idempotent: existing run/repeat lits
   are not merged with neighbors."
  [lits]
  (let [lits (mapv rle-form (or lits []))]
    (if (empty? lits)
      []
      (loop [remaining (seq lits)
             out []]
        (if-let [x (first remaining)]
          (if (run-form? x)
            (recur (next remaining) (conj out x))
            (let [t (str (:type x))
                  [same rst] (split-with (fn [y]
                                           (and (not (run-form? y))
                                                (= t (str (:type y)))))
                                         remaining)]
              (cond
                (= 1 (count same))
                (recur rst (conj out x))

                (all-bodies-equal? same)
                (recur rst
                       (conj out {:type "repeat"
                                  :body {:of t
                                         :n (count same)
                                         :value (:body (first same))}}))

                :else
                (recur rst
                       (conj out {:type "run"
                                  :body {:of t
                                         :values (pack-run-values t same)}})))))
          out)))))

(defn rle-form
  "RLE nested sequence/map bodies of a typed literal. Scalars unchanged."
  [form]
  (if-not (and (map? form) (contains? form :type) (contains? form :body))
    form
    (let [t (str (:type form))
          b (:body form)]
      (cond
        (run-form? form) form

        (contains? rle-seq-types t)
        {:type t :body (rle-lits b)}

        (or (= t "map") (= t "hamt/bitmap"))
        {:type t
         :body (mapv (fn [pair]
                       [(rle-form (nth pair 0))
                        (rle-form (nth pair 1))])
                     (or b []))}

        (= t "hamt/entry")
        {:type t :body [(rle-form (nth b 0)) (rle-form (nth b 1))]}

        :else form))))

(defn- expand-run
  "Expand a type-run to n nested {:type :body} lits."
  [{:keys [body]}]
  (let [of (str (:of body))
        values (:values body)]
    (if (= of "char")
      (mapv (fn [ch] {:type "char" :body ch}) (seq (str values)))
      (mapv (fn [v] {:type of :body v}) (or values [])))))

(defn- expand-repeat
  "Expand a value-repeat to n copies of one nested lit."
  [{:keys [body]}]
  (let [of (str (:of body))
        n (long (or (:n body) 0))
        v (:value body)]
    (vec (repeat n {:type of :body v}))))

(defn expand-rle-seq
  "Expand run/repeat elements in an ordered lit list. Non-run lits stay.
   Does not recurse into map/vector bodies (those expand at materialize)."
  [xs]
  (into []
        (mapcat (fn [x]
                  (if-not (and (map? x) (contains? x :type))
                    [x]
                    (let [t (str (:type x))]
                      (cond
                        (= t "run") (expand-run x)
                        (= t "repeat") (expand-repeat x)
                        :else [x]))))
                (or xs []))))

(defn intern-lit-item!
  "Persist one expanded {:type :body} lit. Returns its content hash.

   `ref` is already a hash. Scalars are stored as `[type body]` at their
   value hash. Host-shaped string bodies coerce through the type table
   (collections must be loaded). Nested lit vectors are not expanded here."
  [store {:keys [type body]}]
  (let [t (str type)]
    (cond
      (= t "ref") body
      (contains? scalar-types t)
      (let [tv [t body]
            h (types/scalar-value-hash tv)]
        (binding [store/*cache-only* true]
          (store/s-put store h tv))
        h)
      (= t "string") (types/coerce-and-store! store (str body))
      :else
      (throw (ex-info "cannot intern page-body lit item"
                      {:type t})))))

(defn body-child-hashes
  "Logical direct-child hashes of a page body, interning inlined scalars."
  [store body]
  (mapv #(intern-lit-item! store %) (expand-rle-seq body)))

(def page-budget
  "Payload bytes a digit/node page aims to hold. 32 hashes × 32 bytes."
  1024)

(defn- utf8-len
  [s]
  (count (host/utf8-bytes (str s))))

(defn- scalar-payload-bytes
  [t v]
  (let [t (str t)]
    (case t
      "char" (utf8-len v)
      "u8" 1
      "bool" 1
      "i8" 1
      "u16" 2
      "i16" 2
      "i32" 4
      "u32" 4
      "f32" 4
      "i64" 8
      "u64" 8
      "f64" 8
      "u256" 32
      "null" 0
      "negative" 0
      "ref" 32
      "string" (utf8-len v)
      "blob" #?(:clj (if (bytes? v) (alength ^bytes v) (count v))
                :cljs (if (exists? js/Uint8Array)
                        (.-length v)
                        (count v)))
      ;; unknown nested lit: treat as not fitting a pointer
      32)))

(defn item-payload-bytes
  "Useful payload bytes of one body item (run/repeat/ref/scalar/string).
   Headers are excluded, matching the 32×32-byte children budget."
  [item]
  (let [t (str (:type item))
        b (:body item)]
    (case t
      "run"
      (let [of (str (:of b))
            vs (:values b)]
        (case of
          "char" (utf8-len vs)
          "u8" (count (or vs []))
          "ref" (* 32 (count (or vs [])))
          (* (scalar-payload-bytes of nil)
             (count (or vs [])))))

      "repeat"
      (* (long (or (:n b) 0))
         (scalar-payload-bytes (:of b) (:value b)))

      "ref" 32
      (scalar-payload-bytes t b))))

(defn payload-bytes
  "Sum of item-payload-bytes of a page body."
  [body]
  (reduce + 0 (map item-payload-bytes (or body []))))

(defn append-item
  "Append an expanded lit item to a body and re-collapse runs."
  [body item]
  (rle-lits (conj (vec (expand-rle-seq body)) item)))

(defn prepend-item
  "Prepend an expanded lit item to a body and re-collapse runs."
  [body item]
  (rle-lits (into [item] (expand-rle-seq body))))

(defn- split-char-values
  "Split a char sequence so the left side's UTF-8 length is >= target
   (or the whole string if shorter). Character boundaries only."
  [s target]
  (let [s (str s)
        n (count s)]
    (loop [i 0 acc 0]
      (cond
        (>= i n) [s ""]
        (>= acc target) [(subs s 0 i) (subs s i)]
        :else (recur (inc i) (+ acc (utf8-len (nth s i))))))))

(defn- split-vec-at-bytes
  "Split a vector of equal-size items so left payload >= target."
  [vs elem-size target]
  (let [vs (vec vs)
        n (count vs)
        k (if (pos? elem-size)
            (min n (max 1 (quot (+ (long target) elem-size -1) elem-size)))
            n)]
    [(subvec vs 0 k) (subvec vs k)]))

(defn- split-item-at
  "Split one body item. Returns [left-item-or-nil right-item-or-nil]."
  [item target]
  (let [t (str (:type item))
        b (:body item)]
    (cond
      (<= (item-payload-bytes item) target)
      [item nil]

      (= t "run")
      (let [of (str (:of b))
            vs (:values b)]
        (case of
          "char"
          (let [[l r] (split-char-values vs target)]
            [(when (pos? (count l))
               (if (= 1 (count l))
                 {:type "char" :body (first l)}
                 {:type "run" :body {:of "char" :values l}}))
             (when (pos? (count r))
               (if (= 1 (count r))
                 {:type "char" :body (first r)}
                 {:type "run" :body {:of "char" :values r}}))])

          (let [elem (case of
                       "u8" 1
                       "ref" 32
                       (scalar-payload-bytes of nil))
                [l r] (split-vec-at-bytes vs elem target)]
            [(when (seq l)
               (if (= 1 (count l))
                 {:type of :body (first l)}
                 {:type "run" :body {:of of :values (vec l)}}))
             (when (seq r)
               (if (= 1 (count r))
                 {:type of :body (first r)}
                 {:type "run" :body {:of of :values (vec r)}}))])))

      (= t "repeat")
      (let [of (str (:of b))
            n (long (:n b))
            v (:value b)
            one (scalar-payload-bytes of v)
            k (min n (max 1 (quot (+ (long target) (max 1 one) -1) (max 1 one))))
            left (if (= k 1)
                   {:type of :body v}
                   {:type "repeat" :body {:of of :n k :value v}})
            rn (- n k)
            right (cond
                    (zero? rn) nil
                    (= rn 1) {:type of :body v}
                    :else {:type "repeat" :body {:of of :n rn :value v}})]
        [left right])

      :else
      [item nil])))

(defn chunk-run
  "Split a homogeneous run (`of` + `values`) into items each within `budget`
   payload bytes. Used to persist 1k pages without per-element conj."
  [of values budget]
  (let [of (str of)
        values (if (= of "char") (str values) (vec values))
        item (let [n (if (= of "char") (count values) (count values))]
               (cond
                 (zero? n) nil
                 (= n 1) {:type of :body (if (= of "char") (first values) (first values))}
                 :else {:type "run" :body {:of of :values values}}))]
    (if (nil? item)
      []
      (loop [item item
             out []]
        (if (or (nil? item) (zero? (item-payload-bytes item)))
          out
          (if (<= (item-payload-bytes item) budget)
            (conj out item)
            (let [[l r] (split-item-at item budget)]
              (if (nil? l)
                (conj out item)
                (recur r (conj out l))))))))))

(defn split-body-at
  "Split a page body so the left side's payload is about `target` bytes.
   Returns [left-body right-body]. Either side may be empty."
  [body target]
  (loop [items (seq (or body []))
         acc 0
         left []]
    (if (nil? items)
      [(rle-lits left) []]
      (let [item (first items)
            sz (item-payload-bytes item)]
        (if (< (+ acc sz) target)
          (recur (next items) (+ acc sz) (conj left item))
          (let [[l r] (split-item-at item (max 0 (- target acc)))
                left' (if l (conj left l) left)
                right (rle-lits (into (if r [r] []) (rest items)))]
            [(rle-lits left') right]))))))

(defn body-refs
  "Hashes named by `ref` items (including ref runs/repeats). Inlined
   scalars and nested value lits are omitted — they live in the body."
  [body]
  (into []
        (mapcat (fn [item]
                  (if-not (and (map? item) (contains? item :type))
                    []
                    (let [t (str (:type item))
                          b (:body item)]
                      (cond
                        (= t "ref") [b]
                        (and (= t "run") (= "ref" (str (:of b))))
                        (or (:values b) [])
                        (and (= t "repeat") (= "ref" (str (:of b))))
                        (repeat (long (or (:n b) 0)) (:value b))
                        :else []))))
                (or body []))))
