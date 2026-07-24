(ns dacite.store.pack
  "Layer 1 encodings (:node | :literal) + Layer 2 soft-budget chunking.

   See docs/design/leaf-chunking.md.
   2a: :node only.
   2b/2b′: realized recursive typed literals for value types.
   2c: large values — cheap size gate; refuse literal and walk :node + children."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.wire :as wire]
            [dacite.value.types :as types]
            [dacite.value.scalar :as scalar]
            [dacite.value.collections :as coll]
            [dacite.rooted.gc :as gc]))

(def default-budget
  "Default soft pack budget in bytes (logical EDN length)."
  1024)

(def ^:dynamic *verify-literal-hash*
  "When true, apply-chunk! requires materialize hash to match claimed hash."
  true)

(def ^:private scalar-types
  #{"null" "bool" "char"
    "i8" "i16" "i32" "i64"
    "u8" "u16" "u32" "u64"
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
  "Finger-tree / HAMT internal node types — not value literals (2c′ later)."
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
   Used to refuse literals before building a full realized form (2c)."
  [entry]
  (let [data (types/entry-data entry)]
    (long (or (when (map? data) (:size-bytes data))
              (entry-payload-size entry)
              0))))

(defn clearly-oversized?
  "True when the stored size cue alone exceeds budget — do not build a literal."
  [entry budget]
  (> (size-cue entry) (long budget)))

(defn- host-string
  "Full host string via index access (avoids ft-seq truncation)."
  [st h]
  (let [n (long (or (:count (types/entry-data (store/s-get st h))) 0))]
    (if (zero? n)
      ""
      (apply str
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

(defn literal-of
  "Return {:type t :body b} for the value at h, or nil if not a value type.

   Body is the complete realized content. Nested values are themselves
   {:type :body} maps (recursive). Spine types (ft/*, hamt/*) return nil."
  [st h]
  (when-let [entry (store/s-get st h)]
    (let [t (types/entry-type entry)
          data (types/entry-data entry)]
      (cond
        (tree-internal-type? t)
        nil

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
  "True if h is a value type whose realized literal is under budget.

   Uses a cheap size cue first (2c): if :size-bytes already exceeds budget,
   returns false without building the recursive form or dry-running materialize."
  ([st h] (fits-literal? st h default-budget))
  ([st h budget]
   (let [budget (long budget)
         entry (store/s-get st h)]
     (when (and entry
                (value-type? (types/entry-type entry))
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

(defn materialize-literal!
  "Install a realized literal into st; return the content hash.

   Body is complete realized content. Nested collection elements are either
   recursive {:type :body} maps (2b′) or flat host values (2b wire compat)."
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
        ;; Build set from hashes by wrapping empty set and assoc… use constructor
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

   Policy (2c):
   1. Spine / non-value → :node
   2. Stored size cue > budget → :node (no full realize / dry-run)
   3. Else build realized literal; if wire size > 2×budget or hash dry-run
      fails → :node
   4. Else :literal

   When the pack walk gets :node for a large parent, children are visited
   separately and may still become :literal (mixed encoding)."
  ([st h entry] (encode-item st h entry default-budget))
  ([st h entry budget]
   (let [budget (long (or budget default-budget))
         t (types/entry-type entry)]
     (cond
       (or (tree-internal-type? t)
           (not (value-type? t))
           (clearly-oversized? entry budget))
       (node-item h entry)

       :else
       (if-let [{:keys [type body]} (literal-of st h)]
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
         skip (or skip #{})
         budget (long (or budget default-budget))]
     (letfn [(walk [h]
               (when (and h
                          (not (contains? @visited h))
                          (not (contains? skip h)))
                 (swap! visited conj h)
                 (when-let [entry (store/s-get st h)]
                   (let [item (encode-item st h entry budget)]
                     (swap! items conj item)
                     (if (= :literal (:encoding item))
                       (swap! covered into (gc/mark-reachable st h))
                       (do
                         (swap! covered conj h)
                         (doseq [ch (or (types/child-hashes entry) [])]
                           (walk ch))))))))]
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

;; =============================================================================
;; Apply + transport
;; =============================================================================

(defn apply-chunk!
  "Apply a chunk to an IStore. Returns {:applied n :literals n :nodes n}."
  [st chunk]
  (let [items (:items chunk)
        nodes (atom 0)
        lits (atom 0)]
    (doseq [item items]
      (let [enc (keyword (:encoding item))
            hex (:hash item)
            expected (store/hex->hash hex)]
        (case enc
          :node
          (do (store/s-put st expected (:body item))
              (swap! nodes inc))

          :literal
          (let [got (materialize-literal! st (:type item) (:body item))]
            (when (and *verify-literal-hash* (not= got expected))
              (throw (ex-info "literal hash mismatch"
                              {:expected hex
                               :got (store/hash->hex got)
                               :type (:type item)})))
            (swap! lits inc))

          (throw (ex-info "unsupported chunk item encoding"
                          {:encoding enc :hash hex})))))
    {:applied (+ @nodes @lits)
     :nodes @nodes
     :literals @lits}))

(defprotocol IChunkTransport
  "HTTP (or other) sink for sealed chunks."
  (send-chunk! [this chunk]
    "POST one chunk envelope. Throws on failure. Returns nil or response map."))

(defn put-items-chunked!
  "Layer 2: pack items and send-chunk! each. Returns {:chunks n :items n}."
  ([transport items]
   (put-items-chunked! transport items default-budget))
  ([transport items budget]
   (let [chunks (pack-items items budget)]
     (doseq [ch chunks]
       (send-chunk! transport ch))
     {:chunks (count chunks)
      :items (count items)})))
