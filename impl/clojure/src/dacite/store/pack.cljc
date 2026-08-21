(ns dacite.store.pack
  "Layer 1 encodings (:node | :literal) + Layer 2 soft-budget chunking.

   See docs/design/leaf-chunking.md.
   2a: :node only.
   2b/2b′: realized recursive typed literals for value types.
   2c: large values — cheap size gate; refuse literal and walk :node + children.
   2c′: intermediate ft/* / hamt/* literals when leaf rebuild matches hash."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.wire :as wire]
            [dacite.value.types :as types]
            [dacite.value.scalar :as scalar]
            [dacite.value.collections :as coll]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]
            [dacite.rooted.gc :as gc]))

(def default-budget
  "Default soft pack budget in bytes (logical EDN length).

   Chosen by leaf-chunking 2d sweep (`dacite.bench.todo-bw --budget-sweep`):
   1024 is the smallest budget that collapses the interactive todo suite to
   minimal requests while still splitting clearly oversized values. See
   docs/design/leaf-chunking.md §2d."
  1024)

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
  "Approx wire size of a Layer-1 item (EDN string length)."
  [item]
  (count (wire/write-edn item)))

(defn chunk-size
  "Approx wire size of a chunk envelope."
  [chunk]
  (count (wire/write-edn chunk)))

(defn make-chunk
  "Build a chunk-v1 envelope map."
  [budget items]
  {:dacite.wire/chunk-v1 true
   :budget budget
   :items (vec items)})

(defn pack-items
  "Split Layer-1 items into chunks using a soft budget.

   Append until estimated chunk size ≥ budget, then seal (including the
   item that crossed the threshold). Flushes a final partial chunk."
  ([items] (pack-items items default-budget))
  ([items budget]
   (let [budget (long (or budget default-budget))]
     (if (empty? items)
       []
       (loop [remaining (seq items)
              cur []
              out []]
         (if-let [item (first remaining)]
           (let [cur' (conj cur item)
                 ch (make-chunk budget cur')
                 sz (chunk-size ch)]
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

(defn- join-chars
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

(defn- host-string
  "Full host string via index access (avoids ft-seq truncation)."
  [st h]
  (let [n (long (or (:count (types/entry-data (store/s-get st h))) 0))]
    (if (zero? n)
      ""
      (join-chars
       (map (fn [i]
              (let [r (types/realize (coll/seq-nth st h i))]
                (cond
                  (char? r) r
                  (string? r) r
                  :else (first (str r)))))
            (range n))))))

(defn- host-blob-vec
  "Blob as vector of 0..255 ints (EDN-safe)."
  [st h]
  (let [n (long (or (:count (types/entry-data (store/s-get st h))) 0))]
    (if (zero? n)
      []
      (mapv (fn [i]
              (let [r (types/realize (coll/seq-nth st h i))]
                (bit-and (int r) 0xFF)))
            (range n)))))

(defn- nested-literal
  "Recursive typed literal for child value at eh, or nil if not a value type."
  [st eh]
  (when-let [entry (store/s-get st eh)]
    (let [t (types/entry-type entry)]
      (when (value-type? t)
        (literal-of st eh)))))

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
                               :type (when-let [e (store/s-get st eh)]
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
      (let [leaves (vec (ft/ft-leaves st h))]
        {:type t :body (leaf-literals st leaves)})

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
        {:type t :body [kl vl]})

      (= "hamt/bitmap" t)
      (let [pairs (mapv (fn [[kr vr]]
                          [(or (nested-literal st kr)
                               (throw (ex-info "hamt key not a value" {:h h})))
                           (or (nested-literal st vr)
                               (throw (ex-info "hamt val not a value" {:h h})))])
                        (hamt/hamt-entries st h))]
        {:type t :body pairs})

      :else nil)))

(defn literal-of
  "Return {:type t :body b} for hash h, or nil if no realized form.

   Value types (scalars, string, blob, vector, map, set): complete realized
   content with recursive nested {:type :body} forms.

   Intermediate spine types (ft/*, hamt/*) (2c′): ordered leaf value literals;
   materialize + dry-run must match claimed hash to emit as :literal."
  [st h]
  (when-let [entry (store/s-get st h)]
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
                                                              (store/s-get st eh))})))))
                        (range n))]
          {:type "vector" :body els})

        (= "set" t)
        (let [els (if-let [xs (coll/set-vals st h)]
                    (mapv (fn [el]
                            (let [eh (types/dacite-hash el)]
                              (or (nested-literal st eh)
                                  (throw (ex-info "set child is not a value type"
                                                  {:parent h :child eh})))))
                          xs)
                    [])]
          {:type "set" :body els})

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
          {:type "map" :body pairs})

        :else nil))))

(defn- literal-form-size
  "Approx EDN size of a {:type :body} form."
  [form]
  (count (wire/write-edn form)))

(defn fits-literal?
  "True if h has a realized literal under budget (value or intermediate spine).

   Uses a cheap size cue first (2c): if :size-bytes already exceeds budget,
   returns false without building the recursive form or dry-running materialize."
  ([st h] (fits-literal? st h default-budget))
  ([st h budget]
   (let [budget (long budget)
         entry (store/s-get st h)
         t (when entry (types/entry-type entry))]
     (when (and entry
                (or (value-type? t) (tree-internal-type? t))
                (not (clearly-oversized? entry budget)))
       (when-let [form (literal-of st h)]
         (and (<= (literal-form-size form) (* 2 budget))
              (literal-round-trips? h (:type form) (:body form))))))))

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

      ;; deep / node: conj-right path (matches deep; node may fail dry-run)
      (or (= "ft/deep" type) (= "ft/node" type))
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
      (let [refs (mapv #(materialize-nested! st %) (or body []))]
        (types/dacite-hash (coll/vec-of-refs-with-store st refs)))

      (= "set" type)
      (let [refs (mapv #(materialize-nested! st %) (or body []))]
        (types/dacite-hash
         (apply coll/dacite-set-with-store st
                (map #(types/wrap-entry
                       (types/entry-type (store/s-get st %))
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
                                    (types/entry-type (store/s-get st kh)) st kh)
                                vw (types/wrap-entry
                                    (types/entry-type (store/s-get st vh)) st vh)]
                            [kw vw]))
                        pairs)]
        (types/dacite-hash (apply coll/hash-map-with-store st kvs)))

      (str/starts-with? type "ft/")
      (let [leaf-hs (mapv #(materialize-nested! st %) (or body []))]
        (materialize-ft! st type leaf-hs))

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

(defn encode-item
  "Layer 1: choose :literal or :node for store entry at h.

   Policy (2c / 2c′):
   1. Unknown non-value, non-spine type → :node
   2. Stored size cue > budget → :node (no full realize / dry-run)
   3. Else build realized literal (value or intermediate spine);
      if wire size > 2×budget or hash dry-run fails → :node
   4. Else :literal

   Intermediate ft/* / hamt/* may bottom out as leaf-literal payloads when
   reconstruction matches the claimed hash (2c′). Otherwise the walk continues
   into children."
  ([st h entry] (encode-item st h entry default-budget))
  ([st h entry budget]
   (let [budget (long (or budget default-budget))
         t (types/entry-type entry)]
     (cond
       (and (not (value-type? t))
            (not (tree-internal-type? t)))
       (node-item h entry)

       (clearly-oversized? entry budget)
       (node-item h entry)

       :else
       (if-let [{:keys [type body]}
                (try (literal-of st h)
                     (catch #?(:clj Throwable :cljs :default) _
                       nil))]
         (let [item (literal-item h type body)]
           (if (and (<= (item-size item) (* 2 budget))
                    (literal-round-trips? h type body))
             item
             (node-item h entry)))
         (node-item h entry))))))

(defn encode-reachable
  "Walk from root-h and Layer-1 encode each not-yet-skipped hash.

   Prefer :literal when it fits. When a hash is encoded as :literal, do not
   descend into its children — the receiver materializes them. Large parents
   become :node and the walk continues into children (2c mixed encoding).
   Returns {:items [...] :covered #{hashes}} where covered is every hash that
   will exist on the remote after apply (sent items plus full literal subgraphs)."
  ([st root-h] (encode-reachable st root-h #{} default-budget))
  ([st root-h skip] (encode-reachable st root-h skip default-budget))
  ([st root-h skip budget]
   (let [items (atom [])
         visited (atom #{})
         covered (atom #{})
         ;; skip/visited/covered use hex keys (CLJS-safe; BigInt hash vecs cannot be set elems)
         skip (into #{} (map gc/hash-key) (or skip #{}))
         budget (long (or budget default-budget))]
     (letfn [(walk [h]
               (let [hk (gc/hash-key h)]
                 (when (and h
                            (not (contains? @visited hk))
                            (not (contains? skip hk)))
                   (swap! visited conj hk)
                   (when-let [entry (store/s-get st h)]
                     (let [item (encode-item st h entry budget)]
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

(defn pack-under
  "Primary read packing: one chunk for hash h and a BFS neighborhood under it.

   Always includes an encoding of h when present. Then BFS-expands descendants
   (skipping `have`) with the same :literal/:node policy until soft budget
   seals the chunk (include the item that crossed the threshold, then stop).
   Does not start additional chunks — remainder is left for later node gets.

   Returns a chunk-v1 map, or nil if h is missing from st."
  ([st h] (pack-under st h #{} default-budget))
  ([st h have] (pack-under st h have default-budget))
  ([st h have budget]
   (when (and h (store/s-has? st h))
     (let [budget (long (or budget default-budget))
           ;; visited/have use hex keys (CLJS-safe)
           visited (atom (into #{} (map gc/hash-key) (or have #{})))
           items (atom [])]
       ;; Portable FIFO: list, take from front
       (loop [q (list h)]
         (if-let [cur (first q)]
           (let [q (next q)
                 ck (gc/hash-key cur)]
             (if (contains? @visited ck)
               (recur q)
               (if-let [entry (store/s-get st cur)]
                 (let [item (encode-item st cur entry budget)
                       trial (conj @items item)
                       sz (chunk-size (make-chunk budget trial))]
                   (reset! items trial)
                   (swap! visited conj ck)
                   (let [literal? (= :literal (:encoding item))]
                     (when literal?
                       (swap! visited into (gc/mark-reachable st cur)))
                     (cond
                       (>= sz budget)
                       nil
                       literal?
                       (recur q)
                       :else
                       (let [chs (remove (fn [ch]
                                           (or (nil? ch)
                                               (contains? @visited (gc/hash-key ch))))
                                         (or (types/child-hashes entry) []))]
                         (recur (concat q chs))))))
                 (recur q))))
           nil))
       (when (seq @items)
         (make-chunk budget @items))))))

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
        existed? (store/s-has? st hv)]
    (store/s-put st hv body)
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
            had? (store/s-has? st expected)]
        (case enc
          :node
          (do (store/s-put st expected (:body item))
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
