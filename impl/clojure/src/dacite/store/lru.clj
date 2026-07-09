(ns dacite.store.lru
  "Bounded in-memory content store with LRU eviction."
  (:require [dacite.store :as store]))

(defn- touch-order [order h]
  (let [order' (vec (remove #(= h %) order))]
    (conj order' h)))

(defrecord LruStore [capacity data order]
  store/IStore
  (s-get [_ h]
    (when-let [v (get @data h)]
      (swap! order touch-order h)
      v))

  (s-put [this h value]
    (let [new-key? (not (contains? @data h))]
      (when (and new-key? (pos? capacity) (>= (count @data) capacity))
        (when-let [victim (first @order)]
          (swap! data dissoc victim)
          (swap! order #(vec (rest %)))))
      (swap! data assoc h value)
      (swap! order touch-order h))
    this)

  (s-has? [_ h]
    (contains? @data h))

  (s-delete [this h]
    (swap! data dissoc h)
    (swap! order #(vec (remove (fn [k] (= k h)) %)))
    this)

  (s-snapshot [_]
    @data)

  (s-merge [this m]
    (doseq [[h v] m]
      (store/s-put this h v))
    this)

  (s-reset [this]
    (reset! data {})
    (reset! order [])
    this))

(defn lru-store
  "Create an LRU-bounded in-memory store. capacity 0 means unbounded."
  ([capacity]
   (->LruStore capacity (atom {}) (atom []))))
