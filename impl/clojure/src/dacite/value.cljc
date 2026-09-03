(ns dacite.value
  "Public value API for Dacite.

   Application code should need only this namespace for values (pair with
   `dacite.store` for a rooted store):

     (require '[dacite.value :as v]
              '[dacite.store :as s])

     (def r (v/root (s/mem)))
     (v/cas! r nil (v/map r \"title\" \"hi\"))
     (v/swap! r v/assoc \"title\" \"hello\")

   Constructors take a context first — a store, a root, or another value.
   Collection ops take a Dacite value first: `conj`, `get`, `nth`, `count`, …"
  (:refer-clojure :exclude [vector set count nth get assoc conj seq
                            peek pop keys vals contains? dissoc empty?
                            get-in assoc-in update update-in pr-str
                            type hash map deref swap! add-watch remove-watch char])
  (:require [dacite.store :as store]
            [dacite.value.types :as types]
            [dacite.value.scalar :as scalar]
            [dacite.value.collections :as coll]
            [dacite.value.root-ref :as root-ref]
            #?@(:clj [[dacite.value.render :as render]])))

;; =============================================================================
;; Accessors
;; =============================================================================

(def dacite-store types/dacite-store)
(def realize      types/realize)
(def extract-hash types/extract-hash)
(def store-of     types/store-of)
(def IStoreCarrier types/IStoreCarrier)
(def IDaciteValue  types/IDaciteValue)

(defn dacite-value?
  "True if x is a Dacite value."
  [x]
  (satisfies? types/IDaciteValue x))

(defn type
  "Type-name string of a Dacite value (\"vector\", \"map\", \"i64\", …)."
  [v]
  (types/dacite-type v))

(defn hash
  "Content hash of a Dacite value."
  [v]
  (types/dacite-hash v))

(defn content-hash
  "Strip a value's type tag to recover its data hash (§3.3)."
  [v]
  (types/content-hash (type v) (hash v)))

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
;; Constructors — first argument is ctx (store, root, or Dacite value)
;; =============================================================================

(defn null [ctx] (scalar/null-via ctx))
(defn bool [ctx x] (scalar/bool-via ctx x))
(defn i8 [ctx n] (scalar/i8-via ctx n))
(defn i16 [ctx n] (scalar/i16-via ctx n))
(defn i32 [ctx n] (scalar/i32-via ctx n))
(defn i64 [ctx n] (scalar/i64-via ctx n))
(defn u8 [ctx n] (scalar/u8-via ctx n))
(defn u16 [ctx n] (scalar/u16-via ctx n))
(defn u32 [ctx n] (scalar/u32-via ctx n))
(defn u64 [ctx n] (scalar/u64-via ctx n))
(defn u256 [ctx n] (scalar/u256-via ctx n))
(defn f32 [ctx n] (scalar/f32-via ctx n))
(defn f64 [ctx n] (scalar/f64-via ctx n))
(defn char [ctx ch] (scalar/dacite-char-via ctx ch))
(defn negative [ctx] (scalar/negative-via ctx))
(def negative-sentinel scalar/negative-sentinel)

(defn string
  [ctx s]
  (coll/string-via ctx s))

(defn blob
  [ctx bs]
  (coll/blob-via ctx bs))

(defn vector
  [ctx & xs]
  (apply coll/vector-via ctx xs))

(defn map
  [ctx & kvs]
  (apply coll/hash-map-via ctx kvs))

(defn set
  [ctx & xs]
  (apply coll/set-via ctx xs))

;; =============================================================================
;; Set algebra (§3.5)
;; =============================================================================

(def set-member?    coll/set-member?)
(def set-complement coll/set-complement)
(def set-union      coll/set-union)
(def set-intersect  coll/set-intersect)
(def set-difference coll/set-difference)

;; =============================================================================
;; Collection API
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
  (case (type v)
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
   (case (type v)
     "map"    (coll/map-get (types/dacite-store v) (types/dacite-hash v) k not-found)
     "set"    (coll/set-get (types/dacite-store v) (types/dacite-hash v) k not-found)
     "vector" (if (and (integer? k) (<= 0 k) (< k (count v)))
                (coll/seq-nth (types/dacite-store v) (types/dacite-hash v) k)
                not-found)
     not-found)))

(defn contains?
  "True if key/index k is present."
  [v k]
  (case (type v)
    "map"    (coll/map-contains? (types/dacite-store v) (types/dacite-hash v) k)
    "set"    (coll/set-contains? (types/dacite-store v) (types/dacite-hash v) k)
    "vector" (and (integer? k) (<= 0 k) (< k (count v)))
    false))

(defn assoc
  "Associate k->val in a vector (integer index) or map. Returns a new
   Dacite value."
  [v k val]
  (case (type v)
    "vector" (coll/vec-assoc (types/dacite-store v) (types/dacite-hash v) k val)
    "map"    (coll/map-assoc (types/dacite-store v) (types/dacite-hash v) k val)
    (throw (ex-info "assoc unsupported for type" {:type (type v)}))))

(defn dissoc
  "Remove key k from a map. Returns a new Dacite map."
  [v k]
  (case (type v)
    "map" (coll/map-dissoc (types/dacite-store v) (types/dacite-hash v) k)
    (throw (ex-info "dissoc unsupported for type" {:type (type v)}))))

(defn conj
  "Append to a vector, add to a set, or add a [k v] pair to a map."
  [v x]
  (case (type v)
    "vector" (coll/vec-conj (types/dacite-store v) (types/dacite-hash v) x)
    "set"    (coll/set-conj (types/dacite-store v) (types/dacite-hash v) x)
    "map"    (coll/map-assoc (types/dacite-store v) (types/dacite-hash v)
                             (clojure.core/nth x 0) (clojure.core/nth x 1))
    (throw (ex-info "conj unsupported for type" {:type (type v)}))))

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
  (case (type v)
    ("vector" "string" "blob")
    (coll/seq-remove-nth (types/dacite-store v) (types/dacite-hash v) i)
    (throw (ex-info "remove-nth unsupported for type" {:type (type v)}))))

(defn slice
  "Elements [start, end) of a vector, string, or blob as a new value of
   the same type.

   One-arg end defaults to the count. Leaves are shared. Any page is
   O((end-start) log n) via nth — it does not seq the whole collection."
  ([v start] (slice v start (count v)))
  ([v start end]
   (case (type v)
     ("vector" "string" "blob")
     (coll/seq-slice (types/dacite-store v) (types/dacite-hash v) start end)
     (throw (ex-info "slice unsupported for type" {:type (type v)})))))

(defn keys
  "Wrapped keys of a map, or nil if empty."
  [v]
  (when-let [es (coll/map-entries (types/dacite-store v) (types/dacite-hash v))]
    (clojure.core/map first es)))

(defn vals
  "Wrapped values of a map, or nil if empty."
  [v]
  (when-let [es (coll/map-entries (types/dacite-store v) (types/dacite-hash v))]
    (clojure.core/map second es)))

;; =============================================================================
;; Field access and path updates (stay on the value — not dac->clj)
;; =============================================================================

(def ^:dynamic *string-char-limit*
  "When bound to a number, `native` refuses a Dacite string longer than
   this many characters (it realizes at most that prefix, then throws).
   `pr-str` uses the same bound to truncate. nil (default) means no limit
   for `native`; `pr-str` then falls back to 64 characters."
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
     (.join (to-array (clojure.core/map clojure.core/str cs)) "")))

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
                  {:op op :type (type v)})))

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
     (case (type x)
       "string" (let [[s truncated? n] (realize-string x limit)]
                  (when truncated?
                    (throw (ex-info "string exceeds native char limit"
                                    {:count n :limit limit})))
                  s)
       ("vector" "map" "set" "blob") (refuse-collection "native" x)
       (realize x)))))

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
     (not= "blob" (type x))
     (throw (ex-info "as-bytes is for blobs" {:type (type x)}))
     :else
     (let [st (dacite-store x)
           h (hash x)]
       (when-not (store/s-has? st h)
         (throw (ex-info "blob not in store"
                         {:dacite/missing true :hash h})))
       (let [n (count x)
             take-n (if limit (min n limit) n)]
         (when (and limit (> n limit))
           (throw (ex-info "blob exceeds byte limit"
                           {:count n :limit limit})))
         (let [nums (mapv long (take take-n (or (realize x) ())))]
           #?(:clj (byte-array (clojure.core/map unchecked-byte nums))
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
     (and (dacite-value? x) (= "string" (type x)))
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
            child (if (dacite-value? child) child (map v))]
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
;; Root (value-level handle over a rooted store)
;; =============================================================================

(def root       root-ref/root-ref)
(def root?      root-ref/root-ref?)
(def deref      root-ref/ref-deref)
(def swap!      root-ref/ref-swap!)
(def swap-info! root-ref/ref-swap-info!)
(def cas!       root-ref/ref-cas!)
(def add-watch  root-ref/ref-add-watch)
(def remove-watch root-ref/ref-remove-watch)

