(ns dacite.store.chunk
  "Durable layout: persist pack Layer-1 items instead of an exploded tree.

   Working overlay is a normal mem store (constructors, nth). Inner holds
   only `encode-reachable` items (literals and oversized nodes). `flush!`
   from a root writes those items additively then drops inner keys that
   are not in the new set. `s-get` hydrates inner items into the overlay.

   `s/file` and `s/lmdb` wrap this overlay; rooted commit flushes. Raw
   `file-store` / `lmdb-store` stay exploded. The IndexedDB store wraps it
   too. Budget defaults to `durable-budget` (1024), not the HTTP and peer
   default `pack/default-budget` (4096)."
  (:require [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.rooted.gc :as gc]))

(def durable-budget
  "Default soft chunk budget of a durable store (file, LMDB, IndexedDB), in
   sent bytes: 1 KiB. Its own constant, apart from `pack/default-budget`
   (4 KiB, for HTTP and peers, where a full leaf page should travel as a
   literal). On disk a literal repeats what its entries share, so a larger
   chunk costs space: the seed catalog is 27 086 bytes at 4096 and 18 381 at
   1024 (12 121 against 21 574 live-exploded bytes in the storage test)."
  1024)

(defn pack-item?
  "True if v is a Layer-1 pack item (inner durable form)."
  [v]
  (and (map? v)
       (contains? v :encoding)
       (contains? v :hash)))

(defn- hydrate-inner!
  "Install inner item for h into overlay. Returns the exploded entry, or nil."
  [overlay item h]
  (let [enc (keyword (:encoding item))]
    (case enc
      :literal
      (let [got (pack/materialize-literal! overlay (:type item) (:body item))]
        (when (not= got h)
          (throw (ex-info "chunk hydrate hash mismatch"
                          {:expected (store/hash->hex h)
                           :got (store/hash->hex got)
                           :type (:type item)})))
        (store/await (store/s-get overlay h)))

      :node
      (do (store/await (store/s-put overlay h (:body item)))
          (:body item))

      (throw (ex-info "unsupported chunk encoding"
                      {:encoding enc :hash (:hash item)})))))

(defrecord ChunkedStore [inner overlay budget]
  store/IStore
  (s-get [_ h]
    (store/completed
     (if-some [v (store/await (store/s-get overlay h))]
       v
       (when-let [v (store/await (store/s-get inner h))]
         (if (pack-item? v)
           (hydrate-inner! overlay v h)
           (do (store/await (store/s-put overlay h v))
               v))))))

  (s-put [this h value]
    (store/await (store/s-put overlay h value))
    (store/completed this))

  (s-has? [_ h]
    (store/completed
     (boolean (or (store/await (store/s-has? overlay h))
                  (store/await (store/s-has? inner h))))))

  (s-delete [this h]
    (store/await (store/s-delete overlay h))
    (store/await (store/s-delete inner h))
    (store/completed this))

  (s-merge [this m]
    (store/await (store/s-merge overlay m))
    (store/completed this))

  (s-reset [this]
    (store/await (store/s-reset overlay))
    (store/await (store/s-reset inner))
    (store/completed this))

  store/ISweep
  (sweep-unmarked [_ live]
    (let [over (store/await (store/sweep-unmarked overlay live))
          inn (store/await (store/sweep-unmarked inner live))]
      (store/completed {:removed (+ (:removed over 0) (:removed inn 0))}))))

(defn chunked
  "Wrap `inner` with a mem overlay. Puts go to the overlay; `flush!` writes
   pack items into inner. opts: `:budget` (default `durable-budget`)."
  ([inner] (chunked inner nil))
  ([inner {:keys [budget overlay]}]
   (->ChunkedStore inner
                   (or overlay (store/mem-store))
                   (long (or budget durable-budget)))))

(defn overlay [cs] (:overlay cs))
(defn inner [cs] (:inner cs))
(defn budget [cs] (:budget cs))

(defn chunked-store?
  "True if `st` is a pack-literal overlay (the default s/file and s/lmdb content)."
  [st]
  (instance? ChunkedStore st))

(defn put-reachable!
  "Write pack items for `root-h` into inner without deleting extras.

   Encodes via this store (hydrate on miss) so a reopen+edit still sees
   literals that were never copied into the overlay. Returns
   {:items :covered :keep}."
  [cs root-h]
  (let [b (budget cs)
        {:keys [items covered]} (pack/encode-reachable cs root-h #{} b)
        in (inner cs)
        keep (into #{} (map #(store/hex->hash (:hash %)) items))]
    (doseq [item items]
      (store/await (store/s-put in (store/hex->hash (:hash item)) item)))
    {:items items
     :covered covered
     :keep keep
     :budget b}))

(defn retain-inner!
  "Delete inner entries whose hash is not in `keep` (hash vectors).

   The inner backend sweeps itself. The keep set is not turned into a
   listing of what inner held."
  [cs keep]
  (store/await (store/sweep-unmarked (inner cs)
                                     (into #{} (map gc/hash-key) keep)))
  cs)

(defn flush!
  "Persist pack items for `root-h` and drop other inner keys. Returns
   {:items n :literals n :nodes n :covered n}. No-op when root-h is nil."
  [cs root-h]
  (if (nil? root-h)
    {:items 0 :literals 0 :nodes 0 :covered 0 :budget (budget cs)}
    (let [{:keys [items covered keep budget]} (put-reachable! cs root-h)
          sum (pack/summarize-items items)]
      (retain-inner! cs keep)
      {:items (count items)
       :literals (:literals sum)
       :nodes (:nodes sum)
       :covered (count covered)
       :budget budget})))

(defn inner-stats
  "Durable (inner) entry count and EDN byte size of values.

   The count comes from the backend. It is not a listing of hashes."
  [cs]
  (store/await (store/local-stats (inner cs))))

(defn overlay-stats
  "Working overlay entry count and EDN byte size.

   The count comes from the overlay backend. Reachable nodes from a root
   are `live-count`; this number includes construction debris."
  [cs]
  (store/await (store/local-stats (overlay cs))))

(defn live-count
  "Reachable exploded hashes in the overlay from `root-h`."
  [cs root-h]
  (count (gc/mark-reachable (overlay cs) root-h)))
