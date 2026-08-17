(ns dacite.store.sync
  "Copy a reachable subgraph from one content store into another.

   Completes `push-ref`: move nodes first, then CAS the target root.
   Remote destinations that implement IChunkTransport use packed flush;
   otherwise nodes are copied one at a time, skipping hashes dest already
   has."
  (:require [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.rooted.gc :as gc]))

(defn- copy-nodes
  [src dest root-h]
  (let [live (gc/mark-reachable src root-h)]
    (reduce (fn [acc hex]
              (let [h (gc/->hash hex)]
                (if (store/s-has? dest h)
                  (update acc :skipped inc)
                  (if-let [node (store/s-get src h)]
                    (do (store/s-put dest h node)
                        (update acc :copied inc))
                    acc))))
            {:copied 0 :skipped 0 :live (count live) :via :nodes}
            live)))

(defn sync-reachable!
  "Copy nodes reachable from `root-h` in `src` into `dest`.

   Returns {:copied n :skipped n :live n :via :pack|:nodes}.
   `root-h` nil is a no-op."
  [src dest root-h]
  (cond
    (nil? root-h)
    {:copied 0 :skipped 0 :live 0 :via :nodes}

    (pack/find-chunk-transport dest)
    (let [skip (set (map gc/hash-key (keys (or (store/s-snapshot dest) {}))))
          result (pack/flush-from! dest src root-h skip)]
      {:copied (long (:items result 0))
       :skipped 0
       :live (count (or (:covered result) []))
       :via :pack})

    :else
    (copy-nodes src dest root-h)))
