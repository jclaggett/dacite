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

(defn u8-bytes?
  "True when `x` is a host byte buffer (JVM `byte[]`, CLJS `Uint8Array`)."
  [x]
  #?(:clj (bytes? x)
     :cljs (instance? js/Uint8Array x)))

(defn u8-count
  "Number of bytes in a u8 run payload (byte buffer or seq of 0..255)."
  [vs]
  (cond
    (nil? vs) 0
    (u8-bytes? vs) #?(:clj (alength ^bytes vs) :cljs (.-length vs))
    :else (count vs)))

(defn u8-nth
  "Unsigned byte 0..255 at `i`. Boxes that one value."
  [vs i]
  (if (u8-bytes? vs)
    #?(:clj (Byte/toUnsignedInt (aget ^bytes vs (int i)))
       :cljs (aget vs i))
    (bit-and 0xff (int (nth vs i)))))

(defn copy-u8-range
  "Fresh byte buffer of `vs[start, end)`. A later write to `vs` does not
   change the copy."
  [vs start end]
  (let [start (long start)
        end (long end)
        n (- end start)]
    #?(:clj
       (if (bytes? vs)
         (java.util.Arrays/copyOfRange ^bytes vs (int start) (int end))
         (let [out (byte-array n)]
           (dotimes [i n]
             (aset-byte out i (unchecked-byte (u8-nth vs (+ start i)))))
           out))
       :cljs
       (let [out (js/Uint8Array. n)]
         (if (instance? js/Uint8Array vs)
           (.set out (.subarray vs start end))
           (dotimes [i n]
             (aset out i (u8-nth vs (+ start i)))))
         out))))

(defn u8-run?
  "True when `item` is a run of u8 values."
  [item]
  (and (map? item)
       (= "run" (str (:type item)))
       (= "u8" (str (:of (:body item))))))

(defn u8-piece?
  "True when `item` is one u8, a u8 run, or a u8 repeat."
  [item]
  (let [t (str (:type item))]
    (or (= t "u8")
        (and (or (= t "run") (= t "repeat"))
             (= "u8" (str (:of (:body item))))))))

(defn u8-piece-count
  [item]
  (let [t (str (:type item))
        b (:body item)]
    (case t
      "u8" 1
      "run" (u8-count (:values b))
      "repeat" (long (or (:n b) 0)))))

(defn u8-piece-nth
  "Unsigned byte at index `i` of a u8 piece. Boxes that one value."
  [item i]
  (let [t (str (:type item))
        b (:body item)]
    (case t
      "u8" (bit-and 0xff (int b))
      "run" (u8-nth (:values b) i)
      "repeat" (bit-and 0xff (int (:value b))))))

(defn- u8-run-item
  "One u8 lit for `[start, end)`. Length 1 stays a scalar; longer spans
   are a run whose payload is a copied byte buffer."
  [vs start end]
  (let [n (- (long end) (long start))]
    (if (= 1 n)
      {:type "u8" :body (u8-nth vs start)}
      {:type "run" :body {:of "u8" :values (copy-u8-range vs start end)}})))

(defn u8-page-items
  "Split a u8 source into page items of at most `budget` bytes.
   One forward pass. Each page is a copy of its range."
  [vs budget]
  (let [vs (cond
             (u8-bytes? vs) vs
             (vector? vs) vs
             :else (vec vs))
        n (u8-count vs)
        step (max 1 (long budget))]
    (loop [start 0 out (transient [])]
      (if (>= start n)
        (persistent! out)
        (let [end (min n (+ start step))]
          (recur end (conj! out (u8-run-item vs start end))))))))

(defn self-contained-body?
  "True when every page item can be measured without looking up a hash.
   A `ref` names another store entry; scalar runs do not."
  [body]
  (and (sequential? body)
       (seq body)
       (every? (fn [item]
                 (and (map? item)
                      (contains? item :type)
                      (let [t (str (:type item))
                            of (str (:of (:body item)))]
                        (not (or (= t "ref")
                                 (and (or (= t "run") (= t "repeat"))
                                      (= of "ref")))))))
               body)))

(defn run-form?
  [x]
  (and (map? x)
       (let [t (str (:type x))]
         (or (= t "run") (= t "repeat")))))

(defn- pack-u8-values
  "Copied byte buffer of the bodies of u8 lits."
  [lits]
  ;; `nth` of a lazy seq is O(n), so index a vector (this was quadratic: a
  ;; 100 KB blob took 110 s to encode)
  (let [lits (if (vector? lits) lits (vec lits))
        n (count lits)]
    #?(:clj
       (let [out (byte-array n)]
         (dotimes [i n]
           (aset-byte out i (unchecked-byte (bit-and 0xff (int (:body (nth lits i)))))))
         out)
       :cljs
       (let [out (js/Uint8Array. n)]
         (dotimes [i n]
           (aset out i (bit-and 0xff (int (:body (nth lits i))))))
         out))))

(defn- pack-run-values
  "Compact :values for a type-run of lits that share `of`."
  [of lits]
  (cond
    (= of "char") (join-chars (map :body lits))
    (= of "u8") (pack-u8-values lits)
    :else (mapv :body lits)))

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
    (cond
      (= of "char")
      (mapv (fn [ch] {:type "char" :body ch}) (host/chars-of values))

      (= of "u8")
      (let [n (u8-count values)]
        (mapv (fn [i] {:type "u8" :body (u8-nth values i)}) (range n)))

      :else
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

(defn item-count
  "Number of elements one body item stands for (a `ref` counts 1 here; a
   caller that needs the elements under a ref asks its measure)."
  [item]
  (let [t (str (:type item))
        b (:body item)]
    (case t
      "run" (let [of (str (:of b))
                  vs (:values b)]
              (case of
                "char" (host/cp-count vs)
                "u8" (u8-count vs)
                (count (or vs []))))
      "repeat" (long (or (:n b) 0))
      1)))

(defn item-at
  "The expanded {:type :body} element `i` of one body item, without
   expanding the others. `i` is below `item-count`."
  [item i]
  (let [t (str (:type item))
        b (:body item)]
    (case t
      "run" (let [of (str (:of b))
                  vs (:values b)]
              (case of
                "char" {:type "char" :body (host/char-at vs i)}
                "u8" {:type "u8" :body (u8-nth vs i)}
                {:type of :body (nth vs i)}))
      "repeat" {:type (str (:of b)) :body (:value b)}
      item)))

(defn names-child-hash?
  "True when a literal body names a child by hash: a `ref` item, or a `run`
   or `repeat` of `ref`, anywhere in the body. Such a body is not
   self-contained: its measure depends on entries it does not hold."
  [body]
  (boolean
   (some (fn [x]
           (and (map? x)
                (let [t (str (:type x))]
                  (or (= t "ref")
                      (and (contains? #{"run" "repeat"} t)
                           (map? (:body x))
                           (= "ref" (str (:of (:body x)))))))))
         (tree-seq coll? seq body))))

(defn- blob-host
  "Host byte buffer of a page-body blob: bytes already, or a seq of 0..255."
  [body]
  #?(:clj (if (bytes? body)
            body
            (byte-array (map #(unchecked-byte (bit-and (int %) 0xFF)) body)))
     :cljs (if (sequential? body)
             (js/Uint8Array. (clj->js (mapv #(bit-and (int %) 0xFF) body)))
             body)))

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
          (store/await (store/s-put store h tv)))
        h)
      (= t "string") (types/coerce-and-store! store (str body))
      (= t "blob") (types/coerce-and-store! store (blob-host body))
      :else
      (throw (ex-info "cannot intern page-body lit item"
                      {:type t})))))

(defn body-child-hashes
  "Logical direct-child hashes of a page body, interning inlined scalars."
  [store body]
  (mapv #(intern-lit-item! store %) (expand-rle-seq body)))

(def ^:dynamic page-budget
  "Payload bytes a digit/node page aims to hold (the leaf page budget).
   4 KiB (M1.1a item 5; see docs/design/leaf-chunking.md): about 3.7 times
   fewer pages than 1 KiB for a long string, 9-12% fewer total bytes, and
   about twice the bytes per one-element edit. 16 KiB does not fit a frame.

   Separate from the pack chunk budget (`pack/default-budget`, a soft
   Layer-2 target) and from the largest literal (`pack/default-max-literal`,
   tied to the frame payload). Value hashes do not depend on it: a node
   hashes `(type, elements-fuse)`, not how the elements are paged. Dynamic so
   benches can measure other sizes; the code reads it, never caches it."
  4096)

(def string-wire-overhead
  "A string or blob costs its length plus 5 bytes on the wire (a tag and a
   u32 length). It is counted in its payload size, so a page of tiny strings
   stays near its budget on the wire instead of several times over it.
   Part of keeping sizing on payload bytes; the full 'encoded byte size'
   measure is an open design item (docs/plan/peering.md, M1.1a item 2)."
  5)

(defn utf8-width
  "UTF-8 bytes of code point `cp`."
  [cp]
  (let [cp (long cp)]
    (cond (< cp 0x80) 1
          (< cp 0x800) 2
          (< cp 0x10000) 3
          :else 4)))

(defn utf8-len
  "Number of UTF-8 bytes of `s`, counted without building them.

   The same answer as `(count (host/utf8-bytes s))`, which allocated the
   bytes of the whole run for every append to a page. A lone surrogate has
   no UTF-8 form: it throws, as `host/utf8-bytes` does, the same on each
   host."
  [s]
  (let [s (str s)
        n (long (count s))]
    (loop [i 0 acc 0]
      (if (>= i n)
        acc
        (let [c (long #?(:clj (int (.charAt ^String s (int i)))
                         :cljs (.charCodeAt s i)))]
          (cond
            (< c 0x80) (recur (inc i) (+ acc 1))
            (< c 0x800) (recur (inc i) (+ acc 2))
            (or (< c 0xD800) (>= c 0xE000)) (recur (inc i) (+ acc 3))
            ;; a surrogate pair is one code point of four bytes; a lone
            ;; surrogate throws
            :else (let [cp (host/code-point-at s i)]
                    (recur (+ i (if (>= cp 0x10000) 2 1)) (+ acc 4)))))))))

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
      ;; an element is never free: a page of nulls must still end
      "null" 1
      "negative" 1
      "ref" 32
      "string" (+ string-wire-overhead (utf8-len v))
      "blob" (+ string-wire-overhead
                #?(:clj (cond (nil? v) 0 (bytes? v) (alength ^bytes v) :else (count v))
                   :cljs (cond (nil? v) 0
                               (and (exists? js/Uint8Array) (instance? js/Uint8Array v)) (.-length v)
                               :else (count v))))
      ;; unknown nested lit: treat as not fitting a pointer
      32)))

(defn- variable-size?
  "Types whose payload size depends on the value, not just the type."
  [of]
  (contains? #{"string" "blob"} (str of)))

(defn- values-payload-bytes
  "Payload bytes of the `values` of a run of type `of`."
  [of vs]
  (let [vs (or vs [])]
    (if (variable-size? of)
      (reduce + 0 (map #(scalar-payload-bytes of %) vs))
      (* (scalar-payload-bytes of nil) (count vs)))))

(def item-tag-bytes
  "The type tag of a singleton item on the wire. A `string` or `blob`
   already counts its tag in `string-wire-overhead`."
  1)

(def run-header-bytes
  "A `run` or `repeat` item's wire header: type tag, inner type id, u32
   count. A page of runs of two costs this per two values, so it is counted
   (a run or a repeat of one value is a singleton instead)."
  6)

(defn- run-overhead-bytes
  "Wire bytes of a run or repeat of type `of` besides its values: the header,
   and for `char` the u32 byte length that precedes the text."
  [of]
  (if (= "char" (str of))
    (+ run-header-bytes 4)
    run-header-bytes))

(defn- singleton-payload-bytes
  "Payload bytes of one value that is not part of a run: its body and its
   type tag (a `char` also has a one-byte length)."
  [t v]
  (let [n (scalar-payload-bytes t v)]
    (cond
      (variable-size? t) n
      (= "char" (str t)) (+ item-tag-bytes 1 n)
      :else (+ item-tag-bytes n))))

(defn item-payload-bytes
  "Wire bytes of one body item (run/repeat/ref/scalar/string), without the
   page's own item header. Matches the encoded size of the item (a ref is
   its 32 bytes and a tag; a singleton its body and a tag; a run its
   header and its values, one `repeat` copy counted per value so the page
   stays bounded in elements). The 32x32-byte children budget of spine
   pages is a count of children, not this.

   A `string` or `blob` counts its own length, alone, in a `run` and in a
   `repeat` (they used to count 0 in the last two, so a page could grow
   without bound). A one-byte value counts its tag as well (M1.1a review
   S1): a page of alternating `u8` and `char` is two bytes an element on
   the wire, not one."
  [item]
  (let [t (str (:type item))
        b (:body item)]
    (case t
      "run"
      (let [of (str (:of b))
            vs (:values b)]
        (+ (run-overhead-bytes of)
           (case of
             "char" (utf8-len vs)
             "u8" (u8-count vs)
             "ref" (* 32 (count (or vs [])))
             (values-payload-bytes of vs))))

      "repeat"
      (+ run-header-bytes
         (* (long (or (:n b) 0))
            (scalar-payload-bytes (:of b) (:value b))))

      "ref" (+ item-tag-bytes 32)
      (singleton-payload-bytes t b))))

(defn payload-bytes
  "Sum of item-payload-bytes of a page body."
  [body]
  (reduce + 0 (map item-payload-bytes (or body []))))

(defn- append-slow
  "The definition of `append-item`: expand, conj, collapse. O(body)."
  [body item]
  (rle-lits (conj (vec (expand-rle-seq body)) item)))

(defn- u8-conj
  "A copy of byte buffer `vs` with byte `b` added, or nil when `vs` is not
   a byte buffer."
  [vs b]
  (when (u8-bytes? vs)
    #?(:clj (let [n (alength ^bytes vs)
                  out (byte-array (inc n))]
              (System/arraycopy ^bytes vs 0 out 0 n)
              (aset-byte out n (unchecked-byte (bit-and 0xff (int b))))
              out)
       :cljs (let [n (.-length vs)
                   out (js/Uint8Array. (inc n))]
               (.set out vs)
               (aset out n (bit-and 0xff (int b)))
               out))))

(defn append-item
  "Append an expanded lit item to a body and re-collapse runs.

   The result is `rle-lits` of the expanded elements. A canonical body (one
   `rle-lits` produced) only changes at its last group, so that group alone
   is rewritten: O(1) in the body's element count (the definition,
   `append-slow`, is O(body), which made building a page one element at a
   time quadratic in the page budget). A test compares the two."
  [body item]
  (let [item (rle-form item)
        body (vec (or body []))
        n (count body)
        t (str (:type item))
        ;; the last group, when it can take `item`
        last-item (when (pos? n) (peek body))
        fast? (and last-item
                   (not (run-form? item))
                   (map? last-item)
                   (if (run-form? last-item)
                     (= t (str (:of (:body last-item))))
                     (= t (str (:type last-item)))))]
    (if-not fast?
      (if (or (zero? n) (run-form? item) (nil? last-item) (not (map? last-item)))
        (append-slow body item)
        (conj body item))                ; a different type starts a new group
      (let [head (pop body)]
        (if-not (run-form? last-item)
          ;; two of one type: a repeat when equal, else a run
          (conj head (if (= (:body last-item) (:body item))
                       {:type "repeat" :body {:of t :n 2 :value (:body item)}}
                       {:type "run" :body {:of t :values (pack-run-values t [last-item item])}}))
          (let [b (:body last-item)]
            (if (= "repeat" (str (:type last-item)))
              (if (= (:value b) (:body item))
                (conj head (assoc-in last-item [:body :n] (inc (long (:n b)))))
                (append-slow body item))
              (let [vs (:values b)
                    vs' (case t
                          "char" (str vs (:body item))
                          "u8" (u8-conj vs (:body item))
                          (when (vector? vs) (conj vs (:body item))))]
                (if (nil? vs')
                  (append-slow body item)
                  (conj head (assoc-in last-item [:body :values] vs')))))))))))

(defn payload-after-append
  "`(payload-bytes body')` for `body' = (append-item body item)`, given
   `pb`, the payload of `body`. O(size of the last group): `append-item`
   only rewrites the last item of a canonical body, so the new payload is
   the old one without that item and with the items from there on.
   Falls back to a full count when the earlier items are not the same
   objects (a body that was not canonical)."
  [body pb body']
  (let [n (count body)
        k (dec n)
        body' (if (vector? body') body' (vec body'))]
    (if (and (pos? n)
             (>= (count body') n)
             (or (zero? k)
                 (identical? (nth body (dec k)) (nth body' (dec k)))))
      (+ (long pb)
         (- (long (item-payload-bytes (nth body k))))
         (long (reduce + 0 (map item-payload-bytes (subvec body' k)))))
      (payload-bytes body'))))

(defn prepend-item
  "Prepend an expanded lit item to a body and re-collapse runs."
  [body item]
  (rle-lits (into [item] (expand-rle-seq body))))

(defn- split-char-values
  "Split a char sequence so the left side's UTF-8 length is >= target
   (or the whole string if shorter). Character (code point) boundaries only:
   a surrogate pair is never cut."
  [s target]
  (let [s (str s)
        n (count s)]
    (loop [i 0 acc 0]
      (cond
        (>= i n) [s ""]
        (>= acc target) [(subs s 0 i) (subs s i)]
        :else (let [cp (host/code-point-at s i)]
                (recur (+ i (if (>= cp 0x10000) 2 1))
                       (+ acc (utf8-width cp))))))))

(defn- split-vec-at-bytes
  "Split a vector of equal-size items so left payload >= target."
  [vs elem-size target]
  (let [vs (vec vs)
        n (count vs)
        k (if (pos? elem-size)
            (min n (max 1 (quot (+ (long target) elem-size -1) elem-size)))
            n)]
    [(subvec vs 0 k) (subvec vs k)]))

(defn- split-vec-by-sizes
  "Split a vector of strings or blobs so the left side holds at least
   `target` payload bytes (and at least one value)."
  [of vs target]
  (let [vs (vec vs)
        n (count vs)
        k (loop [i 0 acc 0]
            (cond
              (>= i n) n
              (and (pos? i) (>= acc target)) i
              :else (recur (inc i) (+ acc (long (scalar-payload-bytes of (nth vs i)))))))]
    [(subvec vs 0 k) (subvec vs k)]))

(defn- char-piece-item
  "The body item of a host string of characters: nil when empty, a `char`
   for one code point, else a `run` of `char`."
  [piece]
  (let [n (host/cp-count piece)]
    (cond
      (zero? n) nil
      (= 1 n) {:type "char" :body (host/char-at piece 0)}
      :else {:type "run" :body {:of "char" :values piece}})))

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
            [(char-piece-item l)
             (char-piece-item r)])

          "u8"
          (let [n (u8-count vs)
                k (if (pos? n)
                    (min n (max 1 (long target)))
                    0)
                rn (- n k)]
            [(when (pos? k)
               (if (= 1 k)
                 {:type "u8" :body (u8-nth vs 0)}
                 {:type "run" :body {:of "u8" :values (copy-u8-range vs 0 k)}}))
             (when (pos? rn)
               (if (= 1 rn)
                 {:type "u8" :body (u8-nth vs k)}
                 {:type "run" :body {:of "u8" :values (copy-u8-range vs k n)}}))])

          (let [elem (case of
                       "u8" 1
                       "ref" 32
                       (scalar-payload-bytes of nil))
                [l r] (if (variable-size? of)
                        (split-vec-by-sizes of vs target)
                        (split-vec-at-bytes vs elem target))]
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

(defn- chunk-item
  [of piece]
  (if (= 1 (count piece))
    {:type of :body (nth piece 0)}
    {:type "run" :body {:of of :values piece}}))

(defn- chunk-chars
  "One left-to-right pass over code points: a page never ends inside a
   surrogate pair, and a character is its UTF-8 length (1 to 4 bytes) against
   the budget. Each chunk is a fresh substring, so the tail of a book is not
   copied on every split."
  [s budget]
  (let [n (count s)
        budget (max 1 (long budget))]
    (loop [from 0 out (transient [])]
      (if (>= from n)
        (persistent! out)
        (let [end (loop [i from acc 0]
                    (if (>= i n)
                      i
                      (let [cp (host/code-point-at s i)
                            u (utf8-width cp)]
                        (if (and (pos? acc) (> (+ acc u) budget))
                          i
                          (recur (+ i (if (>= cp 0x10000) 2 1)) (+ acc u))))))]
          (recur end (conj! out (char-piece-item (subs s from end)))))))))

(defn- copy-range
  "A new vector of `vs[start, end)`. A subvec would keep the whole source
   array alive for as long as any page body is."
  [vs start end]
  (mapv #(nth vs %) (range start end)))

(defn- chunk-elems
  [of vs budget]
  (let [n (count vs)
        budget (max 1 (long budget))]
    (if (variable-size? of)
      ;; strings and blobs: cut where the sum of their lengths crosses the
      ;; budget (at least one value a chunk)
      (loop [start 0 out (transient [])]
        (if (>= start n)
          (persistent! out)
          (let [end (loop [i start acc 0]
                      (if (>= i n)
                        i
                        (let [sz (long (scalar-payload-bytes of (nth vs i)))]
                          (if (and (> i start) (> (+ acc sz) budget))
                            i
                            (recur (inc i) (+ acc sz))))))
                piece (copy-range vs start end)]
            (recur end (conj! out (chunk-item of piece))))))
      (let [elem (case of
                   "u8" 1
                   "ref" 32
                   (scalar-payload-bytes of nil))
            step (max 1 (if (pos? (long elem)) (quot budget (long elem)) n))]
        (loop [start 0 out (transient [])]
          (if (>= start n)
            (persistent! out)
            (let [end (min n (+ start step))
                  piece (copy-range vs start end)]
              (recur end (conj! out (chunk-item of piece))))))))))

(defn chunk-run
  "Split a homogeneous run (`of` + `values`) into items each within `budget`
   payload bytes (the run header included). One forward pass: a long string or byte vector is not
   re-copied from the cut to the end on every page.

   A u8 run's payload is a copied byte buffer, not a vector of integers."
  [of values budget]
  (let [of (str of)
        ;; the run's own header counts against the page
        budget (max 1 (- (long budget) (long (run-overhead-bytes of))))]
    (cond
      (= of "char") (chunk-chars (str values) budget)
      (= of "u8") (u8-page-items values budget)
      :else
      (let [vs (vec values)]
        (if (zero? (count vs))
          []
          (chunk-elems of vs budget))))))

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
