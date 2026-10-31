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
   - `verify-item!`: each item is checked from its own bytes, and from
     what is already stored. Nothing waits and nothing is held back."
  (:require [clojure.string :as str]
            [dacite.hash :as hash]
            [dacite.peer.frame :as frame]
            [dacite.rooted.gc :as gc]
            [dacite.reveal :as reveal]
            [dacite.service.reveal :as sr]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
            [dacite.value.lit :as lit]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]
            [dacite.value.types :as types]
            [dacite.wire.binary :as bin]))

(def literal-gate
  "Values whose logical size is over `literal-gate * cap` bytes never get a
   literal built: it could not fit. An optimisation, not a rule; the rule is
   the size of the literal item itself."
  16)

(defn- collection-type? [t] (contains? #{"string" "blob" "vector" "map" "set"} t))

(defn- structural-type? [t]
  (and (string? t) (or (str/starts-with? t "ft/") (str/starts-with? t "hamt/"))))

(def ^:private has-ref?
  "True when a literal body names a child by hash (type `ref`, or a `run` or
   `repeat` of `ref`). A peer literal is self-contained: it holds none."
  lit/names-child-hash?)

;; =============================================================================
;; Writing
;; =============================================================================

(defn- node-bytes [st h] (sr/encode-page st {:hash h}))

(defn literal-bytes
  "Wire-v1 bytes of the literal item for `h`, or nil when it has none, holds
   a ref, or does not fit `cap` bytes (the whole item, header included)."
  [st h cap]
  (when-let [entry (store/await (store/s-get st h))]
    (let [item (pack/encode-item st h entry (* literal-gate cap))]
      (when (and (= :literal (:encoding item)) (not (has-ref? (:body item))))
        (let [bs (bin/encode-item {:enc :literal :hash h
                                   :literal {:type (:type item) :body (:body item)}})]
          (when (<= (bin/byte-len bs) cap)
            bs))))))

(defn- entries-bytes
  "Bytes of the node items a default reveal of `h` would send, not counting
   hashes in `covered`. Stops counting past `limit`: only 'more than the
   literal' matters."
  [st h covered limit]
  (reduce (fn [acc x]
            (if (contains? covered (gc/hash-key x))
              acc
              (let [acc (+ acc (bin/byte-len (node-bytes st x)))]
                (if (> acc limit) (reduced acc) acc))))
          0
          (reveal/page-order st h)))

(defn encode-value
  "Items (wire-v1 bytes, in sending order) that reveal `root` in full.

   For each value, in default reveal order: its literal when that is
   shorter than the node entries it needs and the whole item fits one frame
   payload (`:max-frame-bytes`, default 16384, minus the 6-byte envelope);
   otherwise its node entry, and then its children are considered the same
   way. A literal covers everything under it. Equal sizes keep the node
   entries.

   Returns {:items [bytes] :encs [:node|:literal] :hashes [hash]}."
  ([st root] (encode-value st root {}))
  ([st root {:keys [max-frame-bytes] :or {max-frame-bytes frame/max-frame}}]
   (let [cap (frame/max-literal-item max-frame-bytes)
         order (reveal/page-order st root)
         ;; decide in reveal order (header first); that is also the sending order
         {:keys [picks]}
         (reduce
          (fn [{:keys [covered picks] :as acc} h]
            (if (contains? covered (gc/hash-key h))
              acc
              (let [lit (literal-bytes st h cap)
                    use-lit? (and lit
                                  (< (bin/byte-len lit)
                                     (entries-bytes st h covered (bin/byte-len lit))))]
                (if use-lit?
                  {:covered (into covered (gc/mark-reachable st h))
                   :picks (conj picks {:hash h :enc :literal :bytes lit})}
                  {:covered (conj covered (gc/hash-key h))
                   :picks (conj picks {:hash h :enc :node :bytes (node-bytes st h)})}))))
          {:covered #{} :picks []}
          order)
         ]
     {:items (mapv :bytes picks)
      :encs (mapv :enc picks)
      :hashes (mapv :hash picks)})))

;; =============================================================================
;; Checking
;; =============================================================================

(defn- bad!
  [reason msg data]
  (throw (ex-info msg (assoc data :reason reason))))

(defn- missing? [e]
  (and (instance? clojure.lang.ExceptionInfo e)
       (= :missing (:reason (ex-data e)))))

(defn- root-measure
  "The measure of a collection header's root, or nil when the root is not
   stored (yet). The root is a page, or a bare leaf value that is itself the
   one element."
  [st type-name root]
  (try (case type-name
         ("string" "blob" "vector") (ft/ft-measure st root)
         ("map" "set") (hamt/hamt-measure st root))
       (catch clojure.lang.ExceptionInfo e
         (if (missing? e) nil (throw e)))))

(defn- header-check
  "Check a collection header against the measure of its root, which must be
   stored. The hash covers only the elements-fuse, so count and size-bytes
   are checked here by recomputation: they are in no hash. Returns
   #{:hash :root-measure}; throws `:bad-hash` or `:bad-entry`."
  [type-name {:keys [count size-bytes]} claimed measure]
  (when-not (= claimed (types/value-hash type-name (:elements-fuse measure)))
    (bad! :bad-hash "collection hash does not match its root" {:hash claimed}))
  (when-not (and (= count (:count measure)) (= size-bytes (:size-bytes measure)))
    (bad! :bad-entry "collection count or size differs from its root"
          {:hash claimed}))
  #{:hash :root-measure})

(defn- page-body [data]
  (:body data))

(defn- verify-node!
  [st hash [type-name data :as entry]]
  (cond
    (collection-type? type-name)
    (if-let [m (root-measure st type-name (:root data))]
      (header-check type-name data hash m)
      #{:pending})

    :else
    (let [got (sr/expected-hash st entry)]
      (when-not (= got hash)
        (bad! :bad-hash "item does not hash to its claimed hash" {:hash hash}))
      (cond-> #{:hash}
        ;; a page's measure must follow from the leaves it holds
        (and (contains? #{"ft/digit" "ft/node"} type-name)
             (some? (page-body data))
             (not (has-ref? (page-body data))))
        (as-> checks
              (do (when-not (= (:measure data) (ft/page-body-measure (page-body data)))
                    (bad! :bad-entry "page measure does not match its contents" {:hash hash}))
                  (conj checks :body-measure)))

        (= "hamt/entry" type-name)
        (as-> checks
              (do (when-not (= (:elements-fuse (:measure data))
                               (hash/unchecked-fuse (:key-ref data) (:val-ref data)))
                    (bad! :bad-entry "entry measure does not match its key and value"
                          {:hash hash}))
                  (conj checks :entry-measure)))))))

(defn- verify-item*!
  "Check one decoded wire-v1 item (`bin/decode-item`) from its own bytes.

   Returns the set of checks it passed, and throws {:reason ...} when one
   fails: `:bad-hash`, `:bad-entry` or `:bad-item`.
   Nothing is stored, and nothing is waited for. `st` is read only, for
   what earlier items put there.

   - literal: rebuilt in a scratch store; its hash must equal the item's.
     It is complete, so nothing is left unchecked. No ref is allowed.
   - scalar: its hash from its data.
   - page (`ft/*`, `hamt/*`): its hash from its claimed measure. A digit
     or node page with no ref must also have the measure of its contents,
     and an entry the elements-fuse of its key and value.
   - collection header: its hash, count and size from its root's measure
     when the root is stored; when it is not (header first) the answer is
     #{:pending}, and `accept-item!` checks it when the root arrives.

   Not checked here: that a page's measure follows from its children
   (`ft/deep`, `hamt/bitmap`, a page with a ref). Those bytes hold the
   children's hashes, not their measures. See the spec, §3.3."
  [st {:keys [enc hash entry literal]}]
  (case enc
    :literal
    (do
      (when (has-ref? (:body literal))
        (bad! :bad-item "a literal in an item stream holds no ref" {:hash hash}))
      (let [got (try (pack/materialize-literal! (store/mem-store) (:type literal) (:body literal))
                     (catch Throwable e
                       (bad! :bad-entry (str "literal does not realize: " (ex-message e))
                             {:hash hash})))]
        (when-not (= got hash)
          (bad! :bad-hash "literal does not hash to its claimed hash" {:hash hash}))
        #{:hash :literal}))

    :node
    (verify-node! st hash entry)))

(defn verify-item!
  "Check one decoded wire-v1 item from its own bytes, and from what is
   already stored in `st`. Returns the set of checks it passed (`#{:pending}`
   for a collection header whose root has not arrived), or throws
   {:reason ...}: `:bad-hash`, `:bad-entry`, `:bad-item`. A lookup that finds
   nothing is `:bad-entry` (a measure that cannot be recomputed), never a raw
   store error. Nothing is stored, and nothing is waited for."
  [st item]
  (try (verify-item*! st item)
       (catch clojure.lang.ExceptionInfo e
         (if (missing? e)
           (bad! :bad-entry "the item names an entry that is not stored"
                 {:hash (:hash item) :missing (:hash (ex-data e))})
           (throw e)))))

;; =============================================================================
;; Receiving
;; =============================================================================

(defn receiver
  "A receiver of items: {:store st :pending atom}. `:pending` maps the hash
   of a root to the collection headers (hash -> entry) that wait for it."
  ([] (receiver (store/mem-store)))
  ([st] {:store st :pending (atom {})}))

(defn unchecked
  "Hashes of the collection headers that are stored but whose root has not
   arrived, so whose hash, count and size are not yet checked."
  [{:keys [pending]}]
  (into [] (comp (mapcat vals) (map :hash)) (vals @pending)))

(defn discard-unchecked!
  "Remove the unchecked headers from the store. A session that ends before
   their roots arrive (a failure, a `cancel`, a PARTIAL stream) calls this,
   so the store holds only what was verified."
  [{:keys [store pending] :as rx}]
  (doseq [{:keys [hash]} (mapcat vals (vals @pending))]
    (store/await (store/s-delete store hash)))
  (reset! pending {})
  rx)

(defn- stored? [st h] (some? (store/await (store/s-get st h))))

(defn- resolve-pending!
  "Check the waiting headers whose root is stored now. A header that fails
   is removed from the store before the error is thrown."
  [{:keys [store pending]}]
  (loop []
    (when-let [[rk waiting]
               (first (filter (fn [[_ ws]] (stored? store (:root (val (first ws)))))
                              @pending))]
      (swap! pending dissoc rk)
      (doseq [[hk {:keys [hash entry]}] waiting
              :let [[type-name data] entry
                    m (root-measure store type-name (:root data))]]
        (try (header-check type-name data hash m)
             (catch clojure.lang.ExceptionInfo e
               (store/await (store/s-delete store hash))
               (throw e))))
      (recur))))

(defn accept-item!
  "Verify one decoded item, then store it. Throws and stores nothing when
   it fails. Returns the checks it passed.

   A collection header comes before its pages, so it is stored now and its
   check is `#{:pending}` until its root arrives. Then the arrival of the
   root checks it, and a failure there removes the header and throws, so the
   session ends with `bad-hash` or `bad-entry`. A header already stored is
   not stored again."
  [{:keys [store pending] :as rx} {:keys [enc hash entry literal] :as item}]
  (let [header? (and (= :node enc) (collection-type? (types/entry-type entry)))]
    (if (and header? (stored? store hash))
      #{:known}
      (let [checks (verify-item! store item)]
        (case enc
          :node (store/await (store/s-put store hash entry))
          :literal (pack/materialize-literal! store (:type literal) (:body literal)))
        (when (contains? checks :pending)
          (swap! pending update (gc/hash-key (:root (types/entry-data entry)))
                 assoc (gc/hash-key hash)
                 {:hash hash :entry entry :root (:root (types/entry-data entry))}))
        (when (seq @pending) (resolve-pending! rx))
        checks))))

(defn accept-items!
  "Accept decoded items in the order they arrived. Each is checked, and
   stored, before the next is read: nothing is held back. The first
   failure throws; the items before it stay stored (each is verified, or is
   a header still waiting for its root: see `unchecked`). `rx` is a
   `receiver`, or nil for a new one. Returns the receiver."
  [rx items]
  (let [rx (or rx (receiver))]
    (doseq [item items] (accept-item! rx item))
    rx))

(defn finish!
  "The stream ended with FIN and not PARTIAL: every header's root must have
   arrived. Throws `:bad-entry` when one has not, after removing the
   unchecked headers. Returns the receiver."
  [rx]
  (let [left (unchecked rx)]
    (when (seq left)
      (discard-unchecked! rx)
      (bad! :bad-entry "a collection header's root never arrived"
            {:hashes left}))
    rx))
