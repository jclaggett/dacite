(ns dacite.store.chunk
  "Experimental durable layout: persist pack Layer-1 items instead of an
   exploded tree.

   Working overlay is a normal mem store (constructors, nth). Inner holds
   only `encode-reachable` items (literals and oversized nodes). `flush!`
   from a root rewrites inner. `s-get` hydrates inner items into the overlay.

   Not the default for file/LMDB/HTTP. Budget defaults to pack/default-budget
   (1024)."
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

(defn flush!
  "Rewrite inner from overlay nodes reachable at `root-h`. Returns
   {:items n :literals n :nodes n :covered n}."
  [cs root-h]
  (let [ov (overlay cs)
        in (inner cs)
        b (budget cs)
        {:keys [items covered]} (pack/encode-reachable ov root-h #{} b)
        sum (pack/summarize-items items)]
    (store/s-reset in)
    (doseq [item items]
      (store/s-put in (store/hex->hash (:hash item)) item))
    {:items (count items)
     :literals (:literals sum)
     :nodes (:nodes sum)
     :covered (count covered)
     :budget b}))

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
