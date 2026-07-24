(ns dacite.rooted.gc
  "Garbage collection for content stores (Chapter 4 §4.6).

   Walks from a root hash, marks reachable nodes via types/child-hashes,
   and deletes detached entries from the store.

   Portable: same source on JVM, babashka, and nbb. Snapshot key form may
   differ by host (hash vectors on JVM, hex strings in cljs mem-store);
   keys are normalized before set membership and delete.

   Important (CLJS): hash vectors contain BigInt words and cannot be
   elements of cljs.core hash-sets (engine cannot attach hash cache to
   BigInt). All set membership therefore uses 64-char hex strings."
  (:require [dacite.store :as store]
            [dacite.value.types :as types]))

(defn hash-key
  "Portable identity key for a content hash (64-char hex)."
  [h]
  (cond
    (nil? h) nil
    (string? h) h
    :else (store/hash->hex h)))

(defn ->hash
  "Normalize a snapshot key or hex string to a hash vector."
  [k]
  (if (string? k)
    (store/hex->hash k)
    k))

(defn mark-reachable
  "Return the set of **hex** hash keys reachable from root-hash in store.
   Returns empty set when root-hash is nil.

   Callers that need hash vectors should map `store/hex->hash` (or `->hash`)."
  [store root-hash]
  (if (nil? root-hash)
    #{}
    (loop [queue [root-hash]
           live #{}]
      (if (empty? queue)
        live
        (let [h (nth queue 0)
              queue' (subvec queue 1)
              hk (hash-key h)]
          (if (contains? live hk)
            (recur queue' live)
            (if-some [node (store/s-get store h)]
              (let [children (or (types/child-hashes node) [])
                    live' (conj live hk)
                    queue'' (into queue' children)]
                (recur queue'' live'))
              (recur queue' live))))))))

(defn collect-garbage!
  "Delete store entries not reachable from root-hash.
   Returns {:removed n :kept n}. No-op when root-hash is nil."
  [store root-hash]
  (let [live (mark-reachable store root-hash)
        all-hashes (map ->hash (keys (store/s-snapshot store)))
        detached (remove (fn [h] (contains? live (hash-key h))) all-hashes)
        removed (count detached)]
    (doseq [h detached]
      (store/s-delete store h))
    {:removed removed :kept (count live)}))
