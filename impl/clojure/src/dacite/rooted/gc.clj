(ns dacite.rooted.gc
  "Garbage collection for content stores (Chapter 4 §4.6).

   Walks from a root hash, marks reachable nodes via types/child-hashes,
   and deletes detached entries from the store."
  (:require [dacite.store :as store]
            [dacite.value.types :as types]))

(defn mark-reachable
  "Return the set of hashes reachable from root-hash in store.
   Returns empty set when root-hash is nil."
  [store root-hash]
  (if (nil? root-hash)
    #{}
    (loop [queue (conj clojure.lang.PersistentQueue/EMPTY root-hash)
           live #{}]
      (if (empty? queue)
        live
        (let [h (peek queue)
              queue' (pop queue)]
          (if (contains? live h)
            (recur queue' live)
            (if-some [node (store/s-get store h)]
              (let [children (or (types/child-hashes node) [])
                    live' (conj live h)
                    queue'' (reduce conj queue' children)]
                (recur queue'' live'))
              (recur queue' live))))))))

(defn collect-garbage!
  "Delete store entries not reachable from root-hash.
   Returns {:removed n :kept n}. No-op when root-hash is nil."
  [store root-hash]
  (let [live (mark-reachable store root-hash)
        all-keys (keys (store/s-snapshot store))
        detached (remove live all-keys)
        removed (count detached)]
    (doseq [h detached]
      (store/s-delete store h))
    {:removed removed :kept (count live)}))
