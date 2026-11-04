(ns dacite.cache
  "A bounded least-recently-used cache, portable (JVM, babashka, nbb).

   Holds at most `limit` entries. A read or a write makes an entry the most
   recent; a write past the limit evicts the single least recent entry, so
   memory is bounded without the cliff of emptying the whole cache at once.

   The cache is a mutable cell, meant for memoizing pure functions: a hit
   returns what a miss would compute, so it can change speed but never a
   result.

   JVM: a `java.util.LinkedHashMap` in access order behind a lock (a read
   reorders it, so a read is a write); safe to share between threads. Two
   threads that miss the same key both compute it, and the last put wins, which
   is harmless for a pure function. nbb / ClojureScript: a JS `Map`, whose
   iteration order is insertion order; a hit deletes and re-inserts its key.
   One thread, so no lock. Keys on that host must be primitives (strings,
   numbers), because a JS `Map` compares objects by identity; a vector of
   BigInts would never hit."
  #?(:clj (:import (java.util LinkedHashMap Map$Entry))))

(defn lru
  "A new cache that keeps at most `limit` (a positive integer) entries."
  [limit]
  (when-not (and (integer? limit) (pos? limit))
    (throw (ex-info "cache limit must be a positive integer" {:limit limit})))
  {:limit limit
   :m #?(:clj (LinkedHashMap. 16 0.75 true)  ; true: access order
         :cljs (js/Map.))})

(defn lookup
  "The value cached under `k`, or `not-found` (nil if omitted). A hit makes `k`
   the most recent entry."
  ([c k] (lookup c k nil))
  ([c k not-found]
   (let [m (:m c)]
     #?(:clj (locking m
               (let [v (.get ^LinkedHashMap m k)]
                 (if (and (nil? v) (not (.containsKey ^LinkedHashMap m k)))
                   not-found
                   v)))
        :cljs (if (.has m k)
                (let [v (.get m k)]
                  (.delete m k)
                  (.set m k v)
                  v)
                not-found)))))

(defn put!
  "Cache `v` under `k` as the most recent entry, evicting the least recent
   entry if that makes the cache hold more than its limit. Returns `v`."
  [c k v]
  (let [m (:m c)
        limit (:limit c)]
    #?(:clj (locking m
              (.put ^LinkedHashMap m k v)
              (when (> (.size ^LinkedHashMap m) limit)
                (let [it (.iterator (.entrySet ^LinkedHashMap m))]
                  (.next it)
                  (.remove it))))
       :cljs (do (.delete m k)
                 (.set m k v)
                 (when (> (.-size m) limit)
                   (.delete m (.-value (.next (.keys m)))))))
    v))

(defn size
  "How many entries the cache holds now."
  [c]
  (let [m (:m c)]
    #?(:clj (locking m (.size ^LinkedHashMap m))
       :cljs (.-size m))))

(defn clear!
  "Drop every entry."
  [c]
  (let [m (:m c)]
    #?(:clj (locking m (.clear ^LinkedHashMap m))
       :cljs (.clear m))
    nil))

(defn lru-keys
  "The keys, least recent first. For tests and debugging."
  [c]
  (let [m (:m c)]
    #?(:clj (locking m (vec (.keySet ^LinkedHashMap m)))
       :cljs (vec (js/Array.from (.keys m))))))

(def ^:private miss
  "A sentinel no cached value is identical to (a keyword is not `identical?`
   to itself on ClojureScript)."
  #?(:clj (Object.) :cljs (js-obj)))

(defn memo
  "The cached value under `k`, or `(f)` computed and cached. Compute happens
   outside the lock. `f`'s result must not be nil."
  [c k f]
  (let [v (lookup c k miss)]
    (if (identical? v miss)
      (put! c k (f))
      v)))
