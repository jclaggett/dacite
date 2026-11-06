(ns dacite.peer.origin
  "Verify a value from the origin into a local store (M5.1).

   The origin is the HTTP content store. This namespace does not speak
   HTTP. `fetch-chunk` is `(fn [hash] chunk-bytes-or-nil)` and may return
   a host promise. The bytes are one wire-v1 pack chunk: the body of
   `GET /node/{hex}` with `Accept: application/vnd.dacite.chunk.v1`.

   Each item is checked by `dacite.peer.verify` before it is stored.
   When every child the stored nodes name has been fetched, `finish!`
   requires every collection header's root to have arrived. The result
   is a step of `{:root hash :entries n}`. `:entries` is how many stored
   nodes are reachable from the root. Start from an empty store so that
   count is this value."
  (:require [dacite.peer.verify :as verify]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.value.types :as types]
            [dacite.wire.binary :as bin]))

(def ^:private max-chunks
  "Safety cap on GET /node rounds for one value."
  10000)

(defn count-reachable
  "A step of how many nodes in `st` are reachable from `root`.

   A literal expands into every node it realizes, including scalars the
   origin had inlined and that nothing references. Those stay in the
   store. The count is the live value, the same set `gc/mark-reachable`
   walks. Each node is read with `s-get`."
  [st root]
  (if (nil? root)
    0
    (letfn [(step [queue live]
              (if-let [h (first queue)]
                (let [hk (gc/hash-key h)]
                  (if (contains? live hk)
                    (step (rest queue) live)
                    (chain/then
                     (store/s-get st h)
                     (fn [node]
                       (if (nil? node)
                         (step (rest queue) live)
                         (step (into (rest queue) (or (types/child-hashes node) []))
                               (conj live hk)))))))
                (count live)))]
      (step (list root) #{}))))

(defn- missing-hexes
  "A step of the hex hashes reached from `frontier` that are not stored
   and not already requested.

   Stored nodes are expanded with `s-get`. A hash already in `seen` is
   not read again. A missing hash is not marked seen: the caller fetches
   it and passes it as the next frontier. The store is not listed."
  [st frontier seen fetched]
  (letfn [(step [queue missing]
            (if-let [h (first queue)]
              (let [hk (gc/hash-key h)]
                (if (or (nil? h) (contains? @seen hk))
                  (step (rest queue) missing)
                  (chain/then
                   (store/s-get st h)
                   (fn [node]
                     (if (nil? node)
                       (step (rest queue)
                             (if (contains? fetched hk)
                               missing
                               (conj missing hk)))
                       (do
                         (swap! seen conj hk)
                         (step (into (rest queue)
                                     (remove nil?)
                                     (or (types/child-hashes node) []))
                               missing)))))))
              (into [] (distinct) missing)))]
    (step (seq frontier) [])))

(defn verify-from-origin!
  "Fetch `root` through `fetch-chunk` and verify it into `st`.

   `fetch-chunk` receives a hash and returns the chunk bytes, nil when
   the origin has no such node, or a promise of either. Returns a step
   of `{:root :entries}`."
  [st root fetch-chunk]
  (let [rx (verify/receiver st)
        fetched (atom #{})
        seen (atom #{})
        frontier (atom [])]
    (letfn [(take-chunk [h]
              (let [hx (gc/hash-key h)]
                (when-not (contains? @fetched hx)
                  (swap! fetched conj hx)
                  (chain/then
                   (fetch-chunk h)
                   (fn [bs]
                     (when (nil? bs)
                       (throw (ex-info "the origin has no such node"
                                       {:reason :missing :hash h})))
                     (verify/accept-items! rx (:items (bin/decode-chunk bs))))))))
            (step [n]
              (if (> n max-chunks)
                (throw (ex-info "the origin pull did not finish"
                                {:reason :too-many-chunks :chunks n}))
                (chain/then
                 (missing-hexes st @frontier seen @fetched)
                 (fn [need]
                   (if (seq need)
                     (do
                       (reset! frontier (mapv store/hex->hash need))
                       (chain/then (chain/each need #(take-chunk (store/hex->hash %)))
                                   (fn [_] (step (inc n)))))
                     (chain/then (verify/finish! rx)
                                 (fn [_]
                                   (chain/then (count-reachable st root)
                                               (fn [n-live]
                                                 {:root root
                                                  :entries n-live})))))))))]
      (chain/then (take-chunk root)
                  (fn [_]
                    (reset! frontier [root])
                    (step 1))))))
