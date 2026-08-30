(ns dacite.store
  "Public store API for Dacite (pair with `dacite.value` for values).

   The IStore protocol defines content-addressed storage. Rooted stores
   add a single mutable root **hash** (Chapter 4). Application value code
   should wrap a rooted store with `dacite.value/root-ref` and then work
   with Dacite values rather than hashes.

   Portable:
   - mem-store, layered-store
   - rooted-store, root, cas-root!, set-root!, file-root-cell, mem-root-cell
   - sync-reachable! (copy a subgraph; pack flush to remotes)
   - *store*, with-store, hash→hex helpers

   Host backends:
   - file-store / lmdb-store / lmdb-root-cell (JVM re-exports)
   - remote-rooted-store (JVM HTTP + server root; use with value/root-ref)
   - nbb file: dacite.store.nbb/file-store (require directly)
   - browser remote: dacite.store.browser

   Advanced composition stays in sub-namespaces:
   dacite.store.lru, .pack, .client-cache, .remote, .browser, .rate-limit."
  (:require [dacite.hash :as hash]))

;; =============================================================================
;; Hash utilities (re-exported from dacite.hash)
;; =============================================================================

(def hash->hex hash/hash->hex)
(def hex->hash hash/hex->hash)

;; =============================================================================
;; Store protocol
;; =============================================================================

(def ^:dynamic *pack-near*
  "When bound to a hash, a remote s-get miss asks GET /node/{h}?near=…
   so the server packs under that enclosing value (siblings / parent
   literal) instead of a bare leaf. The store has no parent pointers;
   collection walks bind this to the value being sequenced."
  nil)

(defn keep-near
  "Lazy seq that rebinds *pack-near* as it is realized."
  [near xs]
  (lazy-seq
   (binding [*pack-near* near]
     (when-let [s (seq xs)]
       (cons (first s) (keep-near near (rest s)))))))

(defprotocol IStore
  "Protocol for content-addressed storage."
  (s-get [this h] "Retrieve value by hash. Returns nil if not found.")
  (s-put [this h value] "Store value at hash. Returns the store (or this for mutable stores).")
  (s-has? [this h] "Check if hash exists.")
  (s-delete [this h] "Remove entry at hash. Returns the store.")
  (s-snapshot [this] "Return the current contents as a plain map. Used for bulk reads during construction.")
  (s-merge [this m] "Merge a map of {hash value} pairs into the store.")
  (s-reset [this] "Clear all entries. Returns the store."))

;; =============================================================================
;; In-memory store
;; =============================================================================

(defn ^:private mkey
  "Internal map key for a hash. On JVM a hash is a vector of longs and is
   used directly. On ClojureScript a hash is a vector of BigInts, which
   cannot be a map key (BigInt is a primitive and can't carry the hash uid),
   so we key by the canonical hex string instead. Cross-host storage is
   never compared directly (only root hashes are), so this is internal."
  [h]
  #?(:clj h :cljs (hash/hash->hex h)))

(defrecord MemStore [data]
  IStore
  (s-get [_ h] (get @data (mkey h)))
  (s-put [this h value] (swap! data assoc (mkey h) value) this)
  (s-has? [_ h] (contains? @data (mkey h)))
  (s-delete [this h] (swap! data dissoc (mkey h)) this)
  (s-snapshot [_] @data)
  (s-merge [this m] (swap! data merge m) this)
  (s-reset [this] (reset! data {}) this))

(defn mem-store
  "Create an in-memory content-addressed store."
  ([] (->MemStore (atom {})))
  ([init] (->MemStore (atom init))))

;; =============================================================================
;; Layered store
;; =============================================================================

(defrecord LayeredStore [layers]
  ;; layers is a vector of stores, first = fastest (e.g. mem), last = most durable
  IStore
  (s-get [_ h]
    (loop [seen []
           [layer & more] layers]
      (when layer
        (if-let [v (s-get layer h)]
          (do (doseq [faster seen] (s-put faster h v))
              v)
          (recur (conj seen layer) more)))))

  (s-put [this h value]
    (doseq [layer layers]
      (s-put layer h value))
    this)

  (s-has? [_ h]
    (some #(s-has? % h) layers))

  (s-delete [this h]
    (doseq [layer layers]
      (s-delete layer h))
    this)

  (s-snapshot [_]
    (reduce (fn [acc layer]
              (merge acc (s-snapshot layer)))
            {}
            (reverse layers)))

  (s-merge [this m]
    (doseq [layer layers]
      (s-merge layer m))
    this)

  (s-reset [this]
    (doseq [layer layers]
      (s-reset layer))
    this))

(defn layered-store
  "Create a layered store. Reads fall through from first to last.
   Writes go to all layers.

   Example: (layered-store (mem-store) (file-store \"/tmp/dacite\"))"
  [& layers]
  (->LayeredStore (vec layers)))

;; =============================================================================
;; Current store management
;; =============================================================================

(def ^:dynamic *store*
  "Dynamic var holding the current IStore. Initialized with an in-memory
   store so constructors work without an explicit with-store."
  (mem-store))

(defn reset-store!
  "Reset the current store to empty."
  []
  (s-reset *store*))

(defn set-store!
  "Replace the current store with a new IStore implementation."
  [new-store]
  #?(:clj (alter-var-root #'*store* (constantly new-store))
     :cljs (set! *store* new-store)))

(defn get-store
  "Get a value by hash from the current store. Returns nil if absent."
  [h]
  (s-get *store* h))

(defn put-store!
  "Store a value at hash in the current store."
  [h value]
  (s-put *store* h value))

(defn snapshot-store
  "Return a plain {hash value} map snapshot of the current store."
  []
  (s-snapshot *store*))

(defn merge-store!
  "Merge a map of {hash value} pairs into the current store."
  [m]
  (s-merge *store* m))

(defmacro bind-store
  "Bind *store* to the given store for the duration of body."
  [store & body]
  `(binding [*store* ~store]
     ~@body))

(defmacro with-store
  "Execute body with an isolated store. init can be an IStore or a map
   (which will be wrapped in a mem-store). Returns [snapshot last-value]."
  [[sym init] & body]
  `(let [store# (let [i# ~init]
                  (if (satisfies? IStore i#)
                    i#
                    (mem-store i#)))
         ~sym store#]
     (bind-store store#
                 (let [result# (do ~@body)]
                   [(s-snapshot store#) result#]))))

;; =============================================================================
;; Rooted store (hash-level) — deferred re-export from dacite.rooted
;; =============================================================================
;; dacite.rooted requires dacite.store, so we cannot :require it in the ns form.
;; Resolve vars on first use (JVM/CLJ). nbb requires at call time (SCI). The
;; JVM ClojureScript compiler forbids non-top-level `require`, so the browser
;; branch stubs these; use dacite.store.browser instead.

#?(:clj
   (do
     (defn- rooted-var [sym]
       (requiring-resolve (symbol "dacite.rooted" (name sym))))

     (defn mem-root-cell
       "Ephemeral root cell (atom only)."
       [& args]
       (apply (rooted-var 'mem-root-cell) args))

     (defn file-root-cell
       "Durable root cell: hex hash in `{base}/ROOT`."
       [& args]
       (apply (rooted-var 'file-root-cell) args))

     (defn rooted-store
       "Wrap a content store with a mutable root hash.
        One-arg form uses an ephemeral mem-root-cell; two-arg accepts a root cell."
       [& args]
       (apply (rooted-var 'rooted-store) args))

     (defn root
       "Current root hash, or nil if unset."
       [rs]
       ((rooted-var 'root) rs))

     (defn cas-root!
       "Compare-and-set the root hash. Returns true on success."
       [rs expected new]
       ((rooted-var 'cas-root!) rs expected new))

     (defn set-root!
       "Unconditionally set the root hash (local convenience)."
       [rs new]
       ((rooted-var 'set-root!) rs new))

     (defn update-root!
       "Apply f to the current root hash, CAS-retrying until success."
       [rs f & args]
       (apply (rooted-var 'update-root!) rs f args))

     (defn add-root-watch
       "Register hash-level watch (fn [k rs old-hash new-hash])."
       [rs k f]
       ((rooted-var 'add-root-watch) rs k f))

     (defn remove-root-watch
       [rs k]
       ((rooted-var 'remove-root-watch) rs k))

     (defn set-root-validator!
       [rs f]
       ((rooted-var 'set-root-validator!) rs f))

     (defn push-ref
       [source target]
       ((rooted-var 'push-ref) source target))

     (defn collect-garbage!
       "Remove content not reachable from the current root."
       ([rs] ((rooted-var 'collect-garbage!) rs))
       ([rs root-hash] ((rooted-var 'collect-garbage!) rs root-hash)))

     (defn rc-get
       "Persisted root hash from an IRootCell, or nil."
       [cell]
       ((rooted-var 'rc-get) cell))

     (defn rc-put!
       "Persist root hash into an IRootCell."
       [cell h]
       ((rooted-var 'rc-put!) cell h)))

   :org.babashka/nbb
   (do
     (defn- rooted-var [sym]
       (require 'dacite.rooted)
       (resolve (symbol "dacite.rooted" (name sym))))

     (defn mem-root-cell [& args]
       (apply (rooted-var 'mem-root-cell) args))

     (defn file-root-cell [& args]
       (apply (rooted-var 'file-root-cell) args))

     (defn rooted-store [& args]
       (apply (rooted-var 'rooted-store) args))

     (defn root [rs]
       ((rooted-var 'root) rs))

     (defn cas-root! [rs expected new]
       ((rooted-var 'cas-root!) rs expected new))

     (defn set-root! [rs new]
       ((rooted-var 'set-root!) rs new))

     (defn update-root! [rs f & args]
       (apply (rooted-var 'update-root!) rs f args))

     (defn add-root-watch [rs k f]
       ((rooted-var 'add-root-watch) rs k f))

     (defn remove-root-watch [rs k]
       ((rooted-var 'remove-root-watch) rs k))

     (defn set-root-validator! [rs f]
       ((rooted-var 'set-root-validator!) rs f))

     (defn push-ref [source target]
       ((rooted-var 'push-ref) source target))

     (defn collect-garbage!
       ([rs] ((rooted-var 'collect-garbage!) rs))
       ([rs root-hash] ((rooted-var 'collect-garbage!) rs root-hash)))

     (defn rc-get [cell]
       ((rooted-var 'rc-get) cell))

     (defn rc-put! [cell h]
       ((rooted-var 'rc-put!) cell h))

     (defn sync-reachable!
       "Copy nodes reachable from root-h in src into dest."
       [src dest root-h]
       (require 'dacite.store.sync)
       ((resolve 'dacite.store.sync/sync-reachable!) src dest root-h)))

   :cljs
   (do
     (defn- rooted-browser-stub [fname]
       (throw (js/Error. (str "dacite.store/" fname
                              " is JVM/nbb; the browser uses dacite.store.browser"))))

     (defn mem-root-cell [& _] (rooted-browser-stub "mem-root-cell"))
     (defn file-root-cell [& _] (rooted-browser-stub "file-root-cell"))
     (defn rooted-store [& _] (rooted-browser-stub "rooted-store"))
     (defn root [_] (rooted-browser-stub "root"))
     (defn cas-root! [_ _ _] (rooted-browser-stub "cas-root!"))
     (defn set-root! [_ _] (rooted-browser-stub "set-root!"))
     (defn update-root! [_ _ & _] (rooted-browser-stub "update-root!"))
     (defn add-root-watch [_ _ _] (rooted-browser-stub "add-root-watch"))
     (defn remove-root-watch [_ _] (rooted-browser-stub "remove-root-watch"))
     (defn set-root-validator! [_ _] (rooted-browser-stub "set-root-validator!"))
     (defn push-ref [_ _] (rooted-browser-stub "push-ref"))
     (defn collect-garbage!
       ([_] (rooted-browser-stub "collect-garbage!"))
       ([_ _] (rooted-browser-stub "collect-garbage!")))
     (defn rc-get [_] (rooted-browser-stub "rc-get"))
     (defn rc-put! [_ _] (rooted-browser-stub "rc-put!"))
     (defn sync-reachable! [_ _ _] (rooted-browser-stub "sync-reachable!"))))

;; =============================================================================
;; Host backends (when available on this host)
;; =============================================================================
;; File/LMDB implementations live in sub-namespaces that :require this ns, so
;; we re-export via requiring-resolve on the JVM. On nbb, require
;; dacite.store.nbb directly (SCI has no requiring-resolve and circular ns
;; load is awkward). Browser remotes use dacite.store.browser.

#?(:clj
   (do
     (defn file-store
       "Filesystem content store (JVM + babashka; sharded EDN).
        On nbb use dacite.store.nbb/file-store."
       [& args]
       (apply (requiring-resolve 'dacite.store.file/file-store) args))

     (defn- jvm-var [sym]
       (requiring-resolve (symbol "dacite.store.jvm" (name sym))))

     (defn lmdb-store
       "LMDB content store (JVM). Values are wire-v1 node payloads.
        Not available on babashka (native LMDB)."
       [& args]
       (apply (jvm-var 'lmdb-store) args))

     (defn lmdb-root-cell
       "Durable root cell in an LMDB meta database."
       [& args]
       (apply (jvm-var 'lmdb-root-cell) args))

     (defn lmdb-close
       "Close an LMDB environment."
       [st]
       ((jvm-var 'lmdb-close) st))

     (defn remote-rooted-store
       "HTTP content + server root as a rooted store. Use with `dacite.value/root-ref`.

        Same value API as a local rooted store. `set-root!` / `ref-reset!` throw
        (local-only). Default client-cache policy is `:write-back`.

        (store/remote-rooted-store \"http://127.0.0.1:8080\")
        (store/remote-rooted-store url {:policy :none})"
       [& args]
       (apply (requiring-resolve 'dacite.store.remote/remote-rooted-store) args))

     (defn sync-reachable!
       "Copy nodes reachable from root-h in src into dest.
        Packed flush when dest is a remote; otherwise per-node copy."
       [src dest root-h]
       ((requiring-resolve 'dacite.store.sync/sync-reachable!) src dest root-h))))
