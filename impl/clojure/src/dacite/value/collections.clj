(ns dacite.value.collections
  "Dacite collection types: string, blob, vector, and map.

   Each collection is a content-addressed wrapper around internal tree
   structures (finger trees for sequences, HAMT for maps). All implement
   standard Clojure interfaces so they work transparently with core
   functions like count, nth, get, assoc, seq, etc.

   Also provides wrap-hash / unwrap-hash for converting between raw
   hashes and their typed Dacite wrappers."
  (:refer-clojure :exclude [str vec hash-map])
  (:require [dacite.hash :as hash]
            [dacite.value.types :as types]
            [dacite.store :as store]
            [dacite.value.scalar :as scalar]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt])
  (:import [clojure.lang IDeref IHashEq Counted Seqable ILookup
            IPersistentCollection Indexed IPersistentStack
            IPersistentVector Associative IFn Sequential
            IPersistentMap MapEquivalence]))

;; =============================================================================
;; Forward declarations
;; =============================================================================

(declare ->DaciteString ->DaciteBlob ->DaciteVector ->DaciteMap ->DaciteSet)
(declare wrap-hash coerce-and-store! str)
(declare store-string! store-blob! store-vector! store-map!)
(declare vector-conj-internal vector-count-internal vector-nth-internal
         vector-seq-internal build-vector-from-refs!)
(declare map-count-internal map-get-internal map-entries-internal
         map-assoc-internal map-dissoc-internal)
(declare store-set!)

(defn- extract-hash
  "Extract hash from a Dacite type, or coerce and store a raw value."
  [x]
  (if (satisfies? types/IDaciteHash x)
    (types/dacite-hash x)
    (coerce-and-store! x)))

;; =============================================================================
;; DaciteString — finger tree of chars
;; =============================================================================

(deftype DaciteString [^:unsynchronized-mutable _hash]
  types/IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_]
    (let [{:keys [root]} (second (store/get-store _hash))
          store (store/snapshot-store)
          refs (ft/tree-seq-lazy [store root])]
      (apply clojure.core/str
             (map (fn [ref] (second (store/get-store ref))) refs))))

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

  Counted
  (count [_]
    (:count (second (store/get-store _hash))))

  Seqable
  (seq [this]
    (when (pos? (.count this))
      (let [{:keys [root]} (second (store/get-store _hash))
            refs (ft/tree-seq-lazy [(store/snapshot-store) root])]
        (map (fn [ref] (wrap-hash ref)) refs))))

  CharSequence
  (length [this] (.count this))
  (charAt [_ i]
    (let [{:keys [root]} (second (store/get-store _hash))
          ref (ft/tree-nth [(store/snapshot-store) root] i)]
      (second (store/get-store ref))))
  (subSequence [_ start end]
    (let [{:keys [root]} (second (store/get-store _hash))
          store (store/snapshot-store)
          refs (ft/tree-seq-lazy [store root])]
      (apply clojure.core/str
             (map (fn [ref] (second (store/get-store ref)))
                  (->> refs (drop start) (take (- end start)))))))

  Object
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteString other)
         (= _hash (.-_hash ^DaciteString other))))
  (toString [this] (deref this)))

;; =============================================================================
;; DaciteBlob — finger tree of bytes
;; =============================================================================

(deftype DaciteBlob [^:unsynchronized-mutable _hash]
  types/IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_]
    (let [{:keys [root]} (second (store/get-store _hash))
          refs (ft/tree-seq-lazy [(store/snapshot-store) root])]
      (byte-array (map (fn [ref] (second (store/get-store ref))) refs))))

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

  Counted
  (count [_]
    (:count (second (store/get-store _hash))))

  Seqable
  (seq [this]
    (when (pos? (.count this))
      (let [{:keys [root]} (second (store/get-store _hash))
            refs (ft/tree-seq-lazy [(store/snapshot-store) root])]
        (map (fn [ref] (wrap-hash ref)) refs))))

  Object
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteBlob other)
         (= _hash (.-_hash ^DaciteBlob other))))
  (toString [_]
    (let [{:keys [count]} (second (store/get-store _hash))]
      (clojure.core/str "<blob " count " bytes>"))))

;; =============================================================================
;; DaciteVector
;; =============================================================================

(deftype DaciteVector [^:unsynchronized-mutable _hash]
  types/IDaciteHash
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
    (let [{:keys [root]} (second (store/get-store _hash))
          store (store/snapshot-store)]
      (when-let [vh (ft/tree-last [store root])]
        (wrap-hash vh))))
  (pop [_]
    (let [{:keys [root]} (second (store/get-store _hash))
          store (store/snapshot-store)]
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
  types/IDaciteHash
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
;; DaciteSet — HAMT-backed self-map
;; =============================================================================

(deftype DaciteSet [^:unsynchronized-mutable _hash]
  types/IDaciteHash
  (dacite-hash [_] _hash)

  IDeref
  (deref [_]
    (let [{:keys [root]} (second (store/get-store _hash))
          entries (hamt/entries [(store/snapshot-store) root])]
      (into #{} (map (fn [[kh _vh]] (deref (wrap-hash kh)))) entries)))

  IHashEq
  (hasheq [_] (hash/hash->int _hash))

  Counted
  (count [_]
    (:count (second (store/get-store _hash))))

  Seqable
  (seq [_]
    (let [{:keys [root]} (second (store/get-store _hash))
          entries (hamt/entries [(store/snapshot-store) root])]
      (when (clojure.core/seq entries)
        (map (fn [[kh _vh]] (wrap-hash kh)) entries))))

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
      (->DaciteSet (store-set! s' h'))))
  (cons [_ val]
    (let [{:keys [root]} (second (store/get-store _hash))
          vh (extract-hash val)
          k-hash (types/typed-value-hash (store/get-store vh))
          [s' new-root] (hamt/assoc-val [(store/snapshot-store) root] k-hash vh vh)]
      (->DaciteSet (store-set! s' new-root))))
  (equiv [_ other]
    (and (instance? DaciteSet other)
         (= _hash (.-_hash ^DaciteSet other))))

  IFn
  (invoke [this k] (.valAt this k))
  (invoke [this k not-found] (.valAt this k not-found))

  Iterable
  (iterator [this]
    (.iterator ^Iterable (or (seq this) ())))

  Object
  (hashCode [_] (hash/hash->int _hash))
  (equals [_ other]
    (and (instance? DaciteSet other)
         (= _hash (.-_hash ^DaciteSet other))))
  (toString [this]
    (clojure.core/str (deref this))))

;; =============================================================================
;; Hash wrapping
;; =============================================================================

(defn wrap-hash
  "Wrap a raw hash in the appropriate Dacite type."
  [h]
  (let [[type-kw _data] (store/get-store h)]
    (case type-kw
      "vector" (->DaciteVector h)
      "map" (->DaciteMap h)
      "set" (->DaciteSet h)
      "string" (->DaciteString h)
      "blob" (->DaciteBlob h)
      (scalar/wrap-scalar h))))

(defn unwrap-hash
  "Extract the raw hash from any Dacite type."
  [x]
  (if (satisfies? types/IDaciteHash x)
    (types/dacite-hash x)
    (throw (ex-info "Not a Dacite value" {:value x}))))

;; =============================================================================
;; Auto-coercion (internal)
;; =============================================================================

(defn- coerce-and-store! [x]
  (cond
    (nil? x)              (types/dacite-hash (scalar/null))
    (instance? Boolean x) (types/dacite-hash (scalar/bool x))
    (integer? x)          (types/dacite-hash (scalar/i64 x))
    (float? x)            (types/dacite-hash (scalar/f64 (double x)))
    (double? x)           (types/dacite-hash (scalar/f64 x))
    (char? x)             (types/dacite-hash (scalar/dacite-char x))
    (string? x)           (types/dacite-hash (str x))
    :else (throw (ex-info "Cannot coerce to dacite value" {:value x :type (type x)}))))

;; =============================================================================
;; Vector internals
;; =============================================================================

(defn- vector-count-internal [h]
  (:count (second (store/get-store h))))

(defn- vector-nth-internal [h idx]
  (let [{:keys [root]} (second (store/get-store h))]
    (ft/tree-nth [(store/snapshot-store) root] idx)))

(defn- vector-seq-internal [h]
  (let [{:keys [root]} (second (store/get-store h))]
    (ft/tree-seq-lazy [(store/snapshot-store) root])))

(defn- store-string!
  "Merge ft-store into current store, compute size, store string node. Returns hash."
  [ft-store ft-root]
  (store/merge-store! ft-store)
  (let [store (store/snapshot-store)
        ef (ft/tree-elements-fuse [store ft-root])
        cnt (ft/tree-count [store ft-root])
        sb (ft/tree-size-bytes [store ft-root])
        h (types/node-hash "string" ef)]
    (store/put-store! h ["string" {:root ft-root
                                   :count cnt
                                   :size-bytes sb}])
    h))

(defn- store-blob!
  "Merge ft-store into current store, compute size, store blob node. Returns hash."
  [ft-store ft-root]
  (store/merge-store! ft-store)
  (let [store (store/snapshot-store)
        ef (ft/tree-elements-fuse [store ft-root])
        cnt (ft/tree-count [store ft-root])
        sb (ft/tree-size-bytes [store ft-root])
        h (types/node-hash "blob" ef)]
    (store/put-store! h ["blob" {:root ft-root
                                 :count cnt
                                 :size-bytes sb}])
    h))

(defn- store-vector!
  "Merge ft-store into current store, compute size, store vector node. Returns hash."
  [ft-store ft-root]
  (store/merge-store! ft-store)
  (let [store (store/snapshot-store)
        ef (ft/tree-elements-fuse [store ft-root])
        cnt (ft/tree-count [store ft-root])
        sb (ft/tree-size-bytes [store ft-root])
        h (types/node-hash "vector" ef)]
    (store/put-store! h ["vector" {:root ft-root
                                   :count cnt
                                   :size-bytes sb}])
    h))

(defn- build-vector-from-refs! [refs]
  (let [[init-store init-root] (ft/finger-tree)
        [ft-store ft-root]
        (reduce (fn [[s root] ref] (ft/conj-right [s root] ref))
                [(merge (store/snapshot-store) init-store) init-root]
                refs)]
    (store-vector! ft-store ft-root)))

(defn- vector-conj-internal [vec-hash ref]
  (let [{:keys [root]} (second (store/get-store vec-hash))
        store (store/snapshot-store)
        [ft-store ft-root] (ft/conj-right [store root] ref)]
    (store-vector! ft-store ft-root)))

;; =============================================================================
;; String construction
;; =============================================================================

(defn str
  "Create a dacite string from a Java String."
  [s]
  (let [char-refs (mapv (fn [c] (types/dacite-hash (scalar/dacite-char c))) (seq s))
        [init-s init-r] (ft/finger-tree)
        [ft-store ft-root]
        (reduce (fn [[st root] ref] (ft/conj-right [st root] ref))
                [(merge (store/snapshot-store) init-s) init-r]
                char-refs)]
    (->DaciteString (store-string! ft-store ft-root))))

(defn blob
  "Create a dacite blob from a byte array."
  [^bytes bs]
  (let [byte-refs (mapv (fn [b] (types/dacite-hash (scalar/u8 (Byte/toUnsignedInt b)))) (seq bs))
        [init-s init-r] (ft/finger-tree)
        [ft-store ft-root]
        (reduce (fn [[st root] ref] (ft/conj-right [st root] ref))
                [(merge (store/snapshot-store) init-s) init-r]
                byte-refs)]
    (->DaciteBlob (store-blob! ft-store ft-root))))

;; =============================================================================
;; Vector construction
;; =============================================================================

(defn vec-of-refs
  "Create a dacite vector from raw hashes (refs already in store)."
  [refs]
  (->DaciteVector (build-vector-from-refs! refs)))

(defn vec
  "Create a dacite vector from values (auto-coerced or Dacite types)."
  [values]
  (vec-of-refs (mapv extract-hash values)))

;; =============================================================================
;; Map internals
;; =============================================================================

(defn- map-count-internal [h]
  (:count (second (store/get-store h))))

(defn- map-get-internal [map-hash key]
  (let [{:keys [root]} (second (store/get-store map-hash))
        kh (extract-hash key)
        k-hash (types/typed-value-hash (store/get-store kh))]
    (hamt/get-val [(store/snapshot-store) root] k-hash)))

(defn- map-entries-internal [h]
  (hamt/entries [(store/snapshot-store) (:root (second (store/get-store h)))]))

(defn- store-map!
  "Merge hamt-store into current store, compute size, store map node. Returns hash."
  [hamt-store hamt-root]
  (store/merge-store! hamt-store)
  (let [store (store/snapshot-store)
        ef (hamt/hamt-elements-fuse [store hamt-root])
        cnt (hamt/hamt-count [store hamt-root])
        sb (hamt/hamt-size-bytes [store hamt-root])
        h (types/node-hash "map" ef)]
    (store/put-store! h ["map" {:root hamt-root
                                :count cnt
                                :size-bytes sb}])
    h))

(defn- map-assoc-internal [map-hash k v]
  (let [{:keys [root]} (second (store/get-store map-hash))
        kh (extract-hash k)
        vh (extract-hash v)
        k-hash (types/typed-value-hash (store/get-store kh))
        [s' new-root] (hamt/assoc-val [(store/snapshot-store) root] k-hash kh vh)]
    (store-map! s' new-root)))

(defn- map-dissoc-internal [map-hash k]
  (let [{:keys [root]} (second (store/get-store map-hash))
        kh (extract-hash k)
        k-hash (types/typed-value-hash (store/get-store kh))
        [s' new-root] (hamt/dissoc-val [(store/snapshot-store) root] k-hash)]
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
                  (let [k-hash (types/typed-value-hash (get s kh))]
                    (hamt/assoc-val [s root] k-hash kh vh)))
                (let [[s root] (hamt/hamt)] [(merge (store/snapshot-store) s) root])
                ref-pairs)]
    (->DaciteMap (store-map! hamt-store hamt-root))))

(defn- store-set!
  "Merge hamt-store into current store, compute size, store set node. Returns hash."
  [hamt-store hamt-root]
  (store/merge-store! hamt-store)
  (let [store (store/snapshot-store)
        ef (hamt/hamt-elements-fuse [store hamt-root])
        cnt (hamt/hamt-count [store hamt-root])
        sb (hamt/hamt-size-bytes [store hamt-root])
        h (types/node-hash "set" ef)]
    (store/put-store! h ["set" {:root hamt-root
                                :count cnt
                                :size-bytes sb}])
    h))

(defn dacite-set
  "Create a dacite set from elements (auto-coerced or Dacite types)."
  [& xs]
  (let [refs (mapv extract-hash xs)
        [hamt-store hamt-root]
        (reduce (fn [[s root] vh]
                  (let [k-hash (types/typed-value-hash (get s vh))]
                    (hamt/assoc-val [s root] k-hash vh vh)))
                (let [[s root] (hamt/hamt)] [(merge (store/snapshot-store) s) root])
                refs)]
    (->DaciteSet (store-set! hamt-store hamt-root))))

;; =============================================================================
;; dacite-size implementations for collection types
;; =============================================================================

(defmethod types/dacite-size "string" [[_ data]]
  (:size-bytes data 0))

(defmethod types/dacite-size "vector" [[_ data]]
  (:size-bytes data 0))

(defmethod types/dacite-size "map" [[_ data]]
  (:size-bytes data 0))

(defmethod types/dacite-size "blob" [[_ data]]
  (:size-bytes data 0))

(defmethod types/dacite-size "set" [[_ data]]
  (:size-bytes data 0))


