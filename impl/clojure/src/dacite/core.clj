(ns dacite.core
  "Dacite: Data citing with fused hashing.

   Two APIs for constructing Dacite values:

   1. Integrated (mirrors clojure.core naming):
      Uses a global store atom. Returns wrapped Dacite types that
      implement Clojure interfaces (count, nth, get, assoc, etc).

        (d/i64 42)              => DaciteScalar
        (d/vec [1 2 3])         => DaciteVector
        (d/str \"hello\")        => DaciteString
        (d/hash-map \"a\" 1)     => DaciteMap

   2. Pure functional:
      Takes a store map, returns [store' hash]. No side effects.

        (d/i64 store 42)        => [store' hash]
        (d/vec store [1 2 3])   => [store' hash]

   Arity dispatch: 1 arg = integrated, 2 args = pure.

   Use `with-store` for isolated store contexts (testing, transactions).
   Otherwise the global store is used automatically."
  (:refer-clojure :exclude [str vec hash-map])
  (:require [dacite.hash :as hash]
            [dacite.types :as types]
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
   atom so the integrated API works without with-store."
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
(declare wrap-hash coerce-and-store coerce-and-store!)
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
;; Scalar constructors — arity dispatch
;; =============================================================================

(defn- scalar-pure [store type-kw data]
  (let [v [type-kw data]
        h (hash/typed-value-hash v)]
    [(assoc store h v) h]))

(defn- scalar-integrated [type-kw data]
  (->DaciteScalar (store-put! [type-kw data])))

(defn null
  "([] => DaciteScalar) | ([store] => [store' hash])"
  ([] (scalar-integrated :null nil))
  ([store] (scalar-pure store :null nil)))

(defn bool
  "([b] => DaciteScalar) | ([store b] => [store' hash])"
  ([b] (scalar-integrated :bool b))
  ([store b] (scalar-pure store :bool b)))

(defn i8
  "([n] => DaciteScalar) | ([store n] => [store' hash])"
  ([n] (scalar-integrated :i8 (byte n)))
  ([store n] (scalar-pure store :i8 (byte n))))

(defn i16
  "([n] => DaciteScalar) | ([store n] => [store' hash])"
  ([n] (scalar-integrated :i16 (short n)))
  ([store n] (scalar-pure store :i16 (short n))))

(defn i32
  "([n] => DaciteScalar) | ([store n] => [store' hash])"
  ([n] (scalar-integrated :i32 (int n)))
  ([store n] (scalar-pure store :i32 (int n))))

(defn i64
  "([n] => DaciteScalar) | ([store n] => [store' hash])"
  ([n] (scalar-integrated :i64 (long n)))
  ([store n] (scalar-pure store :i64 (long n))))

(defn u8
  "([n] => DaciteScalar) | ([store n] => [store' hash])"
  ([n] {:pre [(<= 0 n 255)]} (scalar-integrated :u8 n))
  ([store n] {:pre [(<= 0 n 255)]} (scalar-pure store :u8 n)))

(defn u16
  "([n] => DaciteScalar) | ([store n] => [store' hash])"
  ([n] {:pre [(<= 0 n 65535)]} (scalar-integrated :u16 n))
  ([store n] {:pre [(<= 0 n 65535)]} (scalar-pure store :u16 n)))

(defn u32
  "([n] => DaciteScalar) | ([store n] => [store' hash])"
  ([n] {:pre [(<= 0 n 4294967295)]} (scalar-integrated :u32 n))
  ([store n] {:pre [(<= 0 n 4294967295)]} (scalar-pure store :u32 n)))

(defn u64
  "([n] => DaciteScalar) | ([store n] => [store' hash])"
  ([n] {:pre [(<= 0 n)]} (scalar-integrated :u64 n))
  ([store n] {:pre [(<= 0 n)]} (scalar-pure store :u64 n)))

(defn u256
  "([data] => DaciteScalar) | ([store data] => [store' hash])"
  ([^bytes data] {:pre [(= 32 (alength data))]} (scalar-integrated :u256 data))
  ([store ^bytes data] {:pre [(= 32 (alength data))]} (scalar-pure store :u256 data)))

(defn f32
  "([n] => DaciteScalar) | ([store n] => [store' hash])"
  ([n] (scalar-integrated :f32 (float n)))
  ([store n] (scalar-pure store :f32 (float n))))

(defn f64
  "([n] => DaciteScalar) | ([store n] => [store' hash])"
  ([n] (scalar-integrated :f64 (double n)))
  ([store n] (scalar-pure store :f64 (double n))))

(defn dacite-char
  "([c] => DaciteScalar) | ([store c] => [store' hash])"
  ([c] {:pre [(char? c)]} (scalar-integrated :char c))
  ([store c] {:pre [(char? c)]} (scalar-pure store :char c)))

;; =============================================================================
;; Value accessors (pure)
;; =============================================================================

(defn value-type [store h] (types/dacite-type (get store h)))
(defn value-data [store h] (types/dacite-data (get store h)))
(defn lookup [store h] (get store h))

;; =============================================================================
;; Hashing utilities
;; =============================================================================

(defn hash-hex [h] (hash/hash->hex h))

(defn hash-as-value [store raw-hash]
  (u256 store (hash/longs->bytes raw-hash)))

;; =============================================================================
;; String construction — arity dispatch
;; =============================================================================

(defn str
  "([s] => DaciteString) | ([store s] => [store' hash])"
  ([s]
   (let [v [:string s]
         h (hash/typed-value-hash v)]
     (swap! *store* assoc h v)
     (->DaciteString h)))
  ([store s]
   (let [v [:string s]
         h (hash/typed-value-hash v)]
     [(assoc store h v) h])))

(defn string-value [store h]
  (let [[type-kw data] (get store h)]
    (when (= :string type-kw) data)))

;; =============================================================================
;; Auto-coercion (internal)
;; =============================================================================

(defn- coerce-and-store [store x]
  (cond
    (nil? x)              (null store)
    (instance? Boolean x) (bool store x)
    (integer? x)          (i64 store x)
    (float? x)            (f64 store (double x))
    (double? x)           (f64 store x)
    (char? x)             (dacite-char store x)
    (string? x)           (str store x)
    :else (throw (ex-info "Cannot coerce to dacite value" {:value x :type (type x)}))))

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
;; Vector construction — arity dispatch
;; =============================================================================

(defn vec
  "([vals] => DaciteVector) | ([store vals] => [store' hash])"
  ([values]
   (let [refs (mapv extract-hash values)]
     (->DaciteVector (build-vector-from-refs! refs))))
  ([store values]
   (let [[s refs] (reduce (fn [[s refs] v]
                            (let [[s' h] (coerce-and-store s v)]
                              [s' (conj refs h)]))
                          [store []] values)
         [ft-store ft-root]
         (reduce (fn [[st root] ref] (ft/conj-right [st root] ref))
                 (let [[st root] (ft/finger-tree)] [(merge s st) root])
                 refs)
         ef (ft/tree-elements-fuse [ft-store ft-root])
         h (hash/node-hash :vector ef)]
     [(assoc ft-store h [:vector {:root ft-root :refs (clojure.core/vec refs)}]) h])))

(defn vec-of-refs
  "([refs] => DaciteVector) | ([store refs] => [store' hash])"
  ([refs] (->DaciteVector (build-vector-from-refs! refs)))
  ([store refs]
   (let [[ft-store ft-root]
         (reduce (fn [[s root] ref] (ft/conj-right [s root] ref))
                 (let [[s root] (ft/finger-tree)] [(merge store s) root])
                 refs)
         ef (ft/tree-elements-fuse [ft-store ft-root])
         h (hash/node-hash :vector ef)]
     [(assoc ft-store h [:vector {:root ft-root :refs (clojure.core/vec refs)}]) h])))

;; =============================================================================
;; Vector accessors (pure)
;; =============================================================================

(defn vector-count [store h]
  (clojure.core/count (:refs (second (get store h)))))

(defn vector-nth [store h n]
  (nth (:refs (second (get store h))) n))

(defn vector-refs [store h]
  (:refs (second (get store h))))

(defn vector-conj [store h ref]
  (let [{:keys [root refs]} (second (get store h))
        [s' new-root] (ft/conj-right [store root] ref)
        new-refs (conj refs ref)
        ef (ft/tree-elements-fuse [s' new-root])
        vh (hash/node-hash :vector ef)]
    [(assoc s' vh [:vector {:root new-root :refs new-refs}]) vh]))

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
;; Map construction — arity dispatch
;; =============================================================================

(defn hash-map
  "(k v ... => DaciteMap)"
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

(defn map-of
  "Pure: (store {k v ...}) => [store' hash]"
  [store m]
  (let [[s pairs] (reduce (fn [[s pairs] [k v]]
                            (let [[s' kh] (coerce-and-store s k)
                                  [s'' vh] (coerce-and-store s' v)]
                              [s'' (conj pairs [kh vh])]))
                          [store []] m)
        [hamt-store hamt-root]
        (reduce (fn [[st root] [kh vh]]
                  (let [k-hash (hash/typed-value-hash (get st kh))]
                    (hamt/assoc-val [st root] k-hash kh vh)))
                (let [[st root] (hamt/hamt)] [(merge s st) root])
                pairs)
        ef (hamt/hamt-elements-fuse [hamt-store hamt-root])
        h (hash/node-hash :map ef)]
    [(assoc hamt-store h [:map {:root hamt-root :pairs (clojure.core/vec pairs)}]) h]))

;; =============================================================================
;; Map accessors (pure)
;; =============================================================================

(defn map-count [store h]
  (hamt/hamt-count [store (:root (second (get store h)))]))

(defn map-get [store h key]
  (let [{:keys [root]} (second (get store h))
        [s' kh] (coerce-and-store store key)
        k-hash (hash/typed-value-hash (get s' kh))]
    (hamt/get-val [store root] k-hash)))

(defn map-assoc [store h key-ref val-ref]
  (let [{:keys [root pairs]} (second (get store h))
        k-hash (hash/typed-value-hash (get store key-ref))
        [s' new-root] (hamt/assoc-val [store root] k-hash key-ref val-ref)
        new-pairs (conj (clojure.core/vec (remove #(= key-ref (first %)) pairs))
                        [key-ref val-ref])
        ef (hamt/hamt-elements-fuse [s' new-root])
        mh (hash/node-hash :map ef)]
    [(assoc s' mh [:map {:root new-root :pairs new-pairs}]) mh]))

(defn map-dissoc [store h key]
  (let [{:keys [root pairs]} (second (get store h))
        [s' kh] (coerce-and-store store key)
        k-hash (hash/typed-value-hash (get s' kh))
        [s'' new-root] (hamt/dissoc-val [store root] k-hash)
        new-pairs (clojure.core/vec (remove #(= kh (first %)) pairs))
        ef (hamt/hamt-elements-fuse [s'' new-root])
        mh (hash/node-hash :map ef)]
    [(assoc s'' mh [:map {:root new-root :pairs new-pairs}]) mh]))

(defn map-entries [store h]
  (hamt/entries [store (:root (second (get store h)))]))

;; =============================================================================
;; Boundary crossing: dac->clj and clj->dac
;; =============================================================================

(defn dac->clj
  "Recursively convert a Dacite value to plain Clojure data.
   Scalars unwrap to their raw value, strings to String,
   vectors to persistent vectors, maps to persistent hash maps."
  [x]
  (cond
    (instance? DaciteScalar x) @x
    (instance? DaciteString x) @x
    (instance? DaciteVector x) (mapv dac->clj (seq x))
    (instance? DaciteMap x)    (into {} (map (fn [[k v]] [(dac->clj k) (dac->clj v)])) (seq x))
    :else x))

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
