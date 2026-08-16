(ns dacite.rooted
  "Rooted store — a content store plus a single mutable root hash.

   Portable core (cross-language contract):
     (root rs)                 — current root hash, or nil
     (cas-root! rs expected new) — compare-and-set; returns true/false
     (set-root! rs new)        — local unconditional set (not for remote)

   On the JVM, RootedStore also implements IDeref/IAtom/IRef so `@store`,
   `reset!`, `swap!`, and `compare-and-set!` work as conveniences. SCI
   hosts (babashka, nbb) and future language ports use the function API.

   Root durability is an IRootCell: mem (ephemeral), file (host fs), or
   LMDB meta (JVM only via dacite.store.jvm/lmdb-root-cell).

   See docs/book/04-rooted-stores/chapter.md."
  (:require [dacite.rooted.gc :as gc]
            [dacite.store :as store]
            [clojure.string :as str]
            ;; java.io on JVM + babashka (file-root-cell). LMDB root cells
            ;; live in dacite.store.jvm so this ns stays free of native deps.
            #?@(:cljs []
                :default [[clojure.java.io :as io]])))

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

;; -----------------------------------------------------------------------------
;; File root cell — same layout on JVM and Node: {base}/ROOT holds hex or empty
;; -----------------------------------------------------------------------------

(defn- root-file-path [base]
  #?(:cljs (let [path (js/require "path")]
             (.join path (str base) "ROOT"))
     :default (io/file base "ROOT")))

(defn- read-root-file [base]
  #?(:cljs
     (let [fs (js/require "fs")
           p (root-file-path base)]
       (when (.existsSync fs p)
         (let [s (str/trim (.readFileSync fs p "utf8"))]
           (when (seq s)
             (store/hex->hash s)))))
     :default
     (let [f (root-file-path base)]
       (when (.exists f)
         (let [s (str/trim (slurp f))]
           (when (seq s)
             (store/hex->hash s)))))))

(defn- write-root-file! [base h]
  #?(:cljs
     (let [fs (js/require "fs")
           p (root-file-path base)]
       (.mkdirSync fs (str base) #js {:recursive true})
       (.writeFileSync fs p (if h (store/hash->hex h) "") "utf8"))
     :default
     (let [dir (io/file base)
           f (root-file-path base)]
       (when-not (.exists dir)
         (.mkdirs dir))
       (if h
         (spit f (store/hash->hex h))
         (spit f "")))))

(defrecord FileRootCell [base]
  IRootCell
  (rc-get [_] (read-root-file base))
  (rc-put! [this h]
    (write-root-file! base h)
    this))

(defn file-root-cell
  "Durable root cell: hex hash in `{base}/ROOT` (empty file = unset).

   Works on the JVM (java.io) and under nbb (Node fs). Pair with a
   file-backed content store at the same base path."
  [base]
  (->FileRootCell (str base)))

;; =============================================================================
;; Rooted store
;; =============================================================================

(defprotocol IRoot
  "A store that exposes the Chapter 4 root cell (one mutable hash).

   Local `RootedStore` and remote HTTP wrappers both implement this so
   `root` / `cas-root!` / `set-root!` and `dacite.value/root-ref` work
   without dropping to hashes in application code."
  (-root [this]
    "Current root hash, or nil if unset.")
  (-cas-root! [this expected new]
    "Compare-and-set the root. Returns true on success.")
  (-set-root! [this new]
    "Unconditional set. Local convenience — remote implementations throw."))

(defn- validate! [this v]
  (when-let [vf @(:validator this)]
    (when-not (vf v)
      (throw #?(:clj (IllegalStateException. "Invalid reference state")
                :cljs (js/Error. "Invalid reference state")))))
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

;; Used only from JVM IAtom/IAtom2 methods (reader-conditional body).
#_{:clj-kondo/ignore [:unused-private-var]}
(defn- swap* [this f args]
  (let [root-atom (:root-atom this)
        wrapped (fn [v]
                  (validate! this (apply-f f v args)))
        [old new] (swap-vals! root-atom wrapped)]
    (commit! this old new)
    new))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- swap-vals* [this f args]
  (let [root-atom (:root-atom this)
        wrapped (fn [v]
                  (validate! this (apply-f f v args)))
        [old new] (swap-vals! root-atom wrapped)]
    (commit! this old new)
    (vector old new)))

(defrecord RootedStore [content root-atom cell watches validator]
  IRoot
  (-root [_] @root-atom)
  (-cas-root! [this expected new]
    (validate! this new)
    (loop []
      (let [current @root-atom]
        (if (not= expected current)
          false
          (if (compare-and-set! root-atom current new)
            (do (commit! this current new) true)
            (recur))))))
  (-set-root! [this new]
    (validate! this new)
    (let [[old new'] (reset-vals! root-atom new)]
      (commit! this old new')
      new'))

  store/IStore
  (s-get [_ h] (store/s-get content h))
  (s-put [this h value]
    (store/s-put content h value)
    this)
  (s-has? [_ h] (store/s-has? content h))
  (s-delete [this h]
    (store/s-delete content h)
    this)
  (s-snapshot [_] (store/s-snapshot content))
  (s-merge [this m]
    (store/s-merge content m)
    this)
  (s-reset [this]
    (store/s-reset content)
    this)

  #?@(:bb []
      :clj
      [clojure.lang.IDeref
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
                 (swap-vals* this f (into [arg1 arg2] args)))]))

;; =============================================================================
;; Portable function API (canonical for SCI + future language ports)
;; =============================================================================

(defn root
  "Current root hash, or nil if unset."
  [rs]
  (-root rs))

(defn cas-root!
  "Compare-and-set the root. Succeeds only when the current root equals
   expected by value (including nil) — hash vectors compare with `=`, not
   identity, so this is safe for hex round-trips and language ports.
   Persists via the root cell (local) or the server CAS (remote).
   Returns true on success, false on conflict."
  [rs expected new]
  (-cas-root! rs expected new))

(defn set-root!
  "Unconditionally set the root (local convenience). Not offered for
   remote stores under concurrency — those implementations throw.
   Use cas-root! instead."
  [rs new]
  (-set-root! rs new))

(defn update-root!
  "Apply f to the current root (and optional args), CAS-retrying until
   success. Returns the new root. Client-side convenience over cas-root!."
  [rs f & args]
  (loop []
    (let [old (root rs)
          new (apply f old args)]
      (if (cas-root! rs old new)
        new
        (recur)))))

(defn add-root-watch
  "Register watch fn of (fn [k rs old new]). Returns rs."
  [rs k f]
  (swap! (:watches rs) assoc k f)
  rs)

(defn remove-root-watch
  "Remove a watch by key. Returns rs."
  [rs k]
  (swap! (:watches rs) dissoc k)
  rs)

(defn set-root-validator!
  "Set a validator predicate consulted before installing a new root.
   Local-only convenience. Returns rs."
  [rs f]
  (reset! (:validator rs) f)
  rs)

(defn rooted-store
  "Wrap a content store with a mutable root.

   One-arg form uses an ephemeral mem-root-cell. Two-arg form accepts a
   root cell for durability (file-root-cell, store.jvm/lmdb-root-cell)."
  ([content] (rooted-store content (mem-root-cell)))
  ([content cell]
   (->RootedStore content (atom (rc-get cell)) cell (atom {}) (atom nil))))

;; =============================================================================
;; Sync + GC
;; =============================================================================

(defn push-ref
  "Move target's root to source's root via cas-root! on target.
   Returns true on success, false if target's root moved concurrently.
   Content must already be present at target or synced separately."
  [source target]
  (cas-root! target (root target) (root source)))

(defn collect-garbage!
  "Remove content-store entries not reachable from the current root.
   Returns {:removed n :kept n}."
  ([rs]
   (collect-garbage! rs (root rs)))
  ([rs root-hash]
   (gc/collect-garbage! (:content rs) root-hash)))
