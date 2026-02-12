(ns dacite.cache-map
  "A Clojure map backed by a CacheManager.
   
   CacheMap implements standard Clojure map interfaces, allowing it to be used
   as the dacite-map in [dacite-map, root-hash] tuples. All values are
   content-addressed and immutable, so write-through caching is safe.
   
   Operations:
   - (get cache-map hash)       → looks up value from cache
   - (assoc cache-map hash val) → commits value to cache, returns same map
   - (merge cm1 cm2)            → returns cm1 (both share the same backing store)
   - (seq cache-map)            → not supported (cache may be unbounded)
   - (count cache-map)          → returns count of cached entries
   
   Usage:
     (def cm (cache-map (memory-cache-manager)))
     (def ft (finger-tree/from-seq cm [[:i64 1] [:i64 2]]))"
  (:require [dacite.cache :as cache])
  (:import [clojure.lang ILookup Associative IPersistentMap MapEntry Seqable
            IPersistentCollection Counted IObj IMeta]))

(deftype CacheMap [manager meta-map]
  ILookup
  (valAt [_ k]
    (cache/lookup manager k))
  (valAt [_ k not-found]
    (or (cache/lookup manager k) not-found))

  Associative
  (containsKey [_ k]
    (some? (cache/lookup manager k)))
  (entryAt [_ k]
    (when-let [v (cache/lookup manager k)]
      (MapEntry/create k v)))
  (assoc [this k v]
    (cache/store! manager k v)
    this)

  IPersistentMap
  (assocEx [_this _k _v]
    (throw (UnsupportedOperationException. "CacheMap does not support assocEx")))
  (without [_this _k]
    (throw (UnsupportedOperationException. "CacheMap does not support dissoc")))

  IPersistentCollection
  (cons [this o]
    (cond
      (instance? java.util.Map$Entry o)
      (let [e ^java.util.Map$Entry o]
        (.assoc this (.getKey e) (.getValue e)))

      (vector? o)
      (.assoc this (nth o 0) (nth o 1))

      ;; merge passes another map — if same backing store, no-op
      (instance? CacheMap o)
      this

      ;; For other IPersistentMap, iterate entries
      (instance? java.util.Map o)
      (reduce (fn [m e]
                (let [e ^java.util.Map$Entry e]
                  (.assoc ^CacheMap m (.getKey e) (.getValue e))))
              this o)

      :else this))
  (empty [_]
    (CacheMap. manager meta-map))
  (equiv [this other]
    (identical? this other))

  Seqable
  (seq [_]
    ;; Return nil - CacheMap doesn't support enumeration
    ;; persist! is no longer needed with write-through
    nil)

  Counted
  (count [_]
    (if-let [stats (cache/stats manager)]
      (:count stats)
      0))

  IMeta
  (meta [_] meta-map)

  IObj
  (withMeta [_ m]
    (CacheMap. m m))

  Iterable
  (iterator [_]
    (.iterator ^Iterable (or (seq nil) [])))

  Object
  (toString [_]
    (str "#<CacheMap backed by " (type manager) ">")))

(defn cache-map
  "Create a CacheMap backed by a CacheManager.
   
   The returned map can be used as the dacite-map in [dacite-map, root-hash]
   tuples. Values are committed to the cache on assoc and retrieved on get."
  [manager]
  (CacheMap. manager nil))

(defn cache-map?
  "Returns true if x is a CacheMap."
  [x]
  (instance? CacheMap x))

(defn manager
  "Get the underlying CacheManager from a CacheMap."
  [^CacheMap cm]
  (.-manager cm))
