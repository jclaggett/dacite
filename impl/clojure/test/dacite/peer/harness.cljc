(ns dacite.peer.harness
  "Test support for M1.3: the peer checks (client_checks.cljc, flow_checks.cljc)
   run against a store that settles late.

   `LateStore` wraps a store, and every call on it returns a pending
   `chain/Deferred`: nothing is read or written until `settle-one!` or `settle!`
   performs the oldest call. So the peer code really takes its asynchronous path
   (a `Deferred`, the queue, `with-reads`), and a check still runs in one thread,
   with no clock and no waiting: the wrappers below call the peer function and then
   `settle!` until nothing is pending.

   `*late*`, when bound to an atom (`(atom [])`), makes `client` and `responder`
   wrap their store in a `LateStore`. Left nil, they are the plain functions, so a
   check is the same code either way. A check reads the store through `inner`."
  (:require [dacite.peer.client :as c]
            [dacite.peer.responder :as r]
            [dacite.store :as store]
            [dacite.store.chain :as chain]))

(def ^:dynamic *late*
  "nil, or an atom (a vector) holding the calls a `LateStore` has not settled."
  nil)

(defn settle-one!
  "Perform the oldest pending store call and settle its promise. False when none."
  [q]
  (if-let [[f d] (first @q)]
    (do (swap! q subvec 1)
        (try (chain/resolve! d (f))
             (catch #?(:clj Throwable :cljs :default) e (chain/reject! d e)))
        true)
    false))

(defn settle!
  "Settle pending store calls, and the calls their continuations make, until none."
  [q]
  (loop [n 0]
    (when (> n 100000) (throw (ex-info "the store calls do not settle" {})))
    (when (settle-one! q) (recur (inc n)))))

(defn- later
  "A pending promise for `(f)`, performed when it is settled. The call is logged
   at the time it is made and again, with `:settle`, when it is performed."
  [{:keys [q log]} op h f]
  (let [d (chain/deferred)
        hex (when h (store/hash->hex h))]
    (swap! log conj [:call op hex])
    (swap! q conj [(fn [] (swap! log conj [:settle op hex]) (f)) d])
    d))

(defrecord LateStore [inner q log fail-put fail-delete]
  store/IStore
  (s-get [this h] (later this :get h #(store/await (store/s-get inner h))))
  (s-put [this h v]
    (later this :put h
           (fn []
             (when-let [e (when-let [p @fail-put] (p h))]
               (throw (if (instance? #?(:clj Throwable :cljs js/Error) e) e (ex-info "the store refuses" {:reason :quota}))))
             (store/await (store/s-put inner h v))
             this)))
  (s-has? [this h] (later this :has h #(store/await (store/s-has? inner h))))
  (s-delete [this h]
    (later this :delete h
           (fn []
             (when (when-let [p @fail-delete] (p h))
               (throw (ex-info "the store refuses" {:reason :unavailable})))
             (store/await (store/s-delete inner h))
             this)))
  (s-merge [this m] (later this :merge nil #(do (store/await (store/s-merge inner m)) this)))
  (s-reset [this] (later this :reset nil #(do (store/await (store/s-reset inner)) this))))

(defn late-store
  "A `LateStore` over `inner` (default a new mem-store), its calls pending in `q`.
   `(:log s)` is an atom of [:call|:settle op hex-hash]. Set `(:fail-put s)` (an
   atom) to a function of a hash that is truthy for a put that is refused;
   `(:fail-delete s)` is the same for deletes."
  ([q] (late-store (store/mem-store) q))
  ([inner q] (->LateStore inner q (atom []) (atom nil) (atom nil))))

(defn inner
  "The store under a `LateStore`, for a check to read; any other store as it is."
  [st]
  (if (instance? LateStore st) (:inner st) st))

(defn- wrap [opts]
  (if *late*
    (update opts :store #(late-store (or % (store/mem-store)) *late*))
    opts))

(defn- settled
  "The value of step `x` once pending store calls are settled (when late)."
  [x]
  (when *late* (settle! *late*))
  (chain/result x))

(defn client [opts] (c/client (wrap opts)))

(defn request! [cl h] (settled (c/request! cl h)))

(defn on-frame! [cl peer bs] (settled (c/on-frame! cl peer bs)))

(defn have? [cl h] (settled (c/have? cl h)))

(defn close-session! [cl peer] (settled (c/close-session! cl peer)))

(defn responder [opts] (r/responder (wrap opts)))

(defn responder-on-frame! [rs peer bs] (settled (r/on-frame! rs peer bs)))
