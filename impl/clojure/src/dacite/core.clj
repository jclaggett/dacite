(ns dacite.core
  "Dacite: Data citing with fused hashing.

   Public API for constructing and working with Dacite values.

   All values are opaque types holding a hash (pointer into the store).
   Use `with-store` to establish a store context, then use bang constructors:

     (d/with-store [s {}]
       (let [v (d/vector-of! [1 2 3])]
         (count v)       ;; => 3
         (nth v 0)       ;; => DaciteScalar
         @(nth v 0)      ;; => 42
         (conj v 4)))    ;; => DaciteVector

   Pure constructors take a store and return [store' hash]:
     (d/i64 store 42)  => [store' hash]"
  (:refer-clojure :exclude [vector vector-of])
  (:require [dacite.hash :as hash]
            [dacite.types :as types]
            [dacite.finger-tree :as ft]
            [dacite.hamt :as hamt])
  (:import [clojure.lang IDeref IHashEq Counted Seqable ILookup
            IPersistentCollection Indexed IPersistentStack
            IPersistentVector Associative IFn Sequential
            IPersistentMap MapEquivalence]))

;; =============================================================================
;; with-store macro
;; =============================================================================

(def ^:dynamic *store*
  "Dynamic var holding the current store atom when inside with-store."
  nil)

(defmacro with-store
  "Execute body with a managed store atom. Binds the symbol to the atom
   and sets *store* for bang constructors. Returns [final-store last-value].

   Usage:
     (with-store [s {}]
       (let [a (i64! 42)]
         (vector! [a])))
     ;; => [final-store DaciteVector]"
  [[sym init] & body]
  `(let [~sym (atom ~init)]
     (binding [*store* ~sym]
       (let [result# (do ~@body)]
         [@~sym result#]))))

(defn- store-put!
  "Add a typed value to the dynamic *store*. Returns its hash."
  [value]
  (let [h (hash/typed-value-hash value)]
    (swap! *store* assoc h value)
    h))

(defn- store-merge!
  "Merge a map into the dynamic *store*."
  [m]
  (swap! *store* merge m))

;; =============================================================================
;; Forward declarations
;; =============================================================================

(declare ->DaciteScalar ->DaciteVector ->DaciteMap ->DaciteString)
(declare wrap-hash coerce-and-store coerce-and-store!)
(declare vector-conj-internal map-assoc-internal map-dissoc-internal)
(declare vector-refs-internal vector-count-internal)
(declare map-count-internal map-get-internal map-entries-internal)

;; Protocol for extracting the internal hash from any Dacite type
(defprotocol IDaciteHash
  (dacite-hash [this] "Return the internal hash of this Dacite value."))

(defn- extract-hash
  "Extract hash from a Dacite type, or coerce and store a raw value."
  [x]
  (if (satisfies? IDaciteHash x)
    (dacite-hash x)
    (coerce-and-store! x)))

;; =============================================================================
;; DaciteScalar — wraps a hash to a scalar value
;; =============================================================================

(deftype DaciteScalar [^:unsynchronized-mutable _hash]
  IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_]
    (let [[_type-kw data] (get @*store* _hash)]
      data))

  IHashEq
  (hasheq [_]
    (hash/hash->int _hash))

  Object
  (hashCode [_]
    (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteScalar other)
         (= _hash (.-_hash ^DaciteScalar other))))
  (toString [_]
    (let [[type-kw data] (get @*store* _hash)]
      (pr-str [type-kw data])))

  IFn
  (invoke [this] (deref this)))

(defn scalar-type
  "Get the type keyword of a DaciteScalar."
  [s]
  (let [[type-kw _data] (get @*store* (dacite-hash s))]
    type-kw))

(defn scalar-hash
  "Get the raw hash of a DaciteScalar."
  [s]
  (dacite-hash s))

;; =============================================================================
;; DaciteString — wraps a hash to a string value
;; =============================================================================

(deftype DaciteString [^:unsynchronized-mutable _hash]
  IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_]
    (let [[_type-kw data] (get @*store* _hash)]
      data))

  IHashEq
  (hasheq [_]
    (hash/hash->int _hash))

  Counted
  (count [_]
    (let [[_type-kw data] (get @*store* _hash)]
      (clojure.core/count data)))

  Seqable
  (seq [_]
    (let [[_type-kw data] (get @*store* _hash)]
      (seq data)))

  CharSequence
  (length [this] (.count this))
  (charAt [_ i]
    (let [[_type-kw data] (get @*store* _hash)]
      (.charAt ^String data i)))
  (subSequence [_ start end]
    (let [[_type-kw data] (get @*store* _hash)]
      (.subSequence ^String data start end)))

  Object
  (hashCode [_]
    (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteString other)
         (= _hash (.-_hash ^DaciteString other))))
  (toString [_]
    (let [[_type-kw data] (get @*store* _hash)]
      (str data))))

;; =============================================================================
;; DaciteVector — wraps a hash to a vector in the store
;; =============================================================================

(deftype DaciteVector [^:unsynchronized-mutable _hash]
  IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_] _hash)

  IHashEq
  (hasheq [_]
    (hash/hash->int _hash))

  Counted
  (count [_]
    (vector-count-internal _hash))

  Seqable
  (seq [this]
    (when (pos? (.count this))
      (map #(wrap-hash %) (vector-refs-internal _hash))))

  ILookup
  (valAt [this k]
    (.valAt this k nil))
  (valAt [this k not-found]
    (if (and (integer? k) (<= 0 k) (< k (.count this)))
      (wrap-hash (nth (vector-refs-internal _hash) k))
      not-found))

  Indexed
  (nth [this i]
    (wrap-hash (nth (vector-refs-internal _hash) i)))
  (nth [this i not-found]
    (if (and (<= 0 i) (< i (.count this)))
      (wrap-hash (nth (vector-refs-internal _hash) i))
      not-found))

  IPersistentCollection
  (empty [_]
    (let [[s' h] (ft/finger-tree)
          ef (ft/tree-elements-fuse [s' h])
          vh (hash/node-hash :vector ef)]
      (store-merge! s')
      (swap! *store* assoc vh [:vector {:root h :refs []}])
      (->DaciteVector vh)))
  (cons [this val]
    (let [vh (extract-hash val)
          new-hash (vector-conj-internal _hash vh)]
      (->DaciteVector new-hash)))
  (equiv [_ other]
    (and (instance? DaciteVector other)
         (= _hash (.-_hash ^DaciteVector other))))

  IPersistentStack
  (peek [this]
    (let [refs (vector-refs-internal _hash)]
      (when (seq refs)
        (wrap-hash (last refs)))))
  (pop [this]
    (let [refs (vector-refs-internal _hash)]
      (when (empty? refs)
        (throw (IllegalStateException. "Can't pop empty vector")))
      (let [new-refs (clojure.core/vec (butlast refs))
            [s' h'] (reduce (fn [[s root] ref]
                              (ft/conj-right [s root] ref))
                            (ft/finger-tree)
                            new-refs)
            ef (ft/tree-elements-fuse [s' h'])
            vh (hash/node-hash :vector ef)]
        (store-merge! s')
        (swap! *store* assoc vh [:vector {:root h' :refs new-refs}])
        (->DaciteVector vh))))

  Associative
  (containsKey [this k]
    (and (integer? k) (<= 0 k) (< k (.count this))))
  (assoc [this k v]
    (when-not (integer? k)
      (throw (IllegalArgumentException. "Key must be integer")))
    (let [vh (extract-hash v)
          refs (vector-refs-internal _hash)
          new-refs (assoc refs k vh)
          [s' h'] (reduce (fn [[s root] ref]
                            (ft/conj-right [s root] ref))
                          (ft/finger-tree)
                          new-refs)
          ef (ft/tree-elements-fuse [s' h'])
          new-hash (hash/node-hash :vector ef)]
      (store-merge! s')
      (swap! *store* assoc new-hash [:vector {:root h' :refs new-refs}])
      (->DaciteVector new-hash)))
  (entryAt [this k]
    (when (and (integer? k) (<= 0 k) (< k (.count this)))
      (clojure.lang.MapEntry/create k (.nth ^Indexed this k))))

  IPersistentVector
  (length [this] (.count ^Counted this))
  (assocN [this i val] (.assoc ^Associative this i val))

  Sequential

  IFn
  (invoke [this k]
    (.valAt ^ILookup this k))

  Comparable
  (compareTo [this other]
    (if (instance? DaciteVector other)
      (compare _hash (.-_hash ^DaciteVector other))
      (throw (ClassCastException.))))

  Iterable
  (iterator [this]
    (.iterator ^Iterable (or (seq this) ())))

  Object
  (hashCode [_]
    (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteVector other)
         (= _hash (.-_hash ^DaciteVector other))))
  (toString [this]
    (str (into [] (map deref) (seq this)))))

;; =============================================================================
;; DaciteMap — wraps a hash to a map in the store
;; =============================================================================

(deftype DaciteMap [^:unsynchronized-mutable _hash]
  IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_] _hash)

  IHashEq
  (hasheq [_]
    (hash/hash->int _hash))

  Counted
  (count [_]
    (map-count-internal _hash))

  Seqable
  (seq [_]
    (let [entries (map-entries-internal _hash)]
      (when (seq entries)
        (map (fn [[kh vh]]
               (clojure.lang.MapEntry/create (wrap-hash kh) (wrap-hash vh)))
             entries))))

  ILookup
  (valAt [this k]
    (.valAt this k nil))
  (valAt [_ k not-found]
    (let [result (map-get-internal _hash k)]
      (if result
        (wrap-hash result)
        not-found)))

  IPersistentCollection
  (empty [_]
    (let [[s' h'] (hamt/hamt)
          ef (hamt/hamt-elements-fuse [s' h'])
          mh (hash/node-hash :map ef)]
      (store-merge! s')
      (swap! *store* assoc mh [:map {:root h' :pairs []}])
      (->DaciteMap mh)))
  (cons [this entry]
    (if (instance? java.util.Map$Entry entry)
      (let [k (.getKey ^java.util.Map$Entry entry)
            v (.getValue ^java.util.Map$Entry entry)]
        (.assoc ^Associative this k v))
      (if (and (clojure.core/vector? entry) (= 2 (clojure.core/count entry)))
        (.assoc ^Associative this (first entry) (second entry))
        (throw (IllegalArgumentException. "Can only conj [k v] pairs or map entries")))))
  (equiv [_ other]
    (and (instance? DaciteMap other)
         (= _hash (.-_hash ^DaciteMap other))))

  Associative
  (containsKey [_ k]
    (some? (map-get-internal _hash k)))
  (assoc [_ k v]
    (let [new-hash (map-assoc-internal _hash k v)]
      (->DaciteMap new-hash)))
  (entryAt [_ k]
    (let [result (map-get-internal _hash k)]
      (when result
        (clojure.lang.MapEntry/create (wrap-hash k) (wrap-hash result)))))

  IPersistentMap
  (assocEx [this k v]
    (if (.containsKey ^Associative this k)
      (throw (RuntimeException. "Key already present"))
      (.assoc ^Associative this k v)))
  (without [_ k]
    (let [new-hash (map-dissoc-internal _hash k)]
      (->DaciteMap new-hash)))

  MapEquivalence

  IFn
  (invoke [this k]
    (.valAt ^ILookup this k))
  (invoke [this k not-found]
    (.valAt ^ILookup this k not-found))

  Iterable
  (iterator [this]
    (.iterator ^Iterable (or (seq this) ())))

  Object
  (hashCode [_]
    (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteMap other)
         (= _hash (.-_hash ^DaciteMap other))))
  (toString [this]
    (str (into {} (map (fn [[k v]] [@k @v])) (seq this)))))

;; =============================================================================
;; Hash wrapping — resolve hash to appropriate type
;; =============================================================================

(defn wrap-hash
  "Wrap a raw hash in the appropriate DaciteXxx type based on what's in the store."
  [h]
  (let [[type-kw _data] (get @*store* h)]
    (case type-kw
      :vector (->DaciteVector h)
      :map (->DaciteMap h)
      :string (->DaciteString h)
      ;; default: scalar
      (->DaciteScalar h))))

(defn unwrap-hash
  "Extract the raw hash from any Dacite type."
  [x]
  (if (satisfies? IDaciteHash x)
    (dacite-hash x)
    (throw (ex-info "Not a Dacite value" {:value x}))))

;; =============================================================================
;; Scalar constructors (pure)
;; =============================================================================

(defn scalar "Create a scalar of any type. Returns [store' hash]."
  [store type-kw data]
  (let [v [type-kw data] h (hash/typed-value-hash v)]
    [(assoc store h v) h]))

(defn null "Create a null value. Returns [store' hash]."
  [store] (scalar store :null nil))
(defn bool "Create a boolean value. Returns [store' hash]."
  [store b] {:pre [(instance? Boolean b)]} (scalar store :bool b))
(defn i8 "Create i8. Returns [store' hash]." [store n] (scalar store :i8 n))
(defn i16 "Create i16. Returns [store' hash]." [store n] (scalar store :i16 n))
(defn i32 "Create i32. Returns [store' hash]." [store n] (scalar store :i32 n))
(defn i64 "Create i64. Returns [store' hash]." [store n] (scalar store :i64 n))
(defn u8 "Create u8. Returns [store' hash]."
  [store n] {:pre [(<= 0 n 255)]} (scalar store :u8 n))
(defn u16 "Create u16. Returns [store' hash]."
  [store n] {:pre [(<= 0 n 65535)]} (scalar store :u16 n))
(defn u32 "Create u32. Returns [store' hash]."
  [store n] {:pre [(<= 0 n 4294967295)]} (scalar store :u32 n))
(defn u64 "Create u64. Returns [store' hash]."
  [store n] {:pre [(<= 0 n)]} (scalar store :u64 n))
(defn u256 "Create u256 (e.g. hash as data). Returns [store' hash]."
  [store ^bytes data] {:pre [(= 32 (alength data))]} (scalar store :u256 data))
(defn f32 "Create f32. Returns [store' hash]."
  [store n] (scalar store :f32 (float n)))
(defn f64 "Create f64. Returns [store' hash]."
  [store n] (scalar store :f64 (double n)))
(defn dacite-char "Create char. Returns [store' hash]."
  [store c] {:pre [(char? c)]} (scalar store :char c))

;; =============================================================================
;; Scalar constructors (bang — use with with-store)
;; =============================================================================

(defn scalar! "Create scalar in *store*. Returns DaciteScalar."
  [type-kw data]
  (->DaciteScalar (store-put! [type-kw data])))

(defn null! "Create null in *store*. Returns DaciteScalar." []
  (scalar! :null nil))
(defn bool! "Create bool in *store*. Returns DaciteScalar." [b]
  {:pre [(instance? Boolean b)]} (scalar! :bool b))
(defn i8! "Create i8 in *store*. Returns DaciteScalar." [n] (scalar! :i8 (byte n)))
(defn i16! "Create i16 in *store*. Returns DaciteScalar." [n] (scalar! :i16 (short n)))
(defn i32! "Create i32 in *store*. Returns DaciteScalar." [n] (scalar! :i32 (int n)))
(defn i64! "Create i64 in *store*. Returns DaciteScalar." [n] (scalar! :i64 (long n)))
(defn u8! "Create u8 in *store*. Returns DaciteScalar." [n]
  {:pre [(<= 0 n 255)]} (scalar! :u8 n))
(defn u16! "Create u16 in *store*. Returns DaciteScalar." [n]
  {:pre [(<= 0 n 65535)]} (scalar! :u16 n))
(defn u32! "Create u32 in *store*. Returns DaciteScalar." [n]
  {:pre [(<= 0 n 4294967295)]} (scalar! :u32 n))
(defn u64! "Create u64 in *store*. Returns DaciteScalar." [n]
  {:pre [(<= 0 n)]} (scalar! :u64 n))
(defn u256! "Create u256 in *store*. Returns DaciteScalar." [^bytes data]
  {:pre [(= 32 (alength data))]} (scalar! :u256 data))
(defn f32! "Create f32 in *store*. Returns DaciteScalar." [n] (scalar! :f32 (float n)))
(defn f64! "Create f64 in *store*. Returns DaciteScalar." [n] (scalar! :f64 (double n)))
(defn dacite-char! "Create char in *store*. Returns DaciteScalar." [c]
  {:pre [(char? c)]} (scalar! :char c))

;; =============================================================================
;; Value accessors (pure — work with store + hash)
;; =============================================================================

(defn value-type
  "Get the type keyword of a value in the store."
  [store h]
  (types/dacite-type (get store h)))

(defn value-data
  "Get the raw data of a value in the store."
  [store h]
  (types/dacite-data (get store h)))

(defn lookup
  "Look up a value by hash in the store. Returns [type-kw data] or nil."
  [store h]
  (get store h))

;; =============================================================================
;; Hashing utilities
;; =============================================================================

(defn hash-hex
  "Convert a raw hash to a 64-char hex string."
  [h]
  (hash/hash->hex h))

(defn hash-as-value
  "Store a raw hash as a :u256 data value. Returns [store' hash]."
  [store raw-hash]
  (u256 store (hash/longs->bytes raw-hash)))

;; =============================================================================
;; String construction
;; =============================================================================

(defn string
  "Create a dacite string. Returns [store' hash]."
  [store s]
  (let [v [:string s]
        h (hash/typed-value-hash v)]
    [(assoc store h v) h]))

(defn string!
  "Create a dacite string in *store*. Returns DaciteString."
  [s]
  (let [v [:string s]
        h (hash/typed-value-hash v)]
    (swap! *store* assoc h v)
    (->DaciteString h)))

(defn string-value
  "Extract the string from a :string value in the store."
  [store h]
  (let [[type-kw data] (get store h)]
    (when (= :string type-kw) data)))

;; =============================================================================
;; Auto-coercion (internal)
;; =============================================================================

(defn- coerce-and-store
  "Coerce a plain Clojure value to a dacite typed value and store it.
   Returns [store' hash]."
  [store x]
  (cond
    (nil? x)              (null store)
    (instance? Boolean x) (bool store x)
    (integer? x)          (i64 store x)
    (float? x)            (f64 store (double x))
    (double? x)           (f64 store x)
    (char? x)             (dacite-char store x)
    (string? x)           (string store x)
    :else (throw (ex-info "Cannot coerce to dacite value" {:value x :type (type x)}))))

(defn- coerce-and-store!
  "Coerce a plain Clojure value and store in *store*. Returns hash."
  [x]
  (cond
    (nil? x)              (dacite-hash (null!))
    (instance? Boolean x) (dacite-hash (bool! x))
    (integer? x)          (dacite-hash (i64! x))
    (float? x)            (dacite-hash (f64! (double x)))
    (double? x)           (dacite-hash (f64! x))
    (char? x)             (dacite-hash (dacite-char! x))
    (string? x)           (dacite-hash (string! x))
    :else (throw (ex-info "Cannot coerce to dacite value" {:value x :type (type x)}))))

;; =============================================================================
;; Vector internal helpers (work with hashes and *store*)
;; =============================================================================

(defn- vector-count-internal [h]
  (let [{:keys [refs]} (second (get @*store* h))]
    (clojure.core/count refs)))

(defn- vector-refs-internal [h]
  (let [{:keys [refs]} (second (get @*store* h))]
    refs))

(defn- build-vector-from-refs!
  "Build a vector from refs, storing in *store*. Returns hash."
  [refs]
  (let [[ft-store ft-root]
        (reduce (fn [[s root] ref]
                  (ft/conj-right [s root] ref))
                (ft/finger-tree)
                refs)
        ef (ft/tree-elements-fuse [ft-store ft-root])
        h (hash/node-hash :vector ef)]
    (store-merge! ft-store)
    (swap! *store* assoc h [:vector {:root ft-root :refs (clojure.core/vec refs)}])
    h))

(defn- vector-conj-internal
  "Append ref to vector hash, mutating *store*. Returns new hash."
  [vec-hash ref]
  (let [refs (vector-refs-internal vec-hash)]
    (build-vector-from-refs! (conj (clojure.core/vec refs) ref))))

;; =============================================================================
;; Vector construction (pure)
;; =============================================================================

(defn vector
  "Create a dacite vector from a sequence of hashes (refs already in store).
   Returns [store' hash]."
  [store refs]
  (let [[ft-store ft-root]
        (reduce (fn [[s root] ref]
                  (ft/conj-right [s root] ref))
                (let [[s root] (ft/finger-tree)]
                  [(merge store s) root])
                refs)
        ef (ft/tree-elements-fuse [ft-store ft-root])
        h (hash/node-hash :vector ef)]
    [(assoc ft-store h [:vector {:root ft-root :refs (clojure.core/vec refs)}]) h]))

(defn vector-of
  "Create a dacite vector from plain Clojure values (auto-coerced).
   Returns [store' hash]."
  [store values]
  (let [[s refs] (reduce (fn [[s refs] v]
                           (let [[s' h] (coerce-and-store s v)]
                             [s' (conj refs h)]))
                         [store []]
                         values)]
    (vector s refs)))

;; =============================================================================
;; Vector construction (bang)
;; =============================================================================

(defn vector!
  "Create a dacite vector from refs in *store*. Returns DaciteVector."
  [refs]
  (->DaciteVector (build-vector-from-refs! refs)))

(defn vector-of!
  "Create a dacite vector from plain values in *store*. Returns DaciteVector."
  [values]
  (let [refs (mapv coerce-and-store! values)]
    (vector! refs)))

;; =============================================================================
;; Vector accessors (pure)
;; =============================================================================

(defn vector-count
  "Get the element count of a dacite vector (O(1))."
  [store h]
  (let [{:keys [refs]} (second (get store h))]
    (clojure.core/count refs)))

(defn vector-nth
  "Get the nth element ref from a dacite vector."
  [store h n]
  (let [{:keys [refs]} (second (get store h))]
    (nth refs n)))

(defn vector-refs
  "Get all element refs from a dacite vector."
  [store h]
  (let [{:keys [refs]} (second (get store h))]
    refs))

(defn vector-conj
  "Append a ref to a dacite vector. Returns [store' new-hash]."
  [store h ref]
  (let [{:keys [root refs]} (second (get store h))
        [s' new-root] (ft/conj-right [store root] ref)
        new-refs (conj refs ref)
        ef (ft/tree-elements-fuse [s' new-root])
        vh (hash/node-hash :vector ef)]
    [(assoc s' vh [:vector {:root new-root :refs new-refs}]) vh]))

;; =============================================================================
;; Map internal helpers
;; =============================================================================

(defn- map-count-internal [h]
  (let [{:keys [root]} (second (get @*store* h))]
    (hamt/hamt-count [@*store* root])))

(defn- map-get-internal
  "Look up a key (raw Clojure value) in the map. Returns val-hash or nil."
  [map-hash key]
  (let [{:keys [root]} (second (get @*store* map-hash))
        kh (extract-hash key)
        key-val (get @*store* kh)
        k-hash (hash/typed-value-hash key-val)]
    (hamt/get-val [@*store* root] k-hash)))

(defn- map-entries-internal [h]
  (let [{:keys [root]} (second (get @*store* h))]
    (hamt/entries [@*store* root])))

(defn- map-assoc-internal
  "Assoc a key and value into the map. Key/val can be Dacite types or raw values.
   Mutates *store*. Returns new map hash."
  [map-hash k v]
  (let [{:keys [root pairs]} (second (get @*store* map-hash))
        kh (extract-hash k)
        vh (extract-hash v)
        k-hash (hash/typed-value-hash (get @*store* kh))
        [s' new-root] (hamt/assoc-val [@*store* root] k-hash kh vh)
        new-pairs (conj (clojure.core/vec (remove #(= kh (first %)) pairs))
                        [kh vh])
        ef (hamt/hamt-elements-fuse [s' new-root])
        h (hash/node-hash :map ef)]
    (store-merge! s')
    (swap! *store* assoc h [:map {:root new-root :pairs new-pairs}])
    h))

(defn- map-dissoc-internal
  "Dissoc a key from the map. Key can be Dacite type or raw value.
   Mutates *store*. Returns new map hash."
  [map-hash k]
  (let [{:keys [root pairs]} (second (get @*store* map-hash))
        kh (extract-hash k)
        k-hash (hash/typed-value-hash (get @*store* kh))
        [s' new-root] (hamt/dissoc-val [@*store* root] k-hash)
        new-pairs (clojure.core/vec (remove #(= kh (first %)) pairs))
        ef (hamt/hamt-elements-fuse [s' new-root])
        h (hash/node-hash :map ef)]
    (store-merge! s')
    (swap! *store* assoc h [:map {:root new-root :pairs new-pairs}])
    h))

;; =============================================================================
;; Map construction (pure)
;; =============================================================================

(defn dacite-map
  "Create a dacite map from a sequence of [key-hash val-hash] pairs
   (refs already in store). Returns [store' hash]."
  [store pairs]
  (let [[hamt-store hamt-root]
        (reduce (fn [[s root] [kh vh]]
                  (let [k-hash (hash/typed-value-hash (get s kh))]
                    (hamt/assoc-val [s root] k-hash kh vh)))
                (let [[s root] (hamt/hamt)]
                  [(merge store s) root])
                pairs)
        ef (hamt/hamt-elements-fuse [hamt-store hamt-root])
        h (hash/node-hash :map ef)]
    [(assoc hamt-store h [:map {:root hamt-root :pairs (clojure.core/vec pairs)}]) h]))

(defn map-of
  "Create a dacite map from a Clojure map (auto-coerced keys and values).
   Returns [store' hash]."
  [store m]
  (let [[s pairs] (reduce (fn [[s pairs] [k v]]
                            (let [[s' kh] (coerce-and-store s k)
                                  [s'' vh] (coerce-and-store s' v)]
                              [s'' (conj pairs [kh vh])]))
                          [store []]
                          m)]
    (dacite-map s pairs)))

;; =============================================================================
;; Map construction (bang)
;; =============================================================================

(defn dacite-map!
  "Create a dacite map from [key-hash val-hash] pairs in *store*.
   Returns DaciteMap."
  [pairs]
  (let [store @*store*
        [store' h] (dacite-map store pairs)]
    (reset! *store* store')
    (->DaciteMap h)))

(defn map-of!
  "Create a dacite map from a Clojure map in *store*. Returns DaciteMap."
  [m]
  (let [pairs (mapv (fn [[k v]]
                      [(coerce-and-store! k) (coerce-and-store! v)])
                    m)]
    (dacite-map! pairs)))

;; =============================================================================
;; Map accessors (pure)
;; =============================================================================

(defn map-count
  "Get the entry count of a dacite map."
  [store h]
  (let [{:keys [root]} (second (get store h))]
    (hamt/hamt-count [store root])))

(defn map-get
  "Look up a value ref by key in a dacite map. Key is auto-coerced."
  [store h key]
  (let [{:keys [root]} (second (get store h))
        [s' kh] (coerce-and-store store key)
        k-hash (hash/typed-value-hash (get s' kh))]
    (hamt/get-val [store root] k-hash)))

(defn map-assoc
  "Associate a key-ref and val-ref in a dacite map. Returns [store' new-hash]."
  [store h key-ref val-ref]
  (let [{:keys [root pairs]} (second (get store h))
        k-hash (hash/typed-value-hash (get store key-ref))
        [s' new-root] (hamt/assoc-val [store root] k-hash key-ref val-ref)
        new-pairs (conj (clojure.core/vec (remove #(= key-ref (first %)) pairs))
                        [key-ref val-ref])
        ef (hamt/hamt-elements-fuse [s' new-root])
        mh (hash/node-hash :map ef)]
    [(assoc s' mh [:map {:root new-root :pairs new-pairs}]) mh]))

(defn map-dissoc
  "Remove a key from a dacite map. Key is auto-coerced. Returns [store' new-hash]."
  [store h key]
  (let [{:keys [root pairs]} (second (get store h))
        [s' kh] (coerce-and-store store key)
        k-hash (hash/typed-value-hash (get s' kh))
        [s'' new-root] (hamt/dissoc-val [store root] k-hash)
        new-pairs (clojure.core/vec (remove #(= kh (first %)) pairs))
        ef (hamt/hamt-elements-fuse [s'' new-root])
        mh (hash/node-hash :map ef)]
    [(assoc s'' mh [:map {:root new-root :pairs new-pairs}]) mh]))

(defn map-entries
  "Get all entries as a sequence of [key-ref val-ref] hash pairs."
  [store h]
  (let [{:keys [root]} (second (get store h))]
    (hamt/entries [store root])))

;; =============================================================================
;; Content equality
;; =============================================================================

(defn dacite=
  "Content equality: two hashes are equal if they're the same hash."
  [h1 h2]
  (= h1 h2))
