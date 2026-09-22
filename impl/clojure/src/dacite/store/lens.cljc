(ns dacite.store.lens
  "Lens over a rooted store — same IRoot + IStore API at a nested path.

   Client convenience: there is still one document cell. A lens talks
   about the value at `path`; `assoc-in` rebuilds the document; the cell
   CASes as today. Empty path is the original store. Nested lenses
   concatenate. Content hashes stay global.

   `path` is a Dacite vector of Dacite keys (a key may itself be any
   value). A host seq is the same sugar `v/get-in` already takes. A
   Dacite vector argument is the path, not a request to focus that
   vector as the document root.

   Not an HTTP path. Over a remote store the lens still CASes the
   document hash."
  (:require [dacite.rooted :as rs]
            [dacite.store :as store]
            [dacite.value :as v]))

(def ^:private int-types
  "Integer scalar type names. Wrapped values are not host `integer?`,
   so vector `nth`/`get` would miss them unless realized."
  #{"i8" "i16" "i32" "i64" "u8" "u16" "u32" "u64"})

(defn- path-key
  "Key suitable for `v/get-in`: realize integer scalars; leave other
   Dacite values (map keys go through `extract-hash`)."
  [k]
  (if (and (v/dacite-value? k) (contains? int-types (v/type k)))
    (v/realize k)
    k))

(defn- path-keys
  "Normalize constructor `path` to a host vector of keys.

   A Dacite vector is walked with `v/seq` (not `clojure.core/seq`).
   A host seq is the `v/get-in` sugar. nil is empty."
  [path]
  (cond
    (nil? path) []
    (and (v/dacite-value? path) (= "vector" (v/type path)))
    (mapv path-key (or (v/seq path) []))
    (sequential? path) (mapv path-key path)
    :else (throw (ex-info "lens path must be a Dacite vector or a host seq"
                          {:path path}))))

(defn- wrap
  "Rehydrate hash `h` from `st`, or nil."
  [st h]
  (when h
    (v/get-value st h)))

(defn- nested-hash
  "Hash of `get-in` at `path` under document hash `doc-h`, or nil."
  [st doc-h path]
  (when doc-h
    (when-let [doc (wrap st doc-h)]
      (let [n (v/get-in doc path ::missing)]
        (when-not (= n ::missing)
          (v/hash n))))))

(defn- nest
  "Map tree whose leaf at `path` is Dacite value `x`."
  [st path x]
  (reduce (fn [child k]
            (v/assoc (v/map st) k child))
          x
          (rseq (vec path))))

(defn- dissoc-in
  [doc path]
  (if (= 1 (count path))
    (v/dissoc doc (first path))
    (let [k (first path)
          child (v/get doc k)]
      (if child
        (v/assoc doc k (dissoc-in child (rest path)))
        doc))))

(defn- install-at
  "Document hash after placing `new-h` (or nil) at `path`."
  [st doc-h path new-h]
  (when (and new-h (nil? (wrap st new-h)))
    (throw (ex-info "lens cas-root!: new hash is not in the store"
                    {:hash new-h :path path})))
  (let [new-v (wrap st new-h)
        doc-v (wrap st doc-h)]
    (cond
      (nil? new-v) (when doc-v (v/hash (dissoc-in doc-v path)))
      (nil? doc-v) (v/hash (nest st path new-v))
      :else (v/hash (v/assoc-in doc-v path new-v)))))

(defn- validate!
  [this v]
  (when-let [vf (some-> this :validator deref)]
    (when-not (vf v)
      (throw #?(:clj (IllegalStateException. "Invalid reference state")
                :cljs (js/Error. "Invalid reference state")))))
  v)

(defn- apply-f [f v args]
  (case (count args)
    0 (f v)
    1 (f v (nth args 0))
    2 (f v (nth args 0) (nth args 1))
    (apply f v args)))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- swap-loop [this f args]
  (loop []
    (let [old (rs/-root this)
          new (validate! this (apply-f f old args))]
      (if (rs/-cas-root! this old new)
        new
        (recur)))))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- swap-vals-loop [this f args]
  (loop []
    (let [old (rs/-root this)
          new (validate! this (apply-f f old args))]
      (if (rs/-cas-root! this old new)
        (vector old new)
        (recur)))))

(defn- maybe-watch-parent!
  "Fan document-root watches into nested-hash watches. No-op on remotes
   that have no local watch table (HTTP watches stay request-scoped)."
  [lens]
  (let [{:keys [base path watches]} lens]
    (when (contains? base :watches)
      (rs/add-root-watch base (gensym "dacite-lens")
                         (fn [_k _rs old-doc new-doc]
                           (let [old-n (nested-hash base old-doc path)
                                 new-n (nested-hash base new-doc path)]
                             (when (not= old-n new-n)
                               (doseq [[k f] @watches]
                                 (f k lens old-n new-n))))))))
  lens)

(defrecord LensStore [base path watches validator]
  rs/IRoot
  (-root [_]
    (nested-hash base (rs/root base) path))

  (-cas-root! [this expected new]
    (validate! this new)
    (if (= expected new)
      (let [cur (nested-hash base (rs/root base) path)]
        (= expected cur))
      (loop []
        (let [doc-h (rs/root base)
              cur (nested-hash base doc-h path)]
          (if (not= expected cur)
            false
            (let [doc-h' (install-at base doc-h path new)]
              (if (rs/cas-root! base doc-h doc-h')
                true
                (recur))))))))

  (-set-root! [this new]
    (validate! this new)
    ;; Remote stores have no root cell; set-root! is not offered.
    (when-not (contains? base :cell)
      (rs/-set-root! base new))
    (loop []
      (let [doc-h (rs/root base)
            doc-h' (install-at base doc-h path new)]
        (if (or (= doc-h doc-h') (rs/cas-root! base doc-h doc-h'))
          new
          (recur)))))

  store/IStore
  (s-get [_ h] (store/s-get base h))
  (s-put [this h value]
    (store/s-put base h value)
    this)
  (s-has? [_ h] (store/s-has? base h))
  (s-delete [this h]
    (store/s-delete base h)
    this)
  (s-snapshot [_] (store/s-snapshot base))
  (s-merge [this m]
    (store/s-merge base m)
    this)
  (s-reset [this]
    (store/s-reset base)
    this)

  #?@(:bb []
      :clj
      [clojure.lang.IDeref
       (deref [this] (rs/-root this))

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
                      (rs/-cas-root! this expected new))
       (reset [this new]
              (rs/-set-root! this new)
              new)
       (swap [this f] (swap-loop this f []))
       (swap [this f arg] (swap-loop this f [arg]))
       (swap [this f arg1 arg2] (swap-loop this f [arg1 arg2]))
       (swap [this f arg1 arg2 args]
             (swap-loop this f (into [arg1 arg2] args)))

       clojure.lang.IAtom2
       (resetVals [this new]
                  (let [old (rs/-root this)]
                    (rs/-set-root! this new)
                    (vector old new)))
       (swapVals [this f] (swap-vals-loop this f []))
       (swapVals [this f arg] (swap-vals-loop this f [arg]))
       (swapVals [this f arg1 arg2] (swap-vals-loop this f [arg1 arg2]))
       (swapVals [this f arg1 arg2 args]
                 (swap-vals-loop this f (into [arg1 arg2] args)))]))

(defn lens-store?
  "True if `rs` is a lens (not the document store)."
  [rs]
  (instance? LensStore rs))

(defn lens
  "Return a rooted store whose root is the value at `path` in `rs`.

   `path` is a Dacite vector of keys, or a host seq (the same sugar
   `v/get-in` takes). Empty path returns `rs`. A lens of a lens
   concatenates paths. `rs` must implement IRoot."
  [rs path]
  (let [path (path-keys path)]
    (cond
      (empty? path) rs
      (lens-store? rs) (lens (:base rs) (into (vec (:path rs)) path))
      :else (maybe-watch-parent!
             (->LensStore rs path (atom {}) (atom nil))))))
