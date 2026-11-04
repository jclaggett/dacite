(ns dacite.store.bound
  "An upper bound of the size of a node's literal, from the node alone.

   A sender used to build a value's literal to learn whether it fits one
   frame, and built the literal of each big descendant again (`pack/literal-gate`
   cut that short, at the price of a cliff).
   Here the answer is a number computed from the store entries, without
   building any form: the bytes the literal can take on the wire (wire-v1,
   spec section 2) is at most `(literal-item-bound st h)`. A literal is built
   only when that is at or under the cap, and then it fits.

   **A monoid, in memory only.** For a sequence (vector, set, string, and the
   `ft/*` nodes under them) the bound is a sum over its elements, kept as a
   small summary that combines in order:

     {:f first-type :l last-type :b bound :v sum-of-element-bytes}

   `:b` is the sum, over the maximal groups of one type in the sequence, of
   `run-header + value bytes` (a group the literal turns into a run, a repeat
   or one value, each of which costs no more). Two summaries combine by
   adding `:b` and taking the header off once when the last type of the first
   is the first type of the second (their two groups are one in the
   concatenation). The summary of a node is combined from the summaries of its
   children, so a node is summarized once per call (a cache the caller owns),
   in time linear in the items of its pages, and not from its text.

   Nothing here is stored in a node, sent, or hashed: the cache is an atom the
   caller passes, dropped with the call, and a value's hash is the hash of its
   elements, not of this.

   The bound counts no run or repeat compression of equal values (a repeat of
   100 000 equal i64 is bounded by their 800 000 bytes) and counts the header of
   a run in full. A later, exact variant would carry each node's first and last
   run (type, length and value) in the summary and merge them on combine, so a
   repeat that spans pages is bounded by its real size. It is roadmap only, not built
   (docs/plan/peering.md)."
  (:require [dacite.host :as host]
            [dacite.store :as store]
            [dacite.value.hamt :as hamt]
            [dacite.value.lit :as lit]
            [dacite.value.types :as types]
            [dacite.rooted.gc :as gc]))

(def item-header-bytes
  "A literal item's header: enc u8, hash 32, plen u32 (wire-v1)."
  37)

(def collection-overhead-bytes
  "A sequence, set or map literal: its type tag and a u32 count."
  5)

(def ^:private fixed-value-bytes
  "Value bytes (without the type tag) of the scalars whose size is fixed."
  {"null" 0 "negative" 0 "bool" 1 "i8" 1 "u8" 1 "i16" 2 "u16" 2
   "i32" 4 "u32" 4 "f32" 4 "i64" 8 "u64" 8 "f64" 8 "u256" 32})

(defn- run-header
  "Bytes of a run or repeat of type `t` besides its values: tag, inner type,
   u32 count; a `char` run also has the u32 byte length of its text."
  [t]
  (if (= "char" (str t)) 10 6))

(defn- unknown! [what]
  (throw (ex-info "no bound" {:reason ::unknown :what what})))

(defn- cached
  [cache k f]
  (if cache
    (let [hit (get @cache k ::miss)]
      (if (= hit ::miss)
        (let [v (f)]
          (swap! cache assoc k v)
          v)
        hit))
    (f)))

;; ---------------------------------------------------------------------------
;; The summary monoid
;; ---------------------------------------------------------------------------

(defn combine
  "Summary of the concatenation. `nil` is the empty sequence."
  [a b]
  (cond
    (nil? a) b
    (nil? b) a
    :else {:f (:f a)
           :l (:l b)
           :v (+ (long (:v a)) (long (:v b)))
           :b (- (+ (long (:b a)) (long (:b b)))
                 (if (= (:l a) (:f b)) (long (run-header (:l a))) 0))}))

(defn- group
  "Summary of `n` elements of type `t` whose value bytes add up to `v`."
  [t n v]
  (when (pos? (long n))
    {:f t :l t :v v :b (+ (long (run-header t)) (long v))}))

(defn- text-bytes
  "The UTF-8 bytes of a text (a bound that is always met exactly: runs split
   at code points, so no two runs join into a longer encoding)."
  [s]
  (lit/utf8-len s))

(declare lit-size hash-summary)

(defn- node-of [st h]
  (or (store/await (store/s-get st h)) (unknown! :missing)))

(defn- ft-type? [t] (.startsWith ^String (str t) "ft/"))

(defn- ref-summary [st cache h] (hash-summary st cache h))

(defn- scalar-element
  "Summary of one inline scalar, string or blob of type `t` with body `b`."
  [t b]
  (let [t (str t)]
    (cond
      (= t "char") (group "char" 1 (text-bytes b))
      (contains? fixed-value-bytes t) (group t 1 (fixed-value-bytes t))
      (= t "string") (group t 1 (+ 4 (long (text-bytes b))))
      (= t "blob") (group t 1 (+ 4 (long (lit/u8-count b))))
      :else (unknown! t))))

(defn- values-bytes
  "Value bytes of the `values` of a run of type `of`, without the header."
  [of vs]
  (let [of (str of)]
    (cond
      (= of "char") (long (text-bytes vs))
      (= of "u8") (long (lit/u8-count vs))
      (contains? fixed-value-bytes of) (* (long (fixed-value-bytes of)) (count vs))
      (= of "string") (reduce + 0 (map #(+ 4 (long (text-bytes %))) vs))
      (= of "blob") (reduce + 0 (map #(+ 4 (long (lit/u8-count %))) vs))
      :else (unknown! of))))

(defn- item-summary
  "Summary of the elements one page-body item stands for."
  [st cache item]
  (let [t (str (:type item))
        b (:body item)]
    (case t
      "ref" (ref-summary st cache b)
      "run" (let [of (str (:of b))
                  vs (:values b)]
              (if (= of "ref")
                (reduce combine nil (map #(ref-summary st cache %) vs))
                (group of (if (= of "char") (host/cp-count vs) (lit/u8-count vs))
                       (values-bytes of vs))))
      "repeat" (let [of (str (:of b))
                     n (long (:n b))]
                 (if (= of "ref")
                   (reduce combine nil (repeat n (ref-summary st cache (:value b))))
                   (group of n (* n (long (values-bytes of [(:value b)]))))))
      (scalar-element t b))))

(defn- seq-summary
  "Summary of the leaf sequence under finger-tree node `h` (or of the one
   leaf, if `h` is not an `ft/*` node)."
  [st cache h]
  (cached
   cache [:sq (gc/hash-key h)]
   (fn []
     (let [[t data] (node-of st h)]
       (case (str t)
         "ft/empty" nil
         ("ft/digit" "ft/node")
         (if-let [ch (:children data)]
           (reduce combine nil (map #(ref-summary st cache %) ch))
           (reduce combine nil (map #(item-summary st cache %) (or (:body data) []))))
         "ft/deep"
         (combine (combine (seq-summary st cache (:left data))
                           (seq-summary st cache (:spine data)))
                  (seq-summary st cache (:right data)))
         (hash-summary st cache h))))))

(defn- hash-summary
  "Summary of the element or sub-tree at `h`."
  [st cache h]
  (let [[t data] (node-of st h)
        t (str t)]
    (cond
      (ft-type? t) (seq-summary st cache h)
      (= t "char") (group "char" 1 (text-bytes data))
      :else (group t 1 (dec (long (lit-size st cache h)))))))

;; ---------------------------------------------------------------------------
;; Literal sizes
;; ---------------------------------------------------------------------------

(defn- item-exact
  "Wire bytes of one page-body item as it is (a page whose body is its own
   literal)."
  [item]
  (let [t (str (:type item))
        b (:body item)]
    (case t
      "run" (let [of (str (:of b))]
              (+ (long (run-header of)) (long (values-bytes of (:values b)))))
      "repeat" (let [of (str (:of b))
                     one (long (values-bytes of [(:value b)]))]
                 (+ 6 (if (= of "char") (inc one) one)))
      "ref" (unknown! :ref)
      ;; a single value: its tag and its bytes (a char also a length byte)
      (+ 1 (if (= t "char") 1 0) (long (:v (scalar-element t b)))))))

(defn- sequence-literal-size
  [st cache h]
  (+ collection-overhead-bytes (long (or (:b (seq-summary st cache h)) 0))))

(defn- pairs-size
  [st cache pairs]
  (reduce (fn [acc [k v]]
            (+ acc (long (lit-size st cache k)) (long (lit-size st cache v))))
          collection-overhead-bytes
          pairs))

(defn- lit-size
  "Upper bound of the wire bytes of the literal form of the value or node at
   `h` (its type tag included). Throws `::unknown` for what has no literal."
  [st cache h]
  (cached
   cache [:ls (gc/hash-key h)]
   (fn []
     (let [[t data] (node-of st h)
           t (str t)]
       (cond
         (= t "char") (+ 2 (long (text-bytes data)))
         (contains? fixed-value-bytes t) (+ 1 (long (fixed-value-bytes t)))
         (= t "string") (+ 5 (long (or (:v (seq-summary st cache (:root data))) 0)))
         (= t "blob") (+ 5 (long (or (:count data) 0)))
         (= t "vector") (sequence-literal-size st cache (:root data))
         (= t "set")
         (+ collection-overhead-bytes
            (long (or (:b (reduce combine nil
                                  (map (fn [[kh _]] (hash-summary st cache kh))
                                       (hamt/hamt-entries st (:root data)))))
                      0)))
         (= t "map") (pairs-size st cache (hamt/hamt-entries st (:root data)))
         (= t "ft/empty") collection-overhead-bytes
         (contains? #{"ft/digit" "ft/node"} t)
         (let [body (:body data)]
           (if (lit/self-contained-body? body)
             (+ collection-overhead-bytes (reduce + 0 (map item-exact body)))
             (sequence-literal-size st cache h)))
         (= t "ft/deep") (sequence-literal-size st cache h)
         (= t "hamt/empty") collection-overhead-bytes
         (= t "hamt/entry") (+ 1 (long (lit-size st cache (:key-ref data)))
                               (long (lit-size st cache (:val-ref data))))
         (= t "hamt/bitmap") (pairs-size st cache (hamt/hamt-entries st h))
         :else (unknown! t))))))

(defn literal-item-bound
  "Upper bound in bytes of the literal item (37-byte header included) for the
   value or node at `h`, or nil when the type has no literal or an entry is
   missing. `cache` is an atom (or nil): the summaries and sizes of the nodes
   met, by hash; keep it for the length of one call and drop it."
  [st h cache]
  (try
    (+ item-header-bytes (long (lit-size st cache h)))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
      (if (or (= ::unknown (:reason (ex-data e)))
              (= :missing (:reason (ex-data e))))
        nil
        (throw e)))))
