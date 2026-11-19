(ns dacite.store.lru
  "Bounded in-memory content store with LRU eviction.

   The map and the recency order use `dacite.store/mkey`, the same key
   `MemStore` uses: the hash vector on the JVM, the canonical hex on
   ClojureScript. On nbb a hash is a vector of BigInts. An array map
   holds eight of those. The ninth put promotes the map, and SCI will
   not hash a BigInt."
  (:require [dacite.store :as store]))

;; Recency order: each key carries the tick of its last touch, from a
;; monotonic counter, and `:by-tick` (a sorted map, tick -> key) yields the
;; least recent first. Touch, evict, and drop are O(log n). Ticks are
;; native integers, so the sorted map never compares a host BigInt.

(def ^:private empty-order {:tick 0 :by-tick (sorted-map) :tick-of {}})

(defn- drop-order
  "`order` without `k` (unchanged when `k` is absent)."
  [{:keys [by-tick tick-of] :as order} k]
  (if-let [t (get tick-of k)]
    (assoc order :by-tick (dissoc by-tick t) :tick-of (dissoc tick-of k))
    order))

(defn- touch-order
  "`order` with `k` the most recent."
  [order k]
  (let [{:keys [tick] :as order} (drop-order order k)]
    (-> order
        (assoc :tick (inc tick))
        (update :by-tick assoc tick k)
        (update :tick-of assoc k tick))))

(defn- least-recent
  "The least recently touched key, or nil."
  [{:keys [by-tick]}]
  (when-let [[_ k] (first by-tick)] k))

(defrecord LruStore [capacity data order]
  store/IStore
  (s-get [_ h]
    (let [k (store/mkey h)]
      (store/completed
       (when-let [v (get @data k)]
         (swap! order touch-order k)
         v))))

  (s-put [this h value]
    (let [k (store/mkey h)
          new-key? (not (contains? @data k))]
      (when (and new-key? (pos? capacity) (>= (count @data) capacity))
        (when-let [victim (least-recent @order)]
          (swap! data dissoc victim)
          (swap! order drop-order victim)))
      (swap! data assoc k value)
      (swap! order touch-order k))
    (store/completed this))

  (s-has? [_ h]
    (store/completed (contains? @data (store/mkey h))))

  (s-delete [this h]
    (let [k (store/mkey h)]
      (swap! data dissoc k)
      (swap! order drop-order k)
      (store/completed this)))

  (s-reset [this]
    (reset! data {})
    (reset! order empty-order)
    (store/completed this))

  store/ISweep
  (sweep-unmarked [_ live]
    ;; `live` is hex. A key is an `mkey`: already hex on ClojureScript,
    ;; a hash vector on the JVM.
    (let [drop (filterv (fn [k]
                          (not (contains? live (if (string? k) k (store/hash->hex k)))))
                        (keys @data))]
      (doseq [k drop]
        (swap! data dissoc k)
        (swap! order drop-order k))
      (store/completed {:removed (count drop)})))

  store/ILocalStats
  (local-stats [_]
    (let [m @data]
      (store/completed {:entries (count m)
                        :edn-bytes (reduce (fn [n v] (+ n (count (pr-str v)))) 0 (vals m))}))))

(defn lru-store
  "Create an LRU-bounded in-memory store. capacity 0 means unbounded."
  ([capacity]
   (->LruStore capacity (atom {}) (atom empty-order))))
