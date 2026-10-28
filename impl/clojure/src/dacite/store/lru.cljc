(ns dacite.store.lru
  "Bounded in-memory content store with LRU eviction."
  (:require [dacite.store :as store]))

(defn- touch-order [order h]
  (let [order' (vec (remove #(= h %) order))]
    (conj order' h)))

(defrecord LruStore [capacity data order]
  store/IStore
  (s-get [_ h]
    (store/completed
     (when-let [v (get @data h)]
       (swap! order touch-order h)
       v)))

  (s-put [this h value]
    (let [new-key? (not (contains? @data h))]
      (when (and new-key? (pos? capacity) (>= (count @data) capacity))
        (when-let [victim (first @order)]
          (swap! data dissoc victim)
          (swap! order #(vec (rest %)))))
      (swap! data assoc h value)
      (swap! order touch-order h))
    (store/completed this))

  (s-has? [_ h]
    (store/completed (contains? @data h)))

  (s-delete [this h]
    (swap! data dissoc h)
    (swap! order #(vec (remove (fn [k] (= k h)) %)))
    (store/completed this))

  (s-snapshot [_]
    (store/completed @data))

  (s-merge [this m]
    (doseq [[h v] m]
      (store/await (store/s-put this h v)))
    (store/completed this))

  (s-reset [this]
    (reset! data {})
    (reset! order [])
    (store/completed this)))

(defn lru-store
  "Create an LRU-bounded in-memory store. capacity 0 means unbounded."
  ([capacity]
   (->LruStore capacity (atom {}) (atom []))))
