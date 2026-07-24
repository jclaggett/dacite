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

      (= "ft/single" type)
      (do (when-not (= 1 (count leaf-hs))
            (throw (ex-info "ft/single expects one leaf" {:n (count leaf-hs)})))
          (ft/ft-single-from-value-hash st (first leaf-hs)))

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
        have (into #{} (keep ->hash) (or (:have req) []))
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
