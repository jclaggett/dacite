(ns dacite.core
  "Dacite: Content-addressed data with Clojure-native interfaces.

   Constructors create Dacite values backed by a content-addressed store.
   Values implement standard Clojure interfaces (count, nth, get, assoc, etc.)
   so application logic works identically whether the store is an in-memory atom,
   a disk-backed cache, or a distributed network.

     (d/i64 42)              => DaciteScalar
     (d/vec [1 2 3])         => DaciteVector
     (d/str \"hello\")        => DaciteString
     (d/blob bytes)          => DaciteBlob
     (d/hash-map \"a\" 1)     => DaciteMap

   Boundary crossing:
     (d/dac->clj v)          => plain Clojure data (recursive)
     (d/clj->dac data)       => Dacite values (recursive)

   Use `with-store` for isolated store contexts (testing, transactions).
   Otherwise the global store is used automatically."
  (:refer-clojure :exclude [str vec hash-map])
  (:require [dacite.hash :as hash]
            [dacite.types :as types]
            [dacite.store :as store]
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
  "Dynamic var holding the current IStore. Initialized with a global
   in-memory store so constructors work without with-store."
  (store/mem-store))

(defmacro with-store
  "Execute body with an isolated store. init can be an IStore or a map
   (which will be wrapped in a mem-store). Returns [snapshot last-value]."
  [[sym init] & body]
  `(let [~sym (let [i# ~init]
                (if (satisfies? store/IStore i#)
                  i#
                  (store/mem-store i#)))]
     (binding [*store* ~sym]
       (let [result# (do ~@body)]
         [(store/s-snapshot *store*) result#]))))

(defn reset-store!
  "Reset the global store to empty. Useful for REPL/testing."
  []
  (store/s-reset *store*))

(defn set-store!
  "Replace the global store with a new IStore implementation."
  [new-store]
  (alter-var-root #'*store* (constantly new-store)))

(defn- s-get
  "Get a value from *store* by hash."
  [h]
  (store/s-get *store* h))

(defn- s-put!
  "Store a typed value in *store*. Returns its hash."
  [value]
  (let [h (hash/typed-value-hash value)]
    (store/s-put *store* h value)
    h))

(defn- s-merge!
  "Merge a map of {hash value} pairs into *store*."
  [m]
  (store/s-merge *store* m))

(defn- s-snapshot
  "Get a plain map snapshot of *store* for bulk operations."
  []
  (store/s-snapshot *store*))

;; =============================================================================
;; Forward declarations & protocol
;; =============================================================================

(declare ->DaciteScalar ->DaciteVector ->DaciteMap ->DaciteString ->DaciteBlob)
(declare wrap-hash coerce-and-store!)
(declare store-string! store-blob! store-vector! store-map!)
(declare vector-conj-internal vector-count-internal vector-nth-internal vector-seq-internal
         build-vector-from-refs!)
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
    (let [[_type-kw data] (s-get _hash)]
      data))

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

  Object
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteScalar other)
         (= _hash (.-_hash ^DaciteScalar other))))
  (toString [_]
    (let [[type-kw data] (s-get _hash)]
      (pr-str [type-kw data])))

  IFn
  (invoke [this] (deref this)))

(defn scalar-type
  "Get the type name of a Dacite value."
  [x]
  (let [[type-kw _data] (s-get (dacite-hash x))]
    type-kw))

(defn scalar-hash
  "Get the raw hash of a Dacite value."
  [x]
  (dacite-hash x))

(defn size-bytes
  "Get the total size in bytes of a Dacite value (O(1) for collections)."
  [x]
  (let [v (s-get (dacite-hash x))]
    (types/dacite-size v)))

;; =============================================================================
;; DaciteString — finger tree of chars, parallel to DaciteVector
;; =============================================================================

(deftype DaciteString [^:unsynchronized-mutable _hash]
  IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_]
    (let [{:keys [root]} (second (s-get _hash))
          store (s-snapshot)
          refs (ft/tree-seq-lazy [store root])]
      (apply clojure.core/str
             (map (fn [ref] (second (s-get ref))) refs))))

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

  Counted
  (count [_]
    (let [{:keys [root]} (second (s-get _hash))]
      (ft/tree-count [(s-snapshot) root])))

  Seqable
  (seq [this]
    (when (pos? (.count this))
      (let [{:keys [root]} (second (s-get _hash))
            refs (ft/tree-seq-lazy [(s-snapshot) root])]
        (map (fn [ref] (wrap-hash ref)) refs))))

  CharSequence
  (length [this] (.count this))
  (charAt [_ i]
    (let [{:keys [root]} (second (s-get _hash))
          ref (ft/tree-nth [(s-snapshot) root] i)]
      (second (s-get ref))))
  (subSequence [_ start end]
    (let [{:keys [root]} (second (s-get _hash))
          store (s-snapshot)
          refs (ft/tree-seq-lazy [store root])]
      (apply clojure.core/str
             (map (fn [ref] (second (s-get ref)))
                  (->> refs (drop start) (take (- end start)))))))

  Object
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteString other)
         (= _hash (.-_hash ^DaciteString other))))
  (toString [this] (deref this)))

;; =============================================================================
;; DaciteBlob — finger tree of bytes, parallel to DaciteString
;; =============================================================================

(deftype DaciteBlob [^:unsynchronized-mutable _hash]
  IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_]
    (let [{:keys [root]} (second (s-get _hash))
          refs (ft/tree-seq-lazy [(s-snapshot) root])]
      (byte-array (map (fn [ref] (second (s-get ref))) refs))))

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

  Counted
  (count [_]
    (let [{:keys [root]} (second (s-get _hash))]
      (ft/tree-count [(s-snapshot) root])))

  Seqable
  (seq [this]
    (when (pos? (.count this))
      (let [{:keys [root]} (second (s-get _hash))
            refs (ft/tree-seq-lazy [(s-snapshot) root])]
        (map (fn [ref] (wrap-hash ref)) refs))))

  Object
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteBlob other)
         (= _hash (.-_hash ^DaciteBlob other))))
  (toString [_]
    (let [{:keys [root]} (second (s-get _hash))
          cnt (ft/tree-count [(s-snapshot) root])]
      (clojure.core/str "<blob " cnt " bytes>"))))

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
      (map #(wrap-hash %) (vector-seq-internal _hash))))

  ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [this k not-found]
    (if (and (integer? k) (<= 0 k) (< k (.count this)))
      (wrap-hash (vector-nth-internal _hash k))
      not-found))

  Indexed
  (nth [this i]
    (wrap-hash (vector-nth-internal _hash i)))
  (nth [this i not-found]
    (if (and (<= 0 i) (< i (.count this)))
      (wrap-hash (vector-nth-internal _hash i))
      not-found))

  IPersistentCollection
  (empty [_]
    (let [[s' h] (ft/finger-tree)]
      (->DaciteVector (store-vector! s' h))))
  (cons [this val]
    (let [vh (extract-hash val)]
      (->DaciteVector (vector-conj-internal _hash vh))))
  (equiv [_ other]
    (and (instance? DaciteVector other)
         (= _hash (.-_hash ^DaciteVector other))))

  IPersistentStack
  (peek [this]
    (let [{:keys [root]} (second (s-get _hash))
          store (s-snapshot)]
      (when-let [vh (ft/tree-last [store root])]
        (wrap-hash vh))))
  (pop [_]
    (let [{:keys [root]} (second (s-get _hash))
          store (s-snapshot)]
      (when (ft/tree-empty? [store root])
        (throw (IllegalStateException. "Can't pop empty vector")))
      (let [[ft-store ft-root] (ft/tree-butlast [store root])]
        (->DaciteVector (store-vector! ft-store ft-root)))))

  Associative
  (containsKey [this k]
    (and (integer? k) (<= 0 k) (< k (.count this))))
  (assoc [this k v]
    (when-not (integer? k)
      (throw (IllegalArgumentException. "Key must be integer")))
    (let [vh (extract-hash v)
          refs (vector-seq-internal _hash)
          new-refs (assoc (clojure.core/vec refs) k vh)]
      (->DaciteVector (build-vector-from-refs! new-refs))))
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
    (let [[s' h'] (hamt/hamt)]
      (->DaciteMap (store-map! s' h'))))
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
  (let [[type-kw _data] (s-get h)]
    (case type-kw
      "vector" (->DaciteVector h)
      "map" (->DaciteMap h)
      "string" (->DaciteString h)
      "blob" (->DaciteBlob h)
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
  (->DaciteScalar (s-put! [type-kw data])))

(defn null "Create a null value."    []  (scalar "null" nil))
(defn bool "Create a boolean value." [b] (scalar "bool" b))
(defn i8   "Create an i8 value."     [n] (scalar "i8" (byte n)))
(defn i16  "Create an i16 value."    [n] (scalar "i16" (short n)))
(defn i32  "Create an i32 value."    [n] (scalar "i32" (int n)))
(defn i64  "Create an i64 value."    [n] (scalar "i64" (long n)))
(defn u8   "Create a u8 value."      [n] {:pre [(<= 0 n 255)]}        (scalar "u8" n))
(defn u16  "Create a u16 value."     [n] {:pre [(<= 0 n 65535)]}      (scalar "u16" n))
(defn u32  "Create a u32 value."     [n] {:pre [(<= 0 n 4294967295)]} (scalar "u32" n))
(defn u64  "Create a u64 value."     [n] {:pre [(<= 0 n)]}            (scalar "u64" n))

(defn u256
  "Create a u256 value (e.g. hash as data). Data must be 32-byte array."
  [^bytes data]
  {:pre [(= 32 (alength data))]}
  (scalar "u256" data))

(defn f32  "Create an f32 value."    [n] (scalar "f32" (float n)))
(defn f64  "Create an f64 value."    [n] (scalar "f64" (double n)))

(defn dacite-char
  "Create a char value."
  [c]
  {:pre [(char? c)]}
  (scalar "char" c))

;; =============================================================================
;; String construction
;; =============================================================================

(defn str
  "Create a dacite string from a Java String."
  [s]
  (let [char-refs (mapv (fn [c] (dacite-hash (dacite-char c))) (seq s))
        [init-s init-r] (ft/finger-tree)
        [ft-store ft-root]
        (reduce (fn [[st root] ref] (ft/conj-right [st root] ref))
                [(merge (s-snapshot) init-s) init-r]
                char-refs)]
    (->DaciteString (store-string! ft-store ft-root))))

(defn blob
  "Create a dacite blob from a byte array."
  [^bytes bs]
  (let [byte-refs (mapv (fn [b] (dacite-hash (u8 (Byte/toUnsignedInt b)))) (seq bs))
        [init-s init-r] (ft/finger-tree)
        [ft-store ft-root]
        (reduce (fn [[st root] ref] (ft/conj-right [st root] ref))
                [(merge (s-snapshot) init-s) init-r]
                byte-refs)]
    (->DaciteBlob (store-blob! ft-store ft-root))))

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
  (let [{:keys [root]} (second (s-get h))]
    (ft/tree-count [(s-snapshot) root])))

(defn- vector-nth-internal [h idx]
  (let [{:keys [root]} (second (s-get h))]
    (ft/tree-nth [(s-snapshot) root] idx)))

(defn- vector-seq-internal [h]
  (let [{:keys [root]} (second (s-get h))]
    (ft/tree-seq-lazy [(s-snapshot) root])))

(defn- store-string!
  "Merge ft-store into *store*, compute size, store string node. Returns hash."
  [ft-store ft-root]
  (s-merge! ft-store)
  (let [store (s-snapshot)
        ef (ft/tree-elements-fuse [store ft-root])
        sb (ft/tree-size-bytes [store ft-root])
        h (hash/node-hash "string" ef)]
    (store/s-put *store* h ["string" {:root ft-root
                                      :size-bytes sb}])
    h))

(defn- store-blob!
  "Merge ft-store into *store*, compute size, store blob node. Returns hash."
  [ft-store ft-root]
  (s-merge! ft-store)
  (let [store (s-snapshot)
        ef (ft/tree-elements-fuse [store ft-root])
        sb (ft/tree-size-bytes [store ft-root])
        h (hash/node-hash "blob" ef)]
    (store/s-put *store* h ["blob" {:root ft-root
                                    :size-bytes sb}])
    h))

(defn- store-vector!
  "Merge ft-store into *store*, compute size, store vector node. Returns hash."
  [ft-store ft-root]
  (s-merge! ft-store)
  (let [store (s-snapshot)
        ef (ft/tree-elements-fuse [store ft-root])
        sb (ft/tree-size-bytes [store ft-root])
        h (hash/node-hash "vector" ef)]
    (store/s-put *store* h ["vector" {:root ft-root
                                      :size-bytes sb}])
    h))

(defn- build-vector-from-refs! [refs]
  (let [[init-store init-root] (ft/finger-tree)
        [ft-store ft-root]
        (reduce (fn [[s root] ref] (ft/conj-right [s root] ref))
                [(merge (s-snapshot) init-store) init-root]
                refs)]
    (store-vector! ft-store ft-root)))

(defn- vector-conj-internal [vec-hash ref]
  (let [{:keys [root]} (second (s-get vec-hash))
        store (s-snapshot)
        [ft-store ft-root] (ft/conj-right [store root] ref)]
    (store-vector! ft-store ft-root)))

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
  (hamt/hamt-count [(s-snapshot) (:root (second (s-get h)))]))

(defn- map-get-internal [map-hash key]
  (let [{:keys [root]} (second (s-get map-hash))
        kh (extract-hash key)
        k-hash (hash/typed-value-hash (s-get kh))]
    (hamt/get-val [(s-snapshot) root] k-hash)))

(defn- map-entries-internal [h]
  (hamt/entries [(s-snapshot) (:root (second (s-get h)))]))

(defn- store-map!
  "Merge hamt-store into *store*, compute size, store map node. Returns hash."
  [hamt-store hamt-root]
  (s-merge! hamt-store)
  (let [store (s-snapshot)
        ef (hamt/hamt-elements-fuse [store hamt-root])
        sb (hamt/hamt-size-bytes [store hamt-root])
        h (hash/node-hash "map" ef)]
    (store/s-put *store* h ["map" {:root hamt-root
                                   :size-bytes sb}])
    h))

(defn- map-assoc-internal [map-hash k v]
  (let [{:keys [root]} (second (s-get map-hash))
        kh (extract-hash k)
        vh (extract-hash v)
        k-hash (hash/typed-value-hash (s-get kh))
        [s' new-root] (hamt/assoc-val [(s-snapshot) root] k-hash kh vh)]
    (store-map! s' new-root)))

(defn- map-dissoc-internal [map-hash k]
  (let [{:keys [root]} (second (s-get map-hash))
        kh (extract-hash k)
        k-hash (hash/typed-value-hash (s-get kh))
        [s' new-root] (hamt/dissoc-val [(s-snapshot) root] k-hash)]
    (store-map! s' new-root)))

;; =============================================================================
;; Map construction
;; =============================================================================

(defn hash-map
  "Create a dacite map from key-value pairs (auto-coerced or Dacite types)."
  [& kvs]
  (let [pairs (partition 2 kvs)
        ref-pairs (mapv (fn [[k v]] [(extract-hash k) (extract-hash v)]) pairs)
        [hamt-store hamt-root]
        (reduce (fn [[s root] [kh vh]]
                  (let [k-hash (hash/typed-value-hash (get s kh))]
                    (hamt/assoc-val [s root] k-hash kh vh)))
                (let [[s root] (hamt/hamt)] [(merge (s-snapshot) s) root])
                ref-pairs)]
    (->DaciteMap (store-map! hamt-store hamt-root))))

;; =============================================================================
;; Boundary crossing: dac->clj and clj->dac
;; =============================================================================

(def ^:const default-max-bytes
  "Default maximum byte size dac->clj will materialize (1 MB)."
  1048576)

(defn- dac->clj-unsafe
  "Internal recursive converter (no size check)."
  [x]
  (cond
    (instance? DaciteScalar x) @x
    (instance? DaciteString x) @x
    (instance? DaciteBlob x)   @x
    (instance? DaciteVector x) (mapv dac->clj-unsafe (seq x))
    (instance? DaciteMap x)    (into {} (map (fn [[k v]]
                                               [(dac->clj-unsafe k)
                                                (dac->clj-unsafe v)]))
                                     (seq x))
    :else x))

(defn dac->clj
  "Recursively convert a Dacite value to plain Clojure data.
   Scalars unwrap to their raw value, strings to String,
   vectors to persistent vectors, maps to persistent hash maps.

   Optional max-bytes parameter (default 1 MB) limits the total byte
   size that will be materialized. Checked upfront via O(1) size-bytes.
   Throws ex-info if the value exceeds the limit."
  ([x] (dac->clj x default-max-bytes))
  ([x max-bytes]
   (when (satisfies? IDaciteHash x)
     (let [sb (size-bytes x)]
       (when (> sb max-bytes)
         (throw (ex-info (clojure.core/str "dac->clj: value size " sb
                                           " bytes exceeds limit of " max-bytes " bytes")
                         {:size-bytes sb :max-bytes max-bytes})))))
   (dac->clj-unsafe x)))

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
    (bytes? x)                 (blob x)
    (string? x)                (str x)
    (nil? x)                   (null)
    (instance? Boolean x)      (bool x)
    (integer? x)               (i64 x)
    (float? x)                 (f64 (double x))
    (double? x)                (f64 x)
    (char? x)                  (dacite-char x)
    :else (throw (ex-info "Cannot convert to Dacite value" {:value x :type (type x)}))))
