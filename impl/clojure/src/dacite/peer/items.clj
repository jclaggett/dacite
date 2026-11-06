(ns dacite.peer.items
  "Peer protocol v1: the items of an item stream, written and checked.

   Spec: docs/spec/peer-v1.md §3.2 (serialization) and §3.3 (verification).

   Three layers, each one the wire-v1 code that already exists:

     1. entries   hash -> data, as the store holds them (`dacite.store`)
     2. wire-v1   an Item is a node entry, or a literal (`dacite.store.pack`
                  chooses the literal form, `dacite.wire.binary` writes
                  both). Runs and repeats inside a literal are wire-v1's.
     3. frames    whole Items packed into frames (`dacite.peer.frame`)

   This namespace adds only what the peer profile needs on top of layer 2:

   - the literal rule: for each value, the shorter of its literal and the
     node entries it would otherwise need wins, and a literal item is at
     most one frame payload (`frame/max-literal-item`). A larger value
     falls back to node entries, so a literal never spans frames.
   - header first: items go in `reveal/page-order`, a collection header
     before its pages. A header cannot be checked when it arrives (its hash,
     count and size follow from its root), so it is accepted and stored at
     once and checked when its root arrives: see `receiver`.
   - checking and receiving (`verify-item!`, `accept-item!`, `finish!`, ...)
     live in the portable `dacite.peer.verify` and are re-exported here."
  (:require [dacite.peer.frame :as frame]
            [dacite.rooted.gc :as gc]
            [dacite.reveal :as reveal]
            [dacite.peer.verify :as verify]
            [dacite.service.reveal :as sr]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.value.lit :as lit]
            [dacite.wire.binary :as bin]))

(def ^:private has-ref?
  "True when a literal body names a child by hash (type `ref`, or a `run` or
   `repeat` of `ref`). A peer literal is self-contained: it holds none."
  lit/names-child-hash?)

;; =============================================================================
;; Writing
;; =============================================================================

(defn- node-bytes
  "Wire bytes of the node item for `h`. Reuses a measurement made while
   `bin/*node-wire-cache*` is bound, so a page measured for the literal
   comparison is not encoded again (N11)."
  [st h]
  (let [hk (store/hash->hex h)]
    (if-let [hit (when bin/*node-wire-cache* (get @bin/*node-wire-cache* hk))]
      hit
      (let [bs (sr/encode-page st {:hash h})]
        (when bin/*node-wire-cache* (swap! bin/*node-wire-cache* assoc hk bs))
        bs))))

(defn literal-bytes
  "Wire-v1 bytes of the literal item for `h`, or nil when it has none, holds
   a ref, or does not fit `cap` bytes (the whole item, header included).
   `pack/encode-item` makes the choice."
  ([st h cap] (literal-bytes st h cap nil))
  ([st h cap cache]
   (when-let [entry (store/await (store/s-get st h))]
     (let [item (pack/encode-item st h entry (cond-> {:max-literal cap}
                                               cache (assoc :cache cache)))]
       (when (and (= :literal (:encoding item)) (not (has-ref? (:body item))))
         (bin/encode-item {:enc :literal :hash h
                           :literal {:type (:type item) :body (:body item)}}))))))

(defn encode-value
  "Items (wire-v1 bytes, in sending order) that reveal `root` in full.

   For each value, in default reveal order: its literal when that is
   shorter than the node entries it needs and the whole item fits one frame
   payload (`:max-frame-bytes`, default 16384, minus the 6-byte envelope);
   otherwise its node entry, and then its children are considered the same
   way. A literal covers everything under it. Equal sizes keep the node
   entries.

   A node item over one payload (the same cap as a literal) breaks the
   sender SHOULD of spec 3.2. The default is to report it: `:oversized`
   lists each such item as {:hash :bytes-len}, and the item is still sent
   (receivers take up to `max-item`). With `:strict-node-cap? true` it
   throws ex-info {:reason :node-too-large} instead.

   Returns {:items [bytes] :encs [:node|:literal] :hashes [hash]
            :oversized [{:hash :len}]}. Code that sends the items (the M1.2
   session) MUST look at `:oversized`: with the default it is the only
   sign that a node item will not fit a peer's smallest frame."
  ([st root] (encode-value st root {}))
  ([st root {:keys [max-frame-bytes strict-node-cap?]
             :or {max-frame-bytes frame/max-frame}}]
   (binding [bin/*node-wire-cache* (atom {})]
   (let [cap (frame/max-literal-item max-frame-bytes)
         cache (atom {})
         order (reveal/page-order st root)
         ;; decide in reveal order (header first); that is also the sending order
         {:keys [picks]}
         (reduce
          (fn [{:keys [covered picks] :as acc} h]
            (if (contains? covered (gc/hash-key h))
              acc
              (let [entry (store/await (store/s-get st h))
                    item (pack/encode-item st h entry
                                           {:max-literal cap :shorter-wins? true
                                            :covered covered :cache cache})
                    lit (when (and (= :literal (:encoding item))
                                   (not (has-ref? (:body item))))
                          (bin/encode-item {:enc :literal :hash h
                                            :literal {:type (:type item) :body (:body item)}}))]
                (if lit
                  {:covered (into covered (gc/mark-reachable st h))
                   :picks (conj picks {:hash h :enc :literal :bytes lit})}
                  {:covered (conj covered (gc/hash-key h))
                   :picks (conj picks {:hash h :enc :node :bytes (node-bytes st h)})}))))
          {:covered #{} :picks []}
          order)
         oversized (into []
                         (comp (filter #(and (= :node (:enc %))
                                             (> (bin/byte-len (:bytes %)) cap)))
                               (map #(hash-map :hash (:hash %)
                                               :len (bin/byte-len (:bytes %)))))
                         picks)]
     (when (and strict-node-cap? (seq oversized))
       (throw (ex-info "a node item is over one frame payload"
                       {:reason :node-too-large :cap cap :oversized oversized})))
     {:items (mapv :bytes picks)
      :encs (mapv :enc picks)
      :hashes (mapv :hash picks)
      :oversized oversized}))))

;; =============================================================================
;; Checking and receiving
;; =============================================================================

;; Moved to the portable `dacite.peer.verify` (M1.2 slice 1, so nbb runs the
;; same check as the JVM). These names stay so callers and the spec keep working.

(def verify-item! verify/verify-item!)
(def receiver verify/receiver)
(def unchecked verify/unchecked)
(def discard-unchecked! verify/discard-unchecked!)
(def accept-item! verify/accept-item!)
(def accept-items! verify/accept-items!)
(def finish! verify/finish!)
