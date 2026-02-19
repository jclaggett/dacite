(ns dacite.core
  "Dacite: Content-addressed data with Clojure-native interfaces.

   Constructors create Dacite values backed by a content-addressed store.
   Values implement standard Clojure interfaces (count, nth, get, assoc, etc.)
   so application logic works identically whether the store is an in-memory atom,
   a disk-backed cache, or a distributed network.

     (d/i64 42)              => DaciteScalar
     (d/vec [1 2 3])         => DaciteVector
     (d/str \"hello\")        => DaciteString
     (d/hash-map \"a\" 1)     => DaciteMap

   Boundary crossing:
     (d/dac->clj v)          => plain Clojure data (recursive)
     (d/clj->dac data)       => Dacite values (recursive)

   Use `with-store` for isolated store contexts (testing, transactions).
   Otherwise the global store is used automatically."
  (:refer-clojure :exclude [str vec hash-map])
  (:require [dacite.hash :as hash]
            [dacite.finger-tree :as ft]
            [dacite.hamt :as hamt])
  (:import [clojure.lang IDeref IHashEq Counted Seqable ILookup
            IPersistentCollection Indexed IPersistentStack
            IPersistentVector Associative IFn Sequential
            IPersistentMap MapEquivalence]))

;; =============================================================================
;; Store management
;; =============================================================================

(def ^:dynamic *store*
  "Dynamic var holding the current store atom. Initialized with a global
   atom so constructors work without with-store."
  (atom {}))

(defmacro with-store
  "Execute body with an isolated store. Binds the symbol to the atom
   and sets *store*. Returns [final-store last-value]."
  [[sym init] & body]
  `(let [~sym (atom ~init)]
     (binding [*store* ~sym]
       (let [result# (do ~@body)]
         [@~sym result#]))))

(defn reset-store!
  "Reset the global store to empty. Useful for REPL/testing."
  []
  (reset! *store* {}))

(defn- store-put!
  "Add a typed value to *store*. Returns its hash."
  [value]
  (let [h (hash/typed-value-hash value)]
    (swap! *store* assoc h value)
    h))

(defn- store-merge!
  "Merge a map into *store*."
  [m]
  (swap! *store* merge m))

;; =============================================================================
;; Forward declarations & protocol
;; =============================================================================

(declare ->DaciteScalar ->DaciteVector ->DaciteMap ->DaciteString)
(declare wrap-hash coerce-and-store!)
(declare vector-conj-internal vector-refs-internal vector-count-internal)
(declare map-count-internal map-get-internal map-entries-internal)
(declare map-assoc-internal map-dissoc-internal)

(defprotocol IDaciteHash
  (dacite-hash [this] "Return the internal hash of this Dacite value."))

(defn- extract-hash
  "Extract hash from a Dacite type, or coerce and store a raw value."
  [x]
  (if (satisfies? IDaciteHash x)
    (dacite-hash x)
    (coerce-and-store! x)))

;; =============================================================================
;; DaciteScalar
;; =============================================================================

(deftype DaciteScalar [^:unsynchronized-mutable _hash]
  IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_]
    (let [[_type-kw data] (get @*store* _hash)]
      data))

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

  Object
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteScalar other)
         (= _hash (.-_hash ^DaciteScalar other))))
  (toString [_]
    (let [[type-kw data] (get @*store* _hash)]
      (pr-str [type-kw data])))

  IFn
  (invoke [this] (deref this)))

(defn scalar-type
  "Get the type keyword of a Dacite value."
  [x]
  (let [[type-kw _data] (get @*store* (dacite-hash x))]
    type-kw))

(defn scalar-hash
  "Get the raw hash of a Dacite value."
  [x]
  (dacite-hash x))

;; =============================================================================
;; DaciteString
;; =============================================================================

(deftype DaciteString [^:unsynchronized-mutable _hash]
  IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_]
    (let [[_type-kw data] (get @*store* _hash)]
      data))

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

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
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteString other)
         (= _hash (.-_hash ^DaciteString other))))
  (toString [_]
    (let [[_type-kw data] (get @*store* _hash)]
      (clojure.core/str data))))

;; =============================================================================
;; DaciteVector
;; =============================================================================

(deftype DaciteVector [^:unsynchronized-mutable _hash]
  IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_] _hash)

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

  Counted
  (count [_] (vector-count-internal _hash))

  Seqable
  (seq [this]
    (when (pos? (.count this))
      (map #(wrap-hash %) (vector-refs-internal _hash))))

  ILookup
  (valAt [this k] (.valAt this k nil))
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
    (let [vh (extract-hash val)]
      (->DaciteVector (vector-conj-internal _hash vh))))
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
  (invoke [this k] (.valAt ^ILookup this k))

  Comparable
  (compareTo [this other]
    (if (instance? DaciteVector other)
      (compare _hash (.-_hash ^DaciteVector other))
      (throw (ClassCastException.))))

  Iterable
  (iterator [this]
    (.iterator ^Iterable (or (seq this) ())))

  Object
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteVector other)
         (= _hash (.-_hash ^DaciteVector other))))
  (toString [this]
    (clojure.core/str (into [] (map deref) (seq this)))))

;; =============================================================================
;; DaciteMap
;; =============================================================================

(deftype DaciteMap [^:unsynchronized-mutable _hash]
  IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_] _hash)

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

  Counted
  (count [_] (map-count-internal _hash))

  Seqable
  (seq [_]
    (let [entries (map-entries-internal _hash)]
      (when (seq entries)
        (map (fn [[kh vh]]
               (clojure.lang.MapEntry/create (wrap-hash kh) (wrap-hash vh)))
             entries))))

  ILookup
  (valAt [this k] (.valAt this k nil))
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
  (containsKey [_ k] (some? (map-get-internal _hash k)))
  (assoc [_ k v] (->DaciteMap (map-assoc-internal _hash k v)))
  (entryAt [_ k]
    (let [result (map-get-internal _hash k)]
      (when result
        (clojure.lang.MapEntry/create (wrap-hash k) (wrap-hash result)))))

  IPersistentMap
  (assocEx [this k v]
    (if (.containsKey ^Associative this k)
      (throw (RuntimeException. "Key already present"))
      (.assoc ^Associative this k v)))
  (without [_ k] (->DaciteMap (map-dissoc-internal _hash k)))

  MapEquivalence

  IFn
  (invoke [this k] (.valAt ^ILookup this k))
  (invoke [this k not-found] (.valAt ^ILookup this k not-found))

  Iterable
  (iterator [this]
    (.iterator ^Iterable (or (seq this) ())))

  Object
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteMap other)
         (= _hash (.-_hash ^DaciteMap other))))
  (toString [this]
    (clojure.core/str (into {} (map (fn [[k v]] [@k @v])) (seq this)))))

;; =============================================================================
;; Hash wrapping
;; =============================================================================

(defn wrap-hash
  "Wrap a raw hash in the appropriate Dacite type."
  [h]
  (let [[type-kw _data] (get @*store* h)]
    (case type-kw
      :vector (->DaciteVector h)
      :map (->DaciteMap h)
      :string (->DaciteString h)
      (->DaciteScalar h))))

(defn unwrap-hash
  "Extract the raw hash from any Dacite type."
  [x]
  (if (satisfies? IDaciteHash x)
    (dacite-hash x)
    (throw (ex-info "Not a Dacite value" {:value x}))))

;; =============================================================================
;; Scalar constructors
;; =============================================================================

(defn- scalar [type-kw data]
  (->DaciteScalar (store-put! [type-kw data])))

(defn null   "Create a null value."                       []  (scalar :null nil))
(defn bool   "Create a boolean value."                    [b] (scalar :bool b))
(defn i8     "Create an i8 value."                        [n] (scalar :i8 (byte n)))
(defn i16    "Create an i16 value."                       [n] (scalar :i16 (short n)))
(defn i32    "Create an i32 value."                       [n] (scalar :i32 (int n)))
(defn i64    "Create an i64 value."                       [n] (scalar :i64 (long n)))
(defn u8  "Create a u8 value."  [n] {:pre [(<= 0 n 255)]}        (scalar :u8 n))
(defn u16 "Create a u16 value." [n] {:pre [(<= 0 n 65535)]}      (scalar :u16 n))
(defn u32 "Create a u32 value." [n] {:pre [(<= 0 n 4294967295)]} (scalar :u32 n))
(defn u64 "Create a u64 value." [n] {:pre [(<= 0 n)]}            (scalar :u64 n))

(defn u256
  "Create a u256 value (e.g. hash as data). Data must be 32-byte array."
  [^bytes data]
  {:pre [(= 32 (alength data))]}
  (scalar :u256 data))

(defn f32    "Create an f32 value."  [n] (scalar :f32 (float n)))
(defn f64    "Create an f64 value."  [n] (scalar :f64 (double n)))

(defn dacite-char
  "Create a char value."
  [c]
  {:pre [(char? c)]}
  (scalar :char c))

;; =============================================================================
;; String construction
;; =============================================================================

(defn str
  "Create a dacite string."
  [s]
  (let [v [:string s]
        h (hash/typed-value-hash v)]
    (swap! *store* assoc h v)
    (->DaciteString h)))

;; =============================================================================
;; Auto-coercion (internal)
;; =============================================================================

(defn- coerce-and-store! [x]
  (cond
    (nil? x)              (dacite-hash (null))
    (instance? Boolean x) (dacite-hash (bool x))
    (integer? x)          (dacite-hash (i64 x))
    (float? x)            (dacite-hash (f64 (double x)))
    (double? x)           (dacite-hash (f64 x))
    (char? x)             (dacite-hash (dacite-char x))
    (string? x)           (dacite-hash (str x))
    :else (throw (ex-info "Cannot coerce to dacite value" {:value x :type (type x)}))))

;; =============================================================================
;; Vector internals
;; =============================================================================

(defn- vector-count-internal [h]
  (clojure.core/count (:refs (second (get @*store* h)))))

(defn- vector-refs-internal [h]
  (:refs (second (get @*store* h))))

(defn- build-vector-from-refs! [refs]
  (let [[ft-store ft-root]
        (reduce (fn [[s root] ref] (ft/conj-right [s root] ref))
                (ft/finger-tree) refs)
        ef (ft/tree-elements-fuse [ft-store ft-root])
        h (hash/node-hash :vector ef)]
    (store-merge! ft-store)
    (swap! *store* assoc h [:vector {:root ft-root :refs (clojure.core/vec refs)}])
    h))

(defn- vector-conj-internal [vec-hash ref]
  (build-vector-from-refs! (conj (clojure.core/vec (vector-refs-internal vec-hash)) ref)))

;; =============================================================================
;; Vector construction
;; =============================================================================

(defn vec
  "Create a dacite vector from values (auto-coerced or Dacite types)."
  [values]
  (let [refs (mapv extract-hash values)]
    (->DaciteVector (build-vector-from-refs! refs))))

(defn vec-of-refs
  "Create a dacite vector from raw hashes (refs already in store)."
  [refs]
  (->DaciteVector (build-vector-from-refs! refs)))

;; =============================================================================
;; Map internals
;; =============================================================================

(defn- map-count-internal [h]
  (hamt/hamt-count [@*store* (:root (second (get @*store* h)))]))

(defn- map-get-internal [map-hash key]
  (let [{:keys [root]} (second (get @*store* map-hash))
        kh (extract-hash key)
        k-hash (hash/typed-value-hash (get @*store* kh))]
    (hamt/get-val [@*store* root] k-hash)))

(defn- map-entries-internal [h]
  (hamt/entries [@*store* (:root (second (get @*store* h)))]))

(defn- map-assoc-internal [map-hash k v]
  (let [{:keys [root pairs]} (second (get @*store* map-hash))
        kh (extract-hash k)
        vh (extract-hash v)
        k-hash (hash/typed-value-hash (get @*store* kh))
        [s' new-root] (hamt/assoc-val [@*store* root] k-hash kh vh)
        new-pairs (conj (clojure.core/vec (remove #(= kh (first %)) pairs)) [kh vh])
        ef (hamt/hamt-elements-fuse [s' new-root])
        h (hash/node-hash :map ef)]
    (store-merge! s')
    (swap! *store* assoc h [:map {:root new-root :pairs new-pairs}])
    h))

(defn- map-dissoc-internal [map-hash k]
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
;; Map construction
;; =============================================================================

(defn hash-map
  "Create a dacite map from key-value pairs (auto-coerced or Dacite types)."
  [& kvs]
  (let [pairs (partition 2 kvs)
        ref-pairs (mapv (fn [[k v]] [(extract-hash k) (extract-hash v)]) pairs)
        store @*store*
        [hamt-store hamt-root]
        (reduce (fn [[s root] [kh vh]]
                  (let [k-hash (hash/typed-value-hash (get s kh))]
                    (hamt/assoc-val [s root] k-hash kh vh)))
                (let [[s root] (hamt/hamt)] [(merge store s) root])
                ref-pairs)
        ef (hamt/hamt-elements-fuse [hamt-store hamt-root])
        h (hash/node-hash :map ef)]
    (reset! *store* (assoc hamt-store h [:map {:root hamt-root :pairs ref-pairs}]))
    (->DaciteMap h)))

;; =============================================================================
;; Boundary crossing: dac->clj and clj->dac
;; =============================================================================

(def ^:const default-max-nodes
  "Default maximum number of nodes dac->clj will materialize."
  10000)

(defn- dac->clj*
  "Internal recursive converter with node budget tracking."
  [x counter max-nodes]
  (when (> (vswap! counter inc) max-nodes)
    (throw (ex-info (clojure.core/str "dac->clj exceeded max-nodes limit of " max-nodes)
                    {:max-nodes max-nodes})))
  (cond
    (instance? DaciteScalar x) @x
    (instance? DaciteString x) @x
    (instance? DaciteVector x) (mapv #(dac->clj* % counter max-nodes) (seq x))
    (instance? DaciteMap x)    (into {} (map (fn [[k v]]
                                               [(dac->clj* k counter max-nodes)
                                                (dac->clj* v counter max-nodes)]))
                                     (seq x))
    :else x))

(defn dac->clj
  "Recursively convert a Dacite value to plain Clojure data.
   Scalars unwrap to their raw value, strings to String,
   vectors to persistent vectors, maps to persistent hash maps.

   Optional max-nodes parameter (default 10000) limits the number of
   nodes materialized. Throws ex-info if the limit is exceeded."
  ([x] (dac->clj x default-max-nodes))
  ([x max-nodes]
   (dac->clj* x (volatile! 0) max-nodes)))

(defn clj->dac
  "Recursively convert plain Clojure data to Dacite values.
   Vectors become DaciteVector, maps become DaciteMap,
   strings become DaciteString, scalars are auto-coerced.
   Uses *store*."
  [x]
  (cond
    (satisfies? IDaciteHash x) x
    (clojure.core/vector? x)   (vec-of-refs (mapv (comp dacite-hash clj->dac) x))
    (sequential? x)            (vec-of-refs (mapv (comp dacite-hash clj->dac) x))
    (map? x)                   (let [pairs (mapcat (fn [[k v]] [(clj->dac k) (clj->dac v)]) x)]
                                 (apply hash-map pairs))
    (string? x)                (str x)
    (nil? x)                   (null)
    (instance? Boolean x)      (bool x)
    (integer? x)               (i64 x)
    (float? x)                 (f64 (double x))
    (double? x)                (f64 x)
    (char? x)                  (dacite-char x)
    :else (throw (ex-info "Cannot convert to Dacite value" {:value x :type (type x)}))))
