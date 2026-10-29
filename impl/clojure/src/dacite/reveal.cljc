(ns dacite.reveal
  "Default reveal order, interest shares, and the session send clock.

   The asked node comes first. A collection then sends its own pages, so
   every child hash is known, and then the subvalue bodies its shape
   would ask for next. A string, blob, or vector continues first-most.
   A map sends every key body, then every value body, in the HAMT's
   key-hash order. A set sends each element body once, in that order.
   A scalar is one node. An inlined finger-tree leaf stays on its page
   and is not requested again. A hash already listed is not listed again.

   Interest is `peak * exp(-age / tau)`. Shares of `r-max` are those
   interests renormalized. Below the floor a reveal pauses and drops
   out of the sum. Cancel drops it at once.

   `open-reveal`, `cancel-reveal`, and `take-pages` spend those shares.
   Credit accrues at the reveal's share, in bytes per unit of `tau`.
   A page goes out whole when that credit covers its wire-item size.
   A hash this session has already sent is skipped. Asking for a hash
   that is still open resumes the unsent pages and restarts its clock
   at the peak. Credit already earned toward the next page stays.

   No path, header, or frame. `peak`, `tau`, the floor, and `r-max` are
   arguments. This namespace does not choose production values, and it
   does not open a stream."
  (:require [clojure.string :as str]
            [dacite.store :as store]
            [dacite.value.hamt :as hamt]
            [dacite.value.types :as types]
            [dacite.wire.binary :as bin]))

(def ^:private seq-types #{"string" "blob" "vector"})
(def ^:private assoc-types #{"map" "set"})

(defn- exp
  [x]
  #?(:clj (Math/exp (double x))
     :cljs (js/Math.exp x)))

(defn- structural-type?
  [t]
  (and (string? t)
       (or (str/starts-with? t "ft/")
           (str/starts-with? t "hamt/"))))

(defn- child-hash-vec
  [node]
  (into [] (remove nil?) (or (types/child-hashes node) [])))

(defn- structural-children
  "Child hashes that are finger-tree or HAMT pages, in stored order."
  [store node]
  (filterv (fn [ch]
             (structural-type?
              (types/entry-type (store/require-node store ch))))
           (child-hash-vec node)))

(defn- ref-children
  "Child hashes that are values, not structural pages."
  [store node]
  (into []
        (remove (fn [ch]
                  (structural-type?
                   (types/entry-type (store/require-node store ch)))))
        (child-hash-vec node)))

(defn- all-structural-pages
  "Own pages under a tree root, this page first, then children in stored
   order. A leaf value hash contributes nothing."
  [store h]
  (let [n (store/require-node store h)
        t (types/entry-type n)]
    (if-not (structural-type? t)
      []
      (into [h] (mapcat #(all-structural-pages store %)
                        (structural-children store n))))))

(defn- seq-element-refs
  "Value hashes a sequence page points at, left to right.

   Inlined leaves are not refs, so they are absent. A bare leaf root is
   the one element."
  [store root]
  (let [n (store/require-node store root)]
    (if (structural-type? (types/entry-type n))
      (mapcat (fn [page]
                (ref-children store (store/require-node store page)))
              (all-structural-pages store root))
      [root])))

(defn- assoc-refs
  "Key hashes, then value hashes, in HAMT key-hash order.

   A set stores the element as both, so each element is named once."
  [store root kind]
  (let [entries (hamt/hamt-entries store root)]
    (if (= kind "set")
      (map first entries)
      (concat (map first entries) (map second entries)))))

(defn page-order
  "Hashes a default reveal of `h` would send, in order.

   The client can focus a child as soon as an earlier page has named
   that child's hash. Bodies follow the shape's guess: first-most for
   sequences, keys then values for a map, element bodies for a set."
  [store h]
  (let [seen (volatile! #{})]
    (letfn [(take! [hash]
              (when-not (contains? @seen hash)
                (vswap! seen conj hash)
                hash))
            (fresh-pages [hashes]
              (into [] (keep take!) hashes))
            (walk [hash]
              (if-let [emitted (take! hash)]
                (let [n (store/require-node store hash)
                      t (types/entry-type n)]
                  (cond
                    (contains? seq-types t)
                    (let [root (:root (types/entry-data n))]
                      (into [emitted]
                            (concat (fresh-pages (all-structural-pages store root))
                                    (mapcat walk (seq-element-refs store root)))))

                    (contains? assoc-types t)
                    (let [root (:root (types/entry-data n))]
                      (into [emitted]
                            (concat (fresh-pages (all-structural-pages store root))
                                    (mapcat walk (assoc-refs store root t)))))

                    :else [emitted]))
                []))]
      (vec (walk h)))))

(defn omit-sent
  "Hashes from `pages` that this session has not already sent, in order."
  [pages sent]
  (into [] (remove #(contains? sent %)) pages))

(defn interest
  "Interest at `age`: `peak * exp(-age / tau)`. `tau` must be positive.
   `age` and `tau` share a time unit."
  [peak tau age]
  (when-not (pos? tau)
    (throw (ex-info "tau must be positive" {:tau tau})))
  (* (double peak) (exp (- (/ (double age) (double tau))))))

(defn shares
  "Byte-per-second share of `:r-max` for each reveal still in the sum.

   `reveals` is a sequence of maps with `:id` and `:opened-at`, and
   optional `:cancelled?` and `:done?`. Time `t` uses the same unit as
   `:tau`. Interest below `:floor` pauses that reveal. Cancel and done
   drop it immediately. The values sum to `:r-max` when any reveal
   remains in the sum."
  [reveals t {:keys [peak tau floor r-max]}]
  (let [weighted (keep (fn [r]
                         (when-not (or (:cancelled? r) (:done? r))
                           (let [age (- t (:opened-at r))
                                 i (interest peak tau age)]
                             (when (>= i floor)
                               [(:id r) i]))))
                       reveals)
        sum (reduce + 0.0 (map second weighted))]
    (if (pos? sum)
      (into {} (map (fn [[id i]] [id (* (double r-max) (/ i sum))]) weighted))
      {})))

(defn next-reveal
  "Id of the reveal that receives the next page, or nil when none remain.

   The largest share wins. Equal shares go to the request with the
   later `:opened-at`."
  [reveals t params]
  (let [rate (shares reveals t params)]
    (when (seq rate)
      (let [by-id (into {} (map (juxt :id identity)) reveals)]
        (:id (reduce (fn [best id]
                       (let [r (get rate id)
                             b (get rate (:id best))
                             newer (by-id id)]
                         (cond
                           (> r b) newer
                           (< r b) best
                           (> (:opened-at newer) (:opened-at best)) newer
                           :else best)))
                     (by-id (first (keys rate)))
                     (rest (keys rate))))))))

(defn- log
  [x]
  #?(:clj (Math/log (double x))
     :cljs (js/Math.log x)))

(defn- just-after
  "The next instant after `t`. Interest equal to the floor is still in
   the sum, on an instant of no width. Callers that land on that instant
   step past it so the reveal pauses."
  [t]
  #?(:clj (Math/nextUp (double t))
     :cljs
     (let [t (double t)
           bump (if (zero? t)
                  5.0e-324
                  (* (js/Math.abs t) 1.1102230246251565e-16))]
       (+ t bump))))

(defn- finite-time
  [t]
  (let [t (double t)]
    (when-not #?(:clj (Double/isFinite t)
                 :cljs (js/isFinite t))
      (throw (ex-info "time must be finite" {:t t})))
    t))

(defn- check-params
  [{:keys [peak tau floor r-max]}]
  (when-not (and (number? peak) (pos? peak))
    (throw (ex-info "peak must be positive" {:peak peak})))
  (when-not (and (number? tau) (pos? tau))
    (throw (ex-info "tau must be positive" {:tau tau})))
  (when-not (number? floor)
    (throw (ex-info "floor must be a number" {:floor floor})))
  (when-not (and (number? r-max) (>= r-max 0.0))
    (throw (ex-info "r-max must be non-negative" {:r-max r-max}))))

(defn page-bytes
  "Wire-v1 item bytes of the node at `h`.

   That item is the page a reveal sends, whole. The pack chunk envelope
   is a property of today's `GET /node` response, not of the page."
  [store h]
  (let [node (store/require-node store h)
        n (bin/byte-len (bin/encode-item {:enc :node :hash h :entry node}))]
    (when-not (pos? n)
      (throw (ex-info "page has no bytes" {:hash h})))
    n))

(defn session
  "An empty reveal session. The clock starts on the first call that
   names a time. Nothing has been sent."
  []
  {:t nil :sent #{} :seq 0 :reveals []})

(defn- age-of
  [reveal t]
  (- (double t) (double (:opened-at reveal))))

(defn- in-sum?
  [reveal t {:keys [peak tau floor]}]
  (and (not (:cancelled? reveal))
       (not (:done? reveal))
       (seq (:pages reveal))
       (>= (interest peak tau (age-of reveal t)) floor)))

(defn- floor-time
  "Time at which interest equals the floor. Nil when a positive floor
   is never reached."
  [reveal {:keys [peak tau floor]}]
  (cond
    (<= floor 0.0) nil
    (>= floor peak) (double (:opened-at reveal))
    :else (+ (double (:opened-at reveal))
             (* (double tau)
                (log (/ (double peak) (double floor)))))))

(defn- seconds-until-out
  [reveal t params]
  (if-let [limit (floor-time reveal params)]
    (max 0.0 (- limit (double t)))
    ##Inf))

(defn- page-wait
  [credit bytes rate]
  (let [need (- (double bytes) (double credit))]
    (cond
      (<= need 0.0) 0.0
      (pos? rate) (/ need (double rate))
      :else ##Inf)))

(defn- earlier
  [a b]
  (if (< a b) a b))

(defn- prune-sent
  "Drop pages this session has already sent. A reveal with nothing left
   is finished."
  [session]
  (let [sent (:sent session)]
    (update session :reveals
            (fn [rs]
              (mapv (fn [r]
                      (let [pages (into [] (remove #(contains? sent (:hash %)))
                                        (:pages r))]
                        (assoc r :pages pages
                               :done? (or (:done? r) (empty? pages)))))
                    rs)))))

(defn- accrue
  [session rates dt]
  (let [dt (double dt)]
    (-> session
        (update :t + dt)
        (update :reveals
                (fn [rs]
                  (mapv (fn [r]
                          (if-let [rate (get rates (:id r))]
                            (update r :credit + (* dt (double rate)))
                            r))
                        rs))))))

(defn- emit-page
  [session reveal t]
  (let [page (first (:pages reveal))
        credit (max 0.0 (- (double (:credit reveal))
                           (double (:bytes page))))
        pages (vec (rest (:pages reveal)))
        reveal' (assoc reveal :credit credit :pages pages
                       :done? (empty? pages))]
    {:session (-> session
                  (update :sent conj (:hash page))
                  (update :reveals
                          (fn [rs]
                            (mapv (fn [r]
                                    (if (= (:id reveal) (:id r)) reveal' r))
                                  rs))))
     :page {:id (:id reveal)
            :hash (:hash page)
            :bytes (:bytes page)
            :at (double t)}}))

(defn- advance
  "Move the session clock forward to `t`, emitting every page whose
   credit covers it at or before that time."
  [session t params]
  (let [t (finite-time t)]
    (cond
      (nil? (:t session)) [(assoc session :t t) []]
      (< t (:t session))
      (throw (ex-info "session clock moves forward"
                      {:t t :at (:t session)}))
      :else
      (loop [session session
             out []
             steps 0]
        (when (>= steps 1000000)
          (throw (ex-info "reveal session exceeded page steps" {})))
        (let [session (prune-sent session)
              t-now (double (:t session))
              earning (filterv #(in-sum? % t-now params) (:reveals session))
              rates (shares earning t-now params)
              ready (filterv (fn [r]
                               (>= (+ (:credit r) 1.0e-6)
                                   (double (:bytes (first (:pages r))))))
                             earning)]
          (cond
            (and (seq ready) (<= t-now t))
            (let [id (next-reveal ready t-now params)
                  chosen (first (filter #(= id (:id %)) earning))
                  emitted (emit-page session chosen t-now)]
              (recur (:session emitted)
                     (conj out (:page emitted))
                     (inc steps)))

            (>= t-now t)
            [session out]

            (empty? earning)
            [(assoc session :t t) out]

            :else
            (let [dt-page (reduce earlier ##Inf
                                  (map (fn [r]
                                         (page-wait (:credit r)
                                                    (:bytes (first (:pages r)))
                                                    (get rates (:id r) 0.0)))
                                       earning))
                  dt-out (reduce earlier ##Inf
                                 (map #(seconds-until-out % t-now params)
                                      earning))
                  dt (earlier (earlier dt-page dt-out) (- t t-now))]
              (if (pos? dt)
                (recur (accrue session rates dt) out (inc steps))
                (let [nudged (min t (just-after t-now))]
                  (if (= nudged t-now)
                    [(assoc session :t t) out]
                    (recur (assoc session :t nudged) out (inc steps))))))))))))

(defn- with-pages
  [session pages]
  {:session session :pages pages})

(defn- restart-clock
  [session id t]
  (update session :reveals
          (fn [rs]
            (mapv (fn [r]
                    (if (= id (:id r))
                      (assoc r :opened-at (double t))
                      r))
                  rs))))

(defn- live-reveal
  "The open reveal of `h`, including one that has paused or finished.
   A cancelled reveal stays cancelled and is not reused."
  [session h]
  (first (filter #(and (= h (:hash %)) (not (:cancelled? %)))
                 (:reveals session))))

(defn open-reveal
  "Open a reveal of `h` at time `t`, or resume one already open.

   Pages due by `t` from reveals already open are returned first. The
   new reveal starts with no new credit at that instant. Resuming
   restarts the interest clock at the peak and keeps credit already
   earned toward the next page. A hash already sent in this session is
   not queued again.

   Returns `{:session :pages :id}`."
  [session store h t params]
  (check-params params)
  (let [t (finite-time t)
        [session pages] (advance session t params)
        existing (live-reveal session h)]
    (if existing
      {:session (restart-clock session (:id existing) t)
       :pages pages
       :id (:id existing)}
      (let [id (inc (:seq session))
            queued (mapv (fn [ph]
                           {:hash ph :bytes (page-bytes store ph)})
                         (omit-sent (page-order store h) (:sent session)))
            reveal {:id id
                    :hash h
                    :opened-at t
                    :pages queued
                    :credit 0.0
                    :cancelled? false
                    :done? (empty? queued)}]
        {:session (-> session
                      (assoc :seq id :t t)
                      (update :reveals conj reveal))
         :pages pages
         :id id}))))

(defn cancel-reveal
  "Cancel the reveal `id` at time `t`.

   Pages already due by `t` are returned. Later pages of this reveal
   stay unsent. Hashes already sent stay sent.

   Returns `{:session :pages}`."
  [session id t params]
  (check-params params)
  (when-not (some #(= id (:id %)) (:reveals session))
    (throw (ex-info "no such reveal" {:id id})))
  (let [t (finite-time t)
        [session pages] (advance session t params)]
    {:session (update session :reveals
                      (fn [rs]
                        (mapv (fn [r]
                                (if (= id (:id r))
                                  (assoc r :cancelled? true)
                                  r))
                              rs)))
     :pages pages}))

(defn take-pages
  "Pages the session would send from its clock up to time `t`.

   Each page is `{:id :hash :bytes :at}`. `:at` is the time the credit
   covered that page. The clock then stands at `t`.

   Returns `{:session :pages}`."
  [session t params]
  (check-params params)
  (let [[session pages] (advance session (finite-time t) params)]
    (with-pages session pages)))
