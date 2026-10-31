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
   - an order a receiver can verify as items arrive: a collection's own
     pages come before its header (see `verifiable-order`).
   - `verify-item!`: each item is checked from its own bytes, and from
     nothing that has not already been stored. Nothing waits and nothing
     is held back. See the namespace-level notes on what that covers."
  (:require [clojure.string :as str]
            [dacite.hash :as hash]
            [dacite.peer.frame :as frame]
            [dacite.rooted.gc :as gc]
            [dacite.reveal :as reveal]
            [dacite.service.reveal :as sr]
            [dacite.store :as store]
            [dacite.store.pack :as pack]
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

(defn- has-ref?
  "True when a literal body names a child by hash (type `ref`). A peer
   literal is self-contained: it holds no ref."
  [body]
  (boolean (some #(and (map? %) (= "ref" (str (:type %)))) (tree-seq coll? seq body))))

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

(defn verifiable-order
  "`hashes` (a `reveal/page-order`, header first) with each collection
   header moved after its own structural pages (`ft/*`, `hamt/*`).

   A page carries its own measure, so it can be checked when it arrives.
   A collection header holds only the hash of its root, so it can be
   checked once the root's measure is known. Sending the root's pages
   first means the receiver already holds them when the header arrives.
   A header whose root is a bare leaf has no pages to wait for."
  [st hashes]
  (let [type-of (fn [h] (types/entry-type (store/require-node st h)))]
    (loop [todo (seq hashes) out []]
      (if-not todo
        out
        (let [h (first todo)]
          (if (collection-type? (type-of h))
            (let [[pages more] (split-with #(structural-type? (type-of %)) (rest todo))]
              (recur (seq more) (-> out (into pages) (conj h))))
            (recur (next todo) (conj out h))))))))

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
         ;; decide in reveal order (header first), then reorder for verification
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
         by-hash (into {} (map (juxt (comp gc/hash-key :hash) identity)) picks)
         sent (verifiable-order st (mapv :hash picks))
         picks (mapv #(get by-hash (gc/hash-key %)) sent)]
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

(defn- header-check
  "Check a collection header against the measure of its root, which is
   already stored, or is a bare leaf (its elements-fuse is its own hash).
   Returns the set of checks done, or nil when the root is not here yet."
  [st type-name {:keys [root count size-bytes]} claimed]
  (let [measure (try (case type-name
                       ("string" "blob" "vector") (ft/ft-measure st root)
                       ("map" "set") (hamt/hamt-measure st root))
                     (catch clojure.lang.ExceptionInfo e
                       (if (missing? e) nil (throw e))))]
    (cond
      measure
      (do
        (when-not (= claimed (types/value-hash type-name (:elements-fuse measure)))
          (bad! :bad-hash "collection hash does not match its root" {:hash claimed}))
        (when-not (and (= count (:count measure)) (= size-bytes (:size-bytes measure)))
          (bad! :bad-entry "collection count or size differs from its root"
                {:hash claimed}))
        #{:hash :root-measure})

      ;; root not stored: only a bare leaf root can be checked from the header
      (and (contains? #{"string" "blob" "vector"} type-name)
           (= claimed (types/value-hash type-name root)))
      #{:hash :leaf-root}

      :else nil)))

(defn- page-body [data]
  (:body data))

(defn- verify-node!
  [st hash [type-name data :as entry]]
  (cond
    (collection-type? type-name)
    (or (header-check st type-name data hash)
        (bad! :out-of-order
              "a collection header arrived before its root; send the root's pages first"
              {:hash hash}))

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

(defn verify-item!
  "Check one decoded wire-v1 item (`bin/decode-item`) from its own bytes.

   Returns the set of checks it passed, and throws {:reason ...} when one
   fails: `:bad-hash`, `:bad-entry`, `:bad-item`, or `:out-of-order`.
   Nothing is stored, and nothing is waited for. `st` is read only, for
   what earlier items put there.

   - literal: rebuilt in a scratch store; its hash must equal the item's.
     It is complete, so nothing is left unchecked. No ref is allowed.
   - scalar: its hash from its data.
   - page (`ft/*`, `hamt/*`): its hash from its claimed measure. A digit
     or node page with no ref must also have the measure of its contents,
     and an entry the elements-fuse of its key and value.
   - collection header: its hash and count from its root's measure; the
     root is stored already (see `verifiable-order`).

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

(defn accept-item!
  "Verify one decoded item, then store it. Throws and stores nothing when
   it fails. Returns the checks it passed."
  [st {:keys [enc hash entry literal] :as item}]
  (let [checks (verify-item! st item)]
    (case enc
      :node (store/await (store/s-put st hash entry))
      :literal (pack/materialize-literal! st (:type literal) (:body literal)))
    checks))

(defn accept-items!
  "Accept decoded items in the order they arrived. Each is checked, and
   stored, before the next is read: nothing is held back. The first
   failure throws; the items before it stay stored (each is verified).
   Returns `st` (a new mem-store when nil)."
  [st items]
  (let [st (or st (store/mem-store))]
    (doseq [item items] (accept-item! st item))
    st))
