(ns dacite.store.chunk
  "Durable layout: persist pack Layer-1 items instead of an exploded tree.

   Working overlay is a normal mem store (constructors, nth). Inner holds
   only `encode-reachable` items (literals and oversized nodes). `flush!`
   from a root writes those items additively then drops inner keys that
   are not in the new set. `s-get` hydrates inner items into the overlay.

   `s/file` and `s/lmdb` wrap this overlay; rooted commit flushes. Raw
   `file-store` / `lmdb-store` stay exploded. Budget defaults to
   pack/default-budget (1024)."
  (:require [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.rooted.gc :as gc]))

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
        (store/s-get overlay h))

      :node
      (do (store/s-put overlay h (:body item))
          (:body item))

      (throw (ex-info "unsupported chunk encoding"
                      {:encoding enc :hash (:hash item)})))))

(defrecord ChunkedStore [inner overlay budget]
  store/IStore
  (s-get [_ h]
    (or (store/s-get overlay h)
        (when-let [v (store/s-get inner h)]
          (if (pack-item? v)
            (hydrate-inner! overlay v h)
            (do (store/s-put overlay h v)
                v)))))

  (s-put [this h value]
    (store/s-put overlay h value)
    this)

  (s-has? [_ h]
    (or (store/s-has? overlay h)
        (store/s-has? inner h)))

  (s-delete [this h]
    (store/s-delete overlay h)
    (store/s-delete inner h)
    this)

  (s-snapshot [_]
    (store/s-snapshot overlay))

  (s-merge [this m]
    (store/s-merge overlay m)
    this)

  (s-reset [this]
    (store/s-reset overlay)
    (store/s-reset inner)
    this))

(defn chunked
  "Wrap `inner` with a mem overlay. Puts go to the overlay; `flush!` writes
   pack items into inner. opts: `:budget` (default pack/default-budget)."
  ([inner] (chunked inner nil))
  ([inner {:keys [budget overlay]}]
   (->ChunkedStore inner
                   (or overlay (store/mem-store))
                   (long (or budget pack/default-budget)))))

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
      (store/s-put in (store/hex->hash (:hash item)) item))
    {:items items
     :covered covered
     :keep keep
     :budget b}))

(defn retain-inner!
  "Delete inner keys not in `keep` (hash vectors)."
  [cs keep]
  (let [in (inner cs)
        keep (into #{} (map gc/->hash keep))]
    (doseq [k (keys (store/s-snapshot in))]
      (let [h (gc/->hash k)]
        (when-not (contains? keep h)
          (store/s-delete in h))))
    cs))

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

(defn- edn-bytes
  [snap]
  (reduce + 0 (map (fn [v] (count (pr-str v))) (vals snap))))

(defn inner-stats
  "Durable (inner) entry count and EDN byte size of values."
  [cs]
  (let [snap (store/s-snapshot (inner cs))]
    {:entries (count snap)
     :edn-bytes (edn-bytes snap)}))

(defn overlay-stats
  "Working overlay entry count and EDN byte size."
  [cs]
  (let [snap (store/s-snapshot (overlay cs))]
    {:entries (count snap)
     :edn-bytes (edn-bytes snap)}))

(defn live-count
  "Reachable exploded hashes in the overlay from `root-h`."
  [cs root-h]
  (count (gc/mark-reachable (overlay cs) root-h)))
