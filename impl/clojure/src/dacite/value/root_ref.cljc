(ns dacite.value.root-ref
  "Value-level root reference over a rooted store.

   The store layer keeps a single mutable **hash** (Chapter 4). This type
   presents that cell as a Dacite **value** for application code:

     @ref / (ref-deref ref)           → current value or nil
     (reset! ref v) / (ref-reset! …)  → install value (or nil)
     (swap! ref f) / (ref-swap! …)    → CAS-retry over values
     watches                          → old/new are values (or nil)

   Portable hosts (SCI) use the function API; the JVM also implements
   IDeref / IAtom / IRef so clojure.core ops work."
  (:require [dacite.rooted :as rs]
            [dacite.store :as store]
            [dacite.value.types :as types]
            ;; Register wrap-entry methods for scalars / collections
            [dacite.value.scalar]
            [dacite.value.collections])
  #?@(:bb []
      :clj [(:import [clojure.lang IDeref IAtom IAtom2 IRef])]))

(defn- wrap-at
  "Rehydrate hash h from content store st as a Dacite value, or nil."
  [st h]
  (when h
    (when-let [entry (store/s-get st h)]
      (types/wrap-entry (types/entry-type entry) st h))))

(defn- value->root-hash
  "Install v into st if needed; return its content hash, or nil for nil v."
  [st v]
  (cond
    (nil? v) nil
    (satisfies? types/IDaciteValue v) (types/extract-hash st v)
    :else
    (throw (ex-info "Root ref expects a Dacite value or nil"
                    {:value v}))))

(declare notify-watches!)

(deftype RootRef [store watches]
  types/IStoreCarrier
  (carrier-store [_] store)

  #?@(:bb []
      :clj
      [IDeref
       (deref [this]
              (wrap-at store (rs/root store)))

       IRef
       (addWatch [this k f]
                 (swap! watches assoc k f)
                 this)
       (removeWatch [this k]
                    (swap! watches dissoc k)
                    this)
       (getWatches [_] @watches)
       (setValidator [_ _]
                     (throw (UnsupportedOperationException.
                             "RootRef validators are not supported; use store-level validators")))
       (getValidator [_] nil)

       IAtom
       (compareAndSet [this expected new]
                      (let [eh (value->root-hash store expected)
                            nh (value->root-hash store new)
                            ok (rs/cas-root! store eh nh)]
                        (when ok
                          (notify-watches! this eh nh))
                        ok))
       (reset [this new]
              (let [old-h (rs/root store)
                    nh (value->root-hash store new)]
                (rs/set-root! store nh)
                (notify-watches! this old-h nh)
                (wrap-at store nh)))
       (swap [this f]
             (loop []
               (let [old-h (rs/root store)
                     old-v (wrap-at store old-h)
                     new-v (f old-v)
                     nh (value->root-hash store new-v)]
                 (if (rs/cas-root! store old-h nh)
                   (do (notify-watches! this old-h nh)
                       (wrap-at store nh))
                   (recur)))))
       (swap [this f a]
             (loop []
               (let [old-h (rs/root store)
                     old-v (wrap-at store old-h)
                     new-v (f old-v a)
                     nh (value->root-hash store new-v)]
                 (if (rs/cas-root! store old-h nh)
                   (do (notify-watches! this old-h nh)
                       (wrap-at store nh))
                   (recur)))))
       (swap [this f a b]
             (loop []
               (let [old-h (rs/root store)
                     old-v (wrap-at store old-h)
                     new-v (f old-v a b)
                     nh (value->root-hash store new-v)]
                 (if (rs/cas-root! store old-h nh)
                   (do (notify-watches! this old-h nh)
                       (wrap-at store nh))
                   (recur)))))
       (swap [this f a b args]
             (loop []
               (let [old-h (rs/root store)
                     old-v (wrap-at store old-h)
                     new-v (apply f old-v a b args)
                     nh (value->root-hash store new-v)]
                 (if (rs/cas-root! store old-h nh)
                   (do (notify-watches! this old-h nh)
                       (wrap-at store nh))
                   (recur)))))

       IAtom2
       (resetVals [this new]
                  (let [old-h (rs/root store)
                        nh (value->root-hash store new)]
                    (rs/set-root! store nh)
                    (notify-watches! this old-h nh)
                    [(wrap-at store old-h) (wrap-at store nh)]))
       (swapVals [this f]
                 (loop []
                   (let [old-h (rs/root store)
                         old-v (wrap-at store old-h)
                         new-v (f old-v)
                         nh (value->root-hash store new-v)]
                     (if (rs/cas-root! store old-h nh)
                       (do (notify-watches! this old-h nh)
                           [old-v (wrap-at store nh)])
                       (recur)))))
       (swapVals [this f a]
                 (loop []
                   (let [old-h (rs/root store)
                         old-v (wrap-at store old-h)
                         new-v (f old-v a)
                         nh (value->root-hash store new-v)]
                     (if (rs/cas-root! store old-h nh)
                       (do (notify-watches! this old-h nh)
                           [old-v (wrap-at store nh)])
                       (recur)))))
       (swapVals [this f a b]
                 (loop []
                   (let [old-h (rs/root store)
                         old-v (wrap-at store old-h)
                         new-v (f old-v a b)
                         nh (value->root-hash store new-v)]
                     (if (rs/cas-root! store old-h nh)
                       (do (notify-watches! this old-h nh)
                           [old-v (wrap-at store nh)])
                       (recur)))))
       (swapVals [this f a b args]
                 (loop []
                   (let [old-h (rs/root store)
                         old-v (wrap-at store old-h)
                         new-v (apply f old-v a b args)
                         nh (value->root-hash store new-v)]
                     (if (rs/cas-root! store old-h nh)
                       (do (notify-watches! this old-h nh)
                           [old-v (wrap-at store nh)])
                       (recur)))))

       Object
       (toString [this]
                 (str "#dacite/root-ref[" (store/hash->hex (rs/root store)) "]"))]))

(defn- ref-store
  "Content/rooted store inside a RootRef."
  [^RootRef r]
  (.-store r))

(defn- ref-watches
  [^RootRef r]
  (.-watches r))

(defn- notify-watches!
  "Fire value-level watches with (k ref old-val new-val)."
  [r old-h new-h]
  (let [watches (ref-watches r)]
    (when (seq @watches)
      (let [st (ref-store r)
            old-v (wrap-at st old-h)
            new-v (wrap-at st new-h)]
        (doseq [[k f] @watches]
          (f k r old-v new-v))))))

(defn root-ref
  "Wrap a rooted store as a value-level root reference.

   `rooted` must implement `dacite.rooted/IRoot` (`root`, `cas-root!`,
   `set-root!`) — a local `store/rooted-store` or a
   `store/remote-rooted-store`. `ref-reset!` / `set-root!` throw on a
   remote store; seed with `ref-cas!` from nil and update with `ref-swap!`."
  [rooted]
  (->RootRef rooted (atom {})))

(defn root-ref?
  "True if x is a RootRef."
  [x]
  (instance? RootRef x))

;; =============================================================================
;; Portable function API (SCI / nbb / babashka / future ports)
;; =============================================================================

(defn ref-deref
  "Current root value, or nil if unset."
  [r]
  (let [st (ref-store r)]
    (wrap-at st (rs/root st))))

(defn ref-reset!
  "Unconditionally set the root to Dacite value v (or nil). Returns the new value.

   Local-only. On a remote rooted store this throws — use `ref-cas!` (seed
   from nil) or `ref-swap!` (shared update)."
  [r v]
  (let [st (ref-store r)
        old-h (rs/root st)
        nh (value->root-hash st v)]
    (rs/set-root! st nh)
    (notify-watches! r old-h nh)
    (wrap-at st nh)))

(defn ref-swap!
  "Apply f to the current root value (and optional args), CAS-retrying until
   success. f must return a Dacite value or nil. Returns the new value."
  ([r f]
   (let [st (ref-store r)]
     (loop []
       (let [old-h (rs/root st)
             old-v (wrap-at st old-h)
             new-v (f old-v)
             nh (value->root-hash st new-v)]
         (if (rs/cas-root! st old-h nh)
           (do (notify-watches! r old-h nh)
               (wrap-at st nh))
           (recur))))))
  ([r f a]
   (ref-swap! r (fn [v] (f v a))))
  ([r f a b]
   (ref-swap! r (fn [v] (f v a b))))
  ([r f a b & more]
   (ref-swap! r (fn [v] (apply f v a b more)))))

(defn ref-cas!
  "Compare-and-set at the value level. expected and new are Dacite values or nil.
   Returns true on success."
  [r expected new]
  (let [st (ref-store r)
        eh (value->root-hash st expected)
        nh (value->root-hash st new)
        ok (rs/cas-root! st eh nh)]
    (when ok
      (notify-watches! r eh nh))
    ok))

(defn ref-add-watch
  "Register watch fn of (fn [k ref old-val new-val]). Returns ref."
  [r k f]
  (swap! (ref-watches r) assoc k f)
  r)

(defn ref-remove-watch
  "Remove a watch by key. Returns ref."
  [r k]
  (swap! (ref-watches r) dissoc k)
  r)
