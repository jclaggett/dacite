(ns dacite.rooted-store
  "Rooted store — a content store plus a single mutable root hash.

   The portable core is two operations: read the root (`@store`) and
   compare-and-set (`compare-and-set!`). Optional conveniences on a local
   store include `reset!`, `swap!`, watches, and validators.

   See docs/book/04-rooted-stores/chapter.md."
  (:require [dacite.store :as store]))

;; =============================================================================
;; Root cell — durable root persistence
;; =============================================================================

(defprotocol IRootCell
  (rc-get [this] "Persisted root hash, or nil.")
  (rc-put! [this h] "Persist root hash. Returns this."))

(defrecord MemRootCell [a]
  IRootCell
  (rc-get [_] @a)
  (rc-put! [this h]
    (reset! a h)
    this))

(defn mem-root-cell
  "Ephemeral root cell (atom only)."
  ([] (->MemRootCell (atom nil)))
  ([init] (->MemRootCell (atom init))))

(defrecord LmdbRootCell [lmdb k]
  IRootCell
  (rc-get [_] (store/lmdb-get-meta lmdb k))
  (rc-put! [this h]
    (store/lmdb-put-meta! lmdb k h)
    this))

(defn lmdb-root-cell
  "Durable root cell backed by an LMDB store's meta database."
  ([lmdb] (lmdb-root-cell lmdb "root"))
  ([lmdb k] (->LmdbRootCell lmdb k)))

;; =============================================================================
;; Rooted store
;; =============================================================================

(defn- validate! [this v]
  (when-let [vf @(:validator this)]
    (when-not (vf v)
      (throw (IllegalStateException. "Invalid reference state"))))
  v)

(defn- commit! [this old new]
  (rc-put! (:cell this) new)
  (doseq [[k f] @(:watches this)]
    (f k this old new))
  new)

(defn- apply-f [f v args]
  (case (count args)
    0 (f v)
    1 (f v (nth args 0))
    2 (f v (nth args 0) (nth args 1))
    (apply f v args)))

(defn- swap* [this f args]
  (let [root-atom (:root-atom this)
        wrapped (fn [v]
                  (validate! this (apply-f f v args)))
        [old new] (swap-vals! root-atom wrapped)]
    (commit! this old new)
    new))

(defn- swap-vals* [this f args]
  (let [root-atom (:root-atom this)
        wrapped (fn [v]
                  (validate! this (apply-f f v args)))
        [old new] (swap-vals! root-atom wrapped)]
    (commit! this old new)
    (vector old new)))

(defrecord RootedStore [content root-atom cell watches validator]
  store/IStore
  (s-get [this h] (store/s-get content h))
  (s-put [this h value]
    (store/s-put content h value)
    this)
  (s-has? [_ h] (store/s-has? content h))
  (s-snapshot [_] (store/s-snapshot content))
  (s-merge [this m]
    (store/s-merge content m)
    this)
  (s-reset [this]
    (store/s-reset content)
    this)

  clojure.lang.IDeref
  (deref [_] @root-atom)

  clojure.lang.IRef
  (addWatch [_ k f]
    (swap! watches assoc k f)
    nil)
  (removeWatch [_ k]
    (swap! watches dissoc k)
    nil)
  (getWatches [_] @watches)
  (setValidator [_ f]
    (reset! validator f)
    nil)
  (getValidator [_] @validator)

  clojure.lang.IAtom
  (compareAndSet [this expected new]
    (validate! this new)
    (if (compare-and-set! root-atom expected new)
      (do (commit! this expected new) true)
      false))
  (reset [this new]
    (validate! this new)
    (let [[old new] (reset-vals! root-atom new)]
      (commit! this old new)
      new))
  (swap [this f] (swap* this f []))
  (swap [this f arg] (swap* this f [arg]))
  (swap [this f arg1 arg2] (swap* this f [arg1 arg2]))
  (swap [this f arg1 arg2 args]
    (swap* this f (into [arg1 arg2] args)))

  clojure.lang.IAtom2
  (resetVals [this new]
    (validate! this new)
    (let [[old new] (reset-vals! root-atom new)]
      (commit! this old new)
      (vector old new)))
  (swapVals [this f] (swap-vals* this f []))
  (swapVals [this f arg] (swap-vals* this f [arg]))
  (swapVals [this f arg1 arg2] (swap-vals* this f [arg1 arg2]))
  (swapVals [this f arg1 arg2 args]
    (swap-vals* this f (into [arg1 arg2] args))))

(defn rooted-store
  "Wrap a content store with a mutable root.

   One-arg form uses an ephemeral mem-root-cell. Two-arg form accepts a
   root cell for durability (e.g. lmdb-root-cell)."
  ([content] (rooted-store content (mem-root-cell)))
  ([content cell]
   (->RootedStore content (atom (rc-get cell)) cell (atom {}) (atom nil))))

;; =============================================================================
;; Sync
;; =============================================================================

(defn push-ref
  "Move target's root to source's root via compare-and-set on target.
   Returns true on success, false if target's root moved concurrently.
   Content must already be present at target or synced separately."
  [source target]
  (compare-and-set! target @target @source))
