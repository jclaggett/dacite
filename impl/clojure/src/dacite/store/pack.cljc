(ns dacite.store.pack
  "Layer 1 encodings (:node | :literal) + Layer 2 soft-budget chunking.

   See docs/design/leaf-chunking.md.
   2a: :node only.
   2b/2b′: realized recursive typed literals for value types.
   2c: large values — cheap size gate; refuse literal and walk :node + children.
   2c′: intermediate ft/* / hamt/* literals when leaf rebuild matches hash.
   2e: seal on sent (wire-v1) bytes; literal iff item ≤ budget.
   2f: sequence bodies collapse contiguous same-type leaves to nested run/repeat."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.wire :as wire]
            [dacite.value.types :as types]
            [dacite.value.scalar :as scalar]
            [dacite.value.collections :as coll]
            [dacite.value.finger-tree :as ft]
            [dacite.value.lit :as lit]
            [dacite.value.hamt :as hamt]
            [dacite.rooted.gc :as gc]
            [dacite.store.bound :as bound]))

(def default-budget
  "Default soft pack budget in **sent** bytes (wire-v1 for the default GET/POST).

   4 KiB, the size of a leaf page (`lit/page-budget`), and kept at least
   that size by a test: with a smaller budget a full page is never a
   literal, so the first GET of a long string or vector carries only its
   header and spine and the pages follow, one round trip each. Two knobs,
   not one: this is a soft chunk target, the page budget is a hard cap on
   a page. (It was 1024, chosen by the leaf-chunking 2d sweep when pages
   were 1 KiB; the sweep's todo suite gives the same result at every budget
   from 256 to 4096 now. See docs/design/leaf-chunking.md.)

   Include-then-seal: the item that crosses the budget is kept, so a chunk
   is at most the budget plus one item: a node item (about 4.2 KB for a
   full page) or a literal (up to `default-max-literal` with the cap, else
   about the budget)."
  4096)

(def default-max-literal
  "Largest literal **item** (37-byte item header included) in wire-v1 bytes:
   one peer frame payload, 16 384 - 6 = 16 378 (`dacite.peer.frame/max-payload`;
   a test keeps the two equal). A literal never spans frames. Separate from
   `default-budget` (the soft Layer-2 chunk target) and from the leaf page
   budget (`lit/page-budget`).

   `encode-item` and `encode-reachable` take it as `:max-literal`. Absent,
   the old rule holds: a literal is any value whose size cue fits `budget`."
  16378)

(def ^:dynamic *verify-literal-hash*
  "When true, apply-chunk! requires materialize hash to match claimed hash."
  true)

(def ^:private scalar-types
  #{"null" "bool" "char"
    "i8" "i16" "i32" "i64"
    "u8" "u16" "u32" "u64" "u256"
    "f32" "f64" "negative"})

(def ^:private value-types
  "First-class value types that support realized literals (L1)."
  (into scalar-types #{"string" "blob" "vector" "map" "set"}))

(declare materialize-literal! literal-of literal-round-trips?)

;; =============================================================================
;; Chunk envelope (Layer 2 helpers)
;; =============================================================================

(defn node-item
  "Layer-1 encoding: hash + store content."
  [h body]
  {:encoding :node
   :hash (store/hash->hex h)
   :body body})

(defn literal-item
  "Layer-1 encoding: hash + type + realized recursive body."
  [h type body]
  {:encoding :literal
   :hash (store/hash->hex h)
   :type type
   :body body})

(defn item-size
  "Approx EDN size of a Layer-1 item (debug / benches)."
  [item]
  (count (wire/write-edn item)))

(defn chunk-size
  "Approx EDN size of a chunk envelope (EDN GET / benches)."
  [chunk]
  (count (wire/write-edn chunk)))

(defonce ^:private sent-item-size-fn (atom nil))
(defonce ^:private sent-chunk-size-fn (atom nil))

(defn set-wire-size-fns!
  "Install functions that measure Layer-1 items and chunks in sent
   (wire-v1) bytes. Called from `dacite.wire.binary` when that ns loads
   so this ns does not require the codec (circular with apply-chunk!)."
  [item-fn chunk-fn]
  (reset! sent-item-size-fn item-fn)
  (reset! sent-chunk-size-fn chunk-fn)
  nil)

(defn wire-item-size
  "Bytes of a Layer-1 item on the default wire-v1 transport.
   Falls back to EDN length if the binary codec is not loaded."
  [item]
  (if-let [f @sent-item-size-fn]
    (long (f item))
    (item-size item)))

(defn wire-chunk-size
  "Bytes of a chunk on the default wire-v1 transport.
   Falls back to EDN length if the binary codec is not loaded."
  [chunk]
  (if-let [f @sent-chunk-size-fn]
    (long (f chunk))
    (chunk-size chunk)))

(defn make-chunk
  "Build a chunk-v1 envelope map."
  [budget items]
  {:dacite.wire/chunk-v1 true
   :budget budget
   :items (vec items)})

(defn pack-items
  "Split Layer-1 items into chunks using a soft budget.

   Append until sent size ≥ budget, then seal (including the item that
   crossed the threshold). Default size is wire-v1 bytes. Pass `size-fn`
   for EDN length (legacy Accept). Flushes a final partial chunk."
  ([items] (pack-items items default-budget nil))
  ([items budget] (pack-items items budget nil))
  ([items budget size-fn]
   (let [budget (long (or budget default-budget))
         size-fn (or size-fn wire-chunk-size)]
     (if (empty? items)
       []
       (loop [remaining (seq items)
              cur []
              out []]
         (if-let [item (first remaining)]
           (let [cur' (conj cur item)
                 ch (make-chunk budget cur')
                 sz (long (size-fn ch))]
             (if (>= sz budget)
               (recur (next remaining) [] (conj out ch))
               (recur (next remaining) cur' out)))
           (if (seq cur)
             (conj out (make-chunk budget cur))
             out)))))))

(defn chunk?
  "True if m looks like a chunk-v1 envelope."
  [m]
  (and (map? m)
       (or (true? (:dacite.wire/chunk-v1 m))
           (vector? (:items m)))))

;; =============================================================================
;; Realized extraction helpers
;; =============================================================================

(defn- tree-internal-type?
  "Finger-tree / HAMT internal node types (2c′ intermediate literals)."
  [t]
  (or (str/starts-with? (str t) "ft/")
      (str/starts-with? (str t) "hamt/")))

(defn- value-type?
  [t]
  (contains? value-types (str t)))

(defn- entry-payload-size
  "Logical size cue (prefer measure, else EDN length of raw entry)."
  [entry]
  (or (try (types/dacite-size entry)
           (catch #?(:clj Throwable :cljs :default) _ nil))
      (count (wire/write-edn entry))))

(defn size-cue
  "Cheap logical size from stored measures (:size-bytes) or entry fallback.
   Used to refuse literals before building a full realized form (2c).
   FT/HAMT nodes keep size-bytes under :measure."
  [entry]
  (let [data (types/entry-data entry)]
    (long (or (when (map? data)
                (or (:size-bytes data)
                    (get-in data [:measure :size-bytes])))
              (entry-payload-size entry)
              0))))

(defn clearly-oversized?
  "True when the stored size cue alone exceeds budget — do not build a literal."
  [entry budget]
  (> (size-cue entry) (long budget)))

(def ^:private join-chars lit/join-chars)

(defn- host-string
  "Full host string. Walks the leaves once (`coll/seq-vals`); indexing each
   character with `seq-nth` costs a leaf page of work per character."
  [st h]
  (let [n (long (or (:count (types/entry-data (store/require-node st h))) 0))]
    (if (zero? n)
      ""
      (join-chars
       (map (fn [leaf]
              (let [r (types/realize leaf)]
                (cond
                  (char? r) r
                  (string? r) r
                  :else (first (str r)))))
            (coll/seq-vals st h))))))

(defn- host-blob-vec
  "Blob as vector of 0..255 ints (EDN-safe). Reads the page buffers in one
   pass (`blob-host-bytes`), else walks the leaves once; never indexes each
   byte (a page of work a byte)."
  [st h]
  (let [n (long (or (:count (types/entry-data (store/require-node st h))) 0))]
    (if (zero? n)
      []
      (if-let [bs (coll/blob-host-bytes st h)]
        #?(:clj (let [^bytes bs bs]
                  (persistent!
                   (areduce bs i out (transient [])
                            (conj! out (bit-and (int (aget bs i)) 0xFF)))))
           :cljs (mapv #(bit-and (int %) 0xFF) (array-seq bs)))
        (mapv (fn [leaf] (bit-and (int (types/realize leaf)) 0xFF))
              (coll/seq-vals st h))))))

;; =============================================================================
;; Type-run collapse (sequence bodies) — nested Lit {:type "run"| "repeat"}
;; =============================================================================

(def rle-lits
  "Collapse contiguous same-type nested lits into run/repeat.
   Re-export of dacite.value.lit/rle-lits (page bodies use the same algebra)."
  lit/rle-lits)

(def expand-rle-seq
  "Expand run/repeat elements in an ordered lit list.
   Re-export of dacite.value.lit/expand-rle-seq."
  lit/expand-rle-seq)

(def ^:private rle-form lit/rle-form)

(def ^:dynamic *form-cache*
  "An atom, or nil. While `encode-item` runs with a `:cache`, the recursive
   forms of nested values are kept in it by hash: a string held under many
   keys of a map is turned into its form once, not once per key.

   A form is only built under a literal whose bound (`store.bound`) fits the
   cap, so every form kept is of a value that fits. The cache belongs to one
   `encode-value` / `encode-reachable` call and goes with it. Bound with
   `binding` and used only in eagerly realized code in `literal-of`; a lazy
   seq realized after the call would just miss it."
  nil)

(defn- nested-literal
  "Recursive typed literal for child value at eh, or nil if not a value type."
  [st eh]
  (when-let [entry (store/await (store/s-get st eh))]
    (let [t (types/entry-type entry)]
      (when (value-type? t)
        (if-let [c *form-cache*]
          (let [k [:form (gc/hash-key eh)]
                hit (get @c k ::miss)]
            (if (= hit ::miss)
              (let [f (literal-of st eh)]
                (swap! c assoc k f)
                f)
              hit))
          (literal-of st eh))))))

;; =============================================================================
;; literal-of (2b′) — complete realized typed form
;; =============================================================================

(defn- leaf-literals
  "Map ordered leaf value hashes to recursive value literals."
  [st leaf-hs]
  (mapv (fn [eh]
          (or (nested-literal st eh)
              (throw (ex-info "leaf is not a value type"
                              {:leaf eh
                               :type (when-let [e (store/await (store/s-get st eh))]
                                       (types/entry-type e))}))))
        leaf-hs))

(defn- intermediate-literal-of
  "Realized-leaf literal for an ft/* or hamt/* node, or nil if unsupported.

   Body is the complete ordered leaf content under the node (value literals).
   Materialize rebuilds a spine with the same type+elements_fuse when possible;
   encode dry-run rejects cases that do not round-trip (e.g. some hamt/bitmap)."
  [st h entry]
  (let [t (types/entry-type entry)]
    (cond
      (= "ft/empty" t)
      {:type t :body []}

      (str/starts-with? (str t) "ft/")
      (let [body (:body (types/entry-data entry))]
        (if (lit/self-contained-body? body)
          {:type t :body body}
          (let [leaves (vec (ft/ft-leaves st h))]
            {:type t :body (rle-lits (leaf-literals st leaves))})))

      (= "hamt/empty" t)
      {:type t :body []}

      (= "hamt/entry" t)
      (let [data (types/entry-data entry)
            kr (:key-ref data)
            vr (:val-ref data)
            kl (or (nested-literal st kr)
                   (throw (ex-info "hamt entry key not a value" {:h h})))
            vl (or (nested-literal st vr)
                   (throw (ex-info "hamt entry val not a value" {:h h})))]
        {:type t :body [(rle-form kl) (rle-form vl)]})

      (= "hamt/bitmap" t)
      (let [pairs (mapv (fn [[kr vr]]
                          [(or (nested-literal st kr)
                               (throw (ex-info "hamt key not a value" {:h h})))
                           (or (nested-literal st vr)
                               (throw (ex-info "hamt val not a value" {:h h})))])
                        (hamt/hamt-entries st h))]
        {:type t :body (mapv (fn [pair]
                               [(rle-form (nth pair 0))
                                (rle-form (nth pair 1))])
                             pairs)})

      :else nil)))

(defn literal-of
  "Return {:type t :body b} for hash h, or nil if no realized form.

   Value types (scalars, string, blob, vector, map, set): complete realized
   content with recursive nested {:type :body} forms.

   Intermediate spine types (ft/*, hamt/*) (2c′): ordered leaf value literals;
   materialize + dry-run must match claimed hash to emit as :literal."
  [st h]
  (when-let [entry (store/await (store/s-get st h))]
    (let [t (types/entry-type entry)
          data (types/entry-data entry)]
      (cond
        (tree-internal-type? t)
        (try
          (intermediate-literal-of st h entry)
          (catch #?(:clj Throwable :cljs :default) _
            nil))

        (contains? scalar-types t)
        {:type t :body data}

        (= "string" t)
        {:type "string" :body (host-string st h)}

        (= "blob" t)
        {:type "blob" :body (host-blob-vec st h)}

        (= "vector" t)
        (let [n (long (or (:count data) 0))
              els (mapv (fn [i]
                          (let [el (coll/seq-nth st h i)
                                eh (types/dacite-hash el)]
                            (or (nested-literal st eh)
                                (throw (ex-info "vector child is not a value type"
                                                {:parent h :child eh
                                                 :child-type (types/entry-type
                                                              (store/require-node st eh))})))))
                        (range n))]
          {:type "vector" :body (rle-lits els)})

        (= "set" t)
        (let [els (if-let [xs (coll/set-vals st h)]
                    (mapv (fn [el]
                            (let [eh (types/dacite-hash el)]
                              (or (nested-literal st eh)
                                  (throw (ex-info "set child is not a value type"
                                                  {:parent h :child eh})))))
                          xs)
                    [])]
          {:type "set" :body (rle-lits els)})

        (= "map" t)
        (let [pairs (if-let [ps (coll/map-entries st h)]
                      (mapv (fn [[k v]]
                              (let [kh (types/dacite-hash k)
                                    vh (types/dacite-hash v)
                                    kl (or (nested-literal st kh)
                                           (throw (ex-info "map key is not a value type"
                                                           {:parent h :key kh})))
                                    vl (or (nested-literal st vh)
                                           (throw (ex-info "map val is not a value type"
                                                           {:parent h :val vh})))]
                                [kl vl]))
                            ps)
                      [])]
          {:type "map" :body (mapv (fn [pair]
                                     [(rle-form (nth pair 0))
                                      (rle-form (nth pair 1))])
                                   pairs)})

        :else nil))))

(defn fits-literal?
  "True if h has a realized literal under budget (value or intermediate spine).

   Uses the cached size cue (2c / 2f): if :size-bytes already exceeds budget,
   returns false without building the recursive form or dry-running materialize.
   Layer 1 does not measure wire-item size; Layer 2 include-then-seal still
   bounds the sent chunk."
  ([st h] (fits-literal? st h default-budget))
  ([st h budget]
   (let [budget (long budget)
         entry (store/await (store/s-get st h))
         t (when entry (types/entry-type entry))]
     (when (and entry
                (or (value-type? t) (tree-internal-type? t))
                (not (clearly-oversized? entry budget)))
       (when-let [form (literal-of st h)]
         (literal-round-trips? h (:type form) (:body form)))))))

;; =============================================================================
;; materialize-literal! (2b′)
;; =============================================================================

(defn- blob-bytes
  "Coerce a literal blob body (bytes or seq of 0..255) to host bytes."
  [body]
  #?(:clj
     (cond
       (bytes? body) body
       (sequential? body) (byte-array (map #(unchecked-byte (bit-and (int %) 0xFF)) body))
       :else body)
     :cljs
     (cond
       (sequential? body) (clj->js (mapv #(bit-and (int %) 0xFF) body))
       :else body)))

(defn- typed-nested?
  "True if x is a recursive {:type :body} literal form."
  [x]
  (and (map? x)
       (contains? x :type)
       (contains? x :body)
       (not (contains? x :encoding))))

(defn- materialize-nested!
  "Materialize a nested form: typed {:type :body}, or 2b flat host value."
  [st x]
  (cond
    (typed-nested? x)
    (materialize-literal! st (:type x) (:body x))

    ;; 2b flat host compatibility
    :else
    (types/extract-hash st x)))

(defn- materialize-ft!
  "Rebuild an FT node from ordered leaf value hashes."
  [st type leaf-hs]
  (let [type (str type)
        leaf-hs (vec leaf-hs)]
    (cond
      (= "ft/empty" type) (ft/ft-empty st)

      ;; 1-elem tree root is a bare leaf hash (no ft/single type)
      (= "ft/digit" type)
      (ft/ft-digit-from-value-hashes st leaf-hs)

      ;; A node page is one cell, including a 1k run of leaves. conj-right
      ;; would build a different spine and re-measure every prefix.
      (= "ft/node" type)
      (ft/ft-node-from-value-hashes st leaf-hs)

      (= "ft/deep" type)
      (ft/ft-from-value-hashes st leaf-hs)

      :else
      (throw (ex-info "unsupported ft literal type" {:type type})))))

(defn- materialize-hamt!
  "Rebuild a HAMT node from entry pair literals or empty."
  [st type body]
  (let [type (str type)]
    (cond
      (= "hamt/empty" type) (hamt/hamt-empty st)

      (= "hamt/entry" type)
      (let [k (nth body 0)
            v (nth body 1)
            kh (materialize-nested! st k)
            vh (materialize-nested! st v)]
        (hamt/hamt-entry-node st kh kh vh))

      (= "hamt/bitmap" type)
      (let [entries (mapv (fn [pair]
                            (let [k (nth pair 0)
                                  v (nth pair 1)
                                  kh (materialize-nested! st k)
                                  vh (materialize-nested! st v)]
                              [kh kh vh]))
                          body)]
        (hamt/hamt-from-entries st entries))

      :else
      (throw (ex-info "unsupported hamt literal type" {:type type})))))

(defn materialize-literal!
  "Install a realized literal into st; return the content hash.

   Body is complete realized content. Nested collection elements are either
   recursive {:type :body} maps (2b′) or flat host values (2b wire compat).
   Intermediate ft/* / hamt/* bodies are ordered leaf (or entry) literals (2c′)."
  [st type body]
  (let [type (str type)]
    (cond
      (contains? scalar-types type)
      (scalar/put-scalar! st type body)

      (= "string" type)
      (types/dacite-hash (coll/string-with-store st (str body)))

      (= "blob" type)
      (types/dacite-hash (coll/blob-with-store st (blob-bytes body)))

      (= "vector" type)
      (let [refs (mapv #(materialize-nested! st %)
                       (expand-rle-seq (or body [])))]
        (types/dacite-hash (coll/vec-of-refs-with-store st refs)))

      (= "set" type)
      (let [refs (mapv #(materialize-nested! st %)
                       (expand-rle-seq (or body [])))]
        (types/dacite-hash
         (apply coll/dacite-set-with-store st
                (map #(types/wrap-entry
                       (types/entry-type (store/require-node st %))
                       st %)
                     refs))))

      (= "map" type)
      (let [pairs (cond
                    (map? body) (seq body)
                    (sequential? body) body
                    :else [])
            kvs (mapcat (fn [pair]
                          (let [k (if (vector? pair) (nth pair 0) (key pair))
                                v (if (vector? pair) (nth pair 1) (val pair))
                                kh (materialize-nested! st k)
                                vh (materialize-nested! st v)
                                kw (types/wrap-entry
                                    (types/entry-type (store/require-node st kh)) st kh)
                                vw (types/wrap-entry
                                    (types/entry-type (store/require-node st vh)) st vh)]
                            [kw vw]))
                        pairs)]
        (types/dacite-hash (apply coll/hash-map-with-store st kvs)))

      (str/starts-with? type "ft/")
      (if (and (#{"ft/digit" "ft/node"} type)
               (lit/self-contained-body? body))
        (if (= type "ft/digit")
          (ft/ft-digit-from-body! st body)
          (ft/ft-node-from-body! st body))
        (let [leaf-hs (mapv #(materialize-nested! st %)
                            (expand-rle-seq (or body [])))]
          (materialize-ft! st type leaf-hs)))

      (str/starts-with? type "hamt/")
      (materialize-hamt! st type (or body []))

      :else
      (throw (ex-info "cannot materialize literal type"
                      {:type type})))))

(defn- literal-round-trips?
  "True if materializing form yields exactly h."
  [h type body]
  (try
    (= h (materialize-literal! (store/mem-store) type body))
    (catch #?(:clj Throwable :cljs :default) _
      false)))

;; =============================================================================
;; Layer 1 encode
;; =============================================================================

(defn- encode-opts
  "`opts` of `encode-item` and `encode-reachable`: a number is the budget."
  [x]
  (cond
    (map? x) (update x :budget #(long (or % default-budget)))
    :else {:budget (long (or x default-budget))}))

(defn node-bytes-under
  "Wire bytes of the node items that sending `h` as nodes needs: every entry
   reachable from `h` whose hex key is not in `covered`. Stops past `limit`
   (only 'more than the literal' matters)."
  [st h covered limit]
  (loop [queue [h] seen (or covered #{}) acc 0]
    (if (or (empty? queue) (> acc limit))
      acc
      (let [x (peek queue)
            queue (pop queue)
            xk (gc/hash-key x)]
        (if (or (nil? x) (contains? seen xk))
          (recur queue seen acc)
          (if-let [entry (store/await (store/s-get st x))]
            (recur (into queue (reverse (or (types/child-hashes entry) [])))
                   (conj seen xk)
                   (+ acc (long (wire-item-size (node-item x entry)))))
            (recur queue (conj seen xk) acc)))))))

(defn- literal-cache-key
  "A cached literal is the answer for one hash under one budget and cap."
  [hk {:keys [budget max-literal]}]
  [hk budget max-literal])

(def ^:dynamic *on-bound-miss*
  "nil, or a function called with `{:hash :bound :size :why}` when a literal
   whose bound fit the cap turned out over it (`:why :over-cap`). That must
   not happen (the bound is an upper bound); tests bind this and assert it
   stays unused. A literal that does not round-trip (an inner HAMT node, whose
   hash is not that of the map its entries make) is not a size miss: it is the
   old fallback to the node item and is not reported."
  nil)

(defn literal-bound
  "Upper bound in bytes of the literal item for `h` (`store.bound`), or nil.
   `cache` is the atom of one call."
  [st h cache]
  (bound/literal-item-bound st h cache))

(defn- measured-size
  "The stored `:size-bytes` of the entry (its own, or its measure's), or nil.
   Unlike `size-cue` it never falls back to counting the entry's EDN."
  [entry]
  (let [data (types/entry-data entry)]
    (when (map? data)
      (or (:size-bytes data) (get-in data [:measure :size-bytes])))))

(defn- over-cap-by-measure?
  "True when the stored size of the node is already over `max-literal`.
   `:size-bytes` counts each element once with no compression, as the bound
   does, and not more than the bound counts, so no literal that the bound
   would let through is skipped here: it only saves walking the node."
  [entry max-literal]
  (when-let [sb (measured-size entry)]
    (> (long sb) (long max-literal))))

(defn- build-literal
  "The literal item for `h`, or nil when it has none, cannot fit, or does not
   round-trip.

   With a cap the literal is not built to find out whether it fits: its size
   is bounded from the node first (`store.bound`, a sum with no run or repeat
   compression) and a literal whose bound is over the cap is skipped (and
   when the node's stored `:size-bytes` is over the cap already, the bound is
   not even walked). When
   the bound fits, the literal fits (a miss is reported to `*on-bound-miss*`);
   it may still be refused for not round-trip (an inner HAMT node).
   Without a cap the size cue and the budget decide, as before."
  [st h entry {:keys [budget max-literal bound-cache]}]
  (let [t (types/entry-type entry)]
    (when (and (or (value-type? t) (tree-internal-type? t))
               (if max-literal
                 (and (not (over-cap-by-measure? entry max-literal))
                      (when-let [b (literal-bound st h bound-cache)]
                        (<= (long b) (long max-literal))))
                 (not (clearly-oversized? entry budget))))
      (let [miss! (fn [why size]
                    (when (and max-literal *on-bound-miss*)
                      (*on-bound-miss* {:hash h :size size :why why}))
                    nil)
            form (try (literal-of st h)
                      (catch #?(:clj Throwable :cljs :default) _ nil))]
        (if-not form
          nil
          (let [{:keys [type body]} form
                item (literal-item h type body)]
            (cond
              (and max-literal (> (long (wire-item-size item)) (long max-literal)))
              (miss! :over-cap (wire-item-size item))
              (not (literal-round-trips? h type body))
              nil
              :else item)))))))

(defn encode-item
  "Layer 1: choose :literal or :node for store entry at h.

   Policy (2c / 2c′ / 2e / 2f):
   1. Unknown non-value, non-spine type → :node
   2. Stored size cue too big → :node (no full realize / dry-run)
   3. Else build realized literal (value or intermediate spine);
      if it is over the cap or its hash dry-run fails → :node
   4. Else :literal.

   `opts` is a map, or a number (the `budget`, as before):

   - `:budget`       soft Layer-2 chunk target (default `default-budget`).
                     Without `:max-literal` it is also the gate: a literal
                     is any value whose size cue fits it.
   - `:max-literal`  the largest literal item in wire bytes (see
                     `default-max-literal`). With it, a literal is built
                     only when its bound (`store.bound`: the sum of its
                     elements with no run or repeat compression) is at or
                     under it, and then it fits; over it, the node item.
   - `:shorter-wins?` also require the literal to be shorter on the wire
                     than the node items it replaces (peer item streams).
   - `:covered`      hex keys already sent or held; they cost nothing in
                     that comparison.
   - `:cache`        an atom {[hex-key budget max-literal] literal-item-or-:none}:
                     a literal is built once per hash per budget and cap
                     (`encode-value` of a value that holds one string under
                     many keys). It also keeps the bound of each node met
                     (`store.bound`) and the forms of nested values (see
                     `*form-cache*`), so make one per call and drop it with
                     the call.

   Intermediate ft/* / hamt/* may bottom out as leaf-literal payloads when
   reconstruction matches the claimed hash (2c′). Sequence bodies use
   run/repeat. Otherwise the walk continues into children."
  ([st h entry] (encode-item st h entry default-budget))
  ([st h entry opts]
   (let [{:keys [shorter-wins? covered cache] :as opts} (encode-opts opts)
         hk (literal-cache-key (gc/hash-key h) opts)
         ;; the bounds of the nodes met live in the call's cache, or in one
         ;; for this call alone
         opts (assoc opts :bound-cache (or cache (atom {})))
         item (binding [*form-cache* (or cache *form-cache*)]
              (if cache
                (let [c (get @cache hk ::miss)]
                  (if (= c ::miss)
                    (let [it (build-literal st h entry opts)]
                      (swap! cache assoc hk (or it ::none))
                      it)
                    (when-not (= c ::none) c)))
                (build-literal st h entry opts)))]
     (if (and item
              (or (not shorter-wins?)
                  (let [lit-size (long (wire-item-size item))]
                    (< lit-size
                       (long (node-bytes-under st h covered lit-size))))))
       item
       (node-item h entry)))))

(defn encode-reachable
  "Walk from root-h and Layer-1 encode each not-yet-skipped hash.

   Prefer :literal when it fits. When a hash is encoded as :literal, do not
   descend into its children — the receiver materializes them. Large parents
   become :node and the walk continues into children (2c mixed encoding).
   Returns {:items [...] :covered #{hashes}} where covered is every hash that
   will exist on the remote after apply (sent items plus full literal subgraphs).

   The last argument is a budget number or the `opts` map of `encode-item`
   (`:max-literal`, `:shorter-wins?`; a literal cache is added per call)."
  ([st root-h] (encode-reachable st root-h #{} default-budget))
  ([st root-h skip] (encode-reachable st root-h skip default-budget))
  ([st root-h skip opts]
   (let [items (atom [])
         visited (atom #{})
         covered (atom #{})
         ;; skip/visited/covered use hex keys (CLJS-safe; BigInt hash vecs cannot be set elems)
         skip (into #{} (map gc/hash-key) (or skip #{}))
         opts (assoc (encode-opts opts) :cache (atom {}))]
     (letfn [(walk [h]
               (let [hk (gc/hash-key h)]
                 (when (and h
                            (not (contains? @visited hk))
                            (not (contains? skip hk)))
                   (swap! visited conj hk)
                   (when-let [entry (store/await (store/s-get st h))]
                     (let [item (encode-item st h entry
                                             (cond-> opts
                                               (:shorter-wins? opts)
                                               (assoc :covered (into skip @covered))))]
                       (swap! items conj item)
                       (if (= :literal (:encoding item))
                         (swap! covered into (gc/mark-reachable st h))
                         (do
                           (swap! covered conj hk)
                           (doseq [ch (or (types/child-hashes entry) [])]
                             (walk ch)))))))))]
       (when root-h
         (walk root-h))
       {:items @items
        :covered @covered}))))

(defn summarize-items
  "Count encodings and approximate total wire bytes for a Layer-1 item seq."
  [items]
  (let [lits (filter #(= :literal (:encoding %)) items)
        nodes (filter #(= :node (:encoding %)) items)]
    {:items (count items)
     :literals (count lits)
     :nodes (count nodes)
     :approx-bytes (reduce + 0 (map item-size items))}))

(defn encode-summary
  "encode-reachable + summarize-items + chunk count (for benches/tests)."
  ([st root-h] (encode-summary st root-h default-budget))
  ([st root-h budget]
   (let [budget (long (or budget default-budget))
         {:keys [items covered]} (encode-reachable st root-h #{} budget)
         sum (summarize-items items)]
     (assoc sum
            :chunks (count (pack-items items budget))
            :covered (count covered)
            :budget budget))))

(defn- pack-under*
  "Neighborhood fill rooted at `root`. See pack-under."
  [st root have budget size-fn]
  (when (and root (store/await (store/s-has? st root)))
    (let [budget (long (or budget default-budget))
          size-fn (or size-fn wire-chunk-size)
          visited (atom (into #{} (map gc/hash-key) (or have #{})))
          items (atom [])]
      (loop [q (list root)]
        (if-let [cur (first q)]
          (let [q (next q)
                ck (gc/hash-key cur)]
            (if (contains? @visited ck)
              (recur q)
              (if-let [entry (store/await (store/s-get st cur))]
                (let [item (encode-item st cur entry budget)
                      trial (conj @items item)
                      sz (long (size-fn (make-chunk budget trial)))
                      empty? (empty? @items)
                      two (* 2 budget)]
                  (if (and (not empty?) (>= sz two))
                    nil
                    (do
                      (reset! items trial)
                      (swap! visited conj ck)
                      (let [literal? (= :literal (:encoding item))]
                        (when literal?
                          (swap! visited into (gc/mark-reachable st cur)))
                        (cond
                          (and (>= sz budget) literal?)
                          nil
                          (>= sz two)
                          nil
                          literal?
                          (recur q)
                          :else
                          (let [chs (remove (fn [ch]
                                              (or (nil? ch)
                                                  (contains? @visited (gc/hash-key ch))))
                                            (or (types/child-hashes entry) []))]
                            (recur (concat chs q))))))))
                (recur q))))
          nil))
      (when (seq @items)
        (make-chunk budget @items)))))

(defn pack-under
  "Primary read packing: one chunk for hash h and a neighborhood under it.

   Always includes an encoding of h when present. Then expands descendants
   (skipping `have`) with the same :literal/:node policy until the **sent**
   size (wire-v1 by default; EDN if `:size-fn chunk-size`) is ≥ budget.
   Include-then-seal: the item that crossed is kept, so a chunk is at most the
   budget plus one item (a node item, about 4.2 KB for a full page; a literal,
   up to the cap). Remainder is left for later node gets.
   Budget 0 includes only an encoding of h (Layer 2 seals after the first item).

   Opts:
     :size-fn     chunk size in sent bytes

   Returns a chunk-v1 map, or nil if h is missing from st."
  ([st h] (pack-under st h #{} default-budget nil))
  ([st h have] (pack-under st h have default-budget nil))
  ([st h have budget] (pack-under st h have budget nil))
  ([st h have budget {:keys [size-fn]}]
   (when (and h (store/await (store/s-has? st h)))
     (pack-under* st h have budget size-fn))))

;; =============================================================================
;; Apply + transport
;; =============================================================================

(defn- novelty-status
  "Rollup: :complete if nothing new, :partial if any created."
  [created]
  (if (seq created) :partial :complete))

(defn put-node!
  "Install one raw store node at h. Returns novelty map for a single key.

   {:status :complete|:partial  ; complete = already present
    :created [hex…] :exists [hex…]
    :applied 1}"
  [st h body]
  (let [hex (if (string? h) h (store/hash->hex h))
        hv (if (string? h) (store/hex->hash h) h)
        existed? (store/await (store/s-has? st hv))]
    (store/await (store/s-put st hv body))
    (if existed?
      {:status :complete :created [] :exists [hex] :applied 1}
      {:status :partial :created [hex] :exists [] :applied 1})))

(defn apply-chunk!
  "Apply a chunk to an IStore.

   Returns novelty + counts:
     :applied   total items processed
     :nodes     :node encodings
     :literals  :literal encodings
     :created   hex hashes newly installed
     :exists    hex hashes already present (idempotent)
     :status    :complete (all exists) | :partial (any created)"
  [st chunk]
  (let [items (:items chunk)
        nodes (atom 0)
        lits (atom 0)
        created (atom [])
        exists (atom [])]
    (doseq [item items]
      (let [enc (keyword (:encoding item))
            hex (:hash item)
            expected (store/hex->hash hex)
            had? (store/await (store/s-has? st expected))]
        (case enc
          :node
          (do (store/await (store/s-put st expected (:body item)))
              (swap! nodes inc)
              (if had?
                (swap! exists conj hex)
                (swap! created conj hex)))

          :literal
          (do
            (if had?
              (swap! exists conj hex)
              (let [got (materialize-literal! st (:type item) (:body item))]
                (when (and *verify-literal-hash* (not= got expected))
                  (throw (ex-info "literal hash mismatch"
                                  {:expected hex
                                   :got (store/hash->hex got)
                                   :type (:type item)})))
                (swap! created conj hex)))
            (swap! lits inc))

          (throw (ex-info "unsupported chunk item encoding"
                          {:encoding enc :hash hex})))))
    (let [cr @created
          ex @exists]
      {:applied (+ @nodes @lits)
       :nodes @nodes
       :literals @lits
       :created cr
       :exists ex
       :status (novelty-status cr)})))

(defprotocol IChunkTransport
  "Sink for sealed chunks (HTTP POST /nodes or middleware that delegates).

   Middleware above transport must implement send-chunk! by calling the inner
   store — do not peel wrappers past a layer that implements this protocol."
  (send-chunk! [this chunk]
    "POST one chunk envelope. Throws on failure. Returns response map
     (may include :created :exists :status from the server)."))

(defn find-chunk-transport
  "Outermost store in a composition that implements IChunkTransport.

   Walks inward only when the current store does not implement the protocol
   (e.g. write-back → :remote). Never peels past a middleware that already
   implements send-chunk!."
  [s]
  (cond
    (nil? s) nil
    (satisfies? IChunkTransport s) s
    (and (record? s) (contains? s :remote)) (find-chunk-transport (:remote s))
    (and (record? s) (contains? s :inner)) (find-chunk-transport (:inner s))
    (and (record? s) (contains? s :layers))
    (some find-chunk-transport (reverse (:layers s)))
    :else nil))

(defn- as-chunk-transport
  "Resolve s to an IChunkTransport or throw."
  [s]
  (or (find-chunk-transport s)
      (throw (ex-info "no IChunkTransport in store composition"
                      {:store-type (type s)}))))

(defn put-items-chunked!
  "Layer 2: pack items and send-chunk! each via transport (or outermost
   IChunkTransport found by walking inward past non-transport wrappers).

   Returns {:chunks n :items n :created [hex…] :exists [hex…]
            :status :complete|:partial}."
  ([transport items]
   (put-items-chunked! transport items default-budget))
  ([transport items budget]
   (let [t (as-chunk-transport transport)
         chunks (pack-items items budget)
         created (atom [])
         exists (atom [])]
     (doseq [ch chunks]
       (let [data (send-chunk! t ch)]
         (when (map? data)
           (swap! created into (or (:created data) []))
           (swap! exists into (or (:exists data) [])))))
     (let [cr @created
           ex @exists]
       {:chunks (count chunks)
        :items (count items)
        :created cr
        :exists ex
        :status (novelty-status cr)}))))

(defn flush-from!
  "Pack composition flush (W2): encode reachable nodes from content-store
   under root-h (skipping skip), soft-budget pack, send-chunk! each package.

   transport — IChunkTransport or composition containing one (outermost wins)
   content-store — IStore holding nodes to encode (e.g. write-back local)
   root-h — value root to flush
   skip — set of already-flushed hashes (hex or hash vectors)
   budget — soft pack budget (default default-budget)

   Returns {:items n :chunks n :covered #{hex-keys…} :created :exists :status}
   with :items 0 when nothing to send. Does not interpret value completeness."
  ([transport content-store root-h skip]
   (flush-from! transport content-store root-h skip default-budget))
  ([transport content-store root-h skip budget]
   (let [budget (long (or budget default-budget))
         {:keys [items covered]} (encode-reachable content-store root-h
                                                   (or skip #{}) budget)]
     (if (empty? items)
       {:items 0
        :chunks 0
        :covered #{}
        :created []
        :exists []
        :status :complete}
       (let [nov (put-items-chunked! transport items budget)]
         (assoc nov :covered covered))))))

(defrecord DelegatingChunkTransport [inner]
  ;; Middleware helper: IChunkTransport that only forwards send-chunk!.
  ;; Use to wrap a remote with metering, logging, or (later) rate-limit.
  IChunkTransport
  (send-chunk! [_ chunk]
    (send-chunk! (as-chunk-transport inner) chunk)))

(defn wrap-chunk-transport
  "Wrap inner so send-chunk! always goes through this record (for tests /
   future throttle). inner may itself be a composition."
  [inner]
  (->DelegatingChunkTransport inner))

;; =============================================================================
;; Pack fetch (read side) — server builds chunks for client apply-chunk!
;; =============================================================================

(defn- ->hash
  "Coerce hex string or hash vector to hash vector."
  [x]
  (cond
    (nil? x) nil
    (string? x) (store/hex->hash x)
    (vector? x) x
    :else (throw (ex-info "expected hash hex or vector" {:value x}))))

(defn pack-get
  "Server-side pack for a fetch request.

   req keys (all optional except that at least one root/hash is needed):
     :roots   — seq of root hashes (hex or vector) to walk
     :hashes  — additional start hashes to walk
     :have    — hashes the client already has (skip set)
     :budget  — soft budget (default default-budget)

   Returns {:chunks [...] :items n :covered n :budget b}.
   Empty when nothing to send."
  [st req]
  (let [budget (long (or (:budget req) default-budget))
        have (into #{} (map gc/hash-key) (keep ->hash (or (:have req) [])))
        starts (into [] (keep ->hash)
                     (concat (or (:roots req) [])
                             (or (:hashes req) [])))
        {:keys [all-items all-covered]}
        (loop [qs starts
               skip have
               acc-items []
               acc-cov #{}]
          (if-let [h (first qs)]
            (let [{:keys [items covered]} (encode-reachable st h skip budget)]
              (recur (next qs)
                     (into skip covered)
                     (into acc-items items)
                     (into acc-cov covered)))
            {:all-items acc-items :all-covered acc-cov}))
        ;; Dedupe by hash hex keeping first encoding
        items (vec (vals (reduce (fn [m it]
                                   (let [hx (:hash it)]
                                     (if (contains? m hx) m (assoc m hx it))))
                                 {}
                                 all-items)))
        chunks (pack-items items budget)]
    {:chunks chunks
     :items (count items)
     :covered (count all-covered)
     :budget budget}))
