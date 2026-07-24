(ns dacite.value.collections
  "Dacite collection values for the value layer: string, blob, vector,
   map, and set.

   Each collection is a store-aware wrapper of [store, hash] over an
   internal tree (finger tree for sequences, HAMT for maps/sets). All
   wrappers reach the store through their own store field rather than a
   global cache. Operations that produce a new collection persist their
   nodes into that same store and return a new wrapper, giving the
   transparent persistence of §3.1.

   A collection persists as [type-name {:root h :count n :size-bytes n}].
   Its value hash is fuse(type_hash, root.elements_fuse): the type tag
   fused with the shape-independent leaf fuse (§3.3).

   Portability: the value protocol, the tree operations, coercions, and
   the set algebra are all portable and go through dacite.host / the
   finger-tree / HAMT cores. The native clojure.lang.* collection
   interfaces (so plain get/conj/nth/count work on the JVM) are a JVM-only
   adapter guarded with reader conditionals. SCI hosts use the functional
   surface in dacite.value.api, which calls the portable operation fns
   defined here."
  (:refer-clojure :exclude [vector hash-map])
  (:require [dacite.store :as store]
            [dacite.value.types :as types]
            [dacite.value.scalar :as scalar]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]
            #?@(:bb [] :clj [[dacite.hash :as hash]
                             [dacite.value.render :as render]]))
  #?@(:bb []
      :clj [(:import [clojure.lang IHashEq Counted Seqable ILookup
                      IPersistentCollection Indexed IPersistentStack
                      IPersistentVector Associative IFn Sequential
                      IPersistentMap MapEquivalence])]))

;; =============================================================================
;; Forward declarations
;; =============================================================================

(declare ->DaciteString ->DaciteBlob ->DaciteVector ->DaciteMap ->DaciteSet)
(declare string string-with-store wrap-hash)
(declare store-seq-node! store-assoc-node! node-root realize-hashes)

;; =============================================================================
;; Store helpers (portable)
;; =============================================================================

(defn- node-root
  "Root tree hash of a stored collection node."
  [store h]
  (:root (types/entry-data (store/s-get store h))))

(defn- wrap-hash
  "Wrap a raw hash in the appropriate Dacite value (internal helper)."
  [store h]
  (types/wrap-entry (types/entry-type (store/s-get store h)) store h))

(defn- store-seq-node!
  "Persist a sequence collection node (string/blob/vector) over a finger
   tree root. Returns the collection's value hash."
  [store type-name root]
  (let [ef (ft/ft-elements-fuse store root)
        h (types/value-hash type-name ef)]
    (store/s-put store h [type-name {:root root
                                     :count (ft/ft-count store root)
                                     :size-bytes (ft/ft-size-bytes store root)}])
    h))

(defn- store-assoc-node!
  "Persist an associative collection node (map/set) over a HAMT root.
   Returns the collection's value hash."
  [store type-name root]
  (let [ef (hamt/hamt-elements-fuse store root)
        h (types/value-hash type-name ef)]
    (store/s-put store h [type-name {:root root
                                     :count (hamt/hamt-count store root)
                                     :size-bytes (hamt/hamt-size-bytes store root)}])
    h))

(defn- ft-build!
  "Build a finger tree from element hashes (left to right). Returns root."
  [store refs]
  (reduce (fn [root r] (ft/ft-conj-right store root r))
          (ft/ft-empty store)
          refs))

(defn- realize-hashes
  "Lazily realize a seq of element hashes to native values by wrapping each
   hash and recursively calling `realize`. The result is a lazy seq, so
   only the consumed portion is fetched from the store."
  [store hs]
  (map #(types/realize (wrap-hash store %)) hs))

;; =============================================================================
;; Portable operations (the basis of dacite.value.api; the JVM native
;; collection interfaces below mirror these with host-idiomatic exceptions).
;; =============================================================================

(defn coll-count
  "Element/entry count of any Dacite collection, O(1)."
  [store h]
  (:count (types/entry-data (store/s-get store h))))

(defn seq-vals
  "Wrapped elements of a sequence collection (string/blob/vector), or nil
   if empty."
  [store h]
  (when (pos? (coll-count store h))
    (map #(wrap-hash store %) (ft/ft-seq store (node-root store h)))))

(defn seq-nth
  "Wrapped element at index i of a sequence collection."
  [store h i]
  (wrap-hash store (ft/ft-nth store (node-root store h) i)))

(defn vec-conj
  "Append val to a vector, returning a new DaciteVector."
  [store h val]
  (let [vh (types/extract-hash store val)
        nr (ft/ft-conj-right store (node-root store h) vh)]
    (->DaciteVector store (store-seq-node! store "vector" nr))))

(defn vec-assoc
  "Assoc index k to v in a vector, returning a new DaciteVector."
  [store h k v]
  (when-not (integer? k)
    (throw (ex-info "Vector key must be an integer" {:key k})))
  (let [vh (types/extract-hash store v)
        refs (assoc (clojure.core/vec (ft/ft-seq store (node-root store h))) k vh)]
    (->DaciteVector store (store-seq-node! store "vector" (ft-build! store refs)))))

(defn vec-peek
  "Last element of a vector (wrapped), or nil if empty."
  [store h]
  (when-let [vh (ft/ft-last store (node-root store h))]
    (wrap-hash store vh)))

(defn vec-pop
  "Drop the last element of a vector, returning a new DaciteVector."
  [store h]
  (let [root (node-root store h)]
    (when (ft/ft-empty? store root)
      (throw (ex-info "Can't pop empty vector" {})))
    (->DaciteVector store (store-seq-node! store "vector" (ft/ft-butlast store root)))))

(defn seq-remove-nth
  "Remove element at index i from a sequence collection (vector/string/blob).
   Returns a new Dacite value of the same type."
  [store h i]
  (let [type-name (types/entry-type (store/s-get store h))
        nr (ft/ft-remove-nth store (node-root store h) i)
        new-h (store-seq-node! store type-name nr)]
    (types/wrap-entry type-name store new-h)))

(defn vec-remove-nth
  "Remove element at index i from a vector, returning a new DaciteVector."
  [store h i]
  (seq-remove-nth store h i))

(defn map-get
  "Look up key k in a map (wrapped value), or not-found."
  [store h k not-found]
  (let [kh (types/extract-hash store k)]
    (if-let [vh (hamt/hamt-get store (node-root store h) kh)]
      (wrap-hash store vh)
      not-found)))

(defn map-contains?
  [store h k]
  (some? (hamt/hamt-get store (node-root store h) (types/extract-hash store k))))

(defn map-assoc
  [store h k v]
  (let [kh (types/extract-hash store k)
        vh (types/extract-hash store v)
        nr (hamt/hamt-assoc store (node-root store h) kh kh vh)]
    (->DaciteMap store (store-assoc-node! store "map" nr))))

(defn map-dissoc
  [store h k]
  (let [kh (types/extract-hash store k)
        nr (hamt/hamt-dissoc store (node-root store h) kh)]
    (->DaciteMap store (store-assoc-node! store "map" nr))))

(defn map-entries
  "Seq of [wrapped-key wrapped-value] pairs, or nil if empty."
  [store h]
  (let [entries (hamt/hamt-entries store (node-root store h))]
    (when (clojure.core/seq entries)
      (map (fn [[kh vh]] [(wrap-hash store kh) (wrap-hash store vh)]) entries))))

(defn set-contains?
  [store h k]
  (some? (hamt/hamt-get store (node-root store h) (types/extract-hash store k))))

(defn set-conj
  [store h val]
  (let [vh (types/extract-hash store val)
        nr (hamt/hamt-assoc store (node-root store h) vh vh vh)]
    (->DaciteSet store (store-assoc-node! store "set" nr))))

(defn set-get
  "The wrapped member equal to k if present, else not-found."
  [store h k not-found]
  (let [kh (types/extract-hash store k)]
    (if (hamt/hamt-get store (node-root store h) kh)
      (wrap-hash store kh)
      not-found)))

(defn set-vals
  "Seq of wrapped set elements, or nil if empty."
  [store h]
  (let [entries (hamt/hamt-entries store (node-root store h))]
    (when (clojure.core/seq entries)
      (map (fn [[kh _]] (wrap-hash store kh)) entries))))

;; =============================================================================
;; DaciteString — finger tree of chars
;; =============================================================================

(deftype DaciteString [store _hash]
  types/IDaciteValue
  (dacite-hash [_] _hash)
  (dacite-store [_] store)
  (dacite-type [_] "string")
  (realize [_]
    (let [{:keys [root count]} (types/entry-data (store/s-get store _hash))]
      (when (pos? count)
        (realize-hashes store (ft/ft-seq store root)))))

  #?@(:bb []
      :clj
      [IHashEq
       (hasheq [_] (hash/hash->int _hash))

       Counted
       (count [_] (:count (types/entry-data (store/s-get store _hash))))

       Seqable
       (seq [this]
            (when (pos? (.count this))
              (map #(wrap-hash store %) (ft/ft-seq store (node-root store _hash)))))

       CharSequence
       (length [this] (.count this))
       (charAt [_ i]
               (types/entry-data (store/s-get store (ft/ft-nth store (node-root store _hash) i))))
       (subSequence [_ start end]
                    (apply clojure.core/str
                           (map #(types/entry-data (store/s-get store %))
                                (->> (ft/ft-seq store (node-root store _hash))
                                     (drop start)
                                     (take (- end start))))))

       Object
       (hashCode [_] (hash/hash->int _hash))
       (equals [_ other]
               (and (instance? DaciteString other)
                    (= _hash (.-_hash ^DaciteString other))))
       (toString [this] (render/bounded-to-string this))]))

;; =============================================================================
;; DaciteBlob — finger tree of bytes
;; =============================================================================

(deftype DaciteBlob [store _hash]
  types/IDaciteValue
  (dacite-hash [_] _hash)
  (dacite-store [_] store)
  (dacite-type [_] "blob")
  (realize [_]
    (let [{:keys [root count]} (types/entry-data (store/s-get store _hash))]
      (when (pos? count)
        (realize-hashes store (ft/ft-seq store root)))))

  #?@(:bb []
      :clj
      [IHashEq
       (hasheq [_] (hash/hash->int _hash))

       Counted
       (count [_] (:count (types/entry-data (store/s-get store _hash))))

       Seqable
       (seq [this]
            (when (pos? (.count this))
              (map #(wrap-hash store %) (ft/ft-seq store (node-root store _hash)))))

       Object
       (hashCode [_] (hash/hash->int _hash))
       (equals [_ other]
               (and (instance? DaciteBlob other)
                    (= _hash (.-_hash ^DaciteBlob other))))
       (toString [this] (render/bounded-to-string this))]))

;; =============================================================================
;; DaciteVector — finger tree of arbitrary values
;; =============================================================================

(deftype DaciteVector [store _hash]
  types/IDaciteValue
  (dacite-hash [_] _hash)
  (dacite-store [_] store)
  (dacite-type [_] "vector")
  (realize [_]
    (let [{:keys [root count]} (types/entry-data (store/s-get store _hash))]
      (when (pos? count)
        (realize-hashes store (ft/ft-seq store root)))))

  #?@(:bb []
      :clj
      [IHashEq
       (hasheq [_] (hash/hash->int _hash))

       Counted
       (count [_] (:count (types/entry-data (store/s-get store _hash))))

       Seqable
       (seq [this]
            (when (pos? (.count this))
              (map #(wrap-hash store %) (ft/ft-seq store (node-root store _hash)))))

       ILookup
       (valAt [this k] (.valAt this k nil))
       (valAt [this k not-found]
              (if (and (integer? k) (<= 0 k) (< k (.count this)))
                (wrap-hash store (ft/ft-nth store (node-root store _hash) k))
                not-found))

       Indexed
       (nth [_ i]
            (wrap-hash store (ft/ft-nth store (node-root store _hash) i)))
       (nth [this i not-found]
            (if (and (<= 0 i) (< i (.count this)))
              (wrap-hash store (ft/ft-nth store (node-root store _hash) i))
              not-found))

       IPersistentCollection
       (empty [_]
              (->DaciteVector store (store-seq-node! store "vector" (ft/ft-empty store))))
       (cons [_ val]
             (let [vh (types/extract-hash store val)
                   nr (ft/ft-conj-right store (node-root store _hash) vh)]
               (->DaciteVector store (store-seq-node! store "vector" nr))))
       (equiv [_ other]
              (and (instance? DaciteVector other)
                   (= _hash (.-_hash ^DaciteVector other))))

       IPersistentStack
       (peek [_]
             (when-let [vh (ft/ft-last store (node-root store _hash))]
               (wrap-hash store vh)))
       (pop [_]
            (let [root (node-root store _hash)]
              (when (ft/ft-empty? store root)
                (throw (IllegalStateException. "Can't pop empty vector")))
              (->DaciteVector store (store-seq-node! store "vector" (ft/ft-butlast store root)))))

       Associative
       (containsKey [this k]
                    (and (integer? k) (<= 0 k) (< k (.count this))))
       (assoc [this k v]
              (when-not (integer? k)
                (throw (IllegalArgumentException. "Key must be integer")))
              (let [vh (types/extract-hash store v)
                    refs (assoc (clojure.core/vec (ft/ft-seq store (node-root store _hash))) k vh)]
                (->DaciteVector store (store-seq-node! store "vector" (ft-build! store refs)))))
       (entryAt [this k]
                (when (and (integer? k) (<= 0 k) (< k (.count this)))
                  (clojure.lang.MapEntry/create k (.nth ^Indexed this k))))

       IPersistentVector
       (length [this] (.count ^Counted this))
       (assocN [this i val] (.assoc ^Associative this i val))

       Sequential

       IFn
       (invoke [this k] (.valAt ^ILookup this k))

       Iterable
       (iterator [this] (.iterator ^Iterable (or (seq this) ())))

       Object
       (hashCode [_] (hash/hash->int _hash))
       (equals [_ other]
               (and (instance? DaciteVector other)
                    (= _hash (.-_hash ^DaciteVector other))))
       (toString [this] (render/bounded-to-string this))]))

;; =============================================================================
;; DaciteMap — HAMT of key/value
;; =============================================================================

(deftype DaciteMap [store _hash]
  types/IDaciteValue
  (dacite-hash [_] _hash)
  (dacite-store [_] store)
  (dacite-type [_] "map")
  (realize [_]
    (let [entries (hamt/hamt-entries store (node-root store _hash))]
      (when (clojure.core/seq entries)
        (map (fn [[kh vh]]
               [(types/realize (wrap-hash store kh))
                (types/realize (wrap-hash store vh))])
             entries))))

  #?@(:bb []
      :clj
      [IHashEq
       (hasheq [_] (hash/hash->int _hash))

       Counted
       (count [_] (:count (types/entry-data (store/s-get store _hash))))

       Seqable
       (seq [_]
            (let [entries (hamt/hamt-entries store (node-root store _hash))]
              (when (clojure.core/seq entries)
                (map (fn [[kh vh]]
                       (clojure.lang.MapEntry/create (wrap-hash store kh) (wrap-hash store vh)))
                     entries))))

       ILookup
       (valAt [this k] (.valAt this k nil))
       (valAt [_ k not-found]
              (let [kh (types/extract-hash store k)]
                (if-let [vh (hamt/hamt-get store (node-root store _hash) kh)]
                  (wrap-hash store vh)
                  not-found)))

       IPersistentCollection
       (empty [_]
              (->DaciteMap store (store-assoc-node! store "map" (hamt/hamt-empty store))))
       (cons [this entry]
             (cond
               (instance? java.util.Map$Entry entry)
               (.assoc ^Associative this (.getKey ^java.util.Map$Entry entry)
                       (.getValue ^java.util.Map$Entry entry))
               (and (clojure.core/vector? entry) (= 2 (clojure.core/count entry)))
               (.assoc ^Associative this (first entry) (second entry))
               :else
               (throw (IllegalArgumentException. "Can only conj [k v] pairs or map entries"))))
       (equiv [_ other]
              (and (instance? DaciteMap other)
                   (= _hash (.-_hash ^DaciteMap other))))

       Associative
       (containsKey [_ k]
                    (some? (hamt/hamt-get store (node-root store _hash) (types/extract-hash store k))))
       (assoc [_ k v]
              (let [kh (types/extract-hash store k)
                    vh (types/extract-hash store v)
                    nr (hamt/hamt-assoc store (node-root store _hash) kh kh vh)]
                (->DaciteMap store (store-assoc-node! store "map" nr))))
       (entryAt [_ k]
                (let [kh (types/extract-hash store k)]
                  (when-let [vh (hamt/hamt-get store (node-root store _hash) kh)]
                    (clojure.lang.MapEntry/create (wrap-hash store kh) (wrap-hash store vh)))))

       IPersistentMap
       (assocEx [this k v]
                (if (.containsKey ^Associative this k)
                  (throw (RuntimeException. "Key already present"))
                  (.assoc ^Associative this k v)))
       (without [_ k]
                (let [kh (types/extract-hash store k)
                      nr (hamt/hamt-dissoc store (node-root store _hash) kh)]
                  (->DaciteMap store (store-assoc-node! store "map" nr))))

       MapEquivalence

       IFn
       (invoke [this k] (.valAt ^ILookup this k))
       (invoke [this k not-found] (.valAt ^ILookup this k not-found))

       Iterable
       (iterator [this] (.iterator ^Iterable (or (seq this) ())))

       Object
       (hashCode [_] (hash/hash->int _hash))
       (equals [_ other]
               (and (instance? DaciteMap other)
                    (= _hash (.-_hash ^DaciteMap other))))
       (toString [this] (render/bounded-to-string this))]))

;; =============================================================================
;; DaciteSet — HAMT-backed self-map
;; =============================================================================

(deftype DaciteSet [store _hash]
  types/IDaciteValue
  (dacite-hash [_] _hash)
  (dacite-store [_] store)
  (dacite-type [_] "set")
  (realize [_]
    (let [entries (hamt/hamt-entries store (node-root store _hash))]
      (when (clojure.core/seq entries)
        (map (fn [[kh _]] (types/realize (wrap-hash store kh))) entries))))

  #?@(:bb []
      :clj
      [IHashEq
       (hasheq [_] (hash/hash->int _hash))

       Counted
       (count [_] (:count (types/entry-data (store/s-get store _hash))))

       Seqable
       (seq [_]
            (let [entries (hamt/hamt-entries store (node-root store _hash))]
              (when (clojure.core/seq entries)
                (map (fn [[kh _]] (wrap-hash store kh)) entries))))

       ILookup
       (valAt [this k] (.valAt this k nil))
       (valAt [_ k not-found]
              (let [kh (types/extract-hash store k)]
                (if (hamt/hamt-get store (node-root store _hash) kh)
                  (wrap-hash store kh)
                  not-found)))

       IPersistentCollection
       (empty [_]
              (->DaciteSet store (store-assoc-node! store "set" (hamt/hamt-empty store))))
       (cons [_ val]
             (let [vh (types/extract-hash store val)
                   nr (hamt/hamt-assoc store (node-root store _hash) vh vh vh)]
               (->DaciteSet store (store-assoc-node! store "set" nr))))
       (equiv [_ other]
              (and (instance? DaciteSet other)
                   (= _hash (.-_hash ^DaciteSet other))))

       IFn
       (invoke [this k] (.valAt ^ILookup this k))
       (invoke [this k not-found] (.valAt ^ILookup this k not-found))

       Iterable
       (iterator [this] (.iterator ^Iterable (or (seq this) ())))

       Object
       (hashCode [_] (hash/hash->int _hash))
       (equals [_ other]
               (and (instance? DaciteSet other)
                    (= _hash (.-_hash ^DaciteSet other))))
       (toString [this] (render/bounded-to-string this))]))

(defmethod types/wrap-entry "vector"
  [_type-name store h]
  (->DaciteVector store h))

(defmethod types/wrap-entry "map"
  [_type-name store h]
  (->DaciteMap store h))

(defmethod types/wrap-entry "set"
  [_type-name store h]
  (->DaciteSet store h))

(defmethod types/wrap-entry "string"
  [_type-name store h]
  (->DaciteString store h))

(defmethod types/wrap-entry "blob"
  [_type-name store h]
  (->DaciteBlob store h))

;; =============================================================================
;; Constructors — explicit (-with-store) and implicit (*store*)
;; =============================================================================

(defn string-with-store
  "Create a Dacite string from a host string in an explicit store."
  [store s]
  (let [refs (mapv #(scalar/put-scalar! store "char" %) (seq s))]
    (->DaciteString store (store-seq-node! store "string" (ft-build! store refs)))))

(defmethod types/coerce-and-store! :string
  [store x]
  (types/dacite-hash (string-with-store store x)))

(defn string
  "Create a Dacite string using the current store."
  [s]
  (string-with-store store/*store* s))

(defn blob-with-store
  "Create a Dacite blob from a byte array in an explicit store."
  [store bs]
  (let [refs (mapv #(scalar/put-scalar! store "u8"
                                        #?(:clj (Byte/toUnsignedInt %)
                                           :cljs (bit-and % 0xFF)))
                   (seq bs))]
    (->DaciteBlob store (store-seq-node! store "blob" (ft-build! store refs)))))

(defn blob
  "Create a Dacite blob using the current store."
  [bs]
  (blob-with-store store/*store* bs))

(defn vec-of-refs-with-store
  "Create a Dacite vector from raw hashes already in an explicit store."
  [store refs]
  (->DaciteVector store (store-seq-node! store "vector" (ft-build! store refs))))

(defn vector-with-store
  "Create a Dacite vector from values in an explicit store."
  [store & values]
  (vec-of-refs-with-store store (mapv #(types/extract-hash store %) values)))

(defn vector
  "Create a Dacite vector using the current store."
  [& values]
  (apply vector-with-store store/*store* values))

(defn hash-map-with-store
  "Create a Dacite map from key/value pairs in an explicit store."
  [store & kvs]
  (let [root (reduce (fn [root [k v]]
                       (let [kh (types/extract-hash store k)
                             vh (types/extract-hash store v)]
                         (hamt/hamt-assoc store root kh kh vh)))
                     (hamt/hamt-empty store)
                     (partition 2 kvs))]
    (->DaciteMap store (store-assoc-node! store "map" root))))

(defn hash-map
  "Create a Dacite map using the current store."
  [& kvs]
  (apply hash-map-with-store store/*store* kvs))

(defn dacite-set-with-store
  "Create a Dacite set from elements in an explicit store."
  [store & xs]
  (let [root (reduce (fn [root x]
                       (let [vh (types/extract-hash store x)]
                         (hamt/hamt-assoc store root vh vh vh)))
                     (hamt/hamt-empty store)
                     xs)]
    (->DaciteSet store (store-assoc-node! store "set" root))))

(defn dacite-set
  "Create a Dacite set using the current store."
  [& xs]
  (apply dacite-set-with-store store/*store* xs))

(defmethod types/coerce-and-store! :blob
  [store x]
  (types/dacite-hash (blob-with-store store x)))

(defmethod types/coerce-and-store! :vector
  [store xs]
  (types/dacite-hash (apply vector-with-store store xs)))

(defmethod types/coerce-and-store! :sequential
  [store xs]
  (types/dacite-hash (apply vector-with-store store xs)))

(defmethod types/coerce-and-store! :set
  [store xs]
  (types/dacite-hash (apply dacite-set-with-store store xs)))

(defmethod types/coerce-and-store! :map
  [store m]
  (types/dacite-hash
   (apply hash-map-with-store store (mapcat (fn [[k v]] [k v]) m))))

;; =============================================================================
;; Set operations (§3.5) — derived from HAMT primitives + the negative sentinel
;; =============================================================================

(defn- neg-hash
  "Hash of the negative sentinel (ensured present in the store)."
  [store]
  (scalar/put-scalar! store "negative" nil))

(defn- negative-set?
  "Does this self-map root carry the negative sentinel?"
  [store root]
  (some? (hamt/hamt-get store root (neg-hash store))))

(defn- op-merge
  "Add B's elements to A (self-maps)."
  [store ra rb]
  (reduce (fn [r [kref _]] (hamt/hamt-assoc store r kref kref kref))
          ra
          (hamt/hamt-entries store rb)))

(defn- op-keep
  "Keep only A's elements that also appear in B."
  [store ra rb]
  (reduce (fn [r [kref _]]
            (if (hamt/hamt-get store rb kref)
              (hamt/hamt-assoc store r kref kref kref)
              r))
          (hamt/hamt-empty store)
          (hamt/hamt-entries store ra)))

(defn- op-remove
  "Remove from A every element that appears in B."
  [store ra rb]
  (reduce (fn [r [kref _]] (hamt/hamt-dissoc store r kref))
          ra
          (hamt/hamt-entries store rb)))

(defn set-member?
  "Membership test, aware of positive vs negative (cofinite) sets."
  [s x]
  (let [store (types/dacite-store s)
        root (node-root store (types/dacite-hash s))
        xh (types/extract-hash store x)
        present (some? (hamt/hamt-get store root xh))]
    (if (negative-set? store root)
      (and (not present) (not= xh (neg-hash store)))
      present)))

(defn set-complement
  "Complement a set by toggling the negative sentinel."
  [s]
  (let [store (types/dacite-store s)
        root (node-root store (types/dacite-hash s))
        nh (neg-hash store)
        nr (if (hamt/hamt-get store root nh)
             (hamt/hamt-dissoc store root nh)
             (hamt/hamt-assoc store root nh nh nh))]
    (->DaciteSet store (store-assoc-node! store "set" nr))))

(defn- set-binop
  "Dispatch a set binary operation on the pos/neg-ness of both operands.
   Each of pos-pos/pos-neg/neg-pos/neg-neg is a (store ra rb) -> root fn."
  [s1 s2 pos-pos pos-neg neg-pos neg-neg]
  (let [store (types/dacite-store s1)
        ra (node-root store (types/dacite-hash s1))
        rb (node-root store (types/dacite-hash s2))
        op (cond
             (and (not (negative-set? store ra)) (not (negative-set? store rb))) pos-pos
             (and (not (negative-set? store ra)) (negative-set? store rb))       pos-neg
             (and (negative-set? store ra) (not (negative-set? store rb)))       neg-pos
             :else                                                               neg-neg)]
    (->DaciteSet store (store-assoc-node! store "set" (op store ra rb)))))

(defn set-union [a b]
  (set-binop a b
             op-merge                            ; pos ∪ pos = merge(A,B)
             (fn [s ra rb] (op-remove s rb ra))  ; pos ∪ neg = remove(B,A)
             (fn [s ra rb] (op-remove s ra rb))  ; neg ∪ pos = remove(A,B)
             op-keep))                           ; neg ∪ neg = keep(A,B)

(defn set-intersect [a b]
  (set-binop a b
             op-keep                             ; pos ∩ pos = keep(A,B)
             (fn [s ra rb] (op-remove s ra rb))  ; pos ∩ neg = remove(A,B)
             (fn [s ra rb] (op-remove s rb ra))  ; neg ∩ pos = remove(B,A)
             op-merge))                          ; neg ∩ neg = merge(A,B)

(defn set-difference [a b]
  (set-binop a b
             (fn [s ra rb] (op-remove s ra rb))  ; pos \ pos = remove(A,B)
             op-keep                             ; pos \ neg = keep(A,B)
             op-merge                            ; neg \ pos = merge(A,B)
             (fn [s ra rb] (op-remove s rb ra)))) ; neg \ neg = remove(B,A)

;; =============================================================================
;; Sizes (multimethod) for collection types
;; =============================================================================

(defmethod types/dacite-size "string" [[_ data]] (:size-bytes data 0))
(defmethod types/dacite-size "blob" [[_ data]] (:size-bytes data 0))
(defmethod types/dacite-size "vector" [[_ data]] (:size-bytes data 0))
(defmethod types/dacite-size "map" [[_ data]] (:size-bytes data 0))
(defmethod types/dacite-size "set" [[_ data]] (:size-bytes data 0))

;; =============================================================================
;; REPL printing (*print-length* / *print-level*) — JVM only
;; =============================================================================

#?(:bb nil
   :clj
   (do
     (defmethod print-method DaciteString [v w] (render/print-dacite-value v w))
     (defmethod print-method DaciteBlob [v w] (render/print-dacite-value v w))
     (defmethod print-method DaciteVector [v w] (render/print-dacite-value v w))
     (defmethod print-method DaciteMap [v w] (render/print-dacite-value v w))
     (defmethod print-method DaciteSet [v w] (render/print-dacite-value v w))))
