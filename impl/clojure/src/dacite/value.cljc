(ns dacite.value
  "Public value API for Dacite.

   Application code should need only this namespace for values (pair with
   `dacite.store` for store wiring):

     (require '[dacite.value :as v]
              '[dacite.store :as store])

   ## Construction

   - **Bootstrap** (explicit store): `(v/vector-with-store st 1 2 3)`
   - **Relative** (preferred in domain code): `(v/vector-via peer 1 2 3)`
     where `peer` is an existing Dacite value, a root-ref, or an IStore
   - **REPL**: bare `(v/vector 1 2 3)` uses `store/*store*`

   ## Root reference

   Wrap a rooted store once, then work with values:

     (def r (v/root-ref (store/rooted-store (store/mem-store))))
     (v/ref-reset! r (v/vector-via r))
     (v/ref-swap! r v/conj (v/i64-via r 1))
     ;; JVM also: @r, (swap! r f), (reset! r v), add-watch

   ## Collection ops

   First argument is always a Dacite value: `conj`, `get`, `nth`, `count`, …"
  (:refer-clojure :exclude [vector hash-map set count nth get assoc conj seq
                            peek pop keys vals contains? dissoc empty?
                            get-in assoc-in update update-in pr-str subvec])
  (:require [dacite.store :as store]
            [dacite.value.types :as types]
            [dacite.value.scalar :as scalar]
            [dacite.value.collections :as coll]
            [dacite.value.root-ref :as root-ref]
            #?@(:clj [[dacite.convert :as convert]
                      [dacite.value.render :as render]])))

;; =============================================================================
;; Accessors
;; =============================================================================

(def dacite-hash  types/dacite-hash)
(def dacite-store types/dacite-store)
(def dacite-type  types/dacite-type)
(def realize      types/realize)
(def extract-hash types/extract-hash)
(def store-of     types/store-of)
(def IStoreCarrier types/IStoreCarrier)
(def IDaciteValue  types/IDaciteValue)

(defn dacite-value?
  "True if x is a Dacite value."
  [x]
  (satisfies? types/IDaciteValue x))

(defn value-type
  "The type-name string of a Dacite value (\"vector\", \"map\", …)."
  [v]
  (types/dacite-type v))

(defn content-hash
  "Strip a value's type tag to recover its data hash (§3.3)."
  [v]
  (types/content-hash (dacite-type v) (dacite-hash v)))

;; =============================================================================
;; Wrapping & rehydrate
;; =============================================================================

(defn wrap-hash
  "Wrap a raw hash (already in a store) in the appropriate Dacite value."
  ([h]
   (wrap-hash store/*store* h))
  ([st h]
   (types/wrap-entry (types/entry-type (store/s-get st h)) st h)))

(defn get-value-with-store
  "Look up a hash in an explicit store and return the Dacite value, or nil."
  [st h]
  (when-let [entry (store/s-get st h)]
    (types/wrap-entry (types/entry-type entry) st h)))

(defn get-value
  "Look up a hash in a content store and return the Dacite value, or nil.
   One-arg form uses `store/*store*`."
  ([h] (get-value-with-store store/*store* h))
  ([st h] (get-value-with-store st h)))

;; =============================================================================
;; Scalar constructors
;; =============================================================================

;; The JVM ClojureScript compiler munges `dacite.value.scalar` the var onto
;; the same JS object as the namespace, wiping `put-scalar!`. nbb (SCI) and
;; the JVM keep both. Feature order: nbb before :cljs.
#?(:org.babashka/nbb (def scalar scalar/scalar)
   :clj (def scalar scalar/scalar)
   :cljs nil)
(def scalar-with-store   scalar/scalar-with-store)
(def scalar-via          scalar/scalar-via)
(def null                scalar/null)
(def null-with-store     scalar/null-with-store)
(def null-via            scalar/null-via)
(def bool                scalar/bool)
(def bool-with-store     scalar/bool-with-store)
(def bool-via            scalar/bool-via)
(def i8                  scalar/i8)
(def i8-with-store       scalar/i8-with-store)
(def i8-via              scalar/i8-via)
(def i16                 scalar/i16)
(def i16-with-store      scalar/i16-with-store)
(def i16-via             scalar/i16-via)
(def i32                 scalar/i32)
(def i32-with-store      scalar/i32-with-store)
(def i32-via             scalar/i32-via)
(def i64                 scalar/i64)
(def i64-with-store      scalar/i64-with-store)
(def i64-via             scalar/i64-via)
(def u8                  scalar/u8)
(def u8-with-store       scalar/u8-with-store)
(def u8-via              scalar/u8-via)
(def u16                 scalar/u16)
(def u16-with-store      scalar/u16-with-store)
(def u16-via             scalar/u16-via)
(def u32                 scalar/u32)
(def u32-with-store      scalar/u32-with-store)
(def u32-via             scalar/u32-via)
(def u64                 scalar/u64)
(def u64-with-store      scalar/u64-with-store)
(def u64-via             scalar/u64-via)
(def u256                scalar/u256)
(def u256-with-store     scalar/u256-with-store)
(def u256-via            scalar/u256-via)
(def f32                 scalar/f32)
(def f32-with-store      scalar/f32-with-store)
(def f32-via             scalar/f32-via)
(def f64                 scalar/f64)
(def f64-with-store      scalar/f64-with-store)
(def f64-via             scalar/f64-via)
(def dacite-char         scalar/dacite-char)
(def dacite-char-with-store scalar/dacite-char-with-store)
(def dacite-char-via     scalar/dacite-char-via)
(def negative            scalar/negative)
(def negative-with-store scalar/negative-with-store)
(def negative-via        scalar/negative-via)
(def negative-sentinel   scalar/negative-sentinel)

(def dac-int   scalar/i64)
(def dac-int-with-store scalar/i64-with-store)
(def dac-int-via scalar/i64-via)
(def dac-float scalar/f64)
(def dac-float-with-store scalar/f64-with-store)
(def dac-float-via scalar/f64-via)

;; =============================================================================
;; Collection constructors
;; =============================================================================

(def string            coll/string)
(def string-with-store coll/string-with-store)
(def string-via        coll/string-via)
(def blob              coll/blob)
(def blob-with-store   coll/blob-with-store)
(def blob-via          coll/blob-via)
(def vector            coll/vector)
(def vector-with-store coll/vector-with-store)
(def vector-via        coll/vector-via)
(def hash-map          coll/hash-map)
(def hash-map-with-store coll/hash-map-with-store)
(def hash-map-via      coll/hash-map-via)
(def set               coll/dacite-set)
(def set-with-store    coll/dacite-set-with-store)
(def set-via           coll/set-via)
(def dacite-set        coll/dacite-set)
(def dacite-set-with-store coll/dacite-set-with-store)

;; =============================================================================
;; Set algebra (§3.5)
;; =============================================================================

(def set-member?    coll/set-member?)
(def set-complement coll/set-complement)
(def set-union      coll/set-union)
(def set-intersect  coll/set-intersect)
(def set-difference coll/set-difference)

;; =============================================================================
;; Collection API (from former dacite.value.api)
;; =============================================================================

(defn count
  "Number of elements/entries in a Dacite collection, O(1)."
  [v]
  (coll/coll-count (types/dacite-store v) (types/dacite-hash v)))

(defn empty?
  "True if the Dacite collection has no elements."
  [v]
  (zero? (count v)))

(defn seq
  "Elements of a sequence (string/blob/vector) as wrapped values,
   [k v] pairs of a map (wrapped), or members of a set (wrapped).
   Returns nil when empty."
  [v]
  (case (value-type v)
    ("string" "blob" "vector") (coll/seq-vals (types/dacite-store v) (types/dacite-hash v))
    "map" (coll/map-entries (types/dacite-store v) (types/dacite-hash v))
    "set" (coll/set-vals (types/dacite-store v) (types/dacite-hash v))
    nil))

(defn nth
  "Wrapped element at index i of a sequence collection."
  ([v i]
   (coll/seq-nth (types/dacite-store v) (types/dacite-hash v) i))
  ([v i not-found]
   (if (and (integer? i) (<= 0 i) (< i (count v)))
     (coll/seq-nth (types/dacite-store v) (types/dacite-hash v) i)
     not-found)))

(defn get
  "Look up a key/index in a map/set/vector. Returns a wrapped value or
   not-found (nil by default)."
  ([v k] (get v k nil))
  ([v k not-found]
   (case (value-type v)
     "map"    (coll/map-get (types/dacite-store v) (types/dacite-hash v) k not-found)
     "set"    (coll/set-get (types/dacite-store v) (types/dacite-hash v) k not-found)
     "vector" (if (and (integer? k) (<= 0 k) (< k (count v)))
                (coll/seq-nth (types/dacite-store v) (types/dacite-hash v) k)
                not-found)
     not-found)))

(defn contains?
  "True if key/index k is present."
  [v k]
  (case (value-type v)
    "map"    (coll/map-contains? (types/dacite-store v) (types/dacite-hash v) k)
    "set"    (coll/set-contains? (types/dacite-store v) (types/dacite-hash v) k)
    "vector" (and (integer? k) (<= 0 k) (< k (count v)))
    false))

(defn assoc
  "Associate k->val in a vector (integer index) or map. Returns a new
   Dacite value."
  [v k val]
  (case (value-type v)
    "vector" (coll/vec-assoc (types/dacite-store v) (types/dacite-hash v) k val)
    "map"    (coll/map-assoc (types/dacite-store v) (types/dacite-hash v) k val)
    (throw (ex-info "assoc unsupported for type" {:type (value-type v)}))))

(defn dissoc
  "Remove key k from a map. Returns a new Dacite map."
  [v k]
  (case (value-type v)
    "map" (coll/map-dissoc (types/dacite-store v) (types/dacite-hash v) k)
    (throw (ex-info "dissoc unsupported for type" {:type (value-type v)}))))

(defn conj
  "Append to a vector, add to a set, or add a [k v] pair to a map."
  [v x]
  (case (value-type v)
    "vector" (coll/vec-conj (types/dacite-store v) (types/dacite-hash v) x)
    "set"    (coll/set-conj (types/dacite-store v) (types/dacite-hash v) x)
    "map"    (coll/map-assoc (types/dacite-store v) (types/dacite-hash v)
                             (clojure.core/nth x 0) (clojure.core/nth x 1))
    (throw (ex-info "conj unsupported for type" {:type (value-type v)}))))

(defn peek
  "Last element of a vector (wrapped), or nil if empty."
  [v]
  (coll/vec-peek (types/dacite-store v) (types/dacite-hash v)))

(defn pop
  "Drop the last element of a vector. Returns a new Dacite vector."
  [v]
  (coll/vec-pop (types/dacite-store v) (types/dacite-hash v)))

(defn remove-nth
  "Remove the element at index i from a sequence collection."
  [v i]
  (case (value-type v)
    ("vector" "string" "blob")
    (coll/seq-remove-nth (types/dacite-store v) (types/dacite-hash v) i)
    (throw (ex-info "remove-nth unsupported for type" {:type (value-type v)}))))

(defn subvec
  "Elements [start, end) of a vector as a new Dacite vector.

   One-arg end defaults to the count. Leaves are shared. Any page is
   O((end-start) log n) via nth — it does not seq the whole vector."
  ([v start] (subvec v start (count v)))
  ([v start end]
   (case (value-type v)
     "vector" (coll/vec-subvec (types/dacite-store v) (types/dacite-hash v)
                               start end)
     (throw (ex-info "subvec unsupported for type" {:type (value-type v)})))))

(defn keys
  "Wrapped keys of a map, or nil if empty."
  [v]
  (when-let [es (coll/map-entries (types/dacite-store v) (types/dacite-hash v))]
    (map first es)))

(defn vals
  "Wrapped values of a map, or nil if empty."
  [v]
  (when-let [es (coll/map-entries (types/dacite-store v) (types/dacite-hash v))]
    (map second es)))

;; =============================================================================
;; Field access and path updates (stay on the value — not dac->clj)
;; =============================================================================

(def ^:dynamic *string-char-limit*
  "When bound to a number, `native` / `as-str` refuse a Dacite string longer
   than this many characters (they realize at most that prefix, then throw).
   `pr-str` uses the same bound to truncate. nil (default) means no limit
   for `native` / `as-str`; `pr-str` then falls back to 64 characters."
  nil)

(def ^:private default-pr-str-char-limit 64)

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

(defn- realize-string
  "Realize at most `limit` characters of a Dacite string.
   nil limit = the whole string. Returns [host-string truncated? total-count].
   Uses lazy `realize` so only the consumed prefix is fetched."
  [v limit]
  (let [n (count v)]
    (if (zero? n)
      ["" false 0]
      (let [take-n (if limit (min n limit) n)
            s (join-chars (take take-n (or (realize v) ())))]
        [s (> n take-n) n]))))

(defn- refuse-collection [op v]
  (throw (ex-info (str op " is for scalars and strings; collections stay as values")
                  {:op op :type (value-type v)})))

(defn native
  "Host atom for a scalar, or host String for a Dacite string. nil → nil.

   Collections and blobs throw — they are not field-sized atoms. Use
   collection ops (`get`, `nth`, `seq`) and walk them instead of dumping
   the tree into RAM.

   Optional `limit` (or dynamic `*string-char-limit*`) is a max character
   count for Dacite strings: only that prefix is realized, then a longer
   string throws. nil limit = no cap. Host values pass through unchanged."
  ([x] (native x *string-char-limit*))
  ([x limit]
   (cond
     (nil? x) nil
     (not (dacite-value? x)) x
     :else
     (case (value-type x)
       "string" (let [[s truncated? n] (realize-string x limit)]
                  (when truncated?
                    (throw (ex-info "string exceeds native char limit"
                                    {:count n :limit limit})))
                  s)
       ("vector" "map" "set" "blob") (refuse-collection "native" x)
       (realize x)))))

(defn as-str
  "Host string for a Dacite string or scalar. nil → nil. Collections throw.

   Implemented via `native` (same optional `limit` / `*string-char-limit*`).
   For field-sized text (titles, theme names). Not a whole-value dump."
  ([x] (as-str x *string-char-limit*))
  ([x limit]
   (let [n (native x limit)]
     (cond
       (nil? n) nil
       (string? n) n
       :else (clojure.core/str n)))))

(defn as-bytes
  "Host bytes for a Dacite blob. nil → nil. Other types throw.

   Optional `limit` (or `*string-char-limit*`) is a max byte count: only
   that prefix is realized, then a longer blob throws. nil limit = no cap.
   Missing store nodes throw `ex-info` with `:dacite/missing true`."
  ([x] (as-bytes x *string-char-limit*))
  ([x limit]
   (cond
     (nil? x) nil
     #?(:clj (bytes? x) :cljs false) x
     (not (dacite-value? x))
     (throw (ex-info "as-bytes expects a Dacite blob" {:value x}))
     (not= "blob" (value-type x))
     (throw (ex-info "as-bytes is for blobs" {:type (value-type x)}))
     :else
     (let [st (dacite-store x)
           h (dacite-hash x)]
       (when-not (store/s-has? st h)
         (throw (ex-info "blob not in store"
                         {:dacite/missing true :hash h})))
       (let [n (count x)
             take-n (if limit (min n limit) n)]
         (when (and limit (> n limit))
           (throw (ex-info "blob exceeds byte limit"
                           {:count n :limit limit})))
         (let [nums (mapv long (take take-n (or (realize x) ())))]
           #?(:clj (byte-array (map unchecked-byte nums))
              :cljs nums)))))))

(defn pr-str
  "Bounded debug render. Never throws. Does not dump the tree into RAM.

   Dacite strings realize at most `limit` characters (default
   `*string-char-limit*` or 64) and render as `\"prefix…\" (n chars)` when
   longer — same idea as `dacite.value.render`. Other Dacite values use
   bounded `toString` (JVM) or host `str`. Host values use clojure.core/pr-str."
  ([x] (pr-str x (or *string-char-limit* default-pr-str-char-limit)))
  ([x limit]
   (cond
     (nil? x) "nil"
     (and (dacite-value? x) (= "string" (value-type x)))
     (let [[s truncated? n] (realize-string x limit)]
       (if truncated?
         (str "\"" s "…\" (" n " chars)")
         (clojure.core/pr-str s)))
     (dacite-value? x)
     #?(:clj (render/bounded-to-string x)
        :default (clojure.core/str x))
     :else (clojure.core/pr-str x))))

(defn get-in
  "Look up a nested path. Empty path returns v. Missing path → not-found."
  ([v ks] (get-in v ks nil))
  ([v ks not-found]
   (if-not (clojure.core/seq ks)
     v
     (loop [cur v
            ks (clojure.core/seq ks)]
       (if ks
         (if (or (nil? cur) (not (dacite-value? cur)))
           not-found
           (let [nxt (get cur (first ks) ::missing)]
             (if (= nxt ::missing)
               not-found
               (recur nxt (next ks)))))
         cur)))))

(defn assoc-in
  "Assoc at a nested path, creating intermediate maps as needed.
   `ks` must be non-empty."
  [v ks x]
  (when-not (clojure.core/seq ks)
    (throw (ex-info "assoc-in requires a non-empty path" {:value v})))
  (let [k (first ks)
        more (next ks)]
    (if more
      (let [child (get v k)
            child (if (dacite-value? child) child (hash-map-via v))]
        (assoc v k (assoc-in child more x)))
      (assoc v k x))))

(defn update
  "Apply f to the value at k (nil if missing) and assoc the result."
  ([v k f]
   (assoc v k (f (get v k))))
  ([v k f a]
   (assoc v k (f (get v k) a)))
  ([v k f a b]
   (assoc v k (f (get v k) a b)))
  ([v k f a b & more]
   (assoc v k (apply f (get v k) a b more))))

(defn update-in
  "Apply f to the value at path ks (nil if missing) and assoc-in the result.
   `ks` must be non-empty."
  [v ks f & args]
  (assoc-in v ks (apply f (get-in v ks) args)))

;; =============================================================================
;; Root reference (value-level)
;; =============================================================================

;; Same clash: `dacite.value.root-ref` var vs namespace (browser JS).
#?(:org.babashka/nbb (def root-ref root-ref/root-ref)
   :clj (def root-ref root-ref/root-ref)
   :cljs nil)
(def root-ref?       root-ref/root-ref?)
(def ref-deref       root-ref/ref-deref)
(def ref-reset!      root-ref/ref-reset!)
(def ref-swap!       root-ref/ref-swap!)
(def ref-swap-info!  root-ref/ref-swap-info!)
(def ref-cas!        root-ref/ref-cas!)
(def ref-add-watch   root-ref/ref-add-watch)
(def ref-remove-watch root-ref/ref-remove-watch)

;; =============================================================================
;; Store isolation (thin sugar)
;; =============================================================================

(defmacro with-store
  "Execute body with an isolated store. init can be an IStore or a map
   (wrapped in a mem-store). Returns [snapshot last-value]."
  [[sym init] & body]
  `(store/with-store [~sym ~init] ~@body))

;; =============================================================================
;; Boundary crossing (JVM)
;; =============================================================================

#?(:clj
   (do
     (def dac->clj convert/dac->clj)
     (def clj->dac convert/clj->dac)))
