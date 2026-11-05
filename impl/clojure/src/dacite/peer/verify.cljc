(ns dacite.peer.verify
  "Peer protocol v1: verification of items, and the receiver that stores them.
   Portable (.cljc): the same code runs on the JVM and on nbb.

   Spec: docs/spec/peer-v1.md §3.3 and §12.8 (C27 to C33).

   Every item is checked from its own bytes, and from what is already
   stored, **before** it is stored: `accept-item!` verifies and then puts,
   and throws and stores nothing when the check fails. No other path puts a
   received item in the store. The one thing that cannot be checked alone is
   a collection header; it is stored on arrival and tracked as *unchecked*
   (`unchecked`) until its root arrives (C31).

   A failure is an ex-info with `:reason` `:bad-hash`, `:bad-entry` or
   `:bad-item` (or `:bad-state` from the wire decoder). The session that
   reads one ends (`dacite.peer.client`, C13).

   **Store calls may be asynchronous (M1.3).** The functions that touch the
   store (`accept-item!`, `accept-items!`, `checked?`, `discard-unchecked!`,
   `finish!`) chain on store promises (`dacite.store.chain`): each returns a
   *step*, the plain value when the store answered at once (the in-memory store
   always does) or a `Deferred` when it did not (`chain/native` makes a host
   promise of it). The order is the same either way: an item is verified, then
   stored, before the next is read. A store call that fails is `ex-info`
   `:reason :store-failed`, which is not a verification failure: the item is
   not stored and the sender did nothing wrong. `verify-item!` itself is
   synchronous and reads only."
  (:require [dacite.host :as host]
            [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [dacite.store.chain :as chain]
            [dacite.store.pack :as pack]
            [dacite.value.finger-tree :as ft]
            [dacite.value.hamt :as hamt]
            [dacite.value.lit :as lit]
            [dacite.value.types :as types]))

(defn- collection-type? [t] (contains? #{"string" "blob" "vector" "map" "set"} t))

(def ^:private has-ref?
  "True when a literal body names a child by hash (type `ref`, or a `run` or
   `repeat` of `ref`). A peer literal is self-contained: it holds none."
  lit/names-child-hash?)

;; =============================================================================
;; Checking
;; =============================================================================

(defn- bad!
  [reason msg data]
  (throw (ex-info msg (assoc data :reason reason))))

(defn- missing? [e]
  (and (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e)
       (= :missing (:reason (ex-data e)))))

(defn- collection-hash
  "Value hash of a collection node, or nil when a child is not stored yet."
  [st type-name root]
  (when-let [ef (try (case type-name
                       ("string" "blob" "vector") (ft/ft-elements-fuse st root)
                       ("map" "set") (hamt/hamt-elements-fuse st root)
                       nil)
                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                       (if (= :missing (:reason (ex-data e))) nil (throw e))))]
    (types/value-hash type-name ef)))

(defn expected-hash
  "Content hash of a stored node, or nil when a child it needs is absent.

   Structural nodes carry `elements-fuse` in their measure. A bitmap node
   also folds its bitmap and split, matching `hamt/bitmap-node-hash`. A
   collection header uses the elements fuse of its root. A scalar uses
   `scalar-value-hash`."
  [st entry]
  (let [type-name (first entry)
        data (second entry)]
    (cond
      (and (map? data) (contains? (:measure data) :elements-fuse))
      (let [ef (:elements-fuse (:measure data))]
        (if (= "hamt/bitmap" type-name)
          (hamt/bitmap-node-hash ef (:bitmap data) (:split data))
          (types/node-hash type-name ef)))

      (collection-type? type-name)
      (collection-hash st type-name (:root data))

      :else
      (types/scalar-value-hash entry))))

(defn- root-measure
  "The measure of a collection header's root, or nil when the root is not
   stored (yet). The root is a page, or a bare leaf value that is itself the
   one element."
  [st type-name root]
  (try (case type-name
         ("string" "blob" "vector") (ft/ft-measure st root)
         ("map" "set") (hamt/hamt-measure st root))
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
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
    (let [got (expected-hash st entry)]
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

        ;; telescoping (issue #12): two or more children, one per bit, and a
        ;; split that is a hash chunk index
        (= "hamt/bitmap" type-name)
        (as-> checks
              (do (when-not (and (integer? (:split data))
                                 (<= 0 (:split data) hamt/LAST-CHUNK)
                                 (<= 2 (count (:children data)))
                                 (= (count (:children data))
                                    (host/popcount (:bitmap data)))
                                 ;; chunk 51 is one bit, read as 0 or 16: only
                                 ;; those two bits are chunk values there
                                 (or (not= hamt/LAST-CHUNK (:split data))
                                     (= (:bitmap data) 0x10001)))
                    (bad! :bad-entry "hamt/bitmap needs a split, two or more children, one per bit (bits 0 and 16 at split 51)"
                          {:hash hash}))
                  (conj checks :bitmap-shape)))

        ;; key-hash routes the entry (assoc, lookup, first-key-hash) and is in no
        ;; hash; the library always writes it equal to key-ref
        (= "hamt/entry" type-name)
        (as-> checks
              (do (when-not (= (:key-hash data) (:key-ref data))
                    (bad! :bad-entry "hamt/entry key-hash is not its key-ref" {:hash hash}))
                  (when-not (= (:elements-fuse (:measure data))
                               (hamt/entry-fuse (:key-ref data) (:val-ref data)))
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
   - page (`ft/*`, `hamt/*`): its hash from its claimed measure (a bitmap
     node also from its bitmap and split, and its shape is checked: a split
     of 0..51, two or more children, one per bit, and at split 51 the bits 0
     and 16; an entry's `key-hash` must be its `key-ref`). A digit
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
                     (catch #?(:clj Throwable :cljs :default) e
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
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
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

(defn- stored? [view h] (some? (store/await (store/s-get view h))))

(defn- checked-now? [st h]
  (if-let [[type-name data :as entry] (store/await (store/s-get st h))]
    (if (collection-type? type-name)
      (boolean (try (when-let [m (root-measure st type-name (:root data))]
                      (header-check type-name data h m)
                      true)
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                      (if (chain/miss? e) (throw e) false))))
      (some? entry))
    false))

(defn checked?
  "True when `h` is stored and verified. A node that is not a collection header
   is verified when it is stored. A header is verified when its root is stored
   and its hash, count and size match that root: a header that only waits for its
   root, or that is forged, is not. A step (see the namespace doc)."
  [st h]
  (chain/with-reads st #(checked-now? % h)))

(defn- put!
  "Store one entry. A step; a store that fails is `:store-failed`."
  [st h entry]
  (chain/store-op #(store/s-put st h entry) h))

(defn- drop-header!
  "Remove a header this receiver stored, unless the store holds something that
   is not this receiver's copy (another session replaced it with its own), or the
   header has meanwhile been verified through its root. The store is shared by
   every session, so one session giving up must not take away what another
   session sent or what was verified. A step."
  [st hash entry]
  (chain/then (chain/store-op #(store/s-get st hash) hash)
              (fn [current]
                (when (= entry current)
                  (chain/then (checked? st hash)
                              (fn [ok]
                                (when-not ok
                                  (chain/store-op #(store/s-delete st hash) hash))))))))

(defn discard-unchecked!
  "Remove this receiver's unchecked headers from the store. A session that ends
   before their roots arrive (a failure, a `cancel`, a PARTIAL stream) calls this,
   so the store holds only what was verified. A header that another session has
   stored in its place, or that is verified by now, stays (`drop-header!`).
   A step of the receiver."
  [{:keys [store pending] :as rx}]
  (let [headers (vec (mapcat vals (vals @pending)))]
    (reset! pending {})
    (chain/then (chain/each headers (fn [{:keys [hash entry]}] (drop-header! store hash entry)))
                (fn [_] rx))))

(defn- check-waiting!
  "Check the headers (hash -> waiting entry) whose root is stored now. Each failing
   header is removed from the store; the honest ones on the same root stay (a
   passing one is stored again, in case another session's giving up removed the
   shared copy). A step of the failures. A store failure is not one: it throws."
  [store waiting]
  (let [failures (atom [])]
    (chain/then
     (chain/each
      (vals waiting)
      (fn [{:keys [hash entry]}]
        (chain/guard
         (fn []
           (chain/then (chain/with-reads store
                         (fn [view]
                           (let [[type-name data] entry
                                 m (root-measure view type-name (:root data))]
                             (header-check type-name data hash m))))
                       (fn [_] (put! store hash entry))))
         (fn [e]
           (if (and (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e)
                    (not (chain/store-failed? e)))
             (chain/then (drop-header! store hash entry)
                         (fn [_] (swap! failures conj e) nil))
             (throw e))))))
     (fn [_] @failures))))

(defn- resolve-pending!
  "Check the waiting headers whose root is stored now. Each failing header is
   removed from the store; the honest ones on the same root stay. One error is
   thrown once all have been checked: a `:bad-hash` before a `:bad-entry`, then
   the lowest hash, so it does not depend on the order of the pending map. A step."
  [{:keys [store pending] :as rx}]
  (chain/then
   (chain/with-reads store
     (fn [view]
       (first (filter (fn [[_ ws]] (stored? view (:root (val (first ws)))))
                      @pending))))
   (fn [found]
     (when-let [[rk waiting] found]
       (swap! pending dissoc rk)
       ;; Every header waiting on this root is checked on its own: one that
       ;; fails is removed, one that passes is verified and stays, even beside
       ;; a forged one (the error is thrown after all are done).
       (chain/then (check-waiting! store waiting)
                   (fn [failures]
                     (when (seq failures)
                       ;; the same one whatever the order of the map: a bad hash first,
                       ;; then by hash (the code that ends the session reads its reason)
                       (throw (first (sort-by (fn [e] [(if (= :bad-hash (:reason (ex-data e))) 0 1)
                                                       (pr-str (:hash (ex-data e)))])
                                              failures))))
                     (resolve-pending! rx)))))))

(defn- literal-nodes
  "[hash entry] of every node a literal realizes to, built in a scratch store."
  [{:keys [type body]}]
  (let [scratch (store/mem-store)]
    (pack/materialize-literal! scratch type body)
    ;; the key is the hash on the JVM, its hex on nbb
    (vec (for [[k v] (store/await (store/s-snapshot scratch))]
           [(if (string? k) (store/hex->hash k) k) v]))))

(defn- store-item!
  "Put what a verified item holds in the store. A step."
  [st {:keys [enc hash entry literal]}]
  (case enc
    :node (put! st hash entry)
    :literal (chain/each (literal-nodes literal) (fn [[h v]] (put! st h v)))))

(defn accept-item!
  "Verify one decoded item, then store it. Throws and stores nothing when
   it fails. Returns the checks it passed (a step, see the namespace doc).

   A collection header comes before its pages, so it is stored now and its
   check is `#{:pending}` until its root arrives. Then the arrival of the
   root checks it, and a failure there removes the header and throws, so the
   session ends with `bad-hash` or `bad-entry`.

   A header is always checked and tracked by the receiver that gets it, even
   when the store already holds that hash: the store is shared by every session,
   and the copy there may be another session's unchecked, possibly forged one.
   Storing this session's copy replaces it, and this session then owes the check
   for its own copy.

   The item is checked before the first store call and stored (every node of a
   literal) before the next item is read; with a store that settles late the
   caller waits for the step. Nothing unverified is ever put."
  [{:keys [store pending] :as rx} {:keys [hash entry] :as item}]
  (chain/then
   (chain/with-reads store #(verify-item! % item))
   (fn [checks]
     (chain/then
      (store-item! store item)
      (fn [_]
        (when (contains? checks :pending)
          (swap! pending update (gc/hash-key (:root (types/entry-data entry)))
                 assoc (gc/hash-key hash)
                 {:hash hash :entry entry :root (:root (types/entry-data entry))}))
        (chain/then (when (seq @pending) (resolve-pending! rx))
                    (fn [_] checks)))))))

(defn accept-items!
  "Accept decoded items in the order they arrived. Each is checked, and
   stored, before the next is read: nothing is held back. The first
   failure throws; the items before it stay stored (each is verified, or is
   a header still waiting for its root: see `unchecked`). `rx` is a
   `receiver`, or nil for a new one. Returns the receiver (a step)."
  [rx items]
  (let [rx (or rx (receiver))]
    (chain/then (chain/each items #(accept-item! rx %)) (fn [_] rx))))

(defn finish!
  "The stream ended with FIN and not PARTIAL: every header's root must have
   arrived. Throws `:bad-entry` when one has not, after removing the
   unchecked headers. Returns the receiver (a step)."
  [rx]
  (let [left (unchecked rx)]
    (if (seq left)
      (chain/then (discard-unchecked! rx)
                  (fn [_] (bad! :bad-entry "a collection header's root never arrived"
                                {:hashes left})))
      rx)))
