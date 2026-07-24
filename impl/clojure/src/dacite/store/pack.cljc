(ns dacite.store.pack
  "Layer 1 encodings (:node | :literal) + Layer 2 soft-budget chunking.

   See docs/design/leaf-chunking.md.
   2a: :node only. 2b: :literal for small rebuildable nodes (same root hash)."
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

(declare materialize-literal!)
(defn node-item
  "Layer-1 encoding: hash + store content."
  [h body]
  {:encoding :node
   :hash (store/hash->hex h)
   :body body})

(defn literal-item
  "Layer-1 encoding: hash + type + host/rebuild body."
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

(defn- tree-internal-type?
  "Finger-tree / HAMT internal node types — always ship as :node."
  [t]
  (or (str/starts-with? (str t) "ft/")
      (str/starts-with? (str t) "hamt/")))

(defn- entry-payload-size
  "Logical size cue for fits-literal? (prefer measure, else EDN length)."
  [entry]
  (or (try (types/dacite-size entry)
           (catch #?(:clj Throwable :cljs :default) _ nil))
      (count (wire/write-edn entry))))

(defn- host-edn?
  "True if x is a plain EDN-friendly host value (no Dacite wrappers)."
  [x]
  (cond
    (nil? x) true
    (boolean? x) true
    (string? x) true
    (number? x) true
    (keyword? x) true
    (symbol? x) true
    (char? x) true
    #?(:clj (bytes? x) :cljs false) true
    (vector? x) (every? host-edn? x)
    (set? x) (every? host-edn? x)
    (map? x) (and (every? host-edn? (keys x))
                  (every? host-edn? (vals x)))
    ;; realized Dacite strings are LazySeq of chars — not yet host-edn
    :else false))

(declare host-string host-blob-vec host-collection)

(defn- as-host
  "Coerce a Dacite or host value to plain EDN-friendly data.

   Prefer type-aware extraction for Dacite collections/strings so we do not
   depend on ft-seq/realize spines for medium values."
  [x]
  (cond
    (nil? x) nil
    (boolean? x) x
    (string? x) x
    (number? x) x
    (keyword? x) x
    (symbol? x) x
    (char? x) x
    #?(:clj (bytes? x) :cljs false) x
    (satisfies? types/IDaciteValue x)
    (let [t (types/dacite-type x)
          st (types/dacite-store x)
          h (types/dacite-hash x)]
      (cond
        (contains? scalar-types t) (types/realize x)
        (= "string" t) (host-string st h)
        (= "blob" t) (host-blob-vec st h)
        (#{"vector" "map" "set"} t) (host-collection st h t)
        ;; Domain / unknown types: best-effort realize (may not round-trip)
        :else (as-host (types/realize x))))
    ;; realized non-empty string (LazySeq/seq of chars)
    (and (sequential? x)
         (not (vector? x))
         (seq x)
         (every? #(or (char? %) (string? %)) x))
    (apply str x)
    (map-entry? x)
    [(as-host (key x)) (as-host (val x))]
    (vector? x) (mapv as-host x)
    (set? x) (into #{} (map as-host) x)
    (map? x) (into {} (map (fn [[k v]] [(as-host k) (as-host v)])) x)
    (sequential? x) (mapv as-host x)
    :else x))

(defn- host-string
  "Realize a Dacite string entry to a host string.

   Uses index access (not ft-seq/realize) so medium-length strings are not
   truncated by known lazy spine walks."
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
  "Realize a blob as a vector of 0..255 ints (EDN-safe)."
  [st h]
  (let [n (long (or (:count (types/entry-data (store/s-get st h))) 0))]
    (if (zero? n)
      []
      (mapv (fn [i]
              (let [r (types/realize (coll/seq-nth st h i))]
                (bit-and (int r) 0xFF)))
            (range n)))))

(defn- host-collection
  "Realize vector/set/map to plain host data."
  [st h t]
  (case t
    "vector"
    (let [n (long (or (:count (types/entry-data (store/s-get st h))) 0))]
      (mapv (fn [i] (as-host (coll/seq-nth st h i)))
            (range n)))

    "set"
    (if-let [xs (coll/set-vals st h)]
      (into #{} (map as-host) xs)
      #{})

    "map"
    (if-let [pairs (coll/map-entries st h)]
      (into {}
            (map (fn [[k v]]
                   [(as-host k) (as-host v)]))
            pairs)
      {})

    nil))

(defn literal-payload
  "If entry at h can be sent as a host literal under budget, return
   {:type t :body host}. Else nil.

   Scalars and small strings/vectors/maps/sets only; internal tree nodes
   always return nil.

   Note: 2b realized-value literals (host body + hash check). Full law:
   every value node has a complete realized literal; FT/HAMT spine is
   reconstructed on materialize — see docs/design/leaf-chunking.md."
  ([st h entry] (literal-payload st h entry default-budget))
  ([st h entry budget]
   (let [t (types/entry-type entry)
         data (types/entry-data entry)
         budget (long budget)]
     (when-not (tree-internal-type? t)
       (cond
         (contains? scalar-types t)
         (when (<= (entry-payload-size entry) budget)
           {:type t :body data})

         (= "string" t)
         (when (<= (or (:size-bytes data) (entry-payload-size entry)) budget)
           (let [body (host-string st h)]
             (when (host-edn? body)
               {:type "string" :body body})))

         (= "blob" t)
         (when (<= (or (:size-bytes data) 0) budget)
           (let [body (host-blob-vec st h)]
             (when (host-edn? body)
               {:type "blob" :body body})))

         (#{"vector" "map" "set"} t)
         (when (<= (or (:size-bytes data) (entry-payload-size entry)) budget)
           (let [body (host-collection st h t)]
             (when (and (some? body) (host-edn? body))
               {:type t :body body})))

         :else nil)))))

(defn- literal-round-trips?
  "True if materializing type/body in a fresh store yields exactly h.

   Guards against host forms that look small (nested colls, domain types
   that realize to plain data) but do not rebuild to the claimed hash."
  [h type body]
  (try
    (= h (materialize-literal! (store/mem-store) type body))
    (catch #?(:clj Throwable :cljs :default) _
      false)))

(defn encode-item
  "Layer 1: choose :literal or :node for store entry at h.

   Emits :literal only when the host body rebuilds to the same content hash
   (and the wire item is not far over budget)."
  ([st h entry] (encode-item st h entry default-budget))
  ([st h entry budget]
   (if-let [{:keys [type body]} (literal-payload st h entry budget)]
     (let [item (literal-item h type body)]
       (if (and (<= (item-size item) (* 2 budget))
                (literal-round-trips? h type body))
         item
         (node-item h entry)))
     (node-item h entry))))
(defn encode-reachable
  "Walk from root-h and Layer-1 encode each not-yet-skipped hash.

   Prefer :literal when it fits. When a hash is encoded as :literal, do not
   descend into its children — the receiver materializes them. Returns
   {:items [...] :covered #{hashes}} where covered is every hash that will
   exist on the remote after apply (sent items plus full literal subgraphs)."
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

(defn materialize-literal!
  "Install a literal into st; return the content hash of the result."
  [st type body]
  (let [type (str type)]
    (cond
      (contains? scalar-types type)
      (scalar/put-scalar! st type body)

      (= "string" type)
      (types/coerce-and-store! st (str body))

      (= "blob" type)
      (types/coerce-and-store! st (blob-bytes body))

      (= "vector" type)
      (types/coerce-and-store! st (vec body))

      (= "set" type)
      (types/coerce-and-store! st (set body))

      (= "map" type)
      (types/coerce-and-store! st (into {} body))

      :else
      (throw (ex-info "cannot materialize literal type"
                      {:type type})))))

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
