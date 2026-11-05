(ns dacite.store.chain
  "Chain on store promises instead of unwrapping them (M1.3). Portable (.cljc).

   `store/await` only unwraps a promise that has already settled: every
   in-memory store call is one, a JVM future blocks. A store that settles later
   (a JavaScript promise, or a `CompletableFuture`, that is not done yet) cannot
   be awaited from code that must not block. This namespace lets the peer code
   (`dacite.peer.client`, `.responder`, `.verify`) keep one shape for both.

   A **step** is the result of code that may touch a store: a plain value when
   every store call it made had already settled, or a `Deferred` when it has to
   wait. `then` runs a function on a step's value. With a settled store nothing
   is deferred, so the code runs in the caller's stack, in order, exactly as it
   did when it called `store/await`; the in-memory store never makes a `Deferred`.
   `native` turns a step into a host promise (`js/Promise`, `CompletableFuture`)
   for a caller that wants to wait.

   A store promise is one of: a settled map (`store/completed`, `store/rejected`),
   a JVM future (blocks, as `store/await` does), or a host promise that settles
   later (a `CompletionStage`, a JavaScript thenable). A host promise fulfils with
   the value, or with a settled map, which is unwrapped.

   A store call that fails, here, is an `ex-info` `:reason :store-failed` with the
   `:hash` and the `:cause` (`store-op`): the store broke, the peer did nothing
   wrong. `with-reads` is how synchronous code that walks the store (the
   verifier, the reveal walk) reads from a store that is not settled."
  (:require [dacite.store :as store])
  #?(:clj (:import (java.util.concurrent CompletionException CompletionStage CompletableFuture)
                   (java.util.function BiConsumer))))

;; =============================================================================
;; Deferred
;; =============================================================================

(defrecord Deferred [a])

(defn deferred
  "A step that is not done: complete it with `resolve!` or `reject!`."
  []
  (->Deferred (atom {:state :pending :cbs []})))

(defn- settle! [{:keys [a]} state value]
  (let [[old _] (swap-vals! a (fn [s] (if (= :pending (:state s)) {:state state :value value} s)))]
    (when (= :pending (:state old))
      (doseq [cb (:cbs old)] (cb state value)))))

(defn resolve! "Complete `d` with value `v` (once)." [d v] (settle! d :ok v) nil)

(defn reject! "Fail `d` with exception `e` (once)." [d e] (settle! d :err e) nil)

(defn- listen!
  "Call `(cb state value)` when `d` is settled (at once when it is)."
  [{:keys [a]} cb]
  (let [[old _] (swap-vals! a (fn [s] (if (= :pending (:state s)) (update s :cbs conj cb) s)))]
    (when-not (= :pending (:state old))
      (cb (:state old) (:value old)))))

(defn pending?
  "True for a step that is not done."
  [x]
  (and (instance? Deferred x) (= :pending (:state @(:a x)))))

;; =============================================================================
;; Store promises into steps
;; =============================================================================

(defn- thenable? [x]
  #?(:clj (instance? CompletionStage x)
     :cljs (and (some? x) (not (map? x)) (fn? (.-then x)))))

(defn- settled-map? [x]
  (and (map? x) (not (instance? Deferred x))
       (or (contains? x :dacite/error) (contains? x :dacite/result))))

(defn- unwrap-settled [v] (if (settled-map? v) (store/await v) v))

#?(:clj
   (defn- cause [e]
     (if (instance? CompletionException e) (or (.getCause ^Throwable e) e) e)))

(defn- adopt
  "A `Deferred` that follows the host promise `x`."
  [x]
  (let [d (deferred)
        done (fn [v e]
               (if (some? e)
                 (reject! d e)
                 (try (resolve! d (unwrap-settled v))
                      (catch #?(:clj Throwable :cljs :default) err (reject! d err)))))]
    #?(:clj (.whenComplete ^CompletionStage x
                           (reify BiConsumer
                             (accept [_ v e] (done v (when e (cause e))))))
       :cljs (.then x (fn [v] (done v nil)) (fn [e] (done nil (or e (js/Error. "rejected"))))))
    d))

(defn- norm
  "`x` as a plain value (a settled store promise is unwrapped, so a rejection
   throws here) or a pending `Deferred`."
  [x]
  (cond
    (instance? Deferred x)
    (let [{:keys [state value]} @(:a x)]
      (case state :pending x :ok value (throw value)))

    (thenable? x) (norm (adopt x))
    (or #?(:clj (future? x)) (settled-map? x)) (store/await x)
    :else x))

(defn pipe!
  "Complete the `Deferred` `d` with the step `r`, when it is done: `r`'s value, or
   what it throws or fails with."
  [d r]
  (let [n (try (norm r) (catch #?(:clj Throwable :cljs :default) e (reject! d e) ::failed))]
    (cond
      (= ::failed n) nil
      (pending? n) (listen! n (fn [state v] (if (= :ok state) (resolve! d v) (reject! d v))))
      :else (resolve! d n))))

(defn then
  "`(f v)` where `v` is the value of `x`: a store promise or a step. Returns a
   step: `f`'s value when `x` is already done (a rejection throws), else a
   `Deferred` of it. `f` may return a step."
  [x f]
  (let [n (norm x)]
    (if (pending? n)
      (let [d (deferred)]
        (listen! n (fn [state v]
                     (if (= :ok state)
                       (try (pipe! d (f v))
                            (catch #?(:clj Throwable :cljs :default) e (reject! d e)))
                       (reject! d v))))
        d)
      (f n))))

(defn guard
  "Run `(thunk)`, which returns a step or a store promise. A failure, thrown now
   or rejected later, goes to `(on-error e)` and its value is the result. Returns
   a step."
  [thunk on-error]
  (let [r (try [:ok (norm (thunk))]
               (catch #?(:clj Throwable :cljs :default) e [:err e]))]
    (if (= :err (first r))
      (on-error (second r))
      (let [n (second r)]
        (if (pending? n)
          (let [d (deferred)]
            (listen! n (fn [state v]
                         (if (= :ok state)
                           (resolve! d v)
                           (try (pipe! d (on-error v))
                                (catch #?(:clj Throwable :cljs :default) e (reject! d e))))))
            d)
          n)))))

(defn on-done
  "Call `(f)` once step `x` is done, whether it succeeded or failed. Returns nil."
  [x f]
  (let [n (try (norm x) (catch #?(:clj Throwable :cljs :default) _ nil))]
    (if (pending? n) (listen! n (fn [_ _] (f))) (f))
    nil))

(defn each
  "Call `f` on each of `xs` in order, the next only when the step of the last is
   done. Stops at the first failure. Returns a step of nil."
  [xs f]
  (loop [xs (seq xs)]
    (when xs
      (let [n (norm (f (first xs)))]
        (if (pending? n)
          (then n (fn [_] (each (next xs) f)))
          (recur (next xs)))))))

(defn result
  "The value of a step that is done. Throws its failure, or when it is pending."
  [x]
  (let [n (norm x)]
    (if (pending? n) (throw (ex-info "the step is still pending" {:reason :pending})) n)))

(defn native
  "A host promise of step `x`: a `js/Promise`, or on the JVM a `CompletableFuture`."
  [x]
  #?(:cljs (js/Promise.
            (fn [ok err]
              (let [n (norm x)]
                (if (pending? n)
                  (listen! n (fn [state v] (if (= :ok state) (ok v) (err v))))
                  (ok n)))))
     :clj (let [cf (CompletableFuture.)]
            (try (let [n (norm x)]
                   (if (pending? n)
                     (listen! n (fn [state v] (if (= :ok state) (.complete cf v) (.completeExceptionally cf v))))
                     (.complete cf n)))
                 (catch Throwable e (.completeExceptionally cf e)))
            cf)))

;; =============================================================================
;; Store failures
;; =============================================================================

(defn store-failed
  "The error for a store call on `h` that failed with `cause`."
  [h cause]
  (ex-info (str "store failed: " (ex-message cause))
           {:reason :store-failed :hash h :cause cause}))

(defn store-failed? [e] (= :store-failed (:reason (ex-data e))))

(defn store-op
  "Run `(thunk)`, a store call on hash `h`. Returns a step of its value. A
   failure, whenever it comes, is `store-failed`."
  [thunk h]
  (guard thunk (fn [e] (throw (store-failed h e)))))

;; =============================================================================
;; Reading from a store that is not settled
;; =============================================================================

(defn miss?
  "True when `e` is a read of a node that `with-reads` has yet to fetch. Code that
   catches exceptions while it reads must let this one through."
  [e]
  (= ::miss (:reason (ex-data e))))

(def ^:private unknown ::unknown)
(def ^:private absent ::absent)

(defrecord ReadView [cache]
  store/IStore
  (s-get [_ h]
    (let [v (get @cache (store/hash->hex h) unknown)]
      (cond
        (= unknown v) (throw (ex-info "node not fetched yet" {:reason ::miss :hash h}))
        (= absent v) (store/completed nil)
        :else (store/completed v))))
  (s-has? [this h] (store/completed (some? (store/await (store/s-get this h)))))
  (s-put [_ _ _] (throw (ex-info "a read view is read only" {})))
  (s-delete [_ _] (throw (ex-info "a read view is read only" {})))
  (s-snapshot [_] (throw (ex-info "a read view has no snapshot" {})))
  (s-merge [_ _] (throw (ex-info "a read view is read only" {})))
  (s-reset [_] (throw (ex-info "a read view is read only" {}))))

(defn- fetch!
  "Fetch node `h` from `st` into `cache`, and with `expand` (a function of an entry:
   the hashes under it) everything below it. A step of nil."
  [st cache expand h]
  (letfn [(go [queue]
            (loop [queue queue]
              (when-let [x (first queue)]
                (if (contains? @cache (store/hash->hex x))
                  (recur (rest queue))
                  (then (store-op #(store/s-get st x) x)
                        (fn [entry]
                          (swap! cache assoc (store/hash->hex x) (if (nil? entry) absent entry))
                          (go (if (and expand (some? entry))
                                (into (vec (rest queue)) (remove nil?) (expand entry))
                                (rest queue)))))))))]
    (go [h])))

(defn with-reads
  "Run `(f st')`, a synchronous function that only reads, against store `st`,
   which may settle late. Returns a step of `f`'s value.

   With a store that has settled, `f` runs on `st` itself. Otherwise `f` runs on
   a read view; when it reads a node the view does not hold, the node is fetched
   from `st` (a step), kept, and `f` runs again from the start. `f` must not write
   and must let `miss?` exceptions through. A failed fetch is `store-failed`.

   `:expand` (optional) is a function of an entry that returns the hashes under it.
   With it a miss fetches the node and everything below it before `f` runs again,
   for a walk that reads a whole subtree: one more pass, not one per node."
  ([st f] (with-reads st f nil))
  ([st f {:keys [expand]}]
   (try (f st)
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
          (if (= :not-settled (:reason (ex-data e)))
            (let [cache (atom {}) view (->ReadView cache)]
              (letfn [(attempt []
                        (let [r (try [:done (f view)]
                                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                                       (if (miss? e) [:miss (:hash (ex-data e))] (throw e))))]
                          (if (= :miss (first r))
                            (then (fetch! st cache expand (second r)) (fn [_] (attempt)))
                            (second r))))]
                (attempt)))
            (throw e))))))
