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

(defrecord SmartCacheStore [local remote]
  store/IStore
  (s-get [_ h]
    (if-let [v (store/s-get local h)]
      v
      (when-let [v (store/s-get remote h)]
        (store/s-put local h v)
        v)))

  (s-put [this h value]
    (if (store/s-has? local h)
      this
      (do (store/s-put local h value)
          (store/s-put remote h value)
          this)))

  (s-has? [_ h]
    (or (store/s-has? local h)
        (store/s-has? remote h)))

  (s-delete [this h]
    (store/s-delete local h)
    (store/s-delete remote h)
    this)

  (s-snapshot [_]
    (merge (store/s-snapshot remote) (store/s-snapshot local)))

  (s-merge [this m]
    (doseq [[h v] m]
      (store/s-put this h v))
    this)

  (s-reset [this]
    (store/s-reset local)
    this))

(defrecord WriteBackStore [local remote flushed]
  ;; flushed — atom of hash-set of hashes already uploaded to remote
  store/IStore
  (s-get [_ h]
    (if-let [v (store/s-get local h)]
      v
      (when-let [v (store/s-get remote h)]
        (store/s-put local h v)
        (swap! flushed conj h)
        v)))

  (s-put [this h value]
    (store/s-put local h value)
    this)

  (s-has? [_ h]
    (or (store/s-has? local h)
        (store/s-has? remote h)))

  (s-delete [this h]
    (store/s-delete local h)
    (store/s-delete remote h)
    (swap! flushed disj h)
    this)

  (s-snapshot [_]
    (merge (store/s-snapshot remote) (store/s-snapshot local)))

  (s-merge [this m]
    (doseq [[h v] m]
      (store/s-put this h v))
    this)

  (s-reset [this]
    (store/s-reset local)
    (reset! flushed #{})
    this))

(defn write-back-store?
  [s]
  (instance? WriteBackStore s))

(defn flush-reachable!
  "Upload local nodes reachable from root-h that are not yet flushed.
   Returns number of items uploaded. No-op if s is not WriteBackStore.

   When remote implements pack/IChunkTransport, Layer 1 chooses :node or
   :literal and Layer 2 packs soft-budget chunks (POST /nodes). Literals
   cover their descendant subgraph so those hashes are not re-sent."
  [s root-h]
  (if-not (write-back-store? s)
    0
    (let [local (:local s)
          remote (:remote s)
          flushed (:flushed s)]
      (if (satisfies? pack/IChunkTransport remote)
        (let [{:keys [items covered]} (pack/encode-reachable local root-h @flushed)]
          (if (empty? items)
            0
            (do
              (pack/put-items-chunked! remote items pack/default-budget)
              (swap! flushed into covered)
              (count items))))
        ;; Non-chunk remotes: plain per-node PUT of every unflushed live hash.
        (let [live (gc/mark-reachable local root-h)
              to-send (vec (remove @flushed live))
              pairs (keep (fn [h]
                            (when-let [v (store/s-get local h)]
                              [h v]))
                          to-send)]
          (if (empty? pairs)
            0
            (do
              (doseq [[h v] pairs]
                (store/s-put remote h v))
              (swap! flushed into (map first pairs))
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
