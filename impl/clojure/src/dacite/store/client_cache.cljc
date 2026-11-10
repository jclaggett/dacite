(ns dacite.store.client-cache
  "Client-side cache layers over a remote IStore.

   Policies (increasing optimization for todo bandwidth suite):
   - :none        — passthrough
   - :layered     — mem + remote write-through (layered-store)
   - :smart-put   — mem cache; skip remote PUT when local already has hash
   - :write-back  — put only to local; on flush-reachable! / before CAS,
                    upload only nodes reachable from the new root that have
                    not yet been flushed (drops abandoned intermediates and
                    avoids re-PUT of already-uploaded nodes)

   Instrument the remote leg with dacite.store.stats to measure network cost."
  (:require [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.rooted.gc :as gc]))

(defn- flushed-conj!
  "Add hash to flushed set using portable hex key (CLJS-safe)."
  [flushed-atom h]
  (swap! flushed-atom conj (gc/hash-key h)))

(defn- absorb-remote-pack-cache!
  "Copy the neighborhood of `h` from a pack-aware remote into client local.

   Pack-filled GET /node installs that neighborhood into the remote's
   pack-local cache. The copy walks from `h` with `s-get`. It does not
   list the cache."
  [local remote flushed-atom h]
  (loop [r remote]
    (cond
      (nil? r) nil

      (and (record? r) (contains? r :pack-local))
      (doseq [hk (gc/mark-reachable (:pack-local r) h)]
        (let [hh (gc/->hash hk)]
          (when-let [v (store/await (store/s-get (:pack-local r) hh))]
            (store/await (store/s-put local hh v))
            (when flushed-atom
              (flushed-conj! flushed-atom hh)))))

      (and (record? r) (contains? r :remote))
      (recur (:remote r))

      :else nil)))

(defrecord SmartCacheStore [local remote]
  store/IStore
  (s-get [_ h]
    (store/completed
     (if-let [v (store/await (store/s-get local h))]
       v
       (when-let [v (store/await (store/s-get remote h))]
         (store/await (store/s-put local h v))
         (absorb-remote-pack-cache! local remote nil h)
         v))))

  (s-put [this h value]
    (if (store/await (store/s-has? local h))
      (store/completed this)
      (do (store/await (store/s-put local h value))
          (store/await (store/s-put remote h value))
          (store/completed this))))

  (s-has? [_ h]
    (store/completed
     (boolean (or (store/await (store/s-has? local h))
                  (store/await (store/s-has? remote h))))))

  (s-delete [this h]
    (store/await (store/s-delete local h))
    (store/await (store/s-delete remote h))
    (store/completed this))

  (s-reset [this]
    (store/await (store/s-reset local))
    (store/completed this))

  store/ISweep
  (sweep-unmarked [_ live]
    (let [a (store/await (store/sweep-unmarked local live))
          b (store/await (store/sweep-unmarked remote live))]
      (store/completed {:removed (+ (:removed a 0) (:removed b 0))}))))

(defrecord WriteBackStore [local remote flushed]
  ;; flushed — atom of set of hex hash keys already uploaded to remote
  store/IStore
  (s-get [_ h]
    (store/completed
     (if-let [v (store/await (store/s-get local h))]
       v
       (when-let [v (store/await (store/s-get remote h))]
         (store/await (store/s-put local h v))
         (flushed-conj! flushed h)
         ;; Absorb the pack neighborhood of h into the write-back local.
         (absorb-remote-pack-cache! local remote flushed h)
         v))))

  (s-put [this h value]
    (store/await (store/s-put local h value))
    (store/completed this))

  (s-has? [_ h]
    (store/completed
     (boolean (or (store/await (store/s-has? local h))
                  (store/await (store/s-has? remote h))))))

  (s-delete [this h]
    (store/await (store/s-delete local h))
    (store/await (store/s-delete remote h))
    (swap! flushed disj (gc/hash-key h))
    (store/completed this))

  (s-reset [this]
    (store/await (store/s-reset local))
    (reset! flushed #{})
    (store/completed this))

  store/ISweep
  (sweep-unmarked [_ live]
    (let [a (store/await (store/sweep-unmarked local live))
          b (store/await (store/sweep-unmarked remote live))]
      (store/completed {:removed (+ (:removed a 0) (:removed b 0))}))))

(defn write-back-store?
  [s]
  (instance? WriteBackStore s))

(defn flush-reachable!
  "Upload local nodes reachable from root-h that are not yet flushed.
   Returns number of items uploaded. No-op if s is not WriteBackStore.

   When remote (or an outer middleware on it) implements IChunkTransport,
   uses pack/flush-from! (Layer 1 encode + Layer 2 soft chunks). Otherwise
   plain per-node s-put. Node presence novelty (:exists) updates the skip set;
   value completeness is not a store concern."
  [s root-h]
  (if-not (write-back-store? s)
    0
    (let [local (:local s)
          remote (:remote s)
          flushed (:flushed s)]
      (if (pack/find-chunk-transport remote)
        (let [result (pack/flush-from! remote local root-h @flushed)]
          (when (pos? (:items result 0))
            (swap! flushed into (or (:covered result) #{}))
            (doseq [hex (or (:exists result) [])]
              (let [hv (store/hex->hash hex)]
                (when (store/await (store/s-has? local hv))
                  (swap! flushed into (gc/mark-reachable local hv))))))
          (long (:items result 0)))
        ;; Non-chunk remotes: plain per-node PUT of every unflushed live hash.
        (let [live (gc/mark-reachable local root-h) ; hex keys
              to-send (vec (remove @flushed live))
              pairs (keep (fn [hk]
                            (let [h (gc/->hash hk)]
                              (when-let [v (store/await (store/s-get local h))]
                                [h v])))
                          to-send)]
          (if (empty? pairs)
            0
            (do
              (doseq [[h v] pairs]
                (store/await (store/s-put remote h v)))
              (swap! flushed into (map (comp gc/hash-key first) pairs))
              (count pairs))))))))

(defn wrap
  "Wrap remote with client cache according to policy keyword.

   policy: :none | :layered | :smart-put | :write-back
   (:smart-full accepted as alias of :smart-put)"
  [remote policy]
  (case policy
    :none remote
    :layered (store/layered-store (store/mem-store) remote)
    (:smart-put :smart-full)
    (->SmartCacheStore (store/mem-store) remote)
    :write-back
    (->WriteBackStore (store/mem-store) remote (atom #{}))
    (throw (ex-info "unknown client-cache policy" {:policy policy}))))
