(ns dacite.rooted.gc
  "Garbage collection for content stores (Chapter 4 §4.6).

   Walks from a root hash and marks reachable nodes via types/child-hashes,
   using `s-get` only. The backend that holds the entries then deletes
   anything outside that set (`sweep-unmarked`). The live set is not a
   listing of the store, and the sweep does not return the hashes it saw.

   Portable: same source on JVM, babashka, and nbb. Set membership uses
   hex: a ClojureScript hash vector's words are BigInts, and the engine
   cannot attach a hash cache to a BigInt.

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
  "Normalize a hash vector or hex string to a hash vector."
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
            (if-some [node (store/await (store/s-get store h))]
              (let [children (or (types/child-hashes node) [])
                    live' (conj live hk)
                    queue'' (into queue' children)]
                (recur queue'' live'))
              (recur queue' live))))))))

(defn collect-garbage!
  "Delete store entries not reachable from root-hash.
   Returns {:removed n :kept n}.

   A nil root keeps nothing: every entry is detached. The sweep runs
   inside the backend that holds the entries and does not return them."
  [store root-hash]
  (let [live (mark-reachable store root-hash)]
    (when-not (satisfies? store/ISweep store)
      (throw (ex-info "this store cannot sweep entries"
                      {:reason :no-sweep :store (type store)})))
    (let [{:keys [removed]} (store/await (store/sweep-unmarked store live))]
      {:removed removed :kept (count live)})))
