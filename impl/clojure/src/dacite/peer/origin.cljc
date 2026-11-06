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

(defn- child-hexes
  "A step of the child hexes named by the nodes at `hashes`."
  [st hashes]
  (let [out (atom [])]
    (chain/then
     (chain/each hashes
                 (fn [h]
                   (chain/then
                    (store/s-get st h)
                    (fn [node]
                      (when (some? node)
                        (swap! out into (map gc/hash-key (or (types/child-hashes node) []))))
                      nil))))
     (fn [_] @out))))

(defn- missing-hexes
  "A step of the hex hashes named by stored nodes that are neither stored
   nor already requested. Hashes come from `s-keys`; each node is read
   with `s-get`."
  [st fetched]
  (chain/then
   (store/s-keys st)
   (fn [hashes]
     (chain/then
      (child-hexes st hashes)
      (fn [children]
        (let [have (into fetched (map gc/hash-key) hashes)]
          (into [] (comp (remove have) (distinct)) children)))))))

(defn verify-from-origin!
  "Fetch `root` through `fetch-chunk` and verify it into `st`.

   `fetch-chunk` receives a hash and returns the chunk bytes, nil when
   the origin has no such node, or a promise of either. Returns a step
   of `{:root :entries}`."
  [st root fetch-chunk]
  (let [rx (verify/receiver st)
        fetched (atom #{})]
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
                 (missing-hexes st @fetched)
                 (fn [need]
                   (if (seq need)
                     (chain/then (chain/each need #(take-chunk (store/hex->hash %)))
                                 (fn [_] (step (inc n))))
                     (chain/then (verify/finish! rx)
                                 (fn [_]
                                   (chain/then (count-reachable st root)
                                               (fn [n-live]
                                                 {:root root
                                                  :entries n-live})))))))))]
      (chain/then (take-chunk root) (fn [_] (step 1))))))
